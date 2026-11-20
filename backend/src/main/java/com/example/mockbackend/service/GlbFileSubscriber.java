package com.example.mockbackend.service;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Writes a GLB download to a file (InferenceClient, upload mode), like {@code BodySubscribers.ofFile}, with two
 * additions:
 * <ul>
 *   <li>{@code lastData} gets System.nanoTime() whenever bytes arrive, so a body that stops arriving can be cut off
 *       (failure drill FD16b: a Pod stopped mid-download left the GLB body waiting for the whole inference.timeout-ms).</li>
 *   <li>A failure to <em>write</em> the file (this server's disk full, failure drill FD4) is kept apart from a failure to
 *       <em>receive</em> it: {@link #writeError()}.</li>
 * </ul>
 * {@link #abort()} closes the file when the caller gives up (cancelling the exchange does not always tell the
 * subscriber, and an open file cannot be deleted on Windows).
 */
final class GlbFileSubscriber implements HttpResponse.BodySubscriber<Path> {
    private final Path target;
    private final AtomicLong lastData;
    private final CompletableFuture<Path> result = new CompletableFuture<>();
    private Flow.Subscription subscription;
    private FileChannel channel;
    private volatile IOException writeError;
    private volatile boolean aborted;

    GlbFileSubscriber(Path target, AtomicLong lastData) {
        this.target = target;
        this.lastData = lastData;
    }

    /** The I/O error that writing (opening, writing or closing) the file ended with; null when none. */
    IOException writeError() {
        return writeError;
    }

    @Override
    public synchronized void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        if (aborted) {   // the caller gave up before the headers came: create no file it would have to delete
            subscription.cancel();
            return;
        }
        try {
            channel = FileChannel.open(target, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            failWrite(e);
            return;
        }
        subscription.request(1);
    }

    @Override
    public synchronized void onNext(List<ByteBuffer> items) {
        if (result.isDone() || channel == null) {
            return;
        }
        lastData.set(System.nanoTime());
        try {
            for (ByteBuffer item : items) {
                while (item.hasRemaining()) {
                    channel.write(item);
                }
            }
        } catch (IOException e) {
            failWrite(e);
            return;
        }
        subscription.request(1);
    }

    @Override
    public synchronized void onError(Throwable error) {
        closeQuietly();
        result.completeExceptionally(error);
    }

    @Override
    public synchronized void onComplete() {
        if (result.isDone() || channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException e) {
            failWrite(e);
            return;
        }
        result.complete(target);
    }

    @Override
    public CompletionStage<Path> getBody() {
        return result;
    }

    /** Stops writing and closes the file; the caller deletes it. Safe to call more than once. */
    synchronized void abort() {
        aborted = true;
        closeQuietly();
        if (subscription != null) {
            subscription.cancel();
        }
        result.cancel(false);
    }

    /** Caller holds the lock. A write failure after abort() is the abort, not the disk. */
    private void failWrite(IOException e) {
        if (!aborted) {
            writeError = e;
        }
        closeQuietly();
        if (subscription != null) {
            subscription.cancel();
        }
        result.completeExceptionally(e);
    }

    /** Caller holds the lock. */
    private void closeQuietly() {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // the caller deletes the file either way
            }
        }
    }
}

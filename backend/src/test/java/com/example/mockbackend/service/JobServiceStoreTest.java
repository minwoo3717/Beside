package com.example.mockbackend.service;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.StoredUpload;
import com.example.mockbackend.repository.IdempotencyKeyEntry;
import com.example.mockbackend.repository.IdempotencyKeyRepository;
import com.example.mockbackend.repository.JobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How uploads reach the disk: every stream is closed, names a file system refuses still store, a failed request
 * leaves no photos behind, and the job that loses a same-key race is dropped before any worker sees it.
 */
@SpringBootTest(properties = "mock.worker.pending-ms=60000")
class JobServiceStoreTest {

    @TempDir
    static Path storage;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("storage.upload.dir", () -> storage.resolve("uploads").toString());
        registry.add("storage.result.dir", () -> storage.resolve("results").toString());
    }

    @Autowired
    JobServiceImpl jobService;

    @Autowired
    JobRepository jobRepository;

    @Autowired
    IdempotencyKeyRepository idempotencyKeys;

    @Test
    void everyUploadStreamIsClosed() {
        List<TrackedStream> streams = new ArrayList<>();
        Job job = jobService.submitJobV1(List.of(tracked("a.png", streams), tracked("b.png", streams)), null);
        assertThat(job.getUploads()).hasSize(2);
        assertThat(streams).isNotEmpty().allMatch(stream -> stream.closed);
    }

    @Test
    void namesAFileSystemRefusesAreStoredUnderASafeNameAndKeptAsSent() throws IOException {
        String korean = "강아지".repeat(25) + ".jpg";  // 229 bytes of UTF-8: with the job id over ext4's 255
        List<String> names = List.of(korean, "a:b*c?.jpg", "dog.jpg");
        Job job = jobService.submitJobV1(names.stream().<MultipartFile>map(JobServiceStoreTest::photo).toList(), null);
        assertThat(job.getUploads()).extracting(StoredUpload::name).containsExactlyElementsOf(names);
        for (StoredUpload upload : job.getUploads()) {
            String disk = Path.of(upload.path()).getFileName().toString();
            assertThat(Files.size(Path.of(upload.path()))).isEqualTo(3);
            assertThat(disk).startsWith(job.getId() + "_").doesNotContainPattern("[<>:\"|?*\\\\]");
            assertThat(disk.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(255 - 4);
        }
        assertThat(Path.of(job.getUploads().get(0).path()).getFileName().toString()).endsWith(".jpg");
        assertThat(Path.of(job.getUploads().get(2).path()).getFileName().toString()).isEqualTo(job.getId() + "_dog.jpg");
    }

    @Test
    void storedNamesKeepShortExtensionsAndWholeCharacters() {
        assertThat(JobServiceImpl.storedName("id", "photo")).isEqualTo("id_photo");
        assertThat(JobServiceImpl.storedName("id", "a\r\nb.png")).isEqualTo("id_a__b.png");
        String cut = JobServiceImpl.storedName("id", "멍".repeat(100) + ".webp");
        assertThat(cut).endsWith(".webp").startsWith("id_멍");
        assertThat(cut.substring(3).getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(JobServiceImpl.MAX_NAME_BYTES);
        String noExtension = JobServiceImpl.storedName("id", "x".repeat(300) + ".notanextension1");
        assertThat(noExtension.substring(3)).hasSize(JobServiceImpl.MAX_NAME_BYTES);
        assertThat(JobServiceImpl.storedName("id", "???")).isEqualTo("id____");
    }

    @Test
    void aFailedRequestLeavesNoPhotosBehind() throws IOException {
        long jobsBefore = jobRepository.count();
        MockMultipartFile broken = new MockMultipartFile("photos", "broken.png", "image/png", new byte[]{1, 2, 3}) {
            @Override
            public InputStream getInputStream() throws IOException {
                throw new IOException("disk full");
            }
        };
        assertThatThrownBy(() -> jobService.submitJobV1(List.of(photo("first.png"), broken), null))
                .isInstanceOf(UncheckedIOException.class);
        assertThat(jobRepository.count()).isEqualTo(jobsBefore);
        try (Stream<Path> files = Files.list(storage.resolve("uploads"))) {
            assertThat(files.filter(file -> file.getFileName().toString().endsWith("_first.png"))).isEmpty();
        }
    }

    @Test
    void theLoserOfASameKeyRaceIsDroppedBeforeAnyWorkerSeesIt() throws IOException {
        Job first = jobService.submitJobV1(List.of(photo("winner.png")), null);
        idempotencyKeys.saveAndFlush(new IdempotencyKeyEntry("race-key", first.getId(), Instant.now()));
        // The second request missed the key, stored its job (not yet dispatched) and now links: it loses.
        Job loser = jobService.saveUploads(List.of(photo("loser.png")));
        Job answer = jobService.linkIdempotencyKey("race-key", loser);
        assertThat(answer.getId()).isEqualTo(first.getId());
        assertThat(jobRepository.findById(loser.getId())).isEmpty();
        assertThat(Files.exists(Path.of(loser.getUploads().get(0).path()))).isFalse();
        assertThat(jobRepository.findById(first.getId())).isPresent();
    }

    private static MockMultipartFile photo(String name) {
        return new MockMultipartFile("photos", name, "image/png", new byte[]{1, 2, 3});
    }

    private static MockMultipartFile tracked(String name, List<TrackedStream> streams) {
        return new MockMultipartFile("photos", name, "image/png", new byte[]{1, 2, 3}) {
            @Override
            public InputStream getInputStream() {
                TrackedStream stream = new TrackedStream(new byte[]{1, 2, 3});
                streams.add(stream);
                return stream;
            }
        };
    }

    static final class TrackedStream extends ByteArrayInputStream {
        boolean closed;

        TrackedStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}

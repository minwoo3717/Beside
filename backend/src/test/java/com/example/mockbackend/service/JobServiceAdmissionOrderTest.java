package com.example.mockbackend.service;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.exception.ApiException;
import com.example.mockbackend.exception.ErrorCode;
import com.example.mockbackend.repository.IdempotencyKeyRepository;
import com.example.mockbackend.repository.JobRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Queue cap (REVIEW_CHECKLIST #21, proposal before the contract meeting): a request keeps its place until the write
 * that makes its job PENDING is committed (the INSERT of a create, the FAILED → PENDING update of a retry). Given back
 * before, the gate would count neither the place nor the row for a moment and admit past the cap (under-counting,
 * which the design rules out; counting both for a moment is fine). The gate's own tests only imitate that order, so
 * these hold JobServiceImpl to it: the place is still held when the write commits, and a write slowed down inside the
 * place lets no request past the cap.
 * <p>
 * The writes are watched by a test-only wrapper around the JobRepository bean, not a Mockito spy: the scheduler calls
 * the same bean (JobQueueGauge at startup and every 5 s), and a call from that thread can take a doAnswer stubbing
 * meant for the test thread. Its own class because @MockBean and the wrapper change the whole context.
 */
@SpringBootTest(properties = {"admission.max-pending=2", "admission.retry-after-seconds=45"})
class JobServiceAdmissionOrderTest {
    private static final int CAP = 2;
    private static final int REQUESTS = 12;
    /** A write takes SLOW_WRITE_MS here, and the requests come STAGGER_MS apart: most arrive during the first writes. */
    private static final long SLOW_WRITE_MS = 150;
    private static final long STAGGER_MS = 10;

    /** How many places the gate held when each watched write was committed (set and read by the tests). */
    private static final List<Integer> placesHeldAtCommit = new CopyOnWriteArrayList<>();
    private static volatile AdmissionGate watchedGate;
    private static volatile long slowWriteMs;

    @TempDir
    static Path storage;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("storage.upload.dir", () -> storage.resolve("uploads").toString());
        registry.add("storage.result.dir", () -> storage.resolve("results").toString());
    }

    /** Wraps the JobRepository bean: save(Job) and startRetry are slowed down and watched, the rest passes through. */
    @TestConfiguration
    static class WatchedWrites {
        @Bean
        static BeanPostProcessor watchJobWrites() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof JobRepository repository)) {
                        return bean;
                    }
                    return Proxy.newProxyInstance(JobRepository.class.getClassLoader(), new Class<?>[]{JobRepository.class},
                            (proxy, method, args) -> {
                                boolean watched = method.getName().equals("startRetry")
                                        || (method.getName().equals("save") && args != null && args[0] instanceof Job);
                                if (watched) {
                                    Thread.sleep(slowWriteMs);
                                }
                                Object result;
                                try {
                                    result = method.invoke(repository, args);
                                } catch (InvocationTargetException e) {
                                    throw e.getCause();
                                }
                                if (watched) {
                                    recordPlacesAtCommit();
                                }
                                return result;
                            });
                }
            };
        }
    }

    private static void recordPlacesAtCommit() {
        AdmissionGate gate = watchedGate;
        if (gate == null) {
            return; // a write before the test set the gate (startup)
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // A transaction is still open around the write (a @Transactional on JobServiceImpl would be one): the
            // write is committed when that one ends.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    placesHeldAtCommit.add(gate.reserved());
                }
            });
        } else {
            placesHeldAtCommit.add(gate.reserved()); // the repository's own transaction has committed
        }
    }

    /** Takes the dispatch and runs nothing: a job stays as the request left it. */
    @MockBean
    JobWorker jobWorker;

    @Autowired
    JobRepository jobRepository;

    @Autowired
    IdempotencyKeyRepository idempotencyKeys;

    @Autowired
    JobServiceImpl jobService;

    @Autowired
    AdmissionGate admissionGate;

    @BeforeEach
    void watchTheWrites() {
        slowWriteMs = 0;
        placesHeldAtCommit.clear();
        watchedGate = admissionGate;
    }

    @AfterEach
    void clear() {
        watchedGate = null;
        slowWriteMs = 0;
        idempotencyKeys.deleteAll();
        jobRepository.deleteAll(); // nothing runs them: PENDING rows would fill the next test's queue
    }

    @Test
    void aCreateHoldsItsPlaceUntilItsRowIsCommitted() {
        Job job = jobService.submitJobV1(List.of(photo()), "order-key");

        assertThat(placesHeldAtCommit).as("the INSERT was committed while the place was held").containsExactly(1);
        assertThat(admissionGate.reserved()).isZero();
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.PENDING);
    }

    @Test
    void aRetryHoldsItsPlaceUntilItsUpdateIsCommitted() {
        Job failed = jobRepository.save(failedJob());
        placesHeldAtCommit.clear();

        jobService.retry(failed.getId());

        assertThat(placesHeldAtCommit).as("the FAILED -> PENDING update was committed while the place was held")
                .containsExactly(1);
        assertThat(admissionGate.reserved()).isZero();
    }

    @Test
    void slowInsertsFromManyRequestsAtOnceAdmitExactlyTheCap() throws Exception {
        slowWriteMs = SLOW_WRITE_MS;
        List<Integer> statuses = atOnce(i -> {
            jobService.submitJobV1(List.of(photo()), null);
            return 202;
        });

        assertThat(statuses).filteredOn(status -> status == 202).hasSize(CAP);
        assertThat(statuses).filteredOn(status -> status == 503).hasSize(REQUESTS - CAP);
        assertThat(jobRepository.countByStatus(JobStatus.PENDING)).isEqualTo(CAP);
        assertThat(admissionGate.reserved()).isZero();
    }

    @Test
    void slowRetriesOfMoreFailedJobsThanTheCapRequeueExactlyTheCap() throws Exception {
        List<String> failed = new ArrayList<>();
        for (int i = 0; i < CAP + 1; i++) {
            failed.add(jobRepository.save(failedJob()).getId());
        }
        slowWriteMs = SLOW_WRITE_MS;
        List<Integer> statuses = atOnce(i -> {
            jobService.retry(failed.get(i % failed.size()));
            return 202;
        });

        assertThat(statuses).filteredOn(status -> status == 202).hasSize(CAP);
        assertThat(jobRepository.countByStatus(JobStatus.PENDING)).isEqualTo(CAP);
        assertThat(jobRepository.countByStatus(JobStatus.FAILED)).isEqualTo(1);
        assertThat(admissionGate.reserved()).isZero();
    }

    private interface Request {
        int send(int index) throws Exception;
    }

    /**
     * REQUESTS requests from as many threads, released together and then STAGGER_MS apart, so most of them ask while the
     * first ones are inside their slowed write; a refusal answers its status (503 QUEUE_FULL or 409 JOB_NOT_FAILED).
     */
    private static List<Integer> atOnce(Request request) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(REQUESTS);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> answers = new ArrayList<>();
            for (int i = 0; i < REQUESTS; i++) {
                int index = i;
                answers.add(pool.submit(() -> {
                    go.await();
                    Thread.sleep(index * STAGGER_MS);
                    try {
                        return request.send(index);
                    } catch (ApiException refused) {
                        assertThat(refused.getCode()).isIn(ErrorCode.QUEUE_FULL, ErrorCode.JOB_NOT_FAILED);
                        return refused.getStatus().value();
                    }
                }));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> answer : answers) {
                statuses.add(answer.get(60, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    private static MockMultipartFile photo() {
        return new MockMultipartFile("photos", "dog.png", "image/png", new byte[]{1, 2, 3});
    }

    private static Job failedJob() {
        Instant then = Instant.now().minusSeconds(60);
        Job failed = new Job();
        failed.setId(UUID.randomUUID().toString());
        failed.setStatus(JobStatus.FAILED);
        failed.setCreatedAt(then);
        failed.setUpdatedAt(then);
        failed.setQueuedAt(then);
        failed.setFinishedAt(then);
        failed.setAttempt(1);
        failed.setErrorCode("INFERENCE_FAILED");
        failed.setErrorMessage("first attempt");
        return failed;
    }
}

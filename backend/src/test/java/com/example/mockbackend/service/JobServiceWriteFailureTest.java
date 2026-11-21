package com.example.mockbackend.service;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobStatus;
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
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Queue cap (REVIEW_CHECKLIST #21, decided 2026-11-21): a request whose job row is already written
 * but which fails before the job reaches a worker leaves no PENDING row behind. With the cap on, such a row would be
 * counted for admission.stale-after-ms (hours in prod) while no worker ever runs it: the request-side twin of the
 * worker fix (RealJobWorker/MockJobWorker failUnended). Two ways in: the Idempotency-Key link of a create fails with an
 * error other than a same-key race, and the read after a retry's FAILED -> PENDING update fails.
 * <p>
 * The failures are injected by a test-only wrapper around the repositories, not a Mockito spy (the scheduler calls the
 * same JobRepository bean; see JobServiceAdmissionOrderTest), and only on the test thread. Its own class because the
 * wrapper and @MockBean change the whole context.
 */
@SpringBootTest(properties = {"admission.max-pending=1", "admission.retry-after-seconds=45"})
class JobServiceWriteFailureTest {
    private static volatile Thread target;
    private static volatile boolean failKeyLink;
    private static volatile boolean failReadAfterRetry;
    private static volatile boolean retryUpdated;

    @TempDir
    static Path storage;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("storage.upload.dir", () -> storage.resolve("uploads").toString());
        registry.add("storage.result.dir", () -> storage.resolve("results").toString());
    }

    @TestConfiguration
    static class FailingWrites {
        @Bean
        static BeanPostProcessor failSomeRepositoryCalls() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof JobRepository jobs) {
                        return wrap(JobRepository.class, jobs);
                    }
                    if (bean instanceof IdempotencyKeyRepository keys) {
                        return wrap(IdempotencyKeyRepository.class, keys);
                    }
                    return bean;
                }
            };
        }

        private static <T> T wrap(Class<T> type, T repository) {
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
                boolean mine = Thread.currentThread() == target;
                String name = method.getName();
                if (mine && failKeyLink && name.equals("saveAndFlush")) {
                    throw new DataAccessResourceFailureException("test: the key link could not be written");
                }
                if (mine && failReadAfterRetry && retryUpdated && name.equals("findById")) {
                    retryUpdated = false;
                    throw new DataAccessResourceFailureException("test: the job could not be read after the update");
                }
                Object result;
                try {
                    result = method.invoke(repository, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
                if (mine && name.equals("startRetry")) {
                    retryUpdated = true;
                }
                return result;
            }));
        }
    }

    /** Takes the dispatch and runs nothing: a job stays as the request left it. */
    @MockBean
    JobWorker jobWorker;

    @Autowired
    JobServiceImpl jobService;

    @Autowired
    JobRepository jobRepository;

    @Autowired
    IdempotencyKeyRepository idempotencyKeys;

    @Autowired
    AdmissionGate admissionGate;

    @BeforeEach
    void onThisThread() {
        target = Thread.currentThread();
        failKeyLink = false;
        failReadAfterRetry = false;
        retryUpdated = false;
    }

    @AfterEach
    void clear() {
        target = null;
        failKeyLink = false;
        failReadAfterRetry = false;
        idempotencyKeys.deleteAll();
        jobRepository.deleteAll(); // nothing runs them: PENDING rows would fill the next test's queue
    }

    @Test
    void aCreateWhoseKeyLinkFailsLeavesNoJobNoPhotosAndNoPlace() throws IOException {
        failKeyLink = true;

        assertThatThrownBy(() -> jobService.submitJobV1(List.of(photo()), "link-fails"))
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(jobRepository.count()).as("no PENDING row that no worker will run").isZero();
        assertThat(storedFiles()).as("the stored photos go with the row").isZero();
        assertThat(admissionGate.reserved()).isZero();

        failKeyLink = false;
        Job next = jobService.submitJobV1(List.of(photo()), "link-works"); // cap 1: refused if a row were left
        assertThat(jobRepository.findById(next.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.PENDING);
    }

    @Test
    void aRetryWhoseReadAfterTheUpdateFailsGoesBackToFailed() {
        Job failed = jobRepository.save(failedJob());
        failReadAfterRetry = true;

        assertThatThrownBy(() -> jobService.retry(failed.getId()))
                .isInstanceOf(DataAccessResourceFailureException.class);

        Job now = jobRepository.findById(failed.getId()).orElseThrow();
        assertThat(now.getStatus()).as("not a PENDING attempt that nothing dispatches").isEqualTo(JobStatus.FAILED);
        assertThat(now.getAttempt()).isEqualTo(failed.getAttempt());
        assertThat(admissionGate.reserved()).isZero();

        failReadAfterRetry = false;
        Job retried = jobService.retry(failed.getId()); // cap 1: refused if the job were left PENDING
        assertThat(retried.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(retried.getAttempt()).isEqualTo(failed.getAttempt() + 1);
    }

    private static long storedFiles() throws IOException {
        Path uploads = storage.resolve("uploads");
        if (!Files.exists(uploads)) {
            return 0;
        }
        try (Stream<Path> files = Files.walk(uploads)) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    private static Job failedJob() {
        Instant then = Instant.now().minusSeconds(60);
        Job job = new Job();
        job.setId(UUID.randomUUID().toString());
        job.setStatus(JobStatus.FAILED);
        job.setCreatedAt(then);
        job.setQueuedAt(then);
        job.setUpdatedAt(then);
        job.setFinishedAt(then);
        job.setAttempt(1);
        job.setErrorCode("INFERENCE_FAILED");
        job.setErrorMessage("first attempt");
        return job;
    }

    private static MockMultipartFile photo() {
        return new MockMultipartFile("photos", "dog.png", "image/png",
                new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0});
    }
}

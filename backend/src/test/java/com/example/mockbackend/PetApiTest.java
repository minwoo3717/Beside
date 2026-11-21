package com.example.mockbackend;

import com.example.mockbackend.api.v1.dto.JobPetRequest;
import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobRun;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.domain.RunDetails;
import com.example.mockbackend.exception.ApiException;
import com.example.mockbackend.exception.ErrorCode;
import com.example.mockbackend.repository.JobRepository;
import com.example.mockbackend.repository.JobRunRepository;
import com.example.mockbackend.service.PetService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockPart;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The pet library (docs/api/openapi.yaml tag pets) against the mock worker with short delays: pet CRUD and its checks,
 * uploading for a pet (the public POST /api/v1/jobs makes an unassigned job and ignores a petId part; the library then
 * calls PUT /api/v1/jobs/{id}/pet), moving jobs between pets and back to unassigned, the cover rule
 * (chosen COMPLETED job of the pet, else the newest COMPLETED one) and the two ways to delete a pet.
 * One Spring context for the class, so every test makes its own pets and jobs and looks only at those.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PetApiTest {
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @TempDir
    static Path storage;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("storage.upload.dir", () -> storage.resolve("uploads").toString());
        registry.add("storage.result.dir", () -> storage.resolve("results").toString());
        registry.add("storage.result.sample", () -> storage.resolve("sample-dog.glb").toString());
        registry.add("mock.worker.pending-ms", () -> "100");
        registry.add("mock.worker.processing-ms", () -> "50");
    }

    @BeforeAll
    static void writeSample() throws IOException {
        Files.write(storage.resolve("sample-dog.glb"), "glTF pet sample".getBytes(StandardCharsets.UTF_8));
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JobRepository jobs;

    @Autowired
    JobRunRepository jobRuns;

    @Autowired
    PetService petService;

    // ------------------------------------------------------------------ CRUD

    @Test
    void createsListsAndReadsPets() throws Exception {
        MvcResult created = mvc.perform(json(post("/api/v1/pets"), Map.of("name", "  초코  ", "species", "CAT", "note", "갈색 고양이")))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode choco = body(created);
        String chocoId = choco.path("id").asText();
        assertThat(chocoId).matches(UUID_PATTERN);
        assertThat(created.getResponse().getHeader("Location")).isEqualTo("/api/v1/pets/" + chocoId);
        assertThat(choco.path("name").asText()).as("trimmed").isEqualTo("초코");
        assertThat(choco.path("species").asText()).isEqualTo("CAT");
        assertThat(choco.path("note").asText()).isEqualTo("갈색 고양이");
        assertThat(choco.path("jobCount").asInt()).isZero();
        assertThat(choco.has("coverThumbnailUrl")).as("no thumbnail endpoint in API v1.2 (#11)").isFalse();
        for (String empty : List.of("coverJobId", "latestJobAt")) {
            assertThat(choco.has(empty)).as(empty).isTrue();
            assertThat(choco.path(empty).isNull()).as(empty).isTrue();
        }
        assertThat(choco.path("createdAt").asText()).isEqualTo(choco.path("updatedAt").asText());

        JsonNode bori = body(mvc.perform(json(post("/api/v1/pets"), Map.of("name", "보리")))
                .andExpect(status().isCreated()).andReturn());
        assertThat(bori.path("species").asText()).as("default species").isEqualTo("DOG");
        assertThat(bori.path("note").isNull()).isTrue();

        List<String> listed = new ArrayList<>();
        body(mvc.perform(get("/api/v1/pets")).andExpect(status().isOk()).andReturn())
                .path("items").forEach(item -> listed.add(item.path("id").asText()));
        assertThat(listed).as("in the order they were added").containsSubsequence(chocoId, bori.path("id").asText());

        JsonNode detail = body(mvc.perform(get("/api/v1/pets/{id}", chocoId)).andExpect(status().isOk()).andReturn());
        assertThat(detail.path("name").asText()).isEqualTo("초코");
        assertThat(detail.path("createdAt").asText()).isEqualTo(choco.path("createdAt").asText());
        assertThat(detail.path("jobs").isArray()).isTrue();
        assertThat(detail.path("jobs")).isEmpty();
        assertThat(detail.path("modelVersions").isObject()).isTrue();

        for (String unknown : List.of(UUID.randomUUID().toString(), "not-a-pet")) {
            mvc.perform(get("/api/v1/pets/{id}", unknown))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("PET_NOT_FOUND"));
        }
    }

    @Test
    void rejectsPetsThatBreakTheRules() throws Exception {
        List<String> bad = List.of(
                "{}",
                "{\"name\":null}",
                "{\"name\":\"   \"}",
                "{\"name\":\"" + "가".repeat(41) + "\"}",
                "{\"name\":\"나비\",\"species\":\"FISH\"}",
                "{\"name\":\"나비\",\"species\":\"\"}",
                "{\"name\":\"나비\",\"note\":\"" + "a".repeat(501) + "\"}",
                "{\"name\":",
                "not json");
        for (String request : bad) {
            mvc.perform(post("/api/v1/pets").contentType(MediaType.APPLICATION_JSON).content(request))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }
        mvc.perform(json(post("/api/v1/pets"), Map.of("name", "가".repeat(40), "species", "OTHER", "note", "a".repeat(500))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.species").value("OTHER"));
        mvc.perform(json(post("/api/v1/pets"), Map.of("name", "빈 메모", "note", "   ")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.note").isEmpty());
    }

    @Test
    void patchChangesOnlyTheFieldsSent() throws Exception {
        String petId = createPet("콩이", "DOG");
        JsonNode before = pet(petId);
        mvc.perform(json(patch("/api/v1/pets/{id}", petId), Map.of("note", "산책 좋아함"))).andExpect(status().isOk());

        Thread.sleep(5);
        JsonNode renamed = body(mvc.perform(json(patch("/api/v1/pets/{id}", petId), Map.of("name", " 콩떡 ", "species", "OTHER")))
                .andExpect(status().isOk()).andReturn());
        assertThat(renamed.path("name").asText()).isEqualTo("콩떡");
        assertThat(renamed.path("species").asText()).isEqualTo("OTHER");
        assertThat(renamed.path("note").asText()).as("not sent, kept").isEqualTo("산책 좋아함");
        assertThat(Instant.parse(renamed.path("updatedAt").asText())).isAfter(Instant.parse(before.path("updatedAt").asText()));
        assertThat(renamed.path("createdAt").asText()).isEqualTo(before.path("createdAt").asText());

        mvc.perform(patch("/api/v1/pets/{id}", petId).contentType(MediaType.APPLICATION_JSON).content("{\"note\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.note").isEmpty())
                .andExpect(jsonPath("$.name").value("콩떡"));
        mvc.perform(patch("/api/v1/pets/{id}", petId).contentType(MediaType.APPLICATION_JSON).content("{\"unknownField\":1}"))
                .andExpect(status().isOk());

        for (String request : List.of("{\"name\":null}", "{\"species\":null}", "{\"species\":\"cat\"}", "{\"name\":\"\"}", "[1]")) {
            mvc.perform(patch("/api/v1/pets/{id}", petId).contentType(MediaType.APPLICATION_JSON).content(request))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }
        assertThat(pet(petId).path("species").asText()).isEqualTo("OTHER");

        mvc.perform(json(patch("/api/v1/pets/{id}", UUID.randomUUID().toString()), Map.of("name", "없음")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PET_NOT_FOUND"));
    }

    // ------------------------------------------------------------------ jobs in pets

    @Test
    void uploadsIntoAPetAndMovesJobsBetweenPets() throws Exception {
        String first = createPet("하나", "DOG");
        String second = createPet("두리", "CAT");

        String jobId = upload("dog.jpg", first);
        assertThat(job(jobId).path("petId").asText()).isEqualTo(first);
        assertThat(pet(first).path("jobCount").asInt()).isEqualTo(1);

        // The public create route writes nothing into the library (review finding, 2026-11-21): the review branch's
        // petId part is ignored, whether it names a pet or not, and the job is unassigned.
        for (String part : List.of(first, UUID.randomUUID().toString(), "abc")) {
            String created = body(mvc.perform(multipart("/api/v1/jobs")
                            .file(new MockMultipartFile("photos", "dog.jpg", "image/jpeg", new byte[]{1, 2, 3}))
                            .part(new MockPart("petId", part.getBytes(StandardCharsets.UTF_8))))
                    .andExpect(status().isAccepted()).andReturn()).path("jobId").asText();
            assertThat(job(created).path("petId").isNull()).as("petId part %s", part).isTrue();
        }
        assertThat(pet(first).path("jobCount").asInt()).as("only the job put there with PUT /pet").isEqualTo(1);

        JsonNode moved = body(mvc.perform(json(put("/api/v1/jobs/{id}/pet", jobId), Map.of("petId", second)))
                .andExpect(status().isOk()).andReturn());
        assertThat(moved.path("id").asText()).isEqualTo(jobId);
        assertThat(moved.path("petId").asText()).isEqualTo(second);
        assertThat(moved.path("status").isTextual()).as("a JobResponse").isTrue();
        assertThat(pet(first).path("jobCount").asInt()).isZero();
        assertThat(pet(second).path("jobCount").asInt()).isEqualTo(1);

        mvc.perform(put("/api/v1/jobs/{id}/pet", jobId).contentType(MediaType.APPLICATION_JSON).content("{\"petId\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.petId").isEmpty());
        assertThat(pet(second).path("jobCount").asInt()).isZero();

        for (String request : List.of("{}", "{\"petId\":\"" + UUID.randomUUID() + "\"}", "{\"petId\":", "")) {
            mvc.perform(put("/api/v1/jobs/{id}/pet", jobId).contentType(MediaType.APPLICATION_JSON).content(request))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }
        String unknownJob = UUID.randomUUID().toString();
        mvc.perform(json(put("/api/v1/jobs/{id}/pet", unknownJob), Map.of("petId", first)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("JOB_NOT_FOUND"))
                .andExpect(jsonPath("$.error.jobId").value(unknownJob));
    }

    @Test
    void aWorkerSavingItsOlderCopyKeepsTheAssignment() throws Exception {
        String petId = createPet("늦둥이", "DOG");
        String jobId = upload("late.jpg", null);
        Job workersCopy = jobs.findById(jobId).orElseThrow(); // what a worker loaded before the user moved the job
        mvc.perform(json(put("/api/v1/jobs/{id}/pet", jobId), Map.of("petId", petId))).andExpect(status().isOk());

        workersCopy.setUpdatedAt(Instant.now());
        jobs.save(workersCopy); // e.g. JobFinisher.complete minutes later
        assertThat(jobs.findById(jobId).orElseThrow().getPetId()).as("petId is not written by job saves").isEqualTo(petId);
        assertThat(job(jobId).path("petId").asText()).isEqualTo(petId);
    }

    @Test
    void coverIsTheChosenCompletedJobOrElseTheNewestCompleted() throws Exception {
        String petId = createPet("대표", "DOG");
        String other = createPet("남의 펫", "CAT");
        String chosenJob = completed(upload("photo.jpg", petId));
        String newest = completed(upload("plain.jpg", petId));
        String failed = upload("fail-dog.jpg", petId);
        awaitStatus(failed, "FAILED");
        String othersJob = completed(upload("other.jpg", other));

        JsonNode auto = pet(petId);
        assertThat(auto.path("jobCount").asInt()).isEqualTo(3);
        assertThat(auto.path("coverJobId").asText()).as("newest COMPLETED").isEqualTo(newest);
        assertThat(auto.path("latestJobAt").asText()).isEqualTo(job(failed).path("createdAt").asText());
        List<String> order = new ArrayList<>();
        auto.path("jobs").forEach(item -> order.add(item.path("id").asText()));
        assertThat(order).as("newest first").containsExactly(failed, newest, chosenJob);
        assertThat(auto.path("jobs").get(0).has("thumbnailUrl")).as("no thumbnail endpoint in API v1.2 (#11)").isFalse();
        assertThat(auto.path("jobs").get(0).path("petId").asText()).isEqualTo(petId);

        JsonNode chosen = body(mvc.perform(json(patch("/api/v1/pets/{id}", petId), Map.of("coverJobId", chosenJob)))
                .andExpect(status().isOk()).andReturn());
        assertThat(chosen.path("coverJobId").asText()).isEqualTo(chosenJob);
        JsonNode listed = null;
        for (JsonNode item : body(mvc.perform(get("/api/v1/pets")).andExpect(status().isOk()).andReturn()).path("items")) {
            if (item.path("id").asText().equals(petId)) {
                listed = item;
            }
        }
        assertThat(listed).isNotNull();
        assertThat(listed.path("coverJobId").asText()).isEqualTo(chosenJob);
        assertThat(listed.path("jobCount").asInt()).isEqualTo(3);

        for (String notAllowed : List.of(failed, othersJob, UUID.randomUUID().toString())) {
            mvc.perform(json(patch("/api/v1/pets/{id}", petId), Map.of("coverJobId", notAllowed)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }
        assertThat(pet(petId).path("coverJobId").asText()).isEqualTo(chosenJob);

        // Moving the chosen job away clears the choice; the cover falls back to the newest COMPLETED job.
        mvc.perform(json(put("/api/v1/jobs/{id}/pet", chosenJob), Map.of("petId", other))).andExpect(status().isOk());
        assertThat(pet(petId).path("coverJobId").asText()).isEqualTo(newest);
        mvc.perform(json(put("/api/v1/jobs/{id}/pet", chosenJob), Map.of("petId", petId))).andExpect(status().isOk());
        assertThat(pet(petId).path("coverJobId").asText()).as("not chosen again by itself").isEqualTo(newest);

        mvc.perform(json(patch("/api/v1/pets/{id}", petId), Map.of("coverJobId", chosenJob))).andExpect(status().isOk());
        mvc.perform(patch("/api/v1/pets/{id}", petId).contentType(MediaType.APPLICATION_JSON).content("{\"coverJobId\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coverJobId").value(newest));
    }

    @Test
    void detailCarriesTheModelVersionOfEachJobsLastRun() throws Exception {
        String petId = createPet("버전", "DOG");
        String jobId = completed(upload("v.jpg", petId));
        String plain = completed(upload("plain.jpg", petId));
        Job job = jobs.findById(jobId).orElseThrow();
        job.setAttempt(2); // a later run of the same job, as the real worker would record it
        jobRuns.saveAndFlush(JobRun.of(job, Instant.now(), "real",
                new RunDetails(1L, null, null, null, null, null, null, false, "animallift@3f2a1c9")));

        JsonNode versions = pet(petId).path("modelVersions");
        assertThat(versions.path(jobId).asText()).isEqualTo("animallift@3f2a1c9");
        assertThat(versions.has(plain)).as("mock runs have no modelVersion").isFalse();
    }

    // ------------------------------------------------------------------ deleting pets

    @Test
    void deletingAPetLeavesItsJobsUnassignedByDefault() throws Exception {
        String petId = createPet("이별", "DOG");
        String jobId = completed(upload("keep.jpg", petId));

        mvc.perform(delete("/api/v1/pets/{id}", petId)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/pets/{id}", petId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PET_NOT_FOUND"));
        JsonNode kept = job(jobId);
        assertThat(kept.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(kept.path("petId").isNull()).as("back to unassigned").isTrue();

        mvc.perform(delete("/api/v1/pets/{id}", petId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PET_NOT_FOUND"));
    }

    @Test
    void deletingAPetWithItsJobsRefusesWhileOneIsRunning() throws Exception {
        String petId = createPet("정리", "DOG");
        String done = completed(upload("done.jpg", petId));
        String running = completed(upload("running.jpg", petId));
        Path photo = Path.of(jobs.findById(done).orElseThrow().getUploads().get(0).path());
        setStatus(running, JobStatus.PROCESSING); // a run that has not ended

        mvc.perform(delete("/api/v1/pets/{id}", petId).param("withJobs", "true"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("JOB_IN_PROGRESS"))
                .andExpect(jsonPath("$.error.jobId").value(running));
        assertThat(pet(petId).path("jobCount").asInt()).as("nothing deleted").isEqualTo(2);
        assertThat(photo).isRegularFile();

        mvc.perform(delete("/api/v1/pets/{id}", petId).param("withJobs", "maybe"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        setStatus(running, JobStatus.COMPLETED);
        mvc.perform(delete("/api/v1/pets/{id}", petId).param("withJobs", "true")).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/pets/{id}", petId)).andExpect(status().isNotFound());
        for (String jobId : List.of(done, running)) {
            mvc.perform(get("/api/v1/jobs/{id}", jobId)).andExpect(status().isNotFound());
            assertThat(jobRuns.findByJobIdOrderByAttemptAsc(jobId)).as("runs stay").isNotEmpty();
        }
        assertThat(photo).doesNotExist();
    }

    /**
     * Review finding 2026-11-21: putting a job into a pet raced with deleting that pet (existsById, then the update),
     * so a job could keep the deleted pet's id and show up nowhere — under no pet and not as unassigned. Both now run
     * under PetService's lock: whichever goes first, the job ends unassigned (moved, then the deletion unassigns it;
     * or deleted first, and the move is refused as an unknown pet).
     */
    @Test
    void aJobPutIntoAPetWhileThePetIsDeletedEndsUnassigned() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 20; round++) {
                String petId = createPet("경주 " + round, "DOG");
                String jobId = upload("race.jpg", null);
                JobPetRequest move = new JobPetRequest();
                move.setPetId(petId);
                CountDownLatch go = new CountDownLatch(1);
                Future<?> moving = pool.submit(() -> {
                    go.await();
                    try {
                        petService.setJobPet(jobId, move);
                    } catch (ApiException unknownPet) {
                        assertThat(unknownPet.getCode()).isEqualTo(ErrorCode.INVALID_REQUEST); // the deletion won
                    }
                    return null;
                });
                Future<?> deleting = pool.submit(() -> {
                    go.await();
                    petService.delete(petId, false);
                    return null;
                });
                go.countDown();
                moving.get(10, TimeUnit.SECONDS);
                deleting.get(10, TimeUnit.SECONDS);
                assertThat(jobs.findById(jobId).orElseThrow().getPetId()).as("round %d", round).isNull();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ page

    @Test
    void libraryPageIsServedAtLibrary() throws Exception {
        for (String path : List.of("/library", "/library/")) {
            mvc.perform(get(path)).andExpect(status().isOk()).andExpect(forwardedUrl("/library/index.html"));
        }
        String html = mvc.perform(get("/library/index.html"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("보관함", "src=\"/library/library-core.js\"", "src=\"/library/library.js\"",
                "href=\"/library/library.css\"",
                "https://cdn.jsdelivr.net/npm/@google/model-viewer@3.5.0/dist/model-viewer.min.js");
        for (String asset : List.of("/library/library.js", "/library/library-core.js", "/library/library.css")) {
            mvc.perform(get(asset)).andExpect(status().isOk());
        }
        String admin = mvc.perform(get("/admin/index.html")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(admin).as("the dashboard links the library").contains("href=\"/library/\"");
    }

    // ------------------------------------------------------------------ helpers

    private String createPet(String name, String species) throws Exception {
        return body(mvc.perform(json(post("/api/v1/pets"), Map.of("name", name, "species", species)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andReturn()).path("id").asText();
    }

    private String upload(String filename, String petId) throws Exception {
        return upload(filename, petId, new byte[]{1, 2, 3});
    }

    /** POST /api/v1/jobs, then PUT /api/v1/jobs/{id}/pet as the library page does (null = stays unassigned). */
    private String upload(String filename, String petId, byte[] bytes) throws Exception {
        var request = multipart("/api/v1/jobs").file(new MockMultipartFile("photos", filename, "image/jpeg", bytes));
        ResultActions result = mvc.perform(request).andExpect(status().isAccepted());
        String jobId = body(result.andReturn()).path("jobId").asText();
        if (petId != null) {
            mvc.perform(json(put("/api/v1/jobs/{id}/pet", jobId), Map.of("petId", petId))).andExpect(status().isOk());
        }
        return jobId;
    }

    private String completed(String jobId) {
        awaitStatus(jobId, "COMPLETED");
        return jobId;
    }

    private void awaitStatus(String jobId, String expected) {
        await().pollInterval(Duration.ofMillis(50)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(job(jobId).path("status").asText()).isEqualTo(expected));
    }

    private void setStatus(String jobId, JobStatus status) {
        Job job = jobs.findById(jobId).orElseThrow();
        job.setStatus(status);
        jobs.save(job);
    }

    private JsonNode job(String jobId) throws Exception {
        return body(mvc.perform(get("/api/v1/jobs/{id}", jobId)).andExpect(status().isOk()).andReturn());
    }

    private JsonNode pet(String petId) throws Exception {
        return body(mvc.perform(get("/api/v1/pets/{id}", petId)).andExpect(status().isOk()).andReturn());
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, Map<String, ?> body) throws IOException {
        return request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsBytes(body));
    }

    private JsonNode body(MvcResult result) throws IOException {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }
}

package com.example.mockbackend;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.repository.JobRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/v1/jobs pages with a keyset (createdAt desc, id desc) instead of loading every job per request.
 * The order and the cursor (the id of the last item, an opaque string in openapi.yaml) must stay exactly as before,
 * including jobs that share one createdAt (Instant.now() can repeat on Windows).
 */
@SpringBootTest(properties = "mock.worker.pending-ms=60000")
@AutoConfigureMockMvc
class JobListPagingTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JobRepository jobRepository;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void pagesInNewestFirstOrderAcrossTiesWithoutDuplicates() throws Exception {
        Instant base = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(3600);
        for (int i = 0; i < 25; i++) {
            // Groups of three share one createdAt, so page boundaries (limit 7) fall inside a tie.
            jobRepository.save(failedJob(base.plusSeconds(i / 3)));
        }
        List<String> expected = jobRepository.findAll().stream()
                .sorted(Comparator.comparing(Job::getCreatedAt).thenComparing(Job::getId).reversed())
                .map(Job::getId)
                .toList();

        for (int limit : new int[]{1, 7, 25, 100}) {
            List<String> seen = new ArrayList<>();
            String cursor = null;
            int pages = 0;
            do {
                String url = "/api/v1/jobs?limit=" + limit + (cursor == null ? "" : "&cursor=" + cursor);
                JsonNode page = objectMapper.readTree(mvc.perform(get(url)).andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());
                page.path("items").forEach(item -> seen.add(item.path("id").asText()));
                assertThat(page.path("items").size()).isLessThanOrEqualTo(limit);
                cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
                if (cursor != null) {
                    assertThat(cursor).isEqualTo(seen.get(seen.size() - 1));
                }
                assertThat(++pages).isLessThanOrEqualTo(expected.size() + 1);
            } while (cursor != null);
            assertThat(seen).as("limit=%d", limit).containsExactlyElementsOf(expected);
        }
    }

    @Test
    void lastFullPageHasNoNextCursor() throws Exception {
        long total = jobRepository.count();
        JsonNode page = objectMapper.readTree(mvc.perform(get("/api/v1/jobs?limit=100")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        if (total <= 100) {
            assertThat(page.path("items").size()).isEqualTo((int) total);
            assertThat(page.path("nextCursor").isNull()).isTrue();
        }
    }

    @Test
    void jobsTableHasTheIndexThePagingUses() {
        List<String> indexes = jdbc.queryForList(
                "select index_name from information_schema.indexes where table_name = 'JOBS'", String.class);
        assertThat(indexes).anySatisfy(name -> assertThat(name).isEqualToIgnoringCase("idx_jobs_created_at_id"));
    }

    private static Job failedJob(Instant createdAt) {
        Job job = new Job();
        job.setId(UUID.randomUUID().toString());
        job.setStatus(JobStatus.FAILED);
        job.setCreatedAt(createdAt);
        job.setUpdatedAt(createdAt);
        job.setQueuedAt(createdAt);
        job.setFinishedAt(createdAt);
        job.setAttempt(1);
        job.setErrorCode("INFERENCE_FAILED");
        job.setErrorMessage("paging test");
        return job;
    }
}

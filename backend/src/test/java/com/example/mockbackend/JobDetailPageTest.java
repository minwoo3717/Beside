package com.example.mockbackend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The job detail page (/job/?id=, tag quality) and the dashboard scripts it shares. The page shows the uploaded photos'
 * names and sizes only: the review branch's GET /api/v1/jobs/{jobId}/photos/{index} was not taken into API v1.2
 * (REVIEW_CHECKLIST #11, 2026-11-21), so the page never asks for it (job-format.test.cjs checks the rules).
 */
@SpringBootTest
@AutoConfigureMockMvc
class JobDetailPageTest {

    @Autowired
    MockMvc mvc;

    @Test
    void jobDetailPageIsServedAtJobWithAndWithoutTrailingSlash() throws Exception {
        mvc.perform(get("/job")).andExpect(status().isOk()).andExpect(forwardedUrl("/job/index.html"));
        mvc.perform(get("/job/")).andExpect(status().isOk()).andExpect(forwardedUrl("/job/index.html"));
        String html = mvc.perform(get("/job/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/job/job.js")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        // Same pinned model-viewer build (and SRI hash) as the share page.
        String share = mvc.perform(get("/share.html")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String modelViewer = share.lines().filter(line -> line.contains("model-viewer@")).findFirst().orElseThrow().strip();
        assertThat(html).contains(modelViewer);
        for (String asset : List.of("/job/job.js", "/job/job-format.js", "/job/qr.js", "/job/job.css")) {
            mvc.perform(get(asset)).andExpect(status().isOk());
        }
        String script = mvc.perform(get("/job/job.js")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(script).as("no request to the photos endpoint left out of v1.2").doesNotContain("photoUrl");
        // The dashboard links here and carries the report panels' helpers.
        mvc.perform(get("/admin/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/admin/admin-measurement.js")));
        mvc.perform(get("/admin/admin-measurement.js")).andExpect(status().isOk());
    }
}

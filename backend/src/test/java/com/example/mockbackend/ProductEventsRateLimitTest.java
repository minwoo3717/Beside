package com.example.mockbackend;

import com.example.mockbackend.repository.ProductEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The collector's server-wide token bucket (docs/METRICS.md §6.4): over the limit a batch is answered 202 with
 * accepted 0 and nothing stored, so no new status code enters the contract (429 RATE_LIMITED stays reserved, #22).
 * A bucket of 2 that refills once in ~17 minutes, so the third batch of this test is over it.
 */
@SpringBootTest(properties = {"events.rate.batches-per-second=0.001", "events.rate.burst=2"})
@AutoConfigureMockMvc
class ProductEventsRateLimitTest {
    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    ProductEventRepository events;

    @Autowired
    MeterRegistry meters;

    @Test
    void batchesOverTheLimitAreAnsweredButNotStored() throws Exception {
        events.deleteAll();
        double before = meters.get("beside.app.events.dropped").tag("reason", "rate_limited").counter().count();
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON).content(batch(3)))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.accepted").value(3));
        }
        mvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON).content(batch(3)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(0))
                .andExpect(jsonPath("$.dropped").value(3));

        assertThat(events.count()).isEqualTo(6);
        assertThat(meters.get("beside.app.events.dropped").tag("reason", "rate_limited").counter().count() - before)
                .isEqualTo(3.0);
        // A bad batch is still refused with 400, limit or not: the client must learn about its bug.
        mvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    private String batch(int size) {
        ObjectNode batch = objectMapper.createObjectNode()
                .put("installId", UUID.randomUUID().toString())
                .put("sessionId", UUID.randomUUID().toString())
                .put("appVersion", "0.4.0")
                .put("platform", "android");
        ArrayNode list = batch.putArray("events");
        for (int seq = 0; seq < size; seq++) {
            list.addObject().put("name", "photo_saved").put("at", Instant.now().toString()).put("seq", seq);
        }
        return batch.toString();
    }
}

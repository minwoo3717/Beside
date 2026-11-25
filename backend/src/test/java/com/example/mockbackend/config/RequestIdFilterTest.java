package com.example.mockbackend.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * X-Request-Id (docs/CONTRACT.md §2): the app's id when it is safe to log, else a new UUID; in the MDC while the
 * request runs and gone afterwards; echoed as a response header, also on errors and on the /error dispatch.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RequestIdFilterTest {
    private final RequestIdFilter filter = new RequestIdFilter();

    @Autowired
    MockMvc mvc;

    @AfterEach
    void noLeftovers() {
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).as("MDC after the request").isNull();
    }

    @Test
    void theAppsIdIsUsedForTheLogsAndEchoed() throws Exception {
        // BesideApiClient sends a Guid per call; any 1~64 characters of A-Z a-z 0-9 . _ : - are taken as they are.
        for (String id : List.of("3f2b9c4e8a1d4b7f9e6c5a4b3c2d1e0f", "a", "unity:req-1.2_3", "x".repeat(64))) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/jobs/x");
            request.addHeader("X-Request-Id", id);
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicReference<String> seen = new AtomicReference<>();

            filter.doFilter(request, response, recording(seen));

            assertThat(seen.get()).as("MDC inside the request for %s", id).isEqualTo(id);
            assertThat(response.getHeader("X-Request-Id")).isEqualTo(id);
            assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
        }
    }

    @Test
    void anythingElseGetsANewUuid() throws Exception {
        List<String> refused = new ArrayList<>(List.of("", "x".repeat(65), "has space", "line\r\nforged INFO",
                "\u001b[2J", "a/b", "한글"));
        refused.add(null);
        for (String header : refused) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/healthz");
            if (header != null) {
                request.addHeader("X-Request-Id", header);
            }
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicReference<String> seen = new AtomicReference<>();

            filter.doFilter(request, response, recording(seen));

            String id = response.getHeader("X-Request-Id");
            assertThat(UUID.fromString(id)).as("new id for %s", header).isNotNull();
            assertThat(id).isNotEqualTo(header);
            assertThat(seen.get()).isEqualTo(id);
        }
    }

    @Test
    void theMdcIsClearedEvenWhenTheRequestFails() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/jobs");
        request.addHeader("X-Request-Id", "rid-fails");

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res)
                    throws ServletException {
                throw new ServletException("boom");
            }
        })).isInstanceOf(ServletException.class);

        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void theErrorDispatchOfTheSameRequestKeepsItsId() throws Exception {
        // Tomcat forwards a failed request to /error after the first pass left this filter (and cleared the MDC).
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/jobs");
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        String first = (String) request.getAttribute(RequestIdFilter.ATTRIBUTE);
        assertThat(first).isNotBlank();

        request.setDispatcherType(DispatcherType.ERROR);
        MockHttpServletResponse errorResponse = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();
        filter.doFilter(request, errorResponse, recording(seen));

        assertThat(seen.get()).isEqualTo(first);
        assertThat(errorResponse.getHeader("X-Request-Id")).isEqualTo(first);
    }

    @Test
    void everyResponseOfTheRunningServerCarriesTheHeader() throws Exception {
        mvc.perform(get("/api/v1/healthz").header("X-Request-Id", "unity-1234"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "unity-1234"));
        // Errors too: the id is what the app shows next to an error message and what the server log is searched for.
        mvc.perform(get("/api/v1/jobs/00000000-0000-0000-0000-000000000000").header("X-Request-Id", "unity-404"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Request-Id", "unity-404"));
        String generated = mvc.perform(get("/api/v1/no-such-route"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getHeader("X-Request-Id");
        assertThat(UUID.fromString(generated)).isNotNull();
    }

    private static MockFilterChain recording(AtomicReference<String> seen) {
        return new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seen.set(MDC.get(RequestIdFilter.MDC_KEY));
            }
        };
    }
}

package com.ts.ticketreservation.config;

import com.ts.ticketreservation.metrics.ReservationMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AdmissionControlFilterTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AdmissionControlFilter filter = new AdmissionControlFilter(
            new AdmissionProperties(1, Duration.ofMillis(100)), new ReservationMetrics(registry), new JsonMapper(),
            registry);

    @Test
    void shedsWith429WhenNoSlotFreesUpInTime() throws Exception {
        CountDownLatch insideChain = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // First request takes the only slot and holds it.
            executor.submit(() -> {
                filter.doFilter(request("/shows/s1/reserve"), new MockHttpServletResponse(), (req, res) -> {
                    insideChain.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                return null;
            });
            assertThat(insideChain.await(5, TimeUnit.SECONDS)).isTrue();

            var shed = new MockHttpServletResponse();
            filter.doFilter(request("/shows/s1/reserve"), shed, new MockFilterChain());

            assertThat(shed.getStatus()).isEqualTo(429);
            assertThat(shed.getContentAsString()).contains("overloaded");
            assertThat(registry.find("reservations.declined").tag("reason", "overloaded").counter().count())
                    .isEqualTo(1);
            release.countDown();
        }
    }

    @Test
    void releasesSlotSoNextRequestIsAdmitted() throws Exception {
        filter.doFilter(request("/shows"), new MockHttpServletResponse(), new MockFilterChain());

        var next = new MockHttpServletResponse();
        filter.doFilter(request("/shows"), next, new MockFilterChain());

        assertThat(next.getStatus()).isEqualTo(200);
    }

    @Test
    void healthAndMetricsAreNeverShed() throws Exception {
        CountDownLatch insideChain = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.submit(() -> {
                filter.doFilter(request("/shows"), new MockHttpServletResponse(), (req, res) -> {
                    insideChain.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                return null;
            });
            assertThat(insideChain.await(5, TimeUnit.SECONDS)).isTrue();

            for (String path : new String[] {"/health/ready", "/health/live", "/metrics"}) {
                var response = new MockHttpServletResponse();
                filter.doFilter(request(path), response, new MockFilterChain());
                assertThat(response.getStatus()).as(path).isEqualTo(200);
            }
            release.countDown();
        }
    }

    private static MockHttpServletRequest request(String path) {
        var request = new MockHttpServletRequest("POST", path);
        request.setRequestURI(path);
        return request;
    }
}

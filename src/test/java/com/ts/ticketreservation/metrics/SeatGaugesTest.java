package com.ts.ticketreservation.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With the database down, one scrape must cost one failed query, not one per gauge: each
 * attempt can wait the pool's full connection timeout, so retrying per gauge hung /metrics for
 * minutes (3 gauges x shows x 30 s).
 */
class SeatGaugesTest {

    private final AtomicInteger queries = new AtomicInteger();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final JdbcTemplate failingJdbc = new JdbcTemplate() {
        @Override
        public void query(String sql, RowCallbackHandler handler) {
            queries.incrementAndGet();
            throw new CannotGetJdbcConnectionException("pool timeout");
        }
    };

    @Test
    void failedRefreshIsCachedSoAScrapeQueriesOnce() {
        var gauges = new SeatGauges(failingJdbc, registry);
        gauges.register("show-1");
        gauges.register("show-2");
        gauges.register("show-3");

        var values = registry.find("seats.available").gauges().stream().map(Gauge::value).toList();
        registry.find("seats.held").gauges().forEach(Gauge::value);
        registry.find("seats.confirmed").gauges().forEach(Gauge::value);

        assertThat(values).hasSize(3).allMatch(v -> v.isNaN());
        assertThat(queries.get()).isEqualTo(1);
    }
}

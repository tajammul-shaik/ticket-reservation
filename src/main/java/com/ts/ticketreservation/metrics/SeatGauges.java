package com.ts.ticketreservation.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * seats_available / seats_held / seats_confirmed {show_id}, read from the database at scrape
 * time (BRD section 13), so they always match the API rather than drifting from it.
 * <p>
 * One grouped query serves all gauges of a scrape: its result is cached briefly, so a scrape
 * costs one query, not three per show, and the three gauges of a show come from one snapshot.
 * A failed refresh is cached for the same time: otherwise every gauge of the scrape would retry
 * the query, each waiting up to the pool's connection timeout, and /metrics would hang for
 * minutes while the database is down or the pool is saturated.
 */
@Component
public class SeatGauges {

    private static final Duration SNAPSHOT_TTL = Duration.ofSeconds(1);
    private static final Logger log = LoggerFactory.getLogger(SeatGauges.class);

    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry registry;
    private final Set<String> registeredShows = ConcurrentHashMap.newKeySet();

    /** countsByShow is null when the refresh failed; the gauges then report NaN. */
    private record Snapshot(long takenAtNanos, Map<String, Map<String, Long>> countsByShow) {
    }

    private volatile Snapshot snapshot = new Snapshot(0, Map.of());

    public SeatGauges(JdbcTemplate jdbcTemplate, MeterRegistry registry) {
        this.jdbcTemplate = jdbcTemplate;
        this.registry = registry;
    }

    /** Registers gauges for shows that existed before this instance started. */
    @EventListener(ApplicationReadyEvent.class)
    public void registerExistingShows() {
        jdbcTemplate.queryForList("SELECT id FROM shows", String.class).forEach(this::register);
    }

    /** Called once a new show has been committed. */
    public void register(String showId) {
        if (!registeredShows.add(showId)) {
            return;
        }
        for (String status : new String[] {"available", "held", "confirmed"}) {
            Gauge.builder("seats." + status, () -> count(showId, status))
                    .description("Seats currently " + status + ", read from the database")
                    .tag("show_id", showId)
                    .register(registry);
        }
    }

    private double count(String showId, String status) {
        Map<String, Map<String, Long>> counts = currentSnapshot().countsByShow();
        if (counts == null) {
            return Double.NaN;
        }
        return counts.getOrDefault(showId, Map.of()).getOrDefault(status, 0L);
    }

    private Snapshot currentSnapshot() {
        Snapshot current = snapshot;
        if (System.nanoTime() - current.takenAtNanos() < SNAPSHOT_TTL.toNanos()) {
            return current;
        }
        synchronized (this) {
            current = snapshot;
            if (System.nanoTime() - current.takenAtNanos() < SNAPSHOT_TTL.toNanos()) {
                return current;
            }
            Map<String, Map<String, Long>> counts = new HashMap<>();
            try {
                jdbcTemplate.query(
                        "SELECT show_id, status, COUNT(*) AS n FROM seats GROUP BY show_id, status",
                        rs -> {
                            counts.computeIfAbsent(rs.getString("show_id"), id -> new HashMap<>())
                                    .put(rs.getString("status"), rs.getLong("n"));
                        });
                snapshot = new Snapshot(System.nanoTime(), counts);
            } catch (RuntimeException e) {
                // Database unreachable: report NaN rather than stale numbers, and do not retry
                // until the snapshot expires.
                log.warn("Seat gauge refresh failed: {}", e.getClass().getSimpleName());
                snapshot = new Snapshot(System.nanoTime(), null);
            }
            return snapshot;
        }
    }
}

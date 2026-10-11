package com.ts.ticketreservation.monitoring;

import com.ts.ticketreservation.health.DatabaseReadinessCheck;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * GET /monitoring: one human-readable JSON snapshot of the instance, for watching a burst
 * without a Prometheus server. Every number is read from the same Micrometer registry that
 * /metrics publishes, so the two always agree; the only extra work is the readiness check,
 * which has its own connection and a 2 s limit. Aggregates only: no user ids.
 */
@RestController
public class MonitoringController {

    private final MeterRegistry registry;
    private final DatabaseReadinessCheck readinessCheck;
    private final String poolName;

    public MonitoringController(MeterRegistry registry, DatabaseReadinessCheck readinessCheck,
                                @Value("${spring.datasource.hikari.pool-name}") String poolName) {
        this.registry = registry;
        this.readinessCheck = readinessCheck;
        this.poolName = poolName;
    }

    @GetMapping("/monitoring")
    public MonitoringSnapshot monitoring() {
        boolean databaseUp = readinessCheck.isDatabaseReachable();
        return new MonitoringSnapshot(
                databaseUp ? "UP" : "DEGRADED",
                Instant.now(),
                (long) registry.get("process.uptime").timeGauge().value(TimeUnit.SECONDS),
                new MonitoringSnapshot.Database(databaseUp, new MonitoringSnapshot.Pool(
                        poolGauge("hikaricp.connections.active"),
                        poolGauge("hikaricp.connections.idle"),
                        poolGauge("hikaricp.connections.pending"),
                        poolGauge("hikaricp.connections.max"))),
                new MonitoringSnapshot.Admission(
                        gauge("admission.in_flight"),
                        gauge("admission.waiting"),
                        gauge("admission.max_in_flight")),
                new MonitoringSnapshot.Jvm(
                        sumPositive(registry.find("jvm.memory.used").tag("area", "heap").gauges()),
                        sumPositive(registry.find("jvm.memory.max").tag("area", "heap").gauges()),
                        gauge("jvm.threads.live")),
                httpResponsesByStatus(),
                new MonitoringSnapshot.Reservations(
                        count(registry.find("reservations.confirmed").counters()),
                        count(registry.find("reservations.cancelled").counters()),
                        countsByTag(registry.find("reservations.declined").counters(), "reason")),
                shows());
    }

    private Long gauge(String name) {
        return toLong(registry.find(name).gauge());
    }

    private Long poolGauge(String name) {
        return toLong(registry.find(name).tag("pool", poolName).gauge());
    }

    private static Long toLong(Gauge gauge) {
        if (gauge == null || !Double.isFinite(gauge.value())) {
            return null;
        }
        return (long) gauge.value();
    }

    private static long sumPositive(Collection<Gauge> gauges) {
        // Memory pools without a limit report -1.
        return (long) gauges.stream().mapToDouble(Gauge::value).filter(v -> v > 0).sum();
    }

    private static long count(Collection<Counter> counters) {
        return (long) counters.stream().mapToDouble(Counter::count).sum();
    }

    private static Map<String, Long> countsByTag(Collection<Counter> counters, String tag) {
        Map<String, Long> counts = new TreeMap<>();
        for (Counter counter : counters) {
            counts.merge(counter.getId().getTag(tag), (long) counter.count(), Long::sum);
        }
        return counts;
    }

    /** Responses per status code, API endpoints only (health, metrics and monitoring excluded). */
    private Map<String, Long> httpResponsesByStatus() {
        Predicate<Meter> api = meter -> {
            String uri = meter.getId().getTag("uri");
            return uri == null || !(uri.startsWith("/health") || uri.equals("/metrics") || uri.equals("/monitoring"));
        };
        Map<String, Long> counts = new TreeMap<>();
        for (Timer timer : registry.find("http.server.requests").timers()) {
            if (api.test(timer)) {
                counts.merge(timer.getId().getTag("status"), timer.count(), Long::sum);
            }
        }
        return counts;
    }

    /** Seat counts per show from the seat gauges (one cached grouped query; null if the DB is down). */
    private List<MonitoringSnapshot.Show> shows() {
        Map<String, Long[]> byShow = new TreeMap<>();
        String[] statuses = {"available", "held", "confirmed"};
        for (int i = 0; i < statuses.length; i++) {
            for (Gauge gauge : registry.find("seats." + statuses[i]).gauges()) {
                byShow.computeIfAbsent(gauge.getId().getTag("show_id"), id -> new Long[3])[i] = toLong(gauge);
            }
        }
        return byShow.entrySet().stream()
                .map(e -> new MonitoringSnapshot.Show(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2]))
                .toList();
    }
}

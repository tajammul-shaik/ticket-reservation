package com.ts.ticketreservation.monitoring;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Body of GET /monitoring. Gauge-backed numbers are null when their source cannot be read
 * (for example seat counts while the database is down), never a stale or made-up value.
 */
public record MonitoringSnapshot(
        String status,
        Instant generatedAt,
        long uptimeSeconds,
        Database database,
        Admission admission,
        Jvm jvm,
        Map<String, Long> httpResponsesByStatus,
        Reservations reservations,
        List<Show> shows) {

    public record Database(boolean reachable, Pool pool) {
    }

    public record Pool(Long active, Long idle, Long pending, Long max) {
    }

    public record Admission(Long inFlight, Long waiting, Long maxInFlight) {
    }

    public record Jvm(long heapUsedBytes, long heapMaxBytes, Long liveThreads) {
    }

    public record Reservations(long confirmed, long cancelled, Map<String, Long> declined) {
    }

    public record Show(String showId, Long available, Long held, Long confirmed) {
    }
}

package com.ts.ticketreservation.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Reservation counters from BRD section 13. Callers increment only after the transaction has
 * committed (or rolled back, for declines), so counters never count work that did not happen.
 * Counters are per instance and reset on restart.
 */
@Component
public class ReservationMetrics {

    public static final String REASON_SEAT_TAKEN = "seat_taken";
    public static final String REASON_PER_USER_LIMIT = "per_user_limit";
    public static final String REASON_IDEMPOTENT_REPLAY = "idempotent_replay";
    public static final String REASON_IDEMPOTENCY_KEY_CONFLICT = "idempotency_key_conflict";
    public static final String REASON_OVERLOADED = "overloaded";

    private static final List<String> DECLINE_REASONS = List.of(
            REASON_SEAT_TAKEN, REASON_PER_USER_LIMIT, REASON_IDEMPOTENT_REPLAY,
            REASON_IDEMPOTENCY_KEY_CONFLICT, REASON_OVERLOADED);

    private final MeterRegistry registry;

    public ReservationMetrics(MeterRegistry registry) {
        this.registry = registry;
        // Register every reason up front so each series exists (at 0) from the first scrape.
        DECLINE_REASONS.forEach(this::declinedCounter);
    }

    /** reservations_confirmed_total{show_id} */
    public void confirmed(String showId) {
        Counter.builder("reservations.confirmed")
                .description("Reservations committed")
                .tag("show_id", showId)
                .register(registry)
                .increment();
    }

    /** reservations_declined_total{reason} */
    public void declined(String reason) {
        declinedCounter(reason).increment();
    }

    /** reservations_cancelled_total{show_id} */
    public void cancelled(String showId) {
        Counter.builder("reservations.cancelled")
                .description("Successful cancels")
                .tag("show_id", showId)
                .register(registry)
                .increment();
    }

    private Counter declinedCounter(String reason) {
        return Counter.builder("reservations.declined")
                .description("Reserve requests declined, by reason")
                .tag("reason", reason)
                .register(registry);
    }
}

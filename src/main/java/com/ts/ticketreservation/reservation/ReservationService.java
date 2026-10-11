package com.ts.ticketreservation.reservation;

import com.ts.ticketreservation.auth.AuthenticatedUser;
import com.ts.ticketreservation.common.error.ApiException;
import com.ts.ticketreservation.common.error.ErrorCode;
import com.ts.ticketreservation.metrics.ReservationMetrics;
import com.ts.ticketreservation.show.ShowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * FR-03 reserve. One database transaction per attempt; every exit is a commit (201) or a
 * rollback with a clean 4xx. The steps run in the BRD order: idempotency key, per-user limit,
 * seat locks, conditional take. The limit is checked before seats are locked, so once the
 * take succeeds nothing after it can fail.
 * <p>
 * Deliberately not @Transactional: the duplicate-key replay lookup and the deadlock retries
 * must run outside the failed transaction, after it has rolled back.
 */
@Service
public class ReservationService {

    static final int MAX_SEATS_PER_REQUEST = 100;
    static final int MAX_LOCK_RETRIES = 2;

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    // Decline outcomes that are counted in reservations_declined_total, by error code.
    private static final Map<ErrorCode, String> DECLINE_REASONS = Map.of(
            ErrorCode.SEAT_TAKEN, ReservationMetrics.REASON_SEAT_TAKEN,
            ErrorCode.PER_USER_LIMIT, ReservationMetrics.REASON_PER_USER_LIMIT,
            ErrorCode.IDEMPOTENCY_KEY_CONFLICT, ReservationMetrics.REASON_IDEMPOTENCY_KEY_CONFLICT,
            ErrorCode.OVERLOADED, ReservationMetrics.REASON_OVERLOADED);

    private final ReservationRepository reservationRepository;
    private final ShowRepository showRepository;
    private final TransactionTemplate transactionTemplate;
    private final ReservationMetrics metrics;

    public ReservationService(ReservationRepository reservationRepository, ShowRepository showRepository,
                              TransactionTemplate transactionTemplate, ReservationMetrics metrics) {
        this.reservationRepository = reservationRepository;
        this.showRepository = showRepository;
        this.transactionTemplate = transactionTemplate;
        this.metrics = metrics;
    }

    /** The reservation, and whether it is a replay of an earlier request with the same key. */
    public record ReserveOutcome(ReservationResponse reservation, boolean replay) {
    }

    /**
     * Metrics and the outcome log line are recorded here, after the transaction has committed or
     * rolled back, never inside it.
     */
    public ReserveOutcome reserve(AuthenticatedUser user, String showId, List<String> seats, String idempotencyKey) {
        try {
            ReserveOutcome outcome = reserveAndReplay(user, showId, seats, idempotencyKey);
            if (outcome.replay()) {
                metrics.declined(ReservationMetrics.REASON_IDEMPOTENT_REPLAY);
                logOutcome(showId, outcome.reservation().seats(), "replay", ReservationMetrics.REASON_IDEMPOTENT_REPLAY);
            } else {
                metrics.confirmed(showId);
                logOutcome(showId, outcome.reservation().seats(), "confirmed", null);
            }
            return outcome;
        } catch (ApiException e) {
            String reason = DECLINE_REASONS.get(e.errorCode());
            if (reason != null) {
                metrics.declined(reason);
            }
            logOutcome(showId, seats, "declined", e.errorCode().code());
            throw e;
        } catch (CannotGetJdbcConnectionException | CannotCreateTransactionException e) {
            // No pooled connection within the wait timeout: shed, do not fail (R-02). Through
            // TransactionTemplate a pool timeout arrives as CannotCreateTransactionException;
            // missing it turned pool exhaustion into 500s in the live burst.
            metrics.declined(ReservationMetrics.REASON_OVERLOADED);
            logOutcome(showId, seats, "declined", ReservationMetrics.REASON_OVERLOADED);
            throw new ApiException(ErrorCode.OVERLOADED, "service is busy, retry shortly");
        }
    }

    private ReserveOutcome reserveAndReplay(AuthenticatedUser user, String showId, List<String> seats,
                                            String idempotencyKey) {
        List<String> sortedSeats = sortedUnique(seats);
        String requestHash = requestHash(showId, sortedSeats);

        try {
            ReservationResponse created = inTransactionWithLockRetries("reserve",
                    () -> reserveOnce(user.userId(), showId, sortedSeats, idempotencyKey, requestHash));
            return new ReserveOutcome(created, false);
        } catch (DuplicateKeyException e) {
            // Key already used by this user and committed (1062 is only raised once the other
            // insert commits). This transaction has rolled back; answer from the stored row.
            return replay(user.userId(), idempotencyKey, requestHash);
        }
    }

    private static void logOutcome(String showId, List<String> seats, String outcome, String reason) {
        log.info("reserve {} {} {} {}", kv("show_id", showId), kv("seats", seats), kv("outcome", outcome),
                kv("reason", reason));
    }

    /** The reservation after the call, and whether this call is the one that cancelled it. */
    public record CancelOutcome(ReservationResponse reservation, boolean cancelledNow) {
    }

    /**
     * FR-04. Owner only; another user's reservation is a 404. Cancelling twice returns the same
     * body. Locks are taken in the same order as reserve (reservation row, user count, seats),
     * so a user cancelling and re-booking the same seat at once cannot deadlock with itself.
     */
    public CancelOutcome cancel(AuthenticatedUser user, String reservationId) {
        CancelOutcome outcome = inTransactionWithLockRetries("cancel", () -> cancelOnce(user.userId(), reservationId));
        // Committed: count it once, only for the call that actually cancelled.
        if (outcome.cancelledNow()) {
            metrics.cancelled(outcome.reservation().showId());
        }
        log.info("cancel {} {} {}", kv("reservation_id", reservationId),
                kv("show_id", outcome.reservation().showId()),
                kv("outcome", outcome.cancelledNow() ? "cancelled" : "replay"));
        return outcome;
    }

    private CancelOutcome cancelOnce(String userId, String reservationId) {
        int flipped = reservationRepository.markCancelled(reservationId, userId);
        var stored = reservationRepository.findByIdAndUser(reservationId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESERVATION_NOT_FOUND,
                        "reservation " + reservationId + " not found"));
        if (flipped == 0) {
            // Already cancelled earlier: idempotent replay, nothing to release.
            return new CancelOutcome(toResponse(stored), false);
        }

        int seatCount = stored.seats().size();
        if (reservationRepository.subtractFromUserCount(stored.showId(), userId, seatCount) != 1) {
            throw new IllegalStateException("user_show_counts out of step for reservation " + reservationId);
        }
        int released = reservationRepository.releaseSeats(reservationId);
        if (released != seatCount) {
            // Seats only leave a confirmed reservation through this method, so a mismatch is a bug.
            // Throwing rolls the whole cancel back rather than committing a broken invariant.
            throw new IllegalStateException(
                    "reservation " + reservationId + " owns " + released + " of " + seatCount + " seats");
        }
        return new CancelOutcome(toResponse(stored), true);
    }

    /**
     * Runs one transaction, retrying up to {@link #MAX_LOCK_RETRIES} times on MySQL 1213
     * (deadlock) or 1205 (lock wait timeout) (NFR-02). MySQL rolls back the whole transaction on
     * a deadlock but only the failed statement on a lock wait timeout; either way the exception
     * leaves the TransactionTemplate, which rolls the transaction back before the retry.
     */
    private <T> T inTransactionWithLockRetries(String operation, Supplier<T> work) {
        for (int attempt = 0; ; attempt++) {
            try {
                return transactionTemplate.execute(status -> work.get());
            } catch (PessimisticLockingFailureException e) {
                log.warn("Lock conflict on {} attempt {}: {}", operation, attempt + 1, e.getClass().getSimpleName());
                if (attempt >= MAX_LOCK_RETRIES) {
                    throw new ApiException(ErrorCode.OVERLOADED, "too much contention, retry shortly");
                }
            }
        }
    }

    private ReservationResponse reserveOnce(String userId, String showId, List<String> sortedSeats,
                                            String idempotencyKey, String requestHash) {
        var show = showRepository.findShow(showId)
                .orElseThrow(() -> new ApiException(ErrorCode.SHOW_NOT_FOUND, "show " + showId + " not found"));

        // BR-14: amount is computed here, never taken from the client.
        long amountPaise = Math.multiplyExact(show.pricePaise(), sortedSeats.size());
        var reservation = new ReservationRepository.StoredReservation(
                UUID.randomUUID().toString(), showId, userId, requestHash, sortedSeats, amountPaise, "confirmed");

        // 1. Claim the idempotency key (BR-05).
        reservationRepository.insertReservation(reservation, idempotencyKey);

        // 2. Per-user limit (BR-03, BR-04).
        if (!reservationRepository.tryAddToUserCount(showId, userId, sortedSeats.size(), show.perUserLimit())) {
            throw new ApiException(ErrorCode.PER_USER_LIMIT,
                    "would exceed the limit of " + show.perUserLimit() + " seats per user for this show");
        }

        // 3a. Lock seat rows in ascending label order (NFR-02).
        List<String> existing = reservationRepository.lockSeats(showId, sortedSeats);
        if (existing.size() != sortedSeats.size()) {
            var missing = new HashSet<>(sortedSeats);
            existing.forEach(missing::remove);
            throw new ApiException(ErrorCode.INVALID_SEAT, "seats not in this show: " + missing.stream().sorted().toList());
        }

        // 3b. Conditional take; all-or-nothing (BR-01, BR-02, BR-07).
        int taken = reservationRepository.takeSeats(showId, sortedSeats, userId, reservation.id());
        if (taken != sortedSeats.size()) {
            throw new ApiException(ErrorCode.SEAT_TAKEN, "one or more requested seats are not available");
        }

        return toResponse(reservation);
    }

    /** Same key: same body is a replay (200), a different body is a conflict (BR-06). */
    private ReserveOutcome replay(String userId, String idempotencyKey, String requestHash) {
        return reservationRepository.findByUserAndKey(userId, idempotencyKey)
                .map(stored -> {
                    if (!stored.requestHash().equals(requestHash)) {
                        throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                                "idempotency key already used with a different request");
                    }
                    return new ReserveOutcome(toResponse(stored), true);
                })
                .orElseThrow(() -> new IllegalStateException(
                        "duplicate idempotency key but no committed reservation for user " + userId));
    }

    private static List<String> sortedUnique(List<String> seats) {
        if (new HashSet<>(seats).size() != seats.size()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "duplicate seats in request");
        }
        return seats.stream().sorted().toList();
    }

    /** SHA-256 hex of show_id + ":" + sorted seat labels joined by "," (BRD section 10). */
    static String requestHash(String showId, List<String> sortedSeats) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((showId + ":" + String.join(",", sortedSeats)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static ReservationResponse toResponse(ReservationRepository.StoredReservation stored) {
        return new ReservationResponse(stored.id(), stored.showId(), stored.userId(), stored.seats(),
                stored.amountPaise(), stored.status());
    }
}

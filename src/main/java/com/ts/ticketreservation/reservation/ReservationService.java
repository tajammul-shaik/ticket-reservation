package com.ts.ticketreservation.reservation;

import com.ts.ticketreservation.auth.AuthenticatedUser;
import com.ts.ticketreservation.common.error.ApiException;
import com.ts.ticketreservation.common.error.ErrorCode;
import com.ts.ticketreservation.show.ShowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

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

    private final ReservationRepository reservationRepository;
    private final ShowRepository showRepository;
    private final TransactionTemplate transactionTemplate;

    public ReservationService(ReservationRepository reservationRepository, ShowRepository showRepository,
                              TransactionTemplate transactionTemplate) {
        this.reservationRepository = reservationRepository;
        this.showRepository = showRepository;
        this.transactionTemplate = transactionTemplate;
    }

    /** The reservation, and whether it is a replay of an earlier request with the same key. */
    public record ReserveOutcome(ReservationResponse reservation, boolean replay) {
    }

    public ReserveOutcome reserve(AuthenticatedUser user, String showId, List<String> seats, String idempotencyKey) {
        List<String> sortedSeats = sortedUnique(seats);
        String requestHash = requestHash(showId, sortedSeats);

        for (int attempt = 0; ; attempt++) {
            try {
                ReservationResponse created = transactionTemplate.execute(
                        status -> reserveOnce(user.userId(), showId, sortedSeats, idempotencyKey, requestHash));
                return new ReserveOutcome(created, false);
            } catch (DuplicateKeyException e) {
                // Key already used and committed by this user. The transaction has rolled back.
                ReserveOutcome replay = replay(user.userId(), idempotencyKey, requestHash);
                if (replay != null) {
                    return replay;
                }
                // Not visible yet; treat like a transient conflict and try again.
            } catch (PessimisticLockingFailureException e) {
                // MySQL 1213 (deadlock) or 1205 (lock wait timeout); the transaction rolled back.
                log.warn("Lock conflict on reserve attempt {} for show {}", attempt + 1, showId);
            }
            if (attempt >= MAX_LOCK_RETRIES) {
                throw new ApiException(ErrorCode.OVERLOADED, "too much contention, retry shortly");
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
                .orElse(null);
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

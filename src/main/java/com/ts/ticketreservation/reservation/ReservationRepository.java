package com.ts.ticketreservation.reservation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * The atomic SQL statements of the reserve flow (BRD section 10). Every decision is a single
 * conditional statement whose update count is checked; MySQL has no RETURNING.
 */
@Repository
public class ReservationRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper;

    public ReservationRepository(JdbcTemplate jdbcTemplate, JsonMapper jsonMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonMapper = jsonMapper;
    }

    public record StoredReservation(
            String id, String showId, String userId, String requestHash,
            List<String> seats, long amountPaise, String status) {
    }

    /**
     * Step 1: claim the idempotency key. Throws DuplicateKeyException (MySQL 1062) if this user
     * already used the key; if another transaction holds the same key uncommitted, this blocks
     * on the unique index until that transaction ends.
     */
    public void insertReservation(StoredReservation reservation, String idempotencyKey) {
        jdbcTemplate.update("""
                        INSERT INTO reservations
                            (id, show_id, user_id, idempotency_key, request_hash, seats, amount_paise, status)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 'confirmed')
                        """,
                reservation.id(), reservation.showId(), reservation.userId(), idempotencyKey,
                reservation.requestHash(), jsonMapper.writeValueAsString(reservation.seats()),
                reservation.amountPaise());
    }

    /** Replay lookup after a duplicate key; READ COMMITTED means the winner's row is visible. */
    public Optional<StoredReservation> findByUserAndKey(String userId, String idempotencyKey) {
        return jdbcTemplate.query("""
                                SELECT id, show_id, user_id, request_hash, seats, amount_paise, status
                                FROM reservations
                                WHERE user_id = ? AND idempotency_key = ?
                                """,
                        this::mapReservation, userId, idempotencyKey)
                .stream()
                .findFirst();
    }

    /** Owner-scoped lookup: another user's reservation is indistinguishable from a missing one. */
    public Optional<StoredReservation> findByIdAndUser(String reservationId, String userId) {
        return jdbcTemplate.query("""
                                SELECT id, show_id, user_id, request_hash, seats, amount_paise, status
                                FROM reservations
                                WHERE id = ? AND user_id = ?
                                """,
                        this::mapReservation, reservationId, userId)
                .stream()
                .findFirst();
    }

    /** Cancel step 1: flips a confirmed reservation owned by this user; 0 rows if not. */
    public int markCancelled(String reservationId, String userId) {
        return jdbcTemplate.update("""
                        UPDATE reservations SET status = 'cancelled'
                        WHERE id = ? AND user_id = ? AND status = 'confirmed'
                        """,
                reservationId, userId);
    }

    /** Cancel step 2: return the user's limit capacity (BR-11). */
    public int subtractFromUserCount(String showId, String userId, int seatCount) {
        return jdbcTemplate.update("""
                        UPDATE user_show_counts SET held = held - ?
                        WHERE show_id = ? AND user_id = ? AND held >= ?
                        """,
                seatCount, showId, userId, seatCount);
    }

    /**
     * Cancel step 3: free only seats still owned by this reservation (BR-10), so a seat that has
     * since gone to someone else can never be resurrected.
     */
    public int releaseSeats(String reservationId) {
        return jdbcTemplate.update("""
                        UPDATE seats SET status = 'available', holder_user_id = NULL, reservation_id = NULL
                        WHERE reservation_id = ? AND status = 'confirmed'
                        """,
                reservationId);
    }

    private StoredReservation mapReservation(ResultSet rs, int rowNum) throws SQLException {
        return new StoredReservation(
                rs.getString("id"),
                rs.getString("show_id"),
                rs.getString("user_id"),
                rs.getString("request_hash"),
                jsonMapper.readValue(rs.getString("seats"), STRING_LIST),
                rs.getLong("amount_paise"),
                rs.getString("status"));
    }

    /**
     * Step 2: reserve capacity against the per-user limit. The row is created if missing, then
     * a guarded increment succeeds only if the new total stays within the limit. The row lock
     * serialises one user's parallel requests for the show.
     *
     * @return false if the request would exceed the limit
     */
    public boolean tryAddToUserCount(String showId, String userId, int seatCount, int perUserLimit) {
        jdbcTemplate.update("""
                        INSERT INTO user_show_counts (show_id, user_id, held) VALUES (?, ?, 0)
                        ON DUPLICATE KEY UPDATE held = held
                        """,
                showId, userId);
        int updated = jdbcTemplate.update("""
                        UPDATE user_show_counts SET held = held + ?
                        WHERE show_id = ? AND user_id = ? AND held + ? <= ?
                        """,
                seatCount, showId, userId, seatCount, perUserLimit);
        return updated == 1;
    }

    /**
     * Step 3a: lock the requested seat rows. InnoDB takes the locks while walking the primary
     * key (show_id, seat_label) in order, so every transaction locks overlapping seats in the
     * same ascending order and multi-seat requests cannot deadlock each other.
     *
     * @return the labels that exist in this show
     */
    public List<String> lockSeats(String showId, List<String> sortedLabels) {
        List<Object> args = new ArrayList<>(sortedLabels.size() + 1);
        args.add(showId);
        args.addAll(sortedLabels);
        return jdbcTemplate.queryForList(
                "SELECT seat_label FROM seats WHERE show_id = ? AND seat_label IN (" + placeholders(sortedLabels.size()) + ")"
                        + " ORDER BY seat_label FOR UPDATE",
                String.class,
                args.toArray());
    }

    /**
     * Step 3b: the atomic take. Only rows still 'available' change.
     *
     * @return how many seats were taken; fewer than requested means at least one was not available
     */
    public int takeSeats(String showId, List<String> sortedLabels, String userId, String reservationId) {
        List<Object> args = new ArrayList<>(sortedLabels.size() + 3);
        args.add(userId);
        args.add(reservationId);
        args.add(showId);
        args.addAll(sortedLabels);
        return jdbcTemplate.update(
                "UPDATE seats SET status = 'confirmed', holder_user_id = ?, reservation_id = ?"
                        + " WHERE show_id = ? AND seat_label IN (" + placeholders(sortedLabels.size()) + ")"
                        + " AND status = 'available'",
                args.toArray());
    }

    private static String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }
}

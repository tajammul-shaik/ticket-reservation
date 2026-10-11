package com.ts.ticketreservation.show;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Hand-written SQL for shows and their seat inventory. */
@Repository
public class ShowRepository {

    private static final int SEAT_INSERT_BATCH_SIZE = 500;

    private final JdbcTemplate jdbcTemplate;

    public ShowRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record ShowRow(String id, String name, long pricePaise, int perUserLimit, int totalSeats) {
    }

    public void insertShow(ShowRow show) {
        jdbcTemplate.update("""
                        INSERT INTO shows (id, name, price_paise, per_user_limit, total_seats)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                show.id(), show.name(), show.pricePaise(), show.perUserLimit(), show.totalSeats());
    }

    public void insertSeats(String showId, List<String> seatLabels) {
        jdbcTemplate.batchUpdate(
                "INSERT INTO seats (show_id, seat_label) VALUES (?, ?)",
                seatLabels,
                SEAT_INSERT_BATCH_SIZE,
                (ps, label) -> {
                    ps.setString(1, showId);
                    ps.setString(2, label);
                });
    }

    public Optional<ShowRow> findShow(String showId) {
        return jdbcTemplate.query("""
                                SELECT id, name, price_paise, per_user_limit, total_seats
                                FROM shows
                                WHERE id = ?
                                """,
                        (rs, rowNum) -> new ShowRow(
                                rs.getString("id"),
                                rs.getString("name"),
                                rs.getLong("price_paise"),
                                rs.getInt("per_user_limit"),
                                rs.getInt("total_seats")),
                        showId)
                .stream()
                .findFirst();
    }

    /**
     * Every seat with its status, in one statement. Counts are derived from this same result,
     * so the seat list and the counts always describe the same snapshot.
     */
    public List<ShowResponse.SeatView> findSeats(String showId) {
        return jdbcTemplate.query("""
                        SELECT seat_label, status
                        FROM seats
                        WHERE show_id = ?
                        ORDER BY seat_label
                        """,
                (rs, rowNum) -> new ShowResponse.SeatView(rs.getString("seat_label"), rs.getString("status")),
                showId);
    }
}

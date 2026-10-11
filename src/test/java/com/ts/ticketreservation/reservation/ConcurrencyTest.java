package com.ts.ticketreservation.reservation;

import com.ts.ticketreservation.IntegrationTest;
import com.ts.ticketreservation.auth.JwtService;
import com.ts.ticketreservation.auth.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BRD tests T-01 to T-07: real parallel HTTP requests against real MySQL. Every request waits
 * on a start gate so they hit the server together. After each test the reconciliation
 * invariants (T-10) are checked against the database.
 */
class ConcurrencyTest extends IntegrationTest {

    @Autowired
    private JwtService jwtService;

    private final List<String> showsToCheck = new ArrayList<>();

    @AfterEach
    void invariantsHold() {
        showsToCheck.forEach(this::assertReconciles);
    }

    /** T-01: 500 users race for one seat; exactly one wins, everyone else gets a clean 409. */
    @Test
    void hotSeatHasExactlyOneWinner() {
        String showId = createShow(List.of("A11", "A12", "A13"), 4);
        String run = runId();

        List<Response> responses = race(500, i -> {
            String token = token("hot-" + run + "-" + i);
            return () -> reserve(showId, token, "[\"A12\"]", "k");
        });

        Map<Integer, Long> byStatus = countByStatus(responses);
        assertThat(byStatus).containsOnlyKeys(201, 409);
        assertThat(byStatus.get(201)).isEqualTo(1);
        assertThat(byStatus.get(409)).isEqualTo(499);
        assertThat(errors(responses)).containsOnly("seat_taken");
        assertThat(seatStatus(showId, "A12")).isEqualTo("confirmed");
        assertThat(confirmedReservations(showId)).isEqualTo(1);
    }

    /** T-02: one user fires 10 parallel single-seat requests with limit 4; never more than 4. */
    @Test
    void perUserLimitHoldsUnderParallelRequests() {
        List<String> seats = IntStream.rangeClosed(1, 10).mapToObj(i -> "S" + i).toList();
        String showId = createShow(seats, 4);
        String token = token("greedy-" + runId());

        List<Response> responses = race(10, i -> () -> reserve(showId, token, "[\"S" + (i + 1) + "\"]", "k" + i));

        Map<Integer, Long> byStatus = countByStatus(responses);
        assertThat(byStatus.get(201)).isEqualTo(4);
        assertThat(byStatus.get(409)).isEqualTo(6);
        assertThat(errors(responses)).containsOnly("per_user_limit");
        assertThat(confirmedSeats(showId)).isEqualTo(4);
    }

    /** T-03: 20 parallel calls with one key and body produce one reservation. */
    @Test
    void parallelRetriesWithOneKeyReserveOnce() {
        String showId = createShow(List.of("A1", "A2"), 4);
        String token = token("retrier-" + runId());

        List<Response> responses = race(20, i -> () -> reserve(showId, token, "[\"A1\"]", "same-key"));

        Map<Integer, Long> byStatus = countByStatus(responses);
        assertThat(byStatus).containsOnlyKeys(201, 200);
        assertThat(byStatus.get(201)).isEqualTo(1);
        assertThat(byStatus.get(200)).isEqualTo(19);
        Set<String> reservationIds = responses.stream()
                .map(r -> r.body().get("reservation_id").asString())
                .collect(Collectors.toSet());
        assertThat(reservationIds).hasSize(1);
        assertThat(confirmedReservations(showId)).isEqualTo(1);
    }

    /** T-04 under concurrency: one key, two different bodies at once; one wins, one conflicts. */
    @Test
    void parallelKeyReuseWithDifferentBodiesConflicts() {
        String showId = createShow(List.of("A1", "A2"), 4);
        String token = token("reuser-" + runId());

        List<Response> responses = race(2, i -> () -> reserve(showId, token, i == 0 ? "[\"A1\"]" : "[\"A2\"]", "k"));

        assertThat(countByStatus(responses)).containsEntry(201, 1L).containsEntry(409, 1L);
        assertThat(errors(responses)).containsOnly("idempotency_key_conflict");
        assertThat(confirmedSeats(showId)).isEqualTo(1);
    }

    /**
     * T-05 under concurrency: many users grab overlapping seat pairs. No reservation ends up
     * partial: every winning reservation owns all of its seats, and losers own none.
     */
    @Test
    void overlappingMultiSeatRequestsAreAllOrNothing() {
        List<String> seats = IntStream.rangeClosed(1, 8).mapToObj(i -> "P" + i).toList();
        String showId = createShow(seats, 4);
        String run = runId();

        List<Response> responses = race(100, i -> {
            String token = token("pair-" + run + "-" + i);
            String first = seats.get(i % 7);
            String second = seats.get(i % 7 + 1);
            return () -> reserve(showId, token, "[\"" + first + "\",\"" + second + "\"]", "k");
        });

        assertThat(countByStatus(responses)).containsOnlyKeys(201, 409);
        for (Response response : responses) {
            if (response.status() == 201) {
                String reservationId = response.body().get("reservation_id").asString();
                Integer owned = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM seats WHERE reservation_id = ? AND status = 'confirmed'",
                        Integer.class, reservationId);
                assertThat(owned).isEqualTo(2);
            }
        }
        assertThat(confirmedSeats(showId)).isEqualTo(2L * countByStatus(responses).get(201));
    }

    /**
     * T-06: [A1, A2] and [A2, A1] in parallel, 200 rounds. Seats are locked in sorted order,
     * so there is no deadlock: every round has one winner and one clean 409, never a 5xx or 429.
     */
    @Test
    void oppositeSeatOrderNeverDeadlocks() {
        String run = runId();
        String alice = token("dl-a-" + run);
        String bob = token("dl-b-" + run);
        List<Response> all = new ArrayList<>();

        for (int round = 0; round < 200; round++) {
            String showId = createShow(List.of("A1", "A2"), 4);
            String key = "round-" + round;
            List<Response> responses = race(2, i -> i == 0
                    ? () -> reserve(showId, alice, "[\"A1\",\"A2\"]", key)
                    : () -> reserve(showId, bob, "[\"A2\",\"A1\"]", key));
            assertThat(countByStatus(responses)).as("round %d", round)
                    .containsEntry(201, 1L).containsEntry(409, 1L);
            all.addAll(responses);
        }

        assertThat(all).allSatisfy(r -> assertThat(r.status()).isIn(201, 409));
    }

    /** T-07: a user_id in the body is ignored; the reservation belongs to the token's user. */
    @Test
    void bodyUserIdCannotSpoofIdentity() {
        String showId = createShow(List.of("A1"), 4);
        String run = runId();
        String attacker = "attacker-" + run;
        String victim = "victim-" + run;

        var response = post("/shows/" + showId + "/reserve", token(attacker),
                "{\"seats\":[\"A1\"],\"idempotency_key\":\"k\",\"user_id\":\"" + victim + "\"}");

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.body().get("user_id").asString()).isEqualTo(attacker);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT holder_user_id FROM seats WHERE show_id = ? AND seat_label = 'A1'", String.class, showId))
                .isEqualTo(attacker);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservations WHERE user_id = ?", Integer.class, victim)).isZero();
    }

    // --- helpers ---------------------------------------------------------------------------

    /** Builds n requests, releases them together through a start gate, and returns all responses. */
    private List<Response> race(int n, Function<Integer, Callable<Response>> requestFactory) {
        List<Callable<Response>> requests = IntStream.range(0, n).mapToObj(requestFactory::apply).toList();
        CountDownLatch startGate = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Response>> futures = requests.stream()
                    .map(request -> executor.submit(() -> {
                        startGate.await();
                        return request.call();
                    }))
                    .toList();
            startGate.countDown();
            List<Response> responses = new ArrayList<>(n);
            for (Future<Response> future : futures) {
                responses.add(future.get());
            }
            return responses;
        } catch (Exception e) {
            throw new IllegalStateException("race failed", e);
        }
    }

    private String createShow(List<String> seats, int perUserLimit) {
        String seatsJson = seats.stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(","));
        String showId = post("/shows", adminToken(),
                "{\"name\":\"race\",\"seats\":[" + seatsJson + "],\"price_paise\":1000,\"per_user_limit\":" + perUserLimit + "}")
                .body().get("id").asString();
        showsToCheck.add(showId);
        return showId;
    }

    private String token(String userId) {
        return jwtService.issue(userId, Role.USER);
    }

    private Response reserve(String showId, String token, String seatsJson, String key) {
        return post("/shows/" + showId + "/reserve", token,
                "{\"seats\":" + seatsJson + ",\"idempotency_key\":\"" + key + "\"}");
    }

    private static String runId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static Map<Integer, Long> countByStatus(List<Response> responses) {
        return responses.stream().collect(Collectors.groupingBy(Response::status, Collectors.counting()));
    }

    private static Set<String> errors(List<Response> responses) {
        Set<String> errors = new HashSet<>();
        responses.stream().filter(r -> r.status() >= 400).forEach(r -> errors.add(r.body().get("error").asString()));
        return errors;
    }

    private String seatStatus(String showId, String label) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM seats WHERE show_id = ? AND seat_label = ?", String.class, showId, label);
    }

    private long confirmedReservations(String showId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservations WHERE show_id = ? AND status = 'confirmed'", Long.class, showId);
    }

    private long confirmedSeats(String showId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM seats WHERE show_id = ? AND status = 'confirmed'", Long.class, showId);
    }

    /**
     * T-10: available + held + confirmed == total_seats; every confirmed seat belongs to a
     * confirmed reservation; and each user's held count equals the seats they actually hold.
     */
    private void assertReconciles(String showId) {
        var show = get("/shows/" + showId, adminToken()).body();
        assertThat(show.get("invariant_ok").asBoolean()).as("invariant_ok for %s", showId).isTrue();

        Integer orphanSeats = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM seats s
                LEFT JOIN reservations r ON r.id = s.reservation_id AND r.status = 'confirmed'
                WHERE s.show_id = ? AND s.status = 'confirmed' AND r.id IS NULL
                """, Integer.class, showId);
        assertThat(orphanSeats).as("confirmed seats without a confirmed reservation").isZero();

        Integer countMismatches = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM user_show_counts c
                WHERE c.show_id = ?
                  AND c.held <> (SELECT COUNT(*) FROM seats s
                                 WHERE s.show_id = c.show_id AND s.holder_user_id = c.user_id
                                   AND s.status IN ('held', 'confirmed'))
                """, Integer.class, showId);
        assertThat(countMismatches).as("user_show_counts rows that disagree with seats").isZero();
    }
}

package com.ts.ticketreservation.reservation;

import com.ts.ticketreservation.IntegrationTest;
import com.ts.ticketreservation.auth.JwtService;
import com.ts.ticketreservation.auth.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** FR-04, including T-08 (cancel ownership) and T-09 (no resurrection). */
class CancelApiTest extends IntegrationTest {

    @Autowired
    private JwtService jwtService;

    private String showId;
    private String aliceId;
    private String alice;
    private String bob;

    @BeforeEach
    void setUp() {
        showId = post("/shows", adminToken(), """
                {"name":"cancel-test","seats":["A1","A2","A3","A4","A5"],"price_paise":10000,"per_user_limit":4}
                """).body().get("id").asString();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        aliceId = "alice-" + suffix;
        alice = jwtService.issue(aliceId, Role.USER);
        bob = jwtService.issue("bob-" + suffix, Role.USER);
    }

    @AfterEach
    void invariantHolds() {
        assertThat(get("/shows/" + showId, alice).body().get("invariant_ok").asBoolean()).isTrue();
        Integer mismatches = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM user_show_counts c
                WHERE c.show_id = ?
                  AND c.held <> (SELECT COUNT(*) FROM seats s
                                 WHERE s.show_id = c.show_id AND s.holder_user_id = c.user_id)
                """, Integer.class, showId);
        assertThat(mismatches).isZero();
    }

    @Test
    void ownerCancelsAndSeatsAreFreedAtOnce() {
        String reservationId = reserve(alice, "[\"A1\",\"A2\"]", "k1");

        var response = cancel(alice, reservationId);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().get("reservation_id").asString()).isEqualTo(reservationId);
        assertThat(response.body().get("status").asString()).isEqualTo("cancelled");
        assertThat(seatStatus("A1")).isEqualTo("available");
        assertThat(seatStatus("A2")).isEqualTo("available");
        assertThat(heldBy(aliceId)).isZero();
        // BR-11: re-bookable immediately, by anyone.
        assertThat(post("/shows/" + showId + "/reserve", bob, "{\"seats\":[\"A1\"],\"idempotency_key\":\"b\"}").status())
                .isEqualTo(201);
    }

    @Test
    void cancellingTwiceReturnsSameBody() {
        String reservationId = reserve(alice, "[\"A1\"]", "k1");

        var first = cancel(alice, reservationId);
        var second = cancel(alice, reservationId);

        assertThat(second.status()).isEqualTo(200);
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(heldBy(aliceId)).isZero();
    }

    /** T-08. */
    @Test
    void anotherUsersReservationIs404AndUntouched() {
        String reservationId = reserve(alice, "[\"A1\"]", "k1");

        var response = cancel(bob, reservationId);

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.body().get("error").asString()).isEqualTo("reservation_not_found");
        assertThat(seatStatus("A1")).isEqualTo("confirmed");
        assertThat(heldBy(aliceId)).isEqualTo(1);
    }

    @Test
    void unknownReservationIs404() {
        var response = cancel(alice, UUID.randomUUID().toString());

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.body().get("error").asString()).isEqualTo("reservation_not_found");
    }

    /** T-09: a repeated cancel after the seat was re-sold must not free the new owner's seat. */
    @Test
    void cancelAfterResaleDoesNotResurrectSeat() {
        String aliceReservation = reserve(alice, "[\"A1\"]", "k1");
        cancel(alice, aliceReservation);
        String bobReservation = reserve(bob, "[\"A1\"]", "k1");

        var again = cancel(alice, aliceReservation);

        assertThat(again.status()).isEqualTo(200);
        assertThat(seatStatus("A1")).isEqualTo("confirmed");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reservation_id FROM seats WHERE show_id = ? AND seat_label = 'A1'", String.class, showId))
                .isEqualTo(bobReservation);
    }

    @Test
    void cancelRestoresLimitCapacity() {
        String reservationId = reserve(alice, "[\"A1\",\"A2\",\"A3\",\"A4\"]", "k1");
        assertThat(post("/shows/" + showId + "/reserve", alice, "{\"seats\":[\"A5\"],\"idempotency_key\":\"k2\"}").status())
                .isEqualTo(409);

        cancel(alice, reservationId);

        assertThat(post("/shows/" + showId + "/reserve", alice, "{\"seats\":[\"A5\"],\"idempotency_key\":\"k3\"}").status())
                .isEqualTo(201);
    }

    @Test
    void parallelCancelsReleaseOnce() throws Exception {
        String reservationId = reserve(alice, "[\"A1\",\"A2\"]", "k1");

        List<Response> responses = parallel(10, i -> cancel(alice, reservationId));

        assertThat(responses).allSatisfy(r -> assertThat(r.status()).isEqualTo(200));
        assertThat(heldBy(aliceId)).isZero();
        assertThat(seatStatus("A1")).isEqualTo("available");
    }

    /**
     * Same user cancels a reservation and re-books its seat at the same moment, many rounds.
     * Cancel and reserve take locks in the same order, so this never deadlocks or 5xx's.
     */
    @Test
    void concurrentCancelAndRebookStaysConsistent() throws Exception {
        for (int round = 0; round < 50; round++) {
            String reservationId = reserve(alice, "[\"A1\"]", "hold-" + round);
            String rebookKey = "rebook-" + round;

            List<Response> responses = parallel(2, i -> i == 0
                    ? cancel(alice, reservationId)
                    : post("/shows/" + showId + "/reserve", alice,
                            "{\"seats\":[\"A1\"],\"idempotency_key\":\"" + rebookKey + "\"}"));

            assertThat(responses.get(0).status()).as("cancel, round %d", round).isEqualTo(200);
            assertThat(responses.get(1).status()).as("rebook, round %d", round).isIn(201, 409);
            // Leave the seat free for the next round.
            if (responses.get(1).status() == 201) {
                cancel(alice, responses.get(1).body().get("reservation_id").asString());
            }
        }
        assertThat(heldBy(aliceId)).isZero();
    }

    @Test
    void withoutTokenIs401() {
        assertThat(post("/reservations/" + UUID.randomUUID() + "/cancel", null, "").status()).isEqualTo(401);
    }

    private String reserve(String token, String seatsJson, String key) {
        var response = post("/shows/" + showId + "/reserve", token,
                "{\"seats\":" + seatsJson + ",\"idempotency_key\":\"" + key + "\"}");
        assertThat(response.status()).isEqualTo(201);
        return response.body().get("reservation_id").asString();
    }

    private Response cancel(String token, String reservationId) {
        return post("/reservations/" + reservationId + "/cancel", token, "");
    }

    private interface IndexedRequest {
        Response call(int index);
    }

    private static List<Response> parallel(int n, IndexedRequest request) throws Exception {
        CountDownLatch startGate = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Response>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    startGate.await();
                    return request.call(index);
                }));
            }
            startGate.countDown();
            List<Response> responses = new ArrayList<>();
            for (Future<Response> future : futures) {
                responses.add(future.get());
            }
            return responses;
        }
    }

    private String seatStatus(String label) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM seats WHERE show_id = ? AND seat_label = ?", String.class, showId, label);
    }

    private int heldBy(String userId) {
        return jdbcTemplate.queryForList(
                        "SELECT held FROM user_show_counts WHERE show_id = ? AND user_id = ?", Integer.class, showId, userId)
                .stream().findFirst().orElse(0);
    }
}

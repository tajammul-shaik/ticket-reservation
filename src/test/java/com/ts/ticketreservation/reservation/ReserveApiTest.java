package com.ts.ticketreservation.reservation;

import com.ts.ticketreservation.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** FR-03, one request at a time. Concurrent races (T-01 to T-07) are covered separately. */
class ReserveApiTest extends IntegrationTest {

    private String showId;
    // Idempotency keys are scoped per user across all shows, so each test gets fresh users.
    private String aliceId;
    private String bobId;
    private String alice;
    private String bob;

    @BeforeEach
    void createShow() {
        showId = post("/shows", adminToken(), """
                {"name":"reserve-test","seats":["A1","A2","A3","A4","A5","A6"],"price_paise":25000,"per_user_limit":4}
                """).body().get("id").asString();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        aliceId = "alice-" + suffix;
        bobId = "bob-" + suffix;
        alice = userToken(aliceId);
        bob = userToken(bobId);
    }

    @Test
    void reservesSeatsAndComputesAmount() {
        var response = reserve(alice, "[\"A2\",\"A1\"]", "k1");

        assertThat(response.status()).isEqualTo(201);
        var body = response.body();
        assertThat(body.get("reservation_id").asString()).hasSize(36);
        assertThat(body.get("show_id").asString()).isEqualTo(showId);
        assertThat(body.get("user_id").asString()).isEqualTo(aliceId);
        assertThat(strings(body.get("seats"))).containsExactly("A1", "A2");
        assertThat(body.get("amount_paise").asLong()).isEqualTo(50000);
        assertThat(body.get("status").asString()).isEqualTo("confirmed");

        assertThat(seatStatus("A1")).isEqualTo("confirmed");
        assertThat(seatStatus("A2")).isEqualTo("confirmed");
        assertThat(heldBy(aliceId)).isEqualTo(2);
        assertInvariant();
    }

    @Test
    void takenSeatIs409SeatTaken() {
        assertThat(reserve(alice, "[\"A1\"]", "k1").status()).isEqualTo(201);

        var response = reserve(bob, "[\"A1\"]", "k1");

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.body().get("error").asString()).isEqualTo("seat_taken");
        assertThat(heldBy(bobId)).isZero();
        assertInvariant();
    }

    @Test
    void multiSeatRequestIsAllOrNothing() {
        reserve(bob, "[\"A3\"]", "k1");

        var response = reserve(alice, "[\"A2\",\"A3\"]", "k1");

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.body().get("error").asString()).isEqualTo("seat_taken");
        assertThat(seatStatus("A2")).isEqualTo("available");
        assertThat(heldBy(aliceId)).isZero();
        assertThat(reservationCount(aliceId)).isZero();
    }

    @Test
    void sameKeySameBodyReplaysOriginalWith200() {
        var first = reserve(alice, "[\"A1\"]", "retry-me");

        var replay = reserve(alice, "[\"A1\"]", "retry-me");

        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.headers().getFirst("Idempotent-Replay")).isEqualTo("true");
        assertThat(replay.body().get("reservation_id").asString())
                .isEqualTo(first.body().get("reservation_id").asString());
        assertThat(reservationCount(aliceId)).isEqualTo(1);
        assertThat(heldBy(aliceId)).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentSeatsIs409KeyConflict() {
        reserve(alice, "[\"A1\"]", "k1");

        var response = reserve(alice, "[\"A2\"]", "k1");

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.body().get("error").asString()).isEqualTo("idempotency_key_conflict");
        assertThat(seatStatus("A2")).isEqualTo("available");
    }

    @Test
    void declinedKeyIsEvaluatedFreshOnRetry() {
        reserve(bob, "[\"A1\"]", "k1");
        assertThat(reserve(alice, "[\"A1\"]", "k1").status()).isEqualTo(409);

        // The declined attempt stored nothing, so the same key works once the seat is free.
        jdbcTemplate.update("UPDATE seats SET status = 'available', holder_user_id = NULL, reservation_id = NULL"
                + " WHERE show_id = ? AND seat_label = 'A1'", showId);

        assertThat(reserve(alice, "[\"A1\"]", "k1").status()).isEqualTo(201);
    }

    @Test
    void sameKeyFromDifferentUsersDoesNotCollide() {
        assertThat(reserve(alice, "[\"A1\"]", "shared").status()).isEqualTo(201);
        assertThat(reserve(bob, "[\"A2\"]", "shared").status()).isEqualTo(201);
    }

    @Test
    void perUserLimitIsEnforced() {
        assertThat(reserve(alice, "[\"A1\",\"A2\",\"A3\"]", "k1").status()).isEqualTo(201);

        var response = reserve(alice, "[\"A4\",\"A5\"]", "k2");

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.body().get("error").asString()).isEqualTo("per_user_limit");
        assertThat(seatStatus("A4")).isEqualTo("available");
        assertThat(heldBy(aliceId)).isEqualTo(3);
        assertThat(reserve(alice, "[\"A4\"]", "k3").status()).isEqualTo(201);
    }

    @Test
    void identityComesFromTokenNotBody() {
        var response = post("/shows/" + showId + "/reserve", alice, """
                {"seats":["A1"],"idempotency_key":"k1","user_id":"%s"}
                """.formatted(bobId));

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.body().get("user_id").asString()).isEqualTo(aliceId);
        assertThat(heldBy(bobId)).isZero();
    }

    @Test
    void keyMayComeFromHeader() {
        var response = reserveWithHeader(alice, "{\"seats\":[\"A1\"]}", "header-key");

        assertThat(response.status()).isEqualTo(201);
        assertThat(reserveWithHeader(alice, "{\"seats\":[\"A1\"]}", "header-key").status()).isEqualTo(200);
    }

    @Test
    void bodyAndHeaderKeysThatDifferAre400() {
        var response = reserveWithHeader(alice, "{\"seats\":[\"A1\"],\"idempotency_key\":\"body-key\"}", "header-key");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body().get("error").asString()).isEqualTo("invalid_request");
    }

    @Test
    void unknownSeatIs400InvalidSeatAndReservesNothing() {
        var response = reserve(alice, "[\"A1\",\"Z9\"]", "k1");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body().get("error").asString()).isEqualTo("invalid_seat");
        assertThat(seatStatus("A1")).isEqualTo("available");
        assertThat(heldBy(aliceId)).isZero();
    }

    @Test
    void unknownShowIs404() {
        var response = post("/shows/" + UUID.randomUUID() + "/reserve", alice,
                "{\"seats\":[\"A1\"],\"idempotency_key\":\"k1\"}");

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.body().get("error").asString()).isEqualTo("show_not_found");
    }

    @Test
    void invalidRequestsAre400() {
        List<String> invalidBodies = List.of(
                "{\"seats\":[\"A1\"]}",                                     // no key anywhere
                "{\"seats\":[],\"idempotency_key\":\"k\"}",                 // no seats
                "{\"seats\":[\"A1\",\"A1\"],\"idempotency_key\":\"k\"}",    // duplicate seats
                "{\"seats\":[\"A1\"],\"idempotency_key\":\"\"}",            // empty key
                "{\"seats\":[\"A1\"],\"idempotency_key\":\"" + "k".repeat(129) + "\"}");

        for (String body : invalidBodies) {
            var response = post("/shows/" + showId + "/reserve", alice, body);
            assertThat(response.status()).as(body).isEqualTo(400);
            assertThat(response.body().get("error").asString()).as(body).isEqualTo("invalid_request");
        }
    }

    @Test
    void withoutTokenIs401() {
        var response = post("/shows/" + showId + "/reserve", null, "{\"seats\":[\"A1\"],\"idempotency_key\":\"k\"}");

        assertThat(response.status()).isEqualTo(401);
    }

    private Response reserve(String token, String seatsJson, String key) {
        return post("/shows/" + showId + "/reserve", token,
                "{\"seats\":" + seatsJson + ",\"idempotency_key\":\"" + key + "\"}");
    }

    private Response reserveWithHeader(String token, String body, String headerKey) {
        var entity = http.post().uri("/shows/" + showId + "/reserve")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", headerKey)
                .body(body)
                .retrieve()
                .toEntity(String.class);
        return toResponse(entity);
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

    private int reservationCount(String userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservations WHERE show_id = ? AND user_id = ?", Integer.class, showId, userId);
    }

    private void assertInvariant() {
        JsonNode body = get("/shows/" + showId, alice).body();
        assertThat(body.get("invariant_ok").asBoolean()).isTrue();
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asString()));
        return values;
    }
}

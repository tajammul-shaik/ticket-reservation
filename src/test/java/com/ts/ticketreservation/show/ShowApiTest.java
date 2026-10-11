package com.ts.ticketreservation.show;

import com.ts.ticketreservation.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** FR-02 and FR-05. */
class ShowApiTest extends IntegrationTest {

    private static final String VALID_SHOW = """
            {"name":"friday-night","seats":["A2","A1","B1"],"price_paise":25000,"per_user_limit":2}
            """;

    @Test
    void adminCreatesShowWithAllSeatsAvailable() {
        var response = post("/shows", adminToken(), VALID_SHOW);

        assertThat(response.status()).isEqualTo(201);
        var body = response.body();
        assertThat(body.get("id").asString()).hasSize(36);
        assertThat(body.get("name").asString()).isEqualTo("friday-night");
        assertThat(body.get("price_paise").asLong()).isEqualTo(25000);
        assertThat(body.get("per_user_limit").asInt()).isEqualTo(2);
        assertThat(body.get("total_seats").asInt()).isEqualTo(3);
        assertThat(body.get("counts").get("available").asInt()).isEqualTo(3);
        assertThat(body.get("counts").get("held").asInt()).isZero();
        assertThat(body.get("counts").get("confirmed").asInt()).isZero();
        assertThat(body.get("invariant_ok").asBoolean()).isTrue();
        assertThat(labels(body.get("seats"))).containsExactly("A1", "A2", "B1");
    }

    @Test
    void perUserLimitDefaultsToFour() {
        var response = post("/shows", adminToken(), """
                {"name":"no-limit-given","seats":["A1"],"price_paise":100}
                """);

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.body().get("per_user_limit").asInt()).isEqualTo(4);
    }

    @Test
    void showAndSeatsArePersisted() {
        String showId = post("/shows", adminToken(), VALID_SHOW).body().get("id").asString();

        Integer seatRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM seats WHERE show_id = ? AND status = 'available'", Integer.class, showId);
        assertThat(seatRows).isEqualTo(3);
    }

    @Test
    void getReturnsShowStateToAnyAuthenticatedUser() {
        String showId = post("/shows", adminToken(), VALID_SHOW).body().get("id").asString();

        var response = get("/shows/" + showId, userToken("buyer-1"));

        assertThat(response.status()).isEqualTo(200);
        var body = response.body();
        assertThat(body.get("id").asString()).isEqualTo(showId);
        assertThat(body.get("total_seats").asInt()).isEqualTo(3);
        assertThat(body.get("counts").get("available").asInt()).isEqualTo(3);
        assertThat(body.get("invariant_ok").asBoolean()).isTrue();
        assertThat(labels(body.get("seats"))).containsExactly("A1", "A2", "B1");
        assertThat(body.get("seats").get(0).has("holder_user_id")).isFalse();
    }

    @Test
    void countsReflectSeatStatusInDatabase() {
        String showId = post("/shows", adminToken(), VALID_SHOW).body().get("id").asString();
        jdbcTemplate.update("UPDATE seats SET status = 'confirmed' WHERE show_id = ? AND seat_label = 'A1'", showId);

        var body = get("/shows/" + showId, userToken("buyer-1")).body();

        assertThat(body.get("counts").get("available").asInt()).isEqualTo(2);
        assertThat(body.get("counts").get("confirmed").asInt()).isEqualTo(1);
        assertThat(body.get("invariant_ok").asBoolean()).isTrue();
        assertThat(body.get("seats").get(0).get("status").asString()).isEqualTo("confirmed");
    }

    @Test
    void unknownShowIs404() {
        var response = get("/shows/00000000-0000-0000-0000-000000000000", userToken("buyer-1"));

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.body().get("error").asString()).isEqualTo("show_not_found");
    }

    @Test
    void nonAdminIs403AndNoTokenIs401() {
        var asUser = post("/shows", userToken("buyer-1"), VALID_SHOW);
        assertThat(asUser.status()).isEqualTo(403);
        assertThat(asUser.body().get("error").asString()).isEqualTo("forbidden");

        var anonymous = post("/shows", null, VALID_SHOW);
        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(anonymous.body().get("error").asString()).isEqualTo("unauthorized");
    }

    @Test
    void rejectsInvalidShowsWith400() {
        String admin = adminToken();
        List<String> invalidBodies = List.of(
                "{\"name\":\"x\",\"seats\":[\"A1\",\"A1\"],\"price_paise\":100}",      // duplicate labels
                "{\"name\":\"x\",\"seats\":[\"A1\"],\"price_paise\":250.5}",          // float price
                "{\"name\":\"x\",\"seats\":[\"A1\"],\"price_paise\":-1}",             // negative price
                "{\"name\":\"x\",\"seats\":[\"A1\"]}",                                // missing price
                "{\"name\":\"x\",\"seats\":[],\"price_paise\":100}",                  // no seats
                "{\"name\":\"x\",\"seats\":[\"A123456789\"],\"price_paise\":100}",    // label > 8 chars
                "{\"name\":\"x\",\"seats\":[\"\"],\"price_paise\":100}",              // empty label
                "{\"name\":\"x\",\"seats\":[\" A1\"],\"price_paise\":100}",           // padded label
                "{\"name\":\"\",\"seats\":[\"A1\"],\"price_paise\":100}",             // empty name
                "{\"name\":\"x\",\"seats\":[\"A1\"],\"price_paise\":100,\"per_user_limit\":0}");

        for (String body : invalidBodies) {
            var response = post("/shows", admin, body);
            assertThat(response.status()).as(body).isEqualTo(400);
            assertThat(response.body().get("error").asString()).as(body).isEqualTo("invalid_request");
        }
    }

    @Test
    void rejectedShowLeavesNoRows() {
        Integer before = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM shows", Integer.class);

        post("/shows", adminToken(), "{\"name\":\"dup\",\"seats\":[\"A1\",\"A1\"],\"price_paise\":100}");

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM shows", Integer.class)).isEqualTo(before);
    }

    private static List<String> labels(tools.jackson.databind.JsonNode seats) {
        List<String> labels = new ArrayList<>();
        seats.forEach(seat -> labels.add(seat.get("label").asString()));
        return labels;
    }
}

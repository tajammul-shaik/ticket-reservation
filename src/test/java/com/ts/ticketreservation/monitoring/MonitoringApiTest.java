package com.ts.ticketreservation.monitoring;

import com.ts.ticketreservation.IntegrationTest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class MonitoringApiTest extends IntegrationTest {

    @Test
    void publicSnapshotAgreesWithTheApi() {
        String showId = post("/shows", adminToken(), """
                {"name":"Monitored","seats":["A1","A2","A3"],"price_paise":100,"per_user_limit":4}
                """).body().get("id").asString();
        String userId = "mon-" + UUID.randomUUID().toString().substring(0, 8);
        String token = userToken(userId);
        var reserved = toResponse(http.post().uri("/shows/" + showId + "/reserve")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", "mon-k1")
                .body("{\"seats\":[\"A1\"]}")
                .retrieve().toEntity(String.class));
        assertThat(reserved.status()).isEqualTo(201);

        var response = get("/monitoring", null);

        assertThat(response.status()).isEqualTo(200);
        JsonNode body = response.body();
        assertThat(body.get("status").asString()).isEqualTo("UP");
        assertThat(body.get("database").get("reachable").asBoolean()).isTrue();
        assertThat(body.get("database").get("pool").get("max").asLong()).isPositive();
        assertThat(body.get("admission").get("max_in_flight").asLong()).isEqualTo(200);
        assertThat(body.get("jvm").get("heap_used_bytes").asLong()).isPositive();
        assertThat(body.get("http_responses_by_status").get("201").asLong()).isPositive();
        assertThat(body.get("reservations").get("confirmed").asLong()).isPositive();
        assertThat(body.get("reservations").get("declined").has("seat_taken")).isTrue();

        JsonNode show = StreamSupport.stream(body.get("shows").spliterator(), false)
                .filter(s -> s.get("show_id").asString().equals(showId))
                .findFirst().orElseThrow();
        assertThat(show.get("available").asLong()).isEqualTo(2);
        assertThat(show.get("held").asLong()).isZero();
        assertThat(show.get("confirmed").asLong()).isEqualTo(1);

        // Aggregates only: no user ids leak.
        assertThat(body.toString()).doesNotContain(userId);
    }
}

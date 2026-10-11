package com.ts.ticketreservation;

import com.ts.ticketreservation.auth.JwtService;
import com.ts.ticketreservation.auth.Role;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** NFR-13 request ids, FR-07 metrics, and the rule that metrics reconcile with the database. */
class ObservabilityTest extends IntegrationTest {

    @Autowired
    private JwtService jwtService;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void everyResponseCarriesAGeneratedRequestId() {
        var entity = http.get().uri("/health/live").retrieve().toEntity(String.class);

        assertThat(entity.getHeaders().getFirst("X-Request-Id")).matches("[0-9a-f-]{36}");
    }

    @Test
    void clientRequestIdIsEchoedInHeaderAndErrorBody() {
        var entity = http.get().uri("/shows/" + UUID.randomUUID())
                .header("Authorization", "Bearer " + jwtService.issue("u1", Role.USER))
                .header("X-Request-Id", "trace-abc.123")
                .retrieve()
                .toEntity(String.class);
        var response = toResponse(entity);

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.headers().getFirst("X-Request-Id")).isEqualTo("trace-abc.123");
        assertThat(response.body().get("request_id").asString()).isEqualTo("trace-abc.123");
    }

    @Test
    void securityErrorsCarryTheRequestIdToo() {
        var response = toResponse(http.get().uri("/shows/x").header("X-Request-Id", "sec-1")
                .retrieve().toEntity(String.class));

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.body().get("request_id").asString()).isEqualTo("sec-1");
    }

    @Test
    void unsafeClientRequestIdIsReplaced() {
        var entity = http.get().uri("/health/live").header("X-Request-Id", "bad id with spaces")
                .retrieve().toEntity(String.class);

        assertThat(entity.getHeaders().getFirst("X-Request-Id")).matches("[0-9a-f-]{36}");
    }

    @Test
    void metricsEndpointIsPublicPrometheusText() {
        var entity = http.get().uri("/metrics").retrieve().toEntity(String.class);

        assertThat(entity.getStatusCode().value()).isEqualTo(200);
        assertThat(entity.getBody())
                .contains("reservations_declined_total{reason=\"seat_taken\"")
                .contains("http_server_requests_seconds_bucket")
                .contains("hikaricp_connections_pending");
    }

    /**
     * FR-07 acceptance: counters count exactly the committed outcomes, and seat gauges equal
     * the API's counts, after a mix of confirms, declines, replays and a cancel.
     */
    @Test
    void metricsReconcileWithDatabaseAndApi() throws Exception {
        String showId = post("/shows", adminToken(), """
                {"name":"metrics","seats":["A1","A2","A3"],"price_paise":100}
                """).body().get("id").asString();
        String run = UUID.randomUUID().toString().substring(0, 8);
        String alice = jwtService.issue("m-alice-" + run, Role.USER);
        String bob = jwtService.issue("m-bob-" + run, Role.USER);
        double seatTakenBefore = declined("seat_taken");
        double replayBefore = declined("idempotent_replay");

        String r1 = reserve(showId, alice, "[\"A1\"]", "k1").body().get("reservation_id").asString();
        reserve(showId, alice, "[\"A1\"]", "k1");                 // replay
        reserve(showId, bob, "[\"A1\"]", "k1");                   // seat_taken
        reserve(showId, bob, "[\"A2\",\"A3\"]", "k2");            // confirmed
        post("/reservations/" + r1 + "/cancel", alice, "");       // cancelled
        post("/reservations/" + r1 + "/cancel", alice, "");       // cancel replay, not counted again

        assertThat(counter("reservations.confirmed", "show_id", showId)).isEqualTo(2);
        assertThat(counter("reservations.cancelled", "show_id", showId)).isEqualTo(1);
        assertThat(declined("seat_taken") - seatTakenBefore).isEqualTo(1);
        assertThat(declined("idempotent_replay") - replayBefore).isEqualTo(1);

        Integer confirmedInDb = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservations WHERE show_id = ?", Integer.class, showId);
        assertThat(counter("reservations.confirmed", "show_id", showId)).isEqualTo(confirmedInDb.doubleValue());

        // Gauges are read from the DB at scrape time; wait out the 1 s snapshot cache.
        Thread.sleep(1100);
        String scrape = http.get().uri("/metrics").retrieve().body(String.class);
        var counts = get("/shows/" + showId, alice).body().get("counts");
        assertThat(gauge(scrape, "seats_available", showId)).isEqualTo(counts.get("available").asDouble());
        assertThat(gauge(scrape, "seats_confirmed", showId)).isEqualTo(counts.get("confirmed").asDouble());
        assertThat(gauge(scrape, "seats_held", showId)).isEqualTo(counts.get("held").asDouble());
        assertThat(gauge(scrape, "seats_available", showId)).isEqualTo(1.0);
        assertThat(gauge(scrape, "reservations_confirmed_total", showId)).isEqualTo(2.0);
    }

    private Response reserve(String showId, String token, String seatsJson, String key) {
        return post("/shows/" + showId + "/reserve", token,
                "{\"seats\":" + seatsJson + ",\"idempotency_key\":\"" + key + "\"}");
    }

    private double counter(String name, String tagKey, String tagValue) {
        var counter = meterRegistry.find(name).tag(tagKey, tagValue).counter();
        return counter == null ? 0 : counter.count();
    }

    private double declined(String reason) {
        return counter("reservations.declined", "reason", reason);
    }

    private static double gauge(String scrape, String metric, String showId) {
        Matcher matcher = Pattern.compile(
                "(?m)^" + metric + "\\{[^}]*show_id=\"" + Pattern.quote(showId) + "\"[^}]*} (\\S+)$").matcher(scrape);
        assertThat(matcher.find()).as("%s for show %s in /metrics", metric, showId).isTrue();
        return Double.parseDouble(matcher.group(1));
    }
}

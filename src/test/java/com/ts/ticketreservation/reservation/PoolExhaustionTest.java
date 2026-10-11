package com.ts.ticketreservation.reservation;

import com.ts.ticketreservation.IntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A reserve that cannot get a pooled connection must be shed as 429 overloaded, never 500
 * (BR-15). Found by the burst script on the live service, where pool timeouts returned 500.
 * The request waits out the pool's full connection timeout; the shared pool is not reconfigured,
 * so other tests in this Spring context are unaffected.
 */
class PoolExhaustionTest extends IntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void reserveIs429WhenNoConnectionIsAvailable() throws Exception {
        String showId = post("/shows", adminToken(), """
                {"name":"Pool","seats":["A1"],"price_paise":100,"per_user_limit":4}
                """).body().get("id").asString();
        String token = userToken("pool-" + UUID.randomUUID().toString().substring(0, 8));

        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        List<Connection> held = new ArrayList<>();
        try {
            for (int i = 0; i < pool.getMaximumPoolSize(); i++) {
                held.add(dataSource.getConnection());
            }

            var response = toResponse(http.post().uri("/shows/" + showId + "/reserve")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + token)
                    .header("Idempotency-Key", "pool-k1")
                    .body("{\"seats\":[\"A1\"]}")
                    .retrieve().toEntity(String.class));

            assertThat(response.status()).isEqualTo(429);
            assertThat(response.body().get("error").asString()).isEqualTo("overloaded");
        } finally {
            for (Connection connection : held) {
                connection.close();
            }
        }
    }
}

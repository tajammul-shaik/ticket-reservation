package com.ts.ticketreservation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TicketReservationApplicationTests extends IntegrationTest {

    @Test
    void contextLoadsAndFlywayCreatesAllTables() {
        var tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()",
                String.class);

        assertThat(tables).contains("shows", "seats", "reservations", "user_show_counts");
    }

    @Test
    void readinessIsUpWithRealDatabase() {
        var response = get("/health/ready", null);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().get("status").asString()).isEqualTo("UP");
    }
}

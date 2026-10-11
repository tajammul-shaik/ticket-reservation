package com.ts.ticketreservation.health;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HealthControllerTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final DatabaseReadinessCheck readinessCheck = new DatabaseReadinessCheck(jdbcTemplate);
    private final HealthController controller = new HealthController(readinessCheck);

    @AfterEach
    void tearDown() {
        readinessCheck.close();
    }

    @Test
    void liveIsUpWithoutTouchingTheDatabase() {
        assertThat(controller.live()).isEqualTo(Map.of("status", "UP"));
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void readyIsUpWhenSelectOneSucceeds() {
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenReturn(1);

        var response = controller.ready();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(Map.of("status", "UP"));
    }

    @Test
    void readyIs503WhenDatabaseFails() {
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        var response = controller.ready();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("error", "not_ready");
    }

    @Test
    void readyIs503WithinTimeoutWhenDatabaseHangs() {
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenAnswer(invocation -> {
            Thread.sleep(10_000);
            return 1;
        });

        long start = System.nanoTime();
        var response = controller.ready();
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(elapsedMillis).isBetween(DatabaseReadinessCheck.TIMEOUT.toMillis(), 3_000L);
    }
}

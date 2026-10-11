package com.ts.ticketreservation.health;

import com.ts.ticketreservation.IntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Readiness must not fail just because a burst keeps every pooled connection busy: the host
 * would restart a healthy instance mid-burst. Found by the burst script, where /health/ready
 * returned 503 while the main pool was saturated.
 */
class ReadinessUnderLoadTest extends IntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void readyStaysUpWhileTheMainPoolIsExhausted() throws Exception {
        int poolSize = dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize();
        List<Connection> held = new ArrayList<>();
        try {
            for (int i = 0; i < poolSize; i++) {
                held.add(dataSource.getConnection());
            }

            long start = System.nanoTime();
            Response ready = get("/health/ready", null);
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

            assertThat(ready.status()).isEqualTo(200);
            assertThat(elapsedMillis).isLessThan(DatabaseReadinessCheck.TIMEOUT.toMillis());
        } finally {
            for (Connection connection : held) {
                connection.close();
            }
        }
    }

    /**
     * The readiness pool must not shrink the JVM-wide JDBC login timeout to its 2 s limit: new
     * main-pool connections to a managed TLS database would then fail to log in.
     */
    @Test
    void readinessDoesNotShortenTheJdbcLoginTimeout() {
        assertThat(get("/health/ready", null).status()).isEqualTo(200);

        assertThat(DriverManager.getLoginTimeout()).isGreaterThanOrEqualTo(10);
    }
}

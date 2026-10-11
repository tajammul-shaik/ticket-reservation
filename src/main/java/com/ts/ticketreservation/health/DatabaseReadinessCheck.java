package com.ts.ticketreservation.health;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Runs SELECT 1 against the database with a hard time limit.
 * <p>
 * The check has its own one-connection pool. Through the main pool, a burst that keeps every
 * connection busy would make readiness wait behind reserve requests and fail even though the
 * database is fine, and the host could then restart a healthy instance mid-burst. Readiness is
 * about whether the database answers, not whether the pool is busy (pool saturation is visible
 * in hikaricp_connections_pending instead).
 * <p>
 * The limit covers getting the connection as well as running the query, which a JDBC query
 * timeout alone does not: with the database down, Hikari would otherwise block for its full
 * connection timeout before failing.
 */
@Component
public class DatabaseReadinessCheck implements AutoCloseable {

    static final Duration TIMEOUT = Duration.ofSeconds(2);

    // Hikari turns a pool's connection timeout into DriverManager's login timeout, which is
    // JVM-wide: a 2 s value here would also cut the main pool's TLS logins to 2 s. The 2 s
    // readiness limit is enforced by the Future in isDatabaseReachable instead.
    static final Duration POOL_CONNECTION_TIMEOUT = Duration.ofSeconds(30);

    private final JdbcTemplate jdbcTemplate;
    private final HikariDataSource healthDataSource;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @Autowired
    public DatabaseReadinessCheck(DataSourceProperties properties) {
        this.healthDataSource = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        healthDataSource.setPoolName("health-check-pool");
        healthDataSource.setMaximumPoolSize(1);
        healthDataSource.setMinimumIdle(1); // keep one warm connection; a new TLS handshake can be slow
        healthDataSource.setConnectionTimeout(POOL_CONNECTION_TIMEOUT.toMillis());
        healthDataSource.setInitializationFailTimeout(-1); // never block startup on the check's pool
        this.jdbcTemplate = new JdbcTemplate(healthDataSource);
    }

    /** For tests: run the check through the given template. */
    DatabaseReadinessCheck(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.healthDataSource = null;
    }

    /** True if SELECT 1 succeeds within {@link #TIMEOUT}; false on any failure or timeout. */
    public boolean isDatabaseReachable() {
        Future<Integer> result = executor.submit(
                () -> jdbcTemplate.queryForObject("SELECT 1", Integer.class));
        try {
            return Integer.valueOf(1).equals(result.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            result.cancel(true);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
        if (healthDataSource != null) {
            healthDataSource.close();
        }
    }
}

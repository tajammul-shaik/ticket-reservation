package com.ts.ticketreservation.health;

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
 * The limit covers getting a pooled connection as well as running the query, which a JDBC
 * query timeout alone does not: with the database down, Hikari would otherwise block for its
 * full connection timeout (30 s) before failing.
 */
@Component
public class DatabaseReadinessCheck implements AutoCloseable {

    static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final JdbcTemplate jdbcTemplate;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public DatabaseReadinessCheck(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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
    }
}

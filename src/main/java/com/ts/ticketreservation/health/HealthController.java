package com.ts.ticketreservation.health;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * FR-06. Liveness never touches the database; readiness fails closed (503) when the database
 * cannot answer SELECT 1 in time. Flyway runs before the web server starts, so the service
 * does not answer at all until migrations have finished.
 */
@RestController
@RequestMapping("/health")
public class HealthController {

    private final DatabaseReadinessCheck readinessCheck;

    public HealthController(DatabaseReadinessCheck readinessCheck) {
        this.readinessCheck = readinessCheck;
    }

    @GetMapping("/live")
    public Map<String, String> live() {
        return Map.of("status", "UP");
    }

    @GetMapping("/ready")
    public ResponseEntity<Map<String, String>> ready() {
        if (readinessCheck.isDatabaseReachable()) {
            return ResponseEntity.ok(Map.of("status", "UP"));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "not_ready", "message", "database unreachable"));
    }
}

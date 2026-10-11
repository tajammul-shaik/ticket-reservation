package com.ts.ticketreservation.auth;

import io.jsonwebtoken.security.WeakKeyException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "unit-test-jwt-secret-that-is-at-least-32-bytes";
    private static final Instant NOW = Instant.parse("2026-10-11T10:00:00Z");

    private final AuthProperties properties = new AuthProperties(SECRET, "admin", Duration.ofHours(24));

    @Test
    void roundTripsUserAndRole() {
        var service = serviceAt(NOW);

        assertThat(service.verify(service.issue("u123", Role.ADMIN)))
                .contains(new AuthenticatedUser("u123", Role.ADMIN));
    }

    @Test
    void tokenIsValidJustBeforeExpiryAndRejectedAfter() {
        String token = serviceAt(NOW).issue("u1", Role.USER);

        assertThat(serviceAt(NOW.plus(Duration.ofHours(24)).minusSeconds(1)).verify(token)).isPresent();
        assertThat(serviceAt(NOW.plus(Duration.ofHours(24)).plusSeconds(1)).verify(token)).isEmpty();
    }

    @Test
    void rejectsTokenSignedWithAnotherKey() {
        var other = new JwtService(
                new AuthProperties("another-secret-that-is-also-at-least-32-bytes", "admin", Duration.ofHours(24)),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(serviceAt(NOW).verify(other.issue("u1", Role.ADMIN))).isEmpty();
    }

    @Test
    void rejectsGarbage() {
        assertThat(serviceAt(NOW).verify("not.a.jwt")).isEmpty();
        assertThat(serviceAt(NOW).verify("")).isEmpty();
    }

    @Test
    void refusesSecretShorterThan256Bits() {
        assertThatThrownBy(() -> new JwtService(new AuthProperties("too-short", "admin", Duration.ofHours(24))))
                .isInstanceOf(WeakKeyException.class);
    }

    private JwtService serviceAt(Instant instant) {
        return new JwtService(properties, Clock.fixed(instant, ZoneOffset.UTC));
    }
}

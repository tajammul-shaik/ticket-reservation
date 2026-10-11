package com.ts.ticketreservation.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/** HS256 tokens: sub = user id, role claim, 24 h expiry by default (FR-01). */
@Service
public class JwtService {

    private static final String ROLE_CLAIM = "role";

    private final SecretKey key;
    private final Duration tokenTtl;
    private final Clock clock;

    @Autowired
    public JwtService(AuthProperties properties) {
        this(properties, Clock.systemUTC());
    }

    JwtService(AuthProperties properties, Clock clock) {
        // Throws WeakKeyException at startup if the secret is shorter than 256 bits.
        this.key = Keys.hmacShaKeyFor(properties.jwtSecret().getBytes(StandardCharsets.UTF_8));
        this.tokenTtl = properties.tokenTtl();
        this.clock = clock;
    }

    public String issue(String userId, Role role) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(userId)
                .claim(ROLE_CLAIM, role.claimValue())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(tokenTtl)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** The verified user, or empty if the token is malformed, tampered with or expired. */
    public Optional<AuthenticatedUser> verify(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            String userId = claims.getSubject();
            if (userId == null || userId.isBlank()) {
                return Optional.empty();
            }
            return Role.fromClaim(claims.get(ROLE_CLAIM, String.class))
                    .map(role -> new AuthenticatedUser(userId, role));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}

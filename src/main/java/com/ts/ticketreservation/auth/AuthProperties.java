package com.ts.ticketreservation.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** Bound from app.security.* (JWT_SECRET and ADMIN_SECRET in the environment). */
@Validated
@ConfigurationProperties("app.security")
public record AuthProperties(
        @NotBlank String jwtSecret,
        @NotBlank String adminSecret,
        @NotNull @DefaultValue("24h") Duration tokenTtl) {
}

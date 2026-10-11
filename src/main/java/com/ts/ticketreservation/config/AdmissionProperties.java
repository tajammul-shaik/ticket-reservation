package com.ts.ticketreservation.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** NFR-04 load shedding, bound from app.admission.*. */
@Validated
@ConfigurationProperties("app.admission")
public record AdmissionProperties(
        @Min(1) @DefaultValue("200") int maxInFlight,
        @NotNull @DefaultValue("5s") Duration maxWait) {
}

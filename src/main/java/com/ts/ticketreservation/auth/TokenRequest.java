package com.ts.ticketreservation.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** FR-01 request body. user_id fits reservations.user_id VARCHAR(64). */
public record TokenRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,64}", message = "must be 1-64 characters of A-Z a-z 0-9 . _ -")
        String userId) {
}

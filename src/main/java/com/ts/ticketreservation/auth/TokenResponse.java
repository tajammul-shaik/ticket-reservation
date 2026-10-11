package com.ts.ticketreservation.auth;

public record TokenResponse(String token, String userId, Role role) {
}

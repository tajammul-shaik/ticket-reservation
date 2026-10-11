package com.ts.ticketreservation.auth;

/**
 * The security principal for a request, built only from a verified token (BR-08).
 * Controllers read it with {@code @AuthenticationPrincipal AuthenticatedUser user}.
 */
public record AuthenticatedUser(String userId, Role role) {
}

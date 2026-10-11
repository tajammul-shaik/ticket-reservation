package com.ts.ticketreservation.reservation;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * FR-03 request body. The idempotency key may instead come in the Idempotency-Key header;
 * {@link ReservationController} reconciles the two. Any user_id in the body is ignored (BR-08).
 */
public record ReserveRequest(
        @NotEmpty @Size(max = ReservationService.MAX_SEATS_PER_REQUEST) List<@NotNull @Size(min = 1, max = 8) String> seats,
        @Size(min = 1, max = 128) String idempotencyKey) {
}

package com.ts.ticketreservation.reservation;

import com.ts.ticketreservation.auth.AuthenticatedUser;
import com.ts.ticketreservation.common.error.ApiException;
import com.ts.ticketreservation.common.error.ErrorCode;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReservationController {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    static final String IDEMPOTENT_REPLAY_HEADER = "Idempotent-Replay";

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    /** FR-03. 201 for a new reservation; 200 with Idempotent-Replay: true for a replay. */
    @PostMapping("/shows/{showId}/reserve")
    public ResponseEntity<ReservationResponse> reserve(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String showId,
            @Valid @RequestBody ReserveRequest request,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String headerKey) {
        String idempotencyKey = resolveIdempotencyKey(request.idempotencyKey(), headerKey);

        var outcome = reservationService.reserve(user, showId, request.seats(), idempotencyKey);

        if (outcome.replay()) {
            return ResponseEntity.ok().header(IDEMPOTENT_REPLAY_HEADER, "true").body(outcome.reservation());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(outcome.reservation());
    }

    /** FR-04. 200 with status cancelled, also when it was already cancelled. */
    @PostMapping("/reservations/{reservationId}/cancel")
    public ReservationResponse cancel(@AuthenticationPrincipal AuthenticatedUser user,
                                      @PathVariable String reservationId) {
        return reservationService.cancel(user, reservationId).reservation();
    }

    // Key may come in the body or the header; if both are present they must match.
    private static String resolveIdempotencyKey(String bodyKey, String headerKey) {
        if (bodyKey != null && headerKey != null && !bodyKey.equals(headerKey)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "idempotency_key in body and Idempotency-Key header differ");
        }
        String key = bodyKey != null ? bodyKey : headerKey;
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "idempotency_key (body) or Idempotency-Key (header) is required, 1-128 characters");
        }
        return key;
    }
}

package com.ts.ticketreservation.common.error;

import org.springframework.http.HttpStatus;

/** Stable error codes from BRD section 12. The code string is what clients match on. */
public enum ErrorCode {

    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "invalid_request"),
    INVALID_SEAT(HttpStatus.BAD_REQUEST, "invalid_seat"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "unauthorized"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "forbidden"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "not_found"),
    SHOW_NOT_FOUND(HttpStatus.NOT_FOUND, "show_not_found"),
    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "reservation_not_found"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed"),
    SEAT_TAKEN(HttpStatus.CONFLICT, "seat_taken"),
    PER_USER_LIMIT(HttpStatus.CONFLICT, "per_user_limit"),
    IDEMPOTENCY_KEY_CONFLICT(HttpStatus.CONFLICT, "idempotency_key_conflict"),
    OVERLOADED(HttpStatus.TOO_MANY_REQUESTS, "overloaded"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error");

    private final HttpStatus status;
    private final String code;

    ErrorCode(HttpStatus status, String code) {
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}

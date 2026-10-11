package com.ts.ticketreservation.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.MDC;

/**
 * Body of every non-success response: { "error", "message", "request_id" }.
 * request_id comes from the MDC; it is omitted until the request-id filter is in place.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String error, String message, String requestId) {

    public static final String REQUEST_ID_MDC_KEY = "request_id";

    public static ErrorResponse of(ErrorCode errorCode, String message) {
        return new ErrorResponse(errorCode.code(), message, MDC.get(REQUEST_ID_MDC_KEY));
    }
}

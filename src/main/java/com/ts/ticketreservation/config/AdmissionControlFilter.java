package com.ts.ticketreservation.config;

import com.ts.ticketreservation.common.error.ErrorCode;
import com.ts.ticketreservation.common.error.ErrorResponse;
import com.ts.ticketreservation.metrics.ReservationMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * NFR-04. At most max-in-flight API requests run at once; a request that cannot get a slot
 * within max-wait is shed with 429 overloaded instead of queueing until it times out as a 5xx.
 * Health and metrics are exempt so the host and Prometheus can always reach them.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AdmissionControlFilter extends OncePerRequestFilter {

    private static final Pattern RESERVE_PATH = Pattern.compile("/shows/[^/]+/reserve");

    private final Semaphore slots;
    private final long maxWaitMillis;
    private final ReservationMetrics metrics;
    private final JsonMapper jsonMapper;

    public AdmissionControlFilter(AdmissionProperties properties, ReservationMetrics metrics, JsonMapper jsonMapper) {
        this.slots = new Semaphore(properties.maxInFlight(), true);
        this.maxWaitMillis = properties.maxWait().toMillis();
        this.metrics = metrics;
        this.jsonMapper = jsonMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/health/") || path.equals("/metrics");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean admitted;
        try {
            admitted = slots.tryAcquire(maxWaitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            admitted = false;
        }
        if (!admitted) {
            if (RESERVE_PATH.matcher(request.getRequestURI()).matches()) {
                metrics.declined(ReservationMetrics.REASON_OVERLOADED);
            }
            response.setStatus(ErrorCode.OVERLOADED.status().value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            jsonMapper.writeValue(response.getOutputStream(),
                    ErrorResponse.of(ErrorCode.OVERLOADED, "too many requests in flight, retry shortly"));
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            slots.release();
        }
    }
}

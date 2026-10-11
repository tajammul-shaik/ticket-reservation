package com.ts.ticketreservation.common.web;

import com.ts.ticketreservation.common.error.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * NFR-13. Takes X-Request-Id from the client (if it looks safe) or generates one, puts it in
 * the MDC for every log line, echoes it in the response header, and logs one line per request
 * with status and latency. Runs first, before security, so 401/403 bodies carry it too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Logger log = LoggerFactory.getLogger("http.request");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || !SAFE_REQUEST_ID.matcher(requestId).matches()) {
            requestId = UUID.randomUUID().toString();
        }
        long start = System.nanoTime();
        MDC.put(ErrorResponse.REQUEST_ID_MDC_KEY, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            log.info("{} {} {} {}",
                    kv("method", request.getMethod()),
                    kv("path", request.getRequestURI()),
                    kv("status", response.getStatus()),
                    kv("latency_ms", latencyMs));
            MDC.clear();
        }
    }
}

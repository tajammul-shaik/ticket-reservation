package com.ts.ticketreservation.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Authenticates "Authorization: Bearer &lt;jwt&gt;". A missing or invalid token leaves the
 * request anonymous, and Spring Security answers 401 for protected endpoints.
 * <p>
 * Not a @Component on purpose: Boot would also register it as a plain servlet filter and
 * run it twice. SecurityConfig adds it to the security chain only.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            String token = header.substring(BEARER_PREFIX.length()).strip();
            jwtService.verify(token).ifPresent(user -> {
                var authentication = new UsernamePasswordAuthenticationToken(
                        user, null, List.of(new SimpleGrantedAuthority(user.role().authority())));
                SecurityContextHolder.getContext().setAuthentication(authentication);
                // Every later log line of this request carries the user; RequestIdFilter clears the MDC.
                MDC.put("user_id", user.userId());
            });
        }
        chain.doFilter(request, response);
    }
}

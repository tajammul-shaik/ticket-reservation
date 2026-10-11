package com.ts.ticketreservation.auth;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * FR-01. Demo token issuance: any user id gets a user token; a matching X-Admin-Secret
 * header makes it an admin token. A wrong secret still yields a user token, never an error
 * and never an admin token.
 */
@RestController
@RequestMapping("/auth")
public class TokenController {

    static final String ADMIN_SECRET_HEADER = "X-Admin-Secret";

    private final JwtService jwtService;
    private final byte[] adminSecret;

    public TokenController(JwtService jwtService, AuthProperties properties) {
        this.jwtService = jwtService;
        this.adminSecret = properties.adminSecret().getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/token")
    public TokenResponse issue(@Valid @RequestBody TokenRequest request,
                               @RequestHeader(name = ADMIN_SECRET_HEADER, required = false) String providedSecret) {
        Role role = isAdminSecret(providedSecret) ? Role.ADMIN : Role.USER;
        return new TokenResponse(jwtService.issue(request.userId(), role), request.userId(), role);
    }

    // Constant-time comparison so response timing does not leak the secret.
    private boolean isAdminSecret(String providedSecret) {
        return providedSecret != null
                && MessageDigest.isEqual(adminSecret, providedSecret.getBytes(StandardCharsets.UTF_8));
    }
}

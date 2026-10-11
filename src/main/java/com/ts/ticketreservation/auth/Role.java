package com.ts.ticketreservation.auth;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.Optional;

public enum Role {

    USER("user"),
    ADMIN("admin");

    private final String claimValue;

    Role(String claimValue) {
        this.claimValue = claimValue;
    }

    /** The value carried in the JWT "role" claim and in JSON responses. */
    @JsonValue
    public String claimValue() {
        return claimValue;
    }

    /** The Spring Security authority, e.g. ROLE_ADMIN, used by hasRole("ADMIN"). */
    public String authority() {
        return "ROLE_" + name();
    }

    public static Optional<Role> fromClaim(String value) {
        return Arrays.stream(values()).filter(role -> role.claimValue.equals(value)).findFirst();
    }
}

package com.ts.ticketreservation.auth;

import com.ts.ticketreservation.IntegrationTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** FR-01 and the 401/403 rules. */
class AuthApiTest extends IntegrationTest {

    @Test
    void issuesUserTokenWithoutAdminSecret() {
        var response = post("/auth/token", null, "{\"user_id\":\"u123\"}");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().get("user_id").asString()).isEqualTo("u123");
        assertThat(response.body().get("role").asString()).isEqualTo("user");
        assertThat(response.body().get("token").asString()).isNotBlank();
    }

    @Test
    void issuesAdminTokenWithCorrectAdminSecret() {
        var response = requestToken("u1", ADMIN_SECRET);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().get("role").asString()).isEqualTo("admin");
    }

    @Test
    void wrongAdminSecretYieldsUserTokenNeverAdmin() {
        var response = requestToken("u1", "not-the-secret");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().get("role").asString()).isEqualTo("user");
    }

    @Test
    void rejectsInvalidUserId() {
        assertThat(post("/auth/token", null, "{\"user_id\":\"\"}").status()).isEqualTo(400);
        assertThat(post("/auth/token", null, "{}").status()).isEqualTo(400);
        assertThat(post("/auth/token", null, "{\"user_id\":\"has space\"}").status()).isEqualTo(400);
        assertThat(post("/auth/token", null, "{\"user_id\":\"" + "x".repeat(65) + "\"}").status()).isEqualTo(400);

        var response = post("/auth/token", null, "not json");
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body().get("error").asString()).isEqualTo("invalid_request");
    }

    @Test
    void protectedEndpointWithoutTokenIs401() {
        var response = get("/shows/anything", null);

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.body().get("error").asString()).isEqualTo("unauthorized");
    }

    @Test
    void tamperedOrGarbageTokenIs401() {
        String token = userToken("u1");
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("A") ? "BB" : "AA");

        assertThat(get("/shows/anything", tampered).status()).isEqualTo(401);
        assertThat(get("/shows/anything", "garbage").status()).isEqualTo(401);
    }

    @Test
    void validTokenPassesAuthentication() {
        var response = get("/shows/does-not-exist", userToken("u1"));

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.body().get("error").asString()).isEqualTo("show_not_found");
    }
}

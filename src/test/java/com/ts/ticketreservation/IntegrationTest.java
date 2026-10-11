package com.ts.ticketreservation;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base for tests that call the running app over HTTP against real MySQL. Secrets are fixed
 * here so tests never depend on the developer's local env file.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.security.jwt-secret=test-jwt-secret-that-is-at-least-32-bytes-long",
                "app.security.admin-secret=" + IntegrationTest.ADMIN_SECRET
        })
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

    protected static final String ADMIN_SECRET = "test-admin-secret";

    @LocalServerPort
    private int port;

    @Autowired
    protected JsonMapper jsonMapper;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    protected RestClient http;

    @BeforeEach
    void setUpClient() {
        http = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { })
                .build();
    }

    protected record Response(int status, JsonNode body) {
    }

    protected Response get(String path, String token) {
        var request = http.get().uri(path);
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return toResponse(request.retrieve().toEntity(String.class));
    }

    protected Response post(String path, String token, String jsonBody) {
        var request = http.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(jsonBody);
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return toResponse(request.retrieve().toEntity(String.class));
    }

    protected String userToken(String userId) {
        return post("/auth/token", null, "{\"user_id\":\"" + userId + "\"}").body().get("token").asString();
    }

    protected String adminToken() {
        return requestToken("admin", ADMIN_SECRET).body().get("token").asString();
    }

    /** POST /auth/token with an X-Admin-Secret header. */
    protected Response requestToken(String userId, String adminSecret) {
        var entity = http.post().uri("/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Admin-Secret", adminSecret)
                .body("{\"user_id\":\"" + userId + "\"}")
                .retrieve()
                .toEntity(String.class);
        return toResponse(entity);
    }

    private Response toResponse(org.springframework.http.ResponseEntity<String> entity) {
        String body = entity.getBody();
        JsonNode json = body == null || body.isBlank() ? null : jsonMapper.readTree(body);
        return new Response(entity.getStatusCode().value(), json);
    }
}

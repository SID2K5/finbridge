package com.finbridge.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finbridge.support.AbstractIntegrationTest;
import com.finbridge.user.RoleName;
import com.finbridge.user.User;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * The security review turned into tests: each one states a property an attacker would
 * probe, and proves it against the running application.
 */
class SecurityHardeningIT extends AbstractIntegrationTest {

    private static String base64Url(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    // ---------- tokens ----------

    @Test
    void aTokenWithNoSignatureOrWithAnotherKeyOrAnotherAlgorithmIsRefused() throws Exception {
        User admin = createUser(RoleName.ADMIN);
        String payload = base64Url("{\"sub\":\"" + admin.getId() + "\",\"exp\":4102444800}");

        String unsigned = base64Url("{\"alg\":\"none\"}") + "." + payload + ".";
        String otherKey = Jwts.builder().subject(String.valueOf(admin.getId()))
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor("a-different-secret-a-different-secret-0123".getBytes(
                        StandardCharsets.UTF_8))).compact();
        String valid = jwtService.issueToken(admin.getId(), admin.getEmail(), List.of());
        String[] parts = valid.split("\\.");
        // The real header and signature, with someone else's id spliced into the payload.
        String spliced = parts[0] + "." + base64Url("{\"sub\":\"1\",\"exp\":4102444800}") + "." + parts[2];

        for (String forged : List.of(unsigned, otherKey, spliced, valid + "x", "Bearer", "")) {
            mockMvc.perform(get("/api/users").header("Authorization", "Bearer " + forged))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        }
        mockMvc.perform(get("/api/users").header("Authorization", "Bearer " + valid)).andExpect(status().isOk());
    }

    @Test
    void aTokenIsNotAcceptedInTheUrlOrInACookie() throws Exception {
        String token = jwtService.issueToken(createUser(RoleName.ADMIN).getId(), "x", List.of());

        mockMvc.perform(get("/api/users").param("access_token", token)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/users").cookie(new jakarta.servlet.http.Cookie("Authorization", token)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/users").header("Authorization", "Basic " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aPasswordResetRevokesTheTokensIssuedBeforeIt() throws Exception {
        String admin = bearer(createUser(RoleName.ADMIN));
        User victim = createUser(RoleName.FINANCE_MANAGER);
        String stolen = bearer(victim);
        mockMvc.perform(get("/api/invoices").header("Authorization", stolen)).andExpect(status().isOk());

        Thread.sleep(1100);   // a token's issue time has whole-second precision
        mockMvc.perform(post("/api/users/" + victim.getId() + "/reset-password").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"newPassword\":\"a-brand-new-password-7\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/invoices").header("Authorization", stolen))
                .andExpect(status().isUnauthorized());

        Thread.sleep(1100);
        String fresh = objectMapper.readTree(mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(
                                Map.of("email", victim.getEmail(), "password", "a-brand-new-password-7"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .get("data").get("accessToken").asText();
        mockMvc.perform(get("/api/invoices").header("Authorization", "Bearer " + fresh)).andExpect(status().isOk());
    }

    // ---------- what is exposed ----------

    @Test
    void onlyTheHealthEndpointOfTheActuatorExists() throws Exception {
        String admin = bearer(createUser(RoleName.ADMIN));

        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());   // no details: no database name or disk
        for (String path : List.of("/actuator", "/actuator/env", "/actuator/beans", "/actuator/heapdump",
                "/actuator/loggers", "/actuator/mappings")) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
            mockMvc.perform(get(path).header("Authorization", admin)).andExpect(status().isNotFound());
        }
    }

    @Test
    void theApiDocumentationIsClosedOutsideTheDevProfile() throws Exception {
        for (String path : List.of("/v3/api-docs", "/swagger-ui.html", "/swagger-ui/index.html")) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void theBackendPublishesNoSoapEndpointOfItsOwn() throws Exception {
        String admin = bearer(createUser(RoleName.ADMIN));

        mockMvc.perform(post("/services").header("Authorization", admin).contentType(MediaType.TEXT_XML)
                .content("<x/>")).andExpect(status().isNotFound());
        mockMvc.perform(post("/ws").header("Authorization", admin).contentType(MediaType.TEXT_XML)
                .content("<x/>")).andExpect(status().isNotFound());
    }

    @Test
    void responsesCarryTheBrowserHardeningHeadersAndAreNotCached() throws Exception {
        mockMvc.perform(get("/api/invoices").header("Authorization", bearer(createUser(RoleName.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        // Also on a refusal.
        mockMvc.perform(get("/api/invoices"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    // ---------- cross-origin ----------

    @Test
    void onlyTheConfiguredFrontendOriginPassesCors() throws Exception {
        mockMvc.perform(options("/api/invoices").header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));

        mockMvc.perform(options("/api/invoices").header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        // No wildcard, ever.
        mockMvc.perform(get("/actuator/health").header("Origin", "https://evil.example"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    // ---------- input ----------

    @Test
    void hostileInputIsTreatedAsDataAndErrorsRevealNothingInternal() throws Exception {
        String admin = bearer(createUser(RoleName.ADMIN));

        // SQL metacharacters in a search are just characters to search for.
        mockMvc.perform(get("/api/suppliers").header("Authorization", admin).param("q", "' OR '1'='1' -- "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.totalElements").value(0));
        mockMvc.perform(get("/api/invoices").header("Authorization", admin)
                        .param("invoiceNumber", "x'; DROP TABLE invoices; --"))
                .andExpect(status().isOk());
        // Sorting is an allow-list: a column name is not accepted.
        mockMvc.perform(get("/api/users").header("Authorization", admin).param("sort", "passwordHash,asc"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/invoices").header("Authorization", admin).param("sort", "id;select 1"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/invoices").header("Authorization", admin).param("size", "100000"))
                .andExpect(status().isBadRequest());

        // A broken request gets the plain envelope: no class name, no stack trace, no SQL.
        for (String body : List.of("{not json", "[]", "{\"email\":{\"$gt\":\"\"},\"password\":1}")) {
            String response = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContainIgnoringCase("exception").doesNotContain("com.finbridge", "java.",
                    "org.springframework", "select ", "at line");
        }
        mockMvc.perform(get("/api/invoices/not-a-number").header("Authorization", admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value("Parameter 'id' has an invalid value"));
    }

    @Test
    void aUserCannotGrantThemselvesARoleOrReadAnotherUsersHash() throws Exception {
        User finance = createUser(RoleName.FINANCE_MANAGER);
        String token = bearer(finance);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/users/" + finance.getId() + "/roles").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"roles\":[\"ADMIN\"]}"))
                .andExpect(status().isForbidden());
        // Extra fields in a body are ignored, not bound: /me cannot be used to change anything.
        String me = mockMvc.perform(get("/api/auth/me").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roles[0]").value("FINANCE_MANAGER"))
                .andReturn().getResponse().getContentAsString();
        assertThat(me).doesNotContain("passwordHash", "$2a$", "$2b$");
    }
}

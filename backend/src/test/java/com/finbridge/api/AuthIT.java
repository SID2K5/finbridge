package com.finbridge.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.finbridge.support.AbstractIntegrationTest;
import com.finbridge.user.RoleName;
import com.finbridge.user.User;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class AuthIT extends AbstractIntegrationTest {

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))));
    }

    @Test
    void loginReturnsATokenThatOpensAProtectedEndpoint() throws Exception {
        User user = createUser(RoleName.FINANCE_MANAGER);

        String body = login(user.getEmail(), PASSWORD)
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(3600))
                .andExpect(jsonPath("$.data.user.email").value(user.getEmail()))
                .andExpect(jsonPath("$.data.user.roles[0]").value("FINANCE_MANAGER"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("passwordHash").doesNotContain("$2a$");
        JsonNode json = objectMapper.readTree(body);
        String token = json.at("/data/accessToken").asText();

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(user.getEmail()));
    }

    @Test
    void wrongPasswordAndUnknownEmailGetTheSame401() throws Exception {
        User user = createUser(RoleName.FINANCE_MANAGER);

        login(user.getEmail(), "wrong-password-1")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.error.message").value("Invalid email or password"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());

        login("nobody" + nextNumber() + "@test.example", PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.error.message").value("Invalid email or password"));
    }

    @Test
    void loginWithAnInvalidBodyIsA400WithFieldErrors() throws Exception {
        login("not-an-email", "")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fieldErrors").isNotEmpty());
    }

    @Test
    void protectedEndpointWithoutATokenIs401() throws Exception {
        mockMvc.perform(get("/api/invoices"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void forgedOrGarbageTokenIs401() throws Exception {
        mockMvc.perform(get("/api/invoices").header("Authorization", "Bearer not.a.token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void fiveWrongPasswordsLockTheAccountInTheDatabase() throws Exception {
        User user = createUser(RoleName.INTEGRATION_OPERATOR);
        for (int attempt = 1; attempt <= 5; attempt++) {
            login(user.getEmail(), "wrong-password-1").andExpect(status().isUnauthorized());
        }

        login(user.getEmail(), PASSWORD).andExpect(status().isUnauthorized());

        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getLockedUntil()).isNotNull();
    }

    @Test
    void aDisabledUsersExistingTokenStopsWorkingImmediately() throws Exception {
        User user = createUser(RoleName.FINANCE_MANAGER);
        String token = bearer(user);
        mockMvc.perform(get("/api/auth/me").header("Authorization", token)).andExpect(status().isOk());

        user.setEnabled(false);
        userRepository.save(user);

        mockMvc.perform(get("/api/auth/me").header("Authorization", token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aCorrelationIdSentByTheClientIsEchoedInHeaderAndBody() throws Exception {
        User user = createUser(RoleName.FINANCE_MANAGER);
        String correlationId = "3f1c9a52-6f0e-4a57-9d2b-1c8e7a4b0d11";

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", bearer(user))
                        .header("X-Correlation-Id", correlationId))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-Id", correlationId))
                .andExpect(jsonPath("$.correlationId").value(correlationId));
    }

    @Test
    void healthEndpointIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}

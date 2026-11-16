package com.finbridge.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finbridge.support.AbstractIntegrationTest;
import com.finbridge.user.RoleName;
import com.finbridge.user.User;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Authorization is enforced by the backend, whatever the frontend shows or hides. */
class RbacIT extends AbstractIntegrationTest {

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private Map<String, Object> newUserBody(String email) {
        return Map.of("email", email, "fullName", "New Person",
                "password", "a-long-password-1", "roles", List.of("FINANCE_MANAGER"));
    }

    @Test
    void financeManagerAndOperatorCannotReachUserManagement() throws Exception {
        for (RoleName role : List.of(RoleName.FINANCE_MANAGER, RoleName.INTEGRATION_OPERATOR)) {
            String token = bearer(createUser(role));

            mockMvc.perform(get("/api/users").header("Authorization", token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
            mockMvc.perform(get("/api/roles").header("Authorization", token))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/auth/register").header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(newUserBody("blocked" + nextNumber() + "@test.example"))))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void everyRoleCanReadInvoicesAndSuppliers() throws Exception {
        for (RoleName role : RoleName.values()) {
            String token = bearer(createUser(role));

            mockMvc.perform(get("/api/invoices").header("Authorization", token)).andExpect(status().isOk());
            mockMvc.perform(get("/api/suppliers").header("Authorization", token)).andExpect(status().isOk());
        }
    }

    @Test
    void adminCreatesAUserWhoCanThenLogIn() throws Exception {
        String admin = bearer(createUser(RoleName.ADMIN));
        String email = "created" + nextNumber() + "@test.example";

        mockMvc.perform(post("/api/auth/register").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content(json(newUserBody(email))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.email").value(email))
                .andExpect(jsonPath("$.data.roles[0]").value("FINANCE_MANAGER"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email, "password", "a-long-password-1"))))
                .andExpect(status().isOk());

        String storedHash = userRepository.findByEmail(email).orElseThrow().getPasswordHash();
        assertThat(storedHash).startsWith("$2").doesNotContain("a-long-password-1");
    }

    @Test
    void registeringAnExistingEmailIsA409() throws Exception {
        String admin = bearer(createUser(RoleName.ADMIN));
        User existing = createUser(RoleName.FINANCE_MANAGER);

        mockMvc.perform(post("/api/auth/register").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content(json(newUserBody(existing.getEmail()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));
    }

    @Test
    void registeringWithAShortPasswordIsA400() throws Exception {
        String admin = bearer(createUser(RoleName.ADMIN));
        Map<String, Object> body = Map.of("email", "weak" + nextNumber() + "@test.example",
                "fullName", "Weak Password", "password", "short", "roles", List.of("FINANCE_MANAGER"));

        mockMvc.perform(post("/api/auth/register").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("password"));
    }

    @Test
    void aRoleChangeTakesEffectOnTheNextRequestWithTheSameToken() throws Exception {
        String admin = bearer(createUser(RoleName.ADMIN));
        User user = createUser(RoleName.FINANCE_MANAGER);
        String userToken = bearer(user);
        mockMvc.perform(get("/api/users").header("Authorization", userToken)).andExpect(status().isForbidden());

        mockMvc.perform(put("/api/users/" + user.getId() + "/roles").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("roles", List.of("ADMIN")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roles[0]").value("ADMIN"));

        mockMvc.perform(get("/api/users").header("Authorization", userToken)).andExpect(status().isOk());
    }

    @Test
    void adminCanDisableAUser() throws Exception {
        String admin = bearer(createUser(RoleName.ADMIN));
        User user = createUser(RoleName.INTEGRATION_OPERATOR);

        mockMvc.perform(patch("/api/users/" + user.getId()).header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("enabled", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false));

        assertThat(userRepository.findById(user.getId()).orElseThrow().isEnabled()).isFalse();
    }
}

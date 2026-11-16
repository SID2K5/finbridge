package com.finbridge.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finbridge.support.AbstractIntegrationTest;
import com.finbridge.user.RoleName;
import org.junit.jupiter.api.Test;

/**
 * The real adapter with no API key (the state of a fresh checkout). The application must
 * start, say clearly that Odoo is not configured, and send nothing.
 */
class OdooNotConfiguredIT extends AbstractIntegrationTest {

    @Test
    void statusSaysRealModeAndNotConfigured() throws Exception {
        String token = bearer(createUser(RoleName.FINANCE_MANAGER));

        mockMvc.perform(get("/api/integrations/status").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.odoo.mode").value("REAL"))
                .andExpect(jsonPath("$.data.odoo.configured").value(false))
                .andExpect(jsonPath("$.data.odoo.state").value("NOT_CONFIGURED"))
                .andExpect(jsonPath("$.data.odoo.apiKey").doesNotExist());
    }

    @Test
    void startingASyncIsRefusedWithAnExplanation() throws Exception {
        String token = bearer(createUser(RoleName.INTEGRATION_OPERATOR));

        mockMvc.perform(post("/api/integrations/odoo/sync").header("Authorization", token))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("ODOO_API_KEY")));
    }

    @Test
    void theHealthCheckReportsTheMissingKeyInsteadOfFailing() throws Exception {
        String token = bearer(createUser(RoleName.ADMIN));

        mockMvc.perform(post("/api/integrations/health-check").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.odoo.ok").value(false))
                .andExpect(jsonPath("$.data.odoo.errorCode").value("ODOO_NOT_CONFIGURED"));
    }
}

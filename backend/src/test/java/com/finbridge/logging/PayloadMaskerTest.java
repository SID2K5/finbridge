package com.finbridge.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PayloadMaskerTest {

    @Test
    void masksSecretsInJson() {
        String masked = PayloadMasker.mask(
                "{\"login\":\"bot\",\"password\":\"hunter2-very-secret\",\"api_key\": \"abc123\",\"token\":12345}");

        assertThat(masked).doesNotContain("hunter2-very-secret", "abc123", "12345");
        assertThat(masked).contains("\"login\":\"bot\"", "\"password\":\"***\"", "\"api_key\": \"***\"");
    }

    @Test
    void masksAuthorizationHeadersAndBearerTokens() {
        String masked = PayloadMasker.mask("POST /json/2/res.partner/search_read\n"
                + "Authorization: bearer 0123456789abcdef0123456789abcdef01234567\nX-Sim-Api-Key: sim-key-value");

        assertThat(masked).doesNotContain("0123456789abcdef", "sim-key-value");
        assertThat(masked).contains("Authorization: ***", "X-Sim-Api-Key: ***");
    }

    @Test
    void masksSecretsInXml() {
        String masked = PayloadMasker.mask("<pay:Auth><pay:Password>s3cret</pay:Password><pay:User>fb</pay:User></pay:Auth>");

        assertThat(masked).doesNotContain("s3cret").contains("<pay:Password>***</pay:Password>", "<pay:User>fb</pay:User>");
    }

    @Test
    void leavesOrdinaryBusinessDataAlone() {
        String payload = "{\"domain\":[[\"move_type\",\"=\",\"in_invoice\"]],\"fields\":[\"id\",\"amount_total\"],\"limit\":100}";

        assertThat(PayloadMasker.mask(payload)).isEqualTo(payload);
    }

    @Test
    void capsVeryLargePayloads() {
        String masked = PayloadMasker.mask("x".repeat(PayloadMasker.MAX_LENGTH + 5000));

        assertThat(masked).hasSize(PayloadMasker.MAX_LENGTH).contains("[truncated, 21000 characters in total]");
    }

    @Test
    void nullStaysNull() {
        assertThat(PayloadMasker.mask(null)).isNull();
    }
}

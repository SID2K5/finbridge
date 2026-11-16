package com.finbridge.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class CorrelationIdFilterTest {

    @Test
    void keepsAValidUuidSentByTheClient() {
        String id = "3f1c9a52-6f0e-4a57-9d2b-1c8e7a4b0d11";

        assertThat(CorrelationIdFilter.resolve(id)).isEqualTo(id);
    }

    @Test
    void replacesAnythingThatIsNotAUuid() {
        String resolved = CorrelationIdFilter.resolve("abc\r\nFAKE LOG LINE");

        assertThat(resolved).doesNotContain("FAKE");
        assertThat(UUID.fromString(resolved)).isNotNull();
    }

    @Test
    void generatesAnIdWhenTheHeaderIsMissing() {
        assertThat(UUID.fromString(CorrelationIdFilter.resolve(null))).isNotNull();
    }
}

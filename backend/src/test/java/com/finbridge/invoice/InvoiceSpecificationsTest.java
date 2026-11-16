package com.finbridge.invoice;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class InvoiceSpecificationsTest {

    @Test
    void wrapsTheSearchTextForAContainsMatchIgnoringCase() {
        assertThat(InvoiceSpecifications.containsPattern("  BILL/2026 ")).isEqualTo("%bill/2026%");
    }

    @Test
    void escapesLikeWildcardsSoTheyAreSearchedLiterally() {
        assertThat(InvoiceSpecifications.containsPattern("100%_off!")).isEqualTo("%100!%!_off!!%");
    }
}

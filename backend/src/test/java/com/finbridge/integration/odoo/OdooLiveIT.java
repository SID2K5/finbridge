package com.finbridge.integration.odoo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.finbridge.logging.IntegrationLogService;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Talks to a REAL Odoo over the JSON-2 API. Skipped unless you point it at one:
 *
 *   ODOO_LIVE_URL      for example http://localhost:8069
 *   ODOO_LIVE_DB       the Odoo database name
 *   ODOO_LIVE_API_KEY  an API key of a user who can read Contacts and Invoicing
 *
 *   mvn verify -Dit.test=OdooLiveIT
 *
 * It only reads. It proves that the URL format, the headers, the field names and the
 * parsing all match the Odoo you actually run, which no mock can.
 */
@EnabledIfEnvironmentVariable(named = "ODOO_LIVE_API_KEY", matches = ".+")
class OdooLiveIT {

    private static OdooJson2Adapter adapter(String apiKey) {
        OdooProperties properties = new OdooProperties(
                System.getenv().getOrDefault("ODOO_LIVE_URL", "http://localhost:8069"),
                System.getenv().getOrDefault("ODOO_LIVE_DB", "finbridge"),
                apiKey, Duration.ofSeconds(5), Duration.ofSeconds(30), 1, Duration.ZERO, 100);
        return new OdooJson2Adapter(properties, mock(IntegrationLogService.class));
    }

    private static OdooJson2Adapter adapter() {
        return adapter(System.getenv("ODOO_LIVE_API_KEY"));
    }

    @Test
    void pingSucceedsWithTheRealKey() {
        adapter().ping();
    }

    @Test
    void aWrongKeyIsReportedAsAnAuthenticationError() {
        assertThatThrownBy(() -> adapter("0000000000000000000000000000000000000000").ping())
                .isInstanceOfSatisfying(OdooException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("ODOO_AUTH");
                    assertThat(ex.getMessage()).doesNotContain("Traceback");
                });
    }

    @Test
    void suppliersAndVendorBillsCanBeReadWithTheFieldsTheMapperExpects() {
        List<OdooPartner> suppliers = adapter().fetchSuppliers(null, 0, 100, "live-test");
        List<OdooVendorBill> bills = adapter().fetchVendorBills(null, 0, 100, "live-test");

        System.out.println("LIVE ODOO: " + suppliers.size() + " supplier(s), " + bills.size() + " posted vendor bill(s)");
        suppliers.forEach(supplier -> System.out.println("  supplier " + supplier));
        bills.forEach(bill -> System.out.println("  bill " + bill));

        assertThat(suppliers).allSatisfy(supplier -> {
            assertThat(supplier.id()).isPositive();
            assertThat(supplier.writeDate()).isNotNull();
        });
        assertThat(bills).allSatisfy(bill -> {
            assertThat(bill.number()).isNotBlank();
            assertThat(bill.partnerId()).isNotNull();
            assertThat(bill.totalAmount()).isNotNull();
            assertThat(bill.currency()).matches("[A-Z]{3}");
            assertThat(bill.writeDate()).isNotNull();
        });
        if (!bills.isEmpty()) {
            List<Long> partnerIds = bills.stream().map(OdooVendorBill::partnerId).distinct().toList();
            assertThat(adapter().fetchPartnersByIds(partnerIds, "live-test")).hasSameSizeAs(partnerIds);
        }
    }
}

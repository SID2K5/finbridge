package com.finbridge.api;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finbridge.integration.odoo.history.SyncRun;
import com.finbridge.integration.odoo.history.SyncRunRepository;
import com.finbridge.integration.odoo.history.SynchronizationHistory;
import com.finbridge.integration.odoo.history.SynchronizationHistoryRepository;
import com.finbridge.invoice.Invoice;
import com.finbridge.invoice.InvoiceStatus;
import com.finbridge.supplier.Supplier;
import com.finbridge.support.AbstractIntegrationTest;
import com.finbridge.user.RoleName;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;

class InvoiceIT extends AbstractIntegrationTest {

    @Autowired
    private SyncRunRepository syncRunRepository;
    @Autowired
    private SynchronizationHistoryRepository historyRepository;

    private String token;
    private Supplier supplier;
    private String prefix;
    private Invoice october;
    private Invoice november;
    private Invoice december;

    @BeforeEach
    void createData() {
        token = bearer(createUser(RoleName.FINANCE_MANAGER));
        long n = nextNumber();
        prefix = "IT" + n;
        supplier = createSupplier("Supplier " + n);
        october = createInvoice(supplier, prefix + "/OCT/0001", LocalDate.of(2026, 10, 5), "59000.0000");
        november = createInvoice(supplier, prefix + "/NOV/0002", LocalDate.of(2026, 11, 12), "14750.5000");
        december = createInvoice(supplier, prefix + "/DEC/0003", LocalDate.of(2026, 12, 1), "99120.0000");
        // An invoice of another supplier that must never appear in this supplier's results.
        createInvoice(createSupplier("Other supplier " + n), "OTHER" + n, LocalDate.of(2026, 11, 12), "1.0000");
    }

    @Test
    void filterBySupplierReturnsOnlyThatSuppliersInvoicesNewestFirst() throws Exception {
        mockMvc.perform(get("/api/invoices").header("Authorization", token)
                        .param("supplierId", supplier.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.totalElements").value(3))
                .andExpect(jsonPath("$.meta.page").value(0))
                .andExpect(jsonPath("$.data[*].invoiceNumber").value(contains(
                        december.getInvoiceNumber(), november.getInvoiceNumber(), october.getInvoiceNumber())))
                .andExpect(jsonPath("$.data[0].supplierName").value(supplier.getName()));
    }

    @Test
    void searchByInvoiceNumberIsAContainsMatchIgnoringCase() throws Exception {
        mockMvc.perform(get("/api/invoices").header("Authorization", token)
                        .param("invoiceNumber", (prefix + "/nov").toLowerCase()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value(november.getId()));
    }

    @Test
    void filterByDateRangeIsInclusive() throws Exception {
        mockMvc.perform(get("/api/invoices").header("Authorization", token)
                        .param("supplierId", supplier.getId().toString())
                        .param("dateFrom", "2026-11-12")
                        .param("dateTo", "2026-12-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].invoiceNumber").value(containsInAnyOrder(
                        november.getInvoiceNumber(), december.getInvoiceNumber())));
    }

    @Test
    void filterByStatus() throws Exception {
        october.setInvoiceStatus(InvoiceStatus.PAID);
        invoiceRepository.save(october);

        mockMvc.perform(get("/api/invoices").header("Authorization", token)
                        .param("supplierId", supplier.getId().toString())
                        .param("status", "PAID"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].invoiceStatus").value("PAID"));
    }

    @Test
    void sortByAmountAndPageThroughResults() throws Exception {
        mockMvc.perform(get("/api/invoices").header("Authorization", token)
                        .param("supplierId", supplier.getId().toString())
                        .param("sort", "totalAmount,asc")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.totalPages").value(2))
                .andExpect(jsonPath("$.data[*].id").value(contains(
                        november.getId().intValue(), october.getId().intValue())));
    }

    @Test
    void invalidQueryParametersAre400() throws Exception {
        mockMvc.perform(get("/api/invoices").header("Authorization", token).param("sort", "odooMoveId,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mockMvc.perform(get("/api/invoices").header("Authorization", token).param("status", "NOT_A_STATUS"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/invoices").header("Authorization", token).param("size", "101"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/invoices").header("Authorization", token)
                        .param("dateFrom", "2026-12-01").param("dateTo", "2026-11-01"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invoiceDetailKeepsExactDecimalAmounts() throws Exception {
        mockMvc.perform(get("/api/invoices/" + november.getId()).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.invoiceNumber").value(november.getInvoiceNumber()))
                .andExpect(jsonPath("$.data.totalAmount").value(14750.5))
                .andExpect(jsonPath("$.data.currency").value("INR"))
                .andExpect(jsonPath("$.data.supplier.id").value(supplier.getId()))
                .andExpect(jsonPath("$.data.syncStatus").value("SYNCED"));
    }

    @Test
    void unknownInvoiceIs404() throws Exception {
        mockMvc.perform(get("/api/invoices/999999999").header("Authorization", token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void theDatabaseRefusesASecondInvoiceWithTheSameOdooId() {
        Invoice duplicate = new Invoice(october.getOdooMoveId(), "DUPLICATE", supplier, LocalDate.of(2026, 10, 5),
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, "INR", Instant.now(), Instant.now());

        assertThatThrownBy(() -> invoiceRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theDatabaseRefusesANegativeInvoiceTotal() {
        Invoice negative = new Invoice(nextNumber(), "NEGATIVE", supplier, LocalDate.of(2026, 10, 5),
                BigDecimal.ONE, BigDecimal.ZERO, new BigDecimal("-1.0000"), "INR", Instant.now(), Instant.now());

        // MySQL reports a CHECK violation with its own error code; depending on the Hibernate
        // version Spring translates it to DataIntegrityViolationException or a more general
        // DataAccessException. Either way the row must be refused.
        assertThatThrownBy(() -> invoiceRepository.saveAndFlush(negative))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void invoiceHistoryListsWhatSynchronizationDidToTheInvoice() throws Exception {
        SyncRun run = syncRunRepository.save(new SyncRun("MANUAL", null, "COMPLETED", Instant.now()));
        historyRepository.save(new SynchronizationHistory(run.getId(), "INVOICE", october.getOdooMoveId(),
                october.getId(), "CREATED", "Created from Odoo"));

        mockMvc.perform(get("/api/invoices/" + october.getId() + "/history").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].action").value("CREATED"))
                .andExpect(jsonPath("$.data[0].syncRunId").value(run.getId()));

        mockMvc.perform(get("/api/invoices/999999999/history").header("Authorization", token))
                .andExpect(status().isNotFound());
    }
}

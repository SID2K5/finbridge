package com.finbridge.invoice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finbridge.supplier.Supplier;
import com.finbridge.supplier.SupplierSyncService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InvoiceSyncServiceTest {

    private static final Instant WRITTEN = Instant.parse("2026-11-10T09:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");

    private final InvoiceRepository invoiceRepository = mock(InvoiceRepository.class);
    private final SupplierSyncService supplierSyncService = mock(SupplierSyncService.class);
    private final InvoiceSyncService service = new InvoiceSyncService(invoiceRepository, supplierSyncService);
    private final Supplier supplier = new Supplier(42L, "Acme", WRITTEN);

    @BeforeEach
    void supplierExists() {
        when(supplierSyncService.findByOdooPartnerId(42L)).thenReturn(Optional.of(supplier));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(call -> call.getArgument(0));
    }

    private static InvoiceSyncData bill(String total, Instant writeDate) {
        return new InvoiceSyncData(7L, "BILL/2026/11/0001", "REF-1", "P00001", 42L, LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 12, 1), new BigDecimal("100.00"), new BigDecimal("18.00"),
                total == null ? null : new BigDecimal(total), "INR", "not_paid", writeDate);
    }

    private Invoice stored(String total, InvoiceStatus status) {
        Invoice invoice = new Invoice(7L, "BILL/2026/11/0001", supplier, LocalDate.of(2026, 11, 1),
                new BigDecimal("100.0000"), new BigDecimal("18.0000"), new BigDecimal(total), "INR", WRITTEN, WRITTEN);
        invoice.setInvoiceStatus(status);
        when(invoiceRepository.findByOdooMoveId(7L)).thenReturn(Optional.of(invoice));
        return invoice;
    }

    @Test
    void aValidBillHasNoRejectionReason() {
        assertThat(service.rejectionReason(bill("118.00", WRITTEN))).isNull();
    }

    @Test
    void billsMissingRequiredDataAreRejectedWithAReason() {
        InvoiceSyncData valid = bill("118.00", WRITTEN);

        assertThat(service.rejectionReason(new InvoiceSyncData(7L, "B/1", null, null, null, valid.invoiceDate(), null,
                null, null, valid.totalAmount(), "INR", null, WRITTEN))).contains("no supplier");
        assertThat(service.rejectionReason(new InvoiceSyncData(7L, "B/1", null, null, 42L, null, null,
                null, null, valid.totalAmount(), "INR", null, WRITTEN))).contains("no invoice date");
        assertThat(service.rejectionReason(new InvoiceSyncData(7L, "B/1", null, null, 42L, valid.invoiceDate(), null,
                null, null, valid.totalAmount(), null, null, WRITTEN))).contains("no currency");
        assertThat(service.rejectionReason(new InvoiceSyncData(7L, "B/1", null, null, 42L, valid.invoiceDate(), null,
                null, null, valid.totalAmount(), "Rupees", null, WRITTEN))).contains("three-letter");
        assertThat(service.rejectionReason(bill("-1.00", WRITTEN))).contains("negative");
        assertThat(service.rejectionReason(bill(null, WRITTEN))).contains("no total");
    }

    @Test
    void anInvalidBillIsNeverStored() {
        InvoiceSyncService.Outcome outcome = service.apply(bill("-5.00", WRITTEN), NOW);

        assertThat(outcome.action()).isEqualTo(InvoiceSyncService.Action.REJECTED_INVALID);
        assertThat(outcome.invoiceId()).isNull();
        assertThat(outcome.message()).contains("negative");
        verify(invoiceRepository, never()).save(any());
    }

    @Test
    void aNewBillIsCreatedWithFourDecimalAmounts() {
        when(invoiceRepository.findByOdooMoveId(7L)).thenReturn(Optional.empty());

        InvoiceSyncService.Outcome outcome = service.apply(bill("118.005", WRITTEN), NOW);

        assertThat(outcome.action()).isEqualTo(InvoiceSyncService.Action.CREATED);
        verify(invoiceRepository).save(org.mockito.ArgumentMatchers.argThat(invoice ->
                invoice.getTotalAmount().toPlainString().equals("118.0050")
                        && invoice.getSyncStatus() == SyncStatus.SYNCED
                        && invoice.getInvoiceStatus() == InvoiceStatus.OPEN
                        && invoice.getLastSyncedAt().equals(NOW)));
    }

    @Test
    void theSameWriteDateMeansUnchangedAndNothingIsTouched() {
        Invoice invoice = stored("118.0000", InvoiceStatus.OPEN);

        InvoiceSyncService.Outcome outcome = service.apply(bill("999.00", WRITTEN), NOW);

        assertThat(outcome.action()).isEqualTo(InvoiceSyncService.Action.UNCHANGED);
        assertThat(invoice.getTotalAmount()).isEqualByComparingTo("118");
        assertThat(invoice.getSyncStatus()).isEqualTo(SyncStatus.SYNCED);
    }

    @Test
    void aLaterWriteDateUpdatesAnOpenInvoice() {
        Invoice invoice = stored("118.0000", InvoiceStatus.OPEN);

        InvoiceSyncService.Outcome outcome = service.apply(bill("236.00", WRITTEN.plusSeconds(60)), NOW);

        assertThat(outcome.action()).isEqualTo(InvoiceSyncService.Action.UPDATED);
        assertThat(invoice.getTotalAmount()).isEqualByComparingTo("236");
        assertThat(invoice.getSyncStatus()).isEqualTo(SyncStatus.UPDATED);
        assertThat(invoice.getOdooWriteDate()).isEqualTo(WRITTEN.plusSeconds(60));
    }

    @Test
    void aPaidInvoiceWhoseAmountChangesInOdooIsKeptAndMarkedStale() {
        Invoice invoice = stored("118.0000", InvoiceStatus.PAID);

        InvoiceSyncService.Outcome outcome = service.apply(bill("236.00", WRITTEN.plusSeconds(60)), NOW);

        assertThat(outcome.action()).isEqualTo(InvoiceSyncService.Action.UPDATED);
        assertThat(outcome.message()).contains("STALE");
        assertThat(invoice.getTotalAmount()).isEqualByComparingTo("118");
        assertThat(invoice.getSyncStatus()).isEqualTo(SyncStatus.STALE);
    }

    @Test
    void aBillWhoseSupplierCannotBeFoundIsRejected() {
        when(supplierSyncService.findByOdooPartnerId(42L)).thenReturn(Optional.empty());

        InvoiceSyncService.Outcome outcome = service.apply(bill("118.00", WRITTEN), NOW);

        assertThat(outcome.action()).isEqualTo(InvoiceSyncService.Action.REJECTED_INVALID);
        assertThat(outcome.message()).contains("partner 42");
    }
}

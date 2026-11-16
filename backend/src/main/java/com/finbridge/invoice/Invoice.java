package com.finbridge.invoice;

import com.finbridge.common.AuditedEntity;
import com.finbridge.supplier.Supplier;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A vendor bill copied from Odoo's account.move. odooMoveId is the unique external
 * reference: the database refuses a second row with the same value, which is the final
 * guard against duplicate synchronization. Amounts are BigDecimal / DECIMAL(19,4), never
 * floating point.
 */
@Entity
@Table(name = "invoices")
public class Invoice extends AuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "odoo_move_id", nullable = false, unique = true, updatable = false)
    private Long odooMoveId;

    @Column(name = "invoice_number", nullable = false, length = 64)
    private String invoiceNumber;

    @Column(name = "vendor_reference", length = 128)
    private String vendorReference;

    @Column(name = "po_reference", length = 64)
    private String poReference;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @Column(name = "invoice_date", nullable = false)
    private LocalDate invoiceDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "untaxed_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal untaxedAmount;

    @Column(name = "tax_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal taxAmount;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalAmount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "invoice_status", nullable = false, length = 24)
    private InvoiceStatus invoiceStatus = InvoiceStatus.OPEN;

    @Column(name = "odoo_payment_state", length = 24)
    private String odooPaymentState;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "sync_status", nullable = false, length = 24)
    private SyncStatus syncStatus = SyncStatus.SYNCED;

    @Column(name = "odoo_write_date", nullable = false)
    private Instant odooWriteDate;

    @Column(name = "last_synced_at", nullable = false)
    private Instant lastSyncedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    protected Invoice() {
    }

    public Invoice(Long odooMoveId, String invoiceNumber, Supplier supplier, LocalDate invoiceDate,
                   BigDecimal untaxedAmount, BigDecimal taxAmount, BigDecimal totalAmount, String currency,
                   Instant odooWriteDate, Instant lastSyncedAt) {
        this.odooMoveId = odooMoveId;
        this.invoiceNumber = invoiceNumber;
        this.supplier = supplier;
        this.invoiceDate = invoiceDate;
        this.untaxedAmount = untaxedAmount;
        this.taxAmount = taxAmount;
        this.totalAmount = totalAmount;
        this.currency = currency;
        this.odooWriteDate = odooWriteDate;
        this.lastSyncedAt = lastSyncedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getOdooMoveId() {
        return odooMoveId;
    }

    public String getInvoiceNumber() {
        return invoiceNumber;
    }

    public void setInvoiceNumber(String invoiceNumber) {
        this.invoiceNumber = invoiceNumber;
    }

    public String getVendorReference() {
        return vendorReference;
    }

    public void setVendorReference(String vendorReference) {
        this.vendorReference = vendorReference;
    }

    public String getPoReference() {
        return poReference;
    }

    public void setPoReference(String poReference) {
        this.poReference = poReference;
    }

    public Supplier getSupplier() {
        return supplier;
    }

    public void setSupplier(Supplier supplier) {
        this.supplier = supplier;
    }

    public LocalDate getInvoiceDate() {
        return invoiceDate;
    }

    public void setInvoiceDate(LocalDate invoiceDate) {
        this.invoiceDate = invoiceDate;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    public BigDecimal getUntaxedAmount() {
        return untaxedAmount;
    }

    public BigDecimal getTaxAmount() {
        return taxAmount;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setAmounts(BigDecimal untaxedAmount, BigDecimal taxAmount, BigDecimal totalAmount) {
        this.untaxedAmount = untaxedAmount;
        this.taxAmount = taxAmount;
        this.totalAmount = totalAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public InvoiceStatus getInvoiceStatus() {
        return invoiceStatus;
    }

    public void setInvoiceStatus(InvoiceStatus invoiceStatus) {
        this.invoiceStatus = invoiceStatus;
    }

    public String getOdooPaymentState() {
        return odooPaymentState;
    }

    public void setOdooPaymentState(String odooPaymentState) {
        this.odooPaymentState = odooPaymentState;
    }

    public SyncStatus getSyncStatus() {
        return syncStatus;
    }

    public void setSyncStatus(SyncStatus syncStatus) {
        this.syncStatus = syncStatus;
    }

    public Instant getOdooWriteDate() {
        return odooWriteDate;
    }

    public void setOdooWriteDate(Instant odooWriteDate) {
        this.odooWriteDate = odooWriteDate;
    }

    public Instant getLastSyncedAt() {
        return lastSyncedAt;
    }

    public void setLastSyncedAt(Instant lastSyncedAt) {
        this.lastSyncedAt = lastSyncedAt;
    }

    public Long getVersion() {
        return version;
    }
}

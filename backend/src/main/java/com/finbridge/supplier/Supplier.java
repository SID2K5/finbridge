package com.finbridge.supplier;

import com.finbridge.common.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A vendor, copied from Odoo's res.partner. odooPartnerId is the unique external reference. */
@Entity
@Table(name = "suppliers")
public class Supplier extends AuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "odoo_partner_id", nullable = false, unique = true)
    private Long odooPartnerId;

    @Column(nullable = false)
    private String name;

    @Column(name = "tax_id", length = 64)
    private String taxId;

    @Column(length = 190)
    private String email;

    @Column(length = 40)
    private String phone;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "country_code", length = 2)
    private String countryCode;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "odoo_write_date", nullable = false)
    private Instant odooWriteDate;

    protected Supplier() {
    }

    public Supplier(Long odooPartnerId, String name, Instant odooWriteDate) {
        this.odooPartnerId = odooPartnerId;
        this.name = name;
        this.odooWriteDate = odooWriteDate;
    }

    public Long getId() {
        return id;
    }

    public Long getOdooPartnerId() {
        return odooPartnerId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTaxId() {
        return taxId;
    }

    public void setTaxId(String taxId) {
        this.taxId = taxId;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getCountryCode() {
        return countryCode;
    }

    public void setCountryCode(String countryCode) {
        this.countryCode = countryCode;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Instant getOdooWriteDate() {
        return odooWriteDate;
    }

    public void setOdooWriteDate(Instant odooWriteDate) {
        this.odooWriteDate = odooWriteDate;
    }
}

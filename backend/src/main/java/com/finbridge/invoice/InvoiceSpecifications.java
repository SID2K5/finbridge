package com.finbridge.invoice;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

/**
 * Turns an InvoiceFilter into a WHERE clause. Values are always bound as query
 * parameters by JPA, never concatenated into SQL.
 */
public final class InvoiceSpecifications {

    private static final char ESCAPE = '!';

    private InvoiceSpecifications() {
    }

    public static Specification<Invoice> from(InvoiceFilter filter) {
        List<Specification<Invoice>> parts = new ArrayList<>();
        if (filter.invoiceNumber() != null && !filter.invoiceNumber().isBlank()) {
            String pattern = containsPattern(filter.invoiceNumber());
            parts.add((root, query, cb) ->
                    cb.like(cb.lower(root.<String>get("invoiceNumber")), pattern, ESCAPE));
        }
        if (filter.supplierId() != null) {
            parts.add((root, query, cb) -> cb.equal(root.get("supplier").get("id"), filter.supplierId()));
        }
        if (filter.status() != null) {
            parts.add((root, query, cb) -> cb.equal(root.get("invoiceStatus"), filter.status()));
        }
        if (filter.syncStatus() != null) {
            parts.add((root, query, cb) -> cb.equal(root.get("syncStatus"), filter.syncStatus()));
        }
        if (filter.dateFrom() != null) {
            parts.add((root, query, cb) ->
                    cb.greaterThanOrEqualTo(root.<LocalDate>get("invoiceDate"), filter.dateFrom()));
        }
        if (filter.dateTo() != null) {
            parts.add((root, query, cb) ->
                    cb.lessThanOrEqualTo(root.<LocalDate>get("invoiceDate"), filter.dateTo()));
        }
        return Specification.allOf(parts);
    }

    /** "%text%" with the LIKE wildcards in the user's text escaped, so "%" searches for a literal percent sign. */
    static String containsPattern(String text) {
        String escaped = text.trim().toLowerCase(Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + escaped + "%";
    }
}

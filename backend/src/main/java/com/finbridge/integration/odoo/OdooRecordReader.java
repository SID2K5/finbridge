package com.finbridge.integration.odoo;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.Locale;

/**
 * Turns Odoo's JSON records into FinBridge records. This is the one place that knows
 * Odoo's conventions:
 *   - an empty field is the JSON value false, not null;
 *   - a many2one is [id, "display name"];
 *   - a datetime is "yyyy-MM-dd HH:mm:ss" in UTC, a date is "yyyy-MM-dd".
 */
final class OdooRecordReader {

    /** Odoo's datetime format, also used to build the write_date filter. Fractions are tolerated on input. */
    static final DateTimeFormatter ODOO_DATETIME = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss")
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd()
            .toFormatter(Locale.ROOT);

    private static final DateTimeFormatter ODOO_DATETIME_OUT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    private OdooRecordReader() {
    }

    static String formatDateTime(Instant instant) {
        return ODOO_DATETIME_OUT.format(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
    }

    static OdooPartner partner(JsonNode node) {
        long id = requiredId(node, "res.partner");
        String name = text(node, "name");
        if (name == null) {
            name = text(node, "display_name");
        }
        JsonNode active = node.get("active");
        return new OdooPartner(
                id,
                name,
                text(node, "vat"),
                text(node, "email"),
                text(node, "phone"),
                text(node, "country_code"),
                active == null || active.asBoolean(true),
                requiredWriteDate(node, "res.partner", id));
    }

    static OdooVendorBill bill(JsonNode node) {
        long id = requiredId(node, "account.move");
        return new OdooVendorBill(
                id,
                text(node, "name"),
                text(node, "ref"),
                many2oneId(node, "partner_id"),
                date(node, "invoice_date", id),
                date(node, "invoice_date_due", id),
                decimal(node, "amount_untaxed"),
                decimal(node, "amount_tax"),
                decimal(node, "amount_total"),
                many2oneName(node, "currency_id"),
                text(node, "payment_state"),
                text(node, "invoice_origin"),
                requiredWriteDate(node, "account.move", id));
    }

    private static long requiredId(JsonNode node, String model) {
        JsonNode id = node.get("id");
        if (id == null || !id.canConvertToLong()) {
            throw new OdooException(OdooException.Kind.BAD_RESPONSE, 200, model + " record without an id");
        }
        return id.asLong();
    }

    private static Instant requiredWriteDate(JsonNode node, String model, long id) {
        String value = text(node, "write_date");
        if (value == null) {
            throw new OdooException(OdooException.Kind.BAD_RESPONSE, 200,
                    model + " " + id + " has no write_date");
        }
        try {
            return LocalDateTime.parse(value, ODOO_DATETIME).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ex) {
            throw new OdooException(OdooException.Kind.BAD_RESPONSE, 200,
                    model + " " + id + " has an unreadable write_date");
        }
    }

    /** The field as trimmed text, or null when Odoo sent false, null, nothing or an empty string. */
    static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.isBoolean() || value.isContainerNode()) {
            return null;
        }
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }

    private static Long many2oneId(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value != null && value.isArray() && value.size() >= 1 && value.get(0).canConvertToLong()) {
            return value.get(0).asLong();
        }
        if (value != null && value.isIntegralNumber()) {
            return value.asLong();
        }
        return null;
    }

    private static String many2oneName(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value != null && value.isArray() && value.size() >= 2 && value.get(1).isTextual()) {
            String text = value.get(1).asText().trim();
            return text.isEmpty() ? null : text;
        }
        return null;
    }

    private static LocalDate date(JsonNode node, String field, long id) {
        String value = text(node, field);
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ex) {
            throw new OdooException(OdooException.Kind.BAD_RESPONSE, 200,
                    "account.move " + id + " has an unreadable " + field);
        }
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber() ? value.decimalValue() : null;
    }
}

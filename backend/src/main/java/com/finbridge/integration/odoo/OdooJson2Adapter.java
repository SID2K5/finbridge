package com.finbridge.integration.odoo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finbridge.logging.IntegrationLogEntry;
import com.finbridge.logging.IntegrationLogEntry.Outcome;
import com.finbridge.logging.IntegrationLogEntry.Type;
import com.finbridge.logging.IntegrationLogService;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * The real Odoo adapter: Odoo 19 External JSON-2 API.
 *
 *   POST {base-url}/json/2/{model}/{method}
 *   Authorization: bearer {API key}       X-Odoo-Database: {database}
 *   body: a JSON object of named arguments
 *   200: the method's return value as bare JSON; 4xx/5xx: {name, message, arguments, context, debug}
 *
 * JSON-2 is RPC over HTTP, not resource-style REST. XML-RPC and JSON-RPC are deprecated in
 * Odoo 19 and are not used anywhere.
 *
 * Every attempt writes one integration_logs row. Timeouts, connection failures and 5xx
 * answers are retried (all calls here are reads, so a retry cannot duplicate anything);
 * 401/403 and other 4xx answers are not.
 */
@Component
@Profile("!mock-odoo")
public class OdooJson2Adapter implements OdooGateway {

    static final List<String> PARTNER_FIELDS = List.of(
            "id", "name", "display_name", "vat", "email", "phone", "country_code", "active", "write_date");

    static final List<String> BILL_FIELDS = List.of(
            "id", "name", "ref", "partner_id", "invoice_date", "invoice_date_due",
            "amount_untaxed", "amount_tax", "amount_total", "currency_id", "payment_state",
            "invoice_origin", "write_date");

    private static final String ORDER = "write_date asc, id asc";
    private static final String USER_AGENT = "finbridge-backend";

    // Amounts are read as BigDecimal straight from the JSON text, never through a double.
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private final OdooProperties properties;
    private final IntegrationLogService integrationLog;
    private final RestClient restClient;

    @Autowired
    public OdooJson2Adapter(OdooProperties properties, IntegrationLogService integrationLog) {
        this(properties, integrationLog, RestClient.builder().requestFactory(requestFactory(properties)));
    }

    /** For tests: lets a mock server stand in for Odoo. */
    OdooJson2Adapter(OdooProperties properties, IntegrationLogService integrationLog, RestClient.Builder builder) {
        this.properties = properties;
        this.integrationLog = integrationLog;
        this.restClient = builder.baseUrl(properties.baseUrl().replaceAll("/+$", "")).build();
    }

    private static JdkClientHttpRequestFactory requestFactory(OdooProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.readTimeout());
        return factory;
    }

    @Override
    public Mode mode() {
        return Mode.REAL;
    }

    @Override
    public boolean isConfigured() {
        return properties.hasApiKey();
    }

    @Override
    public void ping() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("domain", List.of());
        body.put("fields", List.of("id"));
        body.put("limit", 1);
        call("res.partner", "search_read", body, null);
    }

    @Override
    public List<OdooPartner> fetchSuppliers(Instant since, int offset, int limit, String reference) {
        List<Object> domain = new ArrayList<>();
        domain.add(List.of("supplier_rank", ">", 0));
        addSince(domain, since);
        Map<String, Object> body = searchRead(domain, PARTNER_FIELDS, offset, limit);
        // Archived suppliers must still arrive, so that FinBridge can mark them inactive.
        body.put("context", Map.of("active_test", false));
        return records(call("res.partner", "search_read", body, reference), OdooRecordReader::partner);
    }

    @Override
    public List<OdooPartner> fetchPartnersByIds(Collection<Long> ids, String reference) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Object> domain = List.of(List.of("id", "in", List.copyOf(ids)));
        Map<String, Object> body = searchRead(domain, PARTNER_FIELDS, 0, ids.size());
        body.put("context", Map.of("active_test", false));
        return records(call("res.partner", "search_read", body, reference), OdooRecordReader::partner);
    }

    @Override
    public List<OdooVendorBill> fetchVendorBills(Instant since, int offset, int limit, String reference) {
        List<Object> domain = new ArrayList<>();
        domain.add(List.of("move_type", "=", "in_invoice"));
        domain.add(List.of("state", "=", "posted"));
        addSince(domain, since);
        Map<String, Object> body = searchRead(domain, BILL_FIELDS, offset, limit);
        return records(call("account.move", "search_read", body, reference), OdooRecordReader::bill);
    }

    private static void addSince(List<Object> domain, Instant since) {
        if (since != null) {
            domain.add(List.of("write_date", ">=", OdooRecordReader.formatDateTime(since)));
        }
    }

    private static Map<String, Object> searchRead(List<Object> domain, List<String> fields, int offset, int limit) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("domain", domain);
        body.put("fields", fields);
        body.put("offset", offset);
        body.put("limit", limit);
        body.put("order", ORDER);
        return body;
    }

    private static <T> List<T> records(JsonNode result, Function<JsonNode, T> reader) {
        if (!result.isArray()) {
            throw new OdooException(OdooException.Kind.BAD_RESPONSE, 200, "Expected a list of records from Odoo");
        }
        List<T> records = new ArrayList<>(result.size());
        result.forEach(node -> records.add(reader.apply(node)));
        return records;
    }

    /** One JSON-2 call with retries. Returns the parsed return value or throws OdooException. */
    JsonNode call(String model, String method, Map<String, Object> body, String reference) {
        if (!isConfigured()) {
            throw new OdooException(OdooException.Kind.NOT_CONFIGURED, null,
                    "ODOO_API_KEY is not set. Create an API key for the integration user in Odoo and add it to .env");
        }
        String operation = model + "/" + method;
        String requestJson = toJson(body);
        OdooException last = null;
        for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
            if (attempt > 1) {
                pause(properties.retryBackoff().toMillis() * (attempt - 1));
            }
            try {
                return attempt(model, method, operation, requestJson, reference);
            } catch (OdooException ex) {
                last = ex;
                if (!ex.getKind().retryable()) {
                    break;
                }
            }
        }
        throw last;
    }

    private JsonNode attempt(String model, String method, String operation, String requestJson, String reference) {
        long started = System.nanoTime();
        RawResponse response;
        try {
            response = restClient.post()
                    .uri("/json/2/{model}/{method}", model, method)
                    .header("Authorization", "bearer " + properties.apiKey().trim())
                    .header("X-Odoo-Database", properties.database())
                    .header("User-Agent", USER_AGENT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(requestJson)
                    .exchange((request, raw) -> new RawResponse(
                            raw.getStatusCode().value(),
                            new String(raw.getBody().readAllBytes(), StandardCharsets.UTF_8)));
        } catch (ResourceAccessException ex) {
            boolean timeout = isTimeout(ex);
            OdooException.Kind kind = timeout ? OdooException.Kind.TIMEOUT : OdooException.Kind.UNREACHABLE;
            String message = timeout
                    ? "Odoo did not answer in time"
                    : "Odoo could not be reached at " + properties.baseUrl();
            integrationLog.record(IntegrationLogEntry.failure(Type.ODOO, operation, reference,
                    timeout ? Outcome.TIMEOUT : Outcome.FAILURE, null, elapsedMs(started),
                    kind.code(), message, requestJson, null, true));
            throw new OdooException(kind, null, message, ex);
        }

        long durationMs = elapsedMs(started);
        int status = response.status();
        if (status >= 200 && status < 300) {
            JsonNode result;
            try {
                result = JSON.readTree(response.body());
            } catch (JsonProcessingException ex) {
                result = null;
            }
            if (result == null) {
                String message = "Odoo answered " + status + " with a body that is not JSON";
                integrationLog.record(IntegrationLogEntry.failure(Type.ODOO, operation, reference, Outcome.FAILURE,
                        status, durationMs, OdooException.Kind.BAD_RESPONSE.code(), message, requestJson,
                        response.body(), false));
                throw new OdooException(OdooException.Kind.BAD_RESPONSE, status, message);
            }
            integrationLog.record(IntegrationLogEntry.success(Type.ODOO, operation, reference, status, durationMs,
                    requestJson, response.body()));
            return result;
        }

        OdooException.Kind kind;
        if (status == 401 || status == 403) {
            kind = OdooException.Kind.AUTH;
        } else if (status >= 500) {
            kind = OdooException.Kind.SERVER_ERROR;
        } else {
            kind = OdooException.Kind.REJECTED;
        }
        OdooError error = OdooError.parse(response.body(), status);
        integrationLog.record(IntegrationLogEntry.failure(Type.ODOO, operation, reference, Outcome.FAILURE,
                status, durationMs, kind.code(), error.description(), requestJson, error.safeJson(),
                kind.retryable()));
        throw new OdooException(kind, status, error.description());
    }

    private static boolean isTimeout(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof java.net.SocketTimeoutException) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private static void pause(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new OdooException(OdooException.Kind.UNREACHABLE, null, "Interrupted while waiting to retry", ex);
        }
    }

    private static String toJson(Map<String, Object> body) {
        try {
            return JSON.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not build the Odoo request body", ex);
        }
    }

    private record RawResponse(int status, String body) {
    }

    /**
     * Odoo's error object reduced to what may be stored and shown: the exception name and
     * message. The "debug" traceback is dropped here and never leaves this class.
     */
    private record OdooError(String name, String message, int status) {

        static OdooError parse(String body, int status) {
            try {
                JsonNode node = JSON.readTree(body);
                if (node != null && node.isObject()) {
                    return new OdooError(OdooRecordReader.text(node, "name"),
                            OdooRecordReader.text(node, "message"), status);
                }
            } catch (JsonProcessingException ignored) {
                // not JSON (for example an HTML error page from a proxy): fall through
            }
            return new OdooError(null, null, status);
        }

        String description() {
            if (name == null && message == null) {
                return "Odoo answered HTTP " + status;
            }
            return "HTTP " + status + " " + (name != null ? name : "error") + (message != null ? ": " + message : "");
        }

        String safeJson() {
            Map<String, Object> safe = new LinkedHashMap<>();
            safe.put("name", name);
            safe.put("message", message);
            return toJson(safe);
        }
    }
}

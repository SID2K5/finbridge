package com.finbridge.integration.monitoring;

import com.finbridge.integration.external.SimulatorRestClient;
import com.finbridge.integration.odoo.OdooException;
import com.finbridge.integration.odoo.OdooGateway;
import com.finbridge.integration.odoo.OdooProperties;
import com.finbridge.integration.odoo.sync.SyncRunResponse;
import com.finbridge.integration.odoo.sync.SyncService;
import com.finbridge.integration.soap.PaymentGateway;
import com.finbridge.integration.soap.PaymentGatewayException;
import com.finbridge.integration.soap.PaymentProperties;
import com.finbridge.logging.IntegrationLog;
import com.finbridge.logging.IntegrationLogRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * What the Integration Monitoring page shows. The Odoo state is derived from the most
 * recent real call recorded in integration_logs, so it reflects what actually happened
 * rather than a flag kept in memory. The same is done for the SOAP payment service and for
 * the simulator's REST endpoint.
 */
@Service
public class IntegrationStatusService {

    private static final String ODOO = "ODOO";

    public record LastError(Instant at, String operation, String code, String message) {
    }

    /** state: UP, DOWN, UNKNOWN (never called yet) or NOT_CONFIGURED (no API key). */
    public record OdooStatus(String mode, boolean configured, String state, String baseUrl, String database,
                             Instant lastCallAt, LastError lastError, SyncRunResponse lastSync) {
    }

    /** state: UP, DOWN, UNKNOWN (never called yet) or NOT_CONFIGURED. */
    public record ServiceStatus(String state, String url, Instant lastCallAt, LastError lastError) {
    }

    public record StatusResponse(OdooStatus odoo, ServiceStatus soap, ServiceStatus rest) {
    }

    public record CheckResult(String target, boolean ok, long durationMs, String errorCode, String message) {
    }

    public record HealthCheckResponse(CheckResult odoo, CheckResult soap, CheckResult rest) {
    }

    private final OdooGateway gateway;
    private final OdooProperties properties;
    private final IntegrationLogRepository logRepository;
    private final SyncService syncService;
    private final PaymentGateway paymentGateway;
    private final PaymentProperties paymentProperties;
    private final SimulatorRestClient simulator;

    public IntegrationStatusService(OdooGateway gateway, OdooProperties properties,
                                    IntegrationLogRepository logRepository, SyncService syncService,
                                    PaymentGateway paymentGateway, PaymentProperties paymentProperties,
                                    SimulatorRestClient simulator) {
        this.gateway = gateway;
        this.properties = properties;
        this.logRepository = logRepository;
        this.syncService = syncService;
        this.paymentGateway = paymentGateway;
        this.paymentProperties = paymentProperties;
        this.simulator = simulator;
    }

    public StatusResponse status() {
        Optional<IntegrationLog> lastCall = logRepository.findFirstByIntegrationTypeOrderByIdDesc(ODOO);
        Optional<IntegrationLog> lastFailure =
                logRepository.findFirstByIntegrationTypeAndOutcomeNotOrderByIdDesc(ODOO, "SUCCESS");

        String state;
        if (!gateway.isConfigured()) {
            state = "NOT_CONFIGURED";
        } else if (lastCall.isEmpty()) {
            state = "UNKNOWN";
        } else {
            state = "SUCCESS".equals(lastCall.get().getOutcome()) ? "UP" : "DOWN";
        }
        LastError lastError = lastFailure
                .map(row -> new LastError(row.getCreatedAt(), row.getOperation(), row.getErrorCode(),
                        row.getErrorDescription()))
                .orElse(null);
        OdooStatus odoo = new OdooStatus(gateway.mode().name(), gateway.isConfigured(), state,
                properties.baseUrl(), properties.database(),
                lastCall.map(IntegrationLog::getCreatedAt).orElse(null), lastError,
                syncService.latest().orElse(null));
        return new StatusResponse(odoo,
                serviceStatus("SOAP", paymentProperties.wsUrl(), true),
                serviceStatus("REST", paymentProperties.simulatorUrl(), simulator.isConfigured()));
    }

    private ServiceStatus serviceStatus(String type, String url, boolean configured) {
        Optional<IntegrationLog> lastCall = logRepository.findFirstByIntegrationTypeOrderByIdDesc(type);
        LastError lastError = logRepository.findFirstByIntegrationTypeAndOutcomeNotOrderByIdDesc(type, "SUCCESS")
                .map(row -> new LastError(row.getCreatedAt(), row.getOperation(), row.getErrorCode(),
                        row.getErrorDescription()))
                .orElse(null);
        String state;
        if (!configured) {
            state = "NOT_CONFIGURED";
        } else if (lastCall.isEmpty()) {
            state = "UNKNOWN";
        } else {
            // A business failure of one payment (FAILED, a fault) still proves the service answered.
            IntegrationLog last = lastCall.get();
            boolean answered = "SUCCESS".equals(last.getOutcome()) || last.getHttpStatus() != null;
            state = answered ? "UP" : "DOWN";
        }
        return new ServiceStatus(state, url, lastCall.map(IntegrationLog::getCreatedAt).orElse(null), lastError);
    }

    /** Calls Odoo and the payment service now. Each call is also written to integration_logs. */
    public HealthCheckResponse check() {
        long started = System.nanoTime();
        CheckResult odoo;
        try {
            gateway.ping();
            odoo = new CheckResult("odoo", true, elapsedMs(started), null,
                    gateway.mode() == OdooGateway.Mode.MOCK ? "Mock adapter: no request was sent to Odoo" : null);
        } catch (OdooException ex) {
            odoo = new CheckResult("odoo", false, elapsedMs(started), ex.getCode(), ex.getMessage());
        }
        return new HealthCheckResponse(odoo, checkSoap(), checkRest());
    }

    private CheckResult checkSoap() {
        long started = System.nanoTime();
        try {
            paymentGateway.ping();
            return new CheckResult("soap", true, elapsedMs(started), null, null);
        } catch (PaymentGatewayException ex) {
            return new CheckResult("soap", false, elapsedMs(started), ex.getErrorCode(), ex.getMessage());
        }
    }

    private CheckResult checkRest() {
        long started = System.nanoTime();
        try {
            simulator.health();
            return new CheckResult("rest", true, elapsedMs(started), null, null);
        } catch (SimulatorRestClient.SimulatorException ex) {
            return new CheckResult("rest", false, elapsedMs(started), ex.getCode(), ex.getMessage());
        }
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}

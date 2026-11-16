package com.finbridge.integration.external;

import com.finbridge.integration.soap.PaymentProperties;
import com.finbridge.logging.IntegrationLogEntry;
import com.finbridge.logging.IntegrationLogEntry.Outcome;
import com.finbridge.logging.IntegrationLogEntry.Type;
import com.finbridge.logging.IntegrationLogService;
import java.net.http.HttpClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Plain REST client for the payment simulator's health endpoint (GET /sim/health with the
 * X-Sim-Api-Key header). This is FinBridge's third integration style next to Odoo's JSON-2
 * and the SOAP payment calls, and it is logged with integration type REST.
 */
@Component
public class SimulatorRestClient {

    public static class SimulatorException extends RuntimeException {

        private final String code;

        SimulatorException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }

    private static final String OPERATION = "GET /sim/health";

    private final PaymentProperties properties;
    private final IntegrationLogService integrationLog;
    private final RestClient restClient;

    public SimulatorRestClient(PaymentProperties properties, IntegrationLogService integrationLog) {
        this.properties = properties;
        this.integrationLog = integrationLog;
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder().requestFactory(factory)
                .baseUrl(properties.simulatorUrl().replaceAll("/+$", "")).build();
    }

    public boolean isConfigured() {
        return properties.hasSimulatorApiKey();
    }

    /** Throws SimulatorException with a stable code when the simulator is down or refuses the key. */
    public void health() {
        if (!isConfigured()) {
            throw new SimulatorException("SIMULATOR_NOT_CONFIGURED", "SIM_API_KEY is not set in .env");
        }
        long started = System.nanoTime();
        try {
            String body = restClient.get().uri("/sim/health")
                    .header("X-Sim-Api-Key", properties.simulatorApiKey().trim())
                    .retrieve().body(String.class);
            integrationLog.record(IntegrationLogEntry.success(Type.REST, OPERATION, null, 200, elapsedMs(started),
                    null, body));
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            String code = status == 401 || status == 403 ? "SIMULATOR_AUTH" : "SIMULATOR_ERROR";
            String message = status == 401 || status == 403
                    ? "The simulator refused SIM_API_KEY (HTTP " + status + ")"
                    : "The simulator answered HTTP " + status;
            integrationLog.record(IntegrationLogEntry.failure(Type.REST, OPERATION, null, Outcome.FAILURE, status,
                    elapsedMs(started), code, message, null, null, status >= 500));
            throw new SimulatorException(code, message);
        } catch (ResourceAccessException ex) {
            String message = "The simulator could not be reached at " + properties.simulatorUrl();
            integrationLog.record(IntegrationLogEntry.failure(Type.REST, OPERATION, null, Outcome.FAILURE, null,
                    elapsedMs(started), "SIMULATOR_UNREACHABLE", message, null, null, true));
            throw new SimulatorException("SIMULATOR_UNREACHABLE", message);
        }
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}

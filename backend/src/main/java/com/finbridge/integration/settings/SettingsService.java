package com.finbridge.integration.settings;

import com.finbridge.config.AppProperties;
import com.finbridge.exception.InvalidRequestException;
import com.finbridge.exception.ResourceNotFoundException;
import com.finbridge.integration.odoo.OdooGateway;
import com.finbridge.integration.odoo.OdooProperties;
import com.finbridge.integration.soap.PaymentProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settings an administrator may change while the application runs. Each one has a default
 * from application.yml and may be overridden by a row in integration_configurations; the
 * value is read from the database whenever it is needed, so a change applies to the next
 * synchronization run or payment retry without a restart.
 *
 * Only non-secret values live here. API keys, passwords and the JWT secret stay in the
 * environment; this service can only say whether each one is configured.
 */
@Service
public class SettingsService {

    /** The complete list of runtime settings. A key that is not here cannot be stored. */
    public enum Setting {
        ODOO_PAGE_SIZE("odoo.sync.page-size", 1, 500,
                "Records fetched from Odoo per request during a synchronization run"),
        PAYMENT_MAX_ATTEMPTS("payment.max-attempts", 1, 10,
                "How many times one payment may be sent after business failures");

        private final String key;
        private final int min;
        private final int max;
        private final String description;

        Setting(String key, int min, int max, String description) {
            this.key = key;
            this.min = min;
            this.max = max;
            this.description = description;
        }

        public String key() {
            return key;
        }
    }

    public record SettingView(String key, String value, String defaultValue, int min, int max, String description,
                              boolean overridden, Long updatedBy, Instant updatedAt) {
    }

    public record SecretView(String name, String purpose, boolean configured) {
    }

    public record ConnectionView(String name, String url, String detail, long connectTimeoutMs, long readTimeoutMs) {
    }

    public record ConfigView(List<SettingView> settings, List<ConnectionView> connections,
                             List<SecretView> secrets) {
    }

    private final IntegrationConfigurationRepository repository;
    private final OdooProperties odooProperties;
    private final PaymentProperties paymentProperties;
    private final AppProperties appProperties;
    private final OdooGateway odooGateway;
    private final Clock clock;

    public SettingsService(IntegrationConfigurationRepository repository, OdooProperties odooProperties,
                           PaymentProperties paymentProperties, AppProperties appProperties,
                           OdooGateway odooGateway, Clock clock) {
        this.repository = repository;
        this.odooProperties = odooProperties;
        this.paymentProperties = paymentProperties;
        this.appProperties = appProperties;
        this.odooGateway = odooGateway;
        this.clock = clock;
    }

    /** The current value: the stored override when there is a valid one, otherwise the default. */
    @Transactional(readOnly = true)
    public int intValue(Setting setting) {
        Optional<IntegrationConfiguration> stored = repository.findByConfigKey(setting.key);
        if (stored.isPresent()) {
            try {
                int value = Integer.parseInt(stored.get().getConfigValue().trim());
                if (value >= setting.min && value <= setting.max) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
                // a row edited by hand into nonsense: fall back to the default
            }
        }
        return defaultValue(setting);
    }

    private int defaultValue(Setting setting) {
        return switch (setting) {
            case ODOO_PAGE_SIZE -> odooProperties.pageSize();
            case PAYMENT_MAX_ATTEMPTS -> paymentProperties.maxAttempts();
        };
    }

    @Transactional(readOnly = true)
    public ConfigView view() {
        List<SettingView> settings = new ArrayList<>();
        for (Setting setting : Setting.values()) {
            settings.add(view(setting));
        }
        List<ConnectionView> connections = List.of(
                new ConnectionView("Odoo (JSON-2 API)", odooProperties.baseUrl(),
                        "database " + odooProperties.database() + ", mode " + odooGateway.mode(),
                        odooProperties.connectTimeout().toMillis(), odooProperties.readTimeout().toMillis()),
                new ConnectionView("Payment service (SOAP)", paymentProperties.wsUrl(), "contract payments v1",
                        paymentProperties.connectTimeout().toMillis(), paymentProperties.readTimeout().toMillis()),
                new ConnectionView("Payment simulator (REST)", paymentProperties.simulatorUrl(), "health endpoint",
                        paymentProperties.connectTimeout().toMillis(), paymentProperties.readTimeout().toMillis()));
        List<SecretView> secrets = List.of(
                new SecretView("ODOO_API_KEY", "API key of the Odoo integration user", odooProperties.hasApiKey()),
                new SecretView("SIM_API_KEY", "Key for the simulator's REST controls",
                        paymentProperties.hasSimulatorApiKey()),
                new SecretView("JWT_SECRET", "Signs the access tokens",
                        appProperties.security().jwt().secret() != null));
        return new ConfigView(settings, connections, secrets);
    }

    @Transactional
    public SettingView update(String key, String rawValue, Long userId) {
        Setting setting = null;
        for (Setting candidate : Setting.values()) {
            if (candidate.key.equals(key)) {
                setting = candidate;
            }
        }
        if (setting == null) {
            throw new ResourceNotFoundException("There is no setting '" + key + "'");
        }
        int value;
        try {
            value = Integer.parseInt(rawValue == null ? "" : rawValue.trim());
        } catch (NumberFormatException ex) {
            throw new InvalidRequestException(key + " must be a whole number");
        }
        if (value < setting.min || value > setting.max) {
            throw new InvalidRequestException(key + " must be between " + setting.min + " and " + setting.max);
        }
        Setting chosen = setting;
        IntegrationConfiguration row = repository.findByConfigKey(key)
                .orElseGet(() -> new IntegrationConfiguration(chosen.key, chosen.description));
        row.update(String.valueOf(value), userId, clock.instant());
        repository.save(row);
        return view(setting);
    }

    private SettingView view(Setting setting) {
        Optional<IntegrationConfiguration> stored = repository.findByConfigKey(setting.key);
        return new SettingView(setting.key, String.valueOf(intValue(setting)),
                String.valueOf(defaultValue(setting)), setting.min, setting.max, setting.description,
                stored.isPresent(), stored.map(IntegrationConfiguration::getUpdatedBy).orElse(null),
                stored.map(IntegrationConfiguration::getUpdatedAt).orElse(null));
    }
}

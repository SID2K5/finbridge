package com.finbridge.integration.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** One non-secret setting an administrator changed. Secrets are never stored in this table. */
@Entity
@Table(name = "integration_configurations")
public class IntegrationConfiguration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "config_key", nullable = false, unique = true, length = 100, updatable = false)
    private String configKey;

    @Column(name = "config_value", nullable = false, length = 500)
    private String configValue;

    @Column
    private String description;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected IntegrationConfiguration() {
    }

    public IntegrationConfiguration(String configKey, String description) {
        this.configKey = configKey;
        this.description = description;
    }

    public void update(String value, Long userId, Instant now) {
        this.configValue = value;
        this.updatedBy = userId;
        this.updatedAt = now;
    }

    public String getConfigKey() {
        return configKey;
    }

    public String getConfigValue() {
        return configValue;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

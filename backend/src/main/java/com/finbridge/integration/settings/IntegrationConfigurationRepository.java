package com.finbridge.integration.settings;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IntegrationConfigurationRepository extends JpaRepository<IntegrationConfiguration, Long> {

    Optional<IntegrationConfiguration> findByConfigKey(String configKey);
}

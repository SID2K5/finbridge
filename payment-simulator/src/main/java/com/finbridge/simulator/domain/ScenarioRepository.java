package com.finbridge.simulator.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ScenarioRepository extends JpaRepository<ScenarioSetting, String> {
}

package com.finbridge.simulator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The scenario chosen for one invoice reference through the REST control endpoint. */
@Entity
@Table(name = "sim_scenarios")
public class ScenarioSetting {

    @Id
    @Column(length = 64)
    private String invoiceReference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Scenario scenario;

    protected ScenarioSetting() {
    }

    public ScenarioSetting(String invoiceReference, Scenario scenario) {
        this.invoiceReference = invoiceReference;
        this.scenario = scenario;
    }

    public String getInvoiceReference() {
        return invoiceReference;
    }

    public Scenario getScenario() {
        return scenario;
    }

    public void setScenario(Scenario scenario) {
        this.scenario = scenario;
    }
}

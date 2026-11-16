package com.finbridge.simulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * A simulated external payment provider. It is a separate application on purpose: FinBridge
 * talks to it only over the network (SOAP for payments, REST for health and test controls),
 * exactly as it would talk to a bank.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class SimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(SimulatorApplication.class, args);
    }
}

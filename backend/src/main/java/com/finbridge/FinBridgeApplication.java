package com.finbridge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.autoconfigure.webservices.WebServicesAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

// Users are authenticated by JWT against our own users table, so Spring Boot's
// default in-memory user (and its generated password) is switched off.
//
// Spring WS is used only as a SOAP CLIENT here. Its auto-configuration would also publish a
// SOAP endpoint servlet under /services, which FinBridge does not have, so it is switched off.
@SpringBootApplication(exclude = {UserDetailsServiceAutoConfiguration.class, WebServicesAutoConfiguration.class})
@ConfigurationPropertiesScan
public class FinBridgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(FinBridgeApplication.class, args);
    }
}

package com.finbridge.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TimeConfig {

    /** A single clock bean, so tests can control time. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}

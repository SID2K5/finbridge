package com.finbridge.integration.odoo.sync;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class SyncConfig {

    public static final String EXECUTOR = "syncExecutor";

    /**
     * One thread, so two runs can never execute at the same time. SyncService refuses to
     * start a run while another is RUNNING; the single queue slot only covers the instant
     * between a run being closed in the database and its thread becoming free.
     */
    @Bean(name = EXECUTOR)
    public ThreadPoolTaskExecutor syncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("odoo-sync-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        return executor;
    }
}

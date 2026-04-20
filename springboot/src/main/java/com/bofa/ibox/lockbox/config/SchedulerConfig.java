package com.bofa.ibox.lockbox.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Activates Spring's {@code @Scheduled} processing.
 *
 * Kept in a dedicated class so that {@code @EnableScheduling} can be excluded
 * cleanly in integration tests that do not need the file-watcher scheduler
 * (e.g. {@code @SpringBootTest(excludeAutoConfiguration = SchedulerConfig.class)}).
 */
@Configuration
@EnableScheduling
public class SchedulerConfig {
}

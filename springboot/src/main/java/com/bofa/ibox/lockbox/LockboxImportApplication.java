package com.bofa.ibox.lockbox;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Entry point for the Digital Lockbox import service.
 *
 * The application runs continuously as a daemon.
 * {@link com.bofa.ibox.lockbox.service.LockboxFileWatcherService} polls the
 * configured {@code lockbox.import.in-dir} every
 * {@code lockbox.import.scheduler-interval-ms} milliseconds (default 10 s)
 * for new DIGLBX_Aspec_*.json files.
 *
 * Key environment variables:
 *   LOCKBOX_IN_DIR        – directory to watch (default /data/lockbox/in)
 *   LOCKBOX_PROCESSED_DIR – success output dir  (default /data/lockbox/processed)
 *   LOCKBOX_ERROR_DIR     – failure output dir   (default /data/lockbox/error)
 *   LOCKBOX_DB_SCHEMA     – PostgreSQL schema     (default ibox_uat)
 *   DB_PASSWORD           – database password     (required, no default)
 */
@SpringBootApplication
@EnableConfigurationProperties
public class LockboxImportApplication {

    public static void main(String[] args) {
        SpringApplication.run(LockboxImportApplication.class, args);
    }
}

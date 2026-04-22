package com.bofa.ibox.lockbox.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Strongly-typed configuration bound from application.yml / env vars.
 * Key environment variables:
 *   LOCKBOX_IN_DIR, LOCKBOX_PROCESSED_DIR, LOCKBOX_ERROR_DIR,
 *   LOCKBOX_DB_SCHEMA, LOCKBOX_SCHEDULER_ENABLED, LOCKBOX_SCHEDULER_INTERVAL_MS
 */
@Validated
@Component
@ConfigurationProperties(prefix = "lockbox.import")
public class LockboxImportProperties {

    /**
     * Directory the scheduler watches for incoming DIGLBX_Aspec_*.json files.
     * Override via env var LOCKBOX_IN_DIR.
     */
    @NotBlank(message = "lockbox.import.in-dir must be set")
    private String inDir = "/data/lockbox/in";

    /**
     * Directory where successfully processed files are moved.
     * Override via env var LOCKBOX_PROCESSED_DIR.
     */
    @NotBlank(message = "lockbox.import.processed-dir must be set")
    private String processedDir = "/data/lockbox/processed";

    /**
     * Directory where files that failed processing are moved.
     * Override via env var LOCKBOX_ERROR_DIR.
     */
    @NotBlank(message = "lockbox.import.error-dir must be set")
    private String errorDir = "/data/lockbox/error";

    /**
     * Master switch for the file-watcher scheduler.
     * Set to false to disable polling without stopping the application.
     * Override via env var LOCKBOX_SCHEDULER_ENABLED.
     */
    private boolean schedulerEnabled = true;

    // provider_id, lob_id, application_id are no longer static config.
    // They are resolved at runtime by FileSpecLookupService which joins
    // ibox_file_spec → ibox_provider → ibox_client → ibox_application.

    /** Audit / modified_by value written to the table */
    @NotBlank
    private String modifiedBy = "LOCKBOX_IMPORT_JOB";

    /** JDBC batch size for staging inserts (default 1000) */
    @Min(100)
    private int batchSize = 1000;

    /**
     * EF-108: Maximum number of lockbox records allowed in one file.
     * Import is rejected if the file exceeds this limit.
     */
    @Min(1)
    private int maxLockboxCount = 50000;

    /**
     * EF-106: Maximum age (in days) of the ASPECDate in the file.
     * Import is rejected if ASPECDate is older than today minus this value.
     */
    @Min(1)
    private int maxFileAgeDays = 2;

    /**
     * Number of days to retain rows in ibox_lockbox_staging before purging.
     * Used by LockboxStagingPurgeService (default 30 days).
     */
    @Min(1)
    private int stagingRetentionDays = 30;

    /**
     * PostgreSQL schema that owns the lockbox tables and stored procedure.
     * Override via env var LOCKBOX_DB_SCHEMA (e.g. "ibox" for production,
     * "ibox_uat" for UAT). Default: ibox_uat.
     * Must be alphanumeric + underscores only – validated to prevent SQL injection.
     */
    @NotBlank
    @jakarta.validation.constraints.Pattern(
        regexp = "^[a-zA-Z][a-zA-Z0-9_]*$",
        message = "db-schema must start with a letter and contain only letters, digits, or underscores"
    )
    private String dbSchema = "ibox_uat";

    public String getInDir() {
        return inDir;
    }

    public void setInDir(String inDir) {
        this.inDir = inDir;
    }

    public String getProcessedDir() {
        return processedDir;
    }

    public void setProcessedDir(String processedDir) {
        this.processedDir = processedDir;
    }

    public String getErrorDir() {
        return errorDir;
    }

    public void setErrorDir(String errorDir) {
        this.errorDir = errorDir;
    }

    public boolean isSchedulerEnabled() {
        return schedulerEnabled;
    }

    public void setSchedulerEnabled(boolean schedulerEnabled) {
        this.schedulerEnabled = schedulerEnabled;
    }

    public String getModifiedBy() {
        return modifiedBy;
    }

    public void setModifiedBy(String modifiedBy) {
        this.modifiedBy = modifiedBy;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaxLockboxCount() {
        return maxLockboxCount;
    }

    public void setMaxLockboxCount(int maxLockboxCount) {
        this.maxLockboxCount = maxLockboxCount;
    }

    public int getMaxFileAgeDays() {
        return maxFileAgeDays;
    }

    public void setMaxFileAgeDays(int maxFileAgeDays) {
        this.maxFileAgeDays = maxFileAgeDays;
    }

    public int getStagingRetentionDays() {
        return stagingRetentionDays;
    }

    public void setStagingRetentionDays(int stagingRetentionDays) {
        this.stagingRetentionDays = stagingRetentionDays;
    }

    public String getDbSchema() {
        return dbSchema;
    }

    public void setDbSchema(String dbSchema) {
        this.dbSchema = dbSchema;
    }
}

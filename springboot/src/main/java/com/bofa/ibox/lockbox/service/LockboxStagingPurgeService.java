package com.bofa.ibox.lockbox.service;

import com.bofa.ibox.lockbox.config.LockboxImportProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Removes staging rows that are older than the configured retention window
 * (default 30 days, configurable via lockbox.import.staging-retention-days).
 *
 * This service is intentionally separate from the import flow so that:
 *   - A single failed purge never affects an in-progress import.
 *   - The purge can be scheduled independently (e.g. nightly via cron / @Scheduled).
 *   - The retention period can be changed without touching the stored procedure.
 *
 * Invocation options:
 *   1. Call {@link #purgeExpiredRows()} directly from a scheduled job or CLI runner.
 *   2. Wire into a Spring @Scheduled method in a separate @Configuration class.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LockboxStagingPurgeService {

    private final JdbcTemplate            jdbcTemplate;
    private final LockboxImportProperties props;

    private String purgeSql;

    @PostConstruct
    void initSql() {
        purgeSql = "DELETE FROM " + props.getDbSchema()
                 + ".ibox_lockbox_staging WHERE staged_at < ?";
    }

    /**
     * Deletes all staging rows whose {@code staged_at} timestamp is older than
     * {@code now() - stagingRetentionDays}.
     *
     * @return number of rows deleted (0 if nothing expired)
     */
    @Transactional
    public int purgeExpiredRows() {
        int retentionDays = props.getStagingRetentionDays();
        Instant cutoff    = Instant.now().minus(retentionDays, ChronoUnit.DAYS);

        log.info("Purging staging rows older than {} day(s) (cutoff={})", retentionDays, cutoff);

        int deleted = jdbcTemplate.update(purgeSql, Timestamp.from(cutoff));

        if (deleted > 0) {
            log.info("Purged {} staging row(s) older than {} days", deleted, retentionDays);
        } else {
            log.debug("No expired staging rows found (retention={} days)", retentionDays);
        }

        return deleted;
    }
}

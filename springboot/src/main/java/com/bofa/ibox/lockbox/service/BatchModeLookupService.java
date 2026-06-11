package com.bofa.ibox.lockbox.service;

import com.bofa.ibox.lockbox.LockboxConstants;
import com.bofa.ibox.lockbox.config.LockboxImportProperties;
import com.bofa.ibox.lockbox.model.BatchModeInfo;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Resolves the batch mode configuration for an incoming file by looking up
 * the {@code batch_mode_master} table using the {@code provider_id} and
 * {@code client_id} that were already resolved by {@link FileSpecLookupService}.
 *
 * <h3>Lookup strategy</h3>
 * <ul>
 *   <li>Filters by {@code batchstatus = 'ACTIVE'}</li>
 *   <li>Returns the first matching row (ordered by {@code buniqueid} for
 *       determinism if multiple rows exist)</li>
 *   <li>Returns {@link Optional#empty()} — non-fatal — if no matching row
 *       is found; the caller logs a warning and inserts {@code NULL} values
 *       for the batch columns in {@code ibox_lockbox}</li>
 * </ul>
 */
@Service
public class BatchModeLookupService {

    private static final Logger log = LoggerFactory.getLogger(BatchModeLookupService.class);

    private final JdbcTemplate            jdbcTemplate;
    private final LockboxImportProperties props;

    public BatchModeLookupService(JdbcTemplate jdbcTemplate, LockboxImportProperties props) {
        this.jdbcTemplate = jdbcTemplate;
        this.props        = props;
    }

    private String lookupSql;

    @PostConstruct
    void initSql() {
        String s = props.getDbSchema();
        lookupSql = "SELECT batchmode, batchsize, batchmodenum "
                  + "FROM " + s + "." + LockboxConstants.TABLE_BATCH_MODE_MASTER + " "
                  + "WHERE provider_id  = ? "
                  + "  AND client_id    = ? "
                  + "  AND batchstatus  = '" + LockboxConstants.BATCH_STATUS_ACTIVE + "' "
                  + "ORDER BY buniqueid "
                  + "LIMIT 1";
        log.debug("BatchModeLookupService SQL initialised for schema '{}'", s);
    }

    /**
     * Resolves the batch mode/size for the given provider and client.
     *
     * @param providerId resolved from {@code ibox_file_spec}
     * @param clientId   resolved from {@code ibox_file_spec}
     * @return {@link Optional} containing the batch info, or empty if no
     *         active row exists in {@code batch_mode_master}
     */
    public Optional<BatchModeInfo> resolve(int providerId, int clientId) {
        List<BatchModeInfo> results = jdbcTemplate.query(
                lookupSql,
                (rs, rowNum) -> BatchModeInfo.builder()
                        .batchMode   (rs.getString("batchmode"))
                        .batchSize   (rs.getObject("batchsize",    Integer.class))
                        .batchModeNum(rs.getObject("batchmodenum", Integer.class))
                        .build(),
                providerId, clientId);

        if (results.isEmpty()) {
            log.warn("No active batch_mode_master row found for provider_id={}, client_id={} – "
                   + "batchmode/batchsize will be NULL in ibox_lockbox", providerId, clientId);
            return Optional.empty();
        }

        BatchModeInfo info = results.get(0);
        log.info("Batch mode resolved – provider_id={}, client_id={}, batchmode='{}', batchsize={}, batchmodenum={}",
                providerId, clientId, info.getBatchMode(), info.getBatchSize(), info.getBatchModeNum());
        return Optional.of(info);
    }
}

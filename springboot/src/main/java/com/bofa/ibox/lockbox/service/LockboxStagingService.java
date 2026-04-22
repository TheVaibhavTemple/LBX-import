package com.bofa.ibox.lockbox.service;

import com.bofa.ibox.lockbox.LockboxConstants;
import com.bofa.ibox.lockbox.config.LockboxImportProperties;
import com.bofa.ibox.lockbox.model.LockboxRow;
import com.bofa.ibox.lockbox.model.RejectedEntry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.List;

/**
 * Handles all database operations for the lockbox import:
 *  - Create import log entry → returns import_log_id
 *  - Bulk-insert rows into staging tagged with import_log_id
 *  - Log rejected (duplicate) entries into import_detail
 *  - Call stored procedure import_lockbox_data()
 */
@Service
public class LockboxStagingService {

    private static final Logger log = LoggerFactory.getLogger(LockboxStagingService.class);

    private final JdbcTemplate            jdbcTemplate;
    private final LockboxImportProperties props;

    public LockboxStagingService(JdbcTemplate jdbcTemplate, LockboxImportProperties props) {
        this.jdbcTemplate = jdbcTemplate;
        this.props = props;
    }

    // SQL strings are built at startup so the schema name is configurable
    // (lockbox.import.db-schema in application.yml, default ibox_uat)
    private String createLogSql;
    private String insertStagingSql;
    private String insertRejectedSql;
    private String callProcedureSql;

    @PostConstruct
    void initSql() {
        String s = props.getDbSchema();
        createLogSql      = "INSERT INTO " + s + "." + LockboxConstants.TABLE_IMPORT_LOG
                          + " (file_name, aspec_date, status)"
                          + " VALUES (?, ?, '" + LockboxConstants.STATUS_IN_PROGRESS + "')";
        insertStagingSql  = "INSERT INTO " + s + "." + LockboxConstants.TABLE_STAGING
                          + " (import_log_id, staged_at, lockboxnumber, site_identifier, lockboxname, lockboxstatus,"
                          + "  digitalindicator, postalcode, specificationidentifier, familygci, primarygci,"
                          + "  addresstype, addresscompanyname, postofficebox, addressattn,"
                          + "  addressstreet1, addressstreet2, addresscity, addressstate,"
                          + "  addresspostalcode, addresscountry, row_hash)"
                          + " VALUES (?,now(),?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        insertRejectedSql = "INSERT INTO " + s + "." + LockboxConstants.TABLE_IMPORT_DETAIL
                          + " (import_log_id, lockboxnumber, site_identifier, postofficebox, operation, changed_fields)"
                          + " VALUES (?, ?, ?, ?, '" + LockboxConstants.STATUS_REJECTED + "',"
                          + " jsonb_build_object('reason', ?::text))";
        callProcedureSql  = "CALL " + s + "." + LockboxConstants.PROC_IMPORT_DATA + "(?,?,?,?,?,?,?,?,?)";
        log.debug("SQL initialised for schema '{}'", s);
    }

    // ----------------------------------------------------------------
    // Step 1: Create import log entry – returns import_log_id
    // ----------------------------------------------------------------
    public long createImportLog(String fileName, LocalDate aspecDate) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            // Pass the column name explicitly so PostgreSQL returns only that one key.
            // Using Statement.RETURN_GENERATED_KEYS causes PG to return every column,
            // which makes GeneratedKeyHolder.getKey() throw InvalidDataAccessApiUsageException.
            PreparedStatement ps = con.prepareStatement(createLogSql,
                new String[]{"import_log_id"});
            ps.setString(1, fileName);
            ps.setObject(2, aspecDate != null ? Date.valueOf(aspecDate) : null);
            return ps;
        }, keyHolder);

        long logId = keyHolder.getKey().longValue();
        log.info("Import log created – import_log_id={}", logId);
        return logId;
    }

    // ----------------------------------------------------------------
    // Step 2: Bulk-insert valid rows into staging (tagged with logId)
    // ----------------------------------------------------------------
    public void loadStaging(long importLogId, List<LockboxRow> rows) {
        int batchSize = props.getBatchSize();
        int total     = rows.size();
        int batches   = (int) Math.ceil((double) total / batchSize);

        log.info("Loading {} valid rows into staging in {} batches (import_log_id={})",
            total, batches, importLogId);

        for (int b = 0; b < batches; b++) {
            int from  = b * batchSize;
            int to    = Math.min(from + batchSize, total);
            List<LockboxRow> chunk = rows.subList(from, to);

            jdbcTemplate.batchUpdate(insertStagingSql, chunk, chunk.size(),
                (ps, row) -> {
                    ps.setLong  (1,  importLogId);
                    ps.setString(2,  row.getLockboxNumber());
                    ps.setString(3,  row.getSiteIdentifier());
                    ps.setString(4,  row.getLockboxName());
                    ps.setString(5,  row.getLockboxStatus());
                    ps.setObject(6,  row.getDigitalIndicator());
                    ps.setString(7,  row.getPostalCode());
                    ps.setString(8,  row.getSpecificationIdentifier());
                    ps.setString(9,  row.getFamilyGci());
                    ps.setString(10, row.getPrimaryGci());
                    ps.setString(11, row.getAddressType());
                    ps.setString(12, row.getAddressCompanyName());
                    ps.setString(13, row.getPostOfficeBox() != null
                                     ? row.getPostOfficeBox()
                                     : LockboxConstants.EMPTY_POST_OFFICE_BOX);
                    ps.setString(14, row.getAddressAttn());
                    ps.setString(15, row.getAddressStreet1());
                    ps.setString(16, row.getAddressStreet2());
                    ps.setString(17, row.getAddressCity());
                    ps.setString(18, row.getAddressState());
                    ps.setString(19, row.getAddressPostalCode());
                    ps.setString(20, row.getAddressCountry());
                    ps.setString(21, row.getRowHash());
                });

            log.debug("  Staged batch {}/{} ({} rows)", b + 1, batches, chunk.size());
        }
        log.info("Staging complete: {} rows loaded", total);
    }

    // ----------------------------------------------------------------
    // Step 3: Log rejected (duplicate) entries into detail table
    // ----------------------------------------------------------------
    public void logRejected(long importLogId, List<RejectedEntry> rejected) {
        if (rejected.isEmpty()) return;

        jdbcTemplate.batchUpdate(insertRejectedSql, rejected, rejected.size(),
            (ps, entry) -> {
                ps.setLong  (1, importLogId);
                ps.setString(2, entry.getLockboxNumber());
                ps.setString(3, entry.getSiteIdentifier());
                ps.setString(4, entry.getPostOfficeBox());
                ps.setString(5, entry.getReason());
            });

        log.warn("Logged {} rejected (duplicate) record(s) into import_detail", rejected.size());
    }

    // ----------------------------------------------------------------
    // Step 4: Call import_lockbox_data stored procedure
    // providerId / lobId / applicationId are resolved dynamically from
    // ibox_file_spec by FileSpecLookupService before this is called.
    // ----------------------------------------------------------------
    public void callImportProcedure(long importLogId, String fileName,
                                    LocalDate aspecDate,
                                    int providerId, int lobId, int applicationId,
                                    int rejectedCount) {
        log.info("Calling import_lockbox_data (import_log_id={}, provider_id={}, application_id={})",
            importLogId, providerId, applicationId);

        jdbcTemplate.update(callProcedureSql,
            importLogId,
            fileName,
            aspecDate != null ? Date.valueOf(aspecDate) : null,
            providerId,
            lobId,
            applicationId,
            rejectedCount,
            null,                       // incomingfileid
            props.getModifiedBy()
        );

        log.info("Stored procedure completed successfully");
    }
}

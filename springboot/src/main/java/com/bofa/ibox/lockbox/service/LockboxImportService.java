package com.bofa.ibox.lockbox.service;

import com.bofa.ibox.lockbox.LockboxConstants;
import com.bofa.ibox.lockbox.config.LockboxImportProperties;
import com.bofa.ibox.lockbox.exception.LockboxValidationException;
import com.bofa.ibox.lockbox.model.BatchModeInfo;
import com.bofa.ibox.lockbox.model.ErrorCode;
import com.bofa.ibox.lockbox.model.FileSpecInfo;
import com.bofa.ibox.lockbox.model.ParseResult;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Matcher;

/**
 * Orchestrates the end-to-end lockbox import for a single file.
 *
 * Called by {@link LockboxFileWatcherService} once the file has been
 * renamed to {@code *.processing} (file-lock step).
 *
 * Flow:
 *  1. EF-101 – validate filename format (original name, before .processing suffix)
 *  2. EF-102 – resolve provider + application from ibox_file_spec (DB lookup)
 *  3. Duplicate check – if the same filename already exists in import_log (any status),
 *                       create a DUPLICATE_PENDING log entry, publish a Service Bus
 *                       alert, and stop (no data imported).
 *  4. Parse + validate JSON (EF-106, EF-108, EV-200…215) – outside transaction
 *  5. Persist in a single @Transactional boundary:
 *       a. Create import log entry → import_log_id
 *       b. Bulk-load valid rows into staging
 *       c. Log rejected records into import_detail
 *       d. Call stored procedure (upsert + audit)
 *       e. Delete staging rows (clean up after successful promotion to main tables)
 */
@Service
public class LockboxImportService {

    private static final Logger log = LoggerFactory.getLogger(LockboxImportService.class);

    private final LockboxFileParser          fileParser;
    private final LockboxStagingService      stagingService;
    private final FileSpecLookupService      fileSpecLookupService;
    private final BatchModeLookupService     batchModeLookupService;
    private final ServiceBusPublisherService serviceBusPublisher;
    private final LockboxImportProperties    props;
    private final JdbcTemplate              jdbcTemplate;

    public LockboxImportService(LockboxFileParser fileParser,
                                LockboxStagingService stagingService,
                                FileSpecLookupService fileSpecLookupService,
                                BatchModeLookupService batchModeLookupService,
                                ServiceBusPublisherService serviceBusPublisher,
                                LockboxImportProperties props,
                                JdbcTemplate jdbcTemplate) {
        this.fileParser            = fileParser;
        this.stagingService        = stagingService;
        this.fileSpecLookupService = fileSpecLookupService;
        this.batchModeLookupService= batchModeLookupService;
        this.serviceBusPublisher   = serviceBusPublisher;
        this.props                 = props;
        this.jdbcTemplate          = jdbcTemplate;
    }

    // Pre-built SQL statements (schema name injected at startup)
    private String duplicateFileNameCheckSql;
    private String insertDuplicatePendingLogSql;
    private String deleteStagingSql;

    @PostConstruct
    void initSql() {
        String s = props.getDbSchema();
        // Filename-based duplicate check: any row with the same file_name already recorded?
        duplicateFileNameCheckSql =
            "SELECT COUNT(*) FROM " + s + "." + LockboxConstants.TABLE_IMPORT_LOG
            + " WHERE file_name = ?";

        // Insert a minimal import_log entry to record that a duplicate alert was sent
        insertDuplicatePendingLogSql =
            "INSERT INTO " + s + "." + LockboxConstants.TABLE_IMPORT_LOG
            + " (file_name, aspec_date, status, provider_id, client_id, total_lockbox_count)"
            + " VALUES (?, ?, '" + LockboxConstants.STATUS_DUPLICATE_PENDING + "', ?, ?, 0)"
            + " RETURNING import_log_id";

        // Delete staging rows after successful promotion to main tables
        deleteStagingSql =
            "DELETE FROM " + s + "." + LockboxConstants.TABLE_STAGING
            + " WHERE import_log_id = ?";
    }

    // ----------------------------------------------------------------
    // Primary entry point – called by LockboxFileWatcherService
    // ----------------------------------------------------------------

    /**
     * Processes the given file (which may already carry the {@code .processing}
     * suffix from the file-locking rename).
     *
     * @param file the locked file to process (e.g. DIGLBX_Aspec_20260416T120000.json.processing)
     * @throws IllegalArgumentException    if the file does not exist
     * @throws LockboxValidationException  for EF-101, EF-102, or any parse-time error
     * @throws RuntimeException            wrapping IOException from the parser
     */
    public void processFile(File file) {
        // Derive the original filename: strip .processing suffix added during file locking
        String rawName  = file.getName();
        String fileName = rawName.endsWith(LockboxConstants.PROCESSING_SUFFIX)
            ? rawName.substring(0, rawName.length() - LockboxConstants.PROCESSING_SUFFIX.length())
            : rawName;

        // ── Guard: file must exist ──────────────────────────────────────
        if (!file.exists() || !file.isFile()) {
            log.error("Lockbox file not found: {}", fileName);
            throw new IllegalArgumentException("Lockbox file not found");
        }

        // ── 1. EF-101: filename format ──────────────────────────────────
        validateFileName(fileName);

        // ── 2. Extract date from filename ───────────────────────────────
        LocalDate fileDate = extractFileDate(fileName);

        // ── 3. EF-102: resolve provider + application from ibox_file_spec
        //       Done BEFORE parsing so we fail fast if the provider is unknown
        FileSpecInfo spec = fileSpecLookupService.resolve(fileName);

        // ── 4. Filename-based duplicate check ───────────────────────────
        //       If the same file name already appears in import_log (under ANY status),
        //       we must NOT import it.  Instead we create a DUPLICATE_PENDING record
        //       and fire a Service Bus alert so the notification service can send the
        //       approve/reject e-mail to the user.
        if (isDuplicateFileName(fileName)) {
            log.warn("Duplicate file detected by name '{}' – publishing alert and skipping import",
                    fileName);
            handleDuplicateFile(fileName, fileDate, spec);
            return;  // ← stop here; do NOT proceed to import
        }

        // ── 5. Resolve batch mode/size from batch_mode_master (non-fatal)
        Optional<BatchModeInfo> batchModeInfo =
                batchModeLookupService.resolve(spec.getProviderId(), spec.getClientId());

        // ── 6. Parse + validate BEFORE opening DB transaction ──────────
        //       (keeps file I/O outside the DB transaction boundary)
        ParseResult result;
        try {
            result = fileParser.parseWithResult(file.getAbsolutePath(), spec.getSpecificationIdentifier());
        } catch (IOException e) {
            log.error("Failed to read lockbox file: {}", fileName, e);
            throw new RuntimeException("Failed to read lockbox file", e);
        }

        // ── 7. Persist parsed result in a single transaction ───────────
        persistResult(result, fileName, fileDate, spec, batchModeInfo.orElse(null));

        log.info("Import finished – file: {}, provider_id: {}, application_id: {}",
            fileName, spec.getProviderId(), spec.getApplicationId());
    }

    // ----------------------------------------------------------------
    // DB persistence (single transaction)
    // ----------------------------------------------------------------

    @Transactional
    void persistResult(ParseResult result, String fileName, LocalDate fileDate,
                       FileSpecInfo spec, BatchModeInfo batchModeInfo) {
        long importLogId = stagingService.createImportLog(
                fileName, fileDate,
                spec.getProviderId(), spec.getClientId(),
                result.getDeclaredLockboxCount());

        log.info("Parse complete – valid: {}, rejected: {}",
            result.validCount(), result.rejectedCount());

        if (result.validCount() == 0) {
            log.warn("No valid rows to import – all records were rejected or file is empty");
        }

        if (result.validCount() > 0) {
            stagingService.loadStaging(importLogId, result.getValidRows());
        }

        stagingService.logRejected(importLogId, result.getRejectedEntries());

        stagingService.callImportProcedure(
            importLogId,
            fileName,
            fileDate,
            spec.getProviderId(),
            spec.getLobId(),
            spec.getApplicationId(),
            result.rejectedCount(),
            batchModeInfo
        );

        // ── Delete staging rows after successful promotion to main tables ──
        int deleted = jdbcTemplate.update(deleteStagingSql, importLogId);
        log.info("Staging cleanup complete – {} row(s) deleted for import_log_id={}",
                deleted, importLogId);

        log.info("Import complete – import_log_id: {}", importLogId);
    }

    // ----------------------------------------------------------------
    // Duplicate file handling
    // ----------------------------------------------------------------

    /**
     * Checks whether a row with the given {@code fileName} already exists
     * in {@code ibox_lockbox_import_log} (regardless of status).
     *
     * @param fileName original filename (without .processing suffix)
     * @return {@code true} if one or more rows exist
     */
    boolean isDuplicateFileName(String fileName) {
        Integer count = jdbcTemplate.queryForObject(
                duplicateFileNameCheckSql, Integer.class, fileName);
        return count != null && count > 0;
    }

    /**
     * Called when a duplicate file is detected.
     * <ol>
     *   <li>Creates an {@code import_log} row with status {@code DUPLICATE_PENDING}
     *       so there is an auditable record of the alert.</li>
     *   <li>Publishes a {@code duplicateBOALockboxFileAlert} event to the
     *       Service Bus topic so the notification service can send the
     *       approve/reject e-mail.</li>
     * </ol>
     * This method does NOT import any data into the main tables.
     *
     * @param fileName original filename
     * @param fileDate date extracted from the filename (may be null)
     * @param spec     resolved file-spec (provider/client ids)
     */
    @Transactional
    void handleDuplicateFile(String fileName, LocalDate fileDate, FileSpecInfo spec) {
        // Create an auditable DUPLICATE_PENDING record and get its id
        Long pendingLogId = jdbcTemplate.queryForObject(
                insertDuplicatePendingLogSql,
                Long.class,
                fileName,
                fileDate != null ? java.sql.Date.valueOf(fileDate) : null,
                spec.getProviderId(),
                spec.getClientId());

        log.info("Duplicate-pending log created – import_log_id={}, file='{}' ",
                pendingLogId, fileName);

        // Publish the Service Bus alert (fire-and-forget from this service's perspective)
        serviceBusPublisher.sendDuplicateFileAlert(pendingLogId);
    }

    // ----------------------------------------------------------------
    // EF-101: Filename must match DIGLBX_Aspec_YYYYMMDDThhmmss.json
    // ----------------------------------------------------------------
    void validateFileName(String fileName) {
        if (!LockboxConstants.FILE_NAME_PATTERN.matcher(fileName).matches()) {
            throw new LockboxValidationException(ErrorCode.EF_101,
                "Filename '" + fileName + "' does not match required format " +
                "DIGLBX_Aspec_YYYYMMDDThhmmss.json");
        }
    }

    // ----------------------------------------------------------------
    // Extract transmission date from filename
    // DIGLBX_Aspec_20260416T120000.json → 2026-04-16
    // ----------------------------------------------------------------
    LocalDate extractFileDate(String fileName) {
        Matcher m = LockboxConstants.FILE_DATE_PATTERN.matcher(fileName);
        if (m.find()) {
            String datePart = m.group(1).substring(0, 8);
            try {
                return LocalDate.parse(datePart, DateTimeFormatter.BASIC_ISO_DATE);
            } catch (DateTimeParseException ex) {
                log.warn("Could not parse date from filename: {}", fileName);
            }
        }
        return null;
    }
}

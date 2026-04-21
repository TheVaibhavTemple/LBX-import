package com.bofa.ibox.lockbox.service;

import com.bofa.ibox.lockbox.LockboxConstants;
import com.bofa.ibox.lockbox.config.LockboxImportProperties;
import com.bofa.ibox.lockbox.exception.LockboxValidationException;
import com.bofa.ibox.lockbox.model.ErrorCode;
import com.bofa.ibox.lockbox.model.FileSpecInfo;
import com.bofa.ibox.lockbox.model.ParseResult;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;

/**
 * Orchestrates the end-to-end lockbox import for a single file.
 *
 * Called by {@link LockboxFileWatcherService} once the file has been
 * renamed to {@code *.processing} (file-lock step).
 *
 * Flow:
 *  1. EF-101 – validate filename format (original name, before .processing suffix)
 *  2. EV-216 – reject if same ASPECDate was already imported successfully
 *  3. EF-102 – resolve provider + application from ibox_file_spec (DB lookup)
 *  4. Parse + validate JSON (EF-106, EF-108, EV-200…215) – outside transaction
 *  5. Persist in a single @Transactional boundary:
 *       a. Create import log entry → import_log_id
 *       b. Bulk-load valid rows into staging
 *       c. Log rejected records into import_detail
 *       d. Call stored procedure (upsert + audit)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LockboxImportService {

    // File naming and status constants are centralised in LockboxConstants.

    private final LockboxFileParser       fileParser;
    private final LockboxStagingService   stagingService;
    private final FileSpecLookupService   fileSpecLookupService;
    private final LockboxImportProperties props;
    private final JdbcTemplate            jdbcTemplate;

    private String duplicateCheckSql;

    @PostConstruct
    void initSql() {
        duplicateCheckSql = "SELECT COUNT(*) FROM " + props.getDbSchema()
            + "." + LockboxConstants.TABLE_IMPORT_LOG
            + " WHERE aspec_date = ? AND status = '" + LockboxConstants.STATUS_SUCCESS + "'";
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
     * @throws LockboxValidationException  for EF-101, EF-102, EV-216, or any parse-time error
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

        // ── 3. EV-216: duplicate transmission check ─────────────────────
        validateNotDuplicate(fileDate, fileName);

        // ── 4. EF-102: resolve provider + application from ibox_file_spec
        //       Done BEFORE parsing so we fail fast if the provider is unknown
//        FileSpecInfo spec = fileSpecLookupService.resolve(fileName);

        //vaibhav todo
        FileSpecInfo spec = FileSpecInfo.builder().applicationId(7).clientId(2).fileSpecId(12345).lobId(3).providerId(4).build();
        
        // ── 5. Parse + validate BEFORE opening DB transaction ──────────
        //       (keeps file I/O outside the DB transaction boundary)
        ParseResult result;
        try {
            result = fileParser.parseWithResult(file.getAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to read lockbox file: {}", fileName, e);
            throw new RuntimeException("Failed to read lockbox file", e);
        }

        // ── 6. Persist parsed result in a single transaction ───────────
        persistResult(result, fileName, fileDate, spec);

        log.info("Import finished – file: {}, provider_id: {}, application_id: {}",
            fileName, spec.getProviderId(), spec.getApplicationId());
    }

    // ----------------------------------------------------------------
    // DB persistence (single transaction)
    // ----------------------------------------------------------------

    @Transactional
    void persistResult(ParseResult result, String fileName, LocalDate fileDate,
                       FileSpecInfo spec) {
        long importLogId = stagingService.createImportLog(fileName, fileDate);

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
            result.rejectedCount()
        );

        log.info("Import complete – import_log_id: {}", importLogId);
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
    // EV-216: Same ASPECDate must not already be successfully imported
    // ----------------------------------------------------------------
    void validateNotDuplicate(LocalDate fileDate, String fileName) {
        if (fileDate == null) return;
        Integer count = jdbcTemplate.queryForObject(
            duplicateCheckSql, Integer.class,
            java.sql.Date.valueOf(fileDate));
        if (count != null && count > 0) {
            throw new LockboxValidationException(ErrorCode.EV_216,
                "File '" + fileName + "' with ASPECDate=" + fileDate +
                " has already been successfully imported");
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

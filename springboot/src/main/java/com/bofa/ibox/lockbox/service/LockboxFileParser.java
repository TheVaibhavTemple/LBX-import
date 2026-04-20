package com.bofa.ibox.lockbox.service;

import com.bofa.ibox.lockbox.LockboxConstants;
import com.bofa.ibox.lockbox.config.LockboxImportProperties;
import com.bofa.ibox.lockbox.exception.LockboxValidationException;
import com.bofa.ibox.lockbox.model.*;
import com.bofa.ibox.lockbox.util.HashUtil;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses and validates the DIGLBX_Aspec_*.json driver file.
 *
 * Two validation modes:
 *
 *   parse()           – strict / fail-fast. Any violation (file or record level) throws
 *                       LockboxValidationException immediately. Used by unit tests.
 *
 *   parseWithResult() – lenient / per-record. Used by the import service.
 *     ┌────────────────────────────────────────────────────────────────────┐
 *     │ FILE-LEVEL  (throws immediately – entire file is rejected)         │
 *     │   • Malformed JSON                                                 │
 *     │   • Missing top-level required fields (schema)                     │
 *     │   • EV-215 SummaryInfo.LockboxCount mismatch                       │
 *     │   • EF-106 ASPECDate too old or bad format                         │
 *     │   • EF-108 File exceeds max lockbox count                          │
 *     ├────────────────────────────────────────────────────────────────────┤
 *     │ RECORD-LEVEL (bad record is MARKED as REJECTED, rest continues)    │
 *     │   • Schema: wrong type, enum violation, pattern, required field     │
 *     │     missing inside a Lockboxes[N] entry                            │
 *     │   • Bean: @NotBlank / @Pattern / @Size on individual LockboxEntry  │
 *     │   • EV-202 AddressPostalCode ≠ LockboxPostalCode                   │
 *     │   • Duplicate (siteIdentifier, lockboxNumber) key in same file     │
 *     └────────────────────────────────────────────────────────────────────┘
 *
 * All rejected records are returned in ParseResult.rejectedEntries and written
 * to ibox_lockbox_import_detail with operation = 'REJECTED'.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LockboxFileParser {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** Matches paths like  $.Lockboxes[5]  or  $.Lockboxes[5].PostalCode */
    private static final Pattern LOCKBOX_INDEX_PATTERN =
        Pattern.compile("\\$\\.Lockboxes\\[(\\d+)\\]");

    private final ObjectMapper            objectMapper;
    private final Validator               validator;
    private final LockboxImportProperties props;

    // ====================================================================
    // Public API
    // ====================================================================

    /**
     * Strict parse: any violation immediately throws LockboxValidationException.
     * Used by unit tests to verify individual error codes.
     */
    public List<LockboxRow> parse(String filePath) throws IOException {
        log.info("Parsing lockbox file: {}", new File(filePath).getName());

        LockboxFileRoot root = parseJson(filePath);   // strict: schema fails file
        validateBeans(root);
        validateSummaryCount(root);
        validateAspecDate(root.getSummaryInfo().getAspecDate());
        validateLockboxCount(root.getLockboxes().size());
        validateAddressPostalCodes(root.getLockboxes());
        warnClosedAndNonDigital(root.getLockboxes());

        log.info("Validation passed – {} lockbox entries, SpecificationIdentifier={}",
            root.getLockboxes().size(), root.getSpecificationIdentifier());

        return flatten(root);
    }

    /**
     * Per-record parse: file-level violations still throw; individual record
     * violations are collected and returned as REJECTED entries.
     * Called by LockboxImportService.
     */
    public ParseResult parseWithResult(String filePath) throws IOException {
        File   file     = new File(filePath);
        String fileName = file.getName();

        List<RejectedEntry> rejected    = new ArrayList<>();
        Set<String>         rejectedKeys = new LinkedHashSet<>();  // "site|lbNum"

        // ── 1. Parse to JsonNode (malformed JSON = fail file) ──────────
        JsonNode jsonNode = parseToNode(file, fileName);

        // ── 2. Schema: top-level violations throw; per-record collected ─
        validateSchemaAndCollect(jsonNode, fileName, rejected, rejectedKeys);

        // ── 3. Deserialise to Java model ────────────────────────────────
        LockboxFileRoot root = deserializeNode(jsonNode, fileName);

        // ── 4. File-level checks ────────────────────────────────────────
        validateSummaryCount(root);                                  // EV-215
        validateAspecDate(root.getSummaryInfo().getAspecDate());     // EF-106
        validateLockboxCount(root.getLockboxes().size());            // EF-108

        // ── 5. Per-record bean validation ───────────────────────────────
        validateBeansPerRecord(root.getLockboxes(), rejected, rejectedKeys);

        // ── 6. Per-record EV-202 ────────────────────────────────────────
        validateAddressPostalCodesPerRecord(root.getLockboxes(), rejected, rejectedKeys);

        // ── 7. Per-record digital-only checks (EV-203 / EV-204) ─────────
        validateDigitalAddressPerRecord(root.getLockboxes(), rejected, rejectedKeys);

        // ── 8. ET-300 / ET-301 warnings ─────────────────────────────────
        warnClosedAndNonDigital(root.getLockboxes());

        log.info("Validation complete – {} in file, {} rejected, {} valid for import",
            root.getLockboxes().size(), rejected.size(),
            root.getLockboxes().size() - rejected.size());

        // ── 9. Flatten valid rows + duplicate check ──────────────────────
        return flattenWithDuplicateCheck(root, rejected, rejectedKeys);
    }

    // ====================================================================
    // JSON parsing helpers
    // ====================================================================

    /**
     * Strict parse: parse + schema-validate + deserialise (used by parse()).
     * Any violation throws immediately.
     */
    private LockboxFileRoot parseJson(String filePath) throws IOException {
        File   file     = new File(filePath);
        String fileName = file.getName();

        JsonNode jsonNode = parseToNode(file, fileName);
        validateAgainstSchema(jsonNode, fileName);          // file-level strict
        return deserializeNode(jsonNode, fileName);
    }

    /**
     * Step 1 – read file to JsonNode.
     * Catches malformed JSON and maps it to EV-200.
     */
    private JsonNode parseToNode(File file, String fileName) throws IOException {
        try {
            return objectMapper.readTree(file);
        } catch (JsonParseException e) {
            String msg = "Malformed JSON in '" + fileName + "' – "
                + e.getOriginalMessage()
                + " (line " + e.getLocation().getLineNr()
                + ", col "  + e.getLocation().getColumnNr() + ")";
            log.error("EV-200 {}", msg);
            throw new LockboxValidationException(ErrorCode.EV_200, msg);
        }
    }

    /**
     * Step 3 – deserialise JsonNode to LockboxFileRoot.
     * Catches mapping errors and maps them to EV-200.
     */
    private LockboxFileRoot deserializeNode(JsonNode jsonNode, String fileName) {
        try {
            return objectMapper.treeToValue(jsonNode, LockboxFileRoot.class);
        } catch (JsonMappingException e) {
            String msg = "JSON mapping error in '" + fileName + "' – " + e.getOriginalMessage();
            log.error("EV-200 {}", msg);
            throw new LockboxValidationException(ErrorCode.EV_200, msg);
        } catch (IOException e) {
            throw new LockboxValidationException(ErrorCode.EV_200,
                "Could not deserialise '" + fileName + "': " + e.getMessage());
        }
    }

    // ====================================================================
    // Schema validation – strict (used by parse())
    // ====================================================================

    /**
     * Validates the whole document against DIGLBX_ASPEC.schema.json.
     * Any violation immediately throws EV-200.
     */
    private void validateAgainstSchema(JsonNode jsonNode, String fileName) {
        Set<ValidationMessage> violations = runSchema(jsonNode, fileName);
        if (violations.isEmpty()) {
            log.debug("JSON schema validation passed for '{}'", fileName);
            return;
        }
        List<String> messages = violations.stream()
            .map(ValidationMessage::getMessage)
            .sorted()
            .toList();
        String detail = String.join("\n  ", messages);
        log.error("EV-200 Schema validation failed for '{}' – {} violation(s):\n  {}",
            fileName, violations.size(), detail);
        throw new LockboxValidationException(ErrorCode.EV_200,
            "JSON schema validation failed for '" + fileName + "' – "
            + violations.size() + " violation(s):\n  " + detail);
    }

    // ====================================================================
    // Schema validation – per-record (used by parseWithResult())
    // ====================================================================

    /**
     * Runs schema validation and splits the results:
     * <ul>
     *   <li>Violations on the top-level document (missing required fields,
     *       wrong SummaryInfo type, etc.) → throw EV-200 immediately.</li>
     *   <li>Violations on individual {@code Lockboxes[N]} entries → the record
     *       is added to {@code rejected} and its key added to {@code rejectedKeys}.</li>
     * </ul>
     */
    private void validateSchemaAndCollect(JsonNode jsonNode, String fileName,
                                          List<RejectedEntry> rejected,
                                          Set<String> rejectedKeys) {
        Set<ValidationMessage> violations = runSchema(jsonNode, fileName);
        if (violations.isEmpty()) {
            log.debug("JSON schema validation passed for '{}'", fileName);
            return;
        }

        // Partition violations into file-level vs record-level
        List<String>              fileLevelMsgs    = new ArrayList<>();
        Map<Integer, List<String>> perRecordMsgs   = new TreeMap<>();

        for (ValidationMessage vm : violations) {
            int idx = extractLockboxIndex(vm);
            if (idx >= 0) {
                perRecordMsgs.computeIfAbsent(idx, k -> new ArrayList<>())
                             .add(vm.getMessage());
            } else {
                fileLevelMsgs.add(vm.getMessage());
            }
        }

        // File-level violations → fail the whole file
        if (!fileLevelMsgs.isEmpty()) {
            String detail = String.join("\n  ", fileLevelMsgs.stream().sorted().toList());
            log.error("EV-200 File-level schema violation(s) in '{}': \n  {}", fileName, detail);
            throw new LockboxValidationException(ErrorCode.EV_200,
                "JSON schema validation failed for '" + fileName + "' – "
                + fileLevelMsgs.size() + " file-level violation(s):\n  " + detail);
        }

        // Record-level violations → mark individual records as REJECTED
        JsonNode lockboxesNode = jsonNode.path("Lockboxes");

        for (Map.Entry<Integer, List<String>> entry : perRecordMsgs.entrySet()) {
            int      idx    = entry.getKey();
            JsonNode lbNode = idx < lockboxesNode.size() ? lockboxesNode.get(idx) : null;

            String lbNum  = lbNode != null ? lbNode.path("LockboxNumber").asText("") : "";
            String siteId = lbNode != null ? lbNode.path("SiteIdentifier").asText("") : "";
            String key    = siteId + "|" + lbNum;

            if (rejectedKeys.add(key)) {
                String reason = "Schema violation(s): "
                    + String.join("; ", entry.getValue());
                log.warn("[EV-200] Marking record as REJECTED – LockboxNumber={} " +
                         "SiteIdentifier={}: {}", lbNum, siteId, reason);
                rejected.add(RejectedEntry.builder()
                    .lockboxNumber  (lbNum)
                    .siteIdentifier (siteId)
                    .postOfficeBox  (LockboxConstants.EMPTY_POST_OFFICE_BOX)
                    .reason         (reason)
                    .build());
            }
        }

        log.info("Schema validation: {} record(s) marked as REJECTED out of {} in file",
            perRecordMsgs.size(), lockboxesNode.size());
    }

    /**
     * Loads DIGLBX_ASPEC.schema.json from the classpath and runs validation.
     * Returns an empty set if the schema file cannot be found (graceful degradation).
     */
    private Set<ValidationMessage> runSchema(JsonNode jsonNode, String fileName) {
        try (var schemaStream = getClass().getClassLoader()
                .getResourceAsStream("DIGLBX_ASPEC.schema.json")) {

            if (schemaStream == null) {
                log.warn("DIGLBX_ASPEC.schema.json not found on classpath – skipping schema validation");
                return Collections.emptySet();
            }

            JsonSchema schema = JsonSchemaFactory
                .getInstance(SpecVersion.VersionFlag.V7)
                .getSchema(schemaStream);

            return schema.validate(jsonNode);

        } catch (LockboxValidationException e) {
            throw e;
        } catch (IOException e) {
            log.error("EV-200 Could not read DIGLBX_ASPEC.schema.json", e);
            throw new LockboxValidationException(ErrorCode.EV_200,
                "Could not load JSON schema file – check classpath");
        } catch (RuntimeException e) {
            log.error("EV-200 Could not apply DIGLBX_ASPEC.schema.json", e);
            throw new LockboxValidationException(ErrorCode.EV_200,
                "Could not apply JSON schema validation");
        }
    }

    /**
     * Extracts the Lockboxes array index from a ValidationMessage path.
     * Returns -1 if the violation is not on a specific Lockboxes entry.
     *
     * <p>Example paths handled:
     * <pre>
     *   $.Lockboxes[5].PostalCode  → 5
     *   $.SummaryInfo.LockboxCount → -1 (file-level)
     * </pre>
     */
    private int extractLockboxIndex(ValidationMessage vm) {
        String msg = vm.getMessage();
        if (msg == null) return -1;
        Matcher m = LOCKBOX_INDEX_PATTERN.matcher(msg);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    // ====================================================================
    // Bean validation
    // ====================================================================

    /** Strict: validates the whole tree; any violation throws EV-200. */
    private void validateBeans(LockboxFileRoot root) {
        Set<ConstraintViolation<LockboxFileRoot>> violations = validator.validate(root);
        if (!violations.isEmpty()) {
            List<String> messages = violations.stream()
                .map(v -> v.getPropertyPath() + " – " + v.getMessage())
                .sorted()
                .toList();
            String detail = String.join("\n  ", messages);
            log.error("EV-200 Bean validation failed:\n  {}", detail);
            throw new LockboxValidationException(ErrorCode.EV_200,
                "Field validation errors:\n  " + detail);
        }
    }

    /**
     * Per-record: validates each LockboxEntry individually.
     * Records that fail are added to {@code rejected}; already-rejected keys are skipped.
     */
    private void validateBeansPerRecord(List<LockboxEntry> lockboxes,
                                        List<RejectedEntry> rejected,
                                        Set<String> rejectedKeys) {
        for (LockboxEntry entry : lockboxes) {
            String key = entry.getSiteIdentifier() + "|" + entry.getLockboxNumber();
            if (rejectedKeys.contains(key)) continue;   // already rejected by schema

            Set<ConstraintViolation<LockboxEntry>> violations = validator.validate(entry);
            if (!violations.isEmpty()) {
                List<String> messages = violations.stream()
                    .map(v -> v.getPropertyPath() + " – " + v.getMessage())
                    .sorted()
                    .toList();
                String reason = "Field validation error(s): " + String.join("; ", messages);
                log.warn("[EV-200] Marking record as REJECTED – LockboxNumber={} " +
                         "SiteIdentifier={}: {}", entry.getLockboxNumber(),
                         entry.getSiteIdentifier(), reason);
                rejected.add(RejectedEntry.builder()
                    .lockboxNumber  (entry.getLockboxNumber())
                    .siteIdentifier (entry.getSiteIdentifier())
                    .postOfficeBox  (LockboxConstants.EMPTY_POST_OFFICE_BOX)
                    .reason         (reason)
                    .build());
                rejectedKeys.add(key);
            }
        }
    }

    // ====================================================================
    // EV-215 / EF-106 / EF-108  (always file-level)
    // ====================================================================

    private void validateSummaryCount(LockboxFileRoot root) {
        int declared = root.getSummaryInfo().getLockboxCount();
        int actual   = root.getLockboxes().size();
        if (declared != actual) {
            throw new LockboxValidationException(ErrorCode.EV_215,
                String.format("SummaryInfo.LockboxCount=%d does not match actual count=%d",
                    declared, actual));
        }
    }

    /**
     * EF-106: ASPECDate must not be older than maxFileAgeDays.
     * Accepts both yyyy-MM-dd and yyyy-MM-ddTHH:mm:ss (time part is ignored).
     */
    void validateAspecDate(String aspecDateStr) {
        if (aspecDateStr == null || aspecDateStr.isBlank()) {
            throw new LockboxValidationException(ErrorCode.EF_106,
                "SummaryInfo.ASPECDate is missing");
        }
        String datePart = (aspecDateStr.length() > 10 && aspecDateStr.charAt(10) == 'T')
            ? aspecDateStr.substring(0, 10) : aspecDateStr;
        LocalDate aspecDate;
        try {
            aspecDate = LocalDate.parse(datePart, DATE_FMT);
        } catch (DateTimeParseException e) {
            throw new LockboxValidationException(ErrorCode.EF_106,
                "SummaryInfo.ASPECDate has invalid format: " + aspecDateStr
                + " – expected yyyy-MM-dd or yyyy-MM-ddTHH:mm:ss");
        }
        LocalDate cutoff = LocalDate.now().minusDays(props.getMaxFileAgeDays());
        if (aspecDate.isBefore(cutoff)) {
            throw new LockboxValidationException(ErrorCode.EF_106,
                String.format("ASPECDate %s is older than %d day(s). File is aged.",
                    aspecDateStr, props.getMaxFileAgeDays()));
        }
    }

    private void validateLockboxCount(int count) {
        int max = props.getMaxLockboxCount();
        if (count > max) {
            throw new LockboxValidationException(ErrorCode.EF_108,
                String.format("File contains %d lockboxes which exceeds the maximum of %d",
                    count, max));
        }
    }

    // ====================================================================
    // EV-202 – strict (used by parse()) and per-record (used by parseWithResult())
    // ====================================================================

    /** Strict: throws on the first mismatch found. */
    void validateAddressPostalCodes(List<LockboxEntry> lockboxes) {
        for (LockboxEntry entry : lockboxes) {
            if (entry.getAddressList() == null) continue;
            String lockboxZip = normaliseZip(entry.getPostalCode());
            for (LockboxAddress addr : entry.getAddressList()) {
                String addrZip = normaliseZip(addr.getAddressPostalCode());
                if (lockboxZip != null && addrZip != null && !lockboxZip.equals(addrZip)) {
                    throw new LockboxValidationException(ErrorCode.EV_202,
                        String.format(
                            "LockboxNumber=%s: AddressPostalCode=%s does not match " +
                            "LockboxPostalCode=%s",
                            entry.getLockboxNumber(),
                            addr.getAddressPostalCode(),
                            entry.getPostalCode()));
                }
            }
        }
    }

    /** Per-record: marks mismatching records as REJECTED instead of failing the file. */
    private void validateAddressPostalCodesPerRecord(List<LockboxEntry> lockboxes,
                                                     List<RejectedEntry> rejected,
                                                     Set<String> rejectedKeys) {
        for (LockboxEntry entry : lockboxes) {
            String key = entry.getSiteIdentifier() + "|" + entry.getLockboxNumber();
            if (rejectedKeys.contains(key) || entry.getAddressList() == null) continue;

            String lockboxZip = normaliseZip(entry.getPostalCode());
            for (LockboxAddress addr : entry.getAddressList()) {
                String addrZip = normaliseZip(addr.getAddressPostalCode());
                if (lockboxZip != null && addrZip != null && !lockboxZip.equals(addrZip)) {
                    String reason = String.format(
                        "[EV-202] AddressPostalCode=%s does not match LockboxPostalCode=%s",
                        addr.getAddressPostalCode(), entry.getPostalCode());
                    log.warn("Marking record as REJECTED – LockboxNumber={} " +
                             "SiteIdentifier={}: {}", entry.getLockboxNumber(),
                             entry.getSiteIdentifier(), reason);
                    rejected.add(RejectedEntry.builder()
                        .lockboxNumber  (entry.getLockboxNumber())
                        .siteIdentifier (entry.getSiteIdentifier())
                        .postOfficeBox  (LockboxConstants.EMPTY_POST_OFFICE_BOX)
                        .reason         (reason)
                        .build());
                    rejectedKeys.add(key);
                    break;  // one rejection entry per lockbox
                }
            }
        }
    }

    // ====================================================================
    // EV-203 / EV-204 – digital-record address checks (DigitalIndicator=true only)
    // ====================================================================

    /**
     * Validates that every record with {@code DigitalIndicator=true} has:
     * <ul>
     *   <li>EV-204: PostalCode with at least 5 characters</li>
     *   <li>EV-203: Non-blank AddressStreet1, AddressCity, and AddressState
     *       on every address entry</li>
     * </ul>
     * Non-digital records are skipped entirely.
     * Already-rejected records are also skipped.
     */
    private void validateDigitalAddressPerRecord(List<LockboxEntry> lockboxes,
                                                 List<RejectedEntry> rejected,
                                                 Set<String> rejectedKeys) {
        for (LockboxEntry entry : lockboxes) {
            if (!Boolean.TRUE.equals(entry.getDigitalIndicator())) continue;

            String key = entry.getSiteIdentifier() + "|" + entry.getLockboxNumber();
            if (rejectedKeys.contains(key)) continue;

            // EV-204: PostalCode must be at least 5 digits
            String postalCode = entry.getPostalCode();
            if (postalCode != null && !postalCode.isBlank() && postalCode.length() < 5) {
                String reason = String.format(
                    "[EV-204] Digital record has PostalCode='%s' which is fewer than 5 digits",
                    postalCode);
                log.warn("Marking record as REJECTED – LockboxNumber={} SiteIdentifier={}: {}",
                    entry.getLockboxNumber(), entry.getSiteIdentifier(), reason);
                rejected.add(RejectedEntry.builder()
                    .lockboxNumber  (entry.getLockboxNumber())
                    .siteIdentifier (entry.getSiteIdentifier())
                    .postOfficeBox  (LockboxConstants.EMPTY_POST_OFFICE_BOX)
                    .reason         (reason)
                    .build());
                rejectedKeys.add(key);
                continue;
            }

            // EV-203: AddressStreet1, AddressCity, AddressState must not be blank
            if (entry.getAddressList() == null) continue;
            for (LockboxAddress addr : entry.getAddressList()) {
                boolean streetBlank = addr.getAddressStreet1() == null || addr.getAddressStreet1().isBlank();
                boolean cityBlank   = addr.getAddressCity()    == null || addr.getAddressCity().isBlank();
                boolean stateBlank  = addr.getAddressState()   == null || addr.getAddressState().isBlank();

                if (streetBlank || cityBlank || stateBlank) {
                    List<String> blanks = new ArrayList<>();
                    if (streetBlank) blanks.add("AddressStreet1");
                    if (cityBlank)   blanks.add("AddressCity");
                    if (stateBlank)  blanks.add("AddressState");
                    String reason = String.format(
                        "[EV-203] Digital record has blank address field(s): %s",
                        String.join(", ", blanks));
                    log.warn("Marking record as REJECTED – LockboxNumber={} SiteIdentifier={}: {}",
                        entry.getLockboxNumber(), entry.getSiteIdentifier(), reason);
                    rejected.add(RejectedEntry.builder()
                        .lockboxNumber  (entry.getLockboxNumber())
                        .siteIdentifier (entry.getSiteIdentifier())
                        .postOfficeBox  (LockboxConstants.EMPTY_POST_OFFICE_BOX)
                        .reason         (reason)
                        .build());
                    rejectedKeys.add(key);
                    break;  // one rejection entry per lockbox
                }
            }
        }
    }

    // ====================================================================
    // ET-300 / ET-301 – warnings only
    // ====================================================================

    private void warnClosedAndNonDigital(List<LockboxEntry> lockboxes) {
        for (LockboxEntry entry : lockboxes) {
            if ("Closed".equalsIgnoreCase(entry.getLockboxStatus())) {
                log.warn("[ET-300] Closed Box – LockboxNumber={} SiteIdentifier={}",
                    entry.getLockboxNumber(), entry.getSiteIdentifier());
            }
            if (Boolean.FALSE.equals(entry.getDigitalIndicator())) {
                log.warn("[ET-301] Non-Digital Transaction – LockboxNumber={} SiteIdentifier={}",
                    entry.getLockboxNumber(), entry.getSiteIdentifier());
            }
        }
    }

    // ====================================================================
    // Flatten helpers
    // ====================================================================

    /**
     * Per-record flatten: skips entries in {@code rejectedKeys}, then checks
     * for duplicate keys within the remaining valid records.
     * Merges pre-collected rejections with any new duplicate rejections.
     */
    ParseResult flattenWithDuplicateCheck(LockboxFileRoot root,
                                          List<RejectedEntry> preRejected,
                                          Set<String> rejectedKeys) {
        List<LockboxRow>    validRows = new ArrayList<>();
        List<RejectedEntry> rejected  = new ArrayList<>(preRejected);
        Set<String>         seen      = new LinkedHashSet<>();

        for (LockboxEntry entry : root.getLockboxes()) {
            // Skip records already marked invalid
            String validationKey = entry.getSiteIdentifier() + "|" + entry.getLockboxNumber();
            if (rejectedKeys.contains(validationKey)) continue;

            String familyGci  = null;
            String primaryGci = null;
            if (entry.getGlobalClientIdentifier() != null) {
                familyGci  = entry.getGlobalClientIdentifier().getFamilyGCI();
                primaryGci = entry.getGlobalClientIdentifier().getPrimaryGCI();
            }

            for (LockboxAddress addr : entry.getAddressList()) {
                String postOfficeBox = LockboxConstants.EMPTY_POST_OFFICE_BOX;
                String dupKey = entry.getSiteIdentifier() + "|"
                              + entry.getLockboxNumber()   + "|"
                              + postOfficeBox;

                if (!seen.add(dupKey)) {
                    String reason = String.format(
                        "Duplicate key (site_identifier=%s, lockboxnumber=%s, " +
                        "postofficebox='%s') already exists in this file",
                        entry.getSiteIdentifier(), entry.getLockboxNumber(), postOfficeBox);
                    log.warn("Rejecting record – {}", reason);
                    rejected.add(RejectedEntry.builder()
                        .lockboxNumber  (entry.getLockboxNumber())
                        .siteIdentifier (entry.getSiteIdentifier())
                        .postOfficeBox  (postOfficeBox)
                        .reason         (reason)
                        .build());
                    continue;
                }

                LockboxRow row = LockboxRow.builder()
                    .lockboxNumber          (entry.getLockboxNumber())
                    .siteIdentifier         (entry.getSiteIdentifier())
                    .lockboxName            (entry.getLockboxName())
                    .lockboxStatus          (entry.getLockboxStatus())
                    .digitalIndicator       (entry.getDigitalIndicator())
                    .postalCode             (entry.getPostalCode())
                    .specificationIdentifier(root.getSpecificationIdentifier())
                    .familyGci              (familyGci)
                    .primaryGci             (primaryGci)
                    .addressType            (addr.getAddressType())
                    .addressCompanyName     (addr.getAddressCompanyName())
                    .postOfficeBox          (postOfficeBox)
                    .addressAttn            (addr.getAddressAttn())
                    .addressStreet1         (addr.getAddressStreet1())
                    .addressStreet2         (addr.getAddressStreet2())
                    .addressCity            (addr.getAddressCity())
                    .addressState           (addr.getAddressState())
                    .addressPostalCode      (addr.getAddressPostalCode())
                    .addressCountry         (addr.getAddressCountry() != null
                                               ? addr.getAddressCountry() : LockboxConstants.DEFAULT_COUNTRY)
                    .build();
                validRows.add(row.toBuilder().rowHash(HashUtil.computeRowHash(row)).build());
            }
        }

        log.info("Flatten complete – {} valid row(s), {} total rejected",
            validRows.size(), rejected.size());
        return new ParseResult(validRows, rejected);
    }

    /**
     * Strict flatten: no duplicate check, no skip (used by parse()).
     */
    List<LockboxRow> flatten(LockboxFileRoot root) {
        List<LockboxRow> rows = new ArrayList<>();

        for (LockboxEntry entry : root.getLockboxes()) {
            String familyGci  = null;
            String primaryGci = null;
            if (entry.getGlobalClientIdentifier() != null) {
                familyGci  = entry.getGlobalClientIdentifier().getFamilyGCI();
                primaryGci = entry.getGlobalClientIdentifier().getPrimaryGCI();
            }

            for (LockboxAddress addr : entry.getAddressList()) {
                LockboxRow row = LockboxRow.builder()
                    .lockboxNumber          (entry.getLockboxNumber())
                    .siteIdentifier         (entry.getSiteIdentifier())
                    .lockboxName            (entry.getLockboxName())
                    .lockboxStatus          (entry.getLockboxStatus())
                    .digitalIndicator       (entry.getDigitalIndicator())
                    .postalCode             (entry.getPostalCode())
                    .specificationIdentifier(root.getSpecificationIdentifier())
                    .familyGci              (familyGci)
                    .primaryGci             (primaryGci)
                    .addressType            (addr.getAddressType())
                    .addressCompanyName     (addr.getAddressCompanyName())
                    .postOfficeBox          (LockboxConstants.EMPTY_POST_OFFICE_BOX)
                    .addressAttn            (addr.getAddressAttn())
                    .addressStreet1         (addr.getAddressStreet1())
                    .addressStreet2         (addr.getAddressStreet2())
                    .addressCity            (addr.getAddressCity())
                    .addressState           (addr.getAddressState())
                    .addressPostalCode      (addr.getAddressPostalCode())
                    .addressCountry         (addr.getAddressCountry() != null
                                               ? addr.getAddressCountry() : LockboxConstants.DEFAULT_COUNTRY)
                    .build();
                rows.add(row.toBuilder().rowHash(HashUtil.computeRowHash(row)).build());
            }
        }
        return rows;
    }

    // ====================================================================
    // Utility
    // ====================================================================

    private String normaliseZip(String zip) {
        if (zip == null || zip.isBlank()) return null;
        return zip.trim().substring(0, Math.min(5, zip.trim().length()));
    }
}

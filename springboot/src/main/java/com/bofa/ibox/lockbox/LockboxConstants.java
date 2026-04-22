package com.bofa.ibox.lockbox;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Application-wide constants shared across services and model classes.
 *
 * Centralising these values ensures:
 *  - No silent divergence when the same literal is used in multiple places
 *  - A single place to update if naming conventions change
 *  - Compile-time safety instead of runtime string matching
 */
public final class LockboxConstants {

    private LockboxConstants() {}

    // ── File naming ───────────────────────────────────────────────────

    /** Suffix appended to a file while it is being processed (OS-level file lock). */
    public static final String PROCESSING_SUFFIX = ".processing";

    /** Matches valid incoming filenames:  DIGLBX_Aspec_YYYYMMDDThhmmss.json (EF-101) */
    public static final Pattern FILE_NAME_PATTERN =
        Pattern.compile("^DIGLBX_Aspec_\\d{8}T\\d{6}\\.json$", Pattern.CASE_INSENSITIVE);

    /** Captures the YYYYMMDD timestamp segment from a valid filename. */
    public static final Pattern FILE_DATE_PATTERN =
        Pattern.compile("DIGLBX_Aspec_(\\d{8}T\\d{6})\\.json", Pattern.CASE_INSENSITIVE);

    // ── Import log status values (ibox_lockbox_import_log.status) ─────

    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_SUCCESS      = "SUCCESS";
    public static final String STATUS_REJECTED     = "REJECTED";   // import_detail.operation

    // ── Database object names ─────────────────────────────────────────
    // Schema prefix (e.g. "ibox_uat.") is prepended at runtime from
    // LockboxImportProperties.dbSchema – only the unqualified names live here.

    public static final String TABLE_IMPORT_LOG    = "ibox_lockbox_import_log";
    public static final String TABLE_STAGING       = "ibox_lockbox_staging";
    public static final String TABLE_IMPORT_DETAIL = "ibox_lockbox_import_detail";
    public static final String PROC_IMPORT_DATA    = "import_lockbox_data";

    // ── Data defaults ─────────────────────────────────────────────────

    /** Default country code per the DIGLBX_Aspec spec (Section 4.2). */
    public static final String DEFAULT_COUNTRY = "US";

    /**
     * Default post-office-box value.
     * The column is NOT NULL in the DB; empty string means "no PO box".
     */
    public static final String EMPTY_POST_OFFICE_BOX = "";

    // ── Validation Constants ──────────────────────────────────────────

    /** Valid AddressType values per the DIGLBX_Aspec spec. */
    public static final Set<String> ALLOWED_ADDRESS_TYPES = new HashSet<>(Arrays.asList(
        "Mailing", "Alternate", "Lockbox"
    ));

    /** Standard 5 or 9 digit US ZIP code format, or alphanumeric international codes (3-15 chars). */
    public static final Pattern POSTAL_CODE_PATTERN =
        Pattern.compile("^[a-zA-Z0-9 -]{3,15}$");
}

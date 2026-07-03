package com.bofa.ibox.lockbox.model;

import java.util.List;

/**
 * Result of parsing the lockbox file.
 * Separates valid rows (ready for staging) from rejected rows.
 *
 * <p>Rejected entries may arise from any of the following:
 * <ul>
 *   <li>Schema violation on a Lockboxes[N] entry (wrong type, enum, pattern, required field)</li>
 *   <li>Bean validation failure on a LockboxEntry (@NotBlank, @Pattern, @Size)</li>
 *   <li>EV-202 AddressPostalCode does not match LockboxPostalCode</li>
 *   <li>Duplicate (site_identifier, lockboxnumber, postofficebox) within the same file</li>
 * </ul>
 * All rejected entries are written to ibox_lockbox_import_detail with operation='REJECTED'.
 */
public class ParseResult {

    /** Rows that passed all record-level validation – ready for staging */
    private final List<LockboxRow> validRows;

    /** Rows rejected due to schema, bean, EV-202, or duplicate violations */
    private final List<RejectedEntry> rejectedEntries;

    /**
     * The declared lockbox count from {@code SummaryInfo.LockboxCount} in the source file.
     * Validated by EV-215 to equal {@code Lockboxes[].size()} before this object is created,
     * so it always reflects the total number of lockbox entries present in the file.
     * Persisted to {@code ibox_lockbox_import_log.total_lockbox_count}.
     */
    private final int declaredLockboxCount;

    public ParseResult(List<LockboxRow> validRows, List<RejectedEntry> rejectedEntries,
                       int declaredLockboxCount) {
        this.validRows            = validRows;
        this.rejectedEntries      = rejectedEntries;
        this.declaredLockboxCount = declaredLockboxCount;
    }

    public List<LockboxRow> getValidRows() {
        return validRows;
    }

    public List<RejectedEntry> getRejectedEntries() {
        return rejectedEntries;
    }

    /** Total lockbox count as declared in {@code SummaryInfo.LockboxCount} of the source file. */
    public int getDeclaredLockboxCount() { return declaredLockboxCount; }

    public int validCount()    { return validRows.size(); }
    public int rejectedCount() { return rejectedEntries.size(); }
}

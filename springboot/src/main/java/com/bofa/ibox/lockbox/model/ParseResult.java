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

    public ParseResult(List<LockboxRow> validRows, List<RejectedEntry> rejectedEntries) {
        this.validRows        = validRows;
        this.rejectedEntries  = rejectedEntries;
    }

    public List<LockboxRow> getValidRows() {
        return validRows;
    }

    public List<RejectedEntry> getRejectedEntries() {
        return rejectedEntries;
    }

    public int validCount()    { return validRows.size(); }
    public int rejectedCount() { return rejectedEntries.size(); }
}

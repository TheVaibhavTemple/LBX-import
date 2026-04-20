package com.bofa.ibox.lockbox.model;

/**
 * Error codes from the Digital Lockbox Specification v1.6.1
 * Subset applicable to the driver file (DIGLBX_Aspec_*.json) import.
 */
public enum ErrorCode {

    // ── EF: Transmission-level errors (100–199) ─────────────────────
    EF_101("EF-101", "Invalid transmission format"),
    EF_102("EF-102", "Unknown file specification"),
    EF_106("EF-106", "Aged transmission creation date"),
    EF_108("EF-108", "Oversized transmission"),

    // ── EV: Data Validation errors (200–299) ────────────────────────
    EV_200("EV-200", "XSD Validation Failure"),
    EV_201("EV-201", "Lockbox account mismatch"),
    EV_202("EV-202", "Lockbox address mismatch"),
    EV_203("EV-203", "Lockbox address missing"),
    EV_204("EV-204", "Lockbox address invalid"),
    EV_205("EV-205", "Trading partner invalid"),
    EV_215("EV-215", "Invalid summary counts"),
    EV_216("EV-216", "Duplicate transmission"),

    // ── ET: Transaction-level warnings (300–399) ────────────────────
    ET_300("ET-300", "Closed Box"),
    ET_301("ET-301", "Non-Digital Transaction"),

    // ── EO: Other (400–499) ─────────────────────────────────────────
    EO_400("EO-400", "Other");

    private final String code;
    private final String description;

    ErrorCode(String code, String description) {
        this.code        = code;
        this.description = description;
    }

    public String getCode()        { return code; }
    public String getDescription() { return description; }
}

package com.bofa.ibox.lockbox.model;

import lombok.Builder;
import lombok.Getter;

/**
 * Flat staging row – one instance per lockbox × address.
 * Maps 1:1 to ibox_uat.ibox_lockbox_staging columns.
 */
@Getter
@Builder(toBuilder = true)
public class LockboxRow {

    // Lockbox fields
    private final String  lockboxNumber;
    private final String  siteIdentifier;
    private final String  lockboxName;
    private final String  lockboxStatus;
    private final Boolean digitalIndicator;
    private final String  postalCode;
    private final String  specificationIdentifier;
    private final String  familyGci;
    private final String  primaryGci;

    // Address fields
    private final String  addressType;
    private final String  addressCompanyName;
    private final String  postOfficeBox;        // defaults to '' (NOT NULL in DB)
    private final String  addressAttn;
    private final String  addressStreet1;
    private final String  addressStreet2;
    private final String  addressCity;
    private final String  addressState;
    private final String  addressPostalCode;
    private final String  addressCountry;

    /** SHA-256 hash of all data fields – used for change detection */
    private final String  rowHash;
}

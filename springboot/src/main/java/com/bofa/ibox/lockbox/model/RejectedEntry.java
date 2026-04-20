package com.bofa.ibox.lockbox.model;

import lombok.Builder;
import lombok.Getter;

/**
 * A lockbox row rejected during parsing due to a duplicate key
 * (site_identifier, lockboxnumber, postofficebox) within the same file.
 */
@Getter
@Builder
public class RejectedEntry {

    private final String lockboxNumber;
    private final String siteIdentifier;
    private final String postOfficeBox;
    private final String reason;
}

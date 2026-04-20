package com.bofa.ibox.lockbox.util;

import com.bofa.ibox.lockbox.model.LockboxRow;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Computes a deterministic SHA-256 hash of a lockbox row's data fields.
 *
 * Rules:
 *  - NULL values are treated as empty string to ensure consistency
 *  - Fields are joined with pipe "|" delimiter to prevent collision
 *    e.g. ("AB","C") != ("A","BC") → "AB|C" vs "A|BC"
 *  - Key fields (site_identifier, lockboxnumber, postofficebox) are EXCLUDED
 *    because they identify the record, not its content
 *  - Hash is used only for change detection, NOT for security
 */
public class HashUtil {

    private static final String DELIMITER = "|";

    private HashUtil() {}

    /**
     * Computes the SHA-256 hash for a {@link LockboxRow} using all data fields.
     * Returns a 64-character lowercase hex string.
     */
    public static String computeRowHash(LockboxRow row) {
        String input = String.join(DELIMITER,
            safe(row.getLockboxName()),
            safe(row.getLockboxStatus()),
            safe(row.getDigitalIndicator() != null ? row.getDigitalIndicator().toString() : null),
            safe(row.getPostalCode()),
            safe(row.getSpecificationIdentifier()),
            safe(row.getAddressType()),
            safe(row.getAddressCompanyName()),
            safe(row.getAddressAttn()),
            safe(row.getAddressStreet1()),
            safe(row.getAddressStreet2()),
            safe(row.getAddressCity()),
            safe(row.getAddressState()),
            safe(row.getAddressPostalCode()),
            safe(row.getAddressCountry())
        );
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hashBytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();   // always 64 hex chars
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available on this JVM", e);
        }
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }
}

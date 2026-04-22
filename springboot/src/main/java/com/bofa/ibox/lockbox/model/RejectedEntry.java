package com.bofa.ibox.lockbox.model;



/**
 * A lockbox row rejected during parsing due to a duplicate key
 * (site_identifier, lockboxnumber, postofficebox) within the same file.
 */
public class RejectedEntry {

    private final String lockboxNumber;
    private final String siteIdentifier;
    private final String postOfficeBox;
    private final String reason;

    private RejectedEntry(Builder builder) {
        this.lockboxNumber = builder.lockboxNumber;
        this.siteIdentifier = builder.siteIdentifier;
        this.postOfficeBox = builder.postOfficeBox;
        this.reason = builder.reason;
    }

    public String getLockboxNumber() { return lockboxNumber; }
    public String getSiteIdentifier() { return siteIdentifier; }
    public String getPostOfficeBox() { return postOfficeBox; }
    public String getReason() { return reason; }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String lockboxNumber;
        private String siteIdentifier;
        private String postOfficeBox;
        private String reason;

        public Builder lockboxNumber(String lockboxNumber) {
            this.lockboxNumber = lockboxNumber;
            return this;
        }

        public Builder siteIdentifier(String siteIdentifier) {
            this.siteIdentifier = siteIdentifier;
            return this;
        }

        public Builder postOfficeBox(String postOfficeBox) {
            this.postOfficeBox = postOfficeBox;
            return this;
        }

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public RejectedEntry build() {
            return new RejectedEntry(this);
        }
    }
}

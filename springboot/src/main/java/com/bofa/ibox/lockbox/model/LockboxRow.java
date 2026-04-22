package com.bofa.ibox.lockbox.model;

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

    private LockboxRow(Builder builder) {
        this.lockboxNumber = builder.lockboxNumber;
        this.siteIdentifier = builder.siteIdentifier;
        this.lockboxName = builder.lockboxName;
        this.lockboxStatus = builder.lockboxStatus;
        this.digitalIndicator = builder.digitalIndicator;
        this.postalCode = builder.postalCode;
        this.specificationIdentifier = builder.specificationIdentifier;
        this.familyGci = builder.familyGci;
        this.primaryGci = builder.primaryGci;
        this.addressType = builder.addressType;
        this.addressCompanyName = builder.addressCompanyName;
        this.postOfficeBox = builder.postOfficeBox;
        this.addressAttn = builder.addressAttn;
        this.addressStreet1 = builder.addressStreet1;
        this.addressStreet2 = builder.addressStreet2;
        this.addressCity = builder.addressCity;
        this.addressState = builder.addressState;
        this.addressPostalCode = builder.addressPostalCode;
        this.addressCountry = builder.addressCountry;
        this.rowHash = builder.rowHash;
    }

    public String getLockboxNumber() { return lockboxNumber; }
    public String getSiteIdentifier() { return siteIdentifier; }
    public String getLockboxName() { return lockboxName; }
    public String getLockboxStatus() { return lockboxStatus; }
    public Boolean getDigitalIndicator() { return digitalIndicator; }
    public String getPostalCode() { return postalCode; }
    public String getSpecificationIdentifier() { return specificationIdentifier; }
    public String getFamilyGci() { return familyGci; }
    public String getPrimaryGci() { return primaryGci; }
    public String getAddressType() { return addressType; }
    public String getAddressCompanyName() { return addressCompanyName; }
    public String getPostOfficeBox() { return postOfficeBox; }
    public String getAddressAttn() { return addressAttn; }
    public String getAddressStreet1() { return addressStreet1; }
    public String getAddressStreet2() { return addressStreet2; }
    public String getAddressCity() { return addressCity; }
    public String getAddressState() { return addressState; }
    public String getAddressPostalCode() { return addressPostalCode; }
    public String getAddressCountry() { return addressCountry; }
    public String getRowHash() { return rowHash; }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .lockboxNumber(this.lockboxNumber)
                .siteIdentifier(this.siteIdentifier)
                .lockboxName(this.lockboxName)
                .lockboxStatus(this.lockboxStatus)
                .digitalIndicator(this.digitalIndicator)
                .postalCode(this.postalCode)
                .specificationIdentifier(this.specificationIdentifier)
                .familyGci(this.familyGci)
                .primaryGci(this.primaryGci)
                .addressType(this.addressType)
                .addressCompanyName(this.addressCompanyName)
                .postOfficeBox(this.postOfficeBox)
                .addressAttn(this.addressAttn)
                .addressStreet1(this.addressStreet1)
                .addressStreet2(this.addressStreet2)
                .addressCity(this.addressCity)
                .addressState(this.addressState)
                .addressPostalCode(this.addressPostalCode)
                .addressCountry(this.addressCountry)
                .rowHash(this.rowHash);
    }

    public static class Builder {
        private String  lockboxNumber;
        private String  siteIdentifier;
        private String  lockboxName;
        private String  lockboxStatus;
        private Boolean digitalIndicator;
        private String  postalCode;
        private String  specificationIdentifier;
        private String  familyGci;
        private String  primaryGci;
        private String  addressType;
        private String  addressCompanyName;
        private String  postOfficeBox;
        private String  addressAttn;
        private String  addressStreet1;
        private String  addressStreet2;
        private String  addressCity;
        private String  addressState;
        private String  addressPostalCode;
        private String  addressCountry;
        private String  rowHash;

        public Builder lockboxNumber(String lockboxNumber) {
            this.lockboxNumber = lockboxNumber;
            return this;
        }

        public Builder siteIdentifier(String siteIdentifier) {
            this.siteIdentifier = siteIdentifier;
            return this;
        }

        public Builder lockboxName(String lockboxName) {
            this.lockboxName = lockboxName;
            return this;
        }

        public Builder lockboxStatus(String lockboxStatus) {
            this.lockboxStatus = lockboxStatus;
            return this;
        }

        public Builder digitalIndicator(Boolean digitalIndicator) {
            this.digitalIndicator = digitalIndicator;
            return this;
        }

        public Builder postalCode(String postalCode) {
            this.postalCode = postalCode;
            return this;
        }

        public Builder specificationIdentifier(String specificationIdentifier) {
            this.specificationIdentifier = specificationIdentifier;
            return this;
        }

        public Builder familyGci(String familyGci) {
            this.familyGci = familyGci;
            return this;
        }

        public Builder primaryGci(String primaryGci) {
            this.primaryGci = primaryGci;
            return this;
        }

        public Builder addressType(String addressType) {
            this.addressType = addressType;
            return this;
        }

        public Builder addressCompanyName(String addressCompanyName) {
            this.addressCompanyName = addressCompanyName;
            return this;
        }

        public Builder postOfficeBox(String postOfficeBox) {
            this.postOfficeBox = postOfficeBox;
            return this;
        }

        public Builder addressAttn(String addressAttn) {
            this.addressAttn = addressAttn;
            return this;
        }

        public Builder addressStreet1(String addressStreet1) {
            this.addressStreet1 = addressStreet1;
            return this;
        }

        public Builder addressStreet2(String addressStreet2) {
            this.addressStreet2 = addressStreet2;
            return this;
        }

        public Builder addressCity(String addressCity) {
            this.addressCity = addressCity;
            return this;
        }

        public Builder addressState(String addressState) {
            this.addressState = addressState;
            return this;
        }

        public Builder addressPostalCode(String addressPostalCode) {
            this.addressPostalCode = addressPostalCode;
            return this;
        }

        public Builder addressCountry(String addressCountry) {
            this.addressCountry = addressCountry;
            return this;
        }

        public Builder rowHash(String rowHash) {
            this.rowHash = rowHash;
            return this;
        }

        public LockboxRow build() {
            return new LockboxRow(this);
        }
    }
}

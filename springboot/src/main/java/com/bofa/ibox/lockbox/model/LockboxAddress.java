package com.bofa.ibox.lockbox.model;

import com.bofa.ibox.lockbox.LockboxConstants;
import com.fasterxml.jackson.annotation.JsonProperty;

public class LockboxAddress {

    /** "Mailing" or "Alternate" */
    @JsonProperty("AddressType")
    private String addressType;

    @JsonProperty("AddressCompanyName")
    private String addressCompanyName;

    @JsonProperty("AddressAttn")
    private String addressAttn;

    @JsonProperty("AddressStreet1")
    private String addressStreet1;

    @JsonProperty("AddressStreet2")
    private String addressStreet2;

    @JsonProperty("AddressCity")
    private String addressCity;

    @JsonProperty("AddressState")
    private String addressState;

    @JsonProperty("AddressPostalCode")
    private String addressPostalCode;

    /** Defaults to "US" per spec; overridable by provider if needed */
    @JsonProperty("AddressCountry")
    private String addressCountry = LockboxConstants.DEFAULT_COUNTRY;

    public String getAddressType() {
        return addressType;
    }

    public void setAddressType(String addressType) {
        this.addressType = addressType;
    }

    public String getAddressCompanyName() {
        return addressCompanyName;
    }

    public void setAddressCompanyName(String addressCompanyName) {
        this.addressCompanyName = addressCompanyName;
    }

    public String getAddressAttn() {
        return addressAttn;
    }

    public void setAddressAttn(String addressAttn) {
        this.addressAttn = addressAttn;
    }

    public String getAddressStreet1() {
        return addressStreet1;
    }

    public void setAddressStreet1(String addressStreet1) {
        this.addressStreet1 = addressStreet1;
    }

    public String getAddressStreet2() {
        return addressStreet2;
    }

    public void setAddressStreet2(String addressStreet2) {
        this.addressStreet2 = addressStreet2;
    }

    public String getAddressCity() {
        return addressCity;
    }

    public void setAddressCity(String addressCity) {
        this.addressCity = addressCity;
    }

    public String getAddressState() {
        return addressState;
    }

    public void setAddressState(String addressState) {
        this.addressState = addressState;
    }

    public String getAddressPostalCode() {
        return addressPostalCode;
    }

    public void setAddressPostalCode(String addressPostalCode) {
        this.addressPostalCode = addressPostalCode;
    }

    public String getAddressCountry() {
        return addressCountry;
    }

    public void setAddressCountry(String addressCountry) {
        this.addressCountry = addressCountry;
    }
}

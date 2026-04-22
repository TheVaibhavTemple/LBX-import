package com.bofa.ibox.lockbox.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;

public class LockboxEntry {

    @Valid
    @JsonProperty("GlobalClientIdentifier")
    private LockboxGCI globalClientIdentifier;

    @NotBlank
    @Pattern(regexp = "ATL|BOS|CHI|DAL|LAC",
             message = "SiteIdentifier must be one of ATL, BOS, CHI, DAL, LAC")
    @JsonProperty("SiteIdentifier")
    private String siteIdentifier;

    @NotBlank
    @Pattern(regexp = "[0-9]{6}",
             message = "LockboxNumber must be exactly 6 digits (EV-201)")
    @JsonProperty("LockboxNumber")
    private String lockboxNumber;

    @NotBlank
    @JsonProperty("LockboxName")
    private String lockboxName;

    @NotNull
    @Pattern(regexp = "Active|Closed",
             message = "LockboxStatus must be Active or Closed")
    @JsonProperty("LockboxStatus")
    private String lockboxStatus;

    @NotNull
    @JsonProperty("DigitalIndicator")
    private Boolean digitalIndicator;

    @NotBlank
    @Pattern(regexp = "^[0-9]{5}(-[0-9]{4})?$",
             message = "PostalCode must be 5 or 9 digit ZIP")
    @JsonProperty("PostalCode")
    private String postalCode;

    @Valid
    @NotEmpty(message = "AddressList must have at least one address")
    @JsonProperty("AddressList")
    private List<LockboxAddress> addressList;

    public LockboxGCI getGlobalClientIdentifier() {
        return globalClientIdentifier;
    }

    public void setGlobalClientIdentifier(LockboxGCI globalClientIdentifier) {
        this.globalClientIdentifier = globalClientIdentifier;
    }

    public String getSiteIdentifier() {
        return siteIdentifier;
    }

    public void setSiteIdentifier(String siteIdentifier) {
        this.siteIdentifier = siteIdentifier;
    }

    public String getLockboxNumber() {
        return lockboxNumber;
    }

    public void setLockboxNumber(String lockboxNumber) {
        this.lockboxNumber = lockboxNumber;
    }

    public String getLockboxName() {
        return lockboxName;
    }

    public void setLockboxName(String lockboxName) {
        this.lockboxName = lockboxName;
    }

    public String getLockboxStatus() {
        return lockboxStatus;
    }

    public void setLockboxStatus(String lockboxStatus) {
        this.lockboxStatus = lockboxStatus;
    }

    public Boolean getDigitalIndicator() {
        return digitalIndicator;
    }

    public void setDigitalIndicator(Boolean digitalIndicator) {
        this.digitalIndicator = digitalIndicator;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public void setPostalCode(String postalCode) {
        this.postalCode = postalCode;
    }

    public List<LockboxAddress> getAddressList() {
        return addressList;
    }

    public void setAddressList(List<LockboxAddress> addressList) {
        this.addressList = addressList;
    }
}

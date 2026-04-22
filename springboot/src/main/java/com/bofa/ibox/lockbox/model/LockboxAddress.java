package com.bofa.ibox.lockbox.model;

import com.bofa.ibox.lockbox.LockboxConstants;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
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
}

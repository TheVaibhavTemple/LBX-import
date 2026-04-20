package com.bofa.ibox.lockbox.model;

import com.bofa.ibox.lockbox.LockboxConstants;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LockboxAddress {

    /** "Mailing" or "Alternate" */
    @NotBlank
    @JsonProperty("AddressType")
    private String addressType;

    @NotBlank
    @JsonProperty("AddressCompanyName")
    private String addressCompanyName;

    @JsonProperty("AddressAttn")
    private String addressAttn;

    @NotBlank
    @JsonProperty("AddressStreet1")
    private String addressStreet1;

    @JsonProperty("AddressStreet2")
    private String addressStreet2;

    @NotBlank
    @JsonProperty("AddressCity")
    private String addressCity;

    @NotBlank
    @Size(min = 2, max = 2)
    @JsonProperty("AddressState")
    private String addressState;

    @NotBlank
    @Pattern(regexp = "^[0-9]{5}(-[0-9]{4})?$",
             message = "AddressPostalCode must be 5 or 9 digit ZIP")
    @JsonProperty("AddressPostalCode")
    private String addressPostalCode;

    /** Defaults to "US" per spec; overridable by provider if needed */
    @JsonProperty("AddressCountry")
    private String addressCountry = LockboxConstants.DEFAULT_COUNTRY;
}

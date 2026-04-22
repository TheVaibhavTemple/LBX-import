package com.bofa.ibox.lockbox.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public class LockboxGCI {

    @NotBlank
    @JsonProperty("FamilyGCI")
    private String familyGCI;

    @NotBlank
    @JsonProperty("PrimaryGCI")
    private String primaryGCI;

    public String getFamilyGCI() {
        return familyGCI;
    }

    public void setFamilyGCI(String familyGCI) {
        this.familyGCI = familyGCI;
    }

    public String getPrimaryGCI() {
        return primaryGCI;
    }

    public void setPrimaryGCI(String primaryGCI) {
        this.primaryGCI = primaryGCI;
    }
}

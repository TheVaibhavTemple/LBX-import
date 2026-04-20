package com.bofa.ibox.lockbox.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LockboxGCI {

    @NotBlank
    @JsonProperty("FamilyGCI")
    private String familyGCI;

    @NotBlank
    @JsonProperty("PrimaryGCI")
    private String primaryGCI;
}

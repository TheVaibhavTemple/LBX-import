package com.bofa.ibox.lockbox.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LockboxSummaryInfo {

    @NotBlank
    @JsonProperty("ASPECDate")
    private String aspecDate;       // kept as String; parsed to LocalDate in service

    @NotNull
    @Min(1)
    @JsonProperty("LockboxCount")
    private Integer lockboxCount;
}

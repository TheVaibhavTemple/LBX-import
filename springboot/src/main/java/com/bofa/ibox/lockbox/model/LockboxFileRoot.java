package com.bofa.ibox.lockbox.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Root structure of the daily DIGLBX_Aspec_*.json driver file.
 *
 * {
 *   "SpecificationIdentifier": "1.6.0",
 *   "Lockboxes": [ ... ],
 *   "SummaryInfo": { "ASPECDate": "2025-04-01", "LockboxCount": 1 }
 * }
 */
@Getter
@Setter
public class LockboxFileRoot {

    @NotBlank
    @JsonProperty("SpecificationIdentifier")
    private String specificationIdentifier;

    @Valid
    @NotEmpty(message = "Lockboxes array must not be empty")
    @JsonProperty("Lockboxes")
    private List<LockboxEntry> lockboxes;

    @Valid
    @NotNull
    @JsonProperty("SummaryInfo")
    private LockboxSummaryInfo summaryInfo;
}

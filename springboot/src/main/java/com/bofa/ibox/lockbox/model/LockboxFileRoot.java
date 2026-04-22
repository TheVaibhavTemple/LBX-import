package com.bofa.ibox.lockbox.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
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

    public String getSpecificationIdentifier() {
        return specificationIdentifier;
    }

    public void setSpecificationIdentifier(String specificationIdentifier) {
        this.specificationIdentifier = specificationIdentifier;
    }

    public List<LockboxEntry> getLockboxes() {
        return lockboxes;
    }

    public void setLockboxes(List<LockboxEntry> lockboxes) {
        this.lockboxes = lockboxes;
    }

    public LockboxSummaryInfo getSummaryInfo() {
        return summaryInfo;
    }

    public void setSummaryInfo(LockboxSummaryInfo summaryInfo) {
        this.summaryInfo = summaryInfo;
    }
}

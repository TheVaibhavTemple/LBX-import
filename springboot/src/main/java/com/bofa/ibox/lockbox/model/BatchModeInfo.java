package com.bofa.ibox.lockbox.model;

/**
 * Holds the batch configuration resolved from {@code batch_mode_master}
 * for a given {@code provider_id} / {@code client_id} pair.
 *
 * <p>Populated by {@link com.bofa.ibox.lockbox.service.BatchModeLookupService}
 * and passed through to the stored procedure so that
 * {@code ibox_lockbox.batchmode}, {@code batchsize}, and {@code batchmode_int}
 * are set on every INSERT.</p>
 */
public class BatchModeInfo {

    /** {@code batch_mode_master.batchmode} */
    private final String  batchMode;

    /** {@code batch_mode_master.batchsize} */
    private final Integer batchSize;

    /** {@code batch_mode_master.batchmodenum} */
    private final Integer batchModeNum;

    private BatchModeInfo(Builder builder) {
        this.batchMode    = builder.batchMode;
        this.batchSize    = builder.batchSize;
        this.batchModeNum = builder.batchModeNum;
    }

    public String  getBatchMode()    { return batchMode; }
    public Integer getBatchSize()    { return batchSize; }
    public Integer getBatchModeNum() { return batchModeNum; }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String  batchMode;
        private Integer batchSize;
        private Integer batchModeNum;

        public Builder batchMode(String batchMode) {
            this.batchMode = batchMode;
            return this;
        }

        public Builder batchSize(Integer batchSize) {
            this.batchSize = batchSize;
            return this;
        }

        public Builder batchModeNum(Integer batchModeNum) {
            this.batchModeNum = batchModeNum;
            return this;
        }

        public BatchModeInfo build() {
            return new BatchModeInfo(this);
        }
    }
}

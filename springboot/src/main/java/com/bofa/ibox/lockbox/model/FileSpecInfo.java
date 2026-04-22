package com.bofa.ibox.lockbox.model;

public class FileSpecInfo {

    /** PK of the matched ibox_file_spec row */
    private final long fileSpecId;

    /** FK from ibox_file_spec → ibox_provider */
    private final int providerId;

    /** FK from ibox_provider → ibox_client */
    private final int clientId;

    /** FK from ibox_application resolved via client → lob chain */
    private final int applicationId;

    /** lob_id from ibox_application */
    private final int lobId;

    private FileSpecInfo(Builder builder) {
        this.fileSpecId = builder.fileSpecId;
        this.providerId = builder.providerId;
        this.clientId = builder.clientId;
        this.applicationId = builder.applicationId;
        this.lobId = builder.lobId;
    }

    public long getFileSpecId() { return fileSpecId; }
    public int getProviderId() { return providerId; }
    public int getClientId() { return clientId; }
    public int getApplicationId() { return applicationId; }
    public int getLobId() { return lobId; }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private long fileSpecId;
        private int providerId;
        private int clientId;
        private int applicationId;
        private int lobId;

        public Builder fileSpecId(long fileSpecId) {
            this.fileSpecId = fileSpecId;
            return this;
        }

        public Builder providerId(int providerId) {
            this.providerId = providerId;
            return this;
        }

        public Builder clientId(int clientId) {
            this.clientId = clientId;
            return this;
        }

        public Builder applicationId(int applicationId) {
            this.applicationId = applicationId;
            return this;
        }

        public Builder lobId(int lobId) {
            this.lobId = lobId;
            return this;
        }

        public FileSpecInfo build() {
            return new FileSpecInfo(this);
        }
    }
}

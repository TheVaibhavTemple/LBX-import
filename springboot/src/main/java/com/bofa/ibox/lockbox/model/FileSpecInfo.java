package com.bofa.ibox.lockbox.model;

import lombok.Builder;
import lombok.Getter;

/**
 * Resolved file-specification identifiers looked up from the database
 * before an import begins.
 *
 * Join chain used to populate these fields:
 *   ibox_file_spec  (matched by file_name_pattern)
 *     → ibox_provider    (via file_spec.provider_id)
 *       → ibox_client    (via provider.client_id)
 *         → ibox_application (via client.lob_id = application.lob_id)
 */
@Getter
@Builder
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
}

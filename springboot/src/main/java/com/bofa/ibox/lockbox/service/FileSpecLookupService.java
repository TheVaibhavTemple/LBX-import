package com.bofa.ibox.lockbox.service;

import com.bofa.ibox.lockbox.config.LockboxImportProperties;
import com.bofa.ibox.lockbox.exception.LockboxValidationException;
import com.bofa.ibox.lockbox.model.ErrorCode;
import com.bofa.ibox.lockbox.model.FileSpecInfo;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Resolves the provider, client, and application identifiers for an incoming
 * file by matching its name against patterns registered in {@code ibox_file_spec}.
 *
 * <h3>Join chain</h3>
 * <pre>
 *  ibox_file_spec   (file_name_pattern ~* filename)
 *    └─► ibox_provider   (via file_spec.provider_id)
 *          └─► ibox_client    (via provider.client_id)
 *                └─► ibox_application (via client.lob_id = application.lob_id)
 * </pre>
 *
 * <h3>Pattern matching</h3>
 * The {@code file_name_pattern} column is treated as a PostgreSQL POSIX
 * case-insensitive regex ({@code ~*}).  Example stored value:
 * {@code ^DIGLBX_Aspec_\d{8}T\d{6}\.json$}
 *
 * <h3>Error</h3>
 * Throws {@code EF-102} if no active file spec matches – the import is aborted
 * before any DB write occurs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileSpecLookupService {

    private final JdbcTemplate            jdbcTemplate;
    private final LockboxImportProperties props;

    private String lookupSql;

    @PostConstruct
    void initSql() {
        String s = props.getDbSchema();
        /*
         * Match the filename against file_name_pattern using PostgreSQL's
         * case-insensitive POSIX regex operator (~*).
         *
         * Join path to get application_id + lob_id:
         *   file_spec → provider (is_active)
         *             → client
         *             → application (lob_id = client.lob_id, is_active)
         *
         * NOTE: this query assumes ibox_client has a lob_id column.
         *       Adjust the ON clause if your schema uses a different join key.
         */
        lookupSql =
            "SELECT " +
            "    fs.file_spec_id, " +
            "    fs.provider_id, " +
            "    p.client_id, " +
            "    a.application_id, " +
            "    a.lob_id " +
            "FROM "      + s + ".ibox_file_spec   fs " +
            "JOIN "      + s + ".ibox_provider    p  ON p.provider_id = fs.provider_id  AND p.is_active = true " +
            "JOIN "      + s + ".ibox_client      c  ON c.client_id   = p.client_id " +
            "JOIN "      + s + ".ibox_application a  ON a.lob_id      = c.lob_id        AND a.is_active = true " +
            "WHERE fs.is_active = true " +
            "  AND ? ~* fs.file_name_pattern " +   // filename must match the stored regex
            "ORDER BY fs.file_spec_id " +           // deterministic if multiple patterns match
            "LIMIT 1";
    }

    /**
     * Looks up the file spec for the given filename and returns the resolved IDs.
     *
     * @param fileName the original filename (without {@code .processing} suffix),
     *                 e.g. {@code DIGLBX_Aspec_20260416T120000.json}
     * @return fully populated {@link FileSpecInfo}
     * @throws LockboxValidationException EF-102 if no active file spec matches
     */
    public FileSpecInfo resolve(String fileName) {
        List<FileSpecInfo> results = jdbcTemplate.query(
            lookupSql,
            (rs, rowNum) -> FileSpecInfo.builder()
                .fileSpecId   (rs.getLong("file_spec_id"))
                .providerId   (rs.getInt ("provider_id"))
                .clientId     (rs.getInt ("client_id"))
                .applicationId(rs.getInt ("application_id"))
                .lobId        (rs.getInt ("lob_id"))
                .build(),
            fileName);

        if (results.isEmpty()) {
            log.error("[EF-102] No active file spec found matching filename: {}", fileName);
            throw new LockboxValidationException(ErrorCode.EF_102,
                "No active file specification found matching filename '" + fileName + "'. " +
                "Ensure the provider has registered a matching file_name_pattern in ibox_file_spec.");
        }

        FileSpecInfo info = results.get(0);
        log.info("File spec resolved – file_spec_id={}, provider_id={}, " +
                 "client_id={}, application_id={}, lob_id={}",
            info.getFileSpecId(), info.getProviderId(), info.getClientId(),
            info.getApplicationId(), info.getLobId());

        return info;
    }
}

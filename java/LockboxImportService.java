import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.sql.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Daily Lockbox Import – reads DIGLBX_Aspec_*.json and loads it into
 * the ibox_uat PostgreSQL schema.
 *
 * Flow:
 *  1. Parse JSON → list of LockboxRow (one row per lockbox + address)
 *  2. Bulk-insert into ibox_uat.ibox_lockbox_staging (batches of 1000)
 *  3. Call stored procedure ibox_uat.import_lockbox_data()
 *     which handles insert-new / update-changed / skip-unchanged
 *
 * Maven dependencies:
 *   org.postgresql:postgresql:42.x
 *   com.fasterxml.jackson.core:jackson-databind:2.x
 *
 * Usage:
 *   java LockboxImportService <file-path> <jdbc-url> <provider_id> <lob_id> <application_id>
 *
 * Example:
 *   java LockboxImportService DIGLBX_Aspec_20260416T120000.json \
 *        "jdbc:postgresql://localhost:5432/ibox?user=ibox_user&password=secret" \
 *        1 2 3
 */
public class LockboxImportService {

    // ----------------------------------------------------------------
    // Flat row POJO – mirrors ibox_lockbox_staging
    // One instance per lockbox × address combination
    // ----------------------------------------------------------------
    static class LockboxRow {
        String  lockboxNumber;
        String  siteIdentifier;
        String  lockboxName;
        String  lockboxStatus;
        Boolean digitalIndicator;
        String  postalCode;
        String  specificationIdentifier;
        String  familyGci;
        String  primaryGci;

        // Address fields
        String  addressType;
        String  addressCompanyName;
        String  postOfficeBox;          // defaults to '' when not provided
        String  addressAttn;
        String  addressStreet1;
        String  addressStreet2;
        String  addressCity;
        String  addressState;
        String  addressPostalCode;
        String  addressCountry;
    }

    // ----------------------------------------------------------------
    // Entry point
    // ----------------------------------------------------------------
    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.err.println(
                "Usage: LockboxImportService <file> <jdbc-url> " +
                "<provider_id> <lob_id> <application_id>");
            System.exit(1);
        }
        String filePath       = args[0];
        String jdbcUrl        = args[1];
        int    providerId     = Integer.parseInt(args[2]);
        int    lobId          = Integer.parseInt(args[3]);
        int    applicationId  = Integer.parseInt(args[4]);

        new LockboxImportService()
            .run(filePath, jdbcUrl, providerId, lobId, applicationId);
    }

    // ----------------------------------------------------------------
    // Orchestration
    // ----------------------------------------------------------------
    public void run(String filePath, String jdbcUrl,
                    int providerId, int lobId, int applicationId)
            throws Exception {

        System.out.println("Reading file: " + filePath);
        List<LockboxRow> rows = parseFile(filePath);
        System.out.printf("Parsed %,d lockbox-address rows%n", rows.size());

        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            conn.setAutoCommit(false);
            try {
                loadToStaging(conn, rows);
                callImportProcedure(conn,
                    new File(filePath).getName(),
                    resolveAspecDate(rows),
                    providerId, lobId, applicationId);
                conn.commit();
                System.out.println("Import committed successfully.");
            } catch (Exception e) {
                conn.rollback();
                System.err.println("Import failed – rolled back: " + e.getMessage());
                throw e;
            }
        }
    }

    // ----------------------------------------------------------------
    // Parse DIGLBX_Aspec_*.json
    //
    // Expected top-level structure:
    // {
    //   "SummaryInfo": { "ASPECDate": "2026-04-16", "LockboxCount": 19000 },
    //   "LockboxAccounts": [
    //     {
    //       "LockboxNumber": "...",
    //       "SiteIdentifier": "...",
    //       "LockboxStatus": "Active",
    //       "AccountName": "...",
    //       "DigitalIndicator": true,
    //       "PostalCode": "...",
    //       "GlobalClientIdentifier": { "FamilyGCI": "...", "PrimaryGCI": "..." },
    //       "SpecificationIdentifier": "...",
    //       "AddressList": [ { "AddressStreet1": "...", ... }, ... ]
    //     }
    //   ]
    // }
    // ----------------------------------------------------------------
    List<LockboxRow> parseFile(String filePath) throws Exception {
        ObjectMapper mapper  = new ObjectMapper();
        JsonNode     root    = mapper.readTree(new File(filePath));
        DateTimeFormatter df = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        JsonNode accounts = root.path("LockboxAccounts");
        if (accounts.isMissingNode()) {
            accounts = root;   // fallback: file is a plain array
        }

        List<LockboxRow> rows = new ArrayList<>();

        for (JsonNode node : accounts) {
            String lockboxNumber         = text(node, "LockboxNumber");
            String siteIdentifier        = text(node, "SiteIdentifier");
            String lockboxName           = text(node, "AccountName");
            String lockboxStatus         = text(node, "LockboxStatus");
            boolean digitalIndicator     = node.path("DigitalIndicator").asBoolean(false);
            String postalCode            = text(node, "PostalCode");
            String specificationId       = text(node, "SpecificationIdentifier");

            String familyGci  = null;
            String primaryGci = null;
            JsonNode gci = node.path("GlobalClientIdentifier");
            if (!gci.isMissingNode()) {
                familyGci  = text(gci, "FamilyGCI");
                primaryGci = text(gci, "PrimaryGCI");
            }

            // Resolve aspec date (may be at account level or in SummaryInfo)
            LocalDate aspecDate = null;
            JsonNode summary = node.path("SummaryInfo");
            if (!summary.isMissingNode() && !summary.path("ASPECDate").isMissingNode()) {
                aspecDate = LocalDate.parse(summary.path("ASPECDate").asText(), df);
            }

            JsonNode addressList = node.path("AddressList");

            if (addressList.isMissingNode() || !addressList.isArray() || addressList.isEmpty()) {
                // No addresses – create one row with blank address
                rows.add(buildRow(lockboxNumber, siteIdentifier, lockboxName,
                    lockboxStatus, digitalIndicator, postalCode,
                    specificationId, familyGci, primaryGci, null));
            } else {
                // One row per address entry
                for (JsonNode addrNode : addressList) {
                    rows.add(buildRow(lockboxNumber, siteIdentifier, lockboxName,
                        lockboxStatus, digitalIndicator, postalCode,
                        specificationId, familyGci, primaryGci, addrNode));
                }
            }
        }
        return rows;
    }

    private LockboxRow buildRow(String lockboxNumber, String siteIdentifier,
            String lockboxName, String lockboxStatus, boolean digitalIndicator,
            String postalCode, String specId, String familyGci, String primaryGci,
            JsonNode addrNode) {

        LockboxRow row = new LockboxRow();
        row.lockboxNumber          = lockboxNumber;
        row.siteIdentifier         = siteIdentifier;
        row.lockboxName            = lockboxName;
        row.lockboxStatus          = lockboxStatus;
        row.digitalIndicator       = digitalIndicator;
        row.postalCode             = postalCode;
        row.specificationIdentifier = specId;
        row.familyGci              = familyGci;
        row.primaryGci             = primaryGci;

        if (addrNode != null) {
            row.addressCompanyName = text(addrNode, "AddressCompanyName");
            row.addressAttn        = text(addrNode, "AdressAttention");   // spec typo preserved
            row.addressStreet1     = text(addrNode, "AddressStreet1");
            row.addressStreet2     = text(addrNode, "AddressStreet2");
            row.addressCity        = text(addrNode, "AddressCity");
            row.addressState       = text(addrNode, "AddressState");
            row.addressPostalCode  = text(addrNode, "AddressPostalCode");
            row.addressCountry     = text(addrNode, "AddressCountry");
        }

        // postofficebox is NOT NULL in the target table; default to empty string
        row.postOfficeBox = "";
        if (row.addressCountry == null || row.addressCountry.isBlank()) {
            row.addressCountry = "US";
        }
        return row;
    }

    // ----------------------------------------------------------------
    // Bulk-insert rows into ibox_lockbox_staging (batches of 1000)
    // ----------------------------------------------------------------
    void loadToStaging(Connection conn, List<LockboxRow> rows) throws SQLException {

        // Clear any leftover data from a previous failed run
        try (Statement st = conn.createStatement()) {
            st.execute("TRUNCATE ibox_uat.ibox_lockbox_staging");
        }

        final String sql =
            "INSERT INTO ibox_uat.ibox_lockbox_staging " +
            "(lockboxnumber, site_identifier, lockboxname, lockboxstatus, " +
            " digitalindicator, postalcode, specificationidentifier, " +
            " familygci, primarygci, " +
            " addresstype, addresscompanyname, postofficebox, addressattn, " +
            " addressstreet1, addressstreet2, addresscity, addressstate, " +
            " addresspostalcode, addresscountry) " +
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int count = 0;
            for (LockboxRow r : rows) {
                ps.setString (1,  r.lockboxNumber);
                ps.setString (2,  r.siteIdentifier);
                ps.setString (3,  r.lockboxName);
                ps.setString (4,  r.lockboxStatus);
                ps.setObject (5,  r.digitalIndicator);
                ps.setString (6,  r.postalCode);
                ps.setString (7,  r.specificationIdentifier);
                ps.setString (8,  r.familyGci);
                ps.setString (9,  r.primaryGci);
                ps.setString (10, r.addressType);
                ps.setString (11, r.addressCompanyName);
                ps.setString (12, r.postOfficeBox != null ? r.postOfficeBox : "");
                ps.setString (13, r.addressAttn);
                ps.setString (14, r.addressStreet1);
                ps.setString (15, r.addressStreet2);
                ps.setString (16, r.addressCity);
                ps.setString (17, r.addressState);
                ps.setString (18, r.addressPostalCode);
                ps.setString (19, r.addressCountry);
                ps.addBatch();

                if (++count % 1000 == 0) {
                    ps.executeBatch();
                    System.out.printf("  Staged %,d rows...%n", count);
                }
            }
            ps.executeBatch();  // flush remainder
        }
        System.out.printf("Staging complete: %,d rows loaded%n", rows.size());
    }

    // ----------------------------------------------------------------
    // Call the stored procedure
    // ----------------------------------------------------------------
    void callImportProcedure(Connection conn, String fileName, LocalDate aspecDate,
                             int providerId, int lobId, int applicationId)
            throws SQLException {

        final String call =
            "CALL ibox_uat.import_lockbox_data(?,?,?,?,?,?,?)";

        try (CallableStatement cs = conn.prepareCall(call)) {
            cs.setString(1, fileName);
            cs.setObject(2, aspecDate != null ? Date.valueOf(aspecDate) : null);
            cs.setInt   (3, providerId);
            cs.setInt   (4, lobId);
            cs.setInt   (5, applicationId);
            cs.setNull  (6, Types.BIGINT);          // incomingfileid – pass NULL if unused
            cs.setString(7, "LOCKBOX_IMPORT_JOB");  // modified_by
            cs.execute();
        }
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------
    private static String text(JsonNode node, String field) {
        JsonNode n = node.path(field);
        return (n.isMissingNode() || n.isNull()) ? null : n.asText();
    }

    private LocalDate resolveAspecDate(List<LockboxRow> rows) {
        // aspecDate is not stored on the row POJO, so derive from the file header
        // or return null – the procedure accepts null safely
        return null;
    }
}

package com.bofa.ibox.lockbox.service;

import com.bofa.ibox.lockbox.config.LockboxImportProperties;
import com.bofa.ibox.lockbox.exception.LockboxValidationException;
import com.bofa.ibox.lockbox.model.LockboxFileRoot;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests that exercise the real DLBX_Aspec.json file and document
 * every validation finding in that file.
 *
 * ┌─────────────────────────────────────────────────────────────────┐
 * │  Findings in DLBX_Aspec.json (500 lockboxes declared)          │
 * ├──────┬──────────────────────────────────────────────────────────┤
 * │ #    │ Issue                                                    │
 * ├──────┼──────────────────────────────────────────────────────────┤
 * │  1   │ EV-200 – Malformed JSON: missing ',' between two array   │
 * │      │  objects at line 2303 ('}{'  instead of  '},{'  )        │
 * │  2   │ EF-106 – ASPECDate "2025-12-18T17:51:28" is 119 days old │
 * │      │  (even after accepting the datetime format, date is aged) │
 * │  3   │ EV-200 – PostalCode is an integer (3, 70, 888888…) not a │
 * │      │  string; fails the 5-digit ZIP @Pattern validation        │
 * │  4   │ EV-200 – Several records have empty AddressCompanyName,  │
 * │      │  AddressStreet1, AddressCity → @NotBlank violations       │
 * │  5   │ EV-200 – AddressPostalCode="" in at least one record →    │
 * │      │  @NotBlank + @Pattern violations                          │
 * │  6   │ EF-101 – Filename "DLBX_Aspec.json" does not match the   │
 * │      │  required pattern DIGLBX_Aspec_YYYYMMDDThhmmss.json       │
 * │      │  (checked in LockboxImportService, not the parser)        │
 * └──────┴──────────────────────────────────────────────────────────┘
 *
 * The primary failure (finding #1) is malformed JSON, which stops
 * parsing before any other rule can be evaluated.
 */
class LockboxFileParserDlbxTest {

    private static final Logger log = LoggerFactory.getLogger(LockboxFileParserDlbxTest.class);

    private LockboxFileParser       parser;
    private LockboxImportProperties props;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper    = new ObjectMapper();
        Validator    validator = Validation.buildDefaultValidatorFactory().getValidator();
        props = new LockboxImportProperties();
        props.setMaxFileAgeDays(2);
        props.setMaxLockboxCount(50000);
        parser = new LockboxFileParser(mapper, validator, props);
    }

    // ================================================================
    // Finding #1 – EV-200 : Malformed JSON
    // ================================================================

    @Nested
    @DisplayName("Finding #1 – EV-200: Malformed JSON (missing comma at line 2303)")
    class MalformedJson {

        @Test
        @DisplayName("parse() throws EV-200 for the real DLBX_Aspec.json file")
        void parse_dlbxAspecJson_throwsEV200() {
            // The file has '}{' at line 2303 where '},{' is required.
            // Jackson throws JsonParseException which the parser maps to EV-200.
            String filePath = classpathFile("DLBX_Aspec.json");

            assertThatThrownBy(() -> parser.parse(filePath))
                .isInstanceOf(LockboxValidationException.class)
                .hasMessageContaining("EV-200")
                .hasMessageContaining("JSON");
        }

        @Test
        @DisplayName("parseWithResult() also throws EV-200 for the same file")
        void parseWithResult_dlbxAspecJson_throwsEV200() {
            String filePath = classpathFile("DLBX_Aspec.json");

            assertThatThrownBy(() -> parser.parseWithResult(filePath))
                .isInstanceOf(LockboxValidationException.class)
                .hasMessageContaining("EV-200");
        }

        @Test
        @DisplayName("EV-200 message includes the file name and line number")
        void ev200Message_includesFileAndLocation() {
            String filePath = classpathFile("DLBX_Aspec.json");

            assertThatThrownBy(() -> parser.parse(filePath))
                .isInstanceOf(LockboxValidationException.class)
                .hasMessageContaining("DLBX_Aspec.json")
                .hasMessageContaining("line");
        }

        @Test
        @DisplayName("An inline JSON snippet with the same '}{ defect also raises EV-200")
        void inlineSnippet_missingComma_raisesEV200() throws Exception {
            // Reproduces the exact defect from the file with a small inline fixture
            String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            String brokenJson = """
                {
                  "SpecificationIdentifier": "1.6.0",
                  "Lockboxes": [
                    {
                      "SiteIdentifier": "ATL", "LockboxNumber": "000001",
                      "LockboxName": "Alpha", "LockboxStatus": "Active",
                      "DigitalIndicator": true, "PostalCode": "10001",
                      "AddressList": [{"AddressType":"Mailing","AddressCompanyName":"Alpha",
                        "AddressAttn":"AP","AddressStreet1":"1 Main","AddressStreet2":"",
                        "AddressCity":"New York","AddressState":"NY",
                        "AddressPostalCode":"10001","AddressCountry":"US"}]
                    }{
                      "SiteIdentifier": "ATL", "LockboxNumber": "000002",
                      "LockboxName": "Beta", "LockboxStatus": "Active",
                      "DigitalIndicator": true, "PostalCode": "10002",
                      "AddressList": [{"AddressType":"Mailing","AddressCompanyName":"Beta",
                        "AddressAttn":"AP","AddressStreet1":"2 Main","AddressStreet2":"",
                        "AddressCity":"New York","AddressState":"NY",
                        "AddressPostalCode":"10002","AddressCountry":"US"}]
                    }
                  ],
                  "SummaryInfo": { "ASPECDate": \"""" + today + """
                ", "LockboxCount": 2 }
                }
                """;

            Path jsonFile = tempDir.resolve("broken.json");
            Files.writeString(jsonFile, brokenJson);

            assertThatThrownBy(() -> parser.parse(jsonFile.toAbsolutePath().toString()))
                .isInstanceOf(LockboxValidationException.class)
                .hasMessageContaining("EV-200")
                .hasMessageContaining("JSON");
        }
    }

    // ================================================================
    // Finding #2 – EF-106 : ASPECDate datetime format + aged date
    // ================================================================

    @Nested
    @DisplayName("Finding #2 – EF-106: ASPECDate datetime format and aged file")
    class AspecDateDatetime {

        @Test
        @DisplayName("ASPECDate with time component 'T17:51:28' – date part extracted, then aged check fires")
        void aspecDate_withTimeComponent_isAged_throwsEF106() {
            // "2025-12-18T17:51:28" is 119 days before today (2026-04-16).
            // Parser now strips the 'T...' part, so format is accepted,
            // but the extracted date 2025-12-18 is older than maxFileAgeDays=2.
            assertThatThrownBy(() -> parser.validateAspecDate("2025-12-18T17:51:28"))
                .isInstanceOf(LockboxValidationException.class)
                .hasMessageContaining("EF-106")
                .hasMessageContaining("aged");
        }

        @Test
        @DisplayName("ASPECDate datetime with today's date passes format and age check")
        void aspecDate_withTimeComponent_todayDate_passes() {
            String todayDatetime = LocalDate.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + "T09:00:00";

            assertThatCode(() -> parser.validateAspecDate(todayDatetime))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("ASPECDate plain yyyy-MM-dd (original format) still works")
        void aspecDate_plainDate_passes() {
            String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

            assertThatCode(() -> parser.validateAspecDate(today))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Completely invalid format still throws EF-106 'invalid format'")
        void aspecDate_garbage_throwsEF106InvalidFormat() {
            assertThatThrownBy(() -> parser.validateAspecDate("18/12/2025"))
                .isInstanceOf(LockboxValidationException.class)
                .hasMessageContaining("EF-106")
                .hasMessageContaining("invalid format");
        }
    }

    // ================================================================
    // Finding #3 – EV-200 : PostalCode as integer in JSON
    // ================================================================

    @Nested
    @DisplayName("Finding #3 – EV-200: PostalCode is an integer (e.g. 3, 70, 888888)")
    class PostalCodeAsInteger {

        @Test
        @DisplayName("PostalCode integer '3' (1 digit) fails @Pattern 5-digit ZIP → EV-200")
        void postalCodeInteger_oneDigit_failsEV200() throws Exception {
            String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            String json = lockboxJson(today, 1, "\"PostalCode\": 3",
                "\"AddressPostalCode\": \"00003\"");

            Path f = tempDir.resolve("pc_int.json");
            Files.writeString(f, json);

            assertThatThrownBy(() -> parser.parse(f.toString()))
                .isInstanceOf(LockboxValidationException.class)
                .hasMessageContaining("EV-200")
                .hasMessageContaining("PostalCode");
        }

        @Test
        @DisplayName("PostalCode integer '60661' (5 digits) is coerced to String and passes pattern")
        void postalCodeInteger_fiveDigits_passesPattern() throws Exception {
            String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            String json = lockboxJson(today, 1, "\"PostalCode\": 60661",
                "\"AddressPostalCode\": \"60661\"");

            Path f = tempDir.resolve("pc_5dig.json");
            Files.writeString(f, json);

            assertThatCode(() -> parser.parse(f.toString()))
                .doesNotThrowAnyException();
        }
    }

    // ================================================================
    // Finding #4 & #5 – EV-200 : Empty mandatory address fields
    // ================================================================

    @Nested
    @DisplayName("Finding #4 & #5 – EV-200: Empty AddressCompanyName / AddressStreet1 / AddressPostalCode")
    class EmptyMandatoryAddressFields {

        @Test
        @DisplayName("Empty AddressCompanyName fails @NotBlank → EV-200")
        void emptyAddressCompanyName_failsEV200() throws Exception {
            String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            String json = buildAddressJson(today,
                "\"AddressCompanyName\": \"\"",
                "\"AddressStreet1\": \"1 Main St\"",
                "\"AddressCity\": \"Chicago\"",
                "\"AddressPostalCode\": \"60601\"");

            Path f = tempDir.resolve("empty_company.json");
            Files.writeString(f, json);

            assertThatThrownBy(() -> parser.parse(f.toString()))
                .isInstanceOf(LockboxValidationException.class)
                .hasMessageContaining("EV-200");
        }

        @Test
        @DisplayName("Empty AddressStreet1 fails @NotBlank → EV-200")
        void emptyAddressStreet1_failsEV200() throws Exception {
            String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            String json = buildAddressJson(today,
                "\"AddressCompanyName\": \"Bank of America\"",
                "\"AddressStreet1\": \"\"",
                "\"AddressCity\": \"Chicago\"",
                "\"AddressPostalCode\": \"60601\"");

            Path f = tempDir.resolve("empty_street.json");
            Files.writeString(f, json);

            assertThatThrownBy(() -> parser.parse(f.toString()))
                .isInstanceOf(LockboxValidationException.class)
                .hasMessageContaining("EV-200");
        }

        @Test
        @DisplayName("Empty AddressPostalCode fails @NotBlank + @Pattern → EV-200")
        void emptyAddressPostalCode_failsEV200() throws Exception {
            String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            String json = buildAddressJson(today,
                "\"AddressCompanyName\": \"Bank of America\"",
                "\"AddressStreet1\": \"1 Main St\"",
                "\"AddressCity\": \"Chicago\"",
                "\"AddressPostalCode\": \"\"");

            Path f = tempDir.resolve("empty_zip.json");
            Files.writeString(f, json);

            assertThatThrownBy(() -> parser.parse(f.toString()))
                .isInstanceOf(LockboxValidationException.class)
                .hasMessageContaining("EV-200");
        }
    }

    // ================================================================
    // Per-record error marking  (parseWithResult behaviour)
    // ================================================================

    @Nested
    @DisplayName("Per-record error marking – bad records are REJECTED, good records proceed")
    class PerRecordErrorMarking {

        @Test
        @DisplayName("Mixed file: invalid records marked REJECTED, valid records returned as valid rows")
        void mixedFile_invalidRecordsRejected_validRecordsContinue() throws Exception {
            // 2 lockboxes: first has PostalCode as integer (schema violation),
            // second is fully valid. Only the second should appear in validRows.
            String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            String json = """
                {
                  "SpecificationIdentifier": "1.6.0",
                  "Lockboxes": [
                    {
                      "SiteIdentifier": "ATL", "LockboxNumber": "000001",
                      "LockboxName": "Bad Record", "LockboxStatus": "Active",
                      "DigitalIndicator": true, "PostalCode": 3,
                      "AddressList": [{"AddressType":"Lockbox","AddressCompanyName":"BofA",
                        "AddressStreet1":"1 Main","AddressStreet2":"",
                        "AddressCity":"Chicago","AddressState":"IL",
                        "AddressPostalCode":"60601","AddressCountry":"US"}]
                    },
                    {
                      "SiteIdentifier": "ATL", "LockboxNumber": "000002",
                      "LockboxName": "Good Record", "LockboxStatus": "Active",
                      "DigitalIndicator": true, "PostalCode": "60601",
                      "AddressList": [{"AddressType":"Lockbox","AddressCompanyName":"BofA",
                        "AddressStreet1":"1 Main","AddressStreet2":"",
                        "AddressCity":"Chicago","AddressState":"IL",
                        "AddressPostalCode":"60601","AddressCountry":"US"}]
                    }
                  ],
                  "SummaryInfo": { "ASPECDate": \"""" + today + """
                ", "LockboxCount": 2 }
                }
                """;

            Path f = tempDir.resolve("mixed.json");
            Files.writeString(f, json);

            com.bofa.ibox.lockbox.model.ParseResult result =
                parser.parseWithResult(f.toString());

            assertThat(result.getValidRows()).hasSize(1);
            assertThat(result.getValidRows().get(0).getLockboxNumber()).isEqualTo("000002");

            assertThat(result.getRejectedEntries()).hasSize(1);
            assertThat(result.getRejectedEntries().get(0).getLockboxNumber()).isEqualTo("000001");
            assertThat(result.getRejectedEntries().get(0).getReason())
                .contains("PostalCode");
        }

        @Test
        @DisplayName("EV-202 mismatch is warning-only: record is NOT rejected")
        void ev202Mismatch_isWarningOnly() throws Exception {
            String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            String json = """
                {
                  "SpecificationIdentifier": "1.6.0",
                  "Lockboxes": [
                    {
                      "SiteIdentifier": "ATL", "LockboxNumber": "000001",
                      "LockboxName": "Mismatch", "LockboxStatus": "Active",
                      "DigitalIndicator": true, "PostalCode": "10001",
                      "AddressList": [{"AddressType":"Lockbox","AddressCompanyName":"BofA",
                        "AddressStreet1":"1 Main","AddressStreet2":"",
                        "AddressCity":"NYC","AddressState":"NY",
                        "AddressPostalCode":"90210","AddressCountry":"US"}]
                    }
                  ],
                  "SummaryInfo": { "ASPECDate": \"""" + today + """
                ", "LockboxCount": 1 }
                }
                """;

            Path f = tempDir.resolve("ev202.json");
            Files.writeString(f, json);

            com.bofa.ibox.lockbox.model.ParseResult result =
                parser.parseWithResult(f.toString());

            // Since EV-202 is now a warning, the record should be valid
            assertThat(result.getValidRows()).hasSize(1);
            assertThat(result.getRejectedEntries()).isEmpty();
        }

        @Test
        @DisplayName("All records invalid – validRows is empty, all appear in rejectedEntries")
        void allInvalid_validRowsEmpty() throws Exception {
            String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            // Both have PostalCode as integer
            String json = """
                {
                  "SpecificationIdentifier": "1.6.0",
                  "Lockboxes": [
                    {
                      "SiteIdentifier": "ATL", "LockboxNumber": "000001",
                      "LockboxName": "Bad1", "LockboxStatus": "Active",
                      "DigitalIndicator": true, "PostalCode": 1,
                      "AddressList": [{"AddressType":"Lockbox","AddressCompanyName":"A",
                        "AddressStreet1":"1 St","AddressStreet2":"",
                        "AddressCity":"NYC","AddressState":"NY",
                        "AddressPostalCode":"10001","AddressCountry":"US"}]
                    },
                    {
                      "SiteIdentifier": "ATL", "LockboxNumber": "000002",
                      "LockboxName": "Bad2", "LockboxStatus": "Active",
                      "DigitalIndicator": true, "PostalCode": 2,
                      "AddressList": [{"AddressType":"Lockbox","AddressCompanyName":"B",
                        "AddressStreet1":"2 St","AddressStreet2":"",
                        "AddressCity":"NYC","AddressState":"NY",
                        "AddressPostalCode":"10002","AddressCountry":"US"}]
                    }
                  ],
                  "SummaryInfo": { "ASPECDate": \"""" + today + """
                ", "LockboxCount": 2 }
                }
                """;

            Path f = tempDir.resolve("all_invalid.json");
            Files.writeString(f, json);

            com.bofa.ibox.lockbox.model.ParseResult result =
                parser.parseWithResult(f.toString());

            assertThat(result.getValidRows()).isEmpty();
            assertThat(result.getRejectedEntries()).hasSize(2);
        }

        @Test
        @DisplayName("File-level violation (missing SummaryInfo) still fails entire file")
        void fileLevelViolation_stillFailsWholeFile() throws Exception {
            String json = """
                {
                  "SpecificationIdentifier": "1.6.0",
                  "Lockboxes": []
                }
                """;
            // No SummaryInfo – file-level schema violation → whole file rejected

            Path f = tempDir.resolve("no_summary.json");
            Files.writeString(f, json);

            assertThatThrownBy(() -> parser.parseWithResult(f.toString()))
                .isInstanceOf(com.bofa.ibox.lockbox.exception.LockboxValidationException.class)
                .hasMessageContaining("EV-200");
        }
    }

    // ================================================================
    // JSON Schema validation – EV-200 (structural type / required / pattern)
    // ================================================================

    @Nested
    @DisplayName("JSON Schema validation – EV-200 via DIGLBX_ASPEC.schema.json")
    class JsonSchemaValidation {

        @Test
        @DisplayName("PostalCode as integer fails schema type:string → EV-200")
        void postalCodeInteger_failsSchemaTypeCheck() throws Exception {
            String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            // Schema requires PostalCode to be a string matching 5-digit ZIP;
            // sending an integer (e.g. 3) must be rejected at the schema layer.
            String json = lockboxJson(today, 1, "\"PostalCode\": 3",
                "\"AddressPostalCode\": \"00003\"");
            Path f = tempDir.resolve("schema_pc_int.json");
            Files.writeString(f, json);

            assertThatThrownBy(() -> parser.parse(f.toString()))
                .isInstanceOf(LockboxValidationException.class)
                .hasMessageContaining("EV-200")
                .hasMessageContaining("PostalCode");
        }

        @Test
        @DisplayName("Missing required field LockboxName fails schema → EV-200")
        void missingRequiredField_failsSchema() throws Exception {
            String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            String json = """
                {
                  "SpecificationIdentifier": "1.6.0",
                  "Lockboxes": [{
                    "SiteIdentifier": "ATL",
                    "LockboxNumber": "000001",
                    "LockboxStatus": "Active",
                    "DigitalIndicator": true,
                    "PostalCode": "60601",
                    "AddressList": [{
                      "AddressType": "Lockbox",
                      "AddressCompanyName": "BofA",
                      "AddressStreet1": "1 Main",
                      "AddressCity": "Chicago",
                      "AddressState": "IL",
                      "AddressPostalCode": "60601",
                      "AddressCountry": "US"
                    }]
                  }],
                  "SummaryInfo": { "ASPECDate": \"""" + today + """
                ", "LockboxCount": 1 }
                }
                """;
            // LockboxName is required in schema
            Path f = tempDir.resolve("schema_no_name.json");
            Files.writeString(f, json);

            assertThatThrownBy(() -> parser.parse(f.toString()))
                .isInstanceOf(LockboxValidationException.class)
                .hasMessageContaining("EV-200");
        }

        @Test
        @DisplayName("Invalid SiteIdentifier fails schema enum → EV-200")
        void invalidSiteIdentifier_failsSchemaEnum() throws Exception {
            String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            String json = lockboxJson(today, 1,
                "\"PostalCode\": \"60601\"", "\"AddressPostalCode\": \"60601\"")
                .replace("\"ATL\"", "\"NYC\"");
            Path f = tempDir.resolve("schema_bad_site.json");
            Files.writeString(f, json);

            assertThatThrownBy(() -> parser.parse(f.toString()))
                .isInstanceOf(LockboxValidationException.class)
                .hasMessageContaining("EV-200")
                .hasMessageContaining("SiteIdentifier");
        }

        @Test
        @DisplayName("PostalCode pattern violation (< 3 digits) fails schema → EV-200")
        void postalCodeBadPattern_failsSchema() throws Exception {
            String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            String json = lockboxJson(today, 1,
                "\"PostalCode\": \"12\"", "\"AddressPostalCode\": \"60601\"");
            Path f = tempDir.resolve("schema_bad_zip.json");
            Files.writeString(f, json);

            assertThatThrownBy(() -> parser.parse(f.toString()))
                .isInstanceOf(LockboxValidationException.class)
                .hasMessageContaining("EV-200")
                .hasMessageContaining("PostalCode");
        }

        @Test
        @DisplayName("Valid file passes schema validation without error")
        void validFile_passesSchemaValidation() throws Exception {
            String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            String json = lockboxJson(today, 1,
                "\"PostalCode\": \"60601\"", "\"AddressPostalCode\": \"60601\"");
            Path f = tempDir.resolve("schema_valid.json");
            Files.writeString(f, json);

            assertThatCode(() -> parser.parse(f.toString()))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Missing top-level SummaryInfo fails schema → EV-200")
        void missingSummaryInfo_failsSchema() throws Exception {
            String json = """
                {
                  "SpecificationIdentifier": "1.6.0",
                  "Lockboxes": []
                }
                """;
            Path f = tempDir.resolve("schema_no_summary.json");
            Files.writeString(f, json);

            assertThatThrownBy(() -> parser.parse(f.toString()))
                .isInstanceOf(LockboxValidationException.class)
                .hasMessageContaining("EV-200")
                .hasMessageContaining("SummaryInfo");
        }

        @Test
        @DisplayName("Empty AddressList (minItems:1) fails schema → EV-200")
        void emptyAddressList_failsSchemaMinItems() throws Exception {
            String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            String json = """
                {
                  "SpecificationIdentifier": "1.6.0",
                  "Lockboxes": [{
                    "SiteIdentifier": "ATL",
                    "LockboxNumber": "000001",
                    "LockboxName": "Test",
                    "LockboxStatus": "Active",
                    "DigitalIndicator": true,
                    "PostalCode": "60601",
                    "AddressList": []
                  }],
                  "SummaryInfo": { "ASPECDate": \"""" + today + """
                ", "LockboxCount": 1 }
                }
                """;
            Path f = tempDir.resolve("schema_empty_addr.json");
            Files.writeString(f, json);

            assertThatThrownBy(() -> parser.parse(f.toString()))
                .isInstanceOf(LockboxValidationException.class)
                .hasMessageContaining("EV-200");
        }

        @Test
        @DisplayName("parse() – DLBX_Aspec.json still fails fast (strict mode)")
        void dlbxFile_strictParse_failsOnFirstSchemaViolation() {
            // parse() is strict: the first schema violation (any of the 427 bad PostalCodes)
            // causes the whole file to be rejected immediately.
            String filePath = classpathFile("DLBX_Aspec.json");

            assertThatThrownBy(() -> parser.parse(filePath))
                .isInstanceOf(LockboxValidationException.class)
                .hasMessageContaining("EV-200");
        }

        @Test
        @DisplayName("parseWithResult() – DLBX_Aspec.json marks bad records, returns valid rows")
        void dlbxFile_lenientParse_marksInvalidRecords() throws Exception {
            // parseWithResult() marks each invalid record individually.
            // The DLBX file has 500 lockboxes; many have PostalCode issues,
            // empty fields, or EV-202 mismatches – all are marked REJECTED.
            // The remaining valid records are returned in validRows.
            String filePath = classpathFile("DLBX_Aspec.json");

            com.bofa.ibox.lockbox.model.ParseResult result =
                parser.parseWithResult(filePath);

            // At least some records are rejected
            assertThat(result.getRejectedEntries())
                .as("Expected rejected entries for invalid records")
                .isNotEmpty();

            // Total = valid + rejected = 500 (minus 11 duplicates counted separately)
            assertThat(result.validCount() + result.rejectedCount())
                .isLessThanOrEqualTo(500);

            // Each rejected entry must have a reason
            assertThat(result.getRejectedEntries())
                .allMatch(r -> r.getReason() != null && !r.getReason().isBlank());

            log.info("DLBX_Aspec.json: {} valid, {} rejected",
                result.validCount(), result.rejectedCount());
        }
    }

    // ================================================================
    // Finding #6 – EF-101 is enforced in LockboxImportService
    //              (Verified here against the service's pattern directly)
    // ================================================================

    @Nested
    @DisplayName("Finding #6 – EF-101: 'DLBX_Aspec.json' does not match required filename format")
    class FilenameFormat {

        @Test
        @DisplayName("'DLBX_Aspec.json' fails EF-101 pattern DIGLBX_Aspec_YYYYMMDDThhmmss.json")
        void dlbxFilename_doesNotMatchPattern() {
            // The pattern is enforced in LockboxImportService.validateFileName().
            // We verify the pattern directly here so the finding is clearly documented.
            java.util.regex.Pattern ef101 =
                java.util.regex.Pattern.compile(
                    "^DIGLBX_Aspec_\\d{8}T\\d{6}\\.json$",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

            assertThat(ef101.matcher("DLBX_Aspec.json").matches())
                .as("DLBX_Aspec.json must not match the EF-101 filename pattern")
                .isFalse();
        }

        @Test
        @DisplayName("A correctly named file matches the EF-101 pattern")
        void correctFilename_matchesPattern() {
            java.util.regex.Pattern ef101 =
                java.util.regex.Pattern.compile(
                    "^DIGLBX_Aspec_\\d{8}T\\d{6}\\.json$",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

            assertThat(ef101.matcher("DIGLBX_Aspec_20260416T120000.json").matches())
                .isTrue();
        }
    }

    // ================================================================
    // Helpers
    // ================================================================

    /**
     * Resolves a test-resource file to its absolute path.
     */
    private String classpathFile(String resourceName) {
        URL url = getClass().getClassLoader().getResource(resourceName);
        if (url == null) throw new IllegalStateException("Test resource not found: " + resourceName);
        return new File(url.getFile()).getAbsolutePath();
    }

    /**
     * Builds a minimal single-lockbox JSON where {@code postalCodeField} and
     * {@code addressPostalCodeField} are injectable as raw JSON field tokens.
     */
    private String lockboxJson(String today, int count, String postalCodeField,
                                String addressPostalCodeField) {
        return """
            {
              "SpecificationIdentifier": "1.6.0",
              "Lockboxes": [{
                "GlobalClientIdentifier": { "FamilyGCI": "111111", "PrimaryGCI": "222222" },
                "SiteIdentifier": "ATL",
                "LockboxNumber": "000001",
                "LockboxName": "Test",
                "LockboxStatus": "Active",
                "DigitalIndicator": true,
                %s,
                "AddressList": [{
                  "AddressType": "Lockbox",
                  "AddressCompanyName": "Bank of America",
                  "AddressAttn": "Test",
                  "AddressStreet1": "1 Main St",
                  "AddressStreet2": "",
                  "AddressCity": "Chicago",
                  "AddressState": "IL",
                  %s,
                  "AddressCountry": "US"
                }]
              }],
              "SummaryInfo": { "ASPECDate": "%s", "LockboxCount": %d }
            }
            """.formatted(postalCodeField, addressPostalCodeField, today, count);
    }

    /**
     * Builds a single-lockbox JSON with injectable address field tokens.
     */
    private String buildAddressJson(String today,
                                     String companyField,
                                     String street1Field,
                                     String cityField,
                                     String postalCodeField) {
        return """
            {
              "SpecificationIdentifier": "1.6.0",
              "Lockboxes": [{
                "SiteIdentifier": "CHI",
                "LockboxNumber": "000001",
                "LockboxName": "Test",
                "LockboxStatus": "Active",
                "DigitalIndicator": true,
                "PostalCode": "60601",
                "AddressList": [{
                  "AddressType": "Lockbox",
                  %s,
                  "AddressAttn": "Test",
                  %s,
                  "AddressStreet2": "",
                  %s,
                  "AddressState": "IL",
                  %s,
                  "AddressCountry": "US"
                }]
              }],
              "SummaryInfo": { "ASPECDate": "%s", "LockboxCount": 1 }
            }
            """.formatted(companyField, street1Field, cityField, postalCodeField, today);
    }
}

package com.bofa.ibox.lockbox.service;

import com.bofa.ibox.lockbox.config.LockboxImportProperties;
import com.bofa.ibox.lockbox.exception.LockboxValidationException;
import com.bofa.ibox.lockbox.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class LockboxFileParserTest {

    private LockboxFileParser      parser;
    private LockboxImportProperties props;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper   = new ObjectMapper();
        Validator    validator = Validation.buildDefaultValidatorFactory().getValidator();
        props = new LockboxImportProperties();
        props.setMaxFileAgeDays(2);
        props.setMaxLockboxCount(50000);
        parser = new LockboxFileParser(mapper, validator, props);
    }

    // ================================================================
    // Happy path
    // ================================================================

    @Nested
    class HappyPath {

        @Test
        void parse_validFile_returnsExpectedRows() throws Exception {
            File file = writeJson(validJson());

            List<LockboxRow> rows = parser.parse(file.getAbsolutePath());

            assertThat(rows).hasSize(1);
            LockboxRow row = rows.get(0);
            assertThat(row.getLockboxNumber())     .isEqualTo("123456");
            assertThat(row.getSiteIdentifier())    .isEqualTo("DAL");
            assertThat(row.getLockboxName())       .isEqualTo("Sears");
            assertThat(row.getLockboxStatus())     .isEqualTo("Active");
            assertThat(row.getDigitalIndicator())  .isTrue();
            assertThat(row.getPostalCode())        .isEqualTo("32809");
            assertThat(row.getFamilyGci())         .isEqualTo("111111");
            assertThat(row.getPrimaryGci())        .isEqualTo("222222");
            assertThat(row.getAddressType())       .isEqualTo("Mailing");
            assertThat(row.getAddressStreet1())    .isEqualTo("7492 Chancellor DR");
            assertThat(row.getAddressCity())       .isEqualTo("Orlando");
            assertThat(row.getAddressState())      .isEqualTo("FL");
            assertThat(row.getAddressPostalCode()) .isEqualTo("32809");
            assertThat(row.getAddressCountry())    .isEqualTo("US");
            assertThat(row.getPostOfficeBox())     .isEqualTo("");
        }

        @Test
        void parse_lockboxWithMultipleAddresses_returnsOneRowPerAddress() throws Exception {
            File file = writeJson(TWO_ADDRESS_JSON);

            List<LockboxRow> rows = parser.parse(file.getAbsolutePath());

            assertThat(rows).hasSize(2);
            assertThat(rows).extracting(LockboxRow::getAddressType)
                .containsExactly("Mailing", "Alternate");
        }

        @Test
        void parse_multipleLockboxes_returnsRowsForAll() throws Exception {
            File file = writeJson(twoLockboxJson());

            List<LockboxRow> rows = parser.parse(file.getAbsolutePath());

            assertThat(rows).hasSize(2);
            assertThat(rows).extracting(LockboxRow::getLockboxNumber)
                .containsExactlyInAnyOrder("100001", "100002");
        }
    }

    // ================================================================
    // EV-200 : JSON schema validation (runs before bean validation)
    // ================================================================

    @Nested
    class EV200_JsonSchemaValidation {

        @Test
        void malformedJson_missingComma_throwsEV200() throws Exception {
            String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            String broken = """
                {
                  "SpecificationIdentifier": "1.6.0",
                  "Lockboxes": [
                    { "SiteIdentifier": "ATL", "LockboxNumber": "000001",
                      "LockboxName": "A", "LockboxStatus": "Active",
                      "DigitalIndicator": true, "PostalCode": "10001",
                      "AddressList": [{"AddressType":"Mailing","AddressCompanyName":"A",
                        "AddressAttn":"X","AddressStreet1":"1 St","AddressStreet2":"",
                        "AddressCity":"NY","AddressState":"NY",
                        "AddressPostalCode":"10001","AddressCountry":"US"}]
                    }{
                      "SiteIdentifier": "ATL", "LockboxNumber": "000002",
                      "LockboxName": "B", "LockboxStatus": "Active",
                      "DigitalIndicator": true, "PostalCode": "10002",
                      "AddressList": [{"AddressType":"Mailing","AddressCompanyName":"B",
                        "AddressAttn":"X","AddressStreet1":"2 St","AddressStreet2":"",
                        "AddressCity":"NY","AddressState":"NY",
                        "AddressPostalCode":"10002","AddressCountry":"US"}]
                    }
                  ],
                  "SummaryInfo": { "ASPECDate": \"""" + today + """
                ", "LockboxCount": 2 }
                }
                """;
            File f = writeJson(broken);
            assertThatThrownBy(() -> parser.parse(f.getAbsolutePath()))
                .isInstanceOf(LockboxValidationException.class)
                .hasMessageContaining("EV-200")
                .hasMessageContaining("line");
        }

        @Test
        void postalCodeAsInteger_failsSchemaBeforeBeanValidation() throws Exception {
            // Schema catches type:string violation on PostalCode before Jackson
            // even deserialises the object. Error still reported as EV-200.
            File file = writeJson(validJson().replace("\"PostalCode\": \"32809\"", "\"PostalCode\": 32809"));
            assertThatThrownBy(() -> parser.parse(file.getAbsolutePath()))
                .isInstanceOf(LockboxValidationException.class)
                .hasMessageContaining("EV-200");
        }

        @Test
        void missingSummaryInfo_failsSchema() throws Exception {
            String json = """
                {
                  "SpecificationIdentifier": "1.6.0",
                  "Lockboxes": []
                }
                """;
            File f = writeJson(json);
            assertThatThrownBy(() -> parser.parse(f.getAbsolutePath()))
                .isInstanceOf(LockboxValidationException.class)
                .hasMessageContaining("EV-200")
                .hasMessageContaining("SummaryInfo");
        }

        @Test
        void missingTopLevelLockboxes_failsSchema() throws Exception {
            String json = """
                {
                  "SpecificationIdentifier": "1.6.0",
                  "SummaryInfo": { "ASPECDate": "2026-04-16", "LockboxCount": 0 }
                }
                """;
            File f = writeJson(json);
            assertThatThrownBy(() -> parser.parse(f.getAbsolutePath()))
                .isInstanceOf(LockboxValidationException.class)
                .hasMessageContaining("EV-200")
                .hasMessageContaining("Lockboxes");
        }
    }

    // ================================================================
    // EV-200 : Bean validation
    // ================================================================

    @Nested
    class EV200_BeanValidation {

        @Test
        void invalidSiteIdentifier_throwsEV200() {
            File file = writeJson(validJson().replace("\"DAL\"", "\"XYZ\""));

            assertThatThrownBy(() -> parser.parse(file.getAbsolutePath()))
                .isInstanceOf(LockboxValidationException.class)
                .hasMessageContaining("EV-200")
                .hasMessageContaining("SiteIdentifier");
        }

        @Test
        void invalidLockboxStatus_throwsEV200() {
            File file = writeJson(validJson().replace("\"Active\"", "\"Pending\""));

            assertThatThrownBy(() -> parser.parse(file.getAbsolutePath()))
                .isInstanceOf(LockboxValidationException.class)
                .hasMessageContaining("EV-200")
                .hasMessageContaining("LockboxStatus");
        }

        @Test
        void missingLockboxName_throwsEV200() {
            File file = writeJson(validJson().replace("\"LockboxName\": \"Sears\",", ""));

            assertThatThrownBy(() -> parser.parse(file.getAbsolutePath()))
                .isInstanceOf(LockboxValidationException.class)
                .hasMessageContaining("EV-200");
        }

        @Test
        void missingAddressCity_throwsEV200() {
            File file = writeJson(validJson().replace("\"AddressCity\": \"Orlando\",", ""));

            assertThatThrownBy(() -> parser.parse(file.getAbsolutePath()))
                .isInstanceOf(LockboxValidationException.class)
                .hasMessageContaining("EV-200");
        }

        @Test
        void addressStateWrongLength_throwsEV200() {
            File file = writeJson(validJson().replace("\"AddressState\": \"FL\"", "\"AddressState\": \"FLA\""));

            assertThatThrownBy(() -> parser.parse(file.getAbsolutePath()))
                .isInstanceOf(LockboxValidationException.class)
                .hasMessageContaining("EV-200")
                .hasMessageContaining("addressState");
        }

        @Test
        void invalidAddressPostalCode_throwsEV200() {
            File file = writeJson(validJson().replace("\"AddressPostalCode\": \"32809\"", "\"AddressPostalCode\": \"ABCDE\""));

            assertThatThrownBy(() -> parser.parse(file.getAbsolutePath()))
                .isInstanceOf(LockboxValidationException.class)
                .hasMessageContaining("EV-200");
        }
    }

    // ================================================================
    // EV-201 : LockboxNumber format [0-9]{6}
    // ================================================================

    @Nested
    class EV201_LockboxNumberFormat {

        @Test
        void lockboxNumberTooShort_throwsEV200WithEV201Message() {
            File file = writeJson(validJson().replace("\"123456\"", "\"12345\""));

            assertThatThrownBy(() -> parser.parse(file.getAbsolutePath()))
                .isInstanceOf(LockboxValidationException.class)
                .hasMessageContaining("EV-201");
        }

        @Test
        void lockboxNumberContainsLetters_throwsEV200() {
            File file = writeJson(validJson().replace("\"123456\"", "\"12345A\""));

            assertThatThrownBy(() -> parser.parse(file.getAbsolutePath()))
                .isInstanceOf(LockboxValidationException.class)
                .hasMessageContaining("EV-201");
        }

        @Test
        void lockboxNumberTooLong_throwsEV200() {
            File file = writeJson(validJson().replace("\"123456\"", "\"1234567\""));

            assertThatThrownBy(() -> parser.parse(file.getAbsolutePath()))
                .isInstanceOf(LockboxValidationException.class)
                .hasMessageContaining("EV-201");
        }
    }

    // ================================================================
    // EV-202 : Address PostalCode must match Lockbox PostalCode
    // ================================================================

    @Nested
    class EV202_AddressPostalCodeMismatch {

        @Test
        void addressZipDiffersFromLockboxZip_throwsEV202() {
            // lockbox PostalCode = 32809, address PostalCode = 90210 → mismatch
            File file = writeJson(validJson().replace("\"AddressPostalCode\": \"32809\"", "\"AddressPostalCode\": \"90210\""));

            assertThatThrownBy(() -> parser.parse(file.getAbsolutePath()))
                .isInstanceOf(LockboxValidationException.class)
                .hasMessageContaining("EV-202")
                .hasMessageContaining("90210")
                .hasMessageContaining("32809");
        }

        @Test
        void addressZipPlus4MatchesBase5_passes() throws Exception {
            // lockbox PostalCode = 32809, address PostalCode = 32809-1234 → base matches
            File file = writeJson(validJson().replace("\"AddressPostalCode\": \"32809\"", "\"AddressPostalCode\": \"32809-1234\""));

            assertThatCode(() -> parser.parse(file.getAbsolutePath()))
                .doesNotThrowAnyException();
        }
    }

    // ================================================================
    // EV-203 : AddressList must not be empty
    // ================================================================

    @Nested
    class EV203_AddressMissing {

        @Test
        void emptyAddressList_throwsEV200() {
            // @NotEmpty on AddressList triggers EV-200 bean violation
            File file = writeJson(validJson().replace(
                "\"AddressList\": [{",
                "\"AddressList\": [ /*REMOVE*/ {").replace(
                "/*REMOVE*/ {", "").replace(
                // simpler approach: use fixture
                "\"AddressList\": [", "\"AddressList_DISABLED\": ["));
            // Use dedicated fixture
            File f2 = writeJson(EMPTY_ADDRESS_JSON);

            assertThatThrownBy(() -> parser.parse(f2.getAbsolutePath()))
                .isInstanceOf(LockboxValidationException.class)
                .hasMessageContaining("EV-200");
        }
    }

    // ================================================================
    // EV-215 : SummaryInfo.LockboxCount mismatch
    // ================================================================

    @Nested
    class EV215_SummaryCountMismatch {

        @Test
        void countHigherThanActual_throwsEV215() {
            File file = writeJson(validJson().replace("\"LockboxCount\": 1", "\"LockboxCount\": 2"));

            assertThatThrownBy(() -> parser.parse(file.getAbsolutePath()))
                .isInstanceOf(LockboxValidationException.class)
                .hasMessageContaining("EV-215")
                .hasMessageContaining("LockboxCount=2")
                .hasMessageContaining("actual count=1");
        }

        @Test
        void countLowerThanActual_throwsEV215() throws Exception {
            File file = writeJson(twoLockboxJson().replace("\"LockboxCount\": 2", "\"LockboxCount\": 1"));

            assertThatThrownBy(() -> parser.parse(file.getAbsolutePath()))
                .isInstanceOf(LockboxValidationException.class)
                .hasMessageContaining("EV-215");
        }
    }

    // ================================================================
    // EF-106 : ASPECDate must not be too old
    // ================================================================

    @Nested
    class EF106_AgedTransmissionDate {

        @Test
        void agedAspecDate_throwsEF106() {
            String oldDate = LocalDate.now().minusDays(5)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            File file = writeJson(validJson().replace("\"ASPECDate\": \"2026-04-16\"", "\"ASPECDate\": \"" + oldDate + "\""));

            assertThatThrownBy(() -> parser.parse(file.getAbsolutePath()))
                .isInstanceOf(LockboxValidationException.class)
                .hasMessageContaining("EF-106")
                .hasMessageContaining("aged");
        }

        @Test
        void todayAspecDate_passes() throws Exception {
            String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            File file = writeJson(validJson().replace("\"ASPECDate\": \"2026-04-16\"", "\"ASPECDate\": \"" + today + "\""));

            assertThatCode(() -> parser.parse(file.getAbsolutePath()))
                .doesNotThrowAnyException();
        }

        @Test
        void missingAspecDate_throwsEF106() {
            assertThatThrownBy(() -> parser.validateAspecDate(null))
                .isInstanceOf(LockboxValidationException.class)
                .hasMessageContaining("EF-106")
                .hasMessageContaining("missing");
        }

        @Test
        void invalidAspecDateFormat_throwsEF106() {
            assertThatThrownBy(() -> parser.validateAspecDate("16-04-2026"))
                .isInstanceOf(LockboxValidationException.class)
                .hasMessageContaining("EF-106")
                .hasMessageContaining("invalid format");
        }

        @Test
        void aspecDateWithTimeComponent_todayDatetime_passes() throws Exception {
            // Provider may send ASPECDate as "yyyy-MM-ddTHH:mm:ss" – the time part is stripped
            String todayWithTime = LocalDate.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + "T17:51:28";

            assertThatCode(() -> parser.validateAspecDate(todayWithTime))
                .doesNotThrowAnyException();
        }

        @Test
        void aspecDateWithTimeComponent_agedDate_throwsEF106() {
            // Even with the time-stripping fix, an old date is still rejected
            assertThatThrownBy(() -> parser.validateAspecDate("2025-12-18T17:51:28"))
                .isInstanceOf(LockboxValidationException.class)
                .hasMessageContaining("EF-106")
                .hasMessageContaining("aged");
        }
    }

    // ================================================================
    // EF-108 : Oversized transmission
    // ================================================================

    @Nested
    class EF108_OversizedTransmission {

        @Test
        void lockboxCountExceedsMax_throwsEF108() throws Exception {
            props.setMaxLockboxCount(1);
            File file = writeJson(twoLockboxJson());

            assertThatThrownBy(() -> parser.parse(file.getAbsolutePath()))
                .isInstanceOf(LockboxValidationException.class)
                .hasMessageContaining("EF-108")
                .hasMessageContaining("exceeds the maximum");
        }

        @Test
        void lockboxCountAtMax_passes() throws Exception {
            props.setMaxLockboxCount(2);
            File file = writeJson(twoLockboxJson());

            assertThatCode(() -> parser.parse(file.getAbsolutePath()))
                .doesNotThrowAnyException();
        }
    }

    // ================================================================
    // ET-300 / ET-301 : Warnings only (import is NOT rejected)
    // ================================================================

    @Nested
    class ET300_ET301_Warnings {

        @Test
        void closedBox_doesNotThrow() throws Exception {
            File file = writeJson(validJson().replace("\"Active\"", "\"Closed\""));

            // ET-300 is a warning – import should still succeed
            assertThatCode(() -> parser.parse(file.getAbsolutePath()))
                .doesNotThrowAnyException();
        }

        @Test
        void nonDigitalIndicator_doesNotThrow() throws Exception {
            File file = writeJson(validJson().replace("\"DigitalIndicator\": true", "\"DigitalIndicator\": false"));

            // ET-301 is a warning – import should still succeed
            assertThatCode(() -> parser.parse(file.getAbsolutePath()))
                .doesNotThrowAnyException();
        }
    }

    // ================================================================
    // flatten() helpers
    // ================================================================

    @Nested
    class Flatten {

        @Test
        void setsDefaultPostOfficeBox() throws Exception {
            LockboxFileRoot root = new ObjectMapper().readValue(validJson(), LockboxFileRoot.class);
            assertThat(parser.flatten(root)).allMatch(r -> "".equals(r.getPostOfficeBox()));
        }

        @Test
        void defaultsAddressCountryToUS_whenNull() throws Exception {
            String json = validJson().replace("\"AddressCountry\": \"US\"", "\"AddressCountry\": null");
            LockboxFileRoot root = new ObjectMapper().readValue(json, LockboxFileRoot.class);
            assertThat(parser.flatten(root)).allMatch(r -> "US".equals(r.getAddressCountry()));
        }
    }

    // ================================================================
    // Fixtures
    // ================================================================

    /** Returns valid JSON with today's date so EF-106 does not fail */
    private String validJson() {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        return """
            {
              "SpecificationIdentifier": "1.6.0",
              "Lockboxes": [{
                "GlobalClientIdentifier": { "FamilyGCI": "111111", "PrimaryGCI": "222222" },
                "SiteIdentifier": "DAL",
                "LockboxNumber": "123456",
                "LockboxName": "Sears",
                "LockboxStatus": "Active",
                "DigitalIndicator": true,
                "PostalCode": "32809",
                "AddressList": [{
                  "AddressType": "Mailing",
                  "AddressCompanyName": "Taylor Farms Florida Inc",
                  "AddressAttn": "Kathy Blair",
                  "AddressStreet1": "7492 Chancellor DR",
                  "AddressStreet2": "Suite 100",
                  "AddressCity": "Orlando",
                  "AddressState": "FL",
                  "AddressPostalCode": "32809",
                  "AddressCountry": "US"
                }]
              }],
              "SummaryInfo": { "ASPECDate": \""""  + today + """
            ", "LockboxCount": 1 }
            }
            """;
    }

    private String twoLockboxJson() {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        return """
            {
              "SpecificationIdentifier": "1.6.0",
              "Lockboxes": [
                {
                  "SiteIdentifier": "BOS", "LockboxNumber": "100001",
                  "LockboxName": "Company A", "LockboxStatus": "Active",
                  "DigitalIndicator": true, "PostalCode": "02101",
                  "AddressList": [{
                    "AddressType": "Mailing", "AddressCompanyName": "Company A",
                    "AddressAttn": "AP", "AddressStreet1": "1 State St", "AddressStreet2": "",
                    "AddressCity": "Boston", "AddressState": "MA",
                    "AddressPostalCode": "02101", "AddressCountry": "US"
                  }]
                },
                {
                  "SiteIdentifier": "CHI", "LockboxNumber": "100002",
                  "LockboxName": "Company B", "LockboxStatus": "Closed",
                  "DigitalIndicator": false, "PostalCode": "60601",
                  "AddressList": [{
                    "AddressType": "Mailing", "AddressCompanyName": "Company B",
                    "AddressAttn": "Finance", "AddressStreet1": "2 Michigan Ave", "AddressStreet2": "",
                    "AddressCity": "Chicago", "AddressState": "IL",
                    "AddressPostalCode": "60601", "AddressCountry": "US"
                  }]
                }
              ],
              "SummaryInfo": { "ASPECDate": \""""  + today + """
            ", "LockboxCount": 2 }
            }
            """;
    }

    static final String TWO_ADDRESS_JSON;
    static {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        TWO_ADDRESS_JSON = """
            {
              "SpecificationIdentifier": "1.6.0",
              "Lockboxes": [{
                "SiteIdentifier": "ATL", "LockboxNumber": "999001",
                "LockboxName": "Acme Corp", "LockboxStatus": "Active",
                "DigitalIndicator": true, "PostalCode": "30301",
                "AddressList": [
                  {
                    "AddressType": "Mailing", "AddressCompanyName": "Acme Corp",
                    "AddressAttn": "Billing", "AddressStreet1": "100 Main St", "AddressStreet2": "",
                    "AddressCity": "Atlanta", "AddressState": "GA",
                    "AddressPostalCode": "30301", "AddressCountry": "US"
                  },
                  {
                    "AddressType": "Alternate", "AddressCompanyName": "Acme Corp Alt",
                    "AddressAttn": "Finance", "AddressStreet1": "200 Alt Ave", "AddressStreet2": "",
                    "AddressCity": "Atlanta", "AddressState": "GA",
                    "AddressPostalCode": "30301", "AddressCountry": "US"
                  }
                ]
              }],
              "SummaryInfo": { "ASPECDate": \"""" + today + """
            ", "LockboxCount": 1 }
            }
            """;
    }

    static final String EMPTY_ADDRESS_JSON;
    static {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        EMPTY_ADDRESS_JSON = """
            {
              "SpecificationIdentifier": "1.6.0",
              "Lockboxes": [{
                "SiteIdentifier": "DAL", "LockboxNumber": "123456",
                "LockboxName": "Sears", "LockboxStatus": "Active",
                "DigitalIndicator": true, "PostalCode": "32809",
                "AddressList": []
              }],
              "SummaryInfo": { "ASPECDate": \"""" + today + """
            ", "LockboxCount": 1 }
            }
            """;
    }

    private File writeJson(String content) {
        try {
            Path p = tempDir.resolve("test_" + System.nanoTime() + ".json");
            Files.writeString(p, content);
            return p.toFile();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}

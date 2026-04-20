package com.bofa.ibox.lockbox.service;

import com.bofa.ibox.lockbox.config.LockboxImportProperties;
import com.bofa.ibox.lockbox.exception.LockboxValidationException;
import com.bofa.ibox.lockbox.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LockboxImportServiceTest {

    @Mock LockboxFileParser       fileParser;
    @Mock LockboxStagingService   stagingService;
    @Mock FileSpecLookupService   fileSpecLookupService;
    @Mock LockboxImportProperties props;
    @Mock JdbcTemplate            jdbcTemplate;

    @InjectMocks
    LockboxImportService importService;

    @TempDir
    Path tempDir;

    /** Valid .processing file (original name = DIGLBX_Aspec_20260416T120000.json) */
    private File validProcessingFile;

    @BeforeEach
    void setUp() throws IOException {
        // Simulate the file-locking rename: watcher renames to *.processing
        Path processing = tempDir.resolve("DIGLBX_Aspec_20260416T120000.json.processing");
        Files.writeString(processing, "{}");
        validProcessingFile = processing.toFile();

        when(props.getDbSchema()).thenReturn("ibox_uat");
        // Default file-spec stub: resolves to provider=1, lob=2, application=3
        when(fileSpecLookupService.resolve(anyString())).thenReturn(mockFileSpecInfo());
        importService.initSql();   // @PostConstruct is not called by @InjectMocks – trigger manually
    }

    // ================================================================
    // Happy path
    // ================================================================

    @Nested
    class HappyPath {

        @Test
        void processFile_validFile_callsStagingAndProcedure() throws Exception {
            LockboxRow  mockRow    = mockRow("123456");
            ParseResult mockResult = new ParseResult(List.of(mockRow), List.of());

            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any()))
                .thenReturn(0);                                     // not a duplicate
            when(stagingService.createImportLog(anyString(), any()))
                .thenReturn(7L);                                    // importLogId = 7
            when(fileParser.parseWithResult(anyString()))
                .thenReturn(mockResult);

            importService.processFile(validProcessingFile);

            // Original filename (without .processing) must be passed to all DB calls
            verify(stagingService).createImportLog(
                eq("DIGLBX_Aspec_20260416T120000.json"),
                eq(LocalDate.of(2026, 4, 16)));

            verify(stagingService).loadStaging(eq(7L), eq(List.of(mockRow)));
            verify(stagingService).logRejected(eq(7L), eq(List.of()));
            verify(stagingService).callImportProcedure(
                eq(7L),
                eq("DIGLBX_Aspec_20260416T120000.json"),
                eq(LocalDate.of(2026, 4, 16)),
                eq(1),   // providerId
                eq(2),   // lobId
                eq(3),   // applicationId
                eq(0));  // rejectedCount
        }

        @Test
        void processFile_noValidRows_skipsLoadStaging_butStillCallsProcedure() throws Exception {
            RejectedEntry rejected = RejectedEntry.builder()
                .lockboxNumber("123456").siteIdentifier("DAL")
                .postOfficeBox("").reason("Bad PostalCode").build();
            ParseResult emptyResult = new ParseResult(List.of(), List.of(rejected));

            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any()))
                .thenReturn(0);
            when(stagingService.createImportLog(anyString(), any())).thenReturn(8L);
            when(fileParser.parseWithResult(anyString())).thenReturn(emptyResult);

            importService.processFile(validProcessingFile);

            verify(stagingService, never()).loadStaging(anyLong(), any());
            verify(stagingService).logRejected(eq(8L), eq(List.of(rejected)));
            verify(stagingService).callImportProcedure(eq(8L), anyString(), any(),
                eq(1), eq(2), eq(3), eq(1));
        }

        @Test
        void processFile_mixedResult_loadsValidAndLogsRejected() throws Exception {
            LockboxRow    validRow  = mockRow("000001");
            RejectedEntry rejected  = RejectedEntry.builder()
                .lockboxNumber("000002").siteIdentifier("ATL")
                .postOfficeBox("").reason("Schema violation").build();
            ParseResult mixed = new ParseResult(List.of(validRow), List.of(rejected));

            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any()))
                .thenReturn(0);
            when(stagingService.createImportLog(anyString(), any())).thenReturn(9L);
            when(fileParser.parseWithResult(anyString())).thenReturn(mixed);

            importService.processFile(validProcessingFile);

            verify(stagingService).loadStaging(eq(9L), eq(List.of(validRow)));
            verify(stagingService).logRejected(eq(9L), eq(List.of(rejected)));
            verify(stagingService).callImportProcedure(eq(9L), anyString(), any(),
                eq(1), eq(2), eq(3), eq(1));
        }

        @Test
        void processFile_plainJsonName_noProcessingSuffix_alsoWorks() throws Exception {
            // File watcher always renames to .processing, but processFile()
            // also works if passed a plain .json file (e.g. in tests / manual runs).
            Path plain = tempDir.resolve("DIGLBX_Aspec_20260416T120000.json");
            Files.writeString(plain, "{}");

            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any()))
                .thenReturn(0);
            when(stagingService.createImportLog(anyString(), any())).thenReturn(10L);
            when(fileParser.parseWithResult(anyString()))
                .thenReturn(new ParseResult(List.of(), List.of()));

            importService.processFile(plain.toFile());

            verify(stagingService).createImportLog(
                eq("DIGLBX_Aspec_20260416T120000.json"), any());
        }
    }

    // ================================================================
    // EF-101 : Invalid filename format
    // ================================================================

    @Nested
    class EF101_InvalidFileName {

        @Test
        void wrongPrefix_throwsEF101() throws IOException {
            // The file exists but its name doesn't match the required pattern
            Path badFile = tempDir.resolve("LOCKBOX_Aspec_20260416T120000.json");
            Files.writeString(badFile, "{}");

            assertThatThrownBy(() -> importService.processFile(badFile.toFile()))
                .isInstanceOf(LockboxValidationException.class)
                .hasMessageContaining("EF-101");
        }

        @Test
        void missingTimestamp_throwsEF101() throws IOException {
            Path badFile = tempDir.resolve("DIGLBX_Aspec_.json");
            Files.writeString(badFile, "{}");

            assertThatThrownBy(() -> importService.processFile(badFile.toFile()))
                .isInstanceOf(LockboxValidationException.class)
                .hasMessageContaining("EF-101");
        }

        @Test
        void wrongExtension_throwsEF101() throws IOException {
            Path badFile = tempDir.resolve("DIGLBX_Aspec_20260416T120000.txt");
            Files.writeString(badFile, "{}");

            assertThatThrownBy(() -> importService.processFile(badFile.toFile()))
                .isInstanceOf(LockboxValidationException.class)
                .hasMessageContaining("EF-101");
        }

        @Test
        void processingFileBadPrefix_throwsEF101() throws IOException {
            // Even with .processing suffix, the derived original name must be valid
            Path badFile = tempDir.resolve("BAD_FILE_20260416T120000.json.processing");
            Files.writeString(badFile, "{}");

            assertThatThrownBy(() -> importService.processFile(badFile.toFile()))
                .isInstanceOf(LockboxValidationException.class)
                .hasMessageContaining("EF-101");
        }

        @Test
        void correctFilename_passes() {
            assertThatCode(() ->
                importService.validateFileName("DIGLBX_Aspec_20260416T120000.json"))
                .doesNotThrowAnyException();
        }

        @Test
        void correctFilename_caseInsensitive_passes() {
            assertThatCode(() ->
                importService.validateFileName("diglbx_aspec_20260416T120000.json"))
                .doesNotThrowAnyException();
        }
    }

    // ================================================================
    // EV-216 : Duplicate transmission
    // ================================================================

    @Nested
    class EV216_DuplicateTransmission {

        @Test
        void alreadyImportedDate_throwsEV216() {
            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any()))
                .thenReturn(1);   // duplicate found

            assertThatThrownBy(() -> importService.processFile(validProcessingFile))
                .isInstanceOf(LockboxValidationException.class)
                .hasMessageContaining("EV-216")
                .hasMessageContaining("already been successfully imported");
        }

        @Test
        void nullCountFromDb_doesNotThrow() {
            // JdbcTemplate mock returns null by default – treated as 0 (no duplicate)
            assertThatCode(() ->
                importService.validateNotDuplicate(LocalDate.of(2026, 4, 16), "file.json"))
                .doesNotThrowAnyException();
        }

        @Test
        void nullFileDate_skipsCheck() {
            assertThatCode(() ->
                importService.validateNotDuplicate(null, "file.json"))
                .doesNotThrowAnyException();
            verifyNoInteractions(jdbcTemplate);
        }
    }

    // ================================================================
    // extractFileDate
    // ================================================================

    @Nested
    class ExtractFileDate {

        @Test
        void validFileName_returnsCorrectDate() {
            assertThat(importService.extractFileDate("DIGLBX_Aspec_20260416T120000.json"))
                .isEqualTo(LocalDate.of(2026, 4, 16));
        }

        @Test
        void endOfYear_returnsCorrectDate() {
            assertThat(importService.extractFileDate("DIGLBX_Aspec_20251231T000000.json"))
                .isEqualTo(LocalDate.of(2025, 12, 31));
        }

        @Test
        void unknownFormat_returnsNull() {
            assertThat(importService.extractFileDate("unknown_file.json")).isNull();
        }

        @Test
        void malformedDate_returnsNull() {
            assertThat(importService.extractFileDate("DIGLBX_Aspec_99999999T000000.json"))
                .isNull();
        }
    }

    // ================================================================
    // Error propagation
    // ================================================================

    @Nested
    class ErrorPropagation {

        @Test
        void fileNotFound_throwsIllegalArgument() {
            // File is not created – does not exist on disk
            File nonExistent = tempDir.resolve("DIGLBX_Aspec_20260417T000000.json").toFile();

            assertThatThrownBy(() -> importService.processFile(nonExistent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
        }

        @Test
        void parserThrowsIOException_wrapsAsRuntimeException() throws Exception {
            // createImportLog is inside persistResult() which is called AFTER parsing,
            // so it is never reached when parseWithResult() throws – no stub needed
            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any()))
                .thenReturn(0);
            when(fileParser.parseWithResult(anyString()))
                .thenThrow(new IOException("disk error"));

            assertThatThrownBy(() -> importService.processFile(validProcessingFile))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to read lockbox file")
                .cause().isInstanceOf(IOException.class)
                .hasMessage("disk error");
        }

        @Test
        void parserThrowsValidationException_propagatesDirectly() throws Exception {
            // createImportLog is inside persistResult() which is called AFTER parsing,
            // so it is never reached when parseWithResult() throws – no stub needed
            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any()))
                .thenReturn(0);
            when(fileParser.parseWithResult(anyString()))
                .thenThrow(new LockboxValidationException(ErrorCode.EV_215, "count mismatch"));

            assertThatThrownBy(() -> importService.processFile(validProcessingFile))
                .isInstanceOf(LockboxValidationException.class)
                .hasMessageContaining("EV-215");
        }
    }

    // ================================================================
    // EF-102: No matching file spec in ibox_file_spec
    // ================================================================

    @Nested
    class EF102_UnknownFileSpec {

        @Test
        void noMatchingFileSpec_throwsEF102() {
            when(fileSpecLookupService.resolve(anyString()))
                .thenThrow(new LockboxValidationException(ErrorCode.EF_102,
                    "No active file specification found"));
            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any()))
                .thenReturn(0);

            assertThatThrownBy(() -> importService.processFile(validProcessingFile))
                .isInstanceOf(LockboxValidationException.class)
                .hasMessageContaining("EF-102");
        }
    }

    // ================================================================
    // Helpers
    // ================================================================

    private FileSpecInfo mockFileSpecInfo() {
        return FileSpecInfo.builder()
            .fileSpecId   (99L)
            .providerId   (1)
            .clientId     (10)
            .lobId        (2)
            .applicationId(3)
            .build();
    }

    private LockboxRow mockRow(String lockboxNumber) {
        return LockboxRow.builder()
            .lockboxNumber      (lockboxNumber)
            .siteIdentifier     ("DAL")
            .lockboxName        ("Test Lockbox")
            .lockboxStatus      ("Active")
            .digitalIndicator   (true)
            .postalCode         ("75201")
            .postOfficeBox      ("")
            .addressType        ("Lockbox")
            .addressCompanyName ("Test Co")
            .addressStreet1     ("1 Main St")
            .addressCity        ("Dallas")
            .addressState       ("TX")
            .addressPostalCode  ("75201")
            .addressCountry     ("US")
            .rowHash            ("abc123")
            .build();
    }
}

package com.bofa.ibox.lockbox.service;

import com.bofa.ibox.lockbox.config.LockboxImportProperties;
import com.bofa.ibox.lockbox.model.LockboxRow;
import com.bofa.ibox.lockbox.model.RejectedEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LockboxStagingServiceTest {

    @Mock JdbcTemplate            jdbcTemplate;
    @Mock LockboxImportProperties props;

    LockboxStagingService service;

    @BeforeEach
    void setUp() {
        when(props.getDbSchema()).thenReturn("ibox_uat");
        when(props.getBatchSize()).thenReturn(1000);
        service = new LockboxStagingService(jdbcTemplate, props);
        service.initSql();   // @PostConstruct is not called by plain new – trigger manually
    }

    // ================================================================
    // loadStaging
    // ================================================================

    @Nested
    class LoadStaging {

        @Test
        void loadsRowsIntoStagingInOneBatch() {
            List<LockboxRow> rows = buildRows(3);

            service.loadStaging(99L, rows);

            // 3 rows, batch-size 1000 → exactly 1 batch call
            verify(jdbcTemplate, times(1))
                .batchUpdate(anyString(), anyList(), anyInt(),
                    any(ParameterizedPreparedStatementSetter.class));
        }

        @Test
        void rows2500_executesBatches3Times() {
            List<LockboxRow> rows = buildRows(2500);

            service.loadStaging(99L, rows);

            // 2500 / 1000 = ceil(2.5) = 3 batches
            verify(jdbcTemplate, times(3))
                .batchUpdate(anyString(), anyList(), anyInt(),
                    any(ParameterizedPreparedStatementSetter.class));
        }

        @Test
        void emptyList_doesNotCallBatchUpdate() {
            service.loadStaging(99L, List.of());

            verify(jdbcTemplate, never())
                .batchUpdate(anyString(), anyList(), anyInt(),
                    any(ParameterizedPreparedStatementSetter.class));
        }

        @Test
        void exactlyOneBatchBoundary_executesOneBatch() {
            // 1000 rows at batch-size 1000 = exactly 1 batch
            List<LockboxRow> rows = buildRows(1000);

            service.loadStaging(99L, rows);

            verify(jdbcTemplate, times(1))
                .batchUpdate(anyString(), anyList(), anyInt(),
                    any(ParameterizedPreparedStatementSetter.class));
        }
    }

    // ================================================================
    // callImportProcedure
    // ================================================================

    @Nested
    class CallImportProcedure {

        @BeforeEach
        void setUpProps() {
            // Only modifiedBy remains in props; provider/lob/application passed as params
            when(props.getModifiedBy()).thenReturn("LOCKBOX_IMPORT_JOB");
        }

        @Test
        void passesAllNineParametersInOrder() {
            service.callImportProcedure(
                42L,
                "DIGLBX_Aspec_20260416T120000.json",
                LocalDate.of(2026, 4, 16),
                1,   // providerId   (resolved from ibox_file_spec)
                2,   // lobId
                3,   // applicationId
                5    // rejectedCount
            );

            // CALL import_lockbox_data(logId, fileName, aspecDate,
            //   providerId, lobId, appId, rejectedCount, incomingFileId, modifiedBy)
            verify(jdbcTemplate).update(
                contains("import_lockbox_data"),
                eq(42L),                                        // p_log_id
                eq("DIGLBX_Aspec_20260416T120000.json"),        // p_file_name
                any(),                                          // p_aspec_date (java.sql.Date)
                eq(1),                                          // p_provider_id
                eq(2),                                          // p_lob_id
                eq(3),                                          // p_application_id
                eq(5),                                          // p_rejected_count
                isNull(),                                       // p_incoming_file_id
                eq("LOCKBOX_IMPORT_JOB")                        // p_modified_by
            );
        }

        @Test
        void nullAspecDate_passesNullSafely() {
            assertThatCode(() ->
                service.callImportProcedure(1L, "file.json", null, 1, 2, 3, 0))
                .doesNotThrowAnyException();
        }

        @Test
        void zeroRejectedCount_passesZero() {
            service.callImportProcedure(1L, "file.json", LocalDate.now(), 1, 2, 3, 0);

            verify(jdbcTemplate).update(anyString(),
                anyLong(), anyString(), any(),
                anyInt(), anyInt(), anyInt(),
                eq(0),      // rejectedCount = 0
                isNull(), anyString());
        }
    }

    // ================================================================
    // logRejected
    // ================================================================

    @Nested
    class LogRejected {

        @Test
        void emptyList_doesNothing() {
            service.logRejected(1L, List.of());
            verifyNoInteractions(jdbcTemplate);
        }

        @Test
        void nonEmptyList_insertsBatchForEachEntry() {
            List<RejectedEntry> entries = List.of(
                RejectedEntry.builder()
                    .lockboxNumber("123456").siteIdentifier("DAL")
                    .postOfficeBox("").reason("Duplicate key").build(),
                RejectedEntry.builder()
                    .lockboxNumber("654321").siteIdentifier("ATL")
                    .postOfficeBox("").reason("Schema violation").build()
            );

            service.logRejected(1L, entries);

            verify(jdbcTemplate, times(1))
                .batchUpdate(anyString(), eq(entries), eq(2),
                    any(ParameterizedPreparedStatementSetter.class));
        }
    }

    // ================================================================
    // Helper
    // ================================================================

    private List<LockboxRow> buildRows(int count) {
        List<LockboxRow> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(LockboxRow.builder()
                .lockboxNumber      (String.format("%06d", i))
                .siteIdentifier     ("DAL")
                .lockboxName        ("Test " + i)
                .lockboxStatus      ("Active")
                .digitalIndicator   (true)
                .postalCode         ("75201")
                .postOfficeBox      ("")
                .addressType        ("Lockbox")
                .addressCompanyName ("Company " + i)
                .addressStreet1     (i + " Main St")
                .addressCity        ("Dallas")
                .addressState       ("TX")
                .addressPostalCode  ("75201")
                .addressCountry     ("US")
                .rowHash            ("abc123")
                .build());
        }
        return rows;
    }
}

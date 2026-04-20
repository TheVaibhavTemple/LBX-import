package com.bofa.ibox.lockbox.integration;

import com.bofa.ibox.lockbox.service.LockboxImportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test – spins up a real PostgreSQL container via Testcontainers,
 * applies the DDL and stored procedure, then runs the full import against
 * the sample file DIGLBX_Aspec.json.
 *
 * Files are copied to a @TempDir with properly formatted names so that
 * EF-101 filename validation passes. Two differently-dated copies are used
 * for the "second run" tests so that EV-216 (duplicate transmission) is not
 * triggered while still testing the stored procedure's INSERT/UPDATE/UNCHANGED logic.
 *
 * Requires Docker on the build machine.
 */
@Testcontainers
@SpringBootTest
class LockboxImportIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ibox")
            .withUsername("ibox_user")
            .withPassword("ibox_pass")
            .withInitScript("db/init.sql");   // DDL + stored procedure

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        // Integration tests call processFile() directly; set dirs to tmp so
        // LockboxFileWatcherService.ensureDirectories() can create them without error.
        String tmp = System.getProperty("java.io.tmpdir");
        registry.add("lockbox.import.in-dir",        () -> tmp + "/lockbox-it/in");
        registry.add("lockbox.import.processed-dir", () -> tmp + "/lockbox-it/processed");
        registry.add("lockbox.import.error-dir",     () -> tmp + "/lockbox-it/error");

        // Disable the scheduler – tests drive the import via processFile() directly
        registry.add("lockbox.import.scheduler-enabled", () -> "false");
        // provider-id / lob-id / application-id are resolved from ibox_file_spec at runtime
    }

    @TempDir
    Path tempDir;

    /** First import:  date 2026-04-16 */
    private File file1;

    /** Second import: date 2026-04-17 (same content, different date avoids EV-216) */
    private File file2;

    @Autowired LockboxImportService importService;
    @Autowired JdbcTemplate         jdbcTemplate;

    @BeforeEach
    void setUpFiles() throws Exception {
        URL resource = getClass().getClassLoader().getResource("DIGLBX_Aspec.json");
        Objects.requireNonNull(resource, "Test resource DIGLBX_Aspec.json not found on classpath");
        Path source = Path.of(resource.toURI());

        file1 = tempDir.resolve("DIGLBX_Aspec_20260416T120000.json").toFile();
        file2 = tempDir.resolve("DIGLBX_Aspec_20260417T120000.json").toFile();

        Files.copy(source, file1.toPath());
        Files.copy(source, file2.toPath());
    }

    // ----------------------------------------------------------------
    // INSERT on first run
    // ----------------------------------------------------------------

    @Test
    void firstRun_insertsAllRecords() {
        importService.processFile(file1);

        int count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ibox_uat.ibox_lockbox", Integer.class);
        assertThat(count).isEqualTo(1);   // sample file has 1 lockbox with 1 address

        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT * FROM ibox_uat.ibox_lockbox WHERE lockboxnumber = '123456'");

        assertThat(row.get("lockboxname"))      .isEqualTo("Sears");
        assertThat(row.get("lockboxstatus"))    .isEqualTo("Active");
        assertThat(row.get("digitalindicator")) .isEqualTo(true);
        assertThat(row.get("site_identifier"))  .isEqualTo("DAL");
        assertThat(row.get("addressstreet1"))   .isEqualTo("7492 Chancellor DR");
        assertThat(row.get("addresscity"))      .isEqualTo("Orlando");
    }

    @Test
    void firstRun_logsSuccessEntry() {
        importService.processFile(file1);

        Map<String, Object> log = jdbcTemplate.queryForMap(
            "SELECT * FROM ibox_uat.ibox_lockbox_import_log ORDER BY import_date DESC LIMIT 1");

        assertThat(log.get("status"))          .isEqualTo("SUCCESS");
        assertThat(log.get("inserted_count"))  .isEqualTo(1);
        assertThat(log.get("updated_count"))   .isEqualTo(0);
        assertThat(log.get("unchanged_count")) .isEqualTo(0);
    }

    // ----------------------------------------------------------------
    // UPDATE on second run with changed data
    // ----------------------------------------------------------------

    @Test
    void secondRun_updatesChangedRecord() {
        // First import (date 2026-04-16)
        importService.processFile(file1);

        // Simulate a field change in the DB
        jdbcTemplate.update(
            "UPDATE ibox_uat.ibox_lockbox SET lockboxname = 'Old Name' WHERE lockboxnumber = '123456'");

        // Second import (date 2026-04-17, same content – lockboxname = 'Sears')
        importService.processFile(file2);

        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT * FROM ibox_uat.ibox_lockbox WHERE lockboxnumber = '123456'");
        assertThat(row.get("lockboxname")).isEqualTo("Sears");  // restored from file

        Map<String, Object> log = jdbcTemplate.queryForMap(
            "SELECT * FROM ibox_uat.ibox_lockbox_import_log ORDER BY import_date DESC LIMIT 1");
        assertThat(log.get("updated_count"))  .isEqualTo(1);
        assertThat(log.get("inserted_count")) .isEqualTo(0);
    }

    // ----------------------------------------------------------------
    // No change – unchanged_count incremented
    // ----------------------------------------------------------------

    @Test
    void secondRun_noChanges_incrementsUnchangedCount() {
        importService.processFile(file1);   // date 2026-04-16 → INSERT
        importService.processFile(file2);   // date 2026-04-17 → UNCHANGED (same data)

        Map<String, Object> log = jdbcTemplate.queryForMap(
            "SELECT * FROM ibox_uat.ibox_lockbox_import_log ORDER BY import_date DESC LIMIT 1");

        assertThat(log.get("unchanged_count")).isEqualTo(1);
        assertThat(log.get("updated_count"))  .isEqualTo(0);
        assertThat(log.get("inserted_count")) .isEqualTo(0);
    }

    // ----------------------------------------------------------------
    // Detail log – INSERT
    // ----------------------------------------------------------------

    @Test
    void firstRun_writesInsertDetailRecord() {
        importService.processFile(file1);

        Map<String, Object> detail = jdbcTemplate.queryForMap(
            "SELECT * FROM ibox_uat.ibox_lockbox_import_detail " +
            "WHERE lockboxnumber = '123456' AND operation = 'INSERT'");

        assertThat(detail.get("operation"))      .isEqualTo("INSERT");
        assertThat(detail.get("site_identifier")).isEqualTo("DAL");
        assertThat(detail.get("changed_fields")) .isNull();   // no diff for new records
    }

    // ----------------------------------------------------------------
    // Detail log – UPDATE with changed fields
    // ----------------------------------------------------------------

    @Test
    void secondRun_writesUpdateDetailWithChangedFields() {
        importService.processFile(file1);

        jdbcTemplate.update(
            "UPDATE ibox_uat.ibox_lockbox SET lockboxname = 'Old Name' WHERE lockboxnumber = '123456'");

        importService.processFile(file2);   // restores lockboxname = 'Sears'

        Map<String, Object> detail = jdbcTemplate.queryForMap(
            "SELECT * FROM ibox_uat.ibox_lockbox_import_detail " +
            "WHERE lockboxnumber = '123456' AND operation = 'UPDATE'");

        assertThat(detail.get("changed_fields").toString())
            .contains("lockboxname")
            .contains("Old Name")
            .contains("Sears");
    }

    @Test
    void secondRun_unchangedRecord_writesNoDetailRow() {
        importService.processFile(file1);
        importService.processFile(file2);   // same content, no update

        int updateCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ibox_uat.ibox_lockbox_import_detail WHERE operation = 'UPDATE'",
            Integer.class);

        assertThat(updateCount).isZero();
    }

    @Test
    void secondRun_multipleFieldsChanged_allCapturedInDetail() {
        importService.processFile(file1);

        jdbcTemplate.update(
            "UPDATE ibox_uat.ibox_lockbox SET lockboxstatus = 'Closed', addresscity = 'Tampa' " +
            "WHERE lockboxnumber = '123456'");

        importService.processFile(file2);

        Map<String, Object> detail = jdbcTemplate.queryForMap(
            "SELECT * FROM ibox_uat.ibox_lockbox_import_detail " +
            "WHERE lockboxnumber = '123456' AND operation = 'UPDATE'");

        String changedFields = detail.get("changed_fields").toString();
        assertThat(changedFields).contains("lockboxstatus");
        assertThat(changedFields).contains("addresscity");
        assertThat(changedFields).contains("Closed");
        assertThat(changedFields).contains("Tampa");
    }

    // ----------------------------------------------------------------
    // GCI lookup
    // ----------------------------------------------------------------

    @Test
    void firstRun_insertsGciRecord() {
        importService.processFile(file1);

        int gciCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ibox_uat.ibox_global_client_identifier " +
            "WHERE familygci='111111' AND primarygci='222222'",
            Integer.class);

        assertThat(gciCount).isEqualTo(1);
    }
}

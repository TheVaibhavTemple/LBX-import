# Lockbox Import — Session 2 Change Log

**Date:** 2026-04-17  
**Branch baseline:** post Session 1 (SHA-256 hash, configurable schema, security hardening)

---

## 1. Bug Fixes

### 1.1 `InvalidDataAccessApiUsageException` — `GeneratedKeyHolder.getKey()`

**File:** `LockboxStagingService.java` → `createImportLog()`

**Problem:** PostgreSQL returns every column when `Statement.RETURN_GENERATED_KEYS` is used.
`GeneratedKeyHolder.getKey()` throws if more than one column is returned.

**Fix:** Pass the column name explicitly so only one key is returned:
```java
// Before
con.prepareStatement(createLogSql, Statement.RETURN_GENERATED_KEYS)

// After
con.prepareStatement(createLogSql, new String[]{"import_log_id"})
```

---

## 2. Feature: File Watcher Scheduler + File Locking

Replaced the one-shot `CommandLineRunner` with a continuous scheduler that polls a directory.

### 2.1 New files

| File | Purpose |
|---|---|
| `LockboxFileWatcherService.java` | Polls `in-dir` every N ms; renames file to `.processing` (atomic lock); routes to `processed/` or `error/` |
| `config/SchedulerConfig.java` | `@Configuration @EnableScheduling` — kept separate so tests can exclude it |
| `config/JacksonConfig.java` | `Jackson2ObjectMapperBuilderCustomizer` — JavaTimeModule, ISO dates, NON_NULL, lenient unknown properties |

### 2.2 Modified files

| File | Change |
|---|---|
| `LockboxImportApplication.java` | Removed `CommandLineRunner` + `System.exit()` — app now runs as a daemon |
| `LockboxImportService.java` | `run()` renamed to `processFile(File)` — accepts `.processing` file, strips suffix internally |
| `LockboxImportProperties.java` | Replaced `filePath` with `inDir`, `processedDir`, `errorDir`, `schedulerEnabled` |
| `application.yml` | Added directory and scheduler config block; removed `file-path` |
| `pom.xml` | Added explicit `jackson-databind` + `jackson-datatype-jsr310` dependencies |
| `LockboxRow.java` | `@Builder` → `@Builder(toBuilder = true)` — required for `.toBuilder()` calls in parser |

### 2.3 File lifecycle at runtime
```
in/DIGLBX_Aspec_20260416T120000.json
  │
  ├── rename → in/DIGLBX_Aspec_20260416T120000.json.processing   (atomic OS lock)
  │
  ├── processFile() — EF-101 → EV-216 → EF-102 → parse → persist
  │
  ├── SUCCESS → processed/DIGLBX_Aspec_20260416T120000.json
  └── FAILURE → error/DIGLBX_Aspec_20260416T120000.json
```

### 2.4 Scheduler configuration
```yaml
lockbox:
  import:
    in-dir:                     ${LOCKBOX_IN_DIR:/data/lockbox/in}
    processed-dir:              ${LOCKBOX_PROCESSED_DIR:/data/lockbox/processed}
    error-dir:                  ${LOCKBOX_ERROR_DIR:/data/lockbox/error}
    scheduler-enabled:          ${LOCKBOX_SCHEDULER_ENABLED:true}
    scheduler-interval-ms:      ${LOCKBOX_SCHEDULER_INTERVAL_MS:10000}     # 10 sec
    scheduler-initial-delay-ms: ${LOCKBOX_SCHEDULER_INITIAL_DELAY_MS:5000} # 5 sec warm-up
```

> Set `LOCKBOX_SCHEDULER_ENABLED=false` to pause polling without restarting the application.

---

## 3. Feature: File Spec Lookup (Dynamic Provider / Application Resolution)

`provider_id`, `lob_id`, and `application_id` were previously hardcoded in `application.yml`.
They are now resolved at runtime by querying `ibox_file_spec`.

### 3.1 New files

| File | Purpose |
|---|---|
| `model/FileSpecInfo.java` | Immutable record — `fileSpecId`, `providerId`, `clientId`, `applicationId`, `lobId` |
| `service/FileSpecLookupService.java` | Matches filename against `ibox_file_spec.file_name_pattern` via PostgreSQL regex `~*`; joins to resolve application |

### 3.2 Join chain (SQL)
```
ibox_file_spec   (WHERE ? ~* file_name_pattern AND is_active = true)
  └── ibox_provider    (via file_spec.provider_id,  is_active = true)
        └── ibox_client     (via provider.client_id)
              └── ibox_application (via client.lob_id = application.lob_id, is_active = true)
```

> **Note:** The join assumes `ibox_client` has a `lob_id` column. Adjust `FileSpecLookupService.initSql()` if your schema differs.

### 3.3 New error code

| Code | Meaning | Trigger |
|---|---|---|
| `EF-102` | Unknown file specification | No active `ibox_file_spec` row matches the filename |

### 3.4 Removed from configuration

```yaml
# Removed — now resolved from DB
lockbox.import.provider-id
lockbox.import.lob-id
lockbox.import.application-id
```

### 3.5 Updated method signature
```java
// LockboxStagingService — before
callImportProcedure(long importLogId, String fileName, LocalDate aspecDate, int rejectedCount)

// After — IDs come from FileSpecInfo, not config
callImportProcedure(long importLogId, String fileName, LocalDate aspecDate,
                    int providerId, int lobId, int applicationId, int rejectedCount)
```

---

## 4. Refactor: Hardcoded Values → `LockboxConstants`

New utility class `LockboxConstants.java` centralises every magic value.

| Constant | Value | Was duplicated in |
|---|---|---|
| `PROCESSING_SUFFIX` | `".processing"` | `LockboxFileWatcherService` + `LockboxImportService` |
| `FILE_NAME_PATTERN` | `^DIGLBX_Aspec_\d{8}T\d{6}\.json$` | both watcher and import service |
| `FILE_DATE_PATTERN` | `DIGLBX_Aspec_(\d{8}T\d{6})\.json` | `LockboxImportService` |
| `STATUS_IN_PROGRESS` | `"IN_PROGRESS"` | SQL strings in `LockboxStagingService` |
| `STATUS_SUCCESS` | `"SUCCESS"` | SQL string in `LockboxImportService` |
| `STATUS_REJECTED` | `"REJECTED"` | SQL string in `LockboxStagingService` |
| `TABLE_IMPORT_LOG` | `"ibox_lockbox_import_log"` | `LockboxStagingService` + `LockboxImportService` |
| `TABLE_STAGING` | `"ibox_lockbox_staging"` | `LockboxStagingService` |
| `TABLE_IMPORT_DETAIL` | `"ibox_lockbox_import_detail"` | `LockboxStagingService` |
| `PROC_IMPORT_DATA` | `"import_lockbox_data"` | `LockboxStagingService` |
| `DEFAULT_COUNTRY` | `"US"` | `LockboxAddress` + `LockboxFileParser` |
| `EMPTY_POST_OFFICE_BOX` | `""` | 5 places in `LockboxFileParser` + `LockboxStagingService` |

---

## 5. Validation Fixes (found during review)

| File | Issue | Fix |
|---|---|---|
| `LockboxImportProperties.java` | Unused `import NotNull` | Removed |
| `LockboxImportProperties.java` | Stale Javadoc — referenced `LOCKBOX_IMPORT_FILE_PATH`, `LOCKBOX_PROVIDER_ID` | Updated to current env vars |
| `LockboxStagingService.java` line 114 | Hardcoded `""` missed in constants refactor | Changed to `LockboxConstants.EMPTY_POST_OFFICE_BOX` |
| `LockboxImportServiceTest.java` | Redundant same-package import of `FileSpecLookupService` | Removed |

---

## 6. Test Changes

### `LockboxImportServiceTest`
- `setUp()` creates `.processing` file instead of plain `.json`
- Added `@Mock FileSpecLookupService` with default stub returning `provider=1, lob=2, app=3`
- Removed `when(props.getFilePath())` stubs (field removed)
- All `importService.run()` → `importService.processFile(validProcessingFile)`
- Updated `verify(stagingService.callImportProcedure(...))` to 7-arg signature
- Added `EF102_UnknownFileSpec` nested test class

### `LockboxStagingServiceTest`
- Removed `when(props.getProviderId/getLobId/getApplicationId)` stubs
- All 3 `callImportProcedure` tests updated to pass IDs as explicit parameters

### `LockboxImportIntegrationTest`
- Removed `provider-id`, `lob-id`, `application-id` from `@DynamicPropertySource`
- Added `scheduler-enabled: false` to prevent watcher from running during tests
- `importService.run()` → `importService.processFile(file1/file2)`
- Uses `@TempDir` to create two properly-named temp files (`20260416` + `20260417`) to satisfy EF-101 and avoid EV-216 on second-run tests

---

## 7. Complete File Inventory

### New files
```
src/main/java/com/bofa/ibox/lockbox/LockboxConstants.java
src/main/java/com/bofa/ibox/lockbox/model/FileSpecInfo.java
src/main/java/com/bofa/ibox/lockbox/service/FileSpecLookupService.java
src/main/java/com/bofa/ibox/lockbox/service/LockboxFileWatcherService.java
src/main/java/com/bofa/ibox/lockbox/config/JacksonConfig.java
src/main/java/com/bofa/ibox/lockbox/config/SchedulerConfig.java
```

### Modified files
```
src/main/java/com/bofa/ibox/lockbox/LockboxImportApplication.java
src/main/java/com/bofa/ibox/lockbox/config/LockboxImportProperties.java
src/main/java/com/bofa/ibox/lockbox/model/LockboxAddress.java
src/main/java/com/bofa/ibox/lockbox/model/LockboxRow.java
src/main/java/com/bofa/ibox/lockbox/model/ErrorCode.java
src/main/java/com/bofa/ibox/lockbox/service/LockboxImportService.java
src/main/java/com/bofa/ibox/lockbox/service/LockboxStagingService.java
src/main/java/com/bofa/ibox/lockbox/service/LockboxFileParser.java
src/main/resources/application.yml
pom.xml
src/test/java/com/bofa/ibox/lockbox/service/LockboxImportServiceTest.java
src/test/java/com/bofa/ibox/lockbox/service/LockboxStagingServiceTest.java
src/test/java/com/bofa/ibox/lockbox/integration/LockboxImportIntegrationTest.java
```

---

## 8. Key Environment Variables (Full Reference)

| Variable | Default | Purpose |
|---|---|---|
| `DB_HOST` | `localhost` | PostgreSQL host |
| `DB_PORT` | `5432` | PostgreSQL port |
| `DB_NAME` | `ibox` | Database name |
| `DB_USER` | `ibox_user` | Database username |
| `DB_PASSWORD` | *(required)* | Database password — no default, fails fast |
| `LOCKBOX_DB_SCHEMA` | `ibox_uat` | Schema owning all lockbox tables + procedure |
| `LOCKBOX_IN_DIR` | `/data/lockbox/in` | Directory to watch for incoming files |
| `LOCKBOX_PROCESSED_DIR` | `/data/lockbox/processed` | Destination for successfully processed files |
| `LOCKBOX_ERROR_DIR` | `/data/lockbox/error` | Destination for failed files |
| `LOCKBOX_SCHEDULER_ENABLED` | `true` | Set `false` to pause polling without restart |
| `LOCKBOX_SCHEDULER_INTERVAL_MS` | `10000` | Polling interval in milliseconds |
| `LOCKBOX_SCHEDULER_INITIAL_DELAY_MS` | `5000` | Warm-up delay before first scan |

---

## 9. DB Setup Required

Before running, ensure `ibox_file_spec` has a row matching the incoming filename:

```sql
-- Example: register DIGLBX provider file spec
INSERT INTO ibox.ibox_file_spec
    (provider_id, client_id, file_name_pattern, file_type, transfer_type, is_active)
VALUES
    (1, 10, '^DIGLBX_Aspec_\d{8}T\d{6}\.json$', 'JSON', 'FILE', true);
```

The `file_name_pattern` column is matched using PostgreSQL's `~*` (case-insensitive POSIX regex).

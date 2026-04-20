# Digital Lockbox Import – Developer Guide

> **Purpose:** Complete technical reference for the Digital Lockbox driver-file import job.  
> Covers architecture, validation rules, error codes, database design, configuration,
> known data-quality issues in the provider file, and troubleshooting runbooks.

---

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [Repository Layout](#2-repository-layout)
3. [Architecture & Data Flow](#3-architecture--data-flow)
4. [Validation Rules & Error Codes](#4-validation-rules--error-codes)
5. [Two-Mode Parsing Strategy](#5-two-mode-parsing-strategy)
6. [Database Design](#6-database-design)
7. [Stored Procedure](#7-stored-procedure-import_lockbox_data)
8. [SHA-256 Hash / Change Detection](#8-sha-256-hash--change-detection)
9. [Staging Purge Service](#9-staging-purge-service)
10. [Configuration Reference](#10-configuration-reference)
11. [JSON Schema Validation](#11-json-schema-validation)
12. [Known Data-Quality Issues in DLBX_Aspec.json](#12-known-data-quality-issues-in-dlbx_aspecjson)
13. [Unit Test Coverage](#13-unit-test-coverage)
14. [Troubleshooting Runbook](#14-troubleshooting-runbook)
15. [Design Decisions & Trade-offs](#15-design-decisions--trade-offs)

---

## 1. Project Overview

The job imports the daily **Digital Lockbox Aspec driver file** (`DIGLBX_Aspec_YYYYMMDDThhmmss.json`)
provided by the digital-lockbox vendor into the `ibox_uat` PostgreSQL schema.

**What it does:**

- Validates the file at both file-level (reject entire file) and record-level (mark bad records as REJECTED)
- Loads valid rows into a staging table tagged with a unique `import_log_id`
- Calls a stored procedure that upserts the staging rows into `ibox_lockbox` using SHA-256 hash comparison
- Writes a full audit trail: every INSERT, UPDATE, and REJECTED record is logged in `ibox_lockbox_import_detail`

**Stack:** Spring Boot 4.0.5 · Java 21 · PostgreSQL · JUnit 5 · Mockito · AssertJ

---

## 2. Repository Layout

```
lockbox import/
├── sql/
│   ├── 01_schema.sql                   # DDL: all tables + indexes
│   └── 02_import_procedure.sql         # Stored procedure: import_lockbox_data
│
├── DIGLBX_ASPEC.schema.json            # JSON Schema (Draft-7) for the driver file
├── DLBX_Aspec.json                     # Sample provider file (see §12 for known issues)
│
└── springboot/
    └── src/
        ├── main/
        │   ├── java/com/bofa/ibox/lockbox/
        │   │   ├── config/
        │   │   │   └── LockboxImportProperties.java   # @ConfigurationProperties
        │   │   ├── exception/
        │   │   │   └── LockboxValidationException.java
        │   │   ├── model/
        │   │   │   ├── ErrorCode.java                 # Error code enum (EF/EV/ET)
        │   │   │   ├── LockboxFileRoot.java            # JSON top-level model
        │   │   │   ├── LockboxEntry.java               # JSON Lockboxes[N] model
        │   │   │   ├── LockboxAddress.java             # JSON AddressList[N] model
        │   │   │   ├── LockboxGCI.java                 # GlobalClientIdentifier model
        │   │   │   ├── LockboxSummaryInfo.java         # JSON SummaryInfo model
        │   │   │   ├── LockboxRow.java                 # Flattened DB row (one per address)
        │   │   │   ├── ParseResult.java                # validRows + rejectedEntries
        │   │   │   └── RejectedEntry.java              # lockboxNumber, siteId, reason
        │   │   ├── service/
        │   │   │   ├── LockboxFileParser.java          # JSON parse + all validation
        │   │   │   ├── LockboxImportService.java       # Orchestration (run())
        │   │   │   ├── LockboxStagingService.java      # DB operations
        │   │   │   └── LockboxStagingPurgeService.java # Separate 30-day purge
        │   │   └── util/
        │   │       └── HashUtil.java                   # SHA-256 row hash computation
        │   └── resources/
        │       ├── application.yml
        │       └── DIGLBX_ASPEC.schema.json            # classpath copy of schema
        │
        └── test/
            ├── java/com/bofa/ibox/lockbox/service/
            │   ├── LockboxFileParserTest.java          # Unit tests – synthetic fixtures
            │   ├── LockboxFileParserDlbxTest.java      # Unit tests – real DLBX file
            │   ├── LockboxImportServiceTest.java       # Unit tests – Mockito
            │   └── LockboxStagingServiceTest.java      # Unit tests – Mockito
            └── resources/
                ├── DLBX_Aspec.json                     # Test copy of provider sample
                ├── DIGLBX_ASPEC.schema.json            # Test copy of schema
                └── db/
                    └── init.sql                        # Testcontainers DB bootstrap
```

---

## 3. Architecture & Data Flow

```
DIGLBX_Aspec_YYYYMMDDThhmmss.json
         │
         ▼
LockboxImportService.run()
  │
  ├─ [1] EF-101  validateFileName()          → throws if filename does not match pattern
  ├─ [2] EV-216  validateNotDuplicate()      → throws if aspec_date already SUCCESS in DB
  ├─ [3]         createImportLog()           → INSERT into ibox_lockbox_import_log (IN_PROGRESS)
  │                                             returns import_log_id
  ├─ [4]         fileParser.parseWithResult()
  │                │
  │                ├─ parseToNode()           → EV-200 if malformed JSON
  │                ├─ validateSchemaAndCollect()
  │                │    ├─ top-level schema failures → throw EV-200 (file rejected)
  │                │    └─ Lockboxes[N] failures    → mark record REJECTED
  │                ├─ deserializeNode()        → EV-200 if Jackson mapping fails
  │                ├─ validateSummaryCount()   → EV-215 (file-level, throws)
  │                ├─ validateAspecDate()      → EF-106 (file-level, throws)
  │                ├─ validateLockboxCount()   → EF-108 (file-level, throws)
  │                ├─ validateBeansPerRecord() → mark record REJECTED (@NotBlank/@Pattern)
  │                ├─ validateAddressPostalCodesPerRecord() → mark record REJECTED (EV-202)
  │                ├─ warnClosedAndNonDigital() → log WARN (ET-300 / ET-301)
  │                └─ flattenWithDuplicateCheck() → mark duplicate keys REJECTED
  │                                              → returns ParseResult
  │
  ├─ [5] loadStaging(importLogId, validRows)
  │         → bulk INSERT into ibox_lockbox_staging (JDBC batch, 1000 rows/batch)
  │         → skipped entirely if validRows is empty
  │
  ├─ [6] logRejected(importLogId, rejectedEntries)
  │         → batch INSERT into ibox_lockbox_import_detail (operation='REJECTED')
  │
  └─ [7] callImportProcedure(importLogId, fileName, aspecDate, rejectedCount)
            → CALL ibox_uat.import_lockbox_data(9 params)
            → procedure upserts staging → ibox_lockbox (hash-based change detection)
            → marks import_log status = SUCCESS (or FAILED on exception)
```

### Key design point — `import_log_id` tagging

Every staging row carries `import_log_id`. The stored procedure filters **only its own rows**
(`WHERE import_log_id = p_log_id`). This means:

- Multiple runs can coexist in staging simultaneously without interference.
- Re-running a failed import (new `import_log_id`) will not conflict with prior rows.
- Old staging rows are cleaned up independently by `LockboxStagingPurgeService`.

---

## 4. Validation Rules & Error Codes

### File-level rules — entire file is rejected on failure

| Code   | Where enforced              | Condition |
|--------|-----------------------------|-----------|
| EF-101 | `LockboxImportService`      | Filename does not match `DIGLBX_Aspec_\d{8}T\d{6}\.json` (case-insensitive) |
| EF-106 | `LockboxFileParser`         | `SummaryInfo.ASPECDate` is missing, has invalid format, or is older than `maxFileAgeDays` (default 2) |
| EF-108 | `LockboxFileParser`         | Number of lockboxes in file exceeds `maxLockboxCount` (default 50,000) |
| EV-200 | `LockboxFileParser`         | Malformed JSON · missing top-level required fields · JSON mapping error |
| EV-215 | `LockboxFileParser`         | `SummaryInfo.LockboxCount` ≠ actual number of entries in `Lockboxes[]` |
| EV-216 | `LockboxImportService`      | A file with the same `aspec_date` already has status=`SUCCESS` in the import log |

### Record-level rules — bad record is REJECTED, rest of file continues

| Code   | Where enforced              | Condition |
|--------|-----------------------------|-----------|
| EV-200 | Schema / bean validation    | `Lockboxes[N]` entry fails JSON Schema (wrong type, enum, pattern, required field missing) |
| EV-200 | Bean validation             | `@NotBlank` / `@Pattern` / `@Size` violation on a `LockboxEntry` or `LockboxAddress` field |
| EV-202 | `LockboxFileParser`         | `AddressPostalCode` (first 5 digits) ≠ `LockboxPostalCode` (first 5 digits) |
| —      | `LockboxFileParser`         | Duplicate `(site_identifier, lockboxnumber, postofficebox)` key within same file |

### Warnings — logged only, no rejection

| Code   | Condition |
|--------|-----------|
| ET-300 | `LockboxStatus` = `"Closed"` |
| ET-301 | `DigitalIndicator` = `false` |

### ASPECDate format handling

The provider sends `ASPECDate` as either:
- Plain date: `"2026-04-16"` ← original spec format
- Datetime: `"2026-04-16T17:51:28"` ← what the provider actually sends

The parser strips the `T...` time component before parsing. Both formats are accepted.
If the date portion is older than `maxFileAgeDays`, EF-106 is thrown.

---

## 5. Two-Mode Parsing Strategy

`LockboxFileParser` exposes two public methods to support different use cases:

### `parse(filePath)` — strict / fail-fast
- Used by **unit tests** to verify individual error codes cleanly.
- Any violation (file-level or record-level) throws `LockboxValidationException` immediately.
- Returns `List<LockboxRow>` on success.

### `parseWithResult(filePath)` — lenient / per-record
- Used by **`LockboxImportService`** in production.
- File-level violations still throw immediately (EV-200 malformed JSON, EV-215, EF-106, EF-108).
- Record-level violations mark the individual record as `REJECTED` and continue processing.
- Returns `ParseResult` containing `validRows` + `rejectedEntries`.

### How schema violations are split between file-level and record-level

The `networknt` JSON Schema validator returns `ValidationMessage` objects whose
`getMessage()` string contains the JSON path of the violation, e.g.:

```
$.Lockboxes[5].PostalCode: integer found, string expected
$.SummaryInfo: required property 'ASPECDate' not found
```

The regex `\$\.Lockboxes\[(\d+)\]` is applied to each message:
- Match → extract index N → record-level → add to rejected list
- No match → file-level → throw EV-200 immediately

---

## 6. Database Design

### `ibox_lockbox_staging`

Temporary landing area. Rows are tagged with `import_log_id` and **never truncated** after import.
Cleaned up by `LockboxStagingPurgeService` after 30 days.

| Column | Notes |
|--------|-------|
| `import_log_id` | FK to `ibox_lockbox_import_log`; all procedure queries filter by this |
| `staged_at` | Set to `now()` on insert; used for purge cutoff |
| `row_hash` | SHA-256 of 14 data fields; computed in Java before insert |

**Indexes:**
```sql
idx_lockbox_staging_log_id       ON (import_log_id)
idx_lockbox_staging_staged_at    ON (staged_at)
idx_lockbox_staging_import_key   ON (import_log_id, site_identifier, lockboxnumber, postofficebox)
```

### `ibox_lockbox_import_log`

One row per file run. Status lifecycle: `IN_PROGRESS` → `SUCCESS` | `FAILED`.

| Column | Notes |
|--------|-------|
| `import_log_id` | Identity PK; generated in Java via `KeyHolder` |
| `aspec_date` | Extracted from filename (not from JSON — done before parsing) |
| `total_records` | Set by procedure; count of staging rows for this run |
| `rejected_count` | Set by procedure from `p_rejected_count` (Java-computed) |
| `status` | `IN_PROGRESS` on create; `SUCCESS`/`FAILED` after procedure |
| `error_message` | Populated only on `FAILED` |

### `ibox_lockbox_import_detail`

One row per INSERT / UPDATE / REJECTED record.

| `operation` | `changed_fields` content |
|-------------|--------------------------|
| `INSERT` | `NULL` |
| `UPDATE` | JSONB: `{"fieldname": {"old": "...", "new": "..."}}` (only changed fields) |
| `REJECTED` | JSONB: `{"reason": "..."}` |

### `ibox_lockbox`

Target table. Unique constraint on `(site_identifier, lockboxnumber, postofficebox)`.
The `row_hash` column was added to support hash-based change detection (no column-by-column compare needed in the procedure).

---

## 7. Stored Procedure: `import_lockbox_data`

**Signature:**
```sql
CALL ibox_uat.import_lockbox_data(
    p_log_id            bigint,          -- import_log_id (created by Java)
    p_file_name         varchar,         -- e.g. DIGLBX_Aspec_20260416T120000.json
    p_aspec_date        date,            -- extracted from filename
    p_provider_id       integer,
    p_lob_id            integer,
    p_application_id    integer,
    p_rejected_count    integer DEFAULT 0,
    p_incoming_file_id  bigint  DEFAULT NULL,
    p_modified_by       varchar DEFAULT 'LOCKBOX_IMPORT'
)
```

**Steps inside the procedure:**

1. Count staging rows for this `import_log_id`
2. Upsert `ibox_global_client_identifier` for new GCI combinations
3a. Log `INSERT` detail for rows not in `ibox_lockbox`
3b. Insert new rows into `ibox_lockbox`
4a. Log `UPDATE` detail with old/new values (only where `row_hash IS DISTINCT FROM`)
4b. Update changed rows in `ibox_lockbox`
5. Update import log: `total_records`, `inserted_count`, `updated_count`, `unchanged_count`, `rejected_count`, `status = 'SUCCESS'`

**On exception:** sets `status = 'FAILED'`, `error_message = SQLERRM`, then re-raises.

> **Note:** The procedure does NOT purge staging rows. That is handled separately by
> `LockboxStagingPurgeService`. This decoupling means a purge failure can never affect
> an in-progress import.

---

## 8. SHA-256 Hash / Change Detection

**Class:** `HashUtil.computeRowHash(LockboxRow)`

The hash is computed over **14 data fields** joined with `|`:

```
lockboxName | lockboxStatus | digitalIndicator | postalCode | specificationIdentifier
| addressType | addressCompanyName | addressAttn | addressStreet1 | addressStreet2
| addressCity | addressState | addressPostalCode | addressCountry
```

**Fields deliberately excluded from the hash:**
- `siteIdentifier`, `lockboxNumber`, `postOfficeBox` — these are the record identity key, not data

**Rules:**
- `null` → treated as `""` (empty string) for consistency
- Delimiter `|` prevents hash collision between adjacent fields
- Hash is stored in `ibox_lockbox_staging.row_hash` and `ibox_lockbox.row_hash`
- Procedure uses `l.row_hash IS DISTINCT FROM s.row_hash` (single comparison instead of 14 column comparisons)

---

## 9. Staging Purge Service

**Class:** `LockboxStagingPurgeService.purgeExpiredRows()`

```java
DELETE FROM ibox_uat.ibox_lockbox_staging WHERE staged_at < ?
// cutoff = now() - stagingRetentionDays (default 30)
```

**How to schedule:**  
The service is a plain `@Service`. Wire it into a Spring `@Scheduled` method in a
separate `@Configuration` class, or call it from a CLI runner / external cron job.

```java
@Scheduled(cron = "0 0 2 * * *")   // 2 AM daily
public void scheduledPurge() {
    int deleted = purgeService.purgeExpiredRows();
    log.info("Scheduled purge: {} rows deleted", deleted);
}
```

---

## 10. Configuration Reference

All values are under the `lockbox.import.*` prefix in `application.yml`.
Every value can be overridden via environment variable.

| Property | Env var | Default | Description |
|----------|---------|---------|-------------|
| `file-path` | `LOCKBOX_FILE_PATH` | *(required)* | Full path to the `DIGLBX_Aspec_*.json` file |
| `provider-id` | `LOCKBOX_PROVIDER_ID` | `1` | `provider_id` on `ibox_lockbox` |
| `lob-id` | `LOCKBOX_LOB_ID` | `1` | `lob_id` on `ibox_lockbox` |
| `application-id` | `LOCKBOX_APPLICATION_ID` | `1` | `application_id` on `ibox_lockbox` |
| `modified-by` | — | `LOCKBOX_IMPORT_JOB` | Audit username written to the table |
| `batch-size` | — | `1000` | JDBC batch size for staging inserts |
| `max-lockbox-count` | — | `50000` | EF-108: reject file if count exceeds this |
| `max-file-age-days` | — | `2` | EF-106: reject file if ASPECDate is older than N days |
| `staging-retention-days` | — | `30` | Days before purge service removes staging rows |

**Database connection** (Hikari):

| Property | Default |
|----------|---------|
| `DB_HOST` | `localhost` |
| `DB_PORT` | `5432` |
| `DB_NAME` | `ibox` |
| `DB_USER` | `ibox_user` |
| `DB_PASSWORD` | *(required)* |

---

## 11. JSON Schema Validation

**Schema file:** `DIGLBX_ASPEC.schema.json` (Draft-7)  
**Location:** `src/main/resources/` (classpath) and `src/test/resources/` (test classpath)  
**Library:** `com.networknt:json-schema-validator:1.5.3`

### Key constraints in the schema

| Field | Constraint |
|-------|-----------|
| `SpecificationIdentifier` | required, string |
| `SummaryInfo` | required object with `ASPECDate` (minLength:1) and `LockboxCount` (integer) |
| `Lockboxes` | required array |
| `SiteIdentifier` | enum: `ATL`, `CHI`, `DAL`, `MIA`, `NYC`, `PHX`, `SFO`, ... |
| `LockboxNumber` | string, pattern `^[0-9]{6}$` (exactly 6 digits) |
| `LockboxName` | string, minLength:1 |
| `LockboxStatus` | enum: `Active`, `Closed` |
| `PostalCode` | string, pattern `^[0-9]{5}` (5-digit ZIP) |
| `AddressList` | array, minItems:1 |
| `AddressType` | enum: `Lockbox`, `Mailing`, `Alternate` |

### Defects fixed in `DIGLBX_ASPEC.schema.json` (from original provider version)

| Defect | Fix applied |
|--------|-------------|
| Missing comma after `SpecificationIdentifier` property — file was **invalid JSON** | Added `,` |
| `"SiteIndentifier"` typo in `required` array | Corrected to `"SiteIdentifier"` |
| `"items"` keyword inside `GlobalClientIdentifier` object (wrong keyword) | Changed to `"properties"` |
| `AddressType` enum missing `"Lockbox"` value | Added `"Lockbox"` |
| `ASPECDate` used `"format": "date"` — fails because provider sends datetime strings | Changed to `"minLength": 1` |
| `LockboxCount` used `"type": "number"` — allows floats | Changed to `"type": "integer"` |
| No pattern on `LockboxNumber` | Added `"pattern": "^[0-9]{6}$"` |
| No `minLength` on `LockboxName` | Added `"minLength": 1` |
| No `minItems` on `AddressList` | Added `"minItems": 1` |

---

## 12. Known Data-Quality Issues in DLBX_Aspec.json

The sample provider file `DLBX_Aspec.json` (500 lockboxes declared) has the following
issues. These are **provider data-quality problems**, not code bugs. With `parseWithResult()`
all record-level issues are handled as `REJECTED` entries.

### Issue 1 — Malformed JSON: missing commas (FIXED)

**Original symptom:** `}{` instead of `},{` between array objects at lines 2303, 4603, 6903, 9203.  
**Fix applied:** Replaced all 4 occurrences at the byte level.  
**Impact without fix:** EV-200, entire file rejected immediately.

### Issue 2 — ASPECDate is a datetime string, not a plain date

`"ASPECDate": "2025-12-18T17:51:28"` — the provider sends a datetime.  
**Resolution:** Parser now strips the `T...` time component before parsing.  
The date `2025-12-18` is 119 days old (as of 2026-04-16) → still fails EF-106 aged check
with `maxFileAgeDays=2`. For testing, update ASPECDate to today's date.

### Issue 3 — PostalCode as integer (~427 records)

`"PostalCode": 3` instead of `"PostalCode": "00003"`.  
**Impact:** Fails JSON Schema `type:string` check → record marked REJECTED.  
**Note:** 5-digit integers (e.g. `60661`) are coerced to string by Jackson and pass the
5-digit pattern. Short integers (1–4 digits) fail the `^[0-9]{5}$` pattern.

### Issue 4 — Empty mandatory address fields (~38 records)

`AddressCompanyName`, `AddressStreet1`, or `AddressCity` is `""`.  
**Impact:** Fails `@NotBlank` bean validation → record marked REJECTED.

### Issue 5 — AddressPostalCode mismatch (~463 records)

`AddressPostalCode` first 5 digits ≠ `LockboxPostalCode` first 5 digits.  
**Impact:** EV-202, record marked REJECTED.

### Issue 6 — Duplicate keys within file (~11 records)

Same `(siteIdentifier, lockboxNumber, postOfficeBox)` appears more than once.  
**Impact:** Second (and subsequent) occurrences marked REJECTED with "Duplicate key" reason.

### Issue 7 — Invalid AddressState values (~7 records)

`AddressState` values outside valid 2-character US state codes.  
**Impact:** Fails `@Pattern` or `@Size` bean validation → record marked REJECTED.

### Summary for production files

| Finding | Count | Handling |
|---------|-------|----------|
| Malformed JSON (`}{`) | 4 locations | **Fixed in file** – must fix before any import |
| Aged ASPECDate | 1 (whole file) | **File-level** – update date to today for production file |
| PostalCode as integer | ~427 | Record-level REJECTED |
| Empty mandatory fields | ~38 | Record-level REJECTED |
| EV-202 PostalCode mismatch | ~463 | Record-level REJECTED |
| Duplicate keys | ~11 | Record-level REJECTED |
| Bad AddressState | ~7 | Record-level REJECTED |

---

## 13. Unit Test Coverage

### `LockboxFileParserTest` — synthetic JSON fixtures

| Nested class | What is tested |
|---|---|
| `HappyPath` | Single/multiple address rows, GCI, null country defaults to US |
| `EV200_JsonSchemaValidation` | Malformed JSON, PostalCode integer, missing SummaryInfo, missing Lockboxes |
| `EV215_SummaryCountMismatch` | Declared count ≠ actual count |
| `EF106_AgedTransmissionDate` | Missing date, old date, today passes, datetime today passes, datetime aged throws |
| `EF108_OversizedFile` | Exactly-at-limit passes, one-over throws |
| `EV202_PostalCodeMismatch` | Mismatch throws, match passes |
| `EV216_DuplicateKey` | Duplicate within file, three addresses same key |

### `LockboxFileParserDlbxTest` — real `DLBX_Aspec.json` file

| Nested class | What is tested |
|---|---|
| `MalformedJson` | parse() and parseWithResult() throw EV-200; message includes filename + line; inline `}{` snippet |
| `AspecDateDatetime` | Aged datetime throws EF-106; today datetime passes; plain date passes; garbage format throws |
| `PostalCodeAsInteger` | 1-digit integer fails; 5-digit integer passes (coerced by Jackson) |
| `EmptyMandatoryAddressFields` | Empty company/street1/postalCode each fail EV-200 |
| `PerRecordErrorMarking` | Mixed file: bad→REJECTED, good→validRows; EV-202 only affects one record; all invalid→empty validRows; file-level violation still throws |
| `JsonSchemaValidation` | PostalCode integer, missing LockboxName, invalid SiteIdentifier enum, bad PostalCode pattern, valid passes, missing SummaryInfo, empty AddressList, DLBX strict fails, DLBX lenient marks records |
| `FilenameFormat` | DLBX_Aspec.json fails EF-101 pattern; correct name passes |

### `LockboxImportServiceTest`

| Nested class | What is tested |
|---|---|
| `HappyPath` | Valid file: createImportLog called, loadStaging called, procedure called with all 4 args |
| `HappyPath` | No valid rows: loadStaging skipped, procedure still called |
| `HappyPath` | Mixed result: loadStaging and logRejected both called |
| `EF101_InvalidFileName` | Wrong prefix, missing timestamp, wrong extension each throw EF-101; correct name passes; case-insensitive passes |
| `EV216_DuplicateTransmission` | Duplicate throws EV-216; null DB count treated as 0; null fileDate skips check |
| `ExtractFileDate` | Valid filename returns correct date; end-of-year; unknown format → null; malformed date → null |
| `ErrorPropagation` | File not found → IllegalArgumentException; IOException → RuntimeException; LockboxValidationException propagates directly |

### `LockboxStagingServiceTest`

| Nested class | What is tested |
|---|---|
| `LoadStaging` | 3 rows → 1 batch; 2500 rows → 3 batches; empty list → no batchUpdate; exactly 1000 rows → 1 batch |
| `CallImportProcedure` | All 9 parameters verified in order; null aspecDate passes safely; zero rejectedCount passes as 0 |
| `LogRejected` | Empty list → no DB call; 2 entries → 1 batchUpdate with correct size |

---

## 14. Troubleshooting Runbook

### "EF-101: does not match required format"

**Cause:** Filename does not match `DIGLBX_Aspec_YYYYMMDDThhmmss.json`.  
**Check:** The pattern is case-insensitive. Required parts: `DIGLBX_`, `Aspec_`, exactly 8-digit date,
`T`, exactly 6-digit time, `.json`.  
**Fix:** Rename the file to the correct format before placing it in the drop directory.

### "EF-106: ASPECDate ... is aged"

**Cause:** The date in `SummaryInfo.ASPECDate` is more than `maxFileAgeDays` (default 2) days old.  
**Check the date in the file:**
```bash
grep -o '"ASPECDate": "[^"]*"' /path/to/file.json
```
**Fix options:**
- Contact the provider to resend with today's date.
- Temporarily increase `max-file-age-days` in `application.yml` for a one-off backfill.

### "EV-200: Malformed JSON"

**Cause:** The file contains a JSON syntax error.  
**Message includes line number and column.** Open the file and navigate to that location.  
**Common pattern:** `}{` instead of `},{` between two array objects.  
**Fix:**
```python
with open('file.json', 'rb') as f:
    raw = f.read()
fixed = raw.replace(b'}{', b'},{')
with open('file_fixed.json', 'wb') as f:
    f.write(fixed)
```
Verify only Lockboxes-array boundaries were affected (not object internals).

### "EV-200: JSON schema validation failed"

**Cause:** A record (or the top-level structure) violates the JSON schema.  
**With `parseWithResult()`** (production): only file-level schema errors throw.
Record-level errors are logged as WARN and written to `ibox_lockbox_import_detail`.  
**Query to see rejected records:**
```sql
SELECT lockboxnumber, site_identifier, changed_fields->>'reason' AS reason
FROM ibox_uat.ibox_lockbox_import_detail
WHERE import_log_id = <your_log_id>
  AND operation = 'REJECTED'
ORDER BY lockboxnumber;
```

### "EV-215: SummaryInfo.LockboxCount does not match"

**Cause:** The `SummaryInfo.LockboxCount` integer in the file does not equal the actual
number of entries in the `Lockboxes[]` array.  
**Fix:** Contact the provider. This indicates a file generation error on their side.

### "EV-216: already been successfully imported"

**Cause:** A file with the same `aspec_date` already has `status='SUCCESS'` in `ibox_lockbox_import_log`.  
**Query to verify:**
```sql
SELECT import_log_id, file_name, aspec_date, status, import_date
FROM ibox_uat.ibox_lockbox_import_log
WHERE aspec_date = '2026-04-16'
ORDER BY import_date DESC;
```
**Fix options:**
- If the previous run was erroneous, set its status to FAILED manually, then re-run.
- If it was legitimate, the re-submission is a provider error.

### Import log shows FAILED status

```sql
SELECT import_log_id, file_name, status, error_message
FROM ibox_uat.ibox_lockbox_import_log
WHERE status = 'FAILED'
ORDER BY import_date DESC
LIMIT 10;
```
The `error_message` column contains the PostgreSQL `SQLERRM` from the procedure's `EXCEPTION` block.

### High number of REJECTED records

Check the pattern of rejections:
```sql
SELECT
    changed_fields->>'reason' AS reason,
    COUNT(*)                  AS cnt
FROM ibox_uat.ibox_lockbox_import_detail
WHERE import_log_id = <your_log_id>
  AND operation = 'REJECTED'
GROUP BY 1
ORDER BY cnt DESC;
```

Common reasons and their sources:
| Reason prefix | Source |
|---|---|
| `Schema violation(s): $.Lockboxes[N]...` | JSON Schema validation |
| `Field validation error(s): postalCode...` | Bean validation (`@Pattern`) |
| `[EV-202] AddressPostalCode...` | Postal code mismatch |
| `Duplicate key (site_identifier=...` | Duplicate within same file |

### Staging table growing too large

Check retention:
```sql
SELECT DATE(staged_at) AS day, COUNT(*) AS rows
FROM ibox_uat.ibox_lockbox_staging
GROUP BY 1 ORDER BY 1 DESC;
```
If `LockboxStagingPurgeService` is not scheduled, rows will accumulate.
Trigger a manual purge:
```java
purgeService.purgeExpiredRows();  // deletes rows older than stagingRetentionDays
```

---

## 15. Design Decisions & Trade-offs

### Why per-record rejection instead of fail-fast?

The provider file has known data-quality issues on a subset of records (bad PostalCodes,
empty fields). Failing the entire file would mean no valid records are imported on days
when even one bad record exists. Per-record rejection imports all valid records and
provides a detailed audit trail of exactly which records failed and why.

### Why `import_log_id` tagging instead of TRUNCATE?

TRUNCATE would mean only one import can be in-flight at a time and no historical
staging data is available for audit. Tagging allows:
- Safe concurrent imports (for backfill/retry scenarios)
- 30-day retention for auditing and debugging
- Independent purge scheduling with no impact on the import procedure

### Why separate `LockboxStagingPurgeService`?

Keeping purge inside the stored procedure meant:
- A purge failure would roll back the entire import
- The retention period could only be changed by modifying and re-deploying the SQL procedure
- The purge ran synchronously during every import even if unnecessary

A separate Java service gives independent scheduling, independent failure modes, and
configurable retention without touching the stored procedure.

### Why SHA-256 hash for change detection?

Without a hash, the stored procedure would need to compare 14 columns individually
for every staging row to determine if an update is needed. With `row_hash`:
- One comparison detects any change
- `IS DISTINCT FROM` handles NULL correctly (no NVL/COALESCE needed)
- Hash computation is fast in Java (done once per row at parse time)
- The 14 fields covered are listed in `HashUtil` — if a new field is added to `ibox_lockbox`
  it must also be added to `HashUtil.computeRowHash()` and to the UPDATE in the procedure

SHA-256 is used (rather than MD5) because MD5 is deprecated and flagged by security scanners,
even though this hash is used only for change detection and has no cryptographic requirement.
SHA-256 produces a **64-character** hex string using Java's built-in `MessageDigest` — no
additional dependency is needed. The `row_hash` columns are `varchar(64)` accordingly.

### Why two copies of `DIGLBX_ASPEC.schema.json`?

- `src/main/resources/` → loaded at runtime by `LockboxFileParser.runSchema()` via classpath
- `src/test/resources/` → loaded by tests (classpath isolation in Surefire)

They must be kept in sync. A future improvement would be to store only one copy
and reference it from both classpath locations via a build-time copy task.

### `LockboxImportService` creates the import log BEFORE parsing

The log is created with `status='IN_PROGRESS'` before `parseWithResult()` is called.
This means that even if parsing fails with a `LockboxValidationException` (e.g. EV-215
or EF-106), there is an orphaned `IN_PROGRESS` log row. The procedure's `EXCEPTION`
block will set the status to `FAILED` only if it is reached. File-level validation
failures that occur before `callImportProcedure()` leave the row as `IN_PROGRESS`.

**To clean up orphaned IN_PROGRESS rows:**
```sql
UPDATE ibox_uat.ibox_lockbox_import_log
SET status = 'FAILED', error_message = 'Terminated before procedure call'
WHERE status = 'IN_PROGRESS'
  AND import_date < now() - INTERVAL '1 hour';
```

A future improvement would be to catch validation exceptions in `run()` and update
the log status to FAILED before re-throwing.

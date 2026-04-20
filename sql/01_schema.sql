-- ============================================================
-- Digital Lockbox Import – Staging & Audit Tables
-- Schema: ibox_uat
-- ============================================================

-- -------------------------------------------------------
-- Staging table: mirrors ibox_lockbox (one row per
-- lockbox + address combination, same as the target table)
--
-- Rows are NEVER truncated after import.
-- Each row is tagged with import_log_id + staged_at.
-- A cleanup step deletes rows older than 30 days.
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS ibox_uat.ibox_lockbox_staging
(
    -- Audit columns (set by Java before insert)
    import_log_id           bigint                  NOT NULL,
    staged_at               timestamp               NOT NULL DEFAULT now(),

    lockboxnumber           character varying(20),
    site_identifier         character varying(10),
    lockboxname             character varying(256),
    lockboxstatus           character varying(10),
    digitalindicator        boolean,
    postalcode              character varying(50),
    specificationidentifier character varying(20),
    familygci               character varying(10),
    primarygci              character varying(10),

    -- Address fields (flattened from AddressList)
    addresstype             character varying(15),
    addresscompanyname      character varying(100),
    postofficebox           character varying(50)    NOT NULL DEFAULT '',
    addressattn             character varying(100),
    addressstreet1          character varying(100),
    addressstreet2          character varying(100),
    addresscity             character varying(50),
    addressstate            character varying(2),
    addresspostalcode       character varying(50),
    addresscountry          character(2)             DEFAULT 'US',

    -- SHA-256 hash of all data fields – computed by Java, used for change detection
    row_hash                character varying(64)
);

-- Add row_hash to ibox_lockbox (run once on existing table)
ALTER TABLE ibox_uat.ibox_lockbox
ADD COLUMN IF NOT EXISTS row_hash character varying(64);

CREATE INDEX IF NOT EXISTS idx_lockbox_staging_log_id
    ON ibox_uat.ibox_lockbox_staging (import_log_id);

CREATE INDEX IF NOT EXISTS idx_lockbox_staging_staged_at
    ON ibox_uat.ibox_lockbox_staging (staged_at);

-- Composite index: speeds up the per-run NOT EXISTS / JOIN lookups in the stored procedure
CREATE INDEX IF NOT EXISTS idx_lockbox_staging_import_key
    ON ibox_uat.ibox_lockbox_staging (import_log_id, site_identifier, lockboxnumber, postofficebox);

-- -------------------------------------------------------
-- Import summary audit log  (one row per file run)
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS ibox_uat.ibox_lockbox_import_log
(
    import_log_id   bigint          NOT NULL GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    file_name       character varying(256),
    import_date     timestamp       NOT NULL DEFAULT now(),
    aspec_date      date,
    total_records   integer,
    inserted_count  integer         NOT NULL DEFAULT 0,
    updated_count   integer         NOT NULL DEFAULT 0,
    unchanged_count integer         NOT NULL DEFAULT 0,
    rejected_count  integer         NOT NULL DEFAULT 0,   -- duplicates rejected from JSON
    status          character varying(20),   -- IN_PROGRESS | SUCCESS | FAILED
    error_message   text
);

-- -------------------------------------------------------
-- Import detail log  (one row per inserted/updated lockbox)
--
-- For INSERT  : operation='INSERT', changed_fields is NULL
-- For UPDATE  : operation='UPDATE', changed_fields is a JSONB
--               object showing old/new value for every field
--               that actually changed, e.g.:
--               {
--                 "lockboxname":   {"old": "Sears", "new": "Sears Holdings"},
--                 "lockboxstatus": {"old": "Active", "new": "Closed"}
--               }
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS ibox_uat.ibox_lockbox_import_detail
(
    detail_id       bigint          NOT NULL GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    import_log_id   bigint          NOT NULL,
    lockboxnumber   character varying(20),
    site_identifier character varying(10),
    postofficebox   character varying(50),
    operation       character varying(10)  NOT NULL,  -- INSERT | UPDATE
    changed_fields  jsonb,                            -- NULL for INSERT, field diff for UPDATE
    changed_at      timestamp       NOT NULL DEFAULT now(),
    CONSTRAINT fk_import_detail_log
        FOREIGN KEY (import_log_id)
        REFERENCES ibox_uat.ibox_lockbox_import_log (import_log_id)
);

CREATE INDEX IF NOT EXISTS idx_import_detail_log_id
    ON ibox_uat.ibox_lockbox_import_detail (import_log_id);

CREATE INDEX IF NOT EXISTS idx_import_detail_lockbox
    ON ibox_uat.ibox_lockbox_import_detail (lockboxnumber, site_identifier);

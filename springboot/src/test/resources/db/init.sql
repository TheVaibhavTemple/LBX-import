-- ============================================================
-- Test database initialisation – runs automatically when the
-- Testcontainers PostgreSQL container starts.
-- Applies schema + stored procedure in the ibox_uat schema.
-- ============================================================

CREATE SCHEMA IF NOT EXISTS ibox_uat;

-- Sequences
CREATE SEQUENCE IF NOT EXISTS ibox_uat.ibox_lockbox_lockboxid_seq START 1;
CREATE SEQUENCE IF NOT EXISTS ibox_uat.ibox_global_client_identifier_globalclientidentifierid_seq START 1;

-- -------------------------------------------------------
-- Main lockbox table
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS ibox_uat.ibox_lockbox
(
    lockboxid               integer     NOT NULL DEFAULT nextval('ibox_uat.ibox_lockbox_lockboxid_seq'),
    globalclientidentifierid integer,
    site_identifier         varchar(10),
    provider_id             integer     NOT NULL,
    lob_id                  integer     NOT NULL,
    application_id          integer     NOT NULL,
    lockboxnumber           varchar(20),
    lockboxname             varchar(256),
    lockboxstatus           varchar(10),
    digitalindicator        boolean,
    postalcode              varchar(50),
    specificationidentifier varchar(20),
    addresstype             varchar(15),
    addresscompanyname      varchar(100),
    postofficebox           varchar(50) NOT NULL DEFAULT '',
    addressattn             varchar(100),
    addressstreet1          varchar(100),
    addressstreet2          varchar(100),
    addresscity             varchar(50),
    addressstate            varchar(2),
    addresspostalcode       varchar(50),
    addresscountry          char(2),
    modified_by             varchar(50),
    created_at              timestamp   NOT NULL DEFAULT now(),
    updated_at              timestamp   NOT NULL DEFAULT now(),
    incomingfileid          bigint,
    last_updated_by         varchar(100),
    last_update_on          timestamp,
    batchmode               varchar(50),
    batchsize               integer,
    batchmode_int           integer,
    row_hash                varchar(64),
    CONSTRAINT ibox_lockbox_pkey PRIMARY KEY (lockboxid),
    CONSTRAINT ibox_lockbox_site_identifier_lockboxnumber_postofficebox_key
        UNIQUE (site_identifier, lockboxnumber, postofficebox)
);

-- -------------------------------------------------------
-- GCI table
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS ibox_uat.ibox_global_client_identifier
(
    globalclientidentifierid integer NOT NULL DEFAULT nextval('ibox_uat.ibox_global_client_identifier_globalclientidentifierid_seq'),
    familygci                varchar(10),
    primarygci               varchar(10),
    provider_id              integer,
    modified_by              bigint,
    created_at               timestamp NOT NULL DEFAULT now(),
    updated_at               timestamp NOT NULL DEFAULT now(),
    CONSTRAINT ibox_global_client_identifier_pkey PRIMARY KEY (globalclientidentifierid)
);

-- -------------------------------------------------------
-- Staging table – rows tagged with import_log_id,
-- retained for 30 days (purged by separate service)
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS ibox_uat.ibox_lockbox_staging
(
    import_log_id           bigint                  NOT NULL,
    staged_at               timestamp               NOT NULL DEFAULT now(),

    lockboxnumber           varchar(20),
    site_identifier         varchar(10),
    lockboxname             varchar(256),
    lockboxstatus           varchar(10),
    digitalindicator        boolean,
    postalcode              varchar(50),
    specificationidentifier varchar(20),
    familygci               varchar(10),
    primarygci              varchar(10),

    addresstype             varchar(15),
    addresscompanyname      varchar(100),
    postofficebox           varchar(50) NOT NULL DEFAULT '',
    addressattn             varchar(100),
    addressstreet1          varchar(100),
    addressstreet2          varchar(100),
    addresscity             varchar(50),
    addressstate            varchar(2),
    addresspostalcode       varchar(50),
    addresscountry          char(2)     DEFAULT 'US',

    row_hash                varchar(64)
);

CREATE INDEX IF NOT EXISTS idx_lockbox_staging_log_id
    ON ibox_uat.ibox_lockbox_staging (import_log_id);

CREATE INDEX IF NOT EXISTS idx_lockbox_staging_staged_at
    ON ibox_uat.ibox_lockbox_staging (staged_at);

CREATE INDEX IF NOT EXISTS idx_lockbox_staging_import_key
    ON ibox_uat.ibox_lockbox_staging (import_log_id, site_identifier, lockboxnumber, postofficebox);

-- -------------------------------------------------------
-- Import summary audit log  (one row per file run)
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS ibox_uat.ibox_lockbox_import_log
(
    import_log_id   bigint    NOT NULL GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    file_name       varchar(256),
    import_date     timestamp NOT NULL DEFAULT now(),
    aspec_date      date,
    total_records   integer,
    inserted_count  integer   NOT NULL DEFAULT 0,
    updated_count   integer   NOT NULL DEFAULT 0,
    unchanged_count integer   NOT NULL DEFAULT 0,
    rejected_count  integer   NOT NULL DEFAULT 0,
    status          varchar(20),
    error_message   text
);

-- -------------------------------------------------------
-- Import detail log  (one row per inserted/updated/rejected lockbox)
--
-- For INSERT   : operation='INSERT', changed_fields is NULL
-- For UPDATE   : operation='UPDATE', changed_fields shows old/new per field
-- For REJECTED : operation='REJECTED', changed_fields->>'reason' explains why
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS ibox_uat.ibox_lockbox_import_detail
(
    detail_id       bigint    NOT NULL GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    import_log_id   bigint    NOT NULL,
    lockboxnumber   varchar(20),
    site_identifier varchar(10),
    postofficebox   varchar(50),
    operation       varchar(10) NOT NULL,   -- INSERT | UPDATE | REJECTED
    changed_fields  jsonb,                  -- NULL for INSERT; old/new values for UPDATE; reason for REJECTED
    changed_at      timestamp NOT NULL DEFAULT now(),
    CONSTRAINT fk_import_detail_log
        FOREIGN KEY (import_log_id)
        REFERENCES ibox_uat.ibox_lockbox_import_log (import_log_id)
);

CREATE INDEX IF NOT EXISTS idx_import_detail_log_id
    ON ibox_uat.ibox_lockbox_import_detail (import_log_id);

CREATE INDEX IF NOT EXISTS idx_import_detail_lockbox
    ON ibox_uat.ibox_lockbox_import_detail (lockboxnumber, site_identifier);

-- -------------------------------------------------------
-- Stored procedure – mirrors 02_import_procedure.sql
-- NOTE: purge of staging rows is handled by LockboxStagingPurgeService
-- -------------------------------------------------------
CREATE OR REPLACE PROCEDURE ibox_uat.import_lockbox_data(
    p_log_id            bigint,
    p_file_name         varchar,
    p_aspec_date        date,
    p_provider_id       integer,
    p_lob_id            integer,
    p_application_id    integer,
    p_rejected_count    integer     DEFAULT 0,
    p_incoming_file_id  bigint      DEFAULT NULL,
    p_modified_by       varchar     DEFAULT 'LOCKBOX_IMPORT'
)
LANGUAGE plpgsql
AS $$
DECLARE
    v_inserted      integer := 0;
    v_updated       integer := 0;
    v_unchanged     integer := 0;
    v_total         integer := 0;
BEGIN

    -- 1. Count rows loaded for this specific import run
    SELECT COUNT(*) INTO v_total
    FROM ibox_uat.ibox_lockbox_staging
    WHERE import_log_id = p_log_id;

    -- 2. Upsert ibox_global_client_identifier
    INSERT INTO ibox_uat.ibox_global_client_identifier
        (familygci, primarygci, provider_id, created_at, updated_at)
    SELECT DISTINCT s.familygci, s.primarygci, p_provider_id, now(), now()
    FROM ibox_uat.ibox_lockbox_staging s
    WHERE s.import_log_id = p_log_id
      AND s.familygci IS NOT NULL
      AND NOT EXISTS (
            SELECT 1 FROM ibox_uat.ibox_global_client_identifier g
            WHERE g.familygci   = s.familygci
              AND g.primarygci  = s.primarygci
              AND g.provider_id = p_provider_id
          );

    -- 3a. Log INSERT detail
    INSERT INTO ibox_uat.ibox_lockbox_import_detail
        (import_log_id, lockboxnumber, site_identifier, postofficebox, operation)
    SELECT p_log_id, s.lockboxnumber, s.site_identifier, s.postofficebox, 'INSERT'
    FROM ibox_uat.ibox_lockbox_staging s
    WHERE s.import_log_id = p_log_id
      AND NOT EXISTS (
          SELECT 1 FROM ibox_uat.ibox_lockbox l
          WHERE l.site_identifier = s.site_identifier
            AND l.lockboxnumber   = s.lockboxnumber
            AND l.postofficebox   = s.postofficebox
      );

    -- 3b. Insert new lockbox rows
    INSERT INTO ibox_uat.ibox_lockbox
    (
        globalclientidentifierid, site_identifier, provider_id, lob_id, application_id,
        lockboxnumber, lockboxname, lockboxstatus, digitalindicator, postalcode,
        specificationidentifier, addresstype, addresscompanyname, postofficebox,
        addressattn, addressstreet1, addressstreet2, addresscity, addressstate,
        addresspostalcode, addresscountry, incomingfileid, modified_by,
        last_updated_by, last_update_on, created_at, updated_at, row_hash
    )
    SELECT
        g.globalclientidentifierid, s.site_identifier, p_provider_id, p_lob_id, p_application_id,
        s.lockboxnumber, s.lockboxname, s.lockboxstatus, s.digitalindicator, s.postalcode,
        s.specificationidentifier, s.addresstype, s.addresscompanyname, s.postofficebox,
        s.addressattn, s.addressstreet1, s.addressstreet2, s.addresscity, s.addressstate,
        s.addresspostalcode, COALESCE(s.addresscountry, 'US'),
        p_incoming_file_id, p_modified_by, p_modified_by, now(), now(), now(),
        s.row_hash
    FROM ibox_uat.ibox_lockbox_staging s
    LEFT JOIN ibox_uat.ibox_global_client_identifier g
           ON g.familygci   = s.familygci
          AND g.primarygci  = s.primarygci
          AND g.provider_id = p_provider_id
    WHERE s.import_log_id = p_log_id
      AND NOT EXISTS (
          SELECT 1 FROM ibox_uat.ibox_lockbox l
          WHERE l.site_identifier = s.site_identifier
            AND l.lockboxnumber   = s.lockboxnumber
            AND l.postofficebox   = s.postofficebox
      );

    GET DIAGNOSTICS v_inserted = ROW_COUNT;

    -- 4a. Log UPDATE detail with old/new values BEFORE update (hash differs only)
    INSERT INTO ibox_uat.ibox_lockbox_import_detail
        (import_log_id, lockboxnumber, site_identifier, postofficebox, operation, changed_fields)
    SELECT
        p_log_id, l.lockboxnumber, l.site_identifier, l.postofficebox, 'UPDATE',
        jsonb_strip_nulls(jsonb_build_object(
            'lockboxname',
                CASE WHEN l.lockboxname IS DISTINCT FROM s.lockboxname
                     THEN jsonb_build_object('old', l.lockboxname, 'new', s.lockboxname) END,
            'lockboxstatus',
                CASE WHEN l.lockboxstatus IS DISTINCT FROM s.lockboxstatus
                     THEN jsonb_build_object('old', l.lockboxstatus, 'new', s.lockboxstatus) END,
            'digitalindicator',
                CASE WHEN l.digitalindicator IS DISTINCT FROM s.digitalindicator
                     THEN jsonb_build_object('old', l.digitalindicator, 'new', s.digitalindicator) END,
            'postalcode',
                CASE WHEN l.postalcode IS DISTINCT FROM s.postalcode
                     THEN jsonb_build_object('old', l.postalcode, 'new', s.postalcode) END,
            'specificationidentifier',
                CASE WHEN l.specificationidentifier IS DISTINCT FROM s.specificationidentifier
                     THEN jsonb_build_object('old', l.specificationidentifier, 'new', s.specificationidentifier) END,
            'addresstype',
                CASE WHEN l.addresstype IS DISTINCT FROM s.addresstype
                     THEN jsonb_build_object('old', l.addresstype, 'new', s.addresstype) END,
            'addresscompanyname',
                CASE WHEN l.addresscompanyname IS DISTINCT FROM s.addresscompanyname
                     THEN jsonb_build_object('old', l.addresscompanyname, 'new', s.addresscompanyname) END,
            'addressattn',
                CASE WHEN l.addressattn IS DISTINCT FROM s.addressattn
                     THEN jsonb_build_object('old', l.addressattn, 'new', s.addressattn) END,
            'addressstreet1',
                CASE WHEN l.addressstreet1 IS DISTINCT FROM s.addressstreet1
                     THEN jsonb_build_object('old', l.addressstreet1, 'new', s.addressstreet1) END,
            'addressstreet2',
                CASE WHEN l.addressstreet2 IS DISTINCT FROM s.addressstreet2
                     THEN jsonb_build_object('old', l.addressstreet2, 'new', s.addressstreet2) END,
            'addresscity',
                CASE WHEN l.addresscity IS DISTINCT FROM s.addresscity
                     THEN jsonb_build_object('old', l.addresscity, 'new', s.addresscity) END,
            'addressstate',
                CASE WHEN l.addressstate IS DISTINCT FROM s.addressstate
                     THEN jsonb_build_object('old', l.addressstate, 'new', s.addressstate) END,
            'addresspostalcode',
                CASE WHEN l.addresspostalcode IS DISTINCT FROM s.addresspostalcode
                     THEN jsonb_build_object('old', l.addresspostalcode, 'new', s.addresspostalcode) END,
            'addresscountry',
                CASE WHEN l.addresscountry IS DISTINCT FROM COALESCE(s.addresscountry, 'US')
                     THEN jsonb_build_object('old', l.addresscountry, 'new', COALESCE(s.addresscountry, 'US')) END
        ))
    FROM ibox_uat.ibox_lockbox l
    JOIN ibox_uat.ibox_lockbox_staging s
      ON l.site_identifier = s.site_identifier
     AND l.lockboxnumber   = s.lockboxnumber
     AND l.postofficebox   = s.postofficebox
    WHERE s.import_log_id    = p_log_id
      AND l.row_hash IS DISTINCT FROM s.row_hash;

    -- 4b. Update changed lockbox rows (hash differs only)
    UPDATE ibox_uat.ibox_lockbox l
    SET
        globalclientidentifierid = g.globalclientidentifierid,
        lockboxname              = s.lockboxname,
        lockboxstatus            = s.lockboxstatus,
        digitalindicator         = s.digitalindicator,
        postalcode               = s.postalcode,
        specificationidentifier  = s.specificationidentifier,
        addresstype              = s.addresstype,
        addresscompanyname       = s.addresscompanyname,
        addressattn              = s.addressattn,
        addressstreet1           = s.addressstreet1,
        addressstreet2           = s.addressstreet2,
        addresscity              = s.addresscity,
        addressstate             = s.addressstate,
        addresspostalcode        = s.addresspostalcode,
        addresscountry           = COALESCE(s.addresscountry, 'US'),
        row_hash                 = s.row_hash,
        incomingfileid           = p_incoming_file_id,
        last_updated_by          = p_modified_by,
        last_update_on           = now(),
        updated_at               = now()
    FROM ibox_uat.ibox_lockbox_staging s
    LEFT JOIN ibox_uat.ibox_global_client_identifier g
           ON g.familygci   = s.familygci
          AND g.primarygci  = s.primarygci
          AND g.provider_id = p_provider_id
    WHERE s.import_log_id   = p_log_id
      AND l.site_identifier = s.site_identifier
      AND l.lockboxnumber   = s.lockboxnumber
      AND l.postofficebox   = s.postofficebox
      AND l.row_hash IS DISTINCT FROM s.row_hash;

    GET DIAGNOSTICS v_updated = ROW_COUNT;
    v_unchanged := v_total - v_inserted - v_updated;

    -- 5. Finalise summary log
    UPDATE ibox_uat.ibox_lockbox_import_log
    SET
        file_name       = p_file_name,
        aspec_date      = p_aspec_date,
        total_records   = v_total,
        inserted_count  = v_inserted,
        updated_count   = v_updated,
        unchanged_count = v_unchanged,
        rejected_count  = p_rejected_count,
        status          = 'SUCCESS'
    WHERE import_log_id = p_log_id;

    RAISE NOTICE 'Lockbox import complete – total: %, inserted: %, updated: %, unchanged: %, rejected: %',
        v_total, v_inserted, v_updated, v_unchanged, p_rejected_count;

EXCEPTION WHEN OTHERS THEN
    UPDATE ibox_uat.ibox_lockbox_import_log
    SET status        = 'FAILED',
        error_message = SQLERRM
    WHERE import_log_id = p_log_id;
    RAISE;
END;
$$;

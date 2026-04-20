-- ============================================================
-- Lockbox Import Stored Procedure
-- Schema  : ibox_uat
-- Procedure: import_lockbox_data
--
-- Parameters:
--   p_log_id           – import_log_id created by Java before staging
--   p_file_name        – original filename e.g. DIGLBX_Aspec_20260416T120000.json
--   p_aspec_date       – ASPECDate from the JSON SummaryInfo block
--   p_provider_id      – provider_id (NOT NULL on ibox_lockbox)
--   p_lob_id           – lob_id      (NOT NULL on ibox_lockbox)
--   p_application_id   – application_id (NOT NULL on ibox_lockbox)
--   p_rejected_count   – number of duplicate records rejected in Java
--   p_incoming_file_id – incomingfileid (pass NULL if not used)
--   p_modified_by      – username / job name performing the import
--
-- Flow:
--   1.  Update import log to IN_PROGRESS (log entry already created by Java)
--   2.  Upsert ibox_global_client_identifier for new GCI combinations
--   3a. Log INSERTs into ibox_lockbox_import_detail
--   3b. Insert new lockbox rows into ibox_lockbox
--   4a. Log UPDATEs (with old/new field values) into ibox_lockbox_import_detail
--   4b. Update changed lockbox rows in ibox_lockbox
--   5.  Mark import log SUCCESS (or FAILED on exception)
-- ============================================================

CREATE OR REPLACE PROCEDURE ibox_uat.import_lockbox_data(
    p_log_id            bigint,
    p_file_name         character varying,
    p_aspec_date        date,
    p_provider_id       integer,
    p_lob_id            integer,
    p_application_id    integer,
    p_rejected_count    integer     DEFAULT 0,
    p_incoming_file_id  bigint      DEFAULT NULL,
    p_modified_by       character varying DEFAULT 'LOCKBOX_IMPORT'
)
LANGUAGE plpgsql
AS $$
DECLARE
    v_inserted      integer := 0;
    v_updated       integer := 0;
    v_unchanged     integer := 0;
    v_total         integer := 0;
BEGIN

    -- --------------------------------------------------------
    -- 1. Count rows loaded for this specific import run
    -- --------------------------------------------------------
    SELECT COUNT(*) INTO v_total
    FROM ibox_uat.ibox_lockbox_staging
    WHERE import_log_id = p_log_id;

    -- --------------------------------------------------------
    -- 2. Upsert ibox_global_client_identifier
    -- --------------------------------------------------------
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

    -- --------------------------------------------------------
    -- 3a. Log INSERT detail
    -- --------------------------------------------------------
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

    -- --------------------------------------------------------
    -- 3b. Insert new lockbox rows
    -- --------------------------------------------------------
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

    -- --------------------------------------------------------
    -- 4a. Log UPDATE detail with old/new values BEFORE update
    --     Only rows where row_hash differs need to be processed
    -- --------------------------------------------------------
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
      AND l.row_hash IS DISTINCT FROM s.row_hash;  -- single hash comparison

    -- --------------------------------------------------------
    -- 4b. Update changed lockbox rows (hash differs only)
    -- --------------------------------------------------------
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
        row_hash                 = s.row_hash,         -- store new hash
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
      AND l.row_hash IS DISTINCT FROM s.row_hash;  -- single hash comparison

    GET DIAGNOSTICS v_updated = ROW_COUNT;
    v_unchanged := v_total - v_inserted - v_updated;

    -- --------------------------------------------------------
    -- 5. Finalise summary log
    -- --------------------------------------------------------
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

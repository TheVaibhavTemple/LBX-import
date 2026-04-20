-- row_hash column widened from varchar(32) to varchar(64) for SHA-256

ALTER TABLE ibox_uat.ibox_lockbox
    ALTER COLUMN row_hash TYPE character varying(64);

ALTER TABLE ibox_uat.ibox_lockbox_staging
    ALTER COLUMN row_hash TYPE character varying(64);

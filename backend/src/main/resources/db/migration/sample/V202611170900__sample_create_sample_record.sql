-- DEMO ONLY: the sample feature's table (data-and-migrations.md §11). Delete this folder together
-- with feature/sample (README "The sample module"). Flyway creates the sample schema itself
-- (spring.flyway.schemas), owned by the migrating owner; the table's grants come from the default
-- privileges of V202611160900, so only USAGE on the schema is granted here.
GRANT USAGE ON SCHEMA sample TO "${app_role}";

CREATE TABLE sample.sample_record (
  id          uuid        PRIMARY KEY,
  name        text        NOT NULL,
  description text,
  version     bigint      NOT NULL DEFAULT 0,
  created_at  timestamptz NOT NULL,
  created_by  text        NOT NULL,
  updated_at  timestamptz NOT NULL,
  updated_by  text        NOT NULL,
  deleted_at  timestamptz,
  deleted_by  text,
  CONSTRAINT ck_sample_record__name_not_blank CHECK (btrim(name) <> ''),
  CONSTRAINT ck_sample_record__deleted_at_with_deleted_by
    CHECK ((deleted_at IS NULL) = (deleted_by IS NULL))
);

-- A name is unique among active records only: a deleted record's name may be reused.
CREATE UNIQUE INDEX uq_sample_record__name ON sample.sample_record (name) WHERE deleted_at IS NULL;
-- The default list order (newest first, then id) over active records only.
CREATE INDEX ix_sample_record__created_at_id__active
  ON sample.sample_record (created_at DESC, id) WHERE deleted_at IS NULL;

-- Test-only fixture table for the persistence ITs (never part of the main migrations). Applied only
-- to a throwaway database, after the common migration.
GRANT USAGE ON SCHEMA fixture TO "${app_role}";

CREATE TABLE fixture.audited_thing (
  id          uuid        PRIMARY KEY,
  name        text        NOT NULL,
  quantity    integer     NOT NULL DEFAULT 0,
  version     bigint      NOT NULL DEFAULT 0,
  created_at  timestamptz NOT NULL,
  created_by  text        NOT NULL,
  updated_at  timestamptz NOT NULL,
  updated_by  text        NOT NULL,
  deleted_at  timestamptz,
  deleted_by  text,
  CONSTRAINT ck_audited_thing__quantity_not_negative CHECK (quantity >= 0),
  CONSTRAINT ck_audited_thing__deleted_at_with_deleted_by
    CHECK ((deleted_at IS NULL) = (deleted_by IS NULL))
);

CREATE UNIQUE INDEX uq_audited_thing__name ON fixture.audited_thing (name) WHERE deleted_at IS NULL;

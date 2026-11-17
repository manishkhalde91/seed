-- Long-running operations (realtime-and-storage.md §1.1): one row per operation, its status
-- changed only through OperationService with a conditional update on status_version (state
-- machine in architecture-diagrams.md §4). requested_by is the actor that started it and the only
-- non-ADMIN reader. Finished rows older than seed.jobs.operation-retention are deleted by the
-- operation-cleanup job. The grants come from the default privileges of V202611160900.
CREATE TABLE common.operation (
  id             uuid        PRIMARY KEY,
  kind           text        NOT NULL,
  subject_id     uuid,
  requested_by   text        NOT NULL,
  status         text        NOT NULL,
  status_version smallint    NOT NULL,
  result         jsonb,
  error_code     text,
  error_message  text,
  correlation_id text        NOT NULL,
  created_at     timestamptz NOT NULL,
  created_by     text        NOT NULL,
  updated_at     timestamptz NOT NULL,
  updated_by     text        NOT NULL,
  CONSTRAINT ck_operation__status CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
  CONSTRAINT ck_operation__status_version_positive CHECK (status_version >= 1),
  CONSTRAINT ck_operation__kind_not_blank CHECK (btrim(kind) <> '')
);

CREATE INDEX ix_operation__requested_by_created_at ON common.operation (requested_by, created_at);
CREATE INDEX ix_operation__created_at ON common.operation (created_at);

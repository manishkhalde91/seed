-- Idempotency-Key records (api-standards.md §1; data-and-migrations.md §2-§3): an internal table,
-- so a bigint identity key and only created_at/updated_at. The grants come from the default
-- privileges of V202611160900. One row per key; a request claims the key by inserting an
-- IN_PROGRESS row with its own claim_token, and stores its response when it completes. Rows older
-- than seed.idempotency.retention are deleted by IdempotencyCleanup.
CREATE TABLE common.idempotency_record (
  id                    bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  idempotency_key       text        NOT NULL,
  request_hash          text        NOT NULL,
  claim_token           uuid        NOT NULL,
  status                text        NOT NULL,
  response_status       integer,
  response_content_type text,
  response_location     text,
  response_body         bytea,
  created_at            timestamptz NOT NULL,
  updated_at            timestamptz NOT NULL,
  CONSTRAINT uq_idempotency_record__idempotency_key UNIQUE (idempotency_key),
  CONSTRAINT ck_idempotency_record__status CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
  CONSTRAINT ck_idempotency_record__completed_has_response
    CHECK (status <> 'COMPLETED' OR response_status IS NOT NULL),
  CONSTRAINT ck_idempotency_record__key_length CHECK (char_length(idempotency_key) BETWEEN 1 AND 64),
  CONSTRAINT ck_idempotency_record__request_hash_length CHECK (char_length(request_hash) = 64)
);

CREATE INDEX ix_idempotency_record__created_at ON common.idempotency_record (created_at);

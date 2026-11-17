-- Idempotency-Key records are scoped to the caller (api-standards.md §1; story 7): the same key
-- from two callers is two independent records, and a caller never replays another's response.
-- caller is the CurrentActor ID (user:<sub>, system:<name> or anonymous) of the claiming request;
-- a token's sub is at most 255 characters, so user:<sub> fits the 300-character bound.
--
-- Existing rows are deleted first: they are temporary (24 h retention), were written before any
-- caller was known, and so cannot be given one. A client retrying such a request runs it again.
--
-- NOT rolling-compatible: instances of the previous release name the dropped constraint
-- uq_idempotency_record__idempotency_key and insert no caller, so every keyed write they handle
-- fails once this has run. Stop all old instances before the migrate run of this release. (No
-- expand/contract here: the seed has no shared deployment yet.)
DELETE FROM common.idempotency_record;

ALTER TABLE common.idempotency_record
  ADD COLUMN caller text NOT NULL;

ALTER TABLE common.idempotency_record
  DROP CONSTRAINT uq_idempotency_record__idempotency_key;

ALTER TABLE common.idempotency_record
  ADD CONSTRAINT uq_idempotency_record__caller_idempotency_key UNIQUE (caller, idempotency_key);

ALTER TABLE common.idempotency_record
  ADD CONSTRAINT ck_idempotency_record__caller_length CHECK (char_length(caller) BETWEEN 1 AND 300);

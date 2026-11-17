-- Spring Modulith's event publication registry (realtime-and-storage.md §1.2; the Modulith 2.1 v2
-- PostgreSQL layout of spring-modulith-events-jdbc). An event published in a transaction is
-- recorded here in that transaction; its listener completes the row after commit, and incomplete
-- rows are resubmitted (on restart, and by the event-publication-resubmit job). Completed rows
-- older than seed.jobs.event-publication-retention are deleted by the event-publication-cleanup
-- job. The grants come from the default privileges of V202611160900.
CREATE TABLE common.event_publication (
  id                     uuid        NOT NULL PRIMARY KEY,
  listener_id            text        NOT NULL,
  event_type             text        NOT NULL,
  serialized_event       text        NOT NULL,
  publication_date       timestamptz NOT NULL,
  completion_date        timestamptz,
  status                 text,
  completion_attempts    integer,
  last_resubmission_date timestamptz
);

CREATE INDEX ix_event_publication__serialized_event
  ON common.event_publication USING hash (serialized_event);
CREATE INDEX ix_event_publication__completion_date
  ON common.event_publication (completion_date);

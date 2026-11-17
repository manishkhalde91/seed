# ADR-13: S3-compatible object storage behind a port

- **Status:** Accepted
- **Date:** 2026-11-13
- **Source:** `_snap-output/specs/spec-monorepo-seed/decisions.md`

- **Proposal:** `ArtifactStorage` port with an S3 adapter (AWS SDK v2, endpoint configurable); metadata rows; backend-only writes; 5-minute presigned GET for downloads.
- **Why:** files do not belong in PostgreSQL; S3 is the de-facto API, served locally by MinIO.
- **Alternatives:** `bytea` columns; streaming downloads through the API; browser presigned uploads.
- **Pros:** no AWS types outside `infrastructure`; works with any S3-compatible store.
- **Cons:** two writes (row + object) need the PENDING/STORED sequence and a cleanup job.
- **Scalability:** storage scales independently; key prefixes per module.

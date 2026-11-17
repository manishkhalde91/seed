# ADR-07: Live status over STOMP with PostgreSQL LISTEN/NOTIFY fan-out

- **Status:** Accepted
- **Date:** 2026-11-13
- **Source:** `_snap-output/specs/spec-monorepo-seed/decisions.md`

- **Proposal:** status changes update `common.operation` and `pg_notify` in one transaction; every instance LISTENs and pushes to its STOMP subscribers; snapshot plus resync for recovery.
- **Why:** push without polling, no sticky sessions, no extra infrastructure, only committed state is pushed.
- **Alternatives:** SSE (no auth header from `EventSource`); a STOMP broker relay; polling.
- **Pros:** real-time, order-tolerant (versioned).
- **Cons:** one extra DB connection per instance; 8 KB NOTIFY payload limit; the load balancer must allow WebSocket.
- **Scalability:** move to a broker relay for user-targeted queues or high volume.

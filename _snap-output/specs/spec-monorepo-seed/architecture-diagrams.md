# Architecture diagrams

## 1. System context (runtime)

```mermaid
flowchart LR
  USER(["User's browser"])
  subgraph MONO["Monorepo deployables"]
    FE["Angular app<br/>static bundle + config.json"]
    BE["Spring Boot backend<br/>1..n instances, one image<br/>REST /api/v1 + STOMP /ws"]
  end
  IDP["OIDC provider<br/>any; mock-oauth2-server locally"]
  DB[("PostgreSQL 16<br/>one schema per module")]
  OBJ[("S3-compatible storage<br/>MinIO locally")]

  USER -- loads --> FE
  USER -- "HTTPS /api, WSS /ws" --> BE
  USER -. "sign-in (code + PKCE)" .-> IDP
  BE -. "token signing keys (JWKS)" .-> IDP
  BE -- "SQL, LISTEN/NOTIFY" --> DB
  BE -- "objects, presigned links" --> OBJ
  USER -- "download via presigned link" --> OBJ
```

The backend and frontend build and deploy independently. Locally, `infrastructure/compose.yaml` provides PostgreSQL, MinIO and the mock OIDC provider. Production hosting is out of scope.

## 2. Backend architecture and request flow

```mermaid
flowchart TB
  CLIENT(["① REST request<br/>HTTPS /api/v1/…<br/>with a bearer token"])

  subgraph PATH["Request path — one feature module, e.g. feature/sample"]
    direction TB
    SEC["② Security filter chain<br/><b>security/</b><br/>checks the JWT and the caller's role;<br/>rejects with 401 / 403"]
    API["③ API layer<br/><b>feature/…/api</b><br/>REST controller: reads and validates the request,<br/>returns the standard response"]
    SVC["④ Application layer<br/><b>feature/…/application</b><br/>service: runs the use case,<br/>owns the transaction"]
    DOM["Domain model<br/><b>feature/…/domain</b><br/>business objects and rules,<br/>plain Java"]
    REPO["⑤ Repository interface<br/><b>feature/…/domain</b><br/>SampleRecordRepository: save, findById …<br/>speaks only in domain objects"]
    IMPL["⑥ Repository implementation<br/><b>feature/…/infrastructure/persistence</b><br/>JpaSampleRecordRepository: maps to JPA entities,<br/>runs queries via Spring Data"]
  end

  subgraph CROSS["Cross-cutting — shared by every feature"]
    direction TB
    CFG["Configuration<br/><b>config/</b><br/>framework setup: JSON, clock, OpenAPI, thread pools"]
    RESP["Standard API response<br/><b>common/api</b><br/>one envelope for every success and page"]
    ERR["Global exception handling<br/><b>common/error</b><br/>turns any exception into the standard error response"]
    LOG["Logging and correlation ID<br/><b>common/logging</b><br/>tags every log line and response;<br/>runs before the security filter chain"]
    AUD["Auditing<br/><b>common/auditing</b><br/>fills created/updated by and at,<br/>soft-delete columns"]
    MIG["Database migrations<br/><b>resources/db/migration</b><br/>Flyway scripts, applied before<br/>the application serves traffic"]
  end

  subgraph EXT["External dependencies"]
    direction TB
    IDP["OIDC provider<br/>issues tokens; publishes signing keys"]
    DB[("⑦ PostgreSQL<br/>one schema per module")]
  end

  CLIENT --> SEC
  SEC -->|"authenticated request"| API
  API -->|"calls use case"| SVC
  SVC -->|"applies rules"| DOM
  SVC -->|"loads / saves via interface"| REPO
  IMPL -.->|"implements"| REPO
  IMPL -->|"SQL"| DB
  SEC -.->|"fetches signing keys"| IDP
  MIG -->|"creates / changes schema"| DB
  PATH -.->|"uses"| CROSS

  classDef ext fill:#F3F4F6,stroke:#4B5563,color:#111827
  classDef layer fill:#DBEAFE,stroke:#1D4ED8,color:#111827
  classDef domain fill:#EDE9FE,stroke:#6D28D9,color:#111827
  classDef infra fill:#DCFCE7,stroke:#15803D,color:#111827
  classDef cross fill:#FEF3C7,stroke:#B45309,color:#111827
  class CLIENT,IDP,DB ext
  class API,SVC layer
  class DOM,REPO domain
  class IMPL infra
  class SEC,CFG,RESP,ERR,LOG,AUD,MIG cross
```

**How to read it**
- **Colours:**
  - blue = request-handling layers
  - purple = domain (model and repository interface)
  - green = infrastructure (repository implementation)
  - amber = cross-cutting packages; the security filter chain is one of them, sitting on the request path
  - grey = outside the application
- **Arrows:**
  - Solid arrows are calls or data flow.
  - The dashed "implements" arrow is a compile-time dependency: the implementation depends on the interface, never the reverse.
  - Spring injects the implementation wherever the interface is asked for.
- **The response** goes back the same way. Controllers wrap results in the standard response; any exception becomes the standard error response, carrying the correlation ID.

| # / component | Package | Responsibility |
|---|---|---|
| ① REST request | — | A client call to `/api/v1/…` with a bearer token |
| ② Security filter chain | `security/` | Validate the JWT (signature, issuer, audience, expiry), map roles, deny by default |
| ③ API layer | `feature/<module>/api` | Bind and validate the request, check `@PreAuthorize`, call one service, wrap the result |
| ④ Application layer | `feature/<module>/application` | Run the use case, own the transaction, use domain objects and repository interfaces |
| Domain model | `feature/<module>/domain` | Business objects, invariants and state transitions; no framework code |
| ⑤ Repository interface | `feature/<module>/domain` | Data-access contract for one aggregate, in domain terms |
| ⑥ Repository implementation | `feature/<module>/infrastructure/persistence` | JPA/Spring Data implementation; entity ↔ domain mapping; data access only |
| ⑦ PostgreSQL | external | Stores data; one schema per module |
| Configuration | `config/` | Framework beans and settings |
| Standard API response | `common/api` | `ApiResponse<T>`, paging wrapper |
| Global exception handling | `common/error` | One `@RestControllerAdvice` mapping exceptions to status + error code |
| Logging and correlation ID | `common/logging` | Correlation ID in MDC and response; structured logs; masking |
| Auditing | `common/auditing` | Base entities and `AuditorAware`; soft delete |
| Database migrations | `resources/db/migration` | Versioned Flyway SQL per module |

## 3. Sequence — long-running work with live status (sample export)

```mermaid
sequenceDiagram
  autonumber
  participant C as Browser
  participant A as Backend instance A
  participant B as Backend instance B
  participant DB as PostgreSQL
  participant S3 as Object storage
  C->>A: POST /api/v1/sample-records/export (Bearer, X-Correlation-Id)
  rect rgb(235,245,255)
  note over A,DB: one transaction
  A->>DB: INSERT common.operation (QUEUED, version 1)
  A->>DB: INSERT event_publication (SampleExportRequested)
  end
  A-->>C: 202 envelope {operationId, status: QUEUED}
  C->>B: STOMP SUBSCRIBE /topic/operations/{id}, then /app/operations/{id} (snapshot)
  A->>A: after commit: SampleExportListener (async)
  A->>DB: UPDATE operation → RUNNING, version 2; pg_notify (same tx)
  DB-->>A: NOTIFY
  DB-->>B: NOTIFY
  B-->>C: STOMP MESSAGE {status: RUNNING, version: 2}
  A->>S3: PUT export file (outside any tx)
  A->>DB: artifact STORED; operation → SUCCEEDED, version 3, result {artifactId}; pg_notify
  B-->>C: STOMP MESSAGE {status: SUCCEEDED, version: 3, artifactId}
  C->>A: GET /api/v1/artifacts/{artifactId}/download-url
  C->>S3: GET presigned URL
```

## 4. Operation status state machine

```mermaid
stateDiagram-v2
  [*] --> QUEUED: request committed
  QUEUED --> RUNNING: listener starts
  RUNNING --> SUCCEEDED
  RUNNING --> FAILED: error after retries
  QUEUED --> FAILED: listener cannot start
  SUCCEEDED --> [*]
  FAILED --> [*]
```

`status_version` only ever increases; clients drop any message whose version is not higher than the last one seen.

## 5. ERD (seed tables)

```mermaid
erDiagram
  SAMPLE_SAMPLE_RECORD {
    uuid id PK "UUIDv7"
    text name "unique among non-deleted"
    text description
    bigint version
    timestamptz created_at
    text created_by
    timestamptz updated_at
    text updated_by
    timestamptz deleted_at
    text deleted_by
  }
  COMMON_OPERATION {
    uuid id PK
    text kind
    uuid subject_id "logical ref, no FK"
    text status
    smallint status_version
    jsonb result
  }
  COMMON_ARTIFACT {
    uuid id PK
    text module
    uuid subject_id
    text object_key
    text sha256
    text status
  }
  COMMON_IDEMPOTENCY_RECORD {
    bigint id PK "identity, never exposed"
    text idempotency_key UK
    text request_hash
  }
  SAMPLE_SAMPLE_RECORD ||..o{ COMMON_OPERATION : "subject_id, by ID across schemas"
  COMMON_OPERATION ||..o| COMMON_ARTIFACT : "result.artifactId"
```

Dotted lines are references by ID across schemas, with no foreign key. There are no local user or role tables: identities and roles come from the OIDC provider. Audit columns hold the token subject or `system:<name>`. `sample_record` is a demonstration table with no business meaning.

# Implementation roadmap

Phases are ordered by dependency. Each ends with a demonstrable exit check. CAP-n refers to SPEC.md.

| Phase | Scope | Capabilities | Exit check |
|---|---|---|---|
| **0. Approve** | Review this spec; answer the open questions (envelope shape, repository interface location, coverage thresholds, Angular upgrade plan); file ADR-01…15 in `docs/adr` | — | Spec approved |
| **1. Repository skeleton** | Monorepo tree, Gradle wrapper (Kotlin DSL), `package-lock.json`, `.editorconfig`, `.gitignore`, `.env.example`, `infrastructure/compose.yaml` (postgres, minio, mock OIDC), README, CODEOWNERS, PR template; Boot project with `config`/`common`/`security` and `feature/` root; Modulith verify and ArchUnit rule set | CAP-1 (infrastructure part), CAP-2 | Compose services healthy; `./gradlew build` green with the boundary-violation fixture failing as expected |
| **2. Cross-cutting backend** | Envelope and factory, exception hierarchy and handler, validation infrastructure, correlation filter, structured logging and masking, OpenAPI export, Actuator | CAP-3, CAP-4, CAP-5, CAP-9, CAP-15, CAP-19 | Envelope contract, masking and drift tests green |
| **3. Persistence** | Flyway layout, `migrate` profile, DB roles, auditing base classes, `AuditorAware`, `IdGenerator`, `Clock`, idempotency records, Repository Pattern rules | CAP-6, CAP-7 | Audit, soft-delete and migration ITs green |
| **4. Security** | Resource server, role mapping, `@PreAuthorize` rule, deny-by-default route test, CORS, limits | CAP-8 | Security matrix test green |
| **5. Sample feature (backend)** | `feature/sample` CRUD, restore, paging | CAP-20 (backend part) | `*ApiIT` and `*PersistenceIT` green |
| **6. Realtime and storage** | `common.operations`, `PgStatusListener`, STOMP config and auth, `common.storage` with S3 adapter, scheduled jobs; sample export | CAP-10, CAP-11 | `*StompIT` across two contexts and `*StorageIT` green |
| **7. Frontend shell** | Angular workspace, config loader, OIDC + guards, interceptors, generated client, layout, Material theme and tokens, `StateView`, realtime services | CAP-12, CAP-14 | Shell signs in through mock OIDC; budgets and lint pass |
| **8. Forms kit and sample UI** | Validators, messages, server-error mapping, `FormSubmitter`, edit mode; sample list, form and export screens | CAP-5 (frontend), CAP-13, CAP-20 | Form tests green; success signal demonstrable locally |
| **9. Quality gates and CI** | Sonar projects, coverage, Gitleaks, Trivy, SBOM, path-filtered `ci.yml` + `ci-gate`, branch protection | CAP-16, CAP-17, CAP-18 | A PR that breaks the gate is blocked; a frontend-only PR runs only frontend jobs |
| **10. Verify** | Clean-checkout run of the README; delete the sample and re-run CI; review generated code | All | Definition of done (quality-and-delivery.md §12) met |

Phases 2, 3 and 4 can run in parallel after phase 1. Phase 7 can start once phase 2's OpenAPI export exists, using a Prism mock until then. Phase 9 should start alongside phase 1 so the gates apply from the first commit.

**Risks to watch**
- springdoc 3.x versus Boot 4.1 patches (phase 2).
- Modulith detection with `feature/` as an intermediate package (phase 1).
- Angular 21 peer ranges in the OIDC and OpenAPI generator libraries (phase 7).
- WebSocket support in whatever proxy a project puts in front (phase 6).

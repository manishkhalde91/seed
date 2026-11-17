# Coding standards and development guidelines

## 1. Naming

| Item | Convention | Example |
|---|---|---|
| Packages | `com.company.seed.feature.<module>` (public API) + `.api`, `.application`, `.application.port`, `.domain`, `.infrastructure.<tech>`, `.config`; shared code in `com.company.seed.{config,common,security}` | `com.company.seed.feature.sample.infrastructure.persistence` |
| Application service | `<Noun>Service`; async handler `<Noun>Listener` | `SampleRecordService`, `SampleExportListener` |
| Repository | interface `<Aggregate>Repository` in `domain/`; `Jpa<Aggregate>Repository` in `infrastructure/persistence/` with package-private `<Aggregate>JpaEntity`, `<Aggregate>SpringDataRepository`, `<Aggregate>PersistenceMapper` | `SampleRecordRepository`, `JpaSampleRecordRepository` |
| Outbound port (non-database) | role noun | `ArtifactStorage` |
| DTOs | `<Action><Noun>Request`, `<Noun>Response`, `<Noun>Summary` | `CreateSampleRecordRequest` |
| Events | past tense, record | `SampleExportRequested` |
| Exceptions | `<Reason>Exception` extending a `common.error` base | `SampleRecordNotFoundException` |
| Config properties | `seed.<module>.<property>` (kebab-case); rename the `seed` prefix per project | `seed.storage.presign-ttl` |
| Angular | kebab-case files, `<name>.<type>.ts`, `app-` selector prefix, one class per file | `sample-list.page.ts`, `sample.store.ts` |
| Tests | Java `should<Outcome>_when<Condition>`; TS `it('should …')` | — |

## 2. Responsibilities

- Each class has one reason to change. A service handles one use case or a small cohesive set.
- An interface exists only as a boundary (repository, port) or when there are two or more real implementations. No `IFooService`/`FooServiceImpl` pairs.
- Controllers stay thin: bind, validate, call one application service, wrap the result. No business logic, no persistence.
- Domain objects own their behaviour; no anaemic entities.

## 3. Principles

- **SOLID**, pragmatically; dependency inversion through repositories and ports.
- **Clean code:** short methods; domain names; no boolean flag parameters; guard clauses; never return `null` (use `Optional` for queries, exceptions for invariants).
- **Dependency injection:** constructor injection only, single constructor, no `@Autowired`; field injection banned (ArchUnit). Inject `@ConfigurationProperties` records, not `@Value`.
- **Immutability:** DTOs, events, commands and value objects are `record`s; collections returned unmodifiable; JPA entities mutated only through intention-revealing methods.
- **Java 21:** records, sealed interfaces, pattern matching for `switch`, text blocks for SQL; no preview features.
- **No Lombok, no MapStruct by default** (MapStruct only through an ADR when a module has many mapped types).

## 4. DTO mapping

- Web DTO ↔ command/result: static `from(...)` / `toCommand()` on the record, or a small `<Noun>WebMapper` in `api/`.
- Domain ↔ JPA entity: `<Aggregate>PersistenceMapper`, used only by the repository implementation.
- Hand-written and tested. Domain objects and JPA entities never appear in a controller signature or an event.

## 5. Avoiding unnecessary abstraction

- No generic `BaseService<T>`, `CrudController<T>` or base repository.
- A new module needs a reason (owned data plus distinct rules), not just a new screen.
- New patterns need a short ADR.

## 6. Separation of concerns

- Business logic in `domain/` or `application/`; mapping and protocol code in `api/` and `infrastructure/`; cross-cutting concerns in `common` or `security`, only when genuinely cross-cutting.
- Frontend: rules live on the server; the frontend mirrors validation for usability only.

## 7. Documentation

- Each module's `package-info.java` describes its responsibility, public API and events in under 10 lines. The sample's says "DEMO — delete in real projects".
- Public interfaces and DTOs have Javadoc; OpenAPI annotations (description, example) on every endpoint and DTO field.
- Architecture decisions go in `docs/adr/NNNN-title.md` using the decisions.md template.
- The root README covers prerequisites, the one local setup path, commands, configuration and adding a feature; it marks what is implemented versus planned.

## 8. Code review

**PR template checklist**
- [ ] Scope limited to one ticket.
- [ ] Tests at the right level; new-code coverage meets the gate.
- [ ] Module and layer boundaries respected; repositories used only from `application/`.
- [ ] Envelope and error codes used; new codes documented.
- [ ] Validation on DTO and domain; frontend parity entry updated.
- [ ] Migration reviewed for compatibility, locks and grants.
- [ ] No external call inside a transaction.
- [ ] Logs: correct levels, no sensitive data.
- [ ] `@PreAuthorize` present with allowed and denied tests.
- [ ] OpenAPI and generated client regenerated.
- [ ] New dependency named and justified.

**Rules**
- Behaviour and boundaries first; formatters handle style.
- One code-owner approval; two for `common/`, `security/`, `config/` or a migration.
- PRs under about 400 changed lines, excluding generated code.
- Comments marked `blocking:` or `nit:`.

## 9. Git

- Trunk-based: short-lived `feat/…`, `fix/…`, `chore/…` branches, squash-merged into `main`.
- Conventional Commits (`feat(sample): …`).

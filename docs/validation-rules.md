# Validation rules: backend ↔ frontend parity

Every constrained request DTO has a row per field here (errors-and-logging.md §3). The backend
constraint is what the API enforces (400 `VALIDATION_FAILED`, `errors[].code` = the constraint's
simple name); the frontend validator is the form rule that mirrors it, so a user sees the same rule
before submitting. Both sides test the same accept and reject samples.

The frontend validators are **planned**: the forms kit and the sample screens arrive in a later
story (roadmap phase 8). Until then the column names the validator each field will use.

**Names** are stored without leading and trailing whitespace (the sample's domain strips them), so
`"Example"` and `"Example "` are the same name; case stays significant (`"example"` is another
name). **NUL characters** (`\u0000`), which PostgreSQL cannot store in text, fail `Pattern`.

Rules that need stored state (a unique name, a stale `version`) are not field rules: the API answers
them with 409 and the frontend shows the server's error.

## Sample records (DEMO ONLY: delete these rows with the sample module)

| DTO | Field | Backend constraint (`errors[].code`) | Frontend validator (planned) | Accept | Reject |
|---|---|---|---|---|---|
| `CreateSampleRecordRequest` | `name` | `@NotBlank` (`NotBlank`) | `Validators.required` + `notBlank` | `"Example"` | `""`, `"   "`, missing |
| `CreateSampleRecordRequest` | `name` | `@Size(max = 100)` (`Size`) | `Validators.maxLength(100)` | 100 characters | 101 characters |
| `CreateSampleRecordRequest` | `name` | `@Pattern([^\u0000]*)` (`Pattern`) | `Validators.pattern(/^[^\u0000]*$/)` | `"Example"` | `"a\u0000b"` |
| `CreateSampleRecordRequest` | `description` | `@Size(max = 1000)` (`Size`); optional | `Validators.maxLength(1000)` | missing, `""`, 1000 characters | 1001 characters |
| `CreateSampleRecordRequest` | `description` | `@Pattern([^\u0000]*)` (`Pattern`) | `Validators.pattern(/^[^\u0000]*$/)` | `"text"` | `"a\u0000b"` |
| `UpdateSampleRecordRequest` | `name` | `@NotBlank` (`NotBlank`) | `Validators.required` + `notBlank` | `"Example"` | `""`, `"   "`, missing |
| `UpdateSampleRecordRequest` | `name` | `@Size(max = 100)` (`Size`) | `Validators.maxLength(100)` | 100 characters | 101 characters |
| `UpdateSampleRecordRequest` | `name` | `@Pattern([^\u0000]*)` (`Pattern`) | `Validators.pattern(/^[^\u0000]*$/)` | `"Example"` | `"a\u0000b"` |
| `UpdateSampleRecordRequest` | `description` | `@Size(max = 1000)` (`Size`); optional, and an omitted or `null` value clears it (full replace) | `Validators.maxLength(1000)` | missing, `""`, 1000 characters | 1001 characters |
| `UpdateSampleRecordRequest` | `description` | `@Pattern([^\u0000]*)` (`Pattern`) | `Validators.pattern(/^[^\u0000]*$/)` | `"text"` | `"a\u0000b"` |
| `UpdateSampleRecordRequest` | `version` | `@NotNull` (`NotNull`), `@PositiveOrZero` (`PositiveOrZero`) | none: a hidden field holding the `version` the form loaded | `0`, `3` | missing, `-1` |

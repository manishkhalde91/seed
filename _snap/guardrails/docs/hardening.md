# Package-manager hardening (optional)

Rule SLOP-DEP-09 already checks every package added or changed in a lockfile: that it
exists, is not brand new, is not a look-alike of a popular name, and is not known to be
malicious. The settings below add a second layer inside the package manager itself.

**Snap never applies these for you.** Each one changes how the whole team, and the CI,
install packages, and some break common projects. Add one only after the team agrees, and
test a clean install first.

## No install scripts from dependencies

Stops a package from running code on your machine while it installs.

| Tool | Setting | Where | Since |
|---|---|---|---|
| npm | `ignore-scripts=true` | `.npmrc` | all versions |
| pnpm | blocked by default; allow by name with `allowBuilds: { esbuild: true }`; `strictDepBuilds: true` fails on unreviewed ones | `pnpm-workspace.yaml` | 10.0 (`allowBuilds` 10.26, `strictDepBuilds` 10.3) |
| Yarn 2+ | `enableScripts: false` | `.yarnrc.yml` | — |

Breaks: packages that build or download a binary on install (esbuild, sharp, prisma,
bcrypt, …) and npm's own `prepare` / `pre*` / `post*` scripts (husky, for example). With npm
you then rebuild the needed ones by name: `npm rebuild esbuild`.

## Only versions older than 7 days

Most hijacked versions are found and pulled within days.

| Tool | Setting | Where | Since |
|---|---|---|---|
| npm | `min-release-age=7` (days) | `.npmrc` | 11.10 |
| pnpm | `minimumReleaseAge: 10080` (minutes) | `pnpm-workspace.yaml` | 10.16 |
| Yarn | `npmMinimalAgeGate: 7d` (keep the unit; a bare number means minutes) | `.yarnrc.yml` | 4.10 |
| uv | `exclude-newer = "7 days"` | `[tool.uv]` in `pyproject.toml`, or `uv.toml` | 0.9.17 |

Breaks: an urgent security fix cannot be installed for 7 days unless you exclude that
package (pnpm `minimumReleaseAgeExclude`, npm `min-release-age-exclude`, Yarn
`npmPreapprovedPackages`, uv `exclude-newer-package`).

## Install only what the lockfile says, in CI

| Tool | Command |
|---|---|
| npm | `npm ci` |
| pnpm | `pnpm install --frozen-lockfile` |
| Yarn 2+ | `yarn install --immutable` |
| uv | `uv sync --locked` (or `uv lock --check`) |
| Poetry | `poetry check --lock` (1.6+) |
| Go | `go mod tidy -diff` (Go 1.23+) shows drift without changing files |
| .NET | `RestorePackagesWithLockFile` = `true` in `Directory.Build.props`, then `dotnet restore --locked-mode` |
| Gradle | dependency locking (`gradle.lockfile`); a normal build fails when resolution differs |

Use these in the project's existing build job. They need a lockfile; adding one to a
project that has none is a team decision.

## .NET: package source mapping

In `nuget.config` (NuGet 6.0 / .NET SDK 6.0.100+), each package pattern must come from a
named source. This also stops a public package from standing in for a private one.

```xml
<packageSourceMapping>
  <packageSource key="nuget.org">
    <package pattern="*" />
  </packageSource>
  <packageSource key="company-feed">
    <package pattern="Company.*" />
  </packageSource>
</packageSourceMapping>
```

Breaks: restore fails for any package that matches no source. List every private feed the
project uses before turning it on. Pin the SDK in `global.json` when you use locked mode.

Sources: npm, pnpm, Yarn, uv, Poetry, Go, Gradle and Microsoft NuGet documentation, checked
21 October 2026.

#!/usr/bin/env python3
# /// script
# requires-python = ">=3.11"
# ///
"""Resolve which context-lake files a Snap skill should load for a job.

The lake is written by SnapCT and read in place by Snap (contract 2.0.0). This script
reads `context_lake` from the merged central config, reads the lake's `lake.yaml`, and
prints a JSON description of the files to load for one load profile:

    resolve_artifacts.py -p <project-root> --profile rules
    resolve_artifacts.py -p <project-root> --profile overview
    resolve_artifacts.py -p <project-root> --profile requirements
    resolve_artifacts.py -p <project-root> --profile feature=refunds
    resolve_artifacts.py -p <project-root> --profile feature --paths services/refunds/validate.ts

`--profile feature` with `--paths` derives the feature from the touched paths: first from the
`source:` paths cited on each feature page, then from the modules the page links, then from a
path segment that matches the feature slug.

A lake with no `lake.yaml` is a legacy lake: the flat `prd.md`, `architecture.md` and
`modules/index.md` are returned with `contract: "legacy"` and a warning. No lake at all
returns an empty list and exit code 0, so skills carry on without grounding.

Standard library only. Exit codes: 0 ok (including "no lake") · 1 config or usage error.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

sys.dont_write_bytecode = True

try:
    from config_utils import ConfigError, load_central_config
except ModuleNotFoundError as error:
    if error.name != "tomllib":
        raise
    sys.stderr.write("error: Python 3.11+ is required (stdlib `tomllib` not found).\n")
    raise SystemExit(3) from None

DEFAULT_LAKE = "{project-root}/_snap-output/context"
CHARS_PER_TOKEN = 4
DEFAULT_BUDGETS = {"rules": 1500, "feature": 6000, "overview": 10000}
DEFAULT_OVERVIEW = ["prd/index.md", "prd/personas/", "prd/product-journeys/",
                    "prd/product-rules.md", "prd/nfr.md"]
RULE_ID = re.compile(r"\b(?:BR|VAL)-\d+\b")
LINE_START = re.compile(r"^- ((?:FR|UJ|NFR|SM|BR|VAL|SC|ST|Q|D)-\d+)\b")
VERIFIED = re.compile(r"^([0-9a-f]{4,40})\s+by\s+\S")


# ── lake.yaml ────────────────────────────────────────────────────────────────

def _unquote(value: str) -> str:
    value = value.split(" #", 1)[0].strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
        return value[1:-1]
    return value


def read_lake_yaml(lake: Path) -> dict | None:
    """The slice of lake.yaml the resolver needs: contract, source_commits, profiles."""
    try:
        text = (lake / "lake.yaml").read_text(encoding="utf-8")
    except OSError:
        return None
    out: dict = {"contract": "", "source_commits": {}, "profiles": {}}
    block = None
    for line in text.splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if not line.startswith((" ", "\t")):
            match = re.match(r"^([A-Za-z_][\w-]*)\s*:\s*(.*)$", line)
            if not match:
                continue
            key, value = match.group(1), match.group(2)
            block = key if key in ("source_commits", "profiles") else None
            if key == "contract":
                out["contract"] = _unquote(value)
            continue
        if block == "source_commits":
            match = re.match(r"^\s+([^\s:]+)\s*:\s*(.*)$", line)
            if match:
                out["source_commits"][_unquote(match.group(1))] = _unquote(match.group(2))
        elif block == "profiles":
            match = re.match(r"^\s+(\w+)\s*:\s*\{(.*)\}\s*$", line)
            if match:
                out["profiles"][match.group(1)] = _parse_profile(match.group(2))
    return out


def _parse_profile(body: str) -> dict:
    profile: dict = {}
    budget = re.search(r"budget\s*:\s*(\d+)", body)
    if budget:
        profile["budget"] = int(budget.group(1))
    files = re.search(r"files\s*:\s*\[(.*?)\]", body)
    if files:
        profile["files"] = [f.strip().strip("\"'") for f in files.group(1).split(",") if f.strip()]
    return profile


# ── config ───────────────────────────────────────────────────────────────────

def lake_root(project_root: Path) -> Path:
    merged = load_central_config(project_root)
    configured = (merged.get("core") or {}).get("context_lake") or DEFAULT_LAKE
    configured = configured.replace("{project-root}", str(project_root))
    path = Path(configured)
    if not path.is_absolute():
        path = project_root / path
    return path.resolve()


# ── file helpers ─────────────────────────────────────────────────────────────

def tokens(text: str) -> int:
    return -(-len(text) // CHARS_PER_TOKEN)


def read(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except OSError:
        return ""


def expand(lake: Path, entries: list[str]) -> list[Path]:
    """Profile entries are files or folders; a folder contributes its markdown, sorted."""
    out: list[Path] = []
    for entry in entries:
        path = lake / entry
        if entry.endswith("/") or path.is_dir():
            out.extend(sorted(p for p in path.rglob("*.md") if p.is_file()))
        elif path.is_file():
            out.append(path)
    return out


def feature_pages(lake: Path) -> dict[str, list[Path]]:
    """slug -> pages. features/x.md and features/x/index.md are the same feature; a
    promoted folder contributes all its markdown, index first."""
    pages: dict[str, list[Path]] = {}
    features = lake / "features"
    if not features.is_dir():
        return pages
    for entry in sorted(features.iterdir()):
        if entry.name.startswith(("_", ".")):
            continue
        if entry.is_file() and entry.suffix == ".md":
            pages[entry.stem] = [entry]
        elif entry.is_dir() and (entry / "index.md").is_file():
            parts = [entry / "index.md"] + sorted(
                p for p in entry.rglob("*.md") if p != entry / "index.md")
            pages[entry.name] = parts
    return pages


# ── feature from paths ───────────────────────────────────────────────────────

def sources_of(text: str) -> list[str]:
    found = []
    for match in re.finditer(r"^\s+source:\s*(\S+)", text, re.M):
        value = re.split(r":\d", match.group(1), 1)[0]
        if value and not value.startswith("["):
            found.append(value)
    return found


def modules_of(text: str) -> list[str]:
    match = re.search(r"\*\*Modules\*\*\s*\n((?:- .*\n?)+)", text)
    if not match:
        return []
    return [m.group(1).strip().lower() for m in re.finditer(r"^- ([^\s—-][^—\n]*?)\s*(?:—|$)", match.group(1), re.M)]


def derive_feature(lake: Path, pages: dict[str, list[Path]], touched: list[str]) -> tuple[str | None, list[str]]:
    """Best feature for the touched paths, and every feature that scored. Ownership comes
    from the lake itself: cited sources beat linked modules beat a slug in the path."""
    scores: dict[str, int] = {}
    for slug, files in pages.items():
        text = "\n".join(read(f) for f in files)
        cited = sources_of(text)
        modules = modules_of(text)
        score = 0
        for raw in touched:
            path = raw.strip().lstrip("./")
            parts = [p.lower() for p in Path(path).parts]
            directory = str(Path(path).parent)
            if path in cited:
                score += 10
            elif any(directory and Path(c).parent.as_posix() == directory for c in cited):
                score += 6
            if any(m in parts for m in modules):
                score += 4
            if slug.lower() in parts or slug.lower() in Path(path).stem.lower():
                score += 2
        if score:
            scores[slug] = score
    ranked = sorted(scores, key=lambda s: (-scores[s], s))
    return (ranked[0] if ranked else None), ranked


# ── staleness (mirrors snapct check_lake.py --stale) ─────────────────────────

def git(root: Path, *args: str) -> tuple[int, str]:
    try:
        proc = subprocess.run(["git", "-C", str(root), *args], capture_output=True,
                              text=True, timeout=30)
        return proc.returncode, proc.stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return 1, ""


def code_roots(project_root: Path, lake_info: dict | None) -> list[Path]:
    """Where `source:` paths resolve: the project root, then a sibling folder per repo the
    lake names in source_commits (multi-repo systems)."""
    roots = [project_root]
    for name in (lake_info or {}).get("source_commits", {}):
        for candidate in (project_root / name, project_root.parent / name):
            if candidate.is_dir() and candidate not in roots:
                roots.append(candidate)
    return [r for r in roots if git(r, "rev-parse", "--git-dir")[0] == 0]


def stale_lines(files: list[Path], roots: list[Path]) -> tuple[int, int]:
    """(stale, checked) over the lines in `files`. Stale: the `verified` commit is older than
    the last commit touching `source`, or no longer exists on the branch."""
    if not roots:
        return 0, 0
    last_touch: dict[tuple[str, str], str] = {}
    stale = checked = 0
    for file in files:
        text = read(file)
        retired_at = text.find("\n## Retired")
        body = text if retired_at < 0 else text[:retired_at]
        for block in re.split(r"\n(?=- (?:[A-Z]+-\d+)\b)", body):
            if not LINE_START.match(block.lstrip("\n")):
                continue
            source = re.search(r"^\s+source:\s*(\S+)", block, re.M)
            verified = re.search(r"^\s+verified:\s*(.+)$", block, re.M)
            if not source or not verified or source.group(1).startswith("["):
                continue
            match = VERIFIED.match(verified.group(1).strip())
            if not match:
                continue
            sha, path = match.group(1), re.split(r":\d", source.group(1), 1)[0]
            checked += 1
            hit = None
            for root in roots:
                key = (str(root), path)
                if key not in last_touch:
                    rc, out = git(root, "log", "-1", "--format=%H", "--", path)
                    last_touch[key] = out if rc == 0 else ""
                if last_touch[key]:
                    hit = (root, last_touch[key])
                    break
            if hit is None:
                checked -= 1
                continue
            root, touched = hit
            if git(root, "cat-file", "-e", f"{sha}^{{commit}}")[0] != 0:
                stale += 1
            elif git(root, "merge-base", "--is-ancestor", touched, sha)[0] != 0:
                stale += 1
    return stale, checked


# ── profiles ─────────────────────────────────────────────────────────────────

def build(project_root: Path, profile: str, touched: list[str]) -> dict:
    lake = lake_root(project_root)
    result: dict = {
        "lake": str(lake), "profile": profile, "contract": None, "source_commits": {},
        "stale_count": 0, "files": [], "feature": None, "rule_ids": [],
        "tokens": 0, "budget": None, "over_budget": False, "warnings": [],
    }
    kind, _, slug = profile.partition("=")
    result["profile"] = kind
    if kind not in ("rules", "overview", "feature", "requirements"):
        raise ValueError(f"unknown profile '{profile}' (rules|overview|feature=<slug>|requirements)")

    agents = project_root / "AGENTS.md"
    if kind == "rules":
        result["budget"] = DEFAULT_BUDGETS["rules"]
        if agents.is_file():
            result["files"] = [str(agents)]
            result["tokens"] = tokens(read(agents))
        return finish(result)

    if not lake.is_dir():
        # No lake is a normal setup (Snap works without SnapCT): no warning, nothing to surface.
        return finish(result)

    info = read_lake_yaml(lake)
    if info is None:
        return legacy(result, lake)

    result["contract"] = info["contract"]
    result["source_commits"] = info["source_commits"]
    profiles = info["profiles"]
    result["budget"] = (profiles.get(kind) or {}).get("budget", DEFAULT_BUDGETS.get(kind))
    pages = feature_pages(lake)
    chosen: list[Path] = []

    overview = expand(lake, (profiles.get("overview") or {}).get("files") or DEFAULT_OVERVIEW)

    if kind == "overview":
        chosen = overview
        result["tokens"] = sum(tokens(read(p)) for p in chosen)
    elif kind == "requirements":
        chosen = overview + [p for files in pages.values() for p in files]
        result["tokens"] = sum(tokens(read(p)) for p in chosen)
    else:  # feature
        if not slug:
            slug, ranked = derive_feature(lake, pages, touched)
            if not touched:
                result["warnings"].append("--profile feature needs a slug or --paths")
            elif slug is None:
                result["warnings"].append(
                    "no feature owns the touched paths; load the overview profile instead")
            elif len(ranked) > 1:
                result["warnings"].append("other features matched: " + ", ".join(ranked[1:]))
        if slug and slug not in pages:
            result["warnings"].append(f"no feature page for '{slug}'")
            slug = None
        if slug:
            result["feature"] = slug
            page_text = "\n".join(read(p) for p in pages[slug])
            chosen = list(pages[slug])
            used = tokens(page_text)
            rules = lake / "prd" / "product-rules.md"
            live_text = page_text.split("\n## Retired", 1)[0]
            ids = sorted(set(RULE_ID.findall(live_text)), key=lambda i: (i.split("-")[0], int(i.split("-")[1])))
            if rules.is_file():
                result["rule_ids"] = ids
                lines = [ln for ln in read(rules).splitlines() if RULE_ID.search(ln)
                         and any(i in ln for i in ids)]
                if lines:
                    chosen.append(rules)
                    used += tokens("\n".join(lines))
            candidates = lake / "agents-candidates.md"
            if candidates.is_file():
                chosen.append(candidates)
                used += tokens(read(candidates))
            result["tokens"] = used

    result["files"] = [str(p) for p in dict.fromkeys(chosen)]
    roots = code_roots(project_root, info)
    stale_files = [p for files in pages.values() for p in files]
    result["stale_count"] = stale_lines(stale_files, roots)[0]
    if result["budget"] and result["tokens"] > result["budget"]:
        result["over_budget"] = True
        result["warnings"].append(
            f"{kind} profile is about {result['tokens']} tokens, over its {result['budget']} budget")
    return finish(result)


def legacy(result: dict, lake: Path) -> dict:
    result["contract"] = "legacy"
    result["warnings"].append(
        f"no lake.yaml in {lake}: legacy lake, falling back to flat files. Run `snapct migrate`.")
    for name in ("prd.md", "architecture.md", "modules/index.md"):
        path = lake / name
        if path.is_file():
            result["files"].append(str(path))
            result["tokens"] += tokens(read(path))
    return finish(result)


def finish(result: dict) -> dict:
    result["files"] = [str(f) for f in result["files"]]
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description="Resolve context-lake files for a Snap skill.")
    parser.add_argument("--project-root", "-p", required=True,
                        help="Absolute project root containing _snap/")
    parser.add_argument("--profile", required=True,
                        help="rules | overview | requirements | feature=<slug> | feature (with --paths)")
    parser.add_argument("--paths", nargs="*", default=[],
                        help="paths the change touches, used to find the owning feature")
    args = parser.parse_args()

    try:
        result = build(Path(args.project_root).resolve(), args.profile, args.paths)
    except (ConfigError, ValueError) as error:
        sys.stderr.write(f"error: {error}\n")
        return 1
    reconfigure = getattr(sys.stdout, "reconfigure", None)
    if reconfigure is not None:
        reconfigure(encoding="utf-8")
    sys.stdout.write(json.dumps(result, indent=2, ensure_ascii=False) + "\n")
    for warning in result["warnings"]:
        sys.stderr.write(f"warning: {warning}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

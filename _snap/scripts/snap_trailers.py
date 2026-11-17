#!/usr/bin/env python3
"""Save the Snap details for the next commit, so the commit message can carry them.

Snap builds call this right before `git commit` (after `git add`):

    snap_trailers.py set --skill snap-build --story AS-1423 --tech-spec _snap-output/spec-refunds.md
    snap_trailers.py set --skill snap-spec --spec _snap-output/specs/spec-refunds/
    snap_trailers.py set --skill snap-build --prompt "add a refund button to the order page"
    snap_trailers.py set --skill snap-build --story AS-1423 --repo ../frontend
    snap_trailers.py clear [--repo ../frontend]

`--repo <folder>` names the git repo (or worktree) that will be committed. Without it, the
current folder is used. In a multi-repo project, run `set` once for each repo you staged files
in: each repo has its own staged files, its own saved file and its own commit.

`set` needs at least one of --spec, --tech-spec, --story, --requirement, --prompt. It writes a small file inside
the git folder (never committed) holding the trailer lines and the tree hash of the staged
files. The git hook `_snap/guardrails/prepare-commit-msg.sh` adds the lines to the commit
message only when the tree of that commit is the same, so old data never lands on another
commit.

Trailers written:
    Snap-Intent  name=value pairs, only the ones given, in this order:
                 spec=<slug> tech-spec=<slug> story=<id> requirement=<id> prompt=<slug>
                 e.g.  Snap-Intent: spec=refunds tech-spec=partial-refunds story=AS-1423
    Snap-Agent   <skill>@<snap version from _snap/_config/manifest.yaml>
    Snap-Model   --model, or "no-model-found"

--spec and --tech-spec take a path or a name: the script reduces it to a slug (the file or
folder name without .md and without a leading "spec-"; SPEC.md means its folder).
--prompt takes the plain text and the script makes a short slug of it. A commit message
stays in git history for good, so give a short title, never client names or secrets.

Standard library only. Exit codes: 0 ok (also when there is no git repo or the hook is
missing: Snap data is a record, never a reason to stop a build) · 2 bad input.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

sys.dont_write_bytecode = True

NO_MODEL = "no-model-found"
FILE_NAME = "snap-trailers"
SKILL = re.compile(r"^[a-z][a-z0-9-]{0,63}$")
MODEL = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:/\[\]-]{0,99}$")
MAX_VALUE = 300
MAX_SLUG = 60
ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._#-]{0,39}$")


def git(*args: str, cwd: Path | None = None) -> tuple[bool, str]:
    try:
        run = subprocess.run(["git", *args], cwd=cwd, capture_output=True, text=True)
    except OSError:
        return False, ""
    return run.returncode == 0, run.stdout.strip()


def clean(label: str, value: str | None) -> str | None:
    """One line, no control characters. Returns None when the value was not given."""
    if value is None:
        return None
    value = value.strip()
    if not value:
        return None
    if len(value) > MAX_VALUE or any(ord(ch) < 32 or ord(ch) == 127 for ch in value):
        sys.stderr.write(f"snap_trailers: {label} must be one line of at most {MAX_VALUE} characters.\n")
        raise SystemExit(2)
    return value


def slugify(text: str) -> str:
    slug = re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")
    if len(slug) > MAX_SLUG:
        slug = slug[:MAX_SLUG].rsplit("-", 1)[0] or slug[:MAX_SLUG]
    return slug


def spec_slug(label: str, value: str | None) -> str | None:
    """A path or a name in, a short slug out: `_snap-output/specs/spec-refunds/SPEC.md` -> `refunds`."""
    value = clean(label, value)
    if value is None:
        return None
    path = Path(value.rstrip("/"))
    name = path.parent.name if path.name.upper() == "SPEC.md".upper() and path.parent.name else path.name
    if name.lower().endswith(".md"):
        name = name[:-3]
    slug = slugify(re.sub(r"^spec-", "", name, flags=re.I))
    if not slug:
        sys.stderr.write(f"snap_trailers: could not make a name from {label} {value!r}.\n")
        raise SystemExit(2)
    return slug


def identifier(label: str, value: str | None) -> str | None:
    value = clean(label, value)
    if value is None:
        return None
    if not ID.match(value):
        sys.stderr.write(f"snap_trailers: {label} must look like an id such as AS-1423 or FR-12.\n")
        raise SystemExit(2)
    return value


def snap_version(root: Path) -> str | None:
    """The Snap version. This script lives in <_snap>/scripts, so its own `_snap` folder is
    tried first (it is the right one when `_snap` is outside the repo), then <repo>/_snap."""
    candidates = [
        Path(__file__).resolve().parents[1] / "_config" / "manifest.yaml",
        root / "_snap" / "_config" / "manifest.yaml",
    ]
    for manifest in candidates:
        try:
            text = manifest.read_text(encoding="utf8")
        except OSError:
            continue
        top = re.search(r"^snap-version:\s*['\"]?([^\s'\"#]+)", text, re.M)
        if top:
            return top.group(1)
        nested = re.search(r"^installation:\s*\n(?:[ \t]+.*\n)*?[ \t]+version:\s*['\"]?([^\s'\"#]+)", text, re.M)
        if nested:
            return nested.group(1)
    return None


def hook_installed(root: Path) -> bool:
    ok, path = git("rev-parse", "--git-path", "hooks/prepare-commit-msg", cwd=root)
    if not ok:
        return False
    hook = Path(path) if Path(path).is_absolute() else root / path
    try:
        text = hook.read_text(encoding="utf8")
        return "prepare-commit-msg.sh" in text or "snap-guardrails-shim" in text
    except OSError:
        return False


def trailer_file(root: Path) -> Path | None:
    ok, path = git("rev-parse", "--git-path", FILE_NAME, cwd=root)
    if not ok:
        return None
    return Path(path) if Path(path).is_absolute() else root / path


def find_root(repo: str | None) -> Path | None:
    """The top folder of the repo to commit: --repo if given, else the current folder."""
    ok, top = git("rev-parse", "--show-toplevel", cwd=Path(repo) if repo else None)
    return Path(top) if ok else None


def cmd_set(args: argparse.Namespace) -> int:
    pairs = []
    spec = spec_slug("--spec", args.spec)
    tech_spec = spec_slug("--tech-spec", args.tech_spec)
    story = identifier("--story", args.story)
    requirement = identifier("--requirement", args.requirement)
    prompt = slugify(args.prompt or "") if clean("--prompt", args.prompt) else None
    for name, value in (("spec", spec), ("tech-spec", tech_spec), ("story", story),
                        ("requirement", requirement), ("prompt", prompt)):
        if value:
            pairs.append(f"{name}={value}")
    if not pairs:
        sys.stderr.write("snap_trailers: give at least one of --spec, --tech-spec, --story, --requirement or --prompt.\n")
        return 2
    if not SKILL.match(args.skill or ""):
        sys.stderr.write("snap_trailers: --skill must be a skill name such as snap-build.\n")
        return 2

    model = (args.model or "").strip()
    if not MODEL.match(model):
        if model:
            sys.stderr.write(f"snap_trailers: --model value not usable, writing {NO_MODEL}.\n")
        model = NO_MODEL

    root = find_root(args.repo)
    if root is None:
        where = f"{args.repo} is" if args.repo else "this folder is"
        sys.stderr.write(f"snap_trailers: {where} not a git repository, so there is no commit to record. Continuing.\n")
        return 0

    ok, tree = git("write-tree", cwd=root)
    if not ok:
        sys.stderr.write("snap_trailers: could not read the staged files (unfinished merge?). Nothing recorded.\n")
        return 0
    target = trailer_file(root)
    if target is None:
        return 0

    version = snap_version(root)
    lines = [f"Snap-Intent: {' '.join(pairs)}"]
    lines.append(f"Snap-Agent: {args.skill}@{version}" if version else f"Snap-Agent: {args.skill}")
    lines.append(f"Snap-Model: {model}")

    target.write_text(f"tree {tree}\n" + "\n".join(lines) + "\n", encoding="utf8")
    print("snap_trailers: saved for the next commit:")
    for line in lines:
        print(f"  {line}")
    if not hook_installed(root):
        sys.stderr.write(
            "snap_trailers: the Snap commit hook is not installed in this clone, so the commit\n"
            "  message will not carry these lines. Re-run the Snap installer to add it.\n"
        )
    return 0


def cmd_clear(args: argparse.Namespace) -> int:
    root = find_root(args.repo)
    target = trailer_file(root) if root else None
    if target is not None:
        target.unlink(missing_ok=True)
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Save Snap details for the next commit message.")
    sub = parser.add_subparsers(dest="command", required=True)

    one = sub.add_parser("set", help="save the details; run after `git add`, right before `git commit`")
    one.add_argument("--skill", required=True, help="the Snap skill, e.g. snap-build or snap-build-auto")
    one.add_argument("--spec", help="the snap-spec spec (folder, SPEC.md path or name) this work came from")
    one.add_argument("--tech-spec", dest="tech_spec", help="the technical spec file snap-build wrote for this work")
    one.add_argument("--story", help="story id, e.g. AS-1423")
    one.add_argument("--requirement", help="requirement id, e.g. FR-12")
    one.add_argument("--prompt", help="free-text request, as a short title (no client names or secrets)")
    one.add_argument("--model", help=f"model id; default {NO_MODEL}")
    one.add_argument("--repo", help="the git repo (or worktree) to commit in; default: the current folder")
    one.set_defaults(run=cmd_set)

    two = sub.add_parser("clear", help="forget the saved details")
    two.add_argument("--repo", help="the git repo (or worktree); default: the current folder")
    two.set_defaults(run=cmd_clear)

    args = parser.parse_args(argv)
    return args.run(args)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))

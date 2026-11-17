# /// script
# requires-python = ">=3.11"
# dependencies = ["pytest>=8.0"]
# ///
"""Tests for resolve_artifacts.py — which context-lake files a skill loads.

Run: uv run --with pytest -m pytest overrides/_snap/scripts/tests/test_resolve_artifacts.py
"""

import importlib.util
import json
import subprocess
import sys
from pathlib import Path

import pytest

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
# In snap-forge the sibling config_utils.py is built into dist/, not kept beside the override.
sys.path.append(str(SCRIPTS.parents[2] / "dist" / "_snap" / "scripts"))
spec = importlib.util.spec_from_file_location("resolve_artifacts", SCRIPTS / "resolve_artifacts.py")
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

LAKE_YAML = """\
contract: "2.0.0"
snapct_version: "3.2.0"
generated: 2026-10-01
branch: develop
source_commits:
  app: "{sha}"
features: features/
profiles:
  rules: {{ budget: 1500 }}
  feature: {{ budget: {feature_budget} }}
  overview: {{ budget: 10000, files: [prd/index.md, prd/product-rules.md] }}
  requirements: {{ files: [overview, features/] }}
"""

REFUNDS = """\
---
type: Feature
title: "Refunds"
slug: refunds
---

# Refunds

## 4. Functional requirements

#### FR-1: Refund a delivered order
A buyer can ask for a refund.

Rules: BR-112

## 5. Business rules this feature owns

- BR-112 Refunds are allowed within 45 days of delivery
  feature: refunds
  source: services/refunds/validate.ts:41
  verified: {sha} by refresh
  confirmed: needs-confirmation

## 11. Entry points and modules

**Modules**
- refunds — [refunds](../modules/refunds.md)

## Retired

- BR-9 Old rule
  feature: refunds
  source: services/refunds/old.ts:1
  verified: deadbeef by refresh
  confirmed: needs-confirmation
"""

BRANCH = """\
---
type: Feature
title: "Branch"
slug: branch
---

# Branch

## 5. Business rules this feature owns

- BR-7 Branches are never written outside services/branch
  feature: branch
  source: services/branch/write.ts:3
  verified: {sha} by refresh
  confirmed: needs-confirmation
"""

RULES = """\
# Product rules

- BR-112 Refunds are allowed within 45 days of delivery
  feature: refunds
- BR-500 An unrelated rule that is long and should not be counted for the refunds feature
  feature: other
"""


def git(root, *args):
    subprocess.run(["git", "-C", str(root), *args], check=True, capture_output=True)


def commit(root, message):
    git(root, "add", "-A")
    git(root, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", message)
    return subprocess.run(["git", "-C", str(root), "rev-parse", "HEAD"], check=True,
                          capture_output=True, text=True).stdout.strip()


@pytest.fixture
def project(tmp_path):
    """A git project with services/refunds, services/branch and a v2 lake."""
    root = tmp_path / "proj"
    (root / "_snap").mkdir(parents=True)
    (root / "_snap" / "config.toml").write_text('[core]\noutput_folder = "_snap-output"\n')
    for path in ("services/refunds/validate.ts", "services/branch/write.ts"):
        (root / path).parent.mkdir(parents=True, exist_ok=True)
        (root / path).write_text("// code\n")
    git(root, "init", "-q")
    sha = commit(root, "code")
    lake = root / "_snap-output" / "context"
    (lake / "features").mkdir(parents=True)
    (lake / "prd").mkdir()
    (lake / "lake.yaml").write_text(LAKE_YAML.format(sha=sha, feature_budget=6000))
    (lake / "features" / "refunds.md").write_text(REFUNDS.format(sha=sha))
    (lake / "features" / "branch.md").write_text(BRANCH.format(sha=sha))
    (lake / "prd" / "index.md").write_text("# PRD\n")
    (lake / "prd" / "product-rules.md").write_text(RULES)
    (lake / "agents-candidates.md").write_text("- Never write branch outside services/branch\n")
    (root / "AGENTS.md").write_text("# Rules\n")
    return root, lake, sha


def names(result):
    return [Path(f).relative_to(result["lake"]).as_posix() if f.startswith(result["lake"])
            else Path(f).name for f in result["files"]]


def test_feature_by_slug_returns_page_rules_and_candidates(project):
    root, _, _ = project
    result = mod.build(root, "feature=refunds", [])
    assert result["feature"] == "refunds"
    assert result["contract"] == "2.0.0"
    assert names(result) == ["features/refunds.md", "prd/product-rules.md", "agents-candidates.md"]
    assert result["rule_ids"] == ["BR-112"]
    assert not result["over_budget"]


def test_feature_derived_from_touched_path(project):
    root, _, _ = project
    result = mod.build(root, "feature", ["services/refunds/validate.ts"])
    assert result["feature"] == "refunds"
    assert result["profile"] == "feature"


def test_feature_derived_from_a_sibling_file_in_a_cited_directory(project):
    root, _, _ = project
    result = mod.build(root, "feature", ["services/branch/read.ts"])
    assert result["feature"] == "branch"


def test_feature_with_unowned_path_warns_and_returns_nothing(project):
    root, _, _ = project
    result = mod.build(root, "feature", ["docs/readme.md"])
    assert result["feature"] is None
    assert result["files"] == []
    assert any("no feature owns" in w for w in result["warnings"])


def test_unknown_feature_slug_warns(project):
    root, _, _ = project
    result = mod.build(root, "feature=ghost", [])
    assert result["files"] == []
    assert any("no feature page for 'ghost'" in w for w in result["warnings"])


def test_promoted_feature_folder_is_the_same_feature(project):
    root, lake, _ = project
    (lake / "features" / "refunds.md").unlink()
    folder = lake / "features" / "refunds"
    folder.mkdir()
    (folder / "index.md").write_text("# Refunds\n\nRules: BR-112\n")
    (folder / "rules.md").write_text("# Rules\n")
    result = mod.build(root, "feature=refunds", [])
    assert names(result)[:2] == ["features/refunds/index.md", "features/refunds/rules.md"]


def test_overview_uses_the_files_named_in_lake_yaml(project):
    root, _, _ = project
    result = mod.build(root, "overview", [])
    assert names(result) == ["prd/index.md", "prd/product-rules.md"]
    assert result["budget"] == 10000


def test_requirements_is_overview_plus_every_feature_page(project):
    root, _, _ = project
    result = mod.build(root, "requirements", [])
    assert names(result) == ["prd/index.md", "prd/product-rules.md",
                             "features/branch.md", "features/refunds.md"]


def test_rules_profile_is_agents_md_only(project):
    root, _, _ = project
    result = mod.build(root, "rules", [])
    assert [Path(f).name for f in result["files"]] == ["AGENTS.md"]
    assert result["budget"] == 1500


def test_budget_override_in_lake_yaml_is_honoured(project):
    root, lake, sha = project
    (lake / "lake.yaml").write_text(LAKE_YAML.format(sha=sha, feature_budget=10))
    result = mod.build(root, "feature=refunds", [])
    assert result["over_budget"] is True
    assert any("over its 10 budget" in w for w in result["warnings"])


def test_no_lake_returns_empty_list_and_exit_zero(tmp_path, capsys, monkeypatch):
    root = tmp_path / "bare"
    (root / "_snap").mkdir(parents=True)
    (root / "_snap" / "config.toml").write_text('[core]\noutput_folder = "_snap-output"\n')
    monkeypatch.setattr(sys, "argv", ["x", "-p", str(root), "--profile", "overview"])
    assert mod.main() == 0
    out = json.loads(capsys.readouterr().out)
    assert out["files"] == []
    assert out["warnings"] == []


def test_lake_without_lake_yaml_falls_back_to_flat_files(project):
    root, lake, _ = project
    (lake / "lake.yaml").unlink()
    (lake / "prd.md").write_text("# PRD\n")
    (lake / "architecture.md").write_text("# Arch\n")
    result = mod.build(root, "overview", [])
    assert result["contract"] == "legacy"
    assert names(result) == ["prd.md", "architecture.md"]
    assert any("legacy lake" in w for w in result["warnings"])


def test_context_lake_key_in_custom_config_wins(project, tmp_path):
    root, lake, _ = project
    elsewhere = tmp_path / "workspace" / "ctx"
    elsewhere.parent.mkdir()
    lake.rename(elsewhere)
    custom = root / "_snap" / "custom"
    custom.mkdir()
    (custom / "config.toml").write_text(f'[core]\ncontext_lake = "{elsewhere}"\n')
    result = mod.build(root, "overview", [])
    assert result["lake"] == str(elsewhere.resolve())
    assert result["contract"] == "2.0.0"


def test_stale_count_flags_a_line_whose_source_changed_after_it_was_verified(project):
    root, _, _ = project
    assert mod.build(root, "overview", [])["stale_count"] == 0
    (root / "services" / "refunds" / "validate.ts").write_text("// changed\n")
    commit(root, "change a cited file")
    assert mod.build(root, "overview", [])["stale_count"] == 1


def test_retired_lines_are_never_stale(project):
    root, _, _ = project
    # BR-9 sits under ## Retired with a verified hash that does not exist; it must not count.
    assert mod.build(root, "overview", [])["stale_count"] == 0


def test_unknown_profile_is_a_usage_error(project):
    root, _, _ = project
    with pytest.raises(ValueError):
        mod.build(root, "everything", [])

"""Canonical autocomplete export ingestion remains verification-only."""

from __future__ import annotations

import copy
import hashlib
import json

import pytest

from seon_inspect import autocomplete_manifest as am


def _digest(value):
    encoded = json.dumps(
        value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(encoded.encode()).hexdigest()


def _manifest():
    coordinate = {
        "database_id": "db-1", "branch": "db", "commit_id": "commit-1", "t": 7}
    closure = {"definitions": "(register! :x/id :string)"}
    closure["id"] = hashlib.sha256(closure["definitions"].encode()).hexdigest()
    config = {"id": "c" * 64}
    profile = {"id": "b" * 64}
    row = {
        "agent": "root", "turn_id": "turn-1", "projection_mode": "observed",
        "coordinate": coordinate, "context": "ctx", "cards": ["fn x/y"],
        "target": "(x/y)", "coverage": 1,
        "schema_closure_id": closure["id"], "config_id": config["id"],
        "profile_id": profile["id"],
    }
    seed = "seon-autocomplete-v1"
    row_id = _digest({"seed": seed, "row": row})
    row.update({"row_id": row_id, "split": am._split_for(row_id)})
    rejection = {
        "agent": "root", "turn_id": "turn-2", "projection_mode": "observed",
        "attempted_target": "", "reason": "no-successful-evals",
    }
    rejection["rejection_id"] = _digest(rejection)
    artifact_manifest = {
        "seon.dev.artifact/version": 3,
        "seon.dev.artifact/application-digest": "a" * 64,
    }
    content = {
        "format": am.FORMAT, "database": "default",
        "source": {"revision": "deadbeef", "projection_sha": "deadbeef",
                   "runtime_root_diff_sha256": "e" * 64},
        "runtime_artifact": {
            "identity_sha256": _digest(artifact_manifest),
            "manifest": artifact_manifest,
        },
        "renderer": {"symbol": "seon.repl.autocomplete/context",
                     "profile": "autocomplete"},
        "split_policy": {
            "id": "sha256-row-id-mod-100/v1", "seed": seed,
            "ranges": {"development": [0, 80], "milestone": [80, 90],
                       "test": [90, 100]}},
        "schema_closures": [closure], "configurations": [config],
        "profiles": [profile], "rows": [row], "rejections": [rejection],
    }
    return {"manifest_id": _digest(content), "content": content}


def test_load_verifies_and_selects_frozen_rows(tmp_path):
    expected = _manifest()
    path = tmp_path / "export.manifest.json"
    path.write_text(json.dumps(expected, sort_keys=True))
    loaded = am.load_manifest(path)
    split = loaded["content"]["rows"][0]["split"]
    assert am.rows_for_split(loaded, split) == loaded["content"]["rows"]
    assert am.rows_for_split(loaded, next(iter(am.SPLITS - {split}))) == []


@pytest.mark.parametrize(
    "mutate,match",
    [
        (lambda m: m["content"]["rows"][0].update(target="tampered"),
         "content digest"),
        (lambda m: m.update(manifest_id="0" * 64), "content digest"),
        (lambda m: m["content"].update(format="unknown"), "content digest"),
    ],
)
def test_tampered_content_is_rejected_before_consumption(mutate, match):
    manifest = _manifest()
    mutate(manifest)
    with pytest.raises(am.AutocompleteManifestError, match=match):
        am.verify_manifest(manifest)


def test_forged_row_or_split_is_rejected_even_with_rehashed_manifest():
    original = _manifest()
    current_split = original["content"]["rows"][0]["split"]
    wrong_split = next(iter(am.SPLITS - {current_split}))
    for field, value, match in [
        ("row_id", "f" * 64, "row .* identity"),
        ("split", wrong_split, "split"),
        ("projection_mode", "counterfactual", "identity"),
    ]:
        manifest = copy.deepcopy(_manifest())
        manifest["content"]["rows"][0][field] = value
        manifest["manifest_id"] = _digest(manifest["content"])
        with pytest.raises(am.AutocompleteManifestError, match=match):
            am.verify_manifest(manifest)


def test_forged_schema_and_rejection_evidence_are_rejected():
    for mutate, match in [
        (lambda m: m["content"]["schema_closures"][0].update(
            definitions="changed"), "schema closure"),
        (lambda m: m["content"]["rejections"][0].update(
            reason="changed"), "rejection .* identity"),
    ]:
        manifest = _manifest()
        mutate(manifest)
        manifest["manifest_id"] = _digest(manifest["content"])
        with pytest.raises(am.AutocompleteManifestError, match=match):
            am.verify_manifest(manifest)

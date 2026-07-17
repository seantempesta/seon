"""Read and verify Seon's canonical autocomplete export manifest.

The ClojureScript exporter owns projection, cards, schema closure, row ids,
splits, and rejection evidence. Inspect only verifies and selects those frozen
rows; it never rebuilds a card or reparses Seon source.
"""

from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path
from typing import Any


FORMAT = "seon.autocomplete.export/v1"
SHA256 = re.compile(r"^[0-9a-f]{64}$")
SPLITS = {"development", "milestone", "test"}


class AutocompleteManifestError(ValueError):
    """The export is malformed or its content identity does not verify."""


def _canonical_json(value: Any) -> str:
    return json.dumps(
        value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def _digest(value: Any) -> str:
    return hashlib.sha256(_canonical_json(value).encode()).hexdigest()


def _require_sha(value: Any, where: str) -> str:
    if not isinstance(value, str) or not SHA256.fullmatch(value):
        raise AutocompleteManifestError(f"{where} is not a SHA-256 identity")
    return value


def _require_db(value: Any, where: str) -> None:
    if not isinstance(value, dict):
        raise AutocompleteManifestError(f"{where} is not a database value")
    required = {"db-name", "t", "as-of", "since", "history",
                "datahike/commit-id"}
    if set(value) != required:
        raise AutocompleteManifestError(
            f"{where} must contain exactly {sorted(required)}")
    if not isinstance(value["db-name"], str) or not value["db-name"]:
        raise AutocompleteManifestError(f"{where}.db-name is invalid")
    if not isinstance(value["datahike/commit-id"], str):
        raise AutocompleteManifestError(f"{where} commit identity is invalid")
    if not isinstance(value["t"], int) or not isinstance(value["history"], bool):
        raise AutocompleteManifestError(f"{where} basis/history is invalid")
    temporal = [value["as-of"], value["since"]]
    if any(point is not None and not isinstance(point, int) for point in temporal):
        raise AutocompleteManifestError(f"{where} temporal selection is invalid")
    if all(point is not None for point in temporal):
        raise AutocompleteManifestError(f"{where} has two temporal selections")


def _split_for(row_id: str) -> str:
    bucket = int(row_id[:8], 16) % 100
    if bucket < 80:
        return "development"
    if bucket < 90:
        return "milestone"
    return "test"


def _unique(rows: list[dict[str, Any]], key: str, where: str) -> set[str]:
    identities = [_require_sha(row.get(key), f"{where}.{key}") for row in rows]
    if len(identities) != len(set(identities)):
        raise AutocompleteManifestError(f"{where} contains duplicate {key}s")
    return set(identities)


def verify_manifest(manifest: dict[str, Any]) -> dict[str, Any]:
    """Return ``manifest`` after complete offline structural/digest checks."""
    if set(manifest) != {"manifest_id", "content"}:
        raise AutocompleteManifestError(
            "manifest envelope must contain exactly manifest_id and content")
    content = manifest.get("content")
    if not isinstance(content, dict):
        raise AutocompleteManifestError("manifest content is not a map")
    manifest_id = _require_sha(manifest.get("manifest_id"), "manifest_id")
    if _digest(content) != manifest_id:
        raise AutocompleteManifestError("manifest content digest does not verify")
    if content.get("format") != FORMAT:
        raise AutocompleteManifestError(
            f"unsupported autocomplete format {content.get('format')!r}")

    policy = content.get("split_policy")
    if not isinstance(policy, dict) or policy.get("id") != "sha256-row-id-mod-100/v1":
        raise AutocompleteManifestError("unsupported or absent split policy")
    if policy.get("ranges") != {
            "development": [0, 80], "milestone": [80, 90], "test": [90, 100]}:
        raise AutocompleteManifestError("split policy ranges do not verify")
    seed = policy.get("seed")
    if not isinstance(seed, str) or not seed:
        raise AutocompleteManifestError("split policy seed is absent")

    closures = content.get("schema_closures")
    configs = content.get("configurations")
    profiles = content.get("profiles")
    rows = content.get("rows")
    rejections = content.get("rejections")
    collections = {
        "schema_closures": closures, "configurations": configs,
        "profiles": profiles, "rows": rows, "rejections": rejections,
    }
    for name, value in collections.items():
        if not isinstance(value, list) or not all(isinstance(x, dict) for x in value):
            raise AutocompleteManifestError(f"{name} is not a list of maps")

    closure_ids = _unique(closures, "id", "schema_closures")
    config_ids = _unique(configs, "id", "configurations")
    profile_ids = _unique(profiles, "id", "profiles")
    for closure in closures:
        definitions = closure.get("definitions")
        if not isinstance(definitions, str) or hashlib.sha256(
                definitions.encode()).hexdigest() != closure["id"]:
            raise AutocompleteManifestError(
                f"schema closure {closure.get('id')!r} does not verify")

    _unique(rows, "row_id", "rows")
    for row in rows:
        row_id = row["row_id"]
        base = {key: value for key, value in row.items()
                if key not in {"row_id", "split"}}
        if _digest({"seed": seed, "row": base}) != row_id:
            raise AutocompleteManifestError(f"row {row_id} identity does not verify")
        if row.get("split") not in SPLITS or row["split"] != _split_for(row_id):
            raise AutocompleteManifestError(f"row {row_id} split does not verify")
        if row.get("projection_mode") != "observed":
            raise AutocompleteManifestError(
                f"row {row_id} has unsupported projection semantics")
        _require_db(row.get("db"), f"row {row_id}.db")
        if row.get("schema_closure_id") not in closure_ids:
            raise AutocompleteManifestError(f"row {row_id} schema closure is absent")
        if row.get("config_id") not in config_ids:
            raise AutocompleteManifestError(f"row {row_id} config identity is absent")
        if row.get("profile_id") not in profile_ids:
            raise AutocompleteManifestError(f"row {row_id} profile identity is absent")

    _unique(rejections, "rejection_id", "rejections")
    for rejection in rejections:
        rejection_id = rejection["rejection_id"]
        base = {key: value for key, value in rejection.items()
                if key != "rejection_id"}
        if _digest(base) != rejection_id:
            raise AutocompleteManifestError(
                f"rejection {rejection_id} identity does not verify")
        if "db" in rejection:
            _require_db(rejection["db"], f"rejection {rejection_id}.db")
        if not isinstance(rejection.get("reason"), str):
            raise AutocompleteManifestError(
                f"rejection {rejection_id} reason is absent")

    artifact = content.get("runtime_artifact")
    if not isinstance(artifact, dict) or set(artifact) != {"application_digest"}:
        raise AutocompleteManifestError("runtime artifact application digest is absent")
    _require_sha(
        artifact.get("application_digest"),
        "runtime_artifact.application_digest",
    )
    source = content.get("source")
    if not isinstance(source, dict):
        raise AutocompleteManifestError("source identity is absent")
    _require_sha(
        source.get("runtime_root_diff_sha256"),
        "source.runtime_root_diff_sha256",
    )
    return manifest


def load_manifest(path: Path) -> dict[str, Any]:
    """Read a canonical export and reject any malformed/tampered artifact."""
    try:
        manifest = json.loads(path.read_text())
    except (OSError, json.JSONDecodeError) as error:
        raise AutocompleteManifestError(
            f"cannot read autocomplete manifest {path}: {error}") from error
    if not isinstance(manifest, dict):
        raise AutocompleteManifestError("manifest root is not a map")
    return verify_manifest(manifest)


def rows_for_split(manifest: dict[str, Any], split: str) -> list[dict[str, Any]]:
    """Select frozen rows after verification; no projection/transformation."""
    if split not in SPLITS:
        raise AutocompleteManifestError(f"unknown autocomplete split {split!r}")
    verified = verify_manifest(manifest)
    return [row for row in verified["content"]["rows"] if row["split"] == split]

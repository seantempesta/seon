"""Content-pinned source admission and native-log finalization.

The repository Gitlinks select Inspect and Inspect Evals. This module makes
that selection executable: a task cannot construct or run unless the checked
out sources, installed distributions, provider, Python lock, dataset lock, and
Seon harness source agree with the reviewed lock. The resulting immutable map
is attached to Inspect's native eval metadata.
"""

from __future__ import annotations

import hashlib
import importlib.metadata
import json
import shutil
import subprocess
from pathlib import Path
from typing import Any, Callable
from urllib.parse import unquote, urlparse

from inspect_ai.log import read_eval_log

REPO_ROOT = Path(__file__).resolve().parents[3]
DEFAULT_LOCK_PATH = REPO_ROOT / "src-inspect-ai" / "evaluation-sources.lock.json"


class SourceAdmissionError(RuntimeError):
    """A scored run's selected source or retained evidence is not exact."""


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _git(cwd: Path, *args: str) -> str:
    proc = subprocess.run(
        ["git", *args], cwd=cwd, text=True, capture_output=True, check=False)
    if proc.returncode != 0:
        detail = proc.stderr.strip() or proc.stdout.strip() or "git failed"
        raise SourceAdmissionError(f"source admission: {detail}")
    return proc.stdout.rstrip()


def _gitlink(checkout: Path, relative_path: str) -> str:
    row = _git(checkout, "ls-files", "-s", "--", relative_path)
    parts = row.split()
    if len(parts) < 2 or parts[0] != "160000":
        raise SourceAdmissionError(
            f"source admission: {relative_path} is not a selected Gitlink")
    return parts[1]


def _dirty_paths(checkout: Path, admitted: list[str], excluded: list[str]) -> list[str]:
    pathspecs = ["--", *admitted, *(f":!{path}" for path in excluded)]
    output = _git(
        checkout, "status", "--porcelain", "--untracked-files=all", *pathspecs)
    return [line[3:] for line in output.splitlines() if line]


def _nested_source_identities(
    checkout: Path, selected: dict[str, Any]
) -> dict[str, dict[str, str]]:
    identities: dict[str, dict[str, str]] = {}
    for name, nested in selected.get("nested_sources", {}).items():
        relative_path = nested["path"]
        nested_checkout = (checkout / relative_path).resolve()
        parent_revision = _gitlink(checkout, relative_path)
        expected_parent_revision = nested["parent_revision"]
        expected_revision = nested["revision"]
        checkout_revision = _git(nested_checkout, "rev-parse", "HEAD")
        if parent_revision != expected_parent_revision:
            raise SourceAdmissionError(
                f"source admission: {name} parent revision mismatch; "
                f"lock={expected_parent_revision} gitlink={parent_revision}")
        if checkout_revision != expected_revision:
            raise SourceAdmissionError(
                f"source admission: {name} revision mismatch; "
                f"lock={expected_revision} checkout={checkout_revision}")
        dirty = _dirty_paths(nested_checkout, ["."], [])
        if dirty:
            raise SourceAdmissionError(
                f"source admission: {name} selected source is dirty: {dirty}")
        identities[name] = {
            "source_path": relative_path,
            "parent_revision": expected_parent_revision,
            "revision": expected_revision,
            "tree": _git(nested_checkout, "rev-parse", "HEAD^{tree}"),
        }
    return identities


def _direct_source_path(distribution: importlib.metadata.Distribution) -> Path | None:
    raw = distribution.read_text("direct_url.json")
    if not raw:
        return None
    url = json.loads(raw).get("url", "")
    parsed = urlparse(url)
    if parsed.scheme != "file":
        return None
    return Path(unquote(parsed.path)).resolve()


def _distribution_identity(name: str) -> tuple[str, Path | None]:
    distribution = importlib.metadata.distribution(name)
    return distribution.version, _direct_source_path(distribution)


def _version_names_revision(version: str, revision: str) -> bool:
    return revision[:9].lower() in version.lower()


def _source_identity(
    repo_root: Path,
    name: str,
    selected: dict[str, Any],
    distribution_identity: Callable[[str], tuple[str, Path | None]],
) -> dict[str, Any]:
    relative_path = selected["path"]
    checkout = (repo_root / relative_path).resolve()
    expected_revision = selected["revision"]
    root_revision = _gitlink(repo_root, relative_path)
    checkout_revision = _git(checkout, "rev-parse", "HEAD")
    if root_revision != expected_revision or checkout_revision != expected_revision:
        raise SourceAdmissionError(
            f"source admission: {name} revision mismatch; lock={expected_revision} "
            f"gitlink={root_revision} checkout={checkout_revision}")
    nested_sources = _nested_source_identities(checkout, selected)
    nested_paths = [nested["path"]
                    for nested in selected.get("nested_sources", {}).values()]
    dirty = _dirty_paths(checkout, list(selected["admitted_paths"]), nested_paths)
    if dirty:
        raise SourceAdmissionError(
            f"source admission: {name} selected source is dirty: {dirty}")
    try:
        version, installed_source = distribution_identity(selected["distribution"])
    except importlib.metadata.PackageNotFoundError as error:
        raise SourceAdmissionError(
            f"source admission: {selected['distribution']} is not installed") from error
    if installed_source != checkout:
        raise SourceAdmissionError(
            f"source admission: {name} installed source {installed_source} "
            f"does not equal selected checkout {checkout}")
    expected_version = selected.get("installed_version")
    if expected_version is not None and version != expected_version:
        raise SourceAdmissionError(
            f"source admission: {name} installed version mismatch; "
            f"lock={expected_version} installed={version}")
    if (selected.get("version_contains_revision")
            and not _version_names_revision(version, expected_revision)):
        raise SourceAdmissionError(
            f"source admission: {name} installed version {version!r} does not "
            f"name selected revision {expected_revision[:9]}")
    return {
        "revision": expected_revision,
        "tree": _git(checkout, "rev-parse", "HEAD^{tree}"),
        "distribution": selected["distribution"],
        "installed_version": version,
        "source_path": relative_path,
        "nested_sources": nested_sources,
    }


def verify_sources(
    bench: dict[str, str],
    *,
    repo_root: Path = REPO_ROOT,
    lock_path: Path = DEFAULT_LOCK_PATH,
    distribution_identity: Callable[[str], tuple[str, Path | None]] =
        _distribution_identity,
) -> dict[str, Any]:
    """Verify and return the immutable source identity for one task run."""
    lock = json.loads(lock_path.read_text())
    if lock.get("schema_version") != 2:
        raise SourceAdmissionError(
            f"source admission: unsupported lock schema {lock.get('schema_version')!r}")

    sources = {
        name: _source_identity(repo_root, name, selected, distribution_identity)
        for name, selected in lock["sources"].items()
    }
    providers: dict[str, Any] = {}
    for name, selected in lock["providers"].items():
        try:
            version, _ = distribution_identity(selected["distribution"])
        except importlib.metadata.PackageNotFoundError as error:
            raise SourceAdmissionError(
                f"source admission: provider {selected['distribution']} is not installed") from error
        if version != selected["version"]:
            raise SourceAdmissionError(
                f"source admission: provider {name} version mismatch; "
                f"lock={selected['version']} installed={version}")
        providers[name] = {
            "distribution": selected["distribution"], "version": version}

    artifacts = {}
    for name, relative_path in lock["artifacts"].items():
        path = repo_root / relative_path
        if not path.is_file():
            raise SourceAdmissionError(
                f"source admission: required artifact is absent: {relative_path}")
        artifacts[name] = {"path": relative_path, "sha256": _sha256(path)}

    seon_dirty = _dirty_paths(
        repo_root, list(lock["seon_admitted_paths"]), [])
    if seon_dirty:
        raise SourceAdmissionError(
            f"source admission: Seon evaluation source is dirty: {seon_dirty}")

    return {
        "schema_version": 2,
        "bench": dict(bench),
        "sources": sources,
        "providers": providers,
        "artifacts": artifacts,
        "seon": {
            "revision": _git(repo_root, "rev-parse", "HEAD"),
            "tree": _git(repo_root, "rev-parse", "HEAD^{tree}"),
        },
        "source_lock": {
            "path": str(lock_path.relative_to(repo_root)),
            "sha256": _sha256(lock_path),
        },
    }


def finalize_native_logs(
    logs: Any,
    *,
    evidence_dir: Path | None = None,
    expected_admission: dict[str, Any] | None = None,
    expected_metadata: dict[str, dict[str, Any]] | None = None,
    require_success: bool = True,
) -> list[dict[str, Any]]:
    """Retain, reopen, and identity-check native logs."""
    rows = list(logs or [])
    if not rows:
        raise SourceAdmissionError(
            "evidence finalization: Inspect returned no native eval log")
    destination = evidence_dir / "inspect-logs" if evidence_dir else None
    manifest: list[dict[str, Any]] = []
    for log in rows:
        location = str(
            log if isinstance(log, (str, Path))
            else getattr(log, "location", "") or "")
        parsed = urlparse(location)
        source = Path(unquote(parsed.path if parsed.scheme == "file" else location))
        if not source.is_file():
            raise SourceAdmissionError(
                f"evidence finalization: native eval log is absent: {location!r}")
        retained = source
        if destination is not None:
            destination.mkdir(parents=True, exist_ok=True)
            retained = destination / source.name
            shutil.copy2(source, retained)
            if not retained.is_file() or _sha256(retained) != _sha256(source):
                raise SourceAdmissionError(
                    f"evidence finalization: failed to retain {source}")
        try:
            native = read_eval_log(retained)
        except Exception as error:
            raise SourceAdmissionError(
                f"evidence finalization: retained log is unreadable: {retained}"
            ) from error
        if require_success and native.status != "success":
            raise SourceAdmissionError(
                f"evidence finalization: retained log status is "
                f"{native.status!r}, not 'success'")
        actual_admission = (native.eval.metadata or {}).get(
            "seon_source_admission")
        if (expected_admission is not None
                and actual_admission != expected_admission):
            raise SourceAdmissionError(
                "evidence finalization: retained log source admission "
                "does not match the admitted run")
        if expected_metadata is not None:
            if set(expected_metadata) != {"eval", "log"}:
                raise SourceAdmissionError(
                    "evidence finalization: expected metadata scopes are invalid")
            actual_scopes = {
                "eval": native.eval.metadata or {},
                "log": native.metadata or {},
            }
            for scope, expected in expected_metadata.items():
                if not isinstance(expected, dict):
                    raise SourceAdmissionError(
                        "evidence finalization: expected metadata is invalid")
                for key, value in expected.items():
                    if actual_scopes[scope].get(key) != value:
                        raise SourceAdmissionError(
                            "evidence finalization: retained log metadata "
                            f"does not match {scope}.{key}")
        manifest.append({
            "location": location,
            "sha256": _sha256(source),
            "retained_path": str(retained),
            "status": native.status,
            "source_admission": actual_admission,
        })
    return manifest

"""Deterministic source admission and native evidence finalization."""

from __future__ import annotations

import json
from pathlib import Path
from types import SimpleNamespace

import pytest

from seon_inspect import source_admission


INSPECT_REV = "a" * 40
EVALS_REV = "b" * 40
VIEW_PARENT_REV = "d" * 40
VIEW_REV = "e" * 40


def _lock(repo: Path) -> Path:
    paths = [
        repo / "reference-code" / "inspect-ai",
        repo / "reference-code" / "inspect-evals",
        repo / "reference-code" / "inspect-ai" / "src" / "inspect_ai" / "_view" / "ts-mono",
        repo / "src-inspect-ai",
        repo / "evals",
    ]
    for path in paths:
        path.mkdir(parents=True, exist_ok=True)
    (repo / "src-inspect-ai" / "uv.lock").write_text("python-lock\n")
    (repo / "evals" / "datasets.lock").write_text("dataset-lock\n")
    lock_path = repo / "src-inspect-ai" / "evaluation-sources.lock.json"
    lock_path.write_text(json.dumps({
        "schema_version": 2,
        "sources": {
            "inspect_ai": {
                "distribution": "inspect-ai", "module": "inspect_ai",
                "path": "reference-code/inspect-ai", "revision": INSPECT_REV,
                "version_contains_revision": True,
                "admitted_paths": ["src/inspect_ai"],
                "nested_sources": {
                    "inspect_view": {
                        "path": "src/inspect_ai/_view/ts-mono",
                        "parent_revision": VIEW_PARENT_REV,
                        "revision": VIEW_REV,
                    },
                },
            },
            "inspect_evals": {
                "distribution": "inspect-evals", "module": "inspect_evals",
                "path": "reference-code/inspect-evals", "revision": EVALS_REV,
                "installed_version": "0.14.3",
                "admitted_paths": ["src/inspect_evals"],
            },
        },
        "providers": {
            "openai": {"distribution": "openai", "version": "2.45.0"}},
        "artifacts": {
            "python_lock": "src-inspect-ai/uv.lock",
            "datasets_lock": "evals/datasets.lock",
        },
        "seon_admitted_paths": ["src-inspect-ai", "evals/datasets.lock"],
    }, sort_keys=True))
    return lock_path


def _git_answer(repo: Path):
    def answer(cwd: Path, *args: str) -> str:
        if args[-1] == "HEAD":
            if cwd.name == "ts-mono":
                return VIEW_REV
            if cwd.name == "inspect-ai":
                return INSPECT_REV
            if cwd.name == "inspect-evals":
                return EVALS_REV
            return "c" * 40
        if args[-1] == "HEAD^{tree}":
            return ("a" if cwd.name == "ts-mono" else
                    "d" if cwd.name == "inspect-ai" else
                    "e" if cwd.name == "inspect-evals" else "f") * 40
        raise AssertionError((cwd, args))
    return answer


def _distributions(repo: Path):
    identities = {
        "inspect-ai": (f"0.3.0+g{INSPECT_REV[:9]}",
                       (repo / "reference-code" / "inspect-ai").resolve()),
        "inspect-evals": ("0.14.3",
                          (repo / "reference-code" / "inspect-evals").resolve()),
        "openai": ("2.45.0", None),
    }
    return identities.__getitem__


def _admit(monkeypatch, tmp_path, distributions=None):
    lock_path = _lock(tmp_path)
    monkeypatch.setattr(
        source_admission, "_gitlink",
        lambda repo, path: (VIEW_PARENT_REV if path.endswith("ts-mono") else
                            INSPECT_REV if path.endswith("inspect-ai") else EVALS_REV))
    monkeypatch.setattr(source_admission, "_git", _git_answer(tmp_path))
    monkeypatch.setattr(source_admission, "_dirty_paths", lambda *args: [])
    return source_admission.verify_sources(
        {"name": "bfcl_ast", "module": "inspect_evals.bfcl",
         "attribute": "bfcl", "kind": "case1"},
        repo_root=tmp_path,
        lock_path=lock_path,
        distribution_identity=distributions or _distributions(tmp_path),
    )


def test_verify_sources_accepts_exact_selected_world(monkeypatch, tmp_path):
    admitted = _admit(monkeypatch, tmp_path)
    assert admitted["sources"]["inspect_ai"]["revision"] == INSPECT_REV
    assert (admitted["sources"]["inspect_ai"]["nested_sources"]
            ["inspect_view"]["revision"] == VIEW_REV)
    assert admitted["sources"]["inspect_evals"]["installed_version"] == "0.14.3"
    assert admitted["providers"]["openai"]["version"] == "2.45.0"
    assert admitted["bench"]["name"] == "bfcl_ast"
    assert len(admitted["artifacts"]["python_lock"]["sha256"]) == 64
    assert len(admitted["source_lock"]["sha256"]) == 64


def test_selected_lock_admits_runtime_build_inputs():
    lock = json.loads(source_admission.DEFAULT_LOCK_PATH.read_text())
    admitted = set(lock["seon_admitted_paths"])
    assert {
        "src", "script", "resources", "acme", "build.clj", "deps.edn",
        "shadow-cljs.edn", "package.json", "package-lock.json",
        "bin/seon", "bin/acme", "bin/fix-bootstrap-macros",
    } <= admitted


def test_verify_sources_rejects_revision_mismatch(monkeypatch, tmp_path):
    lock_path = _lock(tmp_path)
    monkeypatch.setattr(source_admission, "_gitlink", lambda *args: "x" * 40)
    monkeypatch.setattr(source_admission, "_git", _git_answer(tmp_path))
    monkeypatch.setattr(source_admission, "_dirty_paths", lambda *args: [])
    with pytest.raises(source_admission.SourceAdmissionError,
                       match="revision mismatch"):
        source_admission.verify_sources(
            {"name": "gsm8k"}, repo_root=tmp_path, lock_path=lock_path,
            distribution_identity=_distributions(tmp_path))


def test_verify_sources_rejects_dirty_selected_source(monkeypatch, tmp_path):
    lock_path = _lock(tmp_path)
    monkeypatch.setattr(
        source_admission, "_gitlink",
        lambda repo, path: (VIEW_PARENT_REV if path.endswith("ts-mono") else
                            INSPECT_REV if path.endswith("inspect-ai") else EVALS_REV))
    monkeypatch.setattr(source_admission, "_git", _git_answer(tmp_path))
    monkeypatch.setattr(
        source_admission, "_dirty_paths",
        lambda checkout, admitted, excluded: (["src/inspect_ai/x.py"]
                                               if checkout.name == "inspect-ai"
                                               else []),
    )
    with pytest.raises(source_admission.SourceAdmissionError, match="is dirty"):
        source_admission.verify_sources(
            {"name": "gsm8k"}, repo_root=tmp_path, lock_path=lock_path,
            distribution_identity=_distributions(tmp_path))


def test_verify_sources_rejects_nested_revision_mismatch(monkeypatch, tmp_path):
    lock_path = _lock(tmp_path)
    git_answer = _git_answer(tmp_path)

    def mismatched_nested(cwd, *args):
        if cwd.name == "ts-mono" and args[-1] == "HEAD":
            return "x" * 40
        return git_answer(cwd, *args)

    monkeypatch.setattr(
        source_admission, "_gitlink",
        lambda repo, path: (VIEW_PARENT_REV if path.endswith("ts-mono") else
                            INSPECT_REV if path.endswith("inspect-ai") else EVALS_REV))
    monkeypatch.setattr(source_admission, "_git", mismatched_nested)
    monkeypatch.setattr(source_admission, "_dirty_paths", lambda *args: [])
    with pytest.raises(source_admission.SourceAdmissionError,
                       match="inspect_view revision mismatch"):
        source_admission.verify_sources(
            {"name": "gsm8k"}, repo_root=tmp_path, lock_path=lock_path,
            distribution_identity=_distributions(tmp_path))


def test_verify_sources_rejects_dirty_nested_source(monkeypatch, tmp_path):
    lock_path = _lock(tmp_path)
    monkeypatch.setattr(
        source_admission, "_gitlink",
        lambda repo, path: (VIEW_PARENT_REV if path.endswith("ts-mono") else
                            INSPECT_REV if path.endswith("inspect-ai") else EVALS_REV))
    monkeypatch.setattr(source_admission, "_git", _git_answer(tmp_path))
    monkeypatch.setattr(
        source_admission, "_dirty_paths",
        lambda checkout, admitted, excluded: (["apps/inspect/changed.ts"]
                                               if checkout.name == "ts-mono"
                                               else []),
    )
    with pytest.raises(source_admission.SourceAdmissionError,
                       match="inspect_view selected source is dirty"):
        source_admission.verify_sources(
            {"name": "gsm8k"}, repo_root=tmp_path, lock_path=lock_path,
            distribution_identity=_distributions(tmp_path))


def test_verify_sources_rejects_provider_version(monkeypatch, tmp_path):
    identities = _distributions(tmp_path)

    def mismatched(name):
        return ("2.46.0", None) if name == "openai" else identities(name)

    with pytest.raises(source_admission.SourceAdmissionError,
                       match="provider openai version mismatch"):
        _admit(monkeypatch, tmp_path, mismatched)


class _Log:
    def __init__(self, location: str):
        self.location = location


def test_finalize_native_logs_requires_and_copies_exact_bytes(
    monkeypatch, tmp_path
):
    source = tmp_path / "native.eval"
    source.write_bytes(b"inspect-native-log")
    evidence = tmp_path / "evidence"
    admission = {"schema_version": 2, "bench": {"name": "bfcl_ast"}}
    monkeypatch.setattr(
        source_admission,
        "read_eval_log",
        lambda path: SimpleNamespace(
            status="success",
            eval=SimpleNamespace(
                metadata={"seon_source_admission": admission}),
        ),
    )
    manifest = source_admission.finalize_native_logs(
        [_Log(source.as_uri())],
        evidence_dir=evidence,
        expected_admission=admission,
    )
    retained = evidence / "inspect-logs" / "native.eval"
    assert retained.read_bytes() == source.read_bytes()
    assert manifest == [{
        "location": source.as_uri(),
        "sha256": source_admission._sha256(source),
        "retained_path": str(retained),
        "status": "success",
        "source_admission": admission,
    }]


@pytest.mark.parametrize("logs", [[], [_Log("file:///absent/native.eval")]])
def test_finalize_native_logs_rejects_absent_evidence(logs):
    with pytest.raises(source_admission.SourceAdmissionError,
                       match="no native eval log|native eval log is absent"):
        source_admission.finalize_native_logs(logs)


def test_finalize_native_logs_rejects_unreadable_archive(monkeypatch, tmp_path):
    source = tmp_path / "corrupt.eval"
    source.write_bytes(b"not an eval archive")
    monkeypatch.setattr(
        source_admission, "read_eval_log",
        lambda path: (_ for _ in ()).throw(ValueError("corrupt")),
    )
    with pytest.raises(source_admission.SourceAdmissionError,
                       match="retained log is unreadable"):
        source_admission.finalize_native_logs([_Log(source.as_uri())])


@pytest.mark.parametrize("status", ["started", "cancelled", "error"])
def test_finalize_native_logs_rejects_non_success_status(
    monkeypatch, tmp_path, status
):
    source = tmp_path / f"{status}.eval"
    source.write_bytes(b"native")
    monkeypatch.setattr(
        source_admission,
        "read_eval_log",
        lambda path: SimpleNamespace(
            status=status, eval=SimpleNamespace(metadata={})),
    )
    with pytest.raises(source_admission.SourceAdmissionError,
                       match="not 'success'"):
        source_admission.finalize_native_logs([_Log(source.as_uri())])


def test_finalize_native_logs_rejects_wrong_admission(monkeypatch, tmp_path):
    source = tmp_path / "wrong.eval"
    source.write_bytes(b"native")
    monkeypatch.setattr(
        source_admission,
        "read_eval_log",
        lambda path: SimpleNamespace(
            status="success",
            eval=SimpleNamespace(
                metadata={"seon_source_admission": {"revision": "wrong"}}),
        ),
    )
    with pytest.raises(source_admission.SourceAdmissionError,
                       match="does not match"):
        source_admission.finalize_native_logs(
            [_Log(source.as_uri())],
            expected_admission={"revision": "expected"},
        )

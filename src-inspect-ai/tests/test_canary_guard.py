"""CI canary guard — the blind split must never leak into repo/context/config.

Every test split (external) and bespoke dataset carries a canary GUID in
`evals/datasets.lock`. If any of those GUIDs appears ANYWHERE outside the
datasets dir (`evals/`) — source, docs, config, skills, the harness itself —
then held-out data has leaked into agent-visible context or code, and the
bench would measure contamination, not capability. Fail loud.
"""

from __future__ import annotations

from pathlib import Path

from seon_inspect.freeze import CANARY_PREFIX, DEFAULT_LOCK_PATH, REPO_ROOT, read_lock

# Where canaries are ALLOWED to live: the datasets dir only.
DATASETS_DIR = DEFAULT_LOCK_PATH.parent

# The scan surface: everything an agent or the harness could pick context up
# from. reference-code/ (vendored deps) and build/log dirs are out of scope.
SCAN_DIRS = ["src", "docs", "config", "seon-skills", "src-inspect-ai", "test"]

SKIP_DIR_NAMES = {
    ".git",
    ".venv",
    "node_modules",
    "__pycache__",
    ".pytest_cache",
    "logs",
    "target",
    "uv.lock",
}
MAX_FILE_BYTES = 8 * 1024 * 1024


def lock_canaries() -> list[str]:
    lock = read_lock()
    canaries = [s["test"]["canary_guid"] for s in lock["sources"].values()]
    canaries += [b["canary_guid"] for b in lock["bespoke"].values()]
    assert canaries, "datasets.lock carries no canaries — freeze is broken"
    assert all(c.startswith(CANARY_PREFIX) for c in canaries)
    assert len(set(canaries)) == len(canaries), "canary GUIDs must be unique"
    return canaries


def scan_for_canaries(canaries: list[str]) -> list[str]:
    """Return 'path: canary' hits for any canary outside the datasets dir."""
    needles = [c.encode() for c in canaries]
    hits: list[str] = []
    for top in SCAN_DIRS:
        root = REPO_ROOT / top
        if not root.exists():
            continue
        for path in root.rglob("*"):
            if not path.is_file():
                continue
            if any(part in SKIP_DIR_NAMES for part in path.parts):
                continue
            if DATASETS_DIR in path.parents:
                continue
            try:
                if path.stat().st_size > MAX_FILE_BYTES:
                    continue
                blob = path.read_bytes()
            except OSError:
                continue
            for needle in needles:
                if needle in blob:
                    hits.append(f"{path}: {needle.decode()}")
    return hits


def test_no_canary_outside_datasets_dir():
    hits = scan_for_canaries(lock_canaries())
    assert hits == [], (
        "CANARY LEAK — held-out split data escaped the datasets dir "
        "(answer-shaped context; the affected rows measure contamination "
        f"until this is removed):\n" + "\n".join(hits)
    )

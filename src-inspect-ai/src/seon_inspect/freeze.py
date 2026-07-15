"""Dataset freeze — seeded dev/milestone/test splits + datasets.lock.

Implements the eval-design sampling rule (docs/prds/agent-ctx/eval-design.md):
per external source, order samples canonically, shuffle under ONE recorded
seed (per-source derived from the global seed so adding a source never
reshuffles the others), stratify where labels exist (MMLU subjects), then
slice: first dev_n = dev, next milestone_n = milestone, rest = the blind test
reserve. Bespoke generator rows freeze the GENERATOR + seeds instead
(dev_seed=1, milestone_seed=2, fresh seed per test draw); rows whose
generator exists (`seon_inspect.generators`) also record dev/milestone jsonl
sha256s and write the dev artifact to `evals/<row>.dev.jsonl` — the rest stay
`pending-generator`.

The lock (`evals/datasets.lock`, JSON) records per source: the bench, the
upstream pin (HF revision / csv sha256), the seed, the dev+milestone sample-id
lists (draw order), the test split's size + id-list sha256 (ids themselves
stay unlisted — blind), a corpus content hash, and one canary GUID per test
split / bespoke dataset. Regenerating with the lock present is a VERIFY: a
no-op when identical, a loud diff + nonzero exit when not — never a silent
reshuffle. Canary GUIDs are minted once (uuid4, only when a lock entry is
first created) and carried over verbatim on every regeneration, so the lock
bytes are fully deterministic given an existing lock.

Tier discipline (structural, not honor-system):
- dev      → `DevSplit`, per-sample ids public, iterate freely.
- milestone→ `MilestoneSplit`, runnable but AGGREGATE-ONLY: repr redacts ids,
             iteration raises `TierDisciplineError`.
- test     → `load_split(..., "test")` RAISES unless `formal_eval=True`; the
             formal path recomputes the complement from the upstream dataset,
             checks its sha256 against the lock, and injects the canary GUID
             into each sample's METADATA (never agent-visible task text).

CLI:
    python -m seon_inspect.freeze              # create if absent, else verify
    python -m seon_inspect.freeze --write      # rewrite after an INTENTIONAL
                                               # spec change (canaries kept)
    python -m seon_inspect.freeze --lock PATH  # non-default lock location
"""

from __future__ import annotations

import argparse
import hashlib
import importlib
import json
import random
import sys
import uuid
from collections import deque
from pathlib import Path
from typing import Any

# ---------------------------------------------------------------------------
# Locations + seed (config, never env)
# ---------------------------------------------------------------------------

REPO_ROOT = Path(__file__).resolve().parents[3]
DEFAULT_LOCK_PATH = REPO_ROOT / "evals" / "datasets.lock"

# The ONE recorded global seed (eval-design: "one recorded global seed").
# Frozen 2026-07-02; changing it is a deliberate re-freeze (--write + review).
GLOBAL_SEED = 20260702

LOCK_SCHEMA_VERSION = 1
GENERATION_COMMAND = "cd src-inspect-ai && .venv/bin/python -m seon_inspect.freeze"

SAMPLING_PROCEDURE = (
    "per source: sort samples by str(id); shuffle with "
    "random.Random(f'{GLOBAL_SEED}:{source}'); where a stratify label exists, "
    "shuffle stratum order + within-stratum order with the same rng and "
    "interleave round-robin; slice the resulting order: first dev_n = dev, "
    "next milestone_n = milestone, remainder = blind test reserve"
)

# ---------------------------------------------------------------------------
# Source specs
# ---------------------------------------------------------------------------

# External (inspect_evals) sources currently runnable through the pod door.
# dev/milestone sizes from eval-design.md "Capability rows".
# pin = (module, attr) of the upstream dataset pin constant — recorded in the
# lock so an inspect-evals sync that moves a revision diffs LOUDLY.
EXTERNAL_SOURCES: dict[str, dict[str, Any]] = {
    "gsm8k": {
        "capability_row": "reasoning",
        "dev_n": 15,
        "milestone_n": 15,
        "stratify": None,
        # fewshot=0 skips the train-split download; the frozen TEST dataset
        # is identical to what run_bench's default task construction loads.
        "freeze_task_kwargs": {"fewshot": 0},
        "pin": ("inspect_evals.gsm8k.gsm8k", "GSM8K_DATASET_REVISION"),
    },
    "arc_challenge": {
        "capability_row": "science_qa",
        "dev_n": 15,
        "milestone_n": 15,
        "stratify": None,
        "freeze_task_kwargs": {},
        "pin": ("inspect_evals.arc.arc", "ARC_DATASET_REVISION"),
    },
    "mmlu_0_shot": {
        "capability_row": "knowledge",
        "dev_n": 15,
        "milestone_n": 15,
        "stratify": "subject",
        "freeze_task_kwargs": {},
        "pin": ("inspect_evals.mmlu.mmlu", "MMLU_REVISION"),
    },
    "gpqa_diamond": {
        "capability_row": "hard_calibration",
        "dev_n": 10,
        "milestone_n": 10,
        "stratify": None,
        "freeze_task_kwargs": {},
        "pin": ("inspect_evals.gpqa.gpqa", "GPQA_DIAMOND_DATASET_SHA256"),
    },
    "bfcl_ast": {
        "capability_row": "tool_calling",
        "dev_n": 10,
        "milestone_n": 10,
        # Span the AST categories so a split isn't all-simple; category_name
        # is on every bfcl sample's metadata.
        "stratify": "category_name",
        # Categories default to the AST subset via the BenchSpec's
        # default_task_kwargs (catalog.BENCHES) — freeze and run must load
        # the SAME set, so leave this empty.
        "freeze_task_kwargs": {},
        # BFCL's GitHub dataset is commit-pinned (contamination-proof) — record
        # the pin so an inspect-evals sync that moves it diffs loudly.
        "pin": ("inspect_evals.bfcl.bfcl", "BFCL_GITHUB_COMMIT"),
    },
    "swe_bench_verified": {
        # The A-overlay composition arm (benchmark-suite design §3): dataset
        # + official scorer from inspect_evals.swe_bench; driven by
        # tasks/swe_bench_seon.py, never the pod door. Stratified across
        # repos per the design (Verified is django-heavy).
        "capability_row": "swe_bench",
        "dev_n": 10,
        "milestone_n": 25,
        "stratify": "repo",
        "freeze_task_kwargs": {},
        "pin": ("inspect_evals.swe_bench.swe_bench",
                "SWE_BENCH_VERIFIED_REVISION"),
        # Exclusions are FILTERED OUT OF THE DRAW SEQUENCE (never re-shuffled
        # — the seeded order over the full corpus stays fixed; excluding an
        # id just promotes the next id in the sequence). Two kinds, both
        # honest non-difficulty reasons, each recorded in the lock:
        "exclude_ids": {
            "sympy__sympy-22914": (
                "spent as the slice-2 smoke + slice-3 composition probe; "
                "solution/patch published in evals/runs/ evidence"),
            "astropy__astropy-12907": (
                "spent as a slice-2 de-risk smoke probe; solution published "
                "in evals/runs/ evidence"),
            # Availability exclusions (slice 4, 2026-07-05): no arm64 epoch
            # instance image exists — ghcr returns "not found" even
            # AUTHENTICATED (evals/runs/2026-07-05-slice4-dev-pass/
            # pull-stats.txt). Not difficulty picks; the seeded sequence
            # tops the dev slice back up.
            "pydata__xarray-6721": (
                "no arm64 epoch instance image on ghcr (not found, "
                "authenticated pull, 2026-07-05)"),
            "matplotlib__matplotlib-22719": (
                "no arm64 epoch instance image on ghcr (not found, "
                "authenticated pull, 2026-07-05)"),
            "scikit-learn__scikit-learn-14629": (
                "no arm64 epoch instance image on ghcr (not found, "
                "authenticated pull, 2026-07-05)"),
            "pydata__xarray-4629": (
                "no arm64 epoch instance image on ghcr (not found, "
                "authenticated pull, 2026-07-05)"),
        },
    },
    "terminal_bench_2": {
        # Terminal-Bench 2.0 (Harbor harness) — the published 59.1 anchor's
        # task set. NOT an inspect_evals dataset: 89 task DIRECTORIES obtained
        # via `harbor download terminal-bench/terminal-bench-2` (harbor 0.17.1)
        # and frozen from a COMMITTED corpus manifest (offline-reproducible; no
        # harbor in the pinned .venv). Driven by tb2_agent.SeonAgent via
        # harbor's --agent-import-path, never the pod door — same non-pod-door
        # shape as swe_bench_verified. Stratified by category (16) so a split
        # spans the bench. ARM64 NOTE: all 89 prebuilt images are amd64-only
        # (native_arm64 false in the manifest); the dev split is drawn from the
        # full 89 (runnable under amd64 emulation/Rosetta) — there is NO
        # native-arm64 subset to restrict to, and that fact is pinned in the
        # manifest + the lock's `arm64` block.
        "capability_row": "terminal_bench",
        "dev_n": 10,
        "milestone_n": 25,
        "stratify": "category",
        "freeze_task_kwargs": {},
        # Manifest-corpus source (not an inspect Task): freeze reads the
        # committed manifest instead of load_bench_task.
        "corpus": "manifest",
        "manifest": "evals/tb2_terminal_bench_2.corpus.json",
    },
}

# Bespoke generator rows (eval-design): the GENERATOR + seeds are what's
# frozen — seed 1 = dev, seed 2 = milestone, fresh seed per draw = test, so
# test instances are contamination-proof by construction. Rows with a
# generator in `seon_inspect.generators.GENERATORS` freeze for real (status
# "generated": dev/milestone jsonl sha256s + the dev artifact at
# evals/<row>.dev.jsonl); the rest stay "pending-generator". Every row
# reserves its canary GUID so the CI guard covers them from day one. Sizes
# from eval-design "Capability rows".
BESPOKE_ROWS: dict[str, dict[str, Any]] = {
    "database_workflow": {"dev_n": 1, "epochs": 1},
    "namespace_workflow": {"dev_n": 1, "epochs": 1},
    "memory_store_recall": {"dev_n": 10, "epochs": 4},
    "long_term_planning": {"dev_n": 10, "epochs": 4},
    "clojure_codegen_specs": {"dev_n": 10, "epochs": 4},
    "shell_use": {"dev_n": 8, "epochs": 4},
    "web_fetch": {"dev_n": 8, "epochs": 4},
    "file_edit": {"dev_n": 8, "epochs": 4},
    "ui_tiles": {"dev_n": 5, "epochs": 2},
}

CANARY_PREFIX = "SEON-CANARY-"


class TierDisciplineError(Exception):
    """Raised when code touches a tier in a way its discipline forbids."""


# ---------------------------------------------------------------------------
# Deterministic draw
# ---------------------------------------------------------------------------


def _input_repr(inp: Any) -> Any:
    """Stable projection of a Sample.input for hashing.

    A plain-string input hashes as-is. A ChatMessage-list input (bfcl_ast) is
    projected to (role, text) pairs — the message objects' str() carries a
    RANDOM per-load uuid id, so hashing them directly would make the corpus
    fingerprint non-deterministic across freezes."""
    if isinstance(inp, str):
        return inp
    return [
        {"role": getattr(m, "role", None), "text": getattr(m, "text", str(m))}
        for m in inp
    ]


def content_hash(sample: Any) -> str:
    """Stable 16-hex content hash of a sample's substance.

    Multiple-choice samples hash input + SORTED choice texts and exclude the
    letter target — some benches (gpqa_diamond) shuffle choice order UNSEEDED
    at task construction, so choice order and the order-tied letter target are
    presentation, not content. Non-MC samples hash input + target.
    """
    if sample.choices:
        payload = {
            "input": _input_repr(sample.input),
            "choices": sorted(str(c) for c in sample.choices),
        }
    else:
        payload = {"input": _input_repr(sample.input), "target": sample.target}
    encoded = json.dumps(payload, sort_keys=True, ensure_ascii=False, default=str)
    return hashlib.sha256(encoded.encode()).hexdigest()[:16]


def ordered_draw(
    rows: list[dict[str, Any]], seed_key: str, stratify: bool
) -> list[dict[str, Any]]:
    """Return `rows` in the frozen draw order (see SAMPLING_PROCEDURE).

    `rows` items: {"id": <raw id>, "content": <hash>, "stratum": <label|None>}.
    Canonical pre-order = sort by str(id), so the draw is independent of
    upstream iteration order; the rng seed is the string seed_key (CPython
    seeds str deterministically, independent of PYTHONHASHSEED).
    """
    canonical = sorted(rows, key=lambda r: str(r["id"]))
    rng = random.Random(seed_key)
    if not stratify:
        drawn = list(canonical)
        rng.shuffle(drawn)
        return drawn
    groups: dict[str, list[dict[str, Any]]] = {}
    for r in canonical:
        groups.setdefault(str(r["stratum"]), []).append(r)
    strata = sorted(groups)
    rng.shuffle(strata)
    queues: dict[str, deque] = {}
    for s in strata:
        rng.shuffle(groups[s])
        queues[s] = deque(groups[s])
    ordered: list[dict[str, Any]] = []
    while any(queues[s] for s in strata):
        for s in strata:
            if queues[s]:
                ordered.append(queues[s].popleft())
    return ordered


def split_ids(
    ordered: list[dict[str, Any]], dev_n: int, milestone_n: int
) -> dict[str, list[Any]]:
    """Slice the frozen draw order into the three tiers' raw-id lists."""
    if len(ordered) <= dev_n + milestone_n:
        raise ValueError(
            f"dataset too small: {len(ordered)} samples for "
            f"dev={dev_n} + milestone={milestone_n} + a nonempty test reserve"
        )
    ids = [r["id"] for r in ordered]
    return {
        "dev": ids[:dev_n],
        "milestone": ids[dev_n : dev_n + milestone_n],
        "test": ids[dev_n + milestone_n :],
    }


def _ids_sha256(ids: list[Any]) -> str:
    joined = "\n".join(str(i) for i in sorted(ids, key=str))
    return hashlib.sha256(joined.encode()).hexdigest()


def _corpus_sha256(rows: list[dict[str, Any]]) -> str:
    joined = "\n".join(
        f"{r['id']}:{r['content']}" for r in sorted(rows, key=lambda r: str(r["id"]))
    )
    return hashlib.sha256(joined.encode()).hexdigest()


# ---------------------------------------------------------------------------
# Lock build / verify
# ---------------------------------------------------------------------------


def _freeze_task(name: str):
    """Load a source's upstream Task for FREEZING (dataset access only).

    Deliberately bypasses `load_bench_task`'s kind gate: the freeze needs
    every registered source's DATASET, including non-pod-door arms
    (swe_bench_verified, kind "swebench", is run-refused there but its
    sample corpus freezes exactly like any other). Same default_task_kwargs
    merge as load_bench_task, so freeze and run load the SAME set."""
    from seon_inspect.catalog import BENCHES  # deferred: heavy import

    spec = EXTERNAL_SOURCES[name]
    bench = BENCHES[name]
    defaults = bench.default_task_kwargs
    merged = {**(defaults() if defaults else {}), **spec["freeze_task_kwargs"]}
    return bench.task_fn()(**merged)


def excluded_ids(name: str) -> dict[str, str]:
    """A source's frozen id→reason exclusions ({} for most sources).

    Exclusions never re-shuffle: the seeded draw runs over the FULL corpus
    and excluded ids are filtered from the resulting sequence, so each
    exclusion just promotes the next id in the frozen order."""
    return dict(EXTERNAL_SOURCES[name].get("exclude_ids") or {})


def _apply_exclusions(name: str, ordered: list[dict[str, Any]]
                      ) -> list[dict[str, Any]]:
    excl = excluded_ids(name)
    if not excl:
        return ordered
    present = {str(r["id"]) for r in ordered}
    missing = set(excl) - present
    if missing:
        raise ValueError(
            f"{name}: exclude_ids not in the corpus: {sorted(missing)}")
    return [r for r in ordered if str(r["id"]) not in excl]


def _manifest_rows(name: str) -> list[dict[str, Any]]:
    """Load a manifest-corpus source's tasks as draw rows (offline, no harbor).

    A tb-2-style source freezes from a COMMITTED corpus manifest (evals/…json).
    The draw `content` folds the task-spec hash AND the authoritative amd64
    image digest, so a re-pushed environment image (new digest) diffs LOUDLY —
    the image IS the task environment for these prebuilt-image tasks."""
    spec = EXTERNAL_SOURCES[name]
    manifest = json.loads((REPO_ROOT / spec["manifest"]).read_text())
    rows: list[dict[str, Any]] = []
    for t in manifest["tasks"]:
        stratum = t.get(spec["stratify"]) if spec["stratify"] else None
        content = hashlib.sha256(
            (t["content_sha256"] + ":" + t["docker_image_digest"]).encode()
        ).hexdigest()[:16]
        rows.append({"id": t["id"], "content": content, "stratum": stratum})
    ids = [str(r["id"]) for r in rows]
    if len(set(ids)) != len(ids):
        raise ValueError(f"{name}: duplicate task ids — cannot freeze")
    return rows


def _dataset_rows(name: str) -> list[dict[str, Any]]:
    """Load a source's samples as draw rows (downloads/caches via inspect)."""
    spec = EXTERNAL_SOURCES[name]
    if spec.get("corpus") == "manifest":
        return _manifest_rows(name)
    task = _freeze_task(name)
    rows = []
    for s in task.dataset:
        stratum = None
        if spec["stratify"]:
            stratum = (s.metadata or {}).get(spec["stratify"])
        rows.append({"id": s.id, "content": content_hash(s), "stratum": stratum})
    ids = [str(r["id"]) for r in rows]
    if len(set(ids)) != len(ids):
        raise ValueError(f"{name}: duplicate sample ids — cannot freeze")
    return rows


def _pin_value(pin: tuple[str, str]) -> str:
    mod, attr = pin
    return str(getattr(importlib.import_module(mod), attr))


def _new_canary() -> str:
    return CANARY_PREFIX + str(uuid.uuid4())


def build_lock(existing: dict[str, Any] | None = None) -> dict[str, Any]:
    """Build the full lock dict. Deterministic except canary GUIDs, which are
    carried over from `existing` when present and minted only for new entries.
    """
    existing = existing or {}
    ex_sources = existing.get("sources", {})
    ex_bespoke = existing.get("bespoke", {})

    sources: dict[str, Any] = {}
    for name, spec in EXTERNAL_SOURCES.items():
        rows = _dataset_rows(name)
        ordered = ordered_draw(rows, f"{GLOBAL_SEED}:{name}", bool(spec["stratify"]))
        eligible = _apply_exclusions(name, ordered)
        tiers = split_ids(eligible, spec["dev_n"], spec["milestone_n"])
        canary = (
            ex_sources.get(name, {}).get("test", {}).get("canary_guid")
            or _new_canary()
        )
        if spec.get("corpus") == "manifest":
            manifest_path = REPO_ROOT / spec["manifest"]
            manifest = json.loads(manifest_path.read_text())
            pin = {
                "harbor_version": manifest["harbor_version"],
                "harbor_dataset_ref": manifest["harbor_dataset_ref"],
                "corpus_manifest": spec["manifest"],
                "corpus_manifest_sha256":
                    hashlib.sha256(manifest_path.read_bytes()).hexdigest(),
            }
        else:
            pin = {spec["pin"][0] + "." + spec["pin"][1]: _pin_value(spec["pin"])}
        sources[name] = {
            "bench": name,
            "capability_row": spec["capability_row"],
            "pin": pin,
            "seed": f"{GLOBAL_SEED}:{name}",
            "stratify": spec["stratify"],
            "total_n": len(rows),
            "content_sha256": _corpus_sha256(rows),
            "dev": {"n": len(tiers["dev"]), "sample_ids": tiers["dev"]},
            "milestone": {
                "n": len(tiers["milestone"]),
                "sample_ids": tiers["milestone"],
            },
            "test": {
                "n": len(tiers["test"]),
                "sample_ids_sha256": _ids_sha256(tiers["test"]),
                "canary_guid": canary,
            },
        }
        excl = excluded_ids(name)
        if excl:
            # Filtered from the draw sequence (never a re-shuffle); each id
            # carries its honest, non-difficulty reason.
            sources[name]["excluded"] = {
                "n": len(excl),
                "reasons": dict(sorted(excl.items())),
            }
        if spec.get("corpus") == "manifest":
            # The arm64 reality is a FIRST-CLASS lock fact: these prebuilt-image
            # tasks are amd64-only, so "arm64-runnable subset" = the emulated
            # full set, native subset = 0. Pinned so a later arm64 image push
            # (native subset > 0) surfaces as an intentional re-freeze.
            manifest = json.loads((REPO_ROOT / spec["manifest"]).read_text())
            sources[name]["arm64"] = {
                "native_runnable_n": manifest["n_native_arm64"],
                "total_n": manifest["n_tasks"],
                "note": manifest["arch_note"],
            }

    from seon_inspect import generators

    bespoke: dict[str, Any] = {}
    for row, spec in BESPOKE_ROWS.items():
        canary = ex_bespoke.get(row, {}).get("canary_guid") or _new_canary()
        entry: dict[str, Any] = {
            "status": "pending-generator",
            "dev_n": spec["dev_n"],
            "epochs": spec["epochs"],
            "dev_seed": 1,
            "milestone_seed": 2,
            "test_seed": "fresh-per-draw",
            "canary_guid": canary,
        }
        if row in generators.GENERATORS:
            dev = generators.rows_jsonl_bytes(
                generators.generate_rows(row, 1, spec["dev_n"]))
            milestone = generators.rows_jsonl_bytes(
                generators.generate_rows(row, 2, spec["dev_n"]))
            entry.update({
                "status": "generated",
                "generator": f"seon_inspect.generators:{row}",
                "artifact": f"evals/{row}.dev.jsonl",
                "dev_sha256": hashlib.sha256(dev).hexdigest(),
                "milestone_sha256": hashlib.sha256(milestone).hexdigest(),
            })
        bespoke[row] = entry

    lock = {
        "schema_version": LOCK_SCHEMA_VERSION,
        "global_seed": GLOBAL_SEED,
        "command": GENERATION_COMMAND,
        "sampling_procedure": SAMPLING_PROCEDURE,
        "sources": sources,
        "bespoke": bespoke,
    }
    # Pinned image digests (benchmark-suite design §3 freeze rule: the SEON
    # runtime-image digest + the bench instance-image digests are lock
    # entries — a row is comparable only within one seon-image digest).
    # These are RECORDED runtime facts (docker digest resolution at pin
    # time), carried over verbatim like canary GUIDs — never regenerated
    # from a dataset draw.
    if "image_pins" in existing:
        lock["image_pins"] = existing["image_pins"]
    return lock


def lock_bytes(lock: dict[str, Any]) -> bytes:
    return (json.dumps(lock, sort_keys=True, indent=2, ensure_ascii=False) + "\n").encode()


def read_lock(lock_path: Path = DEFAULT_LOCK_PATH) -> dict[str, Any]:
    return json.loads(lock_path.read_text())


def _diff_paths(a: Any, b: Any, path: str = "") -> list[str]:
    """Human-readable list of leaf paths where a != b."""
    if isinstance(a, dict) and isinstance(b, dict):
        out = []
        for k in sorted(set(a) | set(b)):
            p = f"{path}.{k}" if path else str(k)
            if k not in a:
                out.append(f"+ {p} (only regenerated)")
            elif k not in b:
                out.append(f"- {p} (only on disk)")
            else:
                out.extend(_diff_paths(a[k], b[k], p))
        return out
    if a != b:
        return [f"~ {path}: disk={a!r} regenerated={b!r}"]
    return []


def write_bespoke_artifacts(lock: dict[str, Any]) -> list[Path]:
    """Write each generated row's dev jsonl artifact (evals/<row>.dev.jsonl)."""
    from seon_inspect import generators

    written = []
    for row, entry in lock["bespoke"].items():
        if entry["status"] != "generated":
            continue
        data = generators.rows_jsonl_bytes(
            generators.generate_rows(row, entry["dev_seed"], entry["dev_n"]))
        path = REPO_ROOT / entry["artifact"]
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        written.append(path)
    return written


def _verify_bespoke_artifacts(lock: dict[str, Any]) -> list[str]:
    """Diff strings for dev artifacts on disk vs their locked sha256s."""
    diffs = []
    for row, entry in lock["bespoke"].items():
        if entry["status"] != "generated":
            continue
        path = REPO_ROOT / entry["artifact"]
        if not path.is_file():
            diffs.append(f"- {entry['artifact']} (artifact missing on disk)")
            continue
        got = hashlib.sha256(path.read_bytes()).hexdigest()
        if got != entry["dev_sha256"]:
            diffs.append(
                f"~ {entry['artifact']}: disk sha256={got} "
                f"locked={entry['dev_sha256']}")
    return diffs


def verify_lock(lock_path: Path = DEFAULT_LOCK_PATH) -> list[str]:
    """Regenerate with canaries carried over; return diffs vs disk ([] = ok).

    Covers the lock dict AND the generated dev artifacts (byte-identical
    regeneration is part of the freeze contract)."""
    on_disk = read_lock(lock_path)
    rebuilt = build_lock(existing=on_disk)
    return _diff_paths(on_disk, rebuilt) + _verify_bespoke_artifacts(rebuilt)


# ---------------------------------------------------------------------------
# Tier-disciplined loading
# ---------------------------------------------------------------------------


class DevSplit:
    """dev tier: frozen sample list, per-sample inspection is fine."""

    tier = "dev"

    def __init__(self, source: str, sample_ids: list[Any]):
        self.source = source
        self.sample_ids = list(sample_ids)
        self.n = len(self.sample_ids)

    def __iter__(self):
        return iter(self.sample_ids)

    def __repr__(self) -> str:
        return f"<DevSplit {self.source} n={self.n} ids={self.sample_ids!r}>"

    def _eval_sample_ids(self, positions: list[int] | None = None) -> list[Any]:
        return _position_subset(self.sample_ids, positions)


class MilestoneSplit:
    """milestone tier: runnable, AGGREGATE-ONLY. Ids are not enumerable
    through this API — run it via `run_split`, read only aggregate metrics."""

    tier = "milestone"

    def __init__(self, source: str, sample_ids: list[Any]):
        self.source = source
        self.__sample_ids = list(sample_ids)
        self.n = len(self.__sample_ids)

    def __iter__(self):
        raise TierDisciplineError(
            "milestone tier is aggregate-only: per-sample inspection is "
            "forbidden (eval-design). Run it via run_split() and read only "
            "aggregate metrics."
        )

    def __repr__(self) -> str:
        return f"<MilestoneSplit {self.source} n={self.n} aggregate-only>"

    def _eval_sample_ids(self, positions: list[int] | None = None) -> list[Any]:
        # For the harness runner only — per-sample RESULTS stay uninspected.
        return _position_subset(self.__sample_ids, positions)


class TestSplit:
    """test tier: the blind reserve, materialized only for a formal eval."""

    tier = "test"

    def __init__(self, source: str, sample_ids: list[Any], canary_guid: str):
        self.source = source
        self.__sample_ids = list(sample_ids)
        self.n = len(self.__sample_ids)
        self.canary_guid = canary_guid

    def __iter__(self):
        raise TierDisciplineError(
            "test tier is blind: per-sample inspection is forbidden."
        )

    def __repr__(self) -> str:
        return f"<TestSplit {self.source} n={self.n} blind>"

    def _eval_sample_ids(self, positions: list[int] | None = None) -> list[Any]:
        return _position_subset(self.__sample_ids, positions)


def _position_subset(values: list[Any],
                     positions: list[int] | None) -> list[Any]:
    """Runner-private positional projection with no held-out representation.

    Positions are canonicalized so membership is stable independent of caller
    ordering. The containing split still controls whether IDs are iterable or
    visible; milestone and test representations remain aggregate-only/blind.
    """
    if positions is None:
        return list(values)
    if not positions:
        raise ValueError("positions must select at least one sample")
    if any(isinstance(p, bool) or not isinstance(p, int) or p < 0
           for p in positions):
        raise ValueError("positions must be non-negative integers")
    ordered = sorted(set(positions))
    if ordered[-1] >= len(values):
        raise ValueError(
            f"position {ordered[-1]} outside split of {len(values)} samples")
    return [values[p] for p in ordered]


def load_split(
    source: str,
    tier: str,
    *,
    lock_path: Path = DEFAULT_LOCK_PATH,
    formal_eval: bool = False,
):
    """Load one frozen tier of one source from the lock, tier-disciplined.

    dev/milestone read straight from the lock. test RAISES unless
    `formal_eval=True`; the formal path recomputes the complement from the
    upstream dataset and checks its sha256 against the lock before returning.
    """
    lock = read_lock(lock_path)
    if source not in lock["sources"]:
        raise KeyError(f"{source!r} not in datasets.lock sources")
    entry = lock["sources"][source]
    if tier == "dev":
        return DevSplit(source, entry["dev"]["sample_ids"])
    if tier == "milestone":
        return MilestoneSplit(source, entry["milestone"]["sample_ids"])
    if tier == "test":
        if not formal_eval:
            raise TierDisciplineError(
                "test tier is the BLIND reserve — load_split(..., 'test') "
                "requires formal_eval=True (a formal eval, not iteration)."
            )
        spec = EXTERNAL_SOURCES[source]
        rows = _dataset_rows(source)
        ordered = ordered_draw(rows, entry["seed"], bool(spec["stratify"]))
        eligible = _apply_exclusions(source, ordered)
        tiers = split_ids(eligible, entry["dev"]["n"], entry["milestone"]["n"])
        got = _ids_sha256(tiers["test"])
        if got != entry["test"]["sample_ids_sha256"]:
            raise TierDisciplineError(
                f"{source}: recomputed test-split sha256 {got} != locked "
                f"{entry['test']['sample_ids_sha256']} — upstream dataset or "
                "procedure drifted; re-freeze deliberately before a formal eval."
            )
        return TestSplit(source, tiers["test"], entry["test"]["canary_guid"])
    raise KeyError(f"unknown tier {tier!r} (dev|milestone|test)")


def run_split(
    source: str,
    tier: str,
    *,
    lock_path: Path = DEFAULT_LOCK_PATH,
    formal_eval: bool = False,
    positions: list[int] | None = None,
    task_kwargs: dict[str, Any] | None = None,
    **run_kwargs: Any,
):
    """Run one frozen tier through run_bench (sample-id filtered, limit off).

    test tier: injects the canary GUID into each test sample's METADATA
    (never the agent-visible input) before the run — a canary escaping into
    repo/context/config then trips the CI grep.

    `positions` selects a deterministic subset inside this runner. Held-out
    split objects remain non-iterable and redact IDs from their representation;
    callers never need to reach into the private ID projection.
    """
    from seon_inspect.catalog import load_bench_task, run_bench

    split = load_split(source, tier, lock_path=lock_path, formal_eval=formal_eval)
    task = load_bench_task(source, **(task_kwargs or {}))
    selected_ids = split._eval_sample_ids(positions)
    if tier == "test":
        wanted = {str(i) for i in selected_ids}
        for s in task.dataset:
            if str(s.id) in wanted:
                if s.metadata is None:
                    s.metadata = {}
                s.metadata["seon_canary"] = split.canary_guid
    return run_bench(
        source,
        task=task,
        limit=None,
        sample_id=selected_ids,
        **run_kwargs,
    )


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--lock", type=Path, default=DEFAULT_LOCK_PATH)
    parser.add_argument(
        "--write",
        action="store_true",
        help="rewrite the lock after an INTENTIONAL spec change (canaries kept)",
    )
    args = parser.parse_args(argv)
    lock_path: Path = args.lock

    if lock_path.exists() and not args.write:
        diffs = verify_lock(lock_path)
        if not diffs:
            print(f"datasets.lock verified — no-op ({lock_path})")
            return 0
        print(f"datasets.lock DRIFT — {len(diffs)} difference(s) vs {lock_path}:")
        for d in diffs:
            print(f"  {d}")
        print("refusing to silently reshuffle; rerun with --write if intentional.")
        return 1

    existing = read_lock(lock_path) if lock_path.exists() else None
    lock = build_lock(existing=existing)
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    lock_path.write_bytes(lock_bytes(lock))
    for name, entry in lock["sources"].items():
        print(
            f"froze {name}: dev={entry['dev']['n']} "
            f"milestone={entry['milestone']['n']} test={entry['test']['n']} "
            f"(total {entry['total_n']}, seed {entry['seed']})"
        )
    for path in write_bespoke_artifacts(lock):
        print(f"wrote {path}")
    print(f"wrote {lock_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

"""Offline regression tests for the dataset freeze (procedure + lock + tiers).

These run WITHOUT network: the sampling procedure is proven on synthetic rows,
and the committed `evals/datasets.lock` is checked for internal consistency
and tier discipline. Full regenerate-verify against the upstream datasets is
the CLI act (`python -m seon_inspect.freeze` — no-op or loud diff), exercised
at freeze time, not per-test-run.
"""

from __future__ import annotations

import pytest

from seon_inspect import freeze
from seon_inspect.freeze import (
    DEFAULT_LOCK_PATH,
    EXTERNAL_SOURCES,
    GLOBAL_SEED,
    TierDisciplineError,
    load_split,
    ordered_draw,
    read_lock,
    split_ids,
)


def synthetic_rows(n=100, strata=4):
    return [
        {"id": f"s{i:03d}", "content": f"h{i}", "stratum": f"g{i % strata}"}
        for i in range(n)
    ]


# --- procedure -------------------------------------------------------------


def test_draw_is_deterministic():
    rows = synthetic_rows()
    a = ordered_draw(rows, f"{GLOBAL_SEED}:x", stratify=False)
    b = ordered_draw(list(reversed(rows)), f"{GLOBAL_SEED}:x", stratify=False)
    assert [r["id"] for r in a] == [r["id"] for r in b]  # input-order-proof
    c = ordered_draw(rows, f"{GLOBAL_SEED}:y", stratify=False)
    assert [r["id"] for r in a] != [r["id"] for r in c]  # seed matters


def test_stratified_draw_deterministic_and_covering():
    rows = synthetic_rows(n=100, strata=5)
    a = ordered_draw(rows, "seed", stratify=True)
    b = ordered_draw(rows, "seed", stratify=True)
    assert [r["id"] for r in a] == [r["id"] for r in b]
    # round-robin: the first 5 draws cover all 5 strata
    assert {r["stratum"] for r in a[:5]} == {f"g{i}" for i in range(5)}


def test_split_ids_disjoint_and_exhaustive():
    ordered = ordered_draw(synthetic_rows(), "seed", stratify=False)
    tiers = split_ids(ordered, 15, 15)
    assert len(tiers["dev"]) == 15 and len(tiers["milestone"]) == 15
    assert len(tiers["test"]) == 70
    all_ids = tiers["dev"] + tiers["milestone"] + tiers["test"]
    assert len(set(all_ids)) == 100


def test_split_ids_requires_test_remainder():
    ordered = ordered_draw(synthetic_rows(n=20), "seed", stratify=False)
    with pytest.raises(ValueError, match="too small"):
        split_ids(ordered, 15, 15)


def test_mc_content_hash_ignores_choice_order_and_letter_target():
    class S:
        input = "q?"
        choices = ["b", "a", "c"]
        target = "B"

    class T:
        input = "q?"
        choices = ["a", "b", "c"]
        target = "A"

    assert freeze.content_hash(S) == freeze.content_hash(T)


# --- the committed lock ----------------------------------------------------


@pytest.fixture(scope="module")
def lock():
    assert DEFAULT_LOCK_PATH.exists(), "evals/datasets.lock missing — run the freeze"
    return read_lock()


def test_lock_covers_every_configured_source(lock):
    assert set(lock["sources"]) == set(EXTERNAL_SOURCES)
    assert lock["global_seed"] == GLOBAL_SEED


def test_lock_tiers_sized_and_disjoint(lock):
    for name, entry in lock["sources"].items():
        spec = EXTERNAL_SOURCES[name]
        dev, mile = entry["dev"], entry["milestone"]
        assert dev["n"] == spec["dev_n"] == len(dev["sample_ids"])
        assert mile["n"] == spec["milestone_n"] == len(mile["sample_ids"])
        assert not set(map(str, dev["sample_ids"])) & set(map(str, mile["sample_ids"]))
        # Excluded ids (spent-as-probe / availability — reasons in the lock)
        # are filtered from the draw sequence before slicing, so they belong
        # to NO tier.
        spec_excl = spec.get("exclude_ids") or {}
        excluded = entry.get("excluded", {})
        if spec_excl:
            assert excluded == {"n": len(spec_excl),
                                "reasons": dict(sorted(spec_excl.items()))}
        else:
            assert "excluded" not in entry
        n_excluded = excluded.get("n", 0)
        assert entry["test"]["n"] == (
            entry["total_n"] - n_excluded - dev["n"] - mile["n"])
        assert entry["test"]["n"] > 0
        excluded_set = set((excluded.get("reasons") or {}).keys())
        assert not excluded_set & set(map(str, dev["sample_ids"]))
        assert not excluded_set & set(map(str, mile["sample_ids"]))
        # blind: the lock lists NO test ids, only their digest + canary
        assert "sample_ids" not in entry["test"]
        assert len(entry["test"]["sample_ids_sha256"]) == 64


def test_lock_records_provenance(lock):
    for entry in lock["sources"].values():
        assert entry["pin"], "upstream pin missing"
        assert entry["seed"].startswith(str(GLOBAL_SEED))
        assert len(entry["content_sha256"]) == 64
    assert lock["command"]
    assert lock["sampling_procedure"]


def test_lock_bespoke_rows_reserved_or_generated(lock):
    from seon_inspect import generators

    rows = lock["bespoke"]
    assert set(rows) == set(freeze.BESPOKE_ROWS)
    for name, row in rows.items():
        assert (row["dev_seed"], row["milestone_seed"]) == (1, 2)
        assert row["test_seed"] == "fresh-per-draw"
        if name in generators.GENERATORS:
            assert row["status"] == "generated"
            assert row["generator"] == f"seon_inspect.generators:{name}"
            assert row["artifact"] == f"evals/{name}.dev.jsonl"
            assert len(row["dev_sha256"]) == 64
            assert len(row["milestone_sha256"]) == 64
            assert row["dev_sha256"] != row["milestone_sha256"]
        else:
            assert row["status"] == "pending-generator"


# --- tier discipline -------------------------------------------------------


def test_dev_split_is_open():
    split = load_split("gsm8k", "dev")
    ids = list(split)
    assert len(ids) == 15 and split.n == 15
    assert repr(split).startswith("<DevSplit gsm8k")


def test_milestone_split_is_aggregate_only():
    split = load_split("gsm8k", "milestone")
    assert split.n == 15
    assert "aggregate-only" in repr(split)
    # no ids in the repr, no iteration
    lock = read_lock()
    for sid in lock["sources"]["gsm8k"]["milestone"]["sample_ids"]:
        assert str(sid) not in repr(split)
    with pytest.raises(TierDisciplineError):
        list(split)


def test_test_split_is_blind_by_default():
    with pytest.raises(TierDisciplineError, match="BLIND"):
        load_split("gsm8k", "test")


def test_unknown_tier_rejected():
    with pytest.raises(KeyError):
        load_split("gsm8k", "validation")

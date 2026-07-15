"""Offline regression tests for the tool-row generators + their oracle scorers.

Proves, without any pod or external network: determinism (same seed →
byte-identical rows), goal-stated-ness (no Seon function/namespace coaching in
any task text), placeholder rendering, the lock↔artifact contract, and the
oracle checks against known-good and known-bad synthetic transcripts /
workspaces. The Clojure-target checks (parse + behavioral) go through the
REAL bb/node oracles, same as the existing scorer suite.
"""

from __future__ import annotations

import hashlib
import json
import urllib.request

import pytest

from seon_inspect import freeze, generators
from seon_inspect.generators import (WS, FX, GENERATORS, fresh_test_rows,
                                     generate_rows, materialize_setup,
                                     read_rows_jsonl, render_input,
                                     rows_jsonl_bytes, serve_fixtures)
from seon_inspect.tool_scorers import check_answer, check_workspace

ROWS = sorted(GENERATORS)
DEV_N = {row: freeze.BESPOKE_ROWS[row]["dev_n"] for row in ROWS}
FILESYSTEM_SURFACE_ROWS = {
    "F1": (8, "c6da785d8cd721e3874de18d620b323113cb5635f71cdc3a7d4b058e43bae838"),
    "F2": (1, "4de2b65f417cce1959139ea28551a4c14b3654f89bae825642820e13a0347583"),
    "F3": (9, "97e99e027d79184b290e53d0843bcd402f2407d97630cee149851c9a0b05c3ae"),
    "F4": (4, "0028df9d1a02b00a5785fce9a10c75709948b9014308c711dfb5178bcd919734"),
}

# Goal-stated means NO API coaching: task text never names Seon functions,
# namespaces, or tool functions — the agent discovers its tools from context.
COACHING_MARKERS = [
    "my.plan", "my.kb", "my.blob", "my.ui", "my.canvas", "my.data",
    "seon.", "shell/exec", "web/fetch", "edit-file", "fs/", "(exec",
    "clojure fn", "capability", "toolkit", "(in-ns", "schema/register!",
    "db/transact!", "db/query", "(require", "message/user", "(complete",
]


def all_rows(row):
    out = []
    for seed in (1, 2):
        out += generate_rows(row, seed, DEV_N[row])
    return out


# --- determinism -------------------------------------------------------------


@pytest.mark.parametrize("row", ROWS)
def test_same_seed_byte_identical(row):
    a = rows_jsonl_bytes(generate_rows(row, 1, DEV_N[row]))
    b = rows_jsonl_bytes(generate_rows(row, 1, DEV_N[row]))
    assert a == b
    c = rows_jsonl_bytes(generate_rows(row, 2, DEV_N[row]))
    assert a != c  # seed matters


@pytest.mark.parametrize("row", ROWS)
def test_dev_milestone_ids_disjoint(row):
    dev = {r["id"] for r in generate_rows(row, 1, DEV_N[row])}
    mile = {r["id"] for r in generate_rows(row, 2, DEV_N[row])}
    assert not dev & mile


def test_fresh_test_draw_never_reuses_frozen_seeds():
    # A synthetic non-formal seed proves replay without opening the blind tier.
    seed, rows = fresh_test_rows("shell_use", 4, seed=3)
    assert seed not in (1, 2)
    assert len(rows) == 4
    # replayable given the recorded seed
    assert rows == generate_rows("shell_use", seed, 4)


# --- goal-stated, never API-coached ------------------------------------------


@pytest.mark.parametrize("row", ROWS)
def test_task_text_is_goal_stated(row):
    for sample in all_rows(row):
        # EVERY agent-visible phase text (long_term_planning delivers a
        # second message after the restart — scan it too).
        texts = [sample["input"],
                 sample["metadata"].get("phase2_input", "")]
        for text in texts:
            low = text.lower()
            for marker in COACHING_MARKERS:
                assert marker not in low, (
                    f"{sample['id']}: task text coaches the API "
                    f"({marker!r}): {text}")


@pytest.mark.parametrize("row", ROWS)
def test_placeholders_render_completely(row):
    for sample in all_rows(row):
        rendered = render_input(sample, workspace="/tmp/ws",
                                fixture_url="http://127.0.0.1:9/")
        assert WS not in rendered and FX not in rendered


def test_render_input_refuses_missing_placeholder_value():
    sample = generate_rows("shell_use", 1, 1)[0]
    with pytest.raises(ValueError, match="workspace"):
        render_input(sample)


# --- lock ↔ artifact contract -------------------------------------------------


@pytest.mark.parametrize("row", ROWS)
def test_lock_hashes_match_regeneration_and_artifact(row):
    entry = freeze.read_lock()["bespoke"][row]
    assert entry["status"] == "generated"
    dev = rows_jsonl_bytes(generate_rows(row, 1, entry["dev_n"]))
    assert hashlib.sha256(dev).hexdigest() == entry["dev_sha256"]
    mile = rows_jsonl_bytes(generate_rows(row, 2, entry["dev_n"]))
    assert hashlib.sha256(mile).hexdigest() == entry["milestone_sha256"]
    artifact = freeze.REPO_ROOT / entry["artifact"]
    assert artifact.read_bytes() == dev
    assert read_rows_jsonl(artifact) == generate_rows(row, 1, entry["dev_n"])


# --- oracle: shell_use (workspace outcomes) -----------------------------------


def _solve_workspace_checks(ws, oracle):
    """The reference 'perfect agent': materialize every stated outcome."""
    for check in oracle["checks"]:
        p = ws / check["path"]
        if check.get("absent"):
            if p.exists():
                p.unlink()
        elif "equals" in check:
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(check["equals"])


@pytest.mark.parametrize("index", range(8))
def test_shell_oracle_known_good_and_bad(tmp_path, index):
    sample = generate_rows("shell_use", 1, 8)[index]
    oracle = sample["metadata"]["oracle"]

    bad_ws = tmp_path / "bad"
    bad_ws.mkdir()
    materialize_setup(sample, bad_ws)  # setup alone = task not done
    assert check_workspace(bad_ws, oracle)["ok"] is False

    good_ws = tmp_path / "good"
    good_ws.mkdir()
    materialize_setup(sample, good_ws)
    _solve_workspace_checks(good_ws, oracle)
    res = check_workspace(good_ws, oracle)
    assert res["ok"] is True, res["failures"]


def test_shell_oracle_rejects_wrong_content(tmp_path):
    sample = generate_rows("shell_use", 1, 1)[0]  # line-count template
    oracle = sample["metadata"]["oracle"]
    materialize_setup(sample, tmp_path)
    _solve_workspace_checks(tmp_path, oracle)
    # corrupt the outcome: off-by-one count
    check = oracle["checks"][0]
    wrong = str(int(check["equals"].strip()) + 1) + "\n"
    (tmp_path / check["path"]).write_text(wrong)
    res = check_workspace(tmp_path, oracle)
    assert res["ok"] is False and "mismatch" in res["failures"][0]


# --- oracle: file_edit (incl. the code-target parse + behavioral checks) ------


@pytest.mark.parametrize("index", range(8))
def test_file_edit_oracle_known_bad_is_start_state(tmp_path, index):
    sample = generate_rows("file_edit", 1, 8)[index]
    materialize_setup(sample, tmp_path)
    # the UNEDITED starting file must fail — else the task is vacuous
    res = check_workspace(tmp_path, sample["metadata"]["oracle"])
    assert res["ok"] is False, f"{sample['id']} passes with no edit"


@pytest.mark.parametrize("index", [0, 1, 2, 4, 5, 6, 7])
def test_file_edit_oracle_known_good_exact_targets(tmp_path, index):
    sample = generate_rows("file_edit", 1, 8)[index]
    oracle = sample["metadata"]["oracle"]
    materialize_setup(sample, tmp_path)
    _solve_workspace_checks(tmp_path, oracle)
    res = check_workspace(tmp_path, oracle)
    assert res["ok"] is True, res["failures"]


def test_file_edit_behavioral_known_good_and_bad(tmp_path):
    sample = generate_rows("file_edit", 1, 8)[3]  # the fix-the-mean template
    oracle = sample["metadata"]["oracle"]
    beh = next(c["behavioral"] for c in oracle["checks"] if "behavioral" in c)
    fn = beh["fn_name"]
    path_rel = next(c["path"] for c in oracle["checks"] if "behavioral" in c)
    materialize_setup(sample, tmp_path)

    # known-bad: unedited buggy file fails behavioral
    assert check_workspace(tmp_path, oracle)["ok"] is False

    # known-good: a correct mean implementation (textually different from
    # anything the generator knows — outcome-scored, not string-matched)
    (tmp_path / path_rel).write_text(
        "(defn " + fn + "\n  \"Mean of a vector of numbers.\"\n  [v]\n"
        "  (/ (reduce + v) (count v)))\n")
    res = check_workspace(tmp_path, oracle)
    assert res["ok"] is True, res["failures"]

    # a parse-broken edit fails loudly
    (tmp_path / path_rel).write_text("(defn " + fn + " [v] (/ 1")
    assert check_workspace(tmp_path, oracle)["ok"] is False


def test_filesystem_surface_rows_have_exact_golden_bytes_and_reuse_f2_f4():
    rows = generate_rows("file_edit", 1, 10)
    for name, (position, expected_sha) in FILESYSTEM_SURFACE_ROWS.items():
        sample = rows[position]
        assert sample["id"] == f"file_edit-seed1-{position:03d}"
        assert hashlib.sha256(rows_jsonl_bytes([sample])).hexdigest() == \
            expected_sha, name

    assert "2.7.3" in rows[1]["input"] and "7.1.2" in rows[1]["input"]
    assert '" :replicas 9"' in rows[4]["input"]
    assert rows[8]["metadata"]["setup"] != rows[1]["metadata"]["setup"]
    assert rows[9]["metadata"]["setup"] != rows[4]["metadata"]["setup"]


def test_filesystem_candidates_do_not_change_frozen_file_edit_membership():
    entry = freeze.read_lock()["bespoke"]["file_edit"]
    assert entry["dev_n"] == 8
    first_ten = generate_rows("file_edit", 1, 10)
    locked_bytes = rows_jsonl_bytes(first_ten[:entry["dev_n"]])
    artifact = freeze.REPO_ROOT / entry["artifact"]
    assert locked_bytes == artifact.read_bytes()
    assert hashlib.sha256(locked_bytes).hexdigest() == entry["dev_sha256"]


@pytest.mark.parametrize("position", [8, 9])
def test_filesystem_candidate_text_is_goal_stated(position):
    sample = generate_rows("file_edit", 1, 10)[position]
    low = sample["input"].lower()
    for marker in COACHING_MARKERS:
        assert marker not in low, (
            f"{sample['id']}: task text coaches the API ({marker!r})"
        )


def test_f1_oracle_discriminates_discovery_parse_and_behavior(tmp_path):
    sample = generate_rows("file_edit", 1, 9)[8]
    oracle = sample["metadata"]["oracle"]
    materialize_setup(sample, tmp_path)

    unedited = check_workspace(tmp_path, oracle)
    assert unedited["ok"] is False
    assert any("returned 70" in failure for failure in unedited["failures"])

    _solve_workspace_checks(tmp_path, oracle)
    assert check_workspace(tmp_path, oracle)["ok"] is True

    (tmp_path / "src/report.cljs").write_text("(defn tax-total [rows]")
    broken = check_workspace(tmp_path, oracle)
    assert broken["ok"] is False
    assert any("does not parse" in failure for failure in broken["failures"])


def test_f3_oracle_rejects_global_guess_and_invalid_edn(tmp_path):
    sample = generate_rows("file_edit", 1, 10)[9]
    oracle = sample["metadata"]["oracle"]
    materialize_setup(sample, tmp_path)

    config = tmp_path / "config.edn"
    config.write_text(config.read_text().replace(":retries 3", ":retries 6"))
    global_guess = check_workspace(tmp_path, oracle)
    assert global_guess["ok"] is False
    assert not any("does not parse" in f for f in global_guess["failures"])

    _solve_workspace_checks(tmp_path, oracle)
    assert check_workspace(tmp_path, oracle)["ok"] is True

    config.write_text(config.read_text().removesuffix("]\n"))
    broken = check_workspace(tmp_path, oracle)
    assert broken["ok"] is False
    assert any("does not parse" in failure for failure in broken["failures"])


# --- oracle: web_fetch (fixture ground truth) ----------------------------------


@pytest.mark.parametrize("index", range(8))
def test_web_fetch_answer_known_good_and_bad(index):
    sample = generate_rows("web_fetch", 1, 8)[index]
    oracle = sample["metadata"]["oracle"]
    answer = oracle["answer"]
    assert sample["target"] == answer

    # known-good synthetic transcripts: bare answer + conversational wrapper
    assert check_answer(answer, oracle)["ok"] is True
    assert check_answer(f"Looked it up.\nThe answer is {answer}",
                        oracle)["ok"] is True

    # known-bad: a different value of the same shape
    wrong = str(int(answer) + 1) if oracle["kind"] == "integer" else "Zzyzx"
    assert check_answer(wrong, oracle)["ok"] is False
    assert check_answer("", oracle)["ok"] is False


def test_web_fetch_fixtures_serve_locally(tmp_path):
    """End-to-end fixture pipeline, loopback only: materialize → serve →
    fetch → the ground-truth answer is really on the page."""
    sample = generate_rows("web_fetch", 1, 1)[0]
    materialize_setup(sample, tmp_path)
    with serve_fixtures(tmp_path) as base:
        rendered = render_input(sample, fixture_url=base)
        assert base in rendered
        page = next(iter(sorted(sample["metadata"]["setup"])))
        html = urllib.request.urlopen(f"{base}/{page}", timeout=5).read()
        assert sample["metadata"]["oracle"]["answer"] in html.decode()


def test_web_fetch_fixture_answer_grounded():
    """Non-derived ground-truth values appear verbatim in the fixtures;
    derived answers (counts/sums, oracle["derived"]) are computed from them."""
    for sample in all_rows("web_fetch"):
        oracle = sample["metadata"]["oracle"]
        if oracle.get("derived"):
            continue
        joined = "\n".join(sample["metadata"]["setup"].values())
        assert oracle["answer"] in joined, sample["id"]


def test_web_fetch_text_answer_rejects_ambiguity():
    """A reply naming the answer AND a distractor scores wrong."""
    sample = next(s for s in all_rows("web_fetch")
                  if s["metadata"]["oracle"].get("kind") == "text")
    oracle = sample["metadata"]["oracle"]
    ambiguous = f"{oracle['answer']} or maybe {oracle['distractors'][0]}"
    assert check_answer(ambiguous, oracle)["ok"] is False


# --- artifacts stay clean -------------------------------------------------------


@pytest.mark.parametrize("row", ROWS)
def test_no_canary_in_generated_rows(row):
    """Canary GUIDs live in the lock only — never inside generated samples
    (they are injected into test-sample METADATA at formal-eval time)."""
    canary = freeze.read_lock()["bespoke"][row]["canary_guid"]
    assert canary not in rows_jsonl_bytes(all_rows(row)).decode()


@pytest.mark.parametrize("row", ROWS)
def test_rows_are_json_round_trippable(row):
    for sample in all_rows(row):
        assert json.loads(json.dumps(sample)) == sample
        assert sample["metadata"]["row"] == row

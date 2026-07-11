"""Typeahead-replay bench — offline proofs of the pure machinery.

Section extraction, menu→offers parsing, the arm render builder (budget +
arm deltas), the outcome analysis against the REAL bb/node oracles, the
step-trace metrics, and the kill-criteria verdicts. No model, no cluster.
"""

import pytest

from seon_inspect.tasks import typeahead_replay as tr
from seon_inspect.typeahead_corpus import extract_section, offers_from_menu

PROMPT = """header text

;;; ┌─ namespace my.plan ─
; (my.plan/add! [{:my.plan/keys [title]}] …) — Add a durable plan step.
;;; └─ end namespace my.plan ─

;;; ┌─ recent-verbs ─
; recent verbs — the fns you have been calling, most-used first.
; A MENU, never a mandate: select an entry by outputting its glyph
; alone (e.g. ①), or ignore this and write any Clojure — both work.
; ① (my.plan/done! [{:my.plan/keys [id]}] …) — Mark a step done; may unblock its dependents next turn.
; ② (seon.agent.message/user [content] …) — Send a message to your human.
;;; └─ end recent-verbs ─

;;; ┌─ plan-ledger ─
; ☐ ① do the thing
;;; └─ end plan-ledger ─
"""


def test_extract_section_verbatim():
    sec = extract_section(PROMPT, "recent-verbs")
    assert sec.startswith(";;; ┌─ recent-verbs ─")
    assert sec.endswith(";;; └─ end recent-verbs ─")
    assert "my.plan/done!" in sec
    assert extract_section(PROMPT, "absent") is None


def test_offers_from_menu_glyph_aligned():
    offers = offers_from_menu(extract_section(PROMPT, "recent-verbs"))
    assert [o["glyph"] for o in offers] == ["①", "②"]
    assert offers[0]["template"] == [["clamp", "(my.plan/done! "],
                                     ["free", 24], ["clamp", ")"]]
    assert offers[1]["label"].startswith("seon.agent.message/user ")


SAMPLE = {
    "id": "t1", "intent": "do the thing",
    "contract_nses": ["my.plan"],
    "sections": {
        "namespace my.plan": extract_section(PROMPT, "namespace my.plan"),
        "recent-verbs": extract_section(PROMPT, "recent-verbs"),
        "plan-ledger": extract_section(PROMPT, "plan-ledger"),
    },
    "offers": offers_from_menu(extract_section(PROMPT, "recent-verbs")),
    "predicate": {"kind": "verb-call", "heads": [],
                  "head_nses": ["my.plan", "plan"]},
}


def test_build_render_arm_deltas_and_budget():
    r1 = tr.build_render(SAMPLE, "arm1_guided")
    r2 = tr.build_render(SAMPLE, "arm2_typeahead")
    r3 = tr.build_render(SAMPLE, "arm3_degraded")
    # menus in 2/3 only; 2 and 3 byte-identical (machinery is the only diff)
    assert "recent verbs" not in r1 and "☐ ①" not in r1
    assert r2 == r3
    assert "recent verbs" in r2 and "☐ ①" in r2
    # contracts + task identical across arms
    assert "namespace my.plan" in r1 and "namespace my.plan" in r2
    assert SAMPLE["intent"] in r1 and SAMPLE["intent"] in r2
    assert tr.token_estimate(r2) <= tr.RENDER_BUDGET_TOKENS


def test_menu_teaching_refresh_additive_and_idempotent():
    sec = SAMPLE["sections"]["recent-verbs"]
    ref = tr.refresh_menu_teaching(sec)
    assert tr.MENU_TEACHING_ADDENDUM in ref, "P5 example lines appended"
    assert offers_from_menu(ref) == offers_from_menu(sec), \
        "glyph entries stay byte-verbatim (only the teaching is refreshed)"
    assert tr.refresh_menu_teaching(ref) == ref, "idempotent"
    # the refreshed teaching is what arms 2/3 actually render
    assert tr.MENU_TEACHING_ADDENDUM in tr.build_render(SAMPLE,
                                                        "arm2_typeahead")


def test_build_null_render_same_sections_minus_intent():
    r2 = tr.build_render(SAMPLE, "arm2_typeahead")
    n2 = tr.build_null_render(SAMPLE, "arm2_typeahead")
    task_line = ";;; ◀ from user (NEW — unanswered; respond to this)"
    assert task_line in r2 and task_line not in n2
    assert "recent verbs" in n2, "the offer scaffolding stays"
    assert "☐ ①" not in n2, \
        "the plan-ledger (intent-derived — it restates the task) is dropped"
    contract = tr.CONTRACT_LINES[SAMPLE["predicate"]["kind"]]
    assert contract in r2 and contract not in n2
    assert n2.endswith("my.agent=> "), "the bare cursor closes the null"


def test_render_budget_enforced():
    fat = dict(SAMPLE, sections={**SAMPLE["sections"],
                                 "namespace my.plan": "; x" * 20000})
    with pytest.raises(ValueError, match="tokens"):
        tr.build_render(fat, "arm1_guided")


def test_call_heads_and_verb_match():
    code = '(do (my.plan/add! {:my.plan/title "x"}) (str "(not/a-call)"))'
    heads = tr.call_heads(code)
    assert "my.plan/add!" in heads and "not/a-call" not in heads
    assert tr.verb_match(code, SAMPLE["predicate"])
    assert not tr.verb_match("(println 1)", SAMPLE["predicate"])


def test_analyze_reply_eval_answer_real_oracles():
    a = tr.analyze_reply("(def x 25)\n(* x 26)",
                         {"kind": "eval-answer", "expect": ["650"]})
    assert a["parses"] and a["outcome_pass"] and a["got"] == "650"
    # seon-verb forms are skipped, the pure tail still answers
    b = tr.analyze_reply("(my.plan/add! {:my.plan/title \"x\"})\n(+ 40 4)",
                         {"kind": "eval-answer", "expect": ["44"]})
    assert b["outcome_pass"]
    c = tr.analyze_reply("(+ 1", {"kind": "eval-answer", "expect": ["2"]})
    assert not c["parses"] and not c["outcome_pass"]
    # production-convention delivery: answer via a message verb / prose
    d = tr.analyze_reply(';; 42 + 2\n(message/user "44")',
                         {"kind": "eval-answer", "expect": ["44"]})
    assert d["outcome_pass"] and not d["eval_match"]
    # a wrong answer never passes on a substring (token boundary)
    e = tr.analyze_reply('(message/user "440")',
                         {"kind": "eval-answer", "expect": ["44"]})
    assert not e["outcome_pass"]


def test_analyze_reply_verb_call_real_oracles():
    a = tr.analyze_reply("(my.plan/add! {:my.plan/title \"audit\"})",
                         SAMPLE["predicate"])
    assert a["parses"] and a["verb_match"] and a["outcome_pass"]
    # right verb inside a BROKEN reply fails the parse gate
    b = tr.analyze_reply("(my.plan/add! {:my.plan/title \"audit\"}",
                         SAMPLE["predicate"])
    assert not b["outcome_pass"]


def test_step_metrics_uptake_and_lock():
    steps = [
        {"idx": 0, "transition": "expand", "glyph": "①", "auto": True,
         "locked_n": 0, "gen_s": 1.0, "margin": 7.2},
        {"idx": 1, "transition": "progress", "glyph": None, "auto": False,
         "locked_n": 1, "gen_s": 2.0},
    ]
    m = tr.step_metrics(steps)
    assert m["uptake"] == 0.5
    assert m["glyph_selections"] == 1
    assert m["auto_offers"] == 1
    assert m["margins"] == [7.2]
    assert m["rounds_to_lock"] == 2
    assert m["step_s_mean"] == 1.5


class _ScriptedEP:
    """Stub worker endpoint: returns scripted step outputs, records
    every payload (the P6 suppression proof)."""

    def __init__(self, outputs):
        self.outputs = list(outputs)
        self.payloads = []

    def call(self, payload):
        self.payloads.append(payload)
        return self.outputs.pop(0)


def test_step_loop_suppresses_failed_offer():
    # P6: an expand that locks NOTHING suppresses that glyph's offer for
    # the rest of the call (the P5 p1 trace re-fired the identical failed
    # auto-offer 4x — the stateless worker cannot remember; the loop must).
    offers = [{"glyph": "①", "label": "a", "template": [["clamp", "(a )"]]},
              {"glyph": "②", "label": "b", "template": [["clamp", "(b )"]]}]
    ep = _ScriptedEP([
        {"transition": "expand", "glyph": "①", "locked": [],
         "new_draft": "", "events": [{"event": "expand-failed",
                                      "glyph": "①"}]},
        {"transition": "stuck", "locked": [], "new_draft": ""},
        {"transition": "stuck", "locked": [], "new_draft": ""},
    ])
    r = tr.run_step_loop(ep, "render", offers, seed=1, null_render="null")
    assert r["outcome"] == "gave-up"
    assert [o["glyph"] for o in ep.payloads[0]["offers"]] == ["①", "②"]
    assert [o["glyph"] for o in ep.payloads[1]["offers"]] == ["②"]
    assert [o["glyph"] for o in ep.payloads[2]["offers"]] == ["②"]


def test_step_loop_expand_that_locks_is_not_suppressed():
    offers = [{"glyph": "①", "label": "a", "template": [["clamp", "(a )"]]}]
    ep = _ScriptedEP([
        {"transition": "expand", "glyph": "①", "locked": ["(a 1)"],
         "new_draft": ""},
        {"transition": "done", "locked": [], "new_draft": ""},
    ])
    r = tr.run_step_loop(ep, "render", offers, seed=1)
    assert r["outcome"] == "done"
    assert r["text"] == "(a 1)"
    assert [o["glyph"] for o in ep.payloads[1]["offers"]] == ["①"]


def test_kill_criteria_verdicts():
    leak = tr.kill_criteria({
        "arm1_guided": {"outcome_mean": 0.8},
        "arm2_typeahead": {"outcome_mean": 0.8, "uptake_mean": 0.0},
        "arm3_degraded": {"outcome_mean": 0.5}})
    assert "LEAK" in leak["protocol_leak"]["verdict"]
    assert "DEAD WEIGHT" in leak["dead_weight"]["verdict"]
    clean = tr.kill_criteria({
        "arm1_guided": {"outcome_mean": 0.6},
        "arm2_typeahead": {"outcome_mean": 0.8, "uptake_mean": 0.4},
        "arm3_degraded": {"outcome_mean": 0.6}})
    assert clean["protocol_leak"]["verdict"] == "clean"
    assert clean["dead_weight"]["verdict"] == "earns its render"

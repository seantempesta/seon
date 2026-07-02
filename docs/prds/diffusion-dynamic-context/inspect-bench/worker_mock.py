"""Canned offline worker for the diffusion inspect tasks — ZERO GPU.

Port of the proven kill-gate mock (tmp/flash-diffgemma/e1_mock.py — gitignored
scratch; this committed port is the standard home). The canned texts are REAL
Clojure: the bb oracle parses them and the node bundle evals them for real, so
an offline `inspect eval` exercises the full scoring path end-to-end. Only the
GPU transport is mocked.

Scenarios (E1): guided_wins / guided_ties / semantic_trap / refine_converges /
refine_stuck — each drives the decision rule in a direction that matters.
Extra modes for the other tasks: `_cond` (skill-lift control/treatment pools)
and `mode:"refine_loop"` (the ladder-lift ON arm, with stop-tier + metrics).
"""

from __future__ import annotations

FAITHFUL = """```clojure
(schema/register! ::celsius->fahrenheit-request [:map [::celsius :double]])
(schema/register! ::celsius->fahrenheit-response [:map [::fahrenheit :double]])
(defn celsius->fahrenheit
  {:malli/schema [:=> [:cat ::celsius->fahrenheit-request] ::celsius->fahrenheit-response]}
  [{::keys [celsius]}]
  {::fahrenheit (+ 32 (* celsius 1.8))})
```"""

NAKED_PLAIN = """```clojure
(defn celsius->fahrenheit [c]
  (+ 32 (* c 1.8)))
```"""

VACUOUS = """```clojure
(schema/register! ::celsius->fahrenheit-request [:map])
(schema/register! ::celsius->fahrenheit-response [:map])
(defn celsius->fahrenheit
  {:malli/schema [:=> [:cat :any] :any]}
  [{::keys [celsius]}]
  {::fahrenheit (+ 32 (* celsius 1.8))})
```"""

SEMANTIC = """```clojure
(schema/register! ::celsius->fahrenheit-request [:map [::celsius :double]])
(schema/register! ::celsius->fahrenheit-response [:map [::fahrenheit :double]])
(defn celsius->fahrenheit
  {:malli/schema [:=> [:cat ::celsius->fahrenheit-request] ::celsius->fahrenheit-response]}
  [{::keys [celsius]}]
  {::fahrenheit (helper celsius)})
```"""

TRANSDUCER = """```clojure
(schema/register! ::celsius->fahrenheit-request [:map [::celsius :double]])
(schema/register! ::celsius->fahrenheit-response [:map [::fahrenheit :double]])
(defn celsius->fahrenheit
  {:malli/schema [:=> [:cat ::celsius->fahrenheit-request] ::celsius->fahrenheit-response]}
  [{::keys [celsius]}]
  (map (fn [{:keys [celsius]}] {::fahrenheit (+ 32 (* celsius 1.8))})))
```"""

HALLUCINATED = """```clojure
(s/def ::celsius double?)
(defn celsius->fahrenheit [c]
  (+ 32 (* c 1.8)))
```"""

BROKEN = """```clojure
(defn celsius->fahrenheit
  {:malli/schema [:=> [:cat ::celsius->fahrenheit-request] ::resp]
  [{::keys [celsius]}]
  {::fahrenheit (+ 32 (* celsius 1.8
```"""

TEXT = {"faithful": FAITHFUL, "naked_plain": NAKED_PLAIN, "vacuous": VACUOUS,
        "semantic": SEMANTIC, "hallucinated": HALLUCINATED, "broken": BROKEN,
        "transducer": TRANSDUCER}

BODY = {
    "good": "{::fahrenheit (+ 32 (* celsius 1.8))}",
    "broken": "{::fahrenheit (+ 32 (* celsius 1.8",
    "semantic": "{::fahrenheit (helper celsius)}",
    "rambling": ("{::fahrenheit (+ 32 (* celsius 1.8))} "
                 ";; Wait! actually here is a cleaner way to express this"),
}

GARBAGE_ASSEMBLED = (
    "(schema/register! ::celsius->fahrenheit-request [:map [::celsius :double]])\n"
    "(schema/register! ::celsius->fahrenheit-response [:map [::fahrenheit :double]])\n"
    "(defn celsius->fahrenheit\n"
    "  {:malli/schema [:=> [:cat ::celsius->fahrenheit-request] ::celsius->fahrenheit-response]}\n"
    "  [{::keys [celsius]}]\n"
    "  {::fahrenheit <pad><mask> (* celsius 1.8 <end_of_turn><pad>")

SCENARIOS = {
    "guided_wins": {
        "arm1": ["good", "good", "good", "good", "good", "broken"],
        "arm2": ["naked_plain", "naked_plain", "faithful", "naked_plain", "broken", "hallucinated"],
        "arm3": ["naked_plain", "naked_plain", "faithful", "naked_plain", "broken", "hallucinated"],
        "repair_body": "good",
        "arm1_refine": [["rambling", "broken", "good"], ["rambling", "good"], ["good"],
                        ["broken", "good"], ["rambling", "rambling", "good"],
                        ["rambling", "broken", "good"]],
    },
    "guided_ties": {
        "arm1": ["good", "good", "semantic", "good", "semantic", "semantic"],
        "arm2": ["faithful", "faithful", "vacuous", "vacuous", "naked_plain", "broken"],
        "arm3": ["faithful", "faithful", "faithful", "vacuous", "vacuous", "broken"],
        "repair_body": "semantic",
    },
    "semantic_trap": {
        "arm1": ["semantic", "semantic", "semantic", "semantic", "good", "good"],
        "arm2": ["semantic", "vacuous", "hallucinated", "broken", "faithful", "faithful"],
        "arm3": ["semantic", "vacuous", "hallucinated", "broken", "faithful", "faithful"],
        "repair_body": "semantic",
    },
    "refine_converges": {
        "arm1": ["rambling"] * 6,
        "arm2": ["naked_plain", "naked_plain", "faithful", "naked_plain", "broken", "hallucinated"],
        "arm3": ["naked_plain", "naked_plain", "faithful", "naked_plain", "broken", "hallucinated"],
        "repair_body": "broken",
        "arm1_refine": [["rambling", "broken", "good"], ["rambling", "good"],
                        ["broken", "good"], ["rambling", "rambling", "good"],
                        ["rambling", "broken", "good"], ["good"]],
    },
    "refine_stuck": {
        "arm1": ["rambling"] * 6,
        "arm2": ["naked_plain", "broken", "vacuous", "naked_plain", "hallucinated", "broken"],
        "arm3": ["naked_plain", "broken", "vacuous", "naked_plain", "hallucinated", "broken"],
        "repair_body": "broken",
        "arm1_refine": [["rambling", "semantic", "semantic", "semantic"],
                        ["rambling", "broken", "semantic", "semantic"],
                        ["semantic", "semantic", "semantic", "semantic"],
                        ["broken", "semantic", "semantic", "semantic"],
                        ["rambling", "semantic", "semantic", "semantic"],
                        ["rambling", "rambling", "broken", "semantic"]],
    },
    # skill-lift: control = the live hallucination floor; treatment = correct.
    "skill_lift": {
        "control": ["hallucinated", "naked_plain", "hallucinated", "naked_plain",
                    "hallucinated", "naked_plain", "hallucinated", "vacuous"],
        "treatment": ["faithful"] * 8,
    },
    # ladder-lift OFF arm (plain generate): the free-gen floor incl. the live
    # transducer false-positive; the ON arm is mode:"refine_loop" below.
    "ladder": {
        "off": ["transducer", "naked_plain", "faithful", "semantic",
                "naked_plain", "broken"],
    },
}

WORKER_SHA = "mocksha00000"


def make_mock_endpoint(scenario: str):
    """A callable payload-dict -> worker-result-dict for `scenario`."""
    spec = SCENARIOS[scenario]
    refine_traj = spec.get("arm1_refine")
    refine_iter: dict[int, int] = {}

    def infill_result(body_kind):
        return {"worker_sha": WORKER_SHA, "tok_per_s": 135.0, "_mock_kind": body_kind,
                "middle_text": BODY[body_kind], "assembled": GARBAGE_ASSEMBLED,
                "prefix_held": True, "suffix_held": True}

    def gen_result(kind):
        return {"worker_sha": WORKER_SHA, "tok_per_s": 135.0, "_mock_kind": kind,
                "text": TEXT[kind]}

    def refine_result(body_kind, payload):
        prefix = payload.get("prefix", "")
        suffix = payload.get("suffix", "")
        body = BODY[body_kind]
        canvas = prefix + body + suffix
        return {"worker_sha": WORKER_SHA, "tok_per_s": 132.0, "_mock_kind": body_kind,
                "middle_text": body, "canvas_text": canvas,
                "body_span": [len(prefix), len(prefix) + len(body)],
                "argmax_per_position": list(range(8)), "offset_map": [],
                "prefix_held": True, "suffix_held": True}

    def _traj_kind(idx):
        traj = refine_traj[idx % len(refine_traj)]
        return traj[min(refine_iter.get(idx, 0), len(traj) - 1)]

    def endpoint(payload):
        hint = payload.get("_arm_hint")
        idx = payload.get("_idx", 0)
        mode = payload.get("mode")
        cond = payload.get("_cond")
        if cond:  # skill-lift pools
            pool = spec[cond]
            return gen_result(pool[idx % len(pool)])
        if mode == "refine_loop":  # ladder ON: converged, oracle-terminated
            return {"worker_sha": WORKER_SHA, "tok_per_s": 132.0,
                    "text": TEXT["faithful"], "stop_tier": "behavioral",
                    "validated": True, "iters": 2, "oracle_ms_mean": 0.06,
                    "denoise_steps": 31,
                    "iterations": [
                        {"errors_before": 2, "errors_after": 1, "oracle_ms": 0.07},
                        {"errors_before": 1, "errors_after": 0, "oracle_ms": 0.05}]}
        if mode == "generate" and "off" in spec:  # ladder OFF pool
            pool = spec["off"]
            return gen_result(pool[idx % len(pool)])
        if mode in ("denoise_to_step", "resume_renoise") and hint == "arm1":
            if refine_traj is None:
                return refine_result(spec["arm1"][idx % len(spec["arm1"])], payload)
            if mode == "denoise_to_step":
                refine_iter[idx] = 0
            else:
                refine_iter[idx] = refine_iter.get(idx, 0) + 1
            return refine_result(_traj_kind(idx), payload)
        if hint == "arm1_repair":
            return infill_result(spec["repair_body"])
        if hint == "repair":
            return gen_result("naked_plain")
        if hint == "arm1":
            return infill_result(spec["arm1"][idx % len(spec["arm1"])])
        arm = hint if hint in ("arm2", "arm3") else "arm2"
        pool = spec[arm]
        return gen_result(pool[idx % len(pool)])

    return endpoint

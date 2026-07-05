"""The guided-vs-free lift battery (LIVE, local MLX model).

Both arms get the IDENTICAL contract-stating prompt and are scored by the
IDENTICAL scorer (bb refine oracle + a FRESH EvalSession per sample + the
task's [{call,expect}] behavioral checks). Raw generations are persisted;
every row carries the package worker_sha. Perf is TOKENS/SECOND (useful
committed tokens over wall clock) per the owner's standing rule.

Run:  .venv/bin/python -m seon_diffusion.ab_guided [--n 3] [--repair on|off]
      [--tasks mean,c2f] [--out ab_runs] [--entropy-bound 0.1]
"""

import argparse
import json
import re
import time
from pathlib import Path

from .control import generate_guided
from .generate import GenConfig, generate
from .model import load_model
from .oracle import EvalSession, Oracle
from .worker import WORKER_SHA
from . import config

PARSE_LINT_KINDS = ("def-vs-defn", "phase-violation")

TASKS = [
    {"id": "mean",
     "spec": "(defn mean [xs] ...) returning the arithmetic mean of a vector of numbers",
     "checks": [{"call": "(mean [1 2 3])", "expect": "2"},
                {"call": "(mean [1 2 3 4])", "expect": "2.5"}]},
    {"id": "c2f",
     "spec": "(defn c->f [c] ...) converting Celsius to Fahrenheit (F = C * 9/5 + 32)",
     "checks": [{"call": "(c->f 100)", "expect": "212"},
                {"call": "(c->f 0)", "expect": "32"}]},
    {"id": "clamp-n",
     "spec": "(defn clamp-n [x lo hi] ...) clamping x into the inclusive range [lo hi]",
     "checks": [{"call": "(clamp-n 5 0 10)", "expect": "5"},
                {"call": "(clamp-n -3 0 10)", "expect": "0"},
                {"call": "(clamp-n 99 0 10)", "expect": "10"}]},
    {"id": "sum-evens",
     "spec": "(defn sum-evens [xs] ...) returning the sum of the even numbers in xs",
     "checks": [{"call": "(sum-evens [1 2 3 4 5 6])", "expect": "12"}]},
    {"id": "count-pos",
     "spec": "(defn count-pos [xs] ...) counting how many numbers in xs are greater than zero",
     "checks": [{"call": "(count-pos [-1 2 0 3])", "expect": "2"}]},
    {"id": "max-abs",
     "spec": "(defn max-abs [xs] ...) returning the largest absolute value in xs",
     "checks": [{"call": "(max-abs [-5 3 -2])", "expect": "5"}]},
]


def prompt_for(task):
    return (
        "Write ClojureScript. Reply with ONLY Clojure forms — no markdown, no "
        "fences, no prose, no explanations. Define exactly one function: "
        f"{task['spec']}. Numbers should use (/ a b) division, not ratio "
        "literals. Reply with the single (defn ...) form and nothing else.")


def strip_fences(text):
    """Free-arm courtesy: if the model wrapped code in ``` fences, keep only
    the fenced content (the guided arm never produces fences)."""
    blocks = re.findall(r"```(?:\w+)?\n(.*?)```", text, re.DOTALL)
    return "\n".join(blocks) if blocks else text


def score(oracle, code_text, checks):
    """Arm-agnostic scorer: parse/lint via bb refine, then eval every form
    and run the behavioral checks in a FRESH eval session."""
    r = oracle.refine(code_text)
    kinds = [e.get("error-kind") for e in r["renoise_spans"]]
    # any kind other than the structural/phase lints is a PARSE failure
    parse_ok = all(k in PARSE_LINT_KINDS for k in kinds)
    lint_ok = "def-vs-defn" not in kinds
    forms = sorted(
        r["clamps"] + [e for e in r["renoise_spans"]
                       if e.get("error-kind") in PARSE_LINT_KINDS],
        key=lambda f: f["span"][0])
    ses = EvalSession()
    try:
        evals = [ses.eval(f["source"]) for f in forms]
        eval_ok = bool(forms) and all(e.get("ok") for e in evals)
        behav = []
        for c in checks:
            ev = ses.eval(c["call"])
            behav.append(bool(ev.get("ok")) and ev.get("value") == c["expect"])
        behav_ok = bool(behav) and all(behav)
    finally:
        ses.close()
    return {"parse_ok": parse_ok, "lint_ok": lint_ok, "eval_ok": eval_ok,
            "behav_ok": behav_ok, "behav_detail": behav, "n_forms": len(forms),
            "error_kinds": kinds}


def useful_tok_per_s(tok, code, wall):
    n = len(tok(code, add_special_tokens=False)["input_ids"]) if code else 0
    return round(n / wall, 1) if wall > 0 else 0.0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=3)
    ap.add_argument("--tasks", default=None)
    ap.add_argument("--out", default="ab_runs")
    ap.add_argument("--entropy-bound", type=float, default=0.1)
    ap.add_argument("--repair", choices=("on", "off"), default="on")
    args = ap.parse_args()

    repair = args.repair == "on"
    tasks = [t for t in TASKS
             if args.tasks is None or t["id"] in args.tasks.split(",")]
    outdir = Path(args.out)
    outdir.mkdir(exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    samples_f = open(outdir / f"samples-{stamp}.jsonl", "a")
    card_f = open(outdir / f"scorecard-{stamp}.jsonl", "a")

    oracle = Oracle()
    from transformers import AutoTokenizer
    snap = config.model_snapshot()
    print(f"loading model from {snap} …", flush=True)
    tok = AutoTokenizer.from_pretrained(snap)
    model = load_model(snap)
    print(f"model loaded  worker_sha={WORKER_SHA}  repair={args.repair}", flush=True)

    for ti, task in enumerate(tasks):
        enc = tok.apply_chat_template(
            [{"role": "user", "content": prompt_for(task)}],
            tokenize=True, add_generation_prompt=True)
        ids = enc["input_ids"] if hasattr(enc, "keys") else enc
        if ids and isinstance(ids[0], list):
            ids = ids[0]
        for si in range(args.n):
            seed = 1000 * ti + si
            for arm in ("free", "guided"):
                gen = GenConfig(seed=seed, entropy_bound=args.entropy_bound)
                t0 = time.time()
                if arm == "free":
                    r = generate(model, tok, ids, gen)
                    code = strip_fences(r["text"])
                else:
                    ses = EvalSession()
                    try:
                        r = generate_guided(model, tok, ids, oracle,
                                            eval_session=ses, gen=gen,
                                            phase="functions", hints=True,
                                            repair=repair,
                                            checks=task["checks"])
                    finally:
                        ses.close()
                    code = r["text"]
                wall = time.time() - t0
                s = score(oracle, code, task["checks"])
                tps = useful_tok_per_s(tok, code, wall)
                row = {"sha": WORKER_SHA, "repair": repair, "task": task["id"],
                       "arm": arm, "sample": si, "seed": seed,
                       "wall_s": round(wall, 2), "tok_per_s": tps,
                       "forwards": r.get("decoder_forwards"),
                       "rounds": r.get("rounds"), "attempts": r.get("attempts"),
                       "repairs": r.get("repairs"),
                       "checks_passed": r.get("checks_passed"),
                       "locked_forms": r.get("locked_forms"), **s}
                card_f.write(json.dumps(row) + "\n")
                card_f.flush()
                samples_f.write(json.dumps(
                    {"sha": WORKER_SHA, "task": task["id"], "arm": arm,
                     "sample": si, "seed": seed, "raw_text": r["text"],
                     "scored_code": code, "events": r.get("events")}) + "\n")
                samples_f.flush()
                print(f"{task['id']:>10} s{si} {arm:>6}: parse={s['parse_ok']} "
                      f"lint={s['lint_ok']} eval={s['eval_ok']} behav={s['behav_ok']} "
                      f"{tps} tok/s wall={wall:.1f}s fwd={r.get('decoder_forwards')}",
                      flush=True)

    print("\n== summary ==")
    card_f.close()
    rows = [json.loads(l) for l in open(card_f.name)]
    for arm in ("free", "guided"):
        a = [r for r in rows if r["arm"] == arm]
        if not a:
            continue
        def rate(k):
            return sum(1 for r in a if r[k]) / len(a)
        print(f"{arm:>6}: n={len(a)} parse={rate('parse_ok'):.2f} "
              f"lint={rate('lint_ok'):.2f} eval={rate('eval_ok'):.2f} "
              f"behav={rate('behav_ok'):.2f} "
              f"avg {sum(r['tok_per_s'] for r in a)/len(a):.1f} tok/s "
              f"(wall {sum(r['wall_s'] for r in a)/len(a):.1f}s)")
    print(f"worker_sha={WORKER_SHA} repair={args.repair} scorecard={card_f.name}")


if __name__ == "__main__":
    main()

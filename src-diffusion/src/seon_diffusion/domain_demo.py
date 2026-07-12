"""The domain-build demo — 'PRD in, proven domain out' (the killer-app shape).

One hand-written PRD paragraph (the strong model's artifact, stubbed for
now). Two arms:

  free    — one-shot prompt: PRD → schemas + functions, no oracle.
  guided  — the PHASED build: `:schemas` phase (grammar-locked declarations;
            deep validation happens at pod replay) then `:functions` phase
            (locked schema forms ride the prompt as context; full
            lock-and-execute + repair + T3 checks + proof probe).

Scoring is identical: schemas = the right `schema/register!` declarations
present and phase-clean; functions = behavioral checks pass in a fresh
session. Perf in tokens/second.

Run: .venv/bin/python -m seon_diffusion.domain_demo [--n 6] [--entropy-bound 0.5]
"""

import argparse
import json
import time
from pathlib import Path

from .ab_guided import strip_fences, useful_tok_per_s
from .control import generate_guided
from .generate import GenConfig, generate
from .model import load_model
from .oracle import EvalSession, Oracle
from .server import WORKER_SHA
from . import config

PRD = (
    "PRD: a tiny expense-tracking domain. Data: an expense is a map with "
    "namespaced keys :demo.expense/id (string), :demo.expense/amount "
    "(number), :demo.expense/category (keyword). Behavior: add an expense "
    "to a vector of expenses; total the amounts for one category.")

SCHEMA_KEYS = (":demo.expense/id", ":demo.expense/amount", ":demo.expense/category")

CHECKS = [
    {"call": "(count (add-expense [] {:demo.expense/id \"a\" "
             ":demo.expense/amount 12.5 :demo.expense/category :food}))",
     "expect": "1"},
    {"call": "(total-by-category (add-expense (add-expense [] "
             "{:demo.expense/id \"a\" :demo.expense/amount 10 "
             ":demo.expense/category :food}) {:demo.expense/id \"b\" "
             ":demo.expense/amount 5.5 :demo.expense/category :food}) :food)",
     "expect": "15.5"},
    {"call": "(total-by-category [{:demo.expense/id \"a\" "
             ":demo.expense/amount 10 :demo.expense/category :food}] :travel)",
     "expect": "0"},
]

FORMS_ONLY = ("Reply with ONLY Clojure forms — no markdown, no fences, no "
              "prose. ")

SCHEMAS_PROMPT = (
    PRD + "\n\n" + FORMS_ONLY +
    "PHASE 1 — declare ONLY the schemas, one (schema/register! <key> <type>) "
    "form per key: :demo.expense/id is [:string {:seon.db/identity true}], "
    ":demo.expense/amount is :double, :demo.expense/category is :keyword. "
    "No functions, nothing else.")


def functions_prompt(schema_text):
    return (
        PRD + "\n\nSchemas already declared:\n" + schema_text + "\n" +
        FORMS_ONLY +
        "PHASE 2 — define exactly two functions: "
        "(add-expense expenses expense) returns expenses with expense added "
        "at the end; (total-by-category expenses category) returns the sum "
        "of :demo.expense/amount over expenses whose :demo.expense/category "
        "equals category (0 when none match). Nothing else.")


FREE_PROMPT = (
    PRD + "\n\n" + FORMS_ONLY +
    "Declare the three schemas with (schema/register! <key> <type>) — "
    ":demo.expense/id [:string {:seon.db/identity true}], "
    ":demo.expense/amount :double, :demo.expense/category :keyword — then "
    "define (add-expense expenses expense) adding expense at the end, and "
    "(total-by-category expenses category) summing :demo.expense/amount "
    "over the matching category (0 when none match). Nothing else.")


PRELUDE = "(require '[seon.schema :as schema])"


def score_domain(oracle, code_text):
    """Identical for both arms: schema declarations present + phase-clean,
    EVERY form (register! included) evals in a seeded session, function
    checks pass."""
    r = oracle.refine(code_text)
    forms = sorted(r["clamps"], key=lambda f: f["span"][0])
    reg = [f["source"] for f in forms if "schema/register!" in f["source"]]
    schemas_ok = (not r["renoise_spans"]
                  and all(any(k in s for s in reg) for k in SCHEMA_KEYS))
    ses = EvalSession()
    try:
        ses.eval(PRELUDE)
        fn_forms = [f["source"] for f in forms]
        # materialize BEFORE all(): a short-circuit would skip defining the
        # later fns and unfairly fail the behavioral checks (caught live)
        evals = [ses.eval(s) for s in fn_forms]
        evals_ok = bool(fn_forms) and all(e.get("ok") for e in evals)
        behav = []
        for c in CHECKS:
            ev = ses.eval(c["call"])
            behav.append(bool(ev.get("ok")) and ev.get("value") == c["expect"])
        behav_ok = bool(behav) and all(behav)
    finally:
        ses.close()
    # DOMAIN = SHIPPABLE: declarations right AND loads clean AND behaves
    return {"schemas_ok": schemas_ok, "evals_ok": evals_ok,
            "behav_ok": behav_ok,
            "domain_ok": schemas_ok and evals_ok and behav_ok,
            "n_forms": len(forms), "n_schemas": len(reg)}


def _ids(tok, prompt):
    enc = tok.apply_chat_template([{"role": "user", "content": prompt}],
                                  tokenize=True, add_generation_prompt=True)
    ids = enc["input_ids"] if hasattr(enc, "keys") else enc
    return ids[0] if ids and isinstance(ids[0], list) else ids


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=6)
    ap.add_argument("--entropy-bound", type=float, default=0.5)
    ap.add_argument("--out", default="ab_runs")
    args = ap.parse_args()

    oracle = Oracle()
    from transformers import AutoTokenizer
    snap = config.model_snapshot()
    tok = AutoTokenizer.from_pretrained(snap)
    model = load_model(snap)
    print(f"model loaded  worker_sha={WORKER_SHA}", flush=True)

    outdir = Path(args.out)
    outdir.mkdir(exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    card = open(outdir / f"domain-{stamp}.jsonl", "a")

    for si in range(args.n):
        for arm in ("free", "guided"):
            gen = GenConfig(seed=si * 7 + 1, entropy_bound=args.entropy_bound,
                            max_new_tokens=512)
            t0 = time.time()
            if arm == "free":
                r = generate(model, tok, _ids(tok, FREE_PROMPT), gen)
                code = strip_fences(r["text"])
                extra = {}
            else:
                # ONE seeded session across BOTH phases: schemas genuinely
                # register (parse->EVAL gate, owner rule: no parse-only
                # locks), then functions generate against them.
                ses = EvalSession()
                try:
                    ses.eval(PRELUDE)
                    r1 = generate_guided(model, tok, _ids(tok, SCHEMAS_PROMPT),
                                         oracle, eval_session=ses, gen=gen,
                                         phase="schemas", hints=True,
                                         prelude=PRELUDE, checks=None)
                    r2 = generate_guided(model, tok,
                                         _ids(tok, functions_prompt(r1["text"])),
                                         oracle, eval_session=ses, gen=gen,
                                         phase="functions", hints=True,
                                         repair=True, prelude=PRELUDE,
                                         checks=CHECKS)
                finally:
                    ses.close()
                code = r1["text"] + "\n" + r2["text"]
                extra = {"schemas_rounds": r1["rounds"], "fn_rounds": r2["rounds"],
                         "fn_attempts": r2["attempts"], "repairs": r2["repairs"],
                         "forwards": r1["decoder_forwards"] + r2["decoder_forwards"],
                         "checks_passed": r2["checks_passed"]}
            wall = time.time() - t0
            s = score_domain(oracle, code)
            tps = useful_tok_per_s(tok, code, wall)
            row = {"sha": WORKER_SHA, "arm": arm, "sample": si,
                   "wall_s": round(wall, 2), "tok_per_s": tps, **extra, **s,
                   "code": code}
            card.write(json.dumps(row) + "\n")
            card.flush()
            print(f"s{si} {arm:>6}: schemas={s['schemas_ok']} evals={s['evals_ok']} "
                  f"behav={s['behav_ok']} DOMAIN={s['domain_ok']} "
                  f"{tps} tok/s wall={wall:.1f}s", flush=True)

    card.close()
    rows = [json.loads(l) for l in open(card.name)]
    print("\n== domain summary ==")
    for arm in ("free", "guided"):
        a = [r for r in rows if r["arm"] == arm]
        def rate(k):
            return sum(1 for r in a if r[k]) / len(a)
        print(f"{arm:>6}: n={len(a)} schemas={rate('schemas_ok'):.2f} "
              f"evals={rate('evals_ok'):.2f} behav={rate('behav_ok'):.2f} "
              f"DOMAIN={rate('domain_ok'):.2f} "
              f"avg {sum(r['tok_per_s'] for r in a)/len(a):.1f} tok/s "
              f"(wall {sum(r['wall_s'] for r in a)/len(a):.1f}s)")
    print(f"worker_sha={WORKER_SHA} scorecard={card.name}")


if __name__ == "__main__":
    main()

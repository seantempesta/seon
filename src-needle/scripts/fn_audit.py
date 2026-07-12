#!/usr/bin/env python3
"""Scorer false-negative audit — repl-autosuggest lane.

Adversarial audit of the KT3/KT3b useful-match scoring frame: the metric
compares predictions against what the agent HISTORICALLY did next, but
multiple next actions can be equally valid.  This script measures the
FALSE-NEGATIVE rate of the frame: the fraction of zero-scored (fn-match 0)
predictions that are in fact reasonable alternative next acts.

Method (see docs/prds/repl-autosuggest/research/scorer-false-negative-
audit-2026-07-12.md):
  1. `--sample`   stratified seeded sample of 40 zero-useful predictions
                  across three arms (KT3 DeepSeek 20, KT3b 1.5B-instruct
                  instr-few 14, KT3b StarCoder2-3B cont 6), proportional
                  within arm by the target's primary substantive kind.
                  Rows whose target is pure ns-move boilerplate are
                  excluded (their substantive score is null by design).
  2. `--judge`    two LLM judges per item (muse: reasoning_effort minimal;
                  deepseek: thinking ENABLED), two passes each:
                  blind (no target shown; reasonable? valid?) then
                  target (category: reasonable-alternative /
                  premature-but-sensible / wrong-but-related / nonsense).
                  Blind-by-construction: separate stateless calls; the
                  blind pass runs first and never contains the target.
  3. `--selftest` every dataset target scored as its own prediction —
                  all must come back useful == 1.0 (scorer identity check).
  4. `--report`   aggregates: per-judge + agreed FN rates with Wilson CIs,
                  judge agreement (raw + Cohen's kappa), per-kind category
                  table, corrected-ceiling arithmetic, disagreement list
                  for human adjudication (adjudications.json merges back).

Outputs under src-needle/data/fn-audit/ (gitignored). Stdlib only.
"""

import argparse
import json
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DATA = REPO / "data/tune/acme-2026-07-12.jsonl"
OUTDIR = REPO / "src-needle/data/fn-audit"
SCORER = REPO / "src-needle/scripts/kt3_score.clj"

ARMS = {
    "deepseek": {
        "scored": "src-needle/data/kt3/scored-deepseek.json",
        "preds": "src-needle/data/kt3/preds-deepseek.jsonl",
        "n_sample": 20,
    },
    "instr-few": {
        "scored": "src-needle/data/kt3b/scored-qwen25c-1.5b-instr-instr-few.json",
        "preds": "src-needle/data/kt3b/preds-qwen25c-1.5b-instr-instr-few.jsonl",
        "n_sample": 14,
    },
    "starcoder2-cont": {
        "scored": "src-needle/data/kt3b/scored-starcoder2-3b-cont.json",
        "preds": "src-needle/data/kt3b/preds-starcoder2-3b-cont.jsonl",
        "n_sample": 6,
    },
}

JUDGES = {
    "deepseek": {
        "url": "https://api.deepseek.com/chat/completions",
        "model": "deepseek-v4-pro",
        "key_env": "DEEPSEEK_API_KEY",
        "price_in": 0.435, "price_out": 0.87,
        # thinking ENABLED (the audit spec): no disable flag, generous cap
        # (reasoning tokens count against max_tokens on this API).
        "extra": {}, "max_tokens": 8000,
    },
    "muse": {
        "url": "https://api.meta.ai/v1/chat/completions",
        "model": "muse-spark-1.1",
        "key_env": "META_MODEL_API_KEY",
        "price_in": 1.25, "price_out": 4.25,
        # hidden reasoning counts against max_tokens even at minimal effort
        # (observed: 1024 fully burned with empty content on 4/40 items).
        "extra": {"reasoning_effort": "minimal"}, "max_tokens": 4096,
    },
}

# --- prompts -------------------------------------------------------------

BLIND_PROMPT = """\
You audit an autocomplete system for "seon", a Clojure agent runtime. An \
agent works at a REPL; every turn it sees a context projection and a set \
of function cards, then evaluates its next form(s). Below is exactly what \
the agent saw, plus ONE candidate next action proposed by a model. Judge \
the candidate ON ITS OWN MERITS in this situation. You are NOT shown what \
the agent actually did — there is no reference answer here.

A competent agent of THIS system works through the functions and idioms \
visible in its context and cards (plans via my.plan, schemas via \
schema/register!, data via seon.db) — not generic in-memory Clojure \
(atoms, ad-hoc defs) when the context shows a system idiom for the job.

;;; ┌─ the agent's context (verbatim) ─
{context}
;;; └─ end context ─

;;; ┌─ cards ─ available functions ─
{cards}
;;; └─ end cards ─

;;; ┌─ candidate next action ─
{prediction}
;;; └─ end candidate ─

Answer with STRICT JSON only, no prose around it:
{{"reasonable": <true|false>,
  "valid": <true|false>,
  "why": "<ONE sentence>"}}

- "reasonable": would a competent seon agent plausibly evaluate THIS next, \
in THIS situation — a sensible, defensible next act toward the situation's \
visible goal? (It need not be the only sensible act.)
- "valid": is it plausible working Clojure for this system — parses, calls \
functions that exist in the cards/context or are standard clojure.core, \
argument shapes sane for the situation?
"""

TARGET_PROMPT = """\
You audit an autocomplete system for "seon", a Clojure agent runtime. An \
agent works at a REPL; every turn it sees a context projection and \
function cards, then evaluates its next form(s). Below: the situation, a \
model's CANDIDATE next action, and what the agent ACTUALLY evaluated next \
(the historical target). Classify the candidate's relationship to the \
target.

;;; ┌─ the agent's context (verbatim) ─
{context}
;;; └─ end context ─

;;; ┌─ cards ─ available functions ─
{cards}
;;; └─ end cards ─

;;; ┌─ candidate next action ─
{prediction}
;;; └─ end candidate ─

;;; ┌─ what the agent actually did next (historical target) ─
{target}
;;; └─ end target ─

Answer with STRICT JSON only, no prose around it:
{{"category": "<reasonable-alternative|premature-but-sensible|wrong-but-related|nonsense>",
  "why": "<ONE sentence>"}}

Categories:
- "reasonable-alternative": a DIFFERENT but equally valid next act for this \
situation — a competent agent could defensibly do the candidate instead of \
(or before) the target: an order swap, an equivalent formulation, another \
sensible step of the same work, a sensible probe/query first.
- "premature-but-sensible": sensible work, but it skips a prerequisite the \
target performs (e.g. transacts data before registering the schema, marks \
a step done whose work hasn't happened).
- "wrong-but-related": about the same task/entities but NOT a correct or \
useful next act — wrong function for the intent, invented ids, malformed \
or non-idiomatic shape the system would reject, redoing completed work.
- "nonsense": unrelated, fabricated, or incoherent for this situation.
"""

# --- shared plumbing ------------------------------------------------------


def load_env_key(name: str) -> str:
    for envfile in (REPO / ".env", REPO / ".env.acme"):
        if envfile.exists():
            for line in envfile.read_text().splitlines():
                line = line.strip()
                if line.startswith(f"{name}=") and not line.startswith("#"):
                    return line.split("=", 1)[1].strip()
    raise SystemExit(f"{name} not found in .env/.env.acme")


def call_judge(prov: dict, api_key: str, prompt: str) -> dict:
    payload = {
        "model": prov["model"],
        "messages": [{"role": "user", "content": prompt}],
        "temperature": 0.0,
        "max_tokens": prov["max_tokens"],
        **prov["extra"],
    }
    delay = 2.0
    for attempt in range(6):
        req = urllib.request.Request(
            prov["url"],
            data=json.dumps(payload).encode(),
            headers={"Content-Type": "application/json",
                     "Authorization": f"Bearer {api_key}"},
        )
        try:
            with urllib.request.urlopen(req, timeout=240) as resp:
                body = json.load(resp)
            msg = body["choices"][0]["message"]
            usage = body.get("usage", {})
            return {"raw": msg.get("content") or "",
                    "prompt_tokens": usage.get("prompt_tokens", 0),
                    "completion_tokens": usage.get("completion_tokens", 0)}
        except urllib.error.HTTPError as ex:
            detail = ex.read().decode(errors="replace")[:300]
            if ex.code == 400 and "temperature" in payload:
                payload.pop("temperature")  # strict gateways
                continue
            if ex.code in (429, 500, 502, 503, 504) and attempt < 5:
                print(f"  HTTP {ex.code}, retry in {delay:.0f}s", file=sys.stderr)
                time.sleep(delay)
                delay *= 2
                continue
            raise SystemExit(f"judge HTTP {ex.code}: {detail}")
        except (urllib.error.URLError, TimeoutError, OSError) as ex:
            if attempt < 5:
                time.sleep(delay)
                delay *= 2
                continue
            raise SystemExit(f"judge: {ex}")
    raise SystemExit("judge: retries exhausted")


def extract_json(text: str):
    """First balanced {...} object in the reply (judges may fence it)."""
    start = text.find("{")
    while start != -1:
        depth = 0
        for i in range(start, len(text)):
            c = text[i]
            if c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0:
                    try:
                        return json.loads(text[start:i + 1])
                    except json.JSONDecodeError:
                        break
        start = text.find("{", start + 1)
    return None


# --- kind classification (mirror of kt3_score.clj call-kind, on heads) ----

NS_MOVE = {"in-ns", "ns", "require", "use", "refer", "load-file"}
DEFN = {"defn", "defn-", "def", "defonce", "defmacro", "defmethod", "defmulti"}
QUERY = {"query", "q", "pull", "pull-by-name", "pull-many", "entity",
         "datoms", "history", "as-of"}


def head_kind(head: str) -> str:
    ns, _, name = head.rpartition("/")
    if name in NS_MOVE:
        return "ns-move"
    if name in DEFN:
        return "defn"
    if name == "register!":
        return "register"
    if name == "transact!":
        return "transact"
    if name in QUERY and ns in ("", "db"):
        return "query"
    if ns == "plan":
        return "plan"
    return "other"


def primary_kind(target_heads: list) -> str:
    """First substantive (non-ns-move) kind in target reading order."""
    for h in target_heads:
        k = head_kind(h)
        if k != "ns-move":
            return k
    return "ns-move"


# --- sample ---------------------------------------------------------------


def load_arm(arm: dict):
    scored = {s["id"]: s for s in json.loads((REPO / arm["scored"]).read_text())}
    preds = {}
    for l in (REPO / arm["preds"]).read_text().splitlines():
        d = json.loads(l)
        preds[d["id"]] = d
    return scored, preds


def build_sample(seed: int):
    import random
    rows = [json.loads(l) for l in DATA.read_text().splitlines()]
    rng = random.Random(seed)
    sample = []
    pop_stats = {}
    for arm_name, arm in ARMS.items():
        scored, preds = load_arm(arm)
        ids = sorted(scored)
        zero = [i for i in ids if scored[i]["useful"] == 0.0]
        judgeable = [i for i in zero
                     if scored[i]["parsed"] and scored[i]["n-pred"] > 0
                     and primary_kind(scored[i]["target-heads"]) != "ns-move"]
        pop_stats[arm_name] = {
            "n_rows": len(ids), "n_zero": len(zero),
            "n_parse_fail": sum(1 for i in zero if not scored[i]["parsed"]),
            "n_no_call": sum(1 for i in zero
                             if scored[i]["parsed"] and scored[i]["n-pred"] == 0),
            "n_pure_nsmove_target": sum(
                1 for i in zero
                if scored[i]["parsed"] and scored[i]["n-pred"] > 0
                and primary_kind(scored[i]["target-heads"]) == "ns-move"),
            "n_judgeable": len(judgeable),
        }
        # proportional-by-kind allocation (largest remainder), seeded draw
        by_kind = {}
        for i in judgeable:
            by_kind.setdefault(primary_kind(scored[i]["target-heads"]), []).append(i)
        want = arm["n_sample"]
        quotas = {k: len(v) / len(judgeable) * want for k, v in by_kind.items()}
        alloc = {k: int(q) for k, q in quotas.items()}
        rem = want - sum(alloc.values())
        for k in sorted(quotas, key=lambda k: quotas[k] - alloc[k], reverse=True)[:rem]:
            alloc[k] += 1
        for k in sorted(alloc):
            picks = rng.sample(sorted(by_kind[k]), min(alloc[k], len(by_kind[k])))
            for i in sorted(picks):
                sample.append({
                    "key": f"{arm_name}:{i}",
                    "arm": arm_name, "id": i,
                    "primary_kind": k,
                    "target_kinds": sorted((scored[i].get("kinds") or {}).keys()),
                    "coverage": rows[i]["meta"]["coverage"],
                    "context": rows[i]["context"],
                    "cards": "\n".join(rows[i]["cards"]),
                    "target": rows[i]["target"],
                    "prediction": preds[i]["clean"],
                    "pred_heads": scored[i]["pred-heads"],
                    "target_heads": scored[i]["target-heads"],
                })
    OUTDIR.mkdir(parents=True, exist_ok=True)
    (OUTDIR / "sample.json").write_text(json.dumps(
        {"seed": seed, "population": pop_stats, "items": sample}, indent=1))
    print(f"sample: {len(sample)} items -> {OUTDIR / 'sample.json'}")
    print(json.dumps(pop_stats, indent=1))
    print("kind allocation:", Counter((s["arm"], s["primary_kind"]) for s in sample))


# --- judge ----------------------------------------------------------------


def run_judge(judge_name: str, pass_name: str):
    prov = JUDGES[judge_name]
    api_key = load_env_key(prov["key_env"])
    items = json.loads((OUTDIR / "sample.json").read_text())["items"]
    out_path = OUTDIR / f"judge-{judge_name}-{pass_name}.jsonl"
    done = {}
    if out_path.exists():
        for l in out_path.read_text().splitlines():
            d = json.loads(l)
            done[d["key"]] = d
    todo = [it for it in items if it["key"] not in done]
    print(f"{judge_name}/{pass_name}: {len(items)} items, {len(todo)} to judge")
    tmpl = BLIND_PROMPT if pass_name == "blind" else TARGET_PROMPT
    write_lock = threading.Lock()
    counter = {"n": 0}
    with out_path.open("a") as fh:
        def work(it):
            prompt = tmpl.format(context=it["context"], cards=it["cards"],
                                 prediction=it["prediction"],
                                 target=it.get("target", ""))
            res = call_judge(prov, api_key, prompt)
            verdict = extract_json(res["raw"])
            rec = {"key": it["key"], "verdict": verdict, **res}
            with write_lock:
                fh.write(json.dumps(rec) + "\n")
                fh.flush()
                counter["n"] += 1
                if verdict is None:
                    print(f"  {it['key']}: UNPARSEABLE verdict: "
                          f"{res['raw'][:200]!r}", file=sys.stderr)
                if counter["n"] % 10 == 0:
                    print(f"  {counter['n']}/{len(todo)} done")

        with ThreadPoolExecutor(max_workers=6) as ex:
            list(ex.map(work, todo))
    tot_in = sum(json.loads(l)["prompt_tokens"] for l in out_path.read_text().splitlines())
    tot_out = sum(json.loads(l)["completion_tokens"] for l in out_path.read_text().splitlines())
    spend = tot_in / 1e6 * prov["price_in"] + tot_out / 1e6 * prov["price_out"]
    print(f"{judge_name}/{pass_name}: tokens in={tot_in} out={tot_out} spend=${spend:.3f}")


# --- self-test ------------------------------------------------------------


def selftest():
    rows = [json.loads(l) for l in DATA.read_text().splitlines()]
    score_in = [{"id": i, "target": r["target"], "prediction": r["target"]}
                for i, r in enumerate(rows)]
    proc = subprocess.run(["bb", str(SCORER)], input=json.dumps(score_in),
                          capture_output=True, text=True, check=True)
    scored = json.loads(proc.stdout)
    bad = [s for s in scored if s["useful"] < 0.999]
    OUTDIR.mkdir(parents=True, exist_ok=True)
    (OUTDIR / "selftest.json").write_text(json.dumps(
        {"n": len(scored), "n_perfect": len(scored) - len(bad),
         "failures": bad}, indent=1))
    print(f"selftest: {len(scored) - len(bad)}/{len(scored)} rows score 1.0 "
          f"as their own prediction")
    for s in bad:
        print(f"  FAIL id={s['id']} useful={s['useful']} parsed={s['parsed']} "
              f"parse_error={s.get('parse-error')}")


# --- report ---------------------------------------------------------------


def wilson(k: int, n: int, z: float = 1.96):
    if n == 0:
        return (0.0, 0.0, 0.0)
    p = k / n
    d = 1 + z * z / n
    c = (p + z * z / (2 * n)) / d
    h = z * ((p * (1 - p) / n + z * z / (4 * n * n)) ** 0.5) / d
    return (p, max(0.0, c - h), min(1.0, c + h))


def kappa(pairs):
    """Cohen's kappa over label pairs [(a, b), ...]."""
    n = len(pairs)
    if n == 0:
        return None
    po = sum(1 for a, b in pairs if a == b) / n
    labels = set(l for p in pairs for l in p)
    pe = sum((sum(1 for a, _ in pairs if a == l) / n)
             * (sum(1 for _, b in pairs if b == l) / n) for l in labels)
    return (po - pe) / (1 - pe) if pe < 1 else 1.0


def load_verdicts(judge_name: str, pass_name: str):
    path = OUTDIR / f"judge-{judge_name}-{pass_name}.jsonl"
    out = {}
    if path.exists():
        for l in path.read_text().splitlines():
            d = json.loads(l)
            out[d["key"]] = d["verdict"]
    return out


def gated_category(blind, target):
    """Final per-judge label: reasonable-alternative requires blind
    reasonable==true (the blind pass anchors reasonableness)."""
    if blind is None or target is None:
        return None
    cat = target.get("category")
    if cat == "reasonable-alternative" and not blind.get("reasonable"):
        return "wrong-but-related"
    return cat


def report():
    sample = json.loads((OUTDIR / "sample.json").read_text())
    items = sample["items"]
    adj_path = OUTDIR / "adjudications.json"
    adjudications = json.loads(adj_path.read_text()) if adj_path.exists() else {}
    verdicts = {(j, p): load_verdicts(j, p)
                for j in JUDGES for p in ("blind", "target")}

    per_item = []
    for it in items:
        k = it["key"]
        row = {"key": k, "arm": it["arm"], "primary_kind": it["primary_kind"],
               "coverage": it["coverage"]}
        for j in JUDGES:
            b, t = verdicts[(j, "blind")].get(k), verdicts[(j, "target")].get(k)
            row[f"{j}_reasonable"] = b.get("reasonable") if b else None
            row[f"{j}_valid"] = b.get("valid") if b else None
            row[f"{j}_category"] = gated_category(b, t)
            row[f"{j}_blind_why"] = (b or {}).get("why")
            row[f"{j}_target_why"] = (t or {}).get("why")
        cats = [row["muse_category"], row["deepseek_category"]]
        if k in adjudications:
            row["final_category"] = adjudications[k]
            row["source"] = "adjudicated"
        elif cats[0] is not None and cats[0] == cats[1]:
            row["final_category"] = cats[0]
            row["source"] = "agreed"
        else:
            row["final_category"] = None
            row["source"] = "NEEDS-ADJUDICATION"
        per_item.append(row)

    pending = [r for r in per_item if r["source"] == "NEEDS-ADJUDICATION"]
    if pending:
        print(f"\n{len(pending)} items need adjudication "
              f"(write {adj_path} as {{key: category}}):")
        for r in pending:
            print(f"  {r['key']} [{r['primary_kind']}] "
                  f"muse={r['muse_category']} deepseek={r['deepseek_category']}")

    # agreement stats
    reas_pairs = [(r["muse_reasonable"], r["deepseek_reasonable"])
                  for r in per_item
                  if r["muse_reasonable"] is not None
                  and r["deepseek_reasonable"] is not None]
    cat_pairs = [(r["muse_category"], r["deepseek_category"])
                 for r in per_item
                 if r["muse_category"] and r["deepseek_category"]]
    agreement = {
        "blind_reasonable": {
            "n": len(reas_pairs),
            "raw": round(sum(1 for a, b in reas_pairs if a == b) / len(reas_pairs), 3)
            if reas_pairs else None,
            "kappa": round(kappa(reas_pairs), 3) if reas_pairs else None,
        },
        "category": {
            "n": len(cat_pairs),
            "raw": round(sum(1 for a, b in cat_pairs if a == b) / len(cat_pairs), 3)
            if cat_pairs else None,
            "kappa": round(kappa(cat_pairs), 3) if cat_pairs else None,
        },
    }

    # FN rates (final category == reasonable-alternative)
    def fn_stats(rows):
        done = [r for r in rows if r["final_category"]]
        k = sum(1 for r in done if r["final_category"] == "reasonable-alternative")
        p, lo, hi = wilson(k, len(done))
        return {"n": len(done), "fn": k, "rate": round(p, 3),
                "wilson95": [round(lo, 3), round(hi, 3)]}

    fn = {"pooled": fn_stats(per_item)}
    for arm in ARMS:
        fn[arm] = fn_stats([r for r in per_item if r["arm"] == arm])

    # per-kind category table
    kinds_tbl = {}
    for r in per_item:
        if not r["final_category"]:
            continue
        kinds_tbl.setdefault(r["primary_kind"], Counter())[r["final_category"]] += 1
    kinds_tbl = {k: dict(v) for k, v in kinds_tbl.items()}

    # corrected-ceiling arithmetic per arm (documented in the report doc)
    corrected = {}
    for arm_name, arm in ARMS.items():
        pop = sample["population"][arm_name]
        st = fn[arm_name]
        if st["n"] == 0:
            continue
        judgeable_mass = pop["n_judgeable"] / pop["n_rows"]
        rate, lo, hi = st["rate"], *st["wilson95"]
        corrected[arm_name] = {
            "zero_mass": round(pop["n_zero"] / pop["n_rows"], 3),
            "judgeable_mass": round(judgeable_mass, 3),
            "fn_rate": rate, "fn_wilson95": [lo, hi],
            "uplift_full_credit": round(judgeable_mass * rate, 3),
            "uplift_ci": [round(judgeable_mass * lo, 3),
                          round(judgeable_mass * hi, 3)],
        }

    out = {"agreement": agreement, "fn_rates": fn, "per_kind": kinds_tbl,
           "corrected": corrected,
           "n_pending_adjudication": len(pending),
           "per_item": per_item}
    (OUTDIR / "report.json").write_text(json.dumps(out, indent=1))
    print(json.dumps({k: v for k, v in out.items() if k != "per_item"}, indent=1))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sample", action="store_true")
    ap.add_argument("--judge", choices=list(JUDGES))
    ap.add_argument("--judge-pass", choices=["blind", "target"])
    ap.add_argument("--selftest", action="store_true")
    ap.add_argument("--report", action="store_true")
    ap.add_argument("--seed", type=int, default=42)
    args = ap.parse_args()
    if args.sample:
        build_sample(args.seed)
    if args.judge:
        if not args.judge_pass:
            raise SystemExit("--judge requires --judge-pass")
        run_judge(args.judge, args.judge_pass)
    if args.selftest:
        selftest()
    if args.report:
        report()


if __name__ == "__main__":
    main()

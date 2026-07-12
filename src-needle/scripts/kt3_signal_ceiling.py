#!/usr/bin/env python3
"""KT3 frontier signal-ceiling — repl-autosuggest lane.

Question: given EXACTLY the compact autocomplete context + cards (nothing
more), can a frontier model predict the turn's actual forms?  If not, the
projection lacks signal and no 26M model can succeed.

Prompt per row = context verbatim + cards + a terse instruction. Nothing
else is added by construction.

Usage (stdlib only, single process):
  python3 src-needle/scripts/kt3_signal_ceiling.py --provider deepseek
  python3 src-needle/scripts/kt3_signal_ceiling.py --provider muse --sample 50

Outputs under src-needle/data/kt3/ (gitignored):
  preds-<provider>.jsonl   raw + cleaned predictions, usage (resumable cache)
  scored-<provider>.json   per-row mechanical scores (bb kt3_score.clj)
  summary-<provider>.json  aggregates: useful-match, per-kind, coverage corr
"""

import argparse
import json
import re
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DATA = REPO / "data/tune/acme-2026-07-12.jsonl"
OUTDIR = REPO / "src-needle/data/kt3"
SCORER = REPO / "src-needle/scripts/kt3_score.clj"

INSTRUCTION = (
    "You are a Clojure REPL agent in the seon system. Given this situation, "
    "emit ONLY the next REPL form(s) you would evaluate — no prose."
)

PROVIDERS = {
    "deepseek": {
        "url": "https://api.deepseek.com/chat/completions",
        "model": "deepseek-v4-pro",
        "key_env": "DEEPSEEK_API_KEY",
        "price_in": 0.435, "price_out": 0.87,  # $/M (llm-adapters.md catalog)
        # DeepSeek's API defaults thinking ON (reasoning burns the token cap
        # with empty content) — same disable the shipped adapter sends
        # (seon.ai.openai-compat/request-params).
        "extra": {"thinking": {"type": "disabled"}},
    },
    "muse": {
        "url": "https://api.meta.ai/v1/chat/completions",
        "model": "muse-spark-1.1",
        "key_env": "META_MODEL_API_KEY",
        "price_in": 1.25, "price_out": 4.25,
        "extra": {"reasoning_effort": "minimal"},  # hidden reasoning has no off-switch
    },
}


def load_env_key(name: str) -> str:
    for envfile in (REPO / ".env", REPO / ".env.acme"):
        if envfile.exists():
            for line in envfile.read_text().splitlines():
                line = line.strip()
                if line.startswith(f"{name}=") and not line.startswith("#"):
                    return line.split("=", 1)[1].strip()
    raise SystemExit(f"{name} not found in .env/.env.acme")


def build_prompt(row: dict) -> str:
    cards = "\n".join(row["cards"])
    return (
        f"{row['context']}\n\n"
        f";;; ┌─ cards ─ available functions ─\n"
        f"{cards}\n"
        f";;; └─ end cards ─\n\n"
        f"{INSTRUCTION}"
    )


FENCE_RE = re.compile(r"```(?:clojure|clj|edn)?\s*\n(.*?)```", re.DOTALL)


def clean_reply(text: str) -> str:
    """Mechanical cleanup only: unwrap markdown code fences when present."""
    blocks = FENCE_RE.findall(text)
    return "\n".join(b.strip() for b in blocks) if blocks else text.strip()


_print_lock = threading.Lock()


def call_model(prov: dict, api_key: str, prompt: str, row_id: int) -> dict:
    payload = {
        "model": prov["model"],
        "messages": [{"role": "user", "content": prompt}],
        "temperature": 0.0,
        "max_tokens": 2048,
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
            with urllib.request.urlopen(req, timeout=180) as resp:
                body = json.load(resp)
            msg = body["choices"][0]["message"]
            usage = body.get("usage", {})
            return {"id": row_id,
                    "raw": msg.get("content") or "",
                    "prompt_tokens": usage.get("prompt_tokens", 0),
                    "completion_tokens": usage.get("completion_tokens", 0)}
        except urllib.error.HTTPError as ex:
            detail = ex.read().decode(errors="replace")[:300]
            if ex.code == 400 and "temperature" in payload:
                payload.pop("temperature")  # strict gateways
                continue
            if ex.code in (429, 500, 502, 503, 504) and attempt < 5:
                with _print_lock:
                    print(f"  row {row_id}: HTTP {ex.code}, retry in {delay:.0f}s", file=sys.stderr)
                time.sleep(delay)
                delay *= 2
                continue
            raise SystemExit(f"row {row_id}: HTTP {ex.code}: {detail}")
        except (urllib.error.URLError, TimeoutError, OSError) as ex:
            if attempt < 5:
                time.sleep(delay)
                delay *= 2
                continue
            raise SystemExit(f"row {row_id}: {ex}")
    raise SystemExit(f"row {row_id}: retries exhausted")


def pearson(xs, ys):
    n = len(xs)
    mx, my = sum(xs) / n, sum(ys) / n
    cov = sum((x - mx) * (y - my) for x, y in zip(xs, ys))
    vx = sum((x - mx) ** 2 for x in xs) ** 0.5
    vy = sum((y - my) ** 2 for y in ys) ** 0.5
    return cov / (vx * vy) if vx and vy else 0.0


def spearman(xs, ys):
    def ranks(v):
        order = sorted(range(len(v)), key=lambda i: v[i])
        r = [0.0] * len(v)
        i = 0
        while i < len(order):
            j = i
            while j + 1 < len(order) and v[order[j + 1]] == v[order[i]]:
                j += 1
            avg = (i + j) / 2 + 1
            for k in range(i, j + 1):
                r[order[k]] = avg
            i = j + 1
        return r
    return pearson(ranks(xs), ranks(ys))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--provider", choices=PROVIDERS, default="deepseek")
    ap.add_argument("--sample", type=int, default=0, help="seeded subsample size (0 = all rows)")
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--concurrency", type=int, default=6)
    args = ap.parse_args()

    prov = PROVIDERS[args.provider]
    api_key = load_env_key(prov["key_env"])
    rows = [json.loads(l) for l in DATA.read_text().splitlines()]
    ids = list(range(len(rows)))
    if args.sample:
        import random
        ids = sorted(random.Random(args.seed).sample(ids, args.sample))

    OUTDIR.mkdir(parents=True, exist_ok=True)
    preds_path = OUTDIR / f"preds-{args.provider}.jsonl"
    done = {}
    if preds_path.exists():  # resume
        for l in preds_path.read_text().splitlines():
            d = json.loads(l)
            done[d["id"]] = d
    todo = [i for i in ids if i not in done]
    print(f"{args.provider}: {len(ids)} rows, {len(todo)} to fetch (resume {len(done)})")

    write_lock = threading.Lock()
    with preds_path.open("a") as fh:
        def work(i):
            res = call_model(prov, api_key, build_prompt(rows[i]), i)
            res["clean"] = clean_reply(res["raw"])
            with write_lock:
                fh.write(json.dumps(res) + "\n")
                fh.flush()
                done[i] = res
                if len(done) % 20 == 0:
                    print(f"  {len(done)}/{len(ids)} done")
            return res

        with ThreadPoolExecutor(max_workers=args.concurrency) as ex:
            list(ex.map(work, todo))

    # --- score mechanically via the bb reader ---
    score_in = [{"id": i, "target": rows[i]["target"], "prediction": done[i]["clean"]}
                for i in ids]
    proc = subprocess.run(["bb", str(SCORER)], input=json.dumps(score_in),
                          capture_output=True, text=True, check=True)
    scored = json.loads(proc.stdout)
    (OUTDIR / f"scored-{args.provider}.json").write_text(json.dumps(scored, indent=1))

    # --- aggregates ---
    by_id = {s["id"]: s for s in scored}
    useful = [by_id[i]["useful"] for i in ids]
    subst = [(i, by_id[i]["substantive"]["useful"]) for i in ids if by_id[i].get("substantive")]
    cov = [rows[i]["meta"]["coverage"] for i in ids]

    kinds = {}
    for s in scored:
        for k, v in (s.get("kinds") or {}).items():
            agg = kinds.setdefault(k, {"n": 0, "matched": 0, "credit_sum": 0.0})
            agg["n"] += v["n"]
            agg["matched"] += v["matched"]
            agg["credit_sum"] += v["credit-sum"]
    for k, agg in kinds.items():
        agg["match_rate"] = round(agg["matched"] / agg["n"], 3)
        agg["mean_credit"] = round(agg["credit_sum"] / agg["n"], 3)

    lo = [by_id[i]["useful"] for i in ids if rows[i]["meta"]["coverage"] < 0.25]
    hi = [by_id[i]["useful"] for i in ids if rows[i]["meta"]["coverage"] >= 0.25]
    p_in = sum(d["prompt_tokens"] for d in done.values() if d["id"] in set(ids))
    p_out = sum(d["completion_tokens"] for d in done.values() if d["id"] in set(ids))
    spend = p_in / 1e6 * prov["price_in"] + p_out / 1e6 * prov["price_out"]

    subst_vals = [u for _, u in subst]
    summary = {
        "provider": args.provider, "model": prov["model"], "n": len(ids),
        "parse_rate": round(sum(1 for i in ids if by_id[i]["parsed"]) / len(ids), 3),
        "useful_mean": round(sum(useful) / len(useful), 3),
        "useful_ge_0.5": round(sum(1 for u in useful if u >= 0.5) / len(useful), 3),
        "substantive_mean": round(sum(subst_vals) / len(subst_vals), 3),
        "substantive_n": len(subst_vals),
        "substantive_ge_0.5": round(sum(1 for u in subst_vals if u >= 0.5) / len(subst_vals), 3),
        "kinds": kinds,
        "coverage": {
            "pearson_useful": round(pearson(cov, useful), 3),
            "spearman_useful": round(spearman(cov, useful), 3),
            "lo_lt_.25": {"n": len(lo), "useful_mean": round(sum(lo) / len(lo), 3) if lo else None},
            "hi_ge_.25": {"n": len(hi), "useful_mean": round(sum(hi) / len(hi), 3) if hi else None},
        },
        "tokens": {"prompt": p_in, "completion": p_out},
        "spend_usd": round(spend, 4),
    }
    (OUTDIR / f"summary-{args.provider}.json").write_text(json.dumps(summary, indent=1))
    print(json.dumps(summary, indent=1))


if __name__ == "__main__":
    main()

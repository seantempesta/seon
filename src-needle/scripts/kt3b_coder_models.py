#!/usr/bin/env python3
"""KT3b — open small coder models on the KT3 signal-ceiling eval (MLX, local, $0).

Same eval as KT3 (kt3_signal_ceiling.py): same 214 rows
(data/tune/acme-2026-07-12.jsonl), same mechanical scorer (kt3_score.clj,
bb/edamame), temp 0, single sample. New variables: the model (mlx-community
quantized checkpoints / mlx_lm-converted) and the arm.

Arms (owner-corrected 2026-07-12: BASE continuation is primary):

  cont   PRIMARY — base model, continuation framing. Prompt = the row's
         context with (a) the cards bracket (KT3's exact bracket text)
         inserted immediately BEFORE the `;;; ┌─ transcript ─` section and
         (b) the transcript's closing bracket line (`;;; └─ end transcript ─`)
         removed, so the prompt ends exactly where the agent's next form is
         appended — after the last transcript line + one "\\n". NOTHING else
         is appended (no instruction, no chat template, no cue text).
         Generation stops when the first top-level bracketed form balances
         (string/comment/char-literal-aware scanner) or at the token cap;
         the prediction is that first complete form.

  instr-zero   anchor vs KT3's frontier framing — instruct model, KT3's
         exact prompt (context + cards bracket + terse instruction), one
         user message through the chat template, full generation to EOS.

  instr-few    instruct model, k=3 fixed exemplars (seeded pick from
         coverage>=.75 rows; exemplar rows EXCLUDED from scoring) as
         multi-turn chat, then the query row in KT3's framing.

  fim    Qwen2.5-Coder base FIM sentinels: <|fim_prefix|>{cont prompt}
         <|fim_suffix|><|fim_middle|> — same continuation prompt as `cont`
         (prefix = context+cards, suffix empty), same first-form stop.

Token caps: instruct arms 2048 (KT3 parity); cont/fim arms 512 — the
design's decoder budget (targets p50 34 tok; a model that hasn't balanced
one form in 512 tokens scores 0 either way, so the cap changes latency,
not scores).

Mechanical cleanup (documented): instruct arms get KT3's fence-unwrap (+
<think> strip); cont/fim arms are cut at the balanced-form boundary, else
truncated at the first transcript-bracket line (`^;;;`). Rows whose
prediction fails edamame score 0 (KT3 rule, unchanged).

Usage (one model per process — process exit IS the unload):
  .venv/bin/python scripts/kt3b_coder_models.py \
      --model mlx-community/Qwen2.5-Coder-1.5B-4bit --tag qwen25c-1.5b-base \
      --arms cont

Outputs under src-needle/data/kt3b/ (gitignored):
  preds-<tag>-<arm>.jsonl    raw + cleaned predictions + per-row timing
  scored-<tag>-<arm>.json    per-row mechanical scores (bb kt3_score.clj)
  summary-<tag>-<arm>.json   aggregates: useful-match, per-kind, per-kind x
                             coverage-tranche, next-form lens, latency
"""

import argparse
import json
import random
import re
import statistics
import subprocess
import time
from pathlib import Path

from mlx_lm import load, stream_generate
from mlx_lm.sample_utils import make_sampler

REPO = Path(__file__).resolve().parents[2]
DATA = REPO / "data/tune/acme-2026-07-12.jsonl"
OUTDIR = REPO / "src-needle/data/kt3b"
SCORER = REPO / "src-needle/scripts/kt3_score.clj"

INSTRUCTION = (
    "You are a Clojure REPL agent in the seon system. Given this situation, "
    "emit ONLY the next REPL form(s) you would evaluate — no prose."
)

CARDS_OPEN = ";;; ┌─ cards ─ available functions ─"
CARDS_CLOSE = ";;; └─ end cards ─"
TRANSCRIPT_OPEN = ";;; ┌─ transcript ─"
TRANSCRIPT_CLOSE = ";;; └─ end transcript ─"

EXEMPLAR_SEED = 42
EXEMPLAR_K = 3

NS_MOVE = {"in-ns", "ns", "require", "use", "refer", "load-file"}


# ---------- prompts ----------

def kt3_prompt(row: dict) -> str:
    """Byte-identical to kt3_signal_ceiling.build_prompt."""
    cards = "\n".join(row["cards"])
    return (
        f"{row['context']}\n\n"
        f"{CARDS_OPEN}\n{cards}\n{CARDS_CLOSE}\n\n"
        f"{INSTRUCTION}"
    )


def cont_prompt(row: dict) -> str:
    """Continuation framing: cards before the transcript, transcript open."""
    ctx = row["context"]
    i = ctx.index(TRANSCRIPT_OPEN)
    j = ctx.rindex(TRANSCRIPT_CLOSE)
    pre, transcript = ctx[:i], ctx[i:j].rstrip("\n")
    cards = "\n".join(row["cards"])
    return (
        f"{pre}{CARDS_OPEN}\n{cards}\n{CARDS_CLOSE}\n\n"
        f"{transcript}\n"
    )


def chat_tokens(tokenizer, messages):
    """apply_chat_template -> token ids; thinking disabled when supported."""
    try:
        return tokenizer.apply_chat_template(
            messages, add_generation_prompt=True, enable_thinking=False)
    except TypeError:
        return tokenizer.apply_chat_template(messages, add_generation_prompt=True)


def arm_prompt(arm, tokenizer, rows, exemplars, i):
    row = rows[i]
    if arm == "cont":
        return cont_prompt(row)
    if arm == "fim":
        return "<|fim_prefix|>" + cont_prompt(row) + "<|fim_suffix|><|fim_middle|>"
    if arm == "instr-zero":
        return chat_tokens(tokenizer, [{"role": "user", "content": kt3_prompt(row)}])
    if arm == "instr-few":
        messages = []
        for e in exemplars:
            messages.append({"role": "user", "content": kt3_prompt(rows[e])})
            messages.append({"role": "assistant", "content": rows[e]["target"]})
        messages.append({"role": "user", "content": kt3_prompt(row)})
        return chat_tokens(tokenizer, messages)
    raise ValueError(arm)


def pick_exemplars(rows: list) -> list:
    """Seeded pick of K exemplar row ids from coverage>=.75 rows."""
    cands = [i for i, r in enumerate(rows) if r["meta"]["coverage"] >= 0.75]
    return sorted(random.Random(EXEMPLAR_SEED).sample(cands, EXEMPLAR_K))


# ---------- form-boundary scanner (stop condition only; scoring stays bb) ----------

def first_form_end(text: str):
    """Index just past the first balanced top-level bracketed form, else None.

    String-, comment-, and char-literal-aware delimiter scan. Only the STOP
    condition — parsing/scoring still goes through edamame in kt3_score.clj.
    """
    depth, in_str, opened = 0, False, False
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if in_str:
            if c == "\\":
                i += 2
                continue
            if c == '"':
                in_str = False
            i += 1
            continue
        if c == ";":
            j = text.find("\n", i)
            if j < 0:
                return None
            i = j + 1
            continue
        if c == "\\":  # char literal: consume the next char
            i += 2
            continue
        if c == '"':
            in_str = True
        elif c in "([{":
            depth += 1
            opened = True
        elif c in ")]}":
            depth -= 1
            if opened and depth <= 0:
                return i + 1
        i += 1
    return None


FENCE_RE = re.compile(r"```(?:clojure|clj|edn)?\s*\n(.*?)```", re.DOTALL)
THINK_RE = re.compile(r"<think>.*?</think>", re.DOTALL)
BRACKET_RE = re.compile(r"^;;;", re.MULTILINE)
SPECIAL_RE = re.compile(r"<\|[a-z_]+\|>")  # detokenizer artifacts (<|im_end|> …)


def clean_reply(text: str, completion_mode: bool) -> str:
    """Instruct: KT3 fence-unwrap + think-strip. Completion: boundary + form cut.

    Completion boundary: a `^;;;` runtime-structure line (the transcript
    grammar reserves those for section brackets / event lines — a model
    writing one has ended its reply) truncates FIRST; then the prediction is
    everything up to the first balanced top-level form within that region.
    """
    text = SPECIAL_RE.sub("", text)
    if completion_mode:
        m = BRACKET_RE.search(text)
        if m:
            text = text[: m.start()]
        end = first_form_end(text)
        return (text[:end] if end is not None else text).strip()
    text = THINK_RE.sub("", text)
    blocks = FENCE_RE.findall(text)
    return "\n".join(b.strip() for b in blocks) if blocks else text.strip()


# ---------- generation ----------

def generate_one(model, tokenizer, prompt, max_tokens, early_stop):
    sampler = make_sampler(temp=0.0)
    t0 = time.perf_counter()
    chunks, last, buf = [], None, ""
    for resp in stream_generate(model, tokenizer, prompt=prompt,
                                max_tokens=max_tokens, sampler=sampler):
        chunks.append(resp.text)
        last = resp
        if early_stop:
            buf += resp.text
            if first_form_end(buf) is not None or BRACKET_RE.search(buf):
                break
    wall = time.perf_counter() - t0
    return {
        "raw": "".join(chunks),
        "wall_s": round(wall, 3),
        "prompt_tokens": last.prompt_tokens,
        "prompt_tps": round(last.prompt_tps, 1),
        "gen_tokens": last.generation_tokens,
        "gen_tps": round(last.generation_tps, 1),
        "peak_mem_gb": round(last.peak_memory, 2),
        "finish": getattr(last, "finish_reason", None),
    }


# ---------- aggregates ----------

def pearson(xs, ys):
    n = len(xs)
    mx_, my_ = sum(xs) / n, sum(ys) / n
    cov = sum((x - mx_) * (y - my_) for x, y in zip(xs, ys))
    vx = sum((x - mx_) ** 2 for x in xs) ** 0.5
    vy = sum((y - my_) ** 2 for y in ys) ** 0.5
    return cov / (vx * vy) if vx and vy else 0.0


def tranche(cov):
    return "lo_lt_.25" if cov < 0.25 else ("mid_.25-.75" if cov < 0.75 else "hi_ge_.75")


def head_parts(h):
    ns, name = h.split("/", 1)
    return ns, name


def nf_head_match(s):
    """Next-form lens: first substantive target head found in pred heads.

    Uses the scorer's own reading-order head lists (no new parsing): the
    first target head whose name is not ns-move boilerplate, matched
    alias-insensitively against all prediction heads.
    """
    t = next((h for h in s.get("target-heads", ())
              if head_parts(h)[1] not in NS_MOVE), None)
    if t is None:
        return None
    tns, tname = head_parts(t)
    for p in s.get("pred-heads", ()):
        pns, pname = head_parts(p)
        if tname == pname and (not tns or not pns or tns == pns):
            return True
    return False


def summarize(tag, arm, model_id, rows, ids, by_id, done, exemplars):
    useful = [by_id[i]["useful"] for i in ids]
    subst = [by_id[i]["substantive"]["useful"] for i in ids if by_id[i].get("substantive")]
    cov = [rows[i]["meta"]["coverage"] for i in ids]

    kinds, kinds_by_tranche = {}, {}
    for i in ids:
        tr = tranche(rows[i]["meta"]["coverage"])
        for k, v in (by_id[i].get("kinds") or {}).items():
            for store, key in ((kinds, k), (kinds_by_tranche, f"{k}|{tr}")):
                agg = store.setdefault(key, {"n": 0, "matched": 0, "credit_sum": 0.0})
                agg["n"] += v["n"]
                agg["matched"] += v["matched"]
                agg["credit_sum"] += v["credit-sum"]
    for agg in list(kinds.values()) + list(kinds_by_tranche.values()):
        agg["match_rate"] = round(agg["matched"] / agg["n"], 3)
        agg["mean_credit"] = round(agg["credit_sum"] / agg["n"], 3)
        agg["credit_sum"] = round(agg["credit_sum"], 2)

    buckets = {}
    for i in ids:
        buckets.setdefault(tranche(rows[i]["meta"]["coverage"]), []).append(by_id[i]["useful"])

    nf = [nf_head_match(by_id[i]) for i in ids]
    nf = [x for x in nf if x is not None]

    walls = [done[i]["wall_s"] for i in ids]
    return {
        "tag": tag, "arm": arm, "model": model_id, "n": len(ids),
        "exemplars": exemplars,
        "parse_rate": round(sum(1 for i in ids if by_id[i]["parsed"]) / len(ids), 3),
        "no_call_pred_rate": round(
            sum(1 for i in ids if by_id[i]["n-pred"] == 0) / len(ids), 3),
        "useful_mean": round(sum(useful) / len(useful), 3),
        "useful_ge_0.5": round(sum(1 for u in useful if u >= 0.5) / len(useful), 3),
        "useful_mean_ex_exemplars": round(
            statistics.mean(by_id[i]["useful"] for i in ids if i not in set(exemplars)), 3),
        "substantive_mean": round(sum(subst) / len(subst), 3) if subst else None,
        "substantive_n": len(subst),
        "substantive_ge_0.5": round(sum(1 for u in subst if u >= 0.5) / len(subst), 3) if subst else None,
        "next_form_head_match": round(sum(nf) / len(nf), 3) if nf else None,
        "next_form_n": len(nf),
        "kinds": kinds,
        "kinds_by_tranche": dict(sorted(kinds_by_tranche.items())),
        "coverage": {
            "pearson_useful": round(pearson(cov, useful), 3),
            "buckets": {k: {"n": len(v), "useful_mean": round(sum(v) / len(v), 3)}
                        for k, v in sorted(buckets.items())},
        },
        "latency": {
            "wall_s_median": round(statistics.median(walls), 2),
            "wall_s_p90": round(sorted(walls)[int(0.9 * len(walls))], 2),
            "wall_s_mean": round(statistics.mean(walls), 2),
            "prompt_tokens_median": statistics.median(done[i]["prompt_tokens"] for i in ids),
            "prompt_tps_median": statistics.median(done[i]["prompt_tps"] for i in ids),
            "gen_tokens_median": statistics.median(done[i]["gen_tokens"] for i in ids),
            "gen_tps_median": statistics.median(done[i]["gen_tps"] for i in ids),
            "peak_mem_gb": max(done[i]["peak_mem_gb"] for i in ids),
            "hit_token_cap": sum(1 for i in ids if done[i].get("finish") == "length"),
        },
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--tag", required=True)
    ap.add_argument("--arms", default="cont",
                    help="comma list: cont,instr-zero,instr-few,fim")
    ap.add_argument("--adapter-path", default=None,
                    help="LoRA adapter dir (mlx_lm.load adapter_path)")
    ap.add_argument("--max-tokens", type=int, default=2048)
    ap.add_argument("--limit", type=int, default=0, help="first N rows (smoke)")
    args = ap.parse_args()

    rows = [json.loads(l) for l in DATA.read_text().splitlines()]
    exemplars = pick_exemplars(rows)
    OUTDIR.mkdir(parents=True, exist_ok=True)

    print(f"loading {args.model} (adapter: {args.adapter_path}) …", flush=True)
    model, tokenizer = load(args.model, adapter_path=args.adapter_path)
    # mlx-community 4-bit conversions carry config eos_token_id 151643
    # (<|endoftext|>) ONLY and no generation_config.json; upstream Qwen
    # lists [<|im_end|>, <|endoftext|>]. Without this a chat-tuned model
    # that ends its turn at <|im_end|> (e.g. a LoRA trained on chat rows)
    # never stops and burns the token cap.
    if "<|im_end|>" in tokenizer.get_vocab():
        tokenizer.add_eos_token("<|im_end|>")

    for arm in args.arms.split(","):
        early_stop = arm in ("cont", "fim")
        arm_cap = 512 if early_stop else args.max_tokens
        ids = list(range(len(rows)))
        if arm == "instr-few":
            ids = [i for i in ids if i not in set(exemplars)]
        if args.limit:
            ids = ids[: args.limit]

        preds_path = OUTDIR / f"preds-{args.tag}-{arm}.jsonl"
        done = {}
        if preds_path.exists():  # resume
            for l in preds_path.read_text().splitlines():
                d = json.loads(l)
                done[d["id"]] = d
        todo = [i for i in ids if i not in done]
        print(f"{args.tag}/{arm}: {len(ids)} rows, {len(todo)} to generate "
              f"(resume {len(done)})", flush=True)

        with preds_path.open("a") as fh:
            for n, i in enumerate(todo):
                prompt = arm_prompt(arm, tokenizer, rows, exemplars, i)
                rec = generate_one(model, tokenizer, prompt, arm_cap, early_stop)
                rec["id"] = i
                rec["clean"] = clean_reply(rec["raw"], completion_mode=early_stop)
                fh.write(json.dumps(rec) + "\n")
                fh.flush()
                done[i] = rec
                if (n + 1) % 20 == 0:
                    print(f"  {len(done)}/{len(ids)} done "
                          f"(last: {rec['wall_s']}s, {rec['gen_tokens']} tok)", flush=True)

        # --- score mechanically via the bb reader (KT3 scorer, unchanged) ---
        score_in = [{"id": i, "target": rows[i]["target"], "prediction": done[i]["clean"]}
                    for i in ids]
        proc = subprocess.run(["bb", str(SCORER)], input=json.dumps(score_in),
                              capture_output=True, text=True, check=True)
        scored = json.loads(proc.stdout)
        (OUTDIR / f"scored-{args.tag}-{arm}.json").write_text(json.dumps(scored, indent=1))

        by_id = {s["id"]: s for s in scored}
        summary = summarize(args.tag, arm, args.model, rows, ids, by_id, done, exemplars)
        (OUTDIR / f"summary-{args.tag}-{arm}.json").write_text(json.dumps(summary, indent=1))
        print(json.dumps({k: summary[k] for k in
                          ("tag", "arm", "n", "parse_rate", "useful_mean",
                           "substantive_mean", "next_form_head_match", "latency")},
                         indent=1), flush=True)


if __name__ == "__main__":
    main()

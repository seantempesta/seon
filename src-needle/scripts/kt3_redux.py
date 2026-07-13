#!/usr/bin/env python3
"""KT3-redux — the FAIR ceiling test (owner-corrected), repl-autosuggest lane.

Supersedes KT3/KT3b's headline with three owner corrections:

(a) FULL FUNCTION INDEX in every arm's context — all 168 agent-surface fns
    from src-needle/data/fn-index.json rendered as compact cards
    (name + docstring line-1 + arglists), namespace-grouped, inside ONE
    cards bracket; PLUS the row's own cards that are NOT in the index
    (agent-/core-defined fns from that session — the "extras"), so no row
    LOSES a card it had in the 4-card runs. No slivers.

(b) Base models get IN-DOCUMENT EXEMPLARS (`cont-few`): k=3 real
    (task-context -> forms) episodes rendered as part of the continuation
    document before the live row — each exemplar is its own complete doc
    (pre-sections + extras cards + open transcript + the turn's ACTUAL
    forms + the transcript close line), the live doc ends open where the
    next form is appended. Exemplar rows are seeded (seed 42, from
    coverage >= .75 AND bundle_forms >= 2 — the KT3b pick landed two
    trivial in-ns exemplars; the bundle_forms floor is the computed fix)
    and EXCLUDED from scoring in EVERY arm, so all arms score the
    identical row set. `cont-bare` (same document, no exemplar episodes)
    is the delta control.

(c) SCORING v2 via the extended kt3_score.clj mode: PRIMARY = set-union
    best-match F1 over the turn's FULL form set (target_bundle);
    SECONDARY = next-form (the v2 `target` column) + the head-match lens;
    plus full decomposition (right-args / wrong-arg-keys /
    wrong-arg-values / wrong-fn / hallucinated-fn / missing, confusion
    pairs, per-fn arg-key errors, id lens).

Data: data/tune/acme-2026-07-12-v2.jsonl (213 rows, eval-only).

Prompt framings (documented deviations from KT3):
  - instruct arms put the FIXED index block FIRST (provider prefix-cache
    friendly), then the row's extras + context, then the instruction;
  - the instruction adds ONE granularity sentence (the primary lens is
    the whole turn's form set — zero-shot models must be told the target
    granularity KT3 measured them failing);
  - cont arms generate to the first `^;;;` structure line (the model
    closing its reply region) or the 1024 cap — NOT first-form stop —
    so bundles are reachable.

Usage:
  python3 scripts/kt3_redux.py verify
  python3 scripts/kt3_redux.py api --provider deepseek [--limit N]
  .venv/bin/python scripts/kt3_redux.py local \
      --model mlx-community/Qwen2.5-Coder-1.5B-Instruct-4bit \
      --tag qwen25c-1.5b-instruct --arms instr-few
  python3 scripts/kt3_redux.py report

Outputs under src-needle/data/kt3redux/ (gitignored):
  preds-<tag>-<arm>.jsonl    raw + cleaned predictions + timing (resumable)
  scored-<tag>-<arm>.json    per-row extended scores vs target_bundle
  scored-next-<tag>-<arm>.json  same predictions vs the next-form target
  summary-<tag>-<arm>.json   aggregates incl. decomposition tables
"""

import argparse
import json
import random
import re
import statistics
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from collections import Counter, defaultdict
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DATA = REPO / "data/tune/acme-2026-07-12-v2.jsonl"
FN_INDEX = REPO / "src-needle/data/fn-index.json"
OUTDIR = REPO / "src-needle/data/kt3redux"
SCORER = REPO / "src-needle/scripts/kt3_score.clj"

INSTRUCTION = (
    "You are a Clojure REPL agent in the seon system. Given this situation, "
    "emit ONLY the REPL form(s) you would evaluate this turn — no prose. "
    "Emit ALL the forms the turn needs (often several), not just the first."
)

CARDS_OPEN = ";;; ┌─ cards ─ available functions ─"
CARDS_CLOSE = ";;; └─ end cards ─"
SESSION_CARDS_OPEN = ";;; ┌─ cards ─ this session's functions ─"
TRANSCRIPT_OPEN = ";;; ┌─ transcript ─"
TRANSCRIPT_CLOSE = ";;; └─ end transcript ─"

EXEMPLAR_SEED = 42
EXEMPLAR_K = 3

CONT_CAP = 1024   # bundles, not single forms (v2 bundle p50 78 tok / max 959)
INSTR_CAP = 2048  # KT3 parity

PROVIDERS = {
    "deepseek": {
        "url": "https://api.deepseek.com/chat/completions",
        "model": "deepseek-v4-pro",
        "key_env": "DEEPSEEK_API_KEY",
        "price_in": 0.435, "price_out": 0.87,  # $/M (llm-adapters.md catalog)
        # the API defaults thinking ON — same disable the shipped adapter sends
        "extra": {"thinking": {"type": "disabled"}},
    },
    "muse": {
        "url": "https://api.meta.ai/v1/chat/completions",
        "model": "muse-spark-1.1",
        "key_env": "META_MODEL_API_KEY",
        "price_in": 1.25, "price_out": 4.25,
        "extra": {"reasoning_effort": "minimal"},
    },
}


# ---------------------------------------------------------------------------
# Full-index card block (correction a)
# ---------------------------------------------------------------------------

def split_arglists(s):
    """Top-level arglist vectors of an arglists string '([a] [{...}])'."""
    inner = s.strip()
    if inner.startswith("(") and inner.endswith(")"):
        inner = inner[1:-1]
    out, depth, in_str, start = [], 0, False, None
    i = 0
    while i < len(inner):
        c = inner[i]
        if in_str:
            if c == "\\":
                i += 2
                continue
            if c == '"':
                in_str = False
        elif c == '"':
            in_str = True
        elif c in "([{":
            if depth == 0:
                start = i
            depth += 1
        elif c in ")]}":
            depth -= 1
            if depth == 0 and start is not None:
                out.append(inner[start:i + 1])
                start = None
        i += 1
    return out


def render_card(fn):
    """Compact card from a fn-index row: (defn name "doc line 1" args …)."""
    name = fn["seon.fn/sym"].split("/")[-1]
    doc = (fn.get("seon.fn/doc") or "").split("\n")[0].strip()
    doc_lit = json.dumps(doc, ensure_ascii=False)  # JSON string ~ Clojure string
    arglists = split_arglists(fn.get("seon.fn/arglists") or "()") or ["[]"]
    args = " ".join(re.sub(r"\s+", " ", a) for a in arglists)
    if len(arglists) == 1:
        return f"(defn {name} {doc_lit} {args} …)"
    wrapped = " ".join(f"({re.sub(r'[ ]+', ' ', a)} …)"
                       for a in (re.sub(r"\s+", " ", a) for a in arglists))
    return f"(defn {name} {doc_lit} {wrapped})"


def load_index():
    idx = json.loads(FN_INDEX.read_text())
    return idx["fns"]


def index_block(fns):
    """The FIXED full-index card block, namespace-grouped, deterministic."""
    by_ns = defaultdict(list)
    for f in fns:
        ns, _ = f["seon.fn/sym"].rsplit("/", 1)
        by_ns[ns].append(f)
    lines = []
    for ns in sorted(by_ns):
        lines.append(f"; ── {ns} ──")
        for f in sorted(by_ns[ns], key=lambda x: x["seon.fn/sym"]):
            lines.append(render_card(f))
    return "\n".join(lines)


CARD_NAME_RE = re.compile(r'^\(defn ([^\s]+) "((?:[^"\\]|\\.)*)"')


def card_name_doc(card):
    m = CARD_NAME_RE.match(card)
    return (m.group(1), m.group(2)) if m else (None, None)


def row_extras(row, index_by_name):
    """The row's cards NOT already in the index (agent-/core-defined fns)."""
    extras = []
    for c in row["cards"]:
        name, doc = card_name_doc(c)
        dup = False
        for f in index_by_name.get(name, []):
            idoc = (f.get("seon.fn/doc") or "").split("\n")[0].strip()
            if idoc == (doc or "").strip():
                dup = True
                break
        if not dup:
            extras.append(c)
    return extras


def build_index_by_name(fns):
    d = defaultdict(list)
    for f in fns:
        d[f["seon.fn/sym"].split("/")[-1]].append(f)
    return d


# ---------------------------------------------------------------------------
# Prompts
# ---------------------------------------------------------------------------

def cards_block(index_text, extras):
    parts = [CARDS_OPEN, index_text]
    if extras:
        parts.append("; ── this session's additional functions ──")
        parts.extend(extras)
    parts.append(CARDS_CLOSE)
    return "\n".join(parts)


# --- layout variants (owner direction 2026-07-12: NIAH layout sweep) ------

RETRIEVE_INSTRUCTION = (
    INSTRUCTION
    + " First output ONE comment line `;; relevant: <the function names you"
    " will use>`, then the forms."
)


def situation_summary(row):
    """One computed line: the newest user event, else the plan head, else
    the first non-empty context line."""
    lines = row["context"].splitlines()
    for pick in ("◀ from user", "next ready"):
        hits = [l for l in lines if pick in l]
        if hits:
            return hits[-1].strip().lstrip("; ")[:240]
    return next((l.strip() for l in lines if l.strip()), "")[:240]


def structured_index_block(fns):
    """Correction: ns headers as `; ## <ns> (<n> fns)` + a one-line TOC."""
    by_ns = defaultdict(list)
    for f in fns:
        ns, _ = f["seon.fn/sym"].rsplit("/", 1)
        by_ns[ns].append(f)
    lines = ["; namespaces: " + " ".join(sorted(by_ns))]
    for ns in sorted(by_ns):
        lines.append(f"; ## {ns} ({len(by_ns[ns])} fns)")
        for f in sorted(by_ns[ns], key=lambda x: x["seon.fn/sym"]):
            lines.append(render_card(f))
    return "\n".join(lines)


def instr_prompt(row, index_text, extras, layout="plain",
                 structured_text=None):
    """Instruct framing, four layouts:

    plain      index block FIRST (prefix-cacheable), situation, instruction
    sandwich   ONE-line situation summary BEFORE the index, full situation
               AFTER it, instruction at the very end (NIAH query-sandwich)
    structured plain layout with the TOC'd `; ## ns` index block
    retrieve   plain layout + the retrieve-then-emit instruction (the
               `;; relevant:` line is a comment — the scorer only reads
               forms, so the scratch line is free reasoning room)
    """
    if layout == "sandwich":
        return (
            f"; SITUATION (summary — the full transcript follows the index):\n"
            f"; {situation_summary(row)}\n\n"
            f"{cards_block(index_text, extras)}\n\n"
            f"{row['context']}\n\n"
            f"{INSTRUCTION}"
        )
    if layout == "structured":
        return (
            f"{cards_block(structured_text, extras)}\n\n"
            f"{row['context']}\n\n"
            f"{INSTRUCTION}"
        )
    instruction = RETRIEVE_INSTRUCTION if layout == "retrieve" else INSTRUCTION
    return (
        f"{cards_block(index_text, extras)}\n\n"
        f"{row['context']}\n\n"
        f"{instruction}"
    )


def situation_text(row, extras):
    """The per-row part for chat few-shot: extras cards + context."""
    if extras:
        ex = "\n".join([SESSION_CARDS_OPEN, *extras, CARDS_CLOSE])
        return f"{ex}\n\n{row['context']}"
    return row["context"]


def cont_doc(row, extras, forms=None):
    """One continuation document. With `forms` the doc is a CLOSED exemplar
    episode (forms appended in transcript position, transcript closed);
    without, it ends open after the last transcript line + one newline."""
    ctx = row["context"]
    i = ctx.index(TRANSCRIPT_OPEN)
    j = ctx.rindex(TRANSCRIPT_CLOSE)
    pre, transcript = ctx[:i], ctx[i:j].rstrip("\n")
    ex = ""
    if extras:
        ex = "\n".join([SESSION_CARDS_OPEN, *extras, CARDS_CLOSE]) + "\n\n"
    if forms is None:
        return f"{pre}{ex}{transcript}\n"
    return f"{pre}{ex}{transcript}\n{forms}\n{TRANSCRIPT_CLOSE}\n"


def pick_exemplars(rows):
    cands = [i for i, r in enumerate(rows)
             if r["meta"]["coverage"] >= 0.75 and r["meta"].get("bundle_forms", 1) >= 2]
    return sorted(random.Random(EXEMPLAR_SEED).sample(cands, EXEMPLAR_K))


# ---------------------------------------------------------------------------
# Cleanup (KT3/KT3b lineage, documented)
# ---------------------------------------------------------------------------

FENCE_RE = re.compile(r"```(?:clojure|clj|edn)?\s*\n(.*?)```", re.DOTALL)
THINK_RE = re.compile(r"<think>.*?</think>", re.DOTALL)
BRACKET_RE = re.compile(r"^;;;", re.MULTILINE)
# ⟹ opens RESULT lines in the transcript grammar — never agent-authored
# (typed results are stripped by the repl). A model writing one has ended
# its form emission: a reply boundary, same status as a ^;;; line.
RESULT_RE = re.compile(r"^⟹", re.MULTILINE)
SPECIAL_RE = re.compile(r"<\|[a-z_]+\|>")


def clean_reply(text, completion_mode):
    text = SPECIAL_RE.sub("", text)
    if completion_mode:
        cuts = [m.start() for m in
                (BRACKET_RE.search(text), RESULT_RE.search(text)) if m]
        if cuts:
            text = text[: min(cuts)]
        return text.strip()
    text = THINK_RE.sub("", text)
    blocks = FENCE_RE.findall(text)
    return "\n".join(b.strip() for b in blocks) if blocks else text.strip()


# ---------------------------------------------------------------------------
# Scoring (extended kt3_score.clj mode)
# ---------------------------------------------------------------------------

def score(rows, ids, done, index_syms, extras_of, target_key):
    payload = {
        "index-syms": index_syms,
        "rows": [{"id": i,
                  "target": rows[i][target_key],
                  "prediction": done[i]["clean"],
                  "context": rows[i]["context"],
                  "card-names": [card_name_doc(c)[0] for c in extras_of[i]
                                 if card_name_doc(c)[0]]}
                 for i in ids],
    }
    proc = subprocess.run(["bb", str(SCORER)], input=json.dumps(payload),
                          capture_output=True, text=True, check=True)
    return json.loads(proc.stdout)


# ---------------------------------------------------------------------------
# Aggregates
# ---------------------------------------------------------------------------

NS_MOVE = {"in-ns", "ns", "require", "use", "refer", "load-file"}


def pearson(xs, ys):
    n = len(xs)
    mx_, my_ = sum(xs) / n, sum(ys) / n
    cov = sum((x - mx_) * (y - my_) for x, y in zip(xs, ys))
    vx = sum((x - mx_) ** 2 for x in xs) ** 0.5
    vy = sum((y - my_) ** 2 for y in ys) ** 0.5
    return cov / (vx * vy) if vx and vy else 0.0


def tranche(cov):
    return "lo_lt_.25" if cov < 0.25 else ("mid_.25-.75" if cov < 0.75 else "hi_ge_.75")


def nf_head_match(s):
    """First substantive target head found among prediction heads."""
    t = next((h for h in s.get("target-heads", ())
              if h.split("/", 1)[1] not in NS_MOVE), None)
    if t is None:
        return None
    tns, tname = t.split("/", 1)
    for p in s.get("pred-heads", ()):
        pns, pname = p.split("/", 1)
        if tname == pname and (not tns or not pns or tns == pns):
            return True
    return False


def aggregate_decomp(scored):
    t_out, p_out = Counter(), Counter()
    confusions = Counter()
    arg_errors = {}
    ids = Counter()
    for s in scored:
        d = s.get("decomp") or {}
        for to in d.get("target-outcomes", []):
            t_out[to["outcome"]] += 1
            if to.get("pred-head"):
                fn = to["head"]
                agg = arg_errors.setdefault(fn, {"n": 0, "missing": Counter(),
                                                 "extra": Counter(),
                                                 "ns_mismatch": Counter(),
                                                 "value_mismatch": Counter()})
                agg["n"] += 1
                for k in to.get("missing-keys") or []:
                    agg["missing"][k] += 1
                for k in to.get("extra-keys") or []:
                    agg["extra"][k] += 1
                for k in to.get("ns-mismatch-keys") or []:
                    agg["ns_mismatch"][k] += 1
                for k in to.get("value-mismatch-keys") or []:
                    agg["value_mismatch"][k] += 1
        for po in d.get("pred-outcomes", []):
            p_out[po["outcome"]] += 1
        for t, p in d.get("confusions", []):
            confusions[f"{t} -> {p}"] += 1
        di = d.get("ids") or {}
        ids["target"] += len(di.get("target", []))
        ids["recalled"] += len(di.get("recalled", []))
        ids["spurious_grounded"] += len(di.get("spurious-grounded", []))
        ids["spurious_invented"] += len(di.get("spurious-invented", []))
    return {
        "target_outcomes": dict(t_out),
        "pred_outcomes": dict(p_out),
        "top_confusions": confusions.most_common(40),
        "arg_errors": {fn: {"n": a["n"],
                            "missing": dict(a["missing"].most_common(10)),
                            "extra": dict(a["extra"].most_common(10)),
                            "ns_mismatch": dict(a["ns_mismatch"].most_common(10)),
                            "value_mismatch": dict(a["value_mismatch"].most_common(10))}
                       for fn, a in sorted(arg_errors.items(),
                                           key=lambda kv: -kv[1]["n"])},
        "ids": dict(ids),
    }


def summarize(tag, arm, model_id, rows, ids, scored, scored_next, done, exemplars):
    by_id = {s["id"]: s for s in scored}
    nxt = {s["id"]: s for s in scored_next}
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

    nf = [x for x in (nf_head_match(by_id[i]) for i in ids) if x is not None]
    walls = [done[i].get("wall_s") for i in ids if done[i].get("wall_s") is not None]
    nuseful = [nxt[i]["useful"] for i in ids]

    summary = {
        "tag": tag, "arm": arm, "model": model_id, "n": len(ids),
        "exemplars": exemplars,
        "parse_rate": round(sum(1 for i in ids if by_id[i]["parsed"]) / len(ids), 3),
        "no_call_pred_rate": round(
            sum(1 for i in ids if by_id[i]["n-pred"] == 0) / len(ids), 3),
        "bundle_useful_mean": round(sum(useful) / len(useful), 3),
        "bundle_recall_mean": round(statistics.mean(
            by_id[i]["recall"] for i in ids if by_id[i]["recall"] is not None), 3),
        "bundle_precision_mean": round(statistics.mean(
            by_id[i]["precision"] for i in ids), 3),
        "bundle_useful_ge_0.5": round(sum(1 for u in useful if u >= 0.5) / len(useful), 3),
        "substantive_mean": round(sum(subst) / len(subst), 3) if subst else None,
        "substantive_n": len(subst),
        "next_form_useful_mean": round(sum(nuseful) / len(nuseful), 3),
        "next_form_head_match": round(sum(nf) / len(nf), 3) if nf else None,
        "next_form_n": len(nf),
        "pred_calls_total": sum(by_id[i]["n-pred"] for i in ids),
        "target_calls_total": sum(by_id[i]["n-target"] for i in ids),
        "kinds": kinds,
        "kinds_by_tranche": dict(sorted(kinds_by_tranche.items())),
        "coverage": {
            "pearson_useful": round(pearson(cov, useful), 3),
            "buckets": {k: {"n": len(v), "useful_mean": round(sum(v) / len(v), 3)}
                        for k, v in sorted(buckets.items())},
        },
        "decomp": aggregate_decomp([by_id[i] for i in ids]),
    }
    if walls:
        summary["latency"] = {
            "wall_s_median": round(statistics.median(walls), 2),
            "wall_s_p90": round(sorted(walls)[int(0.9 * len(walls))], 2),
            "prompt_tokens_median": statistics.median(
                done[i]["prompt_tokens"] for i in ids if "prompt_tokens" in done[i]),
            "gen_tokens_median": statistics.median(
                done[i]["gen_tokens"] for i in ids if "gen_tokens" in done[i])
            if any("gen_tokens" in done[i] for i in ids) else None,
        }
        pts = [done[i].get("prompt_tps") for i in ids if done[i].get("prompt_tps")]
        gts = [done[i].get("gen_tps") for i in ids if done[i].get("gen_tps")]
        mem = [done[i].get("peak_mem_gb") for i in ids if done[i].get("peak_mem_gb")]
        if pts:
            summary["latency"]["prompt_tps_median"] = statistics.median(pts)
        if gts:
            summary["latency"]["gen_tps_median"] = statistics.median(gts)
        if mem:
            summary["latency"]["peak_mem_gb"] = max(mem)
        caps = sum(1 for i in ids if done[i].get("finish") == "length")
        summary["latency"]["hit_token_cap"] = caps
    return summary


# ---------------------------------------------------------------------------
# Shared run plumbing
# ---------------------------------------------------------------------------

def load_rows():
    return [json.loads(l) for l in DATA.read_text().splitlines() if l.strip()]


def prep():
    rows = load_rows()
    fns = load_index()
    ibn = build_index_by_name(fns)
    index_text = index_block(fns)
    extras_of = [row_extras(r, ibn) for r in rows]
    index_syms = [f["seon.fn/sym"] for f in fns]
    exemplars = pick_exemplars(rows)
    return rows, index_text, extras_of, index_syms, exemplars


def eval_ids(rows, exemplars, limit):
    ids = [i for i in range(len(rows)) if i not in set(exemplars)]
    return ids[:limit] if limit else ids


def resume(preds_path):
    done = {}
    if preds_path.exists():
        for l in preds_path.read_text().splitlines():
            d = json.loads(l)
            done[d["id"]] = d
    return done


def score_and_summarize(tag, arm, model_id, rows, ids, done, index_syms,
                        extras_of, exemplars):
    scored = score(rows, ids, done, index_syms, extras_of, "target_bundle")
    (OUTDIR / f"scored-{tag}-{arm}.json").write_text(json.dumps(scored, indent=1))
    scored_next = score(rows, ids, done, index_syms, extras_of, "target")
    (OUTDIR / f"scored-next-{tag}-{arm}.json").write_text(
        json.dumps(scored_next, indent=1))
    summary = summarize(tag, arm, model_id, rows, ids,
                        scored, scored_next, done, exemplars)
    (OUTDIR / f"summary-{tag}-{arm}.json").write_text(json.dumps(summary, indent=1))
    print(json.dumps({k: summary[k] for k in
                      ("tag", "arm", "n", "parse_rate", "bundle_useful_mean",
                       "substantive_mean", "next_form_useful_mean",
                       "next_form_head_match")}, indent=1), flush=True)
    return summary


# ---------------------------------------------------------------------------
# API arms (DeepSeek / Muse) — KT3's transport, resumable
# ---------------------------------------------------------------------------

def load_env_key(name):
    for envfile in (REPO / ".env", REPO / ".env.acme"):
        if envfile.exists():
            for line in envfile.read_text().splitlines():
                line = line.strip()
                if line.startswith(f"{name}=") and not line.startswith("#"):
                    return line.split("=", 1)[1].strip()
    raise SystemExit(f"{name} not found in .env/.env.acme")


_print_lock = threading.Lock()


def call_model(prov, api_key, prompt, row_id, extra=None, max_tokens=None):
    payload = {
        "model": prov["model"],
        "messages": [{"role": "user", "content": prompt}],
        "temperature": 0.0,
        "max_tokens": max_tokens or INSTR_CAP,
        **(extra if extra is not None else prov["extra"]),
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
            t0 = time.perf_counter()
            with urllib.request.urlopen(req, timeout=600) as resp:
                body = json.load(resp)
            wall = time.perf_counter() - t0
            msg = body["choices"][0]["message"]
            usage = body.get("usage", {})
            rec = {"id": row_id,
                   "raw": msg.get("content") or "",
                   "wall_s": round(wall, 3),
                   "prompt_tokens": usage.get("prompt_tokens", 0),
                   "completion_tokens": usage.get("completion_tokens", 0),
                   "cached_tokens": (usage.get("prompt_tokens_details") or {})
                   .get("cached_tokens", 0)}
            if msg.get("reasoning_content"):
                # kept IN FULL (owner 2026-07-12): reasoned traces are the
                # candidate data-generation recipe for the finetune set.
                rec["reasoning"] = msg["reasoning_content"]
                rec["reasoning_tokens"] = (usage.get("completion_tokens_details")
                                           or {}).get("reasoning_tokens", 0)
            return rec
        except urllib.error.HTTPError as ex:
            detail = ex.read().decode(errors="replace")[:300]
            if ex.code == 400 and "temperature" in payload:
                payload.pop("temperature")
                continue
            if ex.code in (429, 500, 502, 503, 504) and attempt < 5:
                with _print_lock:
                    print(f"  row {row_id}: HTTP {ex.code}, retry in {delay:.0f}s",
                          file=sys.stderr)
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


def run_api(args):
    from concurrent.futures import ThreadPoolExecutor
    prov = PROVIDERS[args.provider]
    api_key = load_env_key(prov["key_env"])
    rows, index_text, extras_of, index_syms, exemplars = prep()
    ids = eval_ids(rows, exemplars, args.limit)
    OUTDIR.mkdir(parents=True, exist_ok=True)
    # --cards row = de-confounding control: the row's ORIGINAL 4 cards only,
    # same NEW instruction/framing — isolates the card-set variable from the
    # instruction change when compared against the -4card rescores.
    row_cards_only = args.cards == "row"
    layout = getattr(args, "layout", "plain")
    thinking = bool(getattr(args, "thinking", False))
    structured_text = (structured_index_block(load_index())
                       if layout == "structured" else None)
    arm = "instr-rowcards" if row_cards_only else "instr"
    if layout != "plain":
        arm += f"-{layout}"
    extra, cap = None, None
    if thinking:
        arm += "-think"
        if args.provider == "deepseek":
            # thinking ON, generous budget (owner 2026-07-12): max_tokens
            # covers reasoning + answer so reasoning can't starve content.
            extra, cap = {"thinking": {"type": "enabled"}}, 12288
        else:
            extra, cap = {"reasoning_effort": "high"}, 12288
    tag = args.provider
    preds_path = OUTDIR / f"preds-{tag}-{arm}.jsonl"
    done = resume(preds_path)
    todo = [i for i in ids if i not in done]
    print(f"{tag}/{arm}: {len(ids)} rows, {len(todo)} to fetch (resume {len(done)})")

    write_lock = threading.Lock()
    with preds_path.open("a") as fh:
        def work(i):
            if row_cards_only:
                prompt = (f"{chr(10).join([CARDS_OPEN, *rows[i]['cards'], CARDS_CLOSE])}\n\n"
                          f"{rows[i]['context']}\n\n{INSTRUCTION}")
            else:
                prompt = instr_prompt(rows[i], index_text, extras_of[i],
                                      layout=layout,
                                      structured_text=structured_text)
            res = call_model(prov, api_key, prompt, i, extra=extra, max_tokens=cap)
            res["clean"] = clean_reply(res["raw"], completion_mode=False)
            with write_lock:
                fh.write(json.dumps(res) + "\n")
                fh.flush()
                done[i] = res
                if len(done) % 20 == 0:
                    print(f"  {len(done)}/{len(ids)} done", flush=True)
            return res
        with ThreadPoolExecutor(max_workers=args.concurrency) as ex:
            list(ex.map(work, todo))

    p_in = sum(done[i]["prompt_tokens"] for i in ids)
    p_out = sum(done[i]["completion_tokens"] for i in ids)
    cached = sum(done[i].get("cached_tokens", 0) for i in ids)
    print(f"tokens: prompt {p_in} (cached {cached}) completion {p_out} "
          f"~= ${p_in / 1e6 * prov['price_in'] + p_out / 1e6 * prov['price_out']:.2f} "
          f"(cache discount not applied)")
    score_and_summarize(tag, arm, prov["model"], rows, ids, done,
                        index_syms, extras_of, exemplars)


# ---------------------------------------------------------------------------
# Local arms (MLX)
# ---------------------------------------------------------------------------

def chat_tokens(tokenizer, messages):
    try:
        return tokenizer.apply_chat_template(
            messages, add_generation_prompt=True, enable_thinking=False)
    except TypeError:
        return tokenizer.apply_chat_template(messages, add_generation_prompt=True)


def local_prompt(arm, tokenizer, rows, exemplars, i, index_text, extras_of,
                 row_cards_only=False, layout="plain", structured_text=None):
    row = rows[i]
    if row_cards_only and arm in ("instr-zero", "instr-few"):
        # de-confounding control: the row's ORIGINAL cards only (same NEW
        # instruction + exemplars) — isolates the full-index variable.
        def sit(r):
            return ("\n".join([CARDS_OPEN, *r["cards"], CARDS_CLOSE])
                    + "\n\n" + r["context"])
        if arm == "instr-zero":
            return chat_tokens(tokenizer, [
                {"role": "user", "content": f"{sit(row)}\n\n{INSTRUCTION}"}])
        messages = [{"role": "system", "content": INSTRUCTION}]
        for e in exemplars:
            messages.append({"role": "user", "content": sit(rows[e])})
            messages.append({"role": "assistant",
                             "content": rows[e]["target_bundle"]})
        messages.append({"role": "user", "content": sit(row)})
        return chat_tokens(tokenizer, messages)
    if arm == "cont-few":
        docs = [f"{CARDS_OPEN}\n{index_text}\n{CARDS_CLOSE}\n"]
        for e in exemplars:
            docs.append(cont_doc(rows[e], extras_of[e],
                                 forms=rows[e]["target_bundle"]))
        docs.append(cont_doc(row, extras_of[i]))
        return "\n\n".join(docs)
    if arm == "cont-bare":
        return "\n\n".join([f"{CARDS_OPEN}\n{index_text}\n{CARDS_CLOSE}\n",
                            cont_doc(row, extras_of[i])])
    if arm == "instr-zero":
        return chat_tokens(tokenizer, [
            {"role": "user",
             "content": instr_prompt(row, index_text, extras_of[i],
                                     layout=layout,
                                     structured_text=structured_text)}])
    if arm == "instr-few":
        instruction = RETRIEVE_INSTRUCTION if layout == "retrieve" else INSTRUCTION
        idx = structured_text if layout == "structured" else index_text
        messages = [{"role": "system",
                     "content": f"{instruction}\n\n{CARDS_OPEN}\n{idx}\n{CARDS_CLOSE}"}]
        for e in exemplars:
            messages.append({"role": "user",
                             "content": situation_text(rows[e], extras_of[e])})
            messages.append({"role": "assistant", "content": rows[e]["target_bundle"]})
        live = situation_text(row, extras_of[i])
        if layout == "sandwich":
            live = (f"; SITUATION (summary — the full transcript follows):\n"
                    f"; {situation_summary(row)}\n\n{live}")
        messages.append({"role": "user", "content": live})
        return chat_tokens(tokenizer, messages)
    raise ValueError(arm)


def generate_one(model, tokenizer, prompt, max_tokens, early_stop):
    from mlx_lm import stream_generate
    from mlx_lm.sample_utils import make_sampler
    sampler = make_sampler(temp=0.0)
    t0 = time.perf_counter()
    chunks, last, buf = [], None, ""
    for resp in stream_generate(model, tokenizer, prompt=prompt,
                                max_tokens=max_tokens, sampler=sampler):
        chunks.append(resp.text)
        last = resp
        if early_stop:
            buf += resp.text
            if BRACKET_RE.search(buf) or RESULT_RE.search(buf):
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


def run_local(args):
    from mlx_lm import load
    rows, index_text, extras_of, index_syms, exemplars = prep()
    OUTDIR.mkdir(parents=True, exist_ok=True)
    adapter = getattr(args, "adapter_path", None)
    print(f"loading {args.model} (adapter: {adapter}) …", flush=True)
    model, tokenizer = load(args.model, adapter_path=adapter)
    # mlx-community 4-bit conversions carry config eos_token_id 151643
    # (<|endoftext|>) only; upstream Qwen generation_config also lists
    # <|im_end|> (the chat turn end). Restore it so chat-tuned models
    # (incl. LoRA adapters trained on chat rows) stop at end-of-turn
    # instead of burning the cap. Stock arms are unaffected in score
    # terms (they emit <|endoftext|> right after <|im_end|>; cleanup
    # strips the marker text either way).
    if "<|im_end|>" in tokenizer.get_vocab():
        tokenizer.add_eos_token("<|im_end|>")

    row_cards_only = getattr(args, "cards", "full") == "row"
    layout = getattr(args, "layout", "plain")
    structured_text = (structured_index_block(load_index())
                       if layout == "structured" else None)
    for arm in args.arms.split(","):
        early_stop = arm.startswith("cont")
        cap = CONT_CAP if early_stop else INSTR_CAP
        ids = eval_ids(rows, exemplars, args.limit)
        arm_tag = f"{arm}-rowcards" if row_cards_only else arm
        if layout != "plain":
            arm_tag += f"-{layout}"
        preds_path = OUTDIR / f"preds-{args.tag}-{arm_tag}.jsonl"
        done = resume(preds_path)
        todo = [i for i in ids if i not in done]
        print(f"{args.tag}/{arm_tag}: {len(ids)} rows, {len(todo)} to generate "
              f"(resume {len(done)})", flush=True)

        with preds_path.open("a") as fh:
            for n, i in enumerate(todo):
                prompt = local_prompt(arm, tokenizer, rows, exemplars, i,
                                      index_text, extras_of,
                                      row_cards_only=row_cards_only,
                                      layout=layout,
                                      structured_text=structured_text)
                rec = generate_one(model, tokenizer, prompt, cap, early_stop)
                rec["id"] = i
                rec["clean"] = clean_reply(rec["raw"], completion_mode=early_stop)
                fh.write(json.dumps(rec) + "\n")
                fh.flush()
                done[i] = rec
                if (n + 1) % 20 == 0:
                    print(f"  {len(done)}/{len(ids)} done "
                          f"(last: {rec['wall_s']}s, {rec['gen_tokens']} tok)",
                          flush=True)

        score_and_summarize(args.tag, arm_tag, args.model, rows, ids, done,
                            index_syms, extras_of, exemplars)


# ---------------------------------------------------------------------------
# verify — every row's target fns present in index ∪ extras ∪ context
# ---------------------------------------------------------------------------

def run_verify(_args):
    rows, index_text, extras_of, index_syms, exemplars = prep()
    OUTDIR.mkdir(parents=True, exist_ok=True)
    # target heads via the real reader: score targets against themselves,
    # read the decomposition's grounding of each (self-)prediction head.
    done = {i: {"clean": rows[i]["target_bundle"]} for i in range(len(rows))}
    all_ids = list(range(len(rows)))
    scored = score(rows, all_ids, done, index_syms, extras_of, "target_bundle")
    # second pass with contexts blanked: heads grounded ONLY by context text
    rows_blank = [dict(r, context="") for r in rows]
    scored_blank = score(rows_blank, all_ids, done, index_syms, extras_of,
                         "target_bundle")
    flagged, context_only = [], []
    for s, sb in zip(scored, scored_blank):
        i = s["id"]
        bad = [p["head"] for p in s["decomp"]["pred-outcomes"]
               if p["outcome"] == "hallucinated-fn"]
        bad_cards_only = [p["head"] for p in sb["decomp"]["pred-outcomes"]
                          if p["outcome"] == "hallucinated-fn"]
        if bad:
            flagged.append({"row": i, "coverage": rows[i]["meta"]["coverage"],
                            "uncovered_heads": bad})
        elif bad_cards_only:
            context_only.append({"row": i,
                                 "coverage": rows[i]["meta"]["coverage"],
                                 "context_only_heads": bad_cards_only})
    report = {
        "rows": len(rows),
        "index_fns": len(index_syms),
        "rows_with_extras": sum(1 for e in extras_of if e),
        "extras_total": sum(len(e) for e in extras_of),
        "exemplars": exemplars,
        "flagged_rows": flagged,
        "context_only_rows": context_only,
    }
    (OUTDIR / "verify.json").write_text(json.dumps(report, indent=1))
    print(json.dumps(report, indent=1))


# ---------------------------------------------------------------------------
# report — markdown tables from the summaries
# ---------------------------------------------------------------------------

def run_report(_args):
    summaries = []
    for p in sorted(OUTDIR.glob("summary-*.json")):
        summaries.append(json.loads(p.read_text()))
    if not summaries:
        print("no summaries yet")
        return

    def fmt(x, nd=3):
        return "—" if x is None else f"{x:.{nd}f}".lstrip("0") or "0"

    print("## Headline (bundle set-union F1 primary; next-form secondary)\n")
    print("| tag | arm | n | parse | no-call | bundle useful | recall | precision | "
          "substantive | nf useful | nf head-match | ≥.5 |")
    print("|---|---|---|---|---|---|---|---|---|---|---|---|")
    for s in summaries:
        print(f"| {s['tag']} | {s['arm']} | {s['n']} | {fmt(s['parse_rate'])} | "
              f"{fmt(s['no_call_pred_rate'])} | **{fmt(s['bundle_useful_mean'])}** | "
              f"{fmt(s.get('bundle_recall_mean'))} | {fmt(s.get('bundle_precision_mean'))} | "
              f"{fmt(s['substantive_mean'])} | {fmt(s['next_form_useful_mean'])} | "
              f"{fmt(s['next_form_head_match'])} | {fmt(s['bundle_useful_ge_0.5'])} |")
    print("| needle-extended | — | — | — | — | *pending* | *pending* | *pending* "
          "| *pending* | *pending* | *pending* | — | (joins from the extension-train lane)")

    print("\n## Coverage tranches (bundle useful mean)\n")
    print("| tag/arm | <.25 | .25–.75 | ≥.75 | Pearson |")
    print("|---|---|---|---|---|")
    for s in summaries:
        b = s["coverage"]["buckets"]
        cells = [f"{fmt(b[k]['useful_mean'])} ({b[k]['n']})" if k in b else "—"
                 for k in ("lo_lt_.25", "mid_.25-.75", "hi_ge_.75")]
        print(f"| {s['tag']}/{s['arm']} | " + " | ".join(cells) +
              f" | {fmt(s['coverage']['pearson_useful'])} |")

    print("\n## Per form-kind mean credit (bundle lens)\n")
    kinds = ["register", "query", "plan", "transact", "defn", "other", "ns-move"]
    print("| tag/arm | " + " | ".join(kinds) + " |")
    print("|---|" + "---|" * len(kinds))
    for s in summaries:
        row = []
        for k in kinds:
            v = s["kinds"].get(k)
            row.append(fmt(v["mean_credit"]) if v else "—")
        print(f"| {s['tag']}/{s['arm']} | " + " | ".join(row) + " |")

    print("\n## Decomposition (per target call / per prediction call)\n")
    print("| tag/arm | right-args | wrong-arg-keys | wrong-arg-values | missing | "
          "wrong-fn | hallucinated | spurious ids (grounded/invented) |")
    print("|---|---|---|---|---|---|---|---|")
    for s in summaries:
        d = s["decomp"]
        t, p = d["target_outcomes"], d["pred_outcomes"]
        nt = sum(t.values()) or 1
        np_ = sum(p.values()) or 1
        i = d["ids"]
        print(f"| {s['tag']}/{s['arm']} "
              f"| {t.get('right-args', 0)} ({t.get('right-args', 0)/nt:.0%}) "
              f"| {t.get('wrong-arg-keys', 0)} ({t.get('wrong-arg-keys', 0)/nt:.0%}) "
              f"| {t.get('wrong-arg-values', 0)} ({t.get('wrong-arg-values', 0)/nt:.0%}) "
              f"| {t.get('missing', 0)} ({t.get('missing', 0)/nt:.0%}) "
              f"| {p.get('wrong-fn', 0)} ({p.get('wrong-fn', 0)/np_:.0%}) "
              f"| {p.get('hallucinated-fn', 0)} ({p.get('hallucinated-fn', 0)/np_:.0%}) "
              f"| {i.get('spurious_grounded', 0)}/{i.get('spurious_invented', 0)} |")

    print("\n## Latency\n")
    print("| tag/arm | wall p50 | wall p90 | prompt tok p50 | prefill tok/s | "
          "gen tok p50 | decode tok/s | peak RAM | cap hits |")
    print("|---|---|---|---|---|---|---|---|---|")
    for s in summaries:
        l = s.get("latency")
        if not l:
            continue
        print(f"| {s['tag']}/{s['arm']} | {l.get('wall_s_median', '—')}s | "
              f"{l.get('wall_s_p90', '—')}s | {l.get('prompt_tokens_median', '—')} | "
              f"{l.get('prompt_tps_median', '—')} | {l.get('gen_tokens_median', '—')} | "
              f"{l.get('gen_tps_median', '—')} | {l.get('peak_mem_gb', '—')} GB | "
              f"{l.get('hit_token_cap', '—')} |")

    # Fair-scored columns (scoring v3, fair_score.py) — shown when the fair
    # rescore has run for this run's arms.
    fair_dir = OUTDIR.parent / "fair"
    fair = [json.loads(p.read_text())
            for p in sorted(fair_dir.glob("summary-fair-kt3redux-*.json"))]
    if fair:
        print("\n## Fair-scored (scoring v3 — see fair_score.py report for all arms)\n")
        print("| arm | n | L2 gated | L3 fired | L4 useful | FAIR useful | Δ |")
        print("|---|---|---|---|---|---|---|")
        for s in fair:
            print(f"| {s['arm']} | {s['n_clean']} | {s['l2_gated_rows']} | "
                  f"{s['l3_fired_rate']} | {s['l4_useful_mean']} | "
                  f"**{s['fair_useful_mean']}** | {s['delta_fair_minus_l4']} |")


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)

    v = sub.add_parser("verify")
    v.set_defaults(fn=run_verify)

    a = sub.add_parser("api")
    a.add_argument("--provider", choices=PROVIDERS, required=True)
    a.add_argument("--limit", type=int, default=0)
    a.add_argument("--concurrency", type=int, default=6)
    a.add_argument("--cards", choices=("full", "row"), default="full")
    a.add_argument("--layout", choices=("plain", "sandwich", "structured",
                                        "retrieve"), default="plain")
    a.add_argument("--thinking", action="store_true")
    a.set_defaults(fn=run_api)

    l = sub.add_parser("local")
    l.add_argument("--model", required=True)
    l.add_argument("--tag", required=True)
    l.add_argument("--arms", default="cont-few")
    l.add_argument("--adapter-path", default=None,
                   help="LoRA adapter dir (mlx_lm.load adapter_path)")
    l.add_argument("--limit", type=int, default=0)
    l.add_argument("--cards", choices=("full", "row"), default="full")
    l.add_argument("--layout", choices=("plain", "sandwich", "structured",
                                        "retrieve"), default="plain")
    l.set_defaults(fn=run_local)

    r = sub.add_parser("report")
    r.set_defaults(fn=run_report)

    args = ap.parse_args()
    args.fn(args)


if __name__ == "__main__":
    main()

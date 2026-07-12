#!/usr/bin/env python3
"""Generate seon long-menu TRAINING queries via agy (gemini CLI).

For every fn in the dumped 168-fn index (data/fn-index.json, KT2b's
translated tool defs), asks agy for 6 distinct natural situations + the
correct arguments; plus one batch of irrelevance queries (no tool
applies). Mechanical gates (parse ∧ param-keys ⊆ tool params ∧ required
params present ∧ scalar argument values literally present in the query
text — the ingredients-coverage rule applied to training data) drop bad
rows; drops are counted, never patched.

Leakage guards: the eval bank cases/kt2b_cases.json is NEVER emitted
verbatim (checked here AND at dataset build); the base-case
expected_args serve only as few-shot GROUNDING for realistic argument
shapes. Paraphrase-family style overlap with the eval bank is inherent
and reported, not hidden (the eval split by seen/held-out fn carries the
generalization signal).

Output: cases/extend_train_queries.json (committed — the dataset build's
reproducible input). Resume-safe: progress lands per batch in
data/extend/gen_progress.jsonl (gitignored).

Run (from src-needle/):  .venv/bin/python scripts/gen_seon_queries.py
"""

import datetime
import json
import re
import subprocess
import sys
import time
from pathlib import Path

PKG_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PKG_ROOT / "src"))

from seon_needle.lint_probe import Registry, fn_to_tool  # noqa: E402

FN_INDEX = PKG_ROOT / "data" / "fn-index.json"
KT2B_CASES = PKG_ROOT / "cases" / "kt2b_cases.json"
OUT = PKG_ROOT / "cases" / "extend_train_queries.json"
PROGRESS = PKG_ROOT / "data" / "extend" / "gen_progress.jsonl"

BATCH = 8
PER_FN = 6
N_IRRELEVANCE = 90

PROMPT_HEAD = """You are generating training data for a tiny on-device \
function-calling model that assists a Clojure REPL agent system. For EACH \
tool below, write {per_fn} DISTINCT natural one-sentence user situations \
that would make an assistant call exactly that tool, plus the correct \
arguments.

Rules:
- Vary the style: imperative ask, question, status statement, terse note.
- Use ONLY the listed parameter names for arguments.
- Every argument value MUST appear literally in the situation text \
(the model learns to copy, not invent). Include all required parameters.
- Ids look like "kJm-2607121415" or "dpl-2607112358" — invent similar ones.
- Keyword-typed string values keep a leading colon, e.g. ":active".
- For tools with a single opaque "request" parameter, pass {{}} unless the \
description names fields.
- Situations must be <= 30 words, concrete, no markdown.

Style examples (do NOT copy these):
  "Step kJm-2607121415 is finished and its outcome verified — record its \
completion." -> {{"id": "kJm-2607121415"}}
  "Add a plan step titled \\"write the rollback fn\\" under the parent step \
dpl-2607112358." -> {{"title": "write the rollback fn"}}

Output STRICT JSON only (no prose, no fences): a list of
{{"name": "<tool sym>", "situations": [{{"query": "...", "arguments": {{...}}}}]}}

Tools:
"""

IRRELEVANCE_PROMPT = """You are generating ABSTENTION training data for a \
tiny function-calling model inside a Clojure REPL agent system. Write {n} \
distinct one-sentence user situations/asks that sound plausible in a \
software/agent context but are answerable by NONE of this system's tools. \
The system's tools only cover: plans/todos, a datalog database \
(query/transact/schema), REPL namespaces and evaluation, agent \
inspection/errors, web fetch/search, shell, blobs, rendering tiles/canvas, \
messaging between agents. So write asks OUTSIDE that surface (e.g. \
opinions, physical-world actions, product pricing, small talk, math \
trivia, requests for features that don't exist). Vary style and length \
(<= 25 words). Output STRICT JSON only: a list of strings."""


def agy(prompt, timeout=240):
    r = subprocess.run(["agy", "-p", prompt], capture_output=True,
                       text=True, timeout=timeout)
    return r.stdout.strip()


def extract_json(text):
    """First balanced JSON array in the reply (fence-tolerant)."""
    m = re.search(r"```(?:json)?\s*\n(.*?)```", text, re.DOTALL)
    if m:
        text = m.group(1)
    start = text.find("[")
    if start < 0:
        raise ValueError("no JSON array")
    depth, in_str, esc = 0, False, False
    for i in range(start, len(text)):
        c = text[i]
        if in_str:
            if esc:
                esc = False
            elif c == "\\":
                esc = True
            elif c == '"':
                in_str = False
            continue
        if c == '"':
            in_str = True
        elif c == "[":
            depth += 1
        elif c == "]":
            depth -= 1
            if depth == 0:
                return json.loads(text[start:i + 1])
    raise ValueError("unbalanced JSON array")


def value_in_query(v, query):
    ql = query.lower()
    if isinstance(v, bool):
        return True
    if isinstance(v, (int, float)):
        return str(v) in query or f"{v:g}" in query
    if isinstance(v, str):
        if len(v) < 4:
            return True
        return v.lower() in ql or v.lstrip(":").lower() in ql
    return True  # dict/list values: not gated (counted upstream)


def gate(sit, tool, eval_queries):
    q = (sit.get("query") or "").strip()
    if not q or len(q) > 400 or q in eval_queries:
        return None
    args = sit.get("arguments")
    if not isinstance(args, dict):
        return None
    params = tool.get("parameters") or {}
    if not set(args) <= set(params):
        return None
    required = {p for p, spec in params.items() if spec.get("required")}
    if not required <= set(args):
        return None
    if not all(value_in_query(v, q) for v in args.values()):
        return None
    return {"query": q, "arguments": args}


def tool_block(sym, tool, seed_args):
    lines = [f'- name: {sym}', f'  description: {tool["description"] or "(none)"}']
    params = tool.get("parameters") or {}
    if params:
        ps = {p: {k: v for k, v in spec.items()} for p, spec in params.items()}
        lines.append(f"  parameters: {json.dumps(ps)}")
    else:
        lines.append("  parameters: {} (call with empty arguments)")
    if seed_args is not None:
        lines.append(f"  example arguments (invent different values): {json.dumps(seed_args)}")
    return "\n".join(lines)


def main():
    index = json.loads(FN_INDEX.read_text())
    registry = Registry(index["schemas"])
    tools = {}
    for row in index["fns"]:
        tool, _ = fn_to_tool(row, registry)
        tools[row["seon.fn/sym"]] = tool

    cases = json.loads(KT2B_CASES.read_text())["cases"]
    eval_queries = {c["query"].strip() for c in cases}
    seed_args = {}
    for c in cases:
        if c.get("expected") and c.get("expected_args") is not None:
            seed_args.setdefault(c["expected"], c["expected_args"])

    PROGRESS.parent.mkdir(parents=True, exist_ok=True)
    done = {}
    if PROGRESS.exists():
        for line in PROGRESS.read_text().splitlines():
            d = json.loads(line)
            done[d["sym"]] = d["situations"]

    syms = sorted(tools)
    todo = [s for s in syms if s not in done]
    print(f"{len(syms)} fns, {len(todo)} to generate", flush=True)

    dropped = 0
    with PROGRESS.open("a") as prog:
        for start in range(0, len(todo), BATCH):
            batch = todo[start:start + BATCH]
            prompt = PROMPT_HEAD.format(per_fn=PER_FN) + "\n\n".join(
                tool_block(s, tools[s], seed_args.get(s)) for s in batch)
            got = {}
            for attempt in range(3):
                try:
                    reply = agy(prompt)
                    data = extract_json(reply)
                    for entry in data:
                        sym = entry.get("name")
                        if sym not in batch:
                            continue
                        kept = []
                        for sit in entry.get("situations", []):
                            g = gate(sit, tools[sym], eval_queries)
                            if g:
                                kept.append(g)
                            else:
                                dropped += 1
                        got[sym] = kept
                    break
                except (ValueError, json.JSONDecodeError,
                        subprocess.TimeoutExpired) as e:
                    print(f"  batch@{start} attempt {attempt}: {e}", flush=True)
                    time.sleep(3)
            for sym in batch:
                sits = got.get(sym, [])
                prog.write(json.dumps({"sym": sym, "situations": sits}) + "\n")
                prog.flush()
                done[sym] = sits
            n_ok = sum(len(v) for v in got.values())
            print(f"  [{start + len(batch)}/{len(todo)}] +{n_ok} situations "
                  f"(dropped so far {dropped})", flush=True)

    # irrelevance
    irr_key = "__irrelevance__"
    if irr_key not in done:
        queries = []
        for attempt in range(3):
            try:
                data = extract_json(agy(IRRELEVANCE_PROMPT.format(n=N_IRRELEVANCE)))
                queries = [q.strip() for q in data if isinstance(q, str)
                           and q.strip() and q.strip() not in eval_queries]
                break
            except (ValueError, json.JSONDecodeError, subprocess.TimeoutExpired) as e:
                print(f"  irrelevance attempt {attempt}: {e}", flush=True)
                time.sleep(3)
        with PROGRESS.open("a") as prog:
            prog.write(json.dumps({"sym": irr_key,
                                   "situations": [{"query": q} for q in queries]}) + "\n")
        done[irr_key] = [{"query": q} for q in queries]

    out_queries = []
    for sym in sorted(done):
        if sym == "__irrelevance__":
            for i, sit in enumerate(done[sym]):
                out_queries.append({"id": f"irr-{i}", "expected": None,
                                    "query": sit["query"]})
        else:
            for i, sit in enumerate(done[sym]):
                out_queries.append({"id": f"{sym}#{i}", "expected": sym,
                                    "query": sit["query"],
                                    "arguments": sit.get("arguments", {})})

    covered = len({q["expected"] for q in out_queries if q["expected"]})
    OUT.write_text(json.dumps({
        "comment": "agy-generated seon TRAINING queries for the needle "
                   "extension finetune (scripts/gen_seon_queries.py). "
                   "Gated mechanically: param-keys subset, required present, "
                   "scalar arg values literally in the query. kt2b_cases.json "
                   "queries excluded verbatim (they are EVAL). Style-seeded "
                   "by the case bank's expected_args (grounding only).",
        "generated": datetime.date.today().isoformat(),
        "model": "agy (gemini-3.5-flash)",
        "n_queries": len(out_queries),
        "fns_covered": covered,
        "dropped_by_gates": dropped,
        "queries": out_queries,
    }, indent=1, ensure_ascii=False))
    print(f"wrote {OUT} ({len(out_queries)} queries, {covered} fns covered, "
          f"{dropped} dropped by gates)")


if __name__ == "__main__":
    main()

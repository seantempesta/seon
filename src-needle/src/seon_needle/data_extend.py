"""Extension-finetune dataset build — home-distribution long-packing + seon
long-menu rows (docs/prds/repl-autosuggest, needle-extension train unit).

Two goals, two strata (design.md "Two vehicles"; the needle arm is
JSON-native end-to-end):

1. HOME, PACKED LONG (anti-forgetting + length teaching). Needle's actual
   post-train set (HF Cactus-Compute/tool-calls) is PRIVATE (401 without a
   token — verified 2026-07-12), so the home stratum is an on-distribution
   substitute, documented honestly:
     - argilla/Synth-APIGen-v0.1 (public, 49k rows) — the APIGen/xlam
       lineage: {query, tools, answers} single-shot function calling, the
       same task shape needle post-trained on. Rows whose answers are
       prose ("cannot be answered") become ABSTENTION rows (target []).
     - MadeAgents/xlam-irrelevance-7.5k (public) — real-API-flavored
       irrelevance rows (query + tools that don't apply -> []).
     - The DISTRACTOR BANK for menu enlargement is needle's OWN home tool
       universe: the 322 tool defs in the 33 POOL_* lists of
       reference-code/needle/needle/dataset/generate.py (their open
       dataset-generation source), extracted by AST literal-eval (that
       module imports google.genai at top level, so it cannot be imported
       directly), plus tools harvested from other Synth-APIGen rows.
   Packing = the dataset.py idea re-based: instead of concat-packing
   multiple examples (needs block-diagonal masks in this enc-dec shape),
   each row's TOOLS SLOT is enlarged with shuffled distractor tools until
   the encoder assembly reaches a sampled target length in (400, 2044].
   This teaches long-menu selection ON the home distribution.

2. SEON LONG-MENU rows (the whole-graph goal). Queries come from
   cases/extend_train_queries.json (agy-generated per fn of the dumped
   168-fn index, mechanically gated — scripts/gen_seon_queries.py) plus
   the KT2b case-bank SEEDS' expected_args; menus are KT2b's translated
   tools (lint_probe.fn_to_tool over data/fn-index.json) at sizes
   {8,16,24,32}, expected tool at a uniformly random position, half
   same-namespace distractors (the hard case). Irrelevance rows (~18%)
   use all-distractor menus. HELD OUT of training targets: the eval
   queries in cases/kt2b_cases.json (verbatim-excluded) and a seeded
   10-fn holdout of case-bank expected fns (never a training TARGET,
   still legal as distractors) so the lint-probe re-run can report
   seen-fn vs unseen-fn selection.

Loss-weighting classes (0=base 1=name 2=value 3=key) are the reference
implementation's own — _token_classes_for_answer and the two _mark
helpers are ADAPTED from reference-code/needle/needle/dataset/dataset.py
(that module imports the heavyweight `datasets` dep at top level and uses
relative imports, so its ~70 lines are re-derived here, same semantics —
the lint_probe normalize_tools precedent).

The 214 mined rows in data/tune/*.jsonl are HELD-OUT EVAL ONLY and are
never touched here (the design's held-out rule).

Run (from src-needle/):
  .venv/bin/python -m seon_needle.data_extend        # build train/val JSONL
Outputs under data/extend/ (gitignored): train.jsonl, val.jsonl,
meta.json. Sizes in TOKENS, always.
"""

import ast
import hashlib
import json
import random
import re
from collections import Counter
from pathlib import Path

import numpy as np

from . import config
from .lint_probe import fn_to_tool, Registry, normalize_tools

PKG_ROOT = config.package_root()
REPO_ROOT = config.repo_root()
OUT_DIR = PKG_ROOT / "data" / "extend"
GENERATE_PY = (REPO_ROOT / "reference-code" / "needle" / "needle"
               / "dataset" / "generate.py")
TRAIN_QUERIES = PKG_ROOT / "cases" / "extend_train_queries.json"
KT2B_CASES = PKG_ROOT / "cases" / "kt2b_cases.json"
FN_INDEX = PKG_ROOT / "data" / "fn-index.json"

MAX_ENC = 2048
MAX_DEC = 512
HOLDOUT_SEED = 42
N_HOLDOUT_FNS = 10


# ---------------------------------------------------------------------------
# Home tool pools (AST extraction — generate.py imports google.genai)
# ---------------------------------------------------------------------------

def home_pools():
    """The 322 home tool defs from generate.py's POOL_* lists."""
    tree = ast.parse(GENERATE_PY.read_text())
    tools = []
    for node in tree.body:
        if isinstance(node, ast.Assign) and len(node.targets) == 1 \
                and isinstance(node.targets[0], ast.Name) \
                and node.targets[0].id.startswith("POOL_"):
            tools.extend(ast.literal_eval(node.value))
    return tools


# ---------------------------------------------------------------------------
# Public-dataset conversion to needle's home flat format
# ---------------------------------------------------------------------------

_JSON_SCHEMA_TYPES = {
    "string": "string", "integer": "number", "number": "number",
    "boolean": "boolean", "array": "array", "object": "dict",
    # xlam-style names
    "str": "string", "int": "number", "float": "number", "bool": "boolean",
    "list": "array", "dict": "dict", "tuple": "array", "set": "array",
}


def _map_type(t):
    if isinstance(t, list):  # union types like ["string", "null"]
        t = next((x for x in t if x and x != "null"), "string")
    if not isinstance(t, str):
        t = "string"
    t = (t or "string").split("[")[0].split(",")[0].strip().lower()
    return _JSON_SCHEMA_TYPES.get(t, "string")


def openai_tool_to_home(t):
    """OpenAI-schema tool (Synth-APIGen) -> needle home flat format."""
    fn = t.get("function", t)
    params = fn.get("parameters") or {}
    required = set(params.get("required") or [])
    out = {}
    for pname, p in (params.get("properties") or {}).items():
        if not isinstance(p, dict):
            p = {}
        entry = {"type": _map_type(p.get("type")),
                 "description": (p.get("description") or "").split("\n")[0][:120]}
        if pname in required:
            entry["required"] = True
        out[pname] = entry
    return {"name": fn["name"],
            "description": (fn.get("description") or "").split("\n")[0][:160],
            "parameters": out}


def xlam_tool_to_home(t):
    """xlam-style tool ({desc, type, default} params) -> home flat format."""
    out = {}
    for pname, p in (t.get("parameters") or {}).items():
        entry = {"type": _map_type(p.get("type")),
                 "description": (p.get("description") or "").split("\n")[0][:120]}
        if "default" not in p:
            entry["required"] = True
        out[pname] = entry
    return {"name": t["name"],
            "description": (t.get("description") or "").split("\n")[0][:160],
            "parameters": out}


def load_synth_apigen():
    """(answered rows, unanswerable rows) in home format, validated.

    answered: every answer name is in the row's own tools, answers parse.
    unanswerable: prose answers / no-tool rows -> abstention (target [])."""
    from huggingface_hub import hf_hub_download
    import pyarrow.parquet as pq

    p = hf_hub_download("argilla/Synth-APIGen-v0.1",
                        "data/train-00000-of-00001.parquet",
                        repo_type="dataset")
    t = pq.read_table(p)
    queries = t.column("query").to_pylist()
    tools_col = t.column("tools").to_pylist()
    ans_col = t.column("answers").to_pylist()

    answered, unanswerable, dropped = [], [], 0
    for q, ts, ans in zip(queries, tools_col, ans_col):
        try:
            tools = [openai_tool_to_home(x) for x in json.loads(ts)]
        except (ValueError, TypeError, KeyError):
            dropped += 1
            continue
        names = {x["name"] for x in tools}
        try:
            calls = json.loads(ans)
        except (ValueError, TypeError):
            calls = None
        if isinstance(calls, list) and calls:
            if not all(isinstance(c, dict) and c.get("name") in names
                       and isinstance(c.get("arguments"), dict) for c in calls):
                dropped += 1
                continue
            answered.append({"query": q, "tools": tools, "calls": calls})
        else:
            # prose answer ("cannot be answered") or empty -> abstention
            unanswerable.append({"query": q, "tools": tools, "calls": []})
    return answered, unanswerable, dropped


def load_xlam_irrelevance():
    from huggingface_hub import hf_hub_download
    p = hf_hub_download("MadeAgents/xlam-irrelevance-7.5k",
                        "xlam-7.5k-irrelevancek.json", repo_type="dataset")
    rows = json.load(open(p))
    out, dropped = [], 0
    for r in rows:
        try:
            tools = [xlam_tool_to_home(t) for t in json.loads(r["tools"])]
        except (ValueError, TypeError, KeyError):
            dropped += 1
            continue
        out.append({"query": r["query"], "tools": tools, "calls": []})
    return out, dropped


# ---------------------------------------------------------------------------
# Tool/param shuffling (reference dataset.py _shuffle_tools_json, list-based)
# ---------------------------------------------------------------------------

def shuffle_tools(tools, rng):
    """Shuffle tool order, per-tool param order, and top-level key order —
    the reference's anti-position-memorization trick, on parsed lists."""
    tools = [dict(t) for t in tools]
    if len(tools) > 1:
        rng.shuffle(tools)
    for tool in tools:
        params = tool.get("parameters")
        if isinstance(params, dict) and len(params) > 1:
            keys = list(params)
            rng.shuffle(keys)
            tool["parameters"] = {k: params[k] for k in keys}
        top = list(tool)
        if len(top) > 1:
            rng.shuffle(top)
            shuffled = {k: tool[k] for k in top}
            tool.clear()
            tool.update(shuffled)
    return tools


# ---------------------------------------------------------------------------
# Token-class labels (adapted from reference dataset.py — see module doc)
# ---------------------------------------------------------------------------

TOKEN_CLASS_BASE, TOKEN_CLASS_NAME, TOKEN_CLASS_VALUE, TOKEN_CLASS_KEY = 0, 1, 2, 3


def _mark_json_value(s, char_cls, key, value_str, weight):
    pattern_str = f'"{re.escape(key)}"\\s*:\\s*"{re.escape(value_str)}"'
    for m in re.finditer(pattern_str, s):
        tail = s[m.start() + len(f'"{key}"'):m.end()]
        val_offset = tail.index(f'"{value_str}"') + 1
        val_start = m.start() + len(f'"{key}"') + val_offset
        val_end = val_start + len(value_str)
        char_cls[val_start:val_end] = np.maximum(char_cls[val_start:val_end], weight)
        return
    pattern_ns = f'"{re.escape(key)}"\\s*:\\s*{re.escape(value_str)}'
    for m in re.finditer(pattern_ns, s):
        colon_offset = s[m.start():m.end()].index(":")
        val_start = m.start() + colon_offset + 1
        while val_start < m.end() and s[val_start] == " ":
            val_start += 1
        char_cls[val_start:m.end()] = np.maximum(char_cls[val_start:m.end()], weight)
        return


def _mark_json_key_in_args(s, char_cls, key, weight):
    for m in re.finditer(f'"{re.escape(key)}"\\s*:', s):
        char_cls[m.start() + 1:m.start() + 1 + len(key)] = np.maximum(
            char_cls[m.start() + 1:m.start() + 1 + len(key)], weight)


def token_classes_for_answer(answer_str, token_ids, sp_model):
    """Per-token class labels (int8) over the tokenized answer JSON."""
    n = len(token_ids)
    classes = np.zeros(n, dtype=np.int8)
    try:
        calls = json.loads(answer_str)
    except (ValueError, TypeError):
        return classes
    if not isinstance(calls, list):
        return classes

    char_cls = np.zeros(len(answer_str), dtype=np.int8)
    for call in calls:
        if not isinstance(call, dict):
            continue
        name = call.get("name", "")
        if name:
            _mark_json_value(answer_str, char_cls, "name", name, TOKEN_CLASS_NAME)
        args = call.get("arguments", {})
        if isinstance(args, dict):
            for k, v in args.items():
                _mark_json_key_in_args(answer_str, char_cls, k, TOKEN_CLASS_KEY)
                # compact separators so list/dict values match the compacted
                # answer JSON (the reference dumps with spaces and thereby
                # never marks non-scalar values — deliberate improvement)
                v_str = (json.dumps(v, separators=(",", ":"))
                         if not isinstance(v, str) else v)
                _mark_json_value(answer_str, char_cls, k, v_str, TOKEN_CLASS_VALUE)

    pieces = sp_model.Encode(answer_str, out_type=str)
    pos = 0
    for i, piece in enumerate(pieces):
        if i >= n:
            break
        raw = piece.replace("▁", " ")
        plen = len(raw)
        if plen > 0 and pos + plen <= len(answer_str):
            classes[i] = char_cls[pos:pos + plen].max()
            pos += plen
        else:
            pos += max(plen, 1)
    return classes


# ---------------------------------------------------------------------------
# Menu enlargement to a target encoder length
# ---------------------------------------------------------------------------

def compact(x):
    return json.dumps(x, separators=(",", ":"))


class ToolBank:
    """Distractor bank with cached per-tool token lengths (name-deduped)."""

    def __init__(self, tools, tokenizer):
        self.tools, self._len, seen = [], {}, set()
        for t in tools:
            if t["name"] in seen:
                continue
            seen.add(t["name"])
            self.tools.append(t)
            self._len[t["name"]] = len(tokenizer.encode(compact(t))) + 1

    def tok_len(self, name):
        return self._len[name]


def enlarge_menu(row, bank, rng, tokenizer, target_total,
                 max_total=MAX_ENC, own_lens=None):
    """Add shuffled bank distractors to the row's tools until the encoder
    assembly (query + sep + tools json) approaches target_total tokens.

    Returns (tools list incl. distractors, approx assembly length)."""
    q_len = len(tokenizer.encode(row["query"])) + 1  # + <tools> sep
    own = list(row["tools"])
    own_names = {t["name"] for t in own}
    if own_lens is None:
        own_lens = [len(tokenizer.encode(compact(t))) + 1 for t in own]
    total = q_len + 2 + sum(own_lens)  # 2 ≈ the [ ] brackets
    order = rng.sample(range(len(bank.tools)), len(bank.tools))
    tools = own
    for i in order:
        t = bank.tools[i]
        if t["name"] in own_names:
            continue
        tl = bank.tok_len(t["name"])
        if total + tl > min(target_total, max_total):
            break
        tools = tools + [t]
        own_names.add(t["name"])
        total += tl
    return shuffle_tools(tools, rng), total


# ---------------------------------------------------------------------------
# Seon stratum
# ---------------------------------------------------------------------------

def seon_tools():
    """{sym -> translated tool (snake name)} + snake->sym map, KT2b path."""
    d = json.loads(FN_INDEX.read_text())
    registry = Registry(d["schemas"])
    tools = {}
    for row in d["fns"]:
        tool, _notes = fn_to_tool(row, registry)
        tools[row["seon.fn/sym"]] = tool
    tj, name_map = normalize_tools(compact(list(tools.values())))
    norm = json.loads(tj)
    by_sym = {}
    snake_to_sym = {}
    for t in norm:
        sym = name_map[t["name"]]
        by_sym[sym] = t
        snake_to_sym[t["name"]] = sym
    return by_sym, snake_to_sym


def holdout_fns(case_expected_fns):
    """Seeded pick of case-bank fns NEVER used as training targets."""
    pool = sorted(set(case_expected_fns))
    return set(random.Random(HOLDOUT_SEED).sample(pool, N_HOLDOUT_FNS))


def build_seon_menu(expected_sym, all_syms, n_tools, rng):
    """KT2b build_menu semantics: half same-ns distractors, half random;
    expected inserted at a uniform position (no post-shuffle so the
    position is a recorded training variable)."""
    pool = sorted(all_syms)
    if expected_sym is None:
        return rng.sample(pool, n_tools), None
    same_ns = [s for s in pool if s != expected_sym
               and s.split("/")[0] == expected_sym.split("/")[0]]
    others = [s for s in pool if s != expected_sym
              and s.split("/")[0] != expected_sym.split("/")[0]]
    n_d = n_tools - 1
    n_same = min(len(same_ns), n_d // 2)
    menu = rng.sample(same_ns, n_same) + rng.sample(others, n_d - n_same)
    rng.shuffle(menu)
    pos = rng.randrange(n_tools)
    menu.insert(pos, expected_sym)
    return menu, pos


def load_train_queries():
    """agy-generated training queries (scripts/gen_seon_queries.py)."""
    d = json.loads(TRAIN_QUERIES.read_text())
    return d["queries"]


# ---------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------

MENU_SIZES = (8, 16, 24, 32)
MENU_WEIGHTS = (0.20, 0.30, 0.25, 0.25)


def _val_split(key, frac=0.03):
    return int(hashlib.sha256(key.encode()).hexdigest()[:8], 16) % 10000 < frac * 10000


def build(n_home=10000, n_home_irr=1800, seed=7, verbose=True):
    from .tokenizer import load_tokenizer
    tokenizer = load_tokenizer()
    rng = random.Random(seed)

    # ---- home stratum -----------------------------------------------------
    answered, unanswerable, drop_a = load_synth_apigen()
    xlam_irr, drop_x = load_xlam_irrelevance()
    rng.shuffle(answered)
    rng.shuffle(unanswerable)
    rng.shuffle(xlam_irr)

    harvest = [t for r in answered[n_home:n_home + 4000] for t in r["tools"]]
    bank = ToolBank(home_pools() + harvest, tokenizer)

    home_rows = []
    skipped_dec = 0
    picks = ([("home", r) for r in answered[:n_home]]
             + [("home-irrelevance", r) for r in unanswerable[:n_home_irr // 2]]
             + [("home-irrelevance", r) for r in xlam_irr[:n_home_irr - n_home_irr // 2]])
    for src, r in picks:
        answers = compact(r["calls"])
        if len(tokenizer.encode(answers)) > MAX_DEC - 3:
            skipped_dec += 1
            continue
        # length curriculum: 30% short (native-ish), 70% extended
        if rng.random() < 0.30:
            target = rng.randint(400, 1024)
        else:
            target = rng.randint(1025, MAX_ENC - 4)
        tools, approx = enlarge_menu(r, bank, rng, tokenizer, target)
        home_rows.append({"query": r["query"], "tools": compact(tools),
                          "answers": answers, "src": src,
                          "n_tools": len(tools)})

    # ---- seon stratum ------------------------------------------------------
    by_sym, snake_to_sym = seon_tools()
    all_syms = sorted(by_sym)
    eval_cases = json.loads(KT2B_CASES.read_text())["cases"]
    eval_queries = {c["query"].strip() for c in eval_cases}
    case_fns = [c["expected"] for c in eval_cases if c.get("expected")]
    held = holdout_fns(case_fns)

    queries = load_train_queries()
    seon_rows, excluded_eval, excluded_held = [], 0, 0
    seen_target_fns = set()
    for q in queries:
        if q["query"].strip() in eval_queries:
            excluded_eval += 1
            continue
        expected = q.get("expected")  # None => irrelevance
        if expected is not None and expected in held:
            excluded_held += 1
            continue
        if expected is not None and expected not in by_sym:
            continue
        # each query gets 2 menu-size variants; irrelevance queries get 4
        # (brief: irrelevance ~15-20% of the seon stratum — KT2b's
        # false-suggestion .25 must go DOWN)
        for variant in range(4 if expected is None else 2):
            vrng = random.Random(f"{seed}|{q['id']}|{variant}")
            n_tools = vrng.choices(MENU_SIZES, MENU_WEIGHTS)[0]
            menu, pos = build_seon_menu(expected, all_syms, n_tools, vrng)
            tools = [by_sym[s] for s in menu]
            tj = compact(tools)
            total = (len(tokenizer.encode(q["query"])) + 3
                     + len(tokenizer.encode(tj)))
            while total > MAX_ENC and len(menu) > 4:
                # trim a distractor from the END (keeps expected's slot)
                for k in range(len(menu) - 1, -1, -1):
                    if menu[k] != expected:
                        menu.pop(k)
                        break
                tools = [by_sym[s] for s in menu]
                tj = compact(tools)
                total = (len(tokenizer.encode(q["query"])) + 3
                         + len(tokenizer.encode(tj)))
            if expected is None:
                answers = "[]"
                src = "seon-irrelevance"
            else:
                args = q.get("arguments") or {}
                answers = compact([{"name": by_sym[expected]["name"],
                                    "arguments": args}])
                src = "seon"
                seen_target_fns.add(expected)
            if len(tokenizer.encode(answers)) > MAX_DEC - 3:
                continue
            seon_rows.append({
                "query": q["query"], "tools": compact([by_sym[s] for s in menu]),
                "answers": answers, "src": src, "n_tools": len(menu),
                "expected": expected,
                "expected_pos": None if pos is None else menu.index(expected),
            })

    # ---- split + write -----------------------------------------------------
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    train, val = [], []
    for i, row in enumerate(home_rows + seon_rows):
        (val if _val_split(f"{row['src']}|{i}|{row['query'][:64]}") else train).append(row)
    rng.shuffle(train)

    with open(OUT_DIR / "train.jsonl", "w") as f:
        for r in train:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    with open(OUT_DIR / "val.jsonl", "w") as f:
        for r in val:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")

    meta = {
        "home_source": "argilla/Synth-APIGen-v0.1 + MadeAgents/xlam-irrelevance-7.5k"
                       " (Cactus-Compute/tool-calls is PRIVATE — 401, no local token)",
        "distractor_bank": {"n_tools": len(bank.tools),
                            "home_pools": 322},
        "counts": Counter(r["src"] for r in train + val),
        "train": len(train), "val": len(val),
        "apigen_dropped": drop_a, "xlam_dropped": drop_x,
        "skipped_dec_overflow": skipped_dec,
        "seon": {
            "train_queries_file": str(TRAIN_QUERIES.name),
            "excluded_eval_verbatim": excluded_eval,
            "held_out_fns": sorted(held),
            "excluded_heldout_fn_queries": excluded_held,
            "distinct_target_fns": len(seen_target_fns),
        },
        "menu_sizes": MENU_SIZES, "menu_weights": MENU_WEIGHTS,
        "max_enc": MAX_ENC, "max_dec": MAX_DEC, "seed": seed,
    }
    meta["counts"] = dict(meta["counts"])
    (OUT_DIR / "meta.json").write_text(json.dumps(meta, indent=1))
    if verbose:
        print(json.dumps(meta, indent=1))
        print(f"wrote {OUT_DIR}/train.jsonl ({len(train)}) + val.jsonl ({len(val)})")
    return meta


if __name__ == "__main__":
    build()

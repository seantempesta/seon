"""KT2b legibility-lint probe — TEMPORARY translation layer, not a bridge.

Probes the STOCK needle checkpoint (26M, native task: English query + JSON
tool defs -> JSON tool call) against the seon agent-facing function index,
translated to needle's home format. If a 26M assembly machine can pick the
right seon function from name + docstring line-1 + schema params alone, that
function is agent-legible; per-fn accuracy = the legibility leaderboard v0.
Zero training. This module is a PROBE (design.md KT2b) — it is deliberately
lossy where malli does not map onto JSON-schema params (refs, nested maps
become "dict"; gaps are recorded per tool and reported, never papered over).

Inputs
  data/fn-index.json          scripts/dump_fn_index.clj (acme wire REPL)
  cases/kt2b_cases.json       hand-curated situations (harness-derived; see
                              the research file for the source mapping)
  reference-code/gorilla-bfcl BFCL v4 live_simple + possible_answer
                              (calibration arm — needle's home benchmark)

Reused from reference-code/needle via importlib (never copied):
  needle/model/constrained.py   trie-constrained decoding (load-bearing:
                                unconstrained greedy garbles keys, B1)
  needle/dataset/tokenizer.py   to_snake_case (tool-name normalization)
normalize_tools/restore_tool_names live in needle/model/run.py whose import
chain needs jax + the heavyweight `datasets` dep, so their ~20 lines are
re-derived here ON TOP of the imported to_snake_case (same semantics).

Run (from src-needle/):
  .venv/bin/python -m seon_needle.lint_probe translate   # translation check
  .venv/bin/python -m seon_needle.lint_probe calibrate   # BFCL anchor arm
  .venv/bin/python -m seon_needle.lint_probe run         # seon probe arms
Results land under data/kt2b/ as JSON (gitignored; the research file quotes
them). Sizes/speeds in TOKENS, always.
"""

import importlib.util
import json
import random
import sys
import time
from collections import Counter
from pathlib import Path

import mlx.core as mx
import numpy as np

from . import config
from .model import load_model, make_padding_mask
from .tokenizer import DEFAULT_MAX_ENC_LEN, DEFAULT_MAX_GEN_LEN, load_tokenizer

REPO_ROOT = config.repo_root()
PKG_ROOT = config.package_root()
BFCL_DATA = (REPO_ROOT / "reference-code" / "gorilla-bfcl"
             / "berkeley-function-call-leaderboard" / "bfcl_eval" / "data")
OUT_DIR = PKG_ROOT / "data" / "kt2b"


# ---------------------------------------------------------------------------
# Reference imports (file-location, bypassing needle/__init__'s heavy deps)
# ---------------------------------------------------------------------------

def _import_ref(module_name, rel_path):
    path = REPO_ROOT / "reference-code" / "needle" / "needle" / rel_path
    spec = importlib.util.spec_from_file_location(module_name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


_ref_constrained = _import_ref("needle_ref_constrained", "model/constrained.py")
_ref_tokenizer = _import_ref("needle_ref_tokenizer", "dataset/tokenizer.py")
to_snake_case = _ref_tokenizer.to_snake_case
build_constrained_decoder = _ref_constrained.build_constrained_decoder


def normalize_tools(tools_json):
    """run.py normalize_tools re-derived (import chain needs jax+datasets).

    Snake-cases every tool name; returns (compact_json, snake->orig map)."""
    tools = json.loads(tools_json)
    name_map = {}
    for t in tools:
        if isinstance(t, dict) and "name" in t:
            snake = to_snake_case(t["name"])
            name_map[snake] = t["name"]
            t["name"] = snake
    return json.dumps(tools, separators=(",", ":")), name_map


def restore_names(calls, name_map):
    for c in calls:
        if isinstance(c, dict) and "name" in c:
            c["name"] = name_map.get(c["name"], c["name"])
    return calls


# ---------------------------------------------------------------------------
# Minimal EDN reader — just enough for pr-str'd malli forms
# ---------------------------------------------------------------------------

class Kw(str):
    """A Clojure keyword, stored WITH the leading colon."""


class Sym(str):
    """A Clojure symbol."""


_DELIMS = set('()[]{}"')


def read_edn(text):
    """Parse one pr-str'd malli form. Vectors/lists -> list, maps -> dict.

    Raises ValueError on any form it cannot make progress on (unterminated
    string, stray closer, empty atom) — NEVER loops. Callers treat a raise
    as a translation gap. Tagged literals (#object[...], #IntoSchema {...},
    #uuid "...") parse to Sym("#<tag>") — opaque, reported as gaps."""
    pos = [0]

    def fail(why):
        raise ValueError(f"read_edn: {why} at {pos[0]} in {text[:80]!r}")

    def peek():
        if pos[0] >= len(text):
            fail("unexpected end of input")
        return text[pos[0]]

    def skip_ws():
        while pos[0] < len(text) and (text[pos[0]].isspace() or text[pos[0]] == ","):
            pos[0] += 1

    def read_string():
        pos[0] += 1  # opening quote
        out = []
        while peek() != '"':
            ch = text[pos[0]]
            if ch == "\\":
                pos[0] += 1
                esc = peek()
                out.append({"n": "\n", "t": "\t", '"': '"', "\\": "\\"}.get(esc, esc))
            else:
                out.append(ch)
            pos[0] += 1
        pos[0] += 1  # closing quote
        return "".join(out)

    def read_atom():
        start = pos[0]
        while pos[0] < len(text) and not text[pos[0]].isspace() \
                and text[pos[0]] not in _DELIMS and text[pos[0]] != ",":
            pos[0] += 1
        tok = text[start:pos[0]]
        if not tok:
            fail(f"stray delimiter {text[pos[0]]!r}")
        if tok == "nil":
            return None
        if tok == "true":
            return True
        if tok == "false":
            return False
        if tok.startswith(":"):
            return Kw(tok)
        try:
            return int(tok)
        except ValueError:
            pass
        try:
            return float(tok)
        except ValueError:
            pass
        return Sym(tok)

    def read_seq(closer):
        pos[0] += 1  # opener
        items = []
        while True:
            skip_ws()
            if peek() == closer:
                pos[0] += 1
                return items
            before = pos[0]
            items.append(read_form())
            if pos[0] == before:
                fail("no progress")  # belt+braces: the 50GB bug class

    def read_form():
        skip_ws()
        ch = peek()
        if ch == '"':
            return read_string()
        if ch == "[":
            return read_seq("]")
        if ch == "(":
            return read_seq(")")
        if ch == "#":
            pos[0] += 1
            nxt = peek()
            if nxt == "{":
                return read_seq("}")  # set -> list
            if nxt == '"':
                return read_string()  # regex -> string
            tag = read_atom()  # #object[...], #IntoSchema {...}, #uuid "..."
            read_form()  # consume the tagged payload
            return Sym("#" + str(tag))
        if ch == "{":
            items = read_seq("}")
            return dict(zip([str(k) for k in items[0::2]], items[1::2]))
        return read_atom()

    return read_form()


# ---------------------------------------------------------------------------
# malli form -> needle tool JSON params
# ---------------------------------------------------------------------------

_PRIMITIVES = {
    ":string": "string", ":int": "integer", ":integer": "integer",
    ":double": "float", ":number": "float", ":float": "float",
    ":boolean": "boolean", ":keyword": "string", ":qualified-keyword": "string",
    ":symbol": "string", ":qualified-symbol": "string", ":inst": "string",
    ":uuid": "string", ":nat-int": "integer", ":pos-int": "integer",
    ":re": "string", ":uri": "string",
}
_ARRAYISH = {":vector", ":sequential", ":set", ":tuple", ":cat", ":+", ":*", ":repeat"}


class Registry:
    """The dumped :seon.schema key->form registry, parsed lazily."""

    def __init__(self, raw):
        self._raw = raw
        self._cache = {}

    def deref(self, kw):
        """Registered form for keyword `kw` (with colon), or None.
        Unparseable forms (tagged-literal soup) parse to an opaque Sym."""
        if kw not in self._raw:
            return None
        if kw not in self._cache:
            try:
                self._cache[kw] = read_edn(self._raw[kw])
            except (ValueError, RecursionError):
                self._cache[kw] = Sym("#unparseable")
        return self._cache[kw]

    def resolve(self, form, seen=None):
        """Follow keyword references until a structural form (cycle-safe).
        Primitives win over dumped built-in rows (:inst dumps as
        #IntoSchema); a deref landing on an opaque #tag stops one short."""
        seen = seen or set()
        while isinstance(form, Kw) and form in self._raw and form not in seen \
                and form not in _PRIMITIVES:
            seen.add(form)
            nxt = self.deref(form)
            if isinstance(nxt, Sym) and nxt.startswith("#"):
                return form
            form = nxt
        return form

    def json_type(self, form, seen=None):
        """(json_type, note) for a malli form. Lossy by design; note != None
        marks a translation gap (reported, never hidden)."""
        seen = seen or set()
        form = self.resolve(form, seen)
        if isinstance(form, Kw):
            if form in _PRIMITIVES:
                return _PRIMITIVES[form], None
            if form == ":map":
                return "dict", None
            if form == ":any":
                return "any", "malli :any"
            return "string", f"unresolved {form}"
        if isinstance(form, list) and form:
            head = form[0]
            if head == ":map" or head == ":map-of":
                return "dict", None
            if head in _ARRAYISH:
                return "array", None
            if head == ":enum":
                vals = [str(v) for v in form[1:] if not isinstance(v, dict)]
                return "string", "one of: " + ", ".join(vals)
            if head == ":and":
                children = [c for c in form[1:] if not isinstance(c, dict)]
                return self.json_type(children[0], seen) if children else ("any", ":and empty")
            if head in (":or", ":maybe"):
                children = [c for c in form[1:] if not isinstance(c, dict)]
                t, _ = self.json_type(children[0], seen) if children else ("any", None)
                note = f"{head} of {len(children)} branches" if head == ":or" and len(children) > 1 else None
                return t, note
            if head == ":=":
                lit = form[1]
                return ("boolean" if isinstance(lit, bool)
                        else "integer" if isinstance(lit, int)
                        else "string"), f"always {lit}"
            if isinstance(head, Kw) and head in _PRIMITIVES:
                return _PRIMITIVES[head], None  # [:string {...}] etc.
            if head in (":fn", ":function", ":=>"):
                return "any", "fn-valued"
            return "string", f"untranslated {head}"
        return "string", f"untranslated literal {form!r}"


def _map_entries(form):
    """[[key, optional?, schema], ...] from a [:map ...] form."""
    out = []
    for entry in form[1:]:
        if isinstance(entry, dict):
            continue  # map-level properties
        key = entry[0]
        props = entry[1] if len(entry) > 1 and isinstance(entry[1], dict) else {}
        schema = entry[-1] if len(entry) > 1 and not isinstance(entry[-1], dict) else None
        out.append((key, bool(props.get(":optional")), schema))
    return out


def fn_to_tool(fn_row, registry):
    """Translate one :seon.fn row -> (needle tool dict, gap notes)."""
    sym = fn_row["seon.fn/sym"]
    doc = (fn_row.get("seon.fn/doc") or "").split("\n")[0].strip()
    notes = []
    if not doc:
        notes.append("no-docstring")
    params, param_keys = {}, {}

    spec_str = fn_row.get("seon.fn/spec")
    if not spec_str:
        notes.append("no-spec")
        return {"name": sym, "description": doc, "parameters": {}}, notes

    try:
        spec = read_edn(spec_str)
    except (ValueError, RecursionError) as e:
        notes.append(f"spec unparseable: {e}")
        return {"name": sym, "description": doc, "parameters": {}}, notes
    arities = list(spec[1:]) if spec[0] == ":function" else [spec]
    if len(arities) > 1:
        notes.append(f"multi-arity({len(arities)}): first arity only")
    args = arities[0][1]  # [:cat ...] | [:catn ...] | bare :cat (nullary)

    def add_param(orig_key, schema, optional):
        name = to_snake_case(str(orig_key).lstrip(":").split("/")[-1])
        if name in param_keys:  # collision -> both fully qualified
            prev = param_keys.pop(name)
            params[to_snake_case(str(prev).lstrip(":"))] = params.pop(name)
            param_keys[to_snake_case(str(prev).lstrip(":"))] = prev
            name = to_snake_case(str(orig_key).lstrip(":"))
            notes.append(f"param-name collision on {name}")
        jtype, note = registry.json_type(schema if schema is not None else orig_key)
        if note and note.startswith(("untranslated", "unresolved", "malli :any")):
            notes.append(f"{orig_key}: {note}")
        desc = str(orig_key) + (f" — {note}" if note and note.startswith("one of") else "")
        p = {"type": jtype, "description": desc}
        if not optional:
            p["required"] = True
        params[name] = p
        param_keys[name] = orig_key

    if isinstance(args, Kw) and args in (":cat", ":catn"):
        pass  # nullary — malli prints an empty [:cat] as bare :cat
    elif args[0] == ":cat":
        items = [i for i in args[1:]]
        if len(items) == 1:
            resolved = registry.resolve(items[0])
            if isinstance(resolved, list) and resolved and resolved[0] == ":and":
                inner = [c for c in resolved[1:] if not isinstance(c, dict)]
                resolved = registry.resolve(inner[0]) if inner else resolved
            if isinstance(resolved, list) and resolved and resolved[0] == ":map":
                for key, optional, schema in _map_entries(resolved):
                    add_param(key, schema, optional)
            else:
                add_param(Kw(":request"), items[0], False)
                notes.append("opaque single arg (not a resolvable :map)")
        else:
            for i, item in enumerate(items):
                add_param(Kw(f":arg{i + 1}"), item, False)
            if items:
                notes.append("unnamed positional args")
    elif args[0] == ":catn":
        for slot in args[1:]:
            add_param(slot[0], slot[1], False)
    else:
        notes.append(f"unhandled arg form {args[0]}")

    return {"name": sym, "description": doc, "parameters": params}, notes


# ---------------------------------------------------------------------------
# Constrained greedy decode (MLX forward pass + reference trie decoder)
# ---------------------------------------------------------------------------

def build_encoder_input(tokenizer, query, tools, max_enc_len=DEFAULT_MAX_ENC_LEN):
    """run.py's layout: [query..., <tools>, tools...] truncated. Also returns
    how many tools tokens were CUT (envelope truncation is a finding)."""
    q_toks = tokenizer.encode(query)
    t_toks = tokenizer.encode(tools)
    if len(q_toks) > max_enc_len - 2:
        q_toks = q_toks[:max_enc_len - 2]
    remaining = max_enc_len - len(q_toks) - 1
    cut = max(0, len(t_toks) - remaining)
    return q_toks + [tokenizer.tools_token_id] + t_toks[:remaining], cut


def constrained_generate_batch(model, tokenizer, queries, tools_list,
                               max_gen_len=DEFAULT_MAX_GEN_LEN,
                               max_enc_len=DEFAULT_MAX_ENC_LEN):
    """Batch greedy decode with the reference trie constraints active.

    Matches generate.generate_batch's KV-cached loop; between logits and
    argmax each ACTIVE example's row detours through numpy for the trie
    mask (exactly run.py's flow). Returns dict with texts + tok/s."""
    B = len(queries)
    pad_id, eos_id = tokenizer.pad_token_id, tokenizer.eos_token_id

    enc_lists, cuts = [], []
    for q, t in zip(queries, tools_list):
        toks, cut = build_encoder_input(tokenizer, q, t, max_enc_len)
        enc_lists.append(toks)
        cuts.append(cut)
    max_enc = max(len(t) for t in enc_lists)
    enc = mx.full((B, max_enc), pad_id, dtype=mx.int32)
    for i, toks in enumerate(enc_lists):
        enc[i, :len(toks)] = mx.array(toks, dtype=mx.int32)

    t0 = time.perf_counter()
    src_mask = make_padding_mask(enc, pad_id)
    encoder_out, enc_mask = model.encode(enc, src_mask=src_mask)
    mx.eval(encoder_out)
    prefill_s = time.perf_counter() - t0
    prefill_tokens = sum(len(t) for t in enc_lists)

    decoder = build_constrained_decoder(tools_list, tokenizer)
    caches = model.new_caches()
    tokens = mx.full((B, 1), eos_id, dtype=mx.int32)
    finished = [False] * B
    gen_tokens = [[] for _ in range(B)]

    t0 = time.perf_counter()
    for pos in range(max_gen_len - 1):
        logits = model.decode(tokens, encoder_out, cross_mask=enc_mask,
                              offset=pos, caches=caches)
        last = logits[:, -1, :]
        argmaxes = [int(t) for t in mx.argmax(last, axis=-1)]
        np_rows = None
        step = []
        for i in range(B):
            if finished[i]:
                step.append(pad_id)
                continue
            if decoder.is_active(i):
                if np_rows is None:
                    np_rows = np.array(last, copy=False)
                row = decoder.constrain_logits(np_rows[i].astype(np.float32), i)
                t = int(np.argmax(row))
            else:
                t = argmaxes[i]
            decoder.update(i, t)
            if t == eos_id:
                finished[i] = True
                step.append(pad_id)
            else:
                gen_tokens[i].append(t)
                step.append(t)
        if all(finished):
            break
        tokens = mx.array(step, dtype=mx.int32)[:, None]
    decode_s = time.perf_counter() - t0
    decode_tokens = sum(len(g) for g in gen_tokens)

    texts = []
    for g in gen_tokens:
        text = tokenizer.decode(g)
        if text.startswith("<tool_call>"):
            text = text[len("<tool_call>"):]
        texts.append(text.strip())
    return {"texts": texts, "tools_cut_tokens": cuts,
            "prefill_tokens": prefill_tokens, "prefill_s": prefill_s,
            "decode_tokens": decode_tokens, "decode_s": decode_s,
            "decode_tok_s": decode_tokens / decode_s if decode_s > 0 else 0.0}


# ---------------------------------------------------------------------------
# Case bank + menus
# ---------------------------------------------------------------------------

def load_index():
    d = json.loads((PKG_ROOT / "data" / "fn-index.json").read_text())
    registry = Registry(d["schemas"])
    tools, gaps = {}, {}
    for row in d["fns"]:
        tool, notes = fn_to_tool(row, registry)
        tools[row["seon.fn/sym"]] = tool
        if notes:
            gaps[row["seon.fn/sym"]] = notes
    return d, tools, gaps


def load_cases():
    return json.loads((PKG_ROOT / "cases" / "kt2b_cases.json").read_text())["cases"]


def build_menu(case, tools, n_distractors, rng):
    """expected + n distractors (half same-namespace — the hard case — half
    random others), shuffled. Irrelevance cases: all-random menu."""
    pool = sorted(tools)
    expected = case.get("expected")
    if expected is None:
        return rng.sample(pool, n_distractors + 1)
    same_ns = [s for s in pool
               if s != expected and s.split("/")[0] == expected.split("/")[0]]
    others = [s for s in pool
              if s != expected and s.split("/")[0] != expected.split("/")[0]]
    n_same = min(len(same_ns), n_distractors // 2)
    menu = (rng.sample(same_ns, n_same)
            + rng.sample(others, n_distractors - n_same) + [expected])
    rng.shuffle(menu)
    return menu


def parse_calls(text):
    """Decoded text -> (calls list, parsed?). '[]' parses to ([], True)."""
    try:
        calls = json.loads(text)
    except (ValueError, TypeError):
        return [], False
    if isinstance(calls, dict):
        calls = [calls]
    if not isinstance(calls, list):
        return [], False
    return [c for c in calls if isinstance(c, dict)], True


# ---------------------------------------------------------------------------
# Scoring — needle's F1 methodology (finetune.py _quick_tool_eval, adapted:
# one expected call per case; irrelevance cases score abstention)
# ---------------------------------------------------------------------------

def score_arm(cases, records):
    n = t_n = parse_ok = name_correct = exact = 0
    name_tp = name_fp = name_fn = 0
    args_total = args_correct = argkey_total = argkey_correct = 0
    irr_n = irr_abstain = 0
    per_fn = {}
    for case, rec in zip(cases, records):
        n += 1
        calls, parsed = parse_calls(rec["text"])
        pred_names = [c.get("name") for c in calls if c.get("name")]
        expected = case.get("expected")
        if expected is None:
            irr_n += 1
            if parsed and not calls:
                irr_abstain += 1
            continue
        t_n += 1
        parse_ok += parsed
        slot = per_fn.setdefault(expected, {"n": 0, "correct": 0, "picks": Counter()})
        slot["n"] += 1
        name_tp += sum(1 for p in set(pred_names) if p == expected)
        name_fp += sum(1 for p in set(pred_names) if p != expected)
        name_fn += 0 if expected in pred_names else 1
        hit = pred_names == [expected]
        if hit:
            name_correct += 1
            slot["correct"] += 1
        slot["picks"].update(pred_names or ["<none>"])
        exp_args = case.get("expected_args")
        if exp_args is not None and hit:
            args_total += 1
            got = calls[0].get("arguments", {}) or {}
            if json.dumps(got, sort_keys=True) == json.dumps(exp_args, sort_keys=True):
                args_correct += 1
            for k, v in exp_args.items():
                argkey_total += 1
                if str(got.get(k)) == str(v):
                    argkey_correct += 1
    p, r = name_tp + name_fp, name_tp + name_fn
    return {
        "n_targeted": t_n, "n_irrelevance": irr_n,
        "parse_rate": round(parse_ok / max(t_n, 1), 4),
        "name_acc": round(name_correct / max(t_n, 1), 4),
        "name_f1": round(2 * name_tp / max(p + r, 1), 4),
        "args_exact": round(args_correct / max(args_total, 1), 4),
        "args_total": args_total,
        "argkey_acc": round(argkey_correct / max(argkey_total, 1), 4),
        "argkey_total": argkey_total,
        "abstain_rate": round(irr_abstain / max(irr_n, 1), 4),
        "false_suggestion_rate": round(1 - irr_abstain / max(irr_n, 1), 4) if irr_n else None,
        "per_fn": {k: {"n": v["n"], "correct": v["correct"],
                       "picks": dict(v["picks"].most_common(4))}
                   for k, v in sorted(per_fn.items())},
    }


# ---------------------------------------------------------------------------
# Arms
# ---------------------------------------------------------------------------

def run_seon_arm(model, tokenizer, cases, tools, n_distractors, batch=16,
                 max_enc_len=DEFAULT_MAX_ENC_LEN):
    records = []
    for start in range(0, len(cases), batch):
        chunk = cases[start:start + batch]
        queries, tools_norm, maps, menus = [], [], [], []
        for case in chunk:
            rng = random.Random(f"{case['id']}|{n_distractors}")
            menu = build_menu(case, tools, n_distractors, rng)
            tj = json.dumps([tools[s] for s in menu], separators=(",", ":"))
            tj, name_map = normalize_tools(tj)
            queries.append(case["query"])
            tools_norm.append(tj)
            maps.append(name_map)
            menus.append(menu)
        out = constrained_generate_batch(model, tokenizer, queries, tools_norm,
                                         max_enc_len=max_enc_len)
        for i, case in enumerate(chunk):
            calls, parsed = parse_calls(out["texts"][i])
            restore_names(calls, maps[i])
            records.append({
                "id": case["id"], "menu": menus[i],
                "tools_cut_tokens": out["tools_cut_tokens"][i],
                "text": json.dumps(calls, separators=(",", ":")) if parsed else out["texts"][i],
            })
        print(f"  [{start + len(chunk)}/{len(cases)}] decode {out['decode_tok_s']:.0f} tok/s")
    return records


def position_accuracy(cases, records):
    """Selection accuracy by the expected tool's POSITION tercile in the
    menu (early/middle/late) — the deep-menu lens the extension train
    must move. Computed from each record's stored menu."""
    by_case = {c["id"]: c for c in cases}
    buckets = {"early": [0, 0], "middle": [0, 0], "late": [0, 0]}
    for rec in records:
        case = by_case[rec["id"]]
        expected = case.get("expected")
        if expected is None or expected not in rec["menu"]:
            continue
        frac = rec["menu"].index(expected) / max(len(rec["menu"]) - 1, 1)
        b = "early" if frac < 1 / 3 else ("middle" if frac < 2 / 3 else "late")
        calls, parsed = parse_calls(rec["text"])
        hit = [c.get("name") for c in calls] == [expected]
        buckets[b][0] += hit
        buckets[b][1] += 1
    return {k: {"n": n, "acc": round(c / n, 4) if n else None}
            for k, (c, n) in buckets.items()}


def cmd_run(menu_sizes=(0, 7, 15), model=None, tokenizer=None,
            max_enc_len=DEFAULT_MAX_ENC_LEN, max_enc_by_size=None,
            out_name="seon_probe_results.json", tag="stock"):
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    _, tools, gaps = load_index()
    cases = load_cases()
    if model is None:
        model, tokenizer = load_model(), load_tokenizer()
    results = {"tag": tag, "gaps": gaps, "arms": {}}
    for nd in menu_sizes:
        arm_cases = [c for c in cases if not (nd == 0 and c.get("expected") is None)]
        label = f"menu{nd + 1}"
        arm_max_enc = (max_enc_by_size or {}).get(nd, max_enc_len)
        print(f"== arm {label}: {len(arm_cases)} cases, {nd} distractors, "
              f"max_enc {arm_max_enc}")
        records = run_seon_arm(model, tokenizer, arm_cases, tools, nd,
                               max_enc_len=arm_max_enc)
        scores = score_arm(arm_cases, records)
        cut = sum(1 for r in records if r["tools_cut_tokens"] > 0)
        scores["menus_truncated"] = cut
        scores["by_position"] = position_accuracy(arm_cases, records)
        results["arms"][label] = {"scores": scores, "records": records,
                                  "max_enc_len": arm_max_enc}
        print(f"  name_acc={scores['name_acc']} parse={scores['parse_rate']} "
              f"F1={scores['name_f1']} truncated={cut}/{len(records)} "
              f"pos={scores['by_position']}")
    path = OUT_DIR / out_name
    path.write_text(json.dumps(results, indent=1))
    print("wrote", path)


# ---------------------------------------------------------------------------
# BFCL calibration arm (the anchor: needle on its home benchmark)
# ---------------------------------------------------------------------------

def _bfcl_tool(fn):
    """BFCL function def -> needle home format (flat properties)."""
    params = fn.get("parameters", {})
    required = set(params.get("required", []))
    out = {}
    for pname, p in params.get("properties", {}).items():
        entry = {"type": p.get("type", "string"),
                 "description": p.get("description", "")[:120]}
        if pname in required:
            entry["required"] = True
        out[pname] = entry
    return {"name": fn["name"], "description": fn.get("description", "")[:160],
            "parameters": out}


def load_bfcl(n=100, seed=42):
    lines = (BFCL_DATA / "BFCL_v4_live_simple.json").read_text().splitlines()
    answers = {}
    for line in (BFCL_DATA / "possible_answer" / "BFCL_v4_live_simple.json").read_text().splitlines():
        row = json.loads(line)
        answers[row["id"]] = row["ground_truth"]
    rows = [json.loads(line) for line in lines]
    rng = random.Random(seed)
    picked = rng.sample(rows, n)
    all_fns = {f["name"]: f for row in rows for f in row["function"]}
    return picked, answers, all_fns


def score_bfcl(rows, answers, records):
    n = parse_ok = name_correct = 0
    args_full = args_full_ok = pkey_total = pkey_ok = 0
    for row, rec in zip(rows, records):
        n += 1
        calls, parsed = parse_calls(rec["text"])
        parse_ok += parsed
        gt = answers[row["id"]][0]
        gt_name = next(iter(gt))
        pred_names = [c.get("name") for c in calls]
        if pred_names == [gt_name]:
            name_correct += 1
            got = calls[0].get("arguments", {}) or {}
            allowed = gt[gt_name]
            full_ok = True
            for pname, vals in allowed.items():
                optional = "" in vals
                pkey_total += 1
                if pname in got:
                    ok = any(got[pname] == v or str(got[pname]) == str(v)
                             for v in vals if v != "")
                elif optional:
                    ok = True
                else:
                    ok = False
                pkey_ok += ok
                full_ok = full_ok and ok
            extras = set(got) - set(allowed)
            args_full += 1
            args_full_ok += full_ok and not extras
    return {"n": n, "parse_rate": round(parse_ok / max(n, 1), 4),
            "name_acc": round(name_correct / max(n, 1), 4),
            "args_full_acc": round(args_full_ok / max(args_full, 1), 4),
            "args_key_acc": round(pkey_ok / max(pkey_total, 1), 4)}


def cmd_calibrate(n=100, distractor_arms=(0, 7), model=None, tokenizer=None,
                  max_enc_len=DEFAULT_MAX_ENC_LEN,
                  out_name="bfcl_calibration.json", tag="stock"):
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    rows, answers, all_fns = load_bfcl(n=n)
    if model is None:
        model, tokenizer = load_model(), load_tokenizer()
    results = {"tag": tag}
    for nd in distractor_arms:
        queries, tools_norm, maps = [], [], []
        for row in rows:
            rng = random.Random(f"{row['id']}|{nd}")
            fns = [_bfcl_tool(f) for f in row["function"]]
            have = {f["name"] for f in row["function"]}
            distractors = rng.sample([v for k, v in sorted(all_fns.items())
                                      if k not in have], nd)
            fns += [_bfcl_tool(f) for f in distractors]
            rng.shuffle(fns)
            tj, name_map = normalize_tools(json.dumps(fns, separators=(",", ":")))
            queries.append(row["question"][0][-1]["content"])
            tools_norm.append(tj)
            maps.append(name_map)
        records = []
        for start in range(0, len(rows), 16):
            out = constrained_generate_batch(
                model, tokenizer, queries[start:start + 16],
                tools_norm[start:start + 16], max_enc_len=max_enc_len)
            for i in range(len(out["texts"])):
                calls, parsed = parse_calls(out["texts"][i])
                restore_names(calls, maps[start + i])
                records.append({"id": rows[start + i]["id"],
                                "text": json.dumps(calls, separators=(",", ":"))
                                if parsed else out["texts"][i]})
            print(f"  [{start + 16}/{len(rows)}] decode {out['decode_tok_s']:.0f} tok/s")
        label = f"menu{nd + 1}"
        results[label] = {"scores": score_bfcl(rows, answers, records),
                          "records": records}
        print(label, results[label]["scores"])
    path = OUT_DIR / out_name
    path.write_text(json.dumps(results, indent=1))
    print("wrote", path)


# ---------------------------------------------------------------------------
# Translation check
# ---------------------------------------------------------------------------

def cmd_translate():
    _, tools, gaps = load_index()
    tokenizer = load_tokenizer()
    sizes = []
    for sym, tool in sorted(tools.items()):
        tj = json.dumps([tool], separators=(",", ":"))
        sizes.append((len(tokenizer.encode(tj)), sym))
    sizes.sort(reverse=True)
    print(f"{len(tools)} tools; {len(gaps)} with translation notes")
    print("largest tool defs (tokens):")
    for n, sym in sizes[:12]:
        print(f"  {n:5d}  {sym}")
    med = sorted(n for n, _ in sizes)[len(sizes) // 2]
    print(f"median tool def: {med} tokens; "
          f"8-tool menu ≈ {med * 8} tokens of the {DEFAULT_MAX_ENC_LEN} envelope")
    for sym, notes in sorted(gaps.items()):
        print(f"  GAP {sym}: {notes}")
    print("\nexample translation (my.plan/step!):")
    print(json.dumps(tools.get("my.plan/step!"), indent=1))


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "translate"
    if cmd == "translate":
        cmd_translate()
    elif cmd == "calibrate":
        cmd_calibrate()
    elif cmd == "run":
        cmd_run()
    else:
        raise SystemExit(f"unknown command {cmd!r} (translate|calibrate|run)")


if __name__ == "__main__":
    main()

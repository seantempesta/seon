"""Surface-tuning compliance sweep — the KT2b follow-up ablation.

Tunes fn PRESENTATION at the translation layer ONLY — docstring line-1s,
name labels, schema->param projections, facade tools — as overrides applied
when the tool JSON is built. Seon source is untouched (zero context-freeze
conflict). Every variant is scored with the exact KT2b methodology: the same
169 cases (cases/kt2b_cases.json), the same per-case-id seeded menus, the
same scorer (lint_probe.score_arm), the stock needle checkpoint with
constrained decoding. One variant dimension at a time vs the KT2b baseline,
plus a cross-model compliance check on Qwen2.5-Coder-1.5B-Instruct
zero-shot (same menu JSONs through a chat prompt).

Menu identity across variants: menus are built from the ORIGINAL fn syms
with the KT2b rng seed (case id + n_distractors), then each sym renders
through the variant's card transform — so any score delta attributes to the
presentation, never to menu composition.

Run (from src-needle/):
  .venv/bin/python -m seon_needle.surface_sweep needle            # all arms
  .venv/bin/python -m seon_needle.surface_sweep needle --arms base,facade
  .venv/bin/python -m seon_needle.surface_sweep qwen              # cross-check
  .venv/bin/python -m seon_needle.surface_sweep report            # tables

Results land in data/kt2b/surface_sweep_{needle,qwen}.json (gitignored,
re-derivable); the research file quotes them. Sizes in TOKENS, always.
"""

import argparse
import json
import random
import re
import sys
import time

from .lint_probe import (OUT_DIR, build_menu, constrained_generate_batch,
                         load_cases, load_index, normalize_tools,
                         parse_calls, restore_names, score_arm)
from .model import load_model
from .tokenizer import load_tokenizer

# ---------------------------------------------------------------------------
# The fix-list fns (KT2b 0.00 tier, "What to do with the leaderboard")
# ---------------------------------------------------------------------------

FIX8 = [
    "seon.db/transact!", "seon.db/query", "seon.schema/register!",
    "my.plan/step!", "my.plan/done!", "my.plan/next",
    "my.blob/put!", "seon.db/entity",
]

# Docstring line-1 rewrites: capability vocabulary, <=72 chars (the KT2b fix
# list's shape — what the SOURCE fix would look like, probed as an override).
DOC_ACTION = {
    "seon.db/transact!": "Save records to the database — persist new facts durably.",
    "seon.db/query": "Ask the database a question: find, count, or sum stored facts.",
    "seon.schema/register!": "Define a new field so facts using it can be saved and queried.",
    "my.plan/step!": "Add a new step to the plan.",
    "my.plan/done!": "Record that a plan step is finished and complete.",
    "my.plan/next": "Get the next plan steps to work on.",
    "my.blob/put!": "Save a long text durably; read it back page by page later.",
    "seon.db/entity": "Fetch one stored record by its id, with all its fields.",
}

# Action-alias name-parts (ns kept — isolates the NAME dimension; docstrings
# and params stay the originals).
NAME_ALIAS = {
    "seon.db/transact!": "seon.db/save-records!",
    "seon.db/query": "seon.db/find-records",
    "seon.schema/register!": "seon.schema/define-field!",
    "my.plan/step!": "my.plan/add-step!",
    "my.plan/done!": "my.plan/finish-step!",
    "my.plan/next": "my.plan/next-steps",
    "my.blob/put!": "my.blob/save-content!",
    "seon.db/entity": "seon.db/get-record",
}

# Documented param projections for the fns whose malli spec does not project
# (KT2b: "opaque single arg" / multi-arity flatten-noise) — the SHAPE fix.
SCHEMA_PROJECT = {
    "seon.db/query": {
        "query": {"type": "array", "required": True,
                  "description": "Datalog query vector: [:find ?x :where [?e :attr ?x]]"},
        "args": {"type": "array", "description": "extra query inputs, if any"},
    },
    "seon.db/entity": {
        "id": {"type": "string", "required": True,
               "description": "entity id or [attribute value] lookup-ref"},
    },
    "seon.db/transact!": {
        "tx_data": {"type": "array", "required": True,
                    "description": "the records to save — a list of maps, one map per fact"},
    },
}

# English per-param descriptions on the EXISTING flat params (names/types
# unchanged — isolates the param-description dimension).
PARAM_ENGDESC = {
    "seon.db/transact!": {
        "tx_data": "the records to save — a list of maps, one map per fact",
        "opts": "transaction options", "conn": "database connection (optional)",
        "return_report": "return the full transaction report",
    },
    "seon.db/query": {"request": "the Datalog query vector, e.g. [:find ?e :where [?e :attr ?v]]"},
    "seon.schema/register!": {
        "registry_key": "name of the new field, e.g. :my.task/due-date",
        "form": "the field's type, e.g. :string, :inst, :int",
    },
    "my.plan/step!": {
        "title": "short name of the new step", "description": "what the step does",
        "expect": "the expected outcome", "id": "your agent id",
        "from": "id of the step this derives from", "parent": "id of the parent step",
        "needs": "ids of steps this one depends on",
    },
    "my.plan/done!": {"id": "the id of the finished step"},
    "my.plan/next": {"id": "your agent id (optional)"},
    "my.blob/put!": {"content": "the text to save", "media": "media type, e.g. text/plain"},
    "seon.db/entity": {"request": "the entity id or [attribute value] lookup-ref"},
}

# Facade tools: purpose-named wrappers (bare name + purpose docstring +
# simple params) fully replacing the raw card — the owner's "would different
# tools be better?" probe. Param names keep the case bank's expected_args
# keys (title, id) where they exist.
FACADE = {
    "seon.db/transact!": {
        "name": "remember",
        "description": "Save facts to memory so they can be recalled later.",
        "parameters": {"facts": {"type": "array", "required": True,
                                 "description": "the facts to save, one map per record"}}},
    "seon.db/query": {
        "name": "recall",
        "description": "Recall stored facts — find, count, or total what has been saved.",
        "parameters": {"about": {"type": "string", "required": True,
                                 "description": "what to look up"}}},
    "seon.schema/register!": {
        "name": "define_field",
        "description": "Define a new field for records before saving facts that use it.",
        "parameters": {"field_name": {"type": "string", "required": True,
                                      "description": "name of the new field"},
                       "field_type": {"type": "string", "required": True,
                                      "description": "one of: string, integer, float, boolean, timestamp"}}},
    "my.plan/step!": {
        "name": "add_step",
        "description": "Add a new step to the plan.",
        "parameters": {"title": {"type": "string", "required": True,
                                 "description": "short name of the new step"},
                       "parent": {"type": "string", "description": "parent step id"}}},
    "my.plan/done!": {
        "name": "finish_step",
        "description": "Mark a plan step finished.",
        "parameters": {"id": {"type": "string", "required": True,
                              "description": "the step id"}}},
    "my.plan/next": {
        "name": "next_step",
        "description": "Get the next plan step to work on.",
        "parameters": {}},
    "my.blob/put!": {
        "name": "save_file",
        "description": "Save a long text durably; read it back in pages later.",
        "parameters": {"content": {"type": "string", "required": True,
                                   "description": "the text to save"},
                       "media": {"type": "string", "description": "media type"}}},
    "seon.db/entity": {
        "name": "get_record",
        "description": "Fetch one stored record by its id.",
        "parameters": {"id": {"type": "string", "required": True,
                              "description": "the record's id"}}},
}


# ---------------------------------------------------------------------------
# Card transforms (each: (sym, cloned tool) -> tool)
# ---------------------------------------------------------------------------

def _clone(tool):
    return json.loads(json.dumps(tool))


def t_base(sym, tool):
    return tool


def t_doc_action(sym, tool):
    if sym in DOC_ACTION:
        tool["description"] = DOC_ACTION[sym]
    return tool


def t_doc_none8(sym, tool):
    if sym in FIX8:
        tool["description"] = ""
    return tool


def t_doc_none_all(sym, tool):
    tool["description"] = ""
    return tool


def t_name_alias(sym, tool):
    if sym in NAME_ALIAS:
        tool["name"] = NAME_ALIAS[sym]
    return tool


def make_ns_strip(all_syms):
    """Strip ns from every tool name; name-part collisions keep the full sym
    (computed, not a hand list)."""
    parts = {}
    for s in all_syms:
        parts.setdefault(s.split("/")[-1], []).append(s)
    colliders = {s for v in parts.values() if len(v) > 1 for s in v}

    def t(sym, tool):
        if sym not in colliders:
            tool["name"] = sym.split("/")[-1]
        return tool
    return t


def t_param_nodesc(sym, tool):
    for p in tool["parameters"].values():
        p["description"] = ""
    return tool


def t_param_engdesc(sym, tool):
    for pname, d in PARAM_ENGDESC.get(sym, {}).items():
        if pname in tool["parameters"]:
            tool["parameters"][pname]["description"] = d
    return tool


def t_param_reqonly(sym, tool):
    tool["parameters"] = {k: v for k, v in tool["parameters"].items()
                          if v.get("required")}
    return tool


def t_param_camel(sym, tool):
    def camel(name):
        head, *rest = name.split("_")
        return head + "".join(w.capitalize() for w in rest)
    tool["parameters"] = {camel(k): v for k, v in tool["parameters"].items()}
    return tool


def t_param_reqobj(sym, tool):
    """Invert the flat projection: one request-object param whose description
    lists the keys (the map-in shape shown as a single dict)."""
    params = tool["parameters"]
    if not params or list(params) == ["request"]:
        return tool
    keys = ", ".join(f"{k} ({v['type']}{', required' if v.get('required') else ''})"
                     for k, v in params.items())
    tool["parameters"] = {"request": {"type": "dict", "required": True,
                                      "description": "map with keys: " + keys}}
    return tool


def t_schema_project(sym, tool):
    if sym in SCHEMA_PROJECT:
        tool["parameters"] = _clone(SCHEMA_PROJECT[sym])
    return tool


def t_facade(sym, tool):
    if sym in FACADE:
        return _clone(FACADE[sym])
    return tool


def t_stack(sym, tool):
    """The combined translation-layer candidate: projected schemas + English
    param descriptions + action docstrings + action-alias names."""
    tool = t_schema_project(sym, tool)
    tool = t_param_engdesc(sym, tool)
    tool = t_doc_action(sym, tool)
    tool = t_name_alias(sym, tool)
    return tool


def t_compact(sym, tool):
    """Compact card: name + docstring line-1 + required-only params, no
    per-param descriptions (the menu16 envelope candidate)."""
    tool["parameters"] = {k: {"type": v["type"], "description": "",
                              **({"required": True} if v.get("required") else {})}
                          for k, v in tool["parameters"].items() if v.get("required")}
    return tool


VARIANTS = {
    # dimension 1 — docstring line-1
    "base": {"transform": t_base, "nd": 7},
    "doc-action": {"transform": t_doc_action, "nd": 7},
    "doc-none8": {"transform": t_doc_none8, "nd": 7},
    "doc-none-all": {"transform": t_doc_none_all, "nd": 7},
    # dimension 2 — the name label
    "name-alias": {"transform": t_name_alias, "nd": 7},
    "ns-strip": {"transform": "NS_STRIP", "nd": 7},  # built per-index
    # dimension 3 — schema shape
    "param-nodesc": {"transform": t_param_nodesc, "nd": 7},
    "param-engdesc": {"transform": t_param_engdesc, "nd": 7},
    "param-reqonly": {"transform": t_param_reqonly, "nd": 7},
    "param-camel": {"transform": t_param_camel, "nd": 7},
    "param-reqobj": {"transform": t_param_reqobj, "nd": 7},
    "schema-project": {"transform": t_schema_project, "nd": 7},
    # dimension 4 — facade tools
    "facade": {"transform": t_facade, "nd": 7},
    # combined translation-layer candidate
    "stack": {"transform": t_stack, "nd": 7},
    # dimension 5 — menu composition (compact cards)
    "menu8-compact": {"transform": t_compact, "nd": 7},
    "menu16-compact": {"transform": t_compact, "nd": 15},
}


def build_variant_tools(label, base_tools):
    transform = VARIANTS[label]["transform"]
    if transform == "NS_STRIP":
        transform = make_ns_strip(sorted(base_tools))
    vtools = {sym: transform(sym, _clone(t)) for sym, t in base_tools.items()}
    alias = {v["name"]: sym for sym, v in vtools.items() if v["name"] != sym}
    return vtools, alias


def variant_menus(cases, base_tools, nd):
    """The KT2b menus, byte-identical: seeded on case id + n_distractors over
    the ORIGINAL sym pool."""
    menus = []
    for case in cases:
        rng = random.Random(f"{case['id']}|{nd}")
        menus.append(build_menu(case, base_tools, nd, rng))
    return menus


# ---------------------------------------------------------------------------
# Needle arms
# ---------------------------------------------------------------------------

def run_needle_variant(model, tokenizer, cases, base_tools, label, batch=16):
    nd = VARIANTS[label]["nd"]
    vtools, alias = build_variant_tools(label, base_tools)
    menus = variant_menus(cases, base_tools, nd)
    records = []
    for start in range(0, len(cases), batch):
        chunk = cases[start:start + batch]
        queries, tools_norm, maps = [], [], []
        for i, case in enumerate(chunk):
            tj = json.dumps([vtools[s] for s in menus[start + i]],
                            separators=(",", ":"))
            tj, name_map = normalize_tools(tj)
            queries.append(case["query"])
            tools_norm.append(tj)
            maps.append(name_map)
        out = constrained_generate_batch(model, tokenizer, queries, tools_norm)
        for i, case in enumerate(chunk):
            calls, parsed = parse_calls(out["texts"][i])
            restore_names(calls, maps[i])          # snake -> variant name
            for c in calls:                        # variant name -> sym
                c["name"] = alias.get(c.get("name"), c.get("name"))
            records.append({
                "id": case["id"], "menu": menus[start + i],
                "tools_cut_tokens": out["tools_cut_tokens"][i],
                "text": json.dumps(calls, separators=(",", ":")) if parsed
                        else out["texts"][i],
            })
        print(f"  [{start + len(chunk)}/{len(cases)}] "
              f"decode {out['decode_tok_s']:.0f} tok/s")
    return records


def cmd_needle(arms):
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    _, base_tools, _ = load_index()
    cases = load_cases()
    model, tokenizer = load_model(), load_tokenizer()
    path = OUT_DIR / "surface_sweep_needle.json"
    results = json.loads(path.read_text()) if path.exists() else {"arms": {}}
    for label in arms:
        t0 = time.perf_counter()
        print(f"== needle arm {label} (nd={VARIANTS[label]['nd']})")
        records = run_needle_variant(model, tokenizer, cases, base_tools, label)
        scores = score_arm(cases, records)
        scores["menus_truncated"] = sum(1 for r in records
                                        if r["tools_cut_tokens"] > 0)
        results["arms"][label] = {"scores": scores, "records": records,
                                  "wall_s": round(time.perf_counter() - t0, 1)}
        print(f"  name_acc={scores['name_acc']} parse={scores['parse_rate']} "
              f"F1={scores['name_f1']} truncated={scores['menus_truncated']}"
              f"/{len(records)} wall={results['arms'][label]['wall_s']}s")
        path.write_text(json.dumps(results, indent=1))
    print("wrote", path)


# ---------------------------------------------------------------------------
# Qwen cross-check (zero-shot chat, same menu JSONs)
# ---------------------------------------------------------------------------

QWEN_MODEL = "mlx-community/Qwen2.5-Coder-1.5B-Instruct-4bit"
QWEN_SYSTEM = (
    "You are a function-calling assistant. You are given a list of available "
    "tools as JSON and a user request. Respond with ONLY a JSON array of tool "
    'calls, e.g. [{"name": "tool_name", "arguments": {"param": "value"}}]. '
    "Use exactly one tool call unless none applies. If no tool applies, "
    "respond with exactly [].")


def qwen_extract(text):
    """First balanced JSON array/object in the reply (fence/think-stripped)."""
    text = re.sub(r"<think>.*?</think>", "", text, flags=re.S)
    m = re.search(r"```(?:json)?\s*(.*?)```", text, re.S)
    if m:
        text = m.group(1)
    text = text.strip()
    dec = json.JSONDecoder()
    for i, ch in enumerate(text):
        if ch in "[{":
            try:
                obj, _ = dec.raw_decode(text[i:])
                return json.dumps(obj, separators=(",", ":"))
            except ValueError:
                continue
    return text


def cmd_qwen(arms, model_path=QWEN_MODEL, max_tokens=320):
    from mlx_lm import load, stream_generate
    from mlx_lm.sample_utils import make_sampler
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    _, base_tools, _ = load_index()
    cases = load_cases()
    model, tokenizer = load(model_path)
    sampler = make_sampler(temp=0.0)
    path = OUT_DIR / "surface_sweep_qwen.json"
    results = json.loads(path.read_text()) if path.exists() else {
        "model": model_path, "arms": {}}
    for label in arms:
        nd = VARIANTS[label]["nd"]
        vtools, alias = build_variant_tools(label, base_tools)
        menus = variant_menus(cases, base_tools, nd)
        print(f"== qwen arm {label} (nd={nd})")
        t0 = time.perf_counter()
        records = []
        for i, case in enumerate(cases):
            tj = json.dumps([vtools[s] for s in menus[i]], separators=(",", ":"))
            tj, name_map = normalize_tools(tj)
            messages = [
                {"role": "system", "content": QWEN_SYSTEM},
                {"role": "user",
                 "content": f"Tools:\n{tj}\n\nUser request: {case['query']}"},
            ]
            prompt = tokenizer.apply_chat_template(
                messages, add_generation_prompt=True, tokenize=False)
            reply = "".join(
                r.text for r in stream_generate(model, tokenizer, prompt,
                                                max_tokens=max_tokens,
                                                sampler=sampler))
            calls, parsed = parse_calls(qwen_extract(reply))
            restore_names(calls, name_map)
            for c in calls:
                c["name"] = alias.get(c.get("name"), c.get("name"))
            records.append({
                "id": case["id"], "menu": menus[i],
                "text": json.dumps(calls, separators=(",", ":")) if parsed
                        else reply[:400],
            })
            if (i + 1) % 20 == 0:
                print(f"  [{i + 1}/{len(cases)}]")
        scores = score_arm(cases, records)
        results["arms"][label] = {"scores": scores, "records": records,
                                  "wall_s": round(time.perf_counter() - t0, 1)}
        print(f"  name_acc={scores['name_acc']} parse={scores['parse_rate']} "
              f"F1={scores['name_f1']} wall={results['arms'][label]['wall_s']}s")
        path.write_text(json.dumps(results, indent=1))
    print("wrote", path)


# ---------------------------------------------------------------------------
# Report tables
# ---------------------------------------------------------------------------

def cmd_report():
    for fname in ("surface_sweep_needle.json", "surface_sweep_qwen.json"):
        path = OUT_DIR / fname
        if not path.exists():
            continue
        results = json.loads(path.read_text())
        print(f"\n### {fname}")
        base_acc = results["arms"].get("base", {}).get("scores", {}).get("name_acc")
        print(f"{'arm':16s} {'name_acc':>8s} {'Δbase':>7s} {'parse':>6s} "
              f"{'F1':>6s} {'argkey':>7s} {'false-sug':>9s} {'trunc':>5s}")
        for label in VARIANTS:
            arm = results["arms"].get(label)
            if not arm:
                continue
            s = arm["scores"]
            delta = (f"{s['name_acc'] - base_acc:+.3f}"
                     if base_acc is not None else "—")
            print(f"{label:16s} {s['name_acc']:8.3f} {delta:>7s} "
                  f"{s['parse_rate']:6.3f} {s['name_f1']:6.3f} "
                  f"{s['argkey_acc']:7.3f} "
                  f"{str(s.get('false_suggestion_rate')):>9s} "
                  f"{str(s.get('menus_truncated', '—')):>5s}")
        print("\nper-fn correct/n (fix-list fns):")
        labels = [l for l in VARIANTS if l in results["arms"]]
        print(f"{'fn':26s} " + " ".join(f"{l[:12]:>12s}" for l in labels))
        for fn in FIX8:
            row = []
            for l in labels:
                pf = results["arms"][l]["scores"]["per_fn"].get(fn, {})
                row.append(f"{pf.get('correct', 0)}/{pf.get('n', 0)}")
            print(f"{fn:26s} " + " ".join(f"{c:>12s}" for c in row))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["needle", "qwen", "report"])
    ap.add_argument("--arms", default=None,
                    help="comma-separated variant labels (default: all for "
                         "needle; base,doc-action,name-alias,facade,stack for qwen)")
    ap.add_argument("--model", default=QWEN_MODEL)
    args = ap.parse_args()
    if args.cmd == "report":
        cmd_report()
        return
    if args.arms:
        arms = args.arms.split(",")
        unknown = [a for a in arms if a not in VARIANTS]
        if unknown:
            raise SystemExit(f"unknown arms {unknown}; have {list(VARIANTS)}")
    elif args.cmd == "needle":
        arms = list(VARIANTS)
    else:
        arms = ["base", "doc-action", "name-alias", "facade", "stack"]
    if args.cmd == "needle":
        cmd_needle(arms)
    else:
        cmd_qwen(arms, model_path=args.model)


if __name__ == "__main__":
    main()

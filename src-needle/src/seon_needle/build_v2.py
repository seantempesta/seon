"""Build the v2 dataset variant from the A1 export (extended-context prep).

Transforms data/tune/acme-2026-07-12.jsonl (contexts are FROZEN — this
reads the exported file, it never re-renders) into
data/tune/acme-2026-07-12-v2.jsonl:

- cards       -> COMPACT cards (kt1_envelope.compact_card: name +
                 docstring line-1 + arglist, the {:malli/schema ...} map
                 stripped)
- target      -> the FIRST clean form of the turn (KT3's next-form
                 granularity finding: whole-turn bundles are the wrong
                 target shape)
- target_substantive -> the first clean NON-ns-move form (KT3's
                 substantive lens; absent when the turn is ns-moves only)
- target_bundle -> the cleaned whole-turn bundle (kept for the
                 bundle-vs-next-form ablation)
- json_tools  -> the row's cards translated to needle-home tool defs
                 (KT2b's fn_to_tool + normalize_tools: snake_case names,
                 name/description/parameters), array in card order; the
                 encoder tools slot is json.dumps(row["json_tools"],
                 separators=(",", ":"))
- json_target -> the next-form target translated to needle-home
                 [{"name", "arguments"}] (owner 2026-07-12: the needle arm
                 trains JSON-NATIVE); null when the next form is not a
                 tool call (def-forms/ns-moves/control/interop — the
                 coder-arm's exclusive food)
- meta        -> v1 meta + v1_row/bundle_forms/json_status (+
                 dropped_forms / json_gaps / json_none_reason when apt)

Junk filter (scripts/split_forms.clj — a real reader, bb/edamame, same
lineage as KT3's scorer): rows that fail to read are dropped
(parses-clean); prose-forms (a list of >=2 bare all-alphabetic symbols,
e.g. KT3 row 179's `(which is incorrect)`) are dropped per-form. Both
rules are computed and structural — no name lists.

JSON translation reuses KT2b's layer verbatim (lint_probe: fn_to_tool,
Registry over the dumped :seon.schema registry, normalize_tools, needle's
own to_snake_case). Cards matching the fn-index (name + doc line-1)
translate through their dumped fully-qualified spec; agent-defined cards
translate card-locally from the card's own {:malli/schema ...} slice,
with `::alias` keywords resolved against the registry when the name part
is UNIQUE there (computed rule). Argument keys use KT2b's param rule
(last segment, snake_case, collision -> fully qualified); values that
JSON cannot carry (quoted forms, symbols) become their byte-exact EDN
source string and mark the row json_status "partial".

Provenance lands in the sidecar data/tune/acme-2026-07-12-v2.meta.json
(source sha256, transform description, counts, translatability stats).

Run (from src-needle/):
  .venv/bin/python -m seon_needle.build_v2 [v1.jsonl] [v2.jsonl]
"""

import datetime
import hashlib
import json
import subprocess
import sys
from collections import Counter

from . import config
from .kt1_envelope import compact_card
from .lint_probe import Kw, Registry, fn_to_tool, normalize_tools, to_snake_case

REPO_ROOT = config.repo_root()
SPLITTER = config.package_root() / "scripts" / "split_forms.clj"
FN_INDEX = config.package_root() / "data" / "fn-index.json"
DEFAULT_V1 = REPO_ROOT / "data" / "tune" / "acme-2026-07-12.jsonl"
DEFAULT_V2 = REPO_ROOT / "data" / "tune" / "acme-2026-07-12-v2.jsonl"


def run_splitter(targets, cards):
    """One bb/edamame pass over all targets + distinct cards."""
    proc = subprocess.run(["bb", str(SPLITTER)],
                          input=json.dumps({"targets": targets, "cards": cards}),
                          capture_output=True, text=True, check=True)
    return json.loads(proc.stdout)


# ---------------------------------------------------------------------------
# Cards -> needle tool defs (KT2b path)
# ---------------------------------------------------------------------------

class AliasRegistry(Registry):
    """KT2b's Registry + `::alias` resolution by UNIQUE name part.

    Card-local specs carry `::request`-style keywords whose namespace only
    the defining ns knows; when exactly ONE registered key has that name
    part, the reference is unambiguous — a computed rule, no name list."""

    def __init__(self, raw):
        super().__init__(raw)
        by_name = {}
        for k in raw:
            by_name.setdefault(k.split("/")[-1], []).append(k)
        self._unique = {n: ks[0] for n, ks in by_name.items() if len(ks) == 1}
        self.alias_hits = 0

    def _dealias(self, form):
        if isinstance(form, Kw) and form.startswith("::"):
            hit = self._unique.get(str(form)[2:])
            if hit:
                self.alias_hits += 1
                return Kw(hit)
        return form

    def deref(self, kw):
        return super().deref(self._dealias(kw))

    def resolve(self, form, seen=None):
        return super().resolve(self._dealias(form), seen)


def match_index_row(name, doc, index_by_name):
    """fn-index row for a card, by name part + doc line-1 disambiguation."""
    rows = index_by_name.get(name, [])
    if len(rows) == 1:
        return rows[0]
    for r in rows:
        d = (r.get("seon.fn/doc") or "").split("\n")[0].strip()
        if d == (doc or "").strip():
            return r
    return None


def card_tools(distinct_cards, parsed_cards):
    """{card string -> (tool dict, orig sym, notes)} + translation stats."""
    index = json.loads(FN_INDEX.read_text())
    registry = AliasRegistry(index["schemas"])
    index_by_name = {}
    for row in index["fns"]:
        index_by_name.setdefault(row["seon.fn/sym"].split("/")[-1], []).append(row)

    tools, matched, local, failed = {}, 0, 0, 0
    for card, parsed in zip(distinct_cards, parsed_cards):
        if "error" in parsed:
            failed += 1
            tools[card] = ({"name": "unparseable", "description": "",
                            "parameters": {}}, "unparseable", ["card unparseable"])
            continue
        row = match_index_row(parsed["name"], parsed.get("doc"), index_by_name)
        if row is not None:
            matched += 1
        else:
            local += 1
            row = {"seon.fn/sym": parsed["name"],
                   "seon.fn/doc": parsed.get("doc") or "",
                   "seon.fn/spec": parsed.get("spec")}
        tool, notes = fn_to_tool(row, registry)
        tools[card] = (tool, row["seon.fn/sym"], notes)
    stats = {"distinct_cards": len(distinct_cards),
             "index_matched": matched, "card_local": local,
             "unparseable": failed, "alias_hits": registry.alias_hits,
             "tools_with_notes": sum(1 for t in tools.values() if t[2])}
    return tools, stats


# ---------------------------------------------------------------------------
# Target call -> needle-home JSON call
# ---------------------------------------------------------------------------

def head_matches(head, sym):
    """KT3's symbol rule: names equal, ns-suffixes equal or either absent."""
    if head.split("/")[-1] != sym.split("/")[-1]:
        return False
    hns = head.split("/")[0].split(".")[-1] if "/" in head else None
    sns = sym.split("/")[0].split(".")[-1] if "/" in sym else None
    return hns is None or sns is None or hns == sns


def deep_value(v, gaps):
    """bb-translated value -> JSON; {"edn": ...} tags become their source
    string and mark a gap (the row goes json_status partial)."""
    if isinstance(v, dict):
        if set(v) == {"edn"}:
            gaps.append("edn-fallback")
            return v["edn"]
        return {k: deep_value(x, gaps) for k, x in v.items()}
    if isinstance(v, list):
        return [deep_value(x, gaps) for x in v]
    return v


def map_arg_keys(keys, gaps):
    """{orig ':kw' -> snake param key}: KT2b's rule (last segment,
    snake_case; collision -> both fully qualified)."""
    shorts = {}
    for k in keys:
        shorts.setdefault(to_snake_case(k.lstrip(":").split("/")[-1]), []).append(k)
    out = {}
    for short, ks in shorts.items():
        if len(ks) == 1:
            out[ks[0]] = short
        else:
            gaps.append(f"param-name collision on {short}")
            for k in ks:
                out[k] = to_snake_case(k.lstrip(":"))
    return out


def translate_call(call, row_tools, gaps):
    """bb call shape + the row's (tool, orig sym) list -> needle-home
    {"name", "arguments"}."""
    head, args = call["head"], call["args"]
    tool = next((t for t, sym, _ in row_tools if head_matches(head, sym)), None)
    if tool is not None:
        name = tool["name"]  # the tools-slot snake name — the trainable join
    else:
        gaps.append(f"head not in the row's tools: {head}")
        name = to_snake_case(head)

    if not args:
        arguments = {}
    elif len(args) == 1 and isinstance(args[0], dict) and set(args[0]) != {"edn"}:
        keymap = map_arg_keys(list(args[0]), gaps)
        arguments = {keymap[k]: deep_value(v, gaps) for k, v in args[0].items()}
    else:
        # positional args ride the tool's declared param order when known
        params = list(tool["parameters"]) if tool else []
        if len(params) >= len(args):
            arguments = {params[i]: deep_value(v, gaps)
                         for i, v in enumerate(args)}
        else:
            gaps.append("unnamed positional args")
            arguments = {f"arg{i + 1}": deep_value(v, gaps)
                         for i, v in enumerate(args)}
    return {"name": name, "arguments": arguments}


# ---------------------------------------------------------------------------
# Rows
# ---------------------------------------------------------------------------

def build_rows(v1_rows, splits, tools_by_card):
    """v2 rows + accounting. Pure over the inputs."""
    v2_rows, dropped_rows = [], []
    dropped_forms = join_mismatch = 0
    json_status = Counter()
    none_reasons = Counter()
    form_shapes = Counter()

    for i, (row, split) in enumerate(zip(v1_rows, splits)):
        if not split["parsed"]:
            dropped_rows.append({"v1_row": i, "reason": "target does not read",
                                 "error": split.get("error")})
            continue
        junk = [f for f in split["forms"] if f["prose"] or f.get("no-loc")]
        kept = [f for f in split["forms"] if not (f["prose"] or f.get("no-loc"))]
        if not kept:
            dropped_rows.append({"v1_row": i, "reason": "all forms junk"})
            continue
        dropped_forms += len(junk)
        for f in kept:  # bundle-level call-shape census (KT3's ~93% check)
            form_shapes["call" if f["call"] else f["call-reason"]] += 1
        bundle = "\n".join(f["text"] for f in kept)
        if not junk and bundle != row["target"]:
            join_mismatch += 1  # byte-fidelity self-check (expected 0)

        row_tools = [tools_by_card[c] for c in row["cards"]]
        tools_json, name_map = normalize_tools(json.dumps(
            [t for t, _, _ in row_tools], separators=(",", ":")))
        json_tools = json.loads(tools_json)
        row_tools = [(t, sym, notes) for t, (_, sym, notes)
                     in zip(json_tools, row_tools)]

        meta = dict(row["meta"])
        meta["v1_row"] = i
        meta["bundle_forms"] = len(kept)
        if junk:
            meta["dropped_forms"] = [f["text"] for f in junk]

        first = kept[0]
        if first["call"] is None:
            json_target = None
            meta["json_status"] = "none"
            meta["json_none_reason"] = first["call-reason"]
            json_status["none"] += 1
            none_reasons[first["call-reason"]] += 1
        else:
            gaps = []
            json_target = [translate_call(first["call"], row_tools, gaps)]
            meta["json_status"] = "partial" if gaps else "full"
            if gaps:
                meta["json_gaps"] = gaps
            json_status[meta["json_status"]] += 1

        out = {
            "context": row["context"],
            "cards": [compact_card(c) for c in row["cards"]],
            "target": first["text"],
            "target_bundle": bundle,
            "json_tools": json_tools,
            "json_target": json_target,
            "meta": meta,
        }
        substantive = next((f["text"] for f in kept if not f["ns-move"]), None)
        if substantive is not None:  # optional = absent, never null
            out["target_substantive"] = substantive
        v2_rows.append(out)

    accounting = {
        "dropped_rows": dropped_rows,
        "forms_dropped": dropped_forms,
        "join_fidelity_mismatches": join_mismatch,
        "json_target_status": dict(json_status),
        "json_none_reasons": dict(none_reasons),
        "bundle_form_shapes": dict(form_shapes),
    }
    return v2_rows, accounting


def main():
    v1_path = sys.argv[1] if len(sys.argv) > 1 else str(DEFAULT_V1)
    v2_path = sys.argv[2] if len(sys.argv) > 2 else str(DEFAULT_V2)

    src_bytes = open(v1_path, "rb").read()
    v1_rows = [json.loads(l) for l in src_bytes.decode().splitlines() if l.strip()]

    distinct_cards = []
    seen = set()
    for r in v1_rows:
        for c in r["cards"]:
            if c not in seen:
                seen.add(c)
                distinct_cards.append(c)

    split = run_splitter([r["target"] for r in v1_rows], distinct_cards)
    tools_by_card, tool_stats = card_tools(distinct_cards, split["cards"])
    v2_rows, accounting = build_rows(v1_rows, split["targets"], tools_by_card)

    with open(v2_path, "w") as f:
        for r in v2_rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")

    sidecar = {
        "source": str(DEFAULT_V1.relative_to(REPO_ROOT)) if v1_path == str(DEFAULT_V1) else v1_path,
        "source_sha256": hashlib.sha256(src_bytes).hexdigest(),
        "generated": datetime.date.today().isoformat(),
        "generator": "src-needle/src/seon_needle/build_v2.py"
                     " + src-needle/scripts/split_forms.clj",
        "transform": [
            "cards: KT1 compaction (name + docstring line-1 + arglist;"
            " {:malli/schema ...} stripped) — kt1_envelope.compact_card",
            "target: FIRST clean form (next-form granularity, KT3);"
            " target_substantive: first clean non-ns-move form (absent when"
            " none); target_bundle: cleaned whole-turn bundle (ablation)",
            "junk filter: parses-clean via bb/edamame (rows) + prose-form"
            " rule (a list of >=2 bare all-alphabetic symbols) per-form",
            "json_tools: cards -> needle tool defs via KT2b's fn_to_tool +"
            " normalize_tools (snake names); encoder tools slot ="
            " json.dumps(row['json_tools'], separators=(',', ':'))",
            "json_target: next-form target -> needle-home"
            " [{name, arguments}]; name joined to the row's tools-slot"
            " snake name (KT3 symbol rule); arg keys last-segment"
            " snake_case (collision -> qualified); keyword values keep"
            " their ':kw' string; non-JSON values (quoted forms, symbols)"
            " -> byte-exact EDN source string + json_status partial; null"
            " when the next form is a def-form/ns-move/control/interop",
            "contexts: UNCHANGED (context generation frozen; no re-render)",
        ],
        "rows_in": len(v1_rows),
        "rows_out": len(v2_rows),
        "card_translation": tool_stats,
        **accounting,
    }
    sidecar_path = v2_path.replace(".jsonl", ".meta.json")
    with open(sidecar_path, "w") as f:
        json.dump(sidecar, f, indent=1, ensure_ascii=False)

    n = len(v2_rows)
    js = accounting["json_target_status"]
    print(f"v1 rows {len(v1_rows)} -> v2 rows {n} "
          f"(rows dropped {len(accounting['dropped_rows'])}, "
          f"junk forms dropped {accounting['forms_dropped']}, "
          f"join mismatches {accounting['join_fidelity_mismatches']})")
    print(f"json_target: full {js.get('full', 0)}/{n} "
          f"({js.get('full', 0) / n:.1%}), partial {js.get('partial', 0)} "
          f"({js.get('partial', 0) / n:.1%}), none {js.get('none', 0)} "
          f"({js.get('none', 0) / n:.1%}) — reasons {accounting['json_none_reasons']}")
    print(f"bundle form shapes: {accounting['bundle_form_shapes']}")
    print(f"card translation: {tool_stats}")
    print("wrote", v2_path)
    print("wrote", sidecar_path)


if __name__ == "__main__":
    main()

"""Build the v3 dataset — display v3 cards + enriched needle tool defs.

Input is the RAW display-v3 export written by `seon.repl.autocomplete/export!`
(data/tune/acme-2026-07-12-v3-raw.jsonl) — contexts rendered with the
`:seon.agent.ctx/ascii?` decoration-strip dial ON, cards on compact-fn-head's
SPEC FACE (registered shape refs expanded, body-elision marker dropped,
decorations stripped), stale/DEPRECATED card filters applied, and the 3
junk-target turns rated `:excluded` in the store
(research/scorer-false-negative-audit-2026-07-12.md rows 31/184/179).

This builder adds ONE column and passes everything else through byte-exact:

- json_tools -> the row's cards translated to needle-home tool defs via
               KT2b's fn_to_tool with the display-v3 ENRICHED param
               descriptions (lint_probe.describe_schema: the registered key
               schema's type/constraints — enum values, element types,
               min/max — never the bare keyword, sweep-proven dead weight);
               snake_case names via normalize_tools; array in card order.
               The encoder tools slot is
               json.dumps(row["json_tools"], separators=(",", ":")).

Rows are LOCKED to the frozen v1 held-out id set (minus the 3 excluded):
the store keeps accumulating turns, but v3 is the SAME held-out set —
newer turns are reported in the sidecar, never silently added.

There is NO card compaction here (display v3 owner ruling: cards MUST carry
their `:malli/schema` contract — the v2-era `compact_card` schema-stripping
is retired; git holds build_v2.py). No target transforms either: v3 keeps
the v1 whole-turn targets (held-out continuity — same turn ids, same
targets; only the DISPLAY changed). Next-form/json_target splits are the
training build's job, re-derivable from this file.

Provenance lands in the sidecar data/tune/acme-2026-07-12-v3.meta.json.

Run (from src-needle/):
  .venv/bin/python -m seon_needle.build_v3 [v3-raw.jsonl] [v3.jsonl]
"""

import datetime
import hashlib
import json
import subprocess
import sys

from . import config
from .lint_probe import Kw, Registry, fn_to_tool, normalize_tools

REPO_ROOT = config.repo_root()
SPLITTER = config.package_root() / "scripts" / "split_forms.clj"
FN_INDEX = config.package_root() / "data" / "fn-index.json"
DEFAULT_RAW = REPO_ROOT / "data" / "tune" / "acme-2026-07-12-v3-raw.jsonl"
DEFAULT_V3 = REPO_ROOT / "data" / "tune" / "acme-2026-07-12-v3.jsonl"
# The FROZEN held-out id set: the v1 export's turn ids. The store keeps
# accumulating turns (the exporter walks everything), so rows outside the
# v1 id set are locked OUT of v3 and reported in the sidecar.
V1_LOCK = REPO_ROOT / "data" / "tune" / "acme-2026-07-12.jsonl"

# The 3 junk-target turns (prose parsed as calls) rated :excluded in the
# store per the scorer FN audit — documented here so the sidecar carries
# the held-out-continuity delta (214 v1 rows -> 211 v3 rows).
EXCLUDED_TURNS = {
    "Ciw-2607112358": "v1 row 31 — pure junk target (root azm-..., 3 steps, ...)",
    "ANC-2607120041": "v1 row 179 — junk lead form (which is incorrect) heading real forms",
    "qaw-2607112254": "v1 row 184 — pure junk target (The plan has been laid down)",
}


def run_splitter(cards):
    """One bb/edamame pass over the distinct cards (name/doc/spec parse)."""
    proc = subprocess.run(["bb", str(SPLITTER)],
                          input=json.dumps({"targets": [], "cards": cards}),
                          capture_output=True, text=True, check=True)
    return json.loads(proc.stdout)["cards"]


# ---------------------------------------------------------------------------
# Cards -> needle tool defs (KT2b path, enriched params)
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
    """{card string -> (tool dict, orig sym, notes)} + translation stats.

    Cards matching the fn-index (name part + doc line-1) translate through
    their dumped fully-qualified spec; agent-defined cards translate
    card-locally from the card's own {:malli/schema ...} slice. NOTE: v3
    cards carry EXPANDED specs, but index-matched cards (the vast
    majority) translate from the index row's spec — expansion never
    enters the tool translation for them."""
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


def main():
    raw_path = sys.argv[1] if len(sys.argv) > 1 else str(DEFAULT_RAW)
    v3_path = sys.argv[2] if len(sys.argv) > 2 else str(DEFAULT_V3)

    src_bytes = open(raw_path, "rb").read()
    rows = [json.loads(l) for l in src_bytes.decode().splitlines() if l.strip()]

    # Held-out lock: only turns present in the frozen v1 export pass.
    lock_ids = {json.loads(l)["meta"]["turn-id"]
                for l in open(V1_LOCK) if l.strip()} - set(EXCLUDED_TURNS)
    locked_out = [r["meta"]["turn-id"] for r in rows
                  if r["meta"]["turn-id"] not in lock_ids]
    rows = [r for r in rows if r["meta"]["turn-id"] in lock_ids]

    distinct_cards = []
    seen = set()
    for r in rows:
        for c in r["cards"]:
            if c not in seen:
                seen.add(c)
                distinct_cards.append(c)

    parsed = run_splitter(distinct_cards)
    tools_by_card, tool_stats = card_tools(distinct_cards, parsed)

    with open(v3_path, "w") as f:
        for r in rows:
            row_tools = [tools_by_card[c][0] for c in r["cards"]]
            tools_json, _name_map = normalize_tools(
                json.dumps(row_tools, separators=(",", ":")))
            out = dict(r)
            out["json_tools"] = json.loads(tools_json)
            f.write(json.dumps(out, ensure_ascii=False) + "\n")

    sidecar = {
        "source": raw_path,
        "source_sha256": hashlib.sha256(src_bytes).hexdigest(),
        "generated": datetime.date.today().isoformat(),
        "generator": "src-needle/src/seon_needle/build_v3.py"
                     " + src-needle/scripts/split_forms.clj",
        "transform": [
            "cards: PASS-THROUGH (spec-face compact-fn-head — registered"
            " shape refs expanded, body-elision marker dropped, decoration"
            " glyphs stripped; NO schema-stripping — the v2 compact_card"
            " compaction is retired per the display-v3 owner ruling)",
            "contexts/targets/meta: PASS-THROUGH byte-exact (held-out"
            " continuity: same turn ids, same targets as v1 minus the 3"
            " :excluded junk turns)",
            "json_tools: cards -> needle tool defs via fn_to_tool with"
            " ENRICHED param descriptions (lint_probe.describe_schema:"
            " registered key schema type/constraints, never the bare"
            " keyword) + normalize_tools snake names; encoder tools slot ="
            " json.dumps(row['json_tools'], separators=(',', ':'))",
        ],
        "excluded_turns": EXCLUDED_TURNS,
        "excluded_reference":
            "docs/prds/repl-autosuggest/research/"
            "scorer-false-negative-audit-2026-07-12.md",
        "locked_out_turns": locked_out,
        "rows": len(rows),
        "card_translation": tool_stats,
    }
    sidecar_path = v3_path.replace(".jsonl", ".meta.json")
    with open(sidecar_path, "w") as f:
        json.dump(sidecar, f, indent=1, ensure_ascii=False)

    print(f"rows {len(rows)}; card translation: {tool_stats}")
    print("wrote", v3_path)
    print("wrote", sidecar_path)


if __name__ == "__main__":
    main()

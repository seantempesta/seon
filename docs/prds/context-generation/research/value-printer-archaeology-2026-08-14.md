---
type: research
status: current
tags: [research, render, print, archaeology]
---

# The value printer — what we had, what we have, what synthesis restores

Orchestrator's own-eyes reading, 2026-08-14 evening. Sources read in
full: `src/seon/print.cljc` at the working tree (949 lines);
`9e44815f5:src-old/seon/render/value.cljc` (1927 lines, the first
implementation's printer) — sampler core, emit half, and ns docstring;
`git log --follow src/seon/print.cljc` (born fresh at `94220a629`
"Implement sealed admitted print grammar" — the archive printer was
never consulted, confirming fuckup #3 in the program README).

## Verdict

The current printer is a fresh reinvention that kept one genuinely new
good idea (the Sink protocol with a tee — one traversal, text + hiccup
in lockstep, `print.cljc:187-207, 670-681`) and lost essentially every
hard-won lesson the 1927-line original encoded. The original's ns
docstring is a complete design brief titled, in effect, "why a sampler,
not pr-str + char-clip" — and the current implementation ships pr-str +
char-clip at its own seams (`projected-text`/`bounded-text`,
`print.cljc:829-839`; `fit-terminal`, `render.clj:524`).

## What the archive printer had that the current one lost

1. **Sample→emit, in that order.** Old: build a depth/breadth-bounded
   SKELETON first (nothing oversized is ever realized or printed), then
   emit. Current: `fit` (`print.cljc:908-943`) is a convergence loop
   that repeatedly re-emits the ENTIRE tree (full `emit-text` per
   iteration), halving limits until the estimate fits — and a second
   character-chop path survives beside it.
2. **Degradation order that protects the payload.** Old:
   `dominant-string-entry` — a map dominated (≥70%) by one long string
   promotes that string to a body block with the small keys as header;
   the payload is the POINT. Current `fit` halves `string-limit` FIRST,
   then children, then depth — it destroys payloads first and keeps
   scaffolding, exactly backwards.
3. **Lazy safety as a contract.** Old `sample-seqish`: guarded head+1
   realization; a poisoned lazy seq (`(map #(throw …) xs)`, `KeySeq` on
   a non-map) degrades to an opaque marker naming the cause; `sample`
   PROMISES it never throws. Current printer has no realization guard —
   it leans entirely on `sci/admit` upstream, and direct `emit-*`
   callers on non-admitted values have no net.
4. **Navigation preservation as a design goal.** Old: every retained
   map key and vector index is a REAL `get-in` path; display-only keys
   carry non-drillable markers; a partial view appends ONE trailing
   drill hint (top-level type + count + the live `result/<id>` var).
   The skeleton exists so the agent can navigate WITHOUT requerying.
   Current: paths live in elision nodes and hiccup `data-seon-path`,
   but the AI text carries no drill affordance and the bare `"..."`
   default destroys the count/path/requery facts entirely.
5. **Honest markers with real vocabulary.** Old: `… +129 more`,
   `… +129 more sampled columns {:a :b :c}` (shape hints from a bounded
   key-intersection sample), `⟨N tokens⟩` on clipped strings,
   `#‹datahike/DB max-tx=42›` opaque tokens, `#datom[e a v]`,
   `{…12 keys}` pruned markers. Current: the rich elision node exists
   (`elision-node`, `print.cljc:694-707`) but the emitter's defaults
   (`::length 32` / `::level 8` from `seon.print.edn`) emit bare
   `"..."` / `"#"` at eight sites, and `::truncated-string` prints the
   ellipsis INSIDE the quoted string (`print.cljc:531` —
   `(pr-str (str value "…"))`), lying about the string's content.
6. **Inline-when-fits layout.** Old: `fits?`/`emit` — render inline
   when the whole node fits the width at this indent, else break one
   child per line with real indentation. Current: `soft-separator`
   column wrap only (`print.cljc:90-96`) — breaks mid-structure at a
   column count; no fits logic, no per-child lines. This is a large
   share of why current output reads as soup.
7. **Bounded small-value key preference.** Old: rank a bounded
   candidate window by preference tier + rendered size + original
   index — byte-stable, keeps identity/small scalars visible without
   visiting the whole map. Current: map entries emit in admitted order,
   cut at `length` with no preference at all.
8. **Verbatim-probe for small values.** Old: prove a value small and
   fully plain, then `pr-str` it WHOLE — no marker noise on tiny data.
   Current: every value pays the same admission + node grammar.

## What the current printer has that the archive lacked (keep these)

- The Sink protocol + tee: one traversal, text and hiccup structurally
  agreeing by construction; `HiccupSink`'s `<details>`/`data-seon-path`
  structural browser.
- The derived table face for uniform map sequences
  (`table-data`/`emit-table`).
- The namespace-map lift (`#:ns{...}` printing).
- Elision as a first-class NODE inside the tree (the old one's markers
  were reserved-key maps; a declared face in the node grammar is
  cleaner) — provided the bare form dies.
- `references` — frontier extraction from print nodes for pull
  membership.
- Schema-declared options (when they stop defaulting to bare-cut).

## Synthesis shape (feeds the PRD; not a wave plan)

Rebuild the printer as: **admit/sample once (guarded, bounded,
skeleton-with-real-paths) → emit through the tee sinks with
inline-when-fits layout → every cut an elision value, no other form
representable.** Revive from the archive: sample-then-emit ordering,
dominant-string promotion, lazy-realization guards, drill hints,
opaque/datom tokens, shape hints, small-value preference, the verbatim
probe. Keep from current: sinks/tee, table face, elision-as-node,
namespace lift, references. Delete: the `fit` convergence loop's
re-emit design, `fit-terminal`'s second pass, the `::length`/`::level`
bare-cut path, `::truncated-string`'s in-string ellipsis, and the
`:?_current-ns_?/face` botch (`print.cljc:572`). The owner has ruled
red tests are acceptable collateral: the tests locking in current
behavior (`print_test.clj:240, 255-269`) are stale expectations to
rewrite as the PRD §6 properties, not constraints.

Open printer-specific questions → the
[open-questions ledger](../plan/open-questions-2026-08-14.md) Q10.

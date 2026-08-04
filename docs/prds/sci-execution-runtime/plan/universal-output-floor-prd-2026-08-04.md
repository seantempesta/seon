---
type: prd
status: active
tags: [prd, rendering, output-bounds, runtime, agent]
---

# The universal output floor — PRD (2026-08-04)

Owner frame (2026-08-04, conversational): the boundaries already exist.
`:seon.render/ai` is the print — a value becomes bounded text for an
agent, tool, log reader, test operator, or terminal operator.
`:seon.render/html` is the page handoff — a value becomes Hiccup, and
web serialization is transport after it. The universal floor is the
COMPLETENESS claim over those two existing projections:

> No consumer-visible text exists except as `:seon.render/ai` applied
> to a value (declared producer or the generic floor). No semantic page
> content exists except as `:seon.render/html` applied to a value.

Evidence base — two independent designs that converged:
[universal-output-floor-2026-08-04.md](../research/universal-output-floor-2026-08-04.md)
(sol: complete crossing inventory, probes P1–P7) and
[universal-output-floor-opus-2026-08-04.md](../research/universal-output-floor-opus-2026-08-04.md)
(Opus: root-cause probes, deletion-oriented design, falsifier queries).
Escape evidence: the three REPL dogfood reports and the session-curation
ugly-output roll-up.

## 1. Why multi-megabyte faces reached agents (settled by both lanes)

1. **Record admission walks monsters.** `datahike.db.DB` is a defrecord,
   so admission walks its indexes: one database value inside an ordinary
   map became a ~994k-character print node behind a 589-character
   display (probed by both lanes independently). Today exactly one arity
   of one function hand-strips `db-before`/`db-after`; every other
   nesting bypasses it (sol P3/P4).
2. **The cap fired on the wrong node.** `mcp-project` computes the
   fitted window (`projected-node`) and then builds the eval face from
   the ORIGINAL node — a one-symbol discard (`src/seon/cluster.clj:290-293`).
   Admission's 262,144-char string cap passes scalars that the sink's
   item/depth caps cannot touch: 262,147 chars render where
   `print-node-window` fits the same node to ~2,051. Both lanes traced
   this to the identical line.
3. **Floorless surfaces.** MCP envelopes, `runtime_status`, `get_value`,
   doc/dir's early `println`, the test runner (raw `clojure.test/report`
   fallthrough at `runner.clj:151`, unbounded message join at `:359`),
   operator faces, log/fault lines, page chrome — each converts values
   to text without the projection (sol's inventory: only three of ~19
   crossings substantially use the boundary today).
4. **Print-early erasure.** Error data stores `pr-str`'d print trees and
   args strings at throw time (`instrument.clj:247-269`,
   `error.clj:263-340`), so no later producer can ever recover the
   value (D6; sol P1).
5. **Five cap units, no shared owner** — admission characters, blob
   threshold characters (misused as a display ceiling in at least three
   sites), print entries/levels, ad-hoc site ceilings — grown in the gap
   left by one missing fact: nothing declares which functions are
   consumer-visible crossings.

## 2. The design (both lanes, merged)

**D1 — Reference values are identities in data, faces in projection.**
Two layers of one rule: at ADMISSION, reference values (database values,
connections — declared via a registered identity-only predicate, a
computed rule, never a class list) refuse entry and admit as identity
(db-name, basis t, commit id); the existing one-arity hand-stripper is
DELETED. At PROJECTION, producer dispatch runs at every value depth —
the same schema-property declarations used at block roots today
(`render.clj:101-150`) — so a database value, error, or message nested
anywhere gets its declared face. Producer dispatch stays OUT of
admission (Opus: re-entrancy against the guarded kernel; recorded so it
is not re-derived).

**D2 — One fit owner, consumer profiles as config facts.**
`print-node-window` generalizes (a move, not new code) into
`seon.print/fit`: node + profile → fitted node. A profile is a config
fact: estimated-TOKEN budget for the visible face (the house unit for
human/agent-visible sizes), structural depth/child budgets, blob
threshold for storage, composition policy (single-line / multiline /
tabular). Agent context, MCP, operator, runner, and logs are PROFILES
on one pipeline, never pipelines. The five independent truncators and
both illegitimate blob-threshold display uses are DELETED.

**D3 — Print late, print once.** Values stay values until the
projection renders them: error data carries admitted values/blob refs
(never `data-edn`/args strings/serialized print trees); doc/dir return
structured documentation values whose declared AI producer owns the
familiar REPL face; runner/operator/MCP owners return event values and
their sinks project; transcript composes values until its one
projection. Composition uses structured fragments; nothing `pr-str`s a
fragment back into data.

**D4 — Elision is a value.** Every elision carries: omitted count (in
the shape's own unit), total when knowable, stable requery identity
(blob digest / entity lookup / report identity) or an explicit refusal,
structural path + next offset, and the profile that produced it. Bare
`...` and bare `:seon.sci.admit/elided` never reach a consumer (folds
ledger D7).

**D5 — What does NOT share the AI floor** (explicit, so nobody forces
it): HTML's declared output is Hiccup (shares producer discovery, not
the text pipeline); internal canonical EDN / blobs / protocol JSON are
durable/transport codecs a consumer never sees directly; literal
authored copy (usage strings, CSS/JS assets) is not a value conversion
— only dynamic values interpolated into it are.

## 3. Conversion ladder (sol §4.4, Opus deletions merged; order = blast radius)

1. Structurally total AI/HTML projection walk (producer dispatch +
   fit + elision records at every depth) — settles before consumers
   convert.
2. Admission identity-only refusal for reference values; delete the
   hand-stripper (with W2's landed `transact!` face as first consumer).
3. Kill the wrong-node bug + route MCP value/envelope/`runtime_status`/
   `get_value` through the AI projection under an MCP profile (fix lane
   for the one-symbol defect dispatched immediately — it is a plain
   bug, not design).
4. Error data as values (delete `data-edn`, instrument strings) — D6
   dies here.
5. doc/dir as values; transcript's local printers removed.
6. Test runner: events as values, concise/verbose profiles, `:fail`
   fallthrough fixed, aggregate bounds; full diagnostics as addressed
   artifacts.
7. Log/fault/stderr profiles on the same pipeline (loud stays loud,
   bounded, identity-bearing).
8. Operator faces; page chrome becomes declared HTML producers (the
   no-scaffold ruling becomes true).

## 4. The standing falsifier (merged)

Two new leaf-level facts in the registry: a function's external SINK
effect (AI-visible text / HTML response / non-visible codec) and the
projection boundary it implements or requires. Then:

- **Graph property** (computed, fail-closed): every path from an indexed
  function to a declared sink must cross its dominating projection; any
  value-to-text call before the projection on a sink path, any cap
  constant not from a profile fact, any elision without count+requery,
  and any UNRESOLVED path fails with the shortest counterexample. The
  discovered set changes by declaring facts, never by editing the test.
- **Runtime construction proof** (generative): schema-registry
  generators place monsters and errors at arbitrary depths; every
  declared sink is instrumented; every observed write must carry
  projection evidence and fit its profile; retrieval by identity with a
  larger profile reveals the omitted region. Today's escapes become
  generated counterexample SHAPES, not an enumerated regression list.

## 5. Graduation

The floor is complete when the graph query finds zero unprojected
consumer-visible crossings and zero unresolved sink paths, the
construction proof observes zero unprojected writes, every AI profile
is a database-derived config fact, and every elision carries count plus
honest requery identity. Then the owner's question has one answer at
every crossing: the value is admitted as data when necessary, printed
exactly once by its AI projection; a page is its HTML projection, then
transport.

## 6. Open items

- Three new defects from the Opus lane need issue notes once the issue
  index settles (record-admission root, `transact!` 2-arity raw report,
  runner `:fail` fallthrough); sol's probes P1–P7 are their repros.
- Ladder steps 1–2 are the next implementation wave after W2 lands
  (they touch render/admit owners no current lane holds).
- The wrong-node fix (ladder 3a) is dispatched as an immediate defect
  lane; the rest of step 3 waits on step 1.

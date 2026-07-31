---
type: issue
status: open
severity: friction
tags: [issue, render, config, context]
---

# The transcript and namespace renderers invent private token dials

## Problem

Two renderers landed in this wave carry their own budget key,
`::token-budget`, which is not a config fact, not a schema attribute,
and not `:seon.sci.admit/caps`. No producer anywhere in `src/` or
`resources/` sets either one, and the two chose opposite defaults, so
both are wrong in production:

- `src/seon/render/transcript.clj:536` — `(get unit ::token-budget 0)`.
  With no key the transcript renders NOTHING but its elision marker.
  `seon.render.transcript` is referenced by no schema declaration, no
  block, and no caller; 595 lines are reachable only from its test.
- `src/seon/render/ns.clj` — `(some-> (::token-budget unit) long (max 1))`,
  nil meaning NO bound. `seon.render.ns` IS wired as the `:seon.ns/ns`
  family lens (`resources/seon/schema/program.edn`), so its entire
  "bounded, whole-form assembly" section is dead on every production
  path.

The budget is also used as a database ROW COUNT. `projection`
(`src/seon/render/transcript.clj:541-542`) computes
`candidate-limit (max recent-entry-count budget)` from an estimated
TOKEN count and passes it as `:limit` to two Datalog queries; measured,
a 100 000-token budget asks for 100 000 rows twice.

Hardcoded budget constants in the same files, none derived from a
config fact: `recent-entry-count 6`
(`src/seon/render/transcript.clj:17-20`, justified by a quarry
anecdote), `(quot preview-budget 2)` (`:418`), and in `ns.clj` a
`referenced-schema-cap` of 40 plus a `(soft-clip summary 78)` — 78 is a
CHARACTER width, which the standing rule forbids for a human-visible
size (estimated tokens through `seon.ai.tokens/estimate`, never raw
characters).

Related cost, same owner: `best-summary`
(`src/seon/render/transcript.clj:462-478`) binary-searches over
`fits?`, and `output-tokens` (`:497-501`) builds BOTH the full AI string
and the full HTML string on every probe, re-serializing every
already-accepted entry. `render-ai` and `render-html` (`:583-595`) each
call `projection` independently, so the whole search runs twice per
block. Measured on 200 messages of ~200 characters: 44 ms at budget
500, 442 ms at budget 20 000 for the AI twin alone.

## Acceptance

Both renderers read the one `:seon.sci.admit/caps` the block floor
already carries, or a declared `:seon.config.*` fact — no private
`::token-budget` survives, and no numeric budget literal remains that a
config fact could own. `candidate-limit` derives its row count from a
per-entry token cost, not from the budget scalar. Cost is accumulated
incrementally rather than by re-serializing the accepted prefix, and
`projection` is computed once with both twins taken from it. One
recurring measurement pins the render cost so a regression is visible.

## Evidence

`docs/prds/sci-execution-runtime/research/context-wave-audit-2026-07-31.md`

The context-MVP seam rerun in
`docs/prds/sci-execution-runtime/research/mvp-seams-notes-2026-07-31.md`
measured the production consequence after distance normalization removed raw
namespace-member traversal: the `seon.flow` owner d2 walk still reached 25
compact namespace cards and rendered 17,696 estimated tokens (71,302 UTF-8
bytes). No raw alias/import/function entity datoms remained. The remaining
size is therefore this absent namespace-card budget on the real walk path,
not the repaired distance seam.

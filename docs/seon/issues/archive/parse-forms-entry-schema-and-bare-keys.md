---
type: issue
status: resolved
severity: cleanup
tags: [issue, agent, schema, architecture]
---

# parse-forms entries: missing :malli/schema + bare keys

## Problem

`seon.repl.internal/parse-forms` and `strip-code-fences` are public fns with no
`:malli/schema` (flagged by Gemini + the paren-balancer fix agent, 2026-06-28).
`strip-code-fences` is trivially `[:=> [:cat :string] :string]`. But `parse-forms`
can't be cleanly schema'd because its entry maps use **bare keys** — `:kind`,
`:source`, `:narration`, `:form`, `:ok?`, `:error`, `:span`, `:error-kind` — which
violates the "every key fully namespaced" Data Rule.

So this is two linked questions, not a one-line fix:

1. **Should the parse-forms entry contract be namespaced?** (e.g.
   `:seon.repl.entry/kind`, `:seon.repl.entry/source`…). It is an INTERNAL
   structure consumed immediately by `seon.eval/eval-batch!` — not a DB datom, a
   callback map, or a map-in/map-out API — so the namespacing rule's
   Datalog-joinability rationale is weaker here. But the rule is stated without
   exception.
2. **Then** add the precise `:malli/schema` — a `[:vector [:or …]]` over the three
   entry shapes (`:form` / `:read` / `:comment`), matching the ns docstring's
   documented entry contract.

A weak `[:vector :map]` schema is NOT acceptable (false sense of completeness);
do it precisely or decide the key convention first.

## Why not auto-fixed

Renaming the entry keys touches `parse-forms` + every consumer in
`seon.eval/eval-batch!` and the render lane — a cross-cutting refactor with a
real convention decision, not a bounded cleanup. Needs a deliberate call.

## Acceptance criteria

- Decide bare-vs-namespaced for the entry contract (owner/architectural call).
- `strip-code-fences` gets its `[:=> [:cat :string] :string]` schema regardless
  (that one IS a quick win — could be split out and done immediately).
- `parse-forms` gets a precise entry-union return schema.
- `bin/test-cljs` green; the segmenter corpus (`internal_test.cljc`) unchanged.

## Links

- `seon.repl.internal/parse-forms` (the segmenter)
- Data Rules — "Maps with namespaced keywords. Every key. No exceptions." (CLAUDE.md)

## Resolution (2026-07-20)

Commit `150bb6036` atomically namespaced the public parse-entry envelope under
`:seon.repl/*`, moved read failures onto the one nested `:seon/error` value,
and migrated every maintained consumer. Commit `3a0dbd31` added the exact
closed discriminator-driven entry union and fence-strip function schema.
Commit `58fb020d` restored the portable Babashka gate without adding Malli or
another parser to the portable owner.

Current closure proof recorded in
[[../../../prds/source-cleanup/research/parse-forms-entry-boundary-2026-07-20]]:
`bin/test-parser` passes 46 tests / 368 assertions and the focused CLJS parser
namespace passes 46 / 369 with zero warnings. Frozen CLJS checkpoint
`286180f7`, which contains all three commits, passed 1,331 tests / 6,151
assertions with zero failures, errors, or warnings. The adjacent bare public
option key is tracked independently in
[[../parse-forms-option-map-uses-bare-key]] and does not reopen this completed
entry-envelope contract.

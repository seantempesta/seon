---
type: issue
status: superseded
severity: blocker
tags: [issue, schema, runtime]
---

# Output map closedness silently decides whether accretion is legal

## Problem

Seon's contract admission enforces `{:closed true}` on **input** maps only
(`src/seon/schema/internal.cljc:89-100`, guarded by `(= :input role)`). Output
map closedness is unconstrained. That single unenforced property decides,
mechanically, whether a change is *accretion* or *breakage* for a given corpus
function.

**Attribution UNVERIFIED.** The terms *accretion*/*breakage* and the rule
"require no more, provide no less" are believed to be Rich Hickey's from the
Spec-ulation keynote (Clojure/conj 2016), but the lane assigned to confirm the
exact phrasing FAILED and no one has verified it. Do not cite it as established
until someone reads the source. The mechanical property below stands on its own
and does not depend on the attribution:

- old output `[:map ...]` (open) — a new returned key **passes** an
  old-contract check;
- old output `[:map {:closed true} ...]` — the same new key **fails** with
  `:malli.core/extra-key`.

So two corpus functions differing only in a property nobody was asked to think
about get opposite verdicts for the same kind of change. Any accretion-check
gate built on `:seon.fn/spec` inherits that inconsistency.

The current toolkit happens to be consistent — every `*-response` schema in
`src/my/plan.cljc:75,119,272`, `src/my/blob.cljc:135,152,167,186` and
`src/my/canvas.cljc:136` is an open `[:map ...]` — but by convention only.
An agent authoring `[:map {:closed true}]` as a return shape passes admission
today and permanently forfeits accretion on its own function.

## Evidence

Measured on a plain JVM with the project's pinned `metosin/malli 0.20.0` plus
`org.clojure/test.check 1.1.1`, using `malli.generator/check` over
`[:=> [:cat ::request] ::response]` with 30 iterations; the new function adds
one key to the returned map and changes nothing else:

```
accrete (open old output)     satisfies     3ms
accrete (closed old output)   BREAKS        {:type :malli.core/extra-key}
```

Probe: `/private/tmp/claude-501/-Users-sean-src-seon/ad6e7227-ef9f-4cc7-954e-ea6dbabccdff/scratchpad/mini.clj`
(scratchpad, not a maintained test).

## Expected owner

`seon.schema.internal/assert-complete-schema!` — the same walk that already
rejects an open `:input` map is the one choke point that must also rule on
`:output` map closedness. `seon.schema/assert-complete-contract!` already
passes `:output` as `role` (`src/seon/schema.cljc:689-692`), so no new
traversal is needed.

## Acceptance criteria

- Admission states ONE rule for output maps and rejects the other form, so
  "provide more" has a single answer across the whole corpus.
- A regression asserts the rule at the admission choke point, not per call
  site.
- The rule is recorded in `docs/conventions.md` next to the closed-input rule,
  naming which of growth/breakage the choice makes expressible.

## Related

- `contract-predicate-transitive-purity-awaits-execution-planner.md` — the
  purity derivation an accretion check needs before it may execute a candidate.
- `closed-higher-order-call-targets-are-discarded-as-uncertainty.md` — why the
  reverse "who calls this?" query under-reports today.

## Resolution

Superseded by the fresh-tree split in f25e34594: the cited State A owner is quarry or deleted, and the current B2/N3/N4 ledgers do not carry this defect forward.

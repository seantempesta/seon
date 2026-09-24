---
type: issue
status: resolved (datahike fork f4614c9d + 620721d6; Seon workaround retired)
severity: friction
created: 2026-09-24
tags: [issue, database, query, datahike, upstream-delta]
---

# Datahike scans the whole store for an attribute variable bound by another clause

Seen 2026-09-24 by lane fix-fn-scan on default (pid 90471, basis-t 536872648,
575,837 datoms), datahike fork `c79cd03a`. Producer, not Seon: Datahike's
planned executor has no index path for a pattern whose attribute is a logic
variable bound upstream while its value is free.

## Mechanism

`scan-datoms` (`reference-code/datahike/src/datahike/query/execute.cljc:487`)
takes an index path for a variable attribute only through `var-attr-avet-pairs`
(`:456`), which requires BOTH `?a` and `?v` bound in one relation. With `?a`
bound and `?v` free, `resolved-a` is nil and the pattern reads the full EAVT
slice, then joins. `group-var-attr-clauses` (`query/plan.cljc:1481`) records
the correlation but only for ordering. A scalar `:in ?a` is a constant
(`replace (:consts context) clause`, `query.cljc:2276`), so the pattern is ground
and reads one AEVT slice. A variable attribute also makes the query's cache
dependency `:all` (`query-attribute-dependencies`, `query.cljc:2971`), so every
commit invalidates its cached result.

## Evidence (cold values from `(d/db-with db [])`)

| Query | Variable attribute | Ground / scalar attribute |
|---|---|---|
| `seon.fn/declared-reference-edges` (4 rules, 26 edges) | 800–902 ms / 2.94 GB | 6.3 ms / 19 MB (type-selected ground rules), equal set |
| same rules, `?target` bound instead | 810 ms / 2.94 GB | (binding the target does not help) |
| `seon.fn/unresolved-callers` rows (`[?attribute ...]`) | 4,262 ms / 4.94 GB | 394 ms / 670 MB, equal |
| capability parents (`[?edge ...]`, 3 targets) | 622 ms / 1.90 GB | 4.6 ms / 7.2 MB, equal |
| arity schema referrers (`[?key ...]` and `[?attribute ...]` both bound) | 18.6 ms | 21.6 ms (the `var-attr-avet-pairs` path works) |

A ground pattern also seeks a pre-bound value in that attribute's index, so a
ground instance whose value type disagrees with the rule (a Long into a symbol
index, a String into `:seon.fn/sym`) throws `ClassCastException` where the
variable join silently matched nothing; Seon selects instances by the installed
`:db/valueType`.

Seon now grounds these at the caller (`seon.fn/ground-attribute-rules`,
scalar-attribute loops in `unresolved-callers` and
`assert-capability-contracts!`). Landing:
`docs/prds/agent-platform/landing/lane-fix-fn-scan-2026-09-24.md`. The Seon-side
sighting of the same class is
[a collection-bound edge clause costs half a second per read](a-collection-bound-edge-clause-costs-half-a-second-per-read.md).

## Wanted upstream

In `scan-datoms`, when `?a` is bound to K distinct attributes and `?v` is free,
read K AEVT slices (one per bound attribute), as `var-attr-avet-pairs` already
does for bound `(a, v)` pairs. Cost becomes O(Σ attribute sizes), not O(store).
Then Seon's rule form and the grounding helper give the same cost, and the
helper can be deleted.

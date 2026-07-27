---
type: issue
status: superseded
tags: [rendering, schema, runtime, issue]
severity: blocker
---

# Value-drill result literals failed boot schema admission

## Evidence

The first restart after Unit 1E (`c1618e22` / `099f7e99`) failed before pod
readiness. Complete boot schema admission surfaced the failure at
`:seon.render.value/available-result` as `:malli.core/child-error`, initially
implicating its `[:= true]` child. The unavailable and failed branches used the
same literal pattern.

Unit 1E's focused 78 tests / 492 assertions did not exercise the exact complete
boot population. The operator exited the pod cleanly with no fallback; default
was left down and no Stage 1.6 evidence from that artifact counted.

## Expected owner and acceptance

Preserve the disjoint result union without broadening it to arbitrary booleans
or adding a hand-written discriminator. Strengthen the one schema bridge or use
the existing pure-EDN enum/literal idiom that the complete Seon population can
store and compile, then test all three exact result branches.

Acceptance requires candidate and complete boot-population compilation, the
focused renderer/config gates, a successful exact-HEAD `bin/seon up`, and the
real `/agents/run` checkpoint after all concurrent source owners release.

## Implementation evidence

Commit `dc968c35` corrects the source defect. The exact indexed-population
falsifier showed that the literal attribution was wrong: `::bounded-data`
contained bare Malli `:vector`, whose constructor requires exactly one child.
The recursive projection made that defect surface while compiling
`::available-result`. The established `[:= true]` / `[:= false]`
discriminators were already valid and remain intact.

The shallow container slots now reference registered pure-EDN `vector?` and
`map?` predicates from Malli's built-in registry. They neither enumerate child
values nor introduce an unbounded recursive schema walk; deep validation stays
at the later bounded producer/transport boundaries. The exact boot-indexed EDN
round trip compiles the complete population and validates all three result
branches. A million-entry poison map proves shallow validation performs zero
entry visits. The focused renderer/config gate passes 80 tests and 510
assertions.

The issue remains open for the orchestrator-owned exact-HEAD startup and real
`/agents/run` proof after concurrent source owners release. Archive it only
with that live evidence.

## Resolution

Superseded by the fresh-tree split in f25e34594: the cited State A owner is quarry or deleted, and the current B2/N3/N4 ledgers do not carry this defect forward.

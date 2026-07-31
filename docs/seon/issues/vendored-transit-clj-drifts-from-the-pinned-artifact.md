---
type: issue
status: open
severity: cleanup
tags: [issue, dependency, source-grounding]
---

# Close the remaining vendored-versus-pinned dependency drift

## Problem

Two more dependencies fail the rule that the source we read is the source we
run: one is vendored well ahead of the artifact we build against, and one is
a live dependency with no checkout at all. Reading either to ground a design
decision produces claims about code the system is not running.

This is the same defect class already recorded for Malli in
`malli-vendor-is-ahead-of-pinned-dependency.md`; these are its remaining
instances.

## Evidence

`deps.edn:70` pins `com.cognitect/transit-clj` `1.0.333`, confirmed as the
resolved version by `clojure -Stree`. `reference-code/transit-clj` is at
`8d2d217e9`, which `git describe` reports as `v1.1.357-5-g8d2d217` — a minor
version and five commits ahead of what we run.

`deps.edn:38` depends on `org.replikativ/hasch` `0.4.100`. There is **no
`reference-code/hasch` submodule**. Hasch computes the content hash under
every Datahike node address and every content-addressed commit id, so it sits
directly beneath the write-amplification and secondary-index work now in
flight, and it cannot be read.

Verified coherent, for contrast: `core.async`, `reitit`,
`persistent-sorted-set`, `datalog-parser`, and `timbre` all have vendored
revisions matching their resolved artifacts exactly.

Full sweep:
`docs/prds/sci-execution-runtime/research/upstream-delta-sweep-2026-07-31.md`.

## Owner

The root dependency ledger (`deps.edn`) and the `reference-code/` checkouts.

## Acceptance

- Every dependency Seon resolves either has a `reference-code/` checkout at
  exactly the resolved revision, or is recorded as deliberately unvendored
  with the reason.
- `hasch` is vendored, or the decision not to vendor it is stated where a
  reader grounding a storage change will see it.
- The check is mechanical enough to re-run — comparing resolved versions
  against vendored `git describe` output is the whole test.

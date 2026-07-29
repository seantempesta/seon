---
type: issue
status: resolved
severity: cleanup
tags: [issue, program-graph, documentation]
---

# Function indexer claims unbuilt call-graph reachability

## Problem

The new build-admission comment explains private rows as necessary for current
`:seon.fn/calls` reachability and names workload derivation, test selection,
usage signals, and renderer discovery as consumers. No `:seon.fn/calls`
attribute or deriving owner exists in the fresh system.

Private rows are still the correct complete inventory. The source comment
states target behavior as implemented behavior and therefore overstates what
the 808 new rows currently buy.

## Evidence

- Target commit `7340e2635`, `src/seon/fn.clj:28-36`, used present-tense
  `:seon.fn/calls` reachability to justify the admission boundary.
- `resources/seon/schema/program.edn` contains no `:seon.fn/calls`
  declaration.
- A fresh in-memory production population reported
  `{:calls-installed? false :calls-datoms nil}`.
- The 808 private rows do carry namespace refs, arglists, source, and privacy
  metadata. They currently provide complete declaration inventory and future
  graph input, not call or reachability edges.
- `archive/five-skills-teach-the-deleted-pod-system.md` already records
  workload reachability over `:seon.fn/calls` as `[TARGET]`, with no deriving
  owner in current `src/`.

## Owner

`src/seon/fn.clj` and the program-graph architecture or PRD that owns future
call-edge derivation.

## Acceptance

The source states only current implemented value, or marks reachability
explicitly as `[TARGET]` with its owning roadmap. When call edges land, the
schema, deriving owner, recurring proof, and consumers exist together.

## Resolution

Resolved by `52423e362`. `src/seon/fn.clj:22-26` now says the complete build
inventory is input to the **future** call graph and names no current
reachability consumers. The shared declaration owner at
`src/seon/program.cljc:64-83` retains the correct `:all` build admission
without claiming the future edge derivation already exists.

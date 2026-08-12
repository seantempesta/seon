---
type: issue
status: open
severity: blocker
tags: [issue, architecture, flow, testing]
---

# Make graph construction attach every declared fanout before resume

## Problem

First-party Flow graph construction leaves `create-flow`, `start`, fanout/tap
attachment, and `resume` ordering to each caller. The current cluster and agent
sites use the safe order, but the API still permits a new graph author to
resume a source before its declared consumers exist. Core.async mults consume
and drop values while they have no taps, so review is the only thing preventing
the first value from disappearing.

The earlier first-injected-fault repair fixed one instance in commit
`a86e5f029`; it did not make the ordering class unrepresentable.

## Evidence

- `src/seon/cluster.clj:2230-2265` locally spells
  `create-flow → start → start-error-fanout! → resume`, the corrected
  order that prevents the cluster graph's first error from preceding its tap.
- `src/seon/cluster/agent.clj:440-457` independently spells
  `create-flow → start → join-error-fanout! → resume` for every agent
  graph.
- `src/seon/flow.clj:638-652` and `src/seon/flow.clj:1033-1051` construct the
  work-launcher and fault-committer graphs with their own inline start/resume
  sequences. The latter also owns the report/error mult and tap installation
  used by source graphs.
- `src/seon/cluster.clj:2265-2304` resumes the cluster graph before constructing
  the render pages mult. By contrast, the dynamic web consumers correctly tap
  before requesting publication at `src/seon/render/web.clj:1008-1024` and tap
  before initial paint at `src/seon/render/web.clj:1071-1075`; that invariant is
  still expressed only at those call sites.
- At core.async pin `dc35f3e0d7bc2eef502e77982f48641f025c8051`,
  `reference-code/core.async/src/main/clojure/clojure/core/async.clj:797-837`
  documents and implements that a mult drops an item received with no taps.

## Owner

Owner: the one graph-construction function in `src/seon/flow.clj`. It accepts a
graph definition plus every declared fanout/tap attachment, performs
`create → start → join every declaration → resume`, and returns the
resumed graph. Every first-party graph constructor uses that owner; per-tab web
taps remain part of the tab lifecycle rather than a second graph constructor.

## Acceptance

- No first-party graph construction site can resume its graph before all
  declared fanouts/taps have joined; an `rg` inventory finds no remaining
  inline resume-before-tap construction sequence.
- One class regression constructs a graph through the owner, injects a value at
  the earliest possible instant, and observes it at the late-declared tap.
- The class regression passes three consecutive focused runs.
- `bin/test --changed` passes for the converted source and test paths.
- The resolving commit records the converted call-site inventory and the issue
  moves to `archive/` with the proof.

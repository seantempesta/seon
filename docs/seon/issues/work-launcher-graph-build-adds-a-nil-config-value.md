---
type: issue
status: open
severity: friction
tags: [issue, flow, config, testing]
---

# Build the work-launcher graph from complete Flow configuration

## Problem

The workload census cannot inspect the work-launcher graph because graph
construction adds a nil configuration value before any proc description is
available.

## Evidence

The bare 2026-08-05 gate errored in
`seon.flow-configuration-test/every-built-graph-proc-declares-a-specific-workload`:

```text
NullPointerException: Cannot invoke "Object.getClass()" because "x" is null
  at clojure.lang.Numbers.add
  at seon.flow/work-launcher-graph-definition (flow.clj:505)
```

The focused pre-rename reproduction at `401fd300e` failed with the same NPE at
the same owner and line. The explicit workload invariant itself was resolved
previously in [[archive/flow-procs-capture-closures-and-default-to-mixed]];
this failure prevents that standing census from observing the graph.

## Owner

`seon.flow/work-launcher-graph-definition` and the configuration fixture used
by `test/seon/flow_configuration_test.clj`.

## Acceptance

Graph construction either receives every required numeric fact or returns one
structured configuration refusal naming the missing key. The workload census
reaches every built proc and never performs arithmetic on nil.

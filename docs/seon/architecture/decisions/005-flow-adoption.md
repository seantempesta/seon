---
type: decision
status: active
date: 2026-07-26
tags: [decision, architecture, flow, runtime]
---

# ADR-005: Adopt core.async.flow through its public API and SPI

## Decision

`core.async.flow` is the one in-process scheduling substrate. Seon uses the
real Flow graph and public API with zero forked Flow files. Runtime owners are
procs whose behavior is a `step-fn`; bounded channels and `conns` form the
`graph-def`; the report channel carries bounded operational evidence.
`flow-monitor` remains the unmodified operations and visualization surface.

A runtime owner implements `flow.spi/ProcLauncher` when it must select over a
database interest or another non-Flow source alongside Flow control. Each
workload class has a bounded input channel and uses core.async's
`executor-for :io` or `executor-for :compute`. The eval seam additionally
arms the one `:interrupt-fn`, runs on a platform thread, and holds its admitted
permit until settlement.

Flow channels are process-local control, scheduling, backpressure, report, and
error state. Runs, claims, receipts, program facts, and transaction reports
remain database authority. A channel wake can prompt a query; it is never the
only record of durable work.

## Consequences

- The run loop and render pipeline are Flow procs, not parallel bespoke loops.
- Database-backed owners derive durable state on demand instead of storing it
  in a proc's threaded state.
- Flow pause, resume, and stop are process-local controls; database fences and
  component lifecycle publish durable readiness and completion.
- A Flow-alpha dependency is pinned deliberately and upgraded as one library;
  Seon does not copy `flow.impl`.

See [[agent-runtime]], [[ui]], and
[[architecture/decisions/012-process-root-cluster-topology]].

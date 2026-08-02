---
type: reference
status: abandoned
tags: [reference, database, history]
---

# Historical separate-JVM agent exploration

> Rejected topology. This page is not implementation or deployment guidance.
> Every fresh Seon agent owns a `core.async.flow` graph inside its cluster JVM.

The 2025-02-14 exploration compared namespace cloning with one JVM per agent.
Both choices predate the current model. The per-agent JVM recipes, nREPL
bridges, private dependency sets, and Integrant ownership proposed here were
deleted because none survives.

The useful dependency finding remains: Flow procs and channels are in-process,
and `flow.spi/ProcLauncher` is the launch boundary. Fresh Seon uses that fact
directly in `seon.flow` and `seon.cluster.agent`; it does not turn Flow into a
cross-process abstraction. The exact dependency contracts are in
`reference-code/core.async/src/main/clojure/clojure/core/async/flow/`.

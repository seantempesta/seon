---
type: issue
status: open
severity: friction
tags: [issue, agent-runtime, flow]
---

# Flow procs capture closures and two default to :mixed, defeating hot reload and pinning platform threads

## Problem

Two related residues in `src/seon/flow.clj`, both proven by the flow-mechanics
probes (2026-07-28):

1. **Closure step-fns defeat hot reload.** Every proc is built as
   `(flow/process (flow/map->step {...closures...}))`. The flow loop calls the
   step it was constructed with (`reference-code/core.async/.../flow/impl.clj:258-261`),
   so hot reload of proc behavior works only when the step-fn is a **var**
   (`#'f`), as the boot-tower doc claims ("graph definitions reference
   transforms as vars"). Measured: after `alter-var-root`, a var-backed proc
   switched behavior immediately while an identical closure-built proc kept the
   old code (probe `hotreload` section). Today's zero-restart live-update story
   does NOT apply to any proc in `src/seon/flow.clj`.

2. **The `:mixed` workload default pins a platform thread per proc.**
   `capacity-observer-proc` (`src/seon/flow.clj:114`) and `mailbox-proc`
   (`src/seon/flow.clj:818`) declare no `:workload`, so their loops run on the
   cached **platform** pool (`flow/impl.clj:247`, `impl/dispatch.clj:73,96`).
   Measured: 100 parked `:mixed` procs = 100 platform threads held while
   parked; 1000 parked `:io` procs = 1000 virtual threads, zero platform. In a
   per-agent-graph world the `:mixed` default is the one scaling cliff found.

## Evidence

`docs/prds/sci-execution-runtime/research/flow-mechanics-2026-07-28.md`
(sections 1 and "Hot reload and cluster reset"), probe script
`docs/prds/sci-execution-runtime/research/scripts/flow-mechanics-2026-07-28.clj`
(`idle` and `hotreload` sections).

## Acceptance

- Every proc in `src/seon/flow.clj` passes a var as its step-fn (one `defn`
  per proc with the arity dispatch inside, or `(flow/process #'step)`), and a
  regression proves re-evaluating a proc's transform changes a running graph's
  behavior without rebuild.
- No proc rides the `:mixed` default: each declares `:io` or `:compute`
  explicitly, and the classification is a computed/asserted rule (a proc
  construction that refuses a missing workload), not a hand-audited list.

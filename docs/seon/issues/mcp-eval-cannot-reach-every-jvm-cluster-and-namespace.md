---
type: issue
status: open
severity: friction
tags: [issue, tooling, sci]
---

# `eval_clj` cannot reach every JVM, cluster, namespace, or the agent's own view

## Problem

The orchestrator's one live-probe tool is narrower than the system it
probes. Owner framing (2026-08-02): "connect to the running system
regardless of what state it's in and what is running — the REPL starts
first; connect to any cluster and namespace… it's just a question of
where and how the form evaluates."

Four gaps, three of them coordinates and one of them semantics:

1. **Root.** Discovery reads advertisements under the DEFAULT operator
   root only, so every `bin/seon --root PATH` JVM — drills, eval hosts,
   lane scratch work, a second deployment — is invisible. The isolation
   seam hardened on 2026-08-02 and the probe surface do not compose.
2. **Degraded boots are unreachable, which inverts the priority.** The
   boot tower opens the prepl at second zero from the bootstrap config,
   BEFORE store/facts/flow, and the composition guarantees the REPL
   survives any later-layer failure (the degraded instance rides the
   throw as `:seon.boot/instance`). But the ADVERTISEMENT is written by
   a later layer — so a JVM with a live REPL and a failed cluster boot
   cannot be found by the tool that exists to diagnose exactly that.
3. **Namespace.** Every form lands in `user`; there is no way to
   evaluate in a chosen namespace.
4. **The agent's view is unreachable.** `eval_clj` runs on the JVM's
   own io-prepl. Nothing can evaluate THROUGH THE DOOR — the cluster's
   live SCI ctx, in an agent's namespace, under admission caps, the
   print grammar, contract enforcement, the time limit — so debugging
   "why did the agent see that?" is done by reasoning across a gap
   instead of standing inside it.

## Acceptance

- `root` parameter, defaulting to the repository's default operator
  root; `cluster` keeps its ambiguity-fails rule; `namespace`
  parameter for both modes.
- Discovery degrades like `bin/seon status` now does: advertisements
  first, then the operator's process records (pid + start-instant +
  generation) plus the bootstrap config's prepl bind — so a
  live-REPL/failed-cluster JVM IS reachable, with the tool stating that
  the cluster layer is degraded.
- A door mode evaluating via `seon.sci.eval/evaluate` against that
  cluster's live ctx in the given namespace, returning what an agent
  would receive. Two properties to state explicitly in the tool's own
  description, because both are load-bearing: the door mode MUTATES the
  shared per-cluster ctx (a debug `def` enters the agents' world —
  authentic, per ruling #31, and worth knowing), and it creates NO run
  or receipts (the loop commits those, not the door), which is what
  makes it a probe rather than a turn.
- Regressions: reach a `--root` cluster; reach a JVM whose cluster boot
  failed; evaluate in a chosen namespace both ways; one form whose door
  result differs visibly from its JVM result (a capped collection or a
  contract violation) proving the two modes are genuinely different
  views.

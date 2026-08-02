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

## Owner

The development MCP boundary in `script/seon/dev/mcp.clj`; implementation
landed in `816cbac0f` and awaits orchestrator review before archival.

## Evidence

Dependency ledger for the implemented boundary:

- Clojure `b18d3adc5b5f4d5d0ccea966203fb67a614d5c3d`, with io-prepl's
  read/eval/session namespace behavior grounded in
  `reference-code/clojure/src/clj/clojure/core/server.clj:228-296`;
- SCI `6de15683b7520cc973bc9c136aec7ad3f9b3788c`, with namespace creation and
  form evaluation grounded in `reference-code/sci/src/sci/core.cljc:354-405`;
- the operator's one observation derivation in
  `script/seon/fresh_operator.clj:434-458,636-651,788-895`; and
- the live cluster context and exact door request in
  `src/seon/cluster.clj:1039-1086,1337-1363` and
  `src/seon/sci/eval.clj:1392-1578`.

Implementation `816cbac0f` replaces the MCP bridge's private advertisement
roster with rows derived from the operator's `source-observations`: root-scoped
advertisements lead, and the operator's reconciled process records plus live
JVM registrations recover a target whose own advertisement is absent. A small
readiness observation over that already-discovered prepl distinguishes a live
REPL from a cluster that reached its shared SCI context. Root is part of the
session identity, duplicate live identities return `:ambiguous-cluster` with
candidates, and all tool failures remain
ordinary MCP error values.

The tool schema now supplies `root`, `namespace`, and `mode` (`jvm` or `door`).
JVM mode enters the requested namespace, explicitly refers `clojure.core`, and
evaluates the one already-read form. Door mode calls `seon.sci.eval/evaluate`
with the selected instance's shared context and the loop handle's admission
caps, time limit, and core-error decision. Its published description states
both load-bearing properties verbatim: it mutates the shared per-cluster
context, and it creates no run or receipts. It also states that returned MCP
content renders directly into the calling agent/orchestrator context.

Recurring focused proof:

```text
bin/test seon.dev.mcp-bridge-test
Ran 20 tests containing 144 assertions.
0 failures, 0 errors.
```

The suite includes a real `clojure.core.server/io-prepl` crossing proving that
an explicit non-default root and namespace return
`[mcp.live.chosen 42]`; function-level regressions cover operator-root
selection, a process-record-derived degraded registration, advertised degraded
classification, ambiguity with the full candidate list, both generated
namespace forms, and the exact tool-description obligations. A direct stdio
`tools/list` probe returned server version `0.4.0` with the new schema and
description.

Live acceptance used only the isolated operator root
`tmp/mcp-eval-live-root` and ended with `bin/seon --root ... down`:

- `default` booted at pid `88188`, prepl `64754`; `runtime_status` and JVM
  evaluation selected that explicit root. JVM namespace `mcp.live.jvm`
  returned `[:jvm mcp.live.jvm (0 ... 199)]`. Door namespace
  `mcp.live.door` returned `[:door "mcp.live.door" (0 ... 199)]`, and a
  second door call returned the prior `marker` value `:door`, proving shared
  context mutation.
- A forced failure of `stack-tower!` after `seon.cluster/start!` opened the
  REPL produced the carried `:seon.boot/refused` instance `failed-boot` at
  prepl `65072`. MCP status reported
  `failed-boot state=degraded ... cluster-layer=degraded`, while JVM mode in
  `mcp.failed.jvm` returned `[mcp.failed.jvm :reachable]`. After temporarily
  moving that cluster's `prepl.edn` aside, the operator-derived registration
  still resolved it and returned
  `[mcp.failed.no-ad :reachable-without-file]`; the file was then restored.
- The identical form `(java.lang.System/getProperty "user.dir")` returned the
  repository path in JVM mode and the flat
  `:seon.sci.eval/evaluation-failed` value "Unable to resolve symbol" in door
  mode, visibly proving different evaluation views.
- Run/receipt counts were `[1 13]` immediately before a door-only debug `def`
  and `[1 13]` immediately after it. The definition appeared in the shared SCI
  context while no run or receipt was created.

The current `seon.cluster/start!` writes its advertisement in REPL layer zero
(`src/seon/cluster.clj:1443-1471`), so an ordinary post-REPL failure retains
that primary discovery fact. The missing-advertisement live falsifier above is
the stronger fallback proof: the process-record/JVM-registration path still
reached the failed cluster when its own file was unavailable.

---
type: issue
status: open
severity: blocker
tags: [issue, sci, runtime, database]
---

# Stop installing private vars so agents cannot reach other clusters or the runtime roots

## Problem

An agent evaluating through the door reaches every cluster in the JVM
and the process's mutable runtime roots. Probed live on cluster
`default`, 2026-08-02, via `seon.sci.eval/evaluate` against the live
cluster ctx:

```clojure
@@#'seon.cluster/running-instances
```

returns the full instance map — every cluster's live
`datahike/Connection`, the store `FileLockImpl`, the flow graph, the
agent routing atom, the prepl `ServerSocket`, and each cluster's own
SCI ctx env atom. From there an agent can write to another cluster's
branch, release the flock, or mutate routing state.

THE CAUSE IS OUR INSTALL STEP, NOT SCI. SCI is deny-by-default and that
part works: in the same context `future`, `pmap`, and
`Thread/currentThread` are all unresolvable ("Unable to resolve
symbol"), because `build-base-ctx` installs only the namespaces it
names and `:classes` carries only `Throwable`/`Error`
(`src/seon/sci/eval.clj:156-228`). Agents cannot spawn threads at all
today.

The reach exists because `acquire!` installs the program graph
INCLUDING PRIVATE VARS. Observed in the live ctx env:
`#'seon.cluster.store/held-flocks`, `#'seon.cluster.store/release-store!`,
`#'seon.cluster.store/panic-on-core-error?`,
`#'seon.cluster.store/jdk-integers->long`, `#'seon.cluster.run/refuse!`
— all `defn-`/`^:private` — alongside the public functions.
`#'seon.cluster/running-instances` is a `defonce ^:private`
(`src/seon/cluster.clj:188`) reachable the same way.

Ruling #20 makes every function in the cluster's PROGRAM GRAPH
callable. Whether private implementation vars are part of that graph is
the argument this fix turns on, and it must be made explicitly rather
than assumed: excluding them restricts no documented agent capability,
while installing them hands out the process's mutable roots.

## What the reach actually enables (2026-08-02, `custody-isolation-design-2026-08-02.md`)

The consequence is worse than cross-cluster writes. Because each
instance carries `:seon.sci.eval/ctx` (`resources/seon/schema.edn:1488`)
and a ctx is an ordinary map whose `:env` is an ordinary atom
(`reference-code/sci/src/sci/core.cljc:312-331`), an agent holding
another cluster's ctx can rewrite that cluster's ENTIRE PROGRAM using
`swap!` and `assoc-in`/`dissoc` alone — no interop, no privileged
function, no Var mutation. Demonstrated in
`research/scripts/custody-probe7-2026-08-02.clj`: a function deleted out
of a victim context (victim then gets `Unable to resolve symbol`), and
an attacker-authored function injected into it and executed by the
victim (`:ATTACKER`). That is arbitrary code execution in every other
cluster's agent world, invisible to any database audit, and NO custody
design touches it — `swap!` on a reachable atom is not a call into any
function we own. Only removing the reference closes it.

Separately verified, and reassuring: the COMPILED runtime cannot be
redefined. `alter-var-root`, `with-redefs`, `var-set`, `intern`,
`binding`, and `push-thread-bindings` against a raw `clojure.lang.Var`
reached from SCI all throw, because SCI's `IVar`/`IBox` protocols are
not extended to `clojure.lang.Var`
(`research/scripts/custody-probe6-2026-08-02.clj`); `.bindRoot` interop
is blocked by the `:classes` gate. ONE EXCEPTION: `alter-meta!`
SUCCEEDS and is process-global, and `seon.instrument` derives from var
metadata (`src/seon/instrument.clj:115,147`), so an agent can alter what
instrumentation and documentation say about a compiled function for
every cluster in the JVM. It cannot change what the function does.

## `ns-publics` is necessary but not sufficient — and the residue is a query

Two lanes converged on `ns-publics` as the smallest correction (708
private host Vars across 42 live namespaces are installed today). The
semantics lane then found the gap: `seon.cluster.store/release-store!`
is PUBLIC (`src/seon/cluster/store.clj:340`), and the store is
process-root-wide, so releasing it affects EVERY cluster in the
process — a cross-cluster damage vector that survives the private-var
fix.

The remaining surface is small and, importantly, DERIVED rather than
enumerated by hand. Public functions whose declared output hands back a
custody object, queried live on `default` 2026-08-02 over ruling #33's
contract facts (`:seon.fn.arity/output-refs` against
`:seon.store/store`, `:seon.store/branch-connection`,
`:seon.sci.eval/ctx`):

```text
seon.cluster.store/open-branch!
seon.cluster.store/open-store!
seon.sci.eval/build-base-ctx
seon.sci.eval/cluster-ctx
```

Four functions, each needing its own decision (relocate to a
never-installed operator namespace, or accept with reasons). Because
this is a query and not a list, it becomes a STANDING CHECK: any future
function that starts handing out custody appears in it automatically.
That check belongs in the stability suite, where its value is catching
the function nobody thought about rather than re-confirming these four.

## Acceptance

- The install seam stops publishing private vars into the cluster ctx
  (or an equivalent computed rule — never a hand list of names), with
  the ruling-#20 argument recorded in the plan.
- Verified before landing: no bootstrap or agent-facing path depends on
  reaching a private var.
- Process-global roots (`running-instances`, the root store holder,
  held flocks, root executors) are unreachable from an agent
  evaluation, proven by the probe above returning a resolution error.
- An agent in cluster A cannot obtain cluster B's connection by any
  route: var reach, an installed function's return value, or a captured
  object's methods.
- The deny-by-default properties that already hold become PINNED
  regressions rather than incidental: no thread creation, no host class
  beyond the installed set, no arbitrary interop.
- Regressions live in the database/SCI stability suite (in-memory
  connections, no disk growth).
- FOREIGN-CONTEXT INTEGRITY is pinned as its own property: for any
  clusters A and B and any agent form evaluated in A, B's env atom is
  unchanged — no namespace added, removed, or rebound. This is the
  regression for the arbitrary-substitution finding above.
- Reachability is not left depending on a `defn-` metadata flag: the
  process-root registry moves to an operator-owned namespace that is
  never installed into any ctx, so a future `defn-` → `defn` slip
  cannot reopen it.
- COMPILED VAR METADATA IMMUTABILITY is either restored (no
  `alter-meta!` effect from an agent form) or recorded as an explicit
  accepted residual with the instrumentation consequence named.

Related: [[seon-db-is-not-the-one-database-namespace]] (ruling #41's
custody model), and ruling #30's persistence gate, which governs what
an agent may COMMIT and is a different control point from reachability.

## Phase 2 public-Var checkpoint — 2026-08-02

Commit `0c3a3d535` changed the one compiled first-party installation seam to
publish `ns-publics` rather than `ns-interns`. The standing custody census now
derives every loaded core-provenanced namespace from database provenance and
asserts that its SCI intern set is exactly the host namespace's public set. It
also proves that the census exercised namespaces containing private Vars, so a
missing program graph cannot produce a vacuous pass.

This preserves ruling #20 rather than narrowing it. `:seon.fn/private?` is the
computed program-graph fact already honored by program documentation,
namespace rendering, and the bootstrap drive. The compiled install seam was
the sole agent-facing projection that disagreed. Public compiled functions
continue to call their private JVM helpers normally; the pre-change audit found
no bootstrap, render, macro, agent-authored acquisition, or SCI test path that
resolved a private first-party Var from inside an evaluation.

The recurring focus passed 42 tests and 173 assertions with zero failures and
zero errors:

```text
bin/test seon.custody-stability-test seon.sci.eval-test
```

The reset-boundary proof used isolated operator root
`tmp/reachability-phase2-live-root-0c3a3d535`. After explicit `init` published
`current-src` digest
`e06fb003ea2e5ec7eaa2510d3ee3a77455207b3a93f578783641f9f3b8a34d8d`, cluster
`reachability-phase2` reached readiness in 1,449 ms. Its `bootstrap:root` run
closed, its root agent owned `my.agents.root`, and one ordinary inbound message
produced a real `deepseek-v4-flash` attempt with finish reason `stop`. The four
receipts evaluated comments, `(+ 40 2)` to `42`, and
`(my.run/complete "42")`; the run closed without an error. Its exact prompt was
captured at basis 536870955 as one 5,529-estimated-token render-walk
contribution. `/`, `/agent/root`, `/ns/my.agents.root`, and the namespace debug
page all returned HTTP 200; the debug page contained both render projections,
the namespace, and the settled transcript value.

`bin/seon --root tmp/reachability-phase2-live-root-0c3a3d535 down` stopped the
one recorded JVM. The final status reported both scratch clusters stopped,
zero live clusters, no orphan JVMs, and a readable roster.

The issue remains open for orchestrator review and the later approved
reachability phases. This checkpoint closes only the owner-selected
private-Var publication step; the derived public custody-returning surface and
the other acceptance rows above remain explicit work.

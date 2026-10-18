---
name: seon-flow-architecture
description: "Design or change Seon procs, graph lifecycle, workloads, channels, and bounded execution using core.async.flow. Use before adding running machinery or changing agent context ownership."
---

# Flow owns running machinery

Use [AGENTS.md](../../../AGENTS.md) for repository laws and
[agent-loop.md](../../../docs/seon/specs/agent-loop.md)
for the current lane and runtime contract. Read the dependency at the
boundary before adding a mechanism.

## Construct through the existing owner

`seon.flow/var-process` requires a step Var, explicit `:io` or
`:compute` workload, and an environment in its arguments
(`seon.flow/var-process`). Using a Var keeps behavior live under
redefinition; topology changes rebuild the graph.

Core.async's step function arities are describe, init, transition,
and transform. The transition hook owns cleanup at stop
(`clojure.core.async.flow/process`, the transition arity in its docstring, `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj`).
Acquire process-local resources in that lifecycle and release them
through its existing completion path.

The dependency documents that `:io` should not do extended computation
and `:compute` must not block (the `:workload` entry of the same `clojure.core.async.flow/process` docstring).
Its default I/O executor uses virtual threads when available; default
compute and mixed executors are cached platform pools
(`clojure.core.async.impl.dispatch/create-default-executor` and `make-io-executor`, `reference-code/core.async/src/main/clojure/clojure/core/async/impl/dispatch.clj`).
A bounded compute guarantee therefore requires the graph's actual
supplied executor, not just the workload keyword.

## Events, control, and buffers

Flow already owns start, stop, pause, resume, ping, and injection.
Its stop sends a command and closes report/error channels; it is not a
join on proc exit
(the `stop` method of the flow `clojure.core.async.flow.impl/create-flow` returns, `reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj`).
Require a proc-published completion when the caller needs an exit proof.

The step description's `:ping-map-fn` defaults to identity
(`clojure.core.async.flow/process` docstring). Supply a deliberate
data selection; never expose a whole context or connection by accident.
A missing ping response is unknown, not healthy.

Pick buffers from loss semantics: a payload-free wake may use sliding-one
because work derives from database facts; required deliveries use
backpressure; observation may drop with accounting. These are repository
transport rules, not a reason to place authoritative work in a channel.
Every execution surface also carries a declared bound; a timeout names
the event that failed to arrive.

## Agent context ownership

Installed: each turn forks the current base context
(`seon.sci.eval/fork-for-turn`). When the agent's previous context is held,
the private `regenerate-agent-context!` in `seon.sci.eval` forks the new base and carries the agent's own
private Vars over it as actual objects; inherited program bindings are
replaced. A base context (`seon.sci.eval/base-ctx`) forks the frozen program context
`seon.sci.eval/program-context`, one `seon.cache` entry of kind
`:seon.sci.eval/program-context` per program content, which interprets the
branch's own program changes (overrides and their source callers; loaded `src`
callers stay compiled) on a fork of the
JVM's loaded program. The law is that a context
is reacquired from its branch head and private defs, atoms and result objects
stay in memory (CLAUDE.md, "An agent's context"). Do not restore `:seon.def`
entities or rebuild a private layer from the database.

**[TARGET]** (`docs/prds/agent-platform/plan/lane-b2-walk-flow-fork.md` §1):
the fork is the context. The binding snapshot, the regeneration
diff and the kernel mirrors carried on a context leave; private objects are
carried by not being replaced.

SCI exposes reusable `init`, `fork`, and `intern` operations
(`sci.core/init`, `sci.core/fork`, `sci.core/intern` in `reference-code/sci/src/sci/core.cljc`).
These are the dependency mechanisms to reuse.

Private objects remain with the agent's live context and disappear on
JVM restart. Program functions, schemas, and tests persist as facts.
The result object map is process-local; the stored printed value records what
the agent saw. Never transport those objects through an EDN restoration
path.

## One turn mechanism

Datahike evidence capture and validity live in `seon.db/read-evidence` and
`seon.db/read-evidence-current?` (`src/seon/db.clj`).
Extend the one mechanism in place rather than introducing a central
dispatcher, block-specific refresh handlers, or another cache.

A model turn opens in the writer before the provider call (`seon.turn/open-call`);
opening while the agent already has an open turn is a no-op inside the writer.
One writer transaction stores the reply and its ordered evaluations
(`seon.turn/plan-call`), and `seon.turn/close-tx` closes the turn; a submitted source run is
written in the same transaction (`with-submission`). At boot open turns close
and unfinished evaluations become interrupted (`seon.turn/recover-call`); no interrupted
execution resumes. The [runtime diagrams](../../../docs/seon/architecture/agent-runtime.md)
show the turn sequence.

## Rendering and verification

One entity schema declares one AI/HTML pair; each block covers a concern,
not one scalar. The value renderer applies the profile once at evaluation
time. HTML renders the live object, or the saved printed value after restart.

Browser delivery stays in the cluster's existing render path.
The socket writer waits for the dependency's drain-or-close completion:
`org.httpkit.server/write-state` (`reference-code/http-kit/src/org/httpkit/server.clj`).
Do not infer socket drain from a successful send.

For this wave verify default using the operator and MCP defaults.
Use virtual replies and the canonical fixture under instrumented contracts;
a host eval or HTTP 200 does not prove graph behavior.
Read the clojure-testing skill for the lane gate and bounded event waits.

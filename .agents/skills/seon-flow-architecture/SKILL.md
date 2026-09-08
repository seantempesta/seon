---
name: seon-flow-architecture
description: "Design or change Seon procs, graph lifecycle, workloads, channels, and bounded execution using core.async.flow. Use before adding running machinery or changing agent context ownership."
---

# Flow owns running machinery

Use [AGENTS.md](../../../AGENTS.md) for repository laws and
[turn PRD §10, §13–§15](../../../docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md)
for the current lane and runtime contract. Read the dependency at the
boundary before adding a mechanism.

## Construct through the existing owner

`seon.flow/var-process` requires a step Var, explicit `:io` or
`:compute` workload, and an environment in its arguments
(`src/seon/flow.clj:123`). Using a Var keeps behavior live under
redefinition; topology changes rebuild the graph.

Core.async's step function arities are describe, init, transition,
and transform. The transition hook owns cleanup at stop
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:168`).
Acquire process-local resources in that lifecycle and release them
through its existing completion path.

The dependency documents that `:io` should not do extended computation
and `:compute` must not block (`flow.clj:198` at the same path).
Its default I/O executor uses virtual threads when available; default
compute and mixed executors are cached platform pools
(`reference-code/core.async/src/main/clojure/clojure/core/async/impl/dispatch.clj:82`).
A bounded compute guarantee therefore requires the graph's actual
supplied executor, not just the workload keyword.

## Events, control, and buffers

Flow already owns start, stop, pause, resume, ping, and injection.
Its stop sends a command and closes report/error channels; it is not a
join on proc exit
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:174`).
Require a proc-published completion when the caller needs an exit proof.

The step description's `:ping-map-fn` defaults to identity
(`flow.clj:191` at the dependency path above). Supply a deliberate
data projection; never expose a whole context or connection by accident.
A missing ping response is unknown, not healthy.

Pick buffers from loss semantics: a payload-free wake may use sliding-one
because work derives from database facts; required deliveries use
backpressure; observation may drop with accounting. These are repository
transport rules, not a reason to place authoritative work in a channel.
Every execution surface also carries a declared bound; a timeout names
the event that failed to arrive.

## Agent context ownership — target

Each agent owns its own graph and one persistent SCI context forked once
from the cluster base. The context receives accepted base diffs before
later turns, retaining private defs/atoms and actual result objects.
Do not construct a new fork every turn or restore `:seon.def` rows.

SCI exposes reusable `init`, `fork`, and `intern` operations
(`reference-code/sci/src/sci/core.cljc:330`, `:345`, `:260`).
These are the dependency mechanisms to reuse. Their existence does not
prove the Seon context-diff integration has landed.

Private objects remain with the agent's live context and disappear on
JVM restart. Program functions, schemas, and tests persist as facts.
The result object map is process-local; stored shown text records what
the agent saw. Never transport those objects through an EDN restoration
path.

## One turn mechanism — target

Opening is system turn 0: ordinary submitted source, reply present,
no provider attempt. Before each agent turn, inspect the latest evaluation
of every distinct read form. Changed read evidence since its `:t`
appends a system turn; unchanged evidence does not. Include generated
and agent-written reads, never writes or effects.

Datahike evidence capture and validity already live in
`src/seon/db.clj:468` and `:561`. Extend the one mechanism in place
rather than introducing a central dispatcher, block-specific refresh
handlers, or another cache.

The turn has three writes: open; store reply/attempts/forms; store
outcomes and close. The writer refuses a second open turn.
At boot open turns close and unfinished evaluations become interrupted;
no interrupted execution resumes.

The prompt is stored evaluations rendered through their entity pair.
Old shown text remains unchanged. Compaction wipes evaluations and
regenerates the opening through the same system-turn algorithm.
The [runtime diagrams](../../../docs/seon/architecture/agent-runtime.md)
show both additive sequences.

## Rendering and verification

One entity schema declares one AI/HTML pair; each block covers a concern,
not one scalar. The value renderer applies the profile once at evaluation
time. HTML renders the live object, or saved shown text after restart.

Browser delivery stays in the cluster's existing render path.
The socket writer waits for the dependency's drain-or-close completion:
`reference-code/http-kit/src/org/httpkit/server.clj:321`.
Do not infer socket drain from a successful send.

For this wave verify default using the operator and MCP defaults.
Use virtual replies and the canonical fixture under armed contracts;
a host eval or HTTP 200 does not prove graph behavior.
Read the clojure-testing skill for the lane gate and bounded event waits.

---
type: issue
status: open
severity: cleanup
tags: [issue, agent, database]
---

# Address resident agents by namespace

## Intended model

An agent's public identity and routing address is one Clojure namespace symbol.
Root may create a deliberate specialist such as `my.tax`; otherwise Seon
assigns a generated symbol such as `my.agents.yellow-cameras-smash`. Exactly
one resident agent may occupy a namespace at a time. Namespace stewardship is
not code ownership: every agent may call, inspect, or repair shared namespaces.

The database shape uses a unique cardinality-one ref:

```clojure
{:seon.agent/namespace [:seon.ns/name 'my.tax]}

```

Datahike source and executable proof are retained in
`docs/prds/database-authority-mesh/research/agent-id-rename-datahike-audit-2026-07-18.md`.
The agent's numeric eid and every durable ref remain stable when its namespace
changes.

`*ns*` remains the transient ClojureScript evaluation namespace. `in-ns` never
changes the persistent agent namespace. AsyncLocalStorage carries the current
agent namespace symbol per invocation. The supervisor may key its process-local
child state by the stable agent eid so adopting another namespace does not
discard a warm compiler process.

## Existing namespace bootstrap to retain

Agent creation already commits the agent and a complete namespace declaration
in one transaction. `seon.agent.home/home-ns-require-specs` is the canonical
structured require vector:

```clojure
[[seon.agent.message :as message]
 [seon.agent.lifecycle :refer [wait complete pause resume terminate]]
 [seon.schema :as schema]
 [seon.db :as db]
 [my.plan :as plan]]
```

`home-ns-form` renders that data as the real `(ns ... (:require ...))` source,
and `require-edges` stores the corresponding program-graph connections. A
per-agent `:seon.eval/home-requires` datom may select a different vector.
Immediately before an eval batch, `seon.eval/setup-agent-ns!` seeds the exact
toolkit refers into the retained self-host compiler state and evaluates this
one namespace form with dependency analysis enabled. The batch then runs with
that namespace as its starting `*ns*`.

Namespace adoption must strengthen this owner rather than bypass it. Moving to
an already existing namespace selects that namespace's declaration on the next
invocation. Renaming a generated namespace to a new deliberate symbol must
atomically keep `:seon.ns/name`, generated namespace source, require edges, and
the agent's namespace ref consistent. The declaration is regenerated from the
structured require data; prior eval forms are not replayed and the shared
compiled program is not rebuilt merely to change the invocation identity.

## Bun invocation context

The selected Bun source implements `node:async_hooks` `AsyncLocalStorage`
directly on JavaScriptCore's async context. Promise reactions snapshot and
restore an immutable context array; native callbacks use Bun's internal async
context frame. Bun deliberately implements this low-impact primitive while
leaving most of the more expensive legacy `async_hooks` surface as partial
stubs. There is no stronger public Bun-specific replacement for invocation-
local identity.

Seon's use of `AsyncLocalStorage.run` is therefore the correct seam, but it is
not durable identity. The database owns the resident namespace; one invocation
reads that current fact, enters the ALS scope, and establishes the matching
ClojureScript namespace. Promise and `await` work inherit it without leaking
between simultaneous agents. The current code has separate ALS instances for
agent identity, transaction context, error configuration, warnings, and print
capture. Consolidation is only a measured simplification candidate: do not
change these correct isolation boundaries without proving lower allocation or
clearer ownership under concurrent async work.

## Discovery and messaging

Namespace facts are the resident-agent directory. Root and ordinary agents can
query all resident agents, their purposes, lifecycle facts, and plans, then
message them without a process registry or root broker.

Sending to a namespace is get-or-create behavior. One transaction resolves the
existing resident or creates the namespace, resident agent, and initial message
atomically. Concurrent sends converge through Datahike uniqueness. Messages
remain ordinary timestamped facts with explicit sender and recipient refs.
There is no stored conversation or thread identity. Agents are told to follow
multiple simultaneous conversations and stay on top of all outstanding work.
The related live-model evaluation is tracked in
`docs/seon/issues/inspect-concurrent-agent-messages.md`.

## Warm generated agents

A configurable small pool may consist of ordinary generated agents whose Bun
children are already ready and have applied the current validated program.
Requesting any agent claims one directly. Requesting a deliberate namespace may
atomically move a warm generated agent to that namespace before its first turn,
while its stable eid and process remain.

Claiming a warm agent triggers asynchronous replenishment to the configured
count. A pool of one costs roughly 170--220 MiB and avoids about 1.4 seconds of
cold assignment on the measured host. Zero remains valid for constrained or
headless deployments. This must reuse the one program-publication and child
supervision mechanisms; a second compiler, package, or worker type is forbidden.

## Dormant and destroyed

A dormant specialist has no live Bun child but retains its namespace, program,
messages, plans, and database facts. A later message resumes the same agent.

Destroy is an explicit action intended for generated one-off agents. It retires
the child immediately and removes agent-local runs, turns, evals, plans,
messages, canvas state, and the generated scratch namespace/program facts. It
does not retract shared domain facts or functions contributed to other
namespaces. Reusing the former generated namespace therefore creates a new
agent rather than resuming the destroyed one.

## Acceptance

- Namespace names store as validated Datahike symbols without keyword
  conversion.
- Unique-ref creation, adoption, collision, history, eid preservation, and
  restart behavior pass focused tests.
- `in-ns` and Promise/`await` hops preserve the distinction between `*ns*` and
  agent identity.
- Root and peer agents discover and message residents by namespace.
- Concurrent send-or-create produces one resident and no lost messages.
- A claimed warm agent performs its first turn without program reacquisition;
  replenishment never blocks that turn.
- Dormancy reclaims process memory and later resumes history; explicit destroy
  reclaims the defined private database graph and later creates fresh state.
- The Inspect task demonstrates whether real models can manage simultaneous
  clearly attributed conversations without additional stored grouping.

---
type: issue
status: active
severity: feature
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

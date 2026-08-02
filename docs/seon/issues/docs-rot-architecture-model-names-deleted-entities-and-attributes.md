---
type: issue
status: open
severity: blocker
tags: [issue, architecture, schema, database, agent]
---

# Reconcile the architecture model with the admitted fresh schema

## Problem

The always-current architecture domain documents still describe the old
`:seon.agent/*`, `:seon.eval/*`, `:seon.route/*`, and remote-protocol model.
The admitted fresh schema has moved the live identities and receipts under
`:seon.cluster.agent/*`, `:seon.cluster.run/*`, and
`:seon.cluster.eval/*`, while routes are currently a Clojure route table.
This is the same failure class as the grants incident: the target teaches a
coherent data model that no longer matches the facts agents can query.

## Evidence

- `docs/seon/architecture/data-model.md:22-48,474-521` identifies agents by
  `:seon.agent/id` and gives them `:seon.agent/run`, parent, termination,
  defaults, home-requires, and schedules. The admitted entity at
  `resources/seon/schema/run.edn:1-22` is
  `:seon.cluster.agent/id` plus optional `:seon.cluster.agent/run`; the
  ownership connection is declared in `resources/seon/schema/agent.edn`.
- `data-model.md:819-833` documents evals as `:seon.eval/*` rows with stored
  `ok?` and error-data. Current receipts are
  `:seon.cluster.eval/*` at `resources/seon/schema/run.edn:24-45,89-111`, and
  state is derived from result/error/interrupted attribute presence.
- `data-model.md:835-861` presents persisted `:seon.route/*` entities as route
  truth. No route schema resource exists; the live route table is
  `src/seon/render/route.clj:4-31`.
- The stale identities spread beyond the table: `architecture.md`,
  `context.md`, and `ui.md` all still use `:seon.agent/id`; agent-runtime,
  laws, observability, and ADR-009 use the superseded
  `:seon.agent.run/process` spelling instead of
  `:seon.cluster.run/process`.
- `data-model.md:206-210` still assigns external crossings to
  `seon.db.protocol` and local replica behavior, but no fresh
  `seon.db.protocol` namespace exists.

The transitive reader surface is the architecture graph itself: root
`AGENTS.md` tells agents to read architecture first; the active runtime runbook
requires the relevant architecture target; architecture, UI, toolkit,
agent-runtime, context, the schema ADRs, and the flow skill all link to
`data-model.md` as attribute authority. Archived `agent-fsm/AGENTS.md` also
calls it the current durable-facts source.

## Owner

`docs/seon/architecture/data-model.md` owns the intended entity/attribute
model, with `resources/seon/schema/*.edn` and fresh transaction/query owners as
the implementation-side falsifier. The other architecture domains own their
copies of those identities.

## Acceptance

- Generate a complete architecture-to-schema census: every claimed current
  attribute either exists in the admitted schema or is explicitly marked
  target/unbuilt with its owning decision.
- Replace superseded agent/run/eval identities throughout all active
  architecture domains in one wave, not only in the tables.
- Route and database-protocol sections state the settled target without
  presenting deleted entities or namespaces as current facts.
- All inbound architecture, skill, and localized-runbook readers are checked
  against the reconciled names.

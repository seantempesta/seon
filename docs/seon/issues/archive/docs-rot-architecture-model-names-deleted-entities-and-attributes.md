---
type: issue
status: resolved
severity: blocker
tags: [issue, architecture, schema, database, agent]
---

# Reconcile the architecture model with the admitted fresh schema

## Problem

The always-current architecture domain documents still described the deleted
`:seon.agent/*`, `:seon.eval/*`, `:seon.route/*`, and remote-protocol model.
The admitted fresh schema moved live identities and receipts under
`:seon.cluster.agent/*`, `:seon.cluster.run/*`, and
`:seon.cluster.eval/*`, while routes are a Clojure route table. This was the
same failure class as the grants incident: the target taught a coherent data
model that did not match the facts agents can query.

## Owner

`docs/seon/architecture/data-model.md` owns the intended entity and attribute
model. `resources/seon/schema/*.edn` plus the fresh transaction and query
owners are the implementation-side falsifier. The other architecture domains
own their copies of those identities.

## Acceptance

- Generate a complete architecture-to-schema census: every claimed current
  attribute either exists in the admitted schema or is explicitly marked
  target/unbuilt with its owning decision.
- Replace superseded agent, run, and eval identities throughout every active
  architecture domain, not only in the tables.
- State route and database boundaries without presenting deleted entities or
  namespaces as current facts.
- Check inbound architecture, skill, and localized-runbook readers against
  the reconciled names.

## Resolution

Resolved by the path-limited architecture-model documentation commit that
contains this note.

- `docs/seon/architecture/data-model.md` is now a census of the admitted
  persistent entities and distinguishes two unsettled target facts:
  `:my/prompt` from owner ruling #24 and the durable host-interop observation
  required by ruling #32. Neither is presented as admitted schema.
- `resources/seon/schema/agent.edn:1-31` verifies agent namespace ownership,
  cluster membership, context links, and the creation request;
  `resources/seon/schema/run.edn:1-23` verifies the agent entity and optional
  current-run ref.
- `resources/seon/schema/run.edn:24-64,69-120` verifies current eval receipts,
  runs, forms, presence-derived receipt state, result blobs, and process
  custody. The architecture set no longer names the deleted agent, eval, or
  route identities.
- `resources/seon/schema/message.edn:1-54` verifies durable messages and their
  agent refs; `resources/seon/schema/context.edn:13-96` verifies prompt
  captures and ordered contribution evidence.
- `src/seon/render/route.clj:5-27` verifies that HTTP route truth is the one
  Clojure route table, including namespace, agent, debug, message, feed, data,
  CSS, and JavaScript routes. No persistent route entity or remote database
  protocol is described.
- `docs/seon/architecture/architecture.md`, `agent-runtime.md`, `context.md`,
  `laws.md`, `observability.md`, `toolkit.md`, and `ui.md` now use the same
  fresh identities and absence-based state model as the census.
- The inbound-reader check found one remaining deleted turn attribute in the
  protected skill corpus and one in a protected PRD runbook. They
  were reported to their owning waves rather than copied into the reconciled
  architecture authority.

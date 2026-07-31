---
type: issue
status: resolved
severity: blocker
tags: [issue, schema, config, render, context]
---

# Context instruction refs have no schema or config owner

## Problem

W1 originally specified `:seon.config/instructions`,
`:seon.cluster.agent/instructions`, and `:seon.cluster.agent/cluster` as plain
ref attributes. Under the packaged-schema derivation, none was installed by
that declaration alone. The config singleton is reconciled as one exact entity,
so a separately transacted instruction set would also be retracted by the next
config apply.

## Evidence

`src/seon/schema/form.cljc:8-73` installs attributes found in entity maps or
carrying persistence facets. `src/seon/schema/edn.clj:66-111` derives the closed
config entity from its two identity fields plus config dials;
`:seon.config/instructions` was neither. The executable probe and observed
`{:dial? false, :entity? false, :installed? false}` result are recorded in
`docs/prds/sci-execution-runtime/research/w1-implementation-notes-2026-07-31.md`.

`src/seon/reconcile.cljc:262-303` retracts current attributes absent from the
desired row, and `src/seon/config.cljc:239-254` supplies only the compiled
config row. The live agent entity schema is
`resources/seon/schema/run.edn:1-24`, outside W1's originally named schema
owners, and did not contain the new agent refs.

## Owner

Context/render W1.

## Acceptance

One ruled owner installs the connections, config apply cannot silently retract
the authoritative instruction set, every agent remains its own entity, agent
creation writes the cluster connection with transaction provenance, and the
production population plus fresh-cluster proof exercises the retained shape.

## Resolution

Owner ruling commit `b2b3e019d` revised spec §2.2 to create a separate
`:seon.cluster/name` identity entity per cluster branch. That entity owns
`:seon.cluster/instructions` and points to the exact-reconciled config singleton
through `:seon.cluster/config`; every independent agent entity points to the
cluster through `:seon.cluster.agent/cluster`. This preserves config's
whole-entity reconciliation and gives the instruction set one schema owner.

The remaining implementation and fresh-cluster evidence belong to W1's active
research note rather than this resolved design-gate issue.

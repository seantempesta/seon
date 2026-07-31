---
type: issue
status: open
severity: blocker
tags: [issue, schema, config, render, context]
---

# Context instruction refs have no schema or config owner

## Problem

W1 specifies `:seon.config/instructions`,
`:seon.cluster.agent/instructions`, and `:seon.cluster.agent/cluster` as plain
ref attributes. Under the current packaged-schema derivation, none is installed
by that declaration alone. The config singleton is also reconciled as one exact
entity, so a separately transacted instruction set would be retracted by the
next config apply.

## Evidence

`src/seon/schema/form.cljc:8-73` installs attributes found in entity maps or
carrying persistence facets. `src/seon/schema/edn.clj:66-111` derives the closed
config entity from its two identity fields plus config dials;
`:seon.config/instructions` is neither. The executable probe and observed
`{:dial? false, :entity? false, :installed? false}` result are recorded in
`docs/prds/sci-execution-runtime/research/w1-implementation-notes-2026-07-31.md`.

`src/seon/reconcile.cljc:262-303` retracts current attributes absent from the
desired row, and `src/seon/config.cljc:239-254` supplies only the compiled
config row. The live agent entity schema is
`resources/seon/schema/run.edn:1-24`, outside W1's named schema owners, and does
not contain the new agent refs.

## Owner

Context/render W1, pending the owner design gate in
`w1-implementation-notes-2026-07-31.md`.

## Acceptance

One ruled owner installs all three connection attributes, config apply cannot
silently retract the authoritative instruction set, agent creation writes the
cluster connection with transaction provenance, and the production population
plus fresh-cluster proof exercises the exact retained shape.

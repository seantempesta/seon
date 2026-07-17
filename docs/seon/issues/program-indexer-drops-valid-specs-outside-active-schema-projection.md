---
type: issue
status: open
severity: friction
tags: [issue, schema, agent, flow]
---

# Make program indexing independent of the active schema projection

## Problem

`seon.client/var->fn-row` validates every compiled function's
`:malli/schema` through Malli's current default registry. A focused or partial
active schema projection can omit a referenced schema even though the function
and its valid compiled contract are present in the artifact. The indexer then
logs a warning and persists the function row without `:seon.fn/spec`.

The indexed program therefore depends on which schema projection happened to
be active before indexing. That makes the same compiled artifact expose
different function contracts and weakens downstream context, instrumentation,
and authored-program publication.

## Evidence

`tmp/test-cljs-20260717-033255-69241.log:1144-1148` runs the focused
`seon.index-core-test` gate successfully but emits two indexer warnings:

- `seon.render.canvas/error-response` loses
  `[:=> [:cat :seon.render.canvas/error-request]
  :seon.render/html-response]`; and
- `seon.state/reconcile!` loses
  `[:=> [:cat :seon.state/reconcile-request]
  :seon.state/reconcile-response]`.

Both functions register valid named request and response forms in their owning
namespaces. The shared failing boundary is
`src/seon/client.cljs:1335-1363`: `var->fn-row` calls `m/schema` against the
current default registry and converts any resolution failure into an omitted
spec. `src/seon/schema.cljc:95-130,376-388` makes that registry reflect the
active projection, while candidate compilation helpers use an explicit
candidate registry.

This is one root cause with two manifestations. Separate function-specific
issues or an allow-list would hide the projection dependency.

## Owner

The compiled-program indexing boundary in `seon.client/var->fn-row`, grounded
in `seon.schema`'s explicit projection/registry mechanics. Function contracts
must be validated against the same complete schema population being indexed,
not whichever runtime projection is active.

## Acceptance

- Indexing the same compiled artifact under a complete and a deliberately
  reduced active schema projection produces identical `:seon.fn/spec` rows.
- `seon.render.canvas/error-response` and `seon.state/reconcile!` retain their
  exact pure-data Malli contracts with no indexer warning.
- A contract with a genuinely missing or non-pure schema reference still
  fails or is reported honestly; the fix does not suppress validation.
- The focused index-core proof covers both valid cross-namespace references
  and one invalid reference without relying on test load order.
- No symbol allow-list, duplicate registry, or per-function workaround is
  introduced.

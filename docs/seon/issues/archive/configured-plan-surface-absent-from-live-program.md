---
type: issue
status: resolved
tags: [issue, plan, rendering]
severity: blocker
---

# Configured plan surface is absent from the live database program

## Evidence

After a clean exact-head restart at `c977e774`, the root Datastar feed renders
the configured plan block as an error card: `The selected function is absent
from the current database program.` The selected HTML symbol is the newly ruled
`my.plan/plan-surface`, which exists in compiled source and passed focused and
complete CLJS gates.

The same feed successfully delivered its full Datastar frame, so this is not a
browser or transport failure. Direct artifact and database probes subsequently
falsified the apparent function-admission diagnosis. The admitted artifact
contains `my.plan.plan_surface`; a fresh process resolves both the direct
global and `seon.eval/lookup-value` as functions; and the boot-owned database
program row exists.

The actual selected symbol is stale persisted agent data. All twelve live
agent-local `:plan` component blocks still decode their `:seon.render/html`
value as the deleted `my.plan.internal/plan-block-html`, while the current
manifest names `my.plan/plan-surface`. Agent context is seed-copied at creation
and existing component entities are intentionally retained, so the source
migration deleted the old function without narrowly migrating its prior
platform default. The generic absent-program error truthfully describes that
stored obsolete symbol but does not identify it in the visible card.

## Acceptance

- An idempotent migration in the existing configuration/state reconciliation
  path changes only a `:plan` block whose decoded HTML symbol is exactly the
  obsolete `my.plan.internal/plan-block-html` default.
- Custom plan renderers, block entity identity, priority, AI renderer,
  unrelated fields, and every other context block remain unchanged.
- A second reconciliation performs no write, newly created agents receive
  `my.plan/plan-surface`, and the live database contains zero obsolete plan
  HTML symbols.
- The root page and server-side feed render the plan surface without an error
  card, with no compatibility registry or hard-coded render branch.
- A missing selected function remains a visible structured error.

## Resolution

Resolved by `25152c33`. Explicit configuration apply now invokes the context
owner's narrow `migrate-plan-surface-default!` after the ordinary
provenance-scoped state reconciliation succeeds. The migration queries only
component blocks carrying both `:seon.agent.ctx/name :plan` and the exact
encoded obsolete HTML symbol, then submits one database-value-fenced
`:db.fn/cas` per match. It does not replace component entities, reseed context,
or touch a customized renderer.

Focused proof:

- `seon.ctx-test` proves the exact selector and old value, component-eid CAS,
  fixed query work/result/weight bounds, and a second apply with zero writes.
- `seon.config-test` proves newly created agents seed `my.plan/plan-surface`
  directly.
- `seon.client-initialization-test` proves configuration apply retains the one
  resolved configuration and managed state reconciliation path.
- `seon.execution.runtime-test` remains green, including the visible
  structured error for a genuinely missing selected function.
- Combined focused gate: 69 tests, 340 assertions, zero failures or errors.

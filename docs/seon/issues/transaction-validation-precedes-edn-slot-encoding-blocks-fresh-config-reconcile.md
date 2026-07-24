---
type: issue
status: open
severity: blocker
tags: [issue, database, config]
---

# Transaction validation precedes EDN-slot encoding during fresh config reconcile

## Resolved transaction boundary

A fresh `bin/seon cluster apply default` applies the page-plan schema pages,
then fails while reconciling the resolved configuration singleton. The
singleton carries the logical vector value for `:seon.eval/home-requires`,
while its database storage schema is intentionally `:string`.

`seon.db/transact!` previously validated the logical transaction value against
that storage schema before `submit-transaction!` called
`encode-edn-slot-values`. The encoder therefore could not make the value
valid: validation had already returned an error.

## Evidence

- Failed apply result:
  `tmp/seon-operator/cluster-apply/92cd2586-e65f-4125-8bdd-f8896c193909.edn`
- Apply passed page-plan/config digest admission, received schema pages, and
  failed with actual value `[[seon.agent.message :as message] …]` versus
  expected schema `:string`.
- The stack terminates at `seon.db.internal/validate-values!` through
  `seon.db/transact!`.
- Commit `a4b8b9d48` moves the one encoding pass into the public transaction
  normalization owner, after coercion/absence normalization and before both
  attribute and value validation. `submit-transaction!` now receives only that
  storage projection and contains no encoder.
- Commit `e35e2344e` makes validation project nested component maps back to
  their logical values solely for Malli checking. It does not create a second
  storage representation or bypass validation.
- The stale `:seon.eval/home-requires :string` registration was restored to
  its real union schema in `src/seon/agent/home.cljc`, so the generic codec
  recognizes it as an EDN slot.
- `src/seon/client.cljs` already uses the public
  `db/encode-edn-slot-values` boundary for initialization data, but
  `reconcile-config!` passes its heterogeneous desired population directly to
  `seon.runtime.state/reconcile!`.

## Proof

- JVM portable contract: 3 tests, 68 assertions, zero failures/errors.
- CLJS portable contract: 3 tests, 68 assertions, zero failures/errors.
- The regression includes a mixed EDN union nested inside a component vector;
  the public transaction reaches transport with the exact `pr-str` projection,
  while malformed logical data still returns a `:user-input` error before any
  transport request.
- Fresh apply result
  `tmp/seon-operator/cluster-apply/9a4a35d8-16d6-4687-afa5-3815203ee82e.edn`
  passed page-plan/config-digest admission and the former
  `:seon.eval/home-requires` failure. It then exposed a separate invalid
  root-context block: `:seon.agent/ctx` contains
  `{:seon.agent.ctx/name :root-role :seon.render/ai nil}` and correctly fails
  its acquired child schema. The refusal is retained; it is not an encoding
  defect and is not fixed by relaxing validation.

## Owner

The transaction normalization owner now establishes the one storage
representation at the validation boundary. The remaining root-context
component input is owned by context resolution/configuration, not by the
database codec or validation gate.

## Acceptance

- Fresh config reconciliation accepts logical `:seon.eval/home-requires`
  vectors and stores the EDN string projection exactly once.
- A malformed logical value is still rejected before writer submission.
- Repeated reconciliation decodes current storage values and submits no
  transaction when converged.
- Fresh `bin/seon cluster apply default` succeeds once the separate
  root-context component input is repaired, followed by all five default
  processes reaching readiness.

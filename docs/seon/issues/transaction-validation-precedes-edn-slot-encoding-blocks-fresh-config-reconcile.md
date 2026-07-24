---
type: issue
status: open
severity: blocker
tags: [issue, database, config]
---

# Transaction validation precedes EDN-slot encoding during fresh config reconcile

## Problem

A fresh `bin/seon cluster apply default` applies the page-plan schema pages,
then fails while reconciling the resolved configuration singleton. The
singleton carries the logical vector value for `:seon.eval/home-requires`,
while its database storage schema is intentionally `:string`.

`seon.db/transact!` validates the logical transaction value against that
storage schema before `submit-transaction!` calls
`encode-edn-slot-values`. The encoder therefore cannot make the value valid:
validation has already returned an error.

## Evidence

- Failed apply result:
  `tmp/seon-operator/cluster-apply/92cd2586-e65f-4125-8bdd-f8896c193909.edn`
- Apply passed page-plan/config digest admission, received schema pages, and
  failed with actual value `[[seon.agent.message :as message] …]` versus
  expected schema `:string`.
- The stack terminates at `seon.db.internal/validate-values!` through
  `seon.db/transact!`.
- `src/seon/db.cljc` validates `tx-data` before
  `submit-transaction!`; the latter is the point that encodes EDN slots.
- `src/seon/client.cljs` already uses the public
  `db/encode-edn-slot-values` boundary for initialization data, but
  `reconcile-config!` passes its heterogeneous desired population directly to
  `seon.runtime.state/reconcile!`.

## Owner

The transaction normalization owner must establish one representation at the
validation boundary. Either the public transaction path encodes exactly once
before storage-schema validation, or config reconciliation supplies encoded
desired database values before diff and transaction compilation. The fix must
preserve logical-value comparison and must not disable schema validation.

## Acceptance

- Fresh config reconciliation accepts logical `:seon.eval/home-requires`
  vectors and stores the EDN string projection exactly once.
- A malformed logical value is still rejected before writer submission.
- Repeated reconciliation decodes current storage values and submits no
  transaction when converged.
- Fresh `bin/seon cluster apply default` succeeds, followed by all five default
  processes reaching readiness.

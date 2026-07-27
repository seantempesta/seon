(ns seon.reconcile
  "Declared configuration converges into database facts: the pure exact
  diff and its one apply operation.

  CONTRACT LAYER (orchestrator-authored, 2026-07-27 — B2 wave, from
  b2-plan §7; the algorithm is quarried from State A's provenance-scoped
  reconciler and simplified by the writer's serial execution). The
  schemas and function contracts are SEALED once the sealed suite
  lands: test/seon/reconcile_test.clj is NOT YET AUTHORED (it needs the
  fresh provenance attributes — :seon.db/user/:seon.db/process — which
  arrive with the config package). No implementation lane starts before
  that suite is committed.

  The model:

  - The managed slice is defined by PROVENANCE, never a taxonomy: an
    entity is managed when its identity's first assertion carries the
    managing process identity in its transaction metadata, or when its
    identity was explicitly adopted. Reconcile takes no kind argument
    and runs in attribute space.
  - Every desired entity map carries exactly ONE identity attribute —
    the upsert handle. Zero or two refuse, never guess.
  - `plan` is PURE over a database value: the exact tx-data that
    converges the managed population onto the desired one — changed
    attributes retracted and re-asserted, absent-from-desired managed
    entities retracted entirely. An EMPTY plan means converged.
  - CONVERGED = ZERO TRANSACTIONS. Datahike commits a transaction
    entity even for empty tx-data, so `reconcile!` computes the plan
    FIRST and issues NO transaction when it is empty. The observable
    acceptance fact: `:max-tx` is identical before and after a
    converged re-apply.
  - A non-empty plan recomputes INSIDE the writer via
    `[:db.fn/call #'reconcile-call request]` — the N2 idiom. There is
    no stale basis inside the serial writer, so State A's three-attempt
    retry is deleted, not ported.
  - A desired identity already owned by an entity OUTSIDE the managed
    scope refuses loudly, never silently adopts.
  - Drift repair is not a feature: a hand-edited fact diverges from
    desired and the next apply converges it. Nothing detects drift;
    reconcile just converges.

  Crash walk: `plan` is pure; `reconcile!` is ONE atomic transaction —
  a kill leaves it fully applied or absent, and re-apply converges
  either way."
  (:require [seon.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; Schemas
;;; ---------------------------------------------------------------------------

; the managing process identity that scopes the managed population;
; opaque here — B2's config owner supplies the one core identity
(schema/register! ::process [:string {:min 1}])
(schema/register! ::desired [:vector [:map]])
; identities the caller explicitly adopts into the managed scope even
; though their first assertion predates management
(schema/register! ::adopt-identities [:set [:vector :any]])
(schema/register! ::converged? :boolean)
(schema/register! ::operations [:int {:min 0}])

(schema/register!
 ::request
 [:map {:closed true}
  [::desired ::desired]
  [::process ::process]
  [::adopt-identities {:optional true} ::adopt-identities]])

(schema/register!
 ::result
 [:map {:closed true}
  [::converged? ::converged?]
  [::operations ::operations]])

;;; ---------------------------------------------------------------------------
;;; Contracts
;;; ---------------------------------------------------------------------------

(defn plan
  "The exact tx-data converging `db` onto the desired population.
  Pure. Empty vector = converged, and the caller must then issue NO
  transaction. Refuses `::no-identity` / `::two-identities` (a desired
  map without exactly one registered identity attribute),
  `::duplicate-identity` (two desired maps with one upsert handle), and
  `::identity-outside-scope` (a desired identity already owned by an
  entity whose provenance is neither the managing process nor an
  adopted identity)."
  {:malli/schema [:=> [:cat :any ::request] [:vector :any]]}
  [db request]
  (throw (ex-info "awaits implementation" {::fn `plan})))

(defn reconcile!
  "Apply the plan through the one connection, converged = zero writes.
  Computes `plan` against the connection's current value first; an
  empty plan issues NO transaction and returns
  {::converged? true ::operations 0} — `:max-tx` provably unchanged.
  A non-empty plan commits exactly one transaction that recomputes
  inside the writer via `[:db.fn/call #'reconcile-call request]`,
  returning {::converged? false ::operations n}. Refusals are `plan`'s,
  surfaced before any transaction when the pre-check already sees them
  and atomically from inside the writer otherwise."
  {:malli/schema [:=> [:cat :any ::request] ::result]}
  [connection request]
  (throw (ex-info "awaits implementation" {::fn `reconcile!})))

(defn reconcile-call
  "The in-writer recomputation — the N2 transition idiom.
  Invoked as [:db.fn/call #'reconcile-call request]: one pure function
  of the mid-transaction database value returning the final tx-data."
  {:malli/schema [:=> [:cat :any ::request] [:vector :any]]}
  [db request]
  (throw (ex-info "awaits implementation" {::fn `reconcile-call})))

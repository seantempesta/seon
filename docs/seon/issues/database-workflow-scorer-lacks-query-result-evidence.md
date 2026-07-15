---
type: issue
status: open
severity: blocker
tags: [issue, agent, database, flow]
---

# Prove database workflow answers from retained query evidence

## Problem

The strengthened database-workflow scorer proves the requested schemas,
transaction contents, later query shape, and both reports. The composition door
intentionally omits eval result bodies, so native `.eval` evidence cannot yet
distinguish an answer computed from the real query result from the same answer
re-derived directly from the prompt. It also cannot distinguish a successful
eval of `db/transact!` that returned `{:seon.db/ok? false ...}` from a committed
transaction.

The deterministic source-only falsifier and complete design are in
[[../../prds/agentic-tool-refinement/research/database-query-result-evidence-audit-2026-07-15]].

## Owner

Extend the existing `seon.db` AsyncLocalStorage read-observation mechanism into
ordered per-eval database-operation evidence. Persist full normalized
observations through the existing blob tier, project bounded descriptors from
the door's final immutable database value, and make the one native scorer fail
closed. Do not restore arbitrary writer REPL forms, expose answer keys to the
pod, or copy unbounded eval results into the response.

## Acceptance

- The scorer consumes a bounded, retained proof derived from the exact final
  database coordinate and request eval set.
- The proof establishes all five stored facts and the value returned by the
  later threshold query, with exact eval/turn identity and ordering.
- A failed compact transaction envelope fails even when the enclosing eval
  completed successfully.
- Large values remain blob-addressed and agent-visible diagnostics stay
  bounded; no stack, source dump, or general database backdoor is introduced.
- Offline good/bad fixtures and one admitted live sample prove a correct query
  result passes while prompt-only arithmetic, wrong stored facts, or a query
  result from another turn fails.

## Operation-capture checkpoint — 2026-07-15

The first internal boundary is implemented in the existing database observer.
One awaited `AsyncLocalStorage` scope now retains ordered query, pull, index,
and transaction operations for an eval. A transaction is observed only after
its envelope resolves, so `:seon.db/ok? false` remains an explicit operation
failure even when the Clojure eval succeeds. Every observation carries a
zero-based position and the actual read or committed database coordinate;
nested scopes compose, concurrent fibers remain isolated, and an older
historical coordinate is foreign rather than current-attachment evidence.

The normal eval path passes the nonempty normalized vector to `record-eval!`
after auto-await and before recorder persistence. It deliberately does not yet
register an eval attribute or call `my.blob`: the next owner must put the
canonical bytes through the one blob tier and attach that ref in the existing
eval transaction. Until that persistence, composition-door projection,
fail-closed scorer consumption, native-log read-back, and the admitted live
sample land together, this issue remains open.

Focused proof is green:

- `seon.db.read-observer-test`: 14 tests, 119 assertions; and
- `seon.eval.promise-ergonomics-test/eval-hands-awaited-database-operations-to-the-recorder`:
  one test, seven assertions.

The remaining persistence owner is the existing `my.blob` content-addressed
tier plus the eval schema/transaction builder in `seon.eval`. It must write one
canonical observation vector, attach its blob lookup ref in the same accepted
eval transaction, and preserve attribute absence for an eval with no database
operations. No downstream door or scorer may consume this process-local value
until that link exists.

## Persistence checkpoint — 2026-07-15

`record-eval!` now publishes every nonempty ordered operation vector as
byte-stable, round-trippable EDN through the one `my.blob` authority and stores
`:seon.eval/database-operations-blob` as a lookup ref in the same accepted
allocation transaction as the eval identity. Canonical serialization orders
maps and sets by type-tagged canonical bytes without rebuilding them through a
comparator collection, so distinct entries cannot be collapsed. Lists,
vectors, sets, mixed map keys, and normalized scalar tags retain their EDN
types on read-back.

An eval with no database operations has no evidence attribute. A blob
publication failure records the eval as failed with a bounded core error and no
evidence ref; it cannot masquerade as a successful query. A captured
`:seon.db/operation-ok? false` transaction remains false in the full retained
vector even when the database function call itself returned normally.

Focused proof reads the blob hash from an immutable database value, verifies
that the eval identity datom and evidence-ref datom share one transaction,
round-trips the complete ordered vector, preserves failed transaction evidence,
and proves absence for an ordinary arithmetic eval. Composition-door
projection and Inspect scoring remain the next clean boundary; this issue stays
open until their offline discrimination fixtures and admitted live sample are
green.

The exact persistence gate is green: three focused tests, ten assertions. It
also proves that a publication failure records an eval failure without a blob
ref and that two equivalent mixed-key/set values constructed in different
orders produce identical bytes before round-trip.

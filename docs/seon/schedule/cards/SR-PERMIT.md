---
title: "SR-PERMIT: a blob's permit is held until its transaction exits"
type: reference
status: ready after SR-P, led-cacheb, blob-konserve
created: 2026-10-08
---

# SR-PERMIT: A blob's permit is held until its transaction actually exits

Ledger row SR-PERMIT (group E). Source: `storage-audit/storage-audit-astra.md` finding 2. Correctness. Model: **Opus**
(the write path every recording takes). CLAUDE.md: "Timeout or cancellation is not termination: retain ownership and
resources … until exit."

## The defect

`seon.blob/with-publication!` (HEAD `src/seon/blob.clj:303-323`) acquires Datahike's reachability permit
(`datahike.gc-guard/acquire-reachability-permit!`, `reference-code/datahike/src/datahike/gc_guard.cljc:190`),
publishes the staged blobs, calls `commit-roots!`, and releases the permit in `finally` (`:281`). `commit-roots!`
ends in `seon.db/transact-call` (`src/seon/db.clj:5706-5776`), which on its write bound returns the diagnostic
`:seon.db/transaction-outcome-unknown true` while Datahike's writer still holds the queued transaction (`pending`,
a `datahike.tools/throwable-promise` from `datahike.writer/transact!`, `writer.cljc:427-442`). The permit is then
released before the write exits; a collection on another connection to the store (it takes the exclusive sweep
permit) can sweep the blob, and the fact naming it commits afterwards: a fact pointing at nothing.

## Decided shape (no new lock, no retry)

- One dynamic Var in `seon.db`: `(def ^:dynamic *on-write-exit* nil)`, docstring "A no-argument function `transact!`
  calls once when a write it stopped waiting for actually exits; nil when no caller holds a resource across the
  write." (AGENTS.md: per-evaluation dynamic state uses `binding`.)
- `transact-call`, in its timeout branch only (`db.clj:~5750`, `(identical? timeout report)`): when `*on-write-exit*`
  is bound, start one virtual thread that derefs `pending` (catching and ignoring its throwable: the outcome is
  already reported as unknown) and then calls the hook: `(Thread/startVirtualThread (bound-fn [] (try @pending (catch Throwable _)) (hook)))`.
  The diagnostic gains `:seon.db/exit-owned true` so the caller knows the hook will run.
- `with-publication!`: bind `seon.db/*on-write-exit*` to a function that releases this permit, around
  `(commit-roots!)`; in `finally`, release the permit only when the result does not carry `:seon.db/exit-owned true`
  (otherwise the hook releases it on actual exit). The release runs exactly once on every path.

## Files

- `src/seon/db.clj`, `src/seon/blob.clj`
- `resources/seon/schemas/seon.db.edn` (`:seon.db/exit-owned` `:boolean`, an optional member of the outcome-unknown
  error variant; find the variant by `rg -n "transaction-outcome-unknown" resources/seon/schemas`)
- `test/seon/blob_publication_test.clj`

## Contracts

- `seon.db/*on-write-exit*`: a dynamic Var, no contract.
- `seon.blob/with-publication!`: unchanged (`[:=> [:cat :seon.db/connection [:sequential :seon.blob/staged-write] [:fn clojure.core/fn?]] [:or :seon.schema/value :seon.db/error-result]]`).

## Proof

New `seon.blob-publication-test/a-timed-out-commit-keeps-its-blob-until-it-exits` (an isolated store, as the file's
existing test; fixture observation as there):

1. Set the branch's `:seon.config.db/write-time-limit-ms` to 1 and stall the writer: queue a transaction whose
   `:db.fn/call` sleeps 500 ms (or whatever the existing writer tests use to delay a commit; read
   `test/seon/db_test.clj` for one), so the next write times out.
2. `with-publication!` a staged blob whose `commit-roots!` transacts a fact naming it: the answer carries
   `:seon.db/transaction-outcome-unknown` and `:seon.db/exit-owned`.
3. Immediately run a collection on the store's other connection: it waits for the permit (assert it has not
   completed while the write is pending, e.g. it returns after the writer's delay).
4. After both finish: the fact is committed and `seon.blob/get` of its digest answers the content.

Also: a refused transaction (an invalid fact) releases the permit at once and leaves no fact; the staged blob is an
ordinary orphan collection reclaims. The existing
`seon.blob-publication-test/publication-and-collection-are-exclusive-in-both-orderings` stays green.

`--test seon.blob-publication-test --test seon.blob-test --test seon.db-test`

## Cost

F: none on the normal path (one unbound dynamic Var read); on a timed-out write, one virtual thread until it exits.

## Commit

`bin/lane commit <lane> -m "SR-PERMIT: a blob's reachability permit is held until its transaction actually exits" --test seon.blob-publication-test --test seon.blob-test --test seon.db-test src/seon/db.clj src/seon/blob.clj resources/seon/schemas/seon.db.edn test/seon/blob_publication_test.clj`

## Dependencies

SR-P (same file, deletes `put!`); led-cacheb (`db.clj`) and blob-konserve (`blob.clj`) release first.

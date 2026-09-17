---
type: issue
status: open
severity: blocker
created: 2026-09-17
tags: [issue, write-admission, seon.db, bounded-execution, publication, class/p1]
---

# Final-report validation runs unbounded on the writer thread and wedges a complete publication

Observed 2026-09-17 on HEAD `ca8fd63b9`, running
`bin/test-fast --paths src/seon/cluster/source.clj -- seon.cluster.source-test`
(exact HEAD: the runner printed an EMPTY snapshot-difference body).

`seon.cluster.source-test/incremental-first-party-publication-retains-complete-scalar-rows`
made no reporter progress for 300 seconds; the suite liveness watchdog dumped
every JVM and exited 124.

```
bin/test: SUITE LIVENESS BUG
bin/test: no reporter progress for 300 seconds
bin/test: last-progress BEGIN test …/incremental-first-party-publication-retains-complete-scalar-rows
```

The dump names the Datahike WRITER thread, not the test thread:

```
datahike.writer$create_thread… → datahike.writing/transact! → datahike.core/with
  → datahike.db.transaction/transact-tx-data (transaction.cljc:1218)
  → datahike.db.transaction/validate-report (transaction.cljc:1212)
  → seon.db/write-report-validator (src/seon/db.clj:3049)
  → seon.db/write-report-error   (src/seon/db.clj:3032)
```

while `main` blocks in `datahike.api.impl/transact`'s deref.

## Why it is unbounded

`write-report-error` (`src/seon/db.clj:3009`) takes
`(distinct (map :e (concat attempted (:tx-data report))))` and, for EVERY
affected entity, pulls the entity's datoms (`write-entity-value`) and runs a
Malli validation against each schema its identity attributes select. A complete
first-party publication carries ~39 458 entities and ~35 835 keyword facts
(measured in `complete-program-publication-is-refused-on-a-cardinality-many-set`).
That work runs inside the serial writer loop, with no declared bound, so a
publication-sized transaction stalls every writer on that connection.

It is slow, not deadlocked. A second run of the same namespace on the same
snapshot (HEAD `ad6fe0fdd`, `PHASE test-slot elapsed-seconds=26`, three slots
less contended) completed the same test in 196 seconds — `BEGIN` 01:40:33.578Z,
`END` 01:43:49.229Z — where the first run, launched at a 1233-second slot wait
with three gates resident, crossed the 300-second watchdog. So the bound that
fires is machine load, which is exactly the property a declared bound is
supposed to remove: the same transaction is a pass or a suite-killing hang
depending on what else is running.

This breaks both halves of AGENTS.md §2.3: the work has no bound at the seam
that admits it, and the failure it produces is a HANG — the watchdog names the
thread but nothing names what never finished. A hang is a worse defect than a
failure.

## Where the decision belongs

`src/seon/db.clj` — the validator seam introduced in `35c5d2fa8`. The shape of
the fix is a design decision for the write-admission owner, not a tuning knob.
Options worth pricing, cheapest constraint first:

1. Validate only the entities whose IDENTITY-bearing datoms this transaction
   asserted, rather than every affected entity. A publication asserts each row
   once, so the set is the same size, and this alone does not bound it.
2. Derive the per-entity validators once per transaction (they are already
   projection-cached) and skip entities whose asserted attributes cannot change
   a required-key verdict — a publication's rows are complete by construction.
3. Give the seam a declared bound and report the entity count that exceeded it
   as a typed refusal naming the transaction, so the failure is loud rather than
   a wedge, whichever of 1 or 2 lands.

## Boundary

Found while fixing the source seal refusal
([note](../../prds/steward-platform/research/source-seal-refusal-2026-09-17.md)).
`src/seon/db.clj` is held by the codex integrator, so that lane wrote the
evidence and stopped rather than editing it. `seon.cluster.source-test` cannot
reach a green tally until this is resolved.

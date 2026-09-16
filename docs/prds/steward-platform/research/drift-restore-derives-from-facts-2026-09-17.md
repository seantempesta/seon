# The drift restore derives from facts

Dated 2026-09-17. Lane: drift-restore. Branch `steward-platform`.
Commit `c79a157fd`.

## What was wrong

`seon.test.runner/restore-live-cluster-schema!` (added `f3b61b975`)
snapshotted every live cluster's schema-projection key set before an
in-process run and, afterwards, put the ENTERING VALUE back whenever the key
set had changed. On 2026-09-17, pid 88182, a live probe turn deliberately
retracted four `:probe*` declarations from `default` between that snapshot
and the run's end. The restore reasserted them into the live projection, so
the cluster's projection disagreed with its own committed facts until an
explicit `env/advance-projection!` from `seon.schema/projection-from-database`
repaired it by hand.

A restore that reasserts what the authority has retracted is a seam acting on
a mirror its authority will re-decide (AGENTS §2.1).

There were also TWO observers of the same state. `ambient-snapshot` carried
`::snapshot-schema-keys`, a bare before/after key-set diff over every running
cluster, and `run-var!` turned any difference into a test error. A
before/after diff cannot distinguish a run's leak from a committed
retraction, so in-process the two owners would have answered the same event
differently.

## What it is now

`restore-live-cluster-schema!` derives.

- The snapshot (`live-cluster-schema-states`) hands each cluster's projection
  state atom AND the environment carrying its `:seon.db/connection`. The
  connection is a VALUE the caller supplies; nothing is re-read from the
  operator at restore time.
- After the run, the projection is derived with
  `(schema/projection-from-database (db/db connection) entering-projection)`
  and installed through `env/advance-projection!` at `(db/basis-t database)`
  — the same seam adoption (`src/seon/cluster.clj:2228`) and evaluation
  (`src/seon/sci/eval.clj:640`) advance through. ONE restore path.
- REUSE IS TAKEN FROM THE ENTERING PROJECTION, NEVER THE EXIT ONE.
  `projection-from-rows` decides reuse by comparing the queried rows'
  fingerprint with the fingerprint the reusable value CARRIES
  (`src/seon/schema.clj:2449`). A projection a run edited in memory still
  carries the fingerprint of the rows it was built from, so reusing it lets
  the run's own edit answer the question the facts must answer. Measured
  live: passing the mutated exit projection returned it unchanged and
  reported nothing — the defect reproduced inside the fix. Passing the
  entering projection is correct in every case and still cheap.
- The entering key set remains, as EVIDENCE FOR NAMING. Every difference
  between the run's exit key set and the derived one is classified by whether
  the entering set held that key:

  | at exit | in facts | entering | verdict |
  |---|---|---|---|
  | yes | no | no | `::drift-added` — the run registered it, committed nothing; restored away |
  | yes | no | yes | `::committed-removed` — a committed transaction retracted it; STAYS retracted |
  | no | yes | yes | `::drift-removed` — the run dropped it in memory; restored back |
  | no | yes | no | `::committed-added` — a committed transaction added it |

- `schema-restore-drift` is the one place a row becomes a verdict.
  `seon.test/run` counts a test error only for the drift rows and names a
  committed change on the way past, so the writer doing its job during a run
  is never a test failure.
- A snapshot carrying no connection has no authority to derive from: that is
  `::schema-authority-unavailable`, a typed unknown carrying the reason,
  never silence (AGENTS §2.4).
- `::snapshot-schema-keys` and `live-cluster-schema-keys` are DELETED from
  the ambient drift detector. In a `bin/test` worker there are no live
  clusters, so the member was always `#{}` there; in-process it is now owned
  by the restore. `::snapshot-instrumented` is untouched.

## Measured, live, on pid 88182 (`default`)

Derivation cost, `schema/projection-from-database` against `default`'s own
connection with the live projection reused: 520 ms and 550 ms on consecutive
warm calls, 2745 declaration keys, returning the reused value `identical?`
(no rebuild). That is the cost of one in-process `seon.test/run`, not of one
assertion.

Behaviour, exercised against `default`'s real facts with throwaway state
atoms (`default`'s own projection was never written):

- a key added to the exit projection only: reported
  `{:seon.cluster/name "probe" :seon.test.runner/drift-added [":probe.candidate/extra"]}`
  and the state ended `identical?` to the facts-derived projection;
- a key dropped from the exit projection: `::drift-removed [":inst"]`, restored;
- no change: `[]`, state untouched;
- no connection: `::schema-authority-unavailable` with its reason string.

After the change, `default`'s own projection agrees with its own facts:
`(identical? (projection-from-database db live) live)` is true, 2745 keys.

## In-process regression runs (pid 88182, one per daemon thread)

Namespace reloaded through `#'seon.test/with-test-loader` first;
`seon.test-support` was NOT reloaded.

| test | result |
|---|---|
| `seon.test.runner-test/a-committed-retraction-survives-the-restore-and-is-named-a-committed-change` | 10 pass, 0 fail, 0 error |
| `seon.test.runner-test/a-registration-that-committed-nothing-is-restored-away-and-named-drift` | 5 pass, 0 fail, 0 error |
| `seon.test.runner-test/a-run-that-touched-no-declaration-reports-nothing` | 5 pass, 0 fail, 0 error |

All three run on the canonical fixture (`test-support/with-database`) with
contracts armed (`restore-live-cluster-schema!` confirmed present in
`seon.instrument/instrumented` after adoption). The first test commits BOTH
directions during the run — it declares a synthetic key, then retracts that
declaration's `:seon.schema/form` and declares another — so the committed
retraction and the committed addition are proven against real datoms, not a
hand-shaped projection.

## Verification boundary

- Proven: the three regressions above, in-process, on the canonical fixture,
  with contracts armed; the live measurements on `default` listed above.
- NOT run by this lane: `bin/test`. The gate request is
  `tmp/orchestrator/gate-requests/drift-restore.txt`.
- Foreign breakage met and NOT repaired (PROTECTED, another lane's file):
  `bin/seon init --dev default --changed …` completed publication,
  development reload, SCI acquisition and JVM instrumentation, then threw
  `ArityException: Wrong number of args (0) passed to
  seon.cluster/current-source-snapshot` from `src/seon/cluster.clj`. The
  loaded definitions and instrumentation for this lane's files WERE adopted
  (verified by resolution: `seon.test.runner/schema-restore-drift` resolves,
  the deleted `live-cluster-schema-keys` does not), but the adoption commit
  id may not have been recorded.

## One hunk this lane did NOT touch, and must be retired

`test/seon/test_support_test.clj:520-554`, the deftest
`a-synthetic-schema-registration-leaves-the-registry-byte-identical`. It is
PROTECTED (the shared-base lane is live in that file), so this lane stopped
at it.

It asserts the SUPERSEDED contract, and it will be red:

```clojure
state (atom {:seon.db/basis-t 1
             :seon.schema/projection
             {:seon.schema.projection/forms {:seon.test-support-test/kept :string}}})
before {"probe-cluster" [state @state]}
...
(is (= [(str probe-key)] (:seon.test.runner/drift-added (first restored))))
(is (= (:seon.schema/projection entering) (:seon.schema/projection @state))
    "the registry is byte-identical to the one the run entered with")
```

The snapshot it builds carries no `:seon.db/connection`, so the restore has
no authority to derive from and answers `::schema-authority-unavailable`
instead of `::drift-added`; and "byte-identical to the one the run entered
with" is the very promise this change removes — the run exits to the one its
FACTS declare. Its replacement is
`seon.test.runner-test/a-registration-that-committed-nothing-is-restored-away-and-named-drift`,
which makes the same assertion against a real connection. The owner of
`test_support_test.clj` should delete that deftest.

---
type: issue
status: resolved
severity: blocker
tags: [issue, runtime, agent, database, live-drive]
---

# Mark the run a restart interrupted, so recovery is provable from facts

## Problem

The crash model's stated guarantee is: reopen the store, **mark dangling
receipts `:interrupted`**, re-derive the graph. Recovery does the first and
third. It does not do the second.

`:seon.cluster.eval/interrupted-at` is declared in the live schema and carries
**zero datoms** cluster-wide, across a cluster that has actually been restarted
mid-turn. Recovery closes the dangling run, reports a count on the boot
instance value, and writes nothing durable saying the run was cut off.

The consequence is not a lost turn — it is a lost distinction. A run
interrupted by a process replacement and a run that ended normally with an
unevaluable form produce **identical facts**: closed, no error, one form, no
receipt. "Which turns did that restart interrupt?" is not answerable by query,
which is the property this project treats as the defect itself. The only
evidence lives in `:seon.boot/recovered-runs` on the process-local instance
value, and that dies with the next JVM.

## Evidence

Cluster `default`, whole-system-arc observer lane, 2026-08-08. Authorized
stage-3 restart: pid 31475 exits 10:04:46Z, pid 48613 serves its first request
10:05:17Z (71 s unavailable).

Recovery reported, on the new instance value:

```clojure
{:seon.boot/recovered-runs 1
 :seon.boot/recovery-operations 1
 :seon.boot/ready-ms 6221}
```

The run it recovered, pulled from the database afterwards:

```clojure
{:id        "945f3226-e46c-44c0-b3a5-e8546ec316b2"
 :agent     "root"
 :opened    "2026-08-08T10:03:57Z"     ; before the restart
 :closed    "2026-08-08T10:04:55Z"     ; after the old JVM exited — closed by recovery
 :process   nil                         ; custody correctly shed
 :run-error ""                          ; no error recorded
 :forms     [{:ord 0 :src "; <assistant1>I'm checking the facts before answering …"}]
 :receipts  []}                         ; no receipt, no interrupted-at
```

Cluster-wide at the same basis:

```clojure
{:declared-interrupt-attrs [:seon.cluster.eval/interrupted-at
                            :seon.effect/interrupted-at
                            :seon.maintenance.receipt/interrupted-at]
 :n-evals-with-interrupted-at 0}
```

Compare two runs that closed *normally*, before the restart, with no
interruption involved (`b0f70394`, `91967e81`): each also has one form, zero
receipts, no error, and no marker. Nothing in the database separates them from
`945f3226`.

Everything else about the recovery was clean and should not be disturbed by the
fix: all four agents survived, 26 runs / 105 forms / 102 receipts / 22 messages
carried forward, no run was left open, and no run retained the dead JVM's
`:seon.cluster.run/process`.

## Resolution

`:seon.cluster.run/interrupted-at` is declared in
`resources/seon/schemas/seon.cluster.run.edn` and asserted by
`interrupt-stamps` in `src/seon/cluster/run.clj`, so BOTH paths that recover a
dead process's custody — the boot recovery phase (`recover-call`, reached from
`seon.cluster/recover-runs!`) and takeover-on-claim (`claim-call`) — leave the
same durable evidence. Presence is the state; there is no status label.

The stamp is not derivable and is therefore not stored-derived state: a process
that died before its first receipt row existed leaves nothing to derive from,
which is exactly the observed run's shape. `seon.cluster.run/render-ai` now
reads the fact instead of guessing from a missing receipt — the observed run
would have rendered "It completed."

What this deliberately does NOT do: recovery does not invent a receipt row for
a form that never started. "It was interrupted at form N" (a stamped receipt)
and "the process holding it died before any form settled" (the run stamp alone)
are different facts and stay different.

## Diagnosis, for the record

Recovery was already stamping dangling RECEIPTS correctly — that mechanism was
never broken. The gap was one level up. Probed in-memory before the fix
(`tmp/recovery-probe/probe_recovery_marker.clj`): a recovered run with one
started receipt and a recovered run with no receipt at all both came back as
`{closed-at, id, opened-at, opening-commit-id}` — no marker on either run.

ROTATION CHECK on the observer's census, and it matters: `d/datoms db :avet
:seon.cluster.eval/interrupted-at` returns 0 for a database that a full query
proves holds 1. The observer's "zero datoms cluster-wide" may itself have been
the `:avet` trap they documented in their own method notes, so that number is
not load-bearing. The defect stands on the run-level evidence instead, which is
independent of how the receipts were counted.

## Scope note

The entanglement with
[Settle a receipt for every recorded run form](a-runs-last-form-can-close-without-a-receipt.md)
is dissolved rather than waited on. The two causes of "no receipt" are now
separable without fixing the receipt gap first: a run recovery cut carries
`:seon.cluster.run/interrupted-at` and a run that closed normally never does,
whatever its receipts do or do not say. The receipt gap remains open on its own
merits.

## Acceptance

- A run that a process replacement interrupted carries a durable fact saying so,
  distinguishable by query from a run that closed normally.
- Any form that was mid-evaluation when the process died settles a receipt
  carrying `:seon.cluster.eval/interrupted-at`, rather than no row.
- "Which runs did the last recovery interrupt, and which forms were in flight?"
  is answerable from the database alone, with no reference to a process-local
  boot value.
- One class regression proves it: open a run, kill the process mid-turn, reopen,
  and assert the interrupted run and its in-flight form are both marked — not
  merely that the run is closed.

All met. Proof:

- `seon.cluster.boot-test/a-dead-holders-run-is-unclaimed-by-the-time-start-returns`
  — the existing live recovery proof, STRENGTHENED rather than duplicated (a
  second live restart test would have been the second mechanism this repository
  forbids). It already booted a real cluster, seeded a run held by a process
  that will not exist afterwards plus a dangling receipt, stopped, and
  restarted. It now also seeds the CONTROL in the same generation — a run that
  closed the ordinary way — and asserts after the restart that
  `:seon.cluster.run/interrupted-at` selects exactly the crashed run, that the
  clean run and the bootstrap runs carry none, and that the run renders as
  interrupted.
- `seon.cluster.run-test/recovery-marks-a-run-that-settled-no-receipt` — the
  observed zero-receipt shape, plus the negative, plus the render.
- `seon.cluster.run-test/transitions-agree-with-the-model` — the state-machine
  invariant now checks `::interrupted-at` presence against the pure model after
  EVERY command, so the takeover-on-claim path is covered by the same oracle.

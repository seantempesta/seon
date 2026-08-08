---
type: issue
status: open
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

## Scope note

This issue is entangled with
[Settle a receipt for every recorded run form](a-runs-last-form-can-close-without-a-receipt.md)
and probably cannot be proven closed before it. While a form can silently
settle no receipt, "no receipt" has two possible causes and an interruption
marker is the only thing that could tell them apart — so the receipt gap is
what makes this one unfalsifiable rather than merely unproven.

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

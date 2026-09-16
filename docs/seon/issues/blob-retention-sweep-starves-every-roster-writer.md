---
type: issue
status: open
severity: blocker
tags: [issue, storage, blob-storage, store-perf, bounded-execution, exclusive-sweep, wave/exclusive-sweep, wave/store-perf]
---

# The once-a-minute blob retention sweep starves every roster writer

## Problem

`seon.blob.retention/reclaim!` acquires the store's EXCLUSIVE sweep permit
(`src/seon/blob/retention.clj:38`) and then, still holding it, enumerates the
entire konserve key space to find the binary keys (`inventory`,
`src/seon/blob/retention.clj:11-29`). Its schedule is `"* * * * *"` — once per
minute (`src/seon/schedule.clj:71-75`).

On the shared development root that walk now costs more than its own period, so
the sweep permit is held essentially all the time. Measured live against
`default` (pid 53378) on an idle machine, 2026-09-16:

```
{:konserve-keys 380285 :binary-keys 3624 :k-keys-ms 64580}
```

64.6 s to enumerate 380,285 keys in order to select 3,624 blobs. Sampling the
store's reachability gate every 500 ms for 70 s with the non-queuing
`datahike.gc-guard/try-reachability-permit!`:

```
{:n 139 :sweep-in-progress 134 :admitted 5}    ; 96.4% of wall time closed
```

Every roster operation waits behind that gate, without a bound:
`datahike.api/branch!` acquires a `:roster` reachability permit
(`reference-code/datahike/src/datahike/versioning.cljc:227-231`) and
`acquire-reachability-permit!` blocks on `async/<!!`
(`reference-code/datahike/src/datahike/gc_guard.cljc:190-206`). So
`seon.cluster.registry/branch!` (`src/seon/cluster/registry.clj:196-206`) and
`retire-branch!` (`:291`) take 57-74 s for their first call and 13-29 ms
thereafter:

```
[{:branch-ms 63224 :delete-ms 29} {:branch-ms 20 :delete-ms 13} {:branch-ms 28 :delete-ms 15}]
retire-scratch! 74326 ms
```

The schedule fires already slip: nominal `04:50:00` observed `04:50:44`,
nominal `04:51:00` observed `04:51:59`.

## Consequence observed

`bin/test --platform` cannot record its results. `record-persistent-results!`
(`src/seon/test/runner.clj:1797`) sends one form whose remote work forks and
retires a scratch branch (`src/seon/cluster/source.clj:300-323`); the send sits
for 60-135 s and the operator's 30,000 ms socket read timeout
(`script/seon/fresh_operator.clj:1548,1605-1615`,
`:seon.config.operator/event-silence-backstop-ms`) fires first:
`persistent results NOT recorded: :seon.fresh-operator/prepl-response-silent`
(batch 27, `tmp/orchestrator/gate-results/batch-27/platform.log`). The 30 s
diagnostic is correct — the remote genuinely had not answered. Cluster start,
refork and source publication sit on the same gate.

## Root causes

1. **The work is ~100× the question.** A blob inventory walks every datahike
   index node to select the binary keys. The blob digests the system references
   are already database facts; the store walk derives what a query answers.
2. **The cadence has no relation to an observable event.** `"* * * * *"` is a
   tuned constant. A reclamation whose run exceeds its own period is never
   reported — it just starves other writers. AGENTS.md §2.3: the bound belongs
   at the seam that admits the work, and a bound firing is a bug report.
3. **The roster permit wait is unbounded.** `seon.cluster/start!` already knows
   the typed `:sweep-in-progress` refusal (`src/seon/cluster.clj:3081`); the
   roster path has none, so one slow sweep stalls a writer forever with no
   event naming what it waits for.

## Related observation

The shared root's store is **72 GB across 379,772 files** under `data/store`.
Under AGENTS.md §6 that order-of-magnitude growth is itself the investigation
signal, and it is what turned a cheap sweep into a starving one.

## What would prove it dead

One regression: with a sweep permit held by a simulated long reclamation, a
roster write (`registry/branch!`) returns a typed, retryable
`:sweep-in-progress` diagnostic within its declared bound instead of blocking,
and a reclamation whose run exceeds its period reports that fact rather than
firing again.

## Evidence

`docs/prds/context-generation/research/gate-recording-refusals-2026-09-16.md`,
section "Second landing — 2026-09-16, lane `gate-recording-latency`".

## Retention-sweep lane — 2026-09-16

Confirmed on the same default PID 53378: inventory alone took
70,308.003625 ms for 3,625 blobs / 66,549,759 physical bytes, below the
536,870,912-byte budget. Current-reference derivation took 172.702375 ms.
A 60,422.284833 ms reachability sample returned 115 sweep-in-progress and
4 admitted verdicts (96.64% closed). The installed database has no
`seon.blob` attributes; a new empty catalog would not prove zero usage.

The proposed outside-permit candidate decision needs deletion-time validation:
a publisher can make the selected digest reachable before the sweep acquires
its permit. A cluster-local catalog also loses inventory when that branch is
retired, while physical bytes remain. Datahike already owns whole-store GC,
but its Konserve sweep also enumerates keys and does not enforce this byte
budget. No production change or schedule retirement was made.

The [decision and exact live evidence](../../prds/context-generation/research/retention-sweep-2026-09-16.md)
record three options under the AGENTS.md §2.5 cross-owner design gate.
The issue remains open. Acceptance for a query-based replacement must include
complete root inventory after branch retirement, zero enumeration/permit under
budget, oldest-unreferenced deletion over budget, and protection when a
candidate becomes referenced between selection and deletion.

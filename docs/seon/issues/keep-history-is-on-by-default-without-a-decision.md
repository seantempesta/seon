---
type: issue
status: open
severity: friction
tags: [issue, database, wave/store-perf]
---

# State a position on `:keep-history?` instead of inheriting it

## Problem

Rulings #23 and #40 settled the policy: history is on by default, while
scratch/eval use may run history-off once the mechanism exists. Seon's store
configuration still inherits Datahike's `true` rather than stating the default
explicitly. This note is now about making the settled default visible and
measured, not choosing the policy again.

## Evidence

`reference-code/datahike/src/datahike/config.cljc:21` —
`(def ^:dynamic *default-keep-history?* true)`.

`src/seon/cluster/store.clj:156-179` is the creation configuration and emits
the file store, self writer, fused roots, diff buffer, and schema flexibility,
but no `:keep-history?`. Datahike's config normalization supplies `true`
(`reference-code/datahike/src/datahike/config.cljc:21,224-238`) and
`empty-db` creates three temporal indices when that value is true
(`reference-code/datahike/src/datahike/db.cljc:897-957`). The writer updates
temporal EAVT/AEVT and temporal AVET for indexed attributes
(`reference-code/datahike/src/datahike/db/transaction.cljc:446-484`).

Seon does read temporal data — `d/history`, `as-of`, and `since` have a
handful of call sites — so history may well be earned. The defect is that
nobody has weighed those reads against the write cost, and the
`datahike-configuration` docstring calls itself "the single place the
configuration shape lives" while omitting the dial with the largest effect.

Full sweep:
`docs/prds/sci-execution-runtime/research/upstream-delta-sweep-2026-07-31.md`.

The retained private-store reproduction is
`docs/prds/sci-execution-runtime/research/scripts/store-options-before-after-2026-08-02.clj`.
For the same fused-root/diff-buffer workload:

| setting | blob forces / small update | final objects | final bytes |
|---|---:|---:|---:|
| history on | 2 | 99 | 709,478 |
| history off | 2 | 60 | 362,844 |

History-off reduced this small run's store bytes by 48.9 % and objects by
39.4 %. It did **not** reduce the two per-commit blob forces after fusion and
diff buffering: those are the immutable commit record and mutable branch head,
whose fused records carry either three or six root nodes. The policy therefore
matters primarily to bytes and long-run growth on the current store path, not
to this serial fsync count.

## Owner

`seon.cluster.store/datahike-configuration` (`src/seon/cluster/store.clj:155-174`),
the same function named by
`file-store-commits-pay-five-times-the-fsyncs-they-need.md`.

## Acceptance

- `datahike-configuration` states the settled default `:keep-history? true`
  explicitly, with the reason recorded where a reader will find it.
- The write cost of the choice is measured with the existing throughput
  harness so the number sits next to the fusion and batching numbers.
- `:keep-history?` is fixed at database creation, so landing a change means
  republishing and reforking, not editing config against live data.

## Backlog triage 2026-08-02

**Applied in `db4efb4fd`.** The creation owner now emits
`:keep-history? true` with rulings #23/#40 at the decision site. This is a
zero-byte behavior change—before and after both normalize to history-on—and
makes the inherited policy explicit. Per-cluster history-off remains the
separate mechanism issue in [[history-off-is-not-a-creation-seam-toggle]].
The post-fix retained-script run reports `:keep-history? true` in Seon's
creation map and the identical controlled-store result: 99 objects / 709,478
bytes before and after.

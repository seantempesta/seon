---
type: issue
status: open
severity: friction
tags: [issue, database, datahike, architecture]
---

# State a position on `:keep-history?` instead of inheriting it

## Problem

Every Seon store runs with full bitemporal history because Datahike's
default is `true` and Seon never says otherwise. History doubles the index
set (temporal EAVT and AVET on top of the current indices) and multiplies
the nodes written per commit — so the single largest multiplier on Seon's
write amplification is an inherited default, not a decision.

## Evidence

`reference-code/datahike/src/datahike/config.cljc:21` —
`(def ^:dynamic *default-keep-history?* true)`.

`src/seon/cluster/store.clj:164-174` is the one place the configuration
shape lives, and it emits exactly three keys: `:store`, `:writer`, and
`:schema-flexibility`. Grep confirms `keep-history` appears **nowhere** in
`src/`, `config/`, or `resources/`; the only occurrences are three tests
that set it explicitly (`test/seon/flow_test.clj:1095`,
`test/seon/flow/loop_test.clj:124`, `test/seon/schema/admission_test.clj:26`).

Seon does read temporal data — `d/history`, `as-of`, and `since` have a
handful of call sites — so history may well be earned. The defect is that
nobody has weighed those reads against the write cost, and the
`datahike-configuration` docstring calls itself "the single place the
configuration shape lives" while omitting the dial with the largest effect.

Full sweep:
`docs/prds/sci-execution-runtime/research/upstream-delta-sweep-2026-07-31.md`.

## Owner

`seon.cluster.store/datahike-configuration` (`src/seon/cluster/store.clj:155-174`),
the same function named by
`file-store-commits-pay-five-times-the-fsyncs-they-need.md`.

## Acceptance

- `datahike-configuration` states `:keep-history?` explicitly, whichever way
  the decision goes, with the reason recorded where a reader will find it.
- If it stays on, the temporal reads that justify it are named; if it goes
  off, every `d/history`/`as-of`/`since` call site is shown to be either
  removed or served another way.
- The write cost of the choice is measured with the existing throughput
  harness so the number sits next to the fusion and batching numbers.
- `:keep-history?` is fixed at database creation, so landing a change means
  republishing and reforking, not editing config against live data.

## Backlog triage 2026-08-02

**Still real, narrowed to making the settled decision explicit and measured.**
Ruling #23 chose retained time travel, and current blob GC now correctly marks
history. `seon.cluster.store/datahike-configuration` nevertheless still omits
`:keep-history?` and inherits Datahike's default silently. The store/performance
wave must write `true` at creation and record the measured temporal-index cost;
the design choice itself is no longer open.

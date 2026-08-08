---
type: issue
status: open
severity: high
tags: [issue, runtime, boot, performance]
---

# A co-hosted second boot takes ~11× the first and the 30 s silence backstop abandons a healthy cluster

## Problem

Adding a second cluster to a running operator JVM takes ~63 s where the first
took ~5.8 s. The operator's event-silence backstop fires at 30 s, prints a
failure, and returns no URL — while the cluster is in fact still booting and
comes up healthy and reachable seconds later.

Two defects in one observation: the boot is slow, and a CLOCK is the primary
failure detector for a boot whose progress is directly observable.

## Evidence

Isolated root `tmp/cohost-operator`, 2026-08-07, after the
[activation closure contract repair](a-cohosted-second-cluster-cannot-boot.md)
unblocked the second boot:

```
bin/seon --root tmp/cohost-operator start b
● b boot: repl / store / branch / recovery
! operator event silence backstop fired: prepl response was silent for 30000 ms
✗ The prepl response went silent for 30000 ms. The partial cluster remains
  addressable as b.
```

Immediately afterwards `status` reports `2/2 clusters alive`, and querying the
live JVM shows both boots COMPLETED:

```clojure
;; [cluster ready-ms web? activation-digest]
[["a" 5809  false "a4dd6e91…"]
 ["b" 63600 false "266bf231…"]]
```

`:seon.boot/ready-ms` is present for both, so cluster b finished its tower at
63.6 s — 33 s after the operator declared failure. The wrapper's abandonment
also costs the URL line the operator normally prints.

The gap sits between the `recovery` phase and the next published progress
event, i.e. in `config`/`program`. The in-JVM regression
`test/seon/cluster/cohost_boot_test.clj` shows the same ratio (cluster A ~50 s
including publication, cluster B ~74 s), so this is the co-hosted boot itself,
not the operator wrapper.

## Why it matters now

The
[test-infrastructure spec](../../prds/sci-execution-runtime/plan/test-infrastructure-spec-2026-08-07.md)'s
four-worker target puts multiple sovereign environments in ONE JVM. A per-
environment cost that GROWS with the number already resident is the wrong
shape for that target, and it taxes every fix cycle that boots a scratch
cluster.

## Owner

- Slowness: the `config`/`program` boot phases in `src/seon/cluster.clj`
  (`stack-tower!`), measured per phase before anything is changed. The
  neighbouring finding that
  [`schema.edn/packaged-forms` re-reads and re-merges every schema resource
  per call](packaged-forms-rereads-every-schema-resource-per-call.md) is a plausible
  contributor and is owned by another lane — measure before attributing.
- Backstop: `:seon.config.operator/event-silence-backstop-ms` in
  `script/seon/fresh_operator.clj`. Per the standing rule, a deadline may only
  guard genuinely unobservable state; a boot publishes its own phases, so the
  detector should be the boot's own progress/readiness event, with the clock
  demoted to a loud last-resort backstop whose firing is itself a bug report.

## Acceptance criteria

- A per-phase measurement of the first versus the co-hosted second boot, in
  the owning PRD's `research/`, before any tuning.
- A second cluster's boot cost is within a stated factor of the first, and the
  factor is justified by what genuinely must be recomputed per cluster.
- `bin/seon start` for a co-hosted cluster reports success and its URL when
  the boot succeeds; the silence backstop no longer fires on a boot that is
  still publishing progress.

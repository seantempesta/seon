---
type: issue
status: open
severity: friction
created: 2026-09-23
tags: [issue, testing, performance, seconds-not-minutes, turn]
---

# Turn integration fixture and two ordinary passes take forty seconds

## Problem

`seon.cluster.turn-test/with-cluster` takes 15.8–23.4 s to stand up in the
default JVM. Two ordinary turn passes after that take a further 29–39 s. The
passes are an agent reading once and then completing, with the provider
stubbed. The bound is ten seconds, so every test in that namespace that
drives a turn is a defect. Each one takes 32–94 s.

## Evidence

Lane receipts-no-program-edges, 2026-09-23. Default was pid 70720, running
published source `data/source/76b42f90…`, which is byte-identical to HEAD
`seon.turn`. HEAD+lane `seon.turn` was installed through a disposable
`with-redefs-fn` (`tmp/receipts-no-program-edges/redef-run.clj`). The bodies
ran under an isolated handle from `seon.cluster.agent/acquire-context!`.

| body | wall |
|---|---|
| `an-ordinary-turn-advances-no-program-attribute-revision`, lane / HEAD | 43.3 s / 43.7 s |
| mixed declaration + ordinary turn (`declaration-body.clj`) | 31.9 s |
| `mixed-plan-publishes-only-the-contracted-function`, lane / HEAD | 51.0 s / 53.3 s |
| `delimiter-repair-is-span-local-and-precedes-intent` | 93.8 s, ended in `OutOfMemoryError` at `seon.reconcile/plan-transaction-data` (reconcile.cljc:349) during a concurrent foreign test run |

A phase split with a timed body
(`tmp/receipts-no-program-edges/sample-body.clj`) gave fixture ready at 17.4 s
and two passes driven by 46.1 s. `seon.sci.eval/base-ctx` was called twice
during the drive, 605 ms in total, so SCI acquisition is not the cost. The
most frequent top `seon.*` frames of RUNNABLE threads, sampled every 250 ms,
were:

- `seon.schema/projection-from-rows` (parse-rows)
- `seon.schema/canonical-coll-string`
- `seon.db/q`
- `seon.schema/framed`
- `seon.schema/compilable-form`
- `seon.schema/structural-schema`
- `seon.schema/projection-rows`

That is projection derivation. A related measurement: `seon.db/carried-projection`
on a speculative value derives every call, at 165–236 ms each
(`stamp-probe.clj`). This is the writer-cost class that
`docs/research/agent-platform/regression-bisect-2026-09-23.md` describes.

## Wanted

The fixture and a two-pass ordinary drive finish within the declared bound.
That means not deriving a projection per read on in-transaction and fixture
values. Re-measure after the writer-cost lane's content-keyed
`carried-projection` memo lands, and record the phase split.

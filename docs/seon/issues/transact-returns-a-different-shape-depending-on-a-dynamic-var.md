---
type: issue
status: open
severity: blocker
tags: [issue, database, runtime, agent, sci]
---

# `seon.db/transact!` returns a different shape depending on a dynamic var

## Problem

`transact-call` (`src/seon/db.clj:1199-1205`) chooses its return SHAPE from a
thread binding:

```clojure
;; Ambient custody marks an agent-facing call. Both public arities
;; therefore return the same declared semantic report inside an eval;
;; unbound system callers retain Datahike's exact report for reducers
;; and listeners.
(if (some? *conn*)
  (agent-transaction-report report)
  report)
```

`agent-transaction-report` (`:1243-1256`) returns
`{:tx :datahike/commit-id :seon.db/datom-count :tx-data :tempids}`. It carries
**no `:db-after`** and no `:db-before`.

So the same call, with the same arguments, returns a Datahike transaction
report or a Seon projection depending on whether the caller happens to be
below an agent evaluation. Any compiled function that reads a report key works
at boot and silently reads `nil` the moment an agent calls it.

This is the failure class the ethos names directly: a fallback that "happens
to be right" under one caller and lies under the second.

## How it surfaced

Cluster `s3` in an isolated root, 2026-08-08, the first live drive after
call preparation was installed (P17 S2). `seon.cluster/ensure-entity!` got
past the arity error that had hidden this, ran its body, committed, and then
failed:

```text
seon.db/pull violated its contract (invalid-input):
  [[{:value nil, :message "must be an immutable Datahike database value"}
    {:value nil, :message "invalid type"}]]
```

`ensure-entity!` read `(:db-after transaction-result)`. Confirmed by probe in
door mode on the live cluster:

```clojure
(let [r (seon.db/transact! {:tx-data [{:seon.ns/name 'probe.only …}]})]
  [(keys r) (some? (:db-after r))])
;; [(:tx :datahike/commit-id :seon.db/datom-count :tx-data :tempids) false]
```

## Scope — one site repaired, at least one still latent

Repaired at the caller in the same commit as P17 S2: `ensure-entity!`
(`src/seon/cluster.clj:1759`) now reads the database back from its connection,
whose current value is at or after the commit it just made.

**Still latent:** `src/seon/cluster.clj:1978` uses `(:db-after result)` as a
"did it commit?" test. It runs in the fault-committer proc, where `*conn*` is
unbound, so it works today — and it will report a successful commit as a
failure the first time anything calls that path from inside an evaluation.

A sweep for other report-key readers of `seon.db/transact!` has not been done.
The three `:db-after` reads in `src/seon/search.clj:263`,
`src/seon/call_preparation.clj:501` and `src/seon/cluster/curate.clj:342` are
Datahike LISTENER reports, not `transact!` returns, and are correct.

## Why it is a blocker

Every agent-side caller of `transact!` that needs the resulting database value
is silently broken, and the breakage is invisible to any test that exercises
the function system-side. Call preparation has just made a great many such
calls reachable for the first time.

## Not fixed here, deliberately

Removing the dynamic-var branch is a `seon.db` change that alters the
agent-facing transaction render, and the ruled successor
([P17 S4](../../prds/sci-execution-runtime/plan/p17-ambient-slices-2026-08-05.md))
owns deleting `seon.db`'s bespoke dynamic-var elision wholesale. The two should
land together rather than have this lane change the projection under it.

## Acceptance

- `seon.db/transact!` returns ONE shape for every caller, so no function's
  behaviour depends on whether a dynamic var was bound below it. If a caller
  needs the resulting database value, the returned value provides it (or names
  a basis the caller can read at) under the same key in both cases.
- `src/seon/cluster.clj:1978` no longer decides "committed" from a key that
  may be absent.
- One regression per class: a contracted function that reads its own
  transaction's result, exercised BOTH system-side and from inside an agent
  evaluation, agreeing.

## Owner

`src/seon/db.clj` (`transact-call`, `agent-transaction-report`), with the
P17 S4 `seon.db` conversion sweep.

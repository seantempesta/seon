---
type: issue
status: resolved
severity: blocker
tags: [issue, runtime, database, agent]
---

# Put run-claim eligibility in one fenced transition

## Problem

`seon.cluster.run/claim-tx` exposes a transaction builder that trusts the
caller to have classified a run as open and a foreign lease as expired. The
returned transaction checks only the observed epoch, process, and lease
values. It neither receives `now` nor fences the run's open/current-run facts.

Consequently, the public transition can steal a still-live foreign claim or
reacquire a run that already has `:seon.cluster.run/closed-at`.

## Evidence

- `src/seon/cluster/run.cljc:176-209` says a live foreign claim is not
  stealable and that takeover is for an expired claim, but `takeover?` means
  only that both observed fields were supplied.
- A focused in-memory Datahike probe claimed a run for `"p1"` through
  `2026-07-26T12:30Z`, then immediately called `claim-tx` with the exact
  observed holder and lease. The transaction committed:

  ```clojure
  #:seon.cluster.run{:process "p2",
                     :claim-epoch 2,
                     :lease-until #inst "2026-07-26T13:00:00.000-00:00"}
  ```

- A second probe correctly closed a run, then called the fresh/reacquire shape
  with its retained epoch. It also committed, leaving one entity with both
  `:seon.cluster.run/closed-at` and a new process, epoch, and lease.
- The quarry kept eligibility in `seon.agent.run.core/claim-plan`
  (`src-old/seon/agent/run/core.cljc:160-196`): closed and live-foreign runs
  return nil before a takeover transaction can be produced.

This violates the database coordination rule: callers may derive a candidate
from a database value, but the one owning transition must make ineligible
claims unrepresentable and fence the exact observed facts.

## Owner

`seon.cluster.run` owns claim selection and its Datahike transaction data.
Restore one public pure claim decision over the observed run and `now`; keep
raw transaction builders private or otherwise impossible to invoke for a
closed/live-foreign run.

## Acceptance

- A live foreign lease cannot produce claim transaction data.
- A closed run cannot produce claim transaction data.
- An expired claim produces one transaction that CAS-fences the observed
  process, lease, epoch, and current-run/open facts.
- A renewal racing the expired-claim transaction wins and the takeover fails.
- A generated state-machine property covers fresh claim, release, reacquire,
  heartbeat, expired takeover, close, and stale writes.

## Closed 2026-07-27

Resolved by `ba5cb0c1e` on the sealed N2 transition contract:
`src/seon/cluster/run.cljc:241-283` routes claims through
`:db.fn/call`, reads open/holder/lease eligibility from the
mid-transaction database value, refuses closed or live-held runs, and
increments the epoch only for an unheld or lapsed run. The recurring generated
model at `test/seon/cluster/run_test.clj:208-490` exercises claim, heartbeat,
release, close, plan, stale epochs, and lease spans against a fresh database
per trial.

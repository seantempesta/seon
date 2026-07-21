---
type: issue
status: resolved
severity: friction
tags: [issue, testing, tooling]
---

# bin/test-cljs lock: no queue option and silent stale reclaim

## Problem

`bin/test-cljs` serializes suites through the `tmp/test-cljs.lock` mkdir lock
(one `out/test/test.js` bundle owner). In a busy shared tree with a lane whose
gate reruns the full suite back-to-back, a second caller repeatedly got
`exit 2` — "Another pod test owns tmp/test-cljs.lock (pid <N>); not racing its
bundle." — with no way to queue. Observed twice with different pids (18258,
3032), and live inspection on 2026-07-20 showed the lock churning between real
runs (the U5 sci-execution lane), so this was genuine contention, not a stale
lock.

Diagnosis of the acquire path also found:

- stale-owner reclaim (dead pid, or a live pid whose command is not a
  test-cljs run) already existed but was **silent** — indistinguishable from a
  clean start; and
- a lock directory whose `pid` file had not yet been written by a concurrent
  runner (the window between its `mkdir` and its `printf`) was treated as
  ownerless and deleted — a small but real race that could let two suites run.

## Fix (2026-07-20, this branch)

One mechanism, in `acquire_test_lock` in `bin/test-cljs`:

- `SEON_TEST_WAIT=1` queues behind a live holder with a 1800s deadline;
  `SEON_TEST_WAIT=<seconds>` sets an explicit deadline; unset/0 keeps the
  immediate `exit 2`, whose message now names the dial;
- every reclaim logs an honest line ("Reclaiming stale … owner pid N is
  dead." / "not a test-cljs run" / "no owner pid recorded after grace");
- a missing `pid` file gets a ~1s grace loop before the lock is treated as
  ownerless, closing the mkdir→pid-write race; and
- the acquire loop retries after reclaim, so a holder that dies mid-wait is
  reclaimed and the waiter proceeds. Two suites still never run concurrently.

## Proof

- Live holder, no dial: immediate refusal, exit 2, message includes the
  `SEON_TEST_WAIT=1` hint.
- Live holder dying at 8s, `SEON_TEST_WAIT=60`: "waiting up to 60s" →
  "Reclaiming stale … pid dead" → "Acquired … after 8s"; lock released on
  exit.
- Fabricated stale lock (dead pid 3032) + full `bin/test-cljs`: reclaimed
  with the honest log line and the suite ran to completion (counts in the
  closing report).

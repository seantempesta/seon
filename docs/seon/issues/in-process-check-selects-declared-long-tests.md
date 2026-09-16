---
type: issue
status: resolved
severity: friction
created: 2026-09-16
tags: [issue, test, runtime, in-process, bounds, wave/steward-platform]
---

# `seon.test/check` selects declared-long tests it cannot finish in-process

## Problem

`seon.test/check` runs the tests reaching a changed symbol inside the calling
JVM under the single `:seon.test/check-time-limit-ms` allowance (120,000 ms on
`default`). Its selection applies no `:seon.test/long` filter, so a change to a
widely reached function pulls in real-boot drills that are declared long
precisely because one of them costs most of the bound. The check then returns
`:seon.test/unknown` with no per-test result at all — the caller learns nothing
about the tests that DID pass before the long one started.

## Evidence

Default, pid 38993, 2026-09-16 12:20Z, after an `src/seon/instrument.clj`
change:

```clojure
(seon.test/check {:seon.db/connection (seon.operator/connection "default")
                  :seon.boot/cluster-name "default"
                  :seon.test/changed '[seon.instrument/violation]
                  :seon.test/paths ["src/seon/instrument.clj"]})
;; =>
;; #:seon.test{:next-tier :none}
;; :seon.error/kind :seon.test/unknown
;; :seon.error/message
;;   ":check-completion never arrived for :seon.test/check within the declared
;;    :seon.test/check-time-limit-ms bound of 120000 ms. Pending:
;;    seon.cluster.boot-test/development-adoption-targets-one-of-two-cohosted-clusters"
```

That test is declared
`^{:seon.test/long "Real source publication and two cohosted clusters verify
named adoption and independent program facts."}`
(`test/seon/cluster/boot_test.clj:1001`). It booted its own isolated root
(`tmp/boot-test/caff60ab-1f5b-4812-afa4-eed7310c6476`, logged by
`seon.cluster.store/create-store!`) and cleaned it up; the developer store was
never a target, so this is NOT the store-wipe class
([a-platform-tier-test-wiped-the-checkouts-store](a-platform-tier-test-wiped-the-checkouts-store.md)).
The declared destructive owners are the three deleting functions, and this test
reaches none of them, so `:seon.test/destructive-excluded` correctly did not
apply.

`grep -n "seon.test/long" src/seon/test.clj` returns nothing: neither
`check-in-process` selection nor the `check` request schema mentions the
declaration.

## Why this is the absence-of-signal class

The bound fires and reports the test that was pending — that part is honest.
What is lost is everything that ran before it: the check returns one unknown
instead of the results it already had, so a lane reading it cannot tell a slow
selection from a red one, and `:seon.test/next-tier :none` reads like a verdict.

## Owner and acceptance

`seon.test/check`'s in-process selection (`src/seon/test.clj:482`,
`src/seon/test.clj:631`). Either exclude `:seon.test/long` tests from the
in-process selection and report them the way destructive reach is already
reported (with the cold command that runs them), or return the per-test results
already recorded alongside the expiry. A regression asserts that a check whose
selection includes a declared-long test still reports the tests it completed and
names the long ones as not-run, never a bare unknown.

## Fix (2026-09-17, unrun)

Both defects are fixed at the one selection seam and reported, never silent:

- `:seon.test/long` is now a program-row fact, declared at the two seams that
  lift a test marker from Var metadata (`src/seon/fn.clj:553`,
  `src/seon/sci/eval.clj:410`) and on the `:seon.test/test` row schema. The
  check queries it instead of loading a Var it deliberately has not loaded.
- `check-in-process` excludes the declared-long selection and reports it as
  `:seon.test/long-excluded` — the count and the names, with each declared
  reason and the cold `bin/test -- <ns>` that runs it — mirroring
  `:seon.test/destructive-excluded`. `:seon.test/include-long? true` on the
  check request is the explicit opt-in.
- On expiry, `check` no longer discards what it holds: `expired-result` returns
  the completed runs with their verdicts, `:seon.test/pending` naming the
  remaining selection, and `:seon.test/expired` carrying the typed unknown that
  names the test which did not return.

Four regressions in `test/seon/test_reaching_test.clj`. Landing note:
[check-excludes-long-tests-2026-09-17](../../prds/steward-platform/research/check-excludes-long-tests-2026-09-17.md).
All four regressions are GREEN in process on `default` pid 63433 (test namespace
hot-reloaded through `seon.test`'s own loader, daemon thread): 1, 8, 4 and 15
assertions, no failures or errors. Batch 69 B2's one cold red was the expiry
fixture's tuned 3000 ms allowance — the bound fired before the first trivial run
returned and the check reported that honestly — now derived from one measured
complete check. This note stays open until a cold gate confirms it.

Remaining mirror: `seon.test.runner/long-reason` (`src/seon/test/runner.clj:622`)
still reads the marker off Var metadata rather than the row it now indexes.

## Resolved 2026-09-17 16:45Z

Batch 72 (cold, recorded) ran `seon.test-reaching-test` GREEN on HEAD with `4e22d2256` + `beb95c1af`: `:seon.test/long` is a program-row fact, `check` excludes long tests by default with a named report and an opt-in, and an expired bound reports the completed verdicts plus the typed expiry.

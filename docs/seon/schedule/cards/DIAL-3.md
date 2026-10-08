---
title: "DIAL-3: test time limit and batch size are dials"
type: reference
status: ready
created: 2026-10-08
---

# DIAL-3: Test time limit and batch size are dials

Ledger row DIAL-3 (group E). Source: `dials/dials.md` rows D8, D9. Owner: "no hardcoded limits". Model: Sonnet.

## Sites (verified at `9d69253e1`)

- `seon.test/bounded-result`'s caller passes `(or (:seon.test/long-ms …) 5000)` (`src/seon/test.clj:285-288`): the
  per-test body bound, CLAUDE.md's five seconds.
- `seon.test/batch-limit` (`test.clj:2155-2159`, `(def batch-limit … 64)`), read at `:2365`
  `(or (:seon.test/batch-limit request) batch-limit)`.
- `seon.test/declared-bound-ms` (`test.clj:2143-2153`) also defaults to 5000 but has **no caller**
  (`rg declared-bound-ms src test resources` finds only its definition): delete it.

## Files

- `resources/seon/schemas/seon.config.test.edn`
- `src/seon/test.clj`
- `test/seon/test_test.clj`

## Schema (`seon.config.test.edn`, beside `:auto-check-cases`)

```clojure
:time-limit-ms [:int {:min 1 :seon.config/dial true :seon.config/default 5000
                      :description "A test body's bound when the test declares no :seon.test/long-ms (CLAUDE.md: five seconds)."}]
:batch-limit [:int {:min 1 :seon.config/dial true :seon.config/default 64
                    :description "Members per admission and release transaction of one test request."}]
```

No `config/default.edn` line (schema defaults, `seon.config/registration-defaults`, `config.clj:165-183`).

## Functions

- `seon.test/run` (`test.clj:~2260`) already reads `effective` (`:2282`). At `:2365`:
  `limit (or (:seon.test/batch-limit request) (:seon.config.test/batch-limit effective))`; delete `(def batch-limit …)`.
- Thread `(:seon.config.test/time-limit-ms effective)` to the member runner: add it to the custody map `run` builds
  for each member (the map handed at `:2085`, `{:seon.sci.eval/ctx … :seon.db/connection … :seon.test/member …}`) as
  `:seon.config.test/time-limit-ms`, and at `:285-288` use `(or (:seon.test/long-ms …) (:seon.config.test/time-limit-ms custody))`.
  Follow the actual path from `run` to that `let` (read `member-result`, `test.clj:1998`) and add the key to each map
  contract it crosses (open maps: add it as an optional member where a closed map refuses it).
- Delete `seon.test/declared-bound-ms`.

## Proof

New `seon.test-test/the-default-test-limit-and-batch-size-come-from-config`: on a test cluster, set
`:seon.config.test/batch-limit` to 2 (through the config writer the existing config tests use) and run a request of
three trivial members; assert the run reports two batches (the members' admission transactions: read how
`seon.test-test` already observes batches, else assert three results and two `:seon.test.run/id`s). Then set
`:seon.config.test/time-limit-ms` to 200 and run a member whose body sleeps 400 ms with no `:seon.test/long-ms`:
assert it fails on its bound (the duration failure data), not that it passed.

`--test seon.test-test seon.test-runner-test seon.config-defaults-test`

## Cost

D: one map lookup instead of a constant.

## Commit

`bin/lane commit <lane> -m "DIAL-3: the default test bound and batch size are dials" --test seon.test-test seon.test-runner-test seon.config-defaults-test resources/seon/schemas/seon.config.test.edn src/seon/test.clj test/seon/test_test.clj`

## Dependencies

None at `9d69253e1`. F4c adds `:step-time-limit-ms` to the same schema file: whichever lands second claims after the
first.

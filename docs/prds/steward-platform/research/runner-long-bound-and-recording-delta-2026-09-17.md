---
type: research
date: 2026-09-17
tags: [test, runner, bounds, database, wave/steward-platform]
---

# The exchange bound derives from the long declaration; recording emits a delta

Bounded lane, two slices in `src/seon/test/runner.clj` and its tests. No test
JVM was launched and `default` (pid 74930) was never restarted.

## Slice 1 — the per-exchange bound derives from `:seon.test/long`

### What was wrong

A named-namespace gate runs a declared-long test complete (§5, correct), but
every task rode the SAME per-exchange bound: `exchange-bound-seconds` =
`(max 60 (- (silence-seconds) 30))` = **270 s** by default, for a real-boot
drill and a pure-function assertion alike. `seon.cluster.boot-test/development-adoption-targets-one-of-two-cohosted-clusters`
(declared long at `test/seon/cluster/boot_test.clj:~1007`) expired every batch
as `::worker-exchange-bound` — the runner reported an exchange failure for a
test the program had already declared runs longer. That is §2.3's defect
exactly: a tuned constant standing in for an observable event the declaration
already makes.

### The fix — one derivation, then one bound

`:seon.test/long` is now read from the program ROW, not Var metadata:

- `seon.test.runner/long-reason` (the metadata read at `src/seon/test/runner.clj:631`)
  is **deleted**. `long-declarations` derives `{test-symbol {:seon.test/long,
  :seon.test/long-ms}}` from `manifest-rows`, and both `test-selection` and
  `test-tasks` take that map.
- `verify-long-declarations-indexed!` is the drift check, mirroring
  `destructive-owner-rows`: a Var that declares `:seon.test/long` while its
  indexed row does not REFUSES the run by name. Without it the row would
  answer NOT-LONG for a test that is one — absence read as health.
- The optional allowance `:seon.test/long-ms` is accretive and lifted at the
  same two seams `:seon.test/long` already uses (`src/seon/fn.clj:555`,
  `src/seon/sci/eval.clj:413`), declared in
  `resources/seon/schemas/seon.test.edn` as `:seon.test/long-ms`
  (`[:int {:min 1}]`) plus one optional entry on the test row.
- `task-exchange-bound-seconds` = `(max default (ceil long-ms/1000))`;
  `task-bound-notice` is the coordinator's line, e.g.
  `BEGIN worker=pool-1 task=… bound=900s (declared :seon.test/long-ms 900000 — <reason>)`
  versus `… bound=270s (default per-exchange bound)`.
- The suite's silence horizon moves with it. `announce!` now `swap!`s instead
  of `reset!`ing, so `::silence-allowances` (task-id → seconds) survives every
  announcement, and the liveness backstop adds the largest in-flight allowance
  to its limit. Otherwise a declared allowance above the horizon would simply
  hand the win to the watchdog — exit 124 with every JVM dumped — which is the
  same defect wearing the other bound's clothes.

`test/seon/cluster/boot_test.clj` is a peer's protected file, so the boot
drill's own `:seon.test/long-ms` declaration is NOT in this commit. Until that
declaration lands the mechanism is inert for that test, and it still expires at
270 s.

### Regressions (`test/seon/test/runner-test`)

- `the-exchange-bound-derives-from-the-long-declaration` — a spat probe file is
  indexed through `seon.fn/build-artifact`, so the declaration under test is
  LIFTED, never hand-supplied. A test declared long with `:seon.test/long-ms
  900000` bounds its exchange at 900 s and the notice names the allowance and
  the reason; a test declared long with no allowance keeps 270 s and the notice
  says `(default per-exchange bound)`; an undeclared test keeps 270 s; and
  dropping the row for the declared test makes `verify-long-declarations-indexed!`
  refuse by name.
- `a-declared-long-exchange-widens-the-silence-horizon` — an announcement never
  drops an in-flight allowance from the progress atom.

## Slice 2 — recording emits a delta

### Measured first, on `default` (pid 74930, jvm mode, `datahike.api/with`, nothing committed)

Run `bfbe7dcff9db` (93 recorded results, 74,642 reach members total, 6 to 1000
per test, 30 results carrying failures) was read back out of the database and
re-recorded AS AN IDENTICAL COMPLETION through `record-tx`:

| | datoms | added | retracted | elapsed |
|---|---|---|---|---|
| identical re-record, BEFORE | **157,981** | 78,915 | 79,066 | 2,563 ms |

By attribute (added / retracted), largest first:

| attribute | added | retracted | value payload |
|---|---|---|---|
| `:seon.test/reach` | 74,642 | 74,794 | 598 KB |
| `:seon.test.failure/*` (17 attributes) | ~3,100 | ~3,100 | 577 KB |
| `:seon.test/failing-assertions` | 270 | 270 | 36 KB |
| `:seon.test/failure-message` | 30 | 30 | 439 KB |
| `:seon.test/reach-digest` | 93 | 93 | 12 KB |
| `:seon.test/pass-count`, `fail-count`, `error-count`, `run`, `run-at`, `run-basis-t` | 0 | 0 | 0 |

**`:seon.test/reach` is 94.6% of the datoms.** The counts that emit ZERO are the
proof of the root cause: Datahike already no-ops an identical `:db/add`. A
control measurement confirmed it directly — re-asserting one test's 1000 reach
members and its digest with no preceding retraction emitted `:tx-data` of
length 1 (the `:db/txInstant` alone). Every one of those 157,981 datoms existed
only because `record-tx` explicitly retracted the attribute first and then
re-asserted the same value.

**Value payload is NOT what fills the store.** The whole 93-result completion
carries ~1.7 MB of values, against the 5–18 MB PER TEST of store growth the
gate session measured (batch 71 +0.7 GB/151 tests, batch 72 +1.0 GB/216, batch
74 +1.8 GB/100). At ~1,700 datoms per recorded test that is ~3–10 KB of store
per datom: index-node rewrite amplification in the persistent set (eavt, aevt,
avet, plus the retained temporal indices), where each touched node is written
whole and a new root persisted. The cost therefore tracks the datom COUNT, and
the datom count is dominated by `:seon.test/reach`.

### The change

`record-tx` still writes a TOTAL replacement — every attribute, reach member
and failure identity a result does not assert is retracted — but it now derives
the difference instead of retracting everything first:

- one `db/pull` per result answers existence AND the row's current latest
  result (reach members with `:seon.fn/sym`, digest, reach-unknown,
  failure-message, failing-assertions), replacing the old `[:db/id]` pull;
- `:seon.test/reach` is replaced member by member: a dropped member emits
  `[:db/retract … member]`, a new member is asserted, a retained member emits
  nothing and is not even handed to the transactor;
- `:seon.test/failing-assertions` likewise, member by member;
- `:seon.test/failure-message`, `:seon.test/reach-digest` and
  `:seon.test/reach-unknown` are retracted only when this result does not
  assert them (a cardinality-one add replaces its own value);
- `failure-replacement-tx` retracts only the attributes the new failure row
  does not carry, plus the `:seon.test.failure/contexts` members it drops. This
  also retires the `(first value)` hack that fed a whole cardinality-many set
  to Datahike's tuple validator;
- `absent-program-identities` is asked only about members NO recorded row
  already holds. A member pulled back out of the database being written into is
  present BY CONSTRUCTION, so the per-identity existence pulls (the "814
  lookup-ref pulls") collapse to the genuinely new members.

### Regressions (`seon.test-failure-facts-test`)

- `an-unchanged-re-record-emits-no-datom` — a two-result completion with a
  reach, failing assertions and a failure message is recorded, then recorded
  again unchanged: `:tx-data` beyond `:db/txInstant` is EMPTY. Changing one
  result's `pass-count` then emits exactly one entity, one attribute, one
  retract and one add.
- `a-changed-reach-member-retracts-only-itself` — dropping one member of a
  two-member closure emits exactly one datom, the retraction of that member,
  and the surviving membership is correct.

Both go through `seon.db/transact!` with `[:db.fn/call #'record-tx …]`, the
same admission path `commit-results!` uses, and assert on the transaction
report — never `datahike.api/with`.

## Measured after, on `default` (pid 74930, `datahike.api/with`, nothing committed)

| completion re-recorded | datoms | elapsed |
|---|---|---|
| the same unchanged 93-result completion | **1** (`:db/txInstant` alone; ZERO result datoms) | 824 ms |
| one result's `:seon.test/pass-count` changed | 1 (one entity, one attribute, the new value) | — |
| three reach members dropped from one result | 3, all retractions of `:seon.test/reach` | — |

157,981 → 0. The 2,563 ms also fell to 824 ms, because the members no longer
reach the transactor at all and the existence pulls collapsed.

## Verification boundary

- clj-kondo: 0 errors on `src/seon/test/runner.clj`, `src/seon/fn.clj`,
  `src/seon/sci/eval.clj`, `test/seon/test/runner_test.clj`,
  `test/seon/test_failure_facts_test.clj`.
- Commits: `8c2f62701` (slice 1: `src/seon/test/runner.clj`,
  `resources/seon/schemas/seon.test.edn`, `test/seon/test/runner_test.clj`) and
  `f272b9e6e` (slice 2: `src/seon/test/runner.clj`,
  `test/seon/test_failure_facts_test.clj`).
- `src/seon/fn.clj` and `src/seon/sci/eval.clj` were CONCURRENTLY EDITED by the
  predicate-functions lane while this lane added the two-line `:seon.test/long-ms`
  lift, so this lane did not commit them. That lane's commit `3764c7965` carried
  both lines in with its own change; the lift is at HEAD and needs no `--paths`
  entry. Verified with `git show HEAD:src/seon/fn.clj | grep long-ms`.
- In-process regression runs on `default` (pid 74930), each
  `(seon.test/run (#'seon.test/resolve-test 'sym) (seon.operator/connection "default")
  {:seon.test.run/provenance … :seon.test/remaining-ms 100000})` on a daemon
  thread, with the test namespace reloaded through `seon.test`'s own loader:

  | test | pass | fail | error |
  |---|---|---|---|
  | `seon.test.runner-test/the-exchange-bound-derives-from-the-long-declaration` | 17 | 0 | 0 |
  | `seon.test.runner-test/a-declared-long-exchange-widens-the-silence-horizon` | 2 | 0 | 0 |
  | `seon.test-failure-facts-test/an-unchanged-re-record-emits-no-datom` | 10 | 0 | 0 |
  | `seon.test-failure-facts-test/a-changed-reach-member-retracts-only-itself` | 9 | 0 | 0 |
  | `seon.test-failure-facts-test/a-repeated-failure-upserts-its-entity` (pre-existing) | 6 | 0 | 0 |

- **Two live-JVM staleness traps cost most of this lane's time, and neither is
  a code defect — record them:**
  1. `bin/seon init --dev default` ran for ~7 minutes and then refused its
     adoption COMMIT with `:seon.cluster/source-changed-during-adoption` (a peer
     lane edited during the window). Every reload had already happened, so the
     new definitions were live even though the commit was not recorded.
  2. `seon.program/shapes` caches the authored declaration resources in a
     process-level `defonce` atom (`src/seon/program.cljc:120`), and
     `seon.fn/artifact` filters every indexed row through `program/canonical-row`.
     A schema attribute added AFTER a JVM started is therefore SILENTLY STRIPPED
     from indexed rows in that JVM: `:seon.test/long-ms` reached the analyzer
     (verified directly) and vanished at `canonical-row`, and the slice-1
     regression read that as "the indexer does not lift the declaration". A
     `(reset! …)` of that atom made the same probe lift it. A fresh gate worker
     JVM is unaffected; an in-process proof of any NEW schema attribute is not.
- One diagnostic smell met while working, not filed by this lane: a
  `:seon.test/failure-identity` that is the right type but the wrong length
  reports `"expected a string, got a string"` — the refusal names neither the
  declared 64-character bound nor the supplied length.
- clj-kondo: 0 errors on every file this lane touched.
- No test JVM was launched, `default` was never restarted, and nothing was
  committed to the database by any measurement (`datahike.api/with` only).

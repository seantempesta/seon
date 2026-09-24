---
type: landing
status: partial
lane: fix-unstarted-members-unknown
created: 2026-09-24
---

# Lane fix-unstarted-members-unknown — landing (2026-09-24)

Census: docs/research/agent-platform/red-baseline-census-2026-09-24.md (Class 1, the 34 deleted-test reds).

## Broken or not done first

- **Items 1 and 2 are not implemented: they need files outside this lane's row.** Stopped per the
  fix-lane preamble. The design below needs one schema attribute in
  `resources/seon/schemas/seon.test.run.edn` (free in the ledger, not in this row). The census's
  shortcut does not work as written; see "Why the census fix (a) fails".
- Default's health is `:faulted`. One open fault is `seon.problems/failed-tests` NPE at
  `clojure.lang.Numbers ops` (20:20:24Z, 1 occurrence): `pos?` on a missing count. It predates this
  commit, and I did not reproduce it on the current Var. It is a candidate for a result that has no
  counts and no `:seon.error/at`. Not investigated further.

## Landed: item 3 (commit 2a543c27a)

- `problems/failed-tests` (problems.clj:385) now selects `[_ :seon.test/sym ?symbol]
  [_ :seon.test.member/symbol ?symbol]`. Only current test rows at the read's database count.
- Regression `seon.test.interrupted-request-test/a-deleted-tests-red-member-is-history-not-a-failed-test`:
  - It admits one run with a current red (`red`) and a symbol that has no test row.
  - It asserts four things: the symbol has no row; its member evidence is still readable through
    `latest-results`; `red` is listed; the deleted symbol is not.
  - Under the old query the deleted symbol is listed.
  - `bin/test-check default --test … --include-long`: run 68d564aa7539, pass 5, fail 0, 4,712 ms.
- Default's numbers (read-only, `seon.problems/problems`, JVM eval, pid 99265). They move as other
  lanes run tests.

  | | failed-tests | from deleted symbols | unknown | `problems` time |
  |---|---:|---:|---:|---:|
  | before | 658 | 34 | 1 | 225 ms |
  | after (Var adopted, docstring present) | 625–627 | 0 | 65 | 288–395 ms |

  - Counts after the fix: 1,437 member symbols, of which 1,378 are current test rows.
  - The query costs 0.29 ms.
  - The time difference comes from concurrent test runs, not from the query.
  - Before the fix, 215 failed tests were "Not started" (Class 1).
- Size: source +5/−2, test +42.

## Items 1 and 2: probed design (not implemented)

**Why the census fix (a) fails.** "Write not-started members with began? false and no counts" breaks
in three places:
- `prepare-failures!` (runner.clj:1313–1316) computes `(pos? (+ fail error))` over nil and throws NPE.
- The outcome map would store nil counts.
- `execution-members` (runner.clj:1100–1106) refuses a member that has `completed-tx` without three
  counts. That refusal takes down the whole run's read.

Writing 0/0/0 counts avoids those failures, but the member then reads as a zero-count result. It is
neither red nor unknown, so it is invisible, which breaks "must stay visible".

**Smallest correct composition (≈25 lines, test.clj plus one schema attribute):**
1. Write nothing onto the unstarted members. A member with no `completed-tx` is already
   `:seon.test/result-unknown` (runner.clj:1595; S0, e33009381). It is not green: `green-members`
   (test.clj:556) requires `began? true`. It is therefore pending and runs again next time.
   Delete the fake 0/0/1 outcome in `release-not-started!` (test.clj:1820–1835).
2. Replace that write with one fact on the run: `:seon.test.run/stopped`. It is an enum of the causes
   the request already knows where `::stopped` is set (test.clj:1781–1803):
   - `:deadline`, when `(remaining-ms)` ≤ 0
   - `:member-unfinished`, when `terminated?` is false
   - `:recording-refused`, when the commit is refused
   - `:platform-red`

   A throw is already recorded by `record-interrupted!` with its cause chain. Caller cancellation and
   shutdown arrive as that throw. `run-batch` returns the cause beside `::stopped`, so nothing parses
   text.
3. `admit-run`'s coverage rule (test.clj:1193–1199) says "an admitted, unstarted member covers an
   identical concurrent request". Add one clause: a run carrying `:seon.test.run/stopped` is not
   concurrent, so its open members do not cover. This is the only reason the old code wrote fake
   completions ("never left to cover later requests").
4. Optional, in runner.clj: the unknown's message names the run's stop cause. It stays readable
   without this, through `:seon.test/run`.

**Consequence to review:** `select`'s `completed` (test.clj:686) requires every member to be complete.
A stopped run then stops serving as the comparison basis. Today its fake completions make it the
basis. I believe the new behaviour is the correct one, but it needs review.

**Files needed:** `resources/seon/schemas/seon.test.run.edn` (the attribute and its `:run` map
entry); runner.clj only for step 4. Regression (planned): a request whose deadline fires after one
member. The next members read as `result-unknown`, the run carries `:deadline`, `verified?` is false,
and an identical next request reserves them again instead of covering.

## Verification limits

- Only item 3 was run. Items 1 and 2 were read at `file:line` and not executed.
- Publication raced other lanes twice ("source head changed", "source changed during development
  adoption", 27.5 s). The test row published on the next hook.
- No reset needed.

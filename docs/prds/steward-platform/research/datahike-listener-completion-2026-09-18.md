---
type: research
status: landed
created: 2026-09-18
tags: [datahike, writer, fork, bounded-execution]
---

# Datahike listener completion

The proven lost-completion defect is fixed in the maintained Datahike fork
and selected by Seon. I read AGENTS.md §§0–5,
`.agents/skills/datahike/SKILL.md`,
`writer-hang-root-cause-2026-09-18.md`, and
`a-datahike-listener-exception-strands-a-committed-transaction-promise.md`
end to end before editing. The dependency checkout was `main` at
`73afe782`; `origin` is `git@github.com:seantempesta/datahike.git` and
`upstream` is `https://github.com/replikativ/datahike.git`.

## Landed change

Datahike fork commit `e11845ba` (`Fix listener failures stranding committed
writes`) adds one notification seam shared by `transact!` and `merge-db!`.
Each operation snapshots the connection's listener registry, delivers its
committed report to the public promise, then invokes the snapshotted callbacks
synchronously on the existing thread. Each callback catches Throwable
independently and logs `:datahike/listener-error` at error level with
`:listener-key` and `:exception`. Notification did not move to another thread;
report contents and commit-before-result ordering are unchanged. Snapshotting
before settlement preserves the prior guarantee that a listener registered
after an operation returns cannot observe that earlier report.

The fork regression covers a throwing listener, a later surviving listener,
a subsequent successful transaction, the observable diagnostic, and a
latch-blocked listener whose committed report is realized before release.
Every assertion runs through both `transact!` and `merge-db!`.

Seon gitlink commit `95e2e1983` advances `reference-code/datahike` from
`73afe782` to `e11845ba`. Seon regression commit `d443d295c` uses the canonical
database fixture and a 250 ms declared write bound. A throwing listener does
not produce `:seon.db/write-bound-exceeded`; the call returns the committed
report, the durable fact is readable, and the Datahike error diagnostic names
the listener key and original exception. `src/seon/db.clj` was not edited.

## Verification

- Datahike: `clojure -M:test -m kaocha.runner --focus
  datahike.test.writer-error-test` — **27 tests, 237 assertions, 0 failures**.
- Seon: `bin/test-fast --paths test/seon/db_test.clj -- seon.db-test` — **58
  tests, 436 assertions, 0 failures, 0 errors**. The required overlay form
  succeeded; no plain-working-tree fallback was needed.
- Both owned diffs passed `git diff --check`. Datahike's whole-file formatter
  reported pre-existing formatting drift outside the owned hunks, so no bulk
  rewrite was applied.

The Seon MCP runtime tools were not exposed to this lane, so no live JVM probe
was possible. The orchestrator still owes the cold path-limited gate and
platform proof. The inherited shared tree contained unrelated edits; the
HEAD-plus-owned-path overlay excluded them, and none was modified or included
in these commits.

## Population-cost baseline retained

The separate population-cost work must compare against the original research's
measurements unchanged:

| Phase | First run ms | Detailed run ms |
|---|---:|---:|
| Entire publication call | 95,299.767 | 86,534.362 |
| All final validators, 9 callbacks | 13,100.801 | 16,994.679 |
| All transaction application, including validators | 27,113.539 | 34,738.387 |
| All commit calls, including storage | 8,244.651 | 8,572.733 |
| Konserve multi-assoc, within commit | 8,156.642 | 8,346.185 |
| Canonical schema-row construction, caller | 1,107.573 | 918.978 |
| Projection fingerprints, 8 calls | 179.406 | 230.570 |
| Submitted-data validation (`write-error`), caller | not instrumented | 6,259.065 |

The detailed population transaction at basis **536870917** retained
**1,304,168 attempted datoms**, **419,043 effective datoms**, and **32,669
affected entities**. Application was **26,014.335 ms**, including
**11,438.038 ms** of final validation; commit was **7,254.573 ms**. Within
validation, owned-value checks were **9,683.007 ms**, arity checks **688.018
ms**, deletion **237.255 ms**, and renderer targets **41.173 ms**. Owned checks
included **46,620 entity-value calls / 326.518 ms** and **32,668
entity-validator calls / 650.491 ms**. Application minus validation remained
**14,576.297 ms**. These figures are preserved observations, not a performance
claim for the listener-completion fix.

## Owner action

The fork is `main...origin/main [ahead 1]`. No push was performed. The owner
must push Datahike commit `e11845ba`; until then, external clones cannot fetch
the gitlink selected by Seon commit `95e2e1983`.

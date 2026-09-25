---
type: landing
status: complete
lane: fix-boot-cost-opus
created: 2026-09-25
---

# Lane fix-boot-cost-opus (2026-09-25)

`bin/seon start` answers after JVM launch, the boot publication of changed
source, the layers above it and one `readiness` read. The 50.9 s start is
mostly the publication (outside this lane's files). This lane batched the one
owned cost, `seon.test.runner/latest-results`, under `readiness` and status.

## Broken or unresolved first

- **The largest cost is outside this lane: the boot-time publication of
  changed source.** `stand-boot-layers!` (src/seon/cluster/boot.clj:197-205)
  calls `cluster/refresh-source!` for the files whose bytes differ from the
  published program. The work happens in `seon.cluster/full-source-refresh!`
  (src/seon/cluster.clj:1953), which runs `seon.fn/analyze-rows` (fn.clj, held
  by another lane) and then `source/publish!` → `populate-source!`
  (src/seon/cluster.clj:1643). At 19:31Z it took about 31 of the 35.3 s
  `ready-ms`. The cost follows the size of the changed files and their
  analysis: 12 files, including `src/seon/sci/eval.clj` and `src/seon/fn.clj`.
  It does not follow the stored test results. The existing issue is
  `docs/seon/issues/a-three-file-changed-path-publication-takes-a-minute.md`.
  Root should add this sighting there; issue files are not in this lane's row.
- **About 14 s ran before the store was opened.** The process started at
  19:31:55, and the first store read was logged at 19:32:09. This lines up with
  the recorded dependency-class cache miss after `reference-code/sci` changed
  (`dependency-classes`, script/seon/operator.clj:455). The fill command is
  `clojure -T:dev-cache ensure-cache`. This lane did not verify it.
- `readiness` still computes whole-history `problems` on every call. The start
  path reads it only after `ready-ms`, but the status path
  (`mcp-runtime-observation`, src/seon/cluster.clj:668) needs the problem
  counts. Moving problems out of `readiness` would need that caller changed in
  cluster.clj, which this lane does not own.
- The regression has not been run (LOAD RULE). Its read-count equality assumes
  `seon.db` appends one evidence entry per `q`/`pull`/`pull-many` call
  (db.clj:752-810). If a cache miss inside a wrapper adds entries after the
  second run, the equality fails spuriously, and a red result would point
  there first.
- RESET NEEDED: no. The change reaches default only when root adopts
  `src/seon/test/runner.clj`.

## Phase timings of the 19:31Z start (pid 37460)

Sources: `data/clusters/default/logs/seon.log` timestamps, the stored
`current-src` commit history (`:seon.source/built-at`, `:db/txInstant`) and
the runtime advertisement.

| interval | span | what |
| --- | --- | --- |
| 19:31:55 → 19:32:09 | 14 s | JVM launch and namespace load up to the first store read (class cache miss) |
| 19:32:09 → 19:32:28 | 19 s | capture and analysis of 12 changed files; program-row tx 536871516 (8,005 datoms) |
| 19:32:28 → 19:32:40 | 12 s | publication after the row tx, up to commit marker 536871517 (`built-at` 19:32:40.249) |
| 19:32:40 → 19:32:44 | 4 s | fork, accretion, host arming, ctx, flow, agents, web (`serve!` log line) |
| after ready | 0.5-1.6 s | `readiness` → `problems` → `failed-tests` → `latest-results` |

The 19:17Z start (18.6 s) published 23 smaller files: 5 s to the row tx and
9 s to the marker. The armed boot profile does not cover the publication,
because `arm-host-program!` runs after it.

## What `readiness` is proportional to (live store, bounded reads)

`problems` families on one database value: `failed-tests` 533 ms, error
signatures 24 ms, stale vars 14 ms, everything else under 8 ms.

Inside `latest-results` there are 1,736 test symbols, 19,637 members and
1,187 runs. The latest results span 232-235 distinct runs. The first query
took 65 ms. The rest was about 5 reads per run (run pull, member pull, history
check, reports query and pull, input-digest pull), 235 times over. The cost
followed how many runs the latest results were spread across.

## Change

`src/seon/test/runner.clj`:

- New `admitted-members` checks and pulls the admitted members of any number
  of runs in one batch: one history query for changed membership, one member
  query, one `pull-many`, and the same completeness refusals as before.
- `execution-members` is now its one-run caller, keeping its signature and
  refusals.
- `recorded-member-results` takes `[run-id symbol]` pairs. It makes one run
  `pull-many` (input digest included), one member batch and one reports query
  and pull.
- `latest-results` makes a single call to it.
- The commit path (`commit-results!`) uses the same function, so there is one
  reader.

Duplicates found with `rg`: `problems/problems` has one producer, and
`latest-results` has one reader, used by `problems` and `seon.test` (1372).
There was nothing to collapse beyond the per-run reader, which is now shared.

## Evidence (JVM mode, scratch namespace `lane-fix-boot-cost.probe`, removed after)

| probe on one database value | old | new |
| --- | --- | --- |
| `latest-results` of all 1,736 symbols (two calls) | 337 / 277 ms | 170 / 128 ms |
| answer, ignoring `:seon.error/at` stamps | 1,669 results, 104 unknown | identical (`=` true) |
| `latest-results []` | — | `[]`, 46 ms |
| `execution-members` on 6 runs, all members and a 2-symbol subset | — | `=` old on every run |
| absent run id (`execution-members`, `recorded-member-results`) | `:admitted-selection`, offending `:absent` | same refusal |

The scratch copies ran unarmed. The installed contracts validate the same
total volume as before, spread over fewer calls.

## Regression

`test/seon/test/interrupted_request_test.clj`
`latest-results-reads-one-batch-whatever-the-runs-it-spans`:

- Admit a second run in which `finished` goes red.
- Assert that `latest-results` records the same number of read-evidence
  entries after it as before it.
- Assert that `finished` answers from the second run, `red` from the first,
  and `interrupted` stays its own unknown in the first run.

`admitted-run!` now takes members and results; its default is unchanged.

## Commits and lines

- `ac93353d7`: runner.clj +104/-83, interrupted_request_test.clj +36/-14.
  Net +43 (source +21, test +22).

## Root runs (serially)

1. Adopt `src/seon/test/runner.clj`, or run `bin/seon down && bin/seon start`
   so default runs from the checkout.
2. `bin/test-check default --ns seon.test.interrupted-request-test`
3. `bin/test-check default --ns seon.test.one-request-test`
4. `bin/test-check default --test seon.test-failure-facts-test/what-made-it-red-names-changed-functions`
5. To measure start: `bin/seon down && bin/seon start` with no source change
   since the last publication. `ready-ms` should then be the 4-5 s layer cost,
   and the gap from `ready-ms` to the start response is `readiness`. After
   that, one `runtime_status` should show the `seon.cluster.boot/readiness`
   maximum under 1 s. A start after an edit adds the publication of the
   changed files (the issue above).

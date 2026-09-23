---
type: landing
status: landed 8bc917872..2ff62cc0c; proofs on a scratch cluster and one platform host; default not adopted
created: 2026-09-23
---

# Realities commit 5: the launchers are deleted, one request remains

Track 1.3d commit 5 (`lane-realities-one-lifecycle.md` §3 item 5, README §4 1.3d).
Every test runs through `seon.test/run` (commit 4); this commit deletes the
machinery that ran tests any other way.

## Commits

| Commit | What |
| --- | --- |
| `8bc917872` | Commit 5 proper (39 paths, +686 / −10,220) |
| `80e9828b8` | `bin/test --platform` boots HEAD with `nuke --force`; a fallback host refuses |
| `04da53689` | `acquire-context!` reads the live branch from the handle's connection; agent_test fixtures carry `:seon.agent/branch` |
| `2b1298f3a` | `program-digest` memoized by the program attributes' revisions (#24ab/#35) |
| `73d36d89f` | C1 hunk 2: a member over one second carries `:seon.profile/explanation` |
| `2a37fd8f5` | Testing-skill anchors after the profile hunk |
| `dce691447` | `arm!` arms what acquires: a refused row is recorded, never thrown (M9 ruling) |
| `2134db8b2` | A report's identity is its captured signature and position, so forgery still refuses |
| `8c47672af` | P0: publication-declared-schema-test quoted the deleted `seon.test.runner/run!`; the program graph refused default's adoption |
| `b13be40e7` | P0: a committed archive carries its own input inventory (`test-input-paths.txt` from `git ls-tree`) |
| `1aabc6be5` | The drift report omits an absent source commit; selection-test's published-path case keeps only its behavior |

## What changed

- **Deleted, with their tests:**
  - `src/seon/test/fast.clj`, `bounds.clj` and `selection.clj`;
  - `bin/test-fast` and `bin/_test-slot`;
  - the worker, claim, staged-result and published-base machinery in `runner.clj`
    (4,953 → 1,728 lines by a reachability script) and `cache.clj` (input readers only);
  - `--prepare-head-base`, which closes
    `docs/seon/issues/prepare-head-base-reads-the-live-checkouts-resources.md`;
  - `dev_cache.clj`'s test-input branch;
  - `fn.clj`'s non-declaration call-edge branch (the fn_test handoff).
  - Deleted test files: `test_runner_integration_test`, `test/bounds_test`,
    `test/published_selection_test`, `test_preparation_test` and
    `dev/base_export_bound_test`, plus the machinery tests in `runner_test`,
    `selection_test`, `test_cache_test`, `test_support_test` and `test_test`.
- **`bin/test`**
  - Plain `bin/test` is `bin/test-check`: one request over the cluster's prepl, starting no JVM.
  - `--platform` nukes a fresh `tmp/test-runs/platform-*` root from committed
    HEAD. It refuses the host if the nuke fell back to an older program. It then runs
    `seon.test/isolated-members` as one request (declared platform rows, members
    reaching a `:seon.fn/destroys` owner, file-backed fixtures), bounded by
    `seon.test/declared-bound-ms`. It deletes the root when green and keeps it when red.
  - `bin/test-check` gains `--policy`, `--changed`, `--include-long`,
    `--isolated` and `--time-limit-ms`.
- **Bounded release.** Admission and release go in batches of
  `seon.test/batch-limit` (64); each batch is its own run. Regression:
  `one-request-test` bounded batches (limit 2, three members, two runs of at most two).
- **`resolve-test`** uses `seon.sci.eval/acquired-database?`.
- **Reuse P1 (run bba05ce63419).** A restart from changed files leaves the cluster's
  program rows at the old commit while the JVM runs new code, so green was reused for
  a changed defn.
  - `select` now reuses nothing when the loaded source differs from the rows' `:seon.source/commit-id`.
  - It reports `:seon.test/loaded-source-drift`.
  - Regression: `evidence-earned-by-another-loaded-program-is-never-reused`.
- **Named `:seon.test/changed`** selects exactly the tests reaching the named seeds
  (`requested-reached`); before, 1,479 members were selected.
- **Report identity P1 (run df397c67dcc7).** A failure report's id named only its
  claim. An edit that moved a failing assertion therefore refused the whole request with
  `:seon.test/report-conflict` and left every later member pending.
  - `runner/report-row` now derives the id from the captured signature plus the
    position facts the signature excludes: line, reported file and contexts.
  - A moved claim is therefore another report, while other content under an unchanged
    signature at the same position collides and refuses. That is the acceptance of
    `docs/seon/issues/moving-a-failing-assertion-conflicts-with-its-immutable-report.md`;
    8bc917872's every-fact id lost the forgery refusal, and 2134db8b2 restored it.
  - Regression: `test-failure-facts-test/a-claim-reported-at-a-moved-line-records-another-report`,
    red on the old code (both lines `3fafcea174b3`) and green here (run 1a5afd5c6e8c, 6 of 6).
- **Live branch.** `acquire-context!` derived the live branch from
  `(:seon.cluster/name handle)`. agent_test's handle guessed that name over a
  branch that also held the host's config row, so an agent on its own cluster counted as
  isolated and refused with "Isolation requires a fresh owned branch."
  - The live branch is now `(get-in database [:config :branch])`.
  - The guess is deleted; `handle` and `arm-one!` name the test's cluster.
- **`program-digest`** (#24ab/#35).
  - It is now a function of the database value, memoized by the program
    attributes' revisions and then by the commit, in the projection's holder (call_preparation's pattern).
  - The derivation scans only program-attribute datoms since the seal.
  - Regression: `test-provenance-test/program-identity-excludes-results-and-includes-admitted-source`
    asserts that a result commit reads the held digest in under 100 ms.
- **`arm!`** no longer throws on a recorded refusal. It throws only when the database
  cannot store the record (`:seon.sci.eval/acquisition-recording-error`). Regression
  `agent-test/a-refused-core-row-is-recorded-and-every-agent-still-arms` is red on the
  parent and green here.
- `my.test/check` names changed seeds, supplied-documentation agents carry
  `:seon.agent/branch`, and the testing skill's anchors were re-verified at each commit.

## Proofs

These ran on the scratch cluster `tmp/realities-c5-root` (source
`tmp/realities-c5-src` = `git archive HEAD` plus exactly this lane's paths, checked
equal before each run), except the platform row. Default was never used.

| Proof | Result |
| --- | --- |
| `seon.test.one-request-test` (all, long included) | run 76eadd2aca96: 9 executed, 51 pass, 1 fail. The fail is `one-request-runs-each-member-as-an-isolated-agent` at 40.5 s against its 30 s bound (routed projection cost, see Limits); every behavioral assertion passes |
| Report identity regression | red on the old runner (same id at both lines), then green (run 37424306dba2) |
| program-digest memo (REPL) | 5,390 ms derived, 1 ms again, 0 ms after a result-only commit, digest equal to the derivation |
| Provenance regression | run 3c0c138d10ef: 6 of 6 behavioral assertions pass, including held < 100 ms |
| `seon.cluster.agent-test` | run eeda8e51557b: 16 executed, 5 reused. No isolation or missing-branch refusal remains; the remaining reds belong to other owners (Limits) |
| Core-fault regression | red on the parent ("Program acquisition refused.", run 4c455f87b815), then green (run 3eb0871405aa) |
| C1 hunk 2 | a 14.5 s member carries an explanation naming `seon.db/with-declarations` ×147, 7,854 ms inclusive |
| `bin/test --platform` from committed HEAD `80e9828b8` | stopped by the orchestrator after 10 of 150 members (404 s): the whole isolated tier is 150 members (79 platform rows, 52 fixture-observation, 64 reaching `populate-published-*`; declared bound 11,653 s), about 100 min serially. Shrinking the tier at its producer is scheduled after stability |
| Platform members reaching commit 5's own functions (`select`, `commit-results!`, `program-digest`), on a host nuked from HEAD 7722d6265 | 22 members (58 if the universal fixture entrances count), run 3b70708ba9e1: 425 pass, 46 fail, 13 error, 1 of 22 green, 2,251 s. The owned reds are fixed in `1aabc6be5`: a nil commit id in the drift report broke nested requests (my.examples), and selection-test's published-path case. The rest, not this lane's: every boot-heavy member (armed, boot, program-restart, oversight, config-application, eval-instrumentation) exceeds its 5 s bound at 90–220 s and several miss their events under that load; `mcp-test` finds no `seon.db.process/config`; selection-test's `:my.turn/namespace-unit` render contract and fn.clj's `declared-reference-edges` tuple contract refuse. Not rerun after `1aabc6be5` (the machine is saturated) |
| Static | `clj-kondo --lint src test script dev_cache.clj`: 0 errors (16.6 s) |

**From-zero boot row** (a schema resource changed, `seon.test.edn`). The platform
host nuked a fresh root from committed HEAD `80e9828b8` and was ready in **120 s**.
This row belongs in `docs/seon/issues/from-zero-boot-takes-minutes.md`; this lane was
not granted that file.

## TIMINGS (every operation over 1 s)

| Operation | Time | Note |
| --- | --- | --- |
| Scratch cluster start (restart after my sync deleted its working directory) | 163 s (ready-ms 116,151) | dependency class cache missed, `:pins-unavailable` (#64) |
| Platform host nuke to readiness | 120 s | from zero, committed HEAD |
| `init --dev default --changed <one test file>` | 38.5 s, 14.6 s | adoption, routed (M9 / move-to-head) |
| `program-digest`, per call, before the memo | 6.3–6.5 s | three calls per request; proportional to program rows changed since the seal (1,797 after adoption) |
| `derive-program-digest` after the scan restriction | 4.7 s (steps sum 1.6 s at the REPL; the rest is contract checks) | still proportional to program rows changed since the seal |
| One pure test's request | 19.6 s | selection plus admission plus three digests before the memo |
| `one-request-test` namespace | 212.7 s | 9 members, the long ones included |
| `agent-test` namespace | 371.9–446.4 s | durations dominated by routed costs |
| Provenance member | 11.6 s, 14.5 s | one real derivation plus the fixture |
| Core-fault member request | 12.4 s | |
| `clj-kondo` whole tree | 16.6 s | |
| `bin/test --platform` request, 10 of 150 members | 404 s (max member 121 s) | stopped; ~100 min projected |
| Proof host nuke to readiness | 140 s | from zero, HEAD 7722d6265 |
| Proof request (22 members) | 2,251 s | ~100 s per boot-heavy member |

Every row over 10 s is a defect: routed or filed below, never "expected".

## Limits and what is left

- **Durations over bound, not widened.**
  - `one-request-runs-each-member-as-an-isolated-agent` is 40.5 s against 30 s.
  - Most agent_test members also exceed their bounds.
  - The cost is the per-connection projection derivation: `seon.db/with-declarations`
    ×147, 7.9 s in one member, per the C1 explanation. It is proportional to the declaration
    population, and each member branch pays it on its own connection.
  - Incremental projection (1625fb9bc) plus db.clj passing a base (the
    speculative-context-consumer lane) should cut it; re-measure after that lands.
- **agent_test reds from other owners:**
  - the member branch inherits the host's `root` agent and its deferred trigger `6c6e909d`, which episode-cap and park/pause observe;
  - turn.clj's error operation is now `seon.turn/turn-completion-error`, where the tests expect `:seon.agent/turn-transform` or `:seon.agent/turn-start`;
  - "Agent creation did not arm" in the routing properties;
  - problems include "stale loaded Var" lines until an adoption.
- **Recording refusal leaves a reserved member uncompleted**
  (`docs/seon/issues/an-unfinished-test-run-hides-every-problem-family.md`). After the report
  conflict, `latest-results` refused cluster status with `:seon.test/population-unknown`
  (`problems-unavailable` on the scratch status). The report-identity fix removes that
  trigger; a recorded unfulfilled outcome for the refused member is still owed.
- **Hand-written program rows** in test_failure_facts_test, accretion_test and
  others lack `:seon.program/definition-digest` and `:seon.program/analyzed-source-digest`.
  This is the known class. The regressions here use indexed tests or pure functions instead.
- **Reuse after a restart** stays off until adoption, because each restart
  republishes a new commit id for unchanged files (boot owner).
- **`green?` refusing on a reused member** (M9 report): not reproduced on this tree.
  A `--ns` request reused 5 members cleanly, and select's `(green? member)` is the local
  green-member set. Awaiting the exact request form.
- **#64** landed in `2ff62cc0c`: babashka-process is pinned to `seantempesta/process`
  `seon-aot-guard` (e83ec5c, pushed by the orchestrator after the owner's approval).
  The key needed no change: `committed-source!` already writes `dependency-pins.txt`, and
  the earlier `:pins-unavailable` came from this lane's own `git archive` snapshot. What
  failed was the fill. Measured with `start --head` on a scratch root:

  | Step | Result |
  | --- | --- |
  | Start, cache miss (`:no-matching-cache`) | 53.7 s total, launch 49.0 s, ready-ms 13,115 |
  | Fill `clojure -T:dev-cache ensure-cache` in the archive | 74.6 s, 373 namespaces (before the fork it refused at 57.4 s on the cyclic load) |
  | Start, cache hit | 29.2 s total, launch 27.3 s, ready-ms 11,942 |
  | `nuke --force` | 121.7 s, ready-ms 86,549; a nuke skips the cache by design (`:nuke-reads-no-derived-state`) |

  After a hit, about 15 s of launch still runs before boot entry, where first-party source
  compiles on load. That cost is over 10 s and belongs in
  `docs/seon/issues/move-to-head-takes-a-minute-or-more-not-seconds.md`. The fill is a
  printed command, not automatic.
- **Orphan worker error schemas** (`:seon.test.runner/unknown-worker-command-error`,
  `worker-launch-failure-error`) have no producer after commit 5. Retiring them is a
  schema-resource change with its own from-zero proof, so they are left for that slice.
- **Default** needs a JVM restart to load the malli fork gitlink from a4e66fa10.

RESET NEEDED: no. Default was not touched.

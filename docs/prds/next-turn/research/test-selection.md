---
type: research
lane: test-selection
created: 2026-09-26
status: research — nothing implemented
authority: owner question 2026-09-26 (rank and filter impacted tests to bound what runs per change); CLAUDE.md "Laws for proof and performance" and the merge paragraph
---

# Test selection: bound and rank what runs per change

**Short answer.** There are three test sets per change, and they are not the same set:

- **Selected** (installed, two hops: tests calling the change or one of its direct callers).
- **Invalidated** (reuse is keyed on the *full static transitive* closure, so every test there loses its green).
- **Required at merge** (the selected set, plus one green test per replaced function).

The installed selection is already small for most changes (per recent commit: median 3 tests, p90 61). It
explodes only at about 20 hubs (`seon.db/*`, fixture helpers: 400 to 925 tests). The candidate list that is
"huge" is the invalidated set (per commit: median 246, p90 1,374). Those tests are never run and silently
become unverified.

The best step is **exactness before ranking**: record the definitions each test actually exercised. The
armed wrappers already count every call (`seon.profile`), so this costs almost nothing. Select and key
reuse by that recorded reach. Then **rank + stop at the first red** in the edit loop (System 1 orders;
the exact set still gates merge), and drain the rest in the background by rank.

## 1. What is installed (verified in source, HEAD 20b156860 + working tree)

### Selection: `seon.fn/call-reach`, `src/seon/fn.clj:1868-1900`

- **What it selects:** the seeds; their callers through `:seon.fn/calls` plus declared invocation
  edges; then the tests that call either set, or name one as `:seon.test/subject`; plus any seed that is
  itself a test.
- **Depth:** two hops, **not transitive**. The owner ruled this on 2026-09-26: "the tests of A and of A's
  callers". It landed as 20a87b1d1; the landing is 6a8e46ec6,
  `docs/research/agent-platform/landing/inv-test-selection-cost-opus-2026-09-26.md`.
- **Mentions select nothing:** a quoted symbol is not a call.
- **Callers:** `gate-set` (`:2000`), the map arity of `gate-sets` (`:1941`), `seon.test/reaching`
  (`src/seon/test.clj:1056`), and the install gate (`src/seon/turn.clj:3346`).
- **The other arity:** the positional `gate-sets` arity keeps the conservative full union closure, and
  only `destructive-reach` reads it (`test.clj:375`).

### `seon.test/select`, `src/seon/test.clj:692-1054`

**What changed, and against what:**
- The changed set is `changed-definition-symbols` (`:584`): entities touched since the comparison basis,
  kept only where `:seon.program/definition-digest` differs. Namespace binding changes widen to the whole
  namespace.
- A schema change under `:incremental` **refuses** with `:coverage-unknown` (`:827-832`).
- A first run with no scope, or `:all`, selects every test (`:852`).

**Reuse (`:958-996`).** A green member is reused only if all of these hold:
- a loaded commit is recorded;
- it was not reached by an *observed* change;
- its stored `:seon.test.member/reach-digest` equals the current digest.

That digest is over the **full static closure** (calls + references + subject + reached schema keys),
with each node's definition digest as the leaf. See `runner/reach-entry` (`src/seon/test/runner.clj:826-864`),
maintained incrementally from Datahike attribute revisions (`:774-825`). So an unchanged digest is
skipped, and a docstring edit changes the digest (`definition-digest` covers the doc).

**`verified?` (`test.clj:1535`)** is true only when all of these hold:
- the latest result is green;
- the input digest is equal;
- the reach digest is equal;
- the test is not a fixture observation.

**`stale` (`:1420`)** lists the tests whose `verified?` is false.

### Execution: `seon.test/run` (`test.clj:2188`)

- **Serial, alphabetical, one isolated branch per member.**
  - The only ordering seam is `ordered` (`:2311`): platform members first, then by symbol.
  - Batches of 64 (`batch-limit`, `:2108`) bound admission and release, not the count.
- **Stop reasons:** the loop stops on deadline, recording refusal, an unfinished member, or a **platform**
  red (`:2403-2440`), and releases the unstarted members (`release-not-started!`). An ordinary red does
  not stop it.
- **Durations:** per-member timings (`:seon.test.timing/run-ms`) are returned but **not stored**. The
  member schema has no duration (`resources/seon/schemas/seon.test.member.edn`).

### Per edit: the install gate

- The install gate (`turn/gate-function-install`, `src/seon/turn.clj:3328`) runs the whole two-hop
  `gate-set` for every contracted `defn` at the agent REPL.
- It does so through a **second runner**: `sci.eval/evaluate-candidate` (`src/seon/sci/eval.clj:4760-4796`)
  calls `(mapv run-candidate-test! …)`. The docstring says "all tests run even after a red result", with
  no count bound and no ordering.
- `my.test/check` (`src/my/test.clj:6`) sends a `:named` request through `seon.test/run`.

### Merge

**`prepare-merge!`** (`src/seon/cluster/source.clj:840-930`):
- it runs `candidate-gate!` (`src/seon/cluster.clj:2758`) on a fresh branch S, which covers the tests
  reaching the delta plus the issue's tests;
- it refuses when an issue test is missing.

**`accept-merge!`** (`:932-1000`) requires:
- every member of the named run green;
- the program unwritten since the run;
- every replaced function reached by **at least one** green member (`:970-973`).

**Gap against the law.** CLAUDE.md says "every current test reaching changed functions must pass". What
is installed is: every test **within two hops**. Tests at distance 3 and beyond lose their reuse, are
never selected, and pile up in `stale`. The owner traded this deliberately (landing §1). It is recorded
here because it is the real size of the problem.

### Recorded dynamic reach: schema without a producer

- `:seon.test/reach` exists: an indexed set of symbols (`resources/seon/schemas/seon.test.edn:2`),
  already checked by `select` (`test.clj:720`).
- The landing counted **0 datoms**.
- CLAUDE.md's "test evidence keyed by exercised definition digests" is not installed.

## 2. Size of the problem

### Static survey (independent of the landing)

- **Sources:** `tmp/next-turn-test-selection/reach.clj` over `clj-kondo` analysis of `src/` + `test/`
  (`kondo.edn`: 8 s; the script: 9.6 s). Output is in `reach.out`.
- **Graph:** 2,269 tests, 5,209 definitions (3,688 under `src/`), 25,114 project-internal call edges.
- **Edges:** var usages only, so quoted mentions are excluded, as installed.

Per `src/` function (n = 3,688):

| tests reaching it | mean | median | p75 | p90 | p99 | max | zero |
|---|---:|---:|---:|---:|---:|---:|---:|
| call it directly | 2.1 | 0 | 1 | 3 | 26 | 602 | 2,523 |
| **installed: it + direct callers** | 12.9 | 2 | 7 | 23 | 231 | 925 | 1,281 |
| 3 hops | 41.0 | 6 | 23 | 77 | 743 | 1,372 | 689 |
| full transitive (= reuse invalidation) | 476.8 | 162 | 710 | 1,481 | 1,776 | 1,944 | 355 |

This agrees with the landing's database survey (median 1, p90 16, p99 187 over 5,264 rows including
test-support). The kondo edges include value uses, which the stored graph did not yet count as calls.

Per **commit**, estimated:
- **Method:** the last 400 non-merge commits touching `src/seon`/`src/my`. Changed functions come from
  hunk headers with a Clojure `xfuncname` plus added `defn` lines, mapped onto the *current* graph.
- **Coverage:** 145 of those commits changed a live function.
- **Caveat:** this is an estimate. Renamed or deleted functions drop out, and a hunk header names the
  preceding form.

| per commit | mean | median | p75 | p90 | p99 | max |
|---|---:|---:|---:|---:|---:|---:|
| changed live functions | 2.5 | 1 | 3 | 5 | 16 | 28 |
| **installed selection** | 19.4 | 3 | 13 | 61 | 150 | 807 |
| invalidated (full closure) | 522.7 | 246 | 1,374 | 1,481 | 1,790 | 1,790 |

- **Big selections are rare:** 4 of 145 commits select 100 or more tests, and 1 selects 500 or more.
- **Hubs** at the installed depth, as `[function direct / installed / full]`:

  | function | direct | installed | full |
  |---|---:|---:|---:|
  | `seon.db/db` | 602 | 925 | 1,530 |
  | `seon.db/transact!` | 198 | 832 | 1,481 |
  | `seon.db/refused-read` | 0 | 807 | 1,643 |
  | `seon.db/current-database-value` | 0 | 794 | 1,643 |
  | `seon.db/q` | 363 | 743 | 1,573 |
  | `seon.db/call-with-custody` | 19 | 661 | 1,482 |
  | `seon.schema/handed-projection` | 330 | 492 | 1,750 |
  | `seon.config/defaults` | 96 | 464 | 1,579 |
  | `seon.id/id` | 44 | 391 | 1,677 |

  In test/: `with-database` 1,297 and `transacted!` 871. Every hub is a database or fixture primitive that
  tests truly execute.

### Live check (one read-only MCP query, JVM, default, basis 536870975, 2,267 tests)

| `gate-set` | tests | time |
|---|---:|---:|
| `seon.db/q` | 742 | 41 ms |
| `seon.db/db` | 924 | 12 ms |
| `seon.test/select` | 7 | 1 ms |
| `seon.fn/gate-set` | 14 | 1 ms |
| `my.issue/status` | 0 | 0 ms |

- `reverse-closure` for `seon.test/select` returned 64 affected functions and 7 tests, in 17 ms.
- My first query was refused by the armed contract: I passed seeds as a vector, not a set. That was my
  mistake, not a platform defect.
- **Selection cost is not the problem. Execution is.**

### Execution cost (estimate)

- **Measured basis:** landing §5 measured 1.5 s mean body over 27 members of one namespace, with about
  45 ms of harness per member. It also measured about 1 s of request floor per changed program
  (`fix-test-request-cost`).
- **Serial totals, using 1.5 s per member:**
  - median commit (3 tests): about 5 s;
  - p90 commit (61 tests): about 90 s;
  - `seon.db/db` (925 tests): about 23 min;
  - a full invalidated median (246 tests): about 6 min.
- **Against the law:** the sub-second law is met only at the median, and only with reuse.

## 3. Prior art, with numbers

- **Facebook Predictive Test Selection** (Machalica et al., ICSE-SEIP 2019,
  [arXiv 1810.05286](https://arxiv.org/abs/1810.05286)).
  - **Model:** a gradient-boosted decision-tree classifier per (change, test).
  - **Features:**
    - change: modification counts of touched files over 3/14/56 days, file and target counts,
      extensions, distinct authors;
    - test: historical failure rates over 7–56 days, project, number of test cases;
    - cross: **minimal build-graph distance** from touched files to the test, and shared path tokens.
  - **Results:** fewer than one-third of the build-dependency-selected tests run. It catches over 95% of
    individual failures and over 99.9% of faulty changes, and halves infrastructure cost.
  - **Flakiness:** a failed test is retried up to 10×, and only consistently failing tests are labelled
    failures.
- **Google TAP** (Memon et al., "Taming Google-Scale Continuous Testing", ICSE-SEIP 2017,
  [paper](https://research.google.com/pubs/pub45861.html)).
  - **How rarely tests fail:**
    - of 5.5 M affected test targets, only 63 K ever failed;
    - 1.23% of executions found a breakage or a fix;
    - pass:fail per change is 99:1;
    - more than 99% of executions pass or flake.
  - **Distance:** targets more than 10 dependency edges from the change "hardly ever break". Running only
    targets within MinDist ≤ 10 or ≤ 6 saved 42% or 55% of resources and **missed no breakage** in their
    simulation.
  - **Change frequency:** frequently modified files are more often in breaking changes.
  - **Seon's case:** the two-hop cut is a much more aggressive MinDist ≤ 2. Google's data says distance
    is a strong ranking feature, but not that 2 is safe.
- **Elbaum, Rothermel & Penix** (FSE 2014, "Techniques for improving regression testing in CI
  environments", Google dataset). *From memory, not re-verified.*
  - pre-submit selection by a **failure window** (run a test if it failed within the last W_f) and an
    **execution window** (run it if it has not run within W_e);
  - post-submit prioritization by the same windows.
  - These are cheap heuristics with most of the benefit, and the model for a System 1 ranker with no
    learning.
- **Regression test selection.** *From memory, not re-verified.*
  - **Ekstazi** (Gligoric, Eloussi & Marinov, ISSTA 2015): *dynamic* class-level file dependencies,
    recorded per test. It reduced end-to-end time about 32% on average (about 54% on longer suites) and
    is safe, modulo nondeterminism and non-code inputs.
  - **STARTS** (Legunsen et al., FSE 2016/2017): *static* class-level RTS, roughly comparable to Ekstazi.
    Static **method-level** selection was less safe or effective.
  - **What this means for Seon:** Seon's definition-level graph is method-level, and the dynamic
    recording is what makes that granularity safe.
- **Test-case prioritization.** *From memory.*
  - APFD (Rothermel et al., TSE 2001) is the standard metric: the average percentage of faults detected
    over the fraction of the suite run.
  - Learning-to-rank for CI: Spieker et al., RETECS (ISSTA 2017, reinforcement learning on history);
    Bertolino et al. (ICSE 2020) compare learning-to-rank against ranking-to-learn.
  - Recurring finding: **recent failure history and duration** dominate. Coverage and distance help on
    new or rarely failing tests.
- **Flaky tests.** *From memory.*
  - Luo et al. (FSE 2014): async waits, concurrency and test-order dependence are the main causes.
  - Google (Micco 2016) reported about 1.5% of runs flaky and about 16% of tests with some flakiness.
  - Standard handling: rerun to classify, and quarantine.
- **Commercial.** Launchable and Gradle Develocity "Predictive Test Selection" ship learned per-test
  failure models on change metadata. Their vendor claims are around "90% confidence from about 20% of
  tests" (marketing, unverified).

## 4. Design space under Seon's laws

**Fixed constraints:**
- merge requires the reaching tests green;
- unknown or red never reuses green;
- a test fails beyond its bound;
- a change is decided by digests.

So **membership, reuse and the verdict are exact**. Only **order, timing and per-edit early answers** may
be System 1.

| decision | exact or System 1 | today | proposal |
|---|---|---|---|
| which tests must be green to merge | exact | two hops + one-per-function | recorded dynamic reach ∪ static fallback |
| which greens are reusable | exact (digest) | full static closure digest | digest over recorded reach (plus static macros/defs/schemas) |
| order of execution | System 1 | alphabetical | score: distance, subject, last red, co-failure, duration |
| when to stop in the edit loop | System 1, with a typed partial verdict | never (install gate) or on platform red | stop at first red; top-K or T-second budget, rest `:pending` |
| what runs now vs in the background | System 1 | the invalidated remainder never runs | drain `stale` by score at idle, one serial worker |
| is a red flaky | System 1 flag; the verdict stays red | none | rerun once and record both; merge still needs a green |

### Exactness improvements that shrink sets without ranking

1. **Recorded (dynamic) reach per test.** This is the Ekstazi mechanism at definition granularity.
   - **The data already exists.** Every armed wrapper increments its cell's `LongAdder`
     (`src/seon/profile.clj:107-126`). `profile/begin` snapshots every cell's call count into one long
     array (`:181-196`). So "the cells whose call count grew between body start and body exit" is the
     test's exercised-definition set, with its definition digest (`:seon.profile/digest`), at zero added
     per-call cost.
   - **Storing and using it:** store it in the existing `:seon.test/reach`, and select by one avet read
     per changed symbol. Tests with no recorded reach fall back to static selection.
   - **Safety argument (standard RTS):** for a deterministic test with unchanged external inputs
     (`:seon.test.run/input-digest` already covers those), a run that touched no changed definition
     behaves the same.
   - **Precautions:**
     - **Include static edges for what wrappers do not see:** macros (expanded at compile time), plain
       `def` values (for example `seon.config/defaults`, a p99 hub), unarmed definitions and schema keys.
     - **Concurrent noise:** activity on other threads while serial members run only *over*-includes,
       which is safe.
   - **Effect:**
     - it replaces the two-hop cut with the *true* depth, closing the law gap;
     - it very likely shrinks the invalidated set below the full static closure (untaken branches,
       dead paths). *Estimate, unmeasured.*
2. **Executable digest (docstring-only edits select nothing).** Priced in the landing's §3, option 1:
   about 55 lines, a derived digest without doc. Small and exact.
3. **Contract-unchanged body edits** are *not* exact. A contract is weaker than behaviour: a caller's
   test can depend on a value the contract allows to change. Use it as a ranking feature (a changed
   `:seon.fn/spec` raises the score of callers' tests), never as a filter.
4. **Arity or branch granularity.** Wrappers know the arity (`seon.instrument` records
   `:seon.instrument/arity`), but digests are per definition. There is little gain until a definition
   digest exists per arity. Not recommended now.

### Ranking features available from stored facts (System 1, heuristic first)

- **Distance:**
  - direct caller (1) vs caller's caller (2);
  - `:seon.test/subject` equals the change;
  - the test lives in the changed function's namespace test ns.
- **History:**
  - latest member result red (`currently-failing-functions`, `fn.clj:2018`);
  - **co-failure**: for past red members, `changed-since-green` (`test.clj:73`) names the definitions
    that changed since that test's last green. That list is the change→failure coupling. Count
    (definition, test) pairs.
- **Duration:** needs `:seon.test.member/run-ms` stored. This is a new accreted key from timings the
  runner already computes (`test.clj:1975`).
- **Change kind:** spec changed vs body only; newly added test (always first).

Order: Elbaum-style windows plus distance first. A learned model (logistic regression or GBDT over the
same features) comes only once a few hundred red members exist. How many red members exist is
**unknown**; one query answers it.

## 5. Top 3, with costs (all estimates) and the smallest falsifying experiment

### ★1. Record dynamic reach from `seon.profile` cells; select and key reuse by it

- **Change:**
  - In `member-result` (`test.clj:1958`), take `profile/begin` before the body and diff after it.
  - Write the grown cells' symbols to `:seon.test/reach`. The schema exists; one more datom set is
    written in the same completion transaction.
  - `call-reach`: tests whose recorded reach contains a changed symbol, ∪ the static two-hop for tests
    with no recorded reach, ∪ static edges to macros and `def`s.
  - The reach digest is over recorded symbols plus the static schema/macro part.
- **Cost:**
  - **Time:** 2–3 engineering days.
  - **Lines:** about +70 to +100 (runner bracket, recording, selection union, digest leaf). Once
    proven, it lets go of the static full-closure reach index, `runner.clj:690-945` (about 250 lines),
    plus the positional `gate-sets` arity and the non-union branch of `gate-sets-in` (about 15). Net is
    likely negative.
  - **Per-member overhead:** two passes over about 5–10 K cells, about 0.1–1 ms. Selection stays one
    avet read per changed symbol, about 1 ms.
  - **Storage:** 2,267 tests × reach size, perhaps 100–300 symbols, is about 0.2–0.7 M datoms.
- **Risk:**
  - **Misses (unsafe):**
    - unarmed code paths (anything reached without a wrapper);
    - reach recorded under an old program;
    - SCI-interpreted definitions whose context cells are not in `live` for the member's fork.
  - **Over-inclusion (safe but noisy):** concurrent agents during a member's run.
- **Falsify (half a day, REPL on a scratch branch, no source edit):**
  1. For 20 sampled tests (stratified: hub-fixture tests, plain tests), bracket a `seon.test/run
     :named` with `profile/begin` / the diff.
  2. Compare |dynamic| with the static full closure and the two-hop set.
  3. Replay the 145 commits' changed functions against the sample.
  - **Falsified if** the median dynamic reach is at least 0.8× the static full closure (no shrink);
    **or** if more than 5% of statically called, armed functions on a path the test's assertions cover
    are absent (a wrapper blind spot); **or** if the overhead exceeds 5 ms per member.

### 2. Rank, then stop at the first red, in the edit loop; typed partial verdict; full set at merge

- **Change:**
  - Replace the sort key in `ordered` (`test.clj:2311`) with a score over the §4 features.
  - Add stop cause `:first-red` for `:named` edit-loop requests. The existing `::stopped` /
    `release-not-started!` path handles the rest.
  - Add an optional `:seon.test/budget-ms` or top-K. Members not run are returned `:pending`, so
    `passed?` stays false: never green.
  - Route the install gate through the same ordered runner instead of `(mapv run-candidate-test! …)`
    (`sci/eval.clj:4793`). That deletes a second test loop (the one-REPL-path / one-test-system law).
  - Merge (`prepare-merge!`) keeps running every member.
- **Cost:**
  - **Time:** 1.5–2 engineering days.
  - **Lines:** about +40 to +60 added, about −20 to −40 deleted (the candidate loop).
  - **Latency:** unchanged when green; on red, time-to-first-red falls from about rank/2 × 1.5 s to
    about 1–3 members, *if* ranking works.
- **Risk:**
  - early stop hides further reds until merge (acceptable: the verdict is red either way);
  - a budget answer could be misread as green (prevented by the typed `:pending`).
- **Falsify (half a day, read-only):** replay recorded runs that contain red members.
  1. Order each run's members by the heuristic score, then alphabetically.
  2. Compute APFD and the rank of the first red.
  - **Falsified if** the heuristic's median first-red rank is not in the top 20% of its run, or its
    APFD gain over alphabetical is under 0.1.
  - **Or:** if fewer than about 30 red runs exist, the history features are premature. Ship distance
    plus duration only.

### 3. Drain the invalidated remainder in the background by rank; store durations

- **Change:**
  - `seon.test/stale` already lists unverified tests. An idle-time, one-worker, `:incremental` request
    takes the top-N by score (respecting the machine-load rule: serial, low priority).
  - Store `:seon.test.member/run-ms`, which also feeds the duration feature and makes long tests visible.
  - Until ★1 lands, this is what makes the tests at distance 3 and beyond eventually proven, not
    silently unverified.
- **Cost:**
  - **Time:** 1–1.5 days.
  - **Lines:** about +40 to +60, with no deletions. That is justified only as the bridge to ★1, and
    shrinks once dynamic reach bounds the remainder.
  - **Load:** background CPU equal to one serial member at a time. It never blocks an edit.
- **Risk:**
  - machine load: the owner's frame-rate rule caps it at one worker;
  - it re-runs hub tests often if hubs change often (Google's finding: frequent files break more).
- **Falsify (1 hour, read-only):** run `seon.test/stale` on default after a day of work.
  - **Falsified (not worth it) if** the stale count stays under about 50, or if the stale tests at depth
    3 and beyond never go red when run once (Google's MinDist result says they rarely will).

**Not in the top 3:**
- **Parallel members:** branches are cheap, and this gives an N× wall-clock cut. It conflicts with the
  load cap; ask the owner.
- **A learned GBDT:** premature without red-history volume.
- **Contract-unchanged filtering:** unsafe.

## Evidence and commands

- `clj-kondo --lint src test --parallel --config '{:output {:format :edn} :analysis {:arglists false} :linters ^:replace {}}' > tmp/next-turn-test-selection/kondo.edn`
  (7.9 s wall).
- `bb tmp/next-turn-test-selection/reach.clj` (9.6 s wall; output in `tmp/next-turn-test-selection/reach.out`).
- **MCP query (JVM, default):** `seon.fn/gate-set` for six symbols and `seon.fn/reverse-closure` for
  two, with the `System/nanoTime` timings above. The first attempt was refused by the
  `:seon.fn/gate-request` contract (vector seeds). That was my error.
- **Not verified:**
  - no test was run;
  - dynamic reach sizes, the red-history volume and the profile-diff overhead are unmeasured;
  - the Elbaum, Ekstazi, STARTS, APFD, flaky-test and vendor numbers are cited from memory. Facebook and
    Google were read from their papers.

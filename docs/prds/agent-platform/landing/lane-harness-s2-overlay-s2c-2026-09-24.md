---
type: landing
status: committed bbb2efd86 464bca11b 41f2f0b53 4da02fcea and a bounded-refusal fix, code only (owner load rule); unadopted; no test request run
lane: harness-s2-overlay-s2c
created: 2026-09-24
design: docs/research/agent-platform/one-test-harness/design-2026-09-24.md §2 steps 1 and 4, §9c row S2
review: docs/research/agent-platform/one-test-harness/astra-review-2026-09-24.md findings 1 and 3, "Open question"
prior: docs/prds/agent-platform/landing/lane-harness-s2a-clean-world-2026-09-24.md ("not clean yet" item 3)
---

# Lane harness-s2-overlay-s2c (2026-09-24)

**What exists and the approach.** S2a gave a cluster's request a clean world: the published
commit plus boot's facts. Isolated executions still branched from their own operational value.
Now every request does the same thing. It builds the published base, adds boot's facts, then
adds the execution's differing program rows (the overlay). That world is a shared checkpoint
branch. The branch is named by its content and prepared once. The registry's roster
serializes who prepares it, and it is read only when a fact shows it is complete.

## Broken or unproven first

- **Nothing new was loaded or run through `seon.test/run`** (load rule). The test files lint
  clean. The overlay and checkpoint functions were probed directly: I extracted the committed
  forms, evaluated them in a throwaway JVM namespace (`lane-s2-scratch`, since removed), and ran
  them against default's store on disposable branches (since retired). No default Var was
  redefined.
- **First preparation takes 1.7–1.8 s.** It is spent in `seon.config/apply-compiled!` (778 ms,
  mostly `seon.reconcile/plan`) and four transactions (1,166 ms). S2a ran these same writes on
  **every** request, where its landing estimated "tens of ms". Now they run once per checkpoint
  identity. They are still over a second. I filed
  `docs/seon/issues/test-world-preparation-spends-seconds-in-config-apply-and-unmemoized-reads.md`.
- **Cluster requests change too.** Default's requests now also carry default's own differing
  rows. Live default holds `my.agents.root/largest` and the `my.agents.root` namespace. There
  is one path now, with no mode branch. So I changed S2a's `clean_world_test`: it now asserts
  that the member's full digest map equals the live cluster's, where it used to assert
  published-only. That comparison reads live default at test time. An agent admitting a row
  during the run would make it red.
- **Written-by facts do not carry.** The overlay carries program-partition attributes only.
  `:seon.ns/steward` (`:seon.program/written-by`) names the steward agent, and the clean world
  has no such agent. Without this, the overlay transaction was refused with "Nothing found for
  entity id [:seon.agent/id "root"]". This follows the ruling that inherited agents disappear.
- **Pushback is a thrown ex-info.** A second caller that finds the checkpoint incomplete gets
  an ex-info with `:seon.error/expected :seon.test/prepared-checkpoint`. It is not a declared
  `seon.test/run` error alternative. The same goes for S2a's "no adopted source commit". A
  declared schema belongs in `resources/seon/schemas/seon.test.edn`, which this lane does not own.
- **Remaining race windows are loud, not silent:**
  - A sweep can retire a same-cluster checkpoint that another caller has just created
    (`branch!`) but not yet opened. That preparer's open or write then fails and throws.
  - If a failed preparation's own retire also fails, that identity refuses for the life of the
    JVM, with the causes suppressed on the thrown failure.
  - A request that read an old head and has not yet branched from it relies on the commit
    surviving until `collect!`. Retirement removes only the roster name.
- **Materialized commit values are not released.** `source/database` and `source/commit-database`
  are called per request (base, and each completion read). `d/release-materialized-db` belongs
  to `seon.cluster.source`, as in `prepare-merge!`. Whether this retains memory is unmeasured.
- The new tests' long bounds are **declared, not measured**: 30 s and 20 s, modelled on the
  sibling nested-request tests (13.8 s measured). Root replaces them with measured clocks.

## Changes

1. **bbb2efd86: scoped first run (root's added item).** `first-run?` widened every
   incremental request that had no green history to every test. Clean member worlds never have
   history, so each nested request selected 2,139 tests. Now widening applies only when the
   request names no changes, tests or namespaces (`scoped?`, `src/seon/test.clj` `select`). The
   change is +4/−1.
   - Regression: `seon.test.scoped-first-run-test` (49 lines). It admits two functions and two
     tests into a fresh branch that has no runs. An incremental request naming `leaf` should
     select only `direct`, within 5 s.
2. **464bca11b: S2-overlay.** New `program-overlay-tx world candidate`. Entity ids differ
   between lineages, so declarations are compared by stored `:seon.program/definition-digest`.
   - It makes one `db/q` per value: 0.5 ms on 10,779 declarations, against 28–44 ms for the
     armed `db/datoms` read.
   - A digest that one side holds and the other lacks names an added, changed or retracted
     identity. Only those identities are read (`seon.fn/published-index-rows`) and replaced
     (`seon.fn/reconcile-tx`). An identity the candidate lacks is retracted with
     `:db/retractEntity`.
   - Affected callers need no copying. Member acquisition (`sci.eval/acquire!`) interprets
     changed rows and their callers.
   - The overlay lands in the same transaction as the source record. The
     `(= :isolated (:seon.agent/mode execution))` own-value branch in `run` is deleted. The
     change is +65/−18.
3. **41f2f0b53: S2c.** New `checkpoint-head` and `prepare-checkpoint!`, and a rewritten
   `clean-request-handle`.
   - The branch is `:checkpoint-<cluster>-<digest>`. The digest covers base, cluster, the
     sorted effective config, the process and the overlay rows.
   - It reads the head when complete. Otherwise the one `registry/branch!` caller that gets
     `created? true` prepares it. Everyone else is pushed back by name.
   - Completion is the source record `{:seon.cluster/name c :seon.source/commit-id base}`,
     written last together with the overlay, and read from the raw commit value (9–12 ms warm).
   - The process is part of the name, so an incomplete checkpoint always belongs to a live
     preparer in this JVM. A dead JVM's checkpoints are never reused, and the sweep retires them.
   - The request then owns a private isolated branch off the complete head. That branch roots
     the commit for its members until `release-context!`. This is the GC handoff.
   - Older `checkpoint-<cluster>-*` branches are retired. Datahike refuses to retire a connected
     branch; only that refusal (`::registry/cluster-connected`) is caught.
   - No lock, flag, atom or cache was added. The change is +90/−32.
- **Net:** source +159/−51 in `src/seon/test.clj`. Tests: +49 (new), +127 (new), and 22 lines
  changed in `clean_world_test`. No other owned file changed: `cluster.clj`, `registry.clj`,
  `agent.clj` and `sci/eval.clj` needed no edit.

## Evidence (default pid 39156, root /Users/sean/src/seon, JVM mode, throwaway namespace)

| probe | observation |
|---|---|
| full digest-map compare, default vs its adopted commit | 158/168 ms each. It differs by `[:seon.fn/sym my.agents.root/largest]` and `[:seon.ns/name my.agents.root]` |
| overlay onto the published commit, candidate = live default with one base test retracted | tx of 15 datoms, including `[:db/retractEntity [:seon.test/sym seon.id-test/printed-identity-ignores-the-callers-print-bindings]]`. The world's full digest map after `db-with` **equals** the candidate's |
| overlay cost | equal programs 10.6 ms. Three identities 124–144 ms: `program-attributes` 50 ms, `published-index-rows` 53 ms |
| checkpoint preparation on a disposable branch | head before = nil. Prepared world's full digest map = live default's, `:seon.agent/id` rows = []. Preparation 1,700 / 1,807 ms |
| refused preparation (overlay writing an absent attribute) | throws "The test world's source record was refused."; branch retired; `checkpoint-head` nil |
| completion read | 8.6 ms incomplete, 54 ms then 12 ms complete. It was 116 ms through `source/database`, whose projection derivation it no longer uses |

## Follow-up after root's first run (4da02fcea)

**Root's reds and their causes.** Each was read from recorded failures on default, then
reproduced in a throwaway namespace.

1. **`scoped-first-run-test` selected `#{}`.** The fixture wrote a second
   `:seon.source/digest` seal. Selection then refused with
   `:seon.test/input-evidence-unavailable`, and a refusal has no members. The selector change
   itself works: on the published base plus the fixture rows it selects `scoped.fixture/direct`.
   It also selects the file's own test, whose row names `scoped.fixture/leaf`. The fixture no
   longer writes a seal. It now asserts no refusal, the fixture's own members `#{direct}`, and
   fewer than 5 members overall.
2. **`overlay-world-test`.** The fixture functions had no contracts, so admission refused them
   with "declares no :malli/schema". The helper then passed a nil row to
   `seon.program/declaration-row`. The functions now declare `[:=> [:cat] :int]`, and the helper
   writes a row only when the evaluation produced one. Both long tests now use the measured
   7.1 s request as their basis, with bound 15 s.
3. **"The test world's source record was refused"** (in `seon.test-reaching-test`).
   - Fixture rows name their file by identity (`:seon.fn/file [:seon.fn.file/relative-path p]`).
     File rows are a recomputed family, not digest-compared, so the published base lacks a new
     fixture file and the lookup refused.
   - The overlay now also carries any recomputed row its rows name that the world lacks; the
     world keeps its own file rows. Probe: live default plus a new agent file referenced by
     `my.agents.root/largest` gives a 17-datom overlay that applies onto the published base,
     with the file row present.
   - The thrown error now includes the refusal's message and offending value.
4. **The other reaching reds are mostly the preparation cost.**
   - `failed-results-with-native-datoms-remain-recordable` measured 5,112 ms and
     `fixture-observations-remain-stale` 6,258 ms, both against a 5 s bound. Each nested request
     has a new overlay, so it pays the 1.8 s first preparation (see the filed issue).
   - `reach-digests-follow-only-changed-closures:100` (1 computed where 0 was expected) and
     `the-declared-opt-in-includes-the-long-test:541` (executed 0) are not diagnosed here.
5. **`one-request-test` `passed? false` with 0 fail and 0 error.** This is consistent with
   declared-long members being excluded when the run omits `--include-long`. Not diagnosed.

**Rerun (root):**
- `bin/test-check default --test seon.test.scoped-first-run-test/a-first-incremental-request-naming-a-change-selects-only-its-reach`
- `bin/test-check default --ns seon.test.overlay-world-test --include-long`
- `bin/test-check default --ns seon.test-reaching-test`
- `bin/test-check default --ns seon.test.one-request-test --include-long`
- `bin/test-check default --test seon.test.clean-world-test/a-member-world-is-the-loaded-program-with-boot-facts-only`

## Second follow-up (default OOM at 23:27Z)

- **Bounded refusals.** The source-record and overlay-read throws now carry the refusal's
  message, its operation, and at most 400 characters of the offending value
  (`seon.render.value/bounded-text`), with the overlay's entry count in the message. The whole
  report is no longer ex-data. 4da02fcea had put the whole overlay transaction inline, which a
  printing runner turned into a string over 2 GB.
- **File rows the overlay references but does not carry.**
  - The earlier scan looked only at the rows. It now inspects the reconciled transaction
    itself. Any 2-element lookup on an identity attribute (map entries excluded) that neither
    the world nor the transaction's own rows hold gets its row added, and the transaction is
    recomputed.
  - Identities that still cannot be supplied refuse with the first five named. So the writer's
    "Nothing found for entity id" cannot reappear unnamed.
  - Root's failing `tmp/reaching-source-*/probe.clj` case did not reproduce. Four candidates,
    each overlaid onto the published base, applied, and each world's full digest map equals its
    candidate's (101–171 ms):
    - live default;
    - live default plus one retracted test;
    - an agent file referenced by `my.agents.root/largest`;
    - a real `seon.fn/rows` index of a new tmp file.
  - Its exact cause is therefore unproven. The named refusal will identify it on the next run.

## Third follow-up (root: concurrent OOM, preparation cost, one-request reds)

1. **The OOM sink was this lane's own test.** `concurrent-first-preparation-...` printed
   `(pr-str outcomes)` as an assertion message. Its outcomes are execution handles, which hold
   connections, database values and SCI contexts, so printing them builds a string over 2 GB.
   - The test now prints only a summary: `:handle` or the refusal message.
   - It uses a cluster of its own (seed-cluster! plus a source record), so its checkpoint is
     genuinely first.
   - The losing caller's refusal carries only the branch keyword. Every test-world throw goes
     through `world-refusal`: message, operation, and at most 400 characters of the offending
     value.
2. **Preparation cost.**
   - The checkpoint now holds boot's facts only. Its identity is base, cluster, sorted
     effective config and process, with the overlay no longer part of it. Every request with
     equal inputs therefore shares one preparation, measured at 950 ms.
   - Each request writes its overlay onto its private branch after acquisition. That write
     measured 935 ms and 896 ms for 15 datoms, and the world's digest map equals the
     candidate's.
   - Before this change, every distinct overlay paid a fresh 1.7–1.8 s preparation. Now a
     request with an empty overlay pays nothing, and a non-empty overlay pays about 0.1 s to
     compute plus about 0.9 s to write.
   - The write cost is `seon.db/write-report-error` (834 ms over 6 writes). It is added to the
     filed issue.
   - `seon.program/program-attributes` memoization belongs to `src/seon/program.cljc`, which
     this lane does not own.
3. **The two one-request reds** (`test/seon/test/one_request_test.clj` is free in the ledger,
   not this lane's file, so it is not edited).
   - `a-stopped-requests-unstarted-member-...:351`: `b-runs-until-released` waits on a
     `:seon.test.one-request-test/marker` datom written on the execution branch. That datom is
     operational data, and clean worlds do not inherit data (owner ruling, design §2). So B
     loops again in the second request, the request stops on B, and `c-after` stays pending.
     - Proposed fix for the file's owner: release B through the program instead. Redefine
       `b-runs-until-released` as `(clojure.test/is true)` with `define-agent-test!` before the
       second request. That is a program row, so the overlay carries it. An S2b seed would
       also do.
   - `an-unexited-member-...:203` (`executed-count` 1 where 0 was expected) is not diagnosed.
     Admission reads the recording connection, which this lane did not change. A failure of the
     same assertion was already recorded when I first read default's results.

## Fourth follow-up (commits b0e68bc03, 1e39e06a0, 2b2c2bf39, 8da3dcd16)

- **program-attributes (b0e68bc03).** It is now cached per projection through
  `seon.schema/projection-cache-value`: 16.8 ms to derive, 0.12 ms on a hit (live projection).
  A freshly derived projection still derives it once.
- **The arity gate inside write-report-error (1e39e06a0).**
  - Profile of one 15-datom overlay write on a fresh branch: 917 ms in total, of which
    `write-report-error` took 318 ms. Within that, `arity-base` took 185 ms and
    `write-owned-values-error` 96 ms.
  - `arity-base` built its cache key from all ~78,000 arity datoms, which alone took 114 ms.
    It now keys a committed value by its Datahike commit id. The first write on a fresh branch
    of a shared commit is committed; later ones keep the content key.
  - About 600 ms of the write is in `transact-call`, outside write-report-error. That part of
    `db.clj` is not in this lane's grant. No after-measurement was possible without loading
    the change.
  - Scope note: `arity-base` sits below write-report-error. The grant says "write-report-error
    only", so root should confirm this edit is within it.
- **one-request :351 (2b2c2bf39).** B is released by redefinition. **:203** (a held request
  executes 1 member) is not diagnosed.
  - In the code, admission holds a member that has `completed-tx` and no `terminated-tx`, which
    the fixture's `record! false` writes.
  - Admission reads the recording connection, which S2 does not touch.
- **Reaching tests (8da3dcd16).** An incremental request's changed tests are now members
  themselves. `an-in-process-check...:522` and `the-declared-opt-in...` had selected nothing
  since the scoped first run landed.
  - `failed-results...:234` and `fixture-observations...:205` fail because the recorded reach
    digest is nil. That digest is attached by `seon.test.runner/commit-results!`, which another
    lane holds. A probe on an equivalent value (live default plus an indexed tmp file) computes
    `runner/reach-digests` in 18 ms, so that attachment is the next place to look.
  - Bounds are not re-declared. Raising a bound needs `:seon.test/long`, which moves the test
    out of the default tier. Measure after these commits before marking anything long.

## Fifth follow-up (e1f50b058)

- **The reach digest is recorded.**
  - `seon.test.runner/commit-results!` computes reach digests on the completion's
    `:seon.db/db`, which is the execution value, not a member world.
  - `complete-members` stores them as `:seon.test.member/reach-digest` (runner.clj ~1419), and
    `recorded-member-results` returns that key.
  - Reuse (`test.clj` ~858) and `verified?` read the member key from the recording connection.
  - `:seon.test.run/loaded-commit-id` comes from the recording cluster row at the writer.
  - The reaching test compared `:seon.test/reach-digest`, the pre-f3a4b33d9 key. **No runner
    change.**
- **Four stale reaching assertions were updated to their owners' rules** (each predates S2):
  - A data-only write derives nothing, following the content-keyed index (7154084b9).
  - An observed fixture never verifies (b0b5eedfe).
  - The member reach key is `:seon.test.member/reach-digest` (f3a4b33d9).
  - An excluded long test leaves `passed?` false (57f02fd5b).
- **one-request :87 (second identical request re-executes) and :203 (held member executes):
  not diagnosed.**
  - For reuse, the source requires all of the following on the recording branch: the latest
    member green with a reach digest, a `loaded-commit-id`, no `:seon.test/loaded-source`
    drift, the member not reached from changes since the last completed basis, and an equal
    current reach.
  - Admission holding a member requires another run whose program digest and input digest
    equal the new request's (`admit-run`, test.clj ~1168).
  - Next probe (root or next lane): in each test, after the second request, read the held or
    reused member's run for `:seon.test.run/program-digest`, `:seon.test.run/input-digest` and
    `:seon.test.run/loaded-commit-id`, and compare them with the second request's provenance.
    If the program digest differs, some write between the two requests touched a program
    attribute; name it with `runner/program-written-since?`.

## Sixth follow-up (266187a0e, 2f4ea22d9)

- **(a) in-process check (266187a0e).** Probe on a `db-with` of live default plus two indexed
  tmp files: changed `[long cheap]` selected nothing. Both rows are `:core` under tmp roots, so
  only agent rows, the `test` root or named identities were eligible. A test named as changed
  in an incremental request is now eligible from any root.
- **(b) One nested request by phase.** A named request for one fresh `seon.id-test` member on
  an MCP branch of default, warm, measured by the armed profiler, 4,155 ms end to end:

| phase | ms | proportional to |
|---|---:|---|
| checkpoint lookup (`checkpoint-head`) | 9 | one raw commit read |
| overlay compute (`program-overlay-tx`) | 35 | two digest queries (10,779 declarations) and the changed rows |
| overlay write (one 15-datom `transact!`) | ~900 | the checked writer. The arity base missed at 1,856 ms under 1e39e06a0's commit-only key; it is now commit, then content |
| request-branch SCI acquisition | ~500 | **cut in 2f4ea22d9**: nobody evaluated in it |
| selection (`select`) | 702 | `selection-facts` 564 ms, over the branch's recorded run history |
| admission (`admit-run`) | 354 | `runner/program-written-since?` 186 ms, program attributes × history index |
| member acquisition (`sci.eval/acquire!`) | 1,144 | base-ctx derivation (~225–475 ms) per new commit, plus install |
| body (`run-var!`) | 42 | the test |
| recording (`commit-results!`) | 167 | admitted member write plus blob publication |
| reach (`reach-digests`) | 116 | the index, content-keyed |
| release | 10 | |

  - After 2f4ea22d9 the world (checkpoint, overlay and request branch) measured
    1,316 / 1,210 ms. That is almost all the overlay transaction.
  - The remaining largest phases:
    - Member acquisition: base-ctx is memoized by program identity, and each request's head is
      a new commit.
    - The overlay write.
    - Selection over run history.
  - All three sit well above the ≤150 ms target, and none is measured after these commits yet.
- **(c) one-request :87/:203 probe.** On an isolated agent branch of default, with one agent
  test and two identical requests, the second request **reused** (executed 0, reused 1). The
  first run recorded all of these:
    - program digest `8aab2bdb…`;
    - input digest `61bf53da…`;
    - loaded commit `6ab5b9ff…`;
    - member reach digest `7406749e…`, equal to the current one.
  - So reuse works in that shape, and :87's difference is in its fixture. Its first request runs
    two marker-writing members over an extra raw-schema attribute. Not reproduced further.

## Root runs (serially, after adopting the three commits)

1. `bin/test-check default --test seon.test.scoped-first-run-test/a-first-incremental-request-naming-a-change-selects-only-its-reach`
2. `bin/test-check default --ns seon.test-reaching-test`: root's named rerun. The long case
   previously timed out at 120 s.
3. `bin/test-check default --test seon.test.clean-world-test/a-member-world-is-the-loaded-program-with-boot-facts-only`
4. `bin/test-check default --test seon.test.overlay-world-test/an-agent-branch-request-runs-its-candidate-on-a-clean-world`.
   It covers: a sentinel absent, default's agents absent, a retracted test absent, and a
   changed callee running the candidate.
5. `bin/test-check default --test seon.test.overlay-world-test/concurrent-first-preparation-yields-one-complete-checkpoint`
6. `bin/test-check default --test seon.test.overlay-world-test/a-checkpoint-without-its-source-record-is-never-read`
7. `bin/test-check default --ns seon.test.one-request-test`. This is the nested/isolated path,
   now on checkpoints.
8. Record `:seon.test.timing/acquire-ms` and the elapsed time for step 3 twice: the first run
   prepares and the second reuses. Put them beside a parent-commit (fe5dcc072) run for the
   hot-path comparison.

RESET NEEDED: no. RESTART NEEDED: no. Released paths: `src/seon/test.clj`,
`test/seon/test/{scoped_first_run,overlay_world,clean_world}_test.clj`, this note, the issue note.

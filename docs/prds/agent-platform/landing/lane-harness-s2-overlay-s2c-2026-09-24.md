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

---
type: design
status: first pass (Opus), awaiting Astra review; no source edited
created: 2026-09-25
lane: design-fast-harness-opus
pins: seon HEAD 757964097; default pid 39484 (started 2026-09-25T22:11:57Z, health :faulted, 6 open faults); datahike 4157916b
---

# A fast, reliable test harness (2026-09-25)

**What exists:** `seon.test/run` already runs each member as an isolated agent for one body.
The data flow is: adopted commit → a checkpoint branch named by the digest of its inputs →
a request branch with the execution's overlay rows → one branch per member → `run-var!` →
`commit-results!` → unlink (`src/seon/test.clj:1715`, `:1861`, `:2078`).
**Principal approach:** keep that one pipeline and add no second harness. Remove the
per-member work that belongs once per world. Build worlds only through the canonical writers,
which agents and files call alike. Compute the verdict (new, fixed, still red, incomplete)
from the evidence the pipeline already stores.

The owner asked on 2026-09-25 whether we have a fast and reliable way to test the system.
Today we do not. The measurements below show the harness itself runs at a 1.2 s request floor
and a 0.33–0.58 s member floor before any body executes. The rest of the slowness is five
distinct owners, each named below.

## 1. Measurements (REPL, JVM mode on default, 2026-09-25 23:33–23:45Z)

The probes ran `#'seon.test/member-result` directly on a real request world from
`#'seon.test/clean-request-handle`, so recorded green could not short-circuit them. Timings
come from the member's own `:seon.test/timings`. The armed functions come from
`seon.profile/begin` and `seon.profile/explain`, whose numbers are inclusive (a caller
includes its callees) and include concurrent work. Every probe branch was released and
unlinked. After the probes the roster held no `agent-*` branch. The two MCP branches were
retired.

### 1.1 The harness floor (identical for every member)

| Phase | Measured | Where the time goes |
|---|---:|---|
| request world (`clean-request-handle`) | 256–432 ms per request | checkpoint read, request branch, `open-world!` projection state, overlay `db/transact!` 153–163 ms |
| member acquisition (`acquire-context!` isolated) | 325–581 ms per member (n=12) | `seon.sci.eval/acquire!` 299–504 ms. The request handle never acquired its program, so each member re-acquires it: 57 of 92 stack samples sat in `load-core-namespaces!`'s whole-program `seon.db/q` (`src/seon/sci/eval.clj:1995`) on the fresh member connection, plus `derive-base-ctx` 150–177 ms on the first member |
| **the same, request acquired once** | **389 ms once, then 28.8–30.4 ms per member (n=4)** | prototype: `acquire-context!` on the request's own branch, then members fork from it. `acquired-database?` matches by commit, so `acquire!` returns at once |
| resolve | 0.2–4.9 ms | `resolve-test` |
| release | 8.0–13.2 ms | `release-context!` |
| selection, admission and recording (one member, `seon.test/run`) | ~1,200 ms of a 2,266 ms request with a 547 ms body | `seon.fn/gate-sets` 445 ms, `clean-request-handle` 273 ms, `admit-run` 218 ms, three `db/transact!` 505 ms |
| reuse answer (unchanged green) | 140–376 ms | `changed-definition-symbols` 456 ms and `program-written-since?` 422 ms (inclusive, two requests) |
| agent-defined one-assertion test, fresh | 2,492 ms for a 26 ms body | as above, plus the overlay write |

A 24 September study measured warm member acquisition at 52–62 ms
(`docs/research/agent-platform/agent-deftest-and-test-forks-2026-09-24.md` §1). The regression
came in with the request world (41f2f0b53). Its comment, "acquiring one here too measured
~0.5 s of nothing" (`src/seon/test.clj:1763`), is false whenever a request has more than
one member.

### 1.2 Five representative slow members

| Member | Acquire | Body | Armed functions over 100 ms (inclusive) | Phase and owner |
|---|---:|---:|---|---|
| `seon.test.accretion-test/source-harvest-keeps-refusal-render-contracts-coherent` | 493 | 10,983 | `seon.fn/rows` 10,745 → `analyze-rows` 10,735 → `analyzed-files` 10,725; `seon.fn.analyzer/analyze` 6,265; `invoke-kondo` 3,517; `schema/canonical-value-string` 1,332 (×1,172,181); `seon.fn/var-row` 1,110; `program/definition-digest` 1,048; `seon.id/digest` 722; `assert-complete-contract!` 706 | **program analysis**: the body re-analyzes every source file (`test/seon/test/accretion_test.clj:78`) to build a projection that the member's world already carries |
| `seon.render.web-debug-test/turn-details-use-the-loop-opening-and-exact-segments` | 456 | 45,843 | `with-database` ×2 91,614; `render/acquire-context!` ×17 40,137; `render.web/derive-context!` ×22 40,128; `render.walk/history` ×12 39,023; `db/carried-projection` ×9,158 35,984; `db/content-projection` ×704 34,138; `sci.kernel/invoke` ×289 34,274 | **body**: a product render cost, not the harness (§8). 3 fail, 1 error |
| `seon.cluster.registry-test/blob-lifetime-follows-schema-derived-history-reachability` | 464 | 2,177 | `test-support/transacted!` ×5 1,444 (the schema population of a new file store); `registry/collect!` ×4 447; `ensure-cluster!` 288; `record-fork!` 252; `config/compile-manifest` 167 | **world construction plus schema population**: a new file store per member (`registry_test.clj:96`) |
| `seon.test.one-request-test/one-request-runs-each-member-as-an-isolated-agent` (3 nested runs) | 581 | 6,837 | `seon.test/run` ×3 4,883; `db/q` ×296 3,588; `db/with-declarations` ×15,105 4,291; `db/transact!` ×13 3,099; `sci.eval/acquire!` ×7 2,195; `acquire-context!` ×4 1,512 | **acquisition plus recording**: each nested request pays the floor in §1.1 (`seon.sci.reader/read` 21,450 ×30 is concurrent prepl reading and is not attributable) |
| `seon.fn-test/contracted-rows-carry-queryable-facts-in-their-spec-transaction` | 547 | 682 | `with-database` ×2 1,297; `db/with-declarations` ×934 1,077; `sci.eval/acquire!` ×2 516 | **acquisition**: the member floor, then a fixture branch (second acquisition ~50 ms). 3 fail |

### 1.3 Canonical fixture writes on a member branch (prototype timing)

| Step | ms |
|---|---:|
| `namespace-row` | 2.0 |
| `transacted!` of that row | 112.6 |
| `program-fn-row` (production analyzer) | 70.2 |
| `transacted!` of the fn row | 427.0 (`db/report-arity-comparison` 287, `arity-base` 165) |
| `agent-tx` | 14.8 |
| `transacted!` of an agent | 113.1 / 151.8 |
| `d/with` of an agent (no validator) | 50.4 |
| whole-program count query on the branch | 4.3 (4,869 functions) |
| new in-memory store with all 1,214 installed attributes (`with-fresh-database`'s work) | 1,050 |

A fixture branch is cheap. A canonical write costs about 110 ms, which is the writer's own
cost (`content-projection` ×6 184 ms). A function-row write costs 430 ms because of the
writer's arity comparison. A new store costs a second before it holds anything.

## 2. World construction: one prepared world, keyed by its inputs

The keying already exists and is right. The checkpoint branch is named
`(id/digest 24 [base cluster (sort effective) process])` (`src/seon/test.clj:1749`) and is
prepared once by the caller that creates it. That branch is the prepared world, and the
Datahike roster is its durable cache: content-keyed, reused across runs and retired when it is
superseded. It needs no core.cache layer. Three changes remain:

1. **Acquire once per request (S1).** `clean-request-handle` acquires the request's program
   through `acquire-context!` on its own branch before it returns, and members fork from that
   context. Measured: 389 ms once, then 29 ms per member instead of 325–581 ms. This deletes the
   false comment and is the whole fix for the member floor.
2. **Namespace worlds from declared seeds (S6).** `apply-seeds!` (`src/seon/test.clj:128`)
   already prepares a Var's `:seon.test/seed`. It could also read the same key from the
   namespace's metadata. Once per request it would prepare that namespace's seeded branch off the
   request head, named `(id/digest [request-commit seed-definition-digests])` exactly as
   checkpoints are, and the namespace's members would branch from it. The seeds are canonical
   writers only (§3). Unchanged seeds against an unchanged commit reuse the branch across runs.
   This is the "shared fixture world": a branch off a captured commit, built once, with no new
   cache object.
3. **No file store unless storage is the subject.** Store-global subjects (blob GC, roster,
   collection bytes) keep a store and declare it. Registry members pay 1.4 s of schema population
   per member. The question of what store they use is option Q2 in §9. The memory-template shortcut
   is refuted: a clone of a konserve memory store connects only under its original id. Datahike
   refuses a different id with "Store identity mismatch" (`reference-code/datahike/src/datahike/connector.cljc:158-170`,
   observed at the REPL).

`d/with` stays admissible only for pure-derivation properties that carry the writer's
validator, as the skill already rules. It saves about 60 ms per write and is not the default.

## 3. One fixture system, for us and for agents

**Owner requirement (2026-09-25):** "no duplicate systems". The prepared world plus the
canonical builders are the one system. Agents call the builders through the REPL path, and test
files call the same functions.

**Verified:** from SCI on the disposable branch `harness-probe-2`,
`seon.test-support/program-row` resolved and returned a digested test row (586 ms eval).
`seon.test-support/namespace-row`, `with-database`, `seon.test/run`, `my.test/run` and
`my.test/check` also resolve from SCI. So the builders are already callable from SCI. The gap
is naming and completeness, not reach.

**One way to write a test.** It is a plain `deftest` in the subject's namespace, or a task's
done-when that names one. Written in a file by root:

```clojure
(ns probe.same-way (:require [clojure.test :refer [deftest is]]))
(deftest same-way (is (= 2 (inc 1))))
```

Written by an agent at the REPL, the same form is evaluated in `probe.same-way` through the
one REPL path (`sci.eval/evaluate`). The turn writes `program/declaration-row` of the
evaluation's `:seon.program/row`, and `(my.test/run)` runs it.

**Proved at the REPL (JVM probe on a member branch):**

- For the synthetic `probe.same-way/same-way`, the production source analysis
  (`program-row`, the path file publication takes) and the SCI evaluation produced **the same
  `:seon.program/definition-digest`**. The rows differ only in `:seon.fn/form-span` (`[0 63]`
  versus absent).
- For the file-published `seon.test.one-request-test/a-member-body-runs-on-its-own-listed-branch`
  (stored admission `:core`), SCI evaluation of its stored source gave a definition digest
  equal to the stored one, and so did re-analysis. `:seon.program/analyzed-source-digest`
  differed in both cases. That digest covers the analyzer's resolver context, so evidence keyed
  by analysis digest is not yet shared between the two ways. This is open question Q4.
- The agent-written row ran through `seon.test/run`: executed 1, pass 1, 2,492 ms. The second
  request was answered from the record (reused 1, 376 ms).

**Still to prove:** the same run for a file row and its agent twin in one namespace, compared
by run facts, not only by digest. This lands with S5 as the class regression.

**The builders are the existing `test-support` writers:** `namespace-row`, `program-row` /
`program-fn-row` / `replacement-tx` / `revision`, `agent-tx` / `agents-tx`, `apply-config!`,
`seed-cluster!`, `transacted!`, `with-database`. S5 adds one builder,
`define-source!`: evaluate one source form as an agent does, then write its declaration row.
It promotes `define-agent-test!` (`test/seon/test/one_request_test.clj:26`, 21 lines) and
replaces the per-file copies. Moving `test-support` under `src/` is not needed for reach
(verified above). Whether to do it anyway for adoption loading is option Q5.

**Second ways to build a world, to retire (lines are current source):**

| Mechanism | Where | Lines | Disposition |
|---|---|---:|---|
| per-file `admit!` / `define!` copies of `transacted!` + `program-row` | `test/seon/test/accretion_test.clj:15`, `test/seon/test/effect_attempt_test.clj:7`, `test/seon/test/overlay_world_test.clj:26`, `test/seon/issue/task_ingestion_test.clj:56`, `test/seon/issue_settlement_test.clj:23`, `test/seon/test_reaching_test.clj:48` | ~45 | delete, call `transacted!` / `define-source!` |
| `define-agent-test!` | `test/seon/test/one_request_test.clj:26` | 21 | becomes `test-support/define-source!` |
| hand-written `:seon.source/digest` facts before a nested run | `test/seon/test_reaching_test.clj:22`, `:37` | ~12 | delete once S1 and S7 make a nested run branch from the request world |
| whole-source re-analysis | `test/seon/test/accretion_test.clj:77-100` | ~14 | read `(db/carried-projection (db/db))` (S4) |
| per-member file store plus `probe-schema-rows` | `test/seon/cluster/registry_test.clj:52-131` | ~60 | Q2 |
| `with-fresh-database` (1.05 s per use) | `test/seon/test_support.clj:614-648`; callers: `source_database_test`, `cluster/mcp_test`, `blob_test`, `ai_stream_fold_test` | 35 | keep only for store-global subjects, declared; the other callers move to `with-database` |
| direct `d/create-database` worlds | 16 files, for example `test/seon/db_test.clj`, `test/seon/cluster/store_transact_test.clj`, `test/seon/fn_test.clj`, `test/seon/program_test.clj` | n/a | per-site review in S5: store subjects declare, the rest move to `with-database` |
| `with-published-file-database` / `populate-published-*` | `test/seon/test_support.clj:62-150`; 15 caller files | 90 | keep: host-bound and declared (`:seon.test/fixture-observation`). This is the one rare human-and-agent capability that needs a JVM root; agents reach it through `bin/test --platform`, never a second function |

**What an agent cannot do today, and how it reaches the capability through the same function:**

- File stores and host-bound publication run only on the platform host (`isolated-members`,
  `src/seon/test.clj:1974`). An agent's test declares `:seon.test/fixture-observation` exactly as
  ours does, and the one request excludes it with the platform command. Nothing forks.
- Tests that redefine JVM Vars have no SCI equivalent, and they are banned anyway (§4).

## 4. Canonical fixtures enforced: lint plus writer

**Check 1, clj-kondo `:discouraged-var` (S5).** The linter exists
(`reference-code/clj-kondo/src/clj_kondo/impl/config.clj:151`). An `:ns-groups` entry by
filename pattern for `test/` sets `clojure.core/with-redefs`, `with-redefs-fn`,
`alter-var-root`, `intern` and `load-file` to `:error` for that group. The publication analyzer
already runs kondo, so a test row carrying the finding refuses at the gate. It needs one
exclusion rule: a string literal (the SCI admission tests, which assert that SCI *refuses*
those forms) is not a call, and kondo does not lint strings.

**Check 2, the writer.** The writer refuses a program-partition test or function row that lacks
`:seon.program/analyzed-source-digest`. The law already requires that digest ("Require each
function/test's analysis digest"), and `resolve-test` already refuses such a row at run time
(`src/seon/test.clj:1463`). Moving the refusal to the write makes a hand-built row fail where
it is built. This needs a check against the canonical writers before it lands (Q3).

**Today's offenders** (`grep -rn -e with-redefs -e alter-var-root -e "(intern " -e "(load-file" test`
finds 346 lines. Of those, 267 are `with-redefs` call sites outside strings, 54 are
`with-redefs-fn`, and 18 are `alter-var-root`, across 93 files):

| Class | Count | Examples (file:line) | Conversion |
|---|---:|---|---|
| provider stand-in `ai/complete` / `ai/credential` | 57 | `test/seon/cluster/turn_test.clj:461` and about 40 more in that file; `test/seon/cluster/armed_test.clj:248`; `test/seon/turn_continue_test.clj:104`; `test/seon/concurrency_independence_test.clj:614`; `test/seon/gen/loop_test.clj:301`; `test/seon/ai_test.clj:1036` | an external effect as an explicit value: Q1 |
| read/write refusal injection (`db/q`, `db/pull`, `db/transact!`) | ~21 | `test/seon/cluster_test.clj:27`, `test/seon/test_provenance_test.clj:108`, `test/seon/test_test.clj:178`, `test/seon/reconcile_test.clj:125`, `test/seon/cluster/source_test.clj:219`, `test/seon/cluster/source_nochange_test.clj:18`, `test/seon/fn_test.clj:168`, `:1938`, `:1950`, `test/seon/test/selection_test.clj:246`, `test/seon/render/web_test.clj:2142`, `test/my/plan_test.clj:444` | produce the real refusal from a world that refuses (an invalid write, or a read on a released connection), or pass the reader where the function already declares it as an input |
| call counting (`kernel/invoke`, `d/history`, `db/pull`) | ~12 | `test/seon/mcp_test.clj:99`, `:114`, `test/seon/render/retained_test.clj:107`, `test/seon/render/web_context_test.clj:79`, `test/seon/render/root_pull_test.clj:259`, `:333`, `test/seon/db_test.clj:2519` | `seon.profile/begin` / `explain` already count calls per armed function: a mechanical, scripted sweep |
| schema-source stand-ins (`projection-from-database`, `load-projection`, `build-projection`, `packaged-forms`, `read-schema-resource`, `io/resource`) | ~25 | `test/seon/sci/eval_test.clj:172` (broke a concurrent publication: `docs/seon/issues/eval-test-with-redefs-of-packaged-forms-breaks-concurrent-publication.md`), `test/seon/config_test.clj:113`, `test/seon/schema_test.clj:743`, `test/seon/turn_loop_test.clj:73` | pass the world value; where the owner reads a resource ambiently, fix the owner to take it as an argument |
| turn and prompt stand-ins (`turn/next-agent-work`, `prompt/prompt`, `render/acquire-context!`, `sci.eval/evaluate`) | ~20 | `test/seon/cluster/agent_test.clj:618`, `:1047`, `test/seon/cluster/prompt_test.clj:310`, `test/seon/cluster/evaluate_sources_test.clj:154`, `test/seon/turn_loop_test.clj:359` | build the real turn world with canonical writers, and use a virtual provider (Q1) |
| instrumentation subjects (`alter-var-root … mi/-f->original`) | 18 | `test/seon/instrument_test.clj:1252`, `test/seon/profile_test.clj:28`, `test/seon/registry_isolation_test.clj:21`, `test/seon/test_support_test.clj:181`, `test/seon/cluster/agent_test.clj:1633` | instrumentation is JVM-global, so these are declared isolated (platform) members using `preserving-instrumentation-state`. The one in `agent_test` (`seon.turn/step`) converts to an injected world |
| `load-file` | 10 | `test/seon/dev/hook_test.clj:15`, `test/seon/cluster/publication_host_test.clj:81`, `test/seon/dev/edit_feedback_test.clj:391`, `test/seon/dev/mcp_bridge_test.clj:23`, `test/seon/instrument_replaced_roots_test.clj:16`, `test/seon/context_cookbook_trial_test.clj:7`, `test/seon/help_trial_test.clj:14` (the last two load PRD research scripts) | require a namespace; delete the two PRD-script tests (no maintained code in PRD directories) |
| `intern` of probe test Vars | ~20 | `test/seon/test/one_request_test.clj:158`, `test/seon/test/seed_preparation_test.clj:15`, `test/seon/cluster/program_change_test.clj:32`, `test/seon/instrument_test.clj:191` | `define-source!` in a throwaway namespace (agent way) |

Hand-built program rows: `test/` has 206 literal `{:seon.fn/sym …}` / `{:seon.test/sym …}` /
`{:seon.ns/name …}` maps. The largest counts are `program_test` 22, `fn_test` 20,
`render_simplification_test` 15, `render/web_test` 13, `db_test` 13 and `turn_test` 11. Many of
these are pull patterns or lookups, not rows. Check 2 identifies the rows that are written. The
open-items ledger already counts 59 fn/test sites and 22 agent-row sites for this slice
(`docs/research/agent-platform/open-items.md`, "Hand-built rows in fixtures").

## 5. Baseline diff: new, fixed and still red, from stored evidence

The evidence exists. `runner/latest-results` (`src/seon/test/runner.clj:1857`) reads each
symbol's latest admitted member, including its counts and `:seon.test.member/reach-digest`.
S2 reads it once on the request's pre-admission database value for the runnable symbols
(one query), then classifies each executed result:

| Previous | Now | Class |
|---|---|---|
| green, or none | red | `:new-red`, with `:reach-changed?` from the digests |
| red | green | `:fixed` |
| red | red | `:still-red`, with `:reach-changed?` (unchanged reach means the failure predates this change) |
| green | green | unlisted |

`tally` prints these as three groups. The run value carries them as
`:seon.test/baseline {:new-red [...] :fixed [...] :still-red [...]}`, so root and lanes stop
diffing output files by hand.

**Incomplete is not red.** `:seon.test/passed?` keeps its strict meaning: every obligation has
green evidence (`src/seon/test.clj:2384`). S2 adds `:seon.test/outcome`, which is `:green`,
`:red` (any executed red) or `:incomplete` (no red, but excluded, long, pending, void or
unfinished members, each group named). `bin/test-check` exits 0, 1 or 2 accordingly. This
explains the registry-test observation "57 pass, passed? false": its members were excluded,
not red.

## 6. Clean runs: committed coherent inputs, detect-don't-lock

Two things already hold. The loaded identity is compared at member start and end
(`sci.eval/call-detecting-program-change`, `src/seon/sci/eval.clj:1391`). An adoption during a
body makes that member `:program-unknown`, stops the batch and leaves the rest pending. What is
missing:

1. **The input's commit state (S3).** Source capture records whether the published input
   roots equal git `HEAD`'s tree (`git diff --quiet HEAD -- <input roots>`, measured cost to
   confirm, expected tens of milliseconds) as `:seon.source/committed?`. The run records the
   loaded source's value. A run on uncommitted inputs is still reported, but its evidence is
   `:void-for-acceptance`: merge and adoption gates refuse to count it, and `tally` prints
   `DIRTY INPUT: <paths>`. That separates "a lane's half-edited file was loaded" from product
   reds without a lock.
2. **Name a void run instead of hiding it.** A `:program-unknown` stop reads today as pending.
   S2 reports it in the outcome as `:void` with both identities, so the verdict is
   `:incomplete` with its cause, never a silent `passed? false`.
3. **No test may alter a Var root** (§4, check 1). That removes the other way a run corrupts a
   concurrent publication.

## 7. Nested runs cost what their members cost

A nested `seon.test/run` pays the request floor (about 1.2 s) plus the member floor (about
0.4 s). S1 cuts the member floor to about 30 ms. S7 cuts the request floor:

- `seon.fn/gate-sets` (445–537 ms) is memoized by the commit's program identity. This is an
  existing issue: `docs/seon/issues/gate-set-rederives-the-declared-reference-population-on-every-call.md`.
- When `program-overlay-tx` answers `[]`, no request branch is created and members branch from
  the checkpoint head, which saves the branch, the open and the overlay write.
- Admission plus the first commit go in one transaction per batch. Today there are three writes,
  505 ms.
- The reuse path (`changed-definition-symbols`, `program-written-since?`) reads through the same
  commit-keyed memo.

Target: a nested request of one member costs at most 300 ms plus that member. This refines the
existing issue `docs/seon/issues/a-nested-test-run-costs-four-seconds.md`: the root cause
measured here is per-member re-acquisition, not whole-program selection alone.

## 8. What the 5 s bound honestly covers

The targets are measured on prototypes where marked (P). The rest are projections and must be
re-measured in each slice's landing note.

| Item | Today | Target |
|---|---:|---:|
| request floor (select, admit, world, record) | ~1,200 ms | ≤ 300 ms |
| member acquisition | 325–581 ms | 29–30 ms (P) |
| resolve plus release | 9–18 ms | same |
| `with-database` fixture branch | ~50 ms after acquisition | ~30 ms |
| canonical ns or agent write | 113–152 ms (P) | same (writer cost) |
| canonical fn-row write | 427 ms (P) | the writer's arity comparison: product issue |
| namespace seed world | per member | once per request, then reused across runs |

With these, a member's harness cost is about 50 ms. The 5 s default then covers the body plus
roughly 30 canonical writes, or 10 function-row writes, or a nested request of up to about 15
small members. Registry-style store-global members stay at their honest 1–2 s and declare it.
Anything still over 5 s after S1–S7 is a product cost, and the fix is to file an issue, not
raise a bound:

- turn-details (46 s) is the render path. `render.walk/history` and `derive-context!` call
  `db/carried-projection` 9,158 times, and each call rebuilds `content-projection`. Existing
  issues: `docs/seon/issues/declares-program-is-recomputed-per-rendered-node.md` and
  `docs/seon/issues/a-nested-as-of-view-rekeys-its-projection-on-every-read.md`.
- The fn-row write at 427 ms is `db/report-arity-comparison` (287 ms). No issue matched my
  search for `arity-comparison`. Root should file one; this lane writes only this file.

Namespace targets: fn-test (65 members, 105 s today) projects to about 65 × (50 ms + body)
plus 300 ms, where most bodies are 0.2–0.7 s, so 15–50 s. That is still over the per-member
ideal because of the writer costs above, and it is dominated by those writer costs, not the
harness.

## 9. Open questions as priced options (simplest viable first; ★ recommended)

**Q1. The provider stand-in for 57 `ai/complete` redefinitions.**
- ★ A. A local HTTP endpoint fixture: `test-support/provider!` starts an http-kit server on
  port 0 that serves scripted replies, and `apply-config!` points `:seon.ai/endpoint` at it.
  This exercises the real request, stream and usage path. Cost: one builder of about 30 lines
  plus a scripted conversion of the call sites. Sacrifice: a local socket per test.
- B. A config dial carrying scripted replies that `ai/complete` returns. Fewer moving parts,
  but the product grows a test mode.
- C. Keep the redefinitions and declare those members isolated. No conversion cost, but the
  banned mechanism survives in about 12 files.

**Q2. Store-global subjects (registry, blob GC; 1.0–2.2 s per member).**
- A. Declare them platform members with a store per member. Honest and zero code, but they
  leave the development tier.
- ★ B. One file store per namespace, prepared once (schema population 1.4 s paid once). Members
  work on uniquely named branches. Only the collection/GC drills get their own store, and they
  declare it. Cost: about 40 lines in `registry_test` with about 60 deleted. Sacrifice: GC
  members stay slow and declared.
- C. A Datahike fork change that lets a memory-store clone connect under a new id
  (`connector.cljc:158-170`). Fast (a persistent-map copy), but it bends the owner's identity
  guard.

**Q3. Writer refusal of rows without an analysis digest.**
- A. Lint only (check 1). Cheap, but hand-built rows remain possible.
- ★ B. The writer refuses at the write for the program partition, after a sweep confirms every
  canonical writer supplies the digest. About 15 lines in the writer's existing digest check.
  Sacrifice: fixtures stay red until converted (S5 is the conversion).
- C. Refuse at resolve only (today). No cost, but the refusal is late and far from its cause.

**Q4. The analyzed-source digest differs between file and REPL rows with equal definitions.**
- A. Accept: evidence reuse is keyed by the definition digest plus the reach digest, which are
  equal. No cost.
- ★ B. Prove in S5 which inputs differ (resolver context, file) and decide whether the reach
  digest must include them. About one hour of REPL work.
- C. Make the analyzer's resolver context identical for both paths. Unknown cost.

**Q5. Where the builders live.**
- ★ A. Stay in `test/seon/test_support.clj`. SCI already resolves them (verified). No cost.
- B. Move to `src/seon/test/world.clj` so adoption always loads them (see
  `docs/seon/issues/development-adoption-cannot-load-test-support.md`). This is a rename
  sweep, and it grows `src` by about 900 lines.

## 10. Slices (each loads on its own; added lines are offset by the deletions listed)

| Slice | Owner file(s) | Added / deleted (estimate) | Proof |
|---|---|---|---|
| S1 acquire the request world once | `src/seon/test.clj` (`clean-request-handle`) | +6 / −3 (the false comment) | `one_request_test` timings: member acquire ≤ 40 ms; §1.1 probe repeated |
| S2 baseline diff and outcome | `src/seon/test.clj` (`run`, `tally`), `resources/seon/schemas/seon.test.edn`, `bin/test-check` exit code | +55 / −10 | a request with one known red and one fixed member classifies both; excluded-only reads `:incomplete` |
| S3 committed-input marker | `src/seon/cluster/source.clj`, the run record, the gate | +35 / −0 | a dirty-tree run reports `DIRTY INPUT` and is not counted by the gate |
| S4 accretion and registry conversions | `test/seon/test/accretion_test.clj`, `test/seon/cluster/registry_test.clj` | +45 / −75 | accretion body under 100 ms; registry per Q2 |
| S5 one fixture system | `test/seon/test_support.clj` (`define-source!`), `.clj-kondo/config.edn` (test ns-group), scripted sweeps by class (§4) | +40 / −(per class; `admit!`/`define!` copies ~45, counting redefs ~12 sites) | the both-ways class regression (§3); kondo refuses a `with-redefs` in `test/` |
| S6 namespace seed worlds | `src/seon/test.clj` (`apply-seeds!`, `run`) | +40 / −(repeated per-test setup in seeded namespaces) | a seeded namespace prepares once per request; a second run reuses the branch |
| S7 request floor | `src/seon/test.clj`, `src/seon/fn.clj` (gate-set memo) | +30 / −20 | nested one-member request ≤ 300 ms plus its body |
| S8 provider endpoint | `test/seon/test_support.clj` | per Q1 | a turn test with a scripted reply and no redefinition |

Order: S1 → S2 → S7 → S4 → S5 → S3 → S6 → S8. S1 and S2 are independent of every other lane's
file and fix the two things root feels most: minutes of floor and hand-diffing reds.

## 11. Risks and verification limits

- The member-floor prototype called `acquire-context!` on the request handle's own branch from
  a JVM probe. Other members' SCI definitions never ran, so the S1 slice must re-prove
  isolation (`seon.test.one-request-test` and the candidate/parent isolation regression
  f3c57b5cc).
- The profiler's inclusive numbers include concurrent work (`seon.sci.reader/read` 21 s), so
  the per-phase attributions above come from stack samples where a claim depends on them.
- The recording cost per member was not isolated (three writes, 505 ms, in one request).
  S2/S7 must measure `commit-results!` alone.
- The roster holds a `current-src` branch on default's store. This lane did not create it
  (`registry-test` names that branch in its own file stores) and did not touch it.
- Default was `:faulted` (6 open faults, 80 failed tests) while these probes ran. No probe
  depended on those faults being clear.

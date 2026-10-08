---
type: spec
status: proposed (2026-10-08); §3-§5 under redesign
tags: [spec, merge, root]
---
# Merge: root reviews and takes an agent's branch

What exists: an agent proposes its branch (`my.program/propose-merge!`), root prepares the proposal on a fresh
branch S of its head and runs the proofs there (`prepare-merge!`), then accepts exactly what S tested
(`accept-merge!`); the writer checks the replaced rows' digests. Data flow: proposal row at the task home → three-way
digest comparison (base, branch, head) → one reconcile transaction onto S → test run on S → one guarded
transaction onto root's head. The approach stays: functions of database values, the three-way value as the diff,
and Datahike commits with merge parents. This spec makes it easy and adds review, repair and auto-acceptance.

Root is whoever holds a merge task: the orchestrator through MCP or a Seon model agent. It is an ordinary task done
through the ordinary REPL in root's own context. Nothing here is a second entrance, and a Seon agent must find root's
job easy. The competency suite (`docs/research/agent-platform/2026-10-08/competency/competency-spec.md` §1-2) uses
this mechanism; this spec owns it. Order and status of the M slices: [the ledger](../schedule/agent-loop-ledger.md)
§3.10.

**Under redesign** (`docs/research/agent-platform/2026-10-08/redo/merge-acceptance.md`): §3-§5 below. Both reviews
found that prepare refuses a conflict before any repair world exists, that a data migration on S never reaches the
head, that acceptance checks only the carried rows' digests although a new caller or data row can arrive on the head
after prepare, that `:no-new-red` treats unknown head evidence as green, that no execution owner runs the policy on
a provider-less root, and that installed accept gates on reaching members, which CLAUDE.md forbids. The diff value
(§2), identity selection, digest checks and proof execution stay.

Owner rulings this spec applies (2026-10-08): an agent may make any REPL-valid change on its branch, conflicting
ones included (redefinitions, refactors, renames, schema changes); its branch never refuses them. Integrity is
judged at root's diff and merge. Acceptance is automatic when root's criteria pass. Only the task's proofs run during
the agent's work; the regressions reaching the change run once here, on the combined program.

## 1. The merge task

**What root holds.**
- The merge task (`:seon.task/kind :merge`, written by `seon.task/propose!` src/seon/task.clj:655) at the task
  home, with its proposal row: branch, commit, proved proof digests, run id, program digest.
- The agent's world: its branch (read through `compare-branch`, src/seon/cluster/source.clj:824) and its proposal.
- The merge branch S, forked from the target head by `candidate-gate!` (src/seon/cluster.clj:2642), with the
  delta applied.
- Its own context on the target: `accept-merge!` lands on the branch root's context holds
  (src/my/program.clj:547). For root that is its cluster; for a scratch reviewer it is the reviewer's branch.

**Steps** (each one form; each answer offers the next form as `:seon.render/operations`):

| # | Form | Answers | Installed |
|---|---|---|---|
| 1 | `(my.program/proposals)` | open proposals at the home | yes, program.clj:473 |
| 2 | `(my.program/diff {:seon.task/merge m})` | the diff value (§2) | TARGET (today `diff` is the agent's own branch only, program.clj:974) |
| 3 | `(my.program/prepare-merge! {:seon.task/merge m})` | S, the run, red members, the diff value | yes, program.clj:513; keeps S on conflict: TARGET |
| 4 | `(my.program/take! …)` / `(my.program/drop! …)` / ordinary forms on S | repair (§3) | TARGET |
| 5 | `(my.program/accept-merge! prepared)` | merge commit, identities, run | yes, program.clj:547 |
| 5' | `(my.program/decline! {:seon.task/merge m :seon.task/reason "…"})` | closed task, agent told | TARGET (today `seon.task/decide!` task.clj:679, no reason, agent not told) |

**Evidence** (all stored facts, consumers named):
- the proposal row (consumer: `proposals`, prepare);
- the run on S, `:seon.test.run/id`, with every member's result (consumer: accept, the score);
- the merge commit with parents head, S and the agent's commit (consumer: write-back, history);
- on the merge task: `:seon.task/outcome` (installed), and TARGET `:seon.task/accepted-by` (`:policy` or `:root`),
  `:seon.task/review-run`, `:seon.task/held` (failed criteria), `:seon.task/target` (branch and merge commit; today
  the outcome is written at the home even when the merge landed on another branch).

## 2. The diff value

One value, from `compare-branch` (source.clj:824) and `seon.program/three-way` (src/seon/program.cljc:704): every
compared declaration family (`:seon.fn/sym`, `:seon.test/sym`, `:seon.ns/name`, `:seon.schema/key`) classified by
its base, branch and head digests. TARGET shape, keyed by identity, small and digest-only:

```clojure
{:seon.merge/branch :agent-…  :seon.merge/base #uuid "…"  :seon.merge/head #uuid "…"
 :seon.merge/added     [{:id [:seon.fn/sym a/f] :after "d1…"}]
 :seon.merge/changed   [{:id [:seon.fn/sym a/g] :before "d0…" :after "d2…"}]
 :seon.merge/removed   [{:id [:seon.fn/sym a/h] :before "d3…"}]
 :seon.merge/head-only [{:id [:seon.fn/sym a/k] :before "d4…" :after "d5…"}]   ; changed on the target since the fork
 :seon.merge/conflicts [{:id [:seon.fn/sym a/g] :base "d0…" :branch "d2…" :head "d6…"}]
 :seon.merge/dangling  [{:caller a/k :calls a/h}]                 ; on the merged value
 :seon.merge/schema    [{:id [:seon.schema/key :a/line] :refused 70 :checked 200 :readers [a/f]}]
 :seon.render/operations [(my.program/declaration-diff {:seon.task/merge "…" :id [:seon.fn/sym a/g]})
                          (my.program/prepare-merge! {:seon.task/merge "…"})]}
```

- **Conflict kinds.**
  - Same entity changed on both sides (three-way `:conflict`, installed).
  - A dangling caller: a declaration on the merged value whose `:seon.fn/calls` names a symbol with no declaration.
    Calls are symbol values, not refs (resources/seon/schemas/seon.fn.edn:49), so the writer cannot refuse it: TARGET
    (M5).
  - A schema the target's data violates: for each added, changed or removed `:seon.schema/key`, the head's refused
    value count from `seon.db/registration-check` (src/seon/db.clj:4077) and the declarations reading the key: TARGET
    (M6).
- **Cost.** Proportional to the declarations changed since the fork: one merge-base walk, digests of the changed
  identities only, one dangling query over the merged callers of removed symbols. No whole-program work.
- **AI render.** Counts per section, then the entries above with one `declaration-diff` form each. No source text.
- **HTML render.** One row per identity: kind, symbol, side, a status mark (added, changed, removed, conflict,
  dangling, schema refusal), and take/drop operations. Source pairs open on demand through `declaration-diff`.

## 3. Taking by identity, repair on S

- **Take** `(my.program/take! {:seon.task/merge m :seon.program/identities #{[:seon.fn/sym a/f]}})`: copy the
  branch's rows for those identities onto S, as `prepare-merge!`'s selection does today (`:seon.program/identities`,
  source.clj:886-893), but onto the kept S instead of a new one. A selected declaration reaching an unselected change
  refuses by name (`unselected-reach`, source.clj:759, installed).
- **Drop**: restore the head's row on S (or retract an added one). It is the same operation with the head as source.
- **Repair**: root evaluates ordinary forms with S as custody (`in-ns`, `defn`, `my.program/ns-unmap!`). A conflict
  is resolved by writing the combined definition on S. Nothing is re-proposed.
- After a take, drop or repair, S's run is stale; accept already refuses a program written since the run
  (`program-written-since?`, source.clj:1007). Root re-runs with `(my.test/check …)` on S, or `prepare-merge!` re-runs
  on the kept S.

TARGET: S kept and named on the merge task (`:seon.task/candidate`), retired when the task closes. Today a refused
or red prepare leaves S unrecorded in the roster (task `seon.cluster.source/a-red-prepare-leaks-its-candidate-branch`).

## 4. Acceptance

Installed (`seon.cluster.source/accept-merge!`, source.clj:941):
- the named run was recorded on S and every task proof is green there;
- S's program is unwritten since the run;
- every function the merge carries has a complete `:malli/schema`;
- every added or replaced function reaches a green member of the run (an installed defect: CLAUDE.md shows
  reaching-test counts and never gates on them; removed by the acceptance redesign);
- no task handle the program names is missing on the target (`seon.task/candidate-dangling`, task.clj:360);
- the writer refuses, before any datom lands, a carried row whose digest on the head moved since prepare
  (`declarations-held-tx`, source.clj:795);
- the merge commit's parents are the head, S and the agent's commit; the merge task closes `:accepted`; S and the
  agent's branch retire as detached io work.

Shown, not gated: the whole run's tally and its red non-proof members. TARGET additions:
- root's chosen tests (`:seon.test/identities` beside the proofs) run in the same run on S;
- the dangling and schema checks of §2 refuse unless root takes them as findings explicitly;
- an accept onto the cluster head advances it; an accept onto any other branch records `:seon.task/target` so the
  outcome names where the program went.

## 5. Auto-accept policy (TARGET, M1-M2, M5-M6)

- **Configuration.** `:seon.config.merge/auto-accept` on the target cluster's configuration facts, a set of
  criteria. Absent means every proposal waits for root. Root-owned: it lives on the target, never in
  `seon.config/defaults`.
- **Runner.** `my.program/review!` in root's context: on the merge message `propose-merge!` already sends,
  root's wake calls it. It calls `prepare-merge!`, checks each criterion on the answer, and when all pass calls
  `accept-merge!` with `:seon.task/accepted-by :policy`. Same functions, same writer; no second path.
- **Criteria** (each on S):

| Criterion | Holds when | Reads |
|---|---|---|
| `:proofs-green` | every task proof green | the run (installed check) |
| `:no-conflict` | three-way `:conflict` empty | the diff value |
| `:no-dangling` | no caller names a missing symbol | M5 |
| `:schema-holds` | no changed or removed key refuses head data or leaves a reader | M6 |
| `:no-new-red` | no reaching member red on S that is green on the head | M2 |

- **Hold.** The proposal stays open, S is kept, `:seon.task/held` records the failed criteria, and one message to
  root names them. Root then reviews by hand (§1 steps 2-5).

## 6. Conflict cases

| Case | Branch | Head (meanwhile) | Expected |
|---|---|---|---|
| Rename vs new caller | `f` renamed `g`, callers updated, `f` removed | new `k` calls `f` | three-way clean; `:dangling [{:caller k :calls f}]`; policy holds `#{:no-dangling}`; root repairs `k` on S to call `g`, re-runs, accepts |
| Contract change vs body change | `f`'s `:malli/schema` changed | `f`'s body changed | one declaration digest each side: `:conflict` on `f`; root writes the combined `defn` on S; accept refuses until the run is fresh |
| Schema evolution vs target data | `:a/line` narrowed | stored rows using `:a/line` | `:schema [{:refused n …}]`; holds `#{:schema-holds}`; root's outcome (a target-side migration or a decline) is under redesign: a migration on S does not reach the head |
| Two agents, one namespace | A adds `f`, B adds `g` in `ns` | A accepted first | per-declaration three-way: `f`, `g` disjoint, B clean (a definition keeps its namespace row since `76daa8fd5`) |
| Same function, two agents | both change `f` | A accepted first | B `:conflict` on `f`; held; root takes one or writes the combination |

## 7. What exists and what is missing

Exists (file:line at `81020da6a`):
- proposal, proofs gate and message: `my.program/propose-merge!` program.clj:416, `seon.task/propose!` task.clj:655;
- open proposals: `my.program/proposals` program.clj:473;
- comparison: `compare-branch` source.clj:824, `merge-base` source.clj:727, `three-way` program.cljc:704;
- the agent's own diff: `my.program/diff` program.clj:974, `declaration-diff` program.clj:991;
- prepare with selection and file proof: source.clj:847, program.clj:513;
- accept with its checks: source.clj:941, program.clj:547; write-back source.clj:1265;
- close: `seon.task/decide!` task.clj:679; branches: `my.agent/create-branch!` agent.clj:117, `retire-branch!`
  agent.clj:168.

Missing or broken, found by the dogfood (§8):

| # | Gap | Task |
|---|---|---|
| G1 | a new contracted function cannot arm on S: its proof is green on the branch and red on S ("this context holds no Var to arm its contract on") | `seon.cluster.source/a-new-function-cannot-arm-in-the-merge-gate` |
| G2 | one registered schema on a branch makes `diff`, compare and prepare refuse the whole comparison (`changed-identities` scope, program.cljc:700) | `seon.cluster.source/a-branch-schema-blocks-diff-and-merge` |
| G3 | the proof run cannot see a branch-registered schema, so propose fails | `seon.test/check-fails-on-agent-registered-schemas` |
| G4 | propose and prepare throw instead of returning their error | `my.program/merge-tools-throw-instead-of-returning` |
| G5 | a test-only new namespace throws entity-id/missing at prepare | `seon.cluster.source/a-test-only-namespace-throws-at-prepare` |
| G6 | every `defn` rewrote its namespace row: false conflicts, ns doc stripped on accept (fixed `76daa8fd5`, M8) | `seon.turn.batch/a-defn-rewrites-its-namespace-row` |
| G7 | root cannot diff a proposal from its own context | `my.program/root-cannot-diff-a-proposal` |
| G8 | accept cannot retire a hosted proposer's branch (open fault) | `my.program/accept-cannot-retire-a-hosted-proposers-branch` |
| G9 | a red or refused prepare leaks S | `seon.cluster.source/a-red-prepare-leaks-its-candidate-branch` |
| G10 | prepare costs seconds | `my.program/the-merge-prepare-costs-three-seconds-and-400k-calls` |
| G11 | no kept S, take/drop, dangling, schema check, policy, decline with reason, `:seon.task/target` | slices below |

**Slices.** The M ids (M0, M1-M7, M9-M13) are rows of [the ledger](../schedule/agent-loop-ledger.md) §3.10, with
their proofs, order and status; M8 landed in `76daa8fd5`. Proofs assert data through MCP `eval_clj`, on a disposable
target branch or the `competency` cluster, never default's program.

## 8. Costs, measured 2026-10-08

Default at `81020da6a`, warm JVM, MCP `eval_clj` SCI, wall time by `System/nanoTime` around the call, one sample each.

| Step | Measured | Budget (TARGET) |
|---|---|---|
| `proposals` | 39 ms, 212 calls | < 50 ms |
| `seon.task/assign!` | 41-54 ms (479 ms first call), ~820 calls | < 100 ms |
| `propose-merge!` (one proof run on the branch) | 0.46-1.05 s, 39k-159k calls | < 300 ms beyond the proofs' own bodies |
| compare (the diff, 4 identities) | 174 ms (JVM, `compare-branch`) | < 100 ms |
| `prepare-merge!`, 1 proof | 1.7 s warm, 175k calls; 8.7 s first call (8.0 s in the run) | < 500 ms beyond the tests' bodies |
| `prepare-merge!`, 1 proof + 2 reaching tests | 2.8 s, 602k calls | as above |
| `accept-merge!` refused (red proof) | 135 ms, 2.4k calls | < 100 ms |
| `accept-merge!` accepted (3 identities) | 1.78 s, 6.2k calls | < 300 ms |
| retire 10 branches | 558 ms | — |

The accept's calls are few but its wall time is 1.78 s: the time is outside armed functions (the writer's
transaction or the retire submission). Profile before ranking. Prepare's calls are dominated by
`seon.schema/projection-cache-value` (24k-89k) and storage-schema predicates, the same family task G10 names.

## 9. Dogfood record (2026-10-08)

Agents `mcp/merge-dogfood` to `…7` proposed; reviewers `mcp/merge-root-dogfood…4` held disposable branches off
default as targets. Default's program was not changed. Every branch and S was retired; the open merge tasks were
declined.

1. A branch with a registered schema could not propose: `propose-merge!` threw `:malli.core/invalid-schema` (G3, G4),
   even after the contract stopped naming the schema.
2. A function and test in a new namespace proposed green (1.05 s) and prepared red (G1). The same with one function
   (G1 again).
3. A test-only new namespace threw at prepare in 77 ms (G5).
4. A docstring change to `seon.task/step-summary` with a proof in `seon.task` proposed (0.9 s), prepared with the
   proof green and one unrelated reaching red shown (2.8 s), and accepted onto the reviewer branch (1.78 s). The
   home recorded the merge task `:accepted` although the merge landed on a scratch branch (§1, `:seon.task/target`).
   The accept's retire of the proposer's branch failed and recorded an open fault (G8).
5. Root's review needed JVM mode, `seon.operator.runtime/running-instances` for the store, and
   `seon.cluster.source/compare-branch` to see what a proposal changes (G7).
6. A reviewer branch forked before a proposal does not see it: proposals live at the home. `review!` and
   `proposals` read the home, not the reviewer's branch.
7. Test failures print to the run's `:out` through `clojure.test`'s reporter, a value printed outside the value
   printer.

---
title: "Hybrid: which tests are red, and which reds a change caused, as Seon data"
type: research
status: hybrid of plan-test-results-opus.md (f8e361b17) and plan-test-results-astra.md (f60ceee3b) over dig-test-results.md, revised in place by lane tr-revise (Opus) after narrow-check-test-results.md (6d82fc948) and the owner's rule of 2026-10-12 ("Do not re-run any tests that don't need to be re-run"). The owner's rule replaces the four questions of the first version (§7). Narrow check self-applied (§9). Checked a second time (narrow-check-test-results.md, d598bad77) and corrected for its seven mechanical failures; the owner's three answers are recorded in §7. Phases are filed as tasks (§6 status).
basis: HEAD 5640aef92. Code read by symbol: `seon.test/select`, `green-members`, `measured-body`, `admit-run`, `fork-point-red`, `change-reds`, `run-request`; `seon.test.runner/reach-digests`, `reach-index`, `reached-graph`, `reach-graph`, `reach-attribute`, `reach-revision-key`, `reach-node`, `reach-schema-node`, `reach-memberships`, `result-at-reach`, `latest-results`, `record-latest-tx`; `seon.batch/sink`, `executed`, `top`; `seon.profile/timed`; `seon.fn.analyzer/attributed-usages`; `seon.db/content-revisions`, `entities-changed-after`, `*read-evidence-sink*`; `seon.config/effective`; `seon.turn/run-reaching`, `test-step`, `record-broken!`, `request-summary`, `fork-commit`; `seon.context/step-observation`, `head-entries`, `base-results`, `step-status`, `step-section`, `main-database`; `seon.problems/failed-tests`, `families`; `seon.cluster.branch-evidence/test-evidence`, `verdict`. Probes on default (§8): `runtime_status` state `:unknown` (6 untested-hot warnings, no faults; commit `6acc85d3…`); root in jvm mode read-only; agent `tr-revise` in sci mode on its own branch `:agent-a6b431a443a0`, retirement requested after.
---

# Which tests are red, and which reds a change caused

## 1. The design in one page

**The owner's rule.** A test result is a fact keyed by what the test ran. A test runs only when its key has no result; a
result at a key is reused wherever that key occurs (its branch, main, after a merge), red and error included. Nothing
is re-run that cannot have changed.

**The key** is the tuple Seon already stores, with two inputs it misses today added at their producers (§2):

| Component | Stored on | Covers |
|---|---|---|
| `:seon.test.member/reach-digest` | member | the test and every declaration its static closure reaches (calls, references, quoted symbols, subject), each declaration's behavior digest, and every schema form its keywords and contracts name, transitively (`seon.test.runner/reach-index`, a Merkle hash over components) |
| + the declarations the body executed outside that closure (new, §2.1) | member | computed names, `requiring-resolve` of a built symbol, anything the static graph cannot see |
| `:seon.test.member/evaluator` | member | compiled and interpreted are different executions (205 tests red only interpreted) |
| `:seon.test.run/input-digest` | run | files and dependency versions under `seon.test.cache/input-roots` (deps.edn, local roots, gitlinks) |
| `:seon.test.run/config-digest` (new, §2.2) | run | the cluster configuration the test read its dials from |

**The one read.** `seon.test.runner/results-at` `(databases keys) → {test result}`: for each test, the newest completed
result at its key in any of `databases`, newest first; a test with no such result is absent (unknown). It generalizes
`result-at-reach` (which matches the reach digest only) and runs nothing, so selection, the block, a render,
`--branch-evidence` and `bin/seon check` all call it. `databases` is the asker's own branch and main's head
(`seon.context/main-database`, already read from a branch for costs); for root it is main and every live branch head.

**Selection** (`seon.test/select`) runs exactly the selected tests whose current key has no result. Today it reuses a
green at an equal reach digest but a red only "while the program is also unchanged since it ran"
(`red-holds?` against `program-revisions-digest`), so any program write reruns every red: root's
`(doc my.root/close!)` ran 54 tests in 10.6 s and 4.3 GB (narrow check, brief); on `tr-revise`'s branch, after one
unrelated `defn`, `select` lists the red `t-red` to run although its latest result sits at its current reach digest
`1efe171c…`, and reuses the two greens (§8 P3). That condition is a fence: it stands in for the stale-Var class
(`seon.test/an-unchanged-red-is-reused-after-the-agent-redefines-what-it-reaches`, a red recorded at a new digest by an
old body). Greens are exposed to the same class and reuse anyway. Phase R1 deletes the fence; R2 closes the class for
both colors at its producer: a result whose executed definitions differ from the tested program has no key.

**Judgment** ("did my change cause this red?") is the same read at the base's own keys: a reached test has a new key at
the head, so the base's keys are computed on the base commit's value and read on the branch and main (verified in the
first version and again by the narrow check: `66f93e8a`→`5bfb2c9d`). A base result nobody holds is run once, on a child
at the base, and kept on the agent's branch, where every later step reuses it by its key (owner, Q4). The step's
judgment is then derived on read: `:seon.agent/broken` and `:seon.agent/new-red` are deleted.

| Head | Base (`results-at` at the base key) | Set | Meaning |
|---|---|---|---|
| `:red` | `:green` | `:seon.context/broken` (exists) | the change caused it |
| `:red` | `:red` | red-before, counted in `:seon.context/test-counts`, never listed | red before the change |
| `:red` | absent result | `:seon.context/unknown` (new) | not yet known; never green, never the change's |
| `:red` | test absent at the base | `:seon.context/new-red` (exists) | a test the change added |
| `:pending` | any | pending | unrun at its key: never red, never green |

**What main holds.** Runs are `:seon.program/partition :seon.data` and never merge (owner, Q1). There is no batch tier
on main (owner, Q2): main runs only keys nobody ran. Main's results come from root's own requests (5 runs, 56 tests
today, §8 P2), root's reads of live branches at equal keys, and root's merge gate, whose answers root records on main
(R8, decided by the owner, §7).

**Nondeterminism** (owner, Q3): two completed results at one key that disagree are a defect of the test. They arise
only from an explicit `:seon.test/rerun?` or two runs racing, since nothing else reruns a key. `seon.problems` derives
them as their own family; the newest result answers meanwhile; root files the test's task.

**Cost.** Selection after an unrelated write executes zero bodies (R1's measured proof). The key costs one reach
derivation per changed program; today a changed program's first derivation for three tests is 390–805 ms and
0.6–1.8 GB (narrow check claim 11, cost-gb item 2), and a whole program 3–5 s and 5–6 GB. R11 makes it proportional to
the changed declarations: attribute maps updated from the entities changed since the previous revision instead of
re-read, node facts taken from those maps instead of one pull per node.

## 2. Each way the key could miss a dependency, closed at its producer

### 2.1 Calls the static graph cannot see

Verified (§8 P4, P5): clj-kondo's analysis already counts a quoted symbol as a reference
(`seon.fn.analyzer/attributed-usages` joins `:symbols`), so `(resolve 'trr.probe/k)` and every
`(delay (requiring-resolve 'seon.test/run))` in `seon.turn` are in static reach (`seon.turn/test-run` references
`[seon.test/run]` on main). Missed: a name built at run time. `t-dyn` calls `k2` through
`(requiring-resolve (symbol "trr.probe" (str "k" 2)))`; after `k2` changed from 4 to 5 the step ran nothing and
`select` reused `t-dyn`'s green while its body answered false.

The producer already observes what ran. `seon.profile/timed` counts every instrumented call into the call sink of the
executing context, keyed by executed definition `[sym digest]` (the digest the installed callable holds), and
`seon.test/measured-body` gives each member its own sink; `k2` appears in that request's cost diff as an interpreted
call. Today the member keeps only a count and a top 10 (`:seon.test.member/calls`, `/top`). R2 keeps the rest of the
observation where it is needed:

- `:seon.test.member/dynamic-calls`: the executed symbols outside the test's static closure (the closure is already in
  hand in `record-latest-tx`, `reach-memberships` of the tested database). Usually empty: zero datoms.
- The member's reach digest is the closure digest of the test plus its dynamic calls (`reach-digests` gains extra
  roots per test). A lookup computes each test's key with the dynamic calls of its newest result; when they are empty,
  as for nearly every test, this is today's digest.
- An executed `[sym digest]` whose digest differs from the tested database's definition digest for `sym` (a carried
  stale Var, a compiled host-bound callable a branch has redefined) leaves the member with no reach digest: the
  result is kept and shown, and it keys nothing (the runner's existing rule: "a member recorded without it ... is
  unknown and executes"). This makes the stale-Var class unreusable for green and red alike, which is what lets R1's
  fence go.

Multimethods and protocols: a defmethod is not instrumented, so the sink cannot see it. Verified on the branch:
after `(defmulti area :shape)` and its `:sq` method, `area` has no entity (`pull` → nil; the namespace's declarations
are `g u h k k2`), and `t-multi`'s reach names `trr.probe/area` as an unresolved name, which the index treats as host
code. Changing the method to `(* 3 s)` ran nothing and `t-multi`'s green was reused while its body answered false. For
files, analysis already attributes defmethod bodies and protocol implementations to the method var
(`attributed-usages`, `:defmethod`, `:protocol-impls`); the branch's write path does not declare them. R3 declares
`defmulti`, `defmethod` and protocol extension forms on the branch as the file index does, so a method body is part of
its multimethod's leaf. Rejected: treating every unresolved name in a program namespace as unknown evidence. 377 of
8,283 call and reference targets on main are such names, mostly task symbols in `:seon/tasks` metadata (§8 P6); that
would make most of the suite unkeyable.

### 2.2 Inputs that are not code

| Input | Today | Design |
|---|---|---|
| Schemas and the registry | in the key: `reach-node` collects each declaration's keywords and its contract's, `reach-schema-node` hashes each reached schema form and its keywords, transitively | unchanged |
| Dependency versions, files under input roots | run's `:seon.test.run/input-digest`; `select` reuses only runs with an equal one | `results-at` compares it too |
| Loaded platform commit, host-bound code | `select` reuses nothing when the loaded source differs from the recorded commit (`loaded-drift?`); a branch's host-bound change is a platform proof, not run in SCI (`seon.turn/platform-proof`) | plus R2: an executed digest that differs from the tested definition leaves the result without a key |
| Configuration facts | not keyed: a test reading a dial after `config/apply!` reuses its old result | R4: `:seon.test.run/config-digest`, `seon.id/id` of `seon.config/effective` for the run's cluster on the tested database, computed once per run; compared like the input digest |
| Fixture files | a test declaring `:seon.test/fixture-observation` is never reused (`seon.test/verified?`) | unchanged |
| Fixture data in the database | a fixture branch is the tested commit plus the test's own writes, both in the key | unchanged |
| Other cluster data read from the tested commit (tasks, agents, messages) | not keyed | not keyed (owner, §7): a test reading live cluster data it did not write breaks "tests are deterministic" (AGENTS.md); it surfaces as nondeterminism (§2.3, R12) |

### 2.3 Nondeterministic tests

Same key, different outcome: R12 adds `:seon.problems/nondeterministic-tests` to `seon.problems/families`, derived
from the members at one key (the read `failed-tests` already makes for reds), each with both run ids. `results-at`
answers the newest, so nothing waits on the defect. No flag, quarantine or rerun loop. The sacrifice: since no key is
rerun, a flake is seen only when someone asks for `:seon.test/rerun?` or two runs race.

### 2.4 The cost of the key

The cold whole-program derivation (3–5 s, 5–6 GB) is paid once per JVM; the cost that recurs is the first derivation
after each changed program, because `reach-attribute` re-reads an attribute's whole datom range whenever its content
revision changes (any `defn` changes `:seon.fn/calls`), and `reached-graph` pulls every node it visits (7,657 reads for
three tests). cost-gb attributes most of that allocation to restoring Datahike index nodes (LZ4 frames, about 745 of
1,300 sampled MB) and found that batching the pulls alone stays red (481 ms / 1,246 MB against the parent's
605 ms / 1,523 MB). R11 removes the reads instead of batching them:

1. `reach-attribute` keeps, per branch, the map it derived for the previous revision with that revision's basis
   (`::reach-attribute-head` already names the previous revision), and builds the new map from it plus the datoms of
   `seon.db/entities-changed-after` that basis. The cost is proportional to the entities changed.
2. `reached-graph` takes every reach fact (`:seon.fn/calls`, `/references`, `/keywords`, `/spec`, `/doc`,
   `:seon.test/subject`) from those attribute maps, as `reach-declarations` already does for names and digests, so
   visiting a node reads nothing.
3. Component digests stay a Merkle hash, so a change rehashes only the components that reach it; the `::reach-index`
   cache stays keyed by content.

## 3. Reads, rendering and entrances

- **Reply** (`seon.turn/test-step`, which sets `:seon.turn.loop/broken` today, not `summary`): `broken` for every agent,
  `:seon.turn.loop/unknown`, and the count `:seon.turn.loop/red-before`; absent when empty (48 tokens measured in the
  first version's sample).
- **Current-state block** (`seon.context/step-section`): `:seon.context/test-counts` `{:broken :unknown :red-before
  :new-red}` first, then `:seon.context/broken-tests` with failing assertions, `:seon.context/unknown-tests` (symbols),
  `:seon.context/new-red-tests`, for every agent; the form `(my.test/reds)` gives each red's head and base result and
  the run ids behind both. A no-task agent's head is `head-entries` over its reached tests: `step-observation`
  accepts an absent task, whose obligations are empty (today it needs the task's `sym` and the block shows only
  `:seon.context/turns`, §8 P3).
- **`bin/lane commit --branch-evidence AGENT`** (`test-evidence`): a reaching red's base by `results-at` at the
  cluster head: red → `:seon.source/red-before`, which proves and is named in the trailer; green →
  `:seon.source/broken`, refused; unknown → unproven, naming the test and the form that runs its base on the agent's
  branch. It runs nothing, so it needs no execution handle (narrow check F4).
- **`bin/seon check`**: `seon.operator/check-verdict`, pure, over the throwaway's members (`bin/test-check` writes them
  under `SEON_TEST_RESULT_FILE`) and main's `results-at` under `:compiled`: red at both does not refuse, green at the
  base refuses, unknown refuses.
- **Evaluator** (narrow check F5): every `results-at` caller passes the evaluator it compares, from the one decision
  `seon.test/run-request` makes (`:compiled` for root, `:interpreted` otherwise): `select` its request's,
  `step-observation` and `base-results` the agent's (`:interpreted`; root's `:compiled`), `test-evidence`
  `:interpreted`, `check-verdict` `:compiled`.

## 4. Edge cases

| Case | Design |
|---|---|
| A red at an unchanged key after an unrelated write | reused (R1); today rerun (§8 P3) |
| A result produced by a stale body | no key (R2): kept and shown, never reused |
| A dial changed by `config/apply!` | new config digest: every result of that cluster reruns once (R4) |
| Over-bound or unfinished member | `:seon.test/result-unknown` (exists): no outcome, no reuse, `:pending` at the head, unknown at the base |
| Designed red (`:seon.error/unimplemented` on an open task) | red at the base on main → red-before; a Prove step's own product → new-red under `designed-red?` (which accepts a plain `:fail` today: M7's task) |
| Compiled result, interpreted asker | absent: main's compiled results never decide an agent's interpreted red |
| Main holds nothing at the base key | unknown, with the form that runs it; run once on the agent's branch (R5), then reused |
| Main moved after the fork | `results-at` reads main's head beside the branch |
| A test the branch deleted | absent at the head, never listed |
| Undigested declaration in the closure | `reach-digests` refuses (`:seon.test/unknown-error`): no key, the test runs |
| Root after a merge | the merge gate's answers are on main (R8), so the merged keys rerun nowhere |

## 5. Deleted, with the phase converting the last caller

| Deleted | Callers converted | Phase |
|---|---|---|
| `red-holds?`, `revisions-now` and the sentence "red evidence only while the program is also unchanged" in `seon.test/select` | `select` only | R1 |
| the boolean fork point (`step-observation`'s `(not (broken s))`, `fork-point-red`'s `(zipmap present (repeat false))`, `:seon.context/fork-point` `[:map-of :seon.test/sym :boolean]`) | `step-status`, `step-observation`, `base-results`, `record-broken!`, `fork-point-red`, `change-reds`, `context_test` table, `test_failure_facts_test` | R6 |
| the child's lost run: `fork-point-red` recording on the released child | `fork-point-red` | R5 |
| `:seon.agent/broken`, `:seon.agent/new-red` (schema `seon.agent.edn`, writes in `record-broken!`, the read in `step-observation`) | `seon.context/step-observation`, `seon.turn/record-broken!`, `seon.turn-test` (asserts `:seon.agent/broken`), intent in `resources/seon/tasks/seon.turn.edn` naming `record-reached-red!`, the `:seon.turn.loop/broken` description in `resources/seon/schemas/seon.turn.loop.edn` (second check F2) | R6 |
| `seon.test.runner/red-at-current-reach?` | `test-evidence`; must-read of `seon.turn/a-reached-test-unrun-or-red-on-main-does-not-block-done`, `seon.cluster.source/a-test-never-run-on-the-target-is-not-blamed-on-the-merge`; `docs/seon/schedule/cards/ROOT-AGENT.md` | R9 |
| `--accept-red-before`, `bin/lane evidence … -- TEST`, the accepted trailer, `:seon.source/accepted-red-before`, `verdict`'s `accepted` | `bin/lane`, `seon.source.edn`, `branch_evidence_test`, `RF-2.md`, `agent-loop-ledger.md`, `docs/research/agent-platform/2026-10-08/lane-templates/repl-slice.md` (F2), intent in `seon.task.edn`; the intent in `seon.operator.edn` is converted by R10, which owns that file | R9, R10 |
| task `seon.operator/bin-lane-commit-cannot-accept-a-test-already-red-on-head` | `seon.operator.edn` | R10 |
| `seon.test.runner/result-at-reach` (generalized into `results-at`) | `seon.context/base-results`, its runner test, the must-read of the task in `resources/seon/tasks/seon.context.edn` that names `result-at-reach` (second check F2) | R6 |
| per-attribute full re-read in `reach-attribute`, per-node pull in `reached-graph` | both private to `seon.test.runner` | R11 |

Not deleted: `seon.test/change-reds`, `red-test-verdicts` (M7's); `fork-point-red` (it is the one place a base runs).
The central round's `OUT` stays: the round no longer runs on main (Q2), so the first version's T5 is dropped, and with
it narrow-check failures F3 and F7.

## 6. Phases (about fifteen minutes each; HEAD boots after every one; each proof red on HEAD 5640aef92; costs through `seon.profile/measure`)

Status per phase: **filed as `ns/name`** (a task in `resources/seon/tasks`, intent prefixed `(hybrid-test-results Rn)`); each landing updates its phase's status.

| # | Slice and owned files | Red proof (data assertion; why red on HEAD) | End-to-end cost bound | Status |
|---|---|---|---|---|
| R1 | **Selection reuses a result at its key, red and error included.** Delete `red-holds?` and `revisions-now` in `seon.test/select`; a red at an equal reach digest, input digest and evaluator is `:seon.test/unchanged` with its failure data (the `red-records` read exists). Files: `src/seon/test.clj`, `test/seon/request_cost_test.clj`. Platform slice (the test runner): `bin/lane commit … --test seon.request-cost-test --test seon.test.redefined-callee-test` | New `seon.request-cost-test/an-unrelated-write-reruns-no-test-at-an-unchanged-key` (pattern of `a-request-counts-its-own-calls-reads-and-transactions`): as root on the fixture cluster, define `f`, a red and a green deftest on it, one request runs both; define an unrelated `u`; then the next request is `(my.test/check {:seon.test/identities #{green red}})` through `seon.profile/measure`: it asserts `:seon.test/executed-count` 0 and `:seon.test/reused-count` 2, the red included (`:passed?` false, failure data present). Red on HEAD: `executed-count` 1, `reused-count` 1, because `select` lists the red to run after an unrelated write (§8 P3; second check row 9). The step after the unrelated `defn` already runs nothing on HEAD, so "no `:seon.cost/tests`" is not the assertion. `a-red-test-reruns-green-after-its-callee-is-redefined` stays green (a real redefinition changes the key) | the measured request < 1 s with zero executed bodies (root today: 54 tests, 10.6 s, 4.3 GB) | filed as `seon.turn/a-read-only-request-re-runs-the-branchs-reaching-tests` |
| R2 | **A result is keyed by what its body executed.** `measured-body` returns the sink's `seon.batch/executed` keys; `record-latest-tx` stores `:seon.test.member/dynamic-calls` (executed symbols outside `reach-memberships`) and omits the reach digest when an executed digest differs from the tested definition digest; `reach-digests` takes extra roots per test; `select` computes each key with its newest result's dynamic calls. Files: `src/seon/test.clj`, `src/seon/test/runner.clj`, `resources/seon/schemas/seon.test.member.edn`, `test/seon/test/redefined_callee_test.clj` | New `seon.test.redefined-callee-test/a-test-reruns-when-a-function-it-calls-by-a-computed-name-changes`: an own-branch agent defines `k2` and a deftest calling it by a built symbol; redefines `k2`; the next `my.test/check` has `:seon.test/executed-count` 1 and `passed?` false. Red on HEAD: the green is reused while the body answers false (§8 P5) | recording overhead < 1 ms per member; 0 datoms for an empty set (measured over the round's members, reported) | filed as `seon.test/a-result-is-keyed-by-what-its-body-executed` |
| R3 | **Multimethod and protocol bodies are declarations on a branch.** The branch write path declares `defmulti`, `defmethod` and protocol extension forms as the file index does (`seon.fn.analyzer/attributed-usages` attributes method bodies to the method var). The owner of the write path is verified first (`seon.sci.eval/install-evaluated-rows!` is the candidate). Files: `src/seon/sci/eval.clj`, `test/seon/test/redefined_callee_test.clj`, `test/seon/test/reach_test.clj` | New `seon.test.redefined-callee-test/a-test-reruns-when-a-method-of-its-multimethod-changes` (§8 P5's `t-multi`), as separate requests: `defmulti`, then `defmethod`, then the `deftest`, each in its own request, then the method changed, then `my.test/check` of the test: `executed-count` 1 (the test is re-executed), `passed?` false. All four forms in one request end in `:seon.sci.eval/acquisition-refusal` ("Program row could not be installed", second check F5), which the implementer verifies first and files as its own task; a file case added to `seon.test.reach-test/a-digest-follows-its-closures-content-and-nothing-else`: a changed defmethod body changes the digest of a test calling the multimethod. Red on HEAD: `area` has no entity on the branch and the green is reused | defining a method < 100 ms request (today's `defn` requests 446 ms whole, its evaluation 22 ms) | filed as `seon.sci.eval/a-defmethod-is-a-declaration-on-a-branch` |
| R4 | **Configuration is in the key.** `seon.test.runner/provenance` adds `:seon.test.run/config-digest` (`seon.id/id` of `seon.config/effective` for the run's cluster on the tested database); `select` compares it beside the input digest. Files: `src/seon/test/runner.clj`, `src/seon/test.clj`, `resources/seon/schemas/seon.test.run.edn`, `test/seon/test/selection_test.clj`. One-time effect: every stored result lacks the digest and reruns once (§7) | New `seon.test.selection-test/a-changed-dial-reruns-a-test-that-reads-it`: a fixture run, then `support/apply-config!` changes one dial; `select` lists the test to run. Red on HEAD: `select`'s run filter compares input digest, evaluator and reach only | one `config/effective` per run, < 5 ms | filed as `seon.test/a-changed-dial-reruns-a-test-that-reads-it` |
| R5 | **A base run is kept on the agent's branch.** `seon.test/admit-run` admits a run whose tested commit is the destination branch's base (`:seon.test.run/tested-branch` the child, member digests on the tested database, which `record-latest-tx` already takes as `tested-database`); `fork-point-red` records on the execution's connection. Files: `src/seon/test.clj`, `src/seon/test/runner.clj`, `test/seon/test_test.clj`. Writer slice: `--test seon.test-test` | New `seon.test-test/a-base-run-is-read-at-the-next-step-without-running`: two `fork-point-red` calls for one red; the second executes no body and answers red. Red on HEAD: the child's run is released with the child (narrow check claim 13: runs 5 → 5) | second call < 10 ms; the first is one test run (≈ 300 ms for a fast test) | filed as `seon.test/a-base-run-is-kept-on-the-agents-branch` |
| R6 | **One status map, unknown its own set, the judgment derived.** `seon.test.runner/results-at` (generalizing `result-at-reach`); `:seon.context/fork-point` becomes `test → :seon.context/proof-status`; `step-status` derives `:seon.context/unknown`; `step-observation` and `base-results` read `results-at` at the base keys; `record-broken!` only runs the bases nobody holds (R5) and writes nothing; delete `:seon.agent/broken`, `:seon.agent/new-red`. Files: `src/seon/test/runner.clj`, `src/seon/context.clj`, `src/seon/turn.clj`, `src/seon/test.clj`, `resources/seon/schemas/{seon.context,seon.agent}.edn`, `resources/seon/tasks/seon.turn.edn`, `resources/seon/tasks/seon.context.edn` (the must-read naming `result-at-reach`), `resources/seon/schemas/seon.turn.loop.edn` (the `:seon.turn.loop/broken` description naming `:seon.agent/broken`), `test/seon/context_test.clj`, `test/seon/turn_test.clj`, `test/seon/test_failure_facts_test.clj`, `test/seon/test/runner_test.clj` | `seon.context-test/each-step-kind-is-done-by-its-one-rule`: every case converted to statuses, plus `{p :green r :pending}` → `:working`, `unknown #{r}`, `broken #{}`. New `seon.test.runner-test/results-at-reads-each-tests-newest-result-at-its-key`: a green at the base key and a red at the head key → the green at the base key; an `:interpreted` result asked under `:compiled` → absent. Red on HEAD: boolean schema, no `:seon.context/unknown`, no `results-at` | block read < 20 ms warm (14–16 ms today) | filed as `seon.context/a-red-with-no-base-result-is-unknown` |
| R7 | **Every agent sees its reds' origin.** `step-observation` accepts an absent task (obligations empty, head = `head-entries` over the reached tests); `test-step` sets `:seon.turn.loop/broken` for every agent, `unknown`, `red-before`; `step-section` counts; `my.test/reds`. Files: `src/seon/context.clj`, `src/seon/turn.clj`, `src/my/test.clj`, `resources/seon/schemas/{seon.turn.loop,seon.context,my.test}.edn`, `test/seon/context_test.clj` | `seon.context-test/broken-tests-show-with-their-failures` (exists, long) gains a no-task agent whose change turns a base-green test red: in `:seon.context/broken-tests`, `test-counts {:broken 1 …}`, `(my.test/reds)` base green. Red on HEAD: the block holds only `:seon.context/turns` (§8 P3) | block read < 20 ms warm | filed as `seon.context/every-agent-sees-where-its-reds-came-from` |
| R8 | **(After M7) Root reads branches; the merge gate's answers stay on main.** Root's `results-at` databases are main and every live branch head; the gate's run on the merge branch is admitted on main through R5's admission (tested branch the merge branch). Files: `src/seon/test/runner.clj`, `src/seon/test.clj` (`admit-run`, shared with R5), M7's merge owner (today `seon.cluster.source/accept-merge!`), `test/seon/cluster/merge_test.clj` | New `seon.cluster.merge-test/main-keeps-the-merge-results-after-the-branch-is-deleted`: after merge and branch deletion, `results-at` on main holds the gate's results at the merged keys and root's next request executes no body for them. Red on HEAD: `:seon.data` runs stay on the deleted branch | recording increment < 100 ms; root's read over live branch heads < 20 ms (0.15 ms per branch head per `result-at-reach`) | filed as `seon.cluster.squash/main-keeps-the-merge-results-after-the-branch-is-deleted` (after M7; decided, §7; entry in `tr-tasks-patches.md` until lane m3 releases `seon.cluster.squash.edn`) |
| R9 | **`--branch-evidence` reads the same answer; the accepted list goes.** `test-evidence` sorts reaching reds by `results-at` at the cluster head under `:interpreted` and passes the reached set once; `:seon.source/broken` refuses, red-before proves, unknown names the form; delete per §5 (F2's lane template included). Host-bound slice (`bin/`): `--test seon.cluster.branch-evidence-test`. Files: `src/seon/cluster/branch_evidence.clj`, `src/seon/test/runner.clj`, `resources/seon/schemas/seon.source.edn`, `bin/lane`, `test/seon/cluster/branch_evidence_test.clj`, `resources/seon/tasks/{seon.turn,seon.cluster.source,seon.task}.edn` (the `seon.operator.edn` intent is R10's), `docs/seon/schedule/cards/{RF-2,ROOT-AGENT}.md`, `docs/seon/schedule/agent-loop-ledger.md`, `docs/research/agent-platform/2026-10-08/lane-templates/repl-slice.md` | `seon.cluster.branch-evidence-test/a-branch-proves-its-files-only-with-current-green-evidence`: "a test red on H too is red before" asserts `proven?` true with no accepted set; a new `testing`: green at H and red on the branch is in `:seon.source/broken` and refuses. Red on HEAD: proven only when accepted | answer < 1 s over the prepl; it runs no test | filed as `seon.cluster.branch-evidence/a-reaching-red-is-proven-red-before-by-its-result-at-the-base-key` |
| R10 | **`bin/seon check` judges its reds against main.** `seon.operator/check-verdict` (pure) over the result file and main's `results-at` under `:compiled`; `check!` sets `SEON_TEST_RESULT_FILE` and asks the live root over the prepl. Files: `script/seon/operator.clj`, `test/seon/operator_test.clj`, `resources/seon/tasks/seon.operator.edn` | New `seon.operator-test/a-check-red-main-holds-red-does-not-refuse` (table over `check-verdict`): main red → not refused, red-before; main green → refused; absent → refused, unknown. Red on HEAD: no such function | the prepl read < 200 ms | filed as `seon.operator/a-check-red-main-holds-red-does-not-refuse` |
| R11 | **The key costs what changed.** `reach-attribute` derives a new revision's map from the previous one plus `seon.db/entities-changed-after` its basis; `reached-graph` reads node facts from attribute maps, no pull per node. Files: `src/seon/test/runner.clj`, `test/seon/test/reach_test.clj` | `seon.test.reach-test/a-changed-programs-first-reach-digest-is-subsecond` (cost-gb's proof, saved in `cost-gb-reach.patch`, re-added): after one declaration transaction, `reach-digests` for one real test < 100 ms and < 128 MB through `seon.profile/measure`. Red on HEAD: 605 ms / 1,523 MB (cost-gb parent) | the proof's bounds; a whole-program derivation after one change reported beside them | filed as `seon.test.runner/the-first-reach-digest-after-a-change-costs-what-changed` |
| R12 | **Two outcomes at one key are a problem of the test.** `seon.problems` family `:seon.problems/nondeterministic-tests` from members at one key with different outcomes, both run ids; `results-at` answers the newest. Files: `src/seon/problems.clj`, `src/seon/test/runner.clj`, `resources/seon/schemas/seon.problems.edn`, `test/seon/problems_test.clj` | New `seon.problems-test/two-outcomes-at-one-key-are-a-nondeterministic-test`: two fixture runs of one test at one key, one green one red (the second with `:seon.test/rerun?`, its body reading a value the fixture changes between runs), → one entry with both run ids. Red on HEAD: no such family | inside `failed-tests`' existing read: < 10 ms added | filed as `seon.problems/two-outcomes-at-one-key-are-a-nondeterministic-test` |

**Order.** R1 first (the owner's measured proof; it touches only `select`). R2 and R4 next: R2 closes the stale-body
class for green and red alike, R4 the configuration input. R3 and R11 independent. R5 → R6 → R7; R9, R10 after R6; R8
after M7; R12 after R1. File collisions with hybrid-done-and-merge: `src/seon/context.clj` (M7, M14) and
`src/seon/turn.clj` (M8, M10, M11, M13): root sequences R6 and R7 before M7 so M7's gate inherits
`:seon.context/unknown`. R1, R2, R5, R6 are Opus (the one path's evidence and the runner); R9, R10 platform slices;
R3, R4, R11, R12 Sonnet, with Astra for R11 if its first lane bows out.

## 7. The owner's rulings applied (all decided; no question remains)

The first version's four questions are answered by the owner's rule (2026-10-12):

- **Q1 → runs are data.** `:seon.test.run/run` stays `:seon.program/partition :seon.data`; nothing merges. AGENTS.md's
  "The program is entities: … test evidence keyed by exercised definition digests" should say evidence is data keyed
  by digests (a root edit to AGENTS.md, not this lane's file).
- **Q2 → no batch tier on main.** The central round is not run on main; main runs only keys nobody ran. The first
  version's T5 is dropped.
- **Q3 → a flake is its own task**, derived (R12); the newest result answers meanwhile.
- **Q4 → the base run is kept and reused by its key** (R5); the stored judgment is deleted (R6).

The three questions this version put to the owner are answered:

1. **Root records its merge gate's answers on main (R8).** The gate reads the source branch's results at the merged
   keys and root's merge admits them on main as root's run, so a merged change reruns nowhere. This is root recording
   its own observation, not a merge of the branch's runs. Rejected: recording nothing, which reruns each merged
   change's reaching tests once on main after the source branch is deleted.
2. **Cluster data a test reads that is neither program, schema, configuration nor input files** (tasks, agents,
   messages on the tested commit) is not in the key (R12). AGENTS.md requires deterministic tests with injected
   inputs; a test that reads such data surfaces as non-deterministic through R12. Rejected: keying it with a
   per-member read sink and `seon.db/content-revisions`, about three phases that rerun every test reading a
   frequently written attribute on every request.
3. **R4 is accepted: a restart never changes a key.** The configuration digest is a new part of the key; a result
   stored before R4 lacks that part, so it is not at any current key and each test runs once, wherever its next
   request is. Nothing else reruns because of R4.

Not a question: the evaluator stays in the key (both plans, and 205 interpreted-only reds). Its consequence is stated:
root's compiled results on main never decide an agent's interpreted red; an agent's bases come from its own runs.

## 8. Verification on default (2026-10-12)

- **P1 (status).** `runtime_status` (second check): state `:unknown`, counts only 6 untested-hot warnings, commit `6acc85d3…`; no open faults.
- **P2 (main, jvm as root, read-only; F1).** 5 runs, 167 members, 56 tests with results, 7 red; 2,887 `:seon.test/sym`
  declarations; 11 `:seon.agent/branch` datoms, 1 distinct value; the latest members' evaluators 55 `:compiled`, 1 `:interpreted`;
  `seon.turn/test-run`'s `:seon.fn/references` `[seon.test/run]`. 24 ms.
- **P3 (red rerun, sci as `tr-revise`).** `g` (returns 1), `k`, `h` (`(inc (g))`), deftests `t-static`, `t-resolve`
  (`((resolve 'trr.probe/k))`), `t-red` (`(= 2 (g))`): reply red `[trr.probe/t-red]`; request 820.8 ms, test step
  384 ms, step allocation 733 MB for three tests of 44.7 ms. Then `(defn u … 7)`: no test executed; request 446 ms,
  test step 265 ms, step 568 MB. Then `(+ 1 1)`: 180.5 ms, test step 124 ms, step 276 MB, no test executed (a step that
  runs nothing costs 124–265 ms and 276–568 MB: the open test-step cost tasks). Then `seon.test/select` (`:named`,
  `:interpreted`, the three tests) in `seon.profile/measure`: 11.0 ms; to run `[t-red]`; reused `t-resolve`,
  `t-static`; `t-red`'s current reach digest `1efe171c…` equals its latest result's. `(keys (seon.context/current-state))`
  before the probe → `(:seon.context/turns)`.
- **P4 (static reach).** `reach-memberships`: `t-resolve` holds `trr.probe/k` (a quoted symbol is a reference);
  `t-static` holds `h`, `g`.
- **P5 (hidden calls).** `k2` (returns 4), `t-dyn` (`(requiring-resolve (symbol "trr.probe" (str "k" 2)))`),
  `(defmulti area :shape)` with an `:sq` method, `t-multi`: both green; `k2` appears in that request's cost diff as an
  interpreted call. Then `k2` → 5 and the method → `(* 3 s)`: the step ran no test; `select` reused both greens
  (`[t-dyn 1 0] [t-multi 1 0]`) while their bodies' expressions evaluated live answered `[false false]`.
  `reach-memberships`: `t-dyn` holds no `k2`; `t-multi` holds `trr.probe/area`, which has no entity
  (`pull` → nil; the namespace declares `g u h k k2`). `:seon.config.test.cost/top` is 10.
- **P6 (unresolved names, jvm).** Of 8,283 distinct `:seon.fn/calls` and `:seon.fn/references` targets on main, 377
  are in a program namespace with no declaration (a target whose namespace has a declaration and whose symbol has none) (sample: task symbols such as
  `my.program/a-search-limit-is-a-positive-integer`). 95 ms.
- **Code read.** `select`'s `red-holds?` and `revisions-now`; `latest` filters this branch's runs only, with no
  tested branch, equal input digest and evaluator. `measured-body` binds a fresh `seon.batch/sink`; `seon.profile/timed`
  counts by `[sym digest]`. Test bodies run with `db/*read-evidence-sink*` nil. `record-latest-tx` computes
  `reach-memberships` of the tested database and stores `:seon.test/reach`. `reach-node` takes calls, references,
  subject, keywords and the contract's keywords; `reach-schema-node` hashes the form. `reach-attribute` re-derives a
  whole attribute map per content revision and retires the branch's previous one. `failed-tests` already judges a red
  by its current reach digest. Carried from the first version and re-verified by the narrow check (claims 4–17, 20–25):
  the base digests differ from the head's, `result-at-reach` across programs answers `{}`, `fork-point-red` costs
  ≈ 300 ms and 250 MB per red and loses its run.
- **Side effect of the narrow check (F6).** Its probe, a sci `(doc …)` evaluated as root, ran root's automatic test step (13 tests, 10.6 s, 3.7 GB) and wrote 13 results to main. No design change; it reproduces the cost the design removes (R1). Checks of this kind read in jvm mode only.
- **Not verified here:** root's `(doc my.root/close!)` cost (cited from the brief and the narrow check; running it
  writes runs to main); R4's red case end to end; whether file-declared defmethod bodies change the multimethod's leaf
  (R3's file case decides it); the size of `:seon.test.member/dynamic-calls` over the suite. No main write, no reset, no
  reload, no uncommitted source loaded. The probe branch's retirement returned `:my.agent/retiring? true`,
  `:my.agent/retired? false` (the known lifecycle task `seon.turn/a-closed-attempt-releases-its-connection-before-branch-deletion`).

## 9. Narrow check, self-applied

1. **Every live claim reproduces** (§8): main's counts re-read (5 runs, 56 tests, 7 red, 2,887 declarations,
   1 distinct branch value); the red rerun after an unrelated write (P3); quoted symbols in reach (P4); the reused greens of a
   computed-name call and a multimethod method while their bodies answer false (P5); the 377 unresolved names (P6). The
   numbers carried from the first version are the narrow check's reproductions, cited by claim. The root rerun cost is
   cited, not reproduced (§8).
2. **Every deletion is in the phase converting its last caller** (§5), searched with `rg` across `src`, `test`,
   `script`, `resources`, `bin`, `docs/seon` and the lane templates: `:seon.agent/broken` and `/new-red` have three
   readers (`step-observation`, `record-broken!`, `turn_test`) and one task intent, all in R6; `red-holds?` is local to
   `select`; `red-at-current-reach?` and the accepted list as the narrow check counted them, plus `repl-slice.md` (F2),
   in R9. HEAD boots after each phase: no phase removes a function a later phase still calls; `result-at-reach` is
   replaced in R6, the phase that converts `base-results`.
3. **Every phase names a red proof** in an existing or named file, a data assertion, and why it is red on HEAD (§6).
   R1 and R11 are measured-cost proofs through `seon.profile/measure` (AGENTS.md 64668ecae); R8 is conditional on M7.
4. **The narrow check's failures:** F1 counts updated (§8 P2). F2: R9 converts the lane template. F3, F7: removed (the
   round's phase is dropped, Q2). F4: removed (`--branch-evidence` runs nothing; the agent's step runs bases). F5:
   every `results-at` caller's evaluator is named (§3). F6: the no-task head is `head-entries` over the reached tests,
   and `test-step`, not `summary`, sets `:seon.turn.loop/broken` (§3, R7). F8: removed (the judgment is derived on read,
   nothing is persisted between phases). F9: R8 depends on M7 and names today's owner, `seon.cluster.source/accept-merge!`.
5. **Words:** Clojure, Datahike, Malli, clojure.test and git only; retired words appear only inside quoted code.

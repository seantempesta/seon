---
title: "Merge audit (Opus): the one merge, its cost per component, and what to delete"
type: audit
status: "read-only audit 2026-10-17: 16 refusal kinds -> 6, path ~4,330 -> ~1,830 lines; decisions for root in section 7"
---

# Merge audit (Opus lane, 2026-10-17)

The owner asked whether the merge code was done correctly, and refined the question during the audit. The four
refinements, all dated 2026-10-17:

1. **Cost.** "We have one way of merging that's simple and should require minimal cost … How much work do we actually
   need to do and what are the cost estimates for each component?"
2. **Reactive testing is core.** Tests run reactively when a function is written; an unchanged function never reruns.
   The merge makes a merge branch, tests only what is new, merges when green, and hands a conflict to the merge
   agent.
3. **Agents never run tests.** "It's auto running the tests so the agent doesn't need to."
4. **Instrumentation validates.** "Through instrumentation it should be auto validating everything."

**Verdict: no.** The merge needs one comparison over C changed entities, one test selection, a lookup of evidence that
the reactive test step already recorded, a run only of the members whose reach digest is new on the merged result, and
one transaction. Around that, today's code carries:

- five derivations of "which tests reach this change";
- three of "is this red at the merge base";
- two merge-base algorithms and two judges;
- a judgment computed four or more times per merge, and a dry-run merge inside every done check;
- writer-side race fences and about 175 error-value guard sites on the path;
- a throwaway JVM per merge.

Each of the 2026-10-16 commits patched two of these duplicates into agreement instead of deleting one. The unapplied
`same-evaluator-red.patch` would add another layer, an evaluator match, to an exemption that should not exist.

This audit is read-only and was written without reading the Astra report.

---

## 1. The reference model, from the work itself

Sizes:

| Symbol | Meaning |
|---|---|
| C | program entities the chain changed since its merge base O (5–50) |
| R | tests reaching them (measured below: 10 / 52 / 73 for C = 5 / 20 / 50) |
| M | main's moves since O |
| R_new | members whose reach intersects M, so their reach digest is new on the merged result |
| F | files to write back |

### What already exists as reactive evidence

Every eval request on the chain branch B runs the test step:

- the merge's member list (`seon.cluster.source/merge-members`) of B's change;
- only the stale members, meaning those with no result at their current reach digest;
- results recorded on B and keyed by reach digest (`seon.test.runner/reach-digests`, `current-results`).

An unchanged definition never reruns. Every contracted call is validated on entry and on return by its instrumented
wrapper. So by the time a chain ends, B holds a green or red result for every member at B's reach digests, and every
value that crossed a contracted function was already checked.

### The one merge

```
O       = merge-base(B, M)                                   cached by the id pair
Δ       = diff(O → B), program attributes                    C identities, cached by content revision
X       = Δ ∩ moved(O → M)                                   conflicts: 2·C digest lookups
merge branch S = M + replacement(Δ)                          one transaction on a fork of M
if X non-empty: S holds main's version beside the unmerged entries
                → the merge agent (seon.step.merge/template) resolves on S. Stop.
T       = members(S, Δ, proofs)                              the same function the step runs; warm fold
evidence = result at reach-digest(t, S) for t ∈ T            a lookup: digests equal B's unless t reaches M's moves
run     = only R_new = {t ∈ T | no result at its S digest}   proportional to main's moves that touch the change
refusals = judge(S, Δ, T, results)                           THE done rule, the same function the step showed
green and no conflict: ONE transaction on main, parents #{M S}, writer CAS on M
                       (the facts squash plus S's program); publish: write back F files, one git commit
```

**Done and merge are one computation.** `judge` is `done-refusals` over B at the attempt's end, and the same function
over S at the merge. The inputs differ only for members whose reach includes something main moved since O. When main
has not moved, R_new = ∅, every result is a lookup, and the merge is a pure read plus one write.

The merge's checks are only the things instrumentation and the step cannot see:

- conflicts with main's moves;
- tests whose digest is new on S;
- contract completeness of new definitions, which the REPL admits with a warning and which therefore must be judged
  once, by `judge`.

---

## 2. Cost table: the merge

Measurements are read-only JVM-mode probes on default. Default was freshly nuked: 7,365 functions, 3,323 tests, no test
results held. The change is synthetic: C functions sampled from `seon.cluster.source`, `seon.merge`, `seon.context`,
`seon.task` and `seon.fn`. Forms are in §10.

**Lookup / new work** says, for each component, which part only reads existing evidence and which part is new
computation.

| # | Component | Lookup / new work | Proportional to | Expected (C≈5–50, R≈5–60) | Today, measured or from the code |
|---|---|---|---|---|---|
| 1 | Merge base | Lookup: commit-graph walk, cached by id pair | commits since fork | <5 ms | `source/merge-base` (datahike) for the program, and a **second algorithm** `registry/first-parent-where` for the facts (`seon.merge/assignment-row`); `accept-merge!` recomputes it again |
| 2 | Find the change | Lookup: Δ is the step's own cached derivation | C·log N | ≈1 ms warm, 5–15 ms cold | `own-changed-identities` **8–16 ms** cold. It is recomputed separately by `seon.context/change`, `seon.cluster.agent/done-judged`, `compare-commit` (prepare), `merge-main!` and `unmerged-count`. |
| 3 | Detect conflicts | New: digests of Δ at O and at M | 2·C avet lookups | 1–5 ms | `digest-map` ×3 + `three-way`: **2.5–4.5 ms**, which is right. `unmerged` 0.08 ms. |
| 4 | Make the merge branch | New: fork M, transact the replacement of Δ | C entities | 20–60 ms | `published-index-entities` + `reconcile-tx`: **15 / 23 / 36 ms** for C = 5 / 20 / 50. Done today by `candidate-gate!`'s `write!`. |
| 5 | Select tests on S | Lookup: the `merge-members` fold, keyed by content revision | C seeds; edges walked only for main's moved call edges | ≈0 warm, 40–65 ms cold | `merge-members`: **43–65 ms cold / 0.05 ms warm**. The merge also runs `merge-seeds` (**28–49 ms**, through `reverse-closure`, which selects **368 / 874 / 927 tests** for the same C), `merge-list`, one `gate-sets` walk per function (`uncovered-functions`), `unselected-reach`, and again in done through `publication`. |
| 6 | Key members by reach digest on S | Lookup, except for members reaching M's moves | R lookups + R_new digests | R × 0.5 ms | `reach-digests`: **312 ms (R=10, cold) / 67 ms (R=52) / 86 ms (R=73)**: 10–60× the expected cost. The step pays it on every request. |
| 7 | Run tests | New: **R_new only** | members reaching main's moves × test time | 0 when main did not move; otherwise R_new × (50–500 ms) | `candidate-gate!` runs the member list on S (reused by digest, which is right in shape). Then reds are run again at the base (`fork-point-red`). Isolated members get a **JVM boot per merge**: 38.9 s for the e2e proof, 70.1 s vs 38.6 s (5793e03be). |
| 8 | Judge | Lookup: R results + contract findings of Δ | R + C | <15 ms | `contract-gaps` **5–12 ms**. Two judges (`done-refusals`, `results-verdict`). `merge-verdict` runs **twice** per merge, each re-reading the run, `program-written-since?` and `tested-task-basis` (task obligations over three database values). |
| 9 | Write main | New: one transaction | C + facts | 20–60 ms | The program is written on S, then replayed on main. Main takes **two** transactions (the merge, then `:seon.task/merge-commit`). Two writer functions (`declarations-held-tx`, `task-basis-held-tx`) re-derive digests and obligations under the writer. `merged` (the facts squash) is computed twice. |
| 10 | Write back files | New: splice, re-analyze, lint, write, commit | F · file bytes | 50–300 ms per file | `proven-files` + lint run **three times**: in done (`publication`), in the gate (`prepare-merge!`) and in `write-back!`. TASK-CHAIN §2.4 measures 280 ms / 0.7 GB per uncached judgment. |

**Expected merge when main did not move:** about 50–150 ms of reads and one transaction, plus F × (50–300 ms) of
write-back. No test runs.

**When main moved:** add R_new test runs, where R_new is only the members that reach main's moves.

---

## 3. Cost table: the per-request path (test step, then done)

Agents never run tests. This path is the only place tests run during work, and done reads its evidence.

| Component | Lookup / new work | Expected | Today |
|---|---|---|---|
| Change and members | Lookup (cached fold) | ~1 ms warm | `step-members` → `merge-members` (correct since 56038bbb1) |
| Stale members | Lookup: reach digest per member, then a result lookup | R × 0.5 ms | `seon.test/stale` over three lists plus `reach-digests`, 67–312 ms |
| Run stale members | New: **one** request over stale(T) | k × test time | **Three** requests (`run-reaching`, `run-branch`, `run-proofs`), plus `run-merge-base` running reds at O. Proofs and branch tests are already in T, because `merge-members` adds the proofs and every test Δ names. Measured per request: 999 / 265 / 147 ms (56038bbb1). |
| Judge (= done) | Lookup | <15 ms | `step-reds` → `step-verdict` → `step-observation` builds the full judgment on every eval request. That includes `publication`, a dry-run merge of every file (280 ms / 0.7 GB uncached) and `red-at-merge-base`. `step-verdict` over 12 kept chains measured **12 s / 29 GB** (b54d25f68). |
| Done at the attempt's end | Lookup: the same call | <15 ms | `seon.cluster.agent/done-judged` rebuilds the judged input with its own code: a copy of `step-observation`'s chained branch, calling `merge-base` where the copy calls `branch-merge-base`. |

### Live-run evidence that reactive tests remove agent test-hunting

- **Run 4** (`docs/research/agent-platform/2026-10-16/live-run-4.md`, cause 2): chain fddd spent **95 forms** in
  `seon.test.runner` and fd3b spent 31 calls, both hunting a way to run or select a test. Done asked for results that
  nothing would produce.
- **After 56038bbb1**, the step runs the member list. Run 7 (`live-run-7.md`, item 6) says the hunt "did not recur":
  `step-verdict` forms 0, 0, 2, 0, 0, and `test.runner` / `run-vars` forms 0. Runs 5 and 6 had no forms to rank.

The evidence agrees. Once the step runs the list done judges, agents stop running tests.

---

## 4. Classification of every function on the path

Classes:

- **(a)** essential to the reference model;
- **(b)** a guard or fence beside a mechanism, including validation that contracts or the writer already guarantee;
- **(c)** a second derivation, or work that bypasses the reactive evidence;
- **(d)** dead or near-dead in production.

### `seon.cluster.source`, merge and judge section (lines 737–1891, 1,155 lines)

| Function | Lines | Class | Reason |
|---|---|---|---|
| `refused`, `merge-base-refusals`, `merge-base`, `merge-base-database`, `branch-merge-base` | 57 | a | One merge base. The criss-cross refusal is real. |
| `replacement`, `release!`, `merge-bound` | 19 | a | |
| `unselected-reach` | 17 | **d** | Reached only through the `:selection` option, which only tests pass (`test/seon/cluster/write_back_test.clj`). |
| `held-digests` | 16 | b | Its users are writer fences and a cache key; only `unmerged` needs it. |
| `moved-digests`, `declarations-held-tx` | 28 | **b** | A writer recheck that merged rows did not move after prepare. A CAS on M's head (the parent the merge names) makes the race impossible. |
| `merge-list`, `carried` | 40 | **c** | A partial merge ("stays"). It contradicts "`merge!` takes the whole delta" in CLAUDE.md and TASK-CHAIN §2.6. Probe: with no green results held, it moves all C changes to `:stays`, and `will-merge` is 0. |
| `merge-seeds` | 33 | **c** | A selection gated on green current results (`reverse-closure`, 368–927 tests). |
| `contribution`, `remove-contribution`, `add-contribution`, `fold-members`, `member-contributions` | 107 | a | The incremental fold behind the one selection. |
| `merge-members` | 37 | **a** | The one selection. |
| `merge-changes`, `agent-owned` | 31 | b | Self-described interim exemption until `seon.cluster.agent/agent-creation-writes-no-program-namespace`. Fix at the producer. |
| `unmerged-tx`, `entry-identity`, `unmerged`, `with-unmerged`, `compare-commit`, `compare-branch`, `unmerged-sets` | 108 | a | Three-way comparison and git's unmerged entries. |
| `unmerged-count` | 15 | c | A whole three-way to classify a non-done source. That path disappears with one merge path. |
| `tested-task-basis`, `task-basis-refusal`, `task-basis-held-tx` | 50 | **b** | Obligations re-derived in prepare, twice in `merge-verdict`, and inside the writer. |
| `lint-refusal`, `file-refusal` | 22 | a | Judged once, at write-back's proof. |
| `publication` | 26 | **c** | A dry-run merge inside done, bypassing the evidence. |
| `prepare-merge!` | 158 | a→rewrite | Its core (compare, conflict, replace on S) is about 30 lines. The rest is selection, double verdict, base re-runs and fences. |
| `red-test-verdicts`, `red-test-refusal`, `green-result?` | 39 | a | |
| `incomplete-findings`, `contract-gaps`, `gap-symbols`, `uncontracted`, `uncontracted-refusal` | 57 | a | The contract-completeness law. |
| `uncovered-message`, `uncovered-refusal` | 19 | a | The coverage law. |
| `uncovered-functions` | 16 | **c** | A separate `gate-sets` walk per function. Coverage should be seed → tests from the one selection. |
| `results-verdict` | 56 | **c** | The second judge. |
| `versions`, `unmerged-refusal` | 24 | a | |
| `red-at-merge-base` | 17 | **b** | Excuses a red member. CLAUDE.md says "a test already red on main stops it for root". |
| `done-refusals` | 63 | **a** | The one judge, loses three clauses. |
| `merge-verdict` | 62 | **b** | Run-basis fences around the second judge, called twice. |
| `accept-merge!` | 66 | a | The one transaction, minus fences. |

### `seon.merge` (650 lines)

| Function | Lines | Class | Reason |
|---|---|---|---|
| `collision-remap`, `cluster-entity?`, `add-count`, `attribute-tx`, `member-merge`, `shown-state`, `squash-merge`, `conflict-task!`, `merge-tx` | 194 | a | The facts squash. |
| `merged` | 21 | a | Its second call, in `answer`, is (c). |
| `assigned-task`, `merged?`, `acceptance` | 37 | a | |
| `chain-row` | 22 | c | Indirection to a merge step's row. |
| `assignment-row` | 24 | **c** | The second merge-base algorithm. |
| `merge-onto`, `merge!` | 135 | a→rewrite | Three paths and two transactions become one path and one transaction. |
| `absent-references`, `carried-entities`, `merge-main!` | 125 | a | Main forward into a chain mid-run. |
| `checkout`, `indexed!`, `publish!` | 104 | a | |
| `merge-commit` | 10 | b | Recovery for a crash between the two transactions. |

### Gate and test

| Function | Lines | Class | Reason |
|---|---|---|---|
| `seon.cluster/candidate-gate!` | 52 | a | Makes S and runs T reused by digest. This is the owner's model; keep it, trimmed. |
| `seon.cluster/reaching-run` | 41 | a | Selects T on S with the same function. Its platform branch is (b). |
| `seon.cluster/isolated-run`, `seon.test.platform/check-files!` and helpers | ≈250 | **b** | A JVM boot per merge. See decision 3. |
| `seon.test/held-red`, `fork-point-red` | 45 | **b** | Red-at-base machinery. |
| `seon.test/host-exclusions`, `isolated-members` | 76 | a | |

### Step and done (`seon.context`, `seon.turn`, `seon.cluster.agent`)

| Function | Lines | Class | Reason |
|---|---|---|---|
| `context/change`, `step-members`, `current-results`, `step-verdict`, `step-section`, `task-done?` | ≈90 | a | |
| `context/step-observation` | 77 | c | Includes `publication` and `red-at-merge-base`; duplicated by `done-judged`. |
| `context/step-status` | 12 | c | Dispatches between two judges. |
| `context/branch-changes` | 37 | c | Shows "stays" from `merge-seeds` + `merge-list`. |
| `agent/done-judged` | 47 | **c** | A copy of `step-observation`. |
| `turn/run-merge-base` | 15 | **b** | Runs reds at the base after every request. |
| `turn/run-branch`, `run-proofs`, `branch-due` | ≈45 | c | Members already contain them. |
| `turn/prove-turn` | 31 | c | Re-implements the step's ordering. |
| `turn/step-reds` | 12 | c | A full verdict per request, only to name the reds the runs returned. |
| `turn/step-proved?` | 19 | c | A third "every proof has a result" derivation. |
| `turn/test-step`, `run-reaching`, `step-tests`, `task-proofs`, `step-due?` | ≈120 | a | |
| `agent/merge-chains!`, `refusals-verdict`, `merge-step` | 78 | a | Under the owner's model the merge step is the conflict path, so it stays. |

### Agent-facing test verbs: deletion candidates under "agents never run tests"

| Verb or instruction | Where | Class | Note |
|---|---|---|---|
| `my.test/check`, `check-now` | `src/my/test.clj` | c | The reactive step runs the same tests. |
| `my.test/run` (macro), `my.test/run-owned` | `src/my/test.clj` | c | The whole namespace (191 lines) goes except anything root still needs. |
| `my.test/check-platform` | `src/my/test.clj`; offered by `seon.turn/platform-proof` as `:seon.render/operations ['(my.test/check-platform {})]` | c | Root's batched platform check should own it. |
| `(my.test/check {:seon.test/changed [sym]})` offered on every Var render | `seon.render.var` (line 159) | c | Invites agents to run tests by hand. |
| Calling a deftest routes through `my.test/check` | `seon.sci.lookup` | c | Decide whether calling a test Var at the REPL stays as Clojure semantics or goes. |
| Instructions to call `(my.test/run)` and `(my.test/check …)` | `.agents/skills/clojure-testing/SKILL.md` | — | A skill claim to rewrite in the same commit. |
| Prose referring to `my.test/check` | `seon.batch` docstring, `src/seon/mcp.clj` platform-check text | — | |
| Task backlog about these verbs | `resources/seon/tasks/my.test.edn`, about 7 tasks | — | Becomes moot; retire it in the deleting commit. |

### Duplicate validation that instrumentation or the writer already guarantees

Counted in the merge section of `source.clj`, `merge.clj`, `context.clj` lines 340–500, `done-judged`, and the turn
step:

- **126** `(:seon.error/at …)` checks;
- **9** `read-refusal?` checks;
- about **50** `vector?`, `set?`, `map?`, `uuid?` and `string?` shape tests.

Nearly all of them thread `[:or value error]` returns from reads through every caller, by hand. CLAUDE.md "Nothing
crashes" says a non-tool function throws its error map inside `ex-info`, and the target S-reads conversion makes reads
throw. Under contracts plus throwing reads, these about **175 guard sites** reduce to the one catch at the entrant
(`db<->flow`, the step).

The specific validations that repeat what is already guaranteed:

- `seon.task/candidate-dangling` (refusal #10): the writer's ref integrity refuses a dangling task ref.
- `merge-verdict`'s run-basis checks: "tested branch holds no commit", "run not recorded on the tested branch" and
  "program changed after the run". Results are keyed by reach digest, which already encodes the program.
- `declarations-held-tx` and `task-basis-held-tx`: one head CAS replaces both.
- `green-result?` and `executed` re-checking `:seon.error/at` on results that the contract of
  `:seon.source/judged` already admitted.
- `results-verdict`'s "Every proof must be a member" (#5): impossible by construction.
- `assignment-row`'s two refusals, and `squash-merge`'s and `conflict-task!`'s `:seon.error/at` re-checks of writer
  answers.
- The cost of instrumentation itself: `:seon.source/judged` and the results vectors are validated on entry to every
  judge call. With the judgment built four or more times per request (`step-reds` → `step-verdict`, `prove-turn`,
  `done-judged`, twice in `merge-verdict`), the O(R) validation repeats. One judge called once removes the repeats.

### Counts

| Class | Functions | Lines (approx.) |
|---|---|---|
| (a) essential (some bodies rewritten) | 66 | ≈2,750, including write-back's 968 |
| (b) guard or fence, including duplicate validation | 15 | ≈560, plus about 175 guard sites inside (a) bodies |
| (c) second derivation or bypass of the evidence | 21 + 6 agent verbs and offers | ≈740 + ≈191 (`my.test`) |
| (d) dead or near-dead | 1, plus the `:selection` option | ≈30 |

---

## 5. Where the same fact is derived more than once

| Fact | Derivations |
|---|---|
| Tests reaching a change | 1. `merge-members` (`seon.test/reaching`)<br>2. `merge-seeds` (`reverse-closure` + a green filter)<br>3. `merge-list` (`reach-closure`)<br>4. `uncovered-functions` (`gate-sets`)<br>5. `unselected-reach` (`reverse-closure`)<br>For `seon.merge/merge-tx` on default: `gate-sets` **3**, `reaching` **3**, `merge-members` **3**, `reverse-closure` **100**. |
| Merge base | `source/merge-base` (prepare, `accept-merge!`, `done-judged`, `merge-main!`); `branch-merge-base` (`context/change`, `run-merge-base`); `registry/first-parent-where` (facts). |
| "A red is the change's" | `red-at-merge-base`, `fork-point-red`, `held-red`; the patch adds evaluator matching. |
| The judgment | `done-refusals` and `results-verdict`; `merge-verdict` ×2 per merge; `step-reds` per request. |
| The judged input | `context/step-observation` and `agent/done-judged`. |
| Would-publish files and lint | `publication` (done), `prepare-merge!` (gate), `write-back!`. |
| Test runs of one change | The step on B (three requests), `run-merge-base` at O, the gate on S, `fork-point-red` at O, and the platform JVM. |
| Task obligations | `assigned-obligations` (step); `tested-task-basis` (prepare, `merge-verdict` ×2, writer). |
| Facts squash | `merged` in the writer, and again in `answer`. |

### Pattern in the history

The 2026-10-16 commits each synchronized a pair of duplicates instead of deleting one:

| Commit | What it synchronized |
|---|---|
| a689f0a35 | Gate seeds vs done seeds |
| 127fe54f6 | Added `held-red` and `red-at-merge-base` so done asks the merge's question |
| b54d25f68 | Done requires results "as the merge does" |
| 56038bbb1 | One member list, but `merge-seeds`, `merge-list` and `gate-sets` kept beside it |
| 5793e03be | A per-merge platform JVM after two reverted attempts (2d5477ea7, 70fc1a300) |

CLAUDE.md "Choosing the design" item 9 names this pattern: "patching reader after reader … the model is wrong."

---

## 6. The 16 refusal kinds and which survive

| # | Refusal | Class | Under the model |
|---|---|---|---|
| 1 | `:seon.source/conflict-error`: three-way conflict | a | **Stays**, and goes to the merge agent on S |
| 2 | `conflict-error`: unresolved unmerged entries (`unmerged-refusal`) | a | **Stays**, merged with #1 |
| 3 | `:seon.source/red-test-error` | a | **Stays** |
| 4 | `:seon.source/stray-red-error` | c | **Goes**: it is #3 |
| 5 | `test-evidence-error` "Every proof must be a member" | b | **Goes**: impossible by construction |
| 6 | `test-evidence-error`: a member with no executed result | a | **Stays** in the step's view ("not run yet"). It cannot occur at the merge once the step ran. |
| 7 | `test-evidence-error` "A replaced function reaches no green test" | a | **Stays**, derived from the one selection |
| 8 | `:seon.source/uncontracted-error` | a | **Stays**, unless contract completeness moves to the write |
| 9 | `:seon.source/lint-error` | a | **Stays**, once, at write-back |
| 10 | `test-evidence-error`: dangling task refs | b | **Goes**: the writer refuses it |
| 11 | `test-evidence-error`: task basis moved | b | **Goes**: head CAS |
| 12 | `merge-verdict`: "tested branch holds no commit" | b | **Goes** |
| 13 | `merge-verdict`: "run not recorded on the tested branch" | b | **Goes** |
| 14 | `merge-verdict`: "tested program changed after the run" | b | **Goes**: the reach digest encodes it |
| 15 | `declarations-held-tx`: "merged declarations changed after prepare" | b | **Goes**: head CAS |
| 16 | `prepare-merge!` scope refusals ("selected declaration is not a change", "reaches an unselected change", "no proofs given") | d/b | **Goes** |

**Six kinds survive** (#1/2, 3, 6, 7, 8, 9). Add one head-CAS refusal ("main moved; merge again"), which makes root
re-run the merge against M's new head, and the existing merge-base criss-cross refusal. The write-back file-proof
variants stay. Its `:row-changed` and `:file-changed` refusals are (b) race fences.

---

## 7. The smallest correct design

### Data flow

```
per eval request on B (the reactive step; agents run no tests):
  Δ, T     = change(B), members(B, Δ, proofs)            cached
  run      = seon.test/run {:identities (stale T)}       ONE request
  status   = judge(B, Δ, T, results)                     shown in the next context; done ≡ empty

root, on a done attempt (db<->flow):
  S        = fork M; transact replacement(Δ)             ONE write; conflicts → unmerged on S
  conflict → append the merge step (seon.step.merge/template) on S; the merge agent resolves. Stop.
  T'       = members(S, Δ, proofs)                       the same function
  run      = stale(T') on S                              = R_new: only the reach of main's moves
  judge(S, Δ, T', results) empty →
           ONE transaction on main: parents #{M S}, CAS on M, facts squash + S's program
  publish! → write-back (proof + lint once) → one git commit
```

### Namespaces and estimated lines

| Namespace / part | Holds | Estimate | Today |
|---|---|---|---|
| `seon.merge` | `merge-base`, `change`, `members` (+ coverage), `judge`, the merge branch, `squash-merge`, `merge!` (one transaction), `merge-main!`, `publish!` | ≈700 | 650 + 1,155 (`source.clj` 737–1891) + ≈150 (`cluster.clj` gate) |
| `seon.cluster.source` write-back | proofs, lint, `commit!`, ledger | ≈850 | 968 |
| `seon.turn` test step | one request over stale(T); `judge` for display | ≈120 | ≈400 |
| `seon.context` | the judged-input builder (shared with done) | ≈80 | ≈330 |
| `seon.cluster.agent` | `merge-chains!`, merge-step append | ≈80 | ≈250 |
| `my.test`, `seon.test.platform` | deleted (or root-only) | 0 | 191 + 238 |
| **Total on the path** | | **≈1,830** | **≈4,330** |

The ≈175 guard sites go further as the S-reads conversion lands.

### Decisions for root

The recommended option is first in each.

1. **Whole delta vs merge list.**
   - **Recommended:** whole delta, as CLAUDE.md and TASK-CHAIN §2.6 already say. Delete `merge-seeds`, `merge-list`,
     `carried`, `unselected-reach`, `:selection` and the "stays" display.
   - Sacrifice: unreached scratch changes merge unless the agent unmaps them. The coverage law (#7) refuses an
     uncovered changed function anyway.
2. **Red on the merged result.**
   - **Recommended:** a red member refuses; root sees a red that main also holds. Delete `red-at-merge-base`,
     `held-red`, `fork-point-red`, `run-merge-base` and `:seon.source/red-on-target`, and decline
     `same-evaluator-red.patch`.
   - Sacrifice: a red already on main blocks the chains that reach it until root fixes main.
3. **Isolated (file-reading) members.**
   - (i) **Recommended:** the merge counts them as unknown-allowed and root's batched post-merge `bin/seon check` runs
     them, filing a problem task on red.
   - (ii) The step defers them and root's platform batch answers them before merge.
   - (iii) Today: one JVM per merge, 30–60 s.
4. **Agent test verbs.** Delete `my.test` and the render and step offers listed in §4. Rewrite the clojure-testing
   skill section in the same commit.

### Phased deletion plan

HEAD boots after each phase. These proof tests stay green throughout:

- merge: `seon.cluster.merge-test/*`, `seon.merge-test/*`, `seon.cluster.write-back-test/*`;
- done: `seon.cluster.source-test/done-refusals-judges-every-task-alike`,
  `seon.cluster.source-test/done-and-merge-judge-one-member-list`,
  `seon.context-test/a-chains-step-is-done-only-when-the-done-rule-refuses-nothing`,
  `seon.context-test/the-test-step-runs-the-member-list-the-done-rule-judges`;
- db-flow chain:
  `seon.cluster.db-flow-test/a-prove-step-whose-product-main-holds-merges-and-its-successor-forks-main`,
  `seon.cluster.db-flow-test/a-refinement-on-a-branch-reaches-main-only-at-the-end`,
  `seon.cluster.db-flow-test/an-attempts-branch-holds-its-attempt-and-agent-under-mains-ids`.

| Phase | Change | Deletes | Phase proof |
|---|---|---|---|
| P0 | Decline `same-evaluator-red.patch` | — | — |
| P1 | One judge: `step-status` always calls `done-refusals`. The product clause becomes `next-proofs`. | `results-verdict`, #4, #5, #10, the `chained?` dispatch | `done-refusals-judges-every-task-alike` with a non-chained row |
| P2 | One judged-input builder | `done-judged` body | db-flow chain tests |
| P3 | Coverage from the one selection (seed → tests) | the `gate-sets` walk in `uncovered-functions` | `seon.cluster.source-test/an-uncovered-refusal-names-the-red-tests-reaching-it` |
| P4 | Merge = S + stale(T') + `judge` + one CAS transaction | `merge-verdict`, `tested-task-basis` ×3, `declarations-held-tx`, `held-digests` (except in `unmerged`), `merge-seeds`, `merge-list`, `carried`, `unselected-reach`, `:selection`, `merge-commit`, the second transaction, the second `merged`, `unmerged-count`'s path | `seon.cluster.merge-test/a-requested-green-end-merges-and-publishes-with-no-hand-act`; `seon.cluster.merge-test/a-red-reaching-test-refuses-acceptance`; a new row: main moved since S refuses by CAS, and the retry runs only R_new (asserted as a count of executed members) |
| P5 | Red is red | `red-at-merge-base`, `held-red`, `fork-point-red`, `run-merge-base`, `:seon.source/red-on-target` | `seon.cluster.source-test/done-and-merge-judge-a-red-reaching-test-the-same-way`, rewritten |
| P6 | One test request per step | `run-branch`, `run-proofs`, `branch-due`, `prove-turn`'s ordering, `step-reds`, `step-proved?` | `seon.turn.test-step-verdict-test/the-step-verdict-covers-every-test-of-the-member-list` |
| P7 | Proof and lint once, at write-back | `publication` in done | write-back lint rows; amend TASK-CHAIN §2.4 (ruling of 2026-10-15) |
| P8 | Isolated members (decision 3) | `isolated-run`, `seon.test.platform/check-files!` and helpers | `seon.merge-test/the-gate-runs-a-file-reading-test-against-the-candidate-and-refuses`, rewritten |
| P9 | Agents run no tests (decision 4) | `my.test`, the render and step offers, the skill text, `my.test.edn` tasks | an agent's context shows the step's results with no test verb offered |
| P10 | Reads throw (S-reads), done per owner | the ≈175 guard sites | each converted family's own tests |

**Ordering.** P1 and P3 both edit `source.clj`, so they run in sequence. P2, P6 and P9 are file-disjoint from them.
P4 needs P1–P3. P5, P7 and P8 need root's rulings. TASK-CHAIN §2.4 and §2.6 are amended in the commits that change
them, each with a red deftest, because the task-chain design is locked.

---

## 8. The top ten deletions

1. `merge-seeds`, `merge-list`, `carried`, `unselected-reach`, `:selection`: the partial merge, two reach derivations
   and selection gated on results.
2. `merge-verdict` and its double call: run-basis fences #12–#14.
3. `tested-task-basis`, `task-basis-refusal`, `task-basis-held-tx`: obligations re-derived up to four times, once
   inside the writer.
4. `declarations-held-tx`, `moved-digests`, `merge-commit` and the second transaction, replaced by one head CAS.
5. `results-verdict`, the second judge.
6. `red-at-merge-base`, `held-red`, `fork-point-red`, `run-merge-base`, `:seon.source/red-on-target`, and the
   unapplied patch.
7. `publication` inside done: a dry-run merge per judgment, 280 ms / 0.7 GB uncached.
8. `seon.cluster.agent/done-judged`, a copy of `step-observation`.
9. `seon.test.platform/check-files!` per merge (≈250 lines, 30–60 s per merge), moved to root's batch.
10. Agents' test verbs: `my.test` (191 lines), the `my.test/check` offer on Var renders, the `check-platform` offer,
    and the skill text. Then `run-branch`, `run-proofs`, `prove-turn`'s ordering and `step-reds`.

---

## 9. Verification limits

- **No test results are held** on default after the nuke, so per-test run time (`:seon.test.member/run-ms`) and the
  reuse ratio at a real merge could not be measured. Run costs come from 56038bbb1, 5793e03be, b54d25f68 and TASK-CHAIN
  §2.4.
- **The probe's change was synthetic.** It was a `d/with` with edited digest strings. `own-changed-identities` timed it
  at 8–16 ms, then `digest-map` refused the edited strings at its output contract, because they are not valid digests.
  The digest-map timings are over main's real values for the same identities. This is a limit of the probe, not a
  defect.
- **The live JVM runs behind HEAD.** It lacks `seon.test/reaching-selection`, added in 6f1d9b0cc, so the
  `merge-members` timings reflect the loaded commit.
- **R_new was not measured end to end.** A chain merged across main's moves is needed for that; P4's new proof row
  measures it.

### Out-of-scope finding

`seon.test.runner/reach-digests` costs 67–312 ms for R = 10–73 on default, which is 10–60× the expected O(R) and is
paid by every request's stale check. Its durable record is this report; root should file it as a task on
`seon.test.runner/reach-digests` for an Astra performance lane.

### Stale skill

`.agents/skills/clojure-testing/SKILL.md` tells agents to run tests (`(my.test/run)`, `(my.test/check …)`), which
contradicts the owner's 2026-10-17 ruling. Rewrite it with P9.

---

## 10. Evidence forms

All forms ran in JVM mode on default (basis t 536871042). Health was `:faulted` with 5 open faults that predate this
audit.

**Reach derivations for one function** (result `ebf51a7e12ef5`, 149 ms):

```clojure
(seon.fn/gate-sets {:seon.db/db db :seon.fn/seeds #{'seon.merge/merge-tx}})        ; 3 tests, 29 ms
(seon.fn/reverse-closure {:seon.db/db db :seon.fn/seeds #{'seon.merge/merge-tx}})   ; 100 tests, 25 ms
(seon.test/reaching {:seon.db/db db :seon.test/changed [[:seon.fn/sym 'seon.merge/merge-tx]]}) ; 3, 9 ms
(seon.cluster.source/merge-members db #{[:seon.fn/sym 'seon.merge/merge-tx]} #{})   ; 3, 73 ms cold
```

**Component timings at C = 5, 20 and 50:**

| Result | What it measured |
|---|---|
| `e37b010465b15` | `merge-members` cold/warm, `reach-digests`, `current-results`, `merge-seeds` (0 seeds) and `merge-list` (`will-merge` 0, `stays` = C) |
| `e36117200fddd` | `own-changed-identities`, `digest-map` ×3, `three-way`, `reverse-closure` (368 / 874 / 927), `contract-gaps` |
| `e257691707e2f` | `published-index-entities` + `reconcile-tx` (15 / 23 / 36 ms), `unmerged` (0.08 ms) |

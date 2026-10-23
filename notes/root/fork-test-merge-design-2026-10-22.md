# Fork, test, merge: how next uses cheap forking, and what it needs (2026-10-22)

Research and design, read-only (one scratch worktree, `research-fork`, made, probed and removed; nothing left running). The owner asked:

> "How well are these designs at taking care of the benefits of this new jolt architecture and cheap forking? Do we have a universal way to test any code that agents modify easily and quickly? What about creating a merge branch and testing all the code in there before we actually merge to main? I want to make sure our worktree logic is sound and that agents can solve problems independently."

Short answers:
- **Forking:** the design uses cheap forking well for agents, clones, test children and loading merges in place. It ignores it for every test run that is not an agent's. `bin/test` runs serially in one cold `jolt` process. Each `bin/check` step is a cold binary that boots a root. Seon's agents run the same tests again every turn.
- **A universal way to test a change:** no. There are two test paths, and they select differently: an agent's `test-vars` and `bin/test --reach`. Only `bin/test` reuses results. Both selections miss changed macros and vars that are named by quoted symbols. The agent path also misses changed methods, fixtures and schemas. On the standing root, the agent path selects nothing from main's suite, because main holds no imported program.
- **A merge branch tested before main:** Seon's own single merge is already proven on main plus the change, because the writer refuses a branch that is behind main. Its merge *train* is not proven together, and that is why trains are off. Claude worktree sessions have no tested integration branch: root proves batches by hand.
- **Worktrees:** the isolation model is sound. The gaps are a few small edges, three stale comments, one footgun, and caches with no bound.

The design below is three small pieces built on what exists: one prove verb, one merge-branch gate, and worktree fixes. All three key results by the same reach digest.

---

## 0. Measured facts (this report's own numbers plus the ones given)

| What | Number | Where from |
|---|---|---|
| Fork an agent from the fork server | 0.8 ms | given |
| An agent's own memory / system cost | ~5 MB / 20–30 MB | given |
| 100 agents ready | ~0.6 s | given |
| Clone an agent (live values, own branch) | 5.4 ms | `target/check/land12/smoke.log` |
| Agent forked test run, small proof set | 5.6–7.1 ms | same smoke.log |
| `target/seon` process start (no root) | 0.78–0.81 s | measured: `bin/ports` (`./seon ports`), 3 runs |
| Root boot inside a check step | 352 ms (5,333 datoms) | `land12/smoke.log` "booted in" |
| Full `bin/check 100` | ~620 s | `target/check/20261021T170256`, per-step log mtimes |
| — of which `swarm 100` | 269 s (43%) | same; `:wall-ms 268212` |
| — `census` (4 shards) | ~80 s | same |
| — `e2e-swarm` | 46 s | same |
| — boot overhead (~40 steps × ~1.2 s) | ~50 s (~8%) | derived from the two rows above |
| `bin/test --reach` program import (src + test) | 9.85 s | measured, scratch worktree, jolt runtime |
| `reach-digests` over the whole repo | 0.69 s (318 tests, 2,282 live forms; `live-forms` alone 0.40 s) | measured, same |
| `reaching-tests` of `seon.config/value` | 273 tests in 0.49 s | measured, same |
| `test-runner/inputs` (git ls-files + digests) | 81 ms | measured, same |
| A fresh worktree (`bin/worktree NAME`) | 4.4 s | measured |
| Shared test results | 9,014 files, 35 MB, never pruned | `ls ../test-results` |
| Standing root's main: imported program | **0** `:ns/file`, **0** tests; dirs `["src" "resources/seon/schemas"]` | read through the door (`seon_eval root`) |

Not measured:
- what one merge costs in the writer during `swarm` (the cause of the 269 s is inferred below, not profiled);
- a forked, parallel `bin/test`;
- a whole-repo test run inside a fork of a warm process.

Each design below names the number that proves it.

---

## 1. Where the design uses fork + copy-on-write + loading in place, and where it ignores them

### Uses them well
- **Agents are forks of one warm, single-threaded fork server** that holds main's db and compiled program. The server's `:tx` op applies each main commit to its db (`d/with`) and runs `program/load-changes!` on it (`src/seon/fork_server.clj:43-53`), so every agent forked after a merge inherits the change compiled. The `:fork` op (`fork_server.clj:54-64`) refuses when more than one thread is active. That rule is physics, written down.
- **Test children are forks of the agent** (`seon.agent/run-tests-forked`, `src/seon/agent.clj:373-406`). A child takes the branch's live state copy-on-write. Writes inside a test go to the fork's db only (`agent.clj:111-114`), and no effect leaves the child (`*fx-stub*`, `agent.clj:128-162`). The run is bounded by engine ticks, not a clock, and a death names the test that was running (`run-answer`, `agent.clj:358-371`). This is the right primitive.
- **`clone!`** (`agent.clj:498-539`) double-forks an agent, live values included, onto a branch of its own branch: 5.4 ms.
- **A merge loads in place, with no build** (`docs/source.md#a-merge-loads-in-place`). Root's writer (`src/seon/writer.clj:18`), the fork server and an agent that merges main (`agent.clj:213`) all call `load-changes!`. In the loop check this cut the turn from 84–96 s to 13.4–13.9 s.
- **A standby fork server** (`fork_server.clj:72-82`, `promote` at `:166-185`), so root never forks after boot.

### Ignores them
1. **`bin/test` is serial, in one cold process.**
   - `bin/test` execs `target/jolt/jolt -A:test run -m test-runner` (`bin/test:12`). That is the jolt runtime, not the built binary, so every run pays 9.85 s of import before it selects anything (`test-runner/program-db`, `test/test_runner.clj:51-57`).
   - `run-vars!` (`test_runner.clj:72-85`) then runs the selected tests one after another in that one process, with no isolation: a test that leaks state or hangs takes the rest with it.
   - The primitive to fix this exists (fork a warm holder, run a shard, read the pipe to EOF), but `bin/test` doesn't use it.
2. **Agents run the same tests every turn, and once more at judgement.**
   - `seon.turn/observe` calls `run-tests-forked` at the start of every turn (`src/seon/turn.clj:72`).
   - The reconciler then asks the agent for `:tests` again (`src/seon/reconcile.clj:620`).
   - A gated `seon.root/merge!` runs them a third time (`src/seon/root.clj:787-802`, `src/seon/agent_proc.clj:171-180`).
   - None of these runs reuses a result. For a wide reach this matters: `seon.config/value` reaches 273 tests (task `seon.agent/a-wide-reach-stays-cheap`).
3. **Every `bin/check` step is a cold `./seon` process that boots a root** (`bin/check` `step()` and `run()`; 30+ calls to `c/start!` in `src/seon/check.clj`).
   - Measured, that boot overhead is ~50 s of ~620 s (~8%). So forking a warm root for checks is a real but modest win.
   - The large costs are elsewhere:
     - the steps run serially, though each already works on its own branch `check-<step>` of one store (`bin/check:12-19`), so they could run side by side;
     - a step's result is never reused by digest, against AGENTS §3: "a check step's [result] by its build's source";
     - `swarm` takes 269 s by itself (next item).
4. **Merge validation runs in the single-threaded writer, at whole-program cost.**
   - `branch-merge` builds three whole-program maps per member (`writer.clj:22-30`). The writer is the one proc that does all of main's writes, so a slow merge stalls every write.
   - `swarm 100` is 100 agents on 5 modules. Each loser loops `merge-main!` then `merge!` up to 20 times while it is behind (`check.clj:524-528`), so the queue is serialized optimistic retries through the writer.
   - The 269 s is most likely that loop times the ~440 ms-per-map cost the audit measured (`AUDIT §2.5`; task `seon.merge/program-cost-follows-what-changed`). Not profiled here: profile one `swarm 20` before acting.
   - This is the merge path's cost, not a forking cost. Its fix is M1's one `:aevt` scan per attribute, plus a changed-since that diffs index trees by node address.
5. **A merge train is never tested as a whole.**
   - `merge-train` (`writer.clj:76-109`) lands key-disjoint members in one commit, but no run ever sees main + A + B together.
   - Two members can be key-disjoint and still meet: B's tests call a function A changed.
   - That is why trains stay off (`resources/seon/config/default.edn:175-181`: "until one combined test run of all train members"). A combined run costs nearly nothing with forks (§3).

---

## 2. A universal, fast way to test any change

### What exists
- **Reach digests** (`seon.program/reach-digests`, `src/seon/program.clj:733-759`). A test's digest covers:
  - its own form;
  - its namespace's forms that define no var (the ns form, fixtures);
  - every live form its `:form/refs` reach, plus the methods and extensions of the multimethods and protocols it reaches;
  - the checkout's `:env` inputs;
  - `:tree` when a reached form calls a tree reader (`slurp`, `file-seq`).
- **Selection by reach** (`seon.program/reaching-tests`, `program.clj:709-726`) over `changed-vars` (`program.clj:700-707`).
- **Results by digest in one directory every checkout shares** (`test_runner.clj:63-70` `plan`, `:72-85` `run-vars!`; `../test-results`). Only green results are reused.
- **Forked, tick-bounded runs** for agents (`agent.clj:373`).

### The gaps (probe: `next-research-fork/test/research_probe.clj`, removed with the worktree)
The probe imported tiny namespaces onto the test store, changed one thing, then compared:
- **digest:** whether `reach-digests` moved, which is what `bin/test --reach` uses;
- **selection:** whether `reaching-tests ∘ changed-vars` selected the test, which is what `seon.agent/test-vars` uses.

| Change | digest moved? | `changed-vars` | agent selects the test? |
|---|---|---|---|
| a macro's body (`(defmacro m [x] \`(inc ~x))` → `(+ 2 ~x)`; the test calls `(a/m 1)`) | **no** | `#{rp.a/m}` | **no** |
| a fn reached only through a quoted symbol (`(requiring-resolve 'rp.b/helper)`) | **no** | `#{rp.b/helper}` | **no** |
| a `defmethod` body | yes | `#{}` | **no** |
| a `use-fixtures` form | yes | `#{}` | **no** |

What the table means:
1. **Macros.**
   - An expansion leaves no reference: `seon.program/dependents` says so (`program.clj:507-533`), and handles it for *loading* by reloading the macro's namespace and the namespaces that require it.
   - Reach handles it nowhere. A changed macro reuses stale green results in `bin/test --reach`, and an agent never runs the tests that use the macro.
   - This is a correctness bug in the shipped reuse, not only a selection gap.
2. **Quoted and syntax-quoted symbols, and vars resolved by name.**
   - `unit-account` records `:var`, `:the-var` and `:host-static` nodes (`program.clj:145-192`), never a `:quote` constant.
   - So `requiring-resolve 'x/f`, `ns-resolve`, `(list 'seon.turn/work! …)` (`reconcile.clj:612`) and the names in `resources/seon/system.edn` are invisible to reach.
   - `bin/test` covers system.edn only coarsely, through `:env` (all of `resources/`). Agents don't cover it at all.
3. **Agent selection is weaker than the digest.**
   - `changed-vars` sees only `:var/form`, so a changed method, protocol extension, fixture or ns form selects nothing.
   - A changed **schema** entry (`my.schema/register!`) selects nothing either: neither `reaching-tests` nor `reach-digests` follows a contract to the schema keys it names. `bin/test` covers schemas through `:env`.
   - `schema-dependents` (`program.clj:540-556`) already computes exactly that edge for loading.
4. **The two paths select differently.** `bin/test` uses digest movement. The agent uses `reaching-tests`, which is the inverse closure, maintained separately and already divergent (items 1–3).
5. **The standing root holds no program.**
   - Main on 7888 has 0 imported namespaces and 0 tests (read through the door). The shipped dirs are `["src" "resources/seon/schemas"]` (`default.edn:202`), and test/ is not among them.
   - So a Seon agent on the standing root that changes Seon's own code reaches **no** test of main's suite. Its run is its proofs plus the tests it wrote itself.
   - "Agents test what their change reaches" holds only on roots started with `--set :seon.config.source/dirs [... "test"]`.
6. **Agents don't reuse results** (item 2 of §1). They also have no REPL verb to run their own tests: `my.*` has none (`src/my/`). An agent can call `clojure.test` in its own process, with no fork and no reuse.
7. **Results are counts only** (`{:test :digest :pass :fail :error :ms}`), and only green is reused. That is fine, because a red result must run again for its reports. The directory has no bound (9,014 files).

### The smallest design: one verb, `prove`, one selection, one store of results

**Primitive.** A candidate is a program db value, and so is its base. Seon agents already have both: the branch and the main it forked from. A git worktree session gets both by import (`seon.source/import-tx`, the facts root and agents record). Everything else is the same code.

```
(seon.proofs/prove base cand {:proofs #{...} :env e :ticks n :jobs k})
  => {:selected [...] :reused {test result} :ran {test result} :digests {test digest}}
```

1. **Select by digest difference, not by `reaching-tests`.** The selection is the tests whose `reach-digests` on `cand` differ from base (or are new), plus `:proofs`, plus the tests with no green result at their digest.
   - This is one function: the digest *is* the reach. `reaching-tests ∘ changed-vars` goes from `test-vars` (`agent.clj:299-309`).
   - It fixes table rows 3 and 4 for agents at once, because digests already include methods, impls and plain forms.
   - It is also how a done judgement can stay cheap: done-refusals read results at the current digests.
2. **Close the reach gaps in `reach-digests`, the one place.**
   - (a) *Macros:* an edge from each form to the macro forms of the namespaces its ns requires. This is `dependents`' own model, so the closure stays conservative.
     - Better, own the stack: Jolt's analyzer records the macros a form expanded (`:form/macros`). That is a `jolt/<topic>` branch and waits for root's go.
   - (b) *Quoted symbols:* `unit-account` adds to `:refs` every qualified symbol in a `:quote` constant that resolves to a var at analysis. That is conservative: a symbol used as data costs one extra edge, never a miss.
   - (c) *Schemas:* an edge from a contracted var's form to the `:schema/key` entities its contract names, and on through `schema-dependents`' `names` closure. With this, agents' runs and `bin/test` agree without the coarse `:env` for schemas.
   - (d) Keep `tree-readers` and `:env` for what has no program facts.
3. **Reuse and store in `../test-results`, through the effect owner.**
   - An agent's run asks root's `:file` effect to read the green digests it needs, and sends its results back to be written (`seon.fx/write-file`, temp then rename). The asker records the outcome on its branch.
   - The turn loop's `observe`, the judge's `:tested` and the gated merge then rerun only what moved since the last green.
   - `:env` for an agent is the binary's source commit's `test-runner/inputs`, computed once by root at boot and recorded as a fact. With no source root, it is the binary's build id: results are then shared only within one generation, which is honest.
4. **Run in forks, `k` at a time.**
   - The holder (the agent, or for a worktree session the `seon` binary with test/ loaded) forks one child per test shard, a namespace with its fixtures, and reads each pipe to EOF: `run-tests-forked`'s child, generalized to a shard.
   - The holder takes no clock and no thread: it forks `k` children and reaps them, exactly as the fork server reaps agents. `k` is `:seon.config.proofs/jobs`, documented config with its unit and its bound.
5. **Expose it as one verb.**
   - Agents: `(my.agent/prove)`, whose default base is the main the agent forked from.
   - Worktree sessions: `bin/test --reach` becomes `seon prove [NS...]` on the built binary (no 9.85 s jolt import), with `base` being main's tree.
   - Root: the reconciler's `:tests` request calls the same function.
6. **Import incrementally, by file digest** (the step `docs/source.md#tests-run-by-reach` already names). A worktree session's run imports only the files whose digest differs from main's last import. On the merged program db, that turns 9.85 s into the changed files' analysis time.

- **Reuses:** `reach-digests`, `test-runner/plan` and `run-vars!`'s storage, `run-tests-forked`'s child and `run-answer`, `seon.fx/write-file`, `import-tx`, `schema-dependents`' `names`.
- **Replaces:**
  - `seon.agent/test-vars`' selection;
  - `seon.program/reaching-tests` as a selector (it stays as `my.agent/callers`' helper if one is wanted);
  - `bin/test --reach`'s serial in-process loop;
  - the jolt-runtime import on every run.
- **Deletes:** `test-runner/run-vars!` and `plan`, which move into `seon.proofs` as `prove`, and their duplicate `green?`.
- **Size:** about +120 / −60 lines in `src` (seon.proofs +80, program +30 for the macro, quote and schema edges, agent −25, test_runner −50, fx 0). Plus one task to put test/ among the standing root's dirs; that is an owner decision, because it changes what the standing root imports (§2 gap 5).
- **Proofs:**
  - `seon.program-test/a-changed-macro-moves-the-digest-of-each-test-that-expands-it` (the probe's row 1, red today);
  - `…/a-var-named-by-a-quoted-symbol-is-reached` (row 2);
  - `…/a-changed-contract-schema-moves-the-digest-of-tests-reaching-the-contracted-var`;
  - `seon.agent-test/an-agents-run-selects-a-changed-method-and-fixture` (rows 3–4);
  - `seon.agent-test/a-wide-reach-reuses-unchanged-results`, which already exists as a named proof with no deftest (task `a-wide-reach-stays-cheap`);
  - `seon.proofs-test/a-second-prove-of-an-unchanged-branch-runs-nothing`;
  - a cost proof: `prove` with nothing changed is linear in live forms at about 0.3 ms per form, with the 0.69 s above as the start (estimate: under 1 s for 2,282 forms in the binary, well under it incrementally).
- **Numbers to report:**
  - turns × tests run per attempt, before and after (today: proofs plus reach, run turns + 1 times);
  - `bin/test --reach` with no change (today ≥ 10 s; target under 1.5 s);
  - a whole-suite cold run serial against `k` = 8 forks (not measured today).

---

## 3. A merge branch tested before main

### What exists
- **Seon, one merge at a time** (`:seon.config.merge/max-running 1`):
  - a merge task's agent merges main into its own branch (`seon.agent/merge-main!`, `agent.clj:195-222`), so its branch is main's head plus the change;
  - it runs its tests in a fork of itself;
  - the writer refuses a branch that is behind (`merge-refusal`, `writer.clj:38-74`).
  - So the program that lands is the program that was tested, apart from §2's selection gaps. In effect this is already a merge branch from main's head: a correct optimistic queue.
  - Its weaknesses:
    - if main moves between test and merge, the branch is behind and loops (swarm, `check.clj:524-528`);
    - the result is proven only as far as the selection reaches;
    - every member's run is serial behind the one writer.
- **Seon, trains** (`reconcile.clj:352-360`, `writer.clj:76-109`) land disjoint green members in one octopus commit, with no combined run. They are off for that reason (DECISIONS; `default.edn:175-181`).
- **Claude worktree sessions** (AGENTS §4, `docs/merging.md#ready-branches-merge-in-batches`):
  - root merges ready, file-disjoint branches onto an integration branch by hand, runs one `bin/check 100` and one `bin/test`, then lands each;
  - nothing makes the integration branch, gates main on it, or reuses its results, beyond `bin/test --reach`'s digests.

### The smallest design: the speculative merge branch, one mechanism for both kinds

**Rule:** main moves only by fast-forward to a merge branch that was built from main's current head and proven.

1. **Cut.** The writer makes `merge/<n>` from main's head with `d/branch!` (sub-ms) and applies the batch's candidates. Each candidate is a branch whose three-way against main has no conflict, and the change is `pm/apply-tx` of their `:ours`, exactly what `merge-train` computes today. The members may share keys only when all agree on them; otherwise a member goes to the next batch.
2. **Prove.**
   - The fork server forks a **prover**: an agent with no model, on `merge/<n>`. It is born holding main's compiled program, so it only `load-diff!`s the batch, the merged program loading in place.
   - It runs `prove` (§2) with base = main and cand = `merge/<n>`, forked and reusing every green digest.
   - The selection is exactly the tests that the *combined* change moves, which is the run DECISIONS asks for before trains.
3. **Land.** If the run is green and main's head is still the commit `merge/<n>` was cut from, the writer lands it: `d/merge-db conn #{merge/<n>}` with empty tx-data, every member a parent, so their merge bases move as today. Each member's attempt is written as `:merged`.
   - If main moved, the writer refuses. The batch is re-cut on the new head, and its green results are reused, so only what main's move reached runs again.
   - If the run is red, bisect the batch (bors and Zuul): split it in halves, each its own merge branch, proven concurrently because forks are cheap. A single red member is refused, with its red tests as data on its attempt (`:merge-red`), and the queue moves on.
4. **Pipeline (Zuul's speculative queue), later.** Given candidates c1 c2 c3, cut m1 = main + c1, m2 = m1 + c2 and m3 = m2 + c3, and prove all three at once. Land the longest green prefix. Each prover costs 0.8 ms and ~20–30 MB, so the depth is bounded by the workload (`:max-merging`), not by cost.

**One mechanism for git worktree sessions.** A Claude worktree session's branch is a candidate of a second kind.
- (a) Root computes the merged tree without a checkout: `git merge-tree --write-tree main jolt/X`, git's own primitive. A conflict there sends the branch back with git's conflict list as data.
- (b) If the merged tree differs from main only in `.clj` files under the source dirs and in schema resources, root imports those files incrementally onto a branch of main's program, and it becomes `merge/<n>` above. It is proven by the same prover, with no build and no worktree.
  - This needs root to hold the program, which is §2 gap 5 again: the standing root must import src and test.
- (c) If it touches something that cannot load in place (a pin, `deps.edn`, native code, `bin/`, Jolt):
  - `bin/worktree --at <dir> <merged-commit>`, which is the generation path that already exists (`seon.fx/build`);
  - `nice -n 15 bin/build seon` there (24 s from the shared cache, `docs/source.md#shared-build-caches`);
  - `seon prove` there, and the `bin/check` steps whose code changed plus `smoke`.
- (d) Land: the merge commit is created (`git commit-tree`, the plumbing `seon.fx/git-commit` already uses) and main moves with `git update-ref refs/heads/main <merge> <old-main>`, compare-and-swap. A moved main refuses and re-cuts.
  - This is a `:git` effect request, so root's merge *is* the reconciler's merge task. That is AGENTS §4 "Where this is going", not a new runner.
- Until it is a task, root's manual batch should follow (a)–(d) by hand. `docs/merging.md` should state (a) and the compare-and-swap in (d) as the rule.

- **Reuses:**
  - `merge-train`'s judging and `apply-tx`;
  - `d/branch!` and `d/merge-db`;
  - the fork server's `:fork` (a prover is an agent with no model, `:model "none"`);
  - `load-diff!`, `prove`;
  - `seon.fx/git-commit`'s plumbing;
  - `bin/worktree --at`, `seon.fx/build`;
  - `merge-refusal`'s typed errors.
- **Replaces:**
  - the agent-side gate in `seon.root/merge!` (`:merge-tests`), since proving moves to the merge branch;
  - the `behind` retry loop, since re-cutting is the writer's job;
  - `max-merging 1` as the safety rule, since a batch is safe because it was proven together;
  - root's hand-made integration branch.
- **Deletes:** the `:merge-tests` round trip in `agent_proc.clj:171-180`, and the "train closes when its first member ends" rule (`reconcile.clj:352-360`), since a batch is cut, not gathered.
- **Size:** writer +40 (cut, land with compare-and-swap), reconcile +30 / −25 (merge-branch flight in place of the train flight), agent_proc −15, fx +15 (`:git` merge-tree and update-ref). Roughly +70 net in `src`, and it lets trains turn on.
- **Order inside it:**
  1. Seon branches first, since every piece exists.
  2. Git step (b) once the standing root imports its source.
  3. Step (c) last.
- **Proofs:**
  - `seon.writer-test/a-merge-branch-lands-only-when-main-has-not-moved` (compare-and-swap);
  - `seon.reconcile-test/two-key-disjoint-members-whose-tests-meet-are-proven-together`, where B's test calls A's changed fn: red when proven together, green today in a train. That is the case trains miss.
  - `…/a-red-batch-bisects-to-its-red-member`;
  - `seon.check/e2e scenario semantic` extended with a train;
  - a cost proof: cutting and merge are independent of program size (proportional to the carried keys, DESIGN-tasks §3.7: ~10 ms for ten items once M1 lands).
- **Numbers to report:**
  - `swarm 100` wall time, before (269 s) and after;
  - merges per minute with `max-merging` 4;
  - a batch proof's run time against the sum of its members' runs.

**What it needs first.**
- The M1 program-map rewrite (`seon.merge/program-cost-follows-what-changed`). Cutting a merge branch must not cost three 440 ms maps in the writer.
- `:seon.merge/kept` (DESIGN-tasks §3.3). A merge branch built from main must read an unresolved conflict as open. Without it, step 1 must refuse every conflicting member, which is the safe default.

---

## 4. Worktree logic: sound, with these gaps

### Sound
- Each checkout is a separate instance of the whole system:
  - its own `target/` (an APFS clone, `bin/worktree` `setup`);
  - submodules as worktrees of the main tree's repos at the branch's pins (objects shared, no network);
  - its own ports from one locked registry (`bin/ports`, `../worktree-ports`);
  - its own store under `runs/`;
  - its own root, double-forked (`bin/root launch`), reached by the committed `.mcp.json` through `./bin/mcp`.
- The compile caches are shared and content-keyed with temp-then-rename (`docs/source.md#shared-build-caches`), so concurrent builds are safe. A fresh worktree takes 4.4 s; its build hits the cache.
- `bin/worktree-check` proves the workflow end to end and ends on `WORKTREE-CHECK OK/FAILED`.
- Many independent worktree sessions at once hold: there is no shared mutable state but the caches and the results directory, and both are content-addressed.

### Gaps, each one a task
1. **A submodule a worktree session adds breaks every worktree made from that worktree session's branch** until root initializes it in the main tree. `setup` runs `git -C "$MAIN/$sub" worktree add` for each gitlink (`bin/worktree:35-37`, under `set -e`). If the main tree has no repo there, the script dies after `git worktree add`, leaving a worktree with no `target/` and no ports.
   - Fix: when `$MAIN/$sub` is missing, `setup` skips that gitlink and says so (`print -u2 "worktree: $sub not initialized in the main tree; root initializes it at merge"`).
   - A worktree session that needs another worktree session's new submodule waits on that merge through `:needs`.
2. **A worktree session that needs another worktree session's unmerged work.** `bin/worktree NAME jolt/OTHER` already supports this, but nothing records it.
   - Fix: record it as `:seon.task/needs` and land in that order. The dependent worktree session merges main, not OTHER, after OTHER lands. A merge-branch batch (§3) carries both together when both are ready.
   - Document it in `docs/merging.md#a-session-works-in-a-worktree-of-its-own`.
3. **Stale comments in `bin/worktree`'s header**, which contradict AGENTS:
   - `:19`: "taken with `git -C ../next-NAME submodule update`". AGENTS says never run `git submodule` in a worktree; use `--sync`.
   - `:24`: "runs (stores) default to its own target/runs". `bin/root` uses `$ROOT/runs`.
   - `:26`: "cherry-picked (or merged) … by explicit paths". AGENTS says root lands by merging, never by copying.
   - An agent reading the script follows the wrong rule. Fix the three comments.
4. **Footgun: a single `bin/check MODE` defaults to `runs/store`**, the worktree root's store, which lacks the checks' seed, so steps fail falsely (`docs/merging.md`). Every worktree has to remember `SEON_STORE=$PWD/target/check-store`.
   - Fix: `bin/check` defaults `SEON_STORE` to `target/check-store` when it is unset. That is one line, and it removes the warning from the docs and `worktree-check`.
5. **Unbounded shared state:**
   - `../test-results` (9,014 files) has no pruning rule, against AGENTS §7 "Disk stays bounded". Fix: root prunes, at each merge, the digests no test of main's last N merges holds; the digests are already computed then.
   - `../worktree-ports` hands out `max + 1` and never checks that a port is free (block 50 = 8400 today; 9 worktree sessions are registered).
6. **Disk.** Each worktree shows ~2 GB apparent (`du`, mostly shared APFS clone blocks). The main tree is 13 GB, 7.6 GB of it `target/cache`. Seven earlier worktrees are still present, and `~/src/seon-jolt` holds ~100 legacy top-level directories and `wt-*.log` files, against §7's "no new top-level directory".
   - Reclaiming them is the owner's act. List them for the owner rather than deleting.
7. **Agents solving problems independently.** Inside a root, a Seon agent has a real REPL, `merge-main!`, a forked test run, `clone!`, and branches that cost one pointer. What it lacks, and these are this report's §2 and §3:
   - a test verb of its own (`my.agent/prove`);
   - reused results;
   - a reach that sees macros, quoted names, methods, fixtures and schemas;
   - on the standing root, any program to reach at all (gap 5 of §2).
   - Claude worktree sessions are independent in practice, but their tests start at ≥10 s and run serially, and their integration is root's hand work.

---

## 5. Order to build

Each item is a task in its owning namespace's file. Plans cite the vars above, proofs are written first and watched red, and each merge rewrites `docs/source.md#tests-run-by-reach` or `docs/merging.md`.

1. **`seon.program/reach-sees-macros-quotes-and-schemas`** (§2 design step 2). It fixes a correctness bug in the shipped `bin/test --reach` (stale greens after a macro change). About +30 lines; the probe's rows 1–2 are its red proofs. Lands alone.
2. **`seon.proofs/prove`**, one selection by digest difference, results through the effect owner, `k` forks (§2 steps 1, 3–5). Replaces `test-vars`' selection and `run-vars!`. Proofs: rows 3–4, `a-wide-reach-reuses-unchanged-results`, `a-second-prove-…-runs-nothing`. Then wire it into `seon.turn/observe`, `:tested` and `my.agent/prove`.
3. **Owner decision, then a task: the standing root imports `src` and `test`** (`:seon.config.source/dirs`). Without it, items 2 and 6 do nothing for Seon's own code on 7888.
4. **`bin/worktree` and `bin/check` edges** (§4 gaps 1, 3, 4; the prune in gap 5). About 15 lines of shell; rerun `bin/worktree-check`.
5. **`seon.merge/program-cost-follows-what-changed`** (M1's scan, already filed). This comes before any merge-branch work. Profile `swarm 20` first to confirm the 269 s cause.
6. **`seon.writer/merge-branches-are-proven-before-main`** (§3, Seon candidates): cut, prover, land with compare-and-swap, bisect on red. Then raise `:seon.config.merge/max-running`, which needs the owner's go (DECISIONS).
7. **`seon.source/import-by-file`**, then **git candidates in the merge branch** (§3 (a), (b), (d)), then the build path (c). Root's manual batch follows (a)–(d) by hand until then.
8. **Checks:** run independent steps in parallel on their own branches and reuse a step's result by its build's source digest. A warm fork for check processes is last, since it is worth ~50 s of ~620 s.

Not proposed:
- a new runner, queue service or test server. The prover is an agent without a model, the queue is the reconciler's merge flight, the store of results is the existing directory, and the verb is one function.
- a stamp (`:seon.tx/changed`). Digest difference over the two dbs needs no stamp; changed-since by node address stays M1's question.

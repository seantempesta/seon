---
title: "Merge path: from an agent's branch to persisted, running code"
type: research
status: active
created: 2026-10-08
lane: merge-path-opus
---

# Merge path: from an agent's branch to persisted, running code

What exists: an agent's change is program rows on its Datahike branch; root compares it with the target by declaration
identity and digest (`seon.cluster.source/compare-branch` over `seon.program/three-way`), tests it on a merge branch
(`seon.cluster.source/prepare-merge!`), commits the tested rows onto the target with a Datahike merge commit
(`seon.cluster.source/accept-merge!`), and can splice the merged declarations into their files by exact byte span,
proven by reindexing (`seon.cluster.source/write-back!`). Nothing after that exists: no git commit, no update of the
target's file facts, and no way for the running JVM to load the result except a restart. Principal approach of this
note: **the target branch's database is the one source of truth; files are its write-back, made as a git commit with
git's plumbing off the commit the database was indexed from, and the merge transaction records the file facts the
write-back proved**, so files and database agree at every commit; the running JVM loads changed namespaces from that
commit's bytes with `Compiler/load` (clj-reload's seam), and restarts only for the kinds that cannot reload.

Measured 2026-10-08 17:50-18:30 JST on default (loaded commit `e3b7bae3a`, HEAD `e78ab4ac8`, 7 commits ahead), MCP
`eval_clj`: agent `mp-src` (SCI, own branch) made the change; agent `mp-root` (JVM mode) played root against a
disposable target branch `:mp-target-…` forked from default's tip (deleted afterwards, with both agents' branches), with a scratch checkout made by
`git archive e3b7bae3a` as the write-back root. Default's program and the shared working tree were not changed.

## 0. Ranked: what blocks a smooth merge most

| # | Blocker | Evidence (measured) | Fix (row) |
|---|---|---|---|
| 1 | **Files and database disagree after every merge.** The merged rows carry no file or span, and the target's file row keeps the old digest | §1 step 7-8: after the merge, `seon.task/step-summary` on the target holds only `:seon.program/definition-digest` (no `:seon.fn/file`, no `:seon.fn/form-span`); `src/seon/task.clj`'s `:seon.fn.file/digest` stays `20d261e3…` while the written file is `367ee7d9…`. The **second merge of the same function cannot be written back**: `prepare-merge!` with the root refuses `:one-file` in 47 ms (`proven-file`'s `file-of` answers nil for a before-row with no file instead of falling back to the namespace's file, source.clj:1122-1124) | MP-1: the merge transaction records the file facts the write-back's reindex already computed |
| 2 | **The write-back's base is the loaded commit, but it writes into the shared working tree** | the target's file facts describe `e3b7bae3a`; the shared tree is 7 commits ahead with 9 src/test/resources files changed and 24 more uncommitted (427 commits in 24 h; `src/seon/sci/eval.clj` 21 of them). Any merge touching one of those files refuses `:file-changed` (source.clj:1159, 1242) | MP-2: build the merge's git commit with plumbing off the loaded commit (no working tree); MP-3: integrate it into the git branch with `git merge-tree` |
| 3 | **Nothing commits to git and nothing tells the JVM** | `write-back!` installs files and stops ("Publishing the changed path stays with the publication owner", source.clj docstring); no function creates a git commit; `merge!` (MA-11) has no write-back step | MP-2, MP-4 |
| 4 | **Reload in place is a no-op in default today** | default now runs with program classes: `seon/task__init.class` resolves under `target/dev-program-classes/3b9f8dc7…/`, mtime `2100-01-01`; `require :reload` (`load-development-definitions!` cluster.clj:1924) loads the class and replaces nothing (PB-03a measured `root-replaced?` false; reproduced here in a plain Clojure 1.12.5 JVM with `foo.core` AOT classes dated 2100: after `require :reload` `(f)` still answered the old 1, after `Compiler/load` of the new source it answered 2 in 15 ms with the same Var, §3). This **corrects hostbound-opus §4 step 11**, which found no program classes in default before the 08:46Z restart. The classpath is also the old archive `data/source/e3b7bae3a…` | MP-4 (RED-5 becomes this row's precondition, not a separate fix) |
| 5 | Root's tools are not yet one `merge!` and need JVM mode | to play root I needed `seon.operator.runtime/running-instances`, `cluster.agent/acquire-context!` and `db/call-with-custody`; without the custody wrapper `accept-merge!` spent **2,678 ms** then refused ("This write names branch … not the writing cluster's branch") | MA-0, MA-4, MA-11, MA-14a (existing) |
| 6 | The merge branch re-runs every test, reusing none | `prepare-merge!`'s run: `:seon.test/reused-count 0`, 2,570 of 3,204 ms in test bodies the source branch had just run green on the same reached definitions | MP-6: reuse a member's green result when its reach digest on the merge branch equals the source branch's |
| 7 | "Red on main before the branch" has no evidence on main | the home holds no result row for the red `seon.render.ns-test/task-and-schema-entities-render-as-graph-nodes` (test results live on test branches); so `merge!` cannot tell a pre-existing red from a new one | D6 |
| 8 | The test-keeping decision has no data | owner ruling §5 "root keeps only new behavior": nothing in the diff says which added deftest duplicates an existing one | MP-5 |

## 1. What exists end to end, step by step, measured

One docstring change to `seon.task/step-summary` plus one new deftest `seon.task-steps-test/step-summary-counts-done-steps`.

| # | Step | Function | Measured | Proportional to (should be) | Works? |
|---|---|---|---|---|---|
| 1 | `defn` on the agent's branch | the one REPL path | 170 ms, **540.6 MB allocated**, 11,401 calls (a docstring change) | the one definition | yes; the allocation is 10× over (finding F1) |
| 2 | tests reaching the change run | F4 step | reaching set of 2 tests; my first deftest's input was wrong and the reply showed it red with the contract error | reaching tests | yes |
| 3 | `my.test/check` of 2 tests | `seon.test/run` | 1,435 ms, **2.74 GB allocated**; failures also printed to `:out` (1,263 tokens) | test bodies | works, 10× over (F2; `:out` is merge.md §9 item 7) |
| 4 | merge request | `my.program/propose-merge!` | not exercised (needs an assigned task); merge.md §8: 0.46-1.05 s | proofs + one write | yes, G3/G4 open |
| 5 | branch the target / merge branch | `registry/branch!` | 18-27 ms | constant | yes |
| 6 | diff | `compare-branch` | **41 ms** for 2 identities (merge.md §8 had 174 ms for 4) | changed identities | yes |
| 7 | test merge with the file proof | `source/prepare-merge!` with root | **3,204 ms**, `:seon.test/reused-count 0`: 2,570 ms in the run (3 members: the proof, the reaching test, one unrelated red), ~630 ms branch, acquire, write and file proof | test bodies + changed files | yes; the red `seon.render.ns-test/task-and-schema-entities-render-as-graph-nodes` (unrelated: a schema usage assertion) shows, not gated |
| 8 | merge commit | `source/accept-merge!` | **1,200 ms** (2,678 ms to refuse under the wrong custody) | one guarded transaction of 2 rows | yes; 10× over (MA-16a) |
| 9 | write-back | `source/write-back!` | **371 ms**, 2 files: `src/seon/task.clj` one span replaced, `test/seon/task_steps_test.clj` one form appended; `git diff` shows exactly 6+/1- lines | changed files (one analysis each) | yes, but leaves the target's file facts stale (blocker 1) |
| 10 | git commit | none | `git commit` in the scratch checkout 20 ms; plumbing commit off the base 74 ms; `git merge-tree --write-tree` with another lane's edit in the same file 16 ms, clean | changed files | **missing** |
| 11 | the JVM loads it | `bin/seon start --head` | 43.5 s downtime (hostbound-opus §5 P5), agents lose private SCI state | changed namespaces | only by restart; in place is a no-op (blocker 4) |
| 12 | the target's database matches the files again | boot publication | only at the next restart (adoption `seon.cluster/adopt-entities!` shows 1.65 s mean, 7.7 s max in default's health) | changed files | **missing** between restarts |
| 13 | second merge of the same function | `prepare-merge!` with root | refused `:one-file` in 47 ms | — | **broken** (blocker 1) |
| 14 | the agent's next context | current-state block | the agent learns the result only by reading the request | — | MA-11/F5 territory |

## 2. The efficient form of each step

**The patch is the declaration, not the line.** Seon already knows what changed: the three-way value names each
changed identity with its digests, each declaration knows its file and byte span, and `proven-file` splices exact spans
and reindexes the result (source.clj:1105). A git diff is a *derived view* of that patch, never an input: Seon never
parses a diff and never runs a textual three-way merge of program files. Each step and what it must cost:

| Step | Efficient form | Proportional to |
|---|---|---|
| diff | `compare-branch`: merge-base walk + digests of changed identities (installed) | commits since the fork + changed identities |
| merge branch | `d/branch!` off the target's tip + one transaction of the non-conflicting rows | changed rows |
| norms | each norm's tx-data from the merge branch's value (MA-8) | rows it converts |
| tests | proofs ∪ reaching tests ∪ root's choice; a member whose reach digest (`:seon.test.member/reach-digest`) on the merge branch equals its digest in the source branch's green run is answered from that record (MP-6); only tests whose reached definitions differ (the target's `behind` changes, norms, root's edits) run | tests whose reach differs between source and merge branch |
| files | `proven-files` over `db/with target merged-rows`: splice spans, reindex each changed file | changed files (one clj-kondo analysis each: 371 ms measured for two files) |
| merge commit | ONE Datahike transaction: program rows + **the reindexed file facts (spans, file digest, `:seon.fn/file` refs)** + norm tx-data + the closed request + the git commit id | changed rows + changed files |
| git | blobs for the changed files, a tree from the loaded commit's tree, `git commit-tree -p <loaded commit>`; then `git merge-tree --write-tree` into the git branch | changed files |
| JVM | `Compiler/load` of the changed namespaces (and their dependents) from the commit's bytes, arm their Vars, advance the SCI base (PB-03b) | changed namespaces |

**One source of truth at each moment.** Today there are two: the target's program rows and the working tree's bytes,
kept equal by gates that refuse (`:file-changed`, `:one-file`, `:span-mismatch`). The order that keeps one truth:

1. **Compute, write nothing.** On the merge branch's value, `proven-files` makes the candidate bytes and their reindex
   (the file facts). Pure: a crash here loses nothing.
2. **Git objects.** Write the blobs, the tree and a commit `G` whose parent is the commit the target's file facts were
   indexed from (the loaded commit). Git objects are immutable and unreachable until a ref names them: harmless if
   anything later fails, collected by `git gc`. The parent is the commit the target's file facts name: the loaded
   commit at first, then each merge's `G` (the target records it), so consecutive merges chain without a restart.
3. **The merge transaction** on the target, guarded by its tip (`registry/head-guard-tx`): program rows, file facts,
   norm data, the closed request, and `G`'s id on the request (`:seon.merge/git-commit`). This is the moment the merge
   happens. Files and rows now agree *as data*: the target says which bytes (`G`) its rows were proven against.
4. **The git ref.** Integrate `G` into the git branch (MP-3) and move the ref with an expected old value
   (`git update-ref <ref> <new> <old>`, a compare-and-set). A crash between 3 and 4 is recovered idempotently: any
   merged request whose `:seon.merge/git-commit` is not reachable from the branch is integrated at the next `merge!` or
   boot. Only additive, never a second truth.
5. **Load.** MP-4, or a restart batch for the kinds that cannot reload.

This replaces today's order (merge commit, then a separate `write-back!` into whatever the working tree holds), whose
window leaves the target merged and the files unwritten with nothing recording it.

**Why off the loaded commit, not the working tree.** The target's file facts are the loaded commit's bytes (they were
published from that archive), so the write-back's base check (`:seon.fn.file/digest` against the bytes) holds by
construction when the base is that commit's blob, read with `git cat-file`. Writing into the shared tree instead
compares against bytes another lane may have changed or not yet committed (24 files today). Git then does what it is
good at: a file-level three-way merge of `G` with the branch tip (`git merge-tree`, 16 ms, clean when lanes touched
other lines). A git conflict means a lane edited the same lines through files: the merge stops and root resolves it
like any unmerged identity.

**Working tree.** Lanes share it, so `merge!` never rewrites a file a lane holds. After the ref moves, each changed
path whose working copy equals the old tip's blob is refreshed (`git checkout <new> -- path`); a path with someone's
uncommitted edit is left alone and named in the answer (the lane rebases its edit with git as it would after any other
commit). `bin/lane claim` already knows the holders.

## 3. How the change reaches the running JVM

Facts:
- Reproduced in a plain Clojure 1.12.5 JVM: `foo.core` compiled to classes, class files touched to 2100, the source
  changed from `(defn f [] 1)` to `2`: `(require 'foo.core :reload)` left `(f)` at 1; `Compiler/load` of the new
  source text answered 2 in 15 ms and kept the same Var (`identical?` true).
- default's classpath is the archive `data/source/e3b7bae3a…` plus `target/dev-program-classes/3b9f8dc7…` (class files
  dated 2100). `require :reload` finds the class newer than the source and loads it: nothing changes.
- clj-reload loads a namespace from a string with `Compiler/load` (`clj_reload/util.clj:121` `ns-load-file`, at
  `61c6fa7`): no classpath lookup, no class-file check. Its `:no-unload` option skips the `remove-ns` Seon must not do
  (the SCI base, Flow graphs and armed wrappers hold Var objects; reloading in place keeps each Var and replaces its
  root, as `require :reload` does).
- Arming: a reload replaces armed roots with unarmed functions; development adoption already orders entities → load
  → arm → record (cluster.clj:2525-2560); the arm step stays.
- PB-03a: a reload costs 30-1,613 ms per namespace (`seon.fn`, 160 rows, is the worst); advancing the SCI base by the
  reloaded namespaces costs 0-3 ms versus a 120-150 ms / 246 MB rebuild.

Recommendation, by kind of change (the classification is per declaration, from the diff, never a roster):

| Kind | Path | Why |
|---|---|---|
| function bodies, new functions, docstrings, contracts, tests | **reload in place** from `G`'s bytes: `Compiler/load` of each changed namespace in clj-reload's dependency order (UL-9), then its dependents only when a macro or an inlined constant changed, then arm, then PB-03b advances the base; the loaded identity becomes `G` | 30-1,600 ms instead of 43.5 s; agents keep private state |
| `defmacro` | reload it and every namespace that uses it (compiled callers hold the old expansion) | the dependents set, from `:seon.fn/calls` |
| `defrecord`/`deftype`/`defprotocol`, `definterface`, `gen-class` | **restart batch** | existing instances keep the old class; a reload makes two incompatible classes |
| boot path, schema resources boot reads, `deps.edn`, the writer, the evaluator, the test runner | **restart batch** (`start --head`) | they run from the JVM's start; hostbound-opus D2/D5 |

The loaded identity must name the bytes, not just a commit: program classes are keyed by content per namespace
(RED-5's fix), or simply bypassed by `Compiler/load` for reloaded namespaces; the next restart builds the cache from
the new commit. The restart batch stays root's: `merge!` answers `:seon.merge/needs-restart` with the declarations
that forced it, and root batches.

## 4. Root's review at its REPL

What root sees is one value per request, and one form per decision. Forms (merge.md §1 names, plus one key):

```clojure
(my.program/merge-requests)                          ; open requests at the home
(my.program/diff {:seon.merge/request "…"})          ; the diff value, merge.md §2, plus :seon.merge/new-tests
(my.program/diff {:seon.merge/request "…"} 'a/g)     ; one declaration's source, ours and theirs
(my.program/merge! {:seon.merge/request "…" :seon.merge/no-commit true}) ; test, keep the merge branch, show the run
(my.program/checkout! {… :seon.merge/side :ours :seon.program/identities #{[:seon.test/sym a/new-test]}}) ; drop a test
(in-ns 'a.core-test) (defn …)                         ; fold: add the row to the table test on the merge branch
(my.program/merge! {:seon.merge/request "…"})        ; commit, write files, git, reload
```

**The test-keeping decision (owner ruling §5).** The diff value adds `:seon.merge/new-tests`: each deftest the source
added, with the existing tests that reach the same changed functions (`seon.fn/reverse-closure` seeded with the new
test's callees, intersected with tests on the target) and whether the task names it as a proof:

```clojure
:seon.merge/new-tests [{:id [:seon.test/sym seon.task-steps-test/step-summary-counts-done-steps]
                        :proof? true
                        :covered-by []}]            ; nothing else reaches step-summary: keep
```

An empty `:covered-by` means keep; a non-empty one asks root to fold (one `defn` of the table test plus `checkout!
:ours` of the new test) or drop (`checkout! :ours`). The decision is recorded as what the merge commit carries:
a source-added test absent from the merge commit was dropped or folded, which is positive data already in the
history; the request carries `:seon.merge/test-decisions` only for the reason text (decision D3 below). A folded
table row is re-tagged with the task in `:seon/tasks`, so the task's proof survives on main.

**How a Seon root does it easily.** Root's whole job is the forms above, each answer offering the next forms as
`:seon.render/operations`. The diff's AI render is counts plus one entry per identity; `new-tests` lists only ids
and covering tests. No source text is shown until root asks for one declaration. This is MA-4 + MA-11's forms; the
only new datum is `:seon.merge/new-tests`.

## 5. Square pegs: decisions for the owner

**D1. Which is the truth between merges: the target's rows or the git branch?** Today both are written by different
writers: Seon agents merge rows, lanes commit files, and the two meet only at a restart (blocker 2).
1. **Database first, files derived per merge (recommended).** `merge!` writes the git commit off the loaded commit
   and integrates it with `git merge-tree`; lanes' git commits reach the database at the next adoption. Cost: two
   writers remain until lanes merge through Seon (RF track); git resolves file-level overlaps. Guarantee: every merged
   row is in a git commit and the target records which.
2. Git first: a merge request's write-back is a git branch; root merges in git; the database re-publishes changed
   paths. Cost: the declaration-level diff, conflicts and norms are lost to a line diff; agents stop seeing their own
   unit of change.
3. Gates only (today). Cost: every second merge to a file refuses; the shared tree drifts; restarts are the only
   reconciliation.

**D2. Where does `merge!` put the git commit?**
1. **A commit off the loaded commit, integrated into the git branch with `git merge-tree` and a compare-and-set ref
   update; clean working-tree paths refreshed, held ones named (recommended).** No working-tree writes under a lane.
2. Into the shared working tree, then `git commit --only` (today's write-back + `bin/lane commit`). Cost: refuses on
   every file another lane changed or holds (33 files right now).
3. A separate git branch per cluster (`seon/<cluster>`) that root merges into the working branch by hand. Cost: one
   more manual step per batch, but no shared-tree contact at all.

**D3. How is root's test-keeping decision recorded?**
1. **Derived: the merge commit's carried rows say what was kept; an optional reason on the request (recommended).**
2. `:seon.merge/test-decisions` vector cells `{:test :decision #{:keep :fold :drop} :into}`. Cost: a stored enum of
   what history already shows.
3. A required decision per new test before `merge!` commits. Cost: automatic merging stops on every new test.

**D4. Reload in place for function-level changes, or restart every batch?** (hostbound-opus D5, now with the AOT fact.)
1. **In place by `Compiler/load` from the merge commit's bytes for functions, restart batch for the kinds in §3
   (recommended).** Sacrifice: the reload hazards a restart never has (a `defonce` holding an old function, a
   closure captured at boot).
2. Restart per batch only. 43.5 s per batch; agents lose state; root must batch so agents wait.
3. Reload via `require :reload` after removing the class cache. Cost: still reads the old archive's classpath;
   needs a classpath change per adoption.

**D6. How does `merge!` know a test was red on main before the source branch existed?** The merge-base evidence it
needs does not exist: results are recorded on test branches, and the home held no result for the red test above.
1. **Run each red member once more on the target's tip when it is red on the merge branch (recommended).** Cost: one
   test body per red test, only on red; the answer is current evidence, never a stale record.
2. Keep baseline runs on main (the "test evidence persists" ruling): every main commit carries results. Cost: a
   whole-suite run per main commit; stale by the next commit.
3. Treat unknown at main as "red on main" and ask root. Cost: every unrelated red stops the automatic merge.

**D5. Norms run as Datahike runs them, or as Seon needs?** Datahike's `transact-norms` calls a `tx-fn` with the
**connection** and resolves it as a JVM Var (`norm/norm.clj:142-158`), but a norm defined on an agent branch is an
SCI definition until merged and loaded. MA-8c/FK-DH already chose a fork seam computing norm tx-data from a database
value; this note adds only that the norm runs in the merge branch's SCI context from its source, never through
`requiring-resolve`.

## 6. Rows and cards this implies

Existing rows this note leans on, not duplicated: MA-0, MA-4, MA-11 (one `merge!`), MA-14a (target branch), MA-15,
MA-16a (profile: accept's 1.2 s, prepare's ~630 ms outside the run), MA-8a-c (norms), RED-5, UL-9, PB-03b, RF-1, RF-2.

| Id | Title | Change | Files | Proof | Deps |
|---|---|---|---|---|---|
| MP-1 | A merge records the file facts its write-back proved | `merge!` computes `proven-files` on the merge branch's value before committing and carries, in the one merge transaction, each changed file's digest and each written declaration's `:seon.fn/file` and `:seon.fn/form-span` from the reindex; `write-back!`'s separate pass after the commit goes; the merge commit's file facts equal what publication would write | `src/seon/cluster/source.clj` | `seon.cluster.source-test/a-second-merge-of-one-function-writes-back` (two merges of one function in a row, both write back; today the second refuses `:one-file`) | MA-11 |
| MP-2 | `merge!` writes its git commit off the loaded commit | blobs, tree and `git commit-tree -p <loaded commit>` with trailers (request, run) made before the merge transaction (the Datahike merge commit id cannot be in it: the transaction records `G`, not the reverse); the parent is the commit the target's file facts name (the loaded commit, then the previous merge's `G`), which records `:seon.merge/git-commit`; base bytes read with `git cat-file` from the loaded commit, never the working tree | `src/seon/cluster/source.clj`, a small git owner (`seon.git`, plumbing via `babashka.process`), `resources/seon/schemas/seon.merge.edn` | `seon.git-test/a-merge-commit-builds-off-the-loaded-commit-without-the-working-tree` (a dirty working copy of the same file is untouched; the commit's file reindexes to the merged digests) | MP-1 |
| MP-3 | Integrate the merge's git commit into the git branch | `git merge-tree --write-tree` of the branch tip and `G`, `commit-tree` with both parents, `update-ref` with the expected old tip; clean working-tree paths refreshed, held paths named; a file conflict stops the merge with the merge branch kept; a merged request whose git commit is unreachable is integrated at the next `merge!` or boot | the git owner, `src/my/program.clj` | `…/a-lane-edit-elsewhere-in-the-file-merges-clean`, `…/a-lane-edit-on-the-same-lines-stops-the-merge`, `…/a-crash-after-the-merge-transaction-integrates-at-the-next-merge` | MP-2 |
| MP-4 | Reload merged namespaces from the commit's bytes | the development reload loads each changed namespace with `Compiler/load` from `G`'s blob (clj-reload's `ns-load-file` shape, no `remove-ns`) in clj-reload's order, dependents only for changed macros, then arms, records, and PB-03b advances the base; kinds in §3 answer `:seon.merge/needs-restart` instead; RED-5's verification is this row's first probe | `src/seon/cluster.clj` | `seon.cluster.adoption-reload-test/a-merged-function-reloads-in-a-jvm-with-program-classes` (platform tier, isolated JVM with program classes) | RED-5, UL-9, PB-03b, MP-2 |
| MP-6 | The merge branch reuses the source branch's green results | at `merge!`'s run, a member whose reach digest on the merge branch equals the one in the source branch's green run is answered from that record (unknown or red never reused); a red member is run once on the target's tip to classify it (D6) | `src/seon/cluster/source.clj`, `src/seon/test/runner.clj` (the reuse key); first probe: the reach digest is the same for one test on two branches holding the same reached rows | `seon.cluster.source-test/a-clean-merge-reuses-the-source-branchs-green-results` (reused-count equals members, prepare under one test body) | MA-11 |
| MP-5 | The diff names new tests and what already covers them | `:seon.merge/new-tests` in the diff value: each source-added deftest, `:proof?`, `:covered-by` (target tests reaching the same changed functions); a folded row re-tags the task | `src/my/program.clj` | `my.program-test/a-new-test-reaching-a-covered-function-names-its-cover` | MA-4 |

## 7. Findings (record as tasks; outside this lane's files)

- F1 `seon.turn.batch/a-docstring-defn-allocates-half-a-gigabyte`: a docstring-only `defn` of `seon.task/step-summary`
  on an agent branch allocated 540.6 MB, 11,401 calls, 170 ms (agent `mp-src`, result `result/eb788551b4327`).
- F2 `seon.test/a-two-test-check-allocates-gigabytes`: `my.test/check` of two tests allocated 2.74 GB in 1,435 ms.
- F3 `seon.cluster.source/accept-merge-spends-seconds-before-a-custody-refusal`: under another branch's custody
  `accept-merge!` ran 2,678 ms of checks and then the writer refused; the custody check belongs before the work.
- F4 `seon.turn.loop/an-empty-reaching-set-reports-passed-false`: a run with nothing reaching answered
  `{:seon.test/passed? false :seon.turn.loop/red []}` (an `mp-root` reply): silence read as failure.
- F5 `seon.db/an-unknown-attribute-read-returns-fifty-frames`: pulling an attribute the loaded schema lacks returns an
  invalid-read map carrying 50 stack frames (Seon-generated bulk, AGENTS.md "Presentation").
- F6 `seon.cluster.source/prepare-tally-is-printed-text`: `:seon.source/tally` is a string built from the run, with a
  failure report inside it: a printed value outside the value printer.
- Default is `:faulted`: 6 open faults (14 "memory pressure" arming refusals), 51 errored receipts, `seon.mcp/jvm-run`
  mean 7.2 s.

## 8. Limits of this note

- `propose-merge!` and the home's merge request were not exercised (the probe called `prepare-merge!` and
  `accept-merge!` directly on a disposable target); their costs are merge.md §8's.
- In-place reload was not run in default (never load into default's JVM); its cost is PB-03a's; its no-op follows from
  the class files measured in default plus the plain-JVM reproduction (a disposable directory outside the checkout, Clojure 1.12.5).
- Whether `seon.render.ns-test/task-and-schema-entities-render-as-graph-nodes` is red at the merge base is unknown:
  the home records no result for it (D6); it is red on the source and merge branches with a cause unrelated to the change.
- One sample per timing.

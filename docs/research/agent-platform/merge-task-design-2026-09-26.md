---
type: design
lane: merge-task-design-opus
status: design for owner ruling — nothing implemented; three priced options, B recommended
created: 2026-09-26
head: c236d2d97 (default pid 80197, booted 17:26:52Z)
authority: owner 2026-09-26 ("we gate on bothering the root agent on all tests passing that the task was assigned … a fresh root env with the merge task … a copy of the agents env to run code in … easy for them to specify what to merge"; "Isn't this just a selection problem?")
---
# Merge as a task: root gets a fresh world and a copy of the agent's world

**What exists.** An agent proposes by message (`my.program/propose-merge!`, `src/my/program.clj:443`).
Root lists proposals (`proposals` `:467`). Then `prepare-merge!` (`:505` →
`seon.cluster.source/prepare-merge!` `src/seon/cluster/source.clj:840`) does four things in one call:
three-way B/C/H, optional `:seon.program/identities` selection, S = a fork of H, and the gate.
The gate is `seon.cluster/candidate-gate!` (`src/seon/cluster.clj:2758`) → `reaching-run` (`:2737`) →
`seon.test/run :named :changed`, the two-hop reach, plus issue tests. `accept-merge!` (`source.clj:932`)
checks green members, then the `uncovered` walk (`:970-973`), dangling tasks and the writer's digest guard
(`:817`). `write-back!` (`:1192`) follows.
**Principal approach.** Take the monolith apart along the owner's line:
- **Gate on the task's proofs.** The *agent* must show its assigned task's proofs green before root is bothered.
- **Root's merge is a world, not a call.** Root works on S (a fork of H) and a copy C′ (a fork of the
  agent's head C). Both are ordinary branches with ordinary hosted REPLs.
- **Root says what to merge** by naming identities from the diff value, at that REPL.

Nothing here is a new evaluator, registry or runner. Only the gate's *required set* changes, and that is a
ruling, not machinery.

## Broken or unverified first

- **Default is `faulted`.** There is one open fault, `ClassCastException` in `seon.schema/valid-candidate-value?`
  (17:13:55Z, signature `fa60ad84…`). It is not from this lane, and it predates my probes.
- **No merge ran here.** The fork and diff costs below are measured. Acceptance, write-back and the gate
  under the new required set are not.
- **The merge entrance has almost no tests.** Its own static test reach: `my.program/prepare-merge!` is
  reached by **0** tests at two hops (0 transitive), `seon.cluster.source/prepare-merge!` by 7.
  `seon.issue/candidate-dangling` is reached by 1.
- **There is no runtime task writer.** Spec 7.8 names `seon.task/create!`, but it does not exist.
  Tasks come only from EDN (`seon.issue/index-tasks!` `src/seon/issue.clj:698`).
- **No agent→task assignment fact exists.** Grep finds no `:seon.agent/task`. Today the "assigned task" is
  whatever `:seon.issue/id` the agent passes to `propose-merge!`.
- **Two families serve as a task's proofs:**
  - `:seon.issue/tests`: 463 issues, 110 test edges;
  - `:seon.task/tasks` on deftests (7.3): 101 tasks, **10** tests name one.

## 1. The merge task

**Producer.** `propose-merge!`, the agent's one entrance, gains the gate the owner named.

**The gate.**
1. It reads its task's proofs. For a task these are `(:seon.task/proofs (seon.issue/task-edges db T))`
   (`issue.clj:716`). For an issue they are `:seon.issue/tests`.
2. It **refuses when the proofs are empty.** A task with no proof cannot be proposed (7.6: raised ≠ specified).
3. It runs exactly those proofs as one `seon.test/run {:seon.test/policy :named :seon.test/identities proofs}`
   on the agent's own world. This is `my.test/check`'s path (`src/my/test.clj:6`). Each member runs on a
   fork of the published commit plus C's program overlay (`clean-request-handle`, `test.clj:1805`).
4. It **refuses unless every proof is green.** Red returns the run to the agent as data, so the agent keeps
   working and root is not woken.

**Record.** Green writes the merge task. Its data is:
- `:seon.task/kind :merge`, an accreted enum member;
- the proposing branch;
- C's commit id;
- the assigned task or issue handle;
- the green proof run id.

Its identity is `seon.id` over `[:merge branch C]`, so re-proposing the same commit finds, not duplicates (2.5).

**Writer.** In Option B it is written through the 7.8 writer this needs anyway: `seon.task/create!`, about
+25 lines. In Option A it stays the existing message, and `:seon.message/about` carries the handle.

**Root's inbox.** `proposals` becomes "merge tasks without an outcome". Outcomes are separate facts (2.8):
accepted with M, or declined with a reason. The roster filter (`program.clj:483-488`) goes, because
openness is a fact, not "the branch is still linked".

**Why a task and not only a message.** Evidence must outlive chat. Root's decision, the run it relied on and
the diff it took are facts about that task. A message is a delivery, and `:seon.turn/handled` already failed
to say "merged" (`fix-dogfood-api` landing: root's wake marked `276dd9433eb3` handled without merging).

## 2. The fresh root environment

`(my.program/open-merge! {:seon.task/id M})` is called by root, which holds cluster custody. It does these steps:

| Step | Existing function | Measured (default, JVM, this lane) |
|---|---|---|
| S = fork of H, named `merge-<M>` | `registry/branch!` `src/seon/cluster/registry.clj:192` with `:seon.cluster.registry/from (commit-id H)` | 20–21 ms |
| C′ = fork of C, named `review-<M>` (C taken from the task, **not** the live branch, so the agent keeps working) | same, `:from C` | 20–21 ms |
| B = merge base of C and H | `merge-base` `source.clj:749` | walk proportional to commits since the fork (not re-measured) |
| the diff value | `program/changed-identities` `src/seon/program.cljc:588` + `digest-map` `:459` + `three-way` `:631` | 31 ms changed-identities; 39–47 ms full three-way (1-function delta) |
| a REPL in each world | `host/host!` `src/seon/cluster/host.clj:124` → `acquire-context!` `src/seon/cluster/agent.clj:973` → `sci.eval/fork-cluster-ctx` `src/seon/sci/eval.clj:3621` | acquire **719 ms cold, 10.6–11.4 ms warm** (base ctx memoized per program); host start 240 ms first (host.clj comment) |
| release/retire at close | `release-context!` `agent.clj:887`, `retire-branch!` `registry.clj:339`, `host/retire!` `host.clj:155` | 0.3 ms release, 8.6 ms retire |

Opening therefore costs about 50 ms of forks and diff, plus one warm acquisition per world. The first
acquisition of a new program costs about 0.7 s and is paid once per program identity. The opening is
sub-second when warm.

**What the environment is.** It is two branches with hosted agents. The environment has these parts:
- **S (`merge-<M>`).** Its REPL is root's fresh environment. The agent id is `merge/<M>`, and its opening is
  generated from the merge task (5a): the task, the diff and the proof run. Its writes, defs and test
  records land on S. S is D1's intermediate branch: it is where conflicts are repaired (D1 §5.3-4).
- **C′ (`review-<M>`).** This is the copy of the agent's environment, with the agent's program exactly as
  proposed. Root evaluates there to read `(source f)`, call the agent's functions and run `(my.test/run)`.
  C′ is disposable: nothing root does there reaches the agent's branch or the cluster.
- **The destination.** The cluster connection is handed from root's own custody into S's environment at
  open, as a held value (law "authority comes only from held custody", passed explicitly). `accept!` reads it
  there. No function conjures it.

**One REPL entrance.** Both REPLs are `evaluate-source!` runs (`agent.clj:754`):
- **Outside root (MCP):** `eval_clj` with `branch merge-<M>` or `branch review-<M>`. This works today
  once the two forks exist. MCP `branch create` forks only from the head (`script/seon/dev/mcp.clj:500-515`),
  so it needs an optional `from` commit (+3 lines) or `open-merge!` itself.
- **Product root:** the same host and evaluate call, reached from `open-merge!`'s returned handles.

There is no second evaluator and no "evaluate in another world" function. Examining is values:
- the diff is a `:seon.program/three-way` map;
- sources come from `pull` on the C′ database value;
- callers come from `my.program/callers` and `tests-reaching` on either world.

## 3. Specifying what to merge

In S's REPL:

```clojure
(my.merge/diff)                 ; the three-way: :added :changed-on-branch :retracted :conflict (+ digests)
(my.merge/take! #{[:seon.fn/sym a/f] [:seon.test/sym a/f-test]})  ; or (my.merge/take! (:seon.program/added (my.merge/diff)))
(my.test/check {:seon.test/identities (my.merge/proofs)})       ; the combined program S = H + selection
(defn a/f …)                    ; repair on S, an ordinary row on S
(my.merge/accept!)              ; M on the cluster; answers :seon.source/accepted
(my.program/write-back! {:seon.source/accepted *1 :seon.fn/root "/…/seon"})
(my.merge/close!)               ; retire S, C′ and both hosts; the outcome fact on the merge task
```

**What each form reuses:**
- **`take!`.** The body of `prepare-merge!` up to `tx`: `replacement` (`source.clj:773`) of the chosen
  identities from C′ onto S, then the namespace-row closure. It also reuses the "selected reaches an
  unselected change" refusal (`unselected-reach` `:781`, 17 ms reverse closure). That refusal is data for
  root, not a gate. `take!` also reuses the optional `proven-file` proof before any test.
- **What `take!` drops.** The gate call and S's construction leave `prepare-merge!`: S already exists and
  is root's world.
- **`my.merge/proofs`.** The merge task's assigned proofs.
- **What the task holds.** The task never lists functions, schemas or tests. It names a branch, a commit and
  the task whose proofs it satisfied. Root's selection is a REPL argument, and acceptance records it (§4).
- **`:seon.program/identities`.** Already the whole selection language (`prepare-merge!`'s selection arity,
  `5f53079fc`). Nothing else is needed.
- **`accept!`.** It is `accept-merge!` with the tested branch = S's current commit. Its selection is
  `(changed-identities H S)`, which is exactly what root took plus its repairs. Its guard is still the
  writer's per-row digests (`declarations-held-tx` `:817`), so writes to unrelated rows on the cluster
  never stale it (`fix-merge-basis`).

## 4. The gate

| When | Must pass | Where | Evidence bound |
|---|---|---|---|
| **propose** (the agent bothers root) | every proof of the assigned task (≥1) | fork of published + C's overlay | run id on C's commit, in the merge task |
| **accept** (root) | the assigned task's proofs **on S's current commit**. Contracts arm as always, and the writer's schema/ref validation runs as always | S | run id(s) whose tested commit = S head; `program-written-since?` false (existing check) |
| **root may add** | any `:seon.test/identities`, `:seon.test/namespaces`, or `my.test/check`'s two-hop reach of the change | S or C′ | every run root cites is bound to acceptance. Root chooses; nothing is implicit |

**Acceptance binds (7.16):**
- the merge task and the assigned task's revision;
- the proof identities;
- the run(s) on S's commit;
- S's commit and B/C/H;
- the expected digests of H's touched rows;
- root's selection.

The result is M, whose parents are H, C and S. That is the existing `:parents` (`source.clj:992`).

**Replacing the CLAUDE.md merge sentence.** The current sentence reads: "contracts, task tests and every
current test reaching changed functions must pass; missing coverage refuses by name". Proposed text:

> Test the combined program on the intermediate branch: contracts and the assigned task's proofs must pass
> there, and a task with no proof cannot be proposed. Root runs whatever else it judges; acceptance records
> every run it relied on. Reach is information root reads, never an implied gate.

D1 R8/R9 and §6 items 1-3 are revised the same way. That is the owner's call, and those files are not this
lane's.

**What is given up, stated plainly.** A caller at distance ≥2 that the agent broke is caught only if root
runs its test. Two things mitigate this: the two-hop set is one read away (1–64 ms), and `my.merge/diff`
can print its size. Mitigation is not a guarantee, so **Q2** asks the owner.

## 5. "Isn't this just a selection problem?" Yes. Here is what is indexed and what is not

Live, default, basis 536870992, JVM read-only.

**Indexed:**
- **`:seon.fn/calls`: 99,042 datoms.** 44,663 of them are on the 2,270 tests, and every test has calls.
  There are 5,309 functions.
- **Two-hop selection (`seon.fn/call-reach` `src/seon/fn.clj:1869`).** It is an avet walk, 1–64 ms per
  query. `seon.db/db` selects 926 tests and `seon.test/select` selects 7.
- **Task proofs.** `:seon.task/tasks` is indexed (`resources/seon/schemas/seon.task.edn`), and
  `task-edges` answers proofs in one query.

**Not indexed:**
- **`:seon.test/reach` (what each test actually exercised): 0 datoms.** The schema exists and `select`
  checks it (`src/seon/test.clj:720`), but nothing produces it.
- **Durable per-member evidence on default now:** 6 runs and 7 members, because the store was rebuilt.

**Why static transitive reach saturates.** A walk of all 5,309 reverse closures took 1,268 ms, as a one-off
survey. Tests reached per function:

| Statistic | Tests reached |
|---|---:|
| median | 75 |
| p75 | 316 |
| p90 | 1,468 |
| p99 | 1,738 |
| max | 1,945 (of 2,270) |

- 961 functions (18%) reach more than 1,000 tests; 314 reach none.
- Hubs, as two-hop / full: `seon.db/db` 926 / 1,531, `seon.db/transact!` 833 / 1,482, `seon.db/q` 744 / 1,574.
- Every test calls the database primitives and fixture helpers, and every function is a few calls from
  them. So "every test transitively reaching the change" means "most of the suite" for a fifth of the program.

**What "just selection" needs:**
1. **The required set must be a ruling.** The owner's ruling (the task's proofs) makes the gate an indexed
   lookup today, with no new selection code.
2. **Exact reach must be recorded** if reach is ever to gate or rank. This is research ★1 in
   `docs/prds/next-turn/research/test-selection.md`: diff `seon.profile` cells around each member into
   `:seon.test/reach`. That is advice for root under this design and a separate lane. The merge does not
   wait for it.

## 6. What this deletes, and three priced options

These counts are physical lines at c236d2d97 and are estimates until cut:
- the merge path's call into `candidate-gate!`/`reaching-run` for merge (`source.clj:907-930`, ≈24).
  `candidate-gate!` stays for publication (`cluster.clj:2886`);
- `accept-merge!`'s `uncovered` walk and its clause (≈6);
- `prepare-merge!`'s S construction and gate (`:840-930` becomes `take!`, ≈−50 net);
- the issue-test "answered" check (≈4);
- `proposals`' roster filter (≈8).

It also removes planned code:
- D1 **O3a** (`seon.test/select` complete-obligation fix, budget 100);
- the reached-seed-veto half of **O3c** are no longer needed for merge.

**Kept:**
- `my.program/tests-reaching`/`breaks` (`program.clj:167,281`). These are root's reading tools, not gates.
  There is no advisory selection in `my.program` that gates anything, so nothing there is deleted.
- `unselected-reach`, as data.

| | A: gate + root's own REPL | **B (recommended): merge task + two-world environment** | C: B + automation + exact reach |
|---|---|---|---|
| **Change** | `propose-merge!` runs the task's proofs and refuses unless green; `prepare-merge!`'s gate runs proofs + root-named tests instead of reach; delete `uncovered`. Root reviews by `eval_clj` on a C′ it forks through `branch create from` | A, plus the `:merge` runtime task (`seon.task/create!`, 7.8); `open-merge!`/`diff`/`take!`/`accept!`/`close!` over S and C′; `prepare-merge!` splits into `take!` | B, plus the product root is woken by merge tasks and opens the environment itself; recorded `:seon.test/reach` (research ★1) shown and ranked in `diff` |
| **Size** | ≈ +35 / −40 | ≈ +125 / −95 over two slices: (1) gate + task + accept, ≈ +60; (2) environment, ≈ +65. **Over 100 added in total**, justified by removing the monolith and O3a | ≈ +250 / −350 (★1 retires the static full-closure reach index, ≈−250); days, cross-owner |
| **Hours** | 2–3 | 6–8, plus review | 3–4 days |
| **Guarantee** | Root is bothered only after green proofs; acceptance binds proofs on S | A's guarantee, plus: root's experiments never touch C or the cluster; the merge outlives chat as a task with outcome and evidence; conflicts are repaired on S (D1 R11) | B's guarantee, plus exact exercised reach per test and no human trigger |
| **Sacrifice** | No fresh context: root's own transcript grows, and a live `prepare-merge!` still builds S per call | A new enum member and writer (7.8 was owed anyway); two hosted agents per open merge (≈20 ms + 11 ms warm each) | Automation competes with the "no auto merge" stance (it opens but never accepts); size |

**Recommendation: B, in two slices.** Slice 1 alone, the gate plus the task, already answers "bother root only
when the proofs pass".

## Owner questions

1. **Q1: required set.** At acceptance, is the gate exactly the assigned task's proofs on S, plus whatever
   root chooses to run and record? This drops "every current test reaching changed functions" and "every
   replaced function reached by ≥1 green test" from the law.
2. **Q2: residual risk.** Should `accept!` at least *show* the two-hop reach count and its stale members
   (no gate)? Or should it run them by default unless root declines by name?
3. **Q3: authority.** May root hand the cluster connection into the merge environment at open, so the fresh
   environment accepts itself? The alternative is that the environment ends with a proposal value and root
   accepts from its own REPL, which adds one form and moves no authority.
4. **Q4: the task family.** The merge task is a runtime `seon.task` row (`:kind :merge`, 7.8), and proofs
   are read from `:seon.task/tasks` or `:seon.issue/tests` until 7.11 migrates issues. Yes?
5. **Q5: the proposer's branch.** After acceptance, is C retired by `close!`, or left to its agent and to
   retention GC?
6. **Q6: base check.** Should the propose gate also require the proofs to be red on B (3.9: a proof that
   already holds proves nothing)? That costs one more run per proposal.

## Evidence and limits

**Forms, JVM, session `merge-task-design`:**
- the counts query (147 ms);
- the full reverse-closure survey (1,268 ms, a one-off; the profile flags it over one second because it
  walks all 5,309 functions, which no merge does);
- fork, acquire, diff and release/retire, three times.

**Probe branches.** `merge-task-design-probe`, created through MCP with one `defn mtd-sq` and retired, and
`merge-task-design-copy{,-2,-3}`, created and retired. The roster was checked empty of them afterwards. The
`mcp/merge-task-design-probe` agent row stays with its admission closed, as for every hosted branch.

**Not measured:**
- a proof run on S. The landing figure is a changed one-test check of 1.5 s and a reused one of 72 ms
  (`fix-test-request-cost`);
- `merge-base` on a long lineage;
- `take!` and `accept!` as designed.

No source was edited and no test was run.

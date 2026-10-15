---
title: "One merge base: Datahike's commit graph is the only record of where a branch starts"
type: plan
status: research (phase status below; every lane updates it in its commit)
created: 2026-10-15
---

# One merge base

The owning plan. Each agent builds on this document in turn, so nobody redoes work that is already done better
elsewhere. Every call site lives in the ledger, [one-merge-base-callsites.md](one-merge-base-callsites.md), and
nothing is done until every ledger row is converted or deleted.

**Evidence it builds on:**
- [datahike-internals.md](datahike-internals.md) (`d10e70f10`): the fork's internals, measured.
- [datahike-seams-inventory.md](datahike-seams-inventory.md) (`cb161f018`): Seon's seams, from grep.
- [own-changes-notes.md](own-changes-notes.md) and `own-changes.patch` (`b064a3e8f`): the red deftest and the
  S5 numbers with the right base.
- [chain-step-cost.md](chain-step-cost.md) (`a76a9c141`).

## The problem (reproduced by root, 2026-10-15)

Transaction ids are counted per branch: 774 tx ids name different transactions on main and on
`:chain-f1f6dc8e6652` (same id, different `:db/txInstant`). Seon records where a branch starts in six places:
- `seon.cluster.registry/base-commit-id`, a base ref that `advance-base!` writes as a side commit that is never on main's lineage;
- `:seon.task/merge-base` copied onto attempt rows;
- `seon.turn/fork-commit`;
- `seon.test/fork-commit` and `change-base`;
- `seon.cluster.source/branch-base-t`;
- the adoption record.

Several of these are *t* values compared across branches. `seon.merge/merge-main!` writes a plain
transaction rather than a merge commit. Consequence: the S5 branch's own change came out as 685 identities where
the right answer is 24, and three live attempts failed, one per reader patched.

## The model (git's, taken whole)

- **A forward merge is a merge commit.** `merge-main!` writes with `:parents #{main-commit}`, as
  `accept-merge!` already does for chain→main.
- **The merge base is a function of two commit ids,** computed from the commit graph by git's walk: generation
  numbers, highest generation first. It is a fork function, `datahike.versioning/merge-base`, cached forever by
  its two ids. Nothing stores a copy.
- **A branch's own change is the diff of two database values,** base vs head, walking both index trees and
  skipping subtrees held at the same address, limited to program attributes, then the existing digest three-way.
  No *t* is ever compared across branches.

## Phases

| Phase | Owner | What | Red proof | Status |
|---|---|---|---|---|
| 0 | Sonnet | `seon.cluster.db-flow-test/every-agent-start-has-a-task` never exits and eats every check's 600 s | ends within its bound | in progress |
| R1 | Haiku | dig: prior art (git commit-graph, upstream Datahike, earlier Seon attempts and why), rulings the design must keep | n/a | done, b2ad9cd44 |
| R2 | Haiku | call-site ledger, src + test + script + resources + docs/skills, each row verified | n/a | done, a41868d3a |
| C | Sonnet | REPL confirmation of the approach on real data before any implementation (section below) | numbers below | waiting on R1, R2 |
| 1 | Opus | fork: generation + small parents record per commit; `datahike.versioning/merge-base` | fork tests: fork, after merge from main, criss-cross | waiting on C |
| 2 | Opus | `merge-main!` writes a merge commit; one Seon reader of the merge base; every ledger reader converts | `seon.merge-forward-test/after-a-forward-merge-the-branch-changed-only-its-own-declarations`; S5: 24, `done-refusals` [] | waiting |
| 3 | Opus | own change by address-skipping index diff; history walks (`commit-at-t`, `basis-revisions`, `commit-facts`) replaced | `seon.profile/measure` on `merge-main!` and the step's change set, parent vs change | waiting |
| 4 | Sonnet | delete what is left: base refs, `advance-base!`, the 125 `:base/*` branches, `base-t`, fork-commits, `change-base`, stored merge-base copies | ledger all converted/deleted; HEAD boots | waiting |

Then one restart with the fork bump in the batch, and one live S5 attempt. Expected: done at start, 0 model calls,
and a merge of its 9 tested entities.

## R1: prior art and constraints

Dig by lane `omb-r1` (Haiku), 2026-10-15. Gathering only: no judgement, no design. Each item cites its source. Quotes are at most two lines. Items marked **unverified** were not checked against source in this dig.

### 1. Git's merge-base algorithm

- **Generation v1 (topological level).** "A commit with at least one parent has topological level one more than the largest topological level among its parents." Source: https://git-scm.com/docs/commit-graph
- **Generation v2 (corrected committer date).** "A commit with at least one parent has corrected committer date equal to the maximum of its committer date and one more than the largest corrected committer date among its parents." Same source.
- **Why v2 exists.** The commit that adds corrected dates reports, on the Linux repo, about 635,579 commits walked with topological levels against about 167,468 with corrected dates during `git merge-base`. Source: the search summary for `commit-graph: implement corrected commit date`, https://googlers.googlesource.com/jrn/git/+/c1a09119f68d9800be53f544389f9ef83258ee7f . Secondhand: the figures come from search results, not from the page itself.
- **Stolee's blog.** The posts were not located. The mailing-list thread names them, but no URL was found.
- **Bound rule.** "If A and B are commits with generation numbers N and M, respectively, and N <= M, then A cannot reach B." Same commit-graph page.
- **Walk order.** "If we walk commits using a priority queue seeded by generation numbers, then we always expand the boundary commit with highest generation number." Same page.
- **Commits outside the graph.** Git treats them as "infinite" generation and walks until it reaches commits with a known number. Source: the commit-graph design notes, https://code.googlesource.com/git/+/HEAD/Documentation/technical/commit-graph.txt (secondhand, via search summary).
- **paint_down_to_common**, `commit-reach.c`, https://github.com/git/git/blob/master/commit-reach.c (fetched raw). Queue comparator: `compare_commits_by_gen_then_commit_date`. Each queued commit carries PARENT1 or PARENT2. Parents of a found merge are marked STALE. The walk stops when only stale entries remain (`/* only stale entries remain */`). The early exit (`state.last_gen < state.topo_ceiling`) applies only when `MERGE_BASE_FIND_ALL` is not set. Note: the fetched text names `nonstale_queue` and `max_nonstale`, not `queue_has_nonstale`.
- **Criss-cross.** "When the history involves criss-cross merges, there can be more than one 'best' common ancestor for two commits." `git merge-base --all` outputs every one. Without `--all`, "it is unspecified which best one is output." Source: https://git-scm.com/docs/git-merge-base (fetched raw adoc). `merge_bases_many` calls `remove_redundant` when more than one result is found (`commit-reach.c`).
- **Recursive / ort virtual base.** **unverified.** Not fetched in this dig.

### 2. Datahike upstream and our fork

- **Remotes** (`reference-code/datahike`): `origin` git@github.com:seantempesta/datahike.git; `upstream` https://github.com/replikativ/datahike.git. Local `upstream/main` is at `23692d77` ("datalog-parser 0.2.43 (#1100)"). The fork's pinned HEAD is `62f94893`.
- **Upstream search.** `gh api search/issues` over replikativ/datahike for merge-base, commit-graph and generation found no issue or PR about merge base, commit graph or LCA. Hits on "generation" are secondary-index and GC work. Nearest open issue: #951 "Online GC is unsafe with diff-buf: the freed-address stream is only sound for a linear history". That bears on merge commits. No PR query matched.
- **Fork commits on the writer and commit records** (`git log -S":datahike/parents"`): 67edd9b6 "audit: tamper-evident commit-ids + verify-chain (#823)"; 5566ab13 "feat(versioning): fork-database — independent writable fork at a point in time"; 3224583b "Branch-head readers accept a compact head that names its commit"; 50d4b3ac "feat: :commit-graph? false — provenance opt-out, single-object commits (experimental) (#869)".
- **Latest commit touching `src/datahike/versioning.cljc`**: c9e57c6f "Branch primary indexes by their existing commit pointer" (2026-10-12).
- **Where parents are written.** `datahike/versioning.cljc` `branch!` (L211), `force-branch!` (L324), `commit-id` (L462), `parent-commit-ids` (L468), `merge!` (L739). `datahike/writing.cljc` L601 (`assoc-in … :datahike/parents`) and L1068 (`:datahike/merge-parents`). `datahike/writer.cljc` L271 unions merge parents across a batch.
- **Opt-out.** `50d4b3ac` means a store with `:commit-graph? false` writes no commit record. A generation sidecar keyed by record would be absent there. See `test/datahike/test/commit_graph_test.clj`: "commits write only the branch head".
- **Fork tests covering branch, merge and parents.** `test/datahike/test/versioning_test.cljc`: `branch-preflights-commit-records` (L186), `commit-branching-rejects-commit-graph-opt-out` (L204), `merge-shares-the-transaction-basis-fence-and-keeps-lineage-exact` (L448), `branches-of-one-store-allocate-distinct-entity-ids` (L487). Also `commit_graph_test.clj`, `branch_head_test.clj`, `gc_test.cljc`, `secondary_versioning_test.clj`.
- **Test alias.** `reference-code/datahike/deps.edn` `:test` (L83): extra-paths `test` plus others; `:jvm-opts ["-ea" "--add-modules" "jdk.incubator.vector"]`; kaocha 1.91.1392. **unverified:** the exact run command. Not executed in this dig.

### 3. Earlier Seon attempts

Each row: the commit, and its own words in the message.

- **`d9eb15c05` "Merge base survives collection"** (reviewed in `docs/research/agent-platform/2026-10-10/review-merge-base.md`). Design: "a branch's fork entity records its base-t … the base is the branch as-of its base-t; the parent walk and its 10000-commit bound are deleted." The review's verdict: "**Changes requested.**" This is the t-as-base design that finding 1 of `datahike-internals.md` shows is wrong across lineages.
- **`dc5e1d348` "P1: squash cluster facts from retained immutable merge bases"**. The "retained immutable merge bases" wording is an earlier base-as-copy idea.
- **`663e105cf` "Assess per-branch entity-id ranges …"**. Recommends "the copy-back as a squash merge from the merge base".
- **`6521ded43` hybrid design**. Names the reason for avoiding parents: "never merge-db!/:parents (datahike.gc follows parents, pinning deleted branches)". This is a constraint on the forward merge commit.
- **`58b1a68cc` "P4: root merges main forward into a chain branch (my.root/merge-main!, seon.merge/merge-main!)"**. Introduces the forward merge as a plain transaction, with the base ref moved by hand.
- **`d7cbfc7d8` Plan 1**. Ruling: "a branch's base/ entry is a merge-base ref, not a copy".
- **`c264da25e` "S4: the merge base is written with the assignment; seon.turn/fork-commit reads it on the branch"**. Stored copy on attempt rows, which is the copy the plan deletes.
- **`969555d6d` "A Prove step ends by itself: seon.turn/fork-commit reads the branch's merge base from its own base ref (seon.cluster.registry/base-commit-id)"**. Moves the read to the base ref.
- **`214c61330` "A retired branch has no merge base: base-commit-id reads only a base ref the store's branches hold"**.
- **`4cfa03eab` "Relaunch base: a relaunched attempt forks its predecessor's end commit from that commit's own branch toward main, so it inherits the task's merge base"**.
- **`c8215bfe1` "merge-main! opens the chain branch for its one action; a chain's final merge reads the base ref merge-main! advances"**.
- **`a76a9c141` and `b064a3e8f`**: the S5 evidence. `b064a3e8f`'s message names git's diff(merge-base, HEAD) as the target model.

Docs that designed a merge base or base ref: `docs/research/agent-platform/2026-10-10/review-merge-base.md` (the t-based design, changes requested); `docs/research/agent-platform/merge-task-design-2026-09-26.md` (matched grep for merge base; **not read in this dig**); `docs/seon/schedule/cards/DIAL-8.md` (the `10000` bound on `merge-base`, to be deleted with the walk).

### 4. Rulings the design must keep

- **`docs/seon/schedule/cards/TASK-CHAIN.md`.** No text names `:seon.task/merge-base`. The only merge-base line is L538, which deletes "the per-attempt main merge-base write". L33: "No automatic relaunch: budget spent, a bail and a refused merge all go to root." L14: "A single task is a chain of one." The rule "relaunch keeps the predecessor's base" lives only in commit `4cfa03eab`, not in TASK-CHAIN's text. **Flag:** the TASK-CHAIN text does not state the relaunch rule.
- **`docs/research/agent-platform/2026-10-15/branch-model-rulings.md`.** L22: "No rebase. Drift is reconciled once, three-way, at the merge." L56: "the entities the branch changed since its merge base (git's". Ruling 9, L90: "each with its `base/` merge-base ref, a pointer, not a copy".
- **`AGENTS.md` and `CLAUDE.md`** (identical text). "Merge is a root task": "`merge!` takes the whole delta since the merge base and commits only when it is green" (L341-346). L370: "the merge base (git's untrusted-contributor model: an agent never pushes)". Section header L298: "The system: one JVM, many realities". L180 names git's merge-base term as the merging vocabulary.
- **`docs/research/agent-platform/2026-10-15/merge-closure-plan.md`.** L32: "`changed` the branch's own changes since its merge base (git's three-way". L93: "A non-chain assignment's stored `:seon.task/merge-base` is a second representation of the base ref; it retires with per-assignment merges (TASK-CHAIN S3b)."

**Flags for root.** Stored merge-base copies on attempt rows, which the plan deletes, are named by:
- `c264da25e` (`:seon.task/merge-base` on attempt rows, written by `seon.task/attempt-tx`);
- `4cfa03eab` (relaunch base inherited from the predecessor);
- `d9eb15c05` (`base-t` on the fork entity);
- `merge-closure-plan.md` L93 (named as retiring with TASK-CHAIN S3b).

Root must reconcile these before phase 4 deletes them.

### 5. Other sources that already do this

- **JGit `MergeBaseGenerator` / RevWalk merge-base filter.** Fetch of the master path returned 404, so the class is **unverified**. JGit is not on our classpath: `~/.m2/repository/org/eclipse/` holds only aether, ee4j, jetty and sisu, and `clojure -Spath` output shows no jgit (grep came back empty).
- **Graph libraries** (JGraphT, commons-graph). No match under `~/.m2`.
- **Git itself** (`commit-reach.c`, cited above) is the reference algorithm. Shelling out to git is not a fit for the database, which owns the commit graph.
- **Datahike's own `datahike.versioning`** has no LCA. `branch-history` walks every ancestor, which the plan rules out.

### Root's reconciliation of the R1 flags (2026-10-15, verified against source)

- **GC and `:parents` (`6521ded43`).** Checked against source: `datahike.gc` keeps every commit reachable through
  `:datahike/parents` from a live branch head.
  - A forward merge's extra parent is a main commit, already reachable from main, so it pins nothing new. The
    constraint does not apply to phase 2.
  - What it does apply to is the chain→main merge commit, which `accept-merge!` already writes with the chain head
    as a parent. That pins a deleted chain's history from main. It is an existing, separate question; it is listed
    for phase 4 and not solved here.
  - Upstream #951 (online GC with diff-buf is sound only for linear history) is noted for the same reason.
- **`:commit-graph? false` (`50d4b3ac`).** It is an opt-out that Seon never sets (no use in `src` or `resources`), so
  commit records exist. `datahike-internals.md` walked 1,896 of them on the live store. Phase 1 may still make
  `merge-base` refuse by name on an opt-out store.
- **Ruling 9 ("each with its `base/` merge-base ref, a pointer, not a copy").** The plan keeps the ruling's intent
  and goes further: the pointer is the commit graph itself, so no `base/` ref is needed. Ruling 9 is revised in place
  in `branch-model-rulings.md` in the phase 4 commit that deletes the refs.
- **Relaunch keeps the predecessor's base (`4cfa03eab`).** This falls out of the graph. A relaunched attempt forks
  its predecessor's end commit, so `merge-base(new head, main)` walks back to the same commit. No stored copy is
  needed. Phase C must confirm it on a disposable fork.
- **`:seon.task/merge-base` on attempt rows (`c264da25e`; `merge-closure-plan.md` L93).** It is deleted; every reader
  derives the base from the graph. TASK-CHAIN L538 already deletes the per-attempt write, so no TASK-CHAIN
  amendment is needed beyond the S3b note.
- **`d9eb15c05` `base-t` on the fork entity.** It is deleted: a *t* is a position on one lineage only (finding 1).

## C: REPL confirmation

(Sonnet writes here: each claim of the model above, its form, its measured value and its verdict.)

## Implementation notes

(Opus writes here per phase: the decisions made, anything that differed from the plan and why.)

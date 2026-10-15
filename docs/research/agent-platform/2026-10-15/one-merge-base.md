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
| 0 | Sonnet | `seon.cluster.db-flow-test/every-agent-start-has-a-task` never exits and eats every check's 600 s | ends within its bound | hang fixed `734e0aed7` (cause `b77f17413`: `var-process` built the world outside its catch on stop); the test now exits in ~12 s but is still red: its `chain-world` teardown calls `seon.cluster.registry/retire-branch!` with a vector branch (contract refusal), plus 2 assertions (root started without an assignment). `retire-branch!` is in a phase-2/4 file, so this is folded into phase 2 |
| R1 | Haiku | dig: prior art (git commit-graph, upstream Datahike, earlier Seon attempts and why), rulings the design must keep | n/a | done, b2ad9cd44 |
| R2 | Haiku | call-site ledger, src + test + script + resources + docs/skills, each row verified | n/a | done, a41868d3a |
| C | Sonnet | REPL confirmation of the approach on real data before any implementation (section below) | numbers below | done, claims 1-5 confirmed with corrections (this commit) |
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

Lane `omb-c1` (Sonnet), 2026-10-15, at `e7b19b902`. Every probe is in
[one-merge-base-probes.clj](one-merge-base-probes.clj) (the file's head says which are A and which are B). Two
environments, neither wrote to default:
- **A**: default's JVM through `eval_clj` (jvm mode), read-only, bounded (claims 2 and 3 on the real chain
  `:chain-f1f6dc8e6652`). Requests allocated 418-447 MB each (`:seon.cost/alloc-mb`).
- **B**: a throwaway JVM on the fork (`cd reference-code/datahike && clojure -M:test`, socket REPL), opened on an
  APFS clone (`cp -cR data/store`, 40 s) of the real store for the walks, because the cold walk allocates 2.4 GB, above
  the 1 GB stop line for default; and on in-memory stores for everything that writes. Both are gone.
  Main's branch is `:cluster-default` (not `:db`); the clone's main head was `6ad05319`, the chain head
  `6ad047ad`. Timings are one run each; "thread" MB is `ThreadMXBean`, which undercounts konserve reads by about half.

| # | Claim | Verdict |
|---|---|---|
| 1 | The merge base comes from the commit graph | **confirmed**, with two corrections: it is the fork point, not the base ref, and `:datahike/created-at` cannot order the walk |
| 2 | Own change by diff gives 24 | **confirmed** for a base that is main at the last forward merge; **refuted** for the base the graph answers today (701) |
| 3 | The address-skipping diff is correct | **confirmed** (0 mismatches over 1,183 attributes and 4 pairs); the identities must be read from the entities, not from the diff's sym datoms |
| 4 | A forward merge as a merge commit yields the right base | **confirmed**; relaunch confirmed; criss-cross yields two bases |
| 5 | Datahike's own `merge!` path works | **confirmed**; the record carries parents, not a generation |

### Claim 1: the merge base from the commit graph

Form (B, probes "claim 1"): `paint-down` is git's `paint_down_to_common` (flags P1, P2, stale, result; queue by commit
instant) over `node-of`, which reads a commit record with `(konserve.core/get kstore cid nil {:sync? true})` and keeps
`[:meta :datahike/parents]`, `[:meta :datahike/updated-at]`, `:max-tx`, `[:config :branch]`.

| | value |
|---|---|
| answer | one base, `6acf6fed-6b55-5297-9f31-af2c339648cb`: written on `:cluster-default` (main), max-tx 536872008, txInstant 2026-10-14 19:05:01 +07 (12:05:01 UTC) |
| cold (1,975 record reads) | **1,451 ms, 2,419 MB thread** |
| warm (cid to node map) | **2.4 ms, 2.3 MB** |
| commits on each side of it | chain 1,126 (0 with more than one parent), main 848 (1 merge commit), 1,975 pops in all |

- **It is what history says.** The chain's first own commit (`6acf6fef`) has exactly one parent, `6acf6fed`: the chain was
  forked there. All 1,126 chain commits are `:chain-f1f6dc8e6652` single-parent commits, because `merge-main!` wrote
  plain transactions. So the graph still places the base at the original fork point, as expected, and not at the S5
  base ref.
- **The S5 base ref is not on the lineage** (finding 4 of `datahike-internals.md` confirmed): `6ad046d3` is a commit
  on `:base/chain-f1f6dc8e6652` with max-tx 536872709 whose only parent `6ad046cc` is a main commit; it is an ancestor of
  neither main nor the chain, and `merge-base(base-ref, main)` is `6ad046cc`, 122 pops. Its database content equals main at
  `6ad046cc`, which is why claim 2 can use it as the stand-in.
- **Correction 1: `:datahike/created-at` is constant.** It is the database's creation date (2026-10-10T03:05:48Z) on every
  commit; only 2 distinct values over 1,975 commits. The per-commit instant is `:datahike/updated-at` (ms). Even that ties
  within a millisecond, so phase 1's generation number is not optional: the ordering must not be a clock. (My first walk
  ordered by `created-at` and returned 713 results; with `(= 3 flags)` excluding stale and `updated-at` it returns one.)
- **Size of the parents record (phase 1).** Per commit, uncached, 60 heads each:

| read | ms each | MB thread each |
|---|---|---|
| whole record, `get` then `:meta` | 0.63 | 1.15 |
| `konserve.core/get-in` on `[cid :meta :datahike/parents]` | 0.63 | 1.15 |
| `konserve.core/get-meta` (header only; has no parents) | 0.14 | 0.16 |
| sidecar key `[:omb/commit-graph cid]` `{:parents :at}` (1,975 keys) | **0.05** (92-110 ms cold for all) | 0.19 (372 MB for all) |

  `get-in` does not help: konserve decodes the whole value. The sidecar makes the cold walk **1,451 ms to about 100 ms
  and 2,419 MB to 372 MB** (13x and 6.5x), and a map of the sidecars walks in 3.6 ms. The remaining 0.19 MB per tiny read is
  konserve's per-read overhead, so a sidecar per commit is the right shape but a bulk read (one key holding a branch's
  parent records) would be cheaper still; that is a phase 1 measurement.
- **Not run on default**: the cold walk (2.4 GB). The A-side evidence for the graph is the head/parents/base-ref reads
  (11 ms, 10.9 MB request) that agree with the clone.

### Claim 2: own change by diff (24 identities)

Form (A, probes "claim 2"): `pss-diff` of the base's and the head's `:aevt` limited to `:seon.program/definition-digest`;
the entities of the differing datoms; their identities `[:seon.fn/sym|:seon.test/sym|:seon.ns/name|:seon.schema/key value]`
read from each database; `seon.program/digest-map` of base and head over those identities; keep the identities whose
digest differs. Base = `(seon.cluster.registry/base-commit-id chain-db :chain-f1f6dc8e6652)` = `6ad046d3`.

| phase (warm, thread) | ms | MB |
|---|---|---|
| `commit-as-db` of the base | 0.64 | 1.6 |
| diff (digest attribute, 289 nodes by attribute; 662 only in base, 682 only in head) | 13.5 | 20.5 |
| identities from the 1,344 differing entities | 105 | 132 |
| `digest-map` on both over 682 candidates | 16.6 | 42.9 |
| **total, in the form** | **about 136** | **about 197** |

Request level for the whole probe: 205 ms, 432 MB. The first (cold) run: 515 ms in the form, 829 MB request. **Result: 24
identities**, the set `own-changes-notes.md` expects: 8 `seon.error/*` functions (`alias-candidates`, `alias-target`,
`candidate-calling-form`, `config-expectation-present-generator`, `core-namespace`, `library-calling-form`,
`library-names`, `nearest-calls`), 14 `:seon.ns/name` rows (12 `my.attempt-*`, `seon.error`, `user`), `[:seon.schema/key
:tmp/probe]`, and the test `seon.error-test/an-unresolved-alias-name-is-answered-with-its-nearest-core-name`. Verdict: **confirmed**.

- **Refuted for today's graph base.** With the commit graph's own base (`6acf6fed`, the fork point) the same pipeline
  answers **701** changed identities (677 entities on the head side), not 24: `merge-main!` wrote main's declarations into
  the chain as plain transactions with fresh entity ids, so against the fork point they are the chain's change. The graph
  answers 24 only once the forward merge is a merge commit (claim 4): the merge base then is main at the merge, and the
  carried rows equal the base's digests. This is the whole reason phase 2 precedes phase 3.
- **Correction 2: read identities from the entities.** Taking the identities from the diff of all five attributes (the sym
  datoms) answered 302 candidates and 20 changed, not 682 and 24: an entity edited in place changes its digest datom and
  leaves its sym datom out of the diff. The identity must be looked up on the entity (as the form does). The lookup is now
  the dominant cost (105 ms, 132 MB); phase 3 should read it with one AEVT slice per identity attribute or lookup only the
  entities whose sym datom is not in the diff.
- The diff itself is not the saving here: the plain alternative for five attributes costs the same order (claim 3).

### Claim 3: the diff is correct

Form (A and B, probes "claim 3"): `pss-diff` (address-skipping; range pruned by attribute; every slot datom a candidate;
every candidate decided by membership in both full sets) against a plain symmetric difference of `(d/datoms db {:index :aevt
:components [attribute]})` as `[e a v tx]` tuples.

| pair | attributes | differing datoms | equal? |
|---|---|---|---|
| base ref vs chain head (A and B) | 5 program attributes (29,446 / 29,486 datoms) | 944 / 984 | **yes** |
| main head vs chain head (A and B) | same | 944 / 984 | **yes** |
| base ref vs main head (A and B) | same | 0 / 0 | **yes** (main moved no declaration since the last forward merge) |
| main head vs chain head (B) | **all 1,183 schema attributes**, 711,069 datoms, 424 attributes differ | 50,429 / 68,877 | **yes, 0 mismatches** |
| 6 + 8 random histories (B, memory) | `:k/n`, `:k/who` for 144 commit pairs each | n/a | **yes, 0 wrong of 2,016** |

- The slot path is exercised only on the real store (98 to 251 slot datoms per diff, e.g. the digest attribute pair has
  `:extras 251`); the in-memory fixtures never produced a slot (`:slot-datoms-seen 0`, also with a 3,000 row tree), so the
  property test does not cover slots and the real-store equality is the evidence.
- **Cost**, base vs chain: one range per attribute, 289 nodes, **about 20 ms and 27 MB warm** (the first call in a JVM
  with cold nodes: 114 ms in A); the plain slices cost 41-50 ms and 46 MB. One contiguous range from the first to the last
  attribute spans every attribute alphabetically between them: 2,069 nodes, 195 ms/181 MB warm, 1,758 ms/777 MB cold. So
  **the bound must be one range per attribute**. Over all 1,183 attributes the diff took 18.4 s and 5.5 GB in B (plain
  slices included); that is the unbounded case and not a design target.
- Honest size of the win: for program attributes (29k datoms) the diff is about 2x cheaper than the plain slice, not
  proportional-to-change, because the rows main moved into the chain differ by entity id (944 and 984 datoms for 24
  identities). The saving the diff buys is correctness without a *t*, and cost that does not grow with history length.

### Claim 4: a forward merge as a merge commit

Form (B, probes "claims 4 and 5"): in-memory fork store. `main = connect`, `chain = branch! main`, transactions on both,
`@(d/merge-db! chain {:parents #{(d/commit-id @main)} :tx-data [...]})` (the call `seon.db/transact!` makes for a request
with `:parents`), more transactions on main and chain. Walk: `merge-base` = `paint-down` plus the redundant-ancestor
filter, with generation numbers (1 + max parent) added in the reader.

| step | merge base of (chain head, main head) |
|---|---|
| 3 transactions on both, no merge | the fork commit |
| 4 the forward merge | the chain commit's parents are `#{previous chain commit, main's commit}`; base is main's commit `m4` |
| 5 more transactions on main (2) and chain (1) | **`m4`** (generations: fork 3, `m4` 4, main head 6, chain head 7) |
| relaunch: `d/branch!` from the chain's end commit, 3 transactions | **`m4`** again, nothing stored |
| relaunch from a chain with no forward merge yet | the original fork commit, for chain and relaunch alike |
| criss-cross: main merges the chain's commit while the chain merges main's | **two bases**, both parents; `paint-down` returns both, neither is redundant |

- **Property**, 14 random histories (transactions, branches, cross merges; 3,237+ head pairs in the larger run): the
  generation-ordered walk equalled a brute-force lowest-common-ancestor set for **every pair (0 wrong)**; the
  instant-ordered walk was also right (0 wrong), but the memory store's commits are milliseconds apart; the real store's
  1 ms ties and the constant `created-at` argue for the generation. 752 of 3,237 pairs had several bases, so criss-cross is
  not a corner of random merging.
- **Criss-cross in Seon.** Seon only merges the chain into main once, at the end, and main into the chain forward; a
  crossing needs main to take a commit of a chain that is still taking main's. Phase 1 still needs a rule: git's
  `merge-base` without `--all` leaves it unspecified and its merge machinery builds a virtual base. The simplest rule
  that is deterministic: the base with the greatest generation, ties refused by name (a typed error).
- Verdict: **confirmed**, including the relaunch case (no stored copy), which settles root's flag on `4cfa03eab`.

### Claim 5: Datahike's own `merge!` path

- `@(d/merge-db! branch-connection {:parents #{commit-uuid} :tx-data ...})` on the `:chain` branch connection worked
  as Seon calls it: the report has `:db-before :db-after :tx-data :tempids :tx-meta :datahike/commit`; the commit's
  `[:meta :datahike/parents]` is `#{previous head, the given commit}`; the parent was a **commit uuid of another branch
  (main)**, not a branch keyword, which is what `merge-main!` has in hand.
- The record of the merge commit has 15 keys (`:config :meta :hash :max-tx :max-eid :op-count :merkle-roots
  :cache-revisions :schema-meta-key` and the six index keys; the real store adds the fused roots) and `:meta` keys
  `:datahike/commit-id :created-at :updated-at :id :parents :version`. **Missing for the walk: a generation.** Nothing else:
  parents are there, `:config :branch` says which branch wrote it. Phase 1 adds the generation (and the small parents
  record) and nothing else.
- Not exercised here: the basis fence `:datahike/expected-basis-t` on the merge request, and GC (`datahike.gc` follows
  `:datahike/parents`; root's reconciliation already covers it: main's commit is reachable from main).

### What changes in the plan

1. Phase 1 orders the walk by a stamped generation, not by `:datahike/created-at` or the clock, and ships the sidecar
   (cold walk 1,451 ms to about 100 ms measured) with `merge-base` returning a set that the Seon reader narrows by
   greatest generation, refusing ties by name.
2. Phase 2 is the only thing that makes the answer 24: until `merge-main!` writes a merge commit the graph base is the
   fork point and the pipeline answers 701. The S5 attempt after the restart must show the merge base moving to a main
   commit.
3. Phase 3 reads identities from entities, bounds the diff with one range per attribute, and measures the lookup, which
   dominates (105 of 136 ms).


## Implementation notes

(Opus writes here per phase: the decisions made, anything that differed from the plan and why.)

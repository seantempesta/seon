---
title: "How much does replaying history cost? Inventory, measurements and a verdict on phase 3"
type: research
date: 2026-10-15
status: measured by lane replay-impact (Sonnet); no source edits. Seon code at 3dfd4a9a6 (phase 2 slices 1-2), fork 593a8276, on an APFS clone of data/store (main head 6ad06cb4, chain :chain-f1f6dc8e6652 head 6ad047ad). Phase 2b (4ca233c04) was read at f516872f2 and its fork-point walk re-measured by simulation.
---

# How much does replaying history cost?

**Short answer.** "Replaying history" is two different things, and only one is expensive.

1. **Walks over commit records** (`seon.db/commit-at-t`, `basis-revisions`, `datahike.api/merge-base` through `seon.cluster.source/merge-base`, `seon.cluster.registry/fork-point`) read one stored commit record per commit. This is where the money goes: 0.5 ms and 1.2 MB per commit (thread; about 2.4 MB request-level) for the old walkers, 0.055 ms and 0.22 MB per commit for the new graph-record walk.
2. **Scans of the history index** (`d/history`, `d/since`, `entities-changed-after`) cost little for one attribute (2 ms for 26k datoms) and a lot for a `d/since` over the whole index (0.2 to 0.55 s, 0.4 to 1 GB), proportional to the store's total history, not to the change.

Phase 3's structural diff replaces group 2 and the t-based readers. It does not touch the merge-base walk, which is now the largest recurring cost on agent paths. `program/changed-identities` from a t is already as cheap as the diff (5 to 22 ms warm, both).

Method: `git archive` of HEAD for `src` and `resources`, the repo's `clojure -Spath` classpath, a throwaway JVM (6 GB, socket REPL) opened on a `cp -c -R` clone of the store (second `cp -cRn` pass; heads are the live ones at clone time). Each Seon walker was called with HEAD's code on raw Datahike values of the clone. Times are warm (second call) unless marked cold; "MB" is the calling thread's allocation (`ThreadMXBean`), which undercounts konserve reads by about half. One run each, a busy machine; read ratios, not decimals. The clone's commit graph records were backfilled once by a transaction on each branch (clone only).

## 1. Inventory (HEAD, src and script)

Cost units: c = commits walked (c_t since a t; c_m main side and c_b branch side since the merge base), D = datoms in the whole store's history, H_a = history of one attribute, k = changed entities. Frequencies are from reading the callers; "per new pair" means per new (branch head, main head) pair, since `merge-base` is cached by the pair and either head moves often.

| Symbol | Walks | Proportional to | Called from (frequency) | Hot? |
|---|---|---|---|---|
| `seon.cluster.source/merge-base` -> `datahike.api/merge-base` | graph records, both sides from the heads to the base | c_m + c_b | `merge-main!` (per forward merge); `agent/done-judged` (per attempt end and start); `compare-branch`/`prepare-merge!`/`accept-merge!` (per merge compare); via `branch-merge-base`: `context/change` (per context render, i.e. per turn), `step/changed`, `test/change-base` + `branch-delta` (per test step), `registry/retained-before` (per collection, once per work branch) | **hot** |
| `seon.cluster.source/branch-merge-base` | same, for the branch's target | same | as above | **hot** |
| `seon.cluster.registry/retained-before` (calls `branch-merge-base` and, at f516872f2, `fork-point` for every non-head branch) | one merge-base walk per work branch | branches x (c_m + c_b) | `seon.maintenance` collection (per collection) | hot in cost, rare in frequency |
| `seon.db/basis-revisions`, `seon.db/changed-attributes` (t arity), `seon.test.runner/program-written-since?` | first-parent commit records back to a t | c_t | `seon.test/record-run` (per recorded run, only when the earlier `or` clauses fail); `seon.cluster.source` results verdict (per prepare-merge!) | warm path, small c_t |
| `seon.db/commit-at-t` | first-parent records back to a t | c_t | only `seon.merge/merge-commit` (fallback when `:seon.task/merge-commit` is absent) | cold, deletable |
| `seon.cluster.registry/first-parent-where`, `fork-point` (f516872f2) | first-parent records of the branch, each read once and cached by commit (`::commit-step`) | new commits only; cold = c_b | `merge/assignment-row` (per merge), `task` fork-point read, `retained-before`, `agent/fork-attempt!` | warm: 0.7 ms; cold once |
| `seon.merge/merged?` | one AVET lookup per walked commit | c_b, microseconds each | `assignment-row` predicate, `merge-onto` | no |
| `seon.program/changed-identities` (t or base arity) via `declaration-changed-identities` | `d/since` of one attribute's history, then a pull | k + H_a | `merge-main!` (main since the base), `squash-merge`, `agent/done-judged`, `context/change`, `test/branch-delta` through `own-changed-identities` | per request, cheap |
| `seon.program/own-changed-identities` | `changed-identities` from the fork entity's base-t, then two digest maps | k | `done-judged`, `context/change`, `test/branch-delta`, `source/compare-branch` | per turn and test step, cheap |
| `seon.db/entities-changed-after` | one attribute's history, tx filter | H_a | `changed-identities` (4-arity), `test.runner/reach-changed-entities` (cached by revisions) | cheap |
| `d/since (d/history v) t` then `d/datoms :eavt` | whole temporal index with a tx filter | D | `source/changed-identities` (per publication), `source/merge-accepted` (per merge), `task/changed-handles` (adoption), `script/astra_cost_handoff` | per publish and merge; **expensive** |
| `d/since (d/history db) t` queried by attribute list | per attribute, tx filter | sum of H_a over the listed attributes | `test/changed-definition-symbols` and the `changes-since` queries in `test/select` (per incremental test step) | per test step; floor 60 ms |
| `d/history` queries by one attribute or one entity | the entity's own history | H_a or less | `turn` wake datoms (per turn), `test.runner/admitted-members`, `task/task-handles`, `source/deleted-identities` (per publication), `step.design`, `my.program` diff views, `render.web` | per turn, small |
| `d/parent-commit-ids` + `d/commit-as-db` | one parent read | 1 | `source/main-parent` (per accepted merge) | no |
| `seon.db/as-of` | filtered view, no copy | none | `test`, `test.runner`, `context`, `render.web` | no |

Not found at HEAD: any `tx-range`, `branch-history` or `datahike.versioning/branch-history` walk in `src` or `script`.

## 2. Measurements now

### Commit-record walks (old walkers), by depth

`commit-at-t` and `basis-revisions` on main, c commits back from the head (thread ms / MB). The chain gives the same line.

| c | commit-at-t | basis-revisions |
|---|---|---|
| 10 | 6 / 15 | 6 / 15 |
| 100 | 56 / 136 | 50 / 136 |
| 400 | 355 / 525 | 241 / 525 |
| 800 | 584 / 993 | 428 / 992 |
| 1,600 | 1,054 / 1,924 | 844 / 1,924 |

Fit: basis-revisions 0.52 ms and 1.2 MB per commit; commit-at-t 0.55 ms and 1.2 MB. Linear, no knee.

### merge-base (fork phase 1, graph records present)

| case | ms | MB |
|---|---|---|
| legacy, no records, cold (2,826 reads) | 1,538 | 1,651 |
| legacy, each call again (nothing cached) | 674 | 1,651 |
| with records, the S5 pair (chain 1,126 commits, main side 740) | 99 to 105 | 423 |
| with records, chain head replaced by a commit 1,100 back | 43 to 49 | 214 |
| p50 / p90 / max over 128 live work branches vs main's head | 46 / 102 / 375 | |
| all 128 work branches, one pass, new pairs (what `retained-before` does) | **8,821** | **26,844** |
| the same pass again (`seon.cache` hit) | 149 | 233 |

Fit with records: 0.055 ms and 0.22 MB per commit above the base on either side. The cache key is the pair (branch head, main head). A transacting turn moves the first, any main commit moves the second, so most reads in a new turn or step miss.

`branch-merge-base` on the chain: 896 ms cold, 0.36 ms on a cache hit.

### Phase 2b's walk (simulated from the HEAD source)

`fork-point` on the chain: 1,565 ms and 1,392 MB cold (1,126 own commits, one full record read each, cached by commit by `seon.cache`), 0.7 ms warm. A new chain commit adds one read (about 1.4 ms). `merged?` over all 1,126 commits: 4 ms. So `assignment-row` is a once-per-commit cost, paid again only after the 512 MB cache evicts or default restarts.

### History-index scans

| Operation | ms | MB | Note |
|---|---|---|---|
| `d/datoms (d/history main) :aevt :seon.program/definition-digest` (26,369 datoms) | 1.7 to 2.8 | 5 to 8 | one attribute, whole history |
| `db/entities-changed-after` (digest attribute), any depth | 0.7 to 7 | 0.7 to 4 | flat in the window |
| `program/changed-identities t main bounds`, depth 10..1,600 (0..1,380 changed) | **5 to 22** warm (first call 130 to 500, one 4,157) | 8 to 36 | grows with k |
| the C1 diff of the same pairs (5 program attributes) | **1 to 19** | 1.6 to 32 | 2 to 335 nodes visited; first call 5 to 52 |
| `own-changed-identities` on the chain (701 identities) | 20 to 26 warm, 1,718 first | 32 | |
| `d/since (d/history) t` then `d/datoms :eavt` (3.8k to 170k datoms since) | **218 to 552** warm, 575 to 2,390 cold | **365 to 1,063** | scans the whole temporal index; not flat only because the result grows |
| `test` change discovery shape: `q` over `d/since` history for 13 attributes | 56 to 128 | 98 to 151 | flat floor 60 ms at depth 10, so it is H_a, not the change |
| `source/deleted-identities` | 73 warm, 4,334 first | 94, 3,107 first | history join, per publication |
| `task-handles` retracted | 2.6 | 3.3 | |

Rates today: main has 1,703 own commits in 23.9 h (71 per hour overall, 88 per hour in the last 6 h, 103 in the last hour). The S5 chain has 1,126 own commits in 15.3 h (73 per hour).

## 3. Projections

Linear fits, no change in kind, and the commit rates above. Chain = one work branch's own commits; the main side of a merge base is main's commits since the fork. The projection for main assumes each agent adds commits to main at about today's rate per agent-hour, which is **not measured**: today's 71 to 103 per hour comes from a handful of lanes and root's merges and test records.

| Quantity | Today | Branch with 10x history (11k own commits) | Main after a day of 10 agents (10x today's rate: about 17k commits per day) |
|---|---|---|---|
| `merge-base` per new pair (0.055 ms, 0.22 MB per commit) | 100 ms, 423 MB (1.9k commits) | 0.6 s, 2.4 GB (11k own + 0.7k main) | a chain living 5 h sees about 4k main + 0.4k own: 0.25 s, 1 GB |
| the same with per-commit record reads (old cost, 0.5 ms) | n/a | 5.7 s | 2.2 s |
| `basis-revisions` / `commit-at-t` from an old t | up to 0.85 s at 1,600 | 5.7 s, 13 GB at 11k | cost follows commits since the t: unchanged for a recent t |
| `retained-before` merge-base pass (branches x per-branch walk) | 8.8 s, 26.8 GB for 128 branches | n/a | if 10x the branches each walks 10x main: up to 100x, minutes. Unverified; bounded by retiring branches |
| `fork-point` cold, per chain | 1.6 s, 1.4 GB once | 15 s, 14 GB once | once per commit, cached; every restart pays it for the live chains |
| `d/since` + `:eavt` scan | 0.22 to 0.55 s | grows with D (10x history): 2 to 5 s, 4 to 10 GB | same |
| test-step `since` queries (60 ms floor) | 60 to 130 ms | 0.6 to 1.3 s | same, by H_a |
| `changed-identities` from a t, and the diff | 5 to 22 ms | grows with k only | grows with k only |

Growth in plain words: the merge-base and commit-record walks grow with the number of commits between the heads and the base. Index scans grow with the store's history. The diff and the t-based changed-identities grow only with the change.

## 4. The diff alternative on the same pairs

C1's address-skipping diff (`pss-diff-by-attr`, five program attributes, AEVT) on the pairs "commit c back, main head":

| c | diff ms / MB | nodes | changed-identities (t) ms / MB | identities |
|---|---|---|---|---|
| 10 to 50 | 0.7 / 1.6 | 2 | 5 / 8 | 0 |
| 100 | 7.5 / 9.8 | 98 | 7 / 11 | 231 |
| 400 | 9.9 / 15.9 | 162 | 8 / 12 | 328 |
| 800 | 14 / 26 | 284 | 11 / 19 | 774 |
| 1,600 | 19 / 32 | 335 | 18 to 21 / 27 to 36 | 1,380 |

The two are the same cost. The diff does not beat a warm t-based `changed-identities`; its value is that no t crosses a lineage, and that it replaces the scans that do not behave this way (`d/since` + `:eavt`, 218 to 552 ms; the test floor of 60 ms). Cold first calls differ only by which one touches the nodes first (diff first: 52 then 11 ms; changed-identities first: 15 then 47 ms).

The diff needs the two values, which means the merge base, which is the 100 ms walk. It does not remove that.

## 5. Ranked: cost x frequency on agent paths

1. **`merge-base` / `branch-merge-base` on a new pair.** 46 ms median, 100 ms for a long chain, 200 to 420 MB each, on every turn and test step that follows a commit on either side. Today it is the largest recurring walker (a few hundred ms a turn at most). The diff does not help it.
2. **`retained-before`'s per-branch merge-base pass.** 8.8 s and 27 GB per collection at 128 work branches (cold pairs). Rare, but the largest single number and it grows with branches x main's growth.
3. **`d/since` + `:eavt` scans** (`source/changed-identities`, `merge-accepted`, `task/changed-handles`): 0.22 to 0.55 s, 0.4 to 1 GB per publication or merge, growing with total history. The diff is a direct fix (13 to 19 ms).
4. **Test-step `since` queries**: 60 to 130 ms and 100 to 150 MB per incremental step, flat with the window. The diff or a per-attribute revision test removes it.
5. **`basis-revisions` through `program-written-since?`**: 0.5 ms per commit since a run's basis; typically 5 to 50 commits, so 3 to 30 ms. A cold tail if a run's basis is old.
6. **`fork-point` cold**: 1.4 ms per commit once, 1.6 s for the S5 chain, again after each restart or eviction.
7. **`commit-at-t`**: one rare fallback, up to 0.5 s.
8. **`changed-identities` from a t, `own-changed-identities`**: 5 to 25 ms. Not a cost problem.

## 6. Verdict

**Is phase 3 worth doing now?** For correctness, yes (it removes the last t that is read across lineages and lets `:seon.branch/base-t` be deleted). For cost, only for a part of it. Of the eight walkers above, the diff converts group 2 (3 and 4) and removes group 1's t readers (5 and 7). It does nothing for 1, 2 and 6, which are where agent time goes.

Concretely:

- **Do in phase 3:** replace the `d/since` + `:eavt` scans in `source/changed-identities`, `source/merge-accepted` and `task/changed-handles` by the diff of the two values they already open (`before`/`after`). Expected gain: 0.22 to 0.55 s and 0.4 to 1 GB per publication or merge down to about 15 ms and 20 MB, and the cost stops growing with history. Same for the test step's `changes-since` queries (60 to 130 ms down to a few ms).
- **Do not expect a gain from converting `program/changed-identities` or `own-changed-identities`** to the diff: they are already 5 to 25 ms. The reason to convert is deleting `base-t`, not speed.
- **Delete rather than convert:** `seon.db/commit-at-t` and `merge/merge-commit`'s fallback to it (`:seon.task/merge-commit` is recorded in the merge's own transaction; sole caller), `script/astra_cost_handoff` (a one-off), and `basis-revisions` once `program-written-since?` asks for `program-revisions` equality or the diff of the tested value and the head instead of walking to the run's t.
- **Fix the merge-base walk separately; this is the larger gain, and it is not phase 3.** Two ideas, neither built: (a) a cheaper per-commit read (graph record bulk read, or the sidecar, 0.22 MB down to about 0.2 KB per commit, derived); (b) an incremental base: a non-merge child of a head whose base is known has that base, and main advancing along its first parent does not move the base, so a new pair needs no walk unless the branch merged main (unverified). (b) also fixes `retained-before`'s 8.8 s, because it needs each branch's base once, not once per main commit.
- **`fork-point` should read the same small graph records as `merge-base`, not `head-record`** (a full 2.4 MB decode per commit): 1.6 s and 1.4 GB cold for the S5 chain would drop to tens of ms.

Expected gain if all of this is done: per publication/merge about 0.3 to 0.5 s and 0.5 to 1 GB saved now and a cost that stops growing; per turn/test step 50 to 200 ms saved (merge-base walk, test floor); per collection 8.8 s down to about 0.15 s.

## Limits

- One run per cell, a busy machine, warm JVM after the first call. Thread allocation undercounts konserve by about half (request-level is about twice).
- The merge-base numbers use fork 593a8276's records; `omb-gc`'s `backfill-commit-graph!` and shared record reader (00c58326) were not measured. The clone's records were backfilled by two transactions; a store never touched since phase 1 pays the legacy walk (1.5 s, 1.65 GB) once per lineage.
- `fork-point` and `assignment-row` were simulated from the f516872f2 source (same `k/get` per commit, memoized by commit), not run through Seon's `seon.cache`.
- The 10x and ten-agent projections extrapolate measured linear fits with today's commit rates; main's rate under ten agents is an assumption.
- The frequencies come from reading callers, not from a profile of a live agent run. `seon.profile` evidence on a live chain is the check before committing to the order.

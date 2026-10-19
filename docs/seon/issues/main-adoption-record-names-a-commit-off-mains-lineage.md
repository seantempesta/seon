---
type: issue
status: open
severity: medium
created: 2026-10-20
tags: [issue, adoption, acquisition, cost, runtime]
---

# Main's loaded program is a `current-src` commit, so main's cold overlay plan diffs two lineages

Found by lane runtime-fix3 (root's question after defn-perf), 2026-10-20; reproduced and measured by lane
adoption-lineage on default `81ab24b8f` (pid 94300, restarted 2026-10-19T21:59Z), JVM-mode reads as root.

## What holds (corrected 2026-10-20)

The adoption record is not wrong data. `seon.sci.eval/loaded-commit-id` reads `:seon.source/commit-id` on the
cluster entity, and it names the `current-src` head, which equals `@seon.sci.eval/loaded-identity`
(`6ad692f3-c02f-594e-b38a-86377b360ce4` after the 21:59 restart; the earlier reading `6ad683c7` was the head of the
20:55 restart, not a 10-18 commit). It is off `cluster-default`'s lineage by design:

- `seon.cluster.source/publish!` builds every publication on the `current-src` branch (`d/force-branch!` onto the
  prior `current-src` commit).
- `seon.cluster/adopt-publication!` and `seon.cluster/development-source-refresh!` copy the changed rows into the
  cluster branch (`seon.cluster/adopt-entities!`, upsert by identity), so the cluster branch allocates its own entity
  ids; `seon.cluster/record-adoption!` then records the `current-src` commit. Every other consumer of that record
  (`seon.cluster/adoption-guard-tx`, `seon.cluster/published-changes`, `seon.cluster.source/changed-identities`, the
  task merge, `seon.test` test worlds) needs it to name a `current-src` commit.
- The cluster branch shares entity ids with `current-src` only up to its fork (`seon.cluster/source-base!`, last
  nuke). Each publication since adds rows under different ids on the two lineages, so the divergence grows with every
  restart until the next nuke: 1,397 entities after the 20:55 restart, about 1,450 after the 21:59 one.

Introduced by `03bd7cfc9` (acquisition compares against the commit the adoption record names) with `76d4f4a68`
(`seon.sci.eval/plan-identities` diffs entity ids, "proportional to what differs", which holds only within one
lineage).

## Measured cost (default, 2026-10-20)

`seon.db/datoms-diff` loaded vs main over `seon.sci.eval/plan-attributes` (only-loaded/only-main): `:seon.fn/sym`
241/241, `:seon.test/sym` 96/96, `:seon.ns/name` 11/17, `:seon.program/definition-digest` 908/912,
`:seon.schema.admission/source` 478/482, `:seon.fn/host-bound?` 244/244. By content the plan is empty except four
agent-authored namespaces only main holds (`my.agents.root`, `sentinel.scratch`, `seon.loaded`,
`seon.p8-probe-test`): no function is overridden, added or retracted.

| operation on main | ms | allocated MB |
|---|---|---|
| `seon.sci.eval/derive-overlay-plan` (cold) | 63-66 | 89-102 |
| `seon.sci.eval/plan-identities` loaded vs main | 26 | 49 |
| `plan-identities` within one lineage (main vs main) | 0.19 | 0.15 |
| `seon.sci.eval/overlay-plan` warm (cache hit) | 0.15 | 0.14 |

`seon.cache/status` after the restart: `:seon.sci.eval/overlay-plan` 4 hits, 1 miss (222 ms load). So a warm request
on main does NOT pay the diff: the plan is keyed by main's program revisions and loaded commit and folds forward on
main's own lineage. The cross-lineage derive runs once per restart (and per eviction or new branch with no head entry
whose program differs from a cached one), and its cost grows with the number of publications since the last nuke.

## Options (owner ruling needed: adoption semantics and a new attribute)

1. **Leave it; correct the docstring** (smallest). Warm cost is already 0.15 ms; the cold derive (~65 ms, ~100 MB)
   is once per restart and resets at each nuke. Correct `seon.sci.eval/plan-identities`'s claim to say it is
   proportional to the cross-lineage divergence for the loaded commit.
2. **Record the cluster branch's own loaded basis (recommended if the cold cost matters).** At adoption, after
   `adopt-entities!`, converge the cluster branch to the published program (adopt every file-admitted identity whose
   content differs from the published commit, not only those changed since the prior adoption: default's program
   derives from files) and record that cluster-branch commit under a new attribute beside `:seon.source/commit-id`
   (new word in the vocabulary). `loaded-program` reads it, so main's and every branch's overlay diff stays on one
   lineage (0.19 ms, 0.15 MB) and the 4 agent namespaces stay in the plan. Costs one cross-lineage comparison per
   adoption (~30 ms, once per restart). Sacrifice: adoption overwrites a file-derived row main changed by a merge that
   was never written back; a new schema attribute and the `seon.test` loaded-program reader change with it.
3. **One lineage for publication and cluster.** Publish onto the cluster branch (or rebuild the cluster branch from
   `current-src` at each restart), so ids never diverge. Largest change: many clusters share one `current-src`, and
   the cluster branch's own facts would have to be carried over.

Regression for 2 (to write red with the ruling): after `adopt-publication!` on a branch of the live store,
`seon.sci.eval/loaded-program` of the cluster database is on the cluster branch's lineage and
`seon.sci.eval/plan-identities` of it against the cluster database is empty.

## Reproduction (JVM mode, root)

```clojure
(let [database (seon.db/db (seon.cluster.boot/connection "default"))
      loaded (#'seon.sci.eval/loaded-program database)]
  (into {} (for [a @#'seon.sci.eval/plan-attributes]
             [a (mapv count (seon.db/datoms-diff loaded database :aevt [a]))])))
```

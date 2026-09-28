---
type: research
lane: store-growth-opus
date: 2026-09-28
status: read-only audit; no src edits
evidence: JVM-mode MCP scans of default (pid 45948) history; tmp/write-audit/{by-ns,by-attr,by-writer,bytes-by-kind,churn}.txt
---
# Store write audit, 2026-09-28

The disk growth comes from how often Seon commits, not from how much data it stores.
`:cluster-default`'s whole history since the store was rebuilt holds only 1,930,956 datoms,
spread over 17,524 transactions between 2026-09-26 16:47Z and 2026-09-28 16:07Z (1.97 days).
Keeping that history cost 56.7 GB of index nodes. Throwaway test branches wrote a further
164.8 GB, which the collection on 2026-09-28 reclaimed.

- 94% of commits are test-member recordings, one transaction per member, with a median of
  8 datoms each.
- Each commit rewrites index nodes in every index. The median is 23 new nodes and 1.57 MB,
  whatever the commit's size.
- Batching the test recordings, and not rewriting unchanged program facts, removes most of
  the retained growth.

Method: read-only scans at the default REPL (lane `store-growth-opus`). Scratch outputs are
in `tmp/write-audit/`. The only write was one disposable branch for the size probe (§4),
which was retired afterwards.

## Ranked table

"Retained" means history the plain collection keeps forever. Bytes per commit are measured
on the last 1,500 `:cluster-default` commits (§4); the totals scale those means to every
commit of the same kind. There were 1.97 days of history.

| # | Writer or attribute class | Commits/day | Datoms/day | Retained bytes/day | Consumer | Producer | Smallest fix at the producer |
|---|---|---|---|---|---|---|---|
| 1 | **Test-member recording** (`seon.test.member`, `.failure`, `.run`). There are 16,487 members over 929 runs; 15,580 members were touched by 2 transactions each. | ~8,400 | ~116,000 | **~14 GB** (1.59 MB mean per member commit) | Yes: selection (`src/seon/test.clj:688`, `:804`) and the var render (`src/seon/render/var.clj:72`) | `src/seon/test.clj:2430` calls `runner/commit-results!` once per member inside `run-batch`, and `:2410` calls `runner/record-exit!` per member. Both land in `src/seon/test/runner.clj:1868` and `:1922` (runner.clj is held by the Astra fixer). | **Batch.** Commit a batch's results in one transaction at the end of the batch (or every N members). Put the exit in the same transaction when it was already observed. A run averages 17.7 members, so commits drop about 17×, saving about 13 GB/day. Also, members are never retracted: all 16,487 are still current, while readers want the newest per test. |
| 2 | **Throwaway test-member branches** (unlinked). Each member branch writes an attempt agent and turn, seeds and fixtures. | not measured | not measured | **~84 GB/day written** (164.8 GB reclaimed by collection); none retained | None after the branch is unlinked | `src/seon/test.clj:183-200` (`attempt-request-context`: one commit, 350-440 ms per member branch), plus seeds (`:162`) and fixture writes | Not measured by writer; this needs a probe of one member's branch writes. The per-commit cost (§4) applies to every one of them. It is reclaimed daily now (commit `7a9fe878d`). |
| 3 | **Program publication**: whole-set rewrite of cardinality-many facts (`:seon.fn/calls`, `/call-arities`, `/keywords`, `:seon.ns/requires`, `:seon.schema/references`). There were 93,889 retract-and-reassert pairs of the same value in the same transaction. | ~34 (publication commits with `seon.fn` or `seon.program` on top) | ~680,000 (1.33M datoms over 133 publication and adoption commits) | **~5 GB** (a publication commit averages 150 MB; the maximum was 237 MB) | Yes: the call graph and reach | `src/seon/program.cljc:1360-1378` (`replacement-tx`). For any changed attribute it emits `:db.fn/retractAttribute` and then re-asserts the whole desired set, called from `src/seon/fn.clj:3694-3712`. | **Write only on change.** For a cardinality-many attribute, retract the removed members and add the new ones (a set difference) instead of `retractAttribute` plus the whole set. `program.cljc` is not held. It is a small change, but it has publication semantics, so root should route it. |
| 4 | **Position churn**: `:seon.fn/form-span` (+55,684/−47,419), `:seon.program/analyzed-source-digest` (+58,096/−49,831, up to 8,332 entities in one transaction), and lint entities (`:seon.lint/*`, +72,814/−59,893) | inside #3 | ~130,000 | inside #3 | Spans and digests: yes. Lint: weak (only `report-analysis-warnings!`, `src/seon/cluster.clj:2258`). | `src/seon/fn.clj:1641` (the lint id is `(id/id [owner finding-type row col])`, so a shifted line re-identifies every finding below it). The digest and span rewrites go through the same `fn.clj` publication path (held). | Give lint findings an identity without row and column (move row and column to values), so an unchanged finding survives a line shift. Find out why `analyzed-source-digest` changes for 8,332 functions in one publication: probably a global input to the digest. Recompute only functions whose own input changed. |
| 5 | **`:seon.test/adoption-identities` on the transaction entity** | ~57 | ~30,900 (60,802 over 112 transactions; median 210, max 7,464) | inside #3 | **None in `src/`.** Only the producer, `test/seon/adoption_entities_test.clj` and two measurement scripts read it. | `src/seon/cluster.clj:3070` (held) | **Stop writing** it, or name its consumer. AGENTS: "Every stored fact names its consumer or is deleted." |
| 6 | **Read evidence and vector cells per evaluation** (`datahike.read/revision` and `/dependency-plan`, `:seon.db/source-argument-position`, `:seon.cluster.eval/read-evidence`, `:seon.db.vector/*`) | ~20 | ~80,000 (about 25,000 per attribute; one transaction wrote 10,365) | **~0.5 GB** (vector commits average 54 MB) | Yes: read currency (`src/seon/db.clj`) and vector reads (`src/seon/cluster.clj:1289`) | `src/seon/db.clj:3849` (vector cells) and the evaluation settlement (held `db.clj`) | Keep. The single 10,365-evidence transaction is one evaluation's reads, which is worth a look (store by reference or deduplicate plans). |
| 7 | Agent, runtime, turn, error, schedule and process rows | ~350 | ~3,000 | ~0.7 GB | Yes | many | These are dominated by the per-commit cost (§4). Batch where a writer emits more than one commit per event. |

The totals roughly account for the 56.7 GB kept on disk. The remainder is commits on the
other roster branches (`:tfp-sel*`, `:design-1`, `:dogfood-*`) and the heads.

## 1. Commits by writer

Transaction provenance is almost absent. Only 12 of the latest 2,000 transactions carry
`:seon.db/process`, and none carries an operation or agent. So writers were classified by
the attribute namespaces they write (`tmp/write-audit/by-writer.txt`):

| Kind (top two namespaces) | Commits | Datoms | Median datoms |
|---|---|---|---|
| `seon.test.member` | 13,963 | 111,965 | 8 |
| `seon.test.member` + `seon.test.failure` (either order) | 1,624 | 46,338 | 20-32 |
| `seon.test.run` + `seon.test.member` (either order) | 929 | 70,850 | 27-99 |
| `seon.agent` (+ `seon.runtime`) | 287 | 1,463 | 1-9 |
| `seon.db.process` + `seon.db` | 100 | 200 | 2 |
| `seon.error` + `seon.error.occurrence` | 99 | 2,627 | 23 |
| `seon.test` + `seon.source` (adoption records) | 68 | 58,761 | 487 |
| `seon.fn` + `seon.program` / `seon.lint` / `seon.fn.arity` (publications) | 63 | 1,246,684 | 6,086-464,747 |
| `datahike.read` + `seon.cluster.eval` (evaluation settlements) | 27 | 47,203 | 2,045 |

Commits per hour follow the test rounds, with up to 1,678 commits an hour (2026-09-27 23Z)
and near zero between rounds. The daily 02:00-03:00 schedule adds three commits.

Size distribution of all 17,524 transactions (datoms): 0-1: 262, 2-5: 181, **6-20: 15,117**,
21-100: 1,520, 101-1,000: 335, over 1,000: 109.

Missing provenance is itself a finding: no transaction names its writer, so this table had
to infer writers from the attributes. The smallest fix is for `seon.db/transact!` (held
`db.clj`) to put `:seon.db/process` and the calling operation on every transaction's
`:tx-meta`.

## 2. Datoms by attribute namespace (history, asserted and retracted)

Full lists are in `tmp/write-audit/by-ns.txt` and `by-attr.txt`.

| Namespace | Asserted | Retracted |
|---|---|---|
| `seon.fn` | 521,057 | 231,568 |
| `seon.test.member` | 168,774 | 0 |
| `seon.program` | 86,940 | 66,045 |
| `seon.lint` | 72,814 | 59,893 |
| `seon.db.vector` | 74,442 | 8,531 |
| `seon.fn.arity` | 53,928 | 26,464 |
| `seon.test` | 72,114 | 4,975 |
| `seon.fn.binding` | 42,495 | 19,814 |
| `datahike.read` | 49,970 | 11,134 |
| `seon.fn.argument` | 33,494 | 20,695 |
| `seon.test.run` | 30,717 | 0 |
| `seon.test.failure` | 24,134 | 297 |

There is no rendered text, blob payload or profile in the top 30: `seon.render` has 1,469
datoms and `seon.error` 1,848.

## 3. Churn

- **Unchanged rewrites:** 96,216 datoms were retracted and re-asserted with the same value;
  93,889 of them in the same transaction. They come from `replacement-tx`'s
  `retractAttribute` of a cardinality-many attribute (table row 3). Top attributes:
  `:seon.fn/call-arities` 40,517, `:seon.fn/calls` 36,962, `:seon.fn/keywords` 13,292,
  `:seon.ns/requires` 1,602, `:seon.schema/references` 1,064
  (`tmp/write-audit/churn.txt`).
- **Entities rewritten wholesale:** lint findings, whose identity includes row and column,
  and every function's `form-span` and `analyzed-source-digest` when a file's lines shift or
  a global digest input changes (row 4).

## 4. Write amplification

Default's index is `:datahike.index/persistent-set` with `{:branching-factor 4096}` and
`keep-history? true`. So every commit writes a new leaf, plus its path, in each touched
index: EAVT, AEVT and AVET, and their three temporal counterparts. Leaves hold up to 4,096
datoms, at 60-255 KB each.

**Measured on production.** On the last 1,500 `:cluster-default` commits, new nodes per
commit were counted against the previous commits, the way the collector marks them:

- The last 300 commits: median 23 nodes (1.57 MB), mean 5.4 MB, maximum 237 MB.
- An 8-datom test-member commit: median 1.47 MB, mean 1.59 MB.

**Measured on a disposable branch**, using raw `d/transact`:

| Write | New files | Bytes |
|---|---|---|
| 1 datom | 5-6 | 0.55-0.70 MB |
| 100 datoms, one commit | 7 | 0.79 MB |
| 10 datoms as 10 commits | 54 | 6.05 MB |
| 10 datoms as 1 commit | 7 | 0.80 MB |

So the cost is almost entirely per commit and barely depends on the number of datoms.
**Batching helps about 7.6× for small writes.** A smaller branching factor would shrink each
rewritten leaf, at the price of deeper trees. It is a store-level Datahike setting and would
need its own measurement.

## Routing (not fixed here)

These are ranked by retained bytes. Nothing was fixed beyond this document: the top
producers live in held or semantic files.

1. **Batch test-member recording:** one transaction per batch, with the exit folded in
   (`src/seon/test.clj:2410-2440`; `runner.clj` is held). Also retract or supersede
   earlier members of the same test.
2. **Set-difference writes for cardinality-many attributes** in
   `src/seon/program.cljc:1360-1378`.
3. **Lint identity without row and column** (`src/seon/fn.clj:1641`, held), and find out
   why 8,332 functions' `analyzed-source-digest` change in one publication.
4. **Delete `:seon.test/adoption-identities`** (`src/seon/cluster.clj:3070`, held) unless
   a consumer is named.
5. **Provenance on every transaction** (`seon.db/transact!`, held), so the next audit can
   group by writer rather than by inferred attribute.
6. **Probe one test member's branch writes** to split the ~84 GB/day of reclaimable garbage
   by writer.
7. **Measure a smaller `:branching-factor`** on a scratch store; it is a Datahike-owner
   decision.

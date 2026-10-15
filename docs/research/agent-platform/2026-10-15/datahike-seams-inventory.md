---
title: "Datahike seams inventory at HEAD a76a9c141"
type: research
date: 2026-10-15
status: gathered and verified by grep (read-only); no judgement
---

# Datahike seams inventory (HEAD a76a9c141)

Lane `dh-inventory`, gather-and-verify only. Read-only `git grep`/`git show` at HEAD; no REPL, no source edits.
Every row is verified against the HEAD text quoted. "Candidate only" marks a Datahike function that may
relate to a Seon hand-rolled path; nothing here judges whether it should be replaced. Full mechanical hit list
(file, line, enclosing definition, matched line) was generated to the lane scratchpad and is reproducible with
the commands given under each table.

## 1. Readers and writers of a branch's base

Notion of base: **base ref** (konserve head record `:base/<branch>` holding a commit id, `registry/base-branch`),
**attempt row** (`:seon.task/merge-base` on an assignment entity), **fork commit** (commit a branch was forked at,
found by walking history), **t** (`:seon.branch/base-t` in a fork entity, or an advanced t recorded on the target),
**adoption record** (the program entity set a branch is built on).

| Symbol | Notion | Quote (HEAD) | Callers |
|---|---|---|---|
| `seon.cluster.registry/base-branch` | base ref (keyword) | `(keyword "base" ...)` of the work branch | `registry/base-commit-id`, `registry/advance-base!`, `registry/fork!`, `registry/retire-branch!`, `registry/retire-unclaimed!` |
| `seon.cluster.registry/base-commit-id` (1- and 2-arity) | base ref | "The commit retained by a work branch's base ref, or nil" | `seon.cluster.agent/fork-attempt!`, `seon.cluster.agent/apply-lifecycle!`, `seon.cluster.agent/done-judged`, `seon.cluster.source/merge-base`, `seon.cluster.source/accept-merge!`, `seon.merge/assignment-row`, `seon.merge/merge-tx`, `seon.task` (relaunch, ~l.1095), `seon.turn/fork-commit`, `seon.context/change`, `seon.step` (~l.57) |
| `seon.cluster.registry/held-branches` | base ref (existence) | `(set (head-record (:store (db/schema-database database)) :branches))` | `registry/base-commit-id` (2-arity default), `registry/retire-unclaimed!` |
| `seon.cluster.registry/advance-base!` | base ref (write) | `(d/force-branch! database (base-branch branch) (d/parent-commit-ids database))` | `seon.merge/merge-main!`, `seon.cluster.source/accept-merge!` |
| `seon.cluster.registry/fork!` | base ref (write) + fork entity | `(branch! {... :seon.store/branch (base-branch branch) ...})` | `seon.cluster.agent/fork-attempt!`, `seon.cluster.source` (fork paths) |
| `seon.cluster.registry/fork-of` | t (reads fork entity) | "`branch`'s own fork entity in `database`, or nil" | `seon.cluster.source/branch-base-t`, `registry/branch-fork-entity`, `seon.my.agent/create-branch!`, `seon.my.program/head-comparison` |
| `seon.cluster.registry/branch-fork-entity` | t (creates fork entity) | `{:seon.branch/fork-id (id/id) ... :seon.branch/base-t (db/basis-t database)}` | `seon.cluster.registry/fork!` |
| `seon.cluster.registry/advanced-base-t` | t (advanced by merge) | `(db/q '[:find ?t . ... [?e :seon.branch/base-t ?t]] target fork-id)` | `seon.cluster.source/branch-base-t`, `registry/target-advanced-base-t` |
| `seon.cluster.source/branch-base-t` | t | "The base-t attribute toward `head` ... else the fork's own" | `seon.cluster.source/merge-base`, `seon.cluster.source/compare-branch`, `seon.merge/merge-main!` |
| `seon.cluster.source/merge-base` | base ref + t guard | `(if-let [commit (registry/base-commit-id branch branches candidate)] (d/commit-as-db branch commit) ...)` | `seon.merge/merge-main!`, `seon.cluster.source/accept-merge!`, `seon.cluster.source/compare-branch` |
| `seon.merge/assignment-row` (defn-) | attempt row, falls back to base ref | "`:seon.task/merge-base`, else the base ref" | `seon.merge/merge!` and merge helpers |
| `seon.merge/merge-tx` (3- and 5-arity) | attempt row | `(d/datoms database :eavt a :seon.task/merge-base)` | `seon.merge/merge!` |
| `seon.cluster.agent/write-merge-base!` (defn-) | attempt row (write once) | `(write! connection [[:db/add (:db/id row) :seon.task/merge-base commit]])` | `seon.cluster.agent/start-attempt!` |
| `seon.cluster.agent/apply-lifecycle!` | attempt row (write) + base ref (read) | `:merge-base (write! connection [[:db/add a :seon.task/merge-base (or (registry/base-commit-id ...))]])` | `seon.cluster.agent/apply-actions!` |
| `seon.cluster.agent/lifecycle-facts` | attempt row (read) | `(db/pull database [... :seon.task/merge-base ...])` | `seon.cluster.agent` lifecycle path |
| `seon.cluster.agent/fork-attempt!` | fork commit + base ref | `(registry/fork! {... :seon.db/db (if (uuid? fork-point) (db/commit-database database fork-point) database) ...})` then `(registry/base-commit-id database branch)` | `seon.cluster.agent/start-attempt!` |
| `seon.cluster.agent/done-judged` | base ref + t (done rule) | `base-commit (registry/base-commit-id at-end branch)`; `(program/changed-identities (db/basis-t base) at-end source/merge-bound)` | `seon.cluster.source/done-refusals` path |
| `seon.test/fork-commit` (defn-) | fork commit (walk) | "the nearest ancestor of its head written on another branch" | `seon.test/change-base` |
| `seon.test/change-base` (defn-) | fork commit or adopted commit | "for any other branch the commit it was forked at" | `seon.test` branch-delta path (~l.2162) |
| `seon.test/branch-delta` | t + program diff | `(program/changed-identities (db/basis-t base) candidate source/merge-bound)` | `seon.step` (~l.61), `seon.test` (~l.2162) |
| `seon.turn/fork-commit` | base ref | `(registry/base-commit-id database (get-in database [:config :branch]))` | `seon.context/change`, `seon.step` |
| `seon.context/change` (defn-) | attempt row, fork commit | `(db/q '[:find ?m . ... [?a :seon.task/merge-base ?m]] ...)`; else `(turn/fork-commit database)` | context render |
| `seon.cluster.source/changed-identities` | adoption record / published report | "Publication report identities, or history since the cluster's adopted commit" | `seon.cluster/published-changes` |
| `seon.cluster/adoption-record` (defn-) | adoption record | writes the adopted program entities | `seon.cluster/development-source-refresh!` |
| `seon.merge/adoption-record-tx` (defn-) | adoption record (write on merge) | "the program the branch is now built on" | `seon.merge/merge-main!` |
| `seon.program/changed-identities` | t (diff since base t) | "the one history walk" (docstring of `seon.test/branch-delta`) | `seon.merge/merge-main!`, `seon.context/change`, `seon.test/branch-delta`, `seon.cluster.agent/done-judged`, `seon.cluster.source/compare-branch`, `seon.merge` (~l.100) |
| `seon.my.root/merge-requests` | attempt row (read) | `(db/q '[:find [(pull ?a [... :seon.task/merge-base ...])]])` | `seon.my.root` tools |

Reproduce: `git grep -n -E 'base-commit-id|advance-base!|base-branch|:seon.task/merge-base|fork-commit|change-base|branch-delta|branch-base-t|merge-base|adoption-record|changed-identities|fork-of' HEAD -- src test`.
Test-only readers (about 60 hits, mostly `db_flow_test`, `merge_test`, `registry_test`, `turn_test`) use the same notions; not tabulated row by row.

Observation: `held-branches` reads the konserve `:branches` record directly, while `branches` reads `d/branches`
on the store's connection (`seon.cluster.registry/branches`). Both answer "does the branch exist" from one fact.

## 2. Direct Datahike calls outside `src/seon/db.clj`

Counts from `git grep` on `src`, `script` (tests summarised at the end). `d/` is `datahike.api`; `k/` is `konserve.core`.

| Namespace | Datahike / konserve function(s) used (count) | Example call (HEAD) |
|---|---|---|
| `seon.cluster.source` | `d/commit-as-db` (11), `d/release-materialized-db` (7), `d/datoms` (6), `d/force-branch!` (2), `d/history` (2), `d/parent-commit-ids` (2), `d/since` (2), `d/db`, `d/q`, `d/release` | `(d/commit-as-db (:seon.store/connection-object store) commit-id)` |
| `seon.cluster.registry` | `d/q` (5), `d/release-materialized-db` (5), `d/branch-as-db` (3), `d/commit-as-db` (3), `d/branch!` (2), `d/force-branch!` (2), `d/branches`, `d/delete-branch!`, `d/gc-storage`, `d/parent-commit-ids`, `d/pull`, `k/get` (1) | `(d/force-branch! database (base-branch branch) (d/parent-commit-ids database))` |
| `seon.cluster` | `d/datoms` (13), `d/release-materialized-db` (8), `d/commit-as-db` (2), `d/history`, `d/pull`, `d/q`, `d/release` | `(source/changed-identities ...)` callers and `d/commit-as-db` in `source-database` |
| `seon.cluster.store` | `d/connect` (4), `d/release` (4), `d/branches` (2), `d/create-database`, `d/database-exists?`, `d/db`, `d/delete-database`, `k/get` | `(d/connect (assoc (:config @main-connection) :branch branch))` in `acquire-branch!` |
| `seon.cluster.wake` | `d/q` (6), `d/datoms` (2), `d/db` (2), `d/listen`, `d/unlisten` | `(d/listen ...)` |
| `seon.cluster.boot` | `d/pull`, `d/q`, `d/release-materialized-db` | |
| `seon.merge` | `d/datoms` (12), `d/entity` (1) | `(d/datoms database :eavt a :seon.task/merge-base)` |
| `seon.schema` | `d/datoms` (8), `d/q` (1) | |
| `seon.maintenance` | `d/commit-as-db`, `d/commit-id`, `d/datoms`, `d/release-materialized-db`, `k/bget`, `k/keys` | |
| `seon.render` | `datahike.db/committed-value-identity` (4) | |
| `seon.call-preparation` | `d/db`, `d/query-attribute-dependencies`, `datahike.db/committed-value-identity` | |
| `seon.sci.eval` | `d/commit-as-db` (3) | |
| `seon.turn` | `datahike.api/pull` (1) | |
| `seon.schedule`, `seon.eval.drive` | `d/listen`, `d/unlisten` | |
| `seon.blob` | `k/bget`, `k/bassoc`, `k/bget-range`, `k/exists?` | `(k/bget ...)` |
| `seon.cluster.boot`, `seon.cluster.registry` | `k/get` on the store | `(k/get konserve key nil {:sync? true})` in `head-record` |
| `script/seon/operator.clj` | `datahike.api/branch!`, `datahike.api/delete-branch!`, `datahike.api/release` | |

Tests: `test/` has roughly 500 direct `d/` or `k/` call sites (`db_test` 82, `registry_test` 38, `data_shapes_test` 24,
`store_test` 23, `merge_test` 21, and many single-digit files). Reproduce:
`git grep -n -E '\((d|k)/[a-zA-Z!?*<>=+.-]+' HEAD -- src script`.

## 3. Places Seon may reimplement Datahike (candidate only)

| Seon path (HEAD) | What it does | Datahike function under `reference-code/datahike/src` that looks related (candidate only) |
|---|---|---|
| `seon.db/commit-at-t` | Walks the first-parent lineage with raw `k/get` on each commit record until `:max-tx = t` | `datahike/api/impl.cljc:380` `parent-commit-ids`; `datahike/versioning.cljc:468` `parent-commit-ids`; `api/impl.cljc:185` `history` |
| `seon.test/fork-commit` and `seon.test/commit-facts` | Walks commits to find the one written on another branch (`commit-facts` cached under `::commit-facts` by commit id) | `datahike/versioning.cljc:468` `parent-commit-ids`; `versioning.cljc:504` `branch-as-db` |
| `seon.program/changed-identities` (program.cljc:875) | History diff between two database values by datoms | `datahike/api/impl.cljc:148` `since`; `api/impl.cljc:185` `history`; `datahike/api/impl.cljc:383` `commit-as-db` |
| `seon.db/as-of`, `seon.db/as-of-key`, `seon.db/carried-projection` | Re-reads an as-of view and derives a view identity | `datahike/api/impl.cljc:148` `since`; `datahike/db.cljc` `committed-value-identity` (already used) |
| `seon.cluster.registry/held-branches`, `seon.cluster.registry/branches` | Two readers of the branch list: raw konserve `:branches` record vs `d/branches` | `datahike/versioning.cljc:181` `branches` (its comment: branches are the fact, lines 182-189) |
| `seon.cluster.registry/branch!`, `fork!`, `retire-branch!`, `advance-base!` | Branch lifecycle and retained base refs via `d/branch!`, `d/delete-branch!`, `d/force-branch!` | `datahike/versioning.cljc:279-320` `delete-branch!`; `datahike/api/impl.cljc:393` `branch-as-db` |
| `seon.cluster.store/open-branch!`, `acquire-branch!`, `release-branch!`, `branch-open-monitor` | Open/close bookkeeping; `open-branch!` refuses a second connection via `connections/*connections*` | `datahike/connections.cljc` (`active-connection`, `*connections*`); `d/connect` reference counting (the `acquire-branch!` docstring says Datahike already counts references) |
| `seon.cluster.registry/base-commit-id` | Reads `:meta :datahike/commit-id` from a konserve head record | `datahike/api/impl.cljc:332` `branches`; `datahike/versioning.cljc` branch head records |
| `seon.cluster.source/commit-database` and `seon.db/commit-database` | Opens a commit as a database value | `datahike/api/impl.cljc:383` `commit-as-db`; `versioning.cljc:474` `commit-as-db` |
| `seon.test/branch-delta` and `seon.cluster.source/merge-base` | Base commit chosen by the base ref plus a t guard | `datahike/versioning.cljc` `force-branch!` semantics (candidate only; base ref is a Seon convention) |

## 4. Counts

| Item | Count | How (HEAD) |
|---|---|---|
| `seon.cluster.registry/on-branch` uses | 14 in `src` and `script`; 11 in `test` | `git grep -n 'registry/on-branch' HEAD -- src script \| wc -l` (and `-- test`). Enclosing definitions include `seon.cluster.agent` (bail-chain!, advance-chains!, merge-chains!, chain-bails, write-chain-attempt!), `seon.task` (7 sites), `seon.merge/merge-main!`, `seon.cluster.registry/apply-config-to-branches!` |
| `seon.db/commit-database` uses | 14 as `db/commit-database` in `src`; 31 across `src` and `script` including `source/commit-database` | `git grep -n 'db/commit-database' HEAD -- src script \| wc -l` |
| `seon.db/as-of` uses | 9 as `db/as-of` in `src` and `script`; 13 with `d/as-of` and bare `(as-of ` | `git grep -n -E 'db/as-of' HEAD -- src script \| wc -l` |
| Open branches the store holds | Not measured (no REPL). Two ways to count, both returning names: `(count (seon.cluster.registry/branches store))` is the branches the store holds; `(count (seon.cluster.registry/held-branches database))` is the same fact read raw. Connections open in this process: keys of `datahike.connections/*connections*` whose `:branch` is set. | Run from an agent's REPL on the store or from `bin/seon status` output if it reports branches. Nothing run here. |

## Verification limits

- Quotes are from `git show HEAD:...`; the working tree is not read.
- Caller lists come from `git grep` plus enclosing-definition awk; a few callers reached through `:refer` or bare names may be missing. Each row says so where the list is partial.
- Datahike candidates are names found by grep under `reference-code/datahike/src`, pinned to the submodule checkout. They were not checked for semantic fit.

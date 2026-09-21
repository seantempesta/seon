---
type: research
status: data pack (facts only; no recommendation, no plan, no ordering)
created: 2026-09-21
tags: [data-pack, publication, adoption, operator, clj-kondo, clj-reload, one-jvm]
---

# Data pack — publication, adoption, operator (lane B1 area)

Everything below was re-verified in this session at `HEAD 6d7192c22`
(branch `steward-platform`) with the working tree DIRTY: the preserved lane
draft is applied to `src/seon/fn.clj` (+102/-24), `src/seon/cluster.clj`
(+26/-8) and two tests (`git diff --stat`, this session). **All `src/seon/fn.clj`
and `src/seon/cluster.clj` line numbers below are WORKING-TREE lines**, which is
also the basis the audit used.

Vendored gitlinks this session: clj-kondo `57252e07`, clj-reload `61c6fa7`
(1.0.0), datahike `006e634a`, sci `fcbd8862`.

One live read was taken (MCP `eval_clj`, `jvm`, `read_only`, cluster `default`,
489 ms): **481,640 EAVT datoms · 4,600 `:seon.fn/sym` rows · 407 `:seon.ns/name`
rows · 53 identity attributes**. Nothing else was run: no gate, no `bin/seon`.

---

## 1. Audit citations re-verified

`deletion-audit-publication-operator-2026-09-21.md`, every `file:line` in §1–§5.

| Audit citation | Verified at HEAD | Note |
|---|---|---|
| `fn.clj:34-38` `report-index-progress!` | ✅ `:34-38` | 14 occurrences in `fn.clj` |
| `fn.clj:40-45` progress budget/stride | ✅ `progress-line-budget :40`, `progress-stride :43` | |
| `fn.clj:106-110` `sha-256` | ✅ `:106` | |
| `fn.clj:1593` `currently-failing-functions`, `:1610` `functions-without-tests` | ✅ both | |
| `fn.clj:1799-1805` `arity-mismatches` re-export | ✅ `:1799-1805`, body is `(db/arity-mismatches database)` | datahike audit §2 row |
| `fn.clj:1807-2001` output-path family | ⚠️ `output-path-report` is at **`:1964`**, not `:1807`; the audit's span start is unverified | |
| `fn.clj:2116-2158` `build-artifact` | ✅ `:2116` | |
| `fn.clj:2162` `artifact-by-path`, `:2175` `manifest-function-symbols` | ✅ both | |
| `fn.clj:2187-2233` `manifest-data`/`replace-manifest-artifacts` | ✅ `manifest-data :2187` | |
| `fn.clj:2234` `analyzed-artifacts` | ✅ `:2234` (private) | |
| `fn.clj:2414` `row-by-identity`, `:2424` `scalar-upsert-rows`, `:2437` `full-rebuild`, `:2443` `plan-file-change`, `:2552` `rows` | ✅ all five | |
| `fn.clj:2663-2760` `backfill-contract-facts!` | ✅ `:2663` | |
| `fn.clj:2858` `commit-index-phase!`, `:2873` `index-tempids`, `:2899` `compile-index-transaction` | ✅ all three | |
| `fn.clj:3047` `reconcile-tx-in`, `:3115` `reconcile-tx`, `:3129` `report-identities`, `:3149` `published-index-rows`, `:3269` `database-manifest`, `:3314` `index!` | ✅ all six | |
| `fn.clj:1093` `assert-clean-analysis!`, `:951` `analyze-forms`, `:327` `first-party-function-symbols` | ✅ all three | |
| `analyzer.clj:223-259` `discard-obsolete-cache-entries!` | ✅ `:223` | |
| `analyzer.clj:570/580/595` `stored-arglists`/`function-stub`/`program-prelude` | ✅ all three; `:557` `namespace-prelude`, `:614` `referenced-program-namespaces`, `:657` `analyze-forms` | |
| `cluster.clj:98-118` `report-source-progress!` | ✅ `:98`; 31 occurrences in `cluster.clj` | |
| `cluster.clj:247-270` `*boot-progress!*`/`boot-phase` | ✅ `:247`, `boot-phase :250` | |
| `cluster.clj:1563` `source-refresh-acquisition-bound-ms` | ✅ `:1563`; body delegates to `operator.state/event-silence-backstop-ms` | |
| `cluster.clj:1619-1644` `source-snapshot` | ✅ `:1619`; `test-input-digest` call at `:1643` | |
| `cluster.clj:1698-1764` `count-installed`/`program-currentness`/`require-coherent-program!` | ✅ `:1698`, `:1710`, `:1733` | |
| `cluster.clj:1765-1798` source artifact trio | ✅ `:1765`, `write-source-artifact! :1771`, `source-artifact :1792` | |
| `cluster.clj:1799-1814` `current-publication` | ✅ `:1799` | |
| `cluster.clj:1815` `full-source-refresh!`; digest re-observation `:1832` | ✅ `:1815`; `:1832` calls `source/path-digests` then `current-source-snapshot` | |
| `cluster.clj:1922-1946` `reload-order`, `:1947-1962` `namespace-requires` | ✅ `:1922`, `:1947`; `reloadable-namespace? :1963` | |
| `cluster.clj:1971-1982` `load-development-definitions!` | ✅ `:1971` | |
| `cluster.clj:1983/1992/2001` adoption-identity trio + `development-namespaces` | ✅ all three | |
| `cluster.clj:2037-2156` `development-source-refresh!` | ✅ `:2037` | |
| `cluster.clj:2157-2173` `require-publication-resources!` | ✅ `:2157` | |
| `cluster.clj:2174-2228` `refresh-source!`, `:2230-2282` `publication-base!` | ✅ `:2174`, `:2230` | |
| `cluster.clj:2284-2296` `process-identity` | ✅ `:2284` | |
| `source.clj:109-124` `path-digests`, `:125-138` `stored-path-digests`, `:139-149` `current`, `:151-162` `database`, `:164-192` `changed-identities`, `:207` `deleted-identities`, `:352-369` `publication-input-digest!`, `:371` `publish!` | ✅ all eight | |
| `fresh_operator.clj` process records `:144 :156 :186 :201 :216 :220 :228` | ✅ `:144 :156 :186 :216 :228` sampled and matched | `:201`, `:220` not sampled |
| `fresh_operator.clj:757` `advertisement-observations`, `:1323` `inconsistency-values`, `:1348` `derive-cluster-truth`, `:1492` `cluster-truth`, `:1695` `repair-actions`, `:1756` `reconciled-truth!`, `:3351` `clear-stale-advertisements!`, `:3363` census, `:3383` discard | ✅ all nine | |
| `fresh_operator.clj:881/937/1018` offline readers | ✅ `offline-roster-form :881`, `test-status-reader-form :937`, `derive-namespace-test-statuses :1018` | |
| `fresh_operator.clj:3008/3046` phase logs | ✅ `phase-log-complete? :3008`, `reset-incomplete-phase :3046` | |
| `fresh_operator.clj:1166` `read-prepl-reply`, `:1609` `live-root-value!`, `:1820` `prepl-eval!`, `:2129` `launch!`, `:2266` `await-advertisement!`, `:2621` `named-init-form`, `:2680` `init-form`, `:2804` `publication-output!` | ✅ all eight | |
| `state.clj:22 :413 :436 :801 :940 :1125 :1199 :1241 :1262 :1457 :1585` | ✅ all eleven sampled and matched | `:436` is `try-file-lock` (the `flock` seam) |
| `operator.clj:65 :70 :168 :180 :551 :919 :1112 :1204` | ✅ all eight | |
| `bin/seon-hook:318 :412 :446 :1688 :1726 :1765 :1774` | ✅ all seven | `:250` is `run-clj-kondo` |
| `bin/seon-hook:1554` `SOURCE_PROGRESS` | ⚠️ the `log!` call is at **`:1557`** | |
| `bin/seon-hook:498,503` schema admission | ✅ `:498` live prepl form, `:503` child `clojure -M:dev` | |
| `instrument.clj:844-858` `current-wrapper?`, `:873` digest | ✅ `:844`, `:873` | confirms the audit's own correction of `:593` |
| `.claude/seon-hook.edn` `:current-source {:enabled false}` | ✅ present, with the 2026-09-20 comment block | |

## 2. Reference-code seams — what each GUARANTEES

| Seam | Verified file:line | Guarantee (facts only) |
|---|---|---|
| clj-kondo `cache-file` | `reference-code/clj-kondo/src/clj_kondo/impl/cache.clj:20-21` | One transit file per (lang, ns): `<cache-dir>/<lang>/<ns>.transit.json`. |
| clj-kondo `from-cache-1` | `.../impl/cache.clj:23-36` | Returns a `:disk` entry whenever the transit file EXISTS; it never reads or validates the recorded `:filename`, so an entry for a deleted/renamed source is still returned, tagged `:source :disk`. Built-in resources are the fallback, tagged `:built-in`. |
| clj-kondo `skip-write?` | `.../impl/cache.clj:38` | Entries whose filename is inside `clj-kondo.exports` (and jar-entry cases) are never written to cache. |
| clj-kondo `to-cache` | `.../impl/cache.clj:65-81` | Overwrites the ns entry unconditionally unless `skip-write?`; must be called inside `with-cache`. |
| clj-kondo `with-cache` | `.../impl/cache.clj:83` | Lock-file protocol with bounded retries and exponential backoff; all cache reads/writes in a run are scoped by it. |
| clj-kondo `load-when-missing` | `.../impl/cache.clj:127-145` | For a required ns absent from `idacs`, loads it from cache and recurses into `:proxied-namespaces`; adds the ns to `:linted-namespaces`. Silently no-ops when there is no cache entry — an SCI-only definition that was never linted produces NO entry and no error. |
| clj-kondo `run!` | `.../src/clj_kondo/core.clj:67` | `:cache false` disables resolution entirely; `:cache-dir` selects the directory otherwise (docstring `:78-84`). The run context carries `:analysis` and `:cache-dir` together (`:196-197`). |
| clj-kondo `reg-var!` | `.../impl/analysis.clj:87-115` | Per var-definition `:analysis` carries `:private :macro :fixed-arities :varargs-min-arity :doc :added :deprecated :test :export :defined-by :defined-by->lint-as :protocol-ns :protocol-name :imported-ns :name-row :name-col :name-end-col :name-end-row :arglist-strs :end-row :end-col` (the `select-some` list at `:92-99`). |
| clj-reload `dependees` | `reference-code/clj-reload/src/clj_reload/parse.clj:122-131` | Inverts `{ns {:requires #{…}}}` to `{ns #{downstream…}}`, keeping only edges whose target is in the supplied set. |
| clj-reload `transitive-closure` | `.../parse.clj:133-147` | Breadth expansion from `starts` over a `{node #{next}}` map; returns a set including the starts. |
| clj-reload `topo-sort` | `.../parse.clj:163-176` | Kahn-style: repeatedly emits `roots` = keys that appear in NO value set, sorted by name, then removes them. **On a cycle it calls `on-cycle`, whose default `report-cycle` (`:150-161`) THROWS `ex-info "Cycle detected"`.** Direction depends on which map is supplied (requires-map vs `dependees`). |
| our `reload-order` | `src/seon/cluster.clj:1922-1946` | Same contract in 25 lines, `sorted-set-by` name order, and on an unsatisfiable set it falls back to `(first remaining)` rather than throwing. `namespace-requires` `:1947-1962` builds its input from `:seon.ns/requires` datoms. |
| the prepl client that exists | `script/seon/fresh_operator.clj:1820-1910` `prepl-eval!` · `:1166` `read-prepl-reply` · `:1609` `live-root-value!` | `prepl-eval!` sends one form to an advertised prepl with a silence bound; `read-prepl-reply` reads ONE terminal value as EDN and refuses carrying the offending text; `live-root-value!` evaluates a form in the process holding an operator root. `launch!` `:2129-2163` is the only cold JVM spawn. |
| Datahike commit identity | `src/seon/cluster/source.clj:139-149` `current` → `registry/branch-commit-id`; datahike audit §6 row cites the fork's own `gc.cljc:24` / `versioning.cljc:457-461` | A branch head is a commit id read from store metadata; `source/database :151-162` materializes any commit id into a database value. |
| MCP `eval_clj` tool declaration | `script/seon/dev/mcp.clj:848-860` | Accepts `code root cluster namespace read_only mode session_id timeout_ms`; `root` selects an arbitrary operator root, `mode` is `jvm` (host io-prepl, no cluster custody bound) or `sci` (mutates the shared ctx). |

## 3. Entry points, adoption paths, progress, bounds

### 3a. Seven publication entry points (one owner: `refresh-source!` `cluster.clj:2174`)

| # | Entry | Call site verified this session |
|---|---|---|
| 1 | `bin/seon init` (no name) | `script/seon/fresh_operator.clj:2706` — `(refresh-source! ~cluster-root)` inside `init-form :2680` |
| 2 | `init --changed` | `fresh_operator.clj:2698` |
| 3 | `init --dev NAME --changed` | `fresh_operator.clj:2702`, plus `development-source-refresh!` `cluster.clj:2037` |
| 4 | the edit hook | `bin/seon-hook:1594` `launch-source-worker-unlocked!` → spawns `bin/seon` at `:1531` → prepl |
| 5 | `seon.operator/publish!` | `src/seon/operator.clj:559-560` (both arities) |
| 6 | `seon.test.cache/prepare-base!` | `src/seon/test/cache.clj:393` (live prepl) and `:395` (`publication-base!`); invoked from `cache.clj:443` and `bin/test:1034` |
| 7 | `seon.bootstrap-drive` | `src/seon/bootstrap_drive.clj:450` |
| + | second publication (store export) | `cluster/publication-base!` `cluster.clj:2230`, also called from `src/seon/test/runner.clj:57-58, 4959` |

`bin/seon init NAME [--force]` is a FORK, not a publication: `named-init-form`
`fresh_operator.clj:2621`. The operator's command table is
`fresh_operator.clj:3656-3666` (`start config export init status open stop down reset logs help`).

### 3b. Four adoption / reload paths

| Path | file:line | Reads |
|---|---|---|
| in-place development adoption | `cluster.clj:2037-2156` | published commit + cluster row `:seon.source/commit-id` (`:2049`) |
| fork from `current-src` | `fresh_operator.clj:2621` `named-init-form` | the same published commit id |
| import of an exported store | `cluster.clj:2230` `publication-base!` → `test/cache.clj:379 prepare-base!` → `:414 ensure-base!` | exported store copy + `manifest.edn` |
| `require :reload` | `cluster.clj:1971-1982` `load-development-definitions!` | `reload-order :1922` + `namespace-requires :1947` |

### 3c. Nine progress mechanisms

| # | Mechanism | file:line | Sites |
|---|---|---|---|
| 1 | `fn/report-index-progress!` | `src/seon/fn.clj:34-38` | 14 occurrences in `fn.clj` |
| 2 | `fn` progress budget/stride | `src/seon/fn.clj:40-45` | one use |
| 3 | `cluster/report-source-progress!` | `src/seon/cluster.clj:98-118` | 31 occurrences in `cluster.clj`; also writes the lock-holder phase |
| 4 | `cluster/*boot-progress!*` + `boot-phase` | `src/seon/cluster.clj:247-270` | boot only |
| 5 | `source/publish!` `:seon.source/progress!` argument | `src/seon/cluster/source.clj:384-385`; emitted at `:447, :449, :472` | the declared argument form |
| 6 | `init-form` progress atom + phase clock | `script/seon/fresh_operator.clj:2755-2790` (binding at `:2786-2788`) | per request |
| 7 | `publication-output!` (re-parses stdout) | `script/seon/fresh_operator.clj:2804-2814` | forwards to the lifecycle owner |
| 8 | lifecycle `:seon.operator.lock/progress` atom | `src/seon/operator/state.clj:1241` `event-silence-backstop-ms`; atom created at `fresh_operator.clj:3652` | silence backstop |
| 9 | hook `SOURCE_PROGRESS` log lines | `bin/seon-hook:1557` | plus `bin/test: SOURCE` printing at `src/seon/test/cache.clj:392` |

### 3d. Seventeen bound declarations

| # | Declaration | file:line | Verified |
|---|---|---|---|
| 1 | `lifecycle-lock-bound-ms` | `src/seon/operator.clj:65` | ✅ |
| 2 | `source-refresh-acquisition-bound-ms` | `src/seon/cluster.clj:1563` | ✅ (delegates to #4) |
| 3 | `lifecycle-lock-timeout-ms` | `src/seon/operator/state.clj:413` | ✅ |
| 4 | `event-silence-backstop-ms` | `src/seon/operator/state.clj:1241` | ✅ |
| 5 | `subprocess-cleanup-ms` (10000) | `src/seon/operator/state.clj:22` | ✅ |
| 6 | `operator-silence-backstop-ms` | `script/seon/fresh_operator.clj:81` | ✅ |
| 7 | `source-preflight-bound-ms` | `script/seon/fresh_operator.clj:244` | ✅ |
| 8 | `publication-bound-ms` | `script/seon/fresh_operator.clj:273` | ✅ |
| 9 | `inventory-bound-ms` | `src/seon/test/cache.clj:34` | ✅ |
| 10 | `inventory-bound-ms` (second) | `src/seon/test/selection.clj:24` (30000) | ✅ |
| 11 | `silence-seconds` | `src/seon/test/bounds.clj:21` | ✅ (audit gave no line) |
| 12–13 | hook `:timeout-seconds 600`, `:call-timeout-seconds 60` | `.claude/seon-hook.edn` `:current-source`, `:review` | ✅ |
| 14 | `population-bound-ms` | `script/seon/dev/clj_kondo.clj:13` | ✅ |
| 15 | `pin-query-bound-ms` | `script/seon/dev/dependency_digest.clj:52` | ✅ |
| 16–17 | `test-timeout-ms 300000`, `changed-test-lock-timeout-ms` | `script/seon/dev/changed_test.clj:19`, `:311` | ✅ |
| + | `projected-delete-ms-per-file` | `src/seon/operator.clj:919` | ✅ (18th, listed by the audit in the same paragraph) |

## 4. The caller-less `fn.clj` vars, confirmed by grep

`grep -rn "seon.fn/<sym>\|(fn/<sym>\|functions/<sym>" src script bin` this
session (production = `src` + `script` + `bin`, excluding the defn itself).

| Symbol | defn | production refs | test refs |
|---|---:|---:|---:|
| `build-artifact` | `fn.clj:2116` | **0** | 28 |
| `rows` | `fn.clj:2552` | **0** | 782 (bare-token count; includes unrelated `rows`) |
| `reconcile-tx` | `fn.clj:3115` | **0** | 20 |
| `plan-file-change` | `fn.clj:2443` | **0** | 4 |
| `artifact-by-path` | `fn.clj:2162` | **0** | 6 |
| `manifest-function-symbols` | `fn.clj:2175` | **0** | 5 |
| `output-path-report` | `fn.clj:1964` | **0** | 3 |
| `currently-failing-functions` | `fn.clj:1593` | **0** | 1 |
| `functions-without-tests` | `fn.clj:1610` | **0** | 1 |
| `arity-mismatches` (re-export) | `fn.clj:1799` | 5 (all `db/arity-mismatches` or this defn) | 14 |
| `backfill-contract-facts!` | `fn.clj:2663` | 1 | 2 |
| `database-manifest` | `fn.clj:3269` | 2 | 2 |
| private helpers of `plan-file-change` | `row-by-identity :2414`, `scalar-upsert-rows :2424`, `full-rebuild :2437` | private, reachable only from `plan-file-change` | — |

`test/seon/fn_test.clj` is 2,967 lines. **The per-symbol test-reference counts
above are grep token counts, not measured deletable line counts**; the audit's
"≈700–900 lines" is an estimate and was not re-derived here.

## 5. Phases of the last measured docstring edit

From `docs/prds/steward-platform/research/one-jvm-redesign-2026-09-22.md`
"Item 5 measured after export repair — 2026-09-23" (`:2479-2510`). Requests
measured by the existing prepl measurement script (`:2489-2495`): one-file
docstring edit 5695.306 → 4320.171 ms; repeat docstring edit **2723.412 ms**;
no change **264.119 ms, head unmoved**; complete publication 195,349 ms; cold
fork command 9,322 ms; boot 12,826 ms.

| Phase of the 2723 ms row | ms | What it reads | Owner |
|---|---:|---|---|
| source build | 553 | `git ls-files` × 2 + toolchain file hashes + gitlink digests, twice per publication | `cluster/source-snapshot :1619`, `test/cache.clj:190 gitlink-digests`, `:217 toolchain-dependencies`, `:45 input-paths`; re-observed at `cluster.clj:1832` |
| analysis / artifact replacement | 475 | changed files' bytes + clj-kondo cache | `fn/analyzed-artifacts :2234`, `analyzer/invoke-kondo :260` |
| publication reconciliation | 303 | previous rows for the selected files | `fn/reconcile-tx-in :3047`, `fn/index! :3314` |
| adoption reconciliation | 406 | identities + namespace dependents | `cluster/development-source-refresh! :2037`, `development-namespaces :2001` |
| instrumentation | 76 | contract digests of changed wrappers | `instrument/current-wrapper? :844`, `arm-var! :873` |
| projection derivation (separate) | 248.417 for 1560 contracts | the whole registry, per newly materialized commit | `source/database :151-162` → `schema/projection-from-database`; `db/carry-derived-projection` does it again |
| committed read probe (same block) | `published-index-rows` 21.890; two indexed identity reads 0.619; selected artifact derivation 28.741; Datahike commit materialization 1.711 | one file selected | `fn.clj:3149`, `source.clj:151` |

Cold publication phases over two seconds, same note (`:740-750`, wall
**178.826 s**): preparation 7.760 s · schema population 2.454 s · program-row
preparation 4.613 s · contract compilation 12.879 / 9.499 / 2.610 s · **final
population compilation 13.226 s** · **transaction 36.068 s (107,049 datoms)** ·
issue indexing 6.124 s · activation seal 3.440 s · branch-head readback 4.875 s.

## 6. What the preserved lane draft does, mechanically

`one-jvm-lane-items-6-8-draft-2026-09-21.patch`, identical to `git diff` in the
tree this session.

| Change | Where | Mechanics |
|---|---|---|
| caller expansion removed from the pre-analysis selection | `cluster.clj:1854` (`selected (when database changed)`) and `fn/build-manifest` `:2348`, `:2378` | the old path unioned `caller-files` into `changed` BEFORE analysis |
| `caller-files` re-signatured | `fn.clj:2274-2305` | takes a `:seon.db/transaction-report`, not `(database changed)`; filters `:tx-data` to `:seon.fn/spec` and `:seon.schema/form` datoms; empty ⇒ `#{}` |
| schema-reference closure | `fn.clj:2286-2296` | fixpoint over `:seon.schema/references` to collect parent schema keys |
| caller symbol selection | `fn.clj:2297-2306` | `:seon.fn.arity/{input,output,guard}-refs` → `:seon.fn/arities` → `:seon.fn/sym`, unioned with changed `:seon.fn/sym`; then `:seon.fn/calls` → caller file path |
| **the second pass** | `fn.clj:3436-3467` inside `index!` | after the declaration transaction commits, it (a) computes `caller-paths`, (b) runs `analyzed-artifacts` a SECOND time over those files against `:db-after`, (c) reads previous `:seon.lint/file` rows, (d) issues a SECOND `db/transact!` on the same connection, (e) unions the second report's identities into `changed-identities`, (f) returns `:seon.db/transaction-report` in the result map |
| `assert-clean-analysis!` hoisted | `fn.clj:2249` | added inside `analyzed-artifacts` |
| `development-namespaces` narrowed | `cluster.clj:2001-2035` | ordinary edits reload ONE namespace; the dependent closure is entered only from roots that are `:seon.fn/macro? true` or `:seon.fn/defined-by clojure.core/defprotocol`; adds a `visited` set to the loop |
| adoption unions two traversals | `cluster.clj:2096-2097` | `development-namespaces` over the previous database AND the current one |
| regressions | `test/seon/cluster/publication_delta_test.clj:52`, `test/seon/fn/publication_cache_test.clj:10` | renamed tests asserting one-namespace reload, macro dependents, zero caller lint on a docstring edit, and `caller-files` on a report |

The landing note records these regressions as **3 errors, not green** (audit §5,
last row). The transaction at (d) is on the still-unpublished scratch
connection opened by `index!`; the draft's own comment at `fn.clj:3452-3453`
states nothing can change that database between the diff and the write.

## 7. Corrections to the audit, with evidence

| Audit claim | Correction | Evidence |
|---|---|---|
| "output-path family `fn.clj:1807-2001`" | `output-path-report` is at `:1964`; the `:1807` span start does not resolve to a defn | `grep -n "^(defn output-path-report"` |
| "hook `SOURCE_PROGRESS` `bin/seon-hook:1554`" | the `log!` call is at `:1557` | `grep -n SOURCE_PROGRESS bin/seon-hook` |
| "~10 single-`deftest` `publication_*` namespaces" | **13** namespaces hold exactly one `deftest` (`publication_{concurrency,convergence,export,facet,findings,host,reuse,test}`, `source_{database,evidence,nochange}`, `fn/publication_{cache,toolchain}`) | `grep -c '(deftest'` per file |
| "`test.cache/sha-256 cache.clj:39-44`, `schema/sha-256 schema.clj:767`" | not re-verified this session (out of B1's owned files) | — |
| `seon.test`'s reads of `:seon.source/test-input-digest` at `test.clj:916, 1365, 2161` | confirmed, plus a fourth at `test.clj:900`; producers are `cluster.clj:1643`, `:1851`, `:1906`, `source.clj:357`, `:409`, `:466`, and further consumers at `test/runner.clj:3639` and `test/fast.clj:30` | `grep -rn test-input-digest src script bin` |
| "`program population compiled: 30320 entities` is 117 s (~4 ms/entity, suspected O(n²))" | the later measured breakdown attributes **13.226 s to final population compilation and 36.068 s to the transaction of 107,049 datoms**, not one 117 s step. `index-tempids` `fn.clj:2873` walks `(tree-seq coll? seq)` over every row × 53 identity attributes ≈ 1.6 M `find` calls, then one `pr-str` per identity and a sort — O(rows × identity-attributes), not O(rows²) | `one-jvm-redesign-2026-09-22.md:740-750`; live read: 53 identity attributes, 481,640 datoms, this session |
| "the audit cites `instrument.clj:593`" (AGENTS.md / plan drift) | confirmed still wrong in AGENTS.md; the seam is `current-wrapper? :844-858`, digest recorded by `arm-var! :873` | read this session |

## 8. Open questions a spec author must decide (evidence both sides)

| Question | Evidence for | Evidence against |
|---|---|---|
| Can clj-kondo's `load-when-missing` replace `analyzer/program-prelude` for agent evaluations? | The cache already carries `:arglist-strs :private :macro :fixed-arities :varargs-min-arity` per var (`analysis.clj:92-99`); `load-when-missing` (`cache.clj:127`) supplies required namespaces without a synthesized stub | An agent's SCI-only definitions were never linted, so no cache entry exists and `load-when-missing` silently no-ops (`cache.clj:135-144`) — those symbols would lint as unresolved. `analyze-forms` currently runs with `{:cache false}` (`analyzer.clj:690-698` per the audit) |
| Does clj-reload's `topo-sort` replace `reload-order`? | `parse.clj:163-176` is the same ordering in a maintained dependency, with `dependees :122` and `transitive-closure :133` beside it | Our version is 25 lines (`cluster.clj:1922-1946`) with a name-order total fallback; clj-reload's default `on-cycle` THROWS (`parse.clj:150-161`), and the direction depends on which map is passed. Adding the dependency for 25 lines is a new dependency edge |
| Is the tempid map the reset's dominant cost? | `index-tempids fn.clj:2873` does ≈1.6 M `find` calls plus a `pr-str`-keyed sort over every identity | The measured breakdown puts 36.068 s in the Datahike transaction of 107,049 datoms and 13.226 s in final population compilation (`one-jvm-redesign:746`); Datahike identity upsert already resolves `:db.unique/identity` |
| Should the caller lint land inside `index!`'s open transaction or as the draft's second transaction? | the draft's second transaction is on the same still-unpublished connection and cannot be raced (`fn.clj:3452-3453`); it is already written and has regressions | ruling "The transaction report is the seam for caller lint" (goals `:282`) describes transact → select → transact → move head, i.e. the draft's shape; a single-transaction variant has no report to select from until it commits |
| Does `require-publication-resources!` (`cluster.clj:2157`) disappear with the snapshot? | its only job is comparing two checkouts' declared inputs via `test.cache/input-digests` twice; with one JVM there is no second checkout | `test/cache.clj:379-475` (`prepare-base!`/`ensure-base!`) and `bin/test:1034` still create checkouts today; those files are lane B4's |
| What replaces the aggregate `:seon.source/test-input-digest`? | per-path `:seon.fn.file/digest` rows already exist (`fn.clj:3296` per the audit; `source/stored-path-digests :125`) | four reads in `seon.test` (`test.clj:900, 916, 1365, 2161`) plus `test/runner.clj:3639` and `test/fast.clj:30` consume the aggregate; the schema declares it in `source.clj:34` |
| Does `bin/seon-hook`'s shell-write digest walk survive? | `.claude/seon-hook.edn` records the walk as 112 ms over 553 files vs 2.7 s for `git status --porcelain` | it is a third digest layer (`bin/seon-hook:1688-1819`) beside `:seon.fn.file/digest` rows and `test.cache/input-digests`; it exists only for tools that name no path |
| When can hook publication be re-enabled? | `.claude/seon-hook.edn` `:current-source {:enabled false}` with the note "Re-enable at the wave-1 reset, when adoption is fast and the tree is quiet" | the recorded reason is 60–150 s publications under the lifecycle lock with three concurrent lanes; the current measured repeat edit is 2723 ms |

## 9. REPL access for a Codex lane — verified

| Fact | Evidence |
|---|---|
| Codex registers the same MCP server as Claude | `.codex/config.toml:2-3` `[mcp_servers.seon] command = "bin/mcp-server"`; `.mcp.json` registers it identically for Claude |
| the server is Babashka, not a JVM | `bin/mcp-server:6` `exec bb --classpath script:src:resources -m seon.dev.mcp` |
| three tools exist | `script/seon/dev/mcp.clj:847-877`: `eval_clj`, `runtime_status`, `get_value` |
| a lane can target its OWN root | `eval_clj` takes `root` ("Operator root path. Defaults to the repository root used by `bin/seon`") and `cluster`; `mcp.clj:851-852` |
| `jvm` mode binds NO cluster custody | `mcp.clj:849` description: `seon.db`'s elided arities refuse; `(seon.operator/connection "default")` supplies an explicit connection |
| `sci` mode MUTATES the shared per-cluster ctx | `mcp.clj:849` |
| bound | `timeout_ms` max 120000 (`mcp.clj:857`) |
| the non-MCP route | `script/seon/fresh_operator.clj:1609 live-root-value!` evaluates one form in the process holding an operator root, over `prepl-eval! :1820` / `read-prepl-reply :1166` |
| the tool-name prefix differs per client | this session called it `mcp__seon__eval_clj`; the Codex spelling was NOT verified here — a lane must list its own tools |
| the measurement script | `docs/prds/steward-platform/research/measure-publication-path-2026-09-22.sh` (2,871 bytes, executable). It `git worktree add`s a HEAD worktree, symlinks `reference-code`, uses an isolated `--root`, runs `init-zero / first-cluster / start / fork / adopt-first / adopt-nochange / adopt-noncore / adopt-core`, then `bin/seon down` and `git checkout --` of the two edited files. It greps `init phase=lifecycle elapsed-ms=` for its rows |

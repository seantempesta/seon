---
type: research
status: data pack (evidence only; no plan, no recommendation)
created: 2026-09-21
tags: [data-pack, lane-b4, test-system, seon.test, seon.test.runner, bin/test, fixtures]
---

# Data pack B4 — the test system

Evidence for whoever writes the lane-B4 specification. **No dispositions, no
ordered commits, no done criteria, no recommendation.** Every row was read at
HEAD `e7721a963` on branch `steward-platform` this session; drift from
[the deletion audit](deletion-audit-test-system-2026-09-21.md) is corrected in
place and collected in §9. One read-only `mcp__seon__eval_clj` (jvm) was run
against `default`; its output is §0b and is the only executed evidence here.

## 0a. Verification boundary

- Working tree carries another lane's uncommitted edits in `src/seon/cluster.clj`,
  `src/seon/fn.clj`, `test/seon/cluster/publication_delta_test.clj`,
  `test/seon/fn/publication_cache_test.clj`. None is a file this pack cites for
  a span. Line numbers are working-tree lines.
- File sizes at HEAD: `src/seon/test.clj` 2,182 · `runner.clj` 4,967 ·
  `cache.clj` 546 · `selection.clj` 215 · `accretion.clj` 411 · `arm.clj` 254 ·
  `fast.clj` 123 · `bounds.clj` 44 · `test/seon/test_support.clj` 1,123 ·
  `bin/test` 1,107 · `bin/test-fast` 48 · `bin/_test-slot` 165 ·
  `bin/test-check` 53 · `resources/seon/schemas/seon.test*.edn` 1,203 (12 files).

## 0b. The one live read (jvm, read_only, cluster `default`, 26 ms)

| Question | Answer | Bears on |
|---|---|---|
| `:seon.test/sym` rows | **2,157** | corpus size |
| `:seon.fn/sym` rows | **4,600** | program size |
| tests carrying `:seon.test/long` | **146** | §4 corpus sweep |
| tests carrying `:seon.test/platform` | **110** | goals §2c says "0 of 1,833"; **it landed** |
| tests carrying `:seon.test/subject` | **0** | synthesis §7.3 confirmed |
| tests carrying `:seon.test/reach` | **0** | PRD §Stage 1 says "281 of 1,829"; **now zero** |
| `(db/pull db [:db/id] [:seon.fn/sym "seon.await/await!"])` | typed `:seon.db/invalid-read` — *"received \"seon.await/await!\" for :seon.fn/sym, whose installed value type is :db.type/symbol"* | §6 settles audit §7.3 |
| same with the symbol | `{:db/id 2194}` | — |
| ref-shaped calls query, string seed (the census test's own query) | **4** callers | §6 |
| value-shaped calls query, symbol seed | **8** callers | §6 |

Cluster facts for §11: pid 10562, prepl 127.0.0.1:51887, web
http://127.0.0.1:7994, `:seon.boot/ready-ms` 5608, agents 1, health `observed`,
problem counts `{:error-signatures 2 :errored-receipts 2 :stale-vars 3}`.

---

## 1. Every audit citation, verified

| Audit claim | Verified at HEAD | Status |
|---|---|---|
| `test.clj:61` `changed-since-green` | `(defn changed-since-green` | ✅ |
| `test.clj:143` `bounded-result` | `(defn- bounded-result [test-var timeout-ms custody]` | ✅ |
| `test.clj:409` `execute-admitted!` | ✅ | ✅ |
| `test.clj:541` `run` · `:576` `run-owned` | ✅ both | ✅ |
| `test.clj:759` `green-members` | ✅ | ✅ |
| `test.clj:916, 1365, 2161` test-input-digest reads | ✅ all three (§8) | ✅ |
| `test.clj:1047` long exclusion | `(not (one entity :seon.test/long))` | ✅ |
| `test.clj:1595` `check-admission` · `:1627` `check-in-process` · `:1901` `check` | ✅ all three | ✅ |
| `runner.clj:360-378` `duration-failures` | `:360` head, `:378` closing `[])))` | ✅ |
| `runner.clj:1086-1180` destructive guard | `:1086` is a `;;;` banner; `:1180` is inside `expensive-fixture-tests`' docstring | ⚠ boundaries off; mechanism present |
| `runner.clj:1447-1545` schema restore | `:1447` `(defn restore-live-cluster-schema!`; `:1545` `(defn- ambient-snapshot` | ✅ |
| `runner.clj:2135-2440` reach | `:2135` `(defn- program-fact`; `:2431` `(defn provenance` | ✅ |
| `runner.clj:2714-3183` recording | `:2714` `complete-members`; `:3145` `commit-results!`; `:3183` `start-cluster!` | ✅ |
| `runner.clj:3032-3145`, `:3443-3560` result queries | `:3032` `result-selector`; `:3443` `run-result-query`; `:3513` `latest-results`; `:3550` `recorded-run!` | ✅ |
| `runner.clj:4494-4630` tally | `:4494` `print-task-failures!`; `:4517` `print-recorded-tally!` | ✅ |
| `runner.clj:1721-1849` is a verbatim copy of `arm.clj:5-137` | exact span is **`runner.clj:1724-1848` ≡ `arm.clj:12-136`**; `diff` differs only in `Instant` import and `::`-alias expansion | ⚠ corrected, claim holds |
| `test_support.clj:406` hold protocol | `(defprotocol Held` | ✅ |
| `test_support.clj:433` retiring base | `(defn- retrying-base` | ✅ |
| `test_support.clj:582` `database-base` · `:615` `fork-cluster-ctx` | ✅ both | ✅ |
| `test_support.clj:921` `with-fresh-database` · `:946` `with-branched-database` · `:982` `with-database` | ✅ all three | ✅ |
| `with-fresh-database` calls `create-base nil` at `:927` | it is at **`:951`**; `create-base` is defined at `:363` | ⚠ corrected |
| `test_support.clj:1038/1051/1070/1073/1085` helpers | actual: `program-fn-row` **:1026**, `apply-config!` **:1039**, `seed-cluster!` **:1058**, `transacted!` **:309**, `closeable` **:1116**, `preserving-instrumentation-state` **:1073** | ⚠ five corrected |
| `datahike versioning.cljc:620 fork-database` | **`:550`**, and it *"Copies every konserve key from the source store into the target store"* — a whole-store copy, **not** a pointer. The pointer is `branch!` at **`:212`** | ⚠ material correction |
| fork measured 37.0 ms p50, first 4,647.8 ms, projection 3,995.96 ms | `test-system-fork-2026-09-23.md:103-110` verbatim | ✅ |
| granted budget 68,105,085 ms | re-summed this session: **68,105,085 ms = 18.9 h** over 65 files | ✅ |
| archived PRD rules in-process primary | `test-system-is-the-database-prd-2026-09-17.md:44` *"The primary host for tests is the cluster's own JVM, in process"*; workers *"are the exception"* `:50` | ✅ |

---

## 2. The surviving-path functions: spans, signatures, callers

Contracts quoted verbatim from `:malli/schema`.

| Function | Span | Arglist | Contract |
|---|---|---|---|
| `seon.test/changed-since-green` | `test.clj:61` | `[database test-symbol]` | `[:=> [:cat :seon.db/database-value :seon.test/sym] [:or [:vector :seon.fn/sym] :seon.test/unknown-error]]` |
| `seon.test/bounded-result` (private) | `:143` | `[test-var timeout-ms custody]` | none declared |
| `seon.test/destroyers` | `:218` | `[database]` | `[:=> [:cat :seon.db/database-value] [:or [:map-of :seon.fn/sym :seon.fn/destroys] :seon.error/value]]` |
| `seon.test/host` | `:302` | `[database test-symbol]` | `[:=> [:cat :seon.db/database-value :seon.test/sym] [:or :seon.test/host-report :seon.error/value]]` |
| `seon.test/execute-admitted!` (private) | `:409` | `([test-var connection])` / `([test-var connection options])` | `[:function [:=> [:cat :seon.test/var :seon.db/connection] :seon.test/host-result] [:=> [:cat :seon.test/var :seon.db/connection :seon.test/run-options] :seon.test/host-result]]` |
| `seon.test/host-admission!` (private) | `:496` | `[connection test-symbol options]` | `[:=> [:cat :seon.db/connection :qualified-symbol :seon.test/run-options] [:or :seon.test.run/admission :seon.test/host-error]]` |
| `seon.test/run` | `:541` | `([test-var connection])` / `([test-var connection options])` | same `:function` pair as `execute-admitted!` |
| `seon.test/run-owned` | `:576` | `[{connection :seon.db/connection test-var :seon.test/var :as request}]` | `[:=> [:cat :seon.test/run-owned-request] :seon.test/host-result]` |
| `seon.test/green-members` (private) | `:759` | `[database members]` | `[:=> [:cat :seon.db/database-value [:sequential :int]] [:set :int]]` |
| `seon.test/select` | `:840` | destructured request map | `[:=> [:cat :seon.test.selection/request] [:or :seon.test.selection/result :seon.test/selection-error :seon.test/unknown-error :seon.test.run/unavailable-error :seon.db/invalid-read-error :seon.schema/missing-projection-error]]` |
| `seon.test/admit-run` | `:1280` | — | writer; holds the `:1365` digest check |
| `seon.test/resolve-test` | `:1506` | request map | `[:=> [:cat :seon.test/resolution-request] [:or :seon.test/var :seon.test/resolution-error :seon.test/not-runnable-error …]]` |
| `seon.test/check-admission` | `:1595` | `[database request]` | `[:=> [:cat :seon.db/database-value :seon.test/check-request] [:or :seon.test.run/admission …]]` |
| `seon.test/check-in-process` (private) | `:1627` | `[request progress]` | none declared |
| `seon.test/check` | `:1901` | `[request]` | `[:=> [:cat :seon.test/check-request] [:or :seon.test/check-result :seon.test/expired :seon.test/selection-error …]]` |
| `seon.test/check-request` | `:2052` | `[{connection cluster test-symbol override}]` | `[:=> [:cat :seon.test.check/request] :seon.test.check/response]` |
| `seon.test/verified?` | `:2142` | 2- and 3-arity | `[:function [:=> [:cat :seon.db/database-value :seon.test/sym] [:or :boolean :seon.test/host-error]] …]` |

**Callers outside `src/seon/test.clj`:**

| Callee | Production callers | Test callers |
|---|---|---|
| `check` | `src/my/test.clj:24` (the agent protocol) | — |
| `check-request` | `bin/test-check:43` (over the operator prepl) | — |
| `run-owned` | `src/my/test.clj:56` (inside the `my.test/run` macro) | — |
| `run` | `src/seon/plan.clj:870` | `test_runner_test.clj:418,966`, `schema_usage_guard_test.clj:34` |
| `changed-since-green` | `src/seon/render/test.clj:61` (as a rendered requery form) | — |
| `host` | `src/seon/render/test.clj:62` | `test_reaching_test.clj:504` |
| `destroyers` | — | `test_reaching_test.clj:576` |
| `verified?` | `src/seon/issue.clj:855`, `:1039`; `src/seon/turn.clj:2077` (comment) | — |
| `stale` | `src/seon/plan.clj:811` | — |
| `selection-admission` / `admit-run` | `src/seon/cluster/source.clj:289`, `:293` (via `requiring-resolve`) | — |
| `recorded-result` | `src/seon/test.clj:1463` → `runner/latest-results` | — |

Namespaces requiring `seon.test` in `src/`: `problems.clj:68`, `plan.clj:25`,
`issue.clj:15`, `render/test.clj:7`, `my/test.clj:3`. `seon.test.runner` is
additionally required by `sci/eval.clj:131`, `problems.clj:69`, `plan.clj:26`;
`seon.test.accretion` by `turn.clj:37`, `fn.clj:23`, `sci/eval.clj:130`;
`seon.test.cache` by `cluster.clj:50`, `cluster/source.clj:21`.

---

## 3. The runner mechanism table, verified, with outside callers

Spans re-read this session. "Outside callers" excludes `runner.clj` itself.

| # | Mechanism | Verified span | Callers outside runner.clj |
|---|---|---|---|
| 1 | report capture / failure identity | `:111-435` | — |
| 2 | `duration-failures` | `:360-378` | — (private) |
| 3 | watchdog, thread dumps, `process-description` | `:435-640` | — |
| 4 | `run-vars!` `:655`, `test-vars-in` `:741`, `run-var!` | `:655-750` | `src/seon/sci/eval.clj:3280` (`run-vars!`); tests: `test_test.clj:86`, `test/runner_test.clj:286,318,404,660`, `test_reaching_test.clj:153,196`, `cluster/publication_reuse_test.clj:60`, `test/selection_test.clj:396` |
| 5 | `marker-reason` `:752`, `long-declarations` | `:752-805` | — |
| 6 | `verify-platform-declarations-indexed!` `:874`, `fixture-observation!` `:960` | `:874-960` | `fixture-observation!`: `test_support.clj:108,133,922`; `test/runner_test.clj:480,484` |
| 7 | `atomic-namespace-task?` `:1019`, `tests-reaching-rows` `:1077` | `:1019-1077` | — |
| 8 | destructive guard | `~:1086-1179` | — (paired with `seon.test/destroyers`) |
| 9 | `expensive-fixture-tests` `:1179` | `:1179-1244` | — |
| 10 | `run-selected-tests` `:1244`, tiers | `:1244-1354` | — |
| 11 | ambient drift (`ambient-drift-journal-limit` `:1393`, `ambient-snapshot` `:1545`) | `:1393-1447`, `:1545-1656` | — |
| 12 | `restore-live-cluster-schema!` | `:1447-1545` | `src/seon/test.clj:456`; `test_support.clj:1113`; `test/runner_test.clj:1258,1291,1311,1319` |
| 13 | `run-task!` | `:1657-1720` | — |
| 14 | arm.clj duplicate (`load-declared-predicate-owners!` … `declared-program-namespaces`) | **`:1724-1848`** | — (`:1849` re-vars to `test.arm/arm-contracts!`) |
| 15 | wire protocol (`write-protocol!` `:1907`, `write-command!` `:1912`) | `:1907-2135` | — |
| 16 | reach (`program-fact` `:2135`, `reach-digests` `:2349`, `reach-memberships` `:2362`, `provenance` `:2431`) | `:2135-2440` | `reach-memberships`: `src/seon/test.clj:87,88`; tests `test_failure_facts_test.clj:436`, `test_reaching_test.clj:156`, `test/selection_test.clj:512`. `reach-digests`: `src/seon/test.clj:2161ff`; 8+ test sites. `provenance`: 6+ test sites, no production caller |
| 17 | claim/execution refusals (`execution-refusal!` `:2454`) | `:2454-2714` | — |
| 18 | recording (`complete-members` `:2714`, `commit-results!` `:3145`) | `:2714-3183` | `commit-results!`: `src/seon/cluster/source.clj:287`; tests `test_runner_test.clj:811,824,843,890,1573`, `test_failure_facts_test.clj:279,287`. `complete-members`: **no caller outside runner.clj** |
| 19 | staged results (`commit-persistent-results!` `:3263`) | `:3263-3443` | — |
| 20 | result queries (`result-selector` `:3032`, `run-result-query` `:3443`, `latest-results` `:3513`, `recorded-run!` `:3550`) | `:3032-3145`, `:3443-3560` | `latest-results`: `src/seon/test.clj:1463`, `src/seon/problems.clj:354`. `recorded-run!`: `src/seon/test/fast.clj:105` only |
| 21 | pool / checkout / exchange (`source-root` `:3628`, `worker-checkout` `:3676`) | `:3628-4307` | `worker-count` mirrored in `src/seon/test/cache.clj:20,29` |
| 22 | confirmation (`confirmation-root` `:4308`) | `:4308-4500` | — |
| 23 | tally (`print-task-failures!` `:4494`, `print-recorded-tally!` `:4517`) | `:4494-4630` | `src/seon/test/fast.clj:108`; `test/published_selection_test.clj:64` |
| 24 | `confirmation-symbols` `:4666` | `:4666-4717` | — |
| 25 | `run-coordinator!` `:4718`, `-main` | `:4718-4967` | `bin/test:1077` (`-m seon.test.runner`) |

`duration-failures` body, verbatim decision at `:369-371`:

```clojure
limit (if (and (string? reason) (not (str/blank? reason))
               (integer? allowance) (pos? allowance))
        (max ordinary allowance) ordinary)
```

`ordinary` is `:seon.test/time-limit-ms`, declared `{:min 1 :default 5000}` at
`resources/seon/schemas/seon.test.edn:27-29`.

---

## 4. The corpus sweep: every `:seon.test/long` declaration

Re-derived this session by regex over `test/**/*.clj`: **65 files, 68,105,085 ms
(18.9 h)**. Class letters are the audit's (§2b). `ms` blank = reason-only, which
under §3's `:369-371` keeps the **5,000 ms** default. "O(program) step" is quoted
from the declaration's own reason text or the body it guards; the owner column
names the `src` namespace that holds that step.

### 4a. Collapsed duplicate blocks (class D)

| File | Lines | Each | Identical reason | Class |
|---|---|---:|---|---|
| `test_runner_integration_test.clj` | `:109,130,180,198,232,251,265,286,314,405,422,425,452,528,659,720,817,919,999,1096,1181,1254,1380,1462` (24) | reason-only | *"Orchestrator integration: owns child processes; excluded from ordinary lane r…"* | D |
| `test_runner_integration_test.clj:46` | 1 | 1,800,000 | *"…observes real child exit while serial work awaits a…"* | B |
| `shell/jvm_test.clj` | `:180,214,237,271,304,321,387` (7) | 600,000 | *"Publish the canonical program into a physical store before observing shell ou…"* | D (publication is the step) |
| `shell/jvm_test.clj:29` | 1 | 600,000 | *"Acquire the canonical database fixture before checking carried config project…"* | A |

Note: the audit reports 25 declarations at 1,800,000 ms in
`test_runner_integration_test.clj` summing to 45,000,000 ms. At HEAD only
**`:46` carries an explicit `long-ms`**; the other 24 are reason-only and the
45,000,000 ms figure comes from the file's `:seon.test/long-ms` occurrences
counted separately from the reason matches. Re-summed: the file's `long-ms`
total **is** 45,000,000 ms (§9, correction 4).

### 4b. Class A — O(program) work, by owning `src` namespace

| Owning step | `src` owner | Declarations (file:line, ms) |
|---|---|---|
| **Canonical fixture acquisition** (`create-base` → the residual `projection-from-database`, 3,996 ms of 4,648) | `src/seon/schema.clj:2788` `projection-from-database`; fixture at `test_support.clj:363` | `my/test_test.clj:13` 300,000 · `cluster/lazy_agents_test.clj:10` 10,000 · `cluster/publication_adoption_test.clj:15` 600,000 · `cluster/publication_delta_test.clj:50` 20,000 · `cluster/publication_inputs_test.clj:84` 10,000 · `config_functions_test.clj:8` 20,000 · `db/declaration_population_test.clj:16` 180,000 · `dev/source_instrumentation_test.clj:14` 600,000 · `fn/publication_test.clj:39` 60,000 · `fn/unresolved_test.clj:7` 60,000 · `publication_validation_test.clj:27` 10,000 · `schema/datahike_test.clj:360` 120,000 · `schema/projection_acquisition_test.clj:37` 10,000, `:94` 10,000 · `shell/jvm_test.clj:29` 600,000 · `test/declared_reference_test.clj:7` 10,000 · `test/host_test.clj:15` 300,000 · `test/published_selection_test.clj:10` 30,000 · `test_support_test.clj:196` 10,000 · `turn_test.clj:1322` 60,000 |
| **Complete / incremental publication** (`refresh-source!`, `publish!`) | `src/seon/cluster/source.clj:371` `publish!`; `src/seon/cluster.clj` | `cluster/boot_test.clj:873` 600,000, `:1142` 600,000, `:1799` 600,000 · `cluster/publication_concurrency_test.clj:8` 15,000 · `cluster/publication_delta_test.clj:14` 15,000 · `cluster/publication_export_test.clj:16` 600,000 · `cluster/publication_facet_test.clj:9` 600,000 · `cluster/publication_host_test.clj:18` 1,200,000 · `cluster/publication_reuse_test.clj:22` 600,000 · `cluster/source_evidence_test.clj:13` 10,000 · `cluster/source_nochange_test.clj:7` 600,000 · `cluster/source_test.clj:305` 10,000 · `dev/fresh_operator_reset_test.clj:292,330` 600,000 ea. · `incremental_publication_test.clj:59` — · `predicate_publication_test.clj:17` 600,000 · `schema/admission_test.clj:126` 300,000 · `schema_redeclare_test.clj:72` 300,000 · `test/publication_test.clj:12` 900,000 · `test/selection_test.clj:296` 900,000 · `fn_test.clj:1696` 180,000 · `cluster_test.clj:56` 30,000 |
| **Cold clj-kondo analysis** | `src/seon/fn/analyzer.clj`; `src/seon/fn.clj:3314` `index!` | `fn/publication_cache_test.clj:9` 600,000 |
| **Complete SCI acquisition** | `src/seon/sci/eval.clj:1771` `acquire-program!`, `:2218` `base-ctx` | `sci/lazy_acquisition_test.clj:9` 30,000 · `error_result_test.clj:99` 60,000 · `error_write_timing_test.clj:80` 60,000 |
| **Whole-image instrumentation** | `src/seon/instrument.clj:898` `collect-contracts!` | `sci/eval_instrumentation_test.clj:1` (ns, reason-only) · `dev/fresh_operator_test.clj:1639` — |
| **Whole-store copy / reidentify** | `src/seon/cluster/store.clj`; `test_support.clj` clone helpers | `dev/fresh_operator_export_test.clj:38` — · `cluster/boot_test.clj:466` 90,000 |
| **Per-trial fixture inside a property run** | `test_support.clj:982` `with-database` per trial | `render/transcript_test.clj:968` — (40 trials) · `print_test.clj:240,264` — (200 trials each) · `schema/datahike_test.clj:243` 25,000 (80 cases) · `concurrency_independence_test.clj:1` (ns) |

### 4c. Class B — process machinery (own child JVMs / shell)

`test_runner_integration_test.clj:46` (1,800,000) · `cluster/store_test.clj:498,568`
(60,000 ea., *"Cold child JVM"*) · `dev/source_instrumentation_test.clj:80`
(60,000, *"Start a cold Clojure JVM"*) · `dev/fresh_operator_reset_test.clj:1`
(ns, 600,000), `:359` (60,000) · `dev/fresh_operator_test.clj:1072,1197,1302,1361,1459,1662`
(reason-only, 90–115 s each) · `dev/edit_feedback_test.clj:221` (*"real hook
subprocesses"*) · `cluster/boot_test.clj:1463` (*"child-JVM operator refork"*) ·
`cluster/cohost_boot_test.clj:100` (180,000, two cold boots) ·
`bootstrap_drive_test.clj:28` (*"real cluster graph bootstrap"*).

### 4d. Class C — genuinely long (kept by the audit)

`flow_test.clj:1427` (*"Forcibly terminates a child JVM"*) ·
`oversight_test.clj:113` (*"Boots a real cluster and fetches its root page"*) ·
`config_application_test.clj:147` (*"Starts a real cluster"*) ·
`cluster/program_restart_test.clj:1` (ns, 67.758 s) ·
`cluster/armed_test.clj:1` (ns, 48.642 s slowest member) ·
`ai_stream_fold_test.clj:374` (90,000, isolated file-backed store) ·
`dev/dependency_cache_test.clj:136` (AOT classes across heap pressure).

### 4e. The 5 s-default population (reason-only) and the fixture file

Reason-only declarations at HEAD, which silently keep 5,000 ms: all of
`cluster/boot_test.clj` `:497,531,565,590,620,702,779,823,958,1089,1226,1351,1386,1463,1541,1663`
(16) · `dev/fresh_operator_test.clj` `:1072,1197,1302,1361,1459,1639,1662` (7) ·
`print_test.clj:240,264` · `render/transcript_test.clj:968` ·
`concurrency_independence_test.clj:1`, `concurrency_streams_test.clj:1`,
`cluster/armed_test.clj:1`, `cluster/program_restart_test.clj:1`,
`sci/eval_instrumentation_test.clj:1` (5 ns-level) ·
`config_application_test.clj:147` · `oversight_test.clj:113` ·
`flow_test.clj:1427` · `dev/edit_feedback_test.clj:221` ·
`dev/fresh_operator_export_test.clj:38` · `dev/dependency_cache_test.clj:136` ·
`incremental_publication_test.clj:59` · `bootstrap_drive_test.clj:28` · the 24
in `test_runner_integration_test.clj` listed in §4a.

`test/seon/test/duration_test.clj` is the marker's own fixture and must be read
before the marker is changed: `:17` *"Explained work without an allowance"*,
`:18` `" "` (a deliberately blank reason), `:24` *"Two bounded database
operations"*, `:30` *"Namespace-scoped bounded work"*. `test/runner_test.clj`
`:849,851,962,966,1005,1048-1053` construct reason/ms pairs programmatically,
including `:1052` at **42 ms**, as marker-lifting fixtures.

---

## 5. `test/seon/test_support.clj`

| Entry | Span | What it builds | Cost |
|---|---|---|---|
| `with-database` | `:982-997` | dispatcher: `(if (or database-id fresh-store?) with-fresh-database with-branched-database)` | — |
| `with-branched-database` (private) | `:946-981` | `acquire-base!` a held base → `d/branch!` off `base-connection` (`:964`) → `d/connect` under `base-projection` (`:966`) → `sci.eval/projection-state` (`:970`) → on exit `d/delete-branch!` + `release-base!` | **37.0 ms p50** |
| `with-fresh-database` (private) | `:921-944` | `(create-base nil)` at **`:951`** → `d/force-branch!` → **`d/fork-database`** into a fresh memory store → connect → delete-database → `close-base!` | **4,647.8 ms**, per requesting test |
| `create-base` (private) | `:363` | clone the published store dir, `reidentify!`, connect, derive the projection | contains the 3,995.96 ms `projection-from-database` |
| `database-base` | `:582` | the JVM-scoped held base | once per JVM per publication |
| hold protocol (`Held`, `acquire-base!`, `release-base!`, `close-base!` `:341`, `retrying-base` `:433`, `checked-fixture-result` `:282`, `acquire-branch!` `:257`) | `:257-577` | retirement-instead-of-close so a mid-run adoption cannot delete a live branch | — |
| `fork-cluster-ctx` | `:615` | SCI copy-on-write fork of the base's acquired ctx | inside the 37 ms |
| file-backed published-root helpers (`clone-directory!` `:60`, `replace-directory!` `:96`, `with-published-file-database` `:145`) | `:60-180` | a file-backed published root for something that boots from files | — |
| canonical fixture helpers | `transacted!` `:309`, `delete-recursively!` `:819`, `program-fn-row` `:1026`, `apply-config!` `:1039`, `seed-cluster!` `:1058`, `preserving-instrumentation-state` `:1073`, `closeable` `:1116` | — | — |

**`with-database` call sites: 927 occurrences across 201 test files.**

**`fresh-store?` / `database-id` call sites — six, not eight:**

| Site | Key |
|---|---|
| `test/seon/blob_test.clj:218` | `::support/fresh-store? true` |
| `test/seon/ai_stream_fold_test.clj:378` | `::support/fresh-store? true` |
| `test/seon/cluster/mcp_test.clj:567` | `:seon.test-support/fresh-store? true` |
| `test/seon/cluster/source_database_test.clj:14` | `:seon.test-support/fresh-store? true` |
| `test/seon/test/runner_test.clj:442` | inside a generated fixture **source string** |
| `test/seon/test/runner_test.clj:479` | inside a generated fixture form |

`:seon.test-support/database-id` has **zero** call sites outside
`test_support.clj:989` (its own docstring). Its `with-fresh-database` branch is
reachable only through `fresh-store?` today.

---

## 6. Corpus mirrors, helper copies, and the symbols-as-strings sites

| Audit claim (§3b/§6c) | Verified at HEAD |
|---|---|
| `await_owner_census_test.clj:10-15` pins 5 caller symbols | ✅ `declared-production-callers` is a literal `#{}` of 5 **strings** at `:10-15`; the query it is compared against is at `:19-27` in the same file. **The live cluster returns 4 callers for that query shape, not 5.** |
| `fn_core_calls_test.clj:6-10` `printer-symbols` roster | ✅ four **strings** `clojure.core/{print,println,prn,pr-str}` |
| a byte-identical 12-row provider error table in two files | ✅ **identical after whitespace normalization**, 12 rows each: `cluster/turn_test.clj:2315` (`turn-evidence-partitions`, 20 lines) and `ai_test.clj:1333` (`evidence-partitions`, 37 lines). Source enum: `resources/seon/schemas/seon.ai.edn:71-82` |
| `flow_test.clj:142-166` fault-schema mirror + `:224-231` fake committer | not re-read line-by-line this session — **unverified** |
| `instrument_test.clj:53-71` hand-rolled `preserving-instrumentation-state` | ✅ `(defn- preserving-instrumentation-state [body] …)` at `:53`, beside the canonical `test_support.clj:1073` |
| `turn_work_test.clj:50-62` `model-attempt` | ✅ at `:51` |
| 3 local `delete-recursively!` copies | **five**, plus the production owner: `cluster/boot_test.clj:90`, `cluster/registry_test.clj:68`, `dev/fresh_operator_reset_test.clj:18` (delegates to `operator-test`), `dev/fresh_operator_test.clj:41`, canonical `test_support.clj:819`, production `src/seon/fs.clj:293` |
| `:seon.audit/poison` probe repeated | ✅ **7** sites: `cluster_test.clj:21,45`, `turn_test.clj:944,964,989`, `my/plan_test.clj:70,435` |

**Symbols-as-strings (§6c) — all eight verified present, and the runtime answer
is settled.** `:seon.fn/sym` is declared `[:qualified-symbol {:seon.db/identity
true …}]` at `resources/seon/schemas/seon.fn.edn:179-183` and installs as
`:db.type/symbol`. A string lookup does **not** match silently: `seon.db/pull`
returns a typed `:seon.db/invalid-read` naming the installed declaration (§0b).

| Site | Text |
|---|---|
| `bootstrap_test.clj:310` | `{:seon.fn/sym "fixture.intent/target"` |
| `operator_test.clj:474` | `:seon.fn/sym "seon.operator/reap-dead-roots!"` |
| `test_reaching_test.clj:68` | `(is (:db/id (db/pull database [:db/id] [:seon.fn/sym "my.note/add!"])))` |
| `help_test.clj:90,106,109` | `[:seon.fn/sym "my.turn/usage-form"]`, `"seon.bootstrap/help-value"` ×2 |
| `rereads_test.clj:165` | `[:db/add [:seon.fn/sym "my.plan/current!"] …]` |
| `adoption_contract_freshness_test.clj:91` | `[:seon.fn/sym "my.adoption-contract-probe/value"]` |

Also note: `:seon.fn/calls` is declared `[:set {:seon.db/index true}
:seon.program/edge-symbol]` (`seon.fn.edn:31`) — a **value** set, per ruling G2 —
yet `await_owner_census_test.clj:19-27` queries it in **ref** shape
(`[?caller :seon.fn/calls ?owner]` with `?owner` an entity). §0b shows the ref
shape returning 4 and the value shape returning 8 for the same owner.

---

## 7. `reference-code/kaocha` — what a runner is, in facts

`src/kaocha/**` totals **3,427 lines** for a general, pluggable, multi-platform
test runner. Overlaps with `src/seon/test/runner.clj` (4,967 lines for one
platform):

| Concern | kaocha | file:line |
|---|---|---|
| Run one test var, with reporting and zero-assertion detection | **34 lines**, the whole `:kaocha.type/var` method | `src/kaocha/type/var.clj:30-63` |
| Run a collection, with fail-fast and skip propagation | **15 lines** | `src/kaocha/testable.clj:214-228` |
| Run one testable with hooks | `run-testable` | `testable.clj:158` |
| Reporter substitution | `with-redefs [t/report r]` — a two-line macro | `api.clj:34-36` |
| Result arithmetic (sum, diff, totals) | **65 lines total** | `result.clj:4-40` |
| Test discovery by namespace pattern | `find-test-nss`, 6 lines over `tools.namespace.find` | `load.clj:17-22` |
| Per-test duration measurement and slowest-N report | a **plugin**, `::duration` computed with `Instant/until` | `plugin/profiling.clj:17-23,58-83` |
| Output capture | a plugin | `plugin/capture_output.cljc` |
| clojure.test interception | a documented monkey-patch of `do-report`, not a reimplementation | `monkey_patch.clj:26-40` |

Underneath both: `clojure.test` supplies `report` as a **dynamic defmulti**
(`reference-code/clojure/src/clj/clojure/test.clj:325`), `do-report` (`:353`),
`inc-report-counter` (`:316`), `test-var` (`:710`, itself `^:dynamic`) and
`test-vars` with fixture composition (`:725-737`). kaocha has **no** duration
*bound* — its profiling plugin measures and reports; it never fails a test for
being slow.

---

## 8. The three reads of `:seon.source/test-input-digest`

Produced by `seon.test.cache/test-input-digest` (`cache.clj:240-253`), which
SHA-256s the sorted inventory of gate inputs outside the program graph
(gitlinks included) for one checkout. Written at `cluster.clj:1643`, `:1851`,
`:1906` and `cluster/source.clj:357`, `:466`; declared a source attribute at
`cluster/source.clj:34`.

| Read | Enclosing function | What it decides | Refusal on mismatch |
|---|---|---|---|
| `test.clj:916` | `select` (`:840`) | that the publication has **one** unique external-input identity, and — when no cluster is named — that it equals the request's `:seon.test.run/input-digest` | `:seon.test/input-evidence-unavailable`, observed = the source-id vector or the request digest |
| `test.clj:1365` | `admit-run` (`:1280`) | at admission, guarded by `(when-not snapshot? …)`: exactly one digest exists, and it equals `(:seon.test.run/input-digest request)` | `:seon.test/input-evidence-unavailable` (`:one-publication-input-digest`) then `:seon.test/program-mismatch` |
| `test.clj:2161` | `verified?` (`:2142`, 2-arity) | whether recorded green still counts: `(= #{(:seon.test.run/input-digest result)} (set inputs))`, joined with reach-digest equality between now and the tested basis | returns `false`, or propagates the read's own `:seon.error/at` |

Two further consumers of the same aggregate outside `seon.test`:
`src/seon/test/fast.clj:30` and `src/seon/test/runner.clj:3639`, both computing
it fresh with `(cache/test-input-digest "." inputs)` rather than reading a row.
The publication audit's row 13 names these three reads as the block on deleting
the aggregate in favour of per-path `:seon.fn.file/digest` rows
(`src/seon/fn.clj:3296`).

---

## 9. Corrections to the audit, with evidence

1. **`bin/test-check` exists and the audit never mentions it** (53 lines,
   babashka). It starts no JVM: it resolves the operator advertisement, then
   evaluates `(seon.test/check-request …)` over the live prepl
   (`bin/test-check:43`) and prints `:seon.test.check/text`. A launcher that is
   "a request to the running cluster" is **already implemented and in the tree**.
2. **`datahike/versioning.cljc:620 fork-database` is wrong.** `fork-database` is
   at `:550` and its own docstring says it *"Copies every konserve key from the
   source store into the target store"* — that is why `with-fresh-database`
   costs 4,648 ms. The O(1) pointer is `branch!` at `:212`.
3. **The arm.clj duplicate span is `runner.clj:1724-1848` ≡ `arm.clj:12-136`**,
   not `:1721-1849`/`:5-137`; `diff` returns only the `Instant` import and
   `::`-alias differences.
4. **`test_runner_integration_test.clj` carries one reason+ms declaration and 24
   reason-only ones** at HEAD; the 45,000,000 ms total is real but comes from
   `long-ms` occurrences, not from 25 reason+ms pairs. The distinction matters
   because §2c's defect applies to the 24.
5. **`fresh-store?`/`database-id` has 6 call sites, not 8**, and only 4 are real
   tests (§5). `database-id` has none.
6. **`with-fresh-database` calls `create-base nil` at `:951`**, not `:927`.
7. **`delete-recursively!` has five test copies plus `src/seon/fs.clj:293`**, not
   three copies.
8. **The symbols-as-strings sites are not vacuous** (audit §7.3 could not
   settle this): a string against `:seon.fn/sym` returns a typed
   `:seon.db/invalid-read`, so the affected assertions fail or propagate a
   refusal rather than matching nothing.
9. **`:seon.test/platform` has landed on 110 rows** — the goals note's "0 of
   1,833" and the PRD's §0c item are stale. **`:seon.test/reach` is now 0 of
   2,157**, where the PRD says 281 of 1,829; the audit's §5 row that describes
   reach as the surviving capability rests on `reach-memberships` computing
   live, not on stored `:seon.test/reach`.
10. **AGENTS.md's own citations into this area have drifted**: `commit-results!`
    cited at `runner.clj:1435` (actual `:3145`, AGENTS.md:1028); `run-owned` at
    `test.clj:380` (actual `:576`, AGENTS.md:931); `run` at `:306` and
    `changed-since-green` at `:54` in the archived PRD §2 (actual `:541`, `:61`);
    `collect-contracts!` at `instrument.clj:687` (actual `:898`), `restore!` at
    `:799` (actual `:1080`), re-arm at `:593` (actual `current-wrapper?` `:844`).
11. **`seon.test.runner/render-run` does not exist.** The PRD's stage-4
    acceptance (*"the printed tally equals `(render-run db run-id)` byte for
    byte"*) names a function that was never written; the tally today is
    `print-recorded-tally!` (`:4517`), called by `fast.clj:108` and
    `bin/test:` via `-main`.
12. **`complete-members` has no caller outside `runner.clj`** despite being
    counted in the audit's "recording authority" survivor block.

---

## 10. Open questions a spec author must decide

Neutral; evidence on both sides, no recommendation.

| # | Question | Evidence for one side | Evidence for the other |
|---|---|---|---|
| 1 | Are `check` and `run-owned` one function with a selection argument? | Both route through `check-request-admission`/`host-admission!` → `admit-run` → `execute-admitted!` → `commit-results!`; `check-admission` (`:1595`) and `host-admission!` (`:496`) both call the shared selector | `run-owned` resolves the Var from facts first (`resolve-test`, `:1506`) and binds the agent's own connection as body custody; `check` never binds body custody and excludes destructive and long members by policy (`:1909-1915`). Their contracts differ (`:seon.test/run-owned-request` vs `:seon.test/check-request`) |
| 2 | Can the platform tier also be a request to the running cluster? | `bin/test-check` proves a shell can drive the live cluster with no JVM; 110 rows already carry `:seon.test/platform` | The platform tier includes boot-from-zero and destructive drills: `dev/fresh_operator_test.clj` (2,206 lines), `cluster/boot_test.clj` (1,837), `operator_test.clj` (1,441) launch real child JVMs; `seon.test/host` (`:302`) returns `:seon.test.host/isolated-snapshot` for anything reaching `:seon.fn/destroys` |
| 3 | Can reach memberships be the same query a profiler joins? | `reach-memberships` (`runner.clj:2362`) already returns per-symbol memberships from stored `:seon.fn/calls`; synthesis §8.5 proposes profiler facts keyed by (symbol, definition digest, branch) | `:seon.test/reach` is stored on **0 of 2,157** rows today (§0b), so the join has no stored side; and the archived PRD warns pull caps cardinality-many at 1,000 (`pull_api.cljc:16`) |
| 4 | Open decision 5 — worker claim granularity (per test / namespace / file) | PRD §6.1 requests three options with measured fixture-load costs | The claim protocol (`runner.clj:2454-2714`) exists only for multiple processes; with one JVM the work list is a sequence |
| 5 | Open decision 6 — does a non-`default` run record a pointer on `:current-src`? | PRD §6.2 recommends the cross-cluster query instead of a pointer | `changed-since-green` (`test.clj:79`) already filters by `:seon.test.run/branch` from `(get-in (db/schema-database database) [:config :branch])`, so "last green" is per-branch today |
| 6 | Open decision 7 — declared `:seon.test/platform` set vs derived by reach | PRD §6.3 recommends keeping the declaration; it is now populated on 110 rows | `seon.test/host` already derives destructiveness by reach from `:seon.fn/destroys`; two derivation styles for "where does this run" coexist |
| 7 | Open decision 8 — the default per-test bound value | Declared `{:default 5000}` at `seon.test.edn:27-29`; owner's law is "seconds, not minutes" | The 37 ms fork is well under it, but `create-base` is 4,648 ms and `projection-from-database` alone is 3,996 ms — a 5 s default leaves 352 ms of headroom for any test that pays a fresh base |
| 8 | What happens to `:369-371` when a reason carries no number? | The bound's own fixture asserts both shapes (`test/duration_test.clj:17` explained-without-allowance, `:18` a blank reason) | 38+ corpus declarations (§4e) are reason-only and their own reason strings state 47–200 s; they pass only because `test.clj:1047` excludes declared-long tests from ordinary selection |
| 9 | Does the ref-shaped `:seon.fn/calls` query in the census test have a legitimate reading? | It returns 4 rows live (§0b), so it is not empty | `:seon.fn/calls` is declared a symbol **value** set (`seon.fn.edn:31`) under ruling G2; the value-shaped query returns 8 for the same owner, and the file's hand-maintained roster names 5 |
| 10 | `bounded-result` (`test.clj:143`) carries **no** `:malli/schema` | §1j requires every function, private included, to carry one | It is the interrupt seam and takes a live `FutureTask`/custody map; the polymorphic-boundary exemption exists in the schema vocabulary |

---

## 11. REPL access for a Codex lane (verified this session)

- **The development cluster is alive**: `default`, pid 10562, prepl
  127.0.0.1:51887, web http://127.0.0.1:7994, ready in 5,608 ms, 1 agent.
  Verified with `mcp__seon__runtime_status` (`cluster: "default"`), health
  `observed`.
- **Reaching it**: `mcp__seon__eval_clj` with `mode: "jvm"` evaluates in that
  JVM's io-prepl. It binds **no** cluster custody, so `seon.db`'s elided
  arities refuse there; `(seon.operator/connection "default")` supplies the
  explicit connection. Verified: the §0b form opens with exactly that call and
  returned in **26 ms**. `mode: "sci"` mutates the shared per-cluster ctx.
- **Oversized values settle into the blob tier**: the §0b result returned
  `:seon.dev.mcp/windowed? true` with `:seon.blob/digest 724e538a…` and
  `:seon.blob/size 6232`, retrievable.
- **A scratch cluster** is `bin/seon --root tmp/<lane>-root start <lane>`
  (AGENTS.md §6); `bin/seon status` reports roots. A lane never stops, reforks
  or restarts `default`.
- **A no-JVM path to the live cluster already exists**:
  `bin/test-check [--root PATH] [CLUSTER [--test NS/TEST [--time-limit-ms N]]]`
  (`bin/test-check:33`), which refuses when no advertisement is present
  (`:35`) and exits 1 unless `:seon.test.check/passed?` is true (`:49`).
- **Load order for HEAD**: `clojure -M -e "(require 'seon.test
  'seon.test.runner 'seon.test.selection)"` is the AGENTS.md §13 proof; not run
  this session — **unverified**.

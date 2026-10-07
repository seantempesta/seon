# Map: test runner, cluster/agents/boot, execution and tools vs the reference libraries

Research only. Line counts are `wc -l` on the checkout of 2026-10-07 (branch refactor/agent-platform).
"Code lines" below exclude blank lines, `;` comments and multi-line string literals (docstrings), measured with a
throwaway tokenizer in the scratchpad. Estimates of removable lines are judgment, flagged "est.".

## 0. Totals for this area

| Group | Files | Lines |
|---|---|---|
| Test runner | `seon/test.clj` 2608, `seon/test/runner.clj` 2107, `test/cache.clj` 249, `test/accretion.clj` 131, `my/test.clj` 75 | 5170 |
| Cluster core | `seon/cluster.clj` 3582, `cluster/agent.clj` 1755, `cluster/source.clj` 1302, `cluster/boot.clj` 824, `cluster/store.clj` 738, `cluster/registry.clj` 661, `cluster/message.clj` 918, `cluster/wake.clj` 578, `cluster/prompt.clj` 460, `cluster/process.clj` 330, `cluster/host.clj` 200, `cluster/status.clj` 190, `cluster/instruction.clj` 79, `cluster/branch.clj` 14 | 11 821 |
| Flow / execution | `flow.clj` 903, `effect.clj` 982, `schedule.clj` 803, `await.clj` 153, `background.clj` 118, `eval/drive.clj` 420 | 3379 |
| Ops machinery | `maintenance.clj` 696, `health.clj` 733, `profile.clj` 642 + `profile/measurement.clj` 203, `bootstrap.clj` 336, `env.clj` 423 | 3033 |
| Tools | `fs.clj` 438 + `fs/jvm.clj` 910, `shell.clj` 28 + `shell/jvm.clj` 464, `edit.clj` 514 + `edit/jvm.clj` 168, `ai.clj` 1331 (+`ai/tokens.cljc` 210) | 4065 |
| Tasks/agent | `task.clj` 653, `agent.clj` 254 | 907 |
| `my/*.clj` | program 1048, agent 208, task 192, fs 109, edit 112, message 84, background 82, shell 42, web 41, turn 34, test 75 | 2027 |

Whole area is about 27.8k lines of the 91k in `src` (the `wc` total in the listing; includes `my/program.clj`, which is the
single largest `my` file). Reference comparables: kaocha 4.3k total, of which the parts that matter here are
`testable.clj` 252 + `type/*.clj` ~135 + `plugin/filter.clj` 169 + `load.clj` 47 + `result.clj` 65 + `runner.clj` 210 + `api.clj` 169.
clj-reload 951 total (`core.clj` 437). core.async.flow 346 + 323 + 95 + 28. babashka-process 718. rewrite-clj zip 1151 (zip alone).
langchain4clj core 624 (a wrapper over a Java library).

Density of the Seon files (code / docstring-strings / comments, of total lines):

| File | total | code | docstring+string lines | `;` comment lines |
|---|---|---|---|---|
| test.clj | 2609 | 1967 | 369 | 203 |
| test/runner.clj | 2108 | 1534 | 384 | 92 |
| cluster.clj | 3583 | 2388 | 769 | 273 |
| cluster/agent.clj | 1756 | 1101 | 445 | 141 |
| cluster/source.clj | 1303 | 929 | 259 | 56 |
| flow.clj | 904 | 644 | 174 | 41 |
| effect.clj | 983 | 692 | 195 | 38 |
| schedule.clj | 804 | 627 | 111 | 15 |
| ai.clj | 1332 | 778 | 371 | 95 |
| edit.clj | 515 | 425 | 47 | 5 |

Reference: kaocha `testable.clj` 253 total / 188 code; clj-reload `core.clj` 438 / 290 code; babashka-process `process.cljc`
719 / 487 code (149 docstring lines). So prose is about 25 percent in Seon, about 20 to 35 percent in the libraries too: **comments and
docstrings are not the reason for the size gap.** Code lines are: Seon's 11 085 code lines in these ten files versus about 1 019
in the four comparable library files listed. The gap comes from features and ceremony, covered below.

Contract density (in-source `:malli/schema` metadata blocks): test.clj 52, runner 55, cluster.clj 83, agent 50, source 50, effect 15.
Almost every defn is contracted (test.clj 52 contracts / 56 defns). The `:or` error-alternative lists on a contract such as
`seon.test/run` (`test.clj:2207-2234`, 13 error alternatives) are 10 to 20 lines each; across the area this is on the order of
1.5 to 2.5k lines (est.). The CLAUDE.md law ("contract every admitted function") makes that cost structural, not accidental.

---

## 1. Pairings: Seon namespace vs closest library code

### 1.1 Test selection and running: `seon.test` + `seon.test.runner` vs kaocha / clojure.test

Seon: `src/seon/test.clj` 2608 + `src/seon/test/runner.clj` 2107 = 4715 lines (plus `test/cache.clj` 249, `test/accretion.clj` 131).
Library: kaocha `testable.clj` 252 + `type/ns.clj` 72 + `type/var.clj` 63 + `type/clojure/test.clj` 26 + `plugin/filter.clj` 169 +
`load.clj` 47 + `result.clj` 65 = 694 lines for load, filter, run, count. Plus `clojure.test` itself (about 800 lines, reference-code/clojure).

What each does:

- kaocha: tests are a tree of "testables"; `load` produces the plan, filter plugin prunes by id/metadata, `run` walks the tree and
  `clojure.test/do-report` events are folded into counters. Selection is `--focus`/`--skip` (ids and metadata keys), applied on
  the in-memory plan: `kaocha/plugin/filter.clj:90-110` (`matches?` then `skip-test` or recurse). Result is a map with counts.
  Running one var is 63 lines: `kaocha/type/var.clj:30-44` (`with-report-counters`, call the test, `do-report :end-test-var`).
- Seon `select` (`test.clj:701-1068`, 368 lines in one function) decides *which* tests to run from recorded evidence: it
  pulls every run, member, source and reach fact for the branch (`test.clj:735-770` `db/q` over `:seon.test.run/*` attributes),
  computes changed definitions (`changed-since-green` `test.clj:71-108`, `changed-definition-symbols` `593-642`,
  `selection-seeds` `643-683`), then walks calls/references/reach edges to find affected tests. Then `admit-run`
  (`test.clj:1174-1399`, 226 lines) writes admission facts; `run` (`test.clj:2207-2570`, 364 lines) builds a world per member
  (`clean-request-handle` `1819-1915`, `program-overlay-tx` `1636-1703`, `member-result` `1969-2088`), runs `run-var!`
  (`runner.clj:442-479`) and `commit-results!` (`runner.clj:1895-1933`) and `record-latest-tx` (`runner.clj:1610-1788`, 179 lines).
- The reach computation is a hand-built transitive-closure graph engine: `runner.clj:820-1100` (`reach-declarations`,
  `reach-graph`, `reach-postorder`, `reach-closures`, `closure-digest`, `reach-entry`, `reach-entries`, `reach-digests`),
  about 280 lines, with weight accounting for the central cache (`reach-weight`, `runner.clj:820` area).
  Kaocha has no analog; kaocha `--watch` (`kaocha/watch.clj`, 379 lines) reruns by file change using tools.namespace, and
  `kaocha.plugin.filter` has no dependence analysis. The nearest idea is `clj-reload`'s dependency graph (section 1.2).

Why the library is shorter (concrete):

1. **It does not persist evidence.** Kaocha returns a result map; nothing is stored, so there is no `record-latest-tx`
   (179 lines), `commit-results!` (39), `failure-replacement-tx` (41), `recorded-member-results` (73), `latest-results` (52),
   `complete-members` (140), `prepare-failures!` (92). That is about 620 lines of runner.clj that exist only to write test results as
   Datahike facts with delta transactions (`runner.clj:1610-1621` explains a 157,981-datom storm that forced the delta design).
2. **It does not decide selection from history.** Selection is by id/metadata given on the command line. Seon's `select`,
   `changed-since-green`, `green-members`, `stale`, `stale-in`, `verified?`, `digest-differences`, `selection-admission` etc.
   (`test.clj:71-108, 684-700, 1419-1453, 1547-1635`) are about 700 lines for "only run what changed since last green".
3. **It does not rebuild a world per test.** Kaocha calls the test function in the same JVM namespace. Seon forks an agent branch
   per member (`test.clj:1819` `clean-request-handle`, 97 lines; `acquire-context!` 201 lines in `cluster/agent.clj:1049`), applies the
   program overlay, runs inside SCI custody, and unlinks (`release-context!` `cluster/agent.clj:954-1007`).
4. **It does not guard JVM-global state.** Seon snapshots and checks ambient state (`runner.clj:645-709` `ambient-snapshot`, 65 lines;
   `restore-live-cluster-schema!` `runner.clj:548-633`, 86 lines, mostly docstring about a 2026-09-17 incident) and excludes
   "destructive" tests via `destroyers`, `destructive-reach`, `destructive-paths`, `host-exclusions`, `host` (`test.clj:351-533`, 1928-1968), about 260 lines.
5. **Idiom: multimethod dispatch on a type plus hierarchy** (`kaocha/type/clojure/test.clj:1-12`: `defmethod testable/-load`, `-run`; 3 `hierarchy/derive!`
   lines). The whole suite/ns/var tree is three tiny files. Seon has one `run` that is a 364-line `let` with 13 error alternatives
   in its contract.
6. **Result is a counter map, not a schema family.** `kaocha/result.clj` is 65 lines of arithmetic over `{:kaocha.result/pass ...}`.
   Seon has `:seon.test.run/member`, `:seon.test.failure/*`, `:seon.test/run-result`, reach-digest, etc. as stored entities (the schema
   resource files are outside this area, but the runner functions that populate them are the lines above).

Honest limit: the *incremental, evidence-based* selection and per-test isolation are product features (the CLAUDE.md contract says a
merge accepts only on recorded evidence). The reducible mass is the persistence/replacement-delta/ambient-state layers and the duplicated
hand-rolled graph walk, not the idea.

### 1.2 Reloading changed namespaces and dependents: `seon.cluster` (reload part) vs clj-reload

Seon: `cluster.clj:1836-2100` roughly 260 lines of reload ordering and dependent selection:
`reload-order` (1836-1862, topological sort by selecting the first `ready` namespace in a sorted set, O(n^2) with set intersections),
`namespace-requires` (1864-1879, Datalog read of `:seon.ns/requires`), `reloadable-namespace?` (1881-1887),
`load-development-definitions!` (1889-1900, calls `(require namespace-name :reload)`), `call-with-program-change` (1902-1936),
`declaration-reload-rule` (1952-1966) and `development-namespaces` (1970-2072, 103 lines, which classifies each changed declaration by
`compiled-into-callers` / `var-indirection` / `unknown` to decide whether dependents must reload).
Then `development-source-refresh!` (`cluster.clj:2486-2620`, 134 lines) and `verify-development-sources!`, `loaded-program-digests`,
`arm-host-program!`, `adopt-entities!` (`2441-2485`) around it.

Library: `clj-reload/core.clj` 437 lines in total, whole feature (scan mtime, parse ns forms, build dependency graph, topo unload, reload,
keep-state): `scan` (`core.clj:206-268`), `ns-unload` (`270-285`), `ns-load` (`287-302`), `unload`/`reload` (`304-380`); `parse.clj` 175
lines reads the `ns` form; `keep.clj` 190 lines for keeping defonce-style state; `util.clj` 149.
Core quote: unload is `(remove-ns ns)` then `(dosync (alter @#'clojure.core/*loaded-libs* disj ns))` (`core.clj:283-285`).

Why shorter:

1. **It reloads the transitive dependents unconditionally**; it never classifies declarations. Seon's `declaration-reload-rule`
   and `compiled-into-callers` set (`cluster.clj:1944-1966`) exist to skip dependents when a changed defn is not inlined; that is an
   optimization needing about 120 lines (1938-2072) with measured justification absent in the source.
2. **It reads the file system, not a database.** Dependency edges come from parsing the ns form (`parse.clj`) on changed files; Seon first
   stores requires as `:seon.ns/requires` facts, then queries them back (`namespace-requires`), then topo-sorts in a fresh function.
   clj-reload also topo-sorts (`core.clj:185` `topo-sort-fn`, 8 lines using `clj-reload.graph`/`tools.namespace`-style graph).
3. **It owns no adoption protocol.** Seon's reload is wrapped by `call-with-program-change` (loaded-identity atom set to unknown,
   advance on success) plus `adopt-entities!`, `adoption-record`, `adoption-guard-tx`, `published-changes`, `candidate-gate!`,
   `rehearse-source!`, `rehearsal-adoption!` (`cluster.clj:2441-2930`, about 490 lines). Those are the "publish then adopt then arm"
   product semantics and are the bulk; the reload proper is the 260 lines above.
4. **Failure model**: clj-reload returns the exception or throws (`core.clj:334-380`, one `:throw` option). Seon returns typed refusals
   (`refused!` plus contracts) at each step.

### 1.3 Agent graphs: `seon.flow` + `cluster/agent.clj` + `cluster/wake.clj` vs core.async.flow

Seon: `flow.clj` 903, `cluster/agent.clj` 1755, `cluster/wake.clj` 578, `cluster/host.clj` 200, plus graph start in `cluster.clj:3199-3560`
(`serve!` 75, `arm-agents!` 107, `disarm-agents!`, `loop-handle`, `cluster-graph-definition`).
Library: `core/async/flow.clj` 346 (of which 267 are docstring/string, only 54 code lines), `flow/impl.clj` 323, `spi.clj` 95,
`impl/graph.clj` 28; flow-monitor `flow_monitor.clj` for inspection.

What Seon builds on top:

- **`flow/var-process`** (`flow.clj:319-421`, 103 lines, 24 lines of docstring explaining refusals) wraps `flow/process` and refuses non-var steps,
  `:mixed` workloads, and args without `:seon.env/environment`. A `flow/process` call is (`flow.clj:165`) a single form; the refusals are validations.
- **A second execution engine, the work launcher**, `flow.clj:469-760`: `execute-io-work!` (53), `work-launcher-step` (38), `start-work-launcher!` (46),
  `stop-work-launcher!` (58), `interrupt-work!` (26), `submit!` (85), `io-terminal!` (28). It is a flow proc running a
  bounded-parallelism executor with per-submission receipts. This is what `seon.effect` calls (`effect.clj:501, 528-534, 910`).
  core.async.flow already gives `:workload :io` (`flow.clj` docs) and `futurize` (`flow.clj:334`); the launcher is a hand-built
  admission/timeout/interrupt layer on top.
- **Per-agent graph** (`cluster/agent.clj:563-612` `graph-definition`) has exactly three procs (mailbox, turn, schedule) and one connection
  (`:conns [[[:seon.agent/mailbox :seon.agent/episode] [:seon.agent/turn :seon.agent/episode]]]`, `agent.clj:610`). Lifecycle:
  `arm!` (1250-1300), `arm-graph!` (1301-1394, 94 lines), `stop-graph!` (1535-1585), `disarm!` (1492-1534).

Why the library is shorter:

1. **It is a protocol (`spi/ProcLauncher`: `describe`, `init`, `transition`, `transform`, `spi.clj`) and an `impl` that owns channel wiring.**
   One generic mechanism; Seon adds 3 hand-rolled procs plus a 4th engine (work launcher) instead of using one `:io` proc per
   effect type.
2. **It never touches durable state.** Seon's procs read databases, write receipts, convey environments (the `env/carry` calls at
   `agent.clj:575-607`). flow.clj docstring itself says "flow conveys no bindings anywhere by design", then spends 100+ lines
   re-conveying them.
3. **It does not enforce policy at construction** (no `refuse non-var`, `refuse :mixed`, `refuse args without environment`); the Seon
   throws in `var-process` (`flow.clj:340-390`) are three hand-written checks that could be a Malli contract on the launcher.
4. **No bespoke buffers.** `agent.clj:575` builds `CountedSlidingBuffer. (LinkedList.) 1 (atom 0)` (a counted `sliding-buffer 1`), a
   custom class around `clojure.core.async/sliding-buffer` to count dropped wakes. flow's `:chan-opts` accepts a plain `(async/sliding-buffer 1)`.

Honest limit: CLAUDE.md requires a Flow graph per agent. A single proc running `turn-step` fed by a sliding-buffer channel would need
about 60 lines of graph code (est.) versus the current mailbox+turn+schedule split; the schedule proc exists only for maintenance (section 4).

### 1.4 Shell: `seon.shell` + `shell/jvm.clj` + `cluster/process.clj` vs babashka.process

Seon already uses the library: `shell/jvm.clj:373` calls `process/process` with `:shutdown process/destroy-tree`. So the pairing is
"what Seon wraps around it": `shell/jvm.clj` 464 + `shell.clj` 28 + `cluster/process.clj` 330 + `await.clj` 153 + `my/shell.clj` 42 + share of `effect.clj`
= about 1000 lines to run one argv with a time limit. Library: `babashka/process.cljc` 718 lines (including `shell`, `exec`, `pipeline`, `sh`).

Where the lines go in Seon:
- stdin sources (`copy-blob-stdin!` `shell/jvm.clj:156-215`, 59 lines; `stdin-task` 215-243): blob-digest stdin (`:seon.blob/digest`); the library takes `:in` as a stream/string/file.
- bounded capture with descriptors to blobs (`capture-task`, `output-descriptor` `303-323`).
- timeouts and reaping (`await-exit` 245-280, 36 lines; `reap!` 281-302; calls `cluster.process/terminate-subprocess!` 184-218 which signals pids and
  verifies `(pid, start-instant)` identities, `cluster/process.clj:57-230`).
- `cwd-path` (40-72, 33 lines) a no-follow cwd check against `seon.fs`.
- virtual-thread task helper `virtual-task` (87-103) and `task-outcomes` (324-352).

Why babashka-process is short: it is a thin `ProcessBuilder` wrapper returning a record that implements `IDeref`; `check` is 10 lines
(`process.cljc:95-110`), `destroy-tree` is 6 lines (`165-172`), `:out :string` slurps in a future, and timeout is left to the caller
(`(deref p 5000 ::timeout)`). It does not do: identity of processes across recycles, blob-backed streams, descendant verification,
bounded output, no-follow cwd. A Seon capability that only needs "run argv, capture at most N bytes, kill on time limit" is
`process/shell {:out :string :timeout ...}`-class code in about 60 lines (est.).

### 1.5 Edits: `seon.edit` + `edit/jvm.clj` + `my/edit.clj` vs rewrite-clj

Seon: `edit.clj` 514 + `edit/jvm.clj` 168 + `my/edit.clj` 112 = 794. It already requires `rewrite-clj.zip :as z` (`edit.clj:7`) and
documents the dependence on rewrite-clj node protocols (`edit.clj:140-143`). So most of the structural work is already delegated;
what remains is selectors (`form` `edit.clj:314-374`, 61 lines; `exact` 396-453, 58; `lines` 454-499, 45;
`matched-form-result` 287-313; `lossless-candidate` 261-286; `actual-edit` 241-260), candidate evidence for ambiguity
refusals, byte windows and digest fencing. rewrite-clj gives `z/of-string`, `z/find-value`, `z/replace`, `z/root-string` (`zip.cljc`
1151 lines); a replace-one-top-level-form edit is about 10 lines with it. The Seon extra is the refusal vocabulary
(`parse-refused`, `lossless-check-failed`, `no-match`, `ambiguous-match`), which is the product contract for agents.
This is the least inflated pairing: 5 comment lines in 515, density is high. Reduction here is small (section 3, item 9).

### 1.6 LLM provider: `seon.ai` vs langchain4clj

Seon: `ai.clj` 1331 lines, hand-written HTTP (`send-request` `ai.clj:1138-1268`, 131 lines), SSE/streaming folds (`stream-event` 652-719,
`stream-fold` 720-772, `stream-chunk-error` 616-651, `streamed-completion` 1057-1099), usage normalization, truncation (`996-1030`),
wire settings (`wire-settings` 457-493), `request-body` (562-601), plus `ai/tokens.cljc` 210.
Library: langchain4clj `core.clj` 624, `streaming.clj`, `tools.clj`, `messages.clj`, ... (6303 total) but it **delegates the wire** to the
LangChain4j Java library (OpenAI/Anthropic builders), so Clojure code is builders and translation of keyword maps to Java objects.

Why shorter: it delegates HTTP, SSE parsing, retries, provider quirks, usage objects to Java; it stores nothing. Seon rejects retries
and failover by owner ruling (`ai.clj:2-4`) and wants a durable `:seon.ai/attempt` per call, with usage fields normalized by hand
per provider. Switching to a Java client dependency would be a new heavy dependency (and would need prompt-cache control). The
llm-providers skill states provider request construction and prompt caching are verified by Seon; I would not recommend a
swap. Reduction is small (item 10): 371 docstring lines and per-provider branches could be trimmed.

### 1.7 Clusters/branches: `cluster/registry.clj`, `cluster/store.clj`, `cluster/source.clj`, `cluster/boot.clj` vs datahike branching

Seon: registry 661 + store 738 + branch 14 + source 1302 + boot 824 + the schema/source population section of `cluster.clj` (498-1400, 900 lines) =
about 4.4k. Library: `datahike/versioning.cljc` 769 lines, whole branching/merge API: `branch!` (`versioning.cljc:212`), `delete-branch!` (279),
`force-branch!` (323), `merge!` (738), `branch-history`, `parent-commit-ids`; `datahike/gc.cljc` `gc-storage!` (89) and konserve for storage.

Seon wraps these with: roster (`registry.clj:110-130`), head guards (`157-175` `head-guard-tx`), `branch!` (209-256), `fork-entity` (257-292),
`reset-cluster!` (319-357), `retire-branch!` (358-390), `retire-unclaimed!` (413-434), blob reference tracing for GC
(`referenced-blobs` 475-534, `collect-and-inventory!` 561-587, `retention-cutoff` 588, `collect!` 629+ about 100 lines). Flock acquisition in store.clj.

Schema/source population (`cluster.clj:498-1230`, about 730 lines): `accretive-property-change?`, `declaration-property-changes`,
`attribute-adoption`, `attribute-change-tx` (689-755), `retire-attributes!` (830-900), `drop-retired-families!`, `schema-entity-changes`,
`instruction-entity-changes`, `entity-lookup-refusal`, `transact-initialization!` (1105), `accrete-schema-population!` (1154-1227). That is
an in-place Datahike schema migrator (compares declarations, picks install / converged / accrete / replace / retire per attribute).
Datahike (schema-on-write) offers only `d/transact` of schema entities and `:db/ident`s; it has no migrator, so some code is needed, but
the migrator is a candidate for deletion if schema declarations were append-only (additive population only; see item 3).

Why Datahike is short: it implements branching as a pointer on shared immutable indexes (as CLAUDE.md says); it has no roster, no
lifecycle facts, no blob GC accounting, no reachability from Seon's `seon.blob` digests. Those are the Seon lines.

---

## 2. Why the libraries do comparable work in far fewer lines: the six recurring causes

Each is cited from both sides above; this section lists the idioms.

1. **Data in, data out; no recorded evidence.** kaocha `testable/run` (`testable.clj:120-137`) returns the testable plus `::events`; no database.
   Seon stores every test member, failure, reach digest and run (`runner.clj:1610-1933`), then reads it back (`1822-2107`), then re-derives
   selection from it (`test.clj:701-1068`). About 2.1k lines (est.: `record-latest-tx` 179, `complete-members` 140, `prepare-failures!` 92,
   `recorded-member-results` 73, `latest-results` 52, `commit-results!` 39, `failure-replacement-tx` 41, `select` 368, `admit-run` 226,
   `member-result` 120, `bounded-result` 121, `verified?` 48, `digest-differences` 32 ...).
2. **Multimethods and hierarchy instead of a closed sum type with a contract.** kaocha `testable/-load`/`-run` are open: adding `:kaocha.type/ns`
   is 72 lines (`type/ns.clj`). Seon error unions are closed lists in contracts: `seon.test/run` lists 13 alternatives (`test.clj:2207-2234`),
   `select` lists 6 (`701-715`). Every new refusal means editing every caller's contract.
3. **One `let`-chain function with refuse-at-each-step vs library-level exceptions.** `select` is 368 lines with 8 `refuse!` calls in the
   binding vector (`test.clj:720-775`); kaocha `run-testable` (`testable.clj:158-211`, 54 lines) has one `cond` and dispatches to hooks.
4. **Plugins/hooks as extension points vs inline features.** kaocha `plugin/run-hook` (`testable.clj:123-124`) is how `--watch`, `--fail-fast`,
   filter, profiling all attach; each is its own small file. Seon puts equivalents (filter by identities/namespaces/changed, long-test
   inclusion, destructive exclusions, host exclusions, check time limits) inline in `select`/`run`.
5. **Delegating to the dependency's own state.** clj-reload keeps `*state` in one atom (`core.clj:54`) and lets `*loaded-libs*` be the truth.
   Seon stores loaded-program identity in an atom passed around (`call-with-program-change`, `cluster.clj:1902-1936`), in facts, and in a
   cached `loaded-program-digests`, three representations that must be kept equal.
6. **No second system.** babashka-process, rewrite-clj, kaocha, and Datahike are each one library with one concern. Seon's tool
   layer for fs/shell/edit/web goes through `seon.effect` (982 lines) which adds a durable request/receipt protocol, work launcher, interrupt
   and settlement (`effect.clj:280-342, 460-540, 588-640, 735-972`), then `my.*` wrappers, then `*.jvm` handlers. Each tool is three
   layers (my.fs 109 + effect share + fs/jvm 910; my.shell 42 + shell/jvm 464; my.edit 112 + edit/jvm 168 + edit 514).

---

## 3. Top 10 reductions (est. lines removed; risk)

Estimates assume the behavior contract of the agent-facing `my.*` API stays unchanged. "est." = my judgment from reading the owner
functions, not measured by deleting.

| # | Seon code (file:line range) | Replacement | Lines removed (est.) | Risk |
|---|---|---|---|---|
| 1 | `test/runner.clj:1337-1933` (`prepare-failures!`, `complete-members`, `failure-replacement-tx`, `record-latest-tx`, `recorded-member-results`, `commit-results!`) plus `test.clj:1969-2088` `member-result`, `215-335` `bounded-result` | Store one result entity per (member, run) as a plain assert with a single `:db.fn/call`; keep only latest-by-attribute upsert. Delete delta/replace machinery (the 157,981-datom note, `runner.clj:1610-1621`, says the cause was retract-and-reassert; an upsert on a unique `:seon.test/sym` avoids it with ~40 lines). | 700 | Medium: touches the evidence schema and render consumers (`render/test.clj`, `render/var.clj`) |
| 2 | `test.clj:701-1068` `select` plus `71-108`, `593-700`, `1069-1166`, `1419-1453`, `1547-1635` | One incremental rule: tests whose `reach-digest` differs from their last green member's digest, or have no green member. Reuse the already-computed reach digests; drop `selection-seeds`, `changed-since-green`, `stale`, `stale-in`, `digest-differences`, `selection-facts` multi-read (read via one query per attribute set). Keep `:named` selection by identities/namespaces (about 30 lines, kaocha's `matches?` shape, `plugin/filter.clj:90`). | 700 | Medium: selection correctness is a gate; needs the incremental regression suite |
| 3 | `cluster.clj:498-1227` schema migrator (`attribute-adoption`, `attribute-change-tx`, `retire-attributes!`, `drop-retired-families!`, `schema-entity-changes`, `accrete-schema-population!`) | Accrete-only: install missing declarations, assert converged ones are equal, refuse otherwise. Retirement and non-accretive replacement become explicit operator tasks rather than live migration. | 450 | Medium-high: CLAUDE.md says schema changes are proven incrementally on a live store; deleting replace/retire paths changes what can be adopted |
| 4 | `cluster.clj:1938-2072` reload dependents selection + `1836-1900` `reload-order` | clj-reload (`reference-code/clj-reload`, 437 line core) or `clojure.tools.namespace.dependency` for topo sort; reload all transitive dependents; drop `declaration-reload-rule`, `compiled-into-callers`, `reload-identity-attribute?`, `reloadable-namespace?`. | 180 | Low-medium: dev adoption gets slower for edits that only touched non-inlined defns; measure first (CLAUDE.md "profile before ranking") |
| 5 | `schedule.clj` (803) + `maintenance.clj:23-200` result projections + the schedule proc in `cluster/agent.clj:575-612` | Three nightly jobs (`footprint`, `rotate-logs`, `collect!`; `schedule.clj:46-62`) become a single `ScheduledExecutorService` started at boot in `seon.cluster.boot` (about 40 lines), no cron-utils, no durable `:seon.schedule.*` entities, no per-agent schedule proc. | 750 net of 40 added | Medium: removes `:seon.schedule*` schema (resources), `my.program` schedule-task reads (`my/program.clj:301, 620, 655`), and the agent's third proc. No agent-facing API depends on it (`grep` shows only `error.clj`, `cluster/agent.clj`, `cluster.clj` require it). |
| 6 | `flow.clj:469-760` work launcher + `effect.clj:460-540` dispatch and `settle-call`/`interrupt-call` (280-385) | One `:io` proc per capability using `flow/futurize` or a plain `Executors/newVirtualThreadPerTaskExecutor`, with `Future.get(timeout)` and `cancel(true)` for the bound. Keep the one durable receipt write per call. | 450 | Medium-high: interrupt/terminal semantics are specified (`effect.clj:468` "named terminal event"); must not regress await-exit behavior |
| 7 | `flow.clj:319-421` `var-process` refusals, `flow.clj:132-195` start/join, `cluster/agent.clj:575` CountedSlidingBuffer | Malli contract on the proc map for non-var / `:mixed` refusal; `(async/sliding-buffer 1)`; use `flow/start` and `flow/stop` as shipped. | 150 | Low |
| 8 | `fs/jvm.clj:160-330, 366-560` no-follow, windowed read, whole-file pass, stage-content | `babashka.fs` (already required, `fs/jvm.clj` header) `fs/read-all-bytes`, `fs/glob`, `fs/delete-tree`, with a single `no-follow` guard function (about 25 lines) before open. Keep digest preconditions on write (`precondition-state` 622-650). | 350 | Medium: path ownership safety is a product rule (`fs.clj` doc "safety depends on path ownership"); needs the fs safety regressions |
| 9 | `shell/jvm.clj:156-300` blob stdin, bounded capture, reap + `cluster/process.clj:116-230` subprocess identity tree | `babashka.process/shell` with `:in`, `:out :string`, `:timeout`, `process/destroy-tree`; drop the `(pid, start-instant)` verification for subprocesses (keep it for the JVM root, `process.clj:57-97`). | 300 | Medium: kill-safety and capture bounds are stated requirements; keep a hard byte cap and a time limit |
| 10 | Docstring and rationale prose in the ten biggest files: 3124 string lines + 959 comment lines (table in section 0) | Move incident narratives (`runner.clj:548-633`, `test.clj:1819-1915`, `runner.clj:1610-1621`, `flow.clj:319-345`) to the owning issue or spec; keep a 2 to 4 line docstring with Examples/Errors headings per CLAUDE.md. | 1200 (not lines of behavior) | Low |

Rough total of items 1 to 9: about 4000 lines of behavior code; with prose, about 5200. Out of this area's 27.8k lines that is under 20
percent: **reaching 10k for `src` overall will need the cuts below plus the other areas, not these alone.** The structural change that
dominates is item 1+2 (evidence persistence and selection), items 5+6 (a second schedule/launcher engine beside Flow), and the
contract/error-union ceremony (not itemized; est. 1.5 to 2.5k lines area-wide, only removable by relaxing "contract every admitted function"
for private functions, which is an owner decision).

Further candidates just under the top 10 (each est. 100 to 300 lines): `my/program.clj` 1048 lines (graph reads: `referrers`, `reach`, `capability-of`; compare to
`clj-kondo` analysis output `reference-code/clj-kondo` which provides var-usages directly); `cluster/message.clj` 918 lines for "durable messages
with permanent routing and settlement claims" (a message is a datom plus a `:status` with one `:db.fn/call` claim, about 200 lines est.);
`cluster/prompt.clj` 460 + `bootstrap.clj` 336 (assembly of the model prompt); `health.clj` 733 (see section 4).

---

## 4. Machinery only other machinery uses (call evidence)

Method: `grep -rlE "\[<ns>( |\]|\))|<ns>/" src` excluding the file itself, then checked whether any caller is `my/*`, `seon.mcp`
or a rendered agent surface. Results as of this checkout.

| Namespace | Lines | Callers in src (self excluded) | Agent-facing? |
|---|---|---|---|
| `seon.schedule` | 803 | `seon/error.clj`, `seon/cluster/agent.clj`, `seon/cluster.clj` | No `my.schedule` exists. Only consumer is `seon.maintenance` jobs (`schedule.clj:46-62`). `my/program.clj:301,620,655` merely reads schedule tasks as program facts. Machinery. |
| `seon.maintenance` | 696 | `seon/schedule.clj`, `seon/cluster/boot.clj` | No. Called by the schedule portfolio and boot (log rotation, store collect, footprint). Operator machinery. |
| `seon.cluster.instruction` | 79 | `seon/cluster.clj` only | Seeds instruction facts; rendered through the context but no agent call. Fold into `cluster.clj` seeding or `schema.edn`. |
| `seon.eval.drive` | 420 | `seon/cluster/agent.clj` only | Single-caller; part of the turn loop. Could be a section of `agent.clj` or deleted if the loop is simplified. |
| `seon.test.accretion` | 131 | `seon/fn.clj`, `seon/error.clj`, `seon/turn/batch.clj` | Schema generatability (Malli generator probe) and "retired REPL install gate" error renders (`accretion.clj:2-4` says "kept while its stored errors are shown"). Only schema admission uses it. Candidate to delete when stored errors age out; its own docstring marks `schema-row` (the 35-line first function) as deleted-pending (`task 0dfdcc8e447e`). |
| `seon.test.cache` | 249 | `seon/cluster/source.clj`, `seon/cluster.clj` | Reads declared test-input files for digests used by publication; machinery for item 2's gating. |
| `seon.background` + `my.background` | 118 + 82 | `my/background.clj` only requires `seon.background` | Agent-facing via `my.background`; it depends only on `seon.effect`/`seon.run`. A thin wrapper; fine. |
| `seon.cluster.host` | 200 | `seon/mcp.clj`, `my/agent.clj` | Branch agents run in their own execution (agent-facing via `my.agent/create-branch!`). Needed. |
| `seon.profile` + `profile/measurement` | 642 + 203 | profile: test, db, cluster, instrument, batch, health, error/refusal; measurement: turn, health, turn/batch | Cross-cutting timing cells. `seon.health` is the only reader that renders (via `seon.mcp`/`runtime_status`). Machinery feeding health; useful only if health is kept in this size. |
| `seon.health` | 733 | `seon/mcp.clj`, `seon/fn.clj`, `seon/cluster/boot.clj`, `seon/cluster.clj`, `seon/render/web.clj` | MCP `runtime_status` and web page. Operator-facing (the owner), not a Seon agent API. The ranking/decay logic (see recent commits a8e3bde5b, 0dd360d1c) is feature-heavy. Candidate to shrink to a query list. |
| `seon.cluster.status` | 190 | `seon/health.clj`, `seon/cluster/agent.clj` | Only health and agent accounting. |
| `seon.flow` (work launcher part) | 470 of 903 | `seon.effect`, `cluster/boot.clj` | Only `seon.effect` calls `submit!`/`interrupt-work!` (`effect.clj:501,528,534,910`). Second execution engine beside the per-agent graphs (item 6). |
| `seon.await` | 153 | turn, effect, flow, cluster/boot, cluster, cluster/host, shell/jvm, cluster/agent, render/web | Used widely, but its job is `(deref f timeout)`; a 20-line helper next to the Java futures. |
| `seon.test.runner` | 2107 | called by `seon/test.clj`, `db.clj`, `problems.clj`, `cluster/source.clj`, `sci/eval.clj`, `render/var.clj`, `cluster/store.clj` | Agent-facing only through `my.test` (75 lines) via `seon.test/run`; `cluster.clj:2632` calls `seon.test/run` through `requiring-resolve`. The 5k lines are the engine behind one request/response. |
| `seon.test` | 2608 | only `seon/problems.clj` and `seon/render/test.clj` use the namespace by name; callers use `seon.test/<key>` as keywords (`seon.test/*` attribute names), not the namespace's functions | Entrant is `my/test.clj` + `cluster.clj:2632`. |

Two further observations backed by greps in this session:

- `seon.flow` header says "Production-shaped core.async.flow launchers used by the standing testbed" (`flow.clj:2`): the namespace name
  and doc describe a testbed, but it is imported by `cluster/agent.clj`, `cluster/boot.clj` and 12 other files (50 test files reference it).
  Its non-launcher functions have 0 callers other than via the aliases listed above; no dead public functions were found among
  `start-graph!`, `join-graph!`, `stop-off-thread!`, `interrupt-work!`, `start-work-launcher!`, `stop-work-launcher!`, `submit!`,
  `hand-off-fault!` (each has 1 to 3 non-test callers).
- `seon.task` (653 lines) holds two systems: a file-backed task index (`task.clj:46-310`: `task-files`, `adopt-tasks-tx`, `index-tasks!`,
  `task-edges`, `task-findings`, `candidate-dangling`) used by orchestrator process tooling (`resources/seon/tasks/*.edn`), and the
  agent-facing create/assign/steps/propose/decide (`task.clj:353-653`) used by `my/task.clj`. The first half (about 300 lines) is
  not required by any agent call, only by `source.clj`/`cluster.clj` publication (`index-tasks!`, `cluster/source.clj:516-545`).

---

## 5. Where the biggest single functions are (for targeted attack)

Longest top-level forms in the area (start line, length):

- `seon.test/select` `test.clj:701` 368; `seon.test/run` `2207` 364; `admit-run` `1174` 226; `bounded-result` `215` 121; `member-result` `1969` 120.
- `seon.test.runner/record-latest-tx` `1610` 179; `complete-members` `1470` 140; `prepare-failures!` `1337` 92; `restore-live-cluster-schema!` `548` 86.
- `seon.cluster/full-source-refresh!` `1665` 171; `populate-source!` `1228` 169; `development-source-refresh!` `2486` 134; `arm-agents!` `3382` 107; `development-namespaces` `1970` 103.
- `seon.cluster.agent/acquire-context!` `1049` 201; `arm-graph!` `1301` 94; `submit-source-in-projection` `690` 91.
- `seon.cluster.source/publish!` `560` 164; `accept-merge!` `945` 98; `prepare-merge!` `851` 94; `proven-file` `1109` 84.
- `seon.effect/request*` `735` 238; `dispatch` `460` 77. `seon.flow/var-process` `319` 103; `submit!` `760` 85.
- `seon.ai/send-request` `1138` 131.

CLAUDE.md asks that long functions be split by responsibility. Several of these (`select`, `run`, `request*`, `acquire-context!`) are single
`let` chains: they are both the largest line mass and the hardest to test; each is a good first slice for a Sonnet lane because
the behavior is covered by named tests (`test/seon/test_test.clj` 304 lines, `test_runner_test.clj` 466, `cluster_test.clj` 491).

## 6. Evidence gaps (what I did not do)

- I did not measure runtime or run any tests (task forbids JVMs). The line reductions are estimated from reading, not from trial deletion.
- I read opening sections and selected function bodies of the large files; I did not read every line of `cluster/source.clj` merge logic,
  `cluster/message.clj`, `cluster/wake.clj`, `health.clj` or `profile.clj`, so entries for them in sections 3 and 4 are weaker.
- Test-side sizes (97k lines) are out of scope; item 1 and 2 would also shrink tests proportionally, but I did not count them.
- Reference line counts: kaocha (src 4281), clj-reload (951), core.async (src 6864 total, flow files listed above), babashka-process (724 incl. pprint),
  rewrite-clj (8057 total, `zip.cljc` 1151), langchain4clj (6303 total, `core.clj` 624), datahike `versioning.cljc` 769.

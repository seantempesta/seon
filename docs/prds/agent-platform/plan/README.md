---
type: plan
status: DRAFT written from the audit summaries before the notes were read in full — superseded by the per-lane specs `lane-*.md` in this directory; its totals are wrong (test corpus ≈5 % deletable, not 14 %; the lever is hoisting setup); rewritten once the lane specs land
created: 2026-09-21
tags: [plan, agent-platform, cut, deletion, namespace-agents]
---

# The cut: one branch, every seam, then the live loop

**Owner, 2026-09-21:** "identify all the code we can delete … code that's
duplicating behavior (in a worse way) than what the libraries are doing …
duplicate code paths, duplicate caches." "A plan that will aggressively cut
the code and provide clear instructions to the agents implementing it on
the reference code to build against … I want them to read and understand
good clojure code relevant to what they are implementing before they are
implementing it. I want them to use the REPL to test ideas, inspect data
and look at perf. I want them to find even better ways to do the fixes …
I don't want everything to be gated on tests running to completion
especially when so many of the tests are part of the problem." "If this
experiment doesn't work we can always revert back to git."

Evidence: the eight audits and their synthesis in
[../research/](../research/synthesis-2026-09-21.md). Every goal that must
survive is in
[durable-goals-and-rulings](../research/durable-goals-and-rulings-2026-09-21.md)
(55 targets, 89 rulings, 36 retired directions). Nothing in this file
repeats a table that lives there; it points.

## 0. The shape

- **One branch, `agent-platform-cut`, from `steward-platform`.** Every lane
  commits to it, path-limited. `steward-platform` is the revert.
- **Cut ≈ 20 K src lines and ≈ 14 K test lines** (22 % / 14 %), the docs
  tree to 9 % of itself, AGENTS.md to its laws. Totals per seam are in the
  synthesis §2.
- **The gate is not the suite.** A lane is done when: its namespaces load
  (`clojure -M -e "(require 'ns…)"`), its REPL probes show the fact or the
  number it claims, the deftests that reach its change pass in-process
  through `seon.test/run-owned` on a forked branch, every time escape in its
  files is converted to a bound or deleted, and the diff is net-negative.
  The orchestrator runs boot-from-zero once per integration, never a full
  suite.
- **Lanes are astra at medium** (these are design cuts), launched through
  `bin/codex-agent`, three at a time, one JVM each. The orchestrator
  designs, reads every diff, integrates, measures.
- **Each lane's first act is reading.** The reading list below names the
  vendored library code (file and line block) and the first-party idiom
  the lane builds on. A lane that has not read the seam does not edit.
- **Each lane's second act is a REPL probe** on its own scratch cluster
  (`bin/seon --root tmp/<lane>-root start <lane>`; downed and deleted when
  done) or the JVM REPL of `default` read-only: the current cost or shape,
  as a number, before the cut; the same probe after.
- **Better is welcome.** The audit tables are the floor. A lane that finds
  a smaller mechanism than the audit proposed takes it and records why.
  A lane that refutes an audit claim with evidence records the refutation
  in its landing note; the note is the deliverable, never chat.

## 1. Phase A — the base every other cut reads (hours, two lanes + orchestrator)

### A0 Instructions (orchestrator, first, same day)

AGENTS.md rewritten to ~250 lines: the five laws, the vocabulary table with
[TARGET] rows verified or deleted, the REPL loop, one page of current
state. Outline and surviving line ranges:
[instructions audit §1](../research/instructions-and-process-audit-2026-09-21.md).
Docs archived by the eleven `git mv` moves in its §4; the eight AGENTS.md
links repointed. Memory index: the 24 superseded handovers deleted, the 32
product rules folded in. `tmp/` swept (7.9 GB; `tmp/head-wt` worktree,
86 lane directories).

### A1 The projection is read, never rebuilt (astra, medium)

The single deletion every other seam depends on: the database value
already carries its compiled registry (`seon.db/carried-projection`,
`src/seon/db.clj:1168`). Nine derivations, four digest schemes and three
cache holders rebuild it.

- **Owned:** `src/seon/schema.clj`, `src/seon/schema/*.clj`,
  `src/seon/instrument.clj`, `src/seon/call_preparation.clj`,
  `src/seon/test/arm.clj`, their tests, `reference-code/malli`.
- **Cut (floor):** [malli audit](../research/deletion-audit-malli-2026-09-21.md)
  tables — `projection-rows`…`projection-from-database`
  (`schema.clj:2514-2810`, 138 callers become reads of the carried value);
  `call_preparation.clj:604-748` (argument structure from the retained
  compiled contract: `m/-function-schema-arities`, `m/-function-info`,
  `m/entries`); the classpath fallback `schema.clj:929-1100`; the
  per-wrapper recompile `instrument.clj:747-754` and the second
  whole-population projection in `apply!:993-995`; `contract-digest`
  `:872-874`; `assert-config-display!` `schema.clj:1206-1225`;
  `supplied-projection` `instrument.clj:604-620` (three scans per call).
  `fn/schema_shape.clj:21-57` → `m/ast`/`m/from-ast` (RESET NEEDED).
- **Read first:** `reference-code/malli/src/malli/core.cljc` — registry
  and `-schema` (`:1943-1950`), `-function-schema-arities`/`-function-info`
  (`:2771-2798`, `:3118-3143`), `ast`/`from-ast` (`:2848-2875`), `walk`,
  `-memoize`; `reference-code/malli/src/malli/registry.cljc` whole;
  `malli/error.cljc:44-181` (`default-errors`); first-party idiom: the
  lazy registry that already landed, `src/seon/schema.clj:454-503`.
- **Fork:** keyword-type entries in `default-errors`; deletes
  `error.clj:977-1000`. Probe `generator.cljc:299-310` per-call registry
  enumeration; fix in the fork if measured.
- **Probe before/after:** `schema/projection-from-database` on `default`
  (the test-system audit measured 3,996 ms — the last O(program) step in
  the 37 ms fixture fork); one arming pass; `m/ast` round-trip of every
  stored form preserves `{:closed false}`/`{:optional false}`.
- **Stop rule:** none for held files — A2 owns `db.clj`; the seam between
  them is `carried-projection`, which A1 reads and A2 does not change.

### A2 Datahike: three answers become one (astra, medium)

- **Owned:** `src/seon/db.clj`, `src/seon/schema/datahike.clj`,
  `src/seon/store.clj`, `src/seon/cluster/store*.clj`, `src/seon/blob.clj`,
  their tests, `reference-code/datahike`, `reference-code/konserve`.
- **Cut (floor):** [datahike audit](../research/deletion-audit-datahike-2026-09-21.md)
  tables — read-currency mechanisms 2 and 3 (`db.clj:1102-1144`,
  `replay-read :971`, `stable-value :530`, ~405 lines); the multi-arity
  `diff` family with no caller (`:2797-3223`, keep the one-arg arity
  `turn.clj:2224` uses); the EDN-string codec (`schema/datahike.clj:118-124`)
  and the decode walker (~260); the O(program) checks inside the
  final-report validator (`write-render-target-error :3904`,
  `arity-mismatches-with :3833`) moved off the commit path; the two tests
  that police that cost (`error_write_timing_test.clj`,
  `publication_validation_test.clj`); nine duplicate test classes collapsed.
  Fix the two live defects: `store.clj:564` vs `registry.clj:165`
  liveness; `store.clj:121-124` unreleased flock.
- **Read first:** `reference-code/datahike/src/datahike/query.cljc`
  `:2658-2671` (cache context) and `:2877` (`query-dependency-plan`);
  `pull_api.cljc:16`, `:315`, `:323` (the 1,000 cut);
  `db/schema.cljc:35-55`, `:87`, `:105` (`:db.type/any`);
  `connector.cljc:144`; `versioning.cljc` whole (branch = pointer,
  `merge!`, `fork-database :620`); `writer.cljc` whole (one serial loop per
  connection); `gc.cljc:120-168`; `db/transaction.cljc` coercion;
  konserve `bassoc`/`bget`/`bget-range` and GC; first-party idiom:
  `src/seon/cluster/source.clj` (every mutation through `versioning`),
  `src/seon/blob.clj`.
- **Fork:** the eight changes in synthesis §5, each landing with the
  deletion it enables; pushed to our datahike fork (no upstream PR).
- **Probe before/after:** one publication transaction's final-report
  validator time on `default`; a retained read's currency check; pull of a
  cardinality-many attribute over 1,000 members.

## 2. Phase B — four seams at once (after A1 lands; three lanes running, the fourth queued)

### B1 Publication and operator: one path, no mirrors (astra, medium)

- **Owned:** `src/seon/fn.clj`, `src/seon/fn/*.clj`, `src/seon/program.cljc`,
  `src/seon/cluster.clj` (publication/adoption/development sections),
  `src/seon/cluster/source.clj`, `src/seon/test/cache.clj`,
  `src/seon/operator.clj`, `src/seon/operator/state.clj`,
  `script/seon/fresh_operator.clj`, `bin/seon`, `bin/seon-hook`,
  `.claude/seon-hook.edn`, their tests, `reference-code/clj-kondo`.
- **Cut (floor):** [publication audit](../research/deletion-audit-publication-operator-2026-09-21.md)
  — the 660 caller-less lines of `fn.clj` (`plan-file-change :2443`,
  `build-artifact :2116`, `rows :2552`, `reconcile-tx :3115`,
  `backfill-contract-facts! :2663`, output-path family `:1807-2001`);
  the operator's process-record/advertisement/claim/repair/phase-log layer
  (~1,300: `fresh_operator.clj`, `state.clj` — the OS process table and
  the prepl answer these, `state.clj:1199-1223`, `:1262`); the test-base
  store and worker checkouts (`cache.clj:358-546`); six of seven
  publication entry points and eight of nine progress mechanisms; the
  toolchain double-hash, `published-index-rows :3149` on first adoption,
  per-commit projection re-derivation (`source.clj:151`). The surviving
  path, one sentence: the running JVM's prepl receives the changed paths,
  hashes only those, lints them with clj-kondo's cache, transacts the
  digest difference on `current-src`, reloads exactly the namespaces the
  transaction report names; cold boot and `reset` alone spawn a JVM.
  Absorb the right algorithm from the preserved draft
  (`../research/one-jvm-lane-items-6-8-draft-2026-09-21.patch`) without its
  second pass and second transaction. Convert the 15 algorithm-defect time
  escapes; delete the 2 that die with their mechanism.
- **Read first:** `reference-code/clj-kondo/src/clj_kondo/core.clj` (`run!`,
  `:cache`, `:analysis` output keys); `impl/cache.clj:26-35`
  (`from-cache-1`), `:129` (`load-when-missing`); `impl/analysis` (what a
  var-definition row carries); `reference-code/clj-reload/src` (reload
  ordering by dependents — compare with our `reload-order`,
  `cluster.clj:2428`); `reference-code/datahike/src/datahike/versioning.cljc`
  (commit ids); `script/seon/fresh_operator.clj:1819` (`prepl-eval!`, the
  client that already exists).
- **Fork:** clj-kondo `from-cache-1` skips a `:disk` entry whose
  `:filename` is gone (~3 lines). Probe whether `load-when-missing` can
  replace the stub prelude synthesized per agent evaluation
  (`analyzer.clj:595`) — SCI-only definitions are not in the cache; record
  the answer either way.
- **Probe before/after:** the committed measurement script rows
  (`docs/prds/steward-platform/research/measure-publication-path-2026-09-22.sh`,
  moved with the archive): no change (264 ms), docstring edit
  (2.7–4.3 s → < 1 s), from zero, boot; reset's "program population
  compiled" 117 s explained by algorithm.

### B2 SCI, turn loop, render: the walk is the history (astra, medium)

- **Owned:** `src/seon/sci/*.clj`, `src/seon/turn.clj`,
  `src/seon/cluster/agent.clj`, `src/seon/flow.clj`, `src/seon/plan.clj`,
  `src/seon/render.clj`, `src/seon/render/*.clj`, `src/seon/print.cljc`,
  `src/seon/repl.clj`, `src/my/*.clj`, their tests, `reference-code/sci`.
- **Cut (floor):** [sci/turn/render audit](../research/deletion-audit-sci-turn-render-2026-09-21.md)
  — `render/transcript.clj` hand-assembled views (`:31-675`, `:1502-2442`;
  turn PRD §15: "nothing assembles the history but the walk");
  `flow.clj:241-944` launcher → `flow/futurize` + `:compute` workload +
  `:compute-timeout-ms`; the per-turn watchdog thread `turn.clj:5053-5262`;
  `render/web.clj` keyframe/delta (`:1849-1916`) and per-tab drain
  (`:2689-2891`) → hyperlith's whole-view batch over `(dropping-buffer 1)`;
  the invocation cache `render.clj:692-889` (commit id answers);
  `render/ns.clj:412-833` HTML clipping ladder (§2.4 violation);
  `plan.clj`/`issue.clj` duplicate task lifecycle (~700, coordinate with
  B3 on `issue.clj`); `base-bindings`/`same-program-root?` O(program)
  snapshot per turn fork and the two ctx atoms. Seven test namespaces
  on one turn loop → one class each.
- **Read first:** `reference-code/sci/src/sci/core.cljc` — `init :331`,
  `fork :345`, `intern :260`, `copy-ns`, `eval-string*`, binding;
  `sci/impl/namespaces`, `vars` (generation), `evaluator`;
  `reference-code/sci/doc/interrupt.md`;
  `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:78`,
  `:165` and `flow/impl.clj:245-260`, `:313-318` (`futurize`, workloads);
  `reference-code/hyperlith` (SSE, brotli writer, batch send);
  `reference-code/datastar-clojure`; the binding turn design:
  `docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md`
  §13–§16.
- **Fork:** sci — arm contract wrappers at the base so `:sci/generation`
  discriminates the private layer; pushed to our `seon-env-hook` branch.
- **Probe before/after:** `fork-for-turn` on `default` (ms); one agent
  turn end to end; the namespace page for the largest namespace with HTML
  unclipped; the debug page.

### B3 Errors, tasks, config, effects: one model (astra, medium)

- **Owned:** `src/seon/error.clj`, `src/seon/error/*.clj`,
  `resources/seon/schemas/seon.error*.edn`, `src/seon/issue.clj`,
  `src/seon/issue/*.clj`, `src/my/issue.clj`, `src/seon/config.clj`,
  `config/default.edn`, `resources/seon/schemas/seon.config*.edn`,
  `src/seon/effect.clj`, `src/seon/search.clj`, `src/seon/bootstrap.clj`,
  their tests, plus the consumer sites of the kind stamps (listed in
  `../research/error-result-retirement-hunks-2026-09-23.md` before archive;
  every conversion is one atomic slice, AGENTS.md rule 13).
- **Cut (floor):** [errors audit](../research/deletion-audit-errors-issues-config-2026-09-21.md)
  — the `diagnostic-*` restatement (`error/refusal.clj:37-74`, 275 sites,
  keep `-member` and `-cause`); the Lucene tier (`seon.search`, zero
  production callers of `search/search`; `similar-identities` is a 25-line
  token overlap); fourteen copies of the error union → `error/facet-keys
  :1921`; the 799 `:seon.error/kind`/`/class` stamps → the base + facet
  model (ruling D3/D12: no general predicate, every contract names its
  error schemas); 43 tuned config dials deleted, 9 replaced by the event
  they stand for; `seon.issue` → `seon.task` (ruling D1: linked facts +
  optional agent; template = render pair + units; detector finding = task
  with detector subject; recurrence id per D13 through `seon.id/id`).
- **Read first:** Clojure `ex-info`/`ex-data`/`Throwable->map`;
  `reference-code/malli/src/malli/error.cljc` (explain, humanize);
  `reference-code/malli/src/malli/core.cljc` `:or`/`:multi` dispatch;
  `reference-code/datahike` tx-meta provenance; `reference-code/konserve`
  for blobs; the two error PRDs
  (`error-conversion-prd-2026-09-20.md`, `error-entities-prd-2026-09-17.md`,
  archived) for the ruled shapes only.
- **Probe before/after:** transact one error of every declared facet on a
  fixture branch: stored, pulled, rendered; a contract miss names function,
  arity, argument path, expected shape, offending value; the error-write
  timing the deleted timing test policed.

### B4 The test system is the runtime (astra, medium; queued behind the first free slot)

- **Owned:** `src/seon/test.clj`, `src/seon/test/*.clj` except `arm.clj`
  and `cache.clj`, `test/seon/test_support.clj`, `bin/test`,
  `bin/test-fast`, `bin/_test-slot`, `resources/seon/schemas/seon.test*.edn`,
  their tests.
- **Cut (floor):** [test-system audit](../research/deletion-audit-test-system-2026-09-21.md)
  — the 1,213 shell lines; `runner.clj:3628-4310` worker pool; the 682
  lines of checkout/exchange/process-tree; `test_runner_integration_test.clj`
  (28 × 30 min); the surviving path `seon.test/check` (`test.clj:1901`) →
  `run-owned` (`:576`), admitted through the one selector, executed in the
  cluster's JVM on a `with-database` branch + ctx fork, recorded through
  `commit-results!`; `bin/test` becomes a launcher of that path for the
  platform tier (boot from zero) only. Fix `duration-failures`
  (`runner.clj:369-371`): a reason without a number is a refusal, not a
  default. Hoist the repeated 46 lines/test into `test-support`. Convert the
  72 algorithm-defect escapes across the tree with their owning lanes; delete
  the 18 process and 31 duplicate ones.
- **Read first:** `reference-code/kaocha` (selection, reporters, bounds —
  what a runner is); `clojure.test` itself (`test-var`, fixtures, report
  multimethod); `test_support.clj:946` (the 37 ms fork, the idiom to
  build on); `reference-code/datahike/src/datahike/versioning.cljc:620`.
- **Probe before/after:** an agent asks "which tests reach my change",
  runs exactly those in-process, gets recorded results; a second identical
  request executes zero; the platform tier boots from zero.

## 3. Phase C — the live loop (the orchestrator, the moment A lands; runs beside B)

1. **Profiling on the wrapper** (owner, 2026-09-21): two `nanoTime` reads
   in the armed wrapper; `LongAdder` count/total + `LongAccumulator` max per
   armed definition, keyed by (symbol, `:seon.fn/digest`, branch); one
   flush on a bound transacts aggregates as facts (symbol as value); hot
   chains are a Datalog join over `:seon.fn/calls`; findings resolve to a
   task identity (ruling D2). Java Flight Recorder is the independent
   check. Read: `java.util.concurrent.atomic.LongAdder`, the wrapper's
   invocation path `instrument.clj:378`, `current-wrapper? :844-858`.
2. **The first namespace agent: contract coverage** (owner's choice). Task
   = the uncontracted functions of one namespace (query) + the tests
   reaching them; the agent works on a forked branch + SCI context, installs
   contracts, runs `run-owned`, merges through `exact-replacement-tx`
   (`program.cljc:840`). A same-identity conflict opens a conflict task for
   root (ruling D6/D8). Subject namespace: owner's pick, or the smallest
   with the most uncontracted `seon.db` readers.
3. **Second agent: a profiling task** from C1's first finding, on its own
   fork, measured before and after by digest.

## 4. Integration (orchestrator, per landing)

Read the diff (what did it delete; which seam does it call, cited; is any
step O(program); did it add a cache, constant, roster or noun — send back).
`clojure -M -e "(require …)"` for every touched namespace. Boot from zero
on a scratch root; `default` reset once per integration with every pending
schema change batched (database data is disposable). The measurement
script row. Push `agent-platform-cut` at every integration. When B lands:
merge to `steward-platform`, then `main`.

## 5. What this plan never does

No second cache, analysis path, runner, registry or noun. No tuned constant
without the event it stands for. No `foo-v2`. No lane runs a full suite. No
lane edits a file another running lane owns; a held file is a stop and a
report, answered by the orchestrator with a ruling. No stored data
migrated: reset.

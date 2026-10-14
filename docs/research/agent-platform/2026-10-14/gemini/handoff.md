---
title: "Handoff: Gemini findings to the implementation orchestrator"
type: research
status: active
---

# Handoff: Gemini findings to the implementation orchestrator (2026-10-14)

From session "Argon model integration" to root ("SEON - Main"), so implementation stays with one orchestrator.
Everything here is committed; the evidence is in this directory ([README](README.md)). Owner decisions are quoted.

## 1. What landed (commits on refactor/agent-platform)

| Commit | What |
|---|---|
| a54610348 | `bin/agy-agent` (Gemini lanes: run, resume, stop, summary, watch, status; default `gemini-3.8-flash-high`), the `agy-lanes` skill, AGENTS.md model roles and lane launch rule name Gemini; the git-worktree prohibition lifted (AGENTS.md, `bin/codex-agent` lane_rule, clusters-branches-contexts.md) for the owner's worktree experiment |
| a581628e1, f0bcf6cb5 | plan §0: Gemini in **Models**, **Performance is on the path**, **Backlog by root cause**, **Worktree experiment**; the moot-task retirement corrected (nothing retired) |
| 166d830d2, 31cf54396 | the worktree pitches and both backlog clusterings, with what root verified |

Also: the `seon` MCP server is registered in agy globally (`agy mcp list`), so Gemini lanes have `runtime_status` and
`eval_clj`. Note: `243683f1d` (lane s1a-rename) carried an earlier AGENTS.md edit of this session in its commit; the
content is correct and later commits replaced it.

## 2. Owner direction from this session (2026-10-14)

- "Huge gains can be found by identifying them and methodically fixing them." Performance is on the path.
- "I want everything that can be shared to be shared if the computations are immutable. If another agent runs the
  same code over the same data, even in different SCI contexts and branches, we use the cached result. Same with
  testing: test results should carry if they are the same tests. We never run full test suites unless explicitly
  requested. Everything is smart, incremental and minimal work."
- Worktrees: "The goal is the DB is the source of truth and we write to the files once we've vetted it. We need
  reliable two-way syncing. The goal is to get rid of the lanes and to have more work happening in parallel and to
  figure out things when we are merging them." Decision pending with the owner (§6).
- Models: Argon-low over Argon-high ("no thinking and similar perf for all but the hardest tasks"); do not waste
  tokens on comparison matrices.

## 3. Launch now: two lanes (owner approved 2026-10-14)

Both are file-disjoint from each other and from every live claim at handoff time (lane-lifecycle-branch holds
`seon.cluster.agent`, `seon.cluster.source`, `seon.cluster.registry`, `seon.merge`, `my.program`; nothing else
relevant). Each is a performance commit: proven by a `seon.profile/measure` assertion on the end-to-end probe named
below, timed on parent and change on a quiet JVM (AGENTS.md "Make cost follow changed inputs"); a measured
regression or no measured gain is not committed. REPL-first on the lane's own branch; commit through `bin/lane`.

### Lane A: analyzer (Sonnet; clearly specified)

Files: `src/seon/fn/analyzer.clj`, `src/seon/fn.clj`, `test/seon/fn/analyzer_test.clj`, `test/seon/fn_test.clj`.

1. **Parse once per content.** `seon.fn.analyzer/parsed-facts` is a pure function of `[filename source]`; since boot
   it ran 9,792 times over about 588 distinct files (65 s self). Retain its result in `seon.cache` keyed by
   `[filename source]` (a new cache kind under `seon.cache`'s conventions, weighed at insert). Proof: analysing the same
   file twice, the second `parsed-facts` call does no kondo parse (`seon.profile/calls-of` on the parse path is 0).
2. **Index var-quotes like the other spans.** `seon.fn.analyzer/attributed-usages` checks each usage with
   `(some #(contains-position? % usage) (get var-quotes (:filename usage)))`, a scan of every var-quote of the file per
   usage (80.6M `contains-position?` calls since boot). Build `span-index` of var-quotes per file once and answer with
   `innermost-span` (its existing binary search), as the function already does for definitions, calls and spans.
   Output must be identical: assert equality of `attributed-usages` before and after on a real analysis (e.g.
   `test/seon/fn_test.clj`'s, the largest observed: 153k checks). Proof: `contains-position?` calls on that analysis
   drop from per-usage-times-quotes to per-usage-log-quotes.
3. **One clean-analysis assertion.** `seon.fn/analyzed-artifacts` calls `seon.fn/assert-clean-analysis!` twice on the
   same analysis (with `first-party-function-symbols` and then with `functions`). Verify whether the second set is a
   subset of the first; if so delete one call, otherwise merge them into one pass. Report which.

Tests to name: `seon.fn.analyzer-test`, `seon.fn-test` (var by var where reds pre-exist), and the publication tests
that analyse files (`seon.fn.publication-test`, `seon.fn.publication-cache-test`).

### Lane B: request path and sharing (Opus: item B1 needs a small design)

Files: `src/seon/test.clj`, `src/seon/test/runner.clj`, `src/seon/config.clj`, `src/seon/render/value.clj`,
`src/seon/program.cljc`, `src/seon/schema/edn.clj`, `src/seon/schema.clj` (only where the key changes), and their
tests (`test/seon/test_test.clj`, `test/seon/test_reaching_test.clj`, `test/seon/config_test.clj`,
`test/seon/program_test.clj`, `test/seon/request_cost_test.clj`).

- **B1. Same test, same result, across branches** (owner: "test results should carry if they are the same tests").
  Today `seon.test/recorded-result` and `seon.test.runner/latest-results` read results only from the calling branch's
  database, and `seon.test`'s `::changes` key in `seon.cache` includes `(get-in candidate [:config :branch])` beside
  `runner/program-revisions-digest`, which already identifies the content. So a test green on agent A's branch reruns
  on agent B's even when its reach digest and inputs are identical. Make reuse answer from the identity of the test
  (`:seon.test.member/reach-digest`, `:seon.test.run/input-digest`, the test's source), wherever the result was
  recorded: decide where a sibling's result is found (main's record, the recording branch read through
  `seon.db/branch-database`, or a `seon.cache` kind keyed by that identity for in-process sharing), and drop the
  branch from the `::changes` key. Unknown or red never reuses green (AGENTS.md "Require current positive proof").
  Proof: two agent branches with the same program; a test run green on the first is answered on the second with no
  execution (zero batches admitted), and a change to anything it reaches reruns it.
- **B2. Config once per basis.** `seon.config/effective` runs 7 times on the same database basis per trivial eval
  request (measured; callers in `seon.mcp`, `seon.turn.batch`, `seon.turn`). It is a pure function of the database
  value and cluster name: retain it at the owner keyed by `[basis cluster]` (Datahike commit id), so every caller
  shares it; no threading of a value through callers. Proof: one trivial `eval_clj` request computes it once.
- **B3. No HTML for an AI reply.** `seon.render.value` calls `seon.print/emit-both` for every printed value (twice per
  trivial request); the AI reply uses only the text face. Emit the face the consumer asked for. Proof: a trivial
  request makes no HTML emission.
- **B4. Key schema derivations by digest, not by the whole forms map** (largest single win; two lanes found it
  independently). `seon.program/authored-shapes` calls `seon.cache/lookup` with the whole packaged forms map (3,749
  entries) as its key, about 615k times since boot; a lookup compares the full map whenever instances differ.
  `seon.cache` sits at 511.99 of 512 MB, holding ten identical 105 MB declaration projections and 52 packaged
  populations, and evicted the schema projection 1,568 times. Key `::authored-shapes`, the declaration projection
  and `::packaged-population` by `seon.schema/declaration-digest` of the forms; in
  `seon.program/source-resolver-names` skip the work when there are no aliases or refers. Proof: `seon.cache` holds
  one declaration projection per distinct population (measure its weighted bytes before and after a sequence of
  requests), and `seon.program/definition-digest` over a namespace's entities makes zero `authored-shapes` misses.
  Expected (Argon's estimate): about 220 s CPU since boot and about 140 MB of cache freed.

## 4. Next, not easy (need design or measurement first)

From [perf-analysis.md](perf-analysis.md), [perf-schema.md](perf-schema.md), [perf-request.md](perf-request.md):

- Whole-program rehearsal per change: `seon.cluster/rehearse-source!` (1,557 s total over 120 calls, about 13 s each)
  re-analyses about 588 files, discards its first pass when recursing on stale files, and copies `.clj-kondo/.cache`
  each time; target proportional to changed plus stale files. Touches `seon.cluster` (free) and analysis in `seon.fn`
  (Lane A's files: after Lane A).
- Contracts compiled per call: `seon.schema/contract-product` recompiles about 38k times because
  `seon.program/contract-facts` strips `:seon.schema/contract-products` from the projection; `contract-references`
  walked 3× per contract.
- Per-datom attribute checks: `seon.db/write-attribute-plan` (17.4M calls) and the `*-attr-in?` predicates allocate a
  key per datom for `seon.schema/projection-cache-value` (47M calls); answer from projection-wide sets.
- Path classification: `seon.test.cache/input-path?` 3.57M calls over all 6,907 `git ls-files` paths against 45
  roots (83% under `docs/`, `evals/`, `v1/`, `tmp/`); callers in `seon.cluster` and `seon.cluster.source` (held by
  lane-lifecycle-branch at handoff).
- The write path ([perf-transact.md](perf-transact.md), Argon-medium, 32 min, finished after handoff). Ordinary
  recording transactions are fine (p50 33.5 ms of 1,181); **190 reconciliation and adoption transactions take
  1.07-4.24 s each, 89% of all write time**. Inside the writer, `seon.fn/reconcile-tx-in` pulls `[*]` and normalizes
  the whole desired entity population (`seon.fn/normalized-index-entity`) even when `:seon.program/definition-digest`
  is unchanged, calling `seon.db/carried-projection` 2.54M times; target proportional to changed digests. Per
  transaction, `seon.db/write-owned-values-error` and `seon.db/write-report-error` rescan the 2,404-attribute schema
  map and validate transaction-report datoms 15+ times (29.9M `transaction-report-datom?` calls). Confirmed by root:
  `seon.db` holds two core.cache LRU atoms of its own (`projection-cache` 24, `value-projection-cache` 4, sizes in
  `seon.db/projection-cache-policy`) outside `seon.cache`, against AGENTS.md (one central cache, no hardcoded policy
  numbers, no further atoms); they thrash (`seon.db/declaration-content-key` scans 34,841 datoms on 2,639 misses).
  Fold them into `seon.cache` kinds keyed by content. Note: `transact-call`'s profiled "self" time is the whole writer
  wait (the profiler does not subtract writer-thread callees), so read its rows with that in mind.
- The profiler's own predicates carry instrumented contracts (`seon.profile/summary` costs about 88k checked calls;
  hot leaves like `contains-position?` pay checks on every call). Owner rule: every function keeps its contract; the
  question for Opus is the instrumentation cost of hot private leaves, not removing contracts.
- `seon.schema/call-with-projection-state`'s 127 s "self" is callback time misattributed (task
  `seon.profile/scope-wrappers-report-callback-time-as-work`).

Rejected: skipping the recording transaction on read-only requests (AGENTS.md: every eval request writes one).

## 5. Backlog by root cause

Both models clustered the 872 declared tasks ([rootcause-flash-high.md](rootcause-flash-high.md),
[rootcause-argon.md](rootcause-argon.md)). Their counts and "subject is gone" calls failed verification (README), so
nothing was retired. Treat the clusters as hypotheses: take one producer at a time, verify it in the code, then retire
or re-file its tasks. Clusters both models name: second printers and clipping outside the value printer; whole-program
reach walks per change; test-world construction and branch-filtered result queries (overlaps B1); host-to-SCI var
copying.

## 6. Pending owner decision: worktrees

Both pitches ([Flash](worktree-pitch-flash.md), [Argon](worktree-pitch-argon.md), brief
[here](worktree-pitch-brief.md)) recommend no per-agent git worktree: the Datahike branch and SCI context are the
workspace and files change only at merge (`seon.merge/publish!`). Measured: 0.76-0.83 s and 217-467 MB per worktree;
the edit hook crashes (empty `reference-code/` submodules) or skips checks there; clj-kondo starts cold. The two real
blockers to parallel work: B1 above, and outside agents' file edits dirtying the shared checkout (the only reason
`bin/lane` claims exist). Root's recommendation to the owner: (1) B1; (2) outside agents work as Seon agents do, by
`defn` on their own branch through `eval_clj` and reading source through `my.program/source`; (3) files change only at
merge, then `bin/lane` claims retire. The owner has not chosen between that and keeping file edits with a per-agent
file view.

## 7. Using Gemini (agy-lanes skill)

Flash-high is the default (fast, reads widely, quotes what it read; found F53's exact clause blind in 7 min). Argon-low
for long open-ended performance hunts and independent plans. Reproduce every quoted excerpt, every count and every
retire-this list before acting. Name the design invariants that matter in each brief (an Argon lane proposed removing
the recording transaction). Resume a lane to finish its work; start fresh for a new question (a resume re-sends the
whole conversation).

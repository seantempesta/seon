---
type: reference
status: current
created: 2026-09-22
tags: [agent-platform, cold-gate, triage, kind-cut, b1b, durations, read-only]
---

# Cold gate triage — 2026-09-22 (read-only)

Input `tmp/cold-gate-2026-09-22e.log` (25,251 lines), HEAD `67a0e940b45bbbd67ae3c51d95321c8037853b1f`,
branch `refactor/agent-platform`. Platform tier green; bulk tier below.
`PHASE coordinator-and-tests elapsed-seconds=2762`, `workers=3`, `namespaces=297`, 1,850 test tasks.
Evidence is the log plus the source read at HEAD. No file was edited, no JVM run.

## 1. Totals

| Measure | Value |
|---|---|
| Distinct failing tests (`attributed output for …` markers) | **529** of 1,850 |
| Tests with at least one `FAIL in` | 369 |
| Tests with at least one `ERROR in` | 243 |
| Raw `FAIL in` / `ERROR in` lines | 916 / 323 |
| Tests whose ONLY failure is a duration overrun | **65** |
| Tests with a duration overrun (alone or with others) | 145 |
| Tasks that never returned (hit the 270 s worker bound) | **16** |
| Sum of all task elapsed-ms | 7,987 s over 3 workers ≈ 2,662 s wall (matches 2,762 s) |
| Recorded tally | **unavailable** — `record-snapshot!` refused (line 25,249) |

### Duration verdict — genuinely slow, not machine load

All 145 overruns are against the default 5,000 ms bound.

| Overrun ratio | min | p25 | median | p75 | max |
|---|---|---|---|---|---|
| elapsed / bound | 1.00× | 1.38× | **1.92×** | 3.70× | 24.07× |
| elapsed ms | 5,001 | — | 9,588 | — | 120,347 |

Only the bottom quartile sits in the 1.0–1.4× band that load would explain. The median test
takes nearly twice its declared bound and the tail reaches 120 s. They also cluster hard:

| Namespace | duration failures |
|---|---|
| seon.cluster.turn-test | 51 |
| seon.cluster.agent-test | 11 |
| seon.cluster.prompt-test | 7 |
| seon.render.web-test | 5 |
| seon.fn-test | 4 |
| 29 other namespaces | 1–3 each |

`seon.cluster.turn-test` alone (n=51, median 9,649 ms, max 46,307 ms) is one fixture that
exceeds its bound on nearly every member. Treat these as declared-bound failures per
AGENTS.md, not as noise — but fix the loop first (§4 Lane A), because the 16 hangs and the
turn-test overruns share a subject.

**The gate's 46 minutes are mostly the 16 hangs.** 16 × 270 s = 4,320 s = 54% of the
7,987 s of task time; divided across 3 workers that is ~24 of the 46 minutes.

## 2. Class table

`OTHER` below = residual assertion failures not matched by a class signature; 42 of them
compare or produce `:seon.error/*` shapes and are expected to be cascades of C1/C6.

| # | Class | Tests | Evidence points at |
|---|---|---|---|
| C2 | Test passes a string where the contract requires a qualified symbol | 71 | pre-existing wrong expectation (tests) |
| C9 | Duration overrun only | 64 | genuinely slow fixtures |
| C1 | Error-facet guard: declared union is a hand-written subset, or the guard fires on data | 44 (+42 OTHER) | **kind-cut regression** |
| C6 | Turn contracts refuse a pulled/partial shape | 21 (+16 hangs) | B1b / turn cut |
| C3 | Whole-entity validation refuses an identity-less fixture row | 20 | pre-existing wrong fixture |
| C7 | Declared event/terminal fact never arrived (bound fired) | 16 | downstream of C1/C6 |
| C4 | `A durable Malli definition contains an unnamed callable` during indexing | 15 | indexing/edit-hook seam |
| C3b | Other fixture-write refusals | 12 | pre-existing wrong fixture |
| C5 | `carried-projection` is nil at a fixture seam | 11 | fixture acquisition cut |
| C8 | Dev seam moved and the test still resolves the old one | 7 | **B1b** |
| — | OTHER (residual assertions) | 248 | mixed / cascades |

### C1 — error-facet declarations (44 tests, plus ~42 error-shape assertions)

The guard is `src/seon/instrument.clj:748-808`: any returned map satisfying `:seon.error/base`
must carry one facet from the arity's declared output union (`declared-result`,
`src/seon/instrument.clj:622`). Two distinct defects live under one message.

**C1a — the declared union is a hand-written subset.** The class regression already in the
tree proves it and names the four producers:

| Line | Test | Expected / actual |
|---|---|---|
| 12,177 | `seon.instrument-test/semantic-admission-explicitly-declares-every-error-facet` | `expected: (= (error/facet-keys projection) (:seon.instrument/declared declared))` — `#'seon.sci.admit/semantic-value missing declarations: (:my.plan/dependency-cycle-error :seon.ai/rate-limited-error :seon.issue/subject-unlinkable-error … )` (40+ facets), same for `#'seon.error/refusal`, `#'seon.error/latest-fact`, `#'seon.sci.kernel/failure-value` |

Owners verified: `src/seon/sci/admit.clj:568-578`, `src/seon/error.clj:84-95` — both enumerate
the facet union by hand. Smallest correct fix: declare ONE registry schema whose members are
`error/facet-keys` and reference it from all four outputs (derive-or-die); do not extend the
four lists.

**C1b — the guard fires on data that merely carries error attributes.** `:seon.error/base`
(`resources/seon/schemas/seon.error.edn:271`) is an open map requiring only
`:seon.error/at`, `/layer`, `/operation`. With `:seon.error/kind` gone there is no marker
separating a produced error from a *pulled error entity* or a *parsed* error map.

| Line | Test | Expected / actual |
|---|---|---|
| 11,192 | `seon.error-test/the-storm-is-bounded-by-the-signature-count` | `actual: … Fixture write was refused at the write: seon.db/pull returned a base error without a complete declared facet. Declared facets: #{:seon.db/invalid-read-error}` — 302 occurrences across 15 tests; `pull` was returning a stored fault ENTITY during whole-entity validation |
| — | `seon.repl/shown-value returned undeclared error facets #{:seon.sci.kernel/error}` (4 tests) | `shown-value` (`src/seon/repl.clj:78-89`) only `edn/read`s the agent's shown text; the parsed map is data |
| — | `seon.turn/phase returned a base error without a complete declared facet. Declared facets: #{}` (7 tests) | `src/seon/turn.clj:3472-3490` declares a `:seon.schema.admission/polymorphic-boundary` exemption that `declared-result` ignores |

Smallest correct fix at the owner (`src/seon/instrument.clj:774`): fire only when the value
matches NO non-error branch of the declared output union (`:seon.db/pulled-entity`,
`:seon.schema/value`, a declared polymorphic-boundary) — i.e. ask the contract, not `base?`
alone. `seon.turn-test/transact-or-refusal`, a *test helper*, tripping the same guard is
corroboration that the predicate is too broad.

### C2 — string test-symbols (71 tests)

Not a regression in `src/`: the contracts are right (symbols-everywhere, 2026-09-17) and a
handful of test helpers build `test-symbol` with `str`.

| Line | Test | Expected / actual |
|---|---|---|
| 21,719 | `seon.test-reaching-test/reach-digests-follow-only-changed-closures` | `seon.test/reach-digest refused test-symbol at []: expected a namespaced symbol, got a string` — origin `test/seon/test_reaching_test.clj:89` `test-symbol (str namespace-name "/probe")`, reached via `with-test` (`:85-96`) and `with-indexed-tests` (`:74-82`) |
| 19,725 | `seon.test-failure-facts-test/a-changed-reach-member-retracts-only-itself` | same refusal; origin `test/seon/test_failure_facts_test.clj:26` `test-symbol (str namespace-name "/probe")`, reached via `with-probe` (`:16-51`) and `transact!` (`:242`) |
| — | `seon.test-reaching-test` (line 70) | `(functions/tests-reaching database "my.note/add!")` — string literal |

Smallest correct fix at the expectation: `(symbol (str namespace-name) "probe")` in each
helper; four helpers cover ~36 of the 71.

### C6 — turn contracts refuse a pulled/partial shape (21 tests + the 16 hangs)

| Line | Test | Expected / actual |
|---|---|---|
| 7,298 | `seon.cluster.turn-test/a-lost-model-call-leaves-a-durable-readable-reason` | `seon.turn/generated-read-fault refused evaluation at [:seon.cluster.eval/ns]: expected an integer, got a map. Called from seon.turn (turn.clj:2050)` — 8 tests |
| 5,361 | `seon.ai-stream-fold-test/a-real-jdk-provider-status-commits-with-its-attempt` | `seon.turn/record-attempt! refused cluster at [:seon.cluster/name]: expected the required key :seon.cluster/name with a string, got a map missing :seon.cluster/name` — 7 tests |
| 22,731 | `seon.turn-continue-test/done-ends-the-session` | `Worker task reached its 270s execution bound before returning.` (no other output) |

Verified owner: `src/seon/turn.clj:2050` calls `(generated-read-fault database % %)`, passing a
pulled source map as the `:seon.sci.eval/evaluation` argument declared at
`src/seon/turn.clj:2060-2063`; the pulled shape expands `:seon.cluster.eval/ns` to a map while
the stored shape is an integer (the entity-schema-vs-pulled-shape class). Smallest correct fix:
pass the evaluation the source belongs to at the call site, or give the reverse-lookup its own
arity — not a `[:or :int :map]` widening.

### C3 / C3b / C5 — fixtures that are not the canonical ones (43 tests)

| Line | Test | Expected / actual |
|---|---|---|
| 5,503 | `seon.blob-test/binary-content-round-trips-on-both-sides-of-the-inline-threshold` | `Complete component validation refused: unowned-entity; … #:seon.db{:entity 2, :entity-value #:seon.config.eval.result{:blob-threshold 4096}}` — `test/seon/blob_test.clj:38-48` hand-rosters `:db/ident` and writes an identity-less config row |
| — | `seon.schema-usage-guard-test` (7 tests) | `unowned-entity … #:seon.schema-usage-guard{:base 7}` — synthetic `::extra-schema` rows written without an owning root |
| 17,993 | `seon.schedule-test/a-nominal-predating-the-task-never-fires` | `seon.program/shapes-in refused projection at []: expected a value satisfying should be a map, got nil. Called from seon.test-support (test_support.clj:1034)` — `(db/carried-projection database)` returns nil at `test/seon/test_support.clj:1031` (6 of 11) |
| 4,655 | `my.agent-test/settings-are-one-owned-component-with-a-derived-render-pair` | `expected: (= {:my.agent/turns-left 0, …} settings)` / `actual: (not (= … {:seon.config.agent/turn-completion-backstop-ms 5678, :seon.config.eval/time-limit-ms 1234}))`, then `Fixture write was refused …: Agents retain their identities.` — a lost declared member plus a fixture that retracts an agent |

The refusals are ruled behaviour (AGENTS.md §3, `src/seon/db.clj:3820`, `:3788`). Fix at the
fixtures: canonical helpers (`apply-config!`, `program-fn-row`, `seed-cluster!`) and an
explicitly handed projection. The one genuine `src/` question is why
`seon.db/carried-projection` answers nil rather than a typed unknown — an absence-as-health
shape worth naming even if the fixture is the caller at fault.

### C4 — unnamed callable during indexing (15 tests)

| Line | Test | Expected / actual |
|---|---|---|
| 10,223 | `seon.dev.edit-feedback-test/pre-edit-refuses-a-patch-path-that-does-not-exist` | `actual: clojure.lang.ExceptionInfo: A durable Malli definition contains an unnamed callable.` at `schema.clj:531`, via `canonicalize` `schema.clj:553/562` |
| 10,347 | `seon.dev.hook-test/idle-edit-starts-without-quiet-delay` | identical stack |

Owner: `src/seon/schema.clj:505-535` (`canonical-definition`), reached from
`src/seon/fn.clj:668` during indexing with `predicate-functions` = `{}`. The refusal's
`:seon.schema/value` is not printed anywhere in the log, so the offending declaration cannot
be named from the evidence — that is itself a §2.4 defect: name the file, declaration and
node in the refusal before anything else, then the cause is one gate run away. Confined to the
edit-hook/publication tests; no static inline `(fn …)` exists in `src/` malli metadata
(one `[:fn (quote seon.render.hiccup/raw?)]` at `src/seon/render/hiccup.clj:80` is named).

### C8 — B1b moved the dev seam (7 tests)

| Line | Test | Expected / actual |
|---|---|---|
| 10,480 | `seon.dev.mcp-bridge-test/discovery-derives-live-stale-invalid-and-degraded-rows` | `actual: … The MCP bridge var did not resolve.` at `mcp_bridge_test.clj:29` |

`test/seon/dev/mcp_bridge_test.clj:20-30` `load-file`s `bin/mcp-server` and resolves
`seon.dev.mcp`. Since `076827cc9`/`171388062` (B1b) `bin/mcp-server` is a bash launcher
(`exec bb --classpath … -m seon.dev.mcp`); the bridge source is `script/seon/dev/mcp.clj`.
Smallest correct fix: load `script/seon/dev/mcp.clj`. `seon.dev.dependency-cache-test`
(2 tests) reports the sibling `The dependency-cache test seam is absent.`

## 3. Recommended lanes — disjoint owned files, ordered by tests recovered

| Lane | Class | Tests | Owned paths |
|---|---|---|---|
| A | C1 error facets | 44 + ~42 cascades | `src/seon/instrument.clj`, `src/seon/error.clj`, `src/seon/sci/admit.clj`, `src/seon/sci/kernel.clj`, `resources/seon/schemas/seon.error.edn`, `src/seon/repl.clj` |
| B | C2 string test-symbols | 71 | `test/seon/test_reaching_test.clj`, `test/seon/test_failure_facts_test.clj`, `test/seon/render_coverage_test.clj`, `test/seon/issue/detect_test.clj`, `test/seon/test_provenance_test.clj`, `test/seon/render/ns_test.clj`, `test/seon/issue_test.clj`, `test/seon/issue_generate_test.clj`, `test/seon/bootstrap_test.clj` |
| C | C6 + hangs + turn durations | 21 + 16 + ~51 | `src/seon/turn.clj`, `test/seon/turn_continue_test.clj`, `test/seon/cluster/turn_test.clj`, `test/seon/rereads_test.clj`, `test/seon/loop_proof_test.clj` |
| D | C3/C3b/C5 fixture honesty | 43 | `test/seon/test_support.clj`, `test/seon/blob_test.clj`, `test/seon/schema_usage_guard_test.clj`, `test/seon/render/root_pull_test.clj`, `test/seon/schedule_test.clj`, `test/my/agent_test.clj` |
| E | C4 + C8 dev seams | 22 | `src/seon/schema.clj`, `test/seon/dev/mcp_bridge_test.clj`, `test/seon/dev/dependency_cache_test.clj`, `test/seon/dev/edit_feedback_test.clj`, `test/seon/dev/hook_test.clj` |

Ordering: run A and B together first (disjoint, largest recovery, A unblocks the ~42 OTHER
error-shape assertions). C next — it owns `src/seon/turn.clj`, which A must not touch; A's
`seon.turn/phase` finding is handed to C, not fixed in A. D and E last and independent.

**Lane A seed.** Read `docs/research/agent-platform/kind-cut-diff-review-2026-09-22.md`,
`docs/prds/agent-platform/plan/lane-b3-errors-tasks-dials.md` §0/§2a/§5, AGENTS.md §2.2/§2.4,
`src/seon/instrument.clj:622-808`, `src/seon/error.clj:73-120`, `src/seon/sci/admit.clj:560-600`,
`resources/seon/schemas/seon.error.edn:271`. Two changes: (1) replace the four hand-written
facet unions with ONE registry schema whose members come from `error/facet-keys` — never
by extending a list; (2) at `src/seon/instrument.clj:774` fire the facet refusal only when
the returned value matches no non-error branch of the arity's declared output, so a pulled
error entity, a parsed shown value and a declared polymorphic boundary are data again. Keep
`seon.instrument-test/semantic-admission-explicitly-declares-every-error-facet`
(`test/seon/instrument_test.clj:1183-1193`) as the class regression and add one asserting that
`seon.db/pull` returning a stored fault entity is NOT a facet refusal. Done when that
namespace plus `seon.error-test`, `seon.flow-test`, `seon.problems-test` and `my.message-test`
are green under `bin/test-fast --paths <owned> -- <those namespaces>`.

**Lane B seed.** Read AGENTS.md §3 (symbols-everywhere, 2026-09-17) and
`docs/prds/steward-platform/research/symbols-everywhere-inventory-2026-09-17.md`. Per-site rule:
every `(str ns "/name")` that feeds a `:seon.test/sym`, `:seon.fn/sym`, detector or
`tests-reaching` argument becomes `(symbol (str ns) "name")`; no production contract is
widened to accept a string. Keep one regression asserting a fixture-built test symbol is a
qualified symbol at the write. Done when the nine test namespaces are green and no
`expected a namespaced symbol, got a string` remains in a fast run.

**Lane C seed.** Read `docs/prds/agent-platform/plan/README.md` §4/§7,
`docs/prds/steward-platform/research/entity-schema-vs-pulled-shape-2026-09-16.md`,
`src/seon/turn.clj:2013-2085`, `:3472-3490`, `:4762`. First act: reproduce one of the 16
270 s hangs (`seon.turn-continue-test/done-ends-the-session`) and name what never arrived —
a hang with no diagnosis is the worse defect (AGENTS.md §2.3). Per-site rule: a turn seam
takes the stored shape it declares; a pulled shape gets its own declared argument, never an
`[:or :int :map]` widening. `seon.turn/phase`'s declared output is handed over from Lane A —
fix it here. Keep one regression asserting the continuation loop settles within its declared
bound. Done when `seon.turn-continue-test` returns under bound and
`seon.cluster.turn-test`'s duration overruns are gone or re-declared with
`:seon.test/long-ms` plus a measured reason.

**Lane D seed.** Read AGENTS.md §5 "Test fixtures — the one right way" (items 1, 2, 8) and
`src/seon/db.clj:3760-3830`. Per-site rule: every fixture write goes through the canonical
helper (`apply-config!`, `program-fn-row`, `seed-cluster!`, `transacted!`) and every
projection is handed explicitly; no hand-rostered `:db/ident` schema, no identity-less row.
Also report (do not fix here) whether `seon.db/carried-projection` should answer a typed
unknown instead of nil. Keep
`seon.test-support-test`'s honest-fixture regression. Done when C3/C3b/C5 signatures are
absent from a fast run of the six owned namespaces.

**Lane E seed.** Read `docs/research/agent-platform/b1b-implementation-brief-2026-09-21.md` and
commits `076827cc9`, `171388062`. Two independent fixes: point
`test/seon/dev/mcp_bridge_test.clj:20` at `script/seon/dev/mcp.clj` (and the sibling
dependency-cache seam); and make `src/seon/schema.clj:531` name the file, declaration and node
of the unnamed callable before refusing, then use that evidence to fix the declaration the
edit-hook tests publish. Keep one regression asserting the refusal is evidence-complete.
Done when `seon.dev.*` namespaces are green.

## 4. Hangs, leaks and gate-level defects

| Line | Observation |
|---|---|
| 22,731 and 15 siblings | **16 tasks hit the 270 s worker execution bound** and returned only `Worker task reached its 270s execution bound before returning.` — no diagnosis of what never arrived. 9 are the whole of `seon.turn-continue-test`; the rest are `seon.rereads-test` (2), `seon.rereads-panel-test`, `seon.loop-proof-test`, `seon.run4-install-test`, `seon.render.web-debug-test`, `seon.help-trial-test`. 4,320 s = 54% of all task time. |
| — | No task BEGAN without an END (1,850/1,850), so no worker was lost; no `SEON FAULT`, liveness dump or watchdog line appears anywhere in the log. |
| 22,204 | `bin/test: persistent results NOT recorded: The cluster threw during the prepl operation` — a mid-run recording failure, itself asserted against by a test at 22,206. |
| 25,248–25,249 | `recorded tally unavailable; execution output above is not durable evidence.` `record-snapshot!` refused with `:seon.error/diagnostic-expected :complete-outcome-events` / `The test execution evidence does not authorize this transition` for run `df60b045fe71`. **This gate produced no recorded facts** — every number above is scraped text, not `:seon.test` rows. Worth its own issue: a 46-minute gate whose verdict is not durable. |
| 25,251 | `retained failed isolated operator root /Users/sean/src/seon/tmp/test-runs/run.XBq8Sp` — retained by design for a red gate; sweep only after confirming no live runner holds it. |
| 5,770–5,772 | `seon.bounded-boundary-census` reports six `:disposition :defect` foreign-subprocess boundaries in `script/seon/dev/dependency_digest.clj:72,74,86` and `script/seon/operator.clj:188,194,198,225,318` — declared-bound gaps in the B1b operator, not a leak in this run. |

---
type: research
status: completed
tags: [research, prd, flow, agent]
---

# CLJS test-suite speed and quality audit (2026-07-12)

## TL;DR

The active CLJS gate is slow mostly because it repeatedly exercises full runtime
boot, not because it compiles slowly. A completed clean run covered 119
namespaces, 1,231 tests, and 5,629 assertions. Its incremental Shadow compile
took 8.86 seconds; the Node test process then ran for about 302 seconds.
`seon.gym.driver-test` alone occupied 137–152 seconds in three recent logs,
roughly half of the runtime. The source explains why: that namespace reaches
`client/boot-seed!` about 24 times through scenarios, batteries, and direct seed
tests.

The runner also has two mechanisms that should be removed, not refined:

- it cold-loads the complete bundle once to `--list`, polls until output appears
  stable, kills that process, and then loads the bundle again to run tests; and
- after a truncated run it can rerun the missing tail in a fresh process and
  accept the stitched result as a pass.

The suite should have one owner and one fast default. Keep `bin/test-cljs` as
that owner, make truncation fail once, and make every async test use one bounded
terminal. Move genuine cold-boot, process, and UI journeys into explicitly
bounded acceptance tiers selected by the same command and compiled artifact.
Do not preserve old production paths merely to keep their tests green.

The wording problem is also real, but exact strings are not universally bad.
Exact parser bytes, EDN/JSON/SSE fields, HTML escaping, route patterns,
Datastar attributes, result-handle grammar, schema keys, and error codes are
contracts. Human teaching prose, plan legends, context headings, default prompt
snapshots, docstrings from production namespaces, and full error sentences are
not. Context tests should assert block identity/order/omission, raw-prompt to
debug-view equality, token accounting, and the presence of fixture facts—not
the current English used to explain them.

No production or test code was changed by this audit.

## Scope and evidence

This was a static audit. It did not run `bin/test-cljs`, restart a pod, or touch
the default/ACME stores. Evidence came from:

- `bin/test-cljs` and `shadow-cljs.edn`;
- the active `.cljs`/`.cljc` tests and support namespaces;
- the test runner in `src/seon/test/runner.cljs`;
- the checked-in Shadow runner source in
  `reference-code/shadow-cljs/src/main/shadow/test/node.cljs` and
  `reference-code/shadow-cljs/src/main/shadow/test.cljs`; and
- completed transcripts under `tmp/test-cljs-*.log`.

The working tree contains another lane's untracked autocomplete test. It is not
treated as committed baseline evidence. Its current failures and a test named
`default-render-keeps-todays-bytes` do, however, demonstrate why new work must
not enter the suite with byte snapshots of a context renderer. Coordinate with
that owner rather than editing the file here.

## Measured baseline

| Evidence | Compile | Node runtime evidence | Gym span | Result |
|---|---:|---:|---:|---|
| `tmp/test-cljs-20260712-071236-31321.log` | 8.86 s | 11:12:54.147–11:17:55.968, about 302 s | 11:14:56.637–11:17:23.272, about 147 s | 1,231 tests, 5,629 assertions, green |
| `tmp/test-cljs-20260712-003421-90846.log` | 8.18 s | about 269 s between first/last runtime timestamps | about 137 s | 1,231 tests, 5,627 assertions, green |
| `tmp/test-cljs-20260712-110006-79885.log` | 11.17 s | about 318 s between first/last runtime timestamps | about 152 s | 1,232 tests, 5,635 assertions |

The clean 07:12 run printed 119 `Testing …` namespace lines. A later `--list`
file reports 120 because the other lane's autocomplete namespace joined the
bundle. The useful conclusion is stable across those changes: compilation is
single-digit/low-double-digit seconds; execution is four-and-a-half to
five-and-a-half minutes; the gym driver consumes about half.

The logs do not carry a timestamp on every namespace boundary, so durations
below are only stated when the source or log provides defensible evidence.

## Where the time goes

### Full boot is repeated inside the gym

`test/seon/gym/driver.cljs:1750-1888` opens a new agent connection, ensures the
bootstrap compiler, applies `seed-scenario-world!`, synchronizes AI config,
creates agents, drives turns, and grades the result for every scenario.
`seed-scenario-world!` itself calls the complete `client/boot-seed!` path at
`test/seon/gym/driver.cljs:1644-1688`.

`test/seon/gym/driver_test.cljs` contains:

- four calls through `run-and-expect-pass!`;
- 18 direct `run-scenario!` sites, of which the paid/todo refusal cases return
  before boot;
- a two-member competency battery; and
- two direct `seed-scenario-world!` tests.

That is about 24 complete boot seeds in one namespace. The observed 137–152
seconds is consistent with roughly six seconds of cluster-wide seed/index work
per invocation. These are valuable acceptance journeys, but repeating the
whole boot to test scorecard parsing, predicate truth tables, judge result
separation, prompt-blob lookup, or refusal guards is unnecessary.

Target:

- one cold-boot parity journey in runtime acceptance;
- one multi-agent scenario journey if it exercises distinct coordination;
- pure scorecard/predicate/judge tests against constructed facts;
- minimal DB fixtures for DB-only grading queries; and
- no `boot-seed!` call in the default fast gate.

This refactor should naturally make the remaining cold-boot test faster. Do
not add a cached legacy boot path just for tests.

### Fresh database setup is copied broadly

Static census:

- 41 test files contain their own `d/create-database` path;
- those files contain 500 `deftest` forms;
- 34 local `fresh-*` helpers and 25 local `with-*conn` helpers repeat the same
  create/connect/schema/root-`set!` lifecycle; and
- `client/open-agent-conn!` has 73 textual references in CLJS tests/support,
  before accounting for helpers called by many tests or scenario loops.

Representative concentration:

| Namespace | Tests | Repeated setup evidence |
|---|---:|---|
| `my.plan-test` | 34 | 34 `with-conn` calls |
| `seon.db-test` | 42 | 28 `with-conn` calls plus history-specific stores |
| `seon.ctx-test` | 43 | 15 `with-conn` calls and full core-row seed |
| `seon.eval.record-eval-tee-test` | 27 | 10 fresh-conn sites and 15 bootstrap references |
| `seon.ai.typeahead-test` | 19 | 10 fresh-conn calls |
| OpenAI/Anthropic adapter tests | 39 | 21 repeated fresh-conn calls across two near-identical fixtures |

Isolation is correct; fixture duplication and unnecessary integration are not.
Replace all local copies with one canonical test-support namespace. Cache pure
schema transaction data once. For read/derive/reconcile tests, prefer an
immutable DB value or Datahike `with` against a known base. Keep a fresh
connection only when the behavior under test is transaction, connection,
history, listener, or ambient-connection behavior.

Do not share one mutable connection across unrelated tests merely to improve
timing. That would exchange latency for order dependence. The useful split is
pure DB-value tests versus truly transactional tests.

### Real timers and processes sit in the default gate

- `seon.store.wire-test` exercises the production 500 ms ping delay. The
  success case waits twice and the exhausted case waits four times, imposing
  about three seconds to test retry counting. Inject the one retry sleeper or
  clock used by production and make these unit cases immediate. Keep one real
  startup-race journey in process acceptance.
- `seon.agent.shell-test` launches real Node/Python/shell processes, includes a
  one-second background command, timeout processes, and 100 ms polls. The
  argument/envelope/paging logic belongs in the fast gate; real signal,
  timeout, and subprocess I/O belong in a bounded process acceptance tier.
- `seon.agent-loop-test` deliberately polls wake paths and includes 200 ms and
  400 ms waits. Keep pure transition/fence behavior fast; keep one end-to-end
  wake path and one timeout path in runtime acceptance.

### Bootstrap/eval coverage is too broad for every edit

The self-host compiler is process-cached, so `ensure-bootstrap!` is not loading
the analysis cache from disk on every call. The suite still drives many actual
self-host evals and fresh agent stores. `seon.eval.record-eval-tee-test` is the
largest concentration, followed by resume/replay and SCI rendering tests.

The reliability refactor changes this boundary substantially. Keep a compact
matrix proving declaration detection, canonical persistence, reconstruction,
same-symbol schema change, dependency-aware re-instrumentation, and no replay
of arbitrary side effects. Delete tests for the old tee/prune/replay sequence
with the production code; do not port every historical regression to the new
implementation shape.

## Runner mechanisms to simplify

### Delete the `--list` poll-and-kill prepass

`bin/test-cljs:117-149` runs the complete bundle with `--list`, polls once per
second until the namespace count is stable for three samples, then sends
`kill -9`. The bundle is loaded again at `bin/test-cljs:193-196` for the real
run.

The behavior is not mysterious: Shadow's list branch prints tests and returns
without exiting at
`reference-code/shadow-cljs/src/main/shadow/test/node.cljs:74-83`; only the
`:end-run-tests` reporter exits the Node process. The Seon script turned that
upstream behavior into a second cold load and a timing heuristic.

Use the actual run as the one authority:

- emit its `:begin-run-tests` `:ns-count`/`:var-count` as a machine-readable
  marker (Shadow already constructs those fields in
  `reference-code/shadow-cljs/src/main/shadow/test.cljs:65-88`);
- require its final summary and `:end-run-tests` exit;
- classify a module-load exception from that same process; and
- remove the list process, stability polling, and force-kill.

Summary absence already proves truncation, as `bin/test-cljs:202-213` says.
There is no need for a second roster process to establish the same fact.

### Delete tail recovery

`bin/test-cljs:236-284` reruns the missing namespaces plus the last-started
namespace in a fresh process and can clear `TRUNCATED` when that tail passes.
This preserves a flaky/order-dependent suite by changing its execution state.
It also means a green verdict may be assembled from two processes rather than
one complete run.

The correct behavior is simpler: a run that does not reach its final summary
fails. Fix the async test that did not settle. Never retry a test runner stall
into green.

### Make one async terminal universal

Static census finds 673 `(async done …)` blocks, only 71 references to the
shared `settle!` terminal, 16 bare `(.then done)` tails, 22 `.finally done`
tails, and many hand-written catch rails. Bare tails are concentrated in
`seon.agent.ctx.render-fns-test`.

Replace the variants with one helper that:

- resolves or rejects into an assertion;
- calls `done` exactly once on both rails;
- owns a per-test timeout;
- clears its timer; and
- reports the test var/namespace when it times out.

Then add a structural lint that rejects new bare `.then done`, Promise chains
without the terminal, and direct wall-clock sleeps in fast tests. Once every
async test is bounded, remove both the suite tail retry and vacuous armed probe
workarounds.

### Stop registering probe tests in the outer suite

`seon.test.runner-probes`, `fixture-support-probes`,
`async-fixture-probes`, and `runner-timeout-probes` contain 11 synthetic
`deftest`s. Shadow registers and directly runs them, so they carry `armed?`
atoms that make their outer invocation a vacuous pass and only activate when
driven by the runner self-tests. Comments in those files document the
workaround explicitly.

Make probe bodies ordinary functions/data local to the runner tests, or expose
one test-var construction seam. A test should run once for its intended
assertion, not once as a no-op and again through a second runner.

## Tests tied to superseded production paths

These are deletion/migration candidates, not compatibility requirements:

| Current test path | Why it should not be ported as-is | Replacement behavior |
|---|---|---|
| `test/my/skills_test.cljs`, skill rows in `test/seon/test_seed.cljs`, `skills-dir-precedence`, and skill seed assertions | Keeps the default skill/context subsystem alive after that path is retired. It also snapshots headings and unload teaching. | No default skills block. Dynamic context blocks come from DB facts; missing blocks are omitted. |
| `test/seon/config_test.cljs:238-242` and `test/seon/boot/reconcile_seed_test.cljs` | Tests route tombstones (`:seon.config/removes`) and full boot reconciliation. Target config means exact declared population; removal is absence. | Apply exact config, retract stale declared facts, preserve outside facts, second apply writes zero datoms, recover a fenced partial apply. |
| `test/seon/state_test.cljs:1-140` | Treats transaction provenance/origin as management authority via `:seon.db/managed-scope`. The new design explicitly separates provenance from config ownership. | Head-fenced desired/current reconciliation over an explicit declared population. Preserve unrelated datoms independent of who wrote them. |
| `test/seon/resume_replay_test.cljs` | Exercises the old resume/replay mechanism even though arbitrary eval replay is forbidden. | Reconstruct strict declarations only; mark unavailable prior runtime results honestly; prove side effects are not fired again. |
| `test/seon/index_core_test.cljs` and ghost-prune assertions | Pins repeated source builders and pruning that the refactor replaces with one exact old/new program reconciliation. | One scan/build result, exact add/change/remove sets, zero work when unchanged, deletion in the same transaction plan. |
| `test/seon/dev/runtime_id_test.cljs` legacy clusterless candidate and date-shaped minted IDs | Explicitly preserves a pre-C27 advertisement and the old generator shape. | One current advertisement grammar; readable package IDs only for agents; compact allocator for other persisted identities; collision retry under the writer. |
| Legacy per-agent canvas HTML-slot tests in `seon.render-test` and `seon.render.canvas-test` | Passing an old field and asserting it is ignored is still compatibility surface. It is distinct from the valid HTML twin on context blocks. | Remove the old canvas attribute/schema/caller. Unknown input fails validation; context-block HTML twins remain supported. |
| `seon.ctx-test`'s private `assemble` helper at lines 280-307 | Reconstructs the shape of retired `assemble-context` so old tests can keep asserting it. | Query `context-root`, `rendered-context-blocks`, and prompt/debug producers directly. |

Provider/config tests also need semantic migration. Keep exact external wire
contracts, but do not keep pre-DB manifest fallbacks, old environment precedence,
or old defaults as a compatibility lane once config becomes an optional
operation that writes canonical database facts.

## Duplicate coverage worth consolidating

- `seon.ctx-test` overlaps the focused `seon.agent.ctx.*` namespaces for
  namespaces, findings, transcript, warnings, menus, and render functions.
  Keep focused block tests plus a small composition/property suite; remove
  duplicate end-to-end prose checks.
- OpenAI-compatible and Anthropic adapter suites repeat the same happy stream,
  fetch-throw, HTTP-status, timeout, and extra-body behaviors. Express the
  common provider contract once as data and run it against both adapters;
  retain provider-specific JSON/body/caching assertions.
- `my.plan-test` combines the durable state machine, reconciliation, rendering,
  teaching prose, escalation messages, and large-window layout in 34 fresh
  stores. Keep the state/edge cases, but turn render assertions into fixture
  fact/order/bound checks and put long workflow arcs into fewer transactional
  scenarios.
- Canvas behavior is spread across `my.canvas-test`, `seon.render-test`,
  `seon.render.canvas-test`, `seon.ctx-test`, `seon.ui.agent-view-test`, and
  `seon.web.datastar-test`. Preserve boundaries—canvas API, render dispatch,
  layout, and SSE morph—but test each boundary once and one end-to-end journey.

Consolidation means deleting redundant assertions after the stronger behavior
test exists. It does not mean creating a parallel `*-v2-test` suite.

## Exact strings: keep contracts, remove prose snapshots

### Legitimate exact assertions

Keep exact comparisons when bytes or keys are consumed mechanically:

- reader/parser input, spans, repaired source, and round trips in
  `seon.repl.internal-test`;
- HTML escaping/attribute serialization in `seon.ui.html-test`;
- file contents, patches, JSON, Transit, SSE framing, URLs, HTTP headers, and
  external provider request/response fields;
- fully namespaced schema/envelope/error keys and enum values;
- Datastar `data-*` attributes and route patterns;
- Datalog result sets, transaction counts, added/retracted facts, and refs;
- result-handle and reserved-glyph syntax when a parser/detector consumes it;
  and
- the relationship that the raw AI context in debug view equals the prompt
  bytes produced for the same DB value.

### Brittle assertions to replace

Examples include:

- `seon.agent.ctx.menu-test:132-141`, which pins “select an entry…” and a
  production docstring;
- `my.skills-test:225-255`, which pins the retired skills heading, prose, and
  unload hint;
- `my.plan-test:248-350`, which pins the empty teaching, “Recently completed”,
  “Open frontier”, and a glyph legend;
- `seon.ctx-test:309-360`, which pins “Wired:” and renderer error prose;
- the copied multi-section prompt fixture at
  `seon.ai.typeahead-test:154-198`; and
- tests that recognize a whole error sentence because the response has no
  structured code.

Replace them with these assertion shapes:

- seed sentinel block names/facts, then assert order, omission, bounds, and
  twin availability;
- parse returned hiccup and assert semantic elements/attributes rather than
  class strings or headings;
- assert plan ready/active/done ids and source titles, not legend prose;
- assert typed error kind/code/ref/retryability; if the response lacks a code,
  improve the response shape instead of regexing its English;
- render prompt and debug context independently from the same DB value and
  compare them, without hardcoding either; and
- compute token totals from returned block projections and assert the sum and
  omission rules.

The existing `system-text-has-no-bare-margin-prose` test is a good model: it
asserts a grammar invariant while explicitly refusing to pin the teaching.
The existing prompt/debug byte-equality property is also valuable because it
compares two live producers rather than a stored snapshot.

## Target suite and budgets

Keep one command owner and one production path:

| Tier | Entry | Hard bound | What belongs |
|---|---|---:|---|
| Fast gate | `bin/test-cljs` | 90 s Node runtime; 5 s per async test | Pure transforms, schemas, parsers, small DB-value/transaction behavior, mocked provider/wire/UI transforms, error edges. No full boot, real sleep, real subprocess, network, browser, or model. |
| Runtime acceptance | `bin/test-cljs --tier runtime` | 180 s total; 30 s per journey | One cold boot, warm mint/resume, declaration reconstruction/instrument delta, config crash recovery, Datahike branch/restore, one wake loop. |
| Process acceptance | `bin/test-cljs --tier process` | 90 s total; 30 s per journey | Real UDS writer/feed, process startup race, shell signal/timeout, restart boundary. |
| Live web acceptance | explicit default-pod drive | 180 s total | Roster/new-agent, agent page, one SSE feed, canvas/button/input morph, debug raw-context/token accounting, focus/sidebar behavior. |
| Paid model acceptance | explicit provider-authorized drive | Explicit scenario/turn/time/cost budget | DeepSeek/Muse behavior only; never the default gate. |

The first implementation should measure these targets and tighten them; a hard
bound must fail, never trigger a retry lane. Track compile and execution time
separately so a slow runtime cannot be blamed on Shadow compilation.

Use one structural tier authority—for example an acceptance namespace/path
convention consumed by `bin/test-cljs`—rather than hand-maintained duplicate
rosters. All tiers must call the same production functions. A tier changes
fixture depth and external resources, not implementation.

## Ordered migration

1. Emit begin/end counts from the actual Shadow run; delete the `--list`
   process and tail retry.
2. Make the bounded async terminal mandatory and migrate bare/manual tails.
   Add a structural lint so the problem cannot return.
3. Extract one canonical fresh-DB helper and replace all local copies. Cache
   pure schema tx data, not mutable connections.
4. Move gym full-boot journeys, real shell sleeps, and real startup retry into
   bounded acceptance tiers. Replace their fast cases with pure/fake-clock
   behavior tests.
5. Delete tests and production code for skills-default, route tombstones,
   provenance-managed `seon.state`, arbitrary replay, ghost pruning,
   clusterless runtime IDs, and legacy canvas input in the same commits.
6. Collapse context/render tests around actual block entities and the one
   prompt/debug producer relationship. Remove instruction/prose snapshots.
7. Consolidate provider contract and plan/render scenario matrices without
   losing boundary/edge coverage.
8. Run the fast gate after each refactor phase; run the affected acceptance
   tier at phase boundaries; run the complete cold/process/browser matrix
   before PRD graduation.

## Required behavioral coverage after the reliability refactor

The trimmed suite is complete only if it mechanically proves:

- generated ID collision detection across registered managed identity attrs;
- provenance transaction metadata contains user and process refs, with the
  explicit genesis case;
- config absent preserves state, explicit empty is distinct, exact apply
  repairs partial state, unrelated facts survive, and converged apply is a
  no-op;
- program reconciliation computes add/change/remove once and performs no work
  when unchanged;
- Malli registry reconstruction is exact, validated, and instrumented once,
  then same-key schema/function changes update only the dependency closure;
- arbitrary eval effects are never replayed and missing runtime results are
  represented honestly;
- as-of is read-only, writable branches are isolated, restore is fenced and
  crash recoverable, and coordinates are branch/commit qualified;
- feed invalidation follows observed DB reads, duplicate subscriptions share a
  renderer, unchanged units do not render, and render caps fail loudly;
- debug context shows the exact raw AI text, optional HTML twin, per-block
  token breakdown, and correct total while omitting missing blocks; and
- canvas controls, message replies, sidebar focus/order, and latest transcript
  behavior survive cold boot and restart through the normal reactive path.

That is stronger coverage than the current 1,231-test count because it tests
state transitions and invariants at their owning boundaries instead of keeping
historical paths and prose alive.

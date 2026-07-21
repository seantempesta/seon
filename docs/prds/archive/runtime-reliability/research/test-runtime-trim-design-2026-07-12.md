---
type: research
status: completed
tags: [research, prd, flow, agent]
---

# CLJS test-runtime trim design (2026-07-12)

> **Owner correction, later 2026-07-12:** `seon.gym` is a retirement target,
> not a refactoring target. It will be replaced by Inspect AI or another
> reviewed external harness. Do not implement the proposed pure gym seams or
> structured gym-result expansion below. The measured 24-seed repetition,
> immutable-DB test support, assertion-quality policy, tiering, and list of
> unique regressions remain valid evidence. Migrate only unique regressions to
> their production owners, preserve scenario/evidence data for the selected
> replacement, then delete the homegrown driver/scorer.

## TL;DR

The default CLJS gate can lose roughly half its Node runtime without weakening
coverage. The dominant defect is architectural: `seon.gym.driver-test` invokes
the complete cluster seed 24 times to test predicate truth tables, judge
wiring, context telemetry, and battery selection. A clean run spent about
146 seconds in that namespace alone. `seon.boot.reconcile-seed-test` invokes
the same full seed twice more to preserve a config/tombstone path the runtime
reliability design is deleting.

The mechanically safe suite correction is not a shared mutable test connection
and not a cached legacy boot. The original audit proposed splitting the gym
pipeline in place into:

- pure scenario validation and selection;
- pure/mechanical predicate evaluation over an immutable database value;
- pure scorecard assembly from structured observations;
- one scenario overlay transaction for tests that truly need writes; and
- the existing end-to-end drive, retained only in a bounded acceptance tier.

Datahike already supplies the right unit-test primitive. `datahike.api/with`
applies transaction data to an immutable database value and returns a new
transaction report without mutating the base
(`reference-code/datahike/src/datahike/core.cljc:126`). A cached immutable base
database is therefore safe for read/derive tests. A fresh connection remains
mandatory for transaction envelopes, history, listeners, ambient-connection
behavior, and agent turns. Never turn the cached database value back into a
writable shared connection.

That in-place gym refactor is superseded by the owner correction above. The
default fast gate should still run zero `boot-seed!` calls. One cold boot, one
warm mint/resume, and one representative scripted agent journey belong in the
runtime acceptance tier. Once the canonical Datahike branch lifecycle lands,
the acceptance harness may seed one scratch base and create isolated writable
copy-on-write branches for additional scenarios through that production
mechanism. It must not invent a test-only clone path first.

The string-assertion cleanup must be semantic, not a blanket ban. Exact parser
grammar, EDN/JSON/SSE framing, URLs, route patterns, Datastar attributes, HTML
escaping, schema keys, and external provider fields are contracts. Context
teaching, headings, glyph legends, docstrings, full error sentences, and
human-facing response prose are not. Tests should assert facts, refs, ordering,
omission, bounds, error codes, token totals, and equality between live
producers. In particular, the prompt/debug equality property is valuable
because it compares two current paths; a copied prompt fixture is not.

## Scope and evidence

This is a read-only source audit. No pod, store, test process, or ACME process
was started. Evidence came from:

- the completed suite audit in
  [[cljs-test-suite-speed-and-quality-audit-2026-07-12]];
- current `bin/test-cljs`, `shadow-cljs.edn`, and the Shadow node runner;
- every active gym driver/test namespace;
- the current CLJS test tree and repeated database fixtures;
- completed logs under `tmp/test-cljs-*.log`; and
- Datahike's immutable `with` and copy-on-write branch implementation.

No external model response was used.

## Current execution topology

### The full gate is runtime-bound, not compile-bound

The completed audit measured a representative clean run at:

| Stage | Observed time |
|---|---:|
| Shadow incremental compile | 8.86 seconds |
| Node test process | about 302 seconds |
| `seon.gym.driver-test` span | about 147 seconds |

Recent completed logs retain the same shape. In
`tmp/test-cljs-20260712-115411-69754.log`, the first timestamp after
`Testing seon.gym.driver-test` is `15:56:58.336Z`; the last is
`15:59:24.004Z`, a 145.668-second span. The namespace emitted 15 scorecards and
opened 32 agent turns, but the source statically reaches the full boot seed 24
times.

### Exact accounting for the 24 gym boots

`test/seon/gym/driver.cljs:1752-1980` makes `run-scenario!` perform all of the
following for every runnable scenario:

1. open and schema-seed a new memory connection;
2. acquire the bootstrap compiler state;
3. invoke `seed-scenario-world!`;
4. invoke the complete `client/boot-seed!` inside it;
5. synchronize AI configuration;
6. create one or more agents;
7. land messages and run real turn/eval machinery;
8. query and render the post-run world; and
9. restore the process-global connection, filesystem config, environment, and
   Malli registry.

The 24 seed invocations in `test/seon/gym/driver_test.cljs` are:

| Source | Full seeds |
|---|---:|
| Four `run-and-expect-pass!` scenario tests | 4 |
| Other runnable direct `run-scenario!` calls | 16 |
| Two matching members inside the competency-battery test | 2 |
| Two direct `seed-scenario-world!` parity tests | 2 |
| Total | 24 |

The two refusal tests call `run-scenario!` but return before opening a
connection, so they are not in this total.

`test/seon/boot/reconcile_seed_test.cljs:45-84` adds two more complete seeds in
the default gate. It tests route tombstones via `:seon.config/removes`, which
the target exact-config design explicitly replaces with absence from the
declared population. The current default gate therefore executes at least 26
complete seeds whose broad work is not needed by the assertions around them.

### Repeated database fixtures are the next broad cost center

A current static scan finds:

- 112 CLJS test namespaces;
- 41 test files that directly call `d/create-database`;
- 501 tests inside those 41 files;
- 32 test files that call `client/open-agent-conn!`; and
- 46 local helper definitions matching a `with-*conn` shape.

Isolation is correct. Reimplementing connection/schema setup and using a
writable connection where an immutable database value is sufficient are not.
The suite should centralize the lifecycle without sharing mutable state.

### Acceptance instruments are registered as vacuous default tests

The default artifact currently registers 23 environment-gated test vars whose
normal execution is a one-assertion skip:

- `seon.gym.baseline-test`: one;
- `seon.gym.measure-test`: one;
- `seon.gym.database-memory-drive-test`: three;
- `seon.gym.paid-test`: 17 paid/gated journeys, excluding its two real unit
  tests; and
- `seon.gym.scorecard-test`: one battery drive.

They are not the main runtime cost when disabled, but they inflate the default
roster and blur the meaning of a passing test. The database-memory drive even
labels itself a temporary measurement instrument and says to delete it after
the drive. Operator instruments should be command-owned acceptance runs, not
no-op `deftest`s.

## Safety invariants for the trim

The speedup is valid only if all of these remain true:

- Every mutable test gets a fresh connection or an isolated production branch.
- No two tests share a writable connection.
- A pure test may share only an immutable database value.
- `d/with` is a test oracle, never a second production transaction path.
- Tests of `seon.db/transact!` still call `seon.db/transact!`; they do not use
  `d/with` and accidentally bypass validation/envelope behavior.
- Tests of history, listeners, CAS, transaction metadata, ambient connection
  lookup, or root `db/*conn*` behavior retain a real connection.
- The root `db/*conn*`, process environment, filesystem config, and Malli
  registry are restored on both success and rejection.
- Acceptance scenarios remain sequential in one Node process while those
  process-global cells exist. Database branches do not isolate JavaScript
  globals.
- One accepted cold-boot journey calls the same production boot owner used by
  the pod.
- No cached or reduced legacy boot is introduced for tests.
- A missing final test summary remains a failure; no tier retries or stitches a
  partial run into green.

## Target test architecture

### One canonical database test support namespace

Add one test-only owner, in place of the repeated local helpers, with four
separate concepts rather than one overloaded fixture:

| Surface | Purpose | Safe consumers |
|---|---|---|
| Cached schema transaction data | Compute the canonical pod test schema once as immutable data | Every fixture |
| Immutable base database promise | Create/schema-seed once, expose only its immutable database value | Query, derive, render, reconcile compilers, gym predicates |
| `with`-database helper | Apply trusted fixture transaction data with `d/with`, return the new database value/report | Pure tests only |
| Fresh connection bracket | Create a unique memory store, set/restore root `db/*conn*`, and settle both rails | Transact/history/listener/ambient/agent tests |

The immutable path is sound because Datahike's `with` checks that the input is a
database value, applies the transaction through the normal transaction
compiler, and returns a report with a distinct `:db-after`; it does not mutate
the input (`reference-code/datahike/src/datahike/core.cljc:126-149`). The
roadmap already settles `datahike.api/with` as the test oracle for proving a
compiled transaction's result.

The cached base should contain only canonical installed schema and genuinely
universal root facts needed by most reads. Tests add the few facts they need.
It should not contain a complete cluster seed, route catalog, skills corpus,
program graph, or current context prose.

The fresh-connection bracket should own the existing CLJS-specific root
`set!`/restore rule and the shared `seon.test.async/settle!` terminal. Callers
should not each reimplement Promise rails. Because the root binding is a shared
JavaScript global, the default runner remains sequential; a test that launches
concurrent fibers must re-pin the intended connection immediately before an
ambient read.

### Factor the gym by behavior boundaries, not by test mode

`run-scenario!` currently combines validation, world construction, driving,
observation, rendering, and scoring. Extract the existing logic into named
functions in the same `seon.gym.driver` namespace; do not create a `v2` driver
or test-only alternate scorer.

The useful boundaries are:

1. Scenario validation and anti-bait checks.
2. Selection of scenarios by competency/status/tier.
3. Compilation/application of the scenario's schema/fixture overlay.
4. Evaluation of one mechanical predicate against an immutable database value,
   transcript value, and designator map.
5. Evaluation of a vector of mechanical predicates and axis rollup.
6. Judge-context construction and judge-result parsing.
7. Scorecard assembly from observations plus supplied run metadata.
8. The existing end-to-end scenario drive, which composes all seven.

Tests call the narrow owner for the behavior they assert. The end-to-end drive
calls those same owners. This is one implementation with several pure seams,
not a fake runner.

### Make gym observations data, then render diagnostics

The current `:seon.gym/result` stores only predicate id, axis, pass boolean, and
one `:seon.gym.result/actual` sentence
(`test/seon/gym/driver.cljs:328-336`). Tests consequently parse that sentence
for token totals, prompt hashes, out-of-range conditions, and thrown status.
That makes diagnostic wording an API accidentally.

Keep the human diagnostic string if it is useful, but add facts produced by the
evaluation itself. Examples:

- observed row vector and expected row predicate;
- observed transcript token estimate;
- matching and total eval counts;
- prompt blob refs, unreadable blob refs, and selected turn index;
- observed domain attributes;
- observed eval error rate and configured maximum;
- selected agent ref and whether canvas content was present; and
- structured predicate error data when evaluation threw.

The predicate's existing attributes already identify which observation fields
apply; no result `:type`/`:kind` discriminator is needed. Tests assert the
facts. `:seon.gym.result/actual` becomes a presentation derived from them.

Apply the same rule to refusal and judge results:

- add a refusal reason/code such as paid-not-authorized or scenario-not-runnable
  instead of regexing `:seon.gym/error`;
- record why a judge result was skipped/failed as structured data instead of
  recognizing the word `SKIPPED`; and
- retain justification text as evidence, not the control field tests branch on.

The self-bait and alias-blind guards already expose useful ex-data. Their tests
should assert scenario id plus `:seon.gym.run/fixture-index` or
`:seon.gym/alias-blind-ns`; they do not need to search the exception sentence.

### One artifact, structurally selected tiers

Keep `bin/test-cljs` as the command owner and `out/test/test.js` as the compiled
artifact. Shadow's node runner accepts a comma-separated namespace/var list via
`--test=`
(`reference-code/shadow-cljs/src/main/shadow/test/node.cljs:14-42`). Use a
directory/namespace convention as the only tier declaration, then derive the
namespace list from that convention. Do not maintain a second handwritten
roster and do not reintroduce the old `--list` Node prepass.

Suggested tiers:

| Tier | Default selection | Contents |
|---|---|---|
| Fast | `bin/test-cljs` | Pure/schema/parser/wire/render transforms, small immutable DB tests, focused fresh-connection behavior |
| Runtime | `bin/test-cljs --tier runtime` | One cold boot, warm mint/resume, declaration reconstruction, config recovery, branch/restore, one wake loop |
| Process | `bin/test-cljs --tier process` | Real writer socket/startup race, subprocess signals/timeouts, feed transport |
| All | `bin/test-cljs --tier all` | Fast plus runtime plus process in one authoritative run |

The tier changes which tests execute, never which production function they
call. All runs still require one Node exit, one final summary, and zero
unbracketed core faults.

Move baseline, measurement, paid-model, and scorecard-battery drives behind
their existing operator commands as ordinary functions. A disabled instrument
should not register a test that passes by doing nothing. Keep the two real pure
unit tests currently housed in `seon.gym.paid-test`, but move them to a normal
fast namespace. Delete the explicitly temporary database-memory drive once its
recorded evidence is preserved.

## Exact gym migration map

### Default fast gate

| Current tests | Current broad setup | Replacement |
|---|---|---|
| Four shipped stub scenarios | Four full boots and agent drives | Validate every scenario as data; move only S01's real message/run/turn loop to runtime acceptance; rely on the owning DB/message tests for the other three behaviors |
| Broken predicate honesty | Full envelope-honesty drive | Evaluate a deliberately false Datalog predicate over a tiny immutable DB; assert structured observed rows, failed result, unaffected sibling result, and false axis |
| Two seeded-world parity tests | Two full seeds | One cold-boot acceptance query; unit-test core/config desired transaction data directly; delete old provenance/tombstone expectations with their production paths |
| Domain-attribute fork detection | Full boot plus scripted eval | Apply two trusted domain facts/provenance transactions to a small fixture DB and call the mechanical predicate evaluator |
| Prompt blob include/exclude and missing blob | Two full boots and turns | Persist/read one fixture blob plus one missing ref against a tiny run/turn DB; call prompt predicate evaluation directly and assert structured blob observations |
| Turn-profile telemetry | Full boot, two turns, dynamic block install | Render a constructed agent/context-block DB twice; assert block identity/order/token sum and that telemetry does not enter results |
| Config-manifest comparison | Two complete scenario boots | Test exact config apply and context block derivation at their owners; keep one config-to-agent-context runtime journey after the new config operation lands |
| Three consultation-anchor tests | Three boots and scripted evals | Use a small DB containing first-eval source facts; table-test no read, generic store read, and domain read through the same evaluator |
| Four judge scenario tests | Four boots and message drives | Build minimal user/agent/message facts, call judge-context construction and verdict parsing directly, assert per-agent inclusion/exclusion and result axes |
| Two curation-axis tests | Two boots and scripted evals | Build run/turn/eval/canvas facts directly; assert rate and canvas derivations plus predicate evaluation |
| Competency battery selection | Two full scenario runs | Extract/test pure matching order; retain one sequential composition acceptance test, not two full boots in the fast gate |
| Paid and todo refusal | No boot due to early return | Keep as fast structured refusal-reason tests; stop regexing prose |

The two-agent Datalog join regression inside the judge scenario is a database
planner contract. Move it to the focused Datahike query regression using the
minimal two-agent/message facts. It does not need a judge or an agent turn.

### Runtime acceptance

Retain only journeys that prove wiring between owners:

1. Fresh scratch store through the canonical boot owner; query genesis,
   program/schema/config facts and transaction count.
2. Mint one agent, land one message, run the S01 scripted loop to idle, and
   assert durable run/turn/message facts plus scorecard schema.
3. Restart/resume the same logical store and prove no reseed/converged write and
   no duplicate agent.
4. Apply one explicit config change and prove only the declared subset/context
   changes.

Those journeys cover the seams the pure tests cannot. They should not also
retest every predicate kind.

### Acceptance branch reuse after the branch lifecycle lands

Datahike's `branch!` creates a new branch from a branch or commit and explicitly
copy-on-write branches secondary indexes
(`reference-code/datahike/src/datahike/versioning.cljc:98-144`). After Seon's
canonical branch coordinate and lifecycle are implemented and tested, a
runtime-acceptance process may:

1. create one scratch logical database;
2. perform one canonical fresh boot;
3. record the base commit coordinate;
4. branch from that commit for each additional writable scenario;
5. connect and run the scenario sequentially on its branch; and
6. release/delete the branch through the production lifecycle.

This preserves writable isolation and exercises the product's real fork path.
It must wait for the canonical lifecycle because current scenario runs also
mutate root `db/*conn*`, process environment, filesystem config, compile state,
and the Malli registry. Copy-on-write storage alone does not make them safe to
run concurrently.

## Prose snapshots: keep contracts, delete presentation coupling

### Static candidate census

Across the current 112 CLJS test namespaces, a mechanical scan finds:

- 474 `is` assertions directly using `str/includes?`;
- 158 `is` assertions directly using `re-find`; and
- 395 `is` assertions beginning with equality to a literal string.

These are candidates, not automatic defects. Many compare fixture data or
machine-consumed syntax. Review by consumer, not by regex count.

### Exact comparisons that remain legitimate

Keep exactness where another machine consumes the representation:

- reader/parser input, repair spans, delimiters, and round trips;
- EDN, JSON, Transit, and provider request/response field names;
- SSE event framing, gzip payload equivalence, URLs, HTTP headers, and route
  patterns;
- HTML escaping and attribute serialization;
- Datastar `data-*` attributes, signal names, and action URLs;
- fully namespaced schema/envelope/error keys and enum/code values;
- Datalog rows, datoms, refs, transaction counts, and add/retract sets;
- result-handle and reserved-glyph grammar consumed by a parser/detector;
- source text whose exact persistence/reconstruction is the behavior under
  test; and
- equality between the raw AI prompt and the debug view produced independently
  from the same database value.

An exact fixture title, message content, source path, or stored source string is
also legitimate when the assertion is a write/read round trip of that input.
The problem is asserting today's explanatory sentence, not asserting data
fidelity.

### High-priority brittle surfaces

| Current test area | Brittle assertions | Behavioral replacement |
|---|---|---|
| `test/my/skills_test.cljs:210-272` | Skills headings, status glyph, cost footer, unload instruction | Delete with the retired default skills path; dynamic context tests assert DB block facts, omission, and optional render twin |
| `test/my/plan_test.cljs:248-350` and later render windows | Empty teaching constant, “Recently completed”, “Open frontier”, glyph legend, escalation prose | Assert ready/active/done/blocked ids, order, dependency truth, window bounds, and message refs; exact user-supplied titles remain valid round trips |
| `test/seon/agent/ctx/menu_test.cljs:122-146` | Selection instruction and production docstring sentence | Extract/assert ranked entry data: symbol, args projection, call count/order, privacy and failed-eval exclusion; keep one minimal renderer grammar test |
| `test/seon/ctx_test.cljs:280-307` | Private `assemble` helper reconstructs a retired API shape | Delete it; query `context-root`, `rendered-context-blocks`, prompt producer, and debug producer directly |
| `test/seon/ctx_test.cljs:309-360` | “Wired:” and renderer error wording | Assert nonblank canvas block, no internal error data leak, typed error block/ref, and prompt/debug equivalence |
| `test/seon/ai/typeahead_test.cljs:154-198` | Copied multi-section production prompt including current plan/menu/transcript teaching | Keep a minimal synthetic grammar fixture containing only the section/event markers `null-render` parses; assert retained/dropped grammar regions, not current prose |
| `test/seon/gym/driver_test.cljs` | Parses `actual`, refusal, and judge-justification sentences | Add structured observations/reasons and assert those facts |
| `test/seon/render/canvas_test.cljs:253-284` | Error guidance sentences and quoted wording | Assert structure path, structured error code, offending value/index, and safe human/AI twin separation |
| `test/seon/agent/search_test.cljs`, shell/web search error tests, and similar envelopes | Regexes full guiding English | Assert `:seon.error/kind`, stable error code, retryability, structured coordinates, and preservation of raw external stderr where relevant |
| `test/seon/index_core_test.cljs` old stub/ghost cases | Exact synthetic `(ns …)` stubs and pruner behavior | Replace with canonical program-row add/change/remove/no-op facts when the program reconciler lands; delete pruner compatibility coverage |
| `test/my/ui_test.cljs` | Full human/AI formatted sentences for presentation helpers | Assert fixture facts appear in both twins in the same semantic order, plus Hiccup element/attribute structure; retain exact text only if explicitly documented as a machine wire format |

### Context-specific invariants to keep

The following current patterns are strong and should survive consolidation:

- `system-text-has-no-bare-margin-prose` checks reader-safe grammar without
  pinning teaching words.
- `prompt-and-debug-view-are-byte-identical` compares current live producers
  rather than a stored snapshot.
- missing context blocks are omitted rather than rendered as placeholders;
- per-block token estimates sum to the total through the canonical estimator;
- a block's raw AI twin and optional HTML twin refer to the same block fact;
- HTML-only blocks do not leak into the AI prompt;
- block identity/order comes from database refs and priorities; and
- absent optional HTML remains absent.

Plan, transcript, canvas, and debug-view tests should follow this pattern:
assert the data and cross-producer relationship, never the current explanation.

## Expected runtime impact

The directly evidenced lower bound is substantial:

- Current full Node run: about 269–318 seconds across recent clean logs.
- Current gym driver namespace: about 137–152 seconds.
- Removing full boots from the fast gym tests should remove nearly all of that
  137–152-second span; pure predicate and small immutable-DB tests should be a
  small fraction of it.
- A 302-second representative run would therefore fall to roughly 150–170
  seconds before any other optimization, a reduction near one half.
- Deleting the obsolete two-boot route-tombstone test should remove additional
  broad boot work, but it has not been isolated with a trustworthy timer and is
  not included in the numeric promise.
- The production retry test currently spends about three seconds on real ping
  sleeps; injecting the sleeper for unit cases removes that fixed floor while
  preserving one real startup-race process acceptance.

The 90-second fast-gate target remains reasonable but is not proven by the gym
cut alone. After it lands, instrument per-namespace elapsed time in the one
Node run and migrate the next measured offenders. Likely candidates are broad
self-host eval suites, real subprocess tests, and files that create a fresh
database for every read-only render/derive case. Do not guess their savings or
share a mutable connection merely to hit the budget.

Compile time should remain approximately unchanged at first because the one
artifact still compiles all tier namespaces. That is acceptable: compile is a
single-digit/low-double-digit-second stage, and keeping acceptance code
compile-checked prevents drift. Only split compile artifacts if measurement
later proves compilation material.

## Ordered minimal refactor

### Commit 1 — structured gym observations and pure seams

- Replace private string-only predicate observations with structured result
  facts plus derived diagnostics.
- Extract pure scenario selection, predicate-vector evaluation, axis rollup,
  judge context/parsing, and scorecard assembly in the existing namespace.
- Add structured refusal and judge skip/failure reasons.
- Keep `run-scenario!` as the single composition owner.

Proof: table-driven pure tests cover every predicate expectation shape,
unknown agent designator, thrown query, missing/unreadable prompt blob, axis
rollup, refusal reason, and judge parsing without opening a connection.

### Commit 2 — canonical test database support

- Add one cached canonical schema transaction value.
- Add one immutable base database promise and `d/with` fixture helper.
- Add one fresh-connection/root-restore bracket using `settle!`.
- Migrate gym predicate fixtures first; then migrate duplicated helpers only in
  measured/high-touch namespaces.

Proof: two derived DB values from the same base cannot see each other's facts;
two fresh connections cannot see each other's facts; rejection restores root
`db/*conn*`; transactional tests still observe the `seon.db` envelope.

### Commit 3 — remove full boots from the fast gym namespace

- Replace the 20 runnable `run-scenario!` calls, two battery member runs, and
  two direct world seeds according to the migration table.
- Move the one S01 end-to-end journey and one cold-boot parity query to runtime
  acceptance.
- Keep scenario EDN validation and anti-bait/alias guard coverage fast.

Proof: instrument the fast test process and assert/observe zero calls to the
canonical boot owner; compare the retained scorecard predicate/axis coverage
set with the pre-refactor set.

### Commit 4 — delete obsolete boot and no-op test paths

- Delete `seon.boot.reconcile-seed-test` with route tombstones and the old
  reconciler.
- Move baseline/measure/paid/battery drives to operator-owned commands.
- Delete the explicitly temporary database-memory drive after preserving its
  evidence.
- Select fast/runtime/process tiers structurally from the same artifact.

Proof: default roster contains no environment-gated vacuous tests; each
acceptance command emits one complete final summary and fails on an unset
required opt-in rather than claiming a test pass.

### Commit 5 — remove prose coupling with owning refactors

- Delete skills-default tests with the production path.
- Convert plan/menu/context/gym error assertions to facts and typed codes.
- Reduce the typeahead fixture to parser grammar.
- Consolidate duplicate context/canvas/render assertions at their owning
  boundaries.

Proof: changing explanatory teaching or headings alone does not fail tests;
changing a block ref/order, datom, structured error, parser marker, route,
Datastar attribute, or raw prompt/debug relationship does.

### Commit 6 — measure and trim the remaining fast gate

- Record per-namespace runtime from the single Node process.
- Replace production sleeps in unit tests with injected clocks/sleepers.
- Move real shell/writer/feed lifecycle cases to process acceptance.
- Convert read-only fresh-connection tests to immutable database values where
  their behavior permits.
- Consolidate provider contract matrices while retaining provider-specific
  wire assertions.

Proof: fast Node runtime at or below the agreed budget; runtime/process tiers
within their bounds; no coverage loss in the reliability transition matrix.

## Required coverage after trimming

Test count is not the target. The final suite must mechanically prove:

- fresh boot, converged restart, changed config, hot reload, mint, resume,
  eval, route/view change, branch, and restore transitions;
- zero full boot work on warm mint and zero seed transaction on converged
  restart;
- exact config repair with absent input distinct from explicit empty input;
- collision-safe atomic ID allocation and no partial candidate transaction;
- genesis plus user/process transaction refs;
- program/schema add/change/remove/no-op reconciliation;
- exact Malli reconstruction and dependency-aware incremental instrumentation;
- no arbitrary effect replay;
- isolated writable branches and fenced restore;
- observed-read UI invalidation, shared subscriptions, bounded rendering, and
  no duplicate focus tile;
- raw prompt/debug equality, optional HTML twins, per-block token accounting,
  and missing-block omission; and
- canvas controls, transcript latest-reply behavior, sidebar ordering, and
  restart continuity through the normal reactive path.

That is stronger coverage than repeatedly booting a cluster to see whether a
diagnostic sentence contains today's wording.

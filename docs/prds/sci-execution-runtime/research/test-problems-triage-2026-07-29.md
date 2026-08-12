---
type: research
status: active
tags: [research, testing]
---

# Test-problem classes and fix waves

## Verdict

The reported problems are not a queue of individual test repairs. They reduce
to eight implementation classes, four uncovered contract classes, and three
small test-harness classes. A fix lane launches for one class and changes the
owning design once; it does not launch for one failing test.

The three largest classes from
[[test-smell-audit-2026-07-29|the awkwardness audit]] remain the spine, but
their current-tree counts have moved:

- production construction copied by fixtures: 35 tests, up from 33;
- lifecycle/render transitions inferred by polling: 14 tests, up from 12; and
- entity walking asserted by point matrices: 20 tests.

Those memberships are not disjoint. For example,
`n-agent-parallel-turns-property` both copies the runtime construction and
polls settlement. The current union of the three headline classes is 58 tests,
not 69. “About 65” in the source audit was the sum of class memberships at its
cutoff, not a deletion promise.

Every class marked **DESIGN REVIEW** is an owner tripwire. Its awkward test is
evidence that the production interface has no honest construction, event,
structure, or contract. A mechanical test-only patch would hide that evidence.

## Boundary and method

This analysis-only pass used the tree at `12e7a9685f90`:

- 53 discovered `*_test.clj` or `*_test.cljc` files;
- 484 `deftest` or `defspec` forms;
- no `src/` or `test/` edits; and
- counts based on test forms, not assertion count or helper call count.

The evidence set was:

1. [[test-smell-audit-2026-07-29]];
2. [[tree-audit-2026-07-29]];
3. the current survivors of Gemini flags in `tmp/reviews/*.md`, especially
   `20260729T110322.472Z.md` and `20260729T110657.118Z.md`;
4. the four requested open issue notes:
   [[flow-monitor-test-preselects-an-unreserved-port]],
   [[a-slow-tab-proof-can-count-a-late-initial-derivation]],
   [[flow-callback-schemas-are-not-generatively-constructible]], and
   [[instrumentation-surfaces-released-connection-contracts]]; and
5. a fresh `rg` sweep for sleeps, polling loops, copied runtime maps,
   presentation-string assertions, producer-count equality, point matrices,
   direct `d/connect`, and missing `d/release`.

A reported flag is included below only if its evidence still exists. Repaired
review findings, such as the missing `core.async` require once reported in
`seon.gen.loop-test`, are not carried forward.

## Dependency ledger

| Dependency or Seon mechanism | Selected source | Constraint on the fix |
|---|---|---|
| `clojure.core.async.flow` at `dc35f3e0d7bc2eef502e77982f48641f025c8051` | `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:108-155`; `flow/impl.clj:98-172` | Flow already publishes report data. Seon must retain and name the application events it needs; tests must not sample effects after those events. |
| Datahike at `9a7a9ef10a95` | `reference-code/datahike/src/datahike/core.cljc:199-217`; `writer.cljc:384-405` | Transaction completion is observable through `listen!`. A committed-fact wait is not a database polling loop. |
| Malli at `80138076960e7820523b4cb932c5b5d1936d4e7f` | registered Seon schemas plus `seon.schema.datahike` | Schemas own constructible domains and projections. Test-local defaults, callback predicates, and optional-`nil` generators must not become alternate contracts. |
| Runtime construction | `src/seon/cluster.clj:659-742` | The live cluster privately builds the loop handle. Fixtures therefore cannot invoke the production construction. |
| Per-agent graphs | `src/seon/cluster/agent.clj:298-356` | Armed entries retain mailbox/completion state but discard the application report stream tests need for armed, idle, pause, and settlement observations. |
| Render graph | `src/seon/render/web.clj`; fixture assembly in `test/seon/render/web_test.clj:110-173` | The fixture drains report/error channels and then busy-pings inferred state. The event exists below the interface and is thrown away above it. |

## Ranking rule

The ranking score is:

`current affected test forms × fragility multiplier`

The multiplier is 5 for races, leaks, hangs, or a second runtime; 4 for copied
production authority or prose coupling; 3 for redundant point matrices; and 2
for misleading names/debt sentinels. Counts overlap across classes, so scores
rank dispatch value and must not be summed as a suite-reduction total.

| Rank | Class-based fix wave | Tests | Cost | Score | Tripwire |
|---:|---|---:|---:|---:|---|
| 1 | Expose the one production runtime construction | 35 | 4 | 140 | **DESIGN REVIEW** |
| 2 | Publish named graph events, including producer overlap | 15 | 5 | 75 | **DESIGN REVIEW** |
| 3 | Replace entity-walk point matrices with one graph property | 20 | 3 | 60 | **DESIGN REVIEW** |
| 4 | Delete test-owned runtime prototypes | 7 | 5 | 35 | **DESIGN REVIEW** |
| 5 | Preserve structured status through the presentation edge | 4 candidates | 4 | 16 | **DESIGN REVIEW before deleting any assertion** |
| 6 | Scope every acquired test resource at acquisition time | 2 tests + 1 dead helper | 5 | 10 | Test-support design |
| 7 | Let the server atomically choose and publish its bound port | 2 | 5 | 10 | **DESIGN REVIEW** |
| 8 | Derive generators and model inputs from honest schemas | 1 current test + 13 uncovered callbacks | 4 | 4 | **DESIGN REVIEW** |

The uncovered contract classes after this table are risk-ranked separately.
Multiplying them by zero existing tests would make the most dangerous missing
coverage look harmless.

## Wave 1 — one production runtime construction

**Design change — DESIGN REVIEW.** Make the private cluster-handle assembly an
ordinary schema'd production function over database-derived dials and explicit
runtime edges. `seon.cluster` calls it in boot. `seon.test-support` calls the
same function with narrow recording-provider or evaluator overrides. The
fixture may replace an external edge; it may not restate production defaults
or interpret source to recreate runtime semantics.

This is not a request to publish a bag of internals. The owner must first rule
the smallest input and output that makes the live construction invokable. The
test smell says that interface is currently missing.

**Affected tests — 35.**

- `seon.cluster.turn-test` — all 20:
  `a-whole-turn-runs-a-REAL-sci-evaluation-end-to-end`,
  `agent-code-with-defn-and-println-folds-green-without-in-ns`,
  `a-settled-orphan-stops-wedging-the-agent`,
  `a-lost-model-call-leaves-a-durable-readable-reason`,
  `a-real-evaluation-that-runs-away-is-stopped-and-recorded`,
  `a-red-form-routes-to-its-namespace-owner-and-the-fold-continues`,
  `a-whole-turn-runs-from-trigger-to-closed-run`,
  `a-completing-disposition-closes-in-the-terminal-transaction`,
  `a-waiting-disposition-frees-the-agent-and-keeps-its-note`,
  `one-successful-call-leaves-exactly-one-attempt-fact`,
  `an-unpaid-failure-with-a-backup-makes-exactly-two-calls`,
  `generated-model-attempt-traces-preserve-presence-and-episode-laws`,
  `a-turn-delivers-what-a-form-asks-to-send-and-still-finishes`,
  `a-refused-delivery-becomes-a-durable-error-fact`,
  `a-recovered-unheld-planned-run-completes-without-error-facts`,
  `a-held-runs-paid-call-is-never-duplicated`,
  `a-prompt-refusal-is-a-recorded-error-value-never-a-throw`,
  `the-call-prompts-with-the-trigger-the-run-opened-on`,
  `streaming-writes-zero-datoms-test`, and
  `concurrent-streams-share-one-conn-test`.
- `seon.cluster.agent-test` — all 11:
  `n-agent-parallel-turns-property`, `park-wake-test`,
  `pause-during-in-flight-call-test`, `episode-cap-refusal-test`,
  `hot-reload-var-test`, `restamp-recovery-test`,
  `arming-does-not-route-a-triggerless-historical-red`,
  `unheld-resume-regression`, `custody-mismatch-regression`,
  `wake-routing-conservation-property`, and
  `wait-closes-in-terminal-tx-test`.
- `seon.gen.loop-test` — all three:
  `a-goal-is-a-message-and-the-attempt-routes-its-own-failures`,
  `a-result-built-on-a-failed-form-is-red-and-routes`, and
  `a-silent-owner-leaves-the-plan-unsettled-forever`.
- `seon.context-test` — `capture-before-provider-test`.

The copied setup includes process identity, channels, provider descriptor,
retry strategy, evaluator, time limit, core-error policy, recurrence limit,
message depth, and result caps. `seon.cluster.agent-test/fake-evaluate` also
regex-matches source to recreate disposition semantics. That fake is a design
verdict, not merely untidy test code.

**One replacement regression.**
`production-runtime-construction-parity-property`: for generated valid dial
facts and explicit runtime edges, boot and `seon.test-support` invoke the same
constructor and produce one schema-valid handle with identical derived
defaults. Behavioral tests keep their domain claims but delete their copied
handle maps.

**Owner namespace.** `seon.cluster`, with fixture access in
`seon.test-support`.

## Wave 2 — named events instead of sampling

**Design change — DESIGN REVIEW.** Retain or tap the application report
channel in armed-agent and render handles. Procs publish named `::armed`,
`::episode-settled`, `::paused`, `::resumed`, render-watch-ready, and
stream-state reports. Work producers also publish entered and settled events
so overlap is proved with a barrier. Tests consume these reports through
`await-event!`; committed outcomes use Datahike listeners. A clock remains only
the loud backstop.

This is one event-publication class. Replacing `Thread/sleep` with a faster
poll, or putting a busy future inside `await-event!`, does not fix it.

**Affected tests — 15 unique forms.**

- Agent graph waits — 10:
  `n-agent-parallel-turns-property`, `park-wake-test`,
  `pause-during-in-flight-call-test`, `hot-reload-var-test`,
  `restamp-recovery-test`,
  `arming-does-not-route-a-triggerless-historical-red`,
  `unheld-resume-regression`, `custody-mismatch-regression`,
  `wake-routing-conservation-property`, and
  `wait-closes-in-terminal-tx-test`.
- Render graph waits — three:
  `slow-tab-newest-complete-page-test`,
  `a-terminal-fact-supersedes-a-partial-after-the-lost-clear-ordering`, and
  `reconnect-mid-stream-is-a-fact-only-repaint`.
- Turn/render composition — `streaming-writes-zero-datoms-test`.
- Missing overlap oracle — `concurrent-streams-share-one-conn-test`.

`n-agent-parallel-turns-property` is already in the first group and is the
second missing-overlap proof. It commits triggers serially, then checks terminal
counts. `concurrent-streams-share-one-conn-test` serially drives passes and
uses an elapsed-time ceiling. Neither proves that two producers were entered
before either was released.

The issue
[[a-slow-tab-proof-can-count-a-late-initial-derivation]] is exactly this class:
wire reads prove delivery but do not prove the initial render transition has
settled before the test snapshots the derivation counter.

**One replacement regression.**
`graph-events-preserve-lifecycle-and-overlap-property`: drive the real agent
and render graph definitions through generated arm, work, pause, stream, and
settle transitions; consume their named reports; for the concurrency
partition, hold two entered producers on one barrier before releasing either;
then assert the durable terminal facts.

**Owner namespaces.** `seon.cluster.agent`, `seon.render.web`, and the common
report vocabulary in `seon.flow`.

## Wave 3 — one generated entity-reference walk

**Design change — DESIGN REVIEW.** Put a schema-derived entity graph generator
beside `seon.render.walk`. Its structured oracle compares reachable identities,
hop distance, viewer-stable projection choice, fallback, cycles, caps, exact
database value, no writes, and deterministic output. Prompt prose and HTML are
presentation consumers of that result, not graph oracles.

**Affected tests — 20.**

- `seon.render.block-test` — 10:
  `a-rendered-unit-embeds-its-refs-as-units`,
  `an-entity-with-no-renderer-still-renders`,
  `a-dangling-ref-is-a-note-and-not-a-dead-page`,
  `ref-following-without-a-database-refuses-in-place`,
  `distance-is-the-hops-a-render-may-spend`,
  `an-absent-distance-is-byte-identical-to-before-the-accretion`,
  `distance-never-overrides-the-admission-caps`,
  `a-slot-may-steer-its-hop-to-another-projection`,
  `a-redirected-hop-needs-no-declaration-on-the-neighbor`, and
  `a-ref-cycle-is-refused-at-the-hole-that-closes-it`.
- `seon.context-pilot-test` — 10:
  `distance-zero-follows-nothing`,
  `distance-two-deepens-rather-than-repeats`,
  `the-implied-distance-is-one`,
  `each-neighbour-is-rendered-by-its-family`,
  `a-family-with-no-lens-still-renders-through-the-floor`,
  `the-viewers-override-wins-over-the-family-and-holds-for-the-walk`,
  `the-view-does-not-walk-into-itself`,
  `one-neighbour-is-rendered-once`,
  `deriving-a-neighbourhood-commits-nothing`, and
  `two-derivations-of-one-database-value-are-the-same-value`.

Keep one prompt-composition example and one web-page/family-lens composition
example. Those prove presentation boundaries and are not substitutes for the
graph property.

**One replacement regression.**
`generated-entity-graphs-preserve-walk-contract`, seeded and recurring through
`bin/test`, with one database per trial and a structured result oracle.

**Owner namespace.** `seon.render.walk`.

## Wave 4 — delete test-owned runtimes

**Design change — DESIGN REVIEW.** Delete the fake planner/namespace-owner
surface and its test-owned graph. Generate-code composition must arm actual
per-agent graphs, commit ordinary messages, consume the real reports from Wave
2, and query durable facts. The provider may be scripted at the external edge;
the test may not serially schedule agents or implement another loop.

**Affected tests — seven.**

- `seon.flow.loop-test` — all four:
  `seeded-lineages-terminate-within-fact-budgets`,
  `seeded-namespace-owner-procs-return-reproducible-outcomes`,
  `asymmetric-owner-escalation-replans-at-current-basis`, and
  `admission-rejection-is-a-value-and-records-the-choice-point`.
- `seon.gen.loop-test` — all three:
  `a-goal-is-a-message-and-the-attempt-routes-its-own-failures`,
  `a-result-built-on-a-failed-form-is-red-and-routes`, and
  `a-silent-owner-leaves-the-plan-unsettled-forever`.

The current `seon.gen.loop-test/drive!` serially scans agents and invokes
`seon.cluster.loop/turn`. That is a second scheduler even though it calls real
turn code. The four `seon.flow.loop-test` forms additionally preserve more than
700 lines of prototype machinery.

**One replacement regression.**
`generated-program-attempt-runs-on-real-agent-graphs`: one end-to-end generated
composition proves message → attempt → red form → owner assignment → owner
answer/decline → structured settlement over the actual graphs.

**Owner namespaces.** `seon.cluster.agent` and `seon.gen.loop`. The obsolete
prototype owner in `seon.flow` is deleted, not moved.

## Wave 5 — structured status, prose only at the edge

**Design change — DESIGN REVIEW before assertion deletion.** Status-producing
functions retain named state, evidence identity, route, and presentation key
until the final renderer. Tests assert the structure. Keep one nonblank prose
smoke and exact bytes only where HTML, Datastar, SSE, CLI, or another public
crossing actually owns the bytes.

**Four candidates from the awkwardness audit.**

- `seon.oversight-test/a-booted-cluster-tells-its-live-fleet-story`;
- `seon.render.agent-test/selection-survives-a-morph-test`;
- `seon.render.agent-test/an-agent-with-nothing-to-say-renders-an-empty-transcript-test`;
- `seon.cluster.armed-test/an-escaped-throwable-becomes-a-fact-and-a-message`.

The current re-read makes the owner review important:

- `selection-survives-a-morph-test` also asserts Datastar signal bytes, which
  are a real browser crossing and must remain exact;
- the armed fault test checks that the message contains the durable error
  identity, which may be the correct evidence join rather than prose pinning;
  and
- exact DOM ids, attributes, SSE framing, and user-authored message content
  were excluded from this class.

Therefore “four candidates” is the honest count. Do not mechanically delete
four tests from a line-number report.

**One replacement regression.**
`status-projections-preserve-structured-evidence`: generate representative
idle, active, empty, and fault states; assert the named structured projection
and evidence refs; render one sample only to prove nonblank presentation.

**Owner namespaces.** `seon.oversight`, `seon.render.agent`, and
`seon.cluster.agent`/fault rendering.

## Wave 6 — lexical ownership for every test resource

**Design change.** Add one scoped resource fixture in `seon.test-support` that
registers cleanup immediately after each successful acquisition and runs every
cleanup independently. Multi-resource tests acquire through it. A later
acquisition failure cannot skip release of an earlier connection, cluster,
Flow graph, server, executor, listener, or latch release.

This is test-support design, not a production-architecture ruling. It still
launches as one class because fixing only the currently failing cleanup leaves
the same failure shape at the next multi-resource fixture.

**Affected current tests and helper.**

- `seon.cluster.boot-test/two-instances-are-isolated`: if starting `b` throws
  in the `let` binding, `a` was acquired before the inner `try` exists.
- `seon.flow-test/flow-monitor-attaches-and-publishes-the-render-graph`: graph,
  fanout, injected wedge, and waits occur before the cleanup `try`; a setup
  failure can leak the wedged proc and executor.
- dead helper `seon.cluster.agent-test/fresh-connection`: creates and connects
  a database, returns the connection unreleased, and has no caller. Both recent
  Gemini reviews named it. Delete it; do not add cleanup to dead code.

The fresh `d/connect` sweep found no second actionable unbalanced test
connection. `seon.flow.kill-child` and `seon.cluster.store-child` deliberately
die while holding resources because SIGKILL recovery is the test subject.

**One replacement regression.**
`scoped-resources-release-prior-acquisitions-on-every-failure-index`: generate
an acquisition count and injected failure ordinal, then assert that every
successfully acquired probe resource closed exactly once and every later one
was never opened.

**Owner namespace.** `seon.test-support`.

## Wave 7 — atomic ephemeral-port ownership

**Design change — DESIGN REVIEW.** Change the Flow Monitor server boundary to
accept operating-system selection (`:port 0`) and publish the actual bound port
in its returned state. Tests connect to that published port. Never
open-reserve-close-rebind.

**Affected tests — two.**

- `seon.flow-test/core-fault-fanout-commits-and-copies-without-competition`;
- `seon.flow-test/flow-monitor-attaches-and-publishes-the-render-graph`.

Both call `free-port`; the open issue currently describes the shared helper but
understates its fanout as one test.

**One replacement regression.**
`flow-monitor-binds-and-publishes-an-os-selected-port`: start on port zero while
other threads allocate ephemeral ports, assert the published port is positive,
and connect through it repeatedly.

**Owner.** `reference-code/core.async.flow-monitor` server API, consumed by
`seon.flow-test`. If the dependency already has a lower-level bound-port
handle, expose that value rather than adding a Seon port allocator.

## Wave 8 — honest schema-derived generator domains

**Design change — DESIGN REVIEW.** Make the registered schema the only
constructible domain for generated calls. Function-valued boundaries use
truthful `:=>` schemas or one computed runtime-edge exclusion. Optional values
are absent, never generated as `nil`; owner-named partitions extend the schema
generator without bypassing validation.

**Current affected test — one, plus a thirteen-callback coverage hole.**

- `seon.cluster.message-test/generated-message-histories-preserve-identity-fanout-and-depth`
  hand-generates `nil` for `chain-limit`. Its pure oracle then evaluates
  `(not (pos-int? chain-limit))` and predicts `:no-limit`, while the execution
  path treats `nil` as unlimited and explicitly bypasses schema validation.
  The model and real call are not asking the same valid question.
- Thirteen `seon.flow` callback declarations remain bare `fn?` and have no
  constructible function generator:
  `work-fn`, `deliver!`, `read-facts`, `step-fn`, `stopped!`,
  `commit-fault!`, `commit-drop!`, `read-core-error-mode`, `panic!`,
  `plan-step-fn`, `fix-step-fn`, `read-sources`, and
  `compile-namespace-fn`.

**One replacement regression.**
`registered-function-contracts-generate-and-validate`: traverse registered
function-valued boundary schemas, generate functions/requests at fixed seeds,
validate every generated value against the exact schema, and run the
owner-named message optionality partitions with `max-chain` absent.

**Owner namespaces.** `seon.flow` contracts and schema admission;
`seon.cluster.message` for the request schema and behavioral model.

## Uncovered production-contract classes

These are not low-priority merely because no existing test is deleted. Each is
an exposed production-design question and requires the owner before a fix
lane chooses a contract.

### Released connections cross live-connection contracts

**DESIGN REVIEW.** Whole-suite instrumentation observed eight `stop!`, nine
`store/transact!`, and two `release-store!` calls crossing contracts that
require a live connection after release or during shutdown. `stop!` itself was
widened for idempotence, but inner calls remain. `transact!` genuinely needs a
live connection, so its violations expose the stop-during-turn race, not a
schema typo.

**Tests dissolved.** None. This is missing integration coverage.

**One regression.** Run the complete discovered suite once with
`seon.instrument/apply!` in panic mode and require zero contract violations,
plus one injected stop-during-turn case proving the chosen shutdown rule.

**Owner namespaces.** `seon.cluster`, `seon.cluster.loop`,
`seon.cluster.store`, and `seon.instrument`.

### Agent turns bypass the bounded compute owner

**DESIGN REVIEW — blocker from the tree audit.** The real agent turn proc is
tagged `:io` and invokes SCI evaluation inline. Existing Flow launcher tests
exercise an isolated launcher, boot checks only executor identity, and agent
tests accept the real graph's `:io` tag. The suite is green across a production
seam it never composes.

**Tests dissolved.** None. Existing isolated launcher examples may later die
when the surviving composition owns their invariant, but that decision belongs
to the compute-owner fix.

**One regression.** A real agent turn must enter the bounded compute executor;
with parallelism `N`, an `N+1` barrier proof observes exactly `N` entered evals,
then all terminal facts after release.

**Owner namespaces.** `seon.cluster.agent`, `seon.cluster`, and `seon.flow`.

### Teardown failure is not addressable after instance removal

**DESIGN REVIEW.** The released-connection issue composes with the older
cleanup finding: production `stop!` may remove an instance from its registry
even when an earlier cleanup action failed, leaving later cleanup unaddressable.
This is production lifecycle, not merely Wave 6's fixture scoping.

**Tests dissolved.** None.

**One regression.** Inject failure at every stop action; assert all independent
cleanup actions ran and that any still-live resource remains addressable for a
retry, with the chosen fail-closed state represented explicitly.

**Owner namespace.** `seon.cluster`.

### Source inventory can make a generator unconstructible

The Gemini flag on
`seon.sci.reader-test/source-round-trips-and-spans-partition-the-tree` remains:
`gen/choose 0 (dec (count files))` is invalid when the recurring runner
discovers no source files.

This is a test-contract defect, not a production design ruling. The source
inventory precondition and the rotation generator are currently fused.

**Tests dissolved.** None; one test is repaired.

**One regression.** Assert the recurring source inventory is nonempty before
constructing `gen/elements`/rotation data, then run the existing round-trip
property.

**Owner namespace.** `seon.sci.reader-test`.

## Small harness classes

These are class fixes, but they do not justify interrupting the four design
waves above.

### Terminal-aware pREPL reads

`seon.cluster.boot-test/prepl-eval` loops forever when `.readLine` returns
`nil`. Four tests use the helper:

- `repl-first-and-under-the-ten-second-bound`;
- `two-instances-are-isolated`;
- `a-delayed-stop-never-kills-a-replacement`; and
- `a-failed-tower-never-takes-the-repl`.

Change the helper once so a value, EOF, malformed frame, and socket failure are
distinct terminal outcomes. Add one EOF regression; do not add four timeouts.
Owner: `seon.cluster.boot-test`, or `seon.test-support` if another suite needs
the protocol reader.

### Observed dead process, never a magic pid

`stale-advertisements-read-as-absent` writes pid `2` as “dead”; pid 2 can be
live. Spawn a trivial child, record its real `(pid, start-instant)`, await its
exit, then write that observed dead identity. One regression remains:
`stale-advertisements-read-as-absent`. Owner: `seon.cluster.boot-test`.

### Finish the canonical-support migration

The awkwardness audit counted 23 actionable duplicate construction sites:

- six recursive-delete helpers;
- four local file-store probe schemas;
- three property-result `check!` wrappers;
- three error classifiers;
- three refusal unwrappers; and
- four suites restating default result caps.

This is site count, not test count; inflating it by helper fanout would
misrepresent the class. Delete helper bodies in favor of `seon.test-support`,
registered error schemas, and production config/result-cap owners. Narrow
adversarial caps and store schemas remain when they are the subject.

The one recurring regression is the existing `seon.test-support-test` contract
for each canonical helper. This tail is mechanical only after Waves 1 and 6
settle what production construction and scoped ownership actually are.

## Overclaimed proof names are assigned to their owning waves

The awkwardness audit named five tests whose names claim more than their bodies
observe. Two are the overlap members already assigned to Wave 2. The remaining
three do not form one implementation class and therefore must not receive a
single miscellaneous “rename tests” lane:

| Test | Current mismatch | Owning wave |
|---|---|---|
| `seon.cluster.agent-test/restamp-recovery-test` | manually calls recovery and arm transitions | Wave 1 construction plus Wave 2 reports; invoke the real recovery owner or rename the remaining transition proof |
| `seon.cluster.loop-test/a-boot-built-database-takes-every-row-the-turn-writes` | constructs its own in-memory database | Wave 1; use the production population/construction boundary |
| `seon.sci.reader-test/standing-no-second-reader-surface-is-pending-until-s5` | passes because known debt remains present | Delete with the N5 one-reader landing; retain a structural no-second-reader property only if it observes absence |

This mapping prevents a cosmetic naming lane from hiding three different
design dependencies.

## Sweep calibration and explicit exclusions

The raw smell search found more clocks and strings than the actionable classes.
They remain for reasons grounded in the boundary:

- `Thread/sleep` in killed child processes intentionally holds the resource
  until SIGKILL.
- The 10 ms loops in cross-process store/Flow tests wait for a real child/file
  event; the clock is the foreign-process backstop.
- The work-duration sleep in `seon.flow-test` creates the workload whose
  bounded capacity is under test.
- `async/poll!` is valid when asserting immediate absence; it is not a wait.
- Exact DOM ids, Datastar attributes, SSE framing, HTTP status, EDN crossings,
  CLI output, and user-authored message content remain exact contracts.
- Prompt presence/absence assertions are not automatically prose pinning. A
  semantic fact that must reach the model is a valid context assertion until a
  structured intermediate projection can observe the same contract.
- Exact datom counts remain valid for zero writes, one atomic terminal
  transaction, idempotency, and duplicate-fact prevention.
- Narrow schemas remain valid when schema/store mechanics are the subject.

The current direct-connection sweep found the one dead unreleased helper
already assigned to Wave 6. It did not find a general connection leak across
active `test/` fixtures.

## Dispatch boundary

The rank table is a triage priority inside the already-scheduled test-smell
program unit; it is not a second program roadmap. The class dispatch is:

- owner rules the design interface before Waves 1, 2, 3, 4, 5, 7, or 8;
- one implementation lane owns one class and its one replacement regression;
- no lane receives an individual failing-test list as its unit;
- Wave 6 and the small harness classes may proceed mechanically where they do
  not overlap a design owner; and
- the complete `bin/test` gate plus the relevant live reset/graph proof closes
  the integrated wave, not a collection of focused green tests.

The earliest dependency-ready test-smell implementation remains Wave 1:
production construction must become invokable before fixture consolidation can
stop copying it. Wave 2 then gives the real-graph conversions and the entity
property an honest event surface. The final graduation gate remains the
program's real-agent/load/restart proof, not this suite cleanup.

---
type: research
status: active
tags: [prd, research]
---

# Fresh test-tree design review

## Review boundary and method

This is the report-only design review requested for the overnight fresh-tree
wave. I edited no source, test, benchmark, configuration, or issue file.

The tree was moving while it was reviewed. The final inventory pass saw:

- 42 files under `test/`;
- 33 `*_test.clj[c]` namespaces discovered by `bin/test`;
- 334 `deftest` or `defspec` forms; and
- six files under `bench/`.

That is 28 tests more than the approximate 306 in the brief because
`test/my/message_test.clj` and `test/seon/cluster/message_test.clj` arrived
during the review. I paused at each final recount, read the new files, and
re-ran the complete filename, test-form, timing, fixture, helper, generator,
and exact-text inventories. Findings below therefore describe the latest
visible tree, not the earlier count.

I read every test and benchmark file, then cross-checked:

- `bin/test` discovery against every test namespace;
- every `deftest`, `defspec`, `quick-check`, and generator;
- every sleep, deadline, timeout, poll, latch, future, wall-clock read, and
  random input;
- every database fixture and schema installation;
- every private helper definition, grouped by name and behavior; and
- exact strings, rendered prose, serialized bytes, exception messages, and
  tests which claim a property without observing it.

The design standard is the constitution in
`testing-story-2026-07-27.md`: one construction at one choke point, one
regression per failure class, generated edges from schemas, event-driven waits,
and recurring runner ownership for every proof.

## Executive verdict

The fresh suite has several excellent class-killers already: run custody's
state-machine property, value admission's total projection, error
normalization's totality property, canonical attribute derivation in
`turn_test`, absence generation in `problems_test`, and channel-derived wake
classification.

The largest remaining leverage is not another test. It is four constructions:

1. a common event-observation boundary for asynchronous tests;
2. one canonical database fixture derived from
   `canonical-database-attributes`;
3. state-machine properties for the model-attempt/turn and block-graph
   mechanisms; and
4. generated schema-AST coverage at the schema bridge and admission gates.

Those four remove most of the present point-test pressure. The recommended
second phase is nine implementable units, in the order in
[[#Recommended implementation order]].

One test is presently a false proof:
`test/seon/sci/eval_test.clj:246-252` sleeps, cancels a future, then executes
`(is true)` while claiming the diagnostic shape is asserted elsewhere. It
proves no diagnostic. Delete that assertion immediately; either expose the
completion/blocked diagnostic as an observable event and assert it, or keep
only the honest ceiling test at lines 226-243.

## Findings ranked by leverage

### 1. Observable-state polling has no single event owner

**Failure class killed:** races, tuned sleeps, slow CI flakes, and false
equalities against a producer which is still producing.

**Evidence:**

- `test/seon/flow_test.clj:56-69` implements `await-condition!` as a 5 ms
  polling loop over process-local observable state. It is used throughout the
  suite for channel counts, active evals, proc counts, database facts, monitor
  messages, and flow status.
- `test/seon/cluster/armed_test.clj:49-60` polls a database value up to 100
  times with 50 ms sleeps even though Datahike publishes transaction events.
- `test/seon/cluster/armed_test.clj:137-143` sleeps 200 ms to infer that an
  empty wake did no work.
- `test/seon/flow_test.clj:782-789` reaches `:paused`, then sleeps 30 ms to
  infer no more work completed.
- `test/seon/cluster/wake_test.clj:110-118`, `:143`, `:174`, and `:200` use
  300-2000 ms channel timers even when `d/transact` or `unlisten!` has already
  synchronously crossed the listener boundary.
- `test/seon/cluster/wake_test.clj:138-143` uses a five-second elapsed-time
  assertion to prove the listener is nonblocking.
- Several `await-condition!` predicates use equality against counts updated by
  running procs, notably `flow_test.clj:402-405`, `:435-438`, `:484-489`,
  `:519-523`, and `:782-790`. Equality is safe only after a terminal event;
  while the producer is live, an upper or lower bound is monotone-safe and
  equality is a race.

**Dissolution design:** each long-lived owner publishes a completion/report
event for the state transition its tests need to observe. Tests wait on the
existing report channel, Datahike `listen!`, a `CountDownLatch` installed at
the exercised callback, or the `Future` returned by the operation. A clock is
only a loud backstop around that event. Add one test-support operation,
`await-event!`, which takes the actual event source and a diagnostic label; do
not add a generic polling API. Negative wake assertions should transact, then
`poll!` after the synchronous listener has returned. Pause should publish
paused readiness; do not infer it by sleeping after `ping`.

When the production interface hides readiness, change that interface to
return or publish readiness. The test should not reconstruct lifecycle state
by repeatedly sampling it.

**Tests which collapse:** the repeated wait mechanics in all 13
`flow_test` tests, all four `armed_test` tests, and four of six `wake_test`
tests collapse onto one event observation construction. The behavioral tests
remain, but their local timers and polling helpers disappear. The duplicate
`event-backstop-seconds` in `flow_test.clj:17` and
`flow/loop_test.clj:14` becomes one support constant used only as a backstop.

**Size:** medium, two owners: test support plus the few production lifecycle
interfaces which currently hide readiness.

**Legitimate clock cases to retain:** foreign HTTP deadlines in `ai_test`;
socket timeout in `boot_test/prepl-eval`; child-JVM ready-file and process-exit
backstops in `store_test` and `flow_test`; the explicit owner-ruled ten-second
boot performance assertion. `Thread/sleep Long/MAX_VALUE` in the two child
process fixtures is payload for a real SIGKILL test, not a wait.

The ten-second boot rule is asserted three times
(`boot_test.clj:152-168`, `:264-279`, and `armed_test.clj:148-157`). Keep the
full start-to-REPL/tower assertion in `boot_test`; the armed-layer duplicate
adds no failure class.

### 2. Runtime fixtures still hand-install schema subsets

**Failure class killed:** fixture-green/live-boot-red failures whenever a
runtime owner adds, removes, or changes a database attribute.

**Evidence:** these runtime-facing fixtures install explicit attribute lists:

- `cluster/run_test.clj:29-61`;
- `cluster/prompt_test.clj:18-44`;
- `cluster/loop_test.clj:227-252` (its second, crash-walk fixture);
- `cluster/wake_test.clj:28-41`;
- `cluster/work_test.clj:33-78`;
- `render/block_test.clj:101-116` and its later ref fixture at `:680-708`;
- `config_test.clj:89-138`; and
- `reconcile_test.clj:39-82`.

`turn_test.clj:31-74`, `error_test.clj:470-480`, and
`problems_test.clj:17-51` demonstrate the correct construction:
install `schema/canonical-database-attributes`, the same derivation boot uses.
`schema/admission_test.clj:31-32` correctly starts from canonical attributes
and adds its deliberately synthetic temporal attributes.

`cluster/loop_test.clj:103-129` is a useful composition check, but it does not
make the later hand-listed crash fixture honest. A new loop-written attribute
can pass the first check and still be absent from the fixture which exercises
the loop.

**Dissolution design:** one `seon.test-support/with-database` installs
`canonical-database-attributes`, creates a fresh memory database per call, and
accepts only explicit extra synthetic schema rows. Runtime tests use it.
Schema-bridge tests may deliberately install one narrow attribute because the
installation itself is their subject. File-store boundary tests may keep
their isolated `probe-schema`; they test Datahike/Konserve store mechanics,
not live schema composition.

The canonical fixture must derive at call time, not cache an attribute vector.
It is a fixture, not a second schema registry.

**Tests which collapse:** the eight fixture definitions above collapse to one
support owner. `cluster/loop_test.clj:35-46` and `:103-129` can collapse to one
composition regression: every attribute emitted by the loop is in the
canonical set, and an actual canonical database accepts a representative
turn transaction.

**Intentional narrow fixtures to retain:** `schema/datahike_test`,
`cluster/store_transact_test`, and the `probe-schema` file-store suites in
`cluster/{store,ancestor,registry,export}_test`. Their subject is schema/store
mechanics, so widening them to all production attributes would hide their
dependency boundary.

**Size:** small, high payoff.

### 3. Model-call and turn recovery are a scenario matrix without a model

**Failure class killed:** a new error phase, backup combination, retry limit,
or crash position taking an unreviewed branch while all named examples remain
green.

**Evidence:**

- `ai_test.clj:278-386` has seven point tests over disposition evidence.
- `cluster/turn_test.clj:501-773` has nine scenario/composition tests over
  success, unpaid failure, ambiguously paid failure, backup, backoff, terminal
  failure, and message delivery; `:838` adds the refusal-to-error-fact seam.
- The same failure constructor is duplicated in `ai_test.clj:267-275` and
  `turn_test.clj:424-446`.
- The exact-attempt assertions (`exactly one`, `exactly two`) are correct only
  after the turn is terminal. They are individually useful examples but do
  not cover the transition space.

`ai/disposition` is already the correct choke point: it computes from evidence,
not a list of error kinds. What is missing is a generated transition model over
that choke point and the durable turn facts.

**Dissolution design:** generate a sequence from the registered schemas:
primary outcome, optional backup, transmission/response/output evidence,
provider class, retry strategy, and disposition. A pure oracle derives the
allowed next action from cost evidence:

- output or possibly transmitted means terminal failure;
- provably free plus backup means immediate failover;
- provably free and transient without backup means bounded backoff; and
- non-transient without backup means terminal failure.

Drive the real turn boundary with a recording completer, then independently
query attempt facts, run closure, plan presence, and total scheduled delay.
Assert invariants after every attempt, not only the final return.

**Tests which collapse:** `ai_test`'s
`output-evidence-is-always-terminal` through
`backoff-happens-only-where-repeating-can-help` collapse to one disposition
property plus one teaching example for “possibly paid never repeats.”
`turn_test`'s eight retry/failover tests from `one-successful-call...` through
`an-unreadable-reply...` collapse to one state-machine property plus two
end-to-end examples: primary success and unpaid primary → backup success.
The real JDK phase classification test remains as the one leaf falsifier.
The two new message tests remain as separate cross-owner composition
regressions: requested delivery commits with its receipt, and a delivery
refusal reaches the durable error owner.

**Size:** medium-large. This is the highest-leverage point-test dissolution.

### 4. Block rendering needs one generated graph property

**Failure class killed:** a newly composed slot, cycle, missing block,
projection error, specialist rule, or budget interaction escaping the
individually named examples.

**Evidence:** `render/block_test.clj` has 38 tests. Four are properties, but
the mechanism's core graph behavior remains a collection of examples:

- selection and evaluation: lines 195-329;
- slot expansion and error placement: lines 335-380;
- specialist selection: lines 413-482; and
- install and budget behavior: lines 533-636; and
- database-ref expansion, dangling refs, absent databases, and entity cycles:
  lines 728-803.

The suite already contains the right design precedents: one evaluation per
block, `surfaces` as the single projection choke point, and admission caps
reused by `expand`. The remaining missing construction is a generated block
graph whose oracle is independent of `expand`.

**Dissolution design:** generate an acyclic/cyclic graph of block identities,
slot edges, declared projection kinds, priorities, projection outcomes, and
the four admission caps. A pure graph oracle computes reachable nodes,
top-level unclaimed nodes, cycle-closing holes, and monotone budget bounds.
Run `surfaces`, then assert:

- each reachable block evaluates at most once;
- output order is deterministic;
- every hole is filled or replaced by one local error card;
- failure of one projection cannot remove a sibling;
- nodes and depth never exceed the shared caps; and
- the exact database value is carried through every unit.

Keep specialist selection as a separate generated classification property;
its input is value attributes plus ordered rules, not a block graph.

**Tests which collapse:** the 12 tests from
`blocks-are-ordered...` through `a-failed-block-puts-its-error...`, plus the
four expansion-budget tests at lines 588-631, collapse to one graph property
and two teaching examples (one layout, one cycle). The four specialist point
tests at lines 413-449 collapse to the existing selection property once its
oracle covers first-match, broken-rule isolation, and default selection.
The five entity-ref examples at lines 728-803 join the same generated graph
property by adding database entities and ref edges; retain the error → run →
forms page as the one composition example.

**Size:** medium.

### 5. Message delivery has a class-killer simulation but no generated model

**Failure class killed:** a recipient, fan-out, derived identity, trigger
history, or chain-limit combination escaping the named examples and allowing
duplicate or unbounded delivery.

**Evidence:** the concurrently added
`cluster/message_test.clj` correctly uses canonical database attributes and
ends with a real two-agent ping-pong simulation. Its 12 tests are nevertheless
all examples:

- delivery/wake composition at lines 86-109;
- sender, fan-out, recipient refusal, and missing-bound cases at lines 112-203;
- chain depth and trigger derivation at lines 209-274; and
- the bounded ping-pong simulation at lines 279-342.

The simulation uses 200 as its own emergency stop. That is acceptable as a
failure sentinel, but the input space is fixed to two agents, one message per
hop, and one chain limit. The construction deserves a pure model rather than
more examples as messaging accretes.

**Dissolution design:** generate a small agent population, human heads,
agent-sent vectors, known/unknown recipients, run/form identities, and a
positive/absent chain limit from the registered schemas. A pure model derives
message ids, sender/to connections, trigger ancestry, per-recipient rows, and
the maximum allowed depth. Drive `message/delivery`, commit its rows and
transaction trigger, then query facts independently after every command.
Assert that ids are deterministic, each valid recipient gets exactly one row,
unknown recipients cost only their own row, a human head resets depth, and no
generated history can exceed the limit.

The existing ping-pong simulation remains the one teaching regression for the
infinite-conversation class. Its equality is safe because it is asserted after
the refusal terminal event.

**Tests which collapse:** the chain-depth examples at lines 209-274 and the
fan-out/refusal examples at lines 144-203 collapse into one state-machine
property. Retain delivery-intersects-wake as the separate cross-owner
composition test and retain one sender/provenance example.

**Size:** medium; it can reuse the command/model harness built for the AI/turn
unit.

### 6. Schema-shape tests enumerate AST spellings instead of generating them

**Failure class killed:** a new legal Malli wrapper or nesting combination
mapping incorrectly while all remembered spellings pass.

**Evidence:**

- `schema/datahike_test.clj:32-74` names four AST shapes: mixed union, alias
  chain, `:and`-wrapped secondary attribute, and direct versus wrapped guard.
- `schema/edn_test.clj:77-123` enumerates admitted/refused population examples,
  including generator metadata, but has no property.
- `config_test` manually duplicates the manifest's dial inventory in
  `expected-dial-attributes` and again in `model-attributes`.

The bridge and EDN gate are choke points. Point tests here are especially
dangerous because every later generator and database fixture trusts them.

**Dissolution design:** define a bounded generator for the supported Malli
schema AST from the admission schema itself: aliases, `:and`, collection
cardinality, refs/components, identity, storage facets, and predicate
generators. Generate semantically equivalent direct and wrapped forms and
assert identical Datahike declarations. Independently assert that every
generated database attribute either maps to exactly one valid declaration or
is refused with one admission rule. For schema populations, generate valid
registries and one mutation (duplicate key, unresolved ref, illegal generator,
malformed declaration); assert the one gate admits or names that mutation.

**Tests which collapse:** the first four `schema/datahike_test` tests collapse
to one equivalence/totality property; retain the real Datahike round trip.
`schema/edn_test`'s `the-gate-admits-and-refuses-populations` becomes the
generated population property; retain directory merge, duplicate-file
provenance, unreadable-file provenance, and `register!` composition as distinct
I/O classes.

In `config_test`, derive dial membership from the registered manifest/effective
schemas and compare the shipped defaults to required/optional semantics. Do
not keep a third handwritten set which can agree with a handwritten fixture
while production drifts.

**Size:** medium. Implement after the canonical fixture so its generators can
reuse the same schema authority.

### 7. Admission and eval re-test the same runaway/value classes

**Failure class killed:** fixes or regressions being assigned to the wrong
owner, with two suites disagreeing about which layer owns the behavior.

**Evidence:**

- `sci/admit_test.clj` owns cyclic/opaque values, pending references,
  infinite lazy realization, caps, diagnostics, and result projection.
- `sci/eval_test.clj` repeats admitted value, infinite lazy sequence,
  failure marker, and “nothing throws” cases.
- Both define the same caps map (`admit_test.clj:57-61`,
  `eval_test.clj:22-26`).
- `eval_test.clj:246-252` contains the false `(is true)` proof described
  above.

**Dissolution design:** `admit` owns the total value codec and all hostile
value generation. `evaluate` owns exactly the composition contract:
fork → armed interrupt → evaluate → admit before disarm → flat value. Give
`evaluate` one generated source property and one standing spin interrupt
regression. Reuse production-derived caps from the request/default config;
do not copy the map into either suite.

**Tests which collapse:** in `eval_test`, `a-value-comes-back-admitted`,
`failure-evidence-has-stable-object-markers`,
`an-infinite-lazy-sequence-dies-inside-the-boundary`, and most generated
ordinary-value cases collapse into the one composition property. Keep the
interpreted infinite loop, uncatchable interrupt, fork isolation, disposition
availability, blocking-host-call ceiling, and real-marker recognition because
they are eval-specific classes.

In `admit_test`, `a-cyclic-value...`, `a-pending-reference...`,
`nothing-lazy-survives...`, caps, hostile projection, and SCI type naming are
valuable named seeds for the totality generator. Once the generator guarantees
those partitions and prints coverage, keep only cyclic graph, infinite native
producer, and panic/record as the three distinct regressions.

**Size:** small-medium.

### 8. Error and problems projections retain prose-shaped assertions

**Failure class killed:** harmless copy edits breaking tests, and meaningful
structured omissions passing because a substring happened to remain.

**Evidence:**

- `error_test.clj:384-404`, `:431-465`, and nearby tests inspect prose/log
  strings even though kind, reason, attribution, routes, and evidence are the
  behavior.
- `problems_test.clj:279-334` checks composed log/HTML projections after the
  family property has already established the derived value.
- `render/block_test.clj:484-508` and `:603-628` search rendered HTML for
  words such as “elided”, “caps”, and configured depth.
- `prompt_test` correctly uses presence/absence of the triggering content and
  warning concept rather than exact wording. That is the model.
- Exact bytes in `render/hiccup_test` are **not** this defect. HTML
  serialization, escaping, void tags, attribute ordering, and shorthand
  precedence are wire contracts; exact output is appropriate there.

**Dissolution design:** render errors and problem entries from a structured
projection carrying kind, evidence reference, route, and presentation key.
Tests assert the structure and schema. Keep one smoke assertion that the
human projection is nonblank and one that the log projection is one line.
For block expansion, error cards should carry machine-readable error data
beside hiccup; assert the error rule and affected block, not English words.

**Tests which collapse:** the five error prose/log tests collapse to one
projection-schema property and one log-line regression. The problems log and
HTML composition tests collapse to one twin-consistency property over the
already generated family value. The block substring assertions join the block
graph property's local error-card invariant.

**Size:** medium because it may require a small production projection-shape
change.

### 9. Test scaffolding has become a parallel implementation

**Failure class killed:** fixture behavior drifting independently across
namespaces and fixes being applied to only one copy.

**Exact duplicate inventory and owner:**

| duplicated behavior | copies | one owner |
|---|---:|---|
| recursive project-local deletion | six: ancestor, boot, export, registry, store, schema admission | `seon.test-support/delete-recursively!` |
| deepest non-empty exception data | three direct copies: run, config, reconcile; plus local variants in schema EDN and store transact | production `seon.error/refusal` for application semantics; `seon.test-support/refusal-data` only for asserting thrown transaction boundaries |
| schema resource EDN reader/selected declaration merge | config and reconcile | production schema resource loader, invoked with an explicit resource set; no test registry |
| fresh canonical in-memory database lifecycle | at least eleven semantic copies | `seon.test-support/with-database` |
| file-store probe schema plus marker query | ancestor, export, registry, store | a file-store test support owner parameterized by marker attribute; keep domain setup local |
| event backstop and channel/latch wait | flow and flow/loop, with more bespoke waits in armed/wake | `seon.test-support/await-event!` |
| result caps | error, problems, render/block, sci/admit, sci/eval | derive from the production effective config/request; never a support constant |
| AI failure value | ai and cluster/turn | a generated value from the registered error/evidence schemas; local literal only for a teaching example |
| error-value predicate | ai and cluster/reply | `seon.error/value?` or schema validation, not a test helper |
| property result assertion (`check!`) | render/block and render/hiccup | `seon.test-support/assert-check!`, printing seed, size, value and shrink |

Generic names such as `request`, `with-database`, `now`, `process`,
`attributes`, and `check!` were not counted as duplicates merely because their
names match. They are included above only where their bodies and responsibility
match.

`refusal-data` must not become a second error classifier. The application
already owns refusal classification in `seon.error/refusal` and
`seon.cluster.store/transact`. Test support may unwrap a thrown boundary so a
test can assert it, but it must return unknown for unclassifiable data and
must never match messages.

**Tests which collapse:** no behavioral class disappears solely from helper
extraction, but approximately 250 lines of fixture/wait/refusal scaffolding do.
More importantly, findings 1 and 2 become constructions rather than conventions
copied into every new suite.

**Size:** small after the event and canonical-fixture contracts are settled.

### 10. Several properties do not derive inputs from registered schemas

**Failure class killed:** a schema change leaving hand-built generators green
over an obsolete input domain.

**Evidence:**

- `ai_test` hand-generates retry strategies.
- `cluster/run_test` hand-generates command tuples.
- `error_test` hand-generates the five source families.
- `render/block_test` hand-generates projection symbols and rules.
- `sci/admit_test` necessarily has a custom hostile-value generator, but its
  ordinary-data branches should compose with the registered admissible-value
  schema.
- Only `schema/edn_test` explicitly challenges generator honesty, and it does
  so with examples rather than a generated registry property.

Custom state-machine commands are legitimate; schemas cannot generate a valid
history by themselves. The defect is duplicating each command's field domain
instead of deriving its arguments from the command/request schema, then using
the model to sequence them.

**Dissolution design:** add a test-support generator operation which compiles a
named registered schema with the production registry and verifies every
generated value against that same schema. State-machine command generators
compose those field generators, then constrain history in the pure model.
Every property reports schema key, seed, size, generated value, explanation,
and shrink.

**Tests which collapse:** this strengthens, rather than deletes, the run and
error totality properties. It enables findings 3-6 to replace point tests
without creating another handwritten domain.

**Size:** medium, after schema-AST admission is trustworthy.

### 11. Benchmark ownership is honest in one file and stale in the rest

**Failure class killed:** treating an undiscovered probe as correctness
coverage, running destructive benchmarks concurrently, and recording numbers
against dead mechanisms.

**Evidence:**

- `bench/seon/render_bench.clj` explicitly says it measures and never asserts
  correctness. It points counted invariants to `test/seon/render/`. This is
  the correct suite/benchmark split.
- `bench/jvm_render_design_mockup.clj` imports the retired host-era
  `seon.sci.ctx`, `seon.sci.interrupt`, `seon.ui.html`, and Datastar/http-kit
  path. It is a historical design probe, not a fresh-tree benchmark.
- `bench/writer_throughput.clj` says writer correctness remains in
  `test-old/seon/db`, which contradicts the fresh-tree testing authority.
- `bench/db_scale.clj` uses fixed destructive paths
  `tmp/bench-store` and `tmp/bench-store/embedding-index`, mutable global RNG
  and uid state, hand-written raw schema, and a `:syn/kind` taxonomy. It is not
  concurrency-safe and teaches the opposite of the current data model.
- `bench/jvm_render_design_mockup.clj:146-150` sleeps around `System/gc`;
  that is a measurement procedure, not a correctness wait, but its result must
  carry that condition.
- `bench/agent_turn_load.sh` drives a real operator with a 120-second foreign
  request timeout. It is load tooling, not suite coverage.

All six benchmark files are invisible to `bin/test`, appropriately. Any claim
which exists only there is therefore a measurement, never proof. The render
benchmark states this; the others need the same explicit contract.

**Dissolution design:** retain benchmarks only when an active PRD names their
question and result location. Give each destructive benchmark a unique
project-local root and a `finally` cleanup. Delete historical host render
mockups once their recorded research question is settled. Rewrite or delete
the database scale fixture before reusing it; do not carry `:syn/kind` forward.
Every benchmark header names the recurring correctness test which owns its
invariants.

**Tests which collapse:** none. This is suite-shape hygiene and removal of
false authority.

**Size:** small cleanup plus a separate rewrite if database scale measurement
is still required.

## Reset-boundary coverage

The fresh suite has real sockets, real file stores, real process death, and
real `cluster/start!` coverage. It does **not** yet have one recurring
reset-boundary test proving that the operator/reset path installs current code
and initialization pages, then boots a cluster which accepts the current
runtime transaction shapes.

These tests are adjacent but do not close that class:

- `cluster/registry_test.clj:200` proves a branch reset returns to a synthetic
  ancestor carrying `probe-schema`.
- `cluster/loop_test.clj:114` proves an in-memory database installed with
  canonical attributes accepts representative turn rows.
- `config_test.clj:258` proves default config facts round-trip through a
  fixture.
- `schema/admission_test.clj:230` proves production ancestor ordering at the
  schema admission boundary.
- `cluster/turn_test` boots a real cluster and drives real turns, but does not
  cross the reset/rebuild boundary.

**Required construction:** one discovered reset-boundary falsifier creates a
named throwaway cluster through the real reset/initialization owner, opens the
resulting database, verifies current schema/config/program facts, and drives
one real turn transaction. It observes the reset completion event, not a
sleep. This is one interaction-class test, not a copy in every suite.

If the operator cannot be used from the source-classpath gate without
requiring an artifact or running supervisor, extract the in-process reset
operation which the operator calls and test that. Do not make `bin/test`
depend on an operator process.

## Runner and suite shape

Every namespace containing a `deftest`/`defspec` is presently named
`*_test.clj` under `test/` and is discoverable by `bin/test`. The non-test
files under `test/` are support/child fixtures:

- `cluster/store_child.clj`;
- `flow/kill_child.clj`;
- `render_fixture.clj`;
- `schema/edn_test_fixture.clj`; and
- five EDN resource fixtures.

No live test is stranded under `bench/`. Benchmarks are intentionally outside
the runner.

The gate still cannot detect a selected namespace which contributes zero
tests; this was already identified by the testing constitution. Add a
per-namespace test count and fail a selected zero-test namespace. This is a
runner honesty fix, not another test harness.

Exact serialization strings in `render/hiccup_test` are appropriate contracts.
Exact or substring prose elsewhere should be replaced by structured
kind/evidence/route assertions as described in finding 8. `prompt_test` is the
best current example of asserting semantic presence/absence without pinning
copy.

## Recommended implementation order

### Unit 1 — remove false and racing proofs

Delete the `(is true)` diagnostic claim. Replace wake negative waits with
synchronous `poll!`; replace armed/flow observable polling with real event
sources. Keep foreign-process clocks only as named backstops.

**Exit:** no `Thread/sleep` in a parent test except an explicit workload or
foreign-process/measurement backstop; no equality sampled from a live
producer; every wait names its publisher.

### Unit 2 — canonical database fixture

Add the one test-support database owner and convert the eight runtime fixture
lists. Keep narrow schema/store fixtures explicit.

**Exit:** `rg "malli->datahike-schema attributes|model-attributes" test`
finds only tests whose subject is the bridge or a synthetic narrow schema.

### Unit 3 — minimal test support

Extract deletion, refusal unwrapping, event waiting, property reporting, and
schema resource loading under the owners in finding 9. Derive caps and
error-value predicates from production schemas/config instead of support
constants.

**Exit:** one implementation per duplicated behavior and no test registry.

### Unit 4 — AI/turn state machine

Build the cost-evidence oracle and durable-fact checker, then collapse the
scenario matrix.

**Exit:** generated traces cover every registered evidence partition, backup
presence, retry bound, and terminal fact invariant; two teaching examples
remain.

### Unit 5 — message state machine

Reuse the turn unit's command/model/fact-checking shape for message delivery
and chain depth.

**Exit:** both suites generate schema-derived histories and independently
query durable invariants; their scenario counts fall.

### Unit 6 — block graph property

Generate block graphs and specialist rules; assert evaluation, ordering,
local-error, and cap invariants at `surfaces`.

**Exit:** the expansion/selection example count drops substantially while the
covered graph partitions increase.

### Unit 7 — schema-AST and population properties

Generate supported schema forms from the admitted grammar and test semantic
equivalence plus refusal mutations.

**Exit:** adding a supported wrapper requires no point test; it enters the
generator grammar and all bridge/admission properties exercise it.

### Unit 8 — admission/eval ownership cut

Keep hostile value totality in `admit`; keep guarded composition and interrupt
semantics in `evaluate`. Remove copied caps and overlapping cases.

**Exit:** each failure class has one named owner and one regression.

### Unit 9 — projection structure and benchmark cleanup

Replace prose-shaped assertions with structured projection assertions; archive
or rewrite stale benchmarks.

**Exit:** exact strings remain only where bytes or deliberately public text are
the contract, and every retained benchmark names its active question,
correctness owner, unique root, and result destination.

## Graduation measure for the second phase

The second phase is complete when:

- the suite has fewer tests because the named point clusters collapsed;
- every remaining asynchronous wait consumes a real event with a clock only as
  a backstop;
- every runtime database fixture installs the canonical database attributes;
- no duplicated helper implements classification, schema registration, or
  lifecycle semantics;
- generated properties derive field domains from registered schemas and print
  reproducible failure data;
- one discovered reset-boundary falsifier owns initialization/reset
  composition; and
- `bin/test` reports and rejects selected namespaces which run zero tests.

The target is not 334 green tests. It is a smaller set in which each surviving
test names a distinct failure class and each broad class is made difficult or
impossible to represent by one construction.

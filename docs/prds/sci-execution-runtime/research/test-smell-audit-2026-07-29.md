---
type: research
status: active
tags: [prd, research, testing]
---

# Test awkwardness as design verdicts

## Audit boundary

This is a report-only sweep of all of `test/`. It asks what production design
change makes each awkward test unnecessary or trivial; it does not propose
repairs to test syntax.

The tree was moving during the audit. The final cutoff, at `23192f6a1`, saw:

- 64 files under `test/`;
- 54 namespaces containing tests;
- 480 `deftest` or `defspec` forms; and
- 20,399 lines under `test/`.

That cutoff includes the then-modified `test/seon/gen/loop_test.clj` and the
new `test/seon/render/agent_test.clj`. No source, test, benchmark,
configuration, or issue file was changed by this lane.

“Affected tests” below means distinct test forms whose bespoke setup, wait, or
point assertion becomes unnecessary or substantially smaller. It is not a
promise that every affected test should be deleted. Construction-site counts
are reported separately rather than inflated into test counts.

## Relationship to the nine-unit review

This audit follows
[[test-design-review-2026-07-28|the 9-unit test-design review]]. It does not
re-report the remaining work already owned there: the model-call/turn model,
message model, schema-AST generation, admission/eval ownership, the original
block-slot graph property, reset-boundary proof, or runner discovery.

The nine units landed as `44fb814f4`, `08c18b305`, `3deb2b89c`,
`b475dec25`, `50a265247`, `ed22456c8`, `67e0fd948`, `2787dcf1d`, and
`3dc01e8fb`. Findings below are survivors after those commits, regressions
introduced after them, or distinct mechanisms that those units did not own.
In particular, the entity-reference walk below is not the already-landed
block-slot generator.

## Dependency ledger

| Mechanism | Grounding | Design consequence |
|---|---|---|
| `clojure.core.async.flow` at `dc35f3e0d7bc2eef502e77982f48641f025c8051` | `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:108-155` returns a `:report-chan`; `flow/impl.clj:98-172` wires report and error channels | A proc transition is observable data. Repeated ping sampling is not the owning interface. |
| Datahike at `9a7a9ef10a954c32075e60d929f9101a9ac8abd9` | `reference-code/datahike/src/datahike/core.cljc:199-217` owns `listen!`/`unlisten!`; `writer.cljc:384-405` publishes transaction reports | A committed fact has a real event. Database tests need not poll the database. |
| Malli at `80138076960e7820523b4cb932c5b5d1936d4e7f` | Registered Seon schemas and `seon.schema.datahike` already generate the canonical database attributes | Test-local error predicates, cap defaults, and full runtime schema lists should not become authorities. |
| Seon runtime assembly | `src/seon/cluster.clj:659-742` privately builds the live loop handle from database dials | Tests cannot invoke the canonical construction, so four suites recreate it. |
| Seon armed graphs | `src/seon/cluster/agent.clj:298-356` starts each graph but retains mailbox/completion, not the application report stream | Tests infer readiness, pause, and settlement from effects after the event instead of consuming the event itself. |

## Ranked verdict

| Rank | Dissolving design change | Awkwardness dissolved |
|---:|---|---:|
| 1 | Make production cluster-handle assembly an ordinary public construction with explicit runtime-edge overrides | 33 tests |
| 2 | Give the entity-reference walk one schema-derived graph property and structured result oracle | 20 tests; about 18 point examples can disappear |
| 3 | Publish and retain named lifecycle/render reports from the real graph | 12 tests, three polling helpers, and one fixed sleep |
| 4 | Delete test-owned loop prototypes and drive integration through actual per-agent graphs | 6 tests and two parallel runtime mechanisms |
| 5 | Make names state the observed boundary, including an actual overlap oracle for concurrency | 5 tests |
| 6 | Keep structured projections until the presentation edge | 4 non-walk prose-pinning tests |
| 7 | Finish the one-construction support consolidation | 23 actionable duplicate construction sites |

## 1. Make cluster-handle assembly an invokable production construction

The production owner is private `seon.cluster/loop-handle`. Tests therefore
copy the complete runtime map: process, wake and completion channels, provider
descriptor, retry strategy, evaluator symbol, time limit, core-error policy,
recurrence limit, message depth, and result caps.

| Evidence and reach | Design change that dissolves it | Or the test is fine and I am wrong because… |
|---|---|---|
| `test/seon/cluster/turn_test.clj:108-226` supplies the copied handle to all 20 tests in the namespace. `test/seon/cluster/agent_test.clj:76-137` supplies the same shape to 10 tests. `test/seon/gen/loop_test.clj:63-100` does it for two tests. `test/seon/context_test.clj:845-884` does it once. | Make handle assembly a schema'd production function over database-derived dials plus explicit runtime edges. The live cluster calls it; `seon.test-support` calls the same function with narrow overrides. Provider completion may remain a recording stub. Evaluator replacement is allowed only when evaluation is the explicit subject. | These suites need cheap, deterministic provider behavior and occasionally different caps. That justifies explicit overrides. It does not justify copying the owner or choosing defaults a second time. |

This affects 33 tests. It also removes the most consequential mock-shaped
setup: `test/seon/cluster/agent_test.clj:44-63` regex-matches source text and
re-implements the evaluator's disposition semantics. That fake makes the
runtime invokable only after a test has recreated one of its mechanisms.
Recording model requests is idiomatic here; interpreting source in the stub
is not.

The unused `fresh-connection` beside the active fixture is a small symptom of
the same missing owner. The canonical database fixture landed; the canonical
runtime construction did not.

## 2. Make entity walking one generated mechanism

The landed block generator proves slot expansion. A later entity-reference
walk has accumulated a second point matrix in two suites, with prompt strings
acting as its oracle.

| Evidence and reach | Design change that dissolves it | Or the test is fine and I am wrong because… |
|---|---|---|
| Ten tests at `test/seon/render/block_test.clj:755-971` enumerate refs, missing renderers, dangling refs, absent databases, distances, caps, projection steering, and cycles. Ten tests in `test/seon/context_pilot_test.clj:171-487` enumerate distance, family/viewer selection, self exclusion, uniqueness, purity, and determinism. | Put a schema-derived entity graph generator beside `seon.render.walk`. Its oracle compares reachable identities, hop distance, viewer-stable projection, fallback, cycles, caps, exact database value, no writes, and deterministic structured output. Keep one prompt composition example and one web-page composition example. | Exact DOM identity is a wire contract and belongs in focused web/hiccup tests. A family-lens example may also be worth keeping as documentation. Those exceptions justify roughly two examples, not two mechanism matrices. |

This affects 20 tests and should remove about 18 point examples while
increasing graph coverage. It also dissolves the new prose-count assertions in
`context_pilot_test`: reachability and uniqueness become properties of
structured walk nodes, not counts of matching sentences.

## 3. Publish graph lifecycle and render-state reports

The event-wait owner landed as `seon.test-support/await-event!`, but several
interfaces still do not expose the event a caller needs. Tests wrap active
polling in that helper, which changes the clock but not the design.

| Evidence and reach | Design change that dissolves it | Or the test is fine and I am wrong because… |
|---|---|---|
| `test/seon/cluster/agent_test.clj:139-147` owns a 25 ms polling loop, used 17 times across eight tests; line 416 also sleeps 300 ms. `test/seon/render/web_test.clj:191-201` and `test/seon/cluster/turn_test.clj:1376-1386` spin in futures, repeatedly pinging until inferred state appears, across three web tests and one turn test. Both render fixtures drain the flow report channel before polling. | Retain or tap the application report channel in the armed/render handle. Procs publish named `::armed`, `::idle` or episode-settled, `::paused`, and streaming/watch-state reports. Tests consume those reports with `await-event!`; committed outcomes use Datahike listeners. The time limit remains only a loud backstop. | `flow/ping` is itself a real request/reply event and is fine for one observation. The smell is an infinite ping loop used to infer that a transition happened. Foreign child readiness, HTTP, blocking SSE reads, and deliberate workload timing also legitimately keep bounded clocks. |

This affects 12 tests. The missing interfaces are:

- agent graph armed and idle/episode-settled reports;
- pause/resume completion reports; and
- render proc streaming/watch-state change reports.

These are observable internal transitions. A sleep survives only because the
armed entry currently discards the graph report stream.

## 4. Delete test-owned loops and prototype runtime semantics

Two suites avoid invoking the surviving per-agent graph by implementing
another execution mechanism.

| Evidence and reach | Design change that dissolves it | Or the test is fine and I am wrong because… |
|---|---|---|
| `test/seon/flow/loop_test.clj` owns a raw prototype schema, query/commit functions, planner step, and graph construction for four tests. Its production counterparts in `src/seon/flow.clj:687-775` are explicitly “fake” and “prototype” functions used only by this suite. `test/seon/gen/loop_test.clj:103-150` serially scans agents and calls work/turn functions for two tests. | Delete the fake planner/namespace-owner surface and its tests. The generate-code composition arms the actual per-agent graphs, sends messages, consumes their reports, and queries durable facts. Keep only a scripted/recording provider at the genuine external edge. | The old flow tests are useful as a design spike, and deterministic serial driving is easy to debug. A spike belongs in dated research once the real mechanism exists; an executable second scheduler makes the suite green for architecture production does not have. |

This dissolves six tests, more than 700 lines of test-owned machinery, and
dead prototype source/schema surface. It is the clearest example of tests
re-implementing a mechanism rather than recording an edge.

## 5. Make proof names state an observed boundary

Five names claim a stronger fact than their bodies observe.

| Evidence and reach | Design change that dissolves it | Or the test is fine and I am wrong because… |
|---|---|---|
| `n-agent-parallel-turns-property` in `cluster/agent_test.clj:301` commits triggers serially and checks terminal counts. `concurrent-streams-share-one-conn-test` in `cluster/turn_test.clj:1488` uses serial `drive-passes!` and a generous elapsed-time assertion. Neither observes overlap. `restamp-recovery-test` at `agent_test.clj:641` manually calls recovery and arm functions. `cluster/loop_test.clj:112` says “boot-built” after constructing an in-memory database itself. `sci/reader_test.clj:392` is green because a known second-reader debt remains pending. | For concurrency, use one barrier proof: both provider/sink calls must enter before either is released, then query terminal facts. For recovery/boot, exercise the owning boot boundary or rename the narrower pure transition honestly. Finish the one reader and delete the debt-presence test; retain only a structural no-second-reader proof if useful. | Independent graphs plus correct terminal facts prove isolation, which is valuable. They do not prove simultaneous progress. Manual transition tests are also valid when named as transition tests. |

The elapsed-time ceiling is especially revealing: it substitutes a tuned
constant for the overlap event the test name promises.

The reset-boundary construction behind the “boot-built” case remains owned by
the prior review; this finding is only the mismatched claim.

## 6. Preserve structure until the presentation edge

The prior review converted several error/problem projections, but newer tests
again pin prose where the underlying structure is already available.

| Evidence and reach | Design change that dissolves it | Or the test is fine and I am wrong because… |
|---|---|---|
| `test/seon/oversight_test.clj:82-114` asserts exact status sentences in addition to state-bearing markup. `test/seon/render/agent_test.clj:238-257` and `test/seon/cluster/armed_test.clj:289-297` use presentation substrings for state already represented by facts or attributes. Four non-walk tests principally depend on such wording. | Projection functions retain named status, evidence, route, and presentation-key data until the final renderer. Tests assert that structure. Keep one nonblank prose smoke test and exact bytes only at a public wire boundary. | HTML element identity, Datastar attributes, serialized SSE framing, CLI protocol text, and intentionally public operator copy are contracts. Exact assertions there are correct and were not counted. |

The numerous semantic presence/absence assertions in prompt tests are not
re-reported here: their remaining model belongs to the prior review. The
entity-walk prose assertions are counted under finding 2.

## 7. Finish one-construction consolidation

The support consolidation landed useful canonical owners, but 23 actionable
construction sites still duplicate those concepts:

- six local recursive-delete helpers;
- four local file-store probe schemas;
- three local property-result `check!` wrappers;
- three local error classifiers;
- three local refusal unwrappers; and
- four suites that restate default result caps instead of deriving them.

| Evidence and reach | Design change that dissolves it | Or the test is fine and I am wrong because… |
|---|---|---|
| Deletion remains in ancestor, boot, export, registry, store, and fresh-operator tests. `check!` remains in context, render-data, and render-hiccup. Error classifiers remain in AI, reply, and SCI reader. Refusal extraction remains in ancestor, export, and registry. Literal default caps remain in AI-stream-fold, context, context-pilot, and prompt. | Delete helper bodies in favor of `seon.test-support`. Validate errors through the registered error schema. Obtain defaults from the production config/result-cap owner. A test-specific adversarial cap stays an explicit input. | Narrow store schemas are good isolation when store mechanics are the subject; one `render/data` cap of four is deliberately adversarial. The smell is duplicating the helper body or default authority, not using a narrow value. |

This finding is deliberately counted as construction sites, not tests, because
each helper fans out across a different number of tests. It is residue of the
prior review's Unit 3 rather than a new support-layer design.

## Awkwardness that is not a verdict

The sweep also found cases that resemble smells but should remain:

- Recording request ledgers and scripted provider responses are appropriate
  external-edge stubs. They become mock-shaped only when they interpret source
  or drive runtime state.
- Exact datom counts are legitimate when the claim is zero writes, one atomic
  terminal transaction, idempotency, or no duplicate durable fact.
- Exact DOM IDs, Datastar attributes, SSE framing, EDN wire projections, and
  public CLI exit/output contracts correctly assert bytes or structure.
- `async/poll!` is correct for proving immediate absence; it is not a wait.
- Time limits around HTTP, foreign processes, blocking streams, and deliberate
  workload measurements are honest backstops because the remote state is not
  otherwise observable by Seon.
- Small narrow schemas are correct when the schema/store bridge itself is the
  subject. A canonical runtime fixture would hide the claim.

These exclusions are the honest check on the audit: a test is not awkward
merely because it is detailed. It is awkward when its detail reconstructs,
samples, or pins a concept production should make irrelevant.

## Recommended cut order

1. Expose the production handle construction and convert the four fixture
   families without changing their claims.
2. Retain graph reports, name lifecycle/render transitions, and replace the
   three pollers plus fixed sleep with event consumption.
3. Add the entity-walk graph property, then delete redundant point examples.
4. Delete the fake flow prototype and make generate-code use real agent graphs.
5. Add the overlap barrier proof, rename or delete overclaimed tests, and
   remove prose pinning.
6. Mechanically finish the support consolidation.

The first three changes alone simplify 65 awkward tests. More importantly,
each removes a reason for the suite to know how the runtime is assembled,
sampled, or traversed.

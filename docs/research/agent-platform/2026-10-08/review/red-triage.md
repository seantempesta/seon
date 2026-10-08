---
type: research
status: landed
date: 2026-10-08
lane: red-triage
---

# red-triage: every listed red on HEAD, classified by the commit that changed its behavior

Confirmed red in one `bin/seon check --include-long` on 7a9ceb44a (82 tests executed, 46 fail, 11 error), then in a second
for the namespaces the first did not name. Classes: (a) tests deleted machinery, (b) retired assumption updated to the ruling,
asserting data, (c) real defect in surviving code, (d) flaky or over its time bound. Commits: e27763aa9, ad831095f, a4c4b7262,
a20670650, 65a20b84a, 909e49261.

| test | commit that changed the behavior | class | action | result after |
|---|---|---|---|---|
| evaluate-source-test/code-evaluates-as-one-ordinary-run-of-its-agent (9 assertions) | 4c3be3361 (bare `1` is code); 7fc4a5d96-era Var render `{:seon.render.var/symbol}`; 191627656 (bare `ns-unmap` retracts nothing, `my.program/ns-unmap!` deletes); a hex task id may start with a digit, no reader accepts the token (flaky 10/16) | b, d | line reads updated; refusal sections now assert `my.program/ns-unmap!` answering its surviving callers as the form's value; the fixture builds the symbol in the form | green |
| sci.eval-test/acquisition-binds-loaded-first-party-compiled-vars | 6098f0359 (the evaluator frame opens at the start namespace it is handed; assignment is the turn's job) | b | assignment assertion dropped (covered by evaluate-source-test's first run) | green |
| sci.eval-test/an-install-reads-registrations-an-earlier-run-committed | S10: install takes the evaluated Var roots (`:seon.sci.eval/bindings`), no source replay; `user` namespace no longer stored | b | fixture writes the namespace beside the row and hands the evaluation its binding | green |
| sci.eval-test/evaluate-invokes-eval-form-exactly-once-on-every-path | every evaluation now runs inside a registration delta | b | asserts the three deltas: only the registering form leaves `:after` | green |
| sci.eval-test/generated-sources-compose-fork-guard-and-admission | 4c3be3361 (delimiters repaired, reader errors are `:seon.batch/unread`, not evaluation failures) | b | reader-level entries leave the failing-source list | green |
| sci.eval-test/the-request-is-what-the-contract-says-it-is | S4a/S10: `:seon.sci.eval/form` is required | b | request carries a form; a missing form is a second row | green |
| sci.eval-test/schema-and-contract-declarations-have-bounded-allocation | `register!` answers its registration (stale value compare fixed, b); allocation 231 MB vs 64 MiB is c | b + c | value compare fixed; bound untouched | still red: issue `docs/seon/issues/guarded-schema-declarations-still-exceed-the-allocation-regression-bound.md`, lane led-pb05 holds seon.schema |
| sci.eval-test/the-evaluator-remains-live-after-its-namespace-reloads | none found | d | green alone and in its namespace; red when run after other namespaces in one JVM (`restore!` replaced set empty) | still red in the combined run; an earlier test leaves instrumentation state: task `seon.sci.eval/tests-reaching-turns-and-acquisition-are-red-at-head` |
| cluster.agent-identity-test/identity-map-and-omitted-arguments-use-the-same-function | e6066f091 (stored `:seon.fn.arity/input`/`output` retired; contracts read from the projection) | b | selector reads `argument-count` only | green |
| cluster.evaluate-sources-test/ordered-evaluation-retains-one-explicit-basis-without-publication | a form omitting its database reads the connection at the call (CLAUDE.md supply rule): `[3 4 nil]` is now `[3 4 {:seon.agent/id ..}]` (b). The re-record then conflicts with itself, `recorded-content-conflict` (c) | b + c | first expectation updated | still red: new task `seon.turn/an-identical-re-record-conflicts-with-itself` |
| sci.branch-execution-test/branch-definitions-and-unchanged-callers-share-one-context | S10 removed `:seon.program/row` from evaluations (b); then `seon.sci.eval-test/ok?` (a private test helper) does not resolve in the live context (c) | b + c | `declare!` goes through the declaration entrance | still red: new task `branch-proof-live-context-lacks-the-private-test-helpers` |
| sci.branch-execution-test/interpreted-callers-resolve-unchanged-names-to-compiled-vars | ddd3cf9a2 (a branch never changes the platform) | b | host-bound callers load `:jvm`, refuse nothing | green |
| sci.lazy-acquisition-test/cluster-program-is-acquired-on-first-use-and-reused-for-that-database | 5f2aa93f4 (an acquire advances the snapshot's database, a new map) | b | asserts the acquisition and environment are identical | green |
| turn-test/a-definition-changed-since-the-run-read-it-is-refused-at-the-writer | `user` namespace not stored | b | namespace entity written beside the competing row | green |
| turn-test/a-form-whose-facts-are-refused-says-it-was-not-admitted | error base requires `:seon.error/layer`, `:operation` | b | refusal fixture is a complete base | green |
| turn-test/settlement-keeps-unresolved-call-and-require-names-as-values | analysed row needs its digest; SCI refuses unresolved qualified calls at definition | b | digested row; the unreachable calls assertion dropped | green |
| test.selection-test/named-selection-reuses-green-members-by-reachable-content | 5e7dd5e4a/8d1881795: reach digests were derived for members the reuse check then excluded | c | fixed at the producer, `seon.test/select`: no digest for a member already reaching an observed change | green (the test still uses `with-redefs`, against the testing skill; follow-up) |
| fn-test/fixture-observations-survive-static-admission-and-the-fold | none isolated: the fold refuses a deftest carrying `:seon.test/fixture-observation` as a digest mismatch (`facts-error`) in a pre-existing namespace | c | left red | task `seon.batch/a-deftest-fixture-observation-is-dropped-by-the-runtime-batch` |
| render.ns-test/identity-reads-stewardship-without-an-agent-cluster-attribute | 648369489 (an agent names its cluster) | b | renamed `identity-reads-stewardship-from-the-namespace`, asserts the cluster present | green |
| render.ns-test/task-and-schema-entities-render-as-graph-nodes | live data: 143 of 200 stored intents exceed 400 characters | b | check uses a schema every intent violates by its own `:min 1`; bound not raised | green |
| render.web-test/a-reconnect-refuses-a-pass-derived-before-it-connected | none isolated: the hold render function `my.agents.root/ns-view` is never entered (20 s wait) | c | left red | task `a-reconnect-refuses-a-pass-derived-before-it-connected-is-red` |
| render.web-test/a-route-throw-answers-a-bounded-500-inside-the-handler | fa31a1a97 (one printer: the 500 body is the value printer's error entry) | b | asserts the operation and the message, not the class name | green |
| render.web-test/reconnect-is-repaint-wire-test | per-basis `as-of` walk over the whole history (9.8 s vs 5 s) | d | the family is absent from the registry, so the walk adds nothing; removed | green |
| render.web-test/unrelated-transaction-reuses-debug-observation-and-render-call | render invocation no longer passes `kernel/invoke`, counters stale | b | asserts the page repaints with the changed doc and observation grew | green |
| schema.datahike-test/canonical-population-native-parity | 3303e2278 (error zoo deleted: predicates `seon.error/ordered-explanations?` etc.) and 6f7cee1f2 (render faces are entity members and properties) | a, b | 460 forms, 454 attributes and natives of the recorded oracle removed (2683 forms remain, closure over references); disjointness assertion replaced by distinctness | green |
| datahike-test/a-transaction-function-error-refuses-as-itself, registration-admits-over-a-handed-declaration-projection | contracted encode takes a database; `register!` answers its registration | b | fixtures updated | green (found beyond the list) |
| cluster.turn-test/runtime-schema-registration-commits-the-evaluated-form-and-attribute | S10 | c | left red | task `schema-entities-lose-their-declaring-namespace` |
| cluster.turn-test/streaming-writes-zero-datoms-test | printer: shown text is pretty-printed | b | reads the shown text back as data | green |
| cluster.turn-test/runtime-schema-key-changes-pass-the-one-usage-guarded-decision | same text compare (b fixed); a form is still refused in the run (c) | b + c | text compare fixed | still red, task `tests-reaching-turns-and-acquisition-are-red-at-head` |
| cluster.turn-test/accepted-first-party-definition-reaches-an-existing-agent-next-turn, acquisition-orders-agent-authored-refer-targets-and-ignores-alias-cycles (6 vs 7 installs), import-addition-is-ordinary-data-and-reacquires-exactly, import-only-ns-unmap-installs-exactly-after-its-context-commit, reasoning-starvation-persists-usage-finish-and-the-named-error (`:seon.ai/exhausted-finish-reason` absent), two-drivers-of-one-opening-both-answer-and-it-settles-once (`seon.fault/fault!` reads a nil connection) | acquisition and import masks (led-stale2 area, eval.clj held) | c | not touched | still red, task `seon.sci.eval/tests-reaching-turns-and-acquisition-are-red-at-head` |
| sci.documentation-test/an-absent-declaration-is-a-typed-statement-not-an-empty-one | 7fc4a5d96 (undocumented: summary absent) | b | asserts no `:summary`/`:doc` key | green |
| sci.documentation-test/documentation-is-returned-data-without-a-second-printed-copy | settings! contract is `:seon.config/agent-overlay` with positional connection and agent (no `:supplied`); directory value carries its render pair | b | expectations follow | green |
| sci.documentation-test/retained-context-receives-new-bare-test-referrals | S1-03: deftest/is arrive as namespace refers at namespace creation, not through `clojure.core` | a | deleted (`bare-test-macros-resolve-without-namespace-referrals` covers resolution) | removed |
| sci.eval-instrumentation-test/an-instrumented-dev-cluster-builds-an-attempt-ready-prompt | real cluster boot is 50 s | d | declared `:seon.test/long` 90 s with the measurement | green; over ten seconds, new task `seon.cluster.boot/a-development-cluster-boots-in-tens-of-seconds` |

## Counts

Deleted 1; updated (b/d) 28 tests; fixed in surviving code 1 (selection); still red with a task 11 (allocation, evaluator-reload order,
evaluate-sources, branch proof, fn-test, reconnect-pass, runtime-schema-registration, runtime-schema-key-changes, and the acquisition/import
group of six counted in `seon.sci.eval/tests-reaching-turns-and-acquisition-are-red-at-head`).

## Findings outside the list

- Check slots: `bin/seon check` waited behind other lanes for a slot for most of an hour at one point (two slots, polled once a second, no queue order).
- `selection_test` asserts reach-digest derivation with `with-redefs`, which the testing skill forbids.
- Failure reports drop the `actual` of an `is` whose value is an error map; the full text is only in the DIAGNOSTIC log file.

# Health machinery cost

`seon.health/report` retains coverage by the attributes it reads, but a coverage miss computed a recursive Datalog relation through a second copy of the declared-invocation rules. `seon.fn/functions-without-tests` now reads the stored edges once, reuses `seon.fn/declared-callers`, and visits each reached entity once. The report keeps its existing cache and its positive observations.

## Result

RESTART NEEDED. Default was neither restarted nor reset. The candidate removes 75 source lines and 36 test lines. It retires `seon.fn/functions-without-tests-is-the-last-datalog-reach`; the root-owned schedule index still names that task and needs reconciliation.

The same complete cold-report probe on one owned, otherwise idle JVM gave:

| Definition | Five report times in ms | Median ms | JVM-window allocation per report |
| --- | --- | --- | --- |
| Parent, first round | 526.76, 431.44, 389.44, 378.98, 375.49 | 389.44 | 921 MB |
| Change, first round | 174.10, 155.55, 143.01, 160.92, 166.71 | 160.92 | 209 MB |
| Change, assertion round | 123.61, 143.14, 140.57, 156.75, 140.29 | 140.57 | 210 MB |
| Parent restored, assertion round | 514.29, 406.65, 425.30, 443.47, 419.25 | 425.30 | 921 MB |

The first comparison improves report latency 2.42 times and allocation 77%. Restoring the parent after the change reproduces the slower report, rather than attributing JVM warming to the patch. The same 300 ms median assertion passes on the change and fails on the parent. This is an experimental bound separating the observed distributions, not a new production policy dial. Every measured problems derivation completed; no sample obtained its speed by returning pending.

The coverage derivation alone produced identical values on the published program: 378.46 → 83.12 ms and 975,193,664 → 114,507,008 allocated bytes. Health's existing attribute-revision cache retains that result; the warm-report path is unchanged.

## Live observations

The supplied survey described default at `b14dc2b3f`. On this run default advertised archive `a221ad5692514d0608e20720c5d5392e725cc3cd`, pid 81386, started 2026-10-12T12:48:38.004Z. Both `bin/seon status` and MCP `runtime_status` answered. It was degraded: six errored receipts, one unowned namespace, and untested-hot warnings.

MCP JVM mode, agent root, cluster default:

```clojure
(seon.profile/measure
 #(seon.health/report {:seon.boot/cluster-name "default"}))
(seon.cache/status)
```

Three warm report calls were 22.75, 10.03 and 12.13 ms; 8.87, 4.81 and 4.81 MB calling-thread allocation. Current cache totals were much smaller than the historical survey:

| Kind | Hits | Misses | Total load ms |
| --- | --- | --- | --- |
| `:seon.health/derived` | 68 | 52 | 629 |
| `:seon.health/problems` | 7 | 5 | 327 |
| `:seon.test.runner/reach-graph` | 15 | 1 | 38 |

`seon.health/derived` retains each family, stored finding, function-file query, regression query and coverage result by loaded identity and read-attribute revisions. Its miss performs that named derivation and collects read evidence. The enclosing problems cache is per committed value; its miss assembles the families, whose individual retained values survive unrelated writes. The reach graph is already keyed by relevant attribute revisions and requested test symbols, and can reuse the larger retained reach index. Its current 38 ms load did not justify changing the runner.

Direct family probes, derivation plus captured evidence, initially found stale Vars largest at 64.23 ms, followed by files without a namespace at 19.99 ms. Warm repeats separated derivation/evidence: stale Vars 8.78/0.24 ms, agent error signatures 6.86/0.17, error signatures 3.05/0.43, the other families 0.35–1.59/0.10–0.27. Stale Vars necessarily compares the published and loaded function populations; failed tests derives current reach only for recorded reds. The largest reproduced remaining coverage miss was 492.76 ms and 972,438,016 allocated bytes, then 3.41 ms through Datahike's query cache. No new cache was warranted.

## Composition and proof

The replacement's input is one immutable database. Function identities map symbols to entity ids; tests remain their own entity ids, so a function and test with the same symbol are not conflated. Calls, references, test subjects, unresolved file references and declared invocations produce outgoing edges. Only an existing function identity is a target, as in the old rules. A vector worklist expands each reached entity once and terminates on cycles. Public unreached functions are sorted as before. Work is proportional to stored edges plus reached entities, followed by sorting the answer; it is not all reachable pairs.

The deleted producers are `seon.fn/declared-reference-rules`, `seon.fn/reach-rules` and `seon.fn/test-reach-rules`. Their only remaining rule-comparison test, `seon.test.declared-reference-test/indexed-declared-references-preserve-rule-results`, is deleted with them. `seon.fn-test/declared-function-values-contribute-edges-without-arities` now proves coverage through a declared capability after its lexical call is removed. `seon.fn-test/tests-reaching-follows-calls-and-explicit-subjects` includes a reached cycle and retains its same-name function/test proof. The existing mention-versus-call selection task remains: this patch preserves coverage's reference semantics.

Library evidence: Datahike pin `c9e57c6fb65365d5ce92b59854fb02ea59054fe7`, `datahike.db/advance-cache-context` and `datahike.query/dependency-plan-attributes` own attribute revision tracking. The patch uses the existing `seon.db/datoms` and `seon.db/q` entrances, preserving their captured dependencies, and `seon.fn/declared-callers` for declared edges. Malli pin `dd13ff81139159778edb0a67b491eccc0baa991d`, `malli.core/validator` and `malli.core/-instrument` supply validation and instrumentation; the existing complete function contract is unchanged.

Alternatives measured and rejected: materializing the old Datalog edges and walking them through `clojure.tools.namespace.dependency/transitive-dependencies-set` cost 2,103.55 ms and 9,023,184,472 allocated bytes just for its lazy-concat traversal of 34,046 edges. `clj-reload.parse/transitive-closure` was inspected, but clj-reload is not on the installed classpath. The final worklist uses the same vector/seen-set approach already present in `seon.fn/gate-set-in`; it adds no dependency or graph service.

## Reproduction

The owned root was `/Users/sean/src/seon/tmp/health-cost`, JVM pid 99597, start 2026-10-12T13:02:27.606Z, booted from committed archive `f240e967ed7f665242cbe15195f6b807243dfc17`. On that root only, the changed `seon.fn/functions-without-tests` was read and evaluated in its namespace, then `seon.instrument/apply!` with `:seon.instrument/changed-identities #{[:seon.fn/sym 'seon.fn/functions-without-tests]}` registered one function. The parent definition was restored and instrumented by the same method for the final red measurement. No candidate source was loaded into default.

The committed `health-cost-probe.bb` calls `seon.profile/measure` around the complete report with a held database and a 10 s observation deadline, clears health and Datahike query-result caches between five samples, and asserts a 300 ms median. Run it only on an owned isolated root; the clearing is experimental setup, not production behavior:

```sh
bb -cp script:src:resources docs/research/agent-platform/2026-10-12/health-cost-probe.bb OWNED_ROOT 300
```

Allocation in the report table is the JVM-window counter because health derives on worker threads. It includes all concurrent work in that JVM; the owned JVM had no other test or probe workload. Parent and change ran in the same JVM against the same database, with instrumentation enabled. Calling-thread allocation alone was about 27 MB in both and would miss the principal saving. Concurrent external JVMs can still affect wall time; the two rounds and restored-parent measurement bound that limitation.

The owned JVM was stopped through `bin/seon --root tmp/health-cost down`; exact identity confirmed actual exit. Full `clj-kondo --lint src script test --cache false`: 0 errors, 1,620 warnings before the candidate check.

Candidate `3b63da718d99fcf070714afb34189c9d987e98e0` passed publication and reload, then its throwaway JVM ran all `seon.health-test`, its explicitly named long report test, `seon.fn-test/tests-reaching-follows-calls-and-explicit-subjects`, and `seon.fn-test/declared-function-values-contribute-edges-without-arities`: run `c2d23639529a`, 10 tests, 58 assertions, 0 failures/errors, 6,873 ms. No selected pre-existing reds. The check took 126,924 ms, including 86,914 ms queued behind two other lanes; publication 5,632 ms, reload 897 ms, throwaway readiness 10,783 ms. These are the required platform rehearsal and test batch, not report-call costs.

Foreign boundary: shared working-tree edits to runner, task, turn, database, dependency pins and fixtures were excluded from this candidate. Those files were not edited, and no foreign sessions were resumed or messaged. No foreign failure blocked this slice.

Co-Authored-By: gpt-6-astra <noreply@openai.com>

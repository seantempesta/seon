---
type: research
status: active
tags: [research, instrumentation, schema, class/n3]
---

# Referenced declarations in wrapper identity — 2026-09-16

## Result and boundary

Arming implementation commit: 98b5f2afe. Selector and residual evidence
commit: 1c98259ba.

The host arming owner now retains the canonical contract's transitive
declaration definitions and a 64-character contract digest. It compares the
captured definitions with the supplied projection before re-arming. The
canonical class regression passes 8 assertions: changing an entity-map
declaration behind an alias replaces its wrapper, accepts the widened value,
still rejects an invalid value, and preserves an unrelated wrapper and
unchanged repeat arming.

The named web-debug failure was **not evidence of stale instrumentation**.
Its explicit nested selector omitted `:db/id`; the current widened schema
correctly refused `{:seon.fn/sym "seon.bootstrap/render-help-ai"}`.
Adding `:db/id` to the existing ledger selector removes that error.
The test then reaches 95 assertions, with 88 passes and seven separate
fixture/budget failures. Those are recorded in
[the residual issue](../../../seon/issues/web-debug-fixture-transactions-and-budget-expectations-fail.md).

Full development adoption and the orchestrator's batched gate remain pending.
The hook exited 124; an explicit adoption waited on other publications'
operator lifecycle lock. The final source was instead hot-reloaded and
re-armed in default for the in-process proofs below. This is not a claim
of completed adoption or browser paint.

## Reading and archaeology

Read AGENTS.md, docs/seon/issues/README.md, the N3 class note and its four
currently tagged member notes, the N3 structural-kill row in
[issue class mining](../../sci-execution-runtime/research/issue-class-mining-2026-08-11.md),
[adoption contract freshness](../../context-generation/research/adoption-contract-freshness-2026-09-15.md),
and tmp/orchestrator/wave2/repl-rule.txt end to end. Also read the archived
evaluation-reader issue and the canonical-fixture and fixture-poison notes.
Applied data-oriented-clojure, repl, clojure-testing, data-modeling, datahike,
and datastar-web-ui skills; read the UI architecture before changing its
selector. The roadmap README was read.

Git archaeology: `5ffc491ae` adds authored-schema comparison;
`52044b4f4` admits `{:db/id n}` in the evaluation entity.
The inherited branch was steward-platform. HEAD was `c4c3ca475` during
the candidate checks; concurrent commits continued during the work.

The broad N3 class remains open. Its operator reload, predicate-readiness,
analysis-cache and partial-generation members are outside this explicitly
bounded host-arming assignment; no closure of those members is claimed.

## Dependency ledger and owning seam

- Malli `reference-code/malli/src/malli/instrument.clj:8`: original callable
  metadata; `:44`: authored schema extraction.
- Malli `reference-code/malli/src/malli/core.cljc:2612`: schema walking;
  `:2827`: reference dereferencing.
- `src/seon/schema.clj:33,593`: direct canonical refs from Malli objects,
  including refs nested behind local registries. This distinguishes schema
  refs from keyword data.
- `src/seon/schema.clj:1780`: the projection already derives forward and
  reverse dependency graphs. The new closure uses these edges, falling back
  to the same direct-ref reader for declaration-only projections.
- `src/seon/instrument.clj:548`: compiled validators remain owned by their
  immutable projection. No second cache or global freshness registry was added.
- `src/seon/instrument.clj:570`: dependency capture; `:593`: pending
  selection predicate; `:608`: wrapper construction; `:654`: apply!.
- `src/seon/cluster.clj:2059`: development adoption already hands the
  reconciled projection to apply!, so no cluster edit was needed.
- `src/seon/id.clj:50`: the existing digest owner, reused rather than
  introducing another hash function.

Guarantee: after arming, changing a canonical declaration in a wrapper's
transitive contract closure replaces that wrapper on the next apply!, while
changes outside that closure retain it.

Selection compares the digest's definition inputs directly, avoiding
recompiling or hashing every unchanged wrapper. New and legacy wrappers
without dependency metadata are uninitialized under this identity and acquire
it once. There is no blanket remove/re-arm operation. Host-only contracts may
contain actual callable objects; they retain their existing JVM identity
semantics, while indexed contracts use the projection's canonical form.
This does not make an entire multi-namespace adoption atomic.

## Member verdicts and measured evidence

| Subject | Verdict |
|---|---|
| Authored contract freshness | Preserved: final run 50143, 7/0/0. |
| Referenced canonical declarations | Repaired at apply!/arm-var!: final run 50142, 8/0/0, transitive alias and unrelated identity assertions. |
| Bare pulled renderer-ref issue | Already resolved by 52044b4f4; initial renderer test run 48986 was 11/0/0. No schema change made here. |
| Web-debug attributed wrapper refusal | Attribution falsified. Run 48991 was 3/0/1; the captured actual renderer ref omitted :db/id. Selector correction yields 88/7/0 in runs 50137 and 50144; seven residuals are separately named. |
| Attempt settings/model relationships | Final run 50145, 4/0/0. No repair necessary. |
| Renderer program reference | Final run 50146, 11/0/0. No repair necessary. |

Counts are passes/failures/errors. The final five in-process runs total
118 passes, seven failures, zero errors; four tests are wholly green
(30 assertions). The web-debug test contributes the remaining 95 assertions.

Read-only source/fixture probes compared the exact renderer entry in live,
fixture-derived, fixture-carried, handed, wrapper-captured, packaged and
Malli-resolved declarations. All had:

```clojure
[:seon.eval/renderer-fn {:optional true}
 [:or :seon.eval/renderer-fn [:map [:db/id :int]]]]
```

The failing call supplied this selector fragment and value:

```clojure
{:seon.eval/renderer-fn [:seon.fn/sym]}
{:seon.fn/sym "seon.bootstrap/render-help-ai"}
```

Malli's complete error set included `:malli.core/missing-key` at
`[:seon.eval/renderer-fn :db/id]`. The integer message was the first branch
of the union, not proof that the union was missing. A retained-cache versus
fresh-cache probe both returned 10 rows; stale-cache attribution was not
established and no cache-clearing mechanism was introduced.

Final direct default probe, 21 ms JVM evaluation time: of-agent returned
10 Juniper evaluations, including renderer refs `{:db/id 4651}`,
`{:db/id 6339}`, and `{:db/id 7229}`; its Var retained its host-wrapper
marker. A repeated default apply! reported 1090 registered/instrumented Vars
and zero replaced entering roots.

## Exact REPL sequence

All tests used MCP JVM mode on default, normal `seon.test/run`, its own
loader, a future, and subsequent short observations. No test JVM or
bin/test-fast invocation was launched. Reusable exact forms are in
[the MCP probe forms](arming-includes-referenced-schemas-2026-09-16.edn).

The new definitions were evaluated in default before source edits.
The helper was called on of-agent's actual contract: 42 canonical
dependencies. The class regression called the new arm-var!, current-wrapper?
and apply! with two complete canonical fixture projections.

Candidate iterations recorded:
- 49026: 0/0/1, missing Malli Var-registry support in dependency inspection;
- 49027: 0/0/1, EDN canonicalization encountered a host schema Var;
- 49932: 0/0/1, durable canonicalization rejected a host-only callable;
- 49935: 7/1/0, MCP reader auto-resolution put prototype metadata under the
  wrong namespace; subsequent MCP forms fully qualified those keys;
- 49938: 8/0/0, final class candidate, before source edit;
- 49942: 8/0/0, reloaded test source against the candidate.

The host callable cases were handled without changing Malli or loosening
contracts. The temporary predicate arity experiment was restored before
evaluating the coordinated apply!/arm-var! definitions.

For the selector slice, the new ledger-acquisition definition was evaluated
before editing its file. The real web-debug fixture invoked it and observed
the exact selected results: run 50137, 88/7/0, with no contract error.

After file edits, explicit host reload of seon.instrument and
seon.render.transcript followed by apply! returned 1090/1090.
The final test namespaces were reloaded through with-test-loader:
50142 (8/0/0), 50143 (7/0/0), 50144 (88/7/0), 50145 (4/0/0),
50146 (11/0/0), 2026-09-16 05:06:23–05:06:48 UTC.
These are hot-reload proofs; adoption is separately unverified.

## Publication, gate request, cleanup

Hook publication df481527-3012-4c14-a1a4-70b257eafb4d returned operator exit
124: publication did not finish within its declared bound. The subsequent
selector edit queued 654cd859-2e16-406e-9afd-c9c9af9d7b9b.

The final read-only source check returned adopted commit
6aaa1e39-5bf9-503b-812d-ee32132d0de8 and published commit
6aaa24f8-dcbc-5785-977e-f48b672b940d. Their inequality independently
confirms that completed development adoption cannot be claimed.

The explicit lane adoption CLI (PID 72819) waited at least 244051 ms
for root-lifecycle.lock, first held by the fn publication (PID 69880),
then the test publication (PID 75328). Only this lane's waiting CLI was
terminated; it exited 143. No foreign process/session was operated.
Default PID 53378 was never stopped, reforked or restarted.

The orchestrator should run one invocation at a time, using the owned
source/test paths:

```sh
bin/test --paths src/seon/instrument.clj src/seon/render/transcript.clj test/seon/instrument_test.clj -- seon.instrument-test seon.render.web-debug-test seon.data-shapes-test
bin/test --paths src/seon/instrument.clj src/seon/render/transcript.clj test/seon/instrument_test.clj --platform
```

Neither gate was run by this lane, per the assignment's no-test-JVM rule.
The web-debug residual must not be reported green. Hook lint reports no
Clojure errors after adding the explicit seon.id require; existing warnings
are recorded by the hook. git diff --check passed for owned code.

No scratch cluster or worktree was created. All lane shell commands exited.
The probe futures completed; only the recorded database test facts remain
after removing the lane's temporary probe Vars.

Cleanup returned 15 removed probe Vars after verifying every retained future
had completed. PID 72819 was absent from the final process check. The remaining
working-tree changes belonged to other lanes and were preserved.

## Exact touched paths

- AGENTS.md
- src/seon/instrument.clj
- test/seon/instrument_test.clj
- src/seon/render/transcript.clj
- docs/prds/steward-platform/research/arming-includes-referenced-schemas-2026-09-16.md
- docs/prds/steward-platform/research/arming-includes-referenced-schemas-2026-09-16.edn
- docs/seon/issues/archive/armed-contract-identity-omits-referenced-schemas.md
- docs/seon/issues/archive/evaluation-reader-refuses-pulled-renderer-ref.md
- docs/seon/issues/web-debug-fixture-transactions-and-budget-expectations-fail.md
- docs/seon/issues/mcp-jvm-small-result-projection-fails-during-live-adoption.md

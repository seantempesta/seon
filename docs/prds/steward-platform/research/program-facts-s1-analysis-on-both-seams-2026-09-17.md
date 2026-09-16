---
type: research
status: ready for review
created: 2026-09-17
tags: [research, program-graph, indexing, sci]
---

# S1 — analysis on both declaration seams

The SCI definition constructor now uses the indexer's source-string analysis
and canonical rows. The permanent resource/publication-versus-turn regression
passes 21 assertions. Broader in-process verification and the Juniper live
proof are recorded below. No cold gate is authorized in this lane.

## Authorities and seams read

Read the named program-facts PRD and `tmp/orchestrator/wave2/repl-rule.txt`
end to end, and AGENTS.md sections 0–5 and 7. Read the integration source
before designing:

- `src/seon/fn/analyzer.clj`, whole file.
- `src/seon/fn.clj:690–780`, `1040–1110`; additionally source extraction,
  namespace context, `var-row`, `analyzed-form`, `analyze-forms`,
  `analysis-rows-by-file`, `build-artifact`, `build-manifest`, `rows`,
  `desired-rows` and contract enrichment.
- `src/seon/program.cljc`: shapes, test markers, canonical-row,
  declaration-row and their supporting constructors.
- `src/seon/sci/reader.cljc`, whole file, including namespace-info and
  the current namespace/alias/refer tracking.
- `src/seon/sci/eval.clj:380–460`, `780–1010`; additionally reader-context,
  binding rows, declared-row, evaluate, evaluate-candidate and installation.
- `reference-code/clj-kondo/src/clj_kondo/impl/analysis.clj`, whole file,
  and its analysis README's consumed output shapes.
- Fixture/publication owners in `test/seon/fn_test.clj`,
  `test/seon/turn_test.clj`, `test/seon/issue_settlement_test.clj`,
  `test/seon/program_test.clj`, `test/seon/test_support.clj`; the runner in
  `src/seon/test.clj`; resource construction in `src/seon/schema/edn.clj`.
- The capability and pending-call integration in `src/seon/test/accretion.clj`,
  `src/seon/effect.clj` and `src/seon/turn.clj`.
- Reload restoration and arming in `src/seon/instrument.clj:664–820`,
  `test/seon/test_support.clj:1085` and the existing evaluator reload regression
  in `test/seon/sci/eval_test.clj`.

Applied the data-oriented-clojure, repl, clojure-testing and datahike skills. The
context-generation roadmap entry was read; the full historical working
edge was not reviewed.

## Dependency ledger and changes

clj-kondo supplies namespace definitions/usages, Var definitions/usages,
metadata and keyword ownership; Seon's analyzer normalizes those keys.
The existing `runtime-analysis-batch` accepts the effective SCI namespace
row as data, including uncommitted aliases and refers. The public,
Malli-contracted `seon.fn/source-rows` runs that batch and the same
`analysis-rows-by-file` constructor the indexer uses.
`program/canonical-row` receives the threaded shapes. Evaluated declarations
have no `:seon.fn/file`, `:seon.fn/form-span`, or file-coordinate defaults.

`definition-row` no longer constructs function/test maps from Var metadata.
It selects the analyzed declaration and merges only normalized contract data,
workload and test markers. Generation comparison still identifies the accepted
definition. `declaration-row` stamps `:agent`. The turn writer and its
transaction/provenance path were not edited.

The evaluator uses the immutable database already carried by its request.
An isolated SCI context has no durable database and continues to return its
private values without constructing a program row. The broader suite caught
an unconditional connection dereference in the first implementation; the
existing isolated-context regression passed 4/4 after this correction.

The existing evaluator reload regression also needed to re-arm its newly
loaded definitions. The canonical restoration fixture correctly excludes
superseded roots, so the test now calls the existing `instrument/apply!` in
`finally`, with its entering handed projection. It passed four assertions
without drift. The [existing issue](../../../seon/issues/sci-reload-test-leaves-worker-instrumentation-changed.md)
records the recurrence and correction; no instrumentation owner was changed.

The parity test exposed two integrations:
- Re-analysis merged into an already analyzed candidate without clearing old
  graph facts. An unresolved forward call retained its earlier resolved edge,
  and capability lookup reached an absent entity. The existing merge now
  replaces graph facts, so the existing pending-call writer resolves forward
  declarations. No second resolution mechanism was added.
- Schema evaluation carried an implicit reader namespace and lost derived
  generatability/shape through declaration canonicalization. Evaluation now
  computes the same contract facts using its candidate projection, and
  canonicalization retains those facts. Namespace properties explicitly
  declared in a schema's definition still come from that definition.

Literal register! in .clj source is not a declaration seam by ruling.
The [resolved issue](../../../seon/issues/archive/schema-parity-compares-resources-with-evaluation.md)
corrects the earlier investigation. No source-registration indexer was added.

## Permanent acceptance

`seon.program-test/indexed-and-evaluated-declarations-are-the-same-entities`
uses `seon.fn-test/with-provenance-file` and canonical `with-database`
fixtures. Its indexed branch receives `build-artifact`, canonical resource
rows and `reconcile-tx` through `transacted!`. Its evaluation branch uses
`seed-cluster!`, `fork-cluster-ctx`, `cluster-handle`, `virtual-turn!`
and the existing turn transitions. No fixture harness or test JVM was added.

The fixtures in `test/fixtures/program_facts_s1/` declare two mutually
calling functions, a declared schema key and a deftest. The resource supplies
the indexed schema; the turn evaluates `seon.schema/register!`.
The test pulls the namespace, both functions, test and schema, checks that
each exists, normalizes refs/components with the existing publication
normalizer, compares every attribute except I1's exceptions, and asserts
nonempty evaluated call edges. The function's declared `:seon.fn/doc-order`
also participates in equality.

## One indexed/evaluated entity diff

At 2026-09-16T18:04:33Z the regression reported 20 passes, one failure and
zero errors: function/test/namespace parity was green, and the schema entity
differed as follows (db ids and admission source removed):

```diff
 {:seon.schema/key :sample.s1/value
  :seon.schema/form "[:int {:min -8, :max 8}]"
- :seon.schema/generatable? true
- :seon.schema/shape
- [:seon.schema.shape/fingerprint
-  "0448f7805c0c1a5a2a18b9ead43c42688fd522274ac08246271ceccf7db052d2"]}
+ :seon.schema/ns [:seon.ns/name sample.s1]}
```

Here minus is the indexed entity and plus is the evaluated entity. After
the schema constructor correction, the same regression passed 21/21;
both normalized entities equal the indexed map above, with no namespace
attribute. The indexed/evaluated difference after the fix is empty.

## In-process and live evidence

The [reproducible verification forms](program-facts-s1-verification-2026-09-17.clj)
reload only the test namespaces through the existing test loader and use
`seon.test/run`'s three-argument arity, declared provenance and 240000 ms
(600000 ms for serial reruns).
The fixture namespace was never reloaded. Default remained PID 53320.

Before file persistence, the new source-row function returned an alias-resolved
`seon.id/id` edge in 18 ms. The existing
`seon.fn-test/fixture-observations-survive-static-and-runtime-admission`
passed 11 assertions. The complete parity prototype passed 21 assertions.

The Juniper live turn `404bfad994bc` closed at transaction 536871253,
2026-09-16T18:25:35.697Z, with no evaluation errors. It ran against the
hot-reloaded evaluator and analyzer after development reload/instrumentation;
the later adoption retry did not converge. Its observed facts were:

```clojure
{:seon.fn/sym "my.agents.juniper.s1/caller"
 :seon.schema.admission/source :agent
 :seon.fn/calls [{:seon.fn/sym "my.agents.juniper.s1/value"}]}

(seon.fn/tests-reaching database "my.agents.juniper.s1/value")
;; ["my.agents.juniper.s1/caller-test"]
```

A pull of the complete caller entity found no `:seon.fn/file`,
`:seon.fn/form-span`, or `:seon.fn.file/*` keys. The turn still names Juniper
through `:seon.turn/agent`. However, querying every attribute of its source
transaction found only `:db/txInstant`, not agent/turn transaction metadata.
That existing writer gap is recorded in
[the provenance issue](../../../seon/issues/turn-declaration-transactions-have-no-agent-or-turn-metadata.md).

Final in-process totals: 72 selected tests, 759 assertions, zero failures and
zero errors in the latest result for each test. This is reach-selected
verification in the four requested namespaces, not their complete cold gate.
The [exact results](program-facts-s1-results-2026-09-17.edn) retain all 87
attempts, including superseded implementation failures and concurrent drift.

| Namespace | Tests | Passing assertions |
|---|---:|---:|
| seon.program-test | 6 | 134 |
| seon.fn-test | 8 | 63 |
| seon.turn-test | 11 | 313 |
| seon.sci.eval-test | 47 | 249 |

The final serial pass repeated the analyzer regression (5/5), permanent
parity regression (21/21), and saved evaluator reload regression (4/4),
all without drift. Test namespaces were reloaded through the canonical loader.

## Review boundary

No protected boot/cluster hunk was needed. Concurrent changes in db, issue,
plan, render and their tests were preserved. Publication encountered the
shared lifecycle lock and source-changed-during-adoption retries. An initial
in-process test passed its five assertions but reported instrumentation drift
for concurrently re-armed `seon.plan` functions; its final rerun was clean.
One publication exhausted the operator's 900000 ms lock-hold bound. A fresh
attempt completed publication but refused adoption because source commit
`6aaad49e-a55e-52ad-981d-4df826c4bb8f` was unavailable. See
[the source-basis issue](../../../seon/issues/development-adoption-refuses-an-unavailable-source-basis.md).
No converged development adoption is claimed; the proofs exercise loaded
definitions in default, whose PID remained 53320.
After the final edits, another publication request waited more than five
minutes behind foreign cluster/source adoption. The lane terminated only its
own waiting operator process (PID 99183, exit 143), before leaving the review
boundary. It did not interrupt the holder or default. The final reload test
loaded the saved evaluator source and re-armed it successfully.

Production files: `src/seon/fn.clj`, `src/seon/program.cljc`,
`src/seon/sci/eval.clj`. Regressions: `test/seon/program_test.clj`,
`test/seon/sci/eval_test.clj`, and the two fixture resource/source files under
`test/fixtures/program_facts_s1/`. The remaining owned paths are this landing
note, its verification script/results, the corrected schema-parity issue,
and the provenance, adoption and reload issue records.

Owned-file clj-kondo check: five files, zero errors, 75 warnings, 312 ms.
The repository markdown hook reports 29 pre-existing historical gitlink
citation errors in `agents-md-audit-2026-09-15.md`; those foreign citations
are outside this slice.

No test JVM, cold gate, restart, scratch cluster or worktree was started.
The orchestrator's personal review precedes any gate.

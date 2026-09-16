# Call-graph fidelity implementation — 2026-09-17

Status: implementation in progress; orchestrator review required before any gate.
No test JVM launched; default PID 53320 has not been stopped or restarted.

## Grounding and dependency ledger

Read end to end: `call-graph-fidelity-2026-09-17.md` (Option B),
`test-system-is-the-database-prd-2026-09-17.md`, parent PRD §4b,
`tmp/orchestrator/wave2/repl-rule.txt`, `src/seon/fn/analyzer.clj`,
`reference-code/clj-kondo/src/clj_kondo/impl/analysis.clj`, and
`reference-code/clj-kondo/analysis/README.md`. The assigned
`reference-code/clj-kondo/doc/analysis.md` does not exist; the latter is its
actual location. Read AGENTS.md §§0–5 and 7 and matching data-oriented-clojure,
repl, clojure-testing, data-modeling and datahike skills.

Read the owning function construction in `src/seon/fn.clj:317–607`,
`runtime-analysis-batch`, `analysis-rows-by-file`, `source-rows` (S1,
`6312fcef0`), artifact/manifest construction and reach queries. Read the
kondo implementation-body seam (`impl/analyzer.clj:2380–2487`) and HOF
recognition (`:3480–3510`). Kondo already emits protocol implementation
spans (`impl/analysis.clj:192`) and defmethod target markers (`:32–39`);
instance invocations (`:216`) have no resolved first-party target.
No dependency pin changes. `seon.program/canonical-row` owns row attribute
selection; `seon.fn/reconcile-tx` and `seon.db/transact!` own fixture writes.

## Baseline on default

Fresh JVM query reproduced the research's numbers: 5,019 function identities,
409 with no incoming edge, 312 public functions with no reaching test,
8 capability handlers with zero reach, 28 seon.print functions with zero
reach. Public-function difference: 286.34575 ms. Single gate-set samples:
`seon.id/digest` 495 tests / 9.026042 ms; `seon.print/emit` 460 / 5.80875 ms;
`seon.fs.jvm/read` 0 / 0.650458 ms; `seon.db/q` 1,058 / 19.311625 ms.

## Slice 1 — implementation attribution

Kondo analysis now requests protocol implementations and retains defmethod
and dispatch-value facts. Span containment attributes body usages to the
protocol method or multimethod identity. An unannotated defmulti is admitted
from its `defined-by->lint-as` declaration even when kondo provides no
arglists; its empty arglist sequence asserts no arity.

REPL-first probe: protocol body target attributed to `fidelity.probe/pm`,
multimethod body target to `fidelity.probe/dispatch`; both produce call edges.
Fixture: `test/fixtures/call_graph_fidelity/implementations.txt`.
Regression: `seon.fn-test/implementation-bodies-contribute-edges-and-test-reach`.
The proposed definitions were evaluated in default before editing source.
The regression used `seon.test/run` with its own loader, explicit connection,
`seon.test.runner/provenance`, and `:seon.test/remaining-ms 180000` in a future.
First result: **4 pass / 0 fail / 1 error**, the error reporting instrumentation
drift during concurrent adoption (20 added wrappers, including our analyzer
and operator functions). This is not claimed green. Adoption is queued behind
other lanes at the operator lifecycle lock. No foreign session was operated.

## Boundary

In-process results are iteration evidence only. The orchestrator must read
all diffs and the fixtures before requesting its batched cold gate.

## Slice 2 — declared function values

The regression `declared-function-values-contribute-edges-without-arities`
ran in default with the same three-argument run form: **12 pass / 0 fail /
0 error**, covering capability, graph Var, declared renderer and task function
values. It asserts both edges and reach and rejects invented call arities.
Kondo does not emit a usage for `var` itself; its parser's `:var` node owns
Var-quote span recognition. This was probed before implementation.

`declared-function-targets` classifies values through schema aliases and scalar
symbol/identity declarations, joining consumers via existing keyword facts.
`:seon.fn/reference-to` describes a reference's target identity, accretively;
capability and schedule function refs declare it. Stored task refs contribute
edges at query time through this property and attribute-consumer facts, so a
new task does not require reindexing source. Whole-graph reach and gate-set
share that relation. The artifact regression also covers literal task values.
No property-name roster or regular expression was added.

A live build-artifact of `src/seon/print.cljc` returns 81 rows and now includes
`emit` → `emit-sequential`, `emit-map-like`, protocol methods and other body
callees. This is a JVM owner probe, not an adopted-database measurement.

## Slice 3 — reference facts and conservative selection

`:seon.fn/references` stores known targets with no resolved call shape.
Kondo's quoted-symbol analysis supplies requiring-resolve literals; source
spans supply their referencing definitions. apply/partial/comp and syntax
quotes use the same rule. File-level `:seon.fn/unresolved-references` retains
targets with no attributable callable definition and widens selection.
Gate-set and the whole-graph Datalog relation follow reference edges;
manifest selection follows the same facts. Missing identity / unattributed
reference selection widens instead of claiming an empty set is sufficient.
The existing result-reach recorder and destructive-call-path follow refs too;
those consumers were read before editing, are not protected paths, and are
included in this slice so the new relation cannot silently disappear there.

REPL probe before source edit recovered apply and requiring-resolve targets
without target arities. Fixture:
`test/fixtures/call_graph_fidelity/references.txt`; regression:
`seon.fn-test/unresolved-call-shapes-preserve-reference-edges-and-reach`.
Post-adoption result pending. Schema changes are new keys and optional entries;
no existing stored key changes meaning. **No RESET NEEDED for this slice.**

## Verification follow-up

The first reference regression returned 5 pass / 10 fail / 0 error against
pre-adoption definitions. Its recorded reach was explicitly unknown because
`:seon.fn/references` was not installed in that database. A follow-up probe
confirmed the loaded analyzer config still lacked `:symbols`, while disk had
it: the queued publication had captured the earlier slice. This result is
retained as a failed iteration, not attributed to another lane or called green.
Consumers of the uninstalled keys were returned to their pre-adoption live
forms until adoption completes; checked-in changes remain intact.

Review found that literal declaration targets must be scoped to the file
whose consumers see them. Full-manifest and single-file indexing now share
that rule; schema declarations remain supplied operation-wide. This prevents
full builds acquiring unrelated source-file literals that incremental builds
cannot see. The same existing row owner implements both paths.

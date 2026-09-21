---
type: issue
status: open
severity: blocker
created: 2026-09-17
tags: [issue, testing, program-graph]
---

# Call-graph fidelity selection awaits adopted proof

The measured graph omitted implementation-body calls, declared handlers and
references with no known arity. The baseline was 409 identities with no incoming
edge, 312 public functions with no reaching test, eight zero-reach capability
handlers and 28 zero-reach seon.print functions.

The implementation and exact verification boundary are in
[the landing note](../../prds/steward-platform/research/call-graph-fidelity-fix-2026-09-17.md).
The authority is [the measured Option B](../../prds/steward-platform/research/call-graph-fidelity-2026-09-17.md).

Acceptance: implementation and declared-value edge/reach regressions,
reference widening regressions, and S1 indexed/evaluated parity pass after
adoption; the orchestrator reviews the diffs before its batched gate. Re-measure
the same baseline queries and record cost. Absence of a call fact is unknown,
not a declaration of no coverage.

Follow-up: implementation commits `15a35c2a7`, `af800d1a0`, `7eeed900d`,
`7907afc7a`, `b13705fc7` are present. Verification remains unknown: default's
loaded test owner refuses runs for missing `:seon.fn/destroys` declarations;
publication reached a writer refusal on `my.agent/identity`'s keyword set.
The exact refusal and correct artifact probe are in the landing note. This
note remains open until adopted fixture/parity proof and the reviewed gate.

## 2026-09-21: stored calls include non-lexical keyword joins

A read-only shortest-path query on live `default`, database basis 536870940,
completed in 840 ms and returned this path using only `:seon.fn/calls` datoms:

```text
seon.db-test/supplied-database-pulls-carry-the-projection-across-unbound-reads
→ seon.db/pull-many
→ seon.db/transact!
→ seon.db/transact-call
→ seon.db/write-report-validator
→ seon.db/write-report-error
→ seon.db/write-render-target-error
→ seon.render.test/render-html
```

The two surprising edges are not lexical calls. A second read-only query at
basis 536870941 completed in 42 ms and checked qualified identities, stored
source, exact half-open UTF-8 file spans, and the call datoms together:

| caller → target | entity | span in `src/seon/db.clj` | edge assertion transaction | stored source equals current span |
|---|---:|---|---:|---|
| `seon.db/pull-many` → `seon.db/transact!` | 3033 | `[115021 117573]` | 536870917 | true |
| `seon.db/write-render-target-error` → `seon.render.test/render-html` | 3119 | `[183353 185111]` | 536870917 | true |

Both rows' analyzed source digest and their file digest were
`8bece1402f2855310a3b586f434f8c19be207cbbccf2c85cf1069e56081ceafc`.
`pull-many` (`src/seon/db.clj:2464`) contains no `transact!` call;
`write-render-target-error` (`src/seon/db.clj:3904`) checks renderer identity
existence and contains no call to the test renderer. Stale declaration source
does not explain either edge. This is stored static evidence, not recorded
runtime reach, and does not quantify how much total selection would shrink
after repair.

The source mechanism is distinct from the analyzer's lexical caller attribution:

- `seon.fn.analyzer/attributed-usages` (`src/seon/fn/analyzer.clj:355`)
  assigns implementation spans; `seon.fn/call-targets-by-caller`
  (`src/seon/fn.clj:387`) groups resolved usage targets by their `from-var`.
- `seon.fn/function-value-schema?` (`src/seon/fn.clj:547`) also classifies
  any symbol/qualified-symbol schema as a function-valued target candidate.
  `declared-function-targets` (`:561`) collects matching literal values from
  maps in the file and selected schema forms.
- `declared-calls-by-caller` (`:590`) joins these targets to every declaration
  mentioning the corresponding keyword. `analysis-rows-by-file` (`:1111`)
  merges the resulting sets into lexical calls before `var-row` stores
  `:seon.fn/calls`. The stored relation loses that distinction.

For the first edge, `pull-many` mentions `:seon.error/operation`, while the same
file contains maps naming `'seon.db/transact!` under that key (`:487`, `:3326`,
`:4422`). Reading an error's operation does not call its named operation.
For the second, the checker mentions `:seon.render/html`; schema forms carry
`seon.render.test/render-html` under that property
(`resources/seon/schemas/seon.test.edn:61`, `:86`). Checking whether a renderer
exists does not execute it. Thus even a calls-only selection is not a
lexical-only baseline. Fresh analyzer execution was outside this read-only
assignment; the exact raw usage records were not re-measured.

The source/edge comparison is reproducible in one read-only JVM form (use a
current explicit connection; historical transaction IDs above are observations,
not stable selectors):

```clojure
(let [database (seon.db/db (seon.operator/connection "default"))
      bytes (java.nio.file.Files/readAllBytes
             (.toPath (clojure.java.io/file "src/seon/db.clj")))]
  {:basis (seon.db/basis-t database)
   :rows
   (mapv
    (fn [[caller target]]
      (let [row (seon.db/pull database
                  [:seon.fn/sym :seon.fn/source :seon.fn/form-span
                   :seon.program/analyzed-source-digest]
                  [:seon.fn/sym caller])
            [start end] (:seon.fn/form-span row)]
        {:row row
         :matches-current
         (= (:seon.fn/source row)
            (String. bytes (int start) (int (- end start))
                     java.nio.charset.StandardCharsets/UTF_8))
         :edge
         (seon.db/q '[:find ?e ?t :in $ ?caller ?target
                     :where [?e :seon.fn/sym ?caller]
                            [?e :seon.fn/calls ?target ?t]]
                   database caller target)}))
    '[[seon.db/pull-many seon.db/transact!]
      [seon.db/write-render-target-error seon.render.test/render-html]])})
```

**Acceptance addition:** B1 must establish graph fidelity before B4 uses
selection size to judge static versus observed reach. A canonical real-analyzer
regression must distinguish actual calls and declared executable consumers from
mere symbol-bearing diagnostics or renderer-existence checks, preserve required
protocol/handler dependencies, and assert absent synthetic edges plus the wanted
gate set. Reconcile and adopt the repaired graph, re-run these path queries, then
repeat the B4 counts with relation provenance. Past observed reach remains
advisory until separately proven complete for safe selection; these static
false connections neither establish nor falsify that completeness.

## Bounded repair decision, 2026-09-21

No production change has been made for this follow-up. The existing
`declared-function-values-contribute-edges-without-arities` regression
(`test/seon/fn_test.clj:2749`) expects calls from fixture `render-owner` and
`task-owner`, although those functions only return symbol-valued attributes
(`test/fixtures/call_graph_fidelity/declarations.txt`). Its expectation must
distinguish dependency from invocation before it can prove call fidelity.

Kondo's usage output carries resolved target, `from-var`, and optional arity
(`reference-code/clj-kondo/src/clj_kondo/impl/analysis.clj:37`, `:42`);
Seon's normalized usage retains these (`src/seon/fn/analyzer.clj:129`). These
facts do not establish the flow of a configured symbol through resolution into
a later call. Actual examples are `seon.render/invoke-selected`
(`src/seon/render.clj:1011`), scheduled handler resolution/invocation
(`src/seon/schedule.clj:685`), and `seon.effect/request*`
(`src/seon/effect.clj:788`, `:842`, `:1004`). Capability owners already declare
their handler; that owned edge must remain.

Three concrete scopes were returned to the orchestrator before source edits:

1. **Recommended smallest constraint:** move synthetic keyword-to-declaration
   dependencies from `:seon.fn/calls` into the existing `:seon.fn/references`
   relation, retaining the conservative union for gate selection. Estimated
   40–90 changed lines in `src/seon/fn.clj` and focused `test/seon/fn_test.clj`
   assertions, plus this note. This corrects the meaning of calls without
   dropping selected tests. It does **not** reduce saturation; consumers that
   follow calls alone also require an audit before claiming broader safety.
2. **Explicit invocation declarations:** declare which function-valued
   attributes an actual dispatch owner invokes, then join candidate targets to
   those owners instead of every keyword reader. Estimated 150–300 changed
   lines across `src/seon/fn.clj`, `resources/seon/schemas/seon.fn.edn`, the
   render/schedule/effect owners above, and focused analyzer/selection fixtures
   and tests. This needs one coordinated schema/source cut. It preserves
   conservative configured dispatch while excluding diagnostic readers;
   undeclared dynamic sites remain explicit unknowns. Shared real dispatch may
   still select many tests. The fact's exact schema is a design decision, not
   an implemented or established vocabulary item.
3. **Infer dataflow:** extend dependency analysis through local values,
   resolution, and invocation using kondo's facts. Estimated 400+ lines across
   `src/seon/fn/analyzer.clj`, `src/seon/fn.clj`, provenance schema, and fixtures;
   hours and multiple cuts rather than one short repair. Only modeled forms
   gain a guarantee; arbitrary higher-order/configured flow remains unknown.
   This is not recommended for the concentrated baseline repair.

Existing `:seon.test/subject` can add explicit tests for a declaration without a
new fact family. It cannot safely subtract tests reached through other static
dependencies: a declared subject is not a completeness assertion about
everything a test can execute. Using subjects as an exclusive gate would
silently turn missing declarations into passing selection. Precision therefore
requires either the explicit dispatch cut or an equally complete substitute,
not merely changing the test selector to ignore unexplained edges.

## Approved explicit-invocation cut, awaiting verification

The owner selected option 2. `:seon.fn/invokes` is now an optional set of
qualified attribute names on the existing function row, declared through the
existing function metadata seam. It asserts invocation, not a target registry
or an inference from symbol shape. Source indexing joins only these declared
invokers to literal candidate targets in the supplied schema forms and source
file. Database selection joins explicit invokers to current symbol-valued or
reference-valued targets. Capability-owner edges remain, as do ordinary calls,
references, implementation attribution, and file-scoped uncertainty.

The concrete converted population is eight keys at six invoking functions:

| Invoking function | Declared keys |
|---|---|
| `seon.render/invoke-selected` | `:seon.render/ai`, `:seon.render/html`, `:seon.render/form` |
| `seon.schedule/invoke-handler` | `:seon.schedule.task/function` |
| `seon.effect/request*` | `:seon.effect/capability` |
| `seon.maintenance/result-entity` | `:seon.maintenance/result-projection` |
| `seon.schema/identity-only-projection-in` | `:seon.schema/identity-projection` |
| `seon.web.jvm/search` | `:seon.config.web/search-result-projection` |

Form renderers are called through `invoke-producer` → `invoked` →
`invoke-selected`; their returned form is data, but the renderer itself is
invoked. Merely checking a renderer's existence does not take this path.
The generic effect invoker conservatively reaches configured handlers even
when a particular test exercises only one capability; no claim of minimal
selection follows from removing false connections.

The existing real-analyzer declaration fixture now actually resolves and calls
its declared renderer/projection targets. It also has diagnostic readers, a
leaf, and a shared dependency. Within that fixture namespace the required sets
are: `leaf` → `reaches-leaf`; `shared` → `reaches-leaf` and `reaches-shared`;
`render-target` → `reaches-declarations`, excluding `reads-diagnostics`.
These are exact fixture requirements, not production averages. A second
regression uses `source-rows` and canonical exact replacement to check that
invocation metadata survives agent admission and disappears when omitted on
redefinition. Present nil/false/scalar/unqualified metadata must refuse.

The database edge query uses the same nonrecursive declaration rules as the
Datalog consumer. Invocation branches bind the small `:seon.fn/invokes`
population and its attribute before reading candidate values, then resolve
bound target identities. This removes duplicated relation implementations;
it is not a measured latency claim. Root must measure the shared `gate-set`
query after adoption.

The inventory was a source review of the previous scalar key-driven mechanism,
not a proof of arbitrary higher-order flow. Composite `:seon.ai/wire` coercion
tuples, Malli predicate/generator forms, generic SCI/private invocation, and
arbitrary test resolution were not made complete by the old scalar joins and
are not newly certified here. `:seon.program/written-by` describes authority;
`:seon.sci.binding/target` registers a binding. Their mere readers are not
invokers. Existing lexical/reference and file-uncertainty edges remain; no new
claim that absent dynamic edges imply complete coverage is made. Unchanged-run
execution was not separately exercised in this lane.

After full reindex and adoption, this compact read-only form verifies the two
previously false call facts and the new dispatch declarations:

```clojure
(let [database (seon.db/db (seon.operator/connection "default"))]
  {:basis (seon.db/basis-t database)
   :phantom-calls
   (seon.db/q '[:find ?caller ?target
               :in $ [[?caller ?target]]
               :where [?e :seon.fn/sym ?caller]
                      [?e :seon.fn/calls ?target]]
             database
             '[[seon.db/pull-many seon.db/transact!]
               [seon.db/write-render-target-error seon.render.test/render-html]])
   :invokers
   (seon.db/q '[:find ?caller ?attribute
               :where [?e :seon.fn/sym ?caller]
                      [?e :seon.fn/invokes ?attribute]] database)
   :render-target-retained
   (seon.db/q '[:find ?e
               :where [?e :seon.fn/sym seon.render/invoke-selected]
                      [?e :seon.fn/calls seon.render.test/render-html]] database)})
```

Expected: no phantom call rows; the six owners/eight keys above present;
the real renderer target retained. No test, JVM, publication, or adoption was
run by this implementation lane. The issue remains open pending root's one
combined canonical checkpoint, fresh graph queries, and selection measurements.

The combined checkpoint `fresh-start-combined-gate-repaired.log` found two
test observers still matching the retired literal declared-reference query.
The refusal-injection and once-per-operation query-count assertions now match
the shared `(declared-edge ?caller ?target)` rule invocation. They still wrap
the real database query, preserve thread scoping, and retain all refusal and
indexed-read assertions. Four real-analyzer fixtures also now carry the
canonical projection into reconciliation; that repair is tracked in
`program-graph-tests-do-not-carry-their-current-contract-projection.md`.
These fixture changes have not yet been run by the owner.

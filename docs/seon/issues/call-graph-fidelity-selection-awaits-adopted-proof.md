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

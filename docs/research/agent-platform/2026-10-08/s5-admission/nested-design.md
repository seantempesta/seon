# S5-03e: nested and cross-namespace definitions (design proof, notes only)

Question: can the one end-of-pass analysis (S5-04) admit a definition that is nested in a larger form, or made in
another namespace than the form's, without a second analysis of the same declaration? Proof for the later
implementation: `seon.cluster.turn-test/a-pass-joins-nested-and-cross-namespace-definitions-once` (not written here).

Probed at HEAD cd8a481c2 on agent branch `s5-03e` (SCI, scratch namespaces) plus read-only JVM measurements against
default's database. Default `bin/seon status` is OK but its cluster state is `:faulted` (open fault
`seon.turn.world/record-fault`, "This write names branch :agent-1048bc748e6e, which is not the writing cluster's
branch"). That is outside this slice and already on the health list; recorded, not filed anew.

## What exists today (observed entity per form, then pulled by `[:seon.fn/sym s]`)

| Form (one eval each) | Program entity | Source stored | Path in `seon.turn.batch/write-entity` |
|---|---|---|---|
| `(do (defn no-capture [x] (inc x)) :ok)` | yes, public | `(defn no-capture [x] (inc x))` exactly, not the `do` | nested? true, `captured-locals` empty, row from the form's analysis |
| `(let [n 3] (defn nc [x] (inc x)))` | yes | the `defn` text only | same |
| `(let [n 3] (defn with-capture [x] (+ x n)))` | none | none | `captured-locals` = #{n}, private `:captures-locals` |
| `(let [inc dec] (defn shadow [x] (inc x)))` | none | none | a local shadowing a global counts as captured |
| `(if true (defn br [] 1) (defn br [] 2))` | yes | the executed branch `(defn br [] 1)` | final root write's origin span joins exactly one definition |
| `(let [f (fn [] 1)] (def ^:private nest-p f) (defn uses-p [] (nest-p)))` | `uses-p` only | `uses-p` text | `nest-p` captures `f`; `uses-p` is admitted although it calls a Var with no program entity |
| `(defmacro defpair ..)` then `(defpair pa pb)` | `pa` and `pb`, both public | both store `(defpair pa pb)` (the owning call) | macro-generated: join by owning call span plus `defined-by` |
| `(do (in-ns 'scratch.other) (defn z1 [] 1))` | yes, `scratch.other/z1` | `(defn z1 [] 1)` | join sees the definition in the authoring namespace, so `:other-namespace`; the definition text is sliced from the analysis and analyzed a second time in the target namespace (`write-entity`, the `analyze-form` call on `definition-source`) |
| same into existing `seon.id`: `zz-probe` | yes, ns `seon.id` | exact | same second analysis |
| `(binding [*ns* ..] (eval '(defn third-a ..)))`, `(intern 'ns 'x (fn ..))` | none | none | no origin: `join :rebound`, private, as designed (not a gap in this slice) |

So classification (admitted / private), exact source slice and namespace are already right in both cases. The
only repeated analysis is the cross-namespace second `analyze-form`, plus one extra clj-kondo run per nested
definition inside `captured-locals`.

## Costs measured (JVM, default's database as read-only context, median of 7, warm)

| Work | ms | MB allocated |
|---|---|---|
| `runtime-analysis-batch` of one small `defn`, authoring-style namespace (`seon.id`) | 17 | 8 |
| same in `seon.turn.batch` | 23 | 18 |
| same in `seon.db` | 50 | 51 |
| two such calls, one per namespace (`seon.id`, `seon.db`) | 66 | 60 |
| one call holding both namespaces as two requests | 52 | 59 |
| `captured-locals` (own clj-kondo run, per nested definition) | 7 | 3.8 |
| clj-kondo on 4500 chars, locals analysis off vs on | 11 vs 11 | 9 vs 9 |

Findings: (1) analysis cost is dominated by the target namespace's program context, not the definition: a cross-ns
definition costs 17-50 ms extra today, for any size of definition. (2) Batching the two namespaces into one call
saves only the fixed kondo start (about 14 ms of 66), not the per-namespace context; "once" means one call per
pass, not zero extra namespace work. (3) Locals analysis in the same kondo run costs nothing measurable, so the
per-nested-definition run can disappear. (A cross-ns call in a namespace with no program entities, `scratch.other`,
took 65 ms and 78 MB in a first cold-ish sample; treat as a cold outlier, not a rate.)

## The smallest seam (answers Q7 for this case)

No analysis is needed before a later form: classification of a nested or cross-ns definition depends only on data
available at write time plus the end analysis. Required captured observation, per definition bind write, is already
on the write: `:seon.batch/sym` (qualified, so the target namespace), `:seon.batch/origin` (complete span in batch
coordinates, `:expanded-by`), `:seon.batch/var`. Two additions inside the existing S5-04 batch shape:

1. The pass analysis request list gets, for each final root write whose `(namespace sym)` differs from the form's
   `:seon.batch/ns-before`, one extra request `{:namespace-name target :form-source <slice of the entry source at
   origin>}` placed in the SAME `runtime-analysis-batch` call (the function already groups consecutive requests per
   namespace and returns per-request spans). The slice comes from the entry source and origin, so no analysis
   result is needed to build it, which is what forces today's second pass. `write-entity` then reads the row from
   that request's coordinates like any local definition and the `analyze-form` call in the `:other-namespace`
   branch is deleted.
2. `captured-locals` becomes a read of the same analysis: add `:locals true` to `seon.fn.analyzer/analysis-config`
   and compute captured as local-usages inside the definition's span whose binding id lies outside it, over the
   form's own request span. This deletes `captured-locals`'s separate kondo run. To be confirmed in
   implementation: the analyzer result keeps `:locals`/`:local-usages` (it merges `raw-analysis`), and the
   `:analysis` config key is contract-checked.

Neither adds a registry, atom or evaluator. Nested and cross-ns remain write-time-origin joins, so a later
redefinition of the same name in the pass is the redef case (S5-03a), and the two designs must agree that the
join key is the write's origin span, not the final Var root.

## Falsifiers and open risks

- If kondo's synthetic-source prelude for a request in an unseen target namespace (`runtime-namespace-form`) fails
  to resolve the form's own earlier definitions, the cross-ns row loses edges. Today's second analysis has the same
  limit, so this is not a regression, but the regression test should assert edges from a cross-ns definition to a
  same-namespace callee.
- `uses-p` above is admitted with an edge to `nest-p`, which has no entity. Whether that edge dangles or is dropped
  was not inspected; the implementation test should assert it (dependency edges are named in the test row).
- Expected win is small and bounded: 17-50 ms and 8-51 MB per cross-ns definition per pass, 7 ms and 4 MB per
  nested definition. It is rare input. If the implementation exceeds about 60 lines for this case, defer it: the
  current behavior is correct.
- Not measured: whole-pass wall time with and without the change; per-form `:ms` in the reply excludes derivation,
  so the numbers above are direct owner timings, not pass clocks.

## Design check

The plan's premise (a single pass analysis can serve every case) holds for this case without a new early
admission seam. The awkward part is that today's join output is only readable after analysis, which is why the
cross-ns path analyzes twice; moving the slice to the write's origin span removes the dependency. No design
decision beyond the plan was taken; Q7 owner approval still gates S5-04.

Probe state: branch agent `s5-03e` holds scratch definitions in `scratch.nested`, `scratch.other`, `scratch.third`
and `seon.id/zz-probe`; none reached default. No RESET NEEDED.

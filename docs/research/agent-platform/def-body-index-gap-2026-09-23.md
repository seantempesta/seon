---
type: research
status: proposed design; independent review and implementation owed
created: 2026-09-23
owner: B1
---

# Definition initializer dependency gap

Kondo already supplies the resolved var and its owning definition. Preserve that data
through declaration-row construction and let the existing dependency walk use it;
do not build another call analyzer or infer runtime callability from syntax.

## Finding and evidence boundary

**The defect is declaration eligibility, not missing kondo attribution.** Nonliteral
`def`/`defonce` values without analyzed arglists usually receive no Seon definition
row. Their outgoing calls are excluded, and their references are retained only as
file-level unresolved references. That fallback connects to tests in the *same file*,
not tests elsewhere that use the omitted var. Therefore it does not close the gap.
The blanket claim that all `def` bodies are missed is too broad: literal defs and
arglist-bearing defs already qualify. Nor is `memoize` a special missing analyzer case.

Assignment extends [cut-l1 KEEP](../../prds/agent-platform/landing/lane-cut-l1-2026-09-23.md)
and the existing [fix schedule](fix-schedule-2026-09-23.md), 16:06Z/16:34Z entries.
No source, schema, plan, skill or other lane's files changed. No tests, publication,
new JVM, branch mutation, reset or process lifecycle operation ran.

Checkout baseline: `8eaf26f3eb58d5bb387e7e148aff71856ebd70fa`, branch
`refactor/agent-platform`. Source line anchors below refer to the observed checkout;
`fn.clj` has another lane's changes, including four added lines before the cited
indexer functions. The same eligibility/filter/fallback logic exists in committed
HEAD. The census is of the requested live `src` tree, **not an immutable HEAD census**;
concurrent source changes are a limit. Raw analysis remains the authoritative census
input. History inspected includes `b0d047021` (signature-only caller lint),
`2bd568c08` (literal-constant facts), and `7ba203acf` (analyzer cache ownership).

`bin/seon status` found default pid **12071**, started
`2026-09-23T15:13:49.274Z`. MCP status and eval were available. Status reported
`:flow :unknown`, missing `:seon.flow/graph` and `:seon.render.web/served`, 15 error
signatures, seven errored receipts and one unknown failed-test observation. These
are degraded foreign boundaries, not health passes. Status also retained a prior
1,684 ms `seon.fs/footprint` observation; it is not a measurement of this analysis.
No workaround or recovery was attempted.

## Dependency and exact mechanism

Production uses `deps.edn:16`'s local kondo checkout,
`reference-code/clj-kondo` at **57252e07975710aa579b24f0d1b2b1e04195caa2**.
Its upstream base is `794a508d` (2026.07.24); `analyze-def`'s `:in-def` assignment
also exists there. This is upstream behavior, not one of our two metadata patches.
The requested census executable reports **clj-kondo v2026.07.24**; it is not a claim
that the system executable contains the maintained fork's patches.

In `reference-code/clj-kondo/src/clj_kondo/impl/analyzer.clj`:

- `analyze-def`, **1852**, sets `:in-def var-name` at **1889**, analyzes the ordinary
  def initializer at **1902**, and analyzes other defining forms' children at **1959**.
- Dispatch sends `def`, `defonce` and `defmulti` to that same function at **3388**.
- The emitted call retains `:in-def` at **3843**; symbol usages likewise carry it
  at **2886**. An anonymous function inside an initializer inherits that owner.
- Definition arities/arglists come from initializer analysis or explicit metadata
  at **1947–1956**. Owning a nested function does not establish that the outer
  initializer result has those arities.

`reference-code/clj-kondo/src/clj_kondo/impl/analysis.clj:19`, `reg-usage!`, emits
`:from` from the call's namespace at **29**, `:arity` when supplied at **37**,
`:from-var` from `:in-def` at **40**, and resolved `:to` at **55**. An absent
`:in-def` omits `:from-var`; it does not omit the usage.

For forms in namespace `example`, with F resolving to `example/F`:

| Shape | Usage for F | Ownership meaning |
|---|---|---|
| `(def x (F))` | `:from example :from-var x :to example :name F :arity 0` | initializer call owned by x |
| `(defonce x (delay (F)))` | same owner x and arity 0 | nested delayed body still belongs to x |
| `(def g (memoize (fn [] (F))))` | owner g and arity 0 | memoize and fn nesting do not erase the owner |
| `(def g (memoize F))` | owner g, **no `:arity`** for F | F is a callable reference, not a direct F invocation |
| `(def m {:k (fn [] (F))})` | owner m and arity 0 | map-held callback belongs to m; m's callability is not inferred |
| `(F)` outside any defining form | `:from example`, **no `:from-var`**, arity 0 | namespace/file initialization usage |

These shape statements follow the dependency source; a separate synthetic lint or
execution was not run. The `def`/`defonce` head's own usage is analyzed in the outer
context and can lack `:from-var`; metadata and implementation forms can also lack it.
Thus “no from-var” is not synonymous with an unattributed executable top-level call.

Seon's path is:

1. `src/seon/fn/analyzer.clj:130` (`var-usage`) preserves both owner fields and
   arity. `attributed-usages`, **444–483**, additionally repairs method/protocol
   ownership from spans and fills missing owners from definition spans. `analyze`,
   **606–620**, applies it and removes CLJS-only entries. No new def span walk is needed.
2. `src/seon/fn.clj:330`, `function-definition?`, accepts only literal defs,
   nonempty arglists, or `defmulti`. `first-party-function-symbols`, **346**, uses
   that predicate. `var-row`, **663**, uses it again and returns nil at **734** for
   other definitions. Literal classification is already separate in
   `analyzer.clj:485–519`, installed at **597–604**.
3. `usage-caller`, `fn.clj:370`, correctly constructs the owner symbol.
   `call-targets-by-caller`, **406–421**, then requires that owner in the eligible
   set. This is where the outgoing call is lost. `call-target`, **376**, preserves
   the distinction between ordinary arity-bearing calls, macros and references.
4. `references-by-caller`, **382–400**, collects usages of an unrecognized caller
   under nil, even arity-bearing usages. `analysis-rows-by-file`, **1244–1273**,
   temporarily attaches them to the namespace row. `artifact`, **1411–1421**,
   hoists them onto the declared file row as `:seon.fn/unresolved-references`.
5. `gate-sets-in`, **1570–1573**, turns those file references into edges **only for
   tests whose `:seon.fn/file` is that file**. `test-reach-rules`, **1488–1492**,
   repeats the restriction. A test in a separate test file cannot traverse the
   missing owner. `src/seon/test.clj:576` (`select`) calls `gate-sets` at **719/727**;
   changing selection alone cannot reconstruct the missing declaration.

`:seon.fn/invokes` is different: it is explicit metadata read by
`invocation-attributes`, `fn.clj:591`, stored by `var-row:674`, and joined by
`declared-reference-rules:1470–1476`. It is not inferred from kondo calls, and must
not be used to patch this gap with manually declared edges.

## One census, with definitions of the counts

Executed once, output preserved at `tmp/astra-def-body-index/analysis.edn`:

```sh
clj-kondo --lint src --config '{:output {:format :edn} :analysis {:var-usages true :var-definitions true}}'
```

The repository config merges into this request (it enables arglists). Kondo reported
104 files, zero errors, 494 warnings and seven infos; exit **2** is the warning
exit, not a successful clean-lint claim. Wall **3.34 s**, user 2.97 s, system 0.27 s,
maximum RSS **407,961,600 bytes**; kondo's own duration was 2,090 ms.
This exceeds one second because the explicitly requested census analyzes all src,
not a changed input. **Do not use this cost or operation as an incremental design;
B1 must keep the production operation proportional to changed analysis.** No armed
Seon function ran in this native CLI operation.

The disposable native Babashka reduction is `tmp/astra-def-body-index/count.clj`;
results and time/RSS are `counts.edn`, `counts.stdout`, `counts.stderr`.
It reads the saved EDN, uses rewrite-clj top-level source spans, and joins each
usage's start position to its enclosing top-level list. “Def body” below includes
the head/metadata usages inside a list headed `def`, `defonce` or their qualified
core spellings; it does not count a nested def under an unrelated top-level form.
The JVM column discards `:lang :cljs`, matching Seon's language filter. Rows are
usage records, not deduplicated positions. The union does not double-count overlap.

| Metric | Raw, both language arms | JVM only |
|---|---:|---:|
| var definitions | 4,103 | 3,842 |
| var usages | 65,606 | 61,394 |
| top-level def/defonce usages | 1,338 | **1,203** |
| usages without `:from-var` | 4,823 | **4,376** |
| intersection | 388 | 369 |
| union of those two categories | 5,773 | **5,210** |
| nonmacro, arity-bearing usages in union | 967 | 804 |
| distinct first-party function targets in union | 124 | 123 |
| first-party function targets used **only** in union | **76** | **76** |
| targets with direct calls only in union (ignoring references) | 29 | 29 |

For the target counts, “function” means a kondo definition with nonempty arglists
or `defmulti`; qualified namespace/name identifies it. “Only” is the set difference
between targets used in the category and targets used anywhere outside it in this
**src-only** analysis. It says nothing about direct references in test/script or
resource data. There are 370 def/defonce definition records, 369 without arglists;
that is **not** 369 missing rows because Seon also admits literal defs.

**76 is exposure in raw analysis, not 76 proven broken selections.** In particular,
method/protocol bodies among the no-from-var usages receive Seon's span repair.
The 29 call-only count can include functions referenced elsewhere; the stronger
76 count includes references and is the appropriate answer to “only through such
usages.” All four reported helpers are in that 76 set. Complete symbol lists are
in `counts.edn`; they include registry predicates, print protocol implementations
and namespace initialization functions as well as the four helpers.

Reduction wall **2.77 s**, RSS **409,223,168 bytes**: disposable whole-output EDN
read plus source-span classification and two censuses. Two earlier draft reductions
failed on delimiters (1.88 s and 2.48 s); they reran no lint and produced no counts.
This offline script is not a proposed production parser, cache or measurement API.
No operation exceeded ten seconds.

Evidence SHA-256:

```text
analysis.edn  13acfed346ccc0fabdb11dfc072868d3fc9dfee3761f1554e8311622ef97dfa3
count.clj     abc032dcce702f31b0457676b225acf8d72b065674caebdf2f3eb4ac0111a1fe
```

## Live stored comparison

MCP `eval_clj`, root `/Users/sean/src/seon`, cluster `default`, JVM,
`read_only true`, timeout 2,000 ms; **391 ms** envelope, **384.989 ms** query body:

```clojure
(let [db @(seon.cluster.boot/connection "default")
      symbols '[seon.fn.analyzer/manifest-roots seon.sci.kernel/new-guard
                seon.config/settings-projection seon.print/distinct-generated-nodes]
      started (System/nanoTime)]
  {:rows
   (mapv
    (fn [s]
      {:symbol s
       :incoming
       (seon.db/q
        '[:find ?caller ?a :in $ ?s
          :where [?e ?a ?s]
          [(contains? #{:seon.fn/calls :seon.fn/references} ?a)]
          [?e :seon.fn/sym ?caller]] db s)
       :files
       (seon.db/q
        '[:find ?path :in $ ?s
          :where [?e :seon.fn/unresolved-references ?s]
          [?e :seon.fn.file/relative-path ?path]] db s)})
    symbols)
   :elapsed-ms (/ (- (System/nanoTime) started) 1e6)})
```

| Target | Raw owner / arity | Stored named incoming | Stored unresolved file |
|---|---|---|---|
| manifest-roots | roots-of / absent (reference), analyzer.clj:221 | empty | src/seon/fn/analyzer.clj |
| new-guard | process-guard / 0, kernel.clj:96 | empty | src/seon/sci/kernel.clj |
| settings-projection | defaults / 0, config.clj:556 | empty | src/seon/config.clj |
| distinct-generated-nodes | node-generator / 2, print.cljc:179/183/187 | empty | src/seon/print.cljc |

The live query checks fn-owned incoming edges, not test-owned incoming edges, and
proves stored fallback presence, not that this dirty source has been adopted.
No `gate-sets`/test execution probe ran; cross-file exclusion follows the cited join.

## Proposed smallest complete repair

**Cost first:** retain resolved owner/target data in a single reduction over the
changed files' definitions and usages, **O(D_changed + U_changed)** time and
**O(D_changed + E_changed)** retained data. Use the existing publication cache and
exact replacement transaction. No full-program lint, runtime var scan, per-call
analysis, second graph or cache. Selection remains proportional to its existing
reachable dependency walk; an explicitly unknown result can require all tests.

1. **Keep named declaration owners.** Extend the existing definition-row admission
   in `function-definition?`/`var-row` and the eligible-caller set to retain
   `clojure.core/def` and `clojure.core/defonce` definitions, including nonliteral
   ones. Use the existing `:seon.fn/sym` declaration identity, source, defining-form
   fact and digest; literal data already use this identity today. Do not construct
   a synthetic function name or an anonymous-function node. Both `g → F` and
   `test → g` then survive. For `(memoize F)`, retain `g references F`, not an
   invented arity or a false lexical call. A map or delayed value also owns its
   initializer/nested-body dependencies without becoming a function.
2. **Separate dependency ownership from callable proof.** Empty arglist information
   must remain unknown, not zero arity; never copy a nested lambda's arities onto
   a map or arbitrary factory result. Preserve literal-only `constant?`. This is
   a declaration graph repair, not permission to execute initializers to discover
   their values. Until B2 proves context-local reconstruction of a nonliteral
   initializer (especially `defonce`, delays, captured callbacks and memoized state),
   treat its reconstruction as host-bound/unknown and refuse an affected override
   by name. Selection can include its tests without claiming isolated execution.
   Review existing acquisition/arming consumers before broadening row eligibility;
   changing only the predicate and calling the repair done is insufficient.
3. **Keep one fallback owner for genuinely ownerless usages.** The existing file
   row is already the declared namespace-source owner (`namespace-row:302` links
   namespace to file). Reuse `:seon.fn/unresolved-references`; do not add a fake
   namespace function or duplicate namespace-level registry. If a changed target
   or its reverse closure reaches such a file reference without a proven bounded
   dependent set, widen to **all eligible tests in the supplied execution program**
   with the file/target reason, or return a typed coverage-unknown refusal. Same-file
   tests alone are not conservative. Targetless unknown-namespace usages cannot be
   encoded in this target-keyed attribute: unresolved analysis must refuse admission
   or carry explicit incomplete-analysis evidence that refuses narrowing. Never
   silently interpret missing identity as an empty dependency set.
4. **One producer/consumer conversion.** Apply the same row rule to file analysis
   (`analysis-rows-by-file`/`source-rows`) and agent analysis (`analyzed-form`,
   `fn.clj:947`). Convert both `gate-sets-in` and `test-reach-rules` fallback joins,
   keeping B4 selection and D1 merge on that shared owner. Exact replacement removes
   edges withdrawn by an initializer edit. Check definition retirement and the
   source/agent digest equivalence against the retained declaration rows.

The immediate smaller safety alternative is to **refuse selective coverage whenever
its reverse closure reaches the existing unresolved-file relation**, then add precise
owners. It is safe but does not satisfy the requested positive memoized-function
selection regression; it is not the completed repair. Adding four manual calls,
arglist metadata everywhere, or bespoke memoize syntax recognition is rejected:
those merely disguise the general omitted-owner defect.

This deletes the eligibility-based loss of def dependencies and the same-file-only
fallback assumption. The four manual “kept because the graph misses defs” exceptions
become ordinary dependency queries after proof; **the four live functions stay**.
The KEEP table is historical evidence, not four source workarounds to delete.
No source/test deletion saving is claimed for this correctness repair. Target is a
small change at existing owners, not hundreds of lines; price actual additions and
any callable-consumer conversion before implementation. Current note: **net src 0,
test 0**. Only this research file is committed.

## Regression and remaining risks

One canonical publication/selection regression with F, g and the test in distinct
files: `(defn F [] 1)`, `(def g (memoize (fn [] (F))))`, and a test asserting `(g)`.
Publish through the real analyzer, assert positive owner rows and `g calls F`, then
change only F and assert `seon.test/select` includes that test via `test → g → F`.
The assertion must fail if g or its edge is absent. Do not seed edges by hand and
do not use previous observed execution reach as a replacement for the graph.

Use table cases in that behavior class for `(memoize F)` (reference), `defonce` with
a delayed F, a map-held callback, and a plain def initializer. Add a separate
ownerless-init case proving explicit all-test widening or coverage refusal across
files. Verify withdrawn edges disappear on replacement and unrelated known paths
stay narrow. Map/delay cases assert dependency selection; they do not infer a call
arity for the container. Run these only during implementation on the canonical
branch fixture with real SCI and armed contracts; this diagnosis ran no tests.

Risks requiring independent review: nonliteral def reconstruction can repeat effects
or preserve stale memoized values; `defonce` is not ordinary replacement semantics;
adding rows affects B2 acquisition/host-bound checks, arming and documentation;
unknown higher-order dispatch still needs conservative treatment; source-only counts
exclude test/script/resource readers; raw no-from-var counts include repaired method
bodies. A full widened gate can be expensive: report the reason or refuse, never
hide the defect by shrinking selection. Existing rows require targeted reanalysis
of affected files and positive adoption evidence; a new analyzer alone does not
repair stored facts. The observed initializer exposure is not proof of the final
regression, graph completeness, isolated execution, or browser behavior.

## Exact paragraph for B1 after independent review

Append to **“Graph fidelity before selective test execution”**, after its existing
paragraph on unresolved dispatch and before §6:

> Preserve dependency owners for every `def` and `defonce`, including nonliteral initializers and memoized or container-held functions: clj-kondo already supplies `:from`/`:from-var`, and the indexer must retain the declaration row instead of requiring inferred arglists before keeping its calls/references. Keep callable proof separate from dependency ownership; do not invent arities, mark nonliteral values constant, or replay effectful initializers to discover their shape. File and agent declaration construction use the same rule. Truly ownerless usages retain the existing declared file/namespace-source owner; reaching its unresolved dependency widens to the complete eligible test set or refuses coverage, never only that file’s tests, and targetless unresolved analysis cannot certify a narrow selection. B1 converts `gate-sets-in` and `test-reach-rules` together, with B4/D1 using that shared result, and coordinates nonliteral declaration acquisition with B2 before claiming isolated execution. Prove through canonical publication that a test in another file reaching F only through `(def g (memoize (fn [] (F))))` is selected when F changes; cover reference-valued wrappers, defonce, map-held callbacks and ownerless initialization without hand-authored edges. Work is O(changed definitions + changed usages), with existing caches and exact replacement; the manual def-body KEEP exceptions retire only after stored graph and adoption proof.

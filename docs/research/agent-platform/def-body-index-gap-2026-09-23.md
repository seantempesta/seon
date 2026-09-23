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

## Follow-up: test-first before the callee exists

Owner ruling: D1 §2e (`eb709fcb8`), `:gate` by default including test-first.
**A missing var in a known namespace is already retained as a symbolic call edge.**
The unconditional claim “the test can therefore be defined first” does not follow:
file publication blocks the unresolved call, and the shared entry must admit a
pending test declaration before attempting to compile its unresolved body.
This is an admission distinction, not a second missing-edge producer.

### Measured analysis and owner probe

At follow-up checkout `84f12b6fbb644d6fca61ac5014a7d0be4760d4d7`, analyzed this tiny
input (not a test run), using native clj-kondo 2026.07.24, `--cache false`:

```clojure
(ns future.example (:require [clojure.test :refer [deftest is]]))
(deftest qualified-test (is (= 1 (future.example/f))))
(deftest bare-test (is (= 1 (f))))
(deftest unknown-ns-test (is (= 1 (absent.example/f))))
```

Exact command, input, output and timing are under
`tmp/astra-def-body-index/test-first/` (`forms.clj`, `analysis.edn`, `time.txt`):

```sh
clj-kondo --lint - --filename future/example.clj --cache false --config '{:output {:format :edn} :analysis {:var-usages true :var-definitions true}}' < tmp/astra-def-body-index/test-first/forms.clj
```

Wall **0.01 s**, maximum RSS **38,600,704 bytes**. Findings: `:unresolved-var`
for `future.example/f`, `:unresolved-symbol` for bare `f`, and
`:unresolved-namespace` for `absent.example/f`. All three deftests have `:test true`
and `:arglist-strs ["[]"]`, so they qualify as callers.

| Target expression | Actual kondo output for f | Seon graph result |
|---|---|---|
| `(future.example/f)` | `:from future.example :from-var qualified-test :to future.example :name f :arity 0` | **kept** as `future.example/qualified-test → future.example/f` |
| `(f)` | same from namespace, owner bare-test, `:to :clj-kondo/unknown-namespace :name f :arity 0` | **dropped**, because target identity is unknown |
| `(absent.example/f)` with no namespace binding | no f var-usage; unresolved-namespace finding | **no edge to keep**; refuse the unresolved namespace |

Dependency source: `namespace.clj:793–800` retains a resolved namespace and var
name without requiring a var definition. `linters.clj:702–730` records the
unresolved-var finding **and** calls `analysis/reg-usage!`; a missing `called-fn`
does not suppress analysis. Bare unknown names use `namespace.clj:927–929`'s
unknown-namespace sentinel. An unresolved namespace takes
`analyzer.clj:3577–3584`'s finding/children path rather than emitting a resolved
call target. All are under `reference-code/clj-kondo/src/clj_kondo/impl/` at the
revision already recorded above.

Seon `usage-symbol` (`src/seon/fn.clj:355–368`) only rejects the unknown-namespace
sentinel or missing name; it does **not** require a current callee row.
`call-targets-by-caller:406–421` restricts the **caller**, not the callee, on its
two-argument path. Both `analysis-rows-by-file:1242` and `analyzed-form:955` use
that path. Test rows store its result at `var-row:644–648` and
`analyzed-form:981–984`. The optional resolvable-targets filter is not supplied by
these callers. There is no “drop unresolved-but-qualified calls” fix to add here.

A read-only JVM probe supplied the saved kondo maps to the existing pure owners
(`first-party-function-symbols`, `call-targets-by-caller`, `blocking-findings`,
`analyzed-form`), converting kondo key names to the adapter's namespaced keys.
The full exact request and tool envelope are saved in `test-first/probe.json`.
It took **11 ms**, and returned:

```clojure
;; Selected fields; other edges include clojure.core/= and clojure.test/is.
{:qualified-test-facts
 {:seon.test/sym future.example/qualified-test
  :seon.fn/calls #{clojure.core/= future.example/f}
  :seon.fn/call-arities #{[clojure.core/= 2] [future.example/f 0]}}
 :blocking [:unresolved-var :unresolved-symbol :unresolved-namespace]}
```

This probes derivation, not a persisted row or a successful declaration. The
minimal test identity input was never submitted to the writer. Default had been
replaced by pid **27531**, running archive
`33957320955a9fff65db3fa067ba3d4d40555e85`; hook publication was off. The probe
therefore exercised those loaded owners, not unadopted checkout changes. Status
reported no missing readiness layers but 16 error signatures, 34 errored receipts
and one unknown failed-test observation. No recovery was attempted. Although the
request was `read_only true`, the MCP renderer reported `windowed? true` and stored
its result blob `d2f1f31fc17fc47e1417647ded75cb9b465fb42577ba290bd61df442ca5003d2`
(6,344 bytes). That existing transport side effect is disclosed; no program/branch
mutation or test execution was requested.

### Why file publication refuses, and why the reverse read needs no new detector

`assert-clean-analysis!`, `fn.clj:1145`, runs before row construction at **2313**
and **2328**. `load-refusal-finding-types:1094–1100` includes unresolved-var.
The attempted external-target exemption (`external-usage-spans:1114`,
`admitted-external-finding?:1122`) compares the usage's entire call span to the
finding's symbol span. Kondo explicitly changes a call finding to name coordinates
at `linters.clj:538–543`. Here the usage spans row 2 columns **34–52**, while the
finding spans **35–51**. They do not match, and the owner probe confirms the
qualified missing call remains blocking. Therefore **this standalone test file is
refused before its row is persisted**. Do not fix this by downgrading every external
unresolved-var finding: that would also admit unrelated misspellings.

The agent row derivation `analyze-forms:1011` itself has no `assert-clean-analysis!`
call; settlement uses it at `src/seon/turn.clj:935–955`. This proves that derivation
can keep the edge, not that an earlier evaluator admits the test. Compiling an
ordinary test body still requires the target var to resolve; SCI's analyzer resolves
call heads at `reference-code/sci/src/sci/impl/analyzer.cljc:1985`. Entry evaluation,
writer admission and final merge are separate proof obligations.

`gate-set-in`, `fn.clj:1531–1554`, already starts from a symbol and seeks AVET
`:seon.fn/calls`, `:seon.fn/references` and `:seon.test/subject` at **1543–1545**.
It does not require a row for the seed. Its caller identity map includes tests at
**1564–1566**. Thus **if T is stored**, the existing walk can find T before f
exists as well as after it exists. Preserve this behavior for D1's captured
pre-definition basis; joining the seed to a current fn row would break test-first.

### Smallest completion and regression, alongside the def-body slice

Keep the existing symbolic edge producer unchanged for a missing var in a known
namespace; add its regression to the same indexer slice. Require a resolved namespace
(or resolved alias) and explicit qualification for a future target. Do not map every
unknown bare name to the current namespace, infer aliases from spelling, manufacture
a stub function, or add a second test-first detector.

D1's shared entrance must allow **an analyzed test source declaration** with a
qualified future target to be stored on the experimental branch before compiling
or executing that body. Use the existing test row/source/call attributes. Classify
this case from the test owner, resolved target and kondo finding, using name-span
coordinates where a finding must be joined; retain its missing-target diagnostic.
This permission is specific to a pending test declaration, not ordinary function
admission or completed publication. Defer executable acquisition until its targets
exist; an attempted earlier run reports unresolved dependencies, never green.
After f's schema and all other entry checks pass, test-first reads the captured
branch basis through the existing `gate-sets`; evaluation of f and execution of T
then use the normal owners. Merge still requires resolved program dependencies and
passing tests. This is a **B1 → D1 admission seam**, not proof that changing the
indexer alone makes test-first operational. It adds no missing-function body and
needs no second stored pending flag: missing targets derive from rows and edges.

Regression: on the canonical branch, with the namespace present and f absent,
submit T containing `(future.example/f)` through the shared test-declaration
entrance; assert T is durable with `:seon.fn/calls` containing that exact symbol,
f still has no definition, and `gate-sets` already returns T. Capture that basis,
then submit a contracted f under shipped `:gate`/test-first policy: the test-first
check passes from that earlier basis, and selection for f includes T. Run T only
after f is available. Negative cases retain refusal for an unknown namespace,
an unqualified unresolved name and an ordinary function with an unresolved callee;
a test introduced only in the same candidate batch is not prior-basis evidence.
Exercise both source-row construction and agent-row construction; no hand-written
edge or `:seon.test/subject` may substitute for the call in this regression.

Cost remains O(changed definitions + usages/findings); a name-span map, if needed,
is built once per changed analysis. The existing reverse index read has cost
proportional to reached edges, without a whole-program analyzer pass. No new test
or production code was written or run in this follow-up. Successful strict-entry
submission, persistence and execution remain implementation proofs, not results
of this diagnosis.

## Exact paragraph for B1 after independent review

Append to **“Graph fidelity before selective test execution”**, after its existing
paragraph on unresolved dispatch and before §6:

> Preserve dependency owners for every `def` and `defonce`, including nonliteral initializers and memoized or container-held functions: clj-kondo already supplies `:from`/`:from-var`, and the indexer must retain the declaration row instead of requiring inferred arglists before keeping its calls/references. Keep callable proof separate from dependency ownership; do not invent arities, mark nonliteral values constant, or replay effectful initializers to discover their shape. File and agent declaration construction use the same rule. Truly ownerless usages retain the existing declared file/namespace-source owner; reaching its unresolved dependency widens to the complete eligible test set or refuses coverage, never only that file’s tests, and targetless unresolved analysis cannot certify a narrow selection. B1 converts `gate-sets-in` and `test-reach-rules` together, with B4/D1 using that shared result, and coordinates nonliteral declaration acquisition with B2 before claiming isolated execution. Prove through canonical publication that a test in another file reaching F only through `(def g (memoize (fn [] (F))))` is selected when F changes; cover reference-valued wrappers, defonce, map-held callbacks and ownerless initialization without hand-authored edges. Work is O(changed definitions + changed usages), with existing caches and exact replacement; the manual def-body KEEP exceptions retire only after stored graph and adoption proof. Test-first uses the same symbolic graph: qualified calls to a not-yet-defined var in a resolved namespace already survive row derivation, and `gate-sets` must keep finding their test owners before the callee row exists. Preserve that behavior with a regression defining test T calling ns/f on the branch first, asserting its durable call edge and prior-basis reach, then defining contracted ns/f under `:gate`, asserting test-first passes and T is selected. B1 and D1 must admit the analyzed pending test declaration without prematurely compiling its unresolved body; current file publication refuses that unresolved-var finding. Keep the missing-target diagnostic, defer execution until resolution, and retain unconditional merge checks. Do not invent stub functions, guess targets for unknown bare names, globally suppress unresolved-var findings, or add another edge producer or test-first detector.

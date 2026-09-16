# Call-graph fidelity for test selection (E2)

Dated 2026-09-17. Read-only research lane on `steward-platform`. Owner
question: *"Do we have an efficient way of querying to find what tests are
affected and efficiently running just those?"* Ruling E2 answers reach
through the stored `:seon.fn/calls` edges derived from clj-kondo analysis;
principle P3 says a missing call fact is UNKNOWN, never "no test needed".
**The selection is exactly as correct as that graph, and the graph is
currently missing four whole call shapes.** Numbers below are measured on
cluster `default` (two `mcp__seon__eval_clj` JVM-mode queries, 2026-09-17)
or from a local clj-kondo probe whose source is given.

---

## 1. What we consume from clj-kondo

`src/seon/fn/analyzer.clj:19-33` is the entire analysis configuration:

```clojure
:analysis
{:arglists true
 :var-usages true
 :keywords true
 :var-definitions {:shallow false :meta true}
 :namespace-definitions {:shallow false :meta true}}
```

`clj-kondo.core/run!` reads those flags at
`reference-code/clj-kondo/src/clj_kondo/core.clj:147-175`. Two consequences
read straight off that source:

- `:namespace-definitions`, `:namespace-usages` and `:var-definitions` are
  seeded unconditionally whenever `:analysis` is present
  (`core.clj:162-165`), which is why `analyze` can project
  `::analyzer/namespace-usages` (`src/seon/fn/analyzer.clj:365-367`) without
  asking for it.
- `:protocol-impls` (`core.clj:151`), `:instance-invocations`
  (`core.clj:152`), `:locals`, `:symbols`, `:java-class-definitions`,
  `:java-class-usages`, `:java-member-definitions` and `:context` are all
  `nil` unless requested. **We request none of them**, so
  `reference-code/clj-kondo/src/clj_kondo/impl/analysis.clj:194` and
  `:218` short-circuit and those vectors are never even allocated.

The six vectors `analyze` returns are `::namespace-definitions`,
`::namespace-usages`, `::var-definitions`, `::var-usages`, `::keywords`,
`::findings` (`src/seon/fn/analyzer.clj:362-382`), each filtered to
non-`:cljs` arms (`:153-154`). Per-entry key projections:
`var-usage` keeps `:from :from-var :to :name :alias :refer :arity :macro
:private :fixed-arities :varargs-min-arity :lang`
(`src/seon/fn/analyzer.clj:111-119`). **`:defmethod` and
`:dispatch-val-str` are emitted by clj-kondo
(`reference-code/clj-kondo/src/clj_kondo/impl/analysis.clj:32,39`) and we
drop them on the floor.**

## 2. How a var-usage becomes `:seon.fn/calls`

Three functions in `src/seon/fn.clj` decide the whole graph:

```clojure
(defn- usage-caller [usage]            ; src/seon/fn.clj:331-335
  (when (and (::analyzer/from usage) (::analyzer/from-var usage)) …))

(defn- call-target [usage]             ; src/seon/fn.clj:337-340
  (when (contains? usage ::analyzer/arity) (usage-symbol usage)))
```

`call-targets-by-caller` (`src/seon/fn.clj:346-361`) keeps a usage only when
BOTH answer, and only when the caller is a first-party definition with
arglists (`src/seon/fn.clj:317-324`). `call-arities-by-caller`
(`src/seon/fn.clj:453-470`) refines the same population, never widens it.
The edge lands as `:seon.fn/calls` on the function row
(`src/seon/fn.clj:544-548` for functions, `:543-548` for tests) and on
agent-admitted rows (`src/seon/fn.clj:793-797`).

So an edge requires **an `:arity` key on the usage** and **an `:in-def`
attribution**. Those two requirements are the two holes.

### `:arity` — only syntactic calls and 25 core HOFs carry it

clj-kondo sets `:arity` on `{:type :call}`
(`reference-code/clj-kondo/src/clj_kondo/impl/analyzer.clj:3656-3680`,
`:3830`) and on `{:type :hof-call}`
(`.../impl/analyzer.clj:2855-2870`). A bare symbol reference goes through
`analyze-usages2` as `{:type :use}` with no `:arity` at all
(`reference-code/clj-kondo/src/clj_kondo/impl/analyzer/usages.clj:239-260`).
The HOF set is a closed literal list of 25 `clojure.core` names
(`.../impl/analyzer.clj:3495-3499`): `map mapv filter filterv remove reduce
every? not-every? some not-any? mapcat iterate max-key min-key group-by
partition-by map-indexed keep keep-indexed update update-in swap!
swap-vals! send send-off send-via`, dispatched into `analyze-hof`
(`.../impl/analyzer.clj:2772-2810`).

### `:from-var` — only `def`/`defn` bodies are attributed

`:in-def` is bound at `.../impl/analyzer.clj:1889` (`analyze-def`) and
`:1045` (named `fn`). `analyze-defmethod`
(`.../impl/analyzer.clj:2478-2487`) hands the fn-tail to `analyze-fn`
**without** binding `:in-def`, and protocol-method bodies inside
`deftype`/`defrecord`/`extend-type` (`.../impl/analyzer.clj:2400-2412`)
likewise carry none.

## 3. Call shapes — edge emitted or not

Probe source: `probe.clj` written to a scratch directory, linted with
`clj-kondo v2026.07.24 --cache false --config '{:analysis {:var-usages true
:protocol-impls true :instance-invocations true :keywords true}}'` on
2026-09-17. Columns are what clj-kondo emitted; the last column applies
`call-target`/`usage-caller` above.

| # | shape (probe row) | `:from`/`:from-var` | `:to`/`:name` | `:arity` | `:seon.fn/calls` edge? | test can reach? |
|---|---|---|---|---|---|---|
| 1 | `(target x)` direct, row 18 | `probe`/`direct` | `probe`/`target` | 1 | **yes** | yes |
| 2 | `(map target2 xs)`, row 19 | `probe`/`higher-order` | `probe`/`target2` | **both**: one usage with no arity AND one with arity 1 | **yes** (25-name HOF list only) | yes |
| 3 | `(apply target3 xs)`, row 20 | `probe`/`applied` | `probe`/`target3` | absent | **no** | **no** |
| 4 | `(partial target4)`, row 21 | `probe`/`partialled` | `probe`/`target4` | absent | **no** | **no** |
| 5 | `(comp target5 target6)`, row 22 | `probe`/`composed` | both | absent | **no** | **no** |
| 6 | `#'target7`, row 23 | `probe`/`var-quoted` | `probe`/`target7` | absent | **no** | **no** |
| 7 | `(pm r 1)` protocol method call, row 25 | `probe`/`protocol-call` | `probe`/`pm` | 2 | **yes**, to the `defprotocol` method var | reaches the method var only |
| 8 | `(defrecord R [] P (pm [this a] (target a)))`, row 13 | `probe`/**nil** | `probe`/`target` | 1 | **no — caller lost** | **no** |
| 9 | `(dispatch x)` multimethod call, row 26 | `probe`/`multi-call` | `probe`/`dispatch` | 1 | **yes**, to the `defmulti` var | reaches the `defmulti` only |
| 10 | `(defmethod dispatch :a [x] (target2 x))`, row 16 | `probe`/**nil** | `probe`/`target2` | 1 | **no — caller lost** | **no** |
| 11 | `(mac x)` our own macro, row 24 | `probe`/`via-macro` | `probe`/`mac` | 1 | **yes**, to the macro var | reaches the macro var only |
| 12 | macro body `` `(target ~x) ``, row 10 | `probe`/`mac` | `probe`/`target` | absent (syntax-quoted) | **no** | **no** |
| 13 | `((requiring-resolve 'probe/target) 1)`, row 27 | `probe`/`resolved` | `clojure.core`/`requiring-resolve` | 1 | edge to `requiring-resolve` only; **the quoted symbol produces NO usage at all** | **no** |
| 14 | `{:fn 'probe/target}` config-named, row 28 | — | — | — | **no usage emitted** | **no** |
| 15 | `{:procs {:a {:step #'target}}}` graph def, row 29 | `probe`/`graph-def` | `probe`/`target` | absent | **no** | **no** |
| 16 | keyword lookups | `:keywords` → `:seon.fn/keywords` (`src/seon/fn.clj:365-385`) | — | — | never a call edge, by design | no |
| 17 | agent source evaluated through SCI | same analyzer, `runtime-analysis-batch` (`src/seon/fn.clj:679-700`) | same | same | same rules, same holes | same |

Corollaries for shapes 7/9/11: the edge lands on the *dispatch* var
(`pm`, `dispatch`, `mac`), so a test reaches the protocol method, the
multimethod and the macro — but nothing they actually run. Combined with
shapes 8/10/12, every implementation body behind a protocol, a
multimethod or one of our own macros is invisible to selection.

Measured scale on one real namespace — `src/seon/print.cljc` linted the
same way, 2026-09-17:

- 2,582 var-usages total; 2,055 carry `:from-var`, 527 do not.
- 1,890 usages have both `:arity` and `:from-var` → candidate edges.
- **433 usages have `:arity` but NO `:from-var`** — 18.6% of all
  arity-bearing usages in that file are dropped for lack of a caller.
  Sampled targets: `append-chunk!` (rows 216/219/224/230),
  `soft-separator` (221), `literal` (227), `hiccup-token` (282/284/289/291)
  — all inside the `Sink` protocol implementations at
  `src/seon/print.cljc:213-229`.
- 165 usages have `:from-var` but no `:arity` (shapes 3-6, 15).

## 4. Measurement on `default`

Query 1 (single JVM-mode form, 55 ms wall). Graph totals: **5,019
`:seon.fn/sym` rows, 1,829 `:seon.test/sym` rows, 68,930 `:seon.fn/calls`
datoms.**

| function | kind | in-edges (`:avet :seon.fn/calls`) | out-edges | `tests-reaching` | ms |
|---|---|---|---|---|---|
| `seon.id/digest` | plain defn | 35 | 2 | 495 | 7 |
| `seon.print/-token` | protocol method (`src/seon/print.cljc:17`) | 5 | 0 | 460 | 6 |
| `seon.print/emit` | multimethod (`src/seon/print.cljc:648`) | 1 | **0** | 460 | 6 |
| `seon.turn/step` | flow step-fn (`src/seon/cluster/agent.clj:460`) | 3 | 38 | **3** | 1 |
| `seon.fs.jvm/read` | capability handler (`src/my/fs.clj:53`) | **0** | 4 | **0** | 1 |
| `seon.error/render-ai` | render fn on a schema pair (`resources/seon/schemas/my.edit.edn:68`) | 7 | 8 | 6 | 1 |
| `seon.bootstrap/doc` | macro (`src/seon/bootstrap.clj:170`) | **0** | 1 | **0** | 1 |
| `seon.print/emit-sequential` | private helper | **0** | 13 | **0** | 1 |
| `seon.db/transact!` | write seam (`src/seon/db.clj:3213`) | 263 | 12 | 1,009 | 11 |
| `seon.turn/settle!` | turn writer (`src/seon/turn.clj:3762`) | 9 | 16 | 74 | 2 |

Query 2 (659 ms wall). 1,196 public + 3,040 private function rows; **409
function rows have no incoming call edge at all**;
`seon.fn/functions-without-tests` returns **312 public functions in 288 ms**;
`(tests-reaching db "seon.db/q")` = 21 ms.

### Fidelity holes I would have expected to be reached

1. **All 8 capability handlers have zero reach**: `seon.edit.jvm/edit`,
   `seon.fs.jvm/{glob,read,stat,write}`, `seon.shell.jvm/run`,
   `seon.web.jvm/{fetch,search}`. `test/seon/fs/jvm_test.clj:22` exercises
   them through `(deref (ns-resolve 'seon.fs.jvm operation))` — shape 13,
   runtime resolve from a symbol. The system already knows the edge: the
   `:seon.effect/capability` marker mints `:seon.fn/capability-fn` as a ref
   (`src/seon/fn.clj:600-607`), and `assert-capability-contracts!`
   (`src/seon/fn.clj:1583-1601`) refuses a dangling one. **Missing shape:
   declared-handler edge from the declaring `my.*` function to the handler.**
2. **28 of 73 `seon.print` functions have zero reach**, including
   `emit-sequential`, `emit-map-like`, `emit-entry`, `emit-separated`,
   `fit-entry`, `append-chunk!`, `append-hiccup!`, `hiccup-token`,
   `soft-separator`, `enrich-node`, `face-class`, `visible-items`,
   `close-frame`, `object-class-text`. `test/seon/print_test.clj:322`
   (`fit-bounds-a-terminal-projection-and-names-what-it-omitted`) and
   `:281` render vectors and maps, which go
   `text` → `emit` → `(defmethod emit ::vector)` (`src/seon/print.cljc:669`)
   → `emit-sequential`. **Missing shape: caller attribution inside
   `defmethod` and protocol-impl bodies (shapes 8 and 10).**
3. **`seon.turn/step` reaches only 3 tests** although it is the turn-loop
   proc every agent runs. It is referenced as `#'turn/step`
   (`src/seon/cluster/agent.clj:460`) — shape 15. Only tests that name it
   directly select it; a change to `seon.turn/step` does not select the
   agent-graph drills that actually exercise it.
4. **`seon.bootstrap/doc` has zero reach** although
   `test/seon/bootstrap_test.clj` covers the injected REPL documentation.
   It is reached as `#'bootstrap/doc` (`src/seon/cluster/agent.clj:339`)
   and by SCI interning — shapes 6 and 13.

None of these is a P3 "unknown" today. `tests-reaching` returns `[]`, and
`bin/test`'s selection reads `[]` as "no test needs to run".

## 5. Storage and cost

Reach is **derived at query time, twice, by two different mechanisms**:

- `seon.fn/gate-set` (`src/seon/fn.clj:1157-1212`, aliased
  `tests-reaching` at `:1214-1220`) walks INCOMING edges with
  `(db/datoms database :avet :seon.fn/calls entity)` in a `loop` with a
  `seen` set, then joins `:seon.test/sym`, `:seon.test/subject` and
  `:seon.test/pending-subject`. `:seon.fn/calls` is `[:set :seon.db/ref]`
  (`resources/seon/schemas/seon.fn.edn:21`), so the reverse walk is an AVET
  prefix scan per node. Measured 1-11 ms per function (table above), 21 ms
  for the hottest (`seon.db/q`).
- `test-reach-rules` (`src/seon/fn.clj:1130-1155`) is a recursive Datalog
  rule set used by `currently-failing-functions` (`:1222-1236`) and
  `functions-without-tests` (`:1240-1261`). Whole-graph closure: **288 ms**
  over 68,930 edges.
- `bin/test`'s actual selection does NOT use the database. It uses
  `seon.test.selection/reaching-tests` (`src/seon/test/selection.clj:134-176`),
  an in-memory backwards BFS over `:seon.fn.file/rows` artifacts following
  `:seon.fn/calls` and `:seon.test/subject` (`:123-131`), seeded from every
  identity DEFINED in a changed path. Same edge set, so the same holes.

`:seon.test/reach` / `:seon.test/reach-digest`
(`resources/seon/schemas/seon.test.edn:2-6,63-65`) are a **different**
thing: `seon.test.runner/reach-refresh` and `reach-entry`
(`src/seon/test/runner.clj:1800-1876`) maintain an incremental forward
closure per test on the database's carried projection cache, invalidated by
`db/since` over `reach-attributes` (`:1769-1772`), and
`reach-digests`/`reach-memberships` (`:1912-1932`) record what a run
actually tested. It is result provenance, not selection input. Note its
honest typed unknown, `:seon.test/reach-unknown`
(`resources/seon/schemas/seon.test.edn:4`) — the same honesty is what
selection lacks.

Cost verdict: **the derivation is not the problem.** 1-21 ms per function
and 288 ms for the whole graph is fast enough that no memoization is
warranted. The problem is entirely fidelity.

## 6. Submodule currency

```
pin      57252e07975710aa579b24f0d1b2b1e04195caa2  Thu Jul 30 10:21:47 2026
describe v2026.07.24-2-g57252e07
upstream 13a32d1caf6278b7e4a8c4ad4936d4fc33db6cf2  Thu Sep 10 13:30:44 2026
```

We are a fork (`git@github.com:seantempesta/clj-kondo.git`) with 2 local
commits on top of `v2026.07.24`: `0fc2f636 Resolve source metadata keywords
in analysis` and `57252e07 Resolve attr-map metadata keywords in source
namespace`. Upstream is **27 commits ahead** of the merge base
(`v2026.08.03`, `v2026.08.04`, then GraalVM/CI/edamame bumps and type-linter
fixes: `4022ff03`, `31afed37`, `ef26c0df`, `a021ce30`, `52d4e2fd`,
`d72e57fd`, `95c7099d`).

`git diff --stat <merge-base>..upstream/master -- src/clj_kondo/impl/analysis.clj
src/clj_kondo/core.clj` is **empty**: only `CHANGELOG.md` changed among the
analysis-emission files. `:protocol-impls`, `:instance-invocations`, the
`:defmethod`/`:dispatch-val-str` flags on var-usages and `:end-row`
precision are all **already present at our pin** (`impl/analysis.clj:32,39,
192-214,216-232`).

**Recommendation: do not move the pin for call-graph fidelity.** It closes
no hole found here. Move it later, on its own, for the perf commit
(`95c7099d`) and the type-linter fixes — as an independent change with its
own gate.

## 7. Verdict and options

**No. The graph is not good enough for E2 today.** Selection silently
returns an empty test set for at least 4 named classes of function, and
`functions-without-tests` reports 312 public functions, an unknown share of
which are covered through an unmodelled shape. A check that reads absence of
an edge as absence of coverage is the project's named recurring failure
class (AGENTS.md, "reads ABSENCE OF SIGNAL as health").

Every hole below is fixed at the ONE declaration seam where the system
already knows the edge. No metadata annotation on tests, no name rule.

### Option A — attribute the bodies clj-kondo already analyses (simplest)

Two mechanical changes, both inside `src/seon/fn.clj`, no new fact family.

1. **Protocol-impl and `defmethod` bodies.** Turn on
   `:protocol-impls true` in `src/seon/fn/analyzer.clj:19-33`, project
   `:protocol-ns/:protocol-name/:method-name/:impl-ns` plus the
   `:row/:end-row/:col/:end-col` span (all emitted at
   `reference-code/clj-kondo/src/clj_kondo/impl/analysis.clj:192-214`), and
   in `call-targets-by-caller` attribute a `:from-var`-less usage to the
   enclosing impl by the same **span containment** join `writes-by-writer`
   already uses (`src/seon/fn.clj:427-450`, `span-contains?` at `:401-414`).
   The impl's caller identity is the protocol method var
   (`ns/method-name`), which already has a row. For `defmethod`, keep the
   `:defmethod` and `:dispatch-val-str` keys we currently drop
   (`src/seon/fn/analyzer.clj:117`) and attribute the body to the
   `defmulti` var; then `seon.print/emit` gains out-edges instead of its measured 0.
   - guarantee: shapes 8 and 10 become real edges; the 433 lost usages in
     `seon.print` alone are recovered; `emit-sequential` becomes reachable.
   - cost: one extra analysis vector, one span join per file; the
     `:protocol-impls` allocation is already free at our pin.
   - give up: the *specific* impl is collapsed into its dispatch var, so
     selection over-approximates (every `emit` caller now reaches every
     `defmethod` body). That is the correct direction under P3.
   - regression: `test/fixtures/` namespace declaring a `defprotocol` +
     `defrecord` impl and a `defmulti` + `defmethod`, each body calling a
     distinct target; assert a `:seon.fn/calls` edge from the method var to
     each target and that `tests-reaching` on each target names the fixture
     test.

2. **Declared-symbol edges.** Where a symbol names a function in a fact we
   already store, mint the call edge from the same row that mints the fact:
   `:seon.fn/capability-fn` (`src/seon/fn.clj:600-607`) IS an edge from the
   declaring `my.*` function to the handler; a flow graph's `#'step-fn`
   (`src/seon/cluster/agent.clj:454,460,467`) IS an edge from the
   graph-building function to the step. The capability case is free —
   `assert-capability-contracts!` (`src/seon/fn.clj:1583-1601`) already
   proves the handler row exists. The step-fn case is the `#'f` shape
   (6/15): admit a var-quote usage as a call edge of *unknown arity* —
   keep it out of `:seon.fn/call-arities`, whose population is documented
   as exactly the edge set (`resources/seon/schemas/seon.fn.edn:44-46`).
   - guarantee: all 8 capability handlers and `seon.turn/step` get real
     reach.
   - cost: one `cond->` clause each; `call-target` gains a second admission
     branch (`:arity` present OR the usage is a var-quote).
   - give up: `#'f` in a doc string or a `doc-line` call
     (`src/seon/cluster/agent.clj:337-339`) becomes an edge too — a mild
     over-approximation, again the safe direction.
   - regression: a fixture with `{:procs {:a {:step #'target}}}` and a
     `:seon.effect/capability` marker; assert both edges and both reaches.

### Option B — Option A plus an honest unknown (recommended)

Everything in A, and then make the remaining shapes REPORT rather than
return empty. Shapes 3, 4, 5, 12, 13, 14 (`apply`, `partial`, `comp`,
our own macro bodies, `requiring-resolve`, a symbol in a config fact) cannot
be resolved statically. Declare them as a queryable fact at indexing — a
`:seon.fn/unresolved-reference` set beside `:seon.fn/pending-calls`
(`resources/seon/schemas/seon.fn.edn:76`) — and have `gate-set` return the
typed unknown, exactly as `:seon.test/reach-unknown`
(`resources/seon/schemas/seon.test.edn:4`) already does on the recording
side. `bin/test`'s selection then WIDENS on unknown, which
`seon.test.selection/widening-path?` (`src/seon/test/selection.clj:108-116`)
already has a path for.

- guarantee: E2 becomes sound. No function can be silently unselected; a
  function whose callers are unresolvable widens instead of vanishing.
- cost: one new attribute, one branch in `gate-set`, one widening branch in
  the runner. Some changes select more tests than strictly needed.
- give up: a little selection sharpness in the namespaces that use
  `partial`/`comp`/our own macros heavily.
- regression: a fixture whose only path to the target is through `apply`;
  assert `tests-reaching` returns the typed unknown, not `[]`, and that the
  runner widens.

### Option C — enable `:instance-invocations` and `:java-class-usages` too

Adds Java interop reach (`.method` calls, `Class/staticMethod`) as facts.
Not a hole for E2 — first-party function selection does not traverse Java —
so it buys diagnostics, not correctness. Do it only if a later assignment
needs "which code touches this Java class".

**Recommendation: Option B**, with Option A's two mechanical fixes landing
first as their own commits so each can be measured against the numbers in
§4. The derivation cost (§5) leaves plenty of headroom.

---

### Issues to file (out of this lane's scope)

- `tests-reaching` returns `[]` for a covered function — absence read as
  health. Severity: blocker for E2.
- All 8 declared capability handlers report zero test coverage while
  `test/seon/fs/jvm_test.clj` exercises them.
- `seon.print`: 28 of 73 functions report zero coverage.

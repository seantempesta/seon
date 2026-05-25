---
type: research
status: draft
tags: [research, testing, agent]
---

# Phase 1 Probe Results — 2026-05-25

Executed the five pre-Phase-1 probes from §4.N of
[[cljs-testing-infrastructure-2026-05-25]] (plus §4.L Probe #5 supplied
inline by the orchestrator while the doc patch was in flight).

All probes ran against the live V0 pod (`bin/seon start pod`, pid 72357
at run time) via `mcp__seon_cljs__eval` over shadow nREPL :7889. Each
self-host eval went through `seon.eval/eval` against the canonical
bootstrap compile-state obtained via `seon.repl/ensure-bootstrap!`.

## TL;DR

| # | Probe | Verdict |
|---|---|---|
| 1 | `:test` meta survival under self-host | **PARTIAL** — analyzer drops it; runtime `meta` preserves it |
| 2 | `with-redefs` under self-host | **PASS** |
| 3 | `use-fixtures :each` async ordering | **PASS** |
| 4 | `mi/instrument!` on post-bootstrap fn | **PASS** (with caveats) |
| 5 | Datahike recursive rules in CLJS | **FAIL** — only first hop returned |

## Setup

```clojure
(require '[seon.repl :as r] '[seon.eval :as e])
(def !cs (atom nil))
(-> (r/ensure-bootstrap!) (.then (fn [cs] (reset! !cs cs))))
;; !cs holds the canonical compile-state, ~47 nses loaded into analyzer
;; cache (cljs.core, cljs.test, cljs.test$macros, etc. — full out/bootstrap/ana set)
```

Probes are wrapped in a `run-probe-serial!` helper that chains
sequential `seon.eval/eval` calls (each form runs through cljs.js
against the shared compile-state) so that vars defined in form N are
visible to form N+1.

## Probe 1 — `:test` meta survival under self-host

### Forms

```clojure
(require '[cljs.test :include-macros true :refer [deftest is]])
(require '[cljs.analyzer.api :as ana])
(deftest probe-1-test (is true))
;; Query 1 — via analyzer's ns-interns:
{:meta-test-truthy? (-> (ana/ns-interns 'cljs.user) (get 'probe-1-test) :meta :test boolean)
 :meta-keys (-> (ana/ns-interns 'cljs.user) (get 'probe-1-test) :meta keys vec)}
;; Query 2 — via runtime var meta:
{:test (get (meta #'cljs.user/probe-1-test) :test nil)
 :truthy? (boolean (:test (meta #'cljs.user/probe-1-test)))
 :keys (vec (keys (meta #'cljs.user/probe-1-test)))}
```

### Output

Query 1 (analyzer): `{:meta-test-truthy? false, :meta-keys [:file :line :column :end-line :end-column]}`

Query 2 (runtime var): `{:test #object [Function], :truthy? true, :keys [:ns :name :file :end-column :column :line :end-line :arglists :doc :test]}`

### Verdict — PARTIAL

The doc's exact probe form (`cljs.analyzer.api/ns-interns`) FAILS — the
analyzer-side view of the var's meta does not carry `:test`. Only
source-location keys survive.

However, the runtime-side `(meta #'cljs.user/probe-1-test)` DOES preserve
`:test` (it holds the gensym test-fn `cljs.test/deftest` stuffs in via
its expansion at the runtime var). So `cljs.test/run-tests` itself works
(`(t/test-vars [#'cljs.user/probe-3-test])` and `(cljs.test/run-tests …)`
both successfully invoke the test body in probe 3).

**Implication for §4.A `:seon.fn/test?`:** the analyzer-tee path cannot
derive `:seon.fn/test?` from the analyzer's `:meta` view. Two viable
fallbacks:

- Walk `cljs.test/get-current-env`'s registered test list (works because
  `deftest`'s side effect registers there); OR
- Inspect the runtime var's `meta` (e.g. resolve the sym via
  `seon.eval/lookup-value`'d `#'…` and read `:test` from its meta) — the
  data IS there at runtime, just not in the analyzer projection.

The first option (`cljs.test/get-current-env`) is the cleaner path because
it doesn't require resolving each candidate var.

## Probe 2 — `with-redefs` under self-host

### Forms

```clojure
(defn probe-2 [] :original)
(let [seen (atom nil)]
  (with-redefs [probe-2 (fn [] :redef)]
    (reset! seen (probe-2)))
  {:during @seen :after (probe-2)})
```

### Output

`{:during :redef, :after :original}`

### Verdict — PASS

Self-host `with-redefs` correctly swaps and restores the var binding.
Fallback (dynvar-based stubbing helper) is not needed.

## Probe 3 — `use-fixtures :each` async ordering

### Forms

```clojure
(require '[cljs.test :include-macros true :refer [deftest is async use-fixtures]])
(def order (atom []))
(use-fixtures :each
  {:before #(swap! order conj :before)
   :after  #(swap! order conj :after)})
(deftest probe-3-test
  (async done
    (js/setTimeout
      (fn [] (swap! order conj :body) (is true) (done))
      10)))
(reset! order [])
(cljs.test/run-tests 'cljs.user)
;; wait 300ms, then:
@order
```

### Output

`[:before :after :before :body :after]`

Decomposition: `cljs.user` had two test vars at run time —
`probe-1-test` (sync, from Probe 1) and `probe-3-test` (async). The
fixtures fired once per test-var. Per-test pattern:

- probe-1-test (sync): `:before :after` ✓
- probe-3-test (async): `:before :body :after` ✓

### Verdict — PASS

`cljs.test`'s self-host async machinery awaits the `(done)` callback
before firing the `:each` `:after` fixture. The doc's worst-case
(`:after` before `:body`) did NOT manifest. Phase 1 can rely on
standard `use-fixtures :each + (async done …)` semantics without the
Promise-wrapper workaround.

## Probe 4 — `mi/instrument!` on post-bootstrap fn

### Setup adjustments

The doc's example used `mi/-register!`, which does not exist in
malli.instrument. The actual API is
`malli.core/-register-function-schema!` (6-arity with `:cljs identity`,
mirroring the canonical `m/=>` expansion path — same pattern
`seon.instrument/collect!` uses for the substrate at build time).

Also note: `(require '[malli.instrument :as mi])` fails under self-host
because `malli.instrument`'s analyzer cache is NOT in
`shadow-cljs.edn :bootstrap :entries`. The RUNTIME namespace IS loaded
(it's a transitive dep of the precompiled `:client` bundle), so calling
`mi/instrument!` works once the analyzer is told the symbol exists.

For the probe, I worked around this by calling
`malli.core/-register-function-schema!` (which IS in the bootstrap entries
via `cljs.core` deps — wait, more accurately, it's loaded as a transitive
dep that DID land in the cache) directly. The implication for Phase 2:
add `malli.instrument` to `shadow-cljs.edn :bootstrap :entries` so agent
code can `(require '[malli.instrument])` cleanly.

### Forms (adjusted)

```clojure
(require '[malli.core :as m])  ; works — in cache
(m/-register-function-schema!
  'cljs.user 'probe-4
  [:=> [:cat :probe-4/probe-4-in] :probe-4/probe-4-out]
  {} :cljs identity)
(defn probe-4
  {:malli/schema [:=> [:cat :probe-4/probe-4-in] :probe-4/probe-4-out]}
  [{:probe-4/keys [n]}] {:probe-4/n2 (* 2 n)})
(malli.instrument/instrument!
  {:filters [(malli.instrument/-filter-var #(= % #'cljs.user/probe-4))]})
(try (probe-4 {:probe-4/n "not-an-int"})
     (catch :default e
       {:type (-> e ex-data :type str)
        :msg (.-message e)
        :data-keys (when-let [d (ex-data e)] (vec (keys d)))}))
```

### Output

`{:type ":malli.core/invalid-input", :msg ":malli.core/invalid-input", :data-keys [:type :message :data]}`

Pre-instrument call returned `{:probe-4/n2 ##NaN}` — confirming the fn
was unwrapped before, then wrapped after.

The registration table also confirmed:
```
{:cljs {... cljs.user {probe-4 {:schema [:=> [:cat :probe-4/probe-4-in] :probe-4/probe-4-out]
                                :ns cljs.user :name probe-4}}}}
```

### Verdict — PASS (with caveats)

Post-bootstrap-defined fns CAN be instrumented. The §4.K Phase 2 hook
("instrument agent-defined fns at write time") is reachable.

Caveats for Phase 2:

1. **Use `m/-register-function-schema!`** (not the non-existent
   `mi/-register!` the doc proposes). Match the 6-arity form
   `(... {} :cljs identity)` exactly — the 4-arity registers under `:clj`
   key which `mi/instrument!` ignores by default.
2. **Add `malli.instrument` to `:bootstrap :entries`** so agent code can
   `(require '[malli.instrument])` from within self-host eval — same
   gap that bit Probe 5's datahike require.
3. The default `mi/instrument!` reporter throws a generic ex-info; the
   substrate already wires `seon.error.instrument/report-fn` for the
   structured envelope. Phase 2 should call instrument! with that
   reporter, not the default.

## Probe 5 — Datahike recursive rules under self-host

### Setup adjustments

Same `:bootstrap :entries` gap as Probe 4: `datahike.api` analyzer cache
is NOT in the bootstrap, so `(require '[datahike.api])` fails inside
self-host eval. The runtime IS loaded
(`(.. js/global -datahike -api -q)` is truthy).

For the probe, I ran the query through the **shadow REPL directly**
(not self-host) — the :client build's analyzer DOES know
`datahike.api`. This isolates the question to "do datahike recursive
rules work in CLJS" rather than "does self-host require resolve
datahike". The query macro at `query.cljc:1011` was already expanded
at shadow build time; the rule list is just data passed at runtime.

Also note: `:store :id (str (random-uuid))` from the doc's recipe
throws — datahike-cljs requires a real UUID, not a stringified one
(error: `Store :id must be a UUID type. Got: function String() ...`).
Used `(random-uuid)` instead.

### Forms (adjusted)

```clojure
(require '[datahike.api :as d])
(def p5-cfg2 {:store {:backend :memory :id (random-uuid)} :schema-flexibility :write})
;; transact schema + data via Promise chain (d/transact! — not d/transact —
;; per "Synchronous transact not supported in ClojureScript")
;; Final shape after txes: a → b → c → d (chain of :probe/requires refs)

(d/q '[:find [?name ...]
       :in $ %
       :where [?root :probe/name "a"] (depends ?root ?dep) [?dep :probe/name ?name]]
     @conn
     '[[(depends ?x ?y) [?x :probe/requires ?y]]
       [(depends ?x ?y) [?x :probe/requires ?z] (depends ?z ?y)]])
```

### Output

`["b"]`

Expected: `["b" "c" "d"]` (or at least `["b" "c"]` for the original a→b→c
shape).

Sanity checks confirmed the data is correct:

- `(d/datoms @conn :eavt)` shows the full a→b→c→d ref chain.
- `(d/q '[:find [?n ...] :where [_ :probe/name ?n]] @conn)` → `["a" "b" "c" "d"]`
- Direct queries `[?a :probe/requires ?d] [?d :probe/name "b"]` etc. work.

Tried reordering the rule clauses (recursive case first vs base case
first) — same result. Tried `:find ?name` (relation) vs `:find [?name ...]`
(collect) — same result (`#{["b"]}` vs `("b")`).

### Verdict — FAIL

Datahike-CLJS's query engine evaluates the rule body but does NOT recurse
into the rule's own self-reference. Only the first hop (the base case
`[?x :probe/requires ?y]`) returns matches; the recursive case
`(depends ?z ?y)` either silently no-ops or fails unification under
self-host.

**Implications for the plan:**

The §4.L "transitive dependents" Datalog sketch (described in
[[datahike-query-capabilities-2026-05-25.md]] as relying on a
`transitive-dependents` recursive rule) CANNOT be implemented as a
single Datalog query under CLJS as the substrate ships today. The audit
that claimed "full Datomic-style rule support" appears to have been
based on the JVM datahike rather than the CLJS port (or on a synthetic
test that didn't exercise > 1 hop).

**Fallbacks (pick before starting Phase 2 step 11):**

1. **Manual BFS in CLJS.** Walk `:probe/requires` (here:
   `:seon.ns/requires`) iteratively from the seed set, accumulating
   visited eids until a fixpoint. Each iteration is a flat (non-recursive)
   datahike query. This is what the doc's `affected-test-syms-for-tx`
   helper would do anyway.
2. **Materialize transitive closure on write.** Whenever
   `:seon.ns/requires` is transacted, compute and store
   `:seon.ns/requires-transitive` (a `:cardinality/many` ref attr) so
   the query becomes a flat `[?ns :seon.ns/requires-transitive ?dep]`.
   Cheaper at query time, more bookkeeping at tx time.
3. **Backport the JVM rule expansion** to CLJS datahike — far more
   invasive; not viable for the Phase 1/2 timeline.

I recommend option 1 (manual BFS) for Phase 2 because it keeps the
mechanism in seon code (not in a forked datahike), and because the
expected dependency graph is small (ns count, not datom count).

## Phase 1 / Phase 2 readiness

### Phase 1 — green-light with one minor adjustment

Phase 1's only ambition is a single-var runner that the daemon can call.
All four probes that matter for Phase 1 (1, 2, 3) and one of two for
Phase 2 (4) pass or have clean fallbacks:

- Probe 1's analyzer-side failure does NOT block Phase 1's
  `vars-in-ns` helper if it uses `cljs.test/get-current-env`'s registered
  tests OR walks runtime var meta (`(meta (resolve sym))`) rather than
  the analyzer's `ns-interns`. Update the §4.A spec to specify the
  fallback path.
- Probe 2 — full pass. `with-redefs` is safe in fixtures.
- Probe 3 — full pass. `use-fixtures :each` + `(async done …)` works.

### Phase 2 — green-light with TWO blockers to address

- **Probe 4** — PASS, but Phase 2 step 9 ("instrument agent-defined fns
  at write time") should:
  1. Add `malli.instrument` to `shadow-cljs.edn :bootstrap :entries`.
  2. Use `m/-register-function-schema!` 6-arity (not `mi/-register!`).
  3. Pass `:report seon.error.instrument/report-fn` to `instrument!`.

- **Probe 5 — BLOCKER for Phase 2 step 11** (`affected-test-syms-for-tx`
  / `transitive-dependents`). The Datalog rule path does not work in
  CLJS datahike. Switch the spec to manual BFS in CLJS (fallback #1
  above) OR materialize-on-write (fallback #2) BEFORE writing step-11
  code. The doc claim that the audit confirmed full recursive-rule
  support needs revision.

### Cross-cutting: bootstrap entries gap

Probes 4 and 5 both hit "ns X not available" on `require` under self-host
because the analyzer cache for that ns isn't in the bootstrap. Phase 2
(and any agent code that wants to introspect malli or datahike from
self-host) should add at minimum:

- `malli.instrument`
- `malli.core` (probably already in via deps)
- `datahike.api`

to `shadow-cljs.edn :bootstrap :entries`. Each entry pulls a transitive
subtree, so measure bundle-size impact before adding more.

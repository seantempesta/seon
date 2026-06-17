---
type: research
status: active
tags: [research, agent, cljs]
---

# Usage-example tests via fn metadata + tiered context surfacing

## TL;DR

The canonical, language-native way to attach a runnable example to a function
is the `:test` VAR METADATA — a 0-arg thunk on the var that `clojure.test` /
`cljs.test` `test-var` invokes; `(defn f {:test (fn [] (assert (= 4 (f 2 2))))}
…)` is exactly this idiom, and `with-test` is sugar for it (clojure.test
`with-test`/`deftest` both literally `(vary-meta name assoc :test (fn [] …))`).
NO new `:seon.*` attribute is needed — but seon must distinguish an
example-on-a-`defn` from a standalone `deftest` by FORM HEAD (`defn`/`defn-`
vs `deftest`), because cljs.analyzer collapses BOTH into the same top-level
`:test true` var-map marker (live-verified below), so `deftest-def?` alone
can't tell them apart. The tiering then falls out as DERIVED render queries:
`namespaces-section` (general, every-turn) renders the inline `:test`-example
source attached to each `:seon.fn` row ONLY; `render-namespace` (ns-focus)
renders every `:seon.test`/`:seon.fn` example as it does today.

## Where the framework source lives

clojure.test and cljs.test are NOT git submodules in `reference-code/` (only
downstream consumers like datahike/kaocha/expectations carry their own
`test.clj`). I extracted the canonical source from the local jars:

- `org/clojure/clojure/1.12.1/clojure-1.12.1.jar → clojure/test.clj`
- `org/clojure/clojurescript/1.12.42/clojurescript-1.12.42.jar → cljs/test.cljc`

(unzipped to `tmp/ref-test/` for this pass; line numbers below are from those
files. If a permanent reference is wanted, vendor `clojure.test`/`cljs.test`
into `reference-code/` — they are currently absent.)

## Q1 — the canonical metadata mechanism

**The mechanism is the `:test` var-metadata: a 0-arg fn stored under `:test`
on the var, run by `test-var`.** This is THE Clojure idiom for "a runnable
thing attached to a definition"; `deftest`, `with-test`, and `set-test` are
three thin wrappers that all produce the same `:test` thunk.

### clojure.test `test-var` — what consumes the metadata (test.clj:708-721)

```clojure
(defn test-var
  "If v has a function in its :test metadata, calls that function,
  with *testing-vars* bound to (conj *testing-vars* v)."
  {:dynamic true, :added "1.1"}
  [v]
  (when-let [t (:test (meta v))]
    (binding [*testing-vars* (conj *testing-vars* v)]
      (do-report {:type :begin-test-var, :var v})
      (inc-report-counter :test)
      (try (t)
           …))))
```

`test-vars` (test.clj:723-735) runs `test-var` on every var that has `:test`
metadata, NOT only on `deftest` vars:

```clojure
(doseq [v vars]
  (when (:test (meta v))
    (each-fixture-fn (fn [] (test-var v)))))
```

### `with-test` — attaches an example to a plain defn (test.clj:609-619)

This is the documented "example lives WITH the fn" idiom:

```clojure
(defmacro with-test
  "Takes any definition form (that returns a Var) as the first argument.
  Remaining body goes in the :test metadata function for that Var. …"
  [definition & body]
  (if *load-tests*
    `(doto ~definition (alter-meta! assoc :test (fn [] ~@body)))
    definition))
```

The clojure.test docstring (test.clj:89-98) shows it verbatim:

```clojure
(with-test
    (defn my-function [x y] (+ x y))
  (is (= 4 (my-function 2 2)))
  (is (= 7 (my-function 3 4))))
```

### `deftest` — the SAME thunk, just a standalone var (test.clj:622-637)

```clojure
(defmacro deftest …
  [name & body]
  (when *load-tests*
    `(def ~(vary-meta name assoc :test `(fn [] ~@body))
          (fn [] (test-var (var ~name))))))
```

`set-test` (test.clj:648-657) is the third form — `(alter-meta! (var ~name)
assoc :test (fn [] ~@body))`. All three converge on `:test` = a 0-arg thunk.

### cljs.test — IDENTICAL mechanism, fewer macros (test.cljc:230-246)

```clojure
(defmacro deftest …
  [name & body]
  (when ana/*load-tests*
    `(do
       (def ~(vary-meta name assoc :test `(fn [] ~@body))
         (fn [] (cljs.test/test-var (.-cljs$lang$var ~name))))
       (set! (.-cljs$lang$var ~name) (var ~name)))))
```

**cljs.test has NO `with-test` and NO `set-test`** (grep count 0 in
test.cljc). cljs.test's `test-vars` likewise filters on `:test` metadata
(test.cljc:351, `(filter (fn [[_ v]] (:test v)))`). So in CLJS the portable
way to attach an example to a fn is to put `{:test (fn [] …)}` in the defn's
metadata map directly (the expansion `with-test` would produce), since the
`with-test` macro itself isn't available.

### Is there an EXAMPLE convention (vs a correctness test)?

No. The language has exactly ONE attached-runnable-code mechanism: `:test`.
There is no separate `:example`/`:doc-test` slot in clojure.test or
cljs.test. So an "example" is NOT a different metadata key — it is a
convention ON TOP of `:test`. The right distinguisher is the FORM HEAD:

- A `:test` thunk attached to a `(defn f …)` = a usage example (it lives with
  the fn, is runnable, demonstrates call-shape).
- A standalone `(deftest …)` = a unit/correctness test.

**Recommendation: agents write the example as `:test` metadata on the defn**
— `(defn f {:test (fn [] (assert (= 4 (f 2 2))))} [a b] (+ a b))`. No new
attribute; the example is the var's `:test` thunk, exactly the language idiom.

## Q2 — how seon should detect + store it

### Live-verified analyzer fact (the load-bearing finding)

I evaluated both shapes into the pod's bootstrap compile-state
(`@seon.repl/!compile-state`) and inspected the cljs.analyzer var-map. For a
PLAIN defn carrying `:test` metadata:

```clojure
(defn add4 {:test (fn [] (assert (= 4 (add4 2 2))))} [a b] (+ a b))
```

the analyzer var-map is:

```clojure
{:test          true                                   ; TOP-LEVEL
 :meta {:file … :line … :column … :end-line … :end-column … :arglists …}}
; (:test (:meta var-map)) => nil  — STRIPPED from :meta
```

i.e. **cljs.analyzer's `parse 'def` treats ANY `:test` metadata — whether from
`deftest` or hand-written on a `defn` — identically**: it hoists `:test true`
to the top of the var-map and dissocs the actual test fn from `:meta` (the
same "non-valid-EDN, can't cache" behavior `deftest-def?`'s docstring already
documents, `eval.cljs:782-803`). Verified 2026-06-17 against
`@seon.repl/!compile-state`.

**Consequence:** `deftest-def?` (`eval.cljs:802`, `(true? (:test var-map))`)
returns TRUE for a `(defn f {:test …} …)` too — it CANNOT tell a usage-example
defn from a standalone deftest. The only reliable discriminator already in the
codebase is `defn-form?` (`eval.cljs:905`), which classifies on the FORM HEAD.

### Current-code interaction bug to flag

Today `build-tee-entities` does:

- fn-entities loop (`eval.cljs:984`): `:when (and (not (deftest-def? var-map))
  (defn-form? source))`
- test-entities loop (`eval.cljs:1064-1066`): `:when (deftest-def? var-map)`

A `(defn f {:test (fn [] …)} …)` form has `deftest-def? = true` AND
`defn-form? = true`. Under the current code it therefore:

1. is EXCLUDED from `:seon.fn` rows (the `(not (deftest-def? var-map))` guard
   drops it) — **the function is lost from the program graph**, and
2. is INCLUDED as a `:seon.test` row whose `:seon.test/source` is the WHOLE
   `(defn f {:test …} …)` form (a fn def masquerading as a test row).

So the moment an agent attaches an example via `:test` metadata today, seon
mis-files it. This must be fixed for usage-examples to work.

### The detection/storage design (no bespoke attr)

Discriminate on form head, derived — never a stored "is-example" flag:

- **`(deftest …)`** — `deftest-def?` true AND `defn-form?` false → `:seon.test`
  row, exactly as today (the standalone correctness test).
- **`(defn f {:test …} …)`** — `deftest-def?` true AND `defn-form?` true →
  a `:seon.fn` row (the fn IS persisted/replayed/overridable as a normal
  fn). The example is part of `:seon.fn/source` (the byte-faithful form text
  ALREADY carries the `{:test (fn [] …)}` map — Finding 1 of the
  simplification audit: `:source` is the exact single defining form). On
  replay the `(defn f {:test …} …)` re-evals and the example thunk reattaches
  to the var for free.
- **plain `(defn f …)` with no `:test`** — `:seon.fn` row, no example.

So the fix to `build-tee-entities` is to gate the fn-entities loop on
`(defn-form? source)` ONLY (drop the `(not (deftest-def? var-map))` clause —
it predates strict persistence and is now redundant since `defn-form?` already
excludes a `(deftest …)` head), and gate test-entities on `(and (deftest-def?
var-map) (not (defn-form? source)))` — i.e. a deftest is a top-level `:test`
var whose source head is NOT `defn`. That single head test routes the example
defn to `:seon.fn` and the standalone deftest to `:seon.test`, with NO new
attribute and NO stored marker.

**Whether a `:seon.fn` HAS an example is DERIVED at render** (reactive-context:
no stored flag): parse `:seon.fn/source`, look for a `:test` key in the
metadata map / a `with-test` wrapper. The example body can be sliced from the
source for rendering. Nothing extra is persisted; the example follows the form
and self-heals if the agent removes it.

## Q3 — tiered surfacing (the render design)

Two surfaces, two tiers, both DERIVED (no new stored flags, no new mechanism —
just which rows/sub-forms a section function selects):

### General context — `namespaces-section` (`ctx.cljs:955`)

Today this renders included nses by recency; agent-authored nses
reconstitute from `:seon.fn`/`:seon.schema`/`:seon.test` rows
(`reconstituted-ns-source`, `ctx.cljs:911`), and core nses render as a shallow
tag. The 241 indexed `:seon.test` rows are the bulk that the user wants OUT of
the every-turn surface.

Change: in the reconstituted/agent-authored branch, **drop the
`:seon.test/source` member rows from the concat** (`ctx.cljs:931-933`) — a
standalone `deftest` is NOT general-context material — and instead, for each
`:seon.fn` member, **append its inline `:test`-example body** (sliced from
`:seon.fn/source`) as a compact `;; example:` block next to the fn. For the
core (shallow-tag) nses, no change — they already don't inline members.

Concretely the general-context fn render becomes:

```
[fn seon.foo/bar]  (bar x y)
;; example: (assert (= 4 (bar 2 2)))
```

— the call-shape teaches itself, the full deftest suite is absent. Because the
example lives in `:seon.fn/source` (which the section already has), this is a
pure parse-at-render derivation: a small helper `fn-example-source` that reads
the `:test` thunk body out of a defn's metadata map. No query change, no schema
change.

### Namespace-focus context — `render-namespace` / `pull-ns-data`
(`ctx.cljs:1092`, `render-one-ns-ai` at `:1184`)

`pull-ns-data` already pulls `{:seon.test/_ns …}` for the ns (`ctx.cljs:1122`)
and `render-one-ns-ai` already renders every `:seon.test` via `test-block-ai`
(`ctx.cljs:1201-1202`). **Keep this unchanged** — when the agent switches into
a namespace to work, it sees ALL tests (the standalone `:seon.test` deftests
PLUS the example-bearing `:seon.fn` rows, whose source already includes the
`:test` example). That is the "all tests for the focused ns" tier with zero new
code; the only adjustment is that example defns now correctly land as
`:seon.fn` rows (Q2), so they render in the fn section with their example
visible, while standalone deftests render in the test section.

### Why this is "new section behavior, not a new mechanism"

Per reactive-context: the tiering is two section FUNCTIONS selecting different
DERIVED views of the same program-graph rows. The general surface selects
`:seon.fn` rows + their inline example sub-form; the ns-focus surface selects
all `:seon.fn` + `:seon.test` rows. Remove an example from a defn and it
vanishes from the general surface on the next render; delete a deftest and it
vanishes from ns-focus. Nothing stored needs clearing.

## Concrete recommendation

1. **What an agent writes:** attach the usage example as `:test` var-metadata
   on the defn — `(defn f {:test (fn [] (assert (= 4 (f 2 2))))} [a b]
   (+ a b))`. This is the language-native idiom (`with-test`'s expansion),
   runnable by `cljs.test/test-var`, and lives WITH the fn. NO `:seon.*`
   attribute is introduced. Standalone correctness suites stay as `(deftest
   …)`. (Optionally teach a one-line `seon.test/example` sugar macro that
   expands to the `{:test (fn [] …)}` defn-meta form for ergonomics — but it
   is sugar over the language mechanism, not a new attribute.)

2. **How it's detected/stored:** discriminate on FORM HEAD (`defn-form?`), not
   on `deftest-def?` (which the live probe proves fires for BOTH shapes).
   Route example-bearing `(defn …{:test …}…)` to a normal `:seon.fn` row (the
   `:test` thunk rides along in `:seon.fn/source`, replays + reattaches for
   free, is overridable like any fn); route standalone `(deftest …)` to a
   `:seon.test` row. Fix the current interaction bug in `build-tee-entities`
   (`eval.cljs:984` / `:1064`): gate fn rows on `(defn-form? source)` and test
   rows on `(and (deftest-def? var-map) (not (defn-form? source)))`. Whether a
   fn HAS an example is DERIVED at render from its source — no stored flag.

3. **How it tiers:** GENERAL context (`namespaces-section`) renders the inline
   `:test`-example body next to each `:seon.fn` and OMITS standalone
   `:seon.test/source` rows — call-shape without the full suite. NAMESPACE
   context (`render-namespace`) renders ALL tests for the focused ns (the
   existing `pull-ns-data` + `render-one-ns-ai` test section, plus the
   now-correctly-filed example defns). Both are derived section selections; no
   new stored state, consistent with reactive-context.

## Unresolved / to verify in implementation

- **`with-test` portability:** confirmed cljs.test does NOT ship `with-test`.
  If agents (or seon) want the `(with-test (defn …) …)` SURFACE syntax in
  CLJS, seon must define a 3-line `with-test` macro (the clojure.test
  expansion) in a seon ns, or agents write the `{:test (fn [] …)}` defn-meta
  directly. Recommend the latter (less machinery) unless ergonomics demand the
  macro.
- **Slicing the example out of `:seon.fn/source` for the compact general
  render** needs a small reader-based helper (`fn-example-source`) — reading
  the `:test` thunk body from the defn's metadata map. Straightforward
  (the source is one form; read it, pull `(:test attr-map)`), but it IS a
  parse-at-render, so memoize if the namespaces-section render proves hot
  (reactive-context's caching escape hatch).
- The `:test`-meta fn body, being a fresh fn object each eval, already perturbs
  `var-digest` (`analyzer_info.cljs:84-89`) — so editing only the example
  re-tees the `:seon.fn` row (desired: the example change is captured). No
  action needed; noting it so it isn't mistaken for spurious churn.

## Cross-references

- `docs/prds/agent-runtime/research/simplification-audit-2026-06-17.md` — B9
  (this improvement), Finding 1 (`:seon.fn/source` is one byte-faithful form),
  the strict-persistence form-head classification this builds on.
- `docs/seon/concepts/reactive-context.md` — derive at render, no stored flags.
- `docs/seon/concepts/code-as-data-runtime.md` — source IS the corpus; the
  example replays with the fn.
- clojure.test (jar `clojure/test.clj`): `with-test` 609-619, `deftest`
  622-637, `set-test` 648-657, `test-var` 708-721, `test-vars` 723-735,
  docstring example 89-98.
- cljs.test (jar `cljs/test.cljc`): `deftest` 230-246, `:test`-filter in
  test-vars 351; no `with-test`/`set-test`.
- `src/seon/eval.cljs` — `deftest-def?` 782-803, `defn-form?` 905-917,
  `build-tee-entities` fn-loop 984, test-loop 1064-1071.
- `src/seon/analyzer_info.cljs` — `var-digest` `:test` handling 84-89,
  `var-projection` 165.
- `src/seon/ctx.cljs` — `namespaces-section` 955, `reconstituted-ns-source`
  911 (member-rows concat 931-933), `pull-ns-data` 1092, `render-one-ns-ai`
  1184 (tests 1201), `test-block-ai` 1172.
- `src/seon/test/runner.cljs` — `:seon.test` schema registration 126-162.

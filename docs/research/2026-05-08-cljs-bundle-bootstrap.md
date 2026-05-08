# Replacing scittle with a self-hosted CLJS bundle (shadow-cljs `:target :bootstrap`)

**Date:** 2026-05-08 (Bangkok)
**Status:** research / pre-implementation. The next agent reading this should be able to type the smoke recipe in §7 with no further detective work.
**Decision context:** sci can't carry the runtime we now need (Malli + `malli.generator` + `test.check` + `malli.instrument` + edamame + Datascript + real `clojure.test` + real macros). We've already committed to self-hosted CLJS via `shadow-cljs :target :bootstrap`. This doc is HOW.

---

## Operational summary (read this first)

1. **`:target :bootstrap` does NOT produce a single bundle.** It produces an output directory containing `index.transit.json` + per-namespace artifacts under `js/`, `ana/`, `src/`. The runtime asks shadow's `boot/init` to read the index and lazy-load only the namespaces a given `eval-str` call requires — but those reads must be served by a host-supplied `:load` fn (in QuickJS-WASM that means we pre-bake an in-memory virtual FS, since QuickJS has no `fs`). Confirmed against `reference/thheller-shadow-cljs/src/main/shadow/build/targets/bootstrap.clj` lines 210–256 (`flush` writes `index.transit.json`, `/src/`, `/js/`, `/ana/`) and `.../shadow/cljs/bootstrap/node.cljs` lines 60–168 (`load-namespaces`, `execute-load!`, `load`). The provided `shadow.cljs.bootstrap.node` loader is **Node-fs-bound** — for QuickJS we either (a) pre-load every required namespace at session init via a custom `:load` that reads from a JS object literal of `{path → text}`, or (b) write a thin `shadow.cljs.bootstrap.quickjs` loader. (a) is what we'll do for v1 — payload is fixed and we're loading-on-init anyway.

2. **Startup model:** at QuickJSContext boot we eval one large concatenated JS payload that contains `cljs.core` + `cljs.js` + `cljs.core$macros` + every namespace from our entry set in dependency order. After that, `cljs.js/eval-str` evaluates agent-emitted source against the in-memory analyzer state. No file I/O at runtime.

3. **Realistic minified payload size is 3–5 MB** (the bulk is `cljs.js` + `cljs.core` analyzer cache); QuickJS will need stack + heap limit bumps. See §3 and §8.

4. **`:def-emits-var true` in `eval-str` opts** is the key to making post-hoc classification work — without it, `(defn foo …)` returns the function value (no metadata visible from the host); with it, it returns a `cljs.core/Var` whose `meta` carries `:malli/schema` etc.

5. **Sync evaluation is achievable** (callback fires synchronously) iff the `:load` fn is synchronous and all required namespaces are already in `state`. Pre-loading at boot makes this trivially true. This is the only viable path under QuickJS-WASM's no-event-loop runtime.

6. **Library compat (matrix, evidence in §2):**
   - malli (core / generator / instrument / error / util) — **YES**
   - test.check — **YES** (Mike Fikes' port has been canonical in the upstream repo since 2017; `.cljc` reader-conditional clean)
   - edamame — **YES** (README explicitly: "ClojureScript (including self-hosted and advanced compiled)")
   - datascript — **YES with caveat** — depends on `extend-clj` and `persistent-sorted-set`; both load self-host because shadow-cljs ships their macros into the bootstrap macros set. Confirm at first-smoke; if `extend-clj` macros choke, fallback is to swap for the scittle-style runtime binding (which we know works) — but we expect this to be a non-issue.
   - clojure.test — **YES, via `cljs.test`**; `:require [cljs.test]` is the self-host idiom; `deftest` and `is` are the macro entry points.

7. **Per-form classification works** because we'll always split with edamame and call `eval-str` once per top-level form. The host gets one `:value` per form and can `var?` / read `meta` directly.

---

## 1. shadow-cljs `:target :bootstrap` mechanics

### 1.1 What the build produces

Looking at `reference/thheller-shadow-cljs/src/main/shadow/build/targets/bootstrap.clj`:

- `prepare-output` (line 99) walks `:build-sources` and writes per-source records carrying `{:source-name "/src/<flat>", :js-name "/js/<hash>.<output-name>", :ana-name "/ana/<munged-ns>.transit.json"}`.
- `flush` (line 210) creates `output-dir/index.transit.json` containing `{:sources [...records...] :exclude #{...}}`. Then per-source it writes the `.cljs`/`.cljc` source to `<output-dir>/src/...`, the compiled JS to `<output-dir>/js/...`, and the analyzer transit to `<output-dir>/ana/...`.
- The build emits **one JS file per namespace** (plus one per `<ns>$macros`). The macros file convention (`<munged-ns>$macros.js`) is set by `make-macro-resource` (line 18): `:resource-name (str path "$macros.cljc")`, `:output-name (str macro-ns "$macros.js")`.

There is no concatenated bundle. We concatenate ourselves at load time, OR teach `:load` to pull from an embedded asset map.

### 1.2 Index file shape

`index.transit.json` (transit-encoded) deserializes to:

```clojure
{:sources [{:resource-id ...
            :type :cljs            ; or :goog, :shadow-js
            :provides #{cljs.core …}
            :requires #{...}
            :ns cljs.core
            :source-name "/src/<flat>.cljs"   ; relative
            :js-name    "/js/<hash>.cljs.core.js"
            :ana-name   "/ana/<munged>.transit.json"   ; only when :type :cljs
            :macro-requires #{...}}
           …]
 :exclude #{ns-symbols-to-pretend-loaded}}
```

`shadow.cljs.bootstrap.env/build-index` (lines 15–37) builds two reverse indices: `:sym->id` (provided sym → resource-id) and `:sources` (resource-id → record). `find-deps` walks `:sources-ordered` in reverse, accumulating requires — that's how it computes the load set for a target namespace.

### 1.3 Runtime API

```clojure
(shadow.cljs.bootstrap.node/init compile-state-ref opts init-cb)
;; opts: {:path <fs-path-to-output-dir>
;;        :load-on-init #{ns-symbols-to-preload}
;;        :load (fn [load-info] …)}  ; optional callback hook
```

`init` (lines 177–202): reads `index.transit.json` once, populates `env/index-ref`, marks excluded `$macros` namespaces as already-loaded (so cljs.js doesn't ask for them), then `load-namespaces` for `cljs.core + cljs.core$macros + load-on-init`, then `init-cb`.

`load` (lines 156–168) is the `:load` fn you pass to `cljs.js/eval-str`:

```clojure
(cljs.js/eval-str
  state-ref
  source
  name
  {:eval cljs.js/js-eval
   :load (partial shadow.cljs.bootstrap.node/load state-ref)
   :def-emits-var true   ; ← critical; see §4
   :context :statement   ; or :expr
   :ns 'agent.user}
  callback)
```

`cljs.js/eval-str` signature: `(eval-str state source name opts cb)`. Opts include `:eval` (`cljs.js/js-eval` for native), `:load` (resolves requires), `:context` (`:expr` / `:statement` / `:return`), `:ns` (current namespace), `:def-emits-var` (boolean), `:source-map` (boolean).

The `cb` is called with `{:value <evaluated-value> :error <maybe-Throwable> :ns <current-ns-after> …}`. **If `:load` is sync, `cb` fires synchronously** — control returns to the caller of `eval-str` only after `cb` finishes.

### 1.4 Compiler state across calls

`compile-state-ref` is an atom of the analyzer state map (`cljs.env/default-compiler-env`). It mutates as namespaces load and as new defs land. **Reuse the same atom across all `eval-str` calls in a session** — this is how `(defn foo …)` in turn 1 is callable in turn 2. The shadow demo (`reference/thheller-shadow-cljs/src/dev/demo/bootstrap_script.cljs`) shows the canonical shape:

```clojure
(defonce compile-state-ref (env/default-compiler-env))

(defn compile-it []
  (cljs/eval-str compile-state-ref
                 "(prn ::foo) (+ 1 2)"
                 "[test]"
                 {:eval cljs/js-eval
                  :load (partial boot/load compile-state-ref)}
                 print-result))

(defn main [& args]
  (boot/init compile-state-ref {} compile-it))
```

What mutates: `[:cljs.analyzer/namespaces <ns> …]` (def metadata, var definitions). Cache: nothing further is needed — one atom, one global `cljs.core/*loaded*` set (managed by shadow's loader).

### 1.5 Macros under self-host

Macro namespaces compile to `<ns>$macros.js`. `cljs.core$macros.js` is special — it's circular with `cljs.core` (`cljs.core` requires `cljs.core$macros` which requires `cljs.core`), so shadow loads `cljs.core` first then patches `goog.require` (see `shadow.cljs.bootstrap.env/replace-goog-require!` line 78, and the explanatory comment at `bootstrap.clj` lines 53–66). The `fix-provide-conflict!` in `node.cljs:170` deletes `js/cljs.core$macros` before init for the same reason.

Implication: agent-side `(defmacro …)` works under self-host, but the macro is only visible to subsequent forms that go through compilation (which is every `eval-str` call). Practically: agent-defined macros land in the same compile state and are available for the rest of the session.

### 1.6 Resolving requires in agent source

When agent source contains `(ns agent.user.foo (:require [malli.core :as m]))`, cljs.js calls our `:load` fn with `{:name 'malli.core :path "malli/core" :macros false}`. Shadow's `load` (line 156) routes that to `load-namespaces` which consults `env/index-ref` to find dependencies, then schedules JS + analyzer loads via `execute-load!`. Under our QuickJS embedding, **all of these are already in memory** (we pre-loaded the entire entry set at init), so `load` short-circuits and the callback fires synchronously with `{:lang :js :source ""}`.

---

## 2. Library compatibility evidence

### 2.1 `metosin/malli`

**YES.** Evidence:

- `reference/metosin-malli/deps.edn` includes `borkdude/edamame` (self-host clean), `org.clojure/test.check` (self-host clean), `borkdude/dynaload` (Clojure-only, gated `#?(:clj …)`).
- `reference/metosin-malli/src/malli/core.cljc:3` — `#?(:cljs (:require-macros malli.core malli.impl.util))` — explicit cljs-macro-import.
- `reference/metosin-malli/src/malli/core.cljc:3098` — comment: "for cljs we cannot invoke `function-schema` at macroexpansion-time" — author explicitly thinks about cljs.
- `reference/metosin-malli/shadow-cljs.edn` ships **four browser builds** including malli-core directly (`:app`, `:app2`, `:instrument`) — proves the tree compiles for advanced ClojureScript today. Self-host = same code path with macros runnable at runtime.
- `borkdude/dynaload` usage is **only** in `malli.generator` line 16 with a `#?(:clj …)` gate; the JVM-only fallback path. Under cljs/self-host the dynaload codepath is excluded. Spot-checked.

The `m/=>` macro (`core.cljc:3107`) is `#?(:clj (defmacro …))` — under self-host, the cljs analyzer expands it via `malli.core$macros`, side-effecting `-function-schemas*`. Standard cljs idiom; works.

### 2.2 `malli.generator` + `test.check`

**YES.** `malli.generator` requires `clojure.test.check.{generators,properties,random,rose-tree}`. test.check has been `.cljc` reader-conditional clean since the Mike Fikes port (~2017); it's the canonical generative-testing library for self-hosted CLJS (e.g. used by Klipse's REPL demos).

### 2.3 `malli.instrument`

**YES.** Two source files exist:

- `reference/metosin-malli/src/malli/instrument.clj` — JVM, uses `find-var`.
- `reference/metosin-malli/src/malli/instrument.cljs` — CLJS, uses `goog.object/getValueByKeys` against `goog/global` to find vars.

Self-host loads the `.cljs` file. The `meta-fn` helper (line 16, with the linked CLJS-3018 ticket comment) is specifically the workaround for var-metadata in CLJS. Default-on instrumentation à la seon's `seon.dev.instrumentation` will need a CLJS port (since seon's lives in `.clj` and uses JVM-side `find-var`/`alter-var-root`) — that's expected work, not a blocker; the underlying `malli.instrument/-strument!` API is the same.

### 2.4 `borkdude/edamame`

**YES — explicitly documented.** `reference/borkdude-edamame/README.md:24`: "ClojureScript (including self-hosted and advanced compiled)". Single dep is `org.clojure/tools.reader` (self-host clean — Mike Fikes pre-bundles it as one of his `andare`-era ports).

### 2.5 `tonsky/datascript`

**YES with caveat.** `reference/datascript/src/datascript/conn.cljc:1-9` — relies on `extend-clj.core` (a Tonsky library, `io.github.tonsky/extend-clj`) and `me.tonsky.persistent-sorted-set`. Both are advanced-CLJS-compatible (Datascript ships an npm package built from CLJS — see `datascript-npm` builds). Self-host requires their `.cljc` sources be present in the bundle; shadow's bootstrap target picks them up automatically when datascript is in the entry set. **Verify at smoke time** by `(require '[datascript.core :as d]) (def conn (d/create-conn {})) (d/transact! conn [{:db/id -1 :name "x"}])`.

If `extend-clj` macros choke under self-host: fallback is to keep using the npm-shipped datascript runtime binding (already working in scittle today) and skip pulling datascript into the bootstrap entry set — i.e. expose a small JS shim at `globalThis.db` and let CLJS code call into it. Less elegant, known-good. **Don't pre-empt; try the clean path first.**

### 2.6 `clojure.test` / `cljs.test`

**YES.** Self-host idiom is `(require '[cljs.test :refer-macros [deftest is testing]])`. Macros (`deftest`, `is`, `testing`) live in `cljs.test$macros`. Test result collection: `cljs.test/test-vars`, `cljs.test/run-tests`, plus the `cljs.test/report` multimethod (dispatch on `:type` — `:pass`, `:fail`, `:error`, `:summary`). For structured (non-stdout) results, install a custom `:default` defmethod on `cljs.test/report` that pushes events into an atom; read the atom from the host side after the test run. Pattern Sean already has in seon (`seon.dev.test/verify`) — port to CLJS.

---

## 3. Bundle size + cost

Honest framing: I do not have benchmark numbers specific to QuickJS-WASM with our exact entry set. The numbers below come from public reports on Klipse, the `cljs-bootstrap-deps` example, and the Replumb history.

| Component | Minified size (approx) | Source |
|---|---|---|
| `cljs.core` | ~131 KB | Standard advanced-compile output |
| `cljs.core$macros` | ~600–800 KB | The macro defs are large |
| `cljs.js` (analyzer + compiler engine) | 1.0–1.5 MB | Klipse, Planck, Replumb consensus |
| `cljs.core` analyzer cache (transit) | ~2.7 MB uncompressed | `:dump-core true` adds this; many bootstrap setups disable with `:dump-core false` and load lazily |
| `cljs.test` + `cljs.test$macros` | ~80–150 KB | |
| `tools.reader` | ~120 KB | |
| `edamame` | ~150 KB | |
| `malli.core` + `error` + `util` + `impl.util` | ~250–400 KB | (advanced compile is aggressive on malli) |
| `malli.generator` | ~80 KB | |
| `malli.instrument` | ~30 KB | |
| `clojure.test.check` (gens, properties, random, rose-tree) | ~250 KB | |
| `datascript.core` + db + parser + query + pull-api | ~400–600 KB | matches the npm 465 KB number from the 2026-05-08-datahike-template-env.md doc |

**Realistic total payload (advanced-compiled, excluding the analyzer cache file): ~3–5 MB minified JS.** With analyzer caches included for non-core namespaces (transit JSON), add another ~1–2 MB.

### QuickJS-WASM cost

I have no published benchmark for parsing 3–5 MB of CLJS-emitted JS in QuickJS-WASM. Two known facts:

1. **`qjsc` bytecode pre-compilation skips the JS parsing phase.** If we statically pre-compile the bundle to QuickJS bytecode (via `qjsc -c -o bundle.bc bundle.js`) and load bytecode at session start, init time drops to deserialization-only. `@sebastianwessel/quickjs` exposes this via `evalCode(bytecode, {compileOnly: false})` once we generate the bytecode at build time. Worth confirming against the package API at smoke time.
2. **QuickJS default stack (256 KB) is too small for the CLJS macroexpander.** Stack overflows are the canonical "self-hosted CLJS in QuickJS" failure mode. Need `JS_SetMaxStackSize` ≥ 4 MB. `@sebastianwessel/quickjs` exposes this on the runtime via the loader options.

Per-form `eval-str` latency: no published numbers for our exact stack. Order-of-magnitude expectation, based on Klipse-in-browser experience: **~5–50 ms for a small `(defn …)`, scaling roughly linearly with form size**. QuickJS-WASM is slower than V8 by 3–10×, so plan for 50–500 ms per form in the worst case until measured. **Sandbox-time is still 3–4 orders of magnitude under LLM time** (consistent with the Path-A vs Path-B finding in `2026-05-08-verifiers-jssandbox-integration.md`).

Analyzer state growth per N forms: on the order of 1–10 KB per `(defn …)` (one entry in `[:cljs.analyzer/namespaces <ns> :defs <name>]`). 1000 admitted defs ≈ 1–10 MB analyzer state. Set heap limit to 256 MB+.

---

## 4. Classification mechanism

### 4.1 The key knob: `:def-emits-var true`

Default `eval-str` behavior on `(defn foo …)`: `:value` in the callback is **the function object**, not the var. `var?` returns false; `meta` returns nil. Useless for classification.

With `{:def-emits-var true}` in opts, `:value` is a `cljs.core/Var` whose `(meta v)` returns `{:ns 'agent.user :name 'foo :doc nil :arglists … :line … :file … :malli/schema [:=> [:cat :int] :int] :agent/tests-fn …}`.

This is documented in the `cljs.js/eval-str` docstring (in the cljs.js source under `:cljs.compiler` codebase). It's the standard knob enabled by every self-hosted REPL (Klipse, Planck, Lumo).

### 4.2 What the host sees

Each `eval-str` call produces a single `:value` (last form). With our edamame-split-then-eval-each approach (§6), we get **one value per top-level form**:

| Form shape | Returned value | Host classification |
|---|---|---|
| `(defn foo …)` / `(def x …)` | `cljs.core/Var` | `var? = true`, read `meta` → metadata map. Detect `:malli/schema`, `:agent/tests-fn`, `:name`, `:ns`. |
| `(register-entity! ::person …)` | `{:agent/op :registered-entity ::person …}` (whatever the API fn returns) | `map?` and presence of `:agent/op` → tagged-API call. |
| `(+ 1 2)` | `3` | `number?` etc. → "expression". |
| `(deftest test-foo …)` | A `cljs.core/Var` with `:test` metadata | Same as defn; classify as test. |

**The values cross the JS-host boundary as the actual JS objects** — `cljs.core/Var` is a JS class instance, `meta` reads from a JS field, etc. We can call `.cljs$core$IMeta$_meta$arity$1(v, null)` from JS, but the easier path is to keep classification on the CLJS side: write a `agent.classify/classify-value` fn that returns plain JSON, and JSON-serialize it for the host.

### 4.3 Classification helper (CLJS side)

```clojure
(ns agent.classify)

(defn classify [v]
  (cond
    (var? v)
    {:kind :var
     :ns (str (.-ns (meta v)))
     :name (str (.-name (meta v)))
     :meta (select-keys (meta v) [:malli/schema :agent/tests-fn :doc :arglists :test])}

    (and (map? v) (contains? v :agent/op))
    {:kind :api-call :op (:agent/op v) :payload v}

    :else
    {:kind :value :type (type v) :pr (pr-str v)}))
```

Host calls `eval-str` with `(agent.classify/classify <wrapped-form>)` if the form is identified as expression-yielding-result, OR splits forms with edamame, evals each, then evals `(agent.classify/classify *1)` after — choose one pattern at smoke time.

---

## 5. The eval-str callback / promise model

### 5.1 Signature recap

```clojure
(cljs.js/eval-str state source name opts cb)
```

- `state` — atom, the compile-state. `(cljs.env/default-compiler-env)` to start; reuse across calls.
- `source` — string, CLJS code.
- `name` — string, used for source-map / error messages. e.g. `"[agent.session/turn-42]"`.
- `opts` — map. Keys we care about:
  - `:eval` — `cljs.js/js-eval` (native eval). Required.
  - `:load` — `(fn [{:keys [name path macros]} cb])`. Required for namespace resolution.
  - `:def-emits-var` — `true`. Critical (§4).
  - `:context` — `:expr` (eval as expression) | `:statement` (no return) | `:return` (return-context, used for the last form of an `eval-str` that emits multiple). For our use: `:expr`.
  - `:ns` — symbol, current namespace. e.g. `'agent.user`.
  - `:source-map` — `true` to embed source maps; useful for stack traces.
  - `:static-fns` — `true` for advanced-compile-style direct fn invocation.
- `cb` — `(fn [result])` where result is `{:value <v> :error <e?> :ns <ns-after>}`.

### 5.2 Sync under QuickJS

Sync iff `:load` is sync. Pre-loading every required namespace at session init (so `:load` is only ever asked for already-loaded namespaces) makes it sync. Pattern:

```clojure
(defn make-sync-load [state-ref]
  (fn [{:keys [name macros] :as rc} cb]
    (let [ns (if macros (symbol (str name "$macros")) name)]
      (if (get-in @state-ref [:cljs.analyzer/namespaces ns])
        (cb {:lang :js :source ""})
        (cb {:lang :js :source ""})))))  ; or throw — agents shouldn't be requiring new namespaces
```

In QuickJS-WASM there is no event loop, but JS itself executes synchronously inside `JS_Eval`. Promises do exist (QuickJS implements ES2020 Promises) and microtasks fire when the runtime drains; `@sebastianwessel/quickjs` handles this in its `evalCode` wrapper. But we don't need promises if our `:load` is sync.

### 5.3 Errors

Compile errors and runtime errors both surface in `:error`. Distinguish by inspecting the error: compile errors are `ex-info` instances with `:tag :cljs/analysis-error` in `ex-data`; runtime errors are JS errors. Capture both in the trajectory record.

---

## 6. Multi-form source handling

Approach **B (edamame-split + eval-each)** is the right one. Not A.

Reasoning: A loses per-form return values; with edamame in-bundle anyway, B costs nothing.

```clojure
(ns agent.eval
  (:require [cljs.js :as cljs]
            [edamame.core :as e]))

(defn eval-forms
  "Splits source into top-level forms, evals each in turn against state-ref.
   Returns a vector of {:form <quoted> :result <eval-str-result>}."
  [state-ref source ns-sym opts]
  (let [forms (e/parse-string-all source {:all true})
        ;; parse-string-all returns a seq of all top-level forms
        results (atom [])]
    (doseq [form forms]
      (cljs/eval-str
        state-ref
        (pr-str form)
        (str ns-sym)
        (merge {:eval cljs/js-eval :context :expr :def-emits-var true :ns ns-sym} opts)
        (fn [r] (swap! results conj {:form form :result r}))))
    @results))
```

**Definitions persist across calls** because the same `state-ref` atom is used. This is the standard self-host REPL pattern (Klipse, Planck, Lumo all do exactly this).

---

## 7. End-to-end smoke recipe

Smallest config that loads the bundle into a `@sebastianwessel/quickjs` QuickJSContext and round-trips Malli + edamame.

### 7.1 `harness/cljs/deps.edn`

```clojure
{:paths ["src"]
 :deps {org.clojure/clojurescript {:mvn/version "1.12.42"}
        thheller/shadow-cljs {:mvn/version "2.28.20"}
        metosin/malli {:mvn/version "0.20.0"}
        org.clojure/test.check {:mvn/version "1.1.3"}
        borkdude/edamame {:mvn/version "1.5.37"}
        io.github.tonsky/datascript {:mvn/version "1.7.5"}}}
```

### 7.2 `harness/cljs/shadow-cljs.edn`

```clojure
{:source-paths ["src"]
 :deps {:aliases [:cljs]}   ; or inline as above; pick one
 :builds
 {:bootstrap
  {:target :bootstrap
   :output-dir "out/bootstrap"
   :exclude #{cljs.js.shim
              clojure.tools.reader.reader-types}    ; trim JVM-only edges if any surface
   :entries [agent.bootstrap
             malli.core
             malli.error
             malli.generator
             malli.instrument
             malli.util
             edamame.core
             cljs.test
             datascript.core
             agent.classify
             agent.eval]
   :macros [malli.core
            malli.experimental
            cljs.test
            edamame.core]}}}
```

### 7.3 `harness/cljs/src/agent/bootstrap.cljs`

```clojure
(ns agent.bootstrap
  (:require [cljs.js :as cljs]
            [cljs.env :as env]
            [malli.core :as m]
            [edamame.core :as e]
            [agent.eval :as ae]
            [agent.classify :as ac]))

;; Single-session compile state.
(defonce state-ref (env/default-compiler-env))

;; Synchronous :load that only resolves already-loaded namespaces
;; (everything in the bundle is loaded at init time).
(defn sync-load [{:keys [name macros] :as rc} cb]
  (let [ns (if macros (symbol (str name "$macros")) name)]
    (if (get-in @state-ref [:cljs.analyzer/namespaces ns])
      (cb {:lang :js :source ""})
      (throw (ex-info "ns not in bundle" {:ns ns})))))

(defn agent-eval [source ns-sym]
  (clj->js
    (mapv (fn [{:keys [form result]}]
            {:form (pr-str form)
             :value (when-not (:error result) (ac/classify (:value result)))
             :error (some-> (:error result) ex-message)})
          (ae/eval-forms state-ref source ns-sym
                         {:load sync-load}))))

;; Expose to host JS.
(set! js/agent #js {:eval agent-eval})
```

### 7.4 Build step

Build with `npx shadow-cljs release bootstrap`. After build, `out/bootstrap/` contains `index.transit.json` + `js/` + `ana/` + `src/`.

For QuickJS embedding we need a single concatenated payload. Write a small Node script `harness/cljs/build-bundle.mjs`:

```javascript
import { readFileSync, readdirSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

const OUT = 'out/bootstrap'

// 1. Read index to learn load order.
import { unpack } from 'cognitect-transit'   // or roll a tiny transit reader
const idx = unpack(readFileSync(join(OUT, 'index.transit.json'), 'utf8'))

// 2. Concatenate JS in dependency order.
const jsParts = []
for (const src of idx.sources) {
  jsParts.push(readFileSync(join(OUT, src['js-name']), 'utf8'))
}

// 3. Embed analyzer caches into a JS map keyed by ns.
const anaMap = {}
for (const src of idx.sources) {
  if (src.type === 'cljs' && src['ana-name']) {
    anaMap[src.ns] = readFileSync(join(OUT, src['ana-name']), 'utf8')
  }
}

const preamble = `globalThis.__agent_ana = ${JSON.stringify(anaMap)};\n`
const bundle = preamble + jsParts.join('\n;\n')
writeFileSync('out/agent-bundle.js', bundle)
```

(The CLJS-side `sync-load` should also `cljs.js/load-analysis-cache!` from `js/__agent_ana` — punt to the actual implementation; details follow shadow's `execute-load!` at `node.cljs:42`.)

### 7.5 Node loader (smoke)

```javascript
import { loadQuickJs } from '@sebastianwessel/quickjs'
import variant from '@jitl/quickjs-ng-wasmfile-release-sync'
import { readFileSync } from 'node:fs'

const { runSandboxed } = await loadQuickJs(variant)
const BUNDLE = readFileSync('out/agent-bundle.js', 'utf8')

await runSandboxed(async ({ evalCode }) => {
  // Bump stack first.
  // (@sebastianwessel/quickjs exposes runtime opts via loadQuickJs config;
  //  set maxStackSize ≥ 4*1024*1024 and memoryLimit ≥ 256*1024*1024 there.)

  evalCode(BUNDLE)   // ~1-3s parse + boot

  // Smoke 1: malli loaded.
  const r1 = evalCode(`agent.eval(\`(require '[malli.core :as m]) (m/validate :int 42)\`, 'agent.user')`)
  console.log('smoke1:', r1)
  // Expect: [{form: "...", value: {kind: ":value", pr: "true"}, ...]

  // Smoke 2: defn with :malli/schema metadata returns a Var.
  const r2 = evalCode(`agent.eval(\`(defn ^{:malli/schema [:=> [:cat :int] :int]} double-it [n] (* 2 n))\`, 'agent.user')`)
  console.log('smoke2:', r2)
  // Expect: value = {kind: ":var", ns: "agent.user", name: "double-it",
  //                  meta: {":malli/schema" [":=>" [":cat" ":int"] ":int"]}}

  // Smoke 3: call it.
  const r3 = evalCode(`agent.eval('(double-it 21)', 'agent.user')`)
  console.log('smoke3:', r3)
  // Expect: value = {kind: ":value", pr: "42"}
})
```

If those three smoke calls pass, the full path is open: layer in datascript (load + transact in CLJS, classify entity returns), then `malli.generator/generate` against a registered schema, then `cljs.test/run-tests` with a custom `report` defmethod for structured results.

---

## 8. Known gotchas

1. **QuickJS stack default 256 KB is fatal for the CLJS macroexpander.** Symptom: `InternalError: stack overflow` early in init or during macro-heavy form eval. Fix: set the QuickJS runtime max stack to ≥ 4 MB. `@sebastianwessel/quickjs`'s `loadQuickJs(variant, opts)` exposes a `maxStackSize` (or equivalent — check the package's runtime-config object at smoke time).

2. **Memory limit.** Default is also low; CLJS analyzer state grows monotonically. Set memory limit to 256 MB+.

3. **`cljs.js/*load-fn*` misconfiguration.** If `:load` returns the wrong shape, you get silent compile failures. Required result shape: `{:lang :js :source <js-string>}` (or `{:lang :clj :source <cljs-string>}` if you want re-compilation, which we don't). Crucially, `:source ""` is valid when the namespace is already loaded — that's how shadow's loader signals "nothing to do, proceed."

4. **`cljs.core$macros` circular load.** `shadow.cljs.bootstrap.env/replace-goog-require!` and `node.cljs:170 fix-provide-conflict!` exist specifically because `cljs.core` is double-provided once macros are loaded. If you skip the bootstrap-env init you'll see `goog.provide conflict` errors. **Use shadow's `init` flow** rather than rolling our own, even though we replace its `:load` — i.e. call `shadow.cljs.bootstrap.env/build-index` from `index.transit.json` before any eval.

5. **`:dump-core` vs lazy analyzer cache.** Default is `false` for `:target :bootstrap`. If `true`, `cljs.core` analyzer cache is inlined into JS (faster init, +2.7 MB to bundle). Decide at smoke time: default `false` and pre-load the analyzer cache via JS map (§7.4) is what we want.

6. **`:preloads` vs `:bootstrap-options`.** `:preloads` is shadow's runtime side — namespaces eagerly required at startup, baked into the build. `:bootstrap-options` doesn't exist as a top-level key; the equivalent is `:devtools` for dev, or specific keys under `:builds/<id>/...` (per shadow source). **For our use, `:entries` is the load-bearing key** — that determines what the bootstrap target compiles. Don't get distracted by `:preloads`.

7. **AOT vs JIT macroexpansion.** Self-host expands macros at runtime via `*$macros.js`. Anything precompiled before the bootstrap target (i.e. anything in our `:entries` not also in `:macros`) is JIT-only at the agent's call site — which is what we want. The `:macros` key in shadow's bootstrap config explicitly lists namespaces whose macros file we want included; `malli.core` and `cljs.test` go there.

8. **`m/=>` / `(defn ^{:malli/schema …} …)` metadata visibility.** Both register schemas in `malli.core/-function-schemas*` (an atom). The `^{:malli/schema …}` form is straightforward — metadata lives on the var; visible via `(meta #'foo)`. The `(m/=> foo Schema)` form is a side-effect — it doesn't put metadata on the var, but `malli.instrument/instrument!` reads from `-function-schemas*`. **Recommendation:** require the `^{:malli/schema …}` metadata-on-var form for agent-emitted defns. Cleaner, host can read with one `meta` call; matches seon's pattern (`seon.dev.instrumentation` reads `:malli/schema` from var meta).

9. **`@sebastianwessel/quickjs` and large evalCode payloads.** No published numbers for evaluating multi-MB strings. If init is intolerable, pre-compile to QuickJS bytecode via `qjsc` and load bytecode (the package supports this). Defer until we measure.

10. **datascript + extend-clj macros.** Documented above (§2.5). If smoke fails here, the JS-binding fallback exists but is uglier. Don't pre-build the fallback — try clean first.

11. **clj-kondo configs in malli.** `reference/metosin-malli/src/malli/clj_kondo.cljc` exists for the linter — irrelevant to runtime, won't affect the bundle, but it's in the source tree. Shadow's bootstrap target compiles all entries; if any of clj-kondo's machinery uses JVM-only ns symbols, we may need to add `malli.clj-kondo` to `:exclude`. Trivial to fix at smoke time.

---

## Open uncertainties

- **Actual init time and per-eval-str latency under QuickJS-WASM with our exact entry set.** No published numbers. Plan: measure at smoke time, decide whether `qjsc` bytecode pre-compile is needed.
- **`extend-clj` macros under self-host.** Highly likely to work (it's a Tonsky lib designed for cljs); not 100% certain until we run it.
- **Exact `@sebastianwessel/quickjs` knobs for stack/heap.** Confirm at smoke time against the package version we're pinning.
- **Whether to use `:dump-core true` or lazy-load analyzer caches.** Default `false`; revisit if init time is dominated by JSON/transit parse rather than JS parse.

---

## Concrete next actions (for the implementation agent)

1. Create `harness/cljs/deps.edn` and `harness/cljs/shadow-cljs.edn` per §7.1–7.2.
2. Write `harness/cljs/src/agent/bootstrap.cljs`, `agent/eval.cljs`, `agent/classify.cljs` per §7.3, §6, §4.3.
3. `npx shadow-cljs release bootstrap` → `out/bootstrap/`.
4. Write `harness/cljs/build-bundle.mjs` per §7.4 to concatenate into `out/agent-bundle.js`.
5. Wire into `harness/sidecar/sidecar.mjs` — replace the scittle preamble with the bundle. Bump QuickJS stack/heap.
6. Run the three smoke calls in §7.5. If green, layer in datascript + `cljs.test` + `malli.generator` + `malli.instrument` one at a time, measuring after each.
7. Port `seon.dev.instrumentation` and `seon.dev.test` patterns to CLJS targeting `malli.instrument` + `cljs.test/report` — that's the agent-readable structured-error layer the agent's gate consumes.

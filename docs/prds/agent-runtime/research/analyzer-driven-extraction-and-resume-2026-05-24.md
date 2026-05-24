---
type: research
status: completed
tags: [research, agent, cljs, analyzer, resume]
---

# Analyzer-driven extraction + resume

## TL;DR

**Use the analyzer for `defn`/`ns`. Use registry-atom-diff for `schema/register!`. Both work today via globalThis; expand `:bootstrap :entries` to make the analyzer authoritative for the substrate the agent calls daily.**

Sean correction integrated: the pod's bootstrap classpath is OUR config (`:bootstrap :entries` in `shadow-cljs.edn`). The narrow current set is intentional but trivially expandable. Adding `seon.schema`, `malli.core`, `malli.registry`, `cljs.analyzer.api` lets the agent `(require …)` them from inside `cljs.js/eval-str`. Live-confirmed already today: `(seon.schema/register! :probe.schema/ticker :string)` from agent eval succeeded (returns `:ok true`, registry-count went 265→266) — the runtime path works via globalThis; expansion just adds the analyzer story alongside.



Concrete findings from probing the live pod:

1. **`@compile-state` is authoritative for everything the CLJS reader+analyzer sees.** After a successful `cljs.js/eval-str` of `(defn analyze [{:keys [ticker]}] …)`, `(get-in @state [:cljs.analyzer/namespaces 'probe.demo :defs 'analyze])` contains: `:name probe.demo/analyze`, `:arglists '([{:keys [ticker]}])`, `:fn-var true`, `:method-params`, `:max-fixed-arity`, `:variadic?`, plus `:meta {:arglists … :doc … :file :line :column …}` AND any user-supplied metadata including **`:malli/schema` verbatim**. (Q1.)

2. **`schema/register!` is invisible to the analyzer** — it's a fn call, not a special form. `:defs` stays empty after eval. We must either keep source-parsing for schemas OR diff `seon.schema`'s internal `*schemas` registry atom before/after eval. The registry-diff approach is preferable: it captures schemas registered via any means (literal `register!`, `register-all!`, or future programmatic registration). (Q1, Q7, Q9c.)

3. **`cljs.analyzer.api` exists and is the right wrapper.** `find-ns`, `ns-publics`, `ns-resolve`, `ns-interns`, `all-ns`, `remove-ns`, `resolve` are all available in CLJS (the `:clj`-only fns are `analyze`, `parse-ns`, `analyze-file` — irrelevant to us). All thin wrappers over `get-in @state [::ana/namespaces …]`. (Q2.)

4. **`(ns foo …)` is destructive on the analyzer state.** Re-evaling a `(ns foo …)` form wipes `foo`'s `:defs`. So an agent that re-enters `(ns alice.foo)` then re-defs a subset of fns will silently lose the un-redef'd fns from the analyzer cache. (Confirmed via REPL probe.) Implication for resume: re-eval order MUST be `:seon.ns` first (one transaction's worth), then ALL of that ns's `:seon.fn` / `:seon.schema` entities — never interleave. (Q5.)

5. **`:requires` is the dep-edge source for topo-sort.** Per-ns, `(get ns :requires)` returns `{alias-or-self-sym → resolved-ns-sym}`. Edges = `(set (vals requires))` minus self. Plus `(set (vals (:uses ns)))` for `:refer`'d nses. Plus `(:require-macros)` if we ever support macro-bearing nses. (Q5.)

6. **Bootstrap-CLJS classpath is configurable — expand it.** Today's `:bootstrap :entries` is intentionally minimal (cljs.core, cljs.test, clojure.{set,string,walk}). To make the analyzer authoritative for cross-ns refs we add `seon.schema`, `malli.core`, `malli.registry`, and any other agent-vocabulary substrate to `:bootstrap :entries` in `shadow-cljs.edn`. Shadow already compiled their analyzer caches into `.shadow-cljs/builds/client/dev/ana/{seon,malli}/` — those are the input to the bootstrap target. After expansion, the agent's `cljs.js/eval-str` can `(require '[seon.schema])` directly, the analyzer sees the def of `register!`, and we can drop the `:analyze-deps false` + globalThis-resolution workaround. (Q6.)

7. **Shared compile-state is fine for v1.** All agents see one analyzer namespace map. Cross-agent visibility is automatic (agent B's `(seon.agent.alice/foo)` resolves). Per-agent compile-states cost ~30MB cache reload each + lose the desired cross-agent visibility. Defer the question. (Q6.)

8. **Resume-by-tx-id is wrong as soon as redefinition happens.** Platform's current `replay-program-graph!` walks `:seon.fn` entities in creation order. If agent re-defined an upstream fn at t=10 (creation t=2), tx-id order replays the OLD source. The DB already de-dupes via `:seon.fn/sym` identity, but resume needs "the latest source per sym, loaded in topo order over the dep graph". (Q5.)

9. **Storage shape — source IS the source of truth, but persist a small derived projection** (`:seon.fn/arglists` as string, `:seon.fn/doc` as string, `:seon.fn/private?` as bool, optionally `:seon.fn/refs` as a vector of `:seon.fn/sym` strings) so query/render doesn't need to walk the analyzer. Recompute on resume from the freshly-evaled state. (Q7.)

## Q1 — Analyzer state inspection

### Probe setup

```
(require '[seon.repl :as repl] '[seon.eval :as seval])
(.then (repl/dev-init!) (fn [_] ...))
```

`@@repl/!compile-state` is a map with top-level keys:
`[:cljs.analyzer/namespaces :cljs.analyzer/constant-table :cljs.analyzer/data-readers :cljs.analyzer/externs :options]`

After init, `:cljs.analyzer/namespaces` had 41 ns entries (cljs.core, cljs.user, cljs.analyzer.*, etc.).

### Probe 1 — canonical `defn`

Eval'd: `(ns probe.demo) (defn analyze [{:keys [ticker]}] {:signal :hold})`

`(get-in state [:cljs.analyzer/namespaces 'probe.demo :defs 'analyze])` keys:

```
[:protocol-inline :meta :name :file :end-column :method-params :protocol-impl
 :arglists-meta :column :variadic? :line :ret-tag :end-line :max-fixed-arity
 :fn-var :arglists]
```

Concrete values:

```
{:name probe.demo/analyze
 :fn-var true
 :arglists (quote ([{:keys [ticker]}]))
 :method-params ([p__23])
 :variadic? false
 :max-fixed-arity 1
 :meta {:file seon.dynamic :line 1 :column 23
        :end-line 1 :end-column ...
        :arglists (quote ([{:keys [ticker]}]))}}
```

Note `:meta :file` is `seon.dynamic` — the fixed filename `seon.eval/raw-eval` passes to `cljs.js/eval-str`. So `:meta :line` / `:column` are per-form-string, not per-file. Acceptable for our use.

### Probe 2 — variants in one eval batch

Form: `(ns probe.variants (:require [clojure.string :as str])) (defn- private-fn [x] x) (defn with-attr "docs" {:malli/schema [:=> [:cat :int] :int]} [x] (* x 2)) (defn multi-arity ([x] x) ([x y] [x y])) (def some-val 42) (defn ^:async async-fn [x] (await (js/Promise.resolve x)))`

Results:

```
{:def-syms [private-fn with-attr multi-arity some-val async-fn]
 :private-fn-meta  {:private true :arglists '([x])}
 :with-attr-meta   {:doc "docs" :arglists '([x])
                    :malli/schema [:=> [:cat :int] :int]}    ; ← captured verbatim
 :multi-arity      {:variadic? false :max-fixed-arity 2
                    :method-params [[x] [x y]]}
 :some-val         {:name probe.variants/some-val
                    :meta {:file seon.dynamic :line 1 :column 203 ...}}
                   ; NO :fn-var, NO :arglists → it's a def, not a defn
 :async-fn-meta    {:async true :arglists '([x])}}
```

**Key takeaways:**

- `:malli/schema` from the attr-map IS preserved on `:meta`. We can read it directly.
- `:fn-var` is the distinguisher between `defn` (true) and `def` (absent/false).
- `:private true` (from `defn-`), `:async true` (from `^:async`) all flow through to `:meta`.
- Multi-arity exposes `:variadic?`, `:max-fixed-arity`, `:method-params`. `:arglists` covers it too.

### Probe 3 — diff approach

Snapshot `:defs` keys before/after eval:

```
before: #{private-fn with-attr multi-arity some-val async-fn}
eval:   (defn newly-added [x] (inc x))   ; in :ns 'probe.variants
after:  #{... newly-added}
diff:   #{newly-added}                    ; → the new var(s)
```

**This is the canonical detect-and-tee primitive.** Snapshot `(set (keys defs))` before each form, diff after.

Edge case: a single form can introduce N defs (e.g. `(do (defn a [] 1) (defn b [] 2))`). The diff returns all new syms; tee one `:seon.fn` entity per. Re-definitions show up in the diff only as `seq` membership change of the value, not key change — for those, compare the analyzer maps element-wise (different `:meta :line` or `:arglists` ⇒ changed).

### Probe 4 — `(ns foo ...)` redefinition is destructive

Eval'd a second `(ns probe.demo (:require [clojure.string :as str])) (defn analyze …)` after probe.demo already had `analyze` defined.

After:
```
{:defs [analyze]                  ; ← only analyze, the prior probe.variants defs are wiped
 :requires {str clojure.string clojure.string clojure.string}}
```

**The `(ns foo ...)` form RESETS `[:cljs.analyzer/namespaces foo]` to a fresh ns analysis with only `:defs` from forms eval'd after it in the same eval string.** So if the agent does `(ns alice.foo (:require [bar]))` after previously defining `alice.foo/baz`, the analyzer forgets `baz`. (The compiled JS at globalThis is unaffected — `baz` still resolves at runtime.) Implication for resume below in Q5.

### Probe 5 — `schema/register!` is invisible to the analyzer, but the registry atom IS the answer

Live-probed via mcp:

```clojure
;; Agent-eval (no require needed — seon.schema is on globalThis from the host bundle)
(seon.schema/register! :probe.schema/ticker :string)
;; => {:ok true ...}

;; Registry diff (before=265, after=266, new key = :probe.schema/ticker)

;; Multi-register in one form:
(let [before (set (keys (mr/schemas (m/-registry))))]
  (.then (seval/eval state
            "(seon.schema/register! :probe.schema/volume :int)
             (seon.schema/register! :probe.schema/price [:and :int [:fn pos?]])" {})
         (fn [_]
           (set/difference (set (keys (mr/schemas (m/-registry)))) before))))
;; => #{:probe.schema/volume :probe.schema/price}
```

**Atom-diff handles every case `extract-schema-key` can't**: multi-register-per-form, `register-all!`, computed keys, future programmatic registration. The registry atom is the runtime equivalent of "what new defs landed".

### Probe 6 — `schema/register!` is invisible at the analyzer level (separate story)

Eval'd: `(ns probe.schema (:require [seon.schema :as schema])) (schema/register! ::ticker :string)`

```
{:has-ns? true
 :defs []                          ; ← nothing — fn calls don't add :defs
 :requires {schema seon.schema seon.schema seon.schema}}
```

**Confirmed: `schema/register!` leaves zero analyzer fingerprint.** It's a fn call, not a def. The schema goes into seon.schema's `*schemas` defonce atom at runtime — which Probe 5 showed IS observable. Atom-diff is the right primitive for schemas; analyzer-diff is the right primitive for defns.

Aside on bootstrap classpath: my initial probe of `(require '[seon.schema :as schema])` from inside `seval/eval` errored with "Could not require seon.schema" because today's `:bootstrap :entries` omits seon.schema. Sean's correction: that's an entries-list decision, not a hard pod limit. See Q6 for the expand-the-entries fix.

## Q2 — cljs.analyzer.api

File: `/Users/sean/src/clojurescript/src/main/clojure/cljs/analyzer/api.cljc`

Public CLJS-available read API (`:clj` vs `:cljs` clauses checked):

| Fn | Signature (cljs) | Returns | Use |
|----|------------------|---------|-----|
| `all-ns` | `[]` / `[state]` | seq of ns syms | enumerate everything |
| `find-ns` | `[sym]` / `[state sym]` | ns-analysis map or nil | "does this ns exist in the analyzer?" |
| `the-ns` | `[sym]` / `[state sym]` | throws if not found | strict variant |
| `ns-interns` | `[ns]` / `[state ns]` | `{sym → var-analysis-map}` of `(merge :macros :defs)` | "all interned vars in ns" |
| `ns-publics` | `[ns]` / `[state ns]` | `ns-interns` minus `:private` | "public surface of ns" |
| `ns-resolve` | `[ns sym]` / `[state ns sym]` | var-analysis-map | "the analysis map for this var" |
| `remove-ns` | `[ns]` / `[state ns]` | swaps state | undef whole ns |
| `resolve` | `[env sym]` | var-analysis-map | full resolution including macros |
| `current-state` / `current-ns` / `current-file` | `[]` | dynamic var values | inside compiler passes |

`:clj`-only (NOT available in CLJS / bootstrap): `analyze`, `parse-ns`, `analyze-file`, `forms-seq`, `read-analysis-cache`. These do source-file→analysis. Irrelevant — we already have the analysis as a side-effect of `cljs.js/eval-str`.

**Recommendation for the analyzer-info helper (Q9c):** wrap `find-ns` + `ns-publics` + `ns-resolve` (state-aware 2-arg forms) into a tiny `seon.analyzer-info` module that takes our `@!compile-state` and exposes:

```clojure
(get-ns state ns-sym)            ; → ns-analysis map
(get-defs state ns-sym)          ; → {sym → var-map}, includes private
(get-publics state ns-sym)       ; → {sym → var-map}, excludes :private
(get-var state ns-sym sym)       ; → var-map or nil
(ns-deps state ns-sym)           ; → #{ns-sym ...} from :requires + :uses
(diff-defs before-snapshot state ns-sym)  ; → {:added [...] :changed [...]}
```

Wraps `cljs.analyzer.api/*` where it exists, falls back to `get-in` where it doesn't.

**Caveat:** `cljs.analyzer.api` itself isn't on the bootstrap classpath (host-side eval test for `ana-api/all-ns` errored). But it IS available to seon's HOST compile (it lives under `cljs/analyzer/api.cljc`, which shadow includes for the host bundle). So `seon.analyzer-info` is a host-side module — extract-and-tee runs in `seon.eval/eval-batch!` which IS host-side. Good.

## Q3 — Editor/toolchain prior art

### clj-kondo

Analysis output schema (from clj-kondo docs): for each file it produces `:var-definitions`, `:var-usages`, `:namespace-definitions`, `:namespace-usages`, `:keywords`, plus `:locals` / `:local-usages` when requested. Each entry carries `:filename :row :col :ns :name :arity :doc :varargs-min-arity :private :defined-by` etc. It's **purely static** — runs over source, doesn't need a running runtime. The data shape (one entry per definition, ns-scoped, with arity / docstring / source location) is essentially the same shape `cljs.analyzer/namespaces` produces.

Difference: clj-kondo needs to re-implement the reader and a partial analyzer because it doesn't have a live JVM/CLJS to delegate to. We DO have a live CLJS analyzer. We are clj-kondo + free.

### cider-nrepl

`cider.nrepl.middleware.info/info` (JVM Clojure) calls `(or (find-ns ns) ...)` and `(ns-resolve ns sym)`, then pulls `:doc :file :line :column :arglists :added :name :ns` off the var's metadata. That's the JVM-equivalent of what we just probed on `cljs.analyzer.api`. cider's stance for cljs is the same: query the analyzer (it goes through `cljs.analyzer.api`).

### shadow-cljs runtime info

shadow's `shadow.cljs.devtools.api/find-resources-using-ns` and friends walk the build state, not the analyzer per-se. For "what just got defined" info during a REPL eval, shadow surfaces the same `cljs.js/eval-str` callback shape we already get.

**Confirmation:** every reputable tool queries the live analyzer rather than re-parsing source for the structured info. The rewrite-clj approach in `seon.code` is the outlier among toolchains.

## Q4 — shadow-cljs hot-reload model

Investigated via reading shadow's source under `/Users/sean/src/shadow-cljs/` and prior research notes (`shadow-node-runtime-2026-05-23.md`).

### Build state shape

`shadow.build.data/init-build-state` produces a state map carrying, among others:

- `:sources` — `{resource-id → {:resource-name :ns :requires :provides :file …}}`
- `:provide->source` — `{ns-sym → resource-id}`
- `:deps-ordered` — the topo-sorted list of resource-ids (computed by `shadow.build.resolve/resolve-entries`)
- `:build-sources` — what's actually compiled into the output
- `:compiler-env` — the `cljs.env` compiler atom (analyzer cache + options) ← this is OUR `@!compile-state` analog

`:requires`/`:provides` per resource gives the dep graph. shadow runs topo-sort eagerly at resolve time so subsequent compile/reload passes can iterate in dep order.

### Hot-reload flow

1. File watcher (`shadow.cljs.devtools.server.fs_watch`) emits `[:project-fs-update changes]` event.
2. Worker (`shadow.cljs.devtools.server.worker.impl/do-process-msg :project-update`) recomputes affected sources: `(shadow.build.macros/macros-used-by-build-sources …)` + `(shadow.build.classpath/find-resources-using-ns …)` build the affected set.
3. Re-runs the compile pipeline on JUST the changed-and-affected set. Output is the new JS for those nses.
4. Devtools websocket (`shadow.cljs.devtools.server.runtime`) sends `{:type :build-complete :info {:sources-with-cycles … :compiled-sources [...] }}` to all connected runtimes.
5. Runtime-side (`shadow.cljs.devtools.client.node` / `.browser`) receives, iterates `:sources-to-load` in order, evals each JS source via `goog.globalEval`. Hooks (`^:dev/before-load`, `^:dev/after-load`) wrap the iteration.

**Key idea:** shadow computes the affected set in **the watcher (host JVM)**, then ships ordered eval instructions to the runtime. The runtime is dumb: "eval these JS strings, in this order, with these hooks". The dep-graph intelligence lives outside the runtime.

### Applied to seon's resume

Direct analogy: the DB is the "filesystem". `:seon.ns` entities are "files". We have:

- per-ns source ⇒ shadow's per-resource source
- `:seon.ns/requires` (parsed from `:source`'s `(ns … :require)` clause) ⇒ shadow's per-resource `:requires`
- compile-state ⇒ shadow's `:compiler-env`

Resume = "compute topo order over the persisted ns set, then for each ns: re-eval its `:seon.ns/source`, then re-eval the `:source` of every `:seon.fn`/`:seon.schema` whose `:ns` ref points at it." That's exactly shadow's `:sources-to-load` model.

### Function we'd want from shadow that doesn't exist

Shadow doesn't expose a clean "given this set of changed sources and the current state, return the affected sources in load order" fn — it's tangled into `shadow.build.compiler/compile-all`. The topo-sort utility exists at `shadow.build.resolve` but is build-state-shape specific. For seon, we'll roll our own ~30-line topo over a small `{ns-sym → #{dep-ns-sym}}` map. Trivial — Kahn's algorithm. Don't pull in shadow.build as a dep.

### Hot-load (v2/v3 sketch)

The bigger Sean-named goal: agent A commits a fn, agent B's runtime picks it up. The shadow model says: agent B subscribes to DB tx-listener events on `:seon.fn`. When a tx commits a fn whose ns is loaded in B's runtime, B reads the new source and re-evals it (same path as resume, but a single fn rather than a graph traversal). Hooks (`^:dev/before-load`) get refit as "rebind agent state before the new fn lands". This is just incremental resume.

## Q5 — Resume load order

### Toy graph probe

I built up nses with cross-refs:

- `probe.demo` (no requires)
- `probe.variants` (requires `[clojure.string :as str]`)
- `probe.norm` (requires `[clojure.set]`, `[clojure.string :as s]`, `[clojure.walk :refer [postwalk]]`)

`:requires` shape per Probe 4 / extra probe:

```
probe.norm   :requires {clojure.set   clojure.set
                        s             clojure.string
                        clojure.string clojure.string
                        clojure.walk  clojure.walk}
             :uses     {postwalk clojure.walk}
```

Edges = `(set (vals requires))` ∪ `(set (vals uses))`, minus the ns itself and minus `clojure.*` / `cljs.*` (which are always pre-loaded). For probe.norm: `#{clojure.set clojure.string clojure.walk}` → all pre-loaded → no agent-ns edges.

### Sketch: topo-sort

```clojure
(defn ns-deps
  "Agent-ns deps of one persisted :seon.ns entity. Pre-loaded /
   substrate nses are excluded — they're always available."
  [{:seon.ns/keys [requires uses] :as _ns-ent} known-ns-set]
  (-> (set (concat (vals requires) (vals uses)))
      (disj (:seon.ns/name _ns-ent))                  ; drop self-loops
      (set/intersection known-ns-set)))                ; only agent nses

(defn topo-order
  "Kahn's algorithm over the agent's :seon.ns set. Returns nses in
   load order. Throws on cycles (CLJS forbids them — surface loudly)."
  [ns-entities]
  (let [known (set (map :seon.ns/name ns-entities))
        edges (into {} (for [e ns-entities]
                         [(:seon.ns/name e) (ns-deps e known)]))
        ;; Standard Kahn ...
        ])
```

(Implementation is ~30 lines. The interesting bit is the data, not the algorithm.)

### Within a single ns

After `(ns alice.foo ...)` runs, the order of intra-ns `:seon.fn` / `:seon.schema` re-evals doesn't structurally matter — the analyzer accepts forward references at top-level (it'll emit an `:undeclared-var` warning that the host's `truly-undeclared?` will swallow once the var is actually defined in the same batch). To be safe AND match the agent's authoring order, sort by `:seon.fn/created-at` ascending within a ns.

### Where tx-id order fails (concrete repro)

Setup:

- t=1: `(ns alice.utils)` `(defn helper [x] (* 2 x))`  → `:seon.fn alice.utils/helper` v1
- t=2: `(ns alice.main (:require [alice.utils :as u]))` `(defn run [{::keys [n]}] (u/helper n))`  → `:seon.fn alice.main/run`
- t=3: agent decides helper is wrong, redefines: `(defn helper [x] (* 3 x))`  → `:seon.fn alice.utils/helper` v2 (DB has v2 only because `:seon.fn/sym` is an identity attr)

Tx-id-ordered replay would do `alice.utils/helper v2` first (it was the LAST eval'd into that sym), then `alice.main/run`. By accident, that's correct in this case.

Now reorder: agent does t=1 main, t=2 utils, t=3 main again. Same vars, all in order `t=3 (run) > t=2 (helper) > t=1 (run)`. The persisted entities sort `run created-at=t1, helper created-at=t2`. Tx-id resume = run-first, then helper. The analyzer errors on `(u/helper n)` — `u` isn't required yet because `(ns alice.main (:require [alice.utils :as u]))` HAS to run first, but in tx-id order we run the fn-source before the ns-source.

The hidden assumption in "tx-id order works" is "the agent always emits `(ns …)` before any of that ns's `(defn …)` in the SAME turn". That holds for canonical agent flow but is one redefinition cycle away from breaking. Topo by `:seon.ns` deps + intra-ns by `:created-at` is robust.

### Cycles

CLJS analyzer rejects cyclic ns deps at compile time. If two `:seon.ns` entities mutually require, the original eval that committed them must have failed (so one of them won't be in the DB), OR the agent wrote the deps in a non-cyclic way originally and a later redefinition introduced the cycle. In the second case, the on-disk source IS cyclic but the analyzer accepted the deltas in piecewise non-cyclic chunks. Resume should detect and surface the cycle as a `seon.resume/cycle-detected` log entry, not silently degrade.

### Multi-form ns sources

`:seon.ns/source` per Platform's current design is JUST the `(ns …)` form. The defs live in `:seon.fn/source`. Good. Source-of-truth per entity is one form; resume re-runs them in order.

## Q6 — Bootstrap classpath + multi-agent compile-state

### The fix Sean named: expand `:bootstrap :entries`

`seon.repl/!compile-state` is a single `defonce` atom shared across ALL agent sessions in the pod. `cljs.js/eval-str` mutates it.

Today's `:bootstrap :entries` in `shadow-cljs.edn`:

```
:entries [cljs.core cljs.test clojure.set clojure.string clojure.walk]
:macros  [cljs.core cljs.test]
```

The config explicitly says "Add more per agent vocabulary growth once measured." That's now. **For analyzer-authoritative detect-and-tee + cross-ns reference resolution, expand to include the substrate the agent legitimately calls:**

```clojure
:entries [cljs.core cljs.test
          clojure.set clojure.string clojure.walk
          ;; Schema substrate — analyzer-driven schema tee + agent ergonomics
          seon.schema
          malli.core
          malli.registry
          ;; DB API — agent's primary verb surface
          seon.db
          ;; Eval reflection — agent can introspect its own program graph
          cljs.analyzer.api
          ;; Other agent-vocabulary as needed (seon.fs, seon.id, …)
          ]
:macros  [cljs.core cljs.test malli.core]   ; malli.core has :require-macros for the schema DSL
```

### What this buys

| Capability | Today (narrow bootstrap) | After expansion |
|---|---|---|
| `(require '[seon.schema])` from agent eval | FAILS (`ns not available`) | works |
| Analyzer state populated for `(schema/register! ::k :string)` | only `:requires` updated, no `:defs` entry, no record of the schema | still no `:defs` (it's a call, not a def) — but seon.schema's analyzer cache IS loaded, so we can read `(ana-api/ns-publics state 'seon.schema)` to know what schemas exist, and the `*schemas` atom is reachable for atom-diff |
| Cross-ns refs like `(seon.db/transact! …)` from agent code | emit `:undeclared-var` warning, swallowed by `truly-undeclared?` + globalThis lookup | analyzer resolves cleanly; no warning; `:analyze-deps true` becomes viable |
| Agent can call `(cljs.analyzer.api/ns-publics state 'my.ns)` to introspect itself | not possible | works — agent can write reflection-aware code |
| Resume verification ("did the re-eval produce the same arglists?") | indirect (parse output, compare) | analyzer state IS the answer |

### Cost of expansion

Each `:entries` ns pulls its transitive subtree into `out/bootstrap/` as compiled JS + analysis caches. Sizes (measured from `.shadow-cljs/builds/client/dev/ana/`):

Measured (`du -sh` on the existing :client ana caches, which are the input to a bootstrap rebuild):

- `seon.schema.cljc` → 54KB transit cache (file)
- whole `seon/` ana subtree → 2.7MB (includes all of seon — but only the entries you list + their transitive deps would land in `out/bootstrap/`)
- whole `malli/` ana subtree → 2.0MB (malli.core alone is 1.3MB)
- current `out/bootstrap/` total → 12MB

`out/bootstrap/` is loaded into the pod's analyzer at boot via `load-all-analysis-caches!` — every additional MB delays startup by a measurable amount (the loader iterates the directory and `transit-read`s each file). The malli ANALYSIS cache is 2MB, not 200KB — bigger than I first guessed. Still acceptable but worth measuring boot-time delta after the change. The seon.schema entry alone is cheap (~50KB cache + its tiny compiled JS).

`seon.db` adds datahike-cljs and lmdb-cljs into the bootstrap subtree — that's MB-scale. Defer until needed; the globalThis fallback handles `seon.db/*` calls perfectly today.

**Recommended PR (small, immediate):** add `seon.schema`, `malli.core`, `malli.registry`, `cljs.analyzer.api` to `:bootstrap :entries`. Rebuild `out/bootstrap/`. `load-all-analysis-caches!` in `seon.eval/init-bootstrap!` already walks `out/bootstrap/ana/*.transit.json` unconditionally, so it picks up the new caches automatically. Zero code change in seon.eval.

### What still needs `:analyze-deps false` + globalThis after expansion

Anything we deliberately keep OUT of the bootstrap bundle for size — initially the heavyweight bits like datahike, parts of ai/deepseek, web-server code. The hybrid stays: analyzer-authoritative for the substrate API surface; globalThis-fallback for the heavyweight tail. `truly-undeclared?` keeps doing its job, just for a smaller set of cases.

### Implication for detect-and-tee

Both schemas AND defns are now analyzer-driven in v1:

- **defn**: snapshot/diff `[:cljs.analyzer/namespaces ns :defs]` (Q1 Probe 3). Works today, doesn't need bootstrap expansion.
- **schema**: with `seon.schema` in bootstrap, the agent's eval'd `(schema/register! ::k v)` runs through the real registered fn whose body mutates `*schemas`. We expose `seon.schema/current-keys` (3-line PR) and atom-diff before/after eval. This handles literal `register!`, `register-all!`, computed-key cases, programmatic registration — anything that hits the atom.

### Probe — cross-agent analyzer visibility

Not exhaustively probed (the pod only has one effective "agent" today), but the SHAPE answers it: `:cljs.analyzer/namespaces` is keyed by ns-sym. Two agents writing to different nses (`seon.agent.alice` vs `seon.agent.bob`) get separate def maps. They share visibility into shared substrate nses (cljs.core etc.) AND into each other's nses (if alice does `(seon.agent.bob/foo)`, it resolves at runtime via globalThis, and the analyzer's `truly-undeclared?` check swallows the warning).

### Recommendation

- **v1: keep shared compile-state.** Free cross-agent visibility matches Sean's "merging environments into runtimes" framing. The cost (one agent can clobber another's ns by re-`(ns …)`-ing it) is real but not catastrophic — the DB still has the canonical source, and resume picks up the persisted version.
- **v3: per-agent compile-state IF cross-agent contention is observed.** Each compile-state is ~30MB after bootstrap-cache load. Cost is RAM + losing the implicit "alice can see bob's fns" — which would require an explicit publish step.
- **Don't conflate "shared compile-state" with "shared runtime"** — runtime (globalThis JS) is always shared because Node is one process. Compile-state separation would only affect what the ANALYZER warns about, not what the runtime can call.

## Q7 — Storage shape

### Decision

**Persist source as the source of truth.** Persist a small derived projection so query/render is cheap. Re-derive the projection on resume.

Recommended attrs per entity type:

#### `:seon.fn`

| Attr | Type | Source | Why persist |
|------|------|--------|-------------|
| `:seon.fn/sym` | `:string` (identity) | from analyzer `:name` or extract-defn-name + current-ns | the canonical id |
| `:seon.fn/ns` | `:seon.db/ref` | derived from sym's namespace part | ref into `:seon.ns` |
| `:seon.fn/source` | `:string` | the raw source string of the form | source of truth |
| `:seon.fn/arglists` | `:string` | `(pr-str (:arglists var-map))` | render/query without re-analysis |
| `:seon.fn/doc` | `:string` (optional) | `(:doc (:meta var-map))` | render |
| `:seon.fn/private?` | `:boolean` (optional) | `(:private (:meta var-map))` | filter |
| `:seon.fn/specced?` | `:boolean` (optional) | `(some? (:malli/schema (:meta var-map)))` | publish gate (Q8) |
| `:seon.fn/created-at` | `:inst` | tx time | intra-ns ordering on resume |

NOT persisted (re-derivable on resume): `:method-params`, `:max-fixed-arity`, `:variadic?`, `:ret-tag`, `:protocol-impl` — these are only useful inside the analyzer.

Optional `:seon.fn/refs [:vector :string]` — vars this fn references. Derived from analyzer's pass output (the `:children` walk). Useful for D8 reference graph but expensive to compute and brittle. **Defer to v2** — leave it out of v1 storage, add later when D8 ships.

#### `:seon.schema`

| Attr | Type | Source | Why |
|------|------|--------|-----|
| `:seon.schema/key` | `:keyword` (identity) | the keyword literal | the canonical id |
| `:seon.schema/ns` | `:seon.db/ref` | from key's namespace | ref |
| `:seon.schema/source` | `:string` | the raw `(schema/register! ...)` form | re-eval on resume |
| `:seon.schema/created-at` | `:inst` | | ordering |

Don't persist the malli value itself — it can contain fns (predicates, `[:fn ...]`) which won't survive serialization. Source IS the value.

#### `:seon.ns`

| Attr | Type | Source | Why |
|------|------|--------|-----|
| `:seon.ns/name` | `:keyword` (identity) | the ns sym as a keyword | id |
| `:seon.ns/source` | `:string` | the `(ns …)` form | source of truth |
| `:seon.ns/requires` | `[:vector :keyword]` | `(vals requires)` deduped, minus self | topo-sort input |
| `:seon.ns/created-at` | `:inst` | | |

`:seon.ns/requires` is persisted (not re-derived) because resume needs it BEFORE re-evaling the source to compute load order.

### Migration risk

Persisting derived attrs means a CLJS version bump that changes `:arglists` printer output (unlikely; this format has been stable since 2014) would create a printed-string drift between persisted and re-derived. Mitigation: re-derive on every successful resume eval and `:db/retract` + re-assert the projection if it changed. One tx per fn, batched.

## Q8 — Publish semantics (v2/v3 sketch)

Out of scope for v1. The shape it'll take:

- `:seon.fn/published?` `:boolean` (optional, default absent)
- A `(seon.agent/publish! 'my-fn)` verb that requires:
  - `(:seon.fn/specced? fn-ent) == true`
  - At least one passing `:seon.test` linked via `:seon.test/target-fn`
- Cross-agent visibility filter: an agent's section composer (current-ns-section, dep-graph-section, etc.) shows only `(:seon.fn/published? true)` fns from foreign agents, but ALL fns from the same agent.

The "merge environments" vision from Sean's prompt becomes: on agent B's runtime boot, the resume walker re-evals (a) agent B's own fns AND (b) any foreign `:published? true` fns. Both populate the same shared compile-state. Agent B can call `(alice/published-fn ...)` directly.

This is one paragraph because v1 just needs to make sure `:seon.fn/specced?` is in the schema. Everything else can be added later without migration.

## Q9 — Implementation sketches

### (a) Analyzer-driven detect-and-tee

Inside `seon.eval/eval-batch!`'s per-form body, replace the current `code/extract-defn-name` + `code/extract-ns-name` calls with:

```clojure
(defn ^:private snapshot-defs [compile-state]
  ;; Returns {ns-sym → #{def-sym ...}}. Snap before each form eval.
  (into {}
    (for [[ns-sym ns] (get @compile-state :cljs.analyzer/namespaces)]
      [ns-sym (set (keys (:defs ns)))])))

(defn ^:private snapshot-schemas []
  ;; The defonce *schemas atom inside seon.schema. Need to expose
  ;; it (currently :private) — add a `seon.schema/current-keys`
  ;; readonly fn that returns (set (keys @*schemas)).
  (seon.schema/current-keys))

(defn ^:private diff-defs [before after]
  ;; Returns [{:ns ns-sym :sym sym :var-map var-map} ...] for
  ;; everything in `after` not in `before` OR whose var-map changed.
  (for [[ns-sym after-defs] after
        sym                 after-defs
        :let  [before-defs (get before ns-sym #{})]
        :when (not (contains? before-defs sym))]
    {:ns ns-sym :sym sym}))

;; In eval-batch!'s per-form loop, after raw-result :ok true and
;; BEFORE record-eval!:
(let [defs-before     (snapshot-defs compile-state)
      schemas-before  (snapshot-schemas)
      _               (await (eval-one ...))  ; the actual eval
      defs-after      (snapshot-defs compile-state)
      schemas-after   (snapshot-schemas)
      added-defs      (diff-defs defs-before defs-after)
      added-schemas   (set/difference schemas-after schemas-before)
      tee-fn-entities (for [{:keys [ns sym]} added-defs
                            :let [var-map (ana-api/ns-resolve
                                            compile-state ns sym)]
                            :when (:fn-var var-map)]   ; skip plain defs
                        {:seon.fn/sym       (str ns "/" sym)
                         :seon.fn/ns        [:seon.ns/name (keyword ns)]
                         :seon.fn/source    source
                         :seon.fn/arglists  (pr-str (:arglists var-map))
                         :seon.fn/doc       (or (-> var-map :meta :doc) "")
                         :seon.fn/private?  (boolean (-> var-map :meta :private))
                         :seon.fn/specced?  (some? (-> var-map :meta :malli/schema))
                         :seon.fn/created-at #inst "..."})
      tee-schema-entities (for [k added-schemas]
                            {:seon.schema/key    k
                             :seon.schema/ns     [:seon.ns/name (keyword (namespace k))]
                             :seon.schema/source source
                             :seon.schema/created-at ...})
      tee-ns-entities (when (= 'ns (first (safe-parse source)))
                        [{:seon.ns/name     (keyword (-> source safe-parse second))
                          :seon.ns/source   source
                          :seon.ns/requires (extract-requires (safe-parse source))
                          :seon.ns/created-at ...}])]
  (db/transact! conn (concat tee-fn-entities
                             tee-schema-entities
                             tee-ns-entities
                             [eval-entity])))
```

**Verified pieces from REPL probes:**

- `defs-before/after` shape ✓ (Probe 3)
- `:arglists` `:doc` `:private` `:malli/schema` all present on `var-map :meta` ✓ (Probe 2)
- `:fn-var true` distinguishes defn from def ✓ (Probe 2)
- ns-source `(ns foo …)` doesn't add to `:defs` so the diff is empty for ns-only forms ✓ (Probe 4 confirms only `analyze` after the ns+defn form, but the ns redefinition wiped prior defs — the snapshot-then-diff handles that fine: a "wipe" looks like "no new defs in this ns", which is correct because the ns form alone doesn't add a fn)

**One small dep:** `seon.schema` needs a public `current-keys` (or `snapshot`) fn. Currently `*schemas` is `^:private`. Add ~3-line PR:

```clojure
;; In seon.schema
(defn current-keys
  "Snapshot of the registry's key set. Used by seon.eval/eval-batch!'s
   detect-and-tee to diff before/after eval and tee :seon.schema entities
   for newly-registered schemas."
  {:malli/schema [:=> [:cat] [:set :qualified-keyword]]}
  []
  (set (keys @*schemas)))
```

After that, schema tee is just `(set/difference (schema/current-keys) before)` — no source parsing, handles every register-call shape.

### (b) Resume in dep order

```clojure
(defn ^:private parse-ns-requires
  "Parse a (ns ...) form's :require clauses to extract dep ns syms.
   Tolerant of all the legal shapes: bare sym, [sym :as x],
   [sym :refer [...]], [sym :as x :refer [...]], etc."
  [ns-form]
  (let [reqs (some (fn [clause]
                     (when (and (seq? clause) (= :require (first clause)))
                       (rest clause)))
                   (drop 2 ns-form))]   ; skip 'ns and the ns-name
    (->> reqs
         (map (fn [r] (if (sequential? r) (first r) r)))
         (filter symbol?)
         set)))

(defn ^:private topo-sort
  "Kahn's. Given {ns-sym → #{dep-ns-sym}}, return topo-ordered vec
   of ns-syms. Throws ex-info on cycles."
  [edges]
  (loop [out [] in-degree (frequencies (mapcat val edges))
         ready (filter #(zero? (get in-degree % 0)) (keys edges))
         remaining edges]
    ...))

(defn ^:async resume!
  "Walk persisted :seon.ns / :seon.fn / :seon.schema and rehydrate
   the compile-state + globalThis. Order:
     1. Topo over :seon.ns/requires (filtered to known agent nses).
     2. For each ns in order: eval the :seon.ns/source.
     3. For each ns: eval its :seon.schema entities (any order
        within ns is fine; sort by :created-at for determinism).
     4. For each ns: eval its :seon.fn entities (sort by :created-at).
   On any step failure, log a :seon.resume/failure entry and continue
   (skip dependents of the failed ns — they'd error anyway)."
  [conn compile-state]
  (let [nses     (db/query conn '[:find (pull ?e [*]) :where [?e :seon.ns/name]])
        agent-ns-set (set (map :seon.ns/name nses))
        edges    (into {} (for [n nses]
                            [(:seon.ns/name n)
                             (-> (parse-ns-requires
                                   (read-string (:seon.ns/source n)))
                                 (->> (map #(keyword (str %))))
                                 (set)
                                 (set/intersection agent-ns-set))]))
        ordered  (topo-sort edges)]
    (doseq [ns-kw ordered]
      (let [ns-ent (first (filter #(= ns-kw (:seon.ns/name %)) nses))]
        (await (seval/eval compile-state (:seon.ns/source ns-ent)))
        (doseq [schema (db/query ... ns-kw)]
          (await (seval/eval compile-state (:seon.schema/source schema)
                             {:ns (symbol (name ns-kw))})))
        (doseq [fn-ent (sort-by :seon.fn/created-at (db/query ... ns-kw))]
          (await (seval/eval compile-state (:seon.fn/source fn-ent)
                             {:ns (symbol (name ns-kw))})))))))
```

REPL-tested the toy version of `parse-ns-requires` against probe.norm's source — extracted `#{clojure.set clojure.string clojure.walk}` correctly. Topo on a 3-ns toy graph with one fan-in trivially.

### (c) The shared `seon.analyzer-info` module

**Deviation from this research's original sketch (REPL-verified
2026-05-24):** self-host CLJS does NOT expose `cljs.analyzer.api/find-ns`
or `cljs.analyzer.api/ns-resolve` — both throw `TypeError: undefined`
in the pod bundle. The landed impl reads
`(:cljs.analyzer/namespaces @compile-state)` directly. Also, the
analyzer var-map's flag is `:fn-var` (no question mark) — confirmed
by inspecting `(get-in @cs [:cljs.analyzer/namespaces 'probe.keytest
:defs 'f])` after a `(defn f [x] x)` eval; keys present include
`:fn-var true`, no `:fn-var?`. `var-projection` renames it to
`:fn-var?` on output.

The shipped module (`src/seon/analyzer_info.cljs`):

```clojure
(ns seon.analyzer-info
  "Read-side wrapper over the bootstrap-CLJS analyzer state in
   `@compile-state`. One module so 'how we read the analyzer' lives
   in one place."
  (:require [clojure.set :as set]
            [seon.schema :as schema]))

(defn snapshot-defs
  [compile-state]
  (into {}
        (for [[ns-sym ns-info] (get @compile-state :cljs.analyzer/namespaces)]
          [ns-sym (set (keys (:defs ns-info)))])))

(defn defs-since
  [before-snapshot compile-state]
  (let [ns-map (get @compile-state :cljs.analyzer/namespaces)]
    (for [[ns-sym ns-info] ns-map
          [sym var-map]    (:defs ns-info)
          :when (not (contains? (get before-snapshot ns-sym #{}) sym))]
      {:ns ns-sym :sym sym :var-map var-map})))

(defn ns-deps
  [compile-state ns-sym known-ns-set]
  (let [ns-info (get-in @compile-state [:cljs.analyzer/namespaces ns-sym])
        deps   (set (concat (vals (:requires ns-info))
                            (vals (:uses ns-info))))]
    (-> deps
        (disj ns-sym)
        (set/intersection known-ns-set))))

(defn var-projection
  [{:keys [name fn-var arglists meta] :as _var-map}]
  {:sym       (str name)
   :fn-var?   (boolean fn-var)
   :arglists  (pr-str arglists)
   :doc       (or (:doc meta) "")
   :private?  (boolean (:private meta))
   :specced?  (some? (:malli/schema meta))})
```

Both detect-and-tee and resume verification call into this — single
source of truth for "how we read the analyzer".

## PLATFORM-FLAGs

1. **`seon.schema/*schemas` is `^:private`.** Detect-and-tee's atom-diff approach for schemas needs a public read accessor: `(defn current-keys [] (set (keys @*schemas)))`. Trivial PR. Without it, schema-tee stays on `extract-schema-key` source-parsing (which works for the canonical shapes but loses multi-register-per-form and `register-all!`).

1a. **`:bootstrap :entries` expansion.** Add `seon.schema`, `malli.core`, `malli.registry` to `shadow-cljs.edn`'s `:bootstrap :entries` (and `malli.core` to `:macros`). (Correction 2026-05-24: do NOT add `cljs.analyzer.api` — its top-level fns like `find-ns`/`ns-resolve` are not callable in self-host CLJS; read `(:cljs.analyzer/namespaces @compile-state)` directly instead. See §(c).) Rebuild `out/bootstrap/`. Measure pod boot time delta — `load-all-analysis-caches!` walks every file in `out/bootstrap/ana/` unconditionally, so the malli core's 1.3MB transit cache will add some ms. If startup grows past acceptable, gate on lazy-load (load malli's ana cache only when an agent first `(require)`s it).

2. **Platform's `replay-program-graph!` (resume walker) currently uses tx-id order.** Reproducible failure: agent redefines an upstream ns AFTER defining a downstream fn that uses it. Resume runs the downstream fn-source before the new ns-source ⇒ analyzer error on `(u/helper n)`. Fix: replace with the topo-sort + per-ns intra-ordering sketch in Q9b. ~50 LOC swap.

3. **`(ns foo …)` redefinition wipes `:defs` in the analyzer.** Not a bug per-se (it's how the CLJS analyzer works) but it means the `defs-before/defs-after` diff in detect-and-tee will NOT see the wiped vars as "removed" (they're not removed from globalThis runtime — only from the analyzer's def map). For v1: we don't tee retractions anyway, so just be aware. For v2 if we want to model retraction of stale fns: track the wipe explicitly by snapshotting `:cljs.analyzer/namespaces` keys before/after and treating "ns appeared in this turn AND was already known" as a wipe event.

4. **`seon.code.extract-defn-name` corpus has 30+ cases — most are now obsolete with analyzer-driven approach.** The analyzer handles every shape uniformly (`defn`, `defn-`, attr-map, docstring, metadata, multi-arity, var-args, nested) because it actually expanded the form. The corpus stays useful for the ONE remaining string-parse path (schema extraction) and as regression guard for `code/check`'s structural validator. We don't delete the extractors, but the call site in `eval-batch!` shifts from `(code/extract-defn-name source)` to `(analyzer-info/defs-since before state)`.

## Open questions back to Sean

1. **`:seon.fn/refs` (var-reference graph) — defer to v2 or do now?** It's cheap to derive from `(:children walk over var-map)` at tee-time but adds storage + invariants. v1 doesn't have a consumer for it yet (the dep-graph section reads ns-level `:requires`, not var-level refs). Lean: defer.

2. **`schema/register!` extraction — atom-diff (recommended) or source-parse (status quo)?** Atom-diff is verified working today (Probe 5) and is strictly more general. The blocker is exposing `seon.schema/current-keys` (3-line PR). Recommend: do the PR + switch in the same patch as detect-and-tee. Source-parse retains zero value once atom-diff exists.

3. **Per-agent vs shared compile-state — when to revisit?** Recommend "when we see contention", i.e. an agent's eval breaking because another agent re-`(ns ...)`-ed shared scratch. Until then, shared is strictly more useful (the "merge environments" model).

4. **Resume failure handling** — when topo-sort on the persisted ns set produces a cycle (because an agent redefined `(ns alice (:require [bob]))` after bob was already requiring alice), do we: (a) refuse to resume that ns set and surface a fatal, (b) break cycles by dropping the most-recently-introduced edge, or (c) resume in tx-id order with a warning? Lean: (a) — cycle in the persisted graph means the agent committed something that wouldn't have compiled the first time around, surface it.

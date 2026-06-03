---
type: prd
status: draft
tags: [prd, architecture]
---

# Namespace Bootstrap POC

## Status: Experimental / Draft

This is an exploratory proof of concept. Not yet part of the production system.

## Context

Build a self-bootstrapping namespace in a single test file. A `(def seed {::ns-key ...})` and a system schema with `:default/fn` entries wire everything — no hardcoded function sequence. The seed controls behavior: `{::resume? true}` restores from Datalevin. `{}` starts fresh with all defaults. The schema IS the wiring.

All keys are `::` (namespace-local). When we refactor, we change the namespace, keys follow.

**Core principle: Turtles all the way down.** Every function fully specced, map-in/map-out. Infrastructure IS domain code.

## The Model

```clojure
;; Minimal seed — just identify yourself
(def seed {::ns-key ::bootstrap})

;; System schema — defaults wire the bootstrap sequence
;; Entry order = dependency order
(schema/register! ::system
  [:map
   [::ns-key :keyword]
   [::dir {:default/fn '(fn [m] (str "tmp/" (name (::ns-key m))))}
    :string]
   [::resume? {:default false} :boolean]
   [::conn {:default/fn '(fn [m] (::conn (create-conn! m)))}
    [:fn d/conn?]]
   [::ctx {:default/fn '(fn [m] (::ctx (init-ctx! m)))}
    ::ctx]])

;; One decode call does everything
(m/decode ::system seed (dependent-default-transformer))
;; => {::ns-key ::bootstrap
;;     ::dir "tmp/bootstrap"
;;     ::resume? false
;;     ::conn <live Datalevin conn>
;;     ::ctx {::screen :home ::workouts []}}

;; Resume from Datalevin
(m/decode ::system {::ns-key ::bootstrap ::resume? true}
          (dependent-default-transformer))
;; => same but ::ctx restored from Datalevin

```

## Implementation

### Part 1: Custom Default Transformer (~20 lines)

From `reference-code/malli/docs/tips.md`. `:default/fn` receives the map being built so later entries can depend on earlier ones.

```clojure
(ns seon.test.bootstrap
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [datalevin.core :as d]
            [malli.core :as m]
            [malli.transform :as mt]
            [seon.schema :as schema]))

(defn dependent-default-transformer [] ...)
;; Key difference: (f acc) not (f), passes current map to default/fn

```

### Part 2: Schemas (~30 lines)

All `::` — local to this namespace.

```clojure
;; Infrastructure
(schema/register! ::ns-key :keyword)
(schema/register! ::dir :string)
(schema/register! ::resume? :boolean)
(schema/register! ::conn [:fn d/conn?])

;; Domain
(schema/register! ::exercise :string)
(schema/register! ::weight :double)
(schema/register! ::reps :int)
(schema/register! ::workout-set
  [:map [::exercise ::exercise] [::weight ::weight] [::reps ::reps]])
(schema/register! ::workouts [:vector ::workout-set])
(schema/register! ::screen [:enum :home :active :history])
(schema/register! ::ctx
  [:map [::screen ::screen] [::workouts ::workouts]])

```

### Part 3: Infrastructure Functions (~50 lines)

Every function specced, map-in/map-out.

```clojure
(defn create-conn!
  {:malli/schema [:=> [:cat [:map [::dir ::dir]]] [:map [::conn ::conn]]]}
  [{::keys [dir]}]
  {::conn (d/create-conn dir {})})

(defn close-conn!
  {:malli/schema [:=> [:cat [:map [::conn ::conn]]] [:map [::closed :boolean]]]}
  [{::keys [conn]}]
  (d/close conn) {::closed true})

(defn persist-ctx!
  {:malli/schema [:=> [:cat [:map [::conn ::conn] [::ns-key ::ns-key] [::data :map]]]
                      [:map [::persisted :boolean]]]}
  [{::keys [conn ns-key data]}]
  (let [safe (into {} (filter (fn [[_ v]]
                                (try (edn/read-string (pr-str v)) true
                                     (catch Exception _ false))) data))]
    (d/transact! conn [{:seon.ctx/namespace ns-key
                         :seon.ctx/data (pr-str safe)
                         :seon.ctx/updated-at (java.util.Date.)}])
    {::persisted true}))

(defn restore-ctx
  {:malli/schema [:=> [:cat [:map [::conn ::conn] [::ns-key ::ns-key]]]
                      [:map [::data {:optional true} :map]]]}
  [{::keys [conn ns-key]}]
  (let [stored (d/q '[:find ?data . :in $ ?ns :where
                       [?e :seon.ctx/namespace ?ns] [?e :seon.ctx/data ?data]]
                     (d/db conn) ns-key)]
    {::data (when stored (edn/read-string stored))}))

(defn init-ctx!
  "Init ctx: if resume? and data exists in Datalevin, merge over defaults."
  {:malli/schema [:=> [:cat [:map [::conn ::conn] [::ns-key ::ns-key]
                                  [::resume? ::resume?]]]
                      [:map [::ctx ::ctx]]]}
  [{::keys [conn ns-key resume?] :as m}]
  (let [defaults {::screen :home ::workouts []}
        persisted (when resume?
                    (::data (restore-ctx m)))]
    {::ctx (merge defaults persisted)}))

```

### Part 4: System Schema (~15 lines)

The seed + system schema IS the wiring. Entry order = dependency order. No explicit init function.

```clojure
(schema/register! ::system
  [:map
   [::ns-key ::ns-key]
   [::dir {:default/fn '(fn [m] (str "tmp/" (name (::ns-key m))))}
    ::dir]
   [::resume? {:default false} ::resume?]
   [::conn {:default/fn '(fn [m] (::conn (create-conn! m)))} ::conn]
   [::ctx {:default/fn '(fn [m] (::ctx (init-ctx! m)))} ::ctx]])

(def seed {::ns-key ::bootstrap})

```

### Part 5: Domain Functions (~20 lines)

Pure data-in/data-out.

```clojure
(defn total-volume
  {:malli/schema [:=> [:cat [:map [::ctx ::ctx]]] [:map [::volume :double]]]}
  [{::keys [ctx]}]
  {::volume (->> (::workouts ctx)
                 (reduce (fn [acc w] (+ acc (* (::weight w) (::reps w)))) 0.0))})

(defn add-workout!
  {:malli/schema [:=> [:cat [:map [::ctx ::ctx] [::exercise ::exercise]
                                  [::weight ::weight] [::reps ::reps]]]
                      [:map [::ctx ::ctx]]]}
  [{::keys [ctx exercise weight reps]}]
  {::ctx (update ctx ::workouts conj
           {::exercise exercise ::weight weight ::reps reps})})

```

### Part 6: Dispatch + Atom (~20 lines)

```clojure
(defn dispatch [input-schema f args]
  (let [decoded (m/decode input-schema args (dependent-default-transformer))]
    (f decoded)))

(defonce *ctx* (atom nil))

(defn wire-atom!
  {:malli/schema [:=> [:cat [:map [::conn ::conn] [::ns-key ::ns-key] [::ctx ::ctx]]]
                      [:map [::wired :boolean]]]}
  [{::keys [conn ns-key ctx]}]
  (reset! *ctx* ctx)
  (remove-watch *ctx* ::persist)
  (add-watch *ctx* ::persist
    (fn [_ _ old new]
      (when (not= old new)
        (persist-ctx! {::conn conn ::ns-key ns-key ::data new}))))
  {::wired true})

```

### Part 7: Tests (~70 lines)

```clojure
(deftest fresh-bootstrap-test
  (let [system (m/decode ::system seed (dependent-default-transformer))]
    (try
      (testing "System bootstrapped from minimal seed"
        (is (d/conn? (::conn system)))
        (is (= :home (-> system ::ctx ::screen)))
        (is (= [] (-> system ::ctx ::workouts))))

      (testing "Wire atom, dispatch with ctx injection"
        (wire-atom! system)
        (schema/register! ::ctx
          [:map {:default/fn (fn [] @*ctx*)}
           [::screen ::screen] [::workouts ::workouts]])
        (let [r (dispatch [:map [::ctx ::ctx]] total-volume {})]
          (is (= 0.0 (::volume r)))))

      (testing "Mutation + persist"
        (let [r (dispatch [:map [::ctx ::ctx] [::exercise ::exercise]
                                [::weight ::weight] [::reps ::reps]]
                          add-workout!
                          {::exercise "Squat" ::weight 100.0 ::reps 5})]
          (reset! *ctx* (::ctx r)))
        (Thread/sleep 50)
        (let [{::keys [data]} (restore-ctx system)]
          (is (= 1 (count (::workouts data))))))

      (finally (close-conn! system)))))

(deftest resume-bootstrap-test
  (let [dir "tmp/resume-test"
        sys1 (m/decode ::system {::ns-key ::bootstrap ::dir dir}
                       (dependent-default-transformer))]
    (try
      (persist-ctx! {::conn (::conn sys1) ::ns-key ::bootstrap
                     ::data {::screen :active ::workouts
                              [{::exercise "Squat" ::weight 100.0 ::reps 5}]}})
      (close-conn! sys1)

      ;; Resume
      (let [sys2 (m/decode ::system {::ns-key ::bootstrap ::dir dir ::resume? true}
                           (dependent-default-transformer))]
        (try
          (testing "Restored from Datalevin"
            (is (= :active (-> sys2 ::ctx ::screen)))
            (is (= 1 (count (-> sys2 ::ctx ::workouts)))))
          (finally (close-conn! sys2))))
      (catch Exception e (throw e)))))

```

## What This Proves

1. **Seed + schema = wiring** — no hardcoded init sequence
2. **Entry order = dependency order** — decode resolves chains
3. **`::resume? true`** — flag in seed controls restore behavior
4. **Minimal seed** — `{::ns-key ::bootstrap}` is enough to start
5. **All `::` keys** — namespace-local, portable when refactored
6. **Every function specced** — including plumbing
7. **Atom is workspace** — `defonce` survives reload, watch persists

## Phase 1: Bootstrap — DONE

Implemented in `src/seon/test/bootstrap.clj`. 14 assertions, 4 tests, all pass.

Proved: seed + schema = wiring, decode-driven init, resume from Datalevin, atom workspace with persist watch, all functions specced map-in/map-out.

---

## Phase 2: Two Paths, One Mechanism

Both paths converge on the same pipeline: **decode input → defaults fill injectable keys → call function → apply ctx return → persist.**

### Path A: Direct Cross-Namespace Call (Transparent Injection)

Another namespace calls `(seon.test.bootstrap/total-volume {})`. Today this fails because `::ctx` is missing. With decode-based instrumentation, it just works:

```
Caller: (total-volume {})
  → instrumentation wrapper intercepts
  → reads :malli/schema, gets input schema
  → m/decode against input schema with dependent-default-transformer
  → ::ctx missing → :default/fn fires → @*state → injected
  → calls total-volume with {::ctx <live state>}
  → returns {::volume 500.0}

```

**Implementation:** Wrap specced functions via `alter-var-root`. For each function with `:malli/schema` whose input spec has entries with `:default/fn`, wrap it:

```clojure
(defn instrument-with-decode!
  "Wrap a function to decode its input before calling.
   Fills missing keys from :default/fn in the schema."
  [{::keys [fn-var]}]
  (let [original @fn-var
        input-schema (-> (meta fn-var) :malli/schema m/schema
                         m/children first  ;; [:cat INPUT OUTPUT] → INPUT
                         m/children first)] ;; the input map schema
    (alter-var-root fn-var
      (fn [_]
        (fn [args]
          (let [decoded (m/decode input-schema args
                                 (dependent-default-transformer))
                result (original decoded)]
            ;; Apply ctx return if present
            (when-let [new-ctx (::ctx result)]
              (reset! *state new-ctx))
            (dissoc result ::ctx)))))))

```

After `(instrument-with-decode! {::fn-var #'total-volume})`, any caller gets transparent injection. The atom watch handles persist.

### Path B: Flow Data Routing (Graph Discovery)

Data arrives on a flow channel. No explicit function name. The system uses the code graph to discover which function handles it.

```
Data arrives: {::exercise "Squat" ::weight 100.0 ::reps 5}
  → query graph: which functions accept these keys?
  → graph: add-workout! requires #{::ctx ::exercise ::weight ::reps}
  → ::ctx is injectable (has :default/fn) → available keys = data keys + injectables
  → match! Specificity: add-workout! matches all 3 data keys + 1 injectable
  → decode fills ::ctx from atom
  → call add-workout!
  → result has ::ctx → atom updates → persist watch fires

```

**Implementation:** Needs the code graph indexed for this namespace. Use existing scanner/ingest infrastructure to populate our embedded Datalevin with function+spec entities. Then query with the same pattern as `gq/functions-with-output-key` but matching on input keys.

```clojure
(defn route-data!
  "Route incoming data to the best matching function via graph discovery."
  {:malli/schema [:=> [:cat [:map [::data :map]]]
                      [:map [::result :map] [::fn-called :string]]]}
  [{::keys [data]}]
  (let [data-keys (set (keys data))
        ;; Find functions whose required (non-injectable) input keys ⊆ data-keys
        candidates (discover-matching-functions {::conn (::conn @*system*)
                                                  ::available-keys data-keys})
        best (first candidates)  ;; sorted by specificity
        fn-var (requiring-resolve (symbol (:seon.fn/qualified-name best)))
        input-schema (...) ;; extract from fn metadata
        decoded (m/decode input-schema data (dependent-default-transformer))
        result (@fn-var decoded)]
    (when-let [new-ctx (::ctx result)]
      (reset! *state new-ctx))
    {::result (dissoc result ::ctx)
     ::fn-called (:seon.fn/qualified-name best)}))

```

### What Both Paths Share

The core pipeline is identical:

```
1. Determine function (explicit call or graph discovery)
2. Get input schema from :malli/schema metadata
3. m/decode with dependent-default-transformer (fills ::ctx from atom)
4. Call function (pure data-in/data-out)
5. If result has ::ctx → reset! atom (triggers persist watch)
6. Return result without ::ctx

```

Steps 2-6 are the same function. Path A and Path B only differ in step 1.

### Test Cases for Phase 2

```clojure
;; Path A: transparent injection via instrumentation
(deftest transparent-injection-test
  (let [system (bootstrap! {})]
    (try
      (wire-atom! system)
      (instrument-with-decode! {::fn-var #'total-volume})

      (testing "Call with empty map — ctx injected transparently"
        (let [r (total-volume {})]
          (is (= 0.0 (::volume r)))))

      (finally (close-conn! system)))))

;; Path B: data-driven routing via graph
(deftest data-driven-routing-test
  (let [system (bootstrap! {})]
    (try
      (wire-atom! system)
      (index-namespace! system)  ;; scan + ingest to local Datalevin

      (testing "Raw data routes to add-workout!"
        (let [r (route-data! {::data {::exercise "Squat"
                                       ::weight 100.0 ::reps 5}})]
          (is (= "seon.test.bootstrap/add-workout!" (::fn-called r)))
          (is (= 1 (count (::workouts @*state))))))

      (finally (close-conn! system)))))

```

### Graph Indexing in Local Datalevin

The existing scanner/ingest infrastructure writes to `:seon.runtime` via the infrastructure flow. For this POC, we need to write to **our own** embedded conn. Options:

1. **Use `d/transact!` directly** on our conn with the entity maps from `extract-graph`. Simplest for POC. Requires registering the graph schemas (`:seon.fn/qualified-name` etc.) in our local Datalevin.
2. **Bind `db/*direct-mode*` + `db/*conn-manager*`** to route `db/transact!` to our conn. Same pattern as `test/seon/ctx_test.clj`.

Option 2 is cleaner — reuses existing ingest functions unchanged.

---

## Phase 3: Subscriber Dispatch

After a Datalevin transaction (ctx persist), discover subscriber functions whose input specs match the changed attributes. Call them through the same decode pipeline.

## Phase 4: REPL Protocol

Browser SSE, agent REPL, nREPL as connection types. Same decode dispatch, different I/O format.

## Phase 5: Agent Context Rendering

`:seon.render/ai` renders namespace code + state as the agent's REPL context, sourced from Datalevin code graph.

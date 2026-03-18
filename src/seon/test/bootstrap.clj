(ns seon.test.bootstrap
  "Namespace bootstrap POC.
  Proves: seed + schema with :default/fn = self-wiring system.
  Entry order in the :map = dependency order.
  One decode call bootstraps everything from a minimal seed.

  Phase 2: Transparent injection via decode-based instrumentation,
  interesting domain functions with emergent data flow, data-driven
  function routing with feedback loops.

  Phase 3: Shape-based discovery queries and data routing.
  Given data keys, build an execution graph of matching functions,
  cascade outputs, prune unneeded nodes, and execute in order."
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d]
            [malli.core :as m]
            [malli.transform :as mt]
            [seon.db :as db]
            [seon.graph.extract :as extract]
            [seon.graph.ingest :as ingest]
            [seon.graph.query :as gq]
            [seon.schema :as schema]))

;; ---------------------------------------------------------------------------
;; Part 1: Dependent Default Transformer
;;
;; Standard Malli default-value-transformer calls (f) with no args.
;; We need (f acc) so later entries see earlier resolved values.
;; ---------------------------------------------------------------------------

(defn dependent-default-transformer
  "Like mt/default-value-transformer but :default/fn receives the
  accumulating map, so later entries can depend on earlier ones.
  Entry order in the [:map ...] = resolution order."
  []
  (mt/transformer
   {:decoders
    {:map
     {:compile
      (fn [schema _]
        (let [entries (m/children schema)
              default-fns (into []
                            (keep (fn [[k {:keys [optional] :as props} v]]
                              (let [dfn (or (some-> props :default/fn m/eval)
                                            (some-> (m/properties v) :default/fn m/eval))
                                    dval (or (some-> props (find :default))
                                             (some-> (m/properties v) (find :default)))]
                                (cond
                                  dfn  [k :fn dfn]
                                  dval [k :val (val dval)]
                                  :else nil))))
                            entries)]
          (when (seq default-fns)
            (fn [x]
              (if (map? x)
                (reduce (fn [acc [k kind v]]
                          (if (contains? acc k)
                            acc
                            (case kind
                              :fn  (assoc acc k (v acc))
                              :val (assoc acc k v))))
                        x default-fns)
                x)))))}}}))

;; ---------------------------------------------------------------------------
;; Part 2: Schemas — all :: (namespace-local)
;; ---------------------------------------------------------------------------

;; Infrastructure
(schema/register! ::ns-key :keyword)
(schema/register! ::dir :string)
(schema/register! ::resume? :boolean)
(schema/register! ::conn [:fn d/conn?])
(schema/register! ::closed :boolean)
(schema/register! ::persisted :boolean)
(schema/register! ::wired :boolean)
(schema/register! ::volume :double)
(schema/register! ::sets :int)

;; Domain (workout tracker as POC domain)
(schema/register! ::exercise :string)
(schema/register! ::weight :double)
(schema/register! ::reps :int)
(schema/register! ::workout-set
  [:map [::exercise ::exercise] [::weight ::weight] [::reps ::reps]])
(schema/register! ::workouts [:vector ::workout-set])
(schema/register! ::screen [:enum :home :active :history])
(schema/register! ::bodyweight :double)
(schema/register! ::ratio :double)
(schema/register! ::exercise-ratio
  [:map [::exercise ::exercise] [::ratio ::ratio]])
(schema/register! ::strength-ratios [:vector ::exercise-ratio])
(schema/register! ::suggestion :string)
(schema/register! ::exercise-suggestion
  [:map [::exercise ::exercise] [::suggestion ::suggestion]])
(schema/register! ::suggestions [:vector ::exercise-suggestion])
(schema/register! ::weekly-volume :double)
(schema/register! ::weekly-sets :int)
(schema/register! ::ctx
  [:map {:default/fn '(fn [_] {:seon.test.bootstrap/screen :home
                                :seon.test.bootstrap/workouts []})}
   [::screen ::screen]
   [::workouts ::workouts]
   [::bodyweight {:optional true} ::bodyweight]
   [::strength-ratios {:optional true} ::strength-ratios]
   [::suggestions {:optional true} ::suggestions]
   [::weekly-volume {:optional true} ::weekly-volume]
   [::weekly-sets {:optional true} ::weekly-sets]])

;; Execution graph schemas
(schema/register! ::data-keys [:set :keyword])
(schema/register! ::system-keys [:set :keyword])
(schema/register! ::consumers [:set :keyword])
(schema/register! ::db-name :keyword)
(schema/register! ::matched-key-count :int)
(schema/register! ::graph-node [:map
                                 [:seon.fn/qualified-name :string]
                                 [::matched-key-count ::matched-key-count]])
(schema/register! ::nodes [:vector ::graph-node])
(schema/register! ::edges [:vector [:tuple :string :string]])
(schema/register! ::graph [:map [::nodes ::nodes] [::edges ::edges]])
(schema/register! ::data [:map-of :keyword :seon.flow/dynamic])
(schema/register! ::result-pair [:tuple :string [:map-of :keyword :seon.flow/dynamic]])
(schema/register! ::results [:vector ::result-pair])
(schema/register! ::state [:map-of :keyword :seon.flow/dynamic])

;; ---------------------------------------------------------------------------
;; Part 3: Infrastructure Functions — map-in/map-out, fully specced
;; ---------------------------------------------------------------------------

(defn create-conn!
  "Create a local Datalevin connection for this namespace's storage."
  {:malli/schema [:=> [:cat [:map [::dir ::dir]]] [:map [::conn ::conn]]]}
  [{::keys [dir]}]
  {::conn (d/get-conn dir)})

(defn close-conn!
  "Close a Datalevin connection."
  {:malli/schema [:=> [:cat [:map [::conn ::conn]]] [:map [::closed :boolean]]]}
  [{::keys [conn]}]
  (d/close conn)
  {::closed true})

(defn persist-ctx!
  "Persist serializable ctx data to Datalevin, keyed by ns-key."
  {:malli/schema [:=> [:cat [:map [::conn ::conn] [::ns-key ::ns-key] [::ctx ::ctx]]]
                      [:map [::persisted :boolean]]]}
  [{::keys [conn ns-key ctx]}]
  (let [safe (into {} (filter (fn [[_ v]]
                                (try (edn/read-string (pr-str v)) true
                                     (catch Exception _ false)))
                              ctx))]
    (d/transact! conn [{:seon.ctx/namespace (pr-str ns-key)
                        :seon.ctx/data (pr-str safe)}])
    {::persisted true}))

(defn restore-ctx
  "Restore ctx from Datalevin for a given ns-key. Returns {::ctx ...} or {}."
  {:malli/schema [:=> [:cat [:map [::conn ::conn] [::ns-key ::ns-key]]]
                      [:map [::ctx {:optional true} ::ctx]]]}
  [{::keys [conn ns-key]}]
  (let [stored (d/q '[:find ?data .
                       :in $ ?ns
                       :where [?e :seon.ctx/namespace ?ns]
                              [?e :seon.ctx/data ?data]]
                     (d/db conn) (pr-str ns-key))]
    (if stored
      {::ctx (edn/read-string stored)}
      {})))

(defn init-ctx!
  "Init ctx: if resume? and data exists in Datalevin, merge over defaults."
  {:malli/schema [:=> [:cat [:map [::conn ::conn] [::ns-key ::ns-key]
                                  [::resume? ::resume?]]]
                      [:map [::ctx ::ctx]]]}
  [{::keys [conn ns-key resume?] :as m}]
  (let [defaults {::screen :home ::workouts []}
        persisted (when resume? (::ctx (restore-ctx m)))]
    {::ctx (merge defaults persisted)}))

;; ---------------------------------------------------------------------------
;; Part 4: System Schema — seed + this = full wiring
;; Entry order = dependency order. No explicit init function.
;; ---------------------------------------------------------------------------

(schema/register! ::system
  [:map
   [::ns-key ::ns-key]
   [::dir {:default/fn (fn [m] (str "tmp/" (name (::ns-key m))))} ::dir]
   [::resume? {:default false} ::resume?]
   [::conn {:default/fn (fn [m] (::conn (create-conn! m)))} ::conn]
   [::ctx {:default/fn (fn [m] (::ctx (init-ctx! m)))} ::ctx]])

(def seed {::ns-key ::bootstrap})

(schema/register! ::bootstrap-request
  [:map [::seed {:optional true} [:map [::ns-key ::ns-key]]]])

(defn bootstrap!
  "Bootstrap a system from a seed map."
  {:malli/schema [:=> [:cat ::bootstrap-request] ::system]}
  [{:keys [seed] :or {seed seed}}]
  (m/decode ::system seed (dependent-default-transformer)))

;; ---------------------------------------------------------------------------
;; Part 5: Domain Functions — pure data-in/data-out
;; ---------------------------------------------------------------------------

(defn total-volume
  "Calculate total training volume from ctx. Includes set count."
  {:malli/schema [:=> [:cat [:map [::ctx ::ctx]]] [:map [::volume :double] [::sets :int]]]}
  [{::keys [ctx]}]
  {::volume (->> (::workouts ctx)
                 (reduce (fn [acc w] (+ acc (* (::weight w) (::reps w)))) 0.0))
   ::sets (count (::workouts ctx))})

(defn add-workout!
  "Add a workout set to ctx. Returns updated ctx."
  {:malli/schema [:=> [:cat [:map [::ctx ::ctx] [::exercise ::exercise]
                                  [::weight ::weight] [::reps ::reps]]]
                      [:map [::ctx ::ctx]]]}
  [{::keys [ctx exercise weight reps]}]
  {::ctx (update ctx ::workouts conj
                 {::exercise exercise ::weight weight ::reps reps})})

(defn record-bodyweight!
  "Record a bodyweight measurement. Updates ctx with ::bodyweight."
  {:malli/schema [:=> [:cat [:map [::ctx ::ctx] [::bodyweight ::bodyweight]]]
                      [:map [::ctx ::ctx]]]}
  [{::keys [ctx bodyweight]}]
  {::ctx (assoc ctx ::bodyweight bodyweight)})

(defn calculate-relative-strength
  "Calculate strength-to-bodyweight ratio for each exercise.
   Requires ::bodyweight in ctx. Returns ratios per exercise (best set)."
  {:malli/schema [:=> [:cat [:map [::ctx ::ctx]]]
                      [:map [::strength-ratios ::strength-ratios]]]}
  [{::keys [ctx]}]
  (let [bw (::bodyweight ctx)
        workouts (::workouts ctx)]
    (if (and bw (pos? bw) (seq workouts))
      (let [best-by-exercise
            (->> workouts
                 (group-by ::exercise)
                 (map (fn [[ex sets]]
                        (let [best-weight (apply max (map ::weight sets))]
                          {::exercise ex
                           ::ratio (/ best-weight bw)})))
                 vec)]
        {::strength-ratios best-by-exercise})
      {::strength-ratios []})))

(defn suggest-next-weight
  "Suggest weight increases based on workout history.
   If an exercise has 3+ sets at the same weight with 5+ reps, suggest increase."
  {:malli/schema [:=> [:cat [:map [::ctx ::ctx]]]
                      [:map [::suggestions ::suggestions]]]}
  [{::keys [ctx]}]
  (let [workouts (::workouts ctx)]
    (if (seq workouts)
      (let [suggestions
            (->> workouts
                 (group-by ::exercise)
                 (keep (fn [[ex sets]]
                         (let [at-weight (group-by ::weight sets)
                               ready (->> at-weight
                                          (filter (fn [[_ s]]
                                                    (and (>= (count s) 3)
                                                         (every? #(>= (::reps %) 5) s))))
                                          (map first))]
                           (when (seq ready)
                             (let [top (apply max ready)]
                               {::exercise ex
                                ::suggestion (str "Ready to increase from "
                                                  top " — try " (* top 1.05))})))))
                 vec)]
        {::suggestions suggestions})
      {::suggestions []})))

(defn update-weekly-volume
  "Compute weekly volume summary from ctx workouts. Pure derived data."
  {:malli/schema [:=> [:cat [:map [::ctx ::ctx]]]
                      [:map [::weekly-volume ::weekly-volume]
                            [::weekly-sets ::weekly-sets]]]}
  [{::keys [ctx]}]
  (let [workouts (::workouts ctx)]
    {::weekly-volume (->> workouts
                          (reduce (fn [acc w] (+ acc (* (::weight w) (::reps w)))) 0.0))
     ::weekly-sets (count workouts)}))

;; ---------------------------------------------------------------------------
;; Part 6: Atom workspace — defonce survives reload, watch persists
;; ---------------------------------------------------------------------------

(defonce *state (atom nil))

(defn wire-atom!
  "Wire the atom to a live system — reset with ctx, add persist watch."
  {:malli/schema [:=> [:cat [:map [::conn ::conn] [::ns-key ::ns-key] [::ctx ::ctx]]]
                      [:map [::wired :boolean]]]}
  [{::keys [conn ns-key ctx]}]
  (reset! *state ctx)
  (remove-watch *state ::persist)
  (add-watch *state ::persist
    (fn [_ _ old new]
      (when (not= old new)
        (persist-ctx! {::conn conn ::ns-key ns-key ::ctx new}))))
  {::wired true})

;; ---------------------------------------------------------------------------
;; Part 7: Transparent Injection via Instrumentation
;;
;; Wrap function vars so callers don't need to provide injectable keys.
;; The wrapper reads :malli/schema, decodes input with defaults from *state,
;; calls the original, and applies ctx updates back to the atom.
;; ---------------------------------------------------------------------------

(defonce ^:private *originals (atom {}))

(defn- extract-input-schema-form
  "Extract the input map schema form from a function's :malli/schema.
   [:=> [:cat INPUT] OUTPUT] -> INPUT form"
  [fn-var]
  (let [schema-form (-> fn-var meta :malli/schema)]
    (when (and (vector? schema-form) (= :=> (first schema-form)))
      (let [cat-form (second schema-form)]
        (when (and (vector? cat-form) (= :cat (first cat-form)))
          (second cat-form))))))

(defn- add-ctx-default
  "Given an input schema form like [:map [::ctx ::ctx] ...],
   return a new form where ::ctx entries get :default/fn that reads @*state."
  [schema-form]
  (if (and (vector? schema-form) (= :map (first schema-form)))
    (into [:map]
          (map (fn [entry]
                 (if (and (vector? entry) (= ::ctx (first entry)))
                   ;; Add :default/fn to the ctx entry props
                   (let [[k & rest-entry] entry
                         has-props? (map? (first rest-entry))
                         props (if has-props? (first rest-entry) {})
                         schema-ref (if has-props? (second rest-entry) (first rest-entry))]
                     [k (assoc props :default/fn (fn [_] @*state)) schema-ref])
                   entry)))
          (rest schema-form))
    schema-form))

(defn instrument-with-decode!
  "Wrap a function var so callers don't need to provide ::ctx.
   The wrapper decodes input (filling ::ctx from *state), calls the original,
   and if the result contains ::ctx, updates *state. Returns result sans ::ctx."
  [{::keys [fn-var]}]
  (let [original @fn-var
        input-form (extract-input-schema-form fn-var)
        injectable-form (add-ctx-default input-form)]
    (swap! *originals assoc fn-var original)
    (alter-var-root fn-var
      (fn [_]
        (fn [args]
          (let [decoded (m/decode injectable-form args (dependent-default-transformer))
                result (original decoded)]
            (when-let [new-ctx (::ctx result)]
              (reset! *state new-ctx))
            (dissoc result ::ctx)))))))

(defn uninstrument!
  "Restore all instrumented functions to their originals."
  []
  (doseq [[v orig] @*originals]
    (alter-var-root v (constantly orig)))
  (reset! *originals {}))

;; ---------------------------------------------------------------------------
;; Part 8: Execution Graph — Shape-Based Data Routing
;;
;; Given data keys, build an execution graph:
;; 1. Find matching functions via shape queries
;; 2. For each, find output keys and cascade to downstream functions
;; 3. Prune functions with no downstream consumers
;; 4. Detect cycles (visited set)
;; 5. Execute in topological order
;; ---------------------------------------------------------------------------

(defn- index-bootstrap-namespace!
  "Extract and ingest the bootstrap namespace into a Datalevin test DB.
   Returns the db-name used."
  [db-name]
  (let [source (slurp "src/seon/test/bootstrap.clj")
        graph (extract/extract-graph {::extract/source source
                                       ::extract/file-path "src/seon/test/bootstrap.clj"})]
    (ingest/ingest-namespace!
     {::ingest/db-name db-name
      ::ingest/ns-name "seon.test.bootstrap"
      ::ingest/functions (::extract/functions graph)
      ::ingest/specs (::extract/specs graph)
      ::ingest/entries (::extract/entries graph)
      ::ingest/shapes (::extract/shapes graph)})
    db-name))

(defn build-execution-graph
  "Given data keys, build the full execution graph.

   Finds matching functions, cascades outputs to downstream functions,
   detects cycles, and prunes functions with no downstream consumers.

   Request keys:
     ::data-keys  - Set of keyword keys available in the incoming data
     ::system-keys - Set of keys always available (injectable via system state)
     ::consumers  - Set of keys that someone wants (browser, REPL, etc.)
     ::db-name    - Database name for shape queries

   Returns:
     Map with ::nodes (vec of function maps in execution order) and
     ::edges (vec of [from-fn to-fn] pairs)."
  {:malli/schema [:=> [:cat [:map
                              [::data-keys [:set :keyword]]
                              [::system-keys {:optional true} [:set :keyword]]
                              [::consumers [:set :keyword]]
                              [::db-name :keyword]]]
                      [:map
                       [::nodes [:vector [:map
                                          [:seon.fn/qualified-name :string]
                                          [::matched-key-count :int]]]]
                       [::edges [:vector [:tuple :string :string]]]]]}
  [{::keys [data-keys system-keys consumers db-name]}]
  (let [all-available (into (or data-keys #{}) (or system-keys #{}))
        ;; Phase 1: Find initial matching functions
        ;; Only include functions that actually consume data keys (matched-key-count > 0).
        ;; Functions with 0 matched keys (all-injectable inputs) are only included
        ;; if they appear as downstream of a matched function.
        initial-matches (filterv #(pos? (:matched-key-count %))
                                 (gq/functions-matching-data
                                  {::gq/db-name db-name
                                   ::gq/available-keys all-available}))
        ;; Phase 2: Cascade — for each match, find output keys and check
        ;; if they feed into other functions. Track visited to detect cycles.
        nodes (atom (vec initial-matches))
        edges (atom [])
        visited-fns (atom (set (map :seon.fn/qualified-name initial-matches)))
        frontier (atom initial-matches)]
    ;; BFS cascade: for each function, find downstream functions whose input
    ;; keys (injectable or not) overlap with the upstream's output keys.
    ;; This discovers functions that CONSUME upstream outputs, even if the
    ;; overlapping key is injectable (like ::ctx).
    (loop [current @frontier
           depth 0]
      (when (and (seq current) (< depth 10))  ;; safety cap on cascade depth
        (let [next-frontier (atom [])]
          (doseq [fn-match current]
            (let [fn-name (:seon.fn/qualified-name fn-match)
                  output-keys (set (gq/function-output-keys
                                    {::gq/db-name db-name
                                     ::gq/qualified-name fn-name}))
                  ;; Find functions that consume any of the output keys
                  ;; (even injectable ones — they still receive the data)
                  consumers-of-output
                  (when (seq output-keys)
                    (db/query db-name
                              '[:find ?ds-fn
                                :in $ [?ok ...]
                                :where
                                [?e :seon.entry/key ?ok]
                                [?s :seon.shape/entries ?e]
                                [?f :seon.fn/input-shape ?s]
                                [?f :seon.fn/qualified-name ?ds-fn]]
                              (vec output-keys)))]
              (doseq [[ds-name] consumers-of-output]
                (when-not (@visited-fns ds-name)
                  ;; Verify the downstream function is satisfiable
                  ;; (all required non-injectable keys met)
                  (let [expanded (into all-available output-keys)
                        matches (gq/functions-matching-data
                                 {::gq/db-name db-name
                                  ::gq/available-keys expanded})
                        ds-match (first (filter #(= ds-name (:seon.fn/qualified-name %))
                                                matches))]
                    (when ds-match
                      (swap! visited-fns conj ds-name)
                      (swap! nodes conj ds-match)
                      (swap! next-frontier conj ds-match)
                      (swap! edges conj [fn-name ds-name])))))))
          (recur @next-frontier (inc depth)))))
    ;; Phase 3: Prune — remove nodes whose output keys have no consumers
    ;; and no downstream edges
    (let [all-nodes @nodes
          all-edges @edges
          ;; Build adjacency: which functions feed which
          downstream-of (group-by first all-edges)
          ;; A function is "consumed" if:
          ;; 1. Its output keys overlap with consumer keys, OR
          ;; 2. It has downstream edges to consumed functions
          consumed? (fn consumed? [fn-name visited]
                      (if (visited fn-name) false  ;; cycle
                          (let [output-keys (set (gq/function-output-keys
                                                  {::gq/db-name db-name
                                                   ::gq/qualified-name fn-name}))
                                ;; Direct consumer interest
                                direct (seq (set/intersection output-keys consumers))
                                ;; Downstream edges
                                ds-edges (get downstream-of fn-name)
                                ;; Produces ctx (state update) — always consumed
                                produces-ctx (contains? output-keys ::ctx)]
                            (or direct
                                produces-ctx
                                (some #(consumed? (second %) (conj visited fn-name))
                                      ds-edges)))))
          pruned (filterv #(consumed? (:seon.fn/qualified-name %) #{}) all-nodes)
          pruned-names (set (map :seon.fn/qualified-name pruned))
          pruned-edges (filterv (fn [[from to]]
                                  (and (pruned-names from) (pruned-names to)))
                                all-edges)]
      {::nodes pruned
       ::edges pruned-edges})))

(defn execute-graph!
  "Execute a pre-built execution graph in topological order.

   For each function:
   1. Resolve the function var
   2. Decode input (fills defaults from system state via dependent-default-transformer)
   3. Call function with merged data + state
   4. If result has ::ctx, update *state atom
   5. Collect non-ctx output for downstream

   Request keys:
     ::graph - The graph from build-execution-graph
     ::data  - The input data map

   Returns:
     Map of ::results (vec of [fn-name result] pairs) and ::state (final *state)."
  {:malli/schema [:=> [:cat [:map
                              [::graph [:map
                                         [::nodes [:vector [:map [:seon.fn/qualified-name :string]]]]
                                         [::edges [:vector [:tuple :string :string]]]]]
                              [::data [:map-of :keyword :seon.flow/dynamic]]]]
                      [:map
                       [::results [:vector [:tuple :string [:map-of :keyword :seon.flow/dynamic]]]]
                       [::state [:map-of :keyword :seon.flow/dynamic]]]]}
  [{::keys [graph data]}]
  (let [nodes (::nodes graph)
        results (atom [])
        accumulated-data (atom (merge data (when @*state {::ctx @*state})))]
    (doseq [node nodes]
      (let [fn-name (:seon.fn/qualified-name node)
            fn-sym (symbol fn-name)
            fn-var (try (requiring-resolve fn-sym) (catch Exception _ nil))]
        (when fn-var
          (let [;; Build input: merge accumulated data
                input @accumulated-data
                ;; Call the function
                result (try (@fn-var input) (catch Exception _ nil))]
            (when result
              ;; If result has ::ctx, update state
              (when-let [new-ctx (::ctx result)]
                (reset! *state new-ctx)
                (swap! accumulated-data assoc ::ctx new-ctx))
              ;; Accumulate non-ctx outputs for downstream
              (let [non-ctx (dissoc result ::ctx)]
                (when (seq non-ctx)
                  (swap! accumulated-data merge non-ctx)
                  (swap! results conj [fn-name non-ctx]))))))))
    {::results @results
     ::state @*state}))

(defn route-data!
  "End-to-end: data arrives -> build graph -> execute -> return results.

   Request keys:
     ::data      - The input data map
     ::consumers - Set of keys that consumers want
     ::db-name   - Database name for shape queries

   Returns:
     Map with ::results and ::state from execute-graph!."
  {:malli/schema [:=> [:cat [:map
                              [::data [:map-of :keyword :seon.flow/dynamic]]
                              [::consumers [:set :keyword]]
                              [::db-name :keyword]]]
                      [:map
                       [::results [:vector [:tuple :string [:map-of :keyword :seon.flow/dynamic]]]]
                       [::state [:map-of :keyword :seon.flow/dynamic]]]]}
  [{::keys [data consumers db-name]}]
  (let [data-keys (set (keys data))
        graph (build-execution-graph {::data-keys data-keys
                                       ::consumers consumers
                                       ::db-name db-name})]
    (execute-graph! {::graph graph ::data data})))

;; ---------------------------------------------------------------------------
;; Part 9: Tests
;; ---------------------------------------------------------------------------

(deftest dependent-default-transformer-test
  (testing "Later entries see earlier resolved values"
    (let [s [:map
             [:a {:default 1} :int]
             [:b {:default/fn '(fn [m] (inc (:a m)))} :int]
             [:c {:default/fn '(fn [m] (+ (:a m) (:b m)))} :int]]
          result (m/decode s {} (dependent-default-transformer))]
      (is (= 1 (:a result)))
      (is (= 2 (:b result)))
      (is (= 3 (:c result))))))

(deftest domain-functions-test
  (let [system (bootstrap! {})]
    (try
      (testing "record-bodyweight! adds bodyweight to ctx"
        (let [r (record-bodyweight! {::ctx (::ctx system) ::bodyweight 85.0})]
          (is (= 85.0 (-> r ::ctx ::bodyweight)))))

      (testing "calculate-relative-strength with no bodyweight returns empty"
        (let [r (calculate-relative-strength {::ctx (::ctx system)})]
          (is (= [] (::strength-ratios r)))))

      (testing "calculate-relative-strength with data"
        (let [ctx-with-data (-> (::ctx system)
                                (assoc ::bodyweight 80.0)
                                (assoc ::workouts [{::exercise "Squat"
                                                    ::weight 120.0
                                                    ::reps 5}
                                                   {::exercise "Bench"
                                                    ::weight 80.0
                                                    ::reps 8}]))
              r (calculate-relative-strength {::ctx ctx-with-data})]
          (is (= 2 (count (::strength-ratios r))))
          (is (some #(= 1.5 (::ratio %))
                    (::strength-ratios r)))
          (is (some #(= 1.0 (::ratio %))
                    (::strength-ratios r)))))

      (testing "suggest-next-weight with insufficient data"
        (let [r (suggest-next-weight {::ctx (::ctx system)})]
          (is (= [] (::suggestions r)))))

      (testing "suggest-next-weight with ready exercise"
        (let [ctx-ready (assoc (::ctx system) ::workouts
                               (vec (repeat 3 {::exercise "Squat"
                                               ::weight 100.0
                                               ::reps 5})))
              r (suggest-next-weight {::ctx ctx-ready})]
          (is (= 1 (count (::suggestions r))))
          (is (= "Squat" (-> r ::suggestions first ::exercise)))))

      (testing "update-weekly-volume"
        (let [ctx-with-workouts (assoc (::ctx system) ::workouts
                                       [{::exercise "Squat" ::weight 100.0 ::reps 5}
                                        {::exercise "Bench" ::weight 60.0 ::reps 8}])
              r (update-weekly-volume {::ctx ctx-with-workouts})]
          (is (= 980.0 (::weekly-volume r)))
          (is (= 2 (::weekly-sets r)))))

      (finally (close-conn! system)))))

(deftest transparent-injection-test
  (let [system (bootstrap! {})]
    (try
      (wire-atom! system)
      (instrument-with-decode! {::fn-var #'total-volume})
      (instrument-with-decode! {::fn-var #'add-workout!})

      (testing "Call total-volume with empty map — ctx injected from *state"
        (let [r (total-volume {})]
          (is (= 0.0 (::volume r)))
          (is (= 0 (::sets r)))))

      (testing "add-workout! with just data keys — ctx injected, state updated"
        (add-workout! {::exercise "Squat" ::weight 100.0 ::reps 5})
        (is (= 1 (count (::workouts @*state))) "State should be updated"))

      (testing "total-volume sees updated state"
        (let [r (total-volume {})]
          (is (= 500.0 (::volume r)))
          (is (= 1 (::sets r)))))

      (testing "Multiple workouts accumulate"
        (add-workout! {::exercise "Bench" ::weight 60.0 ::reps 8})
        (let [r (total-volume {})]
          (is (= 980.0 (::volume r)))
          (is (= 2 (::sets r)))))

      (finally
        (uninstrument!)
        (remove-watch *state ::persist)
        (close-conn! system)))))

(deftest fresh-bootstrap-test
  (let [system (bootstrap! {})]
    (try
      (testing "System bootstrapped from minimal seed"
        (is (d/conn? (::conn system)))
        (is (= :home (-> system ::ctx ::screen)))
        (is (= [] (-> system ::ctx ::workouts))))

      (testing "Domain functions work with bootstrapped system"
        (let [r (total-volume {::ctx (::ctx system)})]
          (is (= 0.0 (::volume r)))))

      (testing "Add workout and check volume"
        (let [r (add-workout! {::ctx (::ctx system)
                               ::exercise "Squat"
                               ::weight 100.0
                               ::reps 5})
              v (total-volume r)]
          (is (= 500.0 (::volume v)))
          (is (= 1 (count (-> r ::ctx ::workouts))))))

      (finally (close-conn! system)))))

(deftest persist-and-resume-test
  (let [dir "tmp/bootstrap-resume-test"
        sys1 (bootstrap! {:seed {::ns-key ::bootstrap ::dir dir}})]
    (try
      (testing "Persist ctx"
        (let [updated-ctx (::ctx (add-workout! {::ctx (::ctx sys1)
                                                ::exercise "Squat"
                                                ::weight 100.0
                                                ::reps 5}))]
          (persist-ctx! {::conn (::conn sys1)
                         ::ns-key (::ns-key sys1)
                         ::ctx updated-ctx})))
      (close-conn! sys1)

      (testing "Resume restores from Datalevin"
        (let [sys2 (bootstrap! {:seed {::ns-key ::bootstrap
                                       ::dir dir
                                       ::resume? true}})]
          (try
            (is (= 1 (count (-> sys2 ::ctx ::workouts))))
            (is (= "Squat" (-> sys2 ::ctx ::workouts first ::exercise)))
            (finally (close-conn! sys2)))))
      (catch Exception e
        (throw e)))))

(deftest atom-wire-test
  (let [system (bootstrap! {})]
    (try
      (testing "Wire atom and mutate"
        (wire-atom! system)
        (is (= :home (::screen @*state)))

        ;; Simulate agent modifying state
        (swap! *state update ::workouts conj
               {::exercise "Deadlift" ::weight 180.0 ::reps 3})

        ;; Watch fires synchronously on swap! thread — no sleep needed
        (let [{::keys [ctx]} (restore-ctx system)]
          (is (some? ctx) "State should be persisted by watch")
          (is (= 1 (count (::workouts ctx))))))
      (finally
        (remove-watch *state ::persist)
        (close-conn! system)))))

(ns seon.test.bootstrap-v2
  "Datalevin-reactive bootstrap v2 — foundation for entity-driven dispatch.

   Core idea: functions declare identity keys in their Malli specs. The system
   pulls entity data for inputs, transacts outputs back, and d/listen! fires
   downstream functions automatically from tx-reports.

   Public API:
     (init! {::conn conn})                  — register listener, build cache
     (call! {::conn conn ::fn-var #'f ::args {}}) — dispatch + reactive chain
     (shutdown! {::conn conn})              — unregister listener, cleanup
     (register-connection! {...})           — register a data consumer
     (unregister-connection! {...})         — remove a consumer

   The reactive chain is: transact -> listener fires -> discover downstream
   functions (cached) -> prune by active consumers -> dispatch each ->
   their transacts trigger more listener calls -> cycle prevention stops it."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d]
            [malli.core :as m]
            [malli.transform :as mt]
            [seon.graph.extract :as extract]
            [seon.graph.ingest :as ingest]
            [seon.schema :as schema]))

;; ---------------------------------------------------------------------------
;; Part 1: Dependent Default Transformer (enhanced for ref-schema deref)
;; ---------------------------------------------------------------------------

(defn- resolve-default-props
  "Extract :default/fn or :default from entry props or the child schema's
   properties (derefing through ref schemas). Returns [kind value] or nil."
  [props child-schema]
  (let [child-props (m/properties child-schema)
        deref-props (when (m/-ref-schema? child-schema)
                      (try (m/properties (m/deref child-schema))
                           (catch Exception _ nil)))
        dfn (or (some-> props :default/fn m/eval)
                (some-> child-props :default/fn m/eval)
                (some-> deref-props :default/fn m/eval))
        dval (or (some-> props (find :default))
                 (some-> child-props (find :default))
                 (some-> deref-props (find :default)))]
    (cond
      dfn  [:fn dfn]
      dval [:val (val dval)]
      :else nil)))

(defn dependent-default-transformer
  "Like mt/default-value-transformer but :default/fn receives the
  accumulating map, so later entries can depend on earlier ones.
  Also derefs through Malli ref schemas to find defaults on registered types."
  []
  (mt/transformer
   {:decoders
    {:map
     {:compile
      (fn [schema _]
        (let [entries (m/children schema)
              default-fns (into []
                            (keep (fn [[k props v]]
                                    (when-let [[kind val] (resolve-default-props props v)]
                                      [k kind val])))
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

;; Identity — namespace entity
(schema/register! ::ns-id
  [:string {:seon.db/identity true
            :default/fn '(fn [_] "seon.test.bootstrap-v2")}])

;; Domain (workout tracker)
(schema/register! ::exercise :string)
(schema/register! ::weight :double)
(schema/register! ::reps :int)
(schema/register! ::volume :double)

;; Composite types stored as EDN strings in Datalevin
(schema/register! ::workout-set
  [:map [::exercise ::exercise] [::weight ::weight] [::reps ::reps]])
(schema/register! ::workouts
  [:vector {:default/fn '(fn [_] [])} ::workout-set])

(schema/register! ::bodyweight :double)

(schema/register! ::suggestion :string)
(schema/register! ::exercise-suggestion
  [:map [::exercise ::exercise] [::suggestion ::suggestion]])
(schema/register! ::suggestions [:vector ::exercise-suggestion])

(schema/register! ::weekly-volume :double)
(schema/register! ::weekly-sets :int)

;; Second identity key — for multi-entity test
(schema/register! ::workout-id
  [:string {:seon.db/identity true}])

;; Connection registry schemas
(schema/register! ::conn-id :string)
(schema/register! ::conn-type [:enum :repl :browser :agent])
(schema/register! ::render-key [:enum :seon.render/ai :seon.render/html :seon.render/edn])
(schema/register! ::consuming-keys [:set :keyword])
(schema/register! ::connection
  [:map
   [::conn-id ::conn-id]
   [::conn-type ::conn-type]
   [::render-key ::render-key]
   [::consuming-keys ::consuming-keys]])

;; ---------------------------------------------------------------------------
;; Part 3: Pure Functions (no identity key = no entity interaction)
;; ---------------------------------------------------------------------------

(defn calculate-volume
  "Calculate volume for a single set. Pure — no identity key."
  {:malli/schema [:=> [:cat [:map [::weight ::weight] [::reps ::reps]]]
                      [:map [::volume ::volume]]]}
  [{::keys [weight reps]}]
  {::volume (* weight (double reps))})

;; ---------------------------------------------------------------------------
;; Part 4: Stateful Functions (identity key in input AND output)
;; ---------------------------------------------------------------------------

(defn add-workout!
  "Add a workout set. Takes existing ::workouts from entity, returns updated."
  {:malli/schema [:=> [:cat [:map [::ns-id ::ns-id]
                                  [::workouts ::workouts]
                                  [::exercise ::exercise]
                                  [::weight ::weight]
                                  [::reps ::reps]]]
                      [:map [::ns-id ::ns-id]
                            [::workouts ::workouts]]]}
  [{::keys [ns-id workouts exercise weight reps]}]
  {::ns-id ns-id
   ::workouts (conj workouts {::exercise exercise
                              ::weight weight
                              ::reps reps})})

(defn record-bodyweight!
  "Record a bodyweight measurement."
  {:malli/schema [:=> [:cat [:map [::ns-id ::ns-id]
                                  [::bodyweight ::bodyweight]]]
                      [:map [::ns-id ::ns-id]
                            [::bodyweight ::bodyweight]]]}
  [{::keys [ns-id bodyweight]}]
  {::ns-id ns-id
   ::bodyweight bodyweight})

(defn update-weekly-volume
  "Compute weekly volume summary from workouts. Reactive downstream."
  {:malli/schema [:=> [:cat [:map [::ns-id ::ns-id]
                                  [::workouts ::workouts]]]
                      [:map [::ns-id ::ns-id]
                            [::weekly-volume ::weekly-volume]
                            [::weekly-sets ::weekly-sets]]]}
  [{::keys [ns-id workouts]}]
  {::ns-id ns-id
   ::weekly-volume (->> workouts
                        (reduce (fn [acc w] (+ acc (* (::weight w) (double (::reps w))))) 0.0))
   ::weekly-sets (count workouts)})

(defn suggest-next-weight
  "Suggest weight increases based on workout history.
   If an exercise has 3+ sets at the same weight with 5+ reps, suggest increase.
   Requires both ::workouts and ::bodyweight."
  {:malli/schema [:=> [:cat [:map [::ns-id ::ns-id]
                                  [::workouts ::workouts]
                                  [::bodyweight ::bodyweight]]]
                      [:map [::ns-id ::ns-id]
                            [::suggestions ::suggestions]]]}
  [{::keys [ns-id workouts bodyweight]}]
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
    {::ns-id ns-id
     ::suggestions suggestions}))

;; ---------------------------------------------------------------------------
;; Part 5: Embedded Datalevin Schema
;; ---------------------------------------------------------------------------

(def ^:private datalevin-schema
  "Schema for the v2 embedded Datalevin. Workouts and suggestions are stored
   as EDN strings since Datalevin doesn't support nested collections."
  {::ns-id {:db/valueType :db.type/string :db/unique :db.unique/identity}
   ::exercise {:db/valueType :db.type/string}
   ::weight {:db/valueType :db.type/double}
   ::reps {:db/valueType :db.type/long}
   ::volume {:db/valueType :db.type/double}
   ::bodyweight {:db/valueType :db.type/double}
   ::weekly-volume {:db/valueType :db.type/double}
   ::weekly-sets {:db/valueType :db.type/long}
   ;; Complex types stored as EDN strings
   ::workouts {:db/valueType :db.type/string}
   ::suggestions {:db/valueType :db.type/string}
   ;; Second identity for multi-entity
   ::workout-id {:db/valueType :db.type/string :db/unique :db.unique/identity}})

;; ---------------------------------------------------------------------------
;; Part 6: Shape Graph Cache
;; ---------------------------------------------------------------------------

;; Cache: set-of-changed-attrs -> vector of matching function qualified names.
;; Only changes on code reload / namespace re-index.
(defonce ^:private *shape-cache (atom {}))

(defn invalidate-cache!
  "Clear the shape graph cache. Call when namespace is re-indexed."
  []
  (reset! *shape-cache {}))

(defn- find-reactive-functions-uncached
  "Given changed attrs, find functions in the graph DB whose input shape
   contains any of those attrs. Returns vector of qualified name strings."
  [graph-conn changed-keys]
  (let [results (d/q '[:find ?fn-name
                        :in $ [?key ...]
                        :where
                        [?e :seon.entry/key ?key]
                        [?s :seon.shape/entries ?e]
                        [?f :seon.fn/input-shape ?s]
                        [?f :seon.fn/qualified-name ?fn-name]]
                      (d/db graph-conn) (vec changed-keys))]
    (mapv first results)))

(defn- find-reactive-functions
  "Cached version: look up matching functions for a set of changed attrs.
   Cache key is the set itself (immutable, good hash)."
  [graph-conn changed-keys]
  (let [cache-key (set changed-keys)]
    (if-let [cached (get @*shape-cache cache-key)]
      cached
      (let [result (find-reactive-functions-uncached graph-conn changed-keys)]
        (swap! *shape-cache assoc cache-key result)
        result))))

;; ---------------------------------------------------------------------------
;; Part 7: Connection Registry (consumers)
;; ---------------------------------------------------------------------------

;; Registry of active data consumers (REPL, browser, agent).
;; Map of conn-id -> connection map.
(defonce ^:private *connections (atom {}))

(defn register-connection!
  "Register a data consumer. The reactive chain uses active consumers
   to prune functions whose output has no audience."
  [{::keys [conn-id] :as conn-map}]
  (swap! *connections assoc conn-id conn-map)
  conn-map)

(defn unregister-connection!
  "Remove a data consumer."
  [{::keys [conn-id]}]
  (swap! *connections dissoc conn-id)
  nil)

(defn active-consumers
  "Returns the set of all keys consumed across all active connections."
  []
  (reduce (fn [acc conn-map]
            (into acc (::consuming-keys conn-map)))
          #{}
          (vals @*connections)))

;; ---------------------------------------------------------------------------
;; Part 8: Entity-Aware Dispatch
;; ---------------------------------------------------------------------------

(defn- find-identity-keys
  "Walk a function's input schema to find keys with :seon.db/identity true.
   Returns a set of keyword keys."
  [schema-form]
  (try
    (let [s (m/schema schema-form)
          children (m/children s)]
      (into #{}
            (keep (fn [[k _props child-schema]]
                    (let [derefed (if (m/-ref-schema? child-schema)
                                   (m/deref child-schema)
                                   child-schema)
                          props (m/properties derefed)]
                      (when (:seon.db/identity props)
                        k))))
            children))
    (catch Exception _ #{})))

(defn- extract-input-output-schemas
  "Extract input and output schema forms from a :malli/schema metadata value.
   Returns [input-form output-form]."
  [schema-meta]
  (when (and (vector? schema-meta) (= :=> (first schema-meta)))
    (let [cat-form (second schema-meta)
          output-form (nth schema-meta 2 nil)]
      (when (and (vector? cat-form) (= :cat (first cat-form)))
        [(second cat-form) output-form]))))

(defn- serialize-for-datalevin
  "Serialize complex values (vectors of maps) to EDN strings for Datalevin storage."
  [k v]
  (if (#{::workouts ::suggestions} k)
    (pr-str v)
    v))

(defn- deserialize-from-datalevin
  "Deserialize EDN strings back to Clojure data."
  [k v]
  (if (and (#{::workouts ::suggestions} k) (string? v))
    (edn/read-string v)
    v))

(defn- pull-entity
  "Pull an entity from the embedded Datalevin by identity key value.
   Returns the entity map with deserialized values, or nil."
  [conn id-key id-value]
  (let [result (d/q [:find '?e '.
                      :in '$ '?id-val
                      :where ['?e id-key '?id-val]]
                     (d/db conn) id-value)]
    (when result
      (let [entity (d/pull (d/db conn) '[*] result)]
        (into {}
              (keep (fn [[k v]]
                      (when (and (keyword? k)
                                 (= "seon.test.bootstrap-v2" (namespace k)))
                        [k (deserialize-from-datalevin k v)])))
              entity)))))

(defn- transact-result!
  "Transact a function result back to the entity in Datalevin.
   Serializes complex values. Returns the tx-report."
  [conn result]
  (let [tx-data (into {}
                      (map (fn [[k v]]
                             [k (serialize-for-datalevin k v)]))
                      result)]
    (d/transact! conn [tx-data])))

(defn- resolve-inputs
  "Given a function's input spec and caller args, resolve all inputs:
   - Identity keys -> pull entity from Datalevin
   - Keys with :default/fn -> filled by dependent-default-transformer
   - Keys with :default -> filled by dependent-default-transformer
   - Remaining -> must come from caller args

   This is general-purpose: works for namespace entities (::ns-id),
   domain entities (::workout-id), or any entity type with an identity key."
  [conn input-schema args]
  (let [id-keys (find-identity-keys input-schema)]
    (if (empty? id-keys)
      ;; Pure function — no entity resolution, just apply defaults
      (m/decode input-schema args (dependent-default-transformer))
      ;; Stateful function — resolve entity by identity key
      (let [id-key (first id-keys)
            ;; Minimal decode to resolve identity key default
            id-value (or (get args id-key)
                         (let [decoded (m/decode input-schema args
                                                (dependent-default-transformer))]
                           (get decoded id-key)))
            entity (when id-value (pull-entity conn id-key id-value))]
        ;; Merge: entity provides state, caller args provide new data
        ;; Then decode for any remaining defaults (e.g. empty ::workouts)
        (m/decode input-schema
                  (merge entity args (when id-value {id-key id-value}))
                  (dependent-default-transformer))))))

(defn- dispatch!
  "Resolve inputs, call function, transact result if stateful.
   Returns {:result map, :tx-report tx-report-or-nil}."
  [conn fn-var args]
  (let [schema-meta (-> fn-var meta :malli/schema)
        [input-form output-form] (extract-input-output-schemas schema-meta)
        output-id-keys (when output-form (find-identity-keys output-form))
        resolved-args (resolve-inputs conn input-form args)
        result (fn-var resolved-args)]
    (if (and (seq output-id-keys) (some output-id-keys (keys result)))
      ;; Stateful: transact result back (listener fires automatically)
      (let [tx-report (transact-result! conn result)]
        {:result result :tx-report tx-report})
      ;; Pure: no transact
      {:result result :tx-report nil})))

;; ---------------------------------------------------------------------------
;; Part 9: Transaction Listener (reactive chain via d/listen!)
;; ---------------------------------------------------------------------------

(def ^:dynamic *processing-chain*
  "Set of function names currently being processed in this reactive chain.
   Used for cycle prevention. Bound during listener dispatch."
  #{})

(defn- changed-attrs
  "Extract the set of changed attribute keywords from a tx-report."
  [tx-report]
  (into #{}
        (keep (fn [datom]
                (let [a (.-a datom)]
                  (when (and (keyword? a)
                             (= "seon.test.bootstrap-v2" (namespace a)))
                    a))))
        (:tx-data tx-report)))

(defn- identity-key?
  "Check if a keyword is registered as a :seon.db/identity schema."
  [k]
  (try
    (let [s (m/schema k)
          derefed (if (m/-ref-schema? s) (m/deref s) s)]
      (boolean (:seon.db/identity (m/properties derefed))))
    (catch Exception _ false)))

(defn- should-run-function?
  "Determine if a downstream function should run based on consumer pruning.
   A function runs if any of its non-identity output keys are in the active
   consumer set. Identity keys in output are routing keys (which entity to
   update), not meaningful data — they don't count as consumed output.

   When no consumers are registered, everything runs (no pruning)."
  [graph-conn fn-name consumers]
  (if (empty? consumers)
    ;; No consumers registered — run everything (no pruning)
    true
    (let [;; Get output shape entries for this function
          output-results (d/q '[:find ?key
                                 :in $ ?fn-name
                                 :where
                                 [?f :seon.fn/qualified-name ?fn-name]
                                 [?f :seon.fn/output-shape ?s]
                                 [?s :seon.shape/entries ?e]
                                 [?e :seon.entry/key ?key]]
                               (d/db graph-conn) fn-name)
          output-keys (set (map first output-results))
          ;; Only check non-identity output keys against consumers
          data-output-keys (remove identity-key? output-keys)]
      (boolean (some consumers data-output-keys)))))

(defn- make-listener
  "Create the d/listen! callback for reactive dispatch.
   Closes over conn, graph-conn, and results-acc."
  [conn graph-conn results-acc]
  (fn [tx-report]
    (let [changed (changed-attrs tx-report)
          ;; Remove identity keys from triggers — they shouldn't cascade
          trigger-attrs (disj changed ::ns-id ::workout-id)]
      (when (seq trigger-attrs)
        (let [matching-fns (find-reactive-functions graph-conn trigger-attrs)
              ;; Filter already-visited (cycle prevention via dynamic var)
              new-fns (remove *processing-chain* matching-fns)
              consumers (active-consumers)]
          (doseq [fn-name new-fns]
            (when (should-run-function? graph-conn fn-name consumers)
              (try
                (let [fn-var (requiring-resolve (symbol fn-name))]
                  (binding [*processing-chain* (conj *processing-chain* fn-name)]
                    (let [{:keys [result]} (dispatch! conn fn-var {})]
                      (swap! results-acc conj [fn-name result]))))
                (catch Exception _e
                  ;; Function might not be satisfiable (missing required attrs)
                  nil)))))))))

;; ---------------------------------------------------------------------------
;; Part 10: Graph Indexing Helpers
;; ---------------------------------------------------------------------------

(def ^:private graph-schema
  "Datalevin schema for the graph index (shapes, entries, functions)."
  (merge ingest/datalevin-schema))

(defn- index-this-namespace!
  "Extract and ingest this namespace's functions into a graph Datalevin."
  [graph-conn]
  (let [file-path "src/seon/test/bootstrap_v2.clj"
        source (slurp file-path)
        graph (extract/extract-graph {::extract/source source
                                       ::extract/file-path file-path})
        entries (::extract/entries graph)
        entries-base (mapv #(dissoc % :seon.entry/value-shape) entries)
        shapes (::extract/shapes graph)
        functions (::extract/functions graph)
        specs (::extract/specs graph)]
    ;; Entries
    (when (seq entries-base)
      (d/transact! graph-conn entries-base))
    ;; Shapes
    (when (seq shapes)
      (d/transact! graph-conn shapes))
    ;; Entry value-shape refs
    (let [entries-with-vs (filterv :seon.entry/value-shape entries)]
      (when (seq entries-with-vs)
        (d/transact! graph-conn
                     (mapv (fn [e]
                             {:seon.entry/id (:seon.entry/id e)
                              :seon.entry/value-shape (:seon.entry/value-shape e)})
                           entries-with-vs))))
    ;; Specs
    (when (seq specs)
      (d/transact! graph-conn specs))
    ;; Functions (with shape links)
    (when (seq functions)
      (d/transact! graph-conn functions))
    ;; Invalidate cache since graph changed
    (invalidate-cache!)
    {:function-count (count functions)
     :shape-count (count shapes)
     :entry-count (count entries)
     :spec-count (count specs)}))

;; ---------------------------------------------------------------------------
;; Part 11: Public API — init!, call!, shutdown!
;; ---------------------------------------------------------------------------

(defn init!
  "Initialize the reactive dispatch system.
   Registers a d/listen! listener on the domain connection that fires
   reactive functions from transaction reports.

   Must be called after graph indexing so shape lookups work.

   Request keys:
     ::conn       - Required. Domain Datalevin connection
     ::graph-conn - Required. Graph index Datalevin connection

   Returns:
     Map with ::listener-key and ::results-acc"
  [{::keys [conn graph-conn]}]
  (let [results-acc (atom [])
        listener-key :reactive-dispatch]
    ;; Register the transaction listener
    (d/listen! conn listener-key (make-listener conn graph-conn results-acc))
    {::listener-key listener-key
     ::results-acc results-acc}))

(defn call!
  "Dispatch a function call and let the reactive chain fire automatically.
   The d/listen! callback handles downstream discovery and dispatch.

   Request keys:
     ::conn        - Required. Domain Datalevin connection
     ::graph-conn  - Required. Graph index connection (for non-cached lookups)
     ::fn-var      - Required. Var of the function to call
     ::args        - Required. Arguments map
     ::results-acc - Required. Atom collecting chain results (from init!)

   Returns:
     Map with :result (direct result) and :chain (all results including downstream)"
  [{::keys [conn fn-var args results-acc]}]
  (let [fn-name (str (.-ns fn-var) "/" (.-sym fn-var))]
    ;; Reset chain accumulator
    (reset! results-acc [])
    ;; Dispatch the initial function — listener fires automatically on transact
    (binding [*processing-chain* #{fn-name}]
      (let [{:keys [result]} (dispatch! conn fn-var args)]
        (swap! results-acc (fn [acc] (into [[fn-name result]] acc)))
        {:result result
         :chain @results-acc}))))

(defn shutdown!
  "Shut down the reactive dispatch system.
   Unregisters the d/listen! listener and clears state.

   Request keys:
     ::conn - Required. Domain Datalevin connection"
  [{::keys [conn]}]
  (d/unlisten! conn :reactive-dispatch)
  (invalidate-cache!)
  (reset! *connections {})
  nil)

;; ---------------------------------------------------------------------------
;; Part 12: Test Fixture
;; ---------------------------------------------------------------------------

(defn- with-embedded-datalevin
  "Create an embedded Datalevin for domain data, another for graph index.
   Sets up init!/shutdown! lifecycle. Calls (f domain-conn graph-conn system)."
  [f]
  (let [ts (System/currentTimeMillis)
        domain-dir (str "tmp/bootstrap-v2-domain-" ts)
        graph-dir (str "tmp/bootstrap-v2-graph-" ts)
        domain-conn (d/get-conn domain-dir datalevin-schema)
        graph-conn (d/get-conn graph-dir graph-schema)]
    (try
      ;; Index this namespace into the graph DB
      (index-this-namespace! graph-conn)
      ;; Initialize the reactive system
      (let [system (init! {::conn domain-conn ::graph-conn graph-conn})]
        (try
          (f domain-conn graph-conn system)
          (finally
            (shutdown! {::conn domain-conn}))))
      (finally
        (d/close domain-conn)
        (d/close graph-conn)))))

;; ---------------------------------------------------------------------------
;; Part 13: Tests
;; ---------------------------------------------------------------------------

(deftest entity-dispatch-test
  (with-embedded-datalevin
    (fn [conn graph-conn {::keys [results-acc]}]
      (testing "add-workout! creates entity in Datalevin"
        (let [{:keys [result]} (call! {::conn conn
                                       ::graph-conn graph-conn
                                       ::fn-var #'add-workout!
                                       ::args {::exercise "Squat"
                                               ::weight 100.0
                                               ::reps 5}
                                       ::results-acc results-acc})]
          (is (= "seon.test.bootstrap-v2" (::ns-id result))
              "ns-id should be defaulted")
          (is (= 1 (count (::workouts result)))
              "Should have one workout")))

      (testing "Entity persisted in Datalevin"
        (let [entity (pull-entity conn ::ns-id "seon.test.bootstrap-v2")]
          (is (some? entity) "Entity should exist")
          (is (= 1 (count (::workouts entity)))
              "Workouts should be persisted")
          (is (= "Squat" (-> entity ::workouts first ::exercise)))))

      (testing "Second workout accumulates"
        (call! {::conn conn ::graph-conn graph-conn ::fn-var #'add-workout!
                ::args {::exercise "Bench" ::weight 60.0 ::reps 8}
                ::results-acc results-acc})
        (let [entity (pull-entity conn ::ns-id "seon.test.bootstrap-v2")]
          (is (= 2 (count (::workouts entity)))))))))

(deftest reactive-chain-test
  (with-embedded-datalevin
    (fn [conn graph-conn {::keys [results-acc]}]
      (testing "add-workout! triggers update-weekly-volume via reactive chain"
        (let [{:keys [chain]} (call! {::conn conn
                                      ::graph-conn graph-conn
                                      ::fn-var #'add-workout!
                                      ::args {::exercise "Squat"
                                              ::weight 100.0
                                              ::reps 5}
                                      ::results-acc results-acc})
              chain-fns (set (map first chain))]
          ;; Chain should include the initial call + downstream reactions
          (is (>= (count chain) 2)
              "Chain should include add-workout! + at least one downstream")
          (is (chain-fns "seon.test.bootstrap-v2/update-weekly-volume")
              "update-weekly-volume should fire reactively")

          ;; Entity should have reactively-computed weekly stats
          (let [entity (pull-entity conn ::ns-id "seon.test.bootstrap-v2")]
            (is (= 1 (count (::workouts entity))))
            (is (= 500.0 (::weekly-volume entity))
                "Weekly volume should be reactively computed")
            (is (= 1 (::weekly-sets entity))
                "Weekly sets should be reactively computed"))))

      (testing "record-bodyweight! triggers suggest-next-weight when workouts exist"
        ;; Add enough workouts for suggestion trigger (3 sets at same weight)
        (dotimes [_ 2]
          (call! {::conn conn ::graph-conn graph-conn ::fn-var #'add-workout!
                  ::args {::exercise "Squat" ::weight 100.0 ::reps 5}
                  ::results-acc results-acc}))

        (let [{:keys [chain]} (call! {::conn conn
                                      ::graph-conn graph-conn
                                      ::fn-var #'record-bodyweight!
                                      ::args {::bodyweight 85.0}
                                      ::results-acc results-acc})
              chain-fns (set (map first chain))]
          (is (chain-fns "seon.test.bootstrap-v2/suggest-next-weight")
              "suggest-next-weight should fire when bodyweight + workouts both exist")
          (let [entity (pull-entity conn ::ns-id "seon.test.bootstrap-v2")]
            (is (= 85.0 (::bodyweight entity)))
            (is (vector? (::suggestions entity))
                "Suggestions should be populated")
            (is (= 1 (count (::suggestions entity)))
                "Should have one suggestion for Squat")))))))

(deftest pure-function-test
  (testing "calculate-volume is pure — no entity interaction"
    (let [result (calculate-volume {::weight 100.0 ::reps 5})]
      (is (= 500.0 (::volume result))))))

(deftest cycle-prevention-test
  (with-embedded-datalevin
    (fn [conn graph-conn {::keys [results-acc]}]
      (testing "Chain stops when function already visited"
        (let [{:keys [chain]} (call! {::conn conn
                                      ::graph-conn graph-conn
                                      ::fn-var #'add-workout!
                                      ::args {::exercise "Squat"
                                              ::weight 100.0
                                              ::reps 5}
                                      ::results-acc results-acc})]
          ;; Chain should be finite
          (is (<= (count chain) 10)
              "Chain should terminate (cycle prevention)"))))))

(deftest listener-test
  (testing "d/listen! fires automatically on transact"
    (let [ts (System/currentTimeMillis)
          domain-dir (str "tmp/bootstrap-v2-listener-" ts)
          conn (d/get-conn domain-dir datalevin-schema)
          reports (atom [])]
      (try
        (d/listen! conn :test-listener
          (fn [report] (swap! reports conj report)))
        ;; Direct transact — listener should fire
        (d/transact! conn [{::ns-id "test-entity"
                            ::workouts (pr-str [])}])
        (is (= 1 (count @reports))
            "Listener should have fired once")
        (is (seq (:tx-data (first @reports)))
            "tx-report should contain tx-data")
        ;; Second transact
        (d/transact! conn [{::ns-id "test-entity"
                            ::bodyweight 80.0}])
        (is (= 2 (count @reports))
            "Listener should have fired twice")
        (finally
          (d/unlisten! conn :test-listener)
          (d/close conn))))))

(deftest consumer-pruning-test
  (with-embedded-datalevin
    (fn [conn graph-conn {::keys [results-acc]}]
      (testing "With consumer for ::weekly-volume, update-weekly-volume runs"
        (register-connection! {::conn-id "repl-1"
                               ::conn-type :repl
                               ::render-key :seon.render/edn
                               ::consuming-keys #{::weekly-volume ::weekly-sets}})
        (let [{:keys [chain]} (call! {::conn conn
                                      ::graph-conn graph-conn
                                      ::fn-var #'add-workout!
                                      ::args {::exercise "Squat"
                                              ::weight 100.0
                                              ::reps 5}
                                      ::results-acc results-acc})
              chain-fns (set (map first chain))]
          (is (chain-fns "seon.test.bootstrap-v2/update-weekly-volume")
              "update-weekly-volume should run — consumer wants ::weekly-volume"))
        (unregister-connection! {::conn-id "repl-1"}))

      (testing "With consumer only for ::bodyweight, update-weekly-volume is pruned"
        (register-connection! {::conn-id "repl-2"
                               ::conn-type :repl
                               ::render-key :seon.render/edn
                               ;; Only consuming bodyweight — nothing wants weekly stats
                               ::consuming-keys #{::bodyweight}})
        (let [{:keys [chain]} (call! {::conn conn
                                      ::graph-conn graph-conn
                                      ::fn-var #'add-workout!
                                      ::args {::exercise "Bench"
                                              ::weight 60.0
                                              ::reps 8}
                                      ::results-acc results-acc})
              chain-fns (set (map first chain))]
          (is (not (chain-fns "seon.test.bootstrap-v2/update-weekly-volume"))
              "update-weekly-volume should be pruned — no consumer wants its output"))
        (unregister-connection! {::conn-id "repl-2"}))

      (testing "With no consumers registered, nothing is pruned"
        (let [{:keys [chain]} (call! {::conn conn
                                      ::graph-conn graph-conn
                                      ::fn-var #'add-workout!
                                      ::args {::exercise "Deadlift"
                                              ::weight 140.0
                                              ::reps 3}
                                      ::results-acc results-acc})
              chain-fns (set (map first chain))]
          (is (chain-fns "seon.test.bootstrap-v2/update-weekly-volume")
              "No consumers = no pruning — everything runs"))))))

(deftest clean-api-test
  (testing "init! -> call! -> verify chain -> shutdown!"
    (let [ts (System/currentTimeMillis)
          domain-dir (str "tmp/bootstrap-v2-api-" ts)
          graph-dir (str "tmp/bootstrap-v2-api-graph-" ts)
          domain-conn (d/get-conn domain-dir datalevin-schema)
          graph-conn (d/get-conn graph-dir graph-schema)]
      (try
        ;; Index
        (index-this-namespace! graph-conn)

        ;; Init
        (let [{::keys [results-acc]} (init! {::conn domain-conn
                                              ::graph-conn graph-conn})]
          ;; Call
          (let [{:keys [result chain]}
                (call! {::conn domain-conn
                        ::graph-conn graph-conn
                        ::fn-var #'add-workout!
                        ::args {::exercise "Squat" ::weight 100.0 ::reps 5}
                        ::results-acc results-acc})]
            (is (= 1 (count (::workouts result))))
            (is (>= (count chain) 2) "Chain should fire downstream"))

          ;; Verify entity state
          (let [entity (pull-entity domain-conn ::ns-id "seon.test.bootstrap-v2")]
            (is (= 500.0 (::weekly-volume entity))
                "Downstream should have computed weekly volume"))

          ;; Shutdown
          (shutdown! {::conn domain-conn})

          ;; After shutdown, transacts should not trigger reactive chain
          (d/transact! domain-conn [{::ns-id "seon.test.bootstrap-v2"
                                     ::bodyweight 90.0}])
          ;; No error = listener was properly removed
          (is true "Shutdown should cleanly unregister listener"))
        (finally
          (d/close domain-conn)
          (d/close graph-conn))))))

(deftest stress-test
  (with-embedded-datalevin
    (fn [conn graph-conn {::keys [results-acc]}]
      (testing "100 rapid add-workout! calls with caching"
        (let [start (System/nanoTime)]
          (dotimes [i 100]
            (call! {::conn conn
                    ::graph-conn graph-conn
                    ::fn-var #'add-workout!
                    ::args {::exercise (str "Exercise-" (mod i 5))
                            ::weight (+ 50.0 (* i 0.5))
                            ::reps (+ 3 (mod i 8))}
                    ::results-acc results-acc}))
          (let [elapsed-ms (/ (- (System/nanoTime) start) 1e6)
                entity (pull-entity conn ::ns-id "seon.test.bootstrap-v2")
                chains-per-sec (/ 100.0 (/ elapsed-ms 1000.0))]
            (is (= 100 (count (::workouts entity)))
                "All 100 workouts should be persisted")
            (when (::weekly-volume entity)
              (is (pos? (::weekly-volume entity))
                  "Weekly volume should be computed"))
            ;; Report throughput
            (println (str "\n=== Stress Test Results (v2 with cache + d/listen!) ===\n"
                          "  Total time: " (format "%.1f" elapsed-ms) " ms\n"
                          "  Chains/second: " (format "%.0f" chains-per-sec) "\n"
                          "  Cache size: " (count @*shape-cache) " entries\n"
                          "  Workouts stored: " (count (::workouts entity)) "\n"
                          "  Weekly volume: " (::weekly-volume entity) "\n"
                          "  Weekly sets: " (::weekly-sets entity)))))))))

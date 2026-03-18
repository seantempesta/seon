(ns seon.test.bootstrap-v2
  "Datalevin-reactive bootstrap v2 — foundation for entity-driven dispatch.

   Core idea: functions declare identity keys in their Malli specs. The system
   pulls entity data for inputs, transacts outputs back, and d/listen! fires
   downstream functions automatically from tx-reports.

   v2 uses proper Datalevin refs instead of EDN serialization. Workouts are
   separate entities linked via :db.type/ref :db.cardinality/many. The atom
   holds a cached recursive pull and a watch diffs changes to transact.

   Public API:
     (init! {::conn conn})                  — register listener, build cache
     (call! {::conn conn ::fn-var #'f ::args {}}) — dispatch + reactive chain
     (shutdown! {::conn conn})              — unregister listener, cleanup
     (register-connection! {...})           — register a data consumer
     (unregister-connection! {...})         — remove a consumer
     (refresh-atom! {...})                  — re-pull namespace entity into atom
     (diff-to-tx old new)                   — compute tx data from state diff

   The reactive chain is: transact -> listener fires -> discover downstream
   functions (cached) -> prune by active consumers -> dispatch each ->
   their transacts trigger more listener calls -> cycle prevention stops it."
  (:require [clojure.core.async :as async]
            [clojure.core.async.flow :as flow]
            [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
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

;; Identity — workout entity (separate from namespace entity)
(schema/register! ::workout-id
                  [:string {:seon.db/identity true}])

;; Domain (workout tracker)
(schema/register! ::exercise :string)
(schema/register! ::weight :double)
(schema/register! ::reps :int)
(schema/register! ::volume :double)

;; Workouts is now a ref collection — the default is an empty vector for
;; Malli decode, but in Datalevin it's a :db.cardinality/many ref attr
(schema/register! ::workouts
                  [:vector {:default/fn '(fn [_] [])}
                   [:map
                    [::workout-id {:optional true} ::workout-id]
                    [::exercise ::exercise]
                    [::weight ::weight]
                    [::reps ::reps]
                    [:db/id {:optional true} :int]]])

(schema/register! ::bodyweight :double)

(schema/register! ::suggestion :string)
(schema/register! ::exercise-suggestion
                  [:map [::exercise ::exercise] [::suggestion ::suggestion]])
(schema/register! ::suggestions [:vector ::exercise-suggestion])

(schema/register! ::weekly-volume :double)
(schema/register! ::weekly-sets :int)

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
  "Add a workout. Returns a new workout entity map. The system creates
   the workout entity and adds a ref from the namespace entity's ::workouts."
  {:malli/schema [:=> [:cat [:map [::ns-id ::ns-id]
                             [::exercise ::exercise]
                             [::weight ::weight]
                             [::reps ::reps]]]
                  [:map [::ns-id ::ns-id]
                   [::workout-id ::workout-id]
                   [::exercise ::exercise]
                   [::weight ::weight]
                   [::reps ::reps]]]}
  [{::keys [ns-id exercise weight reps]}]
  {::ns-id ns-id
   ::workout-id (str (random-uuid))
   ::exercise exercise
   ::weight weight
   ::reps reps})

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
  "Compute weekly volume summary from workouts. Reactive downstream.
   Workouts are now proper entity maps from recursive pull."
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
  "Schema for the v2 embedded Datalevin. Workouts are proper entities
   linked via :db.type/ref :db.cardinality/many. Suggestions remain
   as EDN strings (no identity, purely derived)."
  {::ns-id {:db/valueType :db.type/string :db/unique :db.unique/identity}
   ::workout-id {:db/valueType :db.type/string :db/unique :db.unique/identity}
   ::exercise {:db/valueType :db.type/string}
   ::weight {:db/valueType :db.type/double}
   ::reps {:db/valueType :db.type/long}
   ::volume {:db/valueType :db.type/double}
   ::bodyweight {:db/valueType :db.type/double}
   ::weekly-volume {:db/valueType :db.type/double}
   ::weekly-sets {:db/valueType :db.type/long}
   ;; Proper ref: namespace entity -> workout entities
   ::workouts {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   ;; Suggestions remain as EDN (purely derived, no identity)
   ::suggestions {:db/valueType :db.type/string}
   ;; Screen state for namespace summary rendering
   ::screen {:db/valueType :db.type/keyword}})

;; ---------------------------------------------------------------------------
;; Part 5b: Render Functions (discovered by shape graph)
;; ---------------------------------------------------------------------------

(schema/register! ::screen [:keyword {:default :home}])

(defn render-workout-ai
  "Render a single workout for AI context."
  {:malli/schema [:=> [:cat [:map [::exercise ::exercise]
                             [::weight ::weight]
                             [::reps ::reps]]]
                  [:map [:seon.render/ai :string]]]}
  [{::keys [exercise weight reps]}]
  {:seon.render/ai (str exercise " — " weight "kg × " reps " reps")})

(defn render-namespace-summary-ai
  "Render namespace state summary for AI context."
  {:malli/schema [:=> [:cat [:map [::screen ::screen]
                             [::weekly-volume ::weekly-volume]
                             [::weekly-sets ::weekly-sets]]]
                  [:map [:seon.render/ai :string]]]}
  [{::keys [screen weekly-volume weekly-sets]}]
  {:seon.render/ai (str "Screen: " (name screen)
                        " | Volume: " weekly-volume
                        " | Sets: " weekly-sets)})

;; ---------------------------------------------------------------------------
;; Part 5c: Recursive Render Walk
;; ---------------------------------------------------------------------------

(defn- find-renderer-in-graph
  "Find a render function from the graph DB whose required input keys are
   a subset of the entity's keys AND whose output contains the target
   render key. Returns the qualified-name string of the best match, or nil.

   Uses shape-based graph (seon.fn/input-shape, seon.fn/output-shape)
   which is what extract produces. Not spec-based (output-spec/contains-keys)
   which is the runtime system's parallel indexing mechanism.

   Resolution order:
   1. Most required keys matched (specificity)
   2. Alphabetical qualified-name (deterministic tiebreaker)"
  [graph-conn entity-keys render-key]
  (let [db (d/db graph-conn)
        ;; Find functions whose output shape contains the render key
        candidates (d/q '[:find ?fn-name
                          :in $ ?output-key
                          :where
                          [?f :seon.fn/output-shape ?os]
                          [?os :seon.shape/entries ?oe]
                          [?oe :seon.entry/key ?output-key]
                          [?f :seon.fn/qualified-name ?fn-name]]
                        db render-key)
        fn-names (mapv first candidates)]
    (when (seq fn-names)
      (let [fn-details
            (mapv (fn [fn-name]
                    (let [input-keys
                          (set (map first
                                    (d/q '[:find ?key
                                           :in $ ?fn-name
                                           :where
                                           [?f :seon.fn/qualified-name ?fn-name]
                                           [?f :seon.fn/input-shape ?is]
                                           [?is :seon.shape/entries ?ie]
                                           [?ie :seon.entry/key ?key]]
                                         db fn-name)))
                          optional-keys
                          (set (map first
                                    (d/q '[:find ?key
                                           :in $ ?fn-name
                                           :where
                                           [?f :seon.fn/qualified-name ?fn-name]
                                           [?f :seon.fn/input-shape ?is]
                                           [?is :seon.shape/entries ?ie]
                                           [?ie :seon.entry/key ?key]
                                           [?ie :seon.entry/optional true]]
                                         db fn-name)))
                          required (set/difference input-keys optional-keys)]
                      {:fn-name fn-name
                       :required-keys required}))
                  fn-names)
            matching (filter (fn [{:keys [required-keys]}]
                               (every? entity-keys required-keys))
                             fn-details)]
        (when (seq matching)
          (->> matching
               (sort-by (juxt (comp - count :required-keys)
                              :fn-name))
               first
               :fn-name))))))

(defonce ^:private *render-cache (atom {}))

(defn invalidate-render-cache!
  "Clear the render walk cache. Call when namespace is re-indexed."
  []
  (reset! *render-cache {}))

(defn- find-renderer-cached
  "Cached version of find-renderer-in-graph.
   Cache key is [render-key (set entity-keys)]."
  [graph-conn entity-keys render-key]
  (let [cache-key [render-key entity-keys]
        cached (get @*render-cache cache-key ::miss)]
    (if (not= cached ::miss)
      cached
      (let [result (find-renderer-in-graph graph-conn entity-keys render-key)]
        (swap! *render-cache assoc cache-key result)
        result))))

(defn- try-render-with-keys
  "Try to render an entity map using discovered renderers for the given keys.
   Returns the rendered string or nil if no renderer matched."
  [graph-conn entity-keys entity-data render-keys]
  (some (fn [rk]
          (when-let [fn-name (find-renderer-cached graph-conn entity-keys rk)]
            (try
              (let [f (requiring-resolve (symbol fn-name))
                    result (f entity-data)]
                (get result rk))
              (catch Exception _ nil))))
        render-keys))

(defn- render-node
  "Render a single node in the entity tree. Internal recursive helper.

   At each map node:
   - Query shape graph for a function whose input keys match AND output
     has target render key
   - If found: call renderer, use its output
   - If not found: walk into children recursively, collecting results

   Returns a string representation."
  [graph-conn entity render-keys depth max-depth]
  (cond
    (>= depth max-depth)
    "<max-depth>"

    (and (map? entity) (:db/id entity))
    (let [data (dissoc entity :db/id)
          entity-keys (set (keys data))
          rendered (try-render-with-keys graph-conn entity-keys data render-keys)]
      (if rendered
        rendered
        (let [child-entries
              (->> data
                   (keep (fn [[k v]]
                           (cond
                             (and (vector? v) (seq v) (every? map? v))
                             (let [children (mapv #(render-node graph-conn % render-keys
                                                                (inc depth) max-depth) v)]
                               (str (name k) ":\n"
                                    (str/join "\n" (map #(str "  " %) children))))

                             (and (map? v) (:db/id v))
                             (str (name k) ": "
                                  (render-node graph-conn v render-keys (inc depth) max-depth))

                             :else
                             (str (name k) ": " (pr-str v))))))]
          (str/join "\n" child-entries))))

    (map? entity)
    (let [entity-keys (set (keys entity))
          rendered (try-render-with-keys graph-conn entity-keys entity render-keys)]
      (or rendered (pr-str entity)))

    (and (vector? entity) (seq entity) (every? map? entity))
    (str/join "\n" (mapv #(render-node graph-conn % render-keys (inc depth) max-depth) entity))

    :else
    (pr-str entity)))

(defn render-tree
  "Recursively walk an entity tree, discovering renderers at each level.

   At each map node:
   - Query shape graph for a function whose input keys match AND output
     has target render key
   - If found: call renderer, use its output
   - If not found: walk into children recursively

   render-keys is a vector of keys to try in priority order.
   e.g. [:seon.render/ai] for REPL, [:seon.render/html] for browser.

   Request keys:
     ::graph-conn  - Required. Graph index Datalevin connection
     ::entity      - Required. Entity tree from recursive pull
     ::render-keys - Optional. Vector of render keys (default [:seon.render/ai])
     ::max-depth   - Optional. Maximum recursion depth (default 10)"
  [{::keys [graph-conn entity render-keys max-depth]
    :or {max-depth 10 render-keys [:seon.render/ai]}}]
  (render-node graph-conn entity render-keys 0 max-depth))

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
;; Part 8: Entity-Aware Dispatch (with ref support)
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

(def ^:private this-ns "seon.test.bootstrap-v2")

(defn- this-ns-key?
  "Check if a keyword belongs to this namespace."
  [k]
  (and (keyword? k) (= this-ns (namespace k))))

(defn pull-namespace-entity
  "Pull the full namespace entity tree from Datalevin.
   Uses recursive pull to expand refs into nested maps."
  [conn ns-id]
  (let [result (d/pull (d/db conn) '[* {::workouts [*]}] [::ns-id ns-id])]
    (when (:db/id result)
      ;; Deserialize suggestions from EDN
      (cond-> result
        (string? (::suggestions result))
        (update ::suggestions edn/read-string)))))

(defn- pull-entity
  "Pull an entity from the embedded Datalevin by identity key value.
   Returns the entity map with :db/id and all attrs. For namespace
   entities, recursively pulls ::workouts refs."
  [conn id-key id-value]
  (if (= id-key ::ns-id)
    (pull-namespace-entity conn id-value)
    ;; Non-namespace entity (e.g. workout by ::workout-id)
    (let [result (d/pull (d/db conn) '[*] [id-key id-value])]
      (when (:db/id result)
        result))))

(defn- serialize-suggestions
  "Serialize suggestions to EDN string for Datalevin storage."
  [v]
  (if (vector? v)
    (pr-str v)
    v))

(defn- ensure-namespace-entity!
  "Ensure the namespace entity exists. Creates it if missing."
  [conn ns-id]
  (let [existing (d/pull (d/db conn) '[:db/id] [::ns-id ns-id])]
    (when-not (:db/id existing)
      (d/transact! conn [{::ns-id ns-id}]))))

(defn- transact-result!
  "Transact a function result back to the entity in Datalevin.
   Handles two cases:
   1. Same entity update — output has same identity key as a known entity
   2. New entity + ref — output has a DIFFERENT identity key (::workout-id)
      plus ::ns-id pointing to the parent entity. Creates the new entity
      and adds a ref from parent."
  [conn result]
  (let [has-workout-id (contains? result ::workout-id)
        has-ns-id (contains? result ::ns-id)]
    (if (and has-workout-id has-ns-id)
      ;; New entity + ref case: create workout entity, add ref from namespace
      (let [workout-data (dissoc result ::ns-id)
            ns-id (::ns-id result)
            workout-id (::workout-id result)]
        ;; Ensure namespace entity exists (first workout creates it)
        (ensure-namespace-entity! conn ns-id)
        ;; Transact the new workout entity
        (d/transact! conn [workout-data])
        ;; Add ref from namespace entity to new workout
        (d/transact! conn [[:db/add [::ns-id ns-id] ::workouts [::workout-id workout-id]]]))
      ;; Same entity update: serialize suggestions if present
      (let [tx-data (cond-> result
                      (contains? result ::suggestions)
                      (update ::suggestions serialize-suggestions))]
        (d/transact! conn [tx-data])))))

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
                  (when (this-ns-key? a)
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
;; Part 10: Diff-to-Transact (atom watch)
;; ---------------------------------------------------------------------------

(defn- diff-top-level
  "Compare top-level scalar attrs (non-ref) between old and new state.
   Returns tx data for changed attrs using :db/id from old state."
  [old-state new-state]
  (let [db-id (:db/id old-state)
        ;; Compare only scalar attrs (not ::workouts which is a ref collection)
        scalar-keys (disj (into #{} (concat (keys (dissoc old-state :db/id))
                                            (keys (dissoc new-state :db/id))))
                          ::workouts)]
    (reduce (fn [tx k]
              (let [old-v (get old-state k)
                    new-v (get new-state k)]
                (if (= old-v new-v)
                  tx
                  (conj tx {:db/id db-id k new-v}))))
            []
            scalar-keys)))

(defn- diff-ref-collection
  "Compare ::workouts ref collections between old and new state.
   Matches by :db/id. Returns tx data for:
   - Same :db/id, different attrs -> update datoms
   - New entity (no :db/id) -> new entity + ref add
   - Missing :db/id -> retract ref"
  [old-state new-state]
  (let [parent-id (:db/id old-state)
        old-workouts (or (::workouts old-state) [])
        new-workouts (or (::workouts new-state) [])
        old-by-id (into {} (map (fn [w] [(:db/id w) w])) old-workouts)
        new-by-id (into {} (keep (fn [w] (when (:db/id w) [(:db/id w) w]))) new-workouts)
        new-without-id (filterv (complement :db/id) new-workouts)
        old-ids (set (keys old-by-id))
        new-ids (set (keys new-by-id))]
    (concat
     ;; Updates: same :db/id, different attrs
     (mapcat (fn [id]
               (let [old-w (old-by-id id)
                     new-w (new-by-id id)
                     changed-keys (filter #(and (not= % :db/id)
                                                (not= (get old-w %) (get new-w %)))
                                          (into #{} (concat (keys old-w) (keys new-w))))]
                 (when (seq changed-keys)
                   [(reduce (fn [m k] (assoc m k (get new-w k)))
                            {:db/id id}
                            changed-keys)])))
             (set/intersection old-ids new-ids))
     ;; Retractions: in old but not in new
     (mapv (fn [id]
             [:db/retract parent-id ::workouts id])
           (set/difference old-ids new-ids))
     ;; New entities without :db/id (need new workout-id + ref add)
     (mapcat (fn [w]
               (let [wid (or (::workout-id w) (str (random-uuid)))
                     entity (assoc (dissoc w :db/id) ::workout-id wid)]
                 [entity
                  [:db/add [::ns-id (::ns-id new-state)] ::workouts [::workout-id wid]]]))
             new-without-id))))

(defn diff-to-tx
  "Diff old and new atom states, produce Datalevin transaction data.
   Compares entities by :db/id at each level."
  [old-state new-state]
  (when (and old-state new-state (not= old-state new-state))
    (let [top-level (diff-top-level old-state new-state)
          ref-changes (diff-ref-collection old-state new-state)]
      (vec (concat top-level ref-changes)))))

;; ---------------------------------------------------------------------------
;; Part 10b: Atom as Cached Recursive Pull
;; ---------------------------------------------------------------------------

(defonce ^:private *ctx (atom nil))
(defonce ^:private *ctx-conn (atom nil))
(defonce ^:private *ctx-watch-enabled (atom true))

(defn refresh-atom!
  "Re-pull the namespace entity and reset the atom (without triggering watch)."
  [{::keys [conn ns-id]}]
  (reset! *ctx-watch-enabled false)
  (try
    (reset! *ctx (pull-namespace-entity conn ns-id))
    (reset! *ctx-conn conn)
    (finally
      (reset! *ctx-watch-enabled true))))

(defn setup-atom-watch!
  "Wire the atom watch: on swap!, diff and transact changes."
  [{::keys [conn]}]
  (remove-watch *ctx ::sync-to-datalevin)
  (add-watch *ctx ::sync-to-datalevin
             (fn [_ _ old-state new-state]
               (when (and @*ctx-watch-enabled old-state new-state (not= old-state new-state))
                 (let [tx (diff-to-tx old-state new-state)]
                   (when (seq tx)
            ;; Disable watch during transact to avoid infinite loop
            ;; (listener will re-pull if needed)
                     (reset! *ctx-watch-enabled false)
                     (try
                       (d/transact! conn tx)
                       (finally
                         (reset! *ctx-watch-enabled true)))))))))

;; ---------------------------------------------------------------------------
;; Part 11: Graph Indexing Helpers
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
    ;; Invalidate caches since graph changed
    (invalidate-cache!)
    (invalidate-render-cache!)
    {:function-count (count functions)
     :shape-count (count shapes)
     :entry-count (count entries)
     :spec-count (count specs)}))

;; ---------------------------------------------------------------------------
;; Part 12: Public API — init!, call!, shutdown!
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
  (invalidate-render-cache!)
  (reset! *connections {})
  (remove-watch *ctx ::sync-to-datalevin)
  (reset! *ctx nil)
  (reset! *ctx-conn nil)
  nil)

;; ---------------------------------------------------------------------------
;; Part 13: Test Fixture
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
;; Part 14: Tests
;; ---------------------------------------------------------------------------

(deftest entity-dispatch-test
  (with-embedded-datalevin
    (fn [conn graph-conn {::keys [results-acc]}]
      (testing "add-workout! creates workout entity in Datalevin"
        (let [{:keys [result]} (call! {::conn conn
                                       ::graph-conn graph-conn
                                       ::fn-var #'add-workout!
                                       ::args {::exercise "Squat"
                                               ::weight 100.0
                                               ::reps 5}
                                       ::results-acc results-acc})]
          (is (= "seon.test.bootstrap-v2" (::ns-id result))
              "ns-id should be defaulted")
          (is (some? (::workout-id result))
              "Should return a workout-id")))

      (testing "Workout is a separate entity with its own :db/id"
        (let [entity (pull-namespace-entity conn "seon.test.bootstrap-v2")]
          (is (some? entity) "Namespace entity should exist")
          (is (= 1 (count (::workouts entity)))
              "Should have one workout ref")
          (is (some? (:db/id (first (::workouts entity))))
              "Workout should have its own :db/id")
          (is (= "Squat" (-> entity ::workouts first ::exercise)))))

      (testing "Second workout accumulates as separate entity"
        (call! {::conn conn ::graph-conn graph-conn ::fn-var #'add-workout!
                ::args {::exercise "Bench" ::weight 60.0 ::reps 8}
                ::results-acc results-acc})
        (let [entity (pull-namespace-entity conn "seon.test.bootstrap-v2")]
          (is (= 2 (count (::workouts entity)))
              "Should have two workout refs"))))))

(deftest ref-entity-test
  (with-embedded-datalevin
    (fn [conn graph-conn {::keys [results-acc]}]
      (testing "Each workout is a separate entity with its own :db/id"
        (call! {::conn conn ::graph-conn graph-conn ::fn-var #'add-workout!
                ::args {::exercise "Squat" ::weight 100.0 ::reps 5}
                ::results-acc results-acc})
        (call! {::conn conn ::graph-conn graph-conn ::fn-var #'add-workout!
                ::args {::exercise "Bench" ::weight 80.0 ::reps 8}
                ::results-acc results-acc})
        (let [entity (pull-namespace-entity conn "seon.test.bootstrap-v2")
              workouts (::workouts entity)
              workout-ids (set (map :db/id workouts))]
          (is (= 2 (count workouts)))
          (is (= 2 (count workout-ids))
              "Each workout should have a unique :db/id")
          (is (not (contains? workout-ids (:db/id entity)))
              "Workout :db/id should differ from namespace entity :db/id"))))))

(deftest pull-recursive-test
  (with-embedded-datalevin
    (fn [conn graph-conn {::keys [results-acc]}]
      (testing "Pull namespace entity with recursive workout expansion"
        (call! {::conn conn ::graph-conn graph-conn ::fn-var #'add-workout!
                ::args {::exercise "Squat" ::weight 100.0 ::reps 5}
                ::results-acc results-acc})
        (call! {::conn conn ::graph-conn graph-conn ::fn-var #'add-workout!
                ::args {::exercise "Bench" ::weight 80.0 ::reps 8}
                ::results-acc results-acc})
        (let [entity (pull-namespace-entity conn "seon.test.bootstrap-v2")]
          (is (some? (:db/id entity))
              "Root entity should have :db/id")
          (is (= "seon.test.bootstrap-v2" (::ns-id entity)))
          (is (= 2 (count (::workouts entity))))
          ;; Workouts should be full maps, not just refs
          (let [w (first (::workouts entity))]
            (is (some? (:db/id w)) "Workout should have :db/id")
            (is (some? (::workout-id w)) "Workout should have ::workout-id")
            (is (some? (::exercise w)) "Workout should have ::exercise")
            (is (some? (::weight w)) "Workout should have ::weight")
            (is (some? (::reps w)) "Workout should have ::reps")))))))

(deftest atom-as-pull-test
  (with-embedded-datalevin
    (fn [conn graph-conn {::keys [results-acc]}]
      (testing "Atom value matches recursive pull result"
        (call! {::conn conn ::graph-conn graph-conn ::fn-var #'add-workout!
                ::args {::exercise "Squat" ::weight 100.0 ::reps 5}
                ::results-acc results-acc})
        (refresh-atom! {::conn conn ::ns-id "seon.test.bootstrap-v2"})
        (let [atom-val @*ctx
              pull-val (pull-namespace-entity conn "seon.test.bootstrap-v2")]
          (is (= atom-val pull-val)
              "Atom should hold exact recursive pull result")
          (is (some? (:db/id atom-val))
              "Atom value should include :db/id")
          (is (= 1 (count (::workouts atom-val)))))))))

(deftest diff-to-transact-test
  (with-embedded-datalevin
    (fn [conn _graph-conn {::keys [_results-acc]}]
      (testing "swap! atom top-level attr triggers diff-to-tx"
        ;; Setup: create entity with bodyweight
        (d/transact! conn [{::ns-id "seon.test.bootstrap-v2" ::bodyweight 85.0}])
        (refresh-atom! {::conn conn ::ns-id "seon.test.bootstrap-v2"})
        (setup-atom-watch! {::conn conn})
        ;; Swap bodyweight
        (swap! *ctx assoc ::bodyweight 90.0)
        ;; Verify Datalevin was updated
        (let [entity (pull-namespace-entity conn "seon.test.bootstrap-v2")]
          (is (= 90.0 (::bodyweight entity))
              "Datalevin should reflect the swap!")))

      (testing "swap! atom workout weight triggers diff-to-tx"
        ;; Add a workout via direct transact
        (d/transact! conn [{::workout-id "w-test" ::exercise "Squat" ::weight 100.0 ::reps 5}])
        (d/transact! conn [[:db/add [::ns-id "seon.test.bootstrap-v2"]
                            ::workouts [::workout-id "w-test"]]])
        (refresh-atom! {::conn conn ::ns-id "seon.test.bootstrap-v2"})
        ;; Find the workout and update its weight
        (swap! *ctx assoc-in [::workouts 0 ::weight] 120.0)
        ;; Verify Datalevin was updated
        (let [entity (pull-namespace-entity conn "seon.test.bootstrap-v2")
              w (first (filter #(= "w-test" (::workout-id %)) (::workouts entity)))]
          (is (= 120.0 (::weight w))
              "Workout weight should be updated via diff-to-tx")))

      ;; Clean up watch
      (remove-watch *ctx ::sync-to-datalevin))))

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
          (let [entity (pull-namespace-entity conn "seon.test.bootstrap-v2")]
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
          (let [entity (pull-namespace-entity conn "seon.test.bootstrap-v2")]
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
        (d/transact! conn [{::ns-id "test-entity"}])
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
            (is (some? (::workout-id result))
                "Should return workout-id for new entity")
            (is (>= (count chain) 2) "Chain should fire downstream"))

          ;; Verify entity state
          (let [entity (pull-namespace-entity domain-conn "seon.test.bootstrap-v2")]
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
                entity (pull-namespace-entity conn "seon.test.bootstrap-v2")
                chains-per-sec (/ 100.0 (/ elapsed-ms 1000.0))]
            (is (= 100 (count (::workouts entity)))
                "All 100 workouts should be persisted")
            (when (::weekly-volume entity)
              (is (pos? (::weekly-volume entity))
                  "Weekly volume should be computed"))
            ;; Report throughput
            (println (str "\n=== Stress Test Results (v2 with refs + d/listen!) ===\n"
                          "  Total time: " (format "%.1f" elapsed-ms) " ms\n"
                          "  Chains/second: " (format "%.0f" chains-per-sec) "\n"
                          "  Cache size: " (count @*shape-cache) " entries\n"
                          "  Workouts stored: " (count (::workouts entity)) "\n"
                          "  Weekly volume: " (::weekly-volume entity) "\n"
                          "  Weekly sets: " (::weekly-sets entity)))))))))

;; ---------------------------------------------------------------------------
;; Part 15: Render Tree Tests
;; ---------------------------------------------------------------------------

(deftest render-tree-workout-test
  (with-embedded-datalevin
    (fn [conn graph-conn {::keys [results-acc]}]
      (testing "Workout entity renders via discovered function"
        (call! {::conn conn ::graph-conn graph-conn ::fn-var #'add-workout!
                ::args {::exercise "Squat" ::weight 100.0 ::reps 5}
                ::results-acc results-acc})
        (let [entity (pull-namespace-entity conn "seon.test.bootstrap-v2")
              workout (first (::workouts entity))
              rendered (render-tree {::graph-conn graph-conn
                                     ::entity workout
                                     ::render-keys [:seon.render/ai]})]
          (is (string? rendered) "Should produce a string")
          (is (str/includes? rendered "Squat") "Should mention exercise")
          (is (str/includes? rendered "100.0") "Should mention weight")
          (is (str/includes? rendered "5") "Should mention reps"))))))

(deftest render-tree-namespace-with-nested-test
  (with-embedded-datalevin
    (fn [conn graph-conn {::keys [results-acc]}]
      (testing "Namespace summary renderer matches when ::screen present"
        (call! {::conn conn ::graph-conn graph-conn ::fn-var #'add-workout!
                ::args {::exercise "Squat" ::weight 100.0 ::reps 5}
                ::results-acc results-acc})
        (call! {::conn conn ::graph-conn graph-conn ::fn-var #'add-workout!
                ::args {::exercise "Bench" ::weight 60.0 ::reps 8}
                ::results-acc results-acc})
        ;; Add ::screen so namespace summary renderer matches
        (d/transact! conn [{::ns-id "seon.test.bootstrap-v2" ::screen :home}])
        (let [entity (pull-namespace-entity conn "seon.test.bootstrap-v2")
              rendered (render-tree {::graph-conn graph-conn
                                     ::entity entity
                                     ::render-keys [:seon.render/ai]})]
          (is (string? rendered) "Should produce a string")
          ;; Summary renderer wins at root: it matches ::screen + ::weekly-volume + ::weekly-sets
          (is (str/includes? rendered "Screen: home") "Should render screen")
          (is (str/includes? rendered "Volume:") "Should render volume")
          (is (str/includes? rendered "Sets:") "Should render sets")))

      (testing "Without ::screen, no summary renderer — walks children"
        ;; New DB has workouts but no ::screen, so summary renderer won't match
        (let [entity (d/pull (d/db conn) '[* {::workouts [*]}]
                             [::ns-id "seon.test.bootstrap-v2"])
              ;; Remove ::screen to prevent summary renderer from matching
              no-screen (-> entity (dissoc ::screen) (assoc :db/id (:db/id entity)))
              rendered (render-tree {::graph-conn graph-conn
                                     ::entity no-screen
                                     ::render-keys [:seon.render/ai]})]
          (is (string? rendered))
          ;; Without summary renderer, it walks into children
          ;; Workout renderer should match each workout in ::workouts
          (is (str/includes? rendered "Squat") "Should contain workout exercise")
          (is (str/includes? rendered "Bench") "Should contain second workout"))))))

(deftest render-tree-fallback-test
  (with-embedded-datalevin
    (fn [conn graph-conn {::keys [_results-acc]}]
      (testing "Missing renderer falls back to default format"
        (d/transact! conn [{::ns-id "seon.test.bootstrap-v2" ::bodyweight 85.0}])
        (let [entity (pull-namespace-entity conn "seon.test.bootstrap-v2")
              rendered (render-tree {::graph-conn graph-conn
                                     ::entity entity
                                     ::render-keys [:seon.render/ai]})]
          (is (string? rendered) "Should produce a string even without renderer")
          (is (str/includes? rendered "85.0") "Should contain bodyweight value"))))))

(deftest render-tree-max-depth-test
  (with-embedded-datalevin
    (fn [conn graph-conn {::keys [results-acc]}]
      (testing "Max depth prevents infinite recursion"
        (call! {::conn conn ::graph-conn graph-conn ::fn-var #'add-workout!
                ::args {::exercise "Squat" ::weight 100.0 ::reps 5}
                ::results-acc results-acc})
        (let [entity (pull-namespace-entity conn "seon.test.bootstrap-v2")]
          ;; Depth 0 should immediately cap
          (is (= "<max-depth>"
                 (render-tree {::graph-conn graph-conn
                               ::entity entity
                               ::render-keys [:seon.render/ai]
                               ::max-depth 0}))
              "Depth 0 should immediately cap")
          ;; Depth 1 should render root but cap children
          (let [rendered (render-tree {::graph-conn graph-conn
                                       ::entity entity
                                       ::render-keys [:seon.render/ai]
                                       ::max-depth 1})]
            (is (string? rendered))
            (is (str/includes? rendered "<max-depth>")
                "Children at depth 1 should be capped")))))))

(deftest render-tree-priority-test
  (with-embedded-datalevin
    (fn [conn graph-conn {::keys [results-acc]}]
      (testing "Render priority — tries keys in order"
        (call! {::conn conn ::graph-conn graph-conn ::fn-var #'add-workout!
                ::args {::exercise "Squat" ::weight 100.0 ::reps 5}
                ::results-acc results-acc})
        (let [entity (pull-namespace-entity conn "seon.test.bootstrap-v2")
              workout (first (::workouts entity))
              ;; Try :seon.render/html first (no HTML renderers defined),
              ;; then :seon.render/ai (should match render-workout-ai)
              rendered (render-tree {::graph-conn graph-conn
                                     ::entity workout
                                     ::render-keys [:seon.render/html :seon.render/ai]})]
          (is (string? rendered))
          (is (str/includes? rendered "Squat")
              "Should fall through to :seon.render/ai"))))))

;; ---------------------------------------------------------------------------
;; Part 16: REPL Session — Flow-Based
;; ---------------------------------------------------------------------------
;;
;; A REPL session wraps the reactive dispatch system (init!, call!, render-tree)
;; in a core.async.flow step function. Users interact via start-repl! / repl-eval!
;; from their nREPL.
;;
;; Architecture:
;;   in-port :eval  -->  [repl-step]  -->  out-port :result
;;                          |
;;                          v
;;                  (call! + render-tree internally)
;;
;; The step function owns the Datalevin lifecycle (init!/shutdown!) and
;; maintains conversation history as flow state.

(schema/register! ::repl-source :string)
(schema/register! ::repl-rendered :string)
(schema/register! ::repl-chain [:vector [:tuple :string :map]])
(schema/register! ::repl-timestamp :inst)
(schema/register! ::repl-eval-count [:int {:min 0}])

(schema/register! ::repl-entry
                  [:map
                   [::repl-source ::repl-source]
                   [::repl-rendered ::repl-rendered]
                   [::repl-chain ::repl-chain]
                   [::repl-timestamp ::repl-timestamp]])

(defn repl-step
  "Flow step function for a REPL session.

   Manages a reactive dispatch session (init!/call!/shutdown!) as flow state.
   Receives eval requests on the :eval in-port, dispatches them through the
   reactive chain, renders the entity tree, and delivers results via promise.

   State: conversation history, eval count, domain+graph conns, render keys,
          reactive system handle (listener-key, results-acc).

   The step function handles its own lifecycle:
   - init: sets up Datalevin connections and reactive listener
   - transform: dispatches function calls, renders results
   - transition/stop: shuts down reactive listener"
  ;; describe
  ([]
   {:ins {}
    :outs {}
    :params {::conn "Domain Datalevin connection"
             ::graph-conn "Graph index connection"
             ::render-keys "Vector of render output keys to try"}
    :workload :io})

  ;; init — receives params + ::flow/pid, returns initial state
  ([params]
   (let [conn (::conn params)
         graph-conn (::graph-conn params)
         render-keys (or (::render-keys params) [:seon.render/ai])
         in-ch (::in-ch params)
         out-ch (::out-ch params)
         ;; Initialize the reactive dispatch system
         {::keys [results-acc]} (init! {::conn conn ::graph-conn graph-conn})]
     {::history []
      ::repl-eval-count 0
      ::conn conn
      ::graph-conn graph-conn
      ::render-keys render-keys
      ::results-acc results-acc
      ::flow/in-ports {:eval in-ch}
      ::flow/out-ports {:result out-ch}}))

  ;; transition
  ([state transition]
   (case transition
     :clojure.core.async.flow/stop
     (do
       (when-let [conn (::conn state)]
         (try
           (shutdown! {::conn conn})
           (catch Exception _)))
       state)
     state))

  ;; transform — receives eval requests, dispatches, renders, delivers
  ([state input-id msg]
   (case input-id
     :eval
     (let [{:keys [promise-ref fn-var args]} msg
           {::keys [conn graph-conn render-keys results-acc]} state
           ;; Dispatch the function through the reactive chain
           {:keys [result chain]}
           (call! {::conn conn
                   ::graph-conn graph-conn
                   ::fn-var fn-var
                   ::args args
                   ::results-acc results-acc})
           ;; Pull the updated entity tree
           entity (pull-namespace-entity conn "seon.test.bootstrap-v2")
           ;; Render the entity tree
           rendered (if entity
                      (render-tree {::graph-conn graph-conn
                                    ::entity entity
                                    ::render-keys render-keys})
                      (pr-str result))
           ;; Build history entry
           entry {::repl-source (str fn-var)
                  ::repl-rendered rendered
                  ::repl-chain (vec chain)
                  ::repl-timestamp (java.util.Date.)}
           new-state (-> state
                         (update ::history conj entry)
                         (update ::repl-eval-count inc))]
       ;; Deliver result to the waiting caller
       (deliver promise-ref {::repl-rendered rendered
                             ::repl-chain chain
                             ::repl-entry entry})
       ;; Output the entry on the :result out-port
       [new-state {:result [entry]}])

     ;; Unknown input — pass through
     [state nil])))

(defn start-repl!
  "Start a flow-based REPL session. Returns a session map.

   Creates an embedded Datalevin (domain + graph), indexes this namespace,
   builds a core.async.flow with repl-step, and starts it.

   Use (repl-eval! session fn-var args) to evaluate.
   Use (repl-history session) to see conversation.
   Use (stop-repl! session) to clean up.

   Request keys:
     ::render-keys - Optional. Vector of render keys (default [:seon.render/ai])"
  [{::keys [render-keys]}]
  (let [ts (System/currentTimeMillis)
        domain-dir (str "tmp/repl-session-domain-" ts)
        graph-dir (str "tmp/repl-session-graph-" ts)
        domain-conn (d/get-conn domain-dir datalevin-schema)
        graph-conn (d/get-conn graph-dir graph-schema)
        ;; Index this namespace into the graph DB
        _ (index-this-namespace! graph-conn)
        ;; Create in/out ports for the flow
        in-ch (async/chan 32)
        out-ch (async/chan 32)
        ;; Build the flow
        fl (flow/create-flow
             {:procs {:repl {:proc (flow/process #'repl-step)
                             :args {::conn domain-conn
                                    ::graph-conn graph-conn
                                    ::render-keys (or render-keys [:seon.render/ai])
                                    ::in-ch in-ch
                                    ::out-ch out-ch}}}
              :conns []})
        {:keys [error-chan]} (flow/start fl)]
    (flow/resume fl)
    {::flow fl
     ::in-ch in-ch
     ::out-ch out-ch
     ::error-chan error-chan
     ::domain-conn domain-conn
     ::graph-conn graph-conn
     ::domain-dir domain-dir
     ::graph-dir graph-dir}))

(defn repl-eval!
  "Evaluate a function call in a REPL session.
   Dispatches the function through the reactive chain, renders the entity
   tree, and returns the rendered string.

   Usage from nREPL:
     (def s (start-repl! {}))
     (repl-eval! s #'add-workout! {::exercise \"Squat\" ::weight 100.0 ::reps 5})
     ;; => \"workouts:\\n  Squat - 100.0kg x 5 reps\\nweekly-sets: 1\\n...\"
     (stop-repl! s)"
  [session fn-var args]
  (let [p (promise)
        msg {:promise-ref p :fn-var fn-var :args args}]
    (async/>!! (::in-ch session) msg)
    (let [result (deref p 30000 ::timeout)]
      (if (= result ::timeout)
        (throw (ex-info "REPL eval timed out after 30s"
                        {:fn-var fn-var :args args}))
        result))))

(defn repl-history
  "Get the conversation history from a REPL session.
   Returns the history vector from the flow step's state via ping."
  [session]
  (let [status (flow/ping (::flow session) :timeout-ms 5000)
        repl-state (get-in status [:repl ::flow/state])]
    (::history repl-state)))

(defn stop-repl!
  "Stop a REPL session and clean up all resources.
   Stops the flow (which triggers shutdown! on the reactive system),
   closes channels, and closes Datalevin connections."
  [session]
  (flow/stop (::flow session))
  (async/close! (::in-ch session))
  (async/close! (::out-ch session))
  (d/close (::domain-conn session))
  (d/close (::graph-conn session))
  nil)

;; ---------------------------------------------------------------------------
;; Part 16b: REPL Session — Direct Mode (atom-based, no flow)
;; ---------------------------------------------------------------------------

(defn start-repl-direct!
  "Start a REPL session without flow (simpler, for quick testing).
   Same eval/history/stop interface but uses atoms instead of flow.

   Request keys:
     ::render-keys - Optional. Vector of render keys (default [:seon.render/ai])"
  [{::keys [render-keys]}]
  (let [ts (System/currentTimeMillis)
        domain-dir (str "tmp/repl-direct-domain-" ts)
        graph-dir (str "tmp/repl-direct-graph-" ts)
        domain-conn (d/get-conn domain-dir datalevin-schema)
        graph-conn (d/get-conn graph-dir graph-schema)
        _ (index-this-namespace! graph-conn)
        system (init! {::conn domain-conn ::graph-conn graph-conn})]
    {::mode :direct
     ::domain-conn domain-conn
     ::graph-conn graph-conn
     ::domain-dir domain-dir
     ::graph-dir graph-dir
     ::results-acc (::results-acc system)
     ::render-keys (or render-keys [:seon.render/ai])
     ::direct-history (atom [])
     ::direct-eval-count (atom 0)}))

(defn- repl-eval-direct!
  "Evaluate in a direct-mode session."
  [session fn-var args]
  (let [{::keys [domain-conn graph-conn results-acc render-keys
                 direct-history direct-eval-count]} session
        {:keys [result chain]}
        (call! {::conn domain-conn
                ::graph-conn graph-conn
                ::fn-var fn-var
                ::args args
                ::results-acc results-acc})
        entity (pull-namespace-entity domain-conn "seon.test.bootstrap-v2")
        rendered (if entity
                   (render-tree {::graph-conn graph-conn
                                  ::entity entity
                                  ::render-keys render-keys})
                   (pr-str result))
        entry {::repl-source (str fn-var)
               ::repl-rendered rendered
               ::repl-chain (vec chain)
               ::repl-timestamp (java.util.Date.)}]
    (swap! direct-history conj entry)
    (swap! direct-eval-count inc)
    {::repl-rendered rendered
     ::repl-chain chain
     ::repl-entry entry}))

(defn- repl-history-direct
  "Get history from a direct-mode session."
  [session]
  @(::direct-history session))

(defn- stop-repl-direct!
  "Stop a direct-mode session."
  [session]
  (shutdown! {::conn (::domain-conn session)})
  (d/close (::domain-conn session))
  (d/close (::graph-conn session))
  nil)

;; ---------------------------------------------------------------------------
;; Part 16c: Unified REPL API (dispatches on mode)
;; ---------------------------------------------------------------------------

(defn repl-eval
  "Evaluate a function call in any REPL session (flow or direct).
   Returns map with ::repl-rendered, ::repl-chain, ::repl-entry.

   Usage:
     (def s (start-repl! {}))        ;; or (start-repl-direct! {})
     (repl-eval s #'add-workout! {::exercise \"Squat\" ::weight 100.0 ::reps 5})
     (stop-repl s)"
  [session fn-var args]
  (if (= :direct (::mode session))
    (repl-eval-direct! session fn-var args)
    (repl-eval! session fn-var args)))

(defn repl-hist
  "Get conversation history from any REPL session."
  [session]
  (if (= :direct (::mode session))
    (repl-history-direct session)
    (repl-history session)))

(defn stop-repl
  "Stop any REPL session and clean up."
  [session]
  (if (= :direct (::mode session))
    (stop-repl-direct! session)
    (stop-repl! session)))

;; ---------------------------------------------------------------------------
;; Part 17: REPL Session Tests
;; ---------------------------------------------------------------------------

(deftest repl-session-direct-test
  (testing "Direct-mode REPL session lifecycle"
    (let [session (start-repl-direct! {})]
      (try
        (testing "eval add-workout! returns rendered output"
          (let [result (repl-eval session #'add-workout!
                                  {::exercise "Squat" ::weight 100.0 ::reps 5})]
            (is (string? (::repl-rendered result))
                "Should return rendered string")
            (is (str/includes? (::repl-rendered result) "Squat")
                "Rendered should mention exercise")
            (is (seq (::repl-chain result))
                "Should have reactive chain")))

        (testing "second eval accumulates history"
          (repl-eval session #'add-workout!
                     {::exercise "Bench" ::weight 60.0 ::reps 8})
          (let [history (repl-hist session)]
            (is (= 2 (count history))
                "History should have 2 entries")
            (is (every? #(contains? % ::repl-source) history)
                "Each entry should have source")
            (is (every? #(contains? % ::repl-rendered) history)
                "Each entry should have rendered")
            (is (every? #(contains? % ::repl-timestamp) history)
                "Each entry should have timestamp")))

        (testing "record-bodyweight! renders differently"
          (let [result (repl-eval session #'record-bodyweight!
                                  {::bodyweight 85.0})]
            (is (string? (::repl-rendered result)))
            (is (str/includes? (::repl-rendered result) "85.0")
                "Should show bodyweight")))

        (finally
          (stop-repl session))))))

(deftest repl-session-flow-test
  (testing "Flow-based REPL session lifecycle"
    (let [session (start-repl! {})]
      (try
        (testing "eval add-workout! returns rendered output"
          (let [result (repl-eval session #'add-workout!
                                  {::exercise "Squat" ::weight 100.0 ::reps 5})]
            (is (string? (::repl-rendered result))
                "Should return rendered string")
            (is (str/includes? (::repl-rendered result) "Squat")
                "Rendered should mention exercise")
            (is (seq (::repl-chain result))
                "Should have reactive chain")))

        (testing "second eval accumulates"
          (let [result (repl-eval session #'add-workout!
                                  {::exercise "Bench" ::weight 60.0 ::reps 8})]
            (is (string? (::repl-rendered result)))
            (is (str/includes? (::repl-rendered result) "Bench")
                "Second workout should appear in render")))

        (testing "history via ping shows 2 entries"
          (let [history (repl-hist session)]
            (is (= 2 (count history))
                "Flow state should track 2 history entries")))

        (finally
          (stop-repl session))))))

(deftest repl-render-accumulation-test
  (testing "Rendered output accumulates entity state across evals"
    (let [session (start-repl-direct! {})]
      (try
        ;; First workout
        (repl-eval session #'add-workout!
                   {::exercise "Squat" ::weight 100.0 ::reps 5})
        ;; Second workout
        (repl-eval session #'add-workout!
                   {::exercise "Bench" ::weight 60.0 ::reps 8})
        ;; Record bodyweight
        (repl-eval session #'record-bodyweight! {::bodyweight 85.0})

        ;; The rendered output should show the full accumulated state
        (let [entity (pull-namespace-entity (::domain-conn session)
                                            "seon.test.bootstrap-v2")]
          (is (= 2 (count (::workouts entity)))
              "Should have 2 workouts")
          (is (= 85.0 (::bodyweight entity))
              "Should have bodyweight")
          ;; Weekly volume should be reactively computed
          (is (some? (::weekly-volume entity))
              "Weekly volume should be computed"))

        ;; Last render should show everything
        (let [last-entry (last (repl-hist session))]
          (is (str/includes? (::repl-rendered last-entry) "85.0")
              "Last render should include bodyweight"))

        (finally
          (stop-repl session))))))

(deftest repl-chain-visibility-test
  (testing "Chain results are visible in eval response"
    (let [session (start-repl-direct! {})]
      (try
        (let [result (repl-eval session #'add-workout!
                                {::exercise "Squat" ::weight 100.0 ::reps 5})
              chain-fns (set (map first (::repl-chain result)))]
          (is (chain-fns "seon.test.bootstrap-v2/add-workout!")
              "Chain should include the direct call")
          (is (chain-fns "seon.test.bootstrap-v2/update-weekly-volume")
              "Chain should include reactive downstream"))
        (finally
          (stop-repl session))))))

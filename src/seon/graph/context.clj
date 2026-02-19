(ns seon.graph.context
  "Topological context builder for AI agents.

   Builds linearized context strings from the Datalevin knowledge graph
   using recursive pull and topological sort. When an AI agent needs context
   about a function or namespace, this module:

   1. Starts from a seed entity (function or namespace)
   2. Recursively pulls related entities via refs (call graph, specs, dependencies)
   3. Topologically sorts the subgraph (dependencies before dependents)
   4. Renders a compact, linearized text suitable for AI agent context injection

   Example:
     (require '[seon.graph.context :as ctx])

     (ctx/build {::ctx/conn conn
                 ::ctx/seed \"seon.health.workout/log-workout!\"
                 ::ctx/depth 2})
     ;; => {::ctx/context-text \"## seon.health.workout/log-workout!\\n...\"
     ;;     ::ctx/entity-count 5}

     (ctx/build-for-namespace {::ctx/conn conn
                               ::ctx/namespace \"seon.graph.query\"})
     ;; => {::ctx/context-text \"## Namespace: seon.graph.query\\n...\"
     ;;     ::ctx/entity-count 12}"
  (:require [datalevin.core :as d]
            [clojure.string :as str]
            [seon.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

(schema/register! ::conn
                  [:any {:description "Datalevin connection"}])

(schema/register! ::seed
                  [:string {:min 1
                            :description "Qualified function name (ns/fn) or namespace name"}])

(schema/register! ::depth
                  [:int {:min 1 :max 10
                         :description "How many hops to follow in the call graph (default 2)"}])

(schema/register! ::max-entities
                  [:int {:min 1
                         :description "Cap on entities to prevent huge context (default 50)"}])

(schema/register! ::namespace
                  [:string {:min 1 :description "Namespace name for build-for-namespace"}])

(schema/register! ::context-text
                  [:string {:description "Rendered context text"}])

(schema/register! ::entity-count
                  [:int {:min 0 :description "Number of entities in context"}])

;;; ---------------------------------------------------------------------------
;;; Pull Subgraph
;;; ---------------------------------------------------------------------------

(defn- fn-entity
  "Pull a function entity by qualified name. Returns nil if not found."
  [db qn]
  (let [results (d/q '[:find ?e
                        :in $ ?qn
                        :where [?e :seon.fn/qualified-name ?qn]]
                      db qn)]
    (when (seq results)
      (d/pull db '[*] (ffirst results)))))

(defn- calls-of
  "Get qualified names of functions called by the given function."
  [db from-qn]
  (->> (d/q '[:find ?to-qn
              :in $ ?from-qn
              :where
              [?from :seon.fn/qualified-name ?from-qn]
              [?call :seon.call/from-fn ?from]
              [?call :seon.call/to-fn ?to]
              [?to :seon.fn/qualified-name ?to-qn]]
            db from-qn)
       (map first)
       set))

(defn- callers-of-fn
  "Get qualified names of functions that call the given function."
  [db to-qn]
  (->> (d/q '[:find ?from-qn
              :in $ ?to-qn
              :where
              [?to :seon.fn/qualified-name ?to-qn]
              [?call :seon.call/to-fn ?to]
              [?call :seon.call/from-fn ?from]
              [?from :seon.fn/qualified-name ?from-qn]]
            db to-qn)
       (map first)
       set))

(defn- ns-dependencies
  "Get namespace names that the given namespace depends on."
  [db ns-name]
  (->> (d/q '[:find ?to-ns
              :in $ ?from-ns
              :where
              [?e :seon.ns.dep/from-ns ?from-ns]
              [?e :seon.ns.dep/to-ns ?to-ns]]
            db ns-name)
       (map first)
       set))

(defn- ns-entity
  "Pull a namespace entity by name. Returns nil if not found."
  [db ns-name]
  (let [results (d/q '[:find ?e
                        :in $ ?n
                        :where [?e :seon.ns/name ?n]]
                      db ns-name)]
    (when (seq results)
      (d/pull db '[*] (ffirst results)))))

(defn- spec-for-ref
  "Pull a spec entity from a ref value (entity id)."
  [db ref-val]
  (when ref-val
    (let [eid (if (map? ref-val) (:db/id ref-val) ref-val)]
      (when eid
        (d/pull db '[*] eid)))))

(defn pull-subgraph
  "Given a seed qualified-name and depth, recursively pull related entities.

   Collects:
   - The seed function entity and its spec refs
   - Functions it calls (depth 1) and their specs
   - Functions that call it (depth 1)
   - At depth 2+, recurse on called functions
   - Namespace entities for all found functions

   Returns a set of entity maps, each tagged with :context/type (:fn, :ns, :spec).

   Request keys:
     ::conn          - Required. Datalevin connection
     ::seed          - Required. Qualified function name (e.g. \"seon.graph.query/call-graph\")
     ::depth         - Optional. Hops to follow (default 2)
     ::max-entities  - Optional. Cap (default 50)"
  [{::keys [conn seed depth max-entities]}]
  (let [depth (or depth 2)
        max-entities (or max-entities 50)
        db @conn
        visited (atom #{})
        entities (atom [])
        add! (fn [ent type]
               (when (and ent (< (count @entities) max-entities))
                 (let [key (case type
                             :fn (:seon.fn/qualified-name ent)
                             :ns (:seon.ns/name ent)
                             :spec (str (:seon.spec/key ent))
                             nil)]
                   (when (and key (not (contains? @visited key)))
                     (swap! visited conj key)
                     (swap! entities conj (assoc ent :context/type type))))))]
    ;; Recursive walk
    (letfn [(walk-fn [qn remaining-depth]
              (when (and (pos? remaining-depth)
                         (not (contains? @visited qn))
                         (< (count @entities) max-entities))
                (when-let [ent (fn-entity db qn)]
                  (add! ent :fn)
                  ;; Add input/output specs if present
                  (when-let [in-spec (spec-for-ref db (:seon.fn/input-spec ent))]
                    (add! in-spec :spec))
                  (when-let [out-spec (spec-for-ref db (:seon.fn/output-spec ent))]
                    (add! out-spec :spec))
                  ;; Add namespace entity
                  (when-let [ns-ent (ns-entity db (:seon.fn/namespace ent))]
                    (add! ns-ent :ns))
                  ;; Walk callees
                  (let [callees (calls-of db qn)]
                    (doseq [callee callees]
                      (when (< (count @entities) max-entities)
                        (walk-fn callee (dec remaining-depth)))))
                  ;; Walk callers (only at depth 1 from seed, don't recurse into callers' callers)
                  (when (= remaining-depth depth)
                    (let [callers (callers-of-fn db qn)]
                      (doseq [caller callers]
                        (when (and (not (contains? @visited caller))
                                   (< (count @entities) max-entities))
                          (when-let [caller-ent (fn-entity db caller)]
                            (add! caller-ent :fn)))))))))]
      (walk-fn seed depth))
    @entities))

;;; ---------------------------------------------------------------------------
;;; Topological Sort
;;; ---------------------------------------------------------------------------

(defn toposort
  "Topological sort of entities. Order: specs first, then leaf functions
   (no outgoing calls), then callers. Uses Kahn's algorithm on the call graph.

   Entities is a seq of maps with :context/type and function qualified names.
   Returns entities in dependency order."
  [entities db]
  (let [fns (filter #(= :fn (:context/type %)) entities)
        specs (filter #(= :spec (:context/type %)) entities)
        nses (filter #(= :ns (:context/type %)) entities)
        fn-qns (set (map :seon.fn/qualified-name fns))
        fn-by-qn (into {} (map (juxt :seon.fn/qualified-name identity) fns))
        ;; Build adjacency: from -> [to] (only within subgraph)
        adj (into {}
                  (for [f fns
                        :let [qn (:seon.fn/qualified-name f)
                              callees (calls-of db qn)
                              in-graph (filter fn-qns callees)]]
                    [qn (set in-graph)]))
        ;; In-degree
        in-degree (atom (into {} (map (fn [qn] [qn 0]) fn-qns)))
        _ (doseq [[_ targets] adj
                  t targets]
            (swap! in-degree update t (fnil inc 0)))
        ;; Kahn's
        queue (atom (into (clojure.lang.PersistentQueue/EMPTY)
                          (filter #(zero? (get @in-degree % 0)) fn-qns)))
        sorted (atom [])]
    (while (seq @queue)
      (let [n (peek @queue)]
        (swap! queue pop)
        (swap! sorted conj n)
        (doseq [m (get adj n)]
          (swap! in-degree update m dec)
          (when (zero? (get @in-degree m))
            (swap! queue conj m)))))
    ;; If cycle exists, append remaining unsorted
    (let [sorted-set (set @sorted)
          remaining (remove sorted-set fn-qns)]
      (concat specs
              nses
              (map fn-by-qn (concat @sorted remaining))))))

;;; ---------------------------------------------------------------------------
;;; Render
;;; ---------------------------------------------------------------------------

(defn- render-fn-entity
  "Render a function entity to a compact text block."
  [ent db]
  (let [qn (:seon.fn/qualified-name ent)
        args (:seon.fn/arglists ent)
        doc (:seon.fn/doc ent)
        callees (sort (calls-of db qn))
        callers (sort (callers-of-fn db qn))
        in-spec (spec-for-ref db (:seon.fn/input-spec ent))
        out-spec (spec-for-ref db (:seon.fn/output-spec ent))
        private? (:seon.fn/private ent)]
    (str/join "\n"
              (cond-> [(str "### " (when private? "(private) ") qn
                            (when args (str " " args)))]
                doc (conj (str "  " doc))
                in-spec (conj (str "  Input: " (:seon.spec/definition in-spec)))
                out-spec (conj (str "  Output: " (:seon.spec/definition out-spec)))
                (seq callees) (conj (str "  Calls: " (str/join ", " callees)))
                (seq callers) (conj (str "  Called by: " (str/join ", " callers)))))))

(defn- render-ns-entity
  "Render a namespace entity to a compact text block."
  [ent db]
  (let [ns-name (:seon.ns/name ent)
        deps (sort (ns-dependencies db ns-name))
        doc (:seon.ns/doc ent)]
    (str/join "\n"
              (cond-> [(str "### Namespace: " ns-name)]
                doc (conj (str "  " doc))
                (seq deps) (conj (str "  Requires: " (str/join ", " deps)))))))

(defn- render-spec-entity
  "Render a spec entity to a compact text block."
  [ent]
  (let [k (:seon.spec/key ent)
        defn (:seon.spec/definition ent)
        base (:seon.spec/base-type ent)]
    (str/join "\n"
              (cond-> [(str "### Spec: " k)]
                base (conj (str "  Type: " (name base)))
                defn (conj (str "  Definition: " defn))))))

(defn render-entity
  "Render a single entity to a compact text block.

   Dispatches on :context/type - :fn, :ns, or :spec."
  [ent db]
  (case (:context/type ent)
    :fn (render-fn-entity ent db)
    :ns (render-ns-entity ent db)
    :spec (render-spec-entity ent)
    (str "### Unknown: " (pr-str (select-keys ent [:context/type])))))

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(defn build
  "Build linearized AI context from a seed function or namespace.

   Pulls a subgraph around the seed entity, topologically sorts it,
   and renders each entity to a compact text block.

   Request keys:
     ::conn          - Required. Datalevin connection
     ::seed          - Required. Qualified function name or namespace name
     ::depth         - Optional. Hops to follow (default 2)
     ::max-entities  - Optional. Entity cap (default 50)

   Returns:
     {::context-text \"...\" ::entity-count N}

   Example:
     (build {::conn conn ::seed \"seon.graph.query/call-graph\" ::depth 2})"
  [{::keys [conn seed depth max-entities] :as opts}]
  (let [db @conn
        entities (pull-subgraph opts)
        sorted (toposort entities db)
        text (->> sorted
                  (map #(render-entity % db))
                  (str/join "\n\n"))]
    {::context-text text
     ::entity-count (count sorted)}))

(defn build-for-namespace
  "Build context for all functions in a namespace and their immediate dependencies.

   Convenience function that finds all functions in the namespace and builds
   context with depth 1 for each.

   Request keys:
     ::conn          - Required. Datalevin connection
     ::namespace     - Required. Namespace name (string)
     ::max-entities  - Optional. Entity cap (default 50)

   Returns:
     {::context-text \"...\" ::entity-count N}

   Example:
     (build-for-namespace {::conn conn ::namespace \"seon.graph.query\"})"
  [{::keys [conn namespace max-entities]}]
  (let [db @conn
        max-entities (or max-entities 50)
        ;; Find all functions in the namespace
        fn-qns (->> (d/q '[:find ?qn
                            :in $ ?ns
                            :where
                            [?e :seon.fn/namespace ?ns]
                            [?e :seon.fn/qualified-name ?qn]]
                          db namespace)
                     (map first)
                     sort)
        ;; Pull entities for each function (depth 1) collecting into a single set
        all-entities (atom [])
        seen (atom #{})
        _ (doseq [qn fn-qns
                  :when (< (count @all-entities) max-entities)]
            (let [ents (pull-subgraph {::conn conn ::seed qn
                                       ::depth 1
                                       ::max-entities (- max-entities (count @all-entities))})]
              (doseq [e ents
                      :let [key (case (:context/type e)
                                  :fn (:seon.fn/qualified-name e)
                                  :ns (:seon.ns/name e)
                                  :spec (str (:seon.spec/key e))
                                  nil)]
                      :when (and key (not (contains? @seen key)))]
                (swap! seen conj key)
                (swap! all-entities conj e))))
        sorted (toposort @all-entities db)
        ;; Add namespace header
        ns-ent (ns-entity db namespace)
        header (str "## Namespace: " namespace
                    (when-let [doc (:seon.ns/doc ns-ent)]
                      (str "\n" doc)))
        body (->> sorted
                  (map #(render-entity % db))
                  (str/join "\n\n"))]
    {::context-text (str header "\n\n" body)
     ::entity-count (count sorted)}))

(ns seon.graph.context
  "Topological context builder for AI agents.

   Builds linearized context strings from the Datahike knowledge graph
   using recursive pull and topological sort. When an AI agent needs context
   about a function or namespace, this module:

   1. Starts from a seed entity (function or namespace)
   2. Recursively pulls related entities via refs (call graph, specs, dependencies)
   3. Topologically sorts the subgraph (dependencies before dependents)
   4. Renders a compact, linearized text suitable for AI agent context injection

   Example:
     (require '[seon.graph.context :as ctx])

     (ctx/build {::ctx/db-name :seon.runtime
                 ::ctx/seed \"seon.db.schema/register-entity-schema!\"
                 ::ctx/depth 2})
     ;; => {::ctx/context-text \"## seon.db.schema/register-entity-schema!\\n...\"
     ;;     ::ctx/entity-count 5}

     (ctx/build-for-namespace {::ctx/db-name :seon.runtime
                               ::ctx/namespace \"seon.graph.query\"})
     ;; => {::ctx/context-text \"## Namespace: seon.graph.query\\n...\"
     ;;     ::ctx/entity-count 12}"
  (:require [clojure.string :as str]
            [seon.db :as db]
            [seon.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

(schema/register! ::db-name
                  [:keyword {:description "Database name keyword, e.g. :seon.runtime"}])

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
;;; Pull Subgraph (private helpers take db-name, use db/query and db/pull-by-name)
;;; ---------------------------------------------------------------------------

(defn- fn-entity
  "Pull a function entity by qualified name. Returns nil if not found."
  [db-name qn]
  (let [results (db/query db-name
                          '[:find ?e
                            :in $ ?qn
                            :where [?e :seon.fn/qualified-name ?qn]]
                          qn)]
    (when (seq results)
      (db/pull-by-name db-name '[*] (ffirst results)))))

(defn- calls-of
  "Get qualified names of functions called by the given function."
  [db-name from-qn]
  (->> (db/query db-name
                 '[:find ?to-qn
                   :in $ ?from-qn
                   :where
                   [?from :seon.fn/qualified-name ?from-qn]
                   [?call :seon.call/from-fn ?from]
                   [?call :seon.call/to-fn ?to]
                   [?to :seon.fn/qualified-name ?to-qn]]
                 from-qn)
       (map first)
       set))

(defn- callers-of-fn
  "Get qualified names of functions that call the given function."
  [db-name to-qn]
  (->> (db/query db-name
                 '[:find ?from-qn
                   :in $ ?to-qn
                   :where
                   [?to :seon.fn/qualified-name ?to-qn]
                   [?call :seon.call/to-fn ?to]
                   [?call :seon.call/from-fn ?from]
                   [?from :seon.fn/qualified-name ?from-qn]]
                 to-qn)
       (map first)
       set))

(defn- ns-dependencies
  "Get namespace names that the given namespace depends on."
  [db-name ns-name]
  (->> (db/query db-name
                 '[:find ?to-ns
                   :in $ ?from-ns
                   :where
                   [?e :seon.ns.dep/from-ns ?from-ns]
                   [?e :seon.ns.dep/to-ns ?to-ns]]
                 ns-name)
       (map first)
       set))

(defn- ns-entity
  "Pull a namespace entity by name. Returns nil if not found."
  [db-name ns-name]
  (let [results (db/query db-name
                          '[:find ?e
                            :in $ ?n
                            :where [?e :seon.ns/name ?n]]
                          ns-name)]
    (when (seq results)
      (db/pull-by-name db-name '[*] (ffirst results)))))

(defn- spec-for-ref
  "Pull a spec entity from a ref value (entity id)."
  [db-name ref-val]
  (when ref-val
    (let [eid (if (map? ref-val) (:db/id ref-val) ref-val)]
      (when eid
        (db/pull-by-name db-name '[*] eid)))))

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
     ::db-name       - Optional. Database name keyword (default :seon.runtime)
     ::seed          - Required. Qualified function name (e.g. \"seon.graph.query/call-graph\")
     ::depth         - Optional. Hops to follow (default 2)
     ::max-entities  - Optional. Cap (default 50)"
  [{::keys [db-name seed depth max-entities]}]
  (let [depth (or depth 2)
        max-entities (or max-entities 50)
        db-name (or db-name :seon.runtime)
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
                (when-let [ent (fn-entity db-name qn)]
                  (add! ent :fn)
                  ;; Add input/output specs if present
                  (when-let [in-spec (spec-for-ref db-name (:seon.fn/input-spec ent))]
                    (add! in-spec :spec))
                  (when-let [out-spec (spec-for-ref db-name (:seon.fn/output-spec ent))]
                    (add! out-spec :spec))
                  ;; Add namespace entity
                  (when-let [ns-ent (ns-entity db-name (:seon.fn/namespace ent))]
                    (add! ns-ent :ns))
                  ;; Walk callees
                  (let [callees (calls-of db-name qn)]
                    (doseq [callee callees]
                      (when (< (count @entities) max-entities)
                        (walk-fn callee (dec remaining-depth)))))
                  ;; Walk callers (only at depth 1 from seed, don't recurse into callers' callers)
                  (when (= remaining-depth depth)
                    (let [callers (callers-of-fn db-name qn)]
                      (doseq [caller callers]
                        (when (and (not (contains? @visited caller))
                                   (< (count @entities) max-entities))
                          (when-let [caller-ent (fn-entity db-name caller)]
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
   db-name is a keyword for database resolution.
   Returns entities in dependency order."
  [entities db-name]
  (let [fns (filter #(= :fn (:context/type %)) entities)
        specs (filter #(= :spec (:context/type %)) entities)
        nses (filter #(= :ns (:context/type %)) entities)
        fn-qns (set (map :seon.fn/qualified-name fns))
        fn-by-qn (into {} (map (juxt :seon.fn/qualified-name identity) fns))
        ;; Build adjacency: from -> [to] (only within subgraph)
        adj (into {}
                  (for [f fns
                        :let [qn (:seon.fn/qualified-name f)
                              callees (calls-of db-name qn)
                              in-graph (filter fn-qns callees)]]
                    [qn (set in-graph)]))
        ;; In-degree
        in-degree (atom (into {} (map (fn [qn] [qn 0]) fn-qns)))
        _ (doseq [[_ targets] adj
                  t targets]
            (swap! in-degree update t (fnil inc 0)))
        ;; Kahn's
        queue (atom (into clojure.lang.PersistentQueue/EMPTY
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
  [ent db-name]
  (let [qn (:seon.fn/qualified-name ent)
        args (:seon.fn/arglists ent)
        doc (:seon.fn/doc ent)
        callees (sort (calls-of db-name qn))
        callers (sort (callers-of-fn db-name qn))
        in-spec (spec-for-ref db-name (:seon.fn/input-spec ent))
        out-spec (spec-for-ref db-name (:seon.fn/output-spec ent))
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
  [ent db-name]
  (let [ns-name (:seon.ns/name ent)
        deps (sort (ns-dependencies db-name ns-name))
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
  [ent db-name]
  (case (:context/type ent)
    :fn (render-fn-entity ent db-name)
    :ns (render-ns-entity ent db-name)
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
     ::db-name       - Optional. Database name keyword (default :seon.runtime)
     ::seed          - Required. Qualified function name or namespace name
     ::depth         - Optional. Hops to follow (default 2)
     ::max-entities  - Optional. Entity cap (default 50)

   Returns:
     {::context-text \"...\" ::entity-count N}

   Example:
     (build {::db-name :seon.runtime ::seed \"seon.graph.query/call-graph\" ::depth 2})"
  [{::keys [db-name] :as opts}]
  (let [db-name (or db-name :seon.runtime)
        opts (assoc opts ::db-name db-name)
        entities (pull-subgraph opts)
        sorted (toposort entities db-name)
        text (->> sorted
                  (map #(render-entity % db-name))
                  (str/join "\n\n"))]
    {::context-text text
     ::entity-count (count sorted)}))

(defn build-for-namespace
  "Build context for all functions in a namespace and their immediate dependencies.

   Convenience function that finds all functions in the namespace and builds
   context with depth 1 for each.

   Request keys:
     ::db-name       - Optional. Database name keyword (default :seon.runtime)
     ::namespace     - Required. Namespace name (string)
     ::max-entities  - Optional. Entity cap (default 50)

   Returns:
     {::context-text \"...\" ::entity-count N}

   Example:
     (build-for-namespace {::db-name :seon.runtime ::namespace \"seon.graph.query\"})"
  [{::keys [db-name namespace max-entities]}]
  (let [db-name (or db-name :seon.runtime)
        max-entities (or max-entities 50)
        ;; Find all functions in the namespace
        fn-qns (->> (db/query db-name
                              '[:find ?qn
                                :in $ ?ns
                                :where
                                [?e :seon.fn/namespace ?ns]
                                [?e :seon.fn/qualified-name ?qn]]
                              namespace)
                     (map first)
                     sort)
        ;; Pull entities for each function (depth 1) collecting into a single set
        all-entities (atom [])
        seen (atom #{})
        _ (doseq [qn fn-qns
                  :when (< (count @all-entities) max-entities)]
            (let [ents (pull-subgraph {::db-name db-name ::seed qn
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
        sorted (toposort @all-entities db-name)
        ;; Add namespace header
        ns-ent (ns-entity db-name namespace)
        header (str "## Namespace: " namespace
                    (when-let [doc (:seon.ns/doc ns-ent)]
                      (str "\n" doc)))
        body (->> sorted
                  (map #(render-entity % db-name))
                  (str/join "\n\n"))]
    {::context-text (str header "\n\n" body)
     ::entity-count (count sorted)}))

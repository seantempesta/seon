(ns seon.graph.query
  "Datalog query API for the knowledge graph.

   Provides high-level query functions over the Datalevin graph populated
   by seon.graph.ingest. All queries operate on a named database resolved
   via seon.db.

   Query categories:
   - Dependency queries: who depends on what, at the namespace level
   - Call graph queries: function-level caller/callee relationships
   - Discovery queries: find functions by namespace or name pattern

   Example:
     (require '[seon.graph.query :as gq])

     ;; Namespace dependency queries
     (gq/dependents-of {::gq/db-name :seon.runtime ::gq/ns-name \"seon.ai.claude\"})
     ;; => [\"seon.ai.agent\" \"seon.web.agents\" ...]

     (gq/dependencies-of {::gq/db-name :seon.runtime ::gq/ns-name \"seon.ai.claude\"})
     ;; => [\"seon.ai\" \"seon.ai.claude.sdk\" \"taoensso.timbre\" ...]

     ;; Function call graph
     (gq/call-graph {::gq/db-name :seon.runtime ::gq/ns-name \"seon.ai.claude\" ::gq/fn-name \"launch-agent!\"})
     ;; => [{:seon.call/to-fn \"seon.flow.pool/acquire!\"} ...]

     (gq/callers-of {::gq/db-name :seon.runtime ::gq/ns-name \"seon.flow.pool\" ::gq/fn-name \"acquire!\"})
     ;; => [{:seon.call/from-fn \"seon.ai.claude/launch-agent!\"} ...]

     ;; Discovery
     (gq/functions-in-ns {::gq/db-name :seon.runtime ::gq/ns-name \"seon.flow.pool\"})
     ;; => [{:seon.fn/name \"acquire!\" :seon.fn/arglists \"...\" ...} ...]

     (gq/search-functions {::gq/db-name :seon.runtime ::gq/pattern \"acquire\"})
     ;; => [{:seon.fn/name \"acquire!\" :seon.fn/namespace \"seon.flow.pool\"} ...]"
  (:require [clojure.set]
            [clojure.string :as str]
            [seon.db :as db]
            [seon.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

(schema/register! ::db-name
                  [:keyword {:description "Database name keyword, e.g. :seon.runtime"}])

(schema/register! ::ns-name
                  [:string {:min 1 :description "Namespace name as string"}])

(schema/register! ::fn-name
                  [:string {:min 1 :description "Function name as string"}])

(schema/register! ::pattern
                  [:string {:min 1 :description "Search pattern (substring match)"}])

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(defn dependents-of
  "Find namespaces that depend on (require) the given namespace.

   Returns a vector of namespace name strings that have a :seon.ns.dep/*
   edge pointing to the target namespace.

   Request keys:
     ::db-name - Optional. Database name keyword (default :seon.runtime)
     ::ns-name - Required. Target namespace name (string)

   Returns:
     Vector of namespace name strings

   Example:
     (dependents-of {::db-name :seon.runtime ::ns-name \"seon.ai.claude\"})
     ;; => [\"seon.ai.agent\" \"seon.web.agents\"]"
  [{::keys [db-name ns-name]}]
  (->> (db/query (or db-name :seon.runtime)
                 '[:find ?from-ns
                   :in $ ?target-ns
                   :where
                   [?e :seon.ns.dep/to-ns ?target-ns]
                   [?e :seon.ns.dep/from-ns ?from-ns]]
                 ns-name)
       (map first)
       sort
       vec))

(defn dependencies-of
  "Find namespaces that the given namespace depends on (requires).

   Returns a vector of namespace name strings that the target namespace
   has :seon.ns.dep/* edges pointing to.

   Request keys:
     ::db-name - Optional. Database name keyword (default :seon.runtime)
     ::ns-name - Required. Source namespace name (string)

   Returns:
     Vector of namespace name strings

   Example:
     (dependencies-of {::db-name :seon.runtime ::ns-name \"seon.ai.claude\"})
     ;; => [\"seon.ai\" \"seon.ai.claude.sdk\" \"taoensso.timbre\"]"
  [{::keys [db-name ns-name]}]
  (->> (db/query (or db-name :seon.runtime)
                 '[:find ?to-ns
                   :in $ ?source-ns
                   :where
                   [?e :seon.ns.dep/from-ns ?source-ns]
                   [?e :seon.ns.dep/to-ns ?to-ns]]
                 ns-name)
       (map first)
       sort
       vec))

(defn call-graph
  "Find what functions a given function calls (outgoing edges).

   Returns a vector of maps with the called function details.

   Request keys:
     ::db-name - Optional. Database name keyword (default :seon.runtime)
     ::ns-name - Required. Namespace of the calling function
     ::fn-name - Required. Name of the calling function

   Returns:
     Vector of maps with :seon.call/to-fn, :seon.call/row keys

   Example:
     (call-graph {::db-name :seon.runtime ::ns-name \"seon.ai.claude\" ::fn-name \"launch-agent!\"})
     ;; => [{:seon.call/to-fn \"seon.flow.pool/acquire!\" :seon.call/row 42} ...]"
  [{::keys [db-name ns-name fn-name]}]
  (let [from-qn (str ns-name "/" fn-name)
        db-name (or db-name :seon.runtime)]
    (->> (db/query db-name
                   '[:find ?to-qn ?row
                     :in $ ?from-qn
                     :where
                     [?from-fn :seon.fn/qualified-name ?from-qn]
                     [?e :seon.call/from-fn ?from-fn]
                     [?e :seon.call/to-fn ?to-fn]
                     [?to-fn :seon.fn/qualified-name ?to-qn]
                     [(get-else $ ?e :seon.call/row -1) ?row]]
                   from-qn)
         (map (fn [[to-qn row]]
                (cond-> {:seon.call/to-fn to-qn}
                  (not= row -1) (assoc :seon.call/row row))))
         (sort-by :seon.call/to-fn)
         vec)))

(defn callers-of
  "Find what functions call the given function (incoming edges).

   Returns a vector of maps with the calling function details.

   Request keys:
     ::db-name - Optional. Database name keyword (default :seon.runtime)
     ::ns-name - Required. Namespace of the called function
     ::fn-name - Required. Name of the called function

   Returns:
     Vector of maps with :seon.call/from-fn, :seon.call/row keys

   Example:
     (callers-of {::db-name :seon.runtime ::ns-name \"seon.flow.pool\" ::fn-name \"acquire!\"})
     ;; => [{:seon.call/from-fn \"seon.ai.claude/launch-agent!\"} ...]"
  [{::keys [db-name ns-name fn-name]}]
  (let [to-qn (str ns-name "/" fn-name)
        db-name (or db-name :seon.runtime)]
    (->> (db/query db-name
                   '[:find ?from-qn ?row
                     :in $ ?to-qn
                     :where
                     [?to-fn :seon.fn/qualified-name ?to-qn]
                     [?e :seon.call/to-fn ?to-fn]
                     [?e :seon.call/from-fn ?from-fn]
                     [?from-fn :seon.fn/qualified-name ?from-qn]
                     [(get-else $ ?e :seon.call/row -1) ?row]]
                   to-qn)
         (map (fn [[from-qn row]]
                (cond-> {:seon.call/from-fn from-qn}
                  (not= row -1) (assoc :seon.call/row row))))
         (sort-by :seon.call/from-fn)
         vec)))

(defn functions-in-ns
  "Find all functions defined in a namespace.

   Returns a vector of function entity maps.

   Request keys:
     ::db-name - Optional. Database name keyword (default :seon.runtime)
     ::ns-name - Required. Namespace name (string)

   Returns:
     Vector of maps with :seon.fn/name, :seon.fn/arglists, :seon.fn/private, :seon.fn/row, :seon.fn/doc

   Example:
     (functions-in-ns {::db-name :seon.runtime ::ns-name \"seon.flow.pool\"})
     ;; => [{:seon.fn/name \"acquire!\" :seon.fn/arglists \"...\" :seon.fn/private false} ...]"
  [{::keys [db-name ns-name]}]
  (let [db-name (or db-name :seon.runtime)]
    (->> (db/query db-name
                   '[:find ?e
                     :in $ ?ns
                     :where
                     [?e :seon.fn/namespace ?ns]]
                   ns-name)
         (map (fn [[eid]]
                (db/pull-by-name db-name
                                 '[:seon.fn/name :seon.fn/arglists :seon.fn/private
                                   :seon.fn/row :seon.fn/doc]
                                 eid)))
         (sort-by :seon.fn/name)
         vec)))

(defn transitive-dependents-of
  "Find all namespaces that transitively depend on the given namespace.

   Walks the dependency graph iteratively: starts with direct dependents,
   then finds dependents of dependents, etc. Returns the full transitive
   closure (not including the input namespace itself).

   Request keys:
     ::db-name - Optional. Database name keyword (default :seon.runtime)
     ::ns-name - Required. Target namespace name (string)

   Returns:
     Vector of namespace name strings (sorted)

   Example:
     (transitive-dependents-of {::db-name :seon.runtime ::ns-name \"seon.schema\"})
     ;; => [\"seon.ai\" \"seon.ai.claude\" \"seon.dev.hook\" ...]"
  [{::keys [db-name ns-name]}]
  (let [db-name (or db-name :seon.runtime)]
    (loop [frontier #{ns-name}
           visited #{}]
      (let [new-deps (->> frontier
                          (mapcat (fn [ns]
                                    (dependents-of {::db-name db-name ::ns-name ns})))
                          set
                          (#(clojure.set/difference % visited frontier)))]
        (if (empty? new-deps)
          (-> (disj (into visited frontier) ns-name) sort vec)
          (recur new-deps (into visited frontier)))))))

(defn search-functions
  "Search for functions matching a name pattern (substring match).

   Returns a vector of function entity maps across all namespaces.

   Request keys:
     ::db-name - Optional. Database name keyword (default :seon.runtime)
     ::pattern - Required. Search pattern (case-insensitive substring)

   Returns:
     Vector of maps with :seon.fn/name, :seon.fn/namespace, :seon.fn/arglists, :seon.fn/private

   Example:
     (search-functions {::db-name :seon.runtime ::pattern \"acquire\"})
     ;; => [{:seon.fn/name \"acquire!\" :seon.fn/namespace \"seon.flow.pool\" ...}
     ;;     {:seon.fn/name \"acquire!!\" :seon.fn/namespace \"seon.flow.pool\" ...}]"
  [{::keys [db-name pattern]}]
  (let [pattern-lower (str/lower-case pattern)
        db-name (or db-name :seon.runtime)]
    (->> (db/query db-name
                   '[:find ?e ?name ?ns
                     :where
                     [?e :seon.fn/name ?name]
                     [?e :seon.fn/namespace ?ns]])
         (filter (fn [[_ name _]]
                   (str/includes? (str/lower-case name) pattern-lower)))
         (map (fn [[eid name ns-name]]
                (-> (db/pull-by-name db-name
                                     '[:seon.fn/arglists :seon.fn/private :seon.fn/row :seon.fn/doc]
                                     eid)
                    (assoc :seon.fn/name name :seon.fn/namespace ns-name))))
         (sort-by (juxt :seon.fn/namespace :seon.fn/name))
         vec)))

;;; ---------------------------------------------------------------------------
;;; Unified Resolution: Functions by Output Key
;;; ---------------------------------------------------------------------------

(do
  (schema/register! ::output-key
                    [:keyword {:description "Output spec key to search for (e.g. :seon.render/html)"}])

  (defonce ^:private output-key-cache (atom {}))

  (defn invalidate-output-key-cache!
    "Clear the output-key query cache.
     Called from invalidate-render-cache! on graph rescan."
    []
    (reset! output-key-cache {}))

  (defn- query-functions-with-output-key
    "Internal uncached query for functions-with-output-key."
    [db-name output-key]
    (let [eids (db/query db-name
                         '[:find ?e
                           :in $ ?output-key
                           :where
                           [?e :seon.fn/output-spec ?out]
                           [?out :seon.spec/contains-keys ?output-key]]
                         output-key)]
      (mapv (fn [[eid]]
              (let [pulled (db/pull-by-name db-name
                                            [:seon.fn/qualified-name :seon.fn/namespace
                                             :seon.fn/name :seon.fn/doc :seon.fn/updated-at
                                             {:seon.fn/input-spec [:seon.spec/contains-keys
                                                                   :seon.spec/optional-keys]}
                                             {:seon.fn/output-spec [:seon.spec/contains-keys]}]
                                            eid)
                    input-spec (:seon.fn/input-spec pulled)
                    contains (set (:seon.spec/contains-keys input-spec))
                    optional (set (:seon.spec/optional-keys input-spec))]
                (assoc pulled
                       :required-keys (clojure.set/difference contains optional)
                       :optional-keys optional)))
            eids))))

(defn functions-with-output-key
  "Find functions whose output spec contains a specific key.

   Uses ref join: fn -> output-spec -> contains-keys.
   Pulls input spec data and computes required vs optional keys.
   Results are cached; call invalidate-output-key-cache! on graph rescan.

   This is the unified discovery pattern for renderers, documentation functions,
   health checks, and any other discoverable function type. The output-key
   determines what kind of function you're looking for:
   - :seon.render/html -> HTML renderers
   - :seon.render/ai -> AI renderers
   - :seon.render/documentation -> documentation functions
   - :seon.health/status -> health check functions

   Request keys:
     ::db-name    - Optional. Database name keyword (default :seon.runtime)
     ::output-key - Required. Key to find in output spec's contains-keys

   Returns:
     Vector of maps with:
       :seon.fn/qualified-name - e.g. \"seon.foo/bar\"
       :seon.fn/namespace      - e.g. \"seon.foo\"
       :seon.fn/name           - e.g. \"bar\"
       :seon.fn/doc            - docstring (if present)
       :seon.fn/updated-at     - last update timestamp
       :required-keys          - set of required input keys
       :optional-keys          - set of optional input keys

   Example:
     (functions-with-output-key {::db-name :seon.runtime ::output-key :seon.render/html})
     ;; => [{:seon.fn/qualified-name \"seon.foo/bar\"
     ;;      :required-keys #{:seon.foo/x :seon.foo/y}
     ;;      :optional-keys #{:seon.foo/z}} ...]"
  [{::keys [db-name output-key]}]
  (let [cached (get @output-key-cache output-key ::miss)]
    (if (not= cached ::miss)
      cached
      (let [result (query-functions-with-output-key (or db-name :seon.runtime) output-key)]
        (swap! output-key-cache assoc output-key result)
        result))))

;;; ---------------------------------------------------------------------------
;;; REPL Helpers
;;; ---------------------------------------------------------------------------

(comment
  ;; Requires running system with populated graph

  ;; Who depends on seon.ai.claude?
  (dependents-of {::db-name :seon.runtime ::ns-name "seon.ai.claude"})

  ;; What does seon.ai.claude depend on?
  (dependencies-of {::db-name :seon.runtime ::ns-name "seon.ai.claude"})

  ;; What does launch-agent! call?
  (call-graph {::db-name :seon.runtime ::ns-name "seon.ai.claude" ::fn-name "launch-agent!"})

  ;; Who calls acquire!?
  (callers-of {::db-name :seon.runtime ::ns-name "seon.flow.pool" ::fn-name "acquire!"})

  ;; Functions in pool namespace
  (functions-in-ns {::db-name :seon.runtime ::ns-name "seon.flow.pool"})

  ;; Search for "acquire" functions
  (search-functions {::db-name :seon.runtime ::pattern "acquire"})

  nil)

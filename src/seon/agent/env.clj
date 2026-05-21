(ns seon.agent.env
  "Agent environment toolkit for graph search, schema discovery, and context persistence.

   Designed to be loaded in agent JVMs or the orchestrator. All functions take
   a map with a ::db-name keyword for graph access.

   Query functions wrap seon.graph.query for convenience:
   - search, functions-in, who-calls, what-calls

   Schema discovery:
   - related-schemas, who-produces, who-consumes

   Context persistence:
   - ctx-save!, ctx-load

   Example:
     (require '[seon.agent.env :as env])

     (env/search {::env/db-name :seon.runtime ::env/pattern \"calories\"})
     (env/ctx-save! {::env/db-name :seon.runtime ::env/instance-id \"a13b\" ::env/data {:results [1 2 3]}})
     (env/ctx-load {::env/db-name :seon.runtime ::env/instance-id \"a13b\"})"
  (:require [clojure.edn :as edn]
            [seon.ctx :as ctx]
            [seon.db :as db]
            [seon.graph.query :as gq]
            [seon.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

(schema/register! ::db-name
                  [:keyword {:description "Database name keyword, e.g. :seon.runtime"}])

(schema/register! ::pattern
                  [:string {:min 1 :description "Search pattern (substring match)"}])

(schema/register! ::namespace
                  [:string {:min 1 :description "Namespace name as string"}])

(schema/register! ::fn-name
                  [:string {:min 1 :description "Function name as string"}])

(schema/register! ::spec-key
                  [:keyword {:description "Spec key to find related schemas for"}])

(schema/register! ::keys
                  [:vector {:min 1} :keyword])

(schema/register! ::instance-id
                  [:string {:min 1 :description "Agent instance ID for ctx isolation"}])

(schema/register! ::data
                  [:any {:description "Serializable data to persist"}])

;;; ---------------------------------------------------------------------------
;;; Graph Query Wrappers
;;; ---------------------------------------------------------------------------

(defn search
  "Search the knowledge graph for functions by name pattern.

   Request keys:
     ::db-name - Optional. Database name keyword (default :seon.runtime)
     ::pattern - Required. Substring pattern (case-insensitive)

   Returns:
     Vector of function entity maps with :seon.fn/* keys.

   Example:
     (search {::db-name :seon.runtime ::pattern \"calories\"})
     ;; => [{:seon.fn/name \"calories\" :seon.fn/namespace \"seon.health\" ...} ...]"
  [{::keys [db-name pattern]}]
  (gq/search-functions {::gq/db-name (or db-name :seon.runtime) ::gq/pattern pattern}))

(defn functions-in
  "List all functions defined in a namespace.

   Request keys:
     ::db-name   - Optional. Database name keyword (default :seon.runtime)
     ::namespace - Required. Namespace name string

   Returns:
     Vector of function entity maps.

   Example:
     (functions-in {::db-name :seon.runtime ::namespace \"seon.health\"})"
  [{::keys [db-name namespace]}]
  (gq/functions-in-ns {::gq/db-name (or db-name :seon.runtime) ::gq/ns-name namespace}))

(defn who-calls
  "Find what functions call a given function (incoming edges).

   Request keys:
     ::db-name   - Optional. Database name keyword (default :seon.runtime)
     ::namespace - Required. Namespace of the called function
     ::fn-name   - Required. Name of the called function

   Returns:
     Vector of maps with :seon.call/from-fn keys.

   Example:
     (who-calls {::db-name :seon.runtime ::namespace \"seon.health\" ::fn-name \"log-workout!\"})"
  [{::keys [db-name namespace fn-name]}]
  (gq/callers-of {::gq/db-name (or db-name :seon.runtime) ::gq/ns-name namespace ::gq/fn-name fn-name}))

(defn what-calls
  "Find what a given function calls (outgoing edges).

   Request keys:
     ::db-name   - Optional. Database name keyword (default :seon.runtime)
     ::namespace - Required. Namespace of the calling function
     ::fn-name   - Required. Name of the calling function

   Returns:
     Vector of maps with :seon.call/to-fn keys.

   Example:
     (what-calls {::db-name :seon.runtime ::namespace \"seon.health\" ::fn-name \"log-workout!\"})"
  [{::keys [db-name namespace fn-name]}]
  (gq/call-graph {::gq/db-name (or db-name :seon.runtime) ::gq/ns-name namespace ::gq/fn-name fn-name}))

;;; ---------------------------------------------------------------------------
;;; Schema Discovery
;;; ---------------------------------------------------------------------------

(defn related-schemas
  "Find specs that share keys with a given spec.

   Queries Datahike for specs whose :seon.spec/contains-keys overlap
   with the given spec's contains-keys.

   Request keys:
     ::db-name  - Optional. Database name keyword (default :seon.runtime)
     ::spec-key - Required. The spec keyword to find related schemas for

   Returns:
     Vector of maps with :seon.spec/* keys for specs sharing at least one key.

   Example:
     (related-schemas {::db-name :seon.runtime ::spec-key :seon.db.schema/entity-name})"
  [{::keys [db-name spec-key]}]
  (let [db-name (or db-name :seon.runtime)
        source-keys (db/query db-name
                              '[:find [?k ...]
                                :in $ ?spec
                                :where
                                [?e :seon.spec/key ?spec]
                                [?e :seon.spec/contains-keys ?k]]
                              spec-key)]
    (if (empty? source-keys)
      []
      (->> source-keys
           (mapcat (fn [k]
                     (db/query db-name
                               '[:find ?other-spec
                                 :in $ ?k ?exclude
                                 :where
                                 [?e :seon.spec/key ?other-spec]
                                 [?e :seon.spec/contains-keys ?k]
                                 [(not= ?other-spec ?exclude)]]
                               k spec-key)))
           (map first)
           distinct
           (mapv (fn [spec-key']
                   (ffirst
                    (db/query db-name
                              '[:find (pull ?e [:seon.spec/key :seon.spec/namespace
                                                :seon.spec/definition :seon.spec/base-type
                                                :seon.spec/contains-keys])
                                :in $ ?spec
                                :where
                                [?e :seon.spec/key ?spec]]
                              spec-key'))))
           (sort-by :seon.spec/key)
           vec))))

(defn who-produces
  "Find functions whose output spec contains any of the given keys.

   Request keys:
     ::db-name - Optional. Database name keyword (default :seon.runtime)
     ::keys    - Required. Vector of keywords to search for in output specs

   Returns:
     Vector of function entity maps whose output-spec's contains-keys
     include any of the given keys.

   Example:
     (who-produces {::db-name :seon.runtime ::keys [:seon.db.schema/entity-name]})"
  [{::keys [db-name keys]}]
  (let [db-name (or db-name :seon.runtime)]
    (->> keys
         (mapcat (fn [k]
                   (db/query db-name
                             '[:find (pull ?fn [:seon.fn/qualified-name :seon.fn/namespace
                                               :seon.fn/name :seon.fn/arglists :seon.fn/doc])
                               :in $ ?k
                               :where
                               [?spec :seon.spec/contains-keys ?k]
                               [?fn :seon.fn/output-spec ?spec]]
                             k)))
         (map first)
         (distinct)
         (sort-by :seon.fn/qualified-name)
         vec)))

(defn who-consumes
  "Find functions whose input spec contains any of the given keys.

   Request keys:
     ::db-name - Optional. Database name keyword (default :seon.runtime)
     ::keys    - Required. Vector of keywords to search for in input specs

   Returns:
     Vector of function entity maps whose input-spec's contains-keys
     include any of the given keys.

   Example:
     (who-consumes {::db-name :seon.runtime ::keys [:seon.db.schema/entity-name]})"
  [{::keys [db-name keys]}]
  (let [db-name (or db-name :seon.runtime)]
    (->> keys
         (mapcat (fn [k]
                   (db/query db-name
                             '[:find (pull ?fn [:seon.fn/qualified-name :seon.fn/namespace
                                               :seon.fn/name :seon.fn/arglists :seon.fn/doc])
                               :in $ ?k
                               :where
                               [?spec :seon.spec/contains-keys ?k]
                               [?fn :seon.fn/input-spec ?spec]]
                             k)))
         (map first)
         (distinct)
         (sort-by :seon.fn/qualified-name)
         vec)))

;;; ---------------------------------------------------------------------------
;;; Context Persistence
;;; ---------------------------------------------------------------------------

(defn ctx-save!
  "Save agent context to Datahike, keyed by instance-id.

   Serializes data via pr-str. Non-serializable values will cause an error.

   Request keys:
     ::db-name     - Optional. Database name keyword (default :seon.runtime)
     ::instance-id - Required. Agent instance identifier
     ::data        - Required. Serializable data map

   Returns:
     The data that was saved.

   Example:
     (ctx-save! {::db-name :seon.runtime ::instance-id \"a13b\" ::data {:results [1 2 3]}})"
  [{::keys [db-name instance-id data]}]
  (let [edn-str (pr-str data)]
    (db/transact! (or db-name :seon.runtime)
                  [{:seon.ctx/instance-id instance-id
                    :seon.ctx/data edn-str
                    :seon.ctx/updated-at (java.util.Date.)}])
    data))

(defn ctx-load
  "Load saved agent context from Datahike.

   Request keys:
     ::db-name     - Optional. Database name keyword (default :seon.runtime)
     ::instance-id - Required. Agent instance identifier

   Returns:
     The deserialized data, or nil if no context found for this instance.

   Example:
     (ctx-load {::db-name :seon.runtime ::instance-id \"a13b\"})
     ;; => {:results [1 2 3]}"
  [{::keys [db-name instance-id]}]
  (let [results (db/query (or db-name :seon.runtime)
                          '[:find ?data
                            :in $ ?id
                            :where
                            [?e :seon.ctx/instance-id ?id]
                            [?e :seon.ctx/data ?data]]
                          instance-id)]
    (when (seq results)
      (edn/read-string (ffirst results)))))

;;; ---------------------------------------------------------------------------
;;; REPL Helpers
;;; ---------------------------------------------------------------------------

(comment
  (search {::db-name :seon.runtime ::pattern "acquire"})
  (functions-in {::db-name :seon.runtime ::namespace "seon.flow.pool"})
  (who-calls {::db-name :seon.runtime ::namespace "seon.flow.pool" ::fn-name "acquire!"})
  (what-calls {::db-name :seon.runtime ::namespace "seon.ai.claude" ::fn-name "launch-agent!"})

  nil)

(ns seon.agent.env
  "Agent environment toolkit for graph search, schema discovery, and context persistence.

   Designed to be loaded in agent JVMs or the orchestrator. All functions take
   a map with a Datalevin ::conn for graph access.

   Query functions wrap seon.graph.query for convenience:
   - search, functions-in, who-calls, what-calls

   Schema discovery:
   - related-schemas, who-produces, who-consumes

   Context persistence:
   - ctx-save!, ctx-load

   Example:
     (require '[seon.agent.env :as env])

     (env/search {::env/conn conn ::env/pattern \"calories\"})
     (env/ctx-save! {::env/conn conn ::env/instance-id \"a13b\" ::env/data {:results [1 2 3]}})
     (env/ctx-load {::env/conn conn ::env/instance-id \"a13b\"})"
  (:require [clojure.edn :as edn]
            [datalevin.core :as d]
            [seon.ctx :as ctx]
            [seon.db :as db]
            [seon.graph.query :as gq]
            [seon.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

(schema/register! ::conn
                  [:any {:description "Datalevin connection for graph queries"}])

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
;;; Datalevin Schema Extension (for ctx persistence)
;;; ---------------------------------------------------------------------------

(def ctx-schema
  "Alias for seon.ctx/datalevin-schema. Use the canonical schema from seon.ctx."
  ctx/datalevin-schema)

;;; ---------------------------------------------------------------------------
;;; Graph Query Wrappers
;;; ---------------------------------------------------------------------------

(defn search
  "Search the knowledge graph for functions by name pattern.

   Request keys:
     ::conn    - Required. Datalevin connection
     ::pattern - Required. Substring pattern (case-insensitive)

   Returns:
     Vector of function entity maps with :seon.fn/* keys.

   Example:
     (search {::conn conn ::pattern \"calories\"})
     ;; => [{:seon.fn/name \"calories\" :seon.fn/namespace \"seon.health\" ...} ...]"
  [{::keys [conn pattern]}]
  (gq/search-functions {::gq/conn conn ::gq/pattern pattern}))

(defn functions-in
  "List all functions defined in a namespace.

   Request keys:
     ::conn      - Required. Datalevin connection
     ::namespace - Required. Namespace name string

   Returns:
     Vector of function entity maps.

   Example:
     (functions-in {::conn conn ::namespace \"seon.health\"})"
  [{::keys [conn namespace]}]
  (gq/functions-in-ns {::gq/conn conn ::gq/ns-name namespace}))

(defn who-calls
  "Find what functions call a given function (incoming edges).

   Request keys:
     ::conn      - Required. Datalevin connection
     ::namespace - Required. Namespace of the called function
     ::fn-name   - Required. Name of the called function

   Returns:
     Vector of maps with :seon.call/from-fn keys.

   Example:
     (who-calls {::conn conn ::namespace \"seon.health\" ::fn-name \"log-workout!\"})"
  [{::keys [conn namespace fn-name]}]
  (gq/callers-of {::gq/conn conn ::gq/ns-name namespace ::gq/fn-name fn-name}))

(defn what-calls
  "Find what a given function calls (outgoing edges).

   Request keys:
     ::conn      - Required. Datalevin connection
     ::namespace - Required. Namespace of the calling function
     ::fn-name   - Required. Name of the calling function

   Returns:
     Vector of maps with :seon.call/to-fn keys.

   Example:
     (what-calls {::conn conn ::namespace \"seon.health\" ::fn-name \"log-workout!\"})"
  [{::keys [conn namespace fn-name]}]
  (gq/call-graph {::gq/conn conn ::gq/ns-name namespace ::gq/fn-name fn-name}))

;;; ---------------------------------------------------------------------------
;;; Schema Discovery
;;; ---------------------------------------------------------------------------

(defn related-schemas
  "Find specs that share keys with a given spec.

   Queries Datalevin for specs whose :seon.spec/contains-keys overlap
   with the given spec's contains-keys.

   Request keys:
     ::conn     - Required. Datalevin connection
     ::spec-key - Required. The spec keyword to find related schemas for

   Returns:
     Vector of maps with :seon.spec/* keys for specs sharing at least one key.

   Example:
     (related-schemas {::conn conn ::spec-key :seon.health/workout})"
  [{::keys [conn spec-key]}]
  (let [source-keys (d/q '[:find [?k ...]
                            :in $ ?spec
                            :where
                            [?e :seon.spec/key ?spec]
                            [?e :seon.spec/contains-keys ?k]]
                          @conn spec-key)]
    (if (empty? source-keys)
      []
      (->> source-keys
           (mapcat (fn [k]
                     (d/q '[:find ?other-spec
                             :in $ ?k ?exclude
                             :where
                             [?e :seon.spec/key ?other-spec]
                             [?e :seon.spec/contains-keys ?k]
                             [(not= ?other-spec ?exclude)]]
                           @conn k spec-key)))
           (map first)
           distinct
           (mapv (fn [spec-key']
                   (ffirst
                    (d/q '[:find (pull ?e [:seon.spec/key :seon.spec/namespace
                                           :seon.spec/definition :seon.spec/base-type
                                           :seon.spec/contains-keys])
                            :in $ ?spec
                            :where
                            [?e :seon.spec/key ?spec]]
                          @conn spec-key'))))
           (sort-by :seon.spec/key)
           vec))))

(defn who-produces
  "Find functions whose output spec contains any of the given keys.

   Request keys:
     ::conn - Required. Datalevin connection
     ::keys - Required. Vector of keywords to search for in output specs

   Returns:
     Vector of function entity maps whose output-spec's contains-keys
     include any of the given keys.

   Example:
     (who-produces {::conn conn ::keys [:seon.health/workout]})"
  [{::keys [conn keys]}]
  (->> keys
       (mapcat (fn [k]
                 (d/q '[:find (pull ?fn [:seon.fn/qualified-name :seon.fn/namespace
                                         :seon.fn/name :seon.fn/arglists :seon.fn/doc])
                          :in $ ?k
                          :where
                          [?spec :seon.spec/contains-keys ?k]
                          [?fn :seon.fn/output-spec ?spec]]
                       @conn k)))
       (map first)
       (distinct)
       (sort-by :seon.fn/qualified-name)
       vec))

(defn who-consumes
  "Find functions whose input spec contains any of the given keys.

   Request keys:
     ::conn - Required. Datalevin connection
     ::keys - Required. Vector of keywords to search for in input specs

   Returns:
     Vector of function entity maps whose input-spec's contains-keys
     include any of the given keys.

   Example:
     (who-consumes {::conn conn ::keys [:seon.health/workout]})"
  [{::keys [conn keys]}]
  (->> keys
       (mapcat (fn [k]
                 (d/q '[:find (pull ?fn [:seon.fn/qualified-name :seon.fn/namespace
                                         :seon.fn/name :seon.fn/arglists :seon.fn/doc])
                          :in $ ?k
                          :where
                          [?spec :seon.spec/contains-keys ?k]
                          [?fn :seon.fn/input-spec ?spec]]
                       @conn k)))
       (map first)
       (distinct)
       (sort-by :seon.fn/qualified-name)
       vec))

;;; ---------------------------------------------------------------------------
;;; Context Persistence
;;; ---------------------------------------------------------------------------

(defn ctx-save!
  "Save agent context to Datalevin, keyed by instance-id.

   Serializes data via pr-str. Non-serializable values will cause an error.

   Request keys:
     ::conn        - Required. Datalevin connection (with ctx-schema applied)
     ::instance-id - Required. Agent instance identifier
     ::data        - Required. Serializable data map

   Returns:
     The data that was saved.

   Example:
     (ctx-save! {::conn conn ::instance-id \"a13b\" ::data {:results [1 2 3]}})"
  [{::keys [conn instance-id data]}]
  (let [edn-str (pr-str data)]
    (db/transact! conn [{:seon.ctx/instance-id instance-id
                         :seon.ctx/data edn-str
                         :seon.ctx/updated-at (java.util.Date.)}])
    data))

(defn ctx-load
  "Load saved agent context from Datalevin.

   Request keys:
     ::conn        - Required. Datalevin connection (with ctx-schema applied)
     ::instance-id - Required. Agent instance identifier

   Returns:
     The deserialized data, or nil if no context found for this instance.

   Example:
     (ctx-load {::conn conn ::instance-id \"a13b\"})
     ;; => {:results [1 2 3]}"
  [{::keys [conn instance-id]}]
  (let [results (d/q '[:find ?data
                        :in $ ?id
                        :where
                        [?e :seon.ctx/instance-id ?id]
                        [?e :seon.ctx/data ?data]]
                      @conn instance-id)]
    (when (seq results)
      (edn/read-string (ffirst results)))))

;;; ---------------------------------------------------------------------------
;;; REPL Helpers
;;; ---------------------------------------------------------------------------

(comment
  (require '[integrant.repl.state :as state])
  (require '[seon.db.datalevin.conn :as dlc])

  (def mgr (:seon.db.datalevin/connections state/system))
  (def conn (dlc/get-conn!
             {:seon.db.datalevin.conn/manager mgr
              :seon.db.datalevin.conn/db :seon.ai}))

  (search {::conn conn ::pattern "acquire"})
  (functions-in {::conn conn ::namespace "seon.flow.pool"})
  (who-calls {::conn conn ::namespace "seon.flow.pool" ::fn-name "acquire!"})
  (what-calls {::conn conn ::namespace "seon.ai.claude" ::fn-name "launch-agent!"})

  nil)

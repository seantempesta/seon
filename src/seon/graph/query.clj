(ns seon.graph.query
  "Datalog query API for the knowledge graph.

   Provides high-level query functions over the Datalevin graph populated
   by seon.graph.ingest. All queries operate on the dereferenced connection
   (snapshot of the database).

   Query categories:
   - Dependency queries: who depends on what, at the namespace level
   - Call graph queries: function-level caller/callee relationships
   - Discovery queries: find functions by namespace or name pattern

   Example:
     (require '[seon.graph.query :as gq])

     ;; Namespace dependency queries
     (gq/dependents-of {::gq/conn conn ::gq/ns-name \"seon.ai.claude\"})
     ;; => [\"seon.ai.agent\" \"seon.web.agents\" ...]

     (gq/dependencies-of {::gq/conn conn ::gq/ns-name \"seon.ai.claude\"})
     ;; => [\"seon.ai\" \"seon.ai.claude.sdk\" \"taoensso.timbre\" ...]

     ;; Function call graph
     (gq/call-graph {::gq/conn conn ::gq/ns-name \"seon.ai.claude\" ::gq/fn-name \"launch-agent!\"})
     ;; => [{:seon.call/to-fn \"seon.flow.pool/acquire!\"} ...]

     (gq/callers-of {::gq/conn conn ::gq/ns-name \"seon.flow.pool\" ::gq/fn-name \"acquire!\"})
     ;; => [{:seon.call/from-fn \"seon.ai.claude/launch-agent!\"} ...]

     ;; Discovery
     (gq/functions-in-ns {::gq/conn conn ::gq/ns-name \"seon.flow.pool\"})
     ;; => [{:seon.fn/name \"acquire!\" :seon.fn/arglists \"...\" ...} ...]

     (gq/search-functions {::gq/conn conn ::gq/pattern \"acquire\"})
     ;; => [{:seon.fn/name \"acquire!\" :seon.fn/namespace \"seon.flow.pool\"} ...]"
  (:require [datalevin.core :as d]
            [clojure.string :as str]
            [seon.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

(schema/register! ::conn
                  [:any {:description "Datalevin connection"}])

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

   Note: No :malli/schema - conn is a runtime object.

   Request keys:
     ::conn    - Required. Datalevin connection
     ::ns-name - Required. Target namespace name (string)

   Returns:
     Vector of namespace name strings

   Example:
     (dependents-of {::conn conn ::ns-name \"seon.ai.claude\"})
     ;; => [\"seon.ai.agent\" \"seon.web.agents\"]"
  [{::keys [conn ns-name]}]
  (->> (d/q '[:find ?from-ns
              :in $ ?target-ns
              :where
              [?e :seon.ns.dep/to-ns ?target-ns]
              [?e :seon.ns.dep/from-ns ?from-ns]]
            @conn ns-name)
       (map first)
       sort
       vec))

(defn dependencies-of
  "Find namespaces that the given namespace depends on (requires).

   Returns a vector of namespace name strings that the target namespace
   has :seon.ns.dep/* edges pointing to.

   Request keys:
     ::conn    - Required. Datalevin connection
     ::ns-name - Required. Source namespace name (string)

   Returns:
     Vector of namespace name strings

   Example:
     (dependencies-of {::conn conn ::ns-name \"seon.ai.claude\"})
     ;; => [\"seon.ai\" \"seon.ai.claude.sdk\" \"taoensso.timbre\"]"
  [{::keys [conn ns-name]}]
  (->> (d/q '[:find ?to-ns
              :in $ ?source-ns
              :where
              [?e :seon.ns.dep/from-ns ?source-ns]
              [?e :seon.ns.dep/to-ns ?to-ns]]
            @conn ns-name)
       (map first)
       sort
       vec))

(defn call-graph
  "Find what functions a given function calls (outgoing edges).

   Returns a vector of maps with the called function details.

   Request keys:
     ::conn    - Required. Datalevin connection
     ::ns-name - Required. Namespace of the calling function
     ::fn-name - Required. Name of the calling function

   Returns:
     Vector of maps with :seon.call/to-fn, :seon.call/row keys

   Example:
     (call-graph {::conn conn ::ns-name \"seon.ai.claude\" ::fn-name \"launch-agent!\"})
     ;; => [{:seon.call/to-fn \"seon.flow.pool/acquire!\" :seon.call/row 42} ...]"
  [{::keys [conn ns-name fn-name]}]
  (let [from-fn (str ns-name "/" fn-name)]
    (->> (d/q '[:find ?to-fn ?row
                :in $ ?from-fn
                :where
                [?e :seon.call/from-fn ?from-fn]
                [?e :seon.call/to-fn ?to-fn]
                [(get-else $ ?e :seon.call/row -1) ?row]]
              @conn from-fn)
         (map (fn [[to-fn row]]
                (cond-> {:seon.call/to-fn to-fn}
                  (not= row -1) (assoc :seon.call/row row))))
         (sort-by :seon.call/to-fn)
         vec)))

(defn callers-of
  "Find what functions call the given function (incoming edges).

   Returns a vector of maps with the calling function details.

   Request keys:
     ::conn    - Required. Datalevin connection
     ::ns-name - Required. Namespace of the called function
     ::fn-name - Required. Name of the called function

   Returns:
     Vector of maps with :seon.call/from-fn, :seon.call/row keys

   Example:
     (callers-of {::conn conn ::ns-name \"seon.flow.pool\" ::fn-name \"acquire!\"})
     ;; => [{:seon.call/from-fn \"seon.ai.claude/launch-agent!\"} ...]"
  [{::keys [conn ns-name fn-name]}]
  (let [to-fn (str ns-name "/" fn-name)]
    (->> (d/q '[:find ?from-fn ?row
                :in $ ?to-fn
                :where
                [?e :seon.call/to-fn ?to-fn]
                [?e :seon.call/from-fn ?from-fn]
                [(get-else $ ?e :seon.call/row -1) ?row]]
              @conn to-fn)
         (map (fn [[from-fn row]]
                (cond-> {:seon.call/from-fn from-fn}
                  (not= row -1) (assoc :seon.call/row row))))
         (sort-by :seon.call/from-fn)
         vec)))

(defn functions-in-ns
  "Find all functions defined in a namespace.

   Returns a vector of function entity maps.

   Request keys:
     ::conn    - Required. Datalevin connection
     ::ns-name - Required. Namespace name (string)

   Returns:
     Vector of maps with :seon.fn/name, :seon.fn/arglists, :seon.fn/private, :seon.fn/row, :seon.fn/doc

   Example:
     (functions-in-ns {::conn conn ::ns-name \"seon.flow.pool\"})
     ;; => [{:seon.fn/name \"acquire!\" :seon.fn/arglists \"...\" :seon.fn/private false} ...]"
  [{::keys [conn ns-name]}]
  (->> (d/q '[:find ?e
              :in $ ?ns
              :where
              [?e :seon.fn/namespace ?ns]]
            @conn ns-name)
       (map (fn [[eid]]
              (d/pull @conn '[:seon.fn/name :seon.fn/arglists :seon.fn/private
                              :seon.fn/row :seon.fn/doc]
                      eid)))
       (sort-by :seon.fn/name)
       vec))

(defn search-functions
  "Search for functions matching a name pattern (substring match).

   Returns a vector of function entity maps across all namespaces.

   Request keys:
     ::conn    - Required. Datalevin connection
     ::pattern - Required. Search pattern (case-insensitive substring)

   Returns:
     Vector of maps with :seon.fn/name, :seon.fn/namespace, :seon.fn/arglists, :seon.fn/private

   Example:
     (search-functions {::conn conn ::pattern \"acquire\"})
     ;; => [{:seon.fn/name \"acquire!\" :seon.fn/namespace \"seon.flow.pool\" ...}
     ;;     {:seon.fn/name \"acquire!!\" :seon.fn/namespace \"seon.flow.pool\" ...}]"
  [{::keys [conn pattern]}]
  (let [pattern-lower (str/lower-case pattern)]
    (->> (d/q '[:find ?e ?name ?ns
                :where
                [?e :seon.fn/name ?name]
                [?e :seon.fn/namespace ?ns]]
              @conn)
         (filter (fn [[_ name _]]
                   (str/includes? (str/lower-case name) pattern-lower)))
         (map (fn [[eid name ns-name]]
                (-> (d/pull @conn '[:seon.fn/arglists :seon.fn/private :seon.fn/row :seon.fn/doc] eid)
                    (assoc :seon.fn/name name :seon.fn/namespace ns-name))))
         (sort-by (juxt :seon.fn/namespace :seon.fn/name))
         vec)))

;;; ---------------------------------------------------------------------------
;;; REPL Helpers
;;; ---------------------------------------------------------------------------

(comment
  ;; Requires running system with populated graph
  (require '[integrant.repl.state :as state])
  (require '[seon.db.datalevin.conn :as conn])

  (def mgr (:seon/connection-manager state/system))
  (def dl-conn (conn/get-master-conn!
                {:seon.db.datalevin.conn/manager mgr}))

  ;; Who depends on seon.ai.claude?
  (dependents-of {::conn dl-conn ::ns-name "seon.ai.claude"})

  ;; What does seon.ai.claude depend on?
  (dependencies-of {::conn dl-conn ::ns-name "seon.ai.claude"})

  ;; What does launch-agent! call?
  (call-graph {::conn dl-conn ::ns-name "seon.ai.claude" ::fn-name "launch-agent!"})

  ;; Who calls acquire!?
  (callers-of {::conn dl-conn ::ns-name "seon.flow.pool" ::fn-name "acquire!"})

  ;; Functions in pool namespace
  (functions-in-ns {::conn dl-conn ::ns-name "seon.flow.pool"})

  ;; Search for "acquire" functions
  (search-functions {::conn dl-conn ::pattern "acquire"})

  nil)

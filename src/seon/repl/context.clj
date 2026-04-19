(ns seon.repl.context
  "Context cockpit for AI agents.

   Wraps graph context builder with render pipeline to provide
   agent-friendly context retrieval from the knowledge graph.

   Example:
     (require '[seon.repl.context :as context])

     (context/for-function {::context/db-name :seon.runtime
                            ::context/qualified-name \"seon.health/log-workout!\"})
     ;; => \"## seon.health/log-workout! ...\"

     (context/for-namespace {::context/db-name :seon.runtime
                             ::context/namespace \"seon.health\"})
     ;; => \"# seon.health\\n## Functions\\n...\"

     (context/for-data {::context/db-name :seon.runtime
                        ::context/data {:seon.health/workout {...}}})
     ;; => \"Matching renderers: seon.health.render/workout-view\\n...\""
  (:require [seon.graph.context :as ctx]
            [seon.graph.query :as gq]
            [seon.render :as render]
            [seon.schema :as schema]
            [clojure.string :as str]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

(schema/register! ::db-name
                  [:keyword {:description "Database name keyword (e.g. :seon.runtime)"}])

(schema/register! ::qualified-name
                  [:string {:min 1
                            :description "Fully qualified function name (ns/fn)"}])

(schema/register! ::namespace
                  [:string {:min 1
                            :description "Namespace name"}])

(schema/register! ::data
                  [:map {:description "Data map to find renderers for"}])

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(defn for-function
  "Get AI context for a function.

   Builds linearized context from the knowledge graph centered on the
   given function, including its call graph, specs, and related functions.

   Request keys:
     ::db-name        - Required. Database name keyword
     ::qualified-name - Required. Fully qualified function name (e.g. \"seon.health/log-workout!\")

   Returns:
     Context string suitable for AI agent consumption.

   Example:
     (for-function {::db-name :seon.runtime ::qualified-name \"seon.health/log-workout!\"})"
  [{::keys [db-name qualified-name]}]
  (let [result (ctx/build {::ctx/db-name db-name
                           ::ctx/seed qualified-name
                           ::ctx/depth 2})]
    (::ctx/context-text result)))

(defn for-namespace
  "Get AI context for a namespace.

   Builds linearized context for all functions in the namespace and
   their immediate dependencies.

   Request keys:
     ::db-name   - Required. Database name keyword
     ::namespace - Required. Namespace name (string)

   Returns:
     Context string suitable for AI agent consumption.

   Example:
     (for-namespace {::db-name :seon.runtime ::namespace \"seon.health\"})"
  [{::keys [db-name namespace]}]
  (let [result (ctx/build-for-namespace {::ctx/db-name db-name
                                          ::ctx/namespace namespace})]
    (::ctx/context-text result)))

(defn for-data
  "Given a data map, find relevant renderers and return context about them.

   Uses seon.render/find-renderer to discover renderers that match
   the data shape, and returns information about the matching renderers.

   Request keys:
     ::db-name - Required. Database name keyword
     ::data    - Required. Data map to find renderers for

   Returns:
     Context string describing matching renderers, or a message if none found.

   Example:
     (for-data {::db-name :seon.runtime ::data {:seon.health/workout {...}}})"
  [{::keys [db-name data]}]
  (let [ai-renderer (render/find-renderer db-name data :ai)
        html-renderer (render/find-renderer db-name data :html)
        renderers (remove nil? [ai-renderer html-renderer])]
    (if (seq renderers)
      (str "Matching renderers for data with keys " (pr-str (keys data)) ":\n"
           (str/join "\n" (map (fn [qn] (str "  - " qn)) renderers))
           "\n\n"
           ;; Build context for each renderer function
           (str/join "\n\n"
                     (map (fn [qn]
                            (let [result (ctx/build {::ctx/db-name db-name
                                                     ::ctx/seed qn
                                                     ::ctx/depth 1})]
                              (::ctx/context-text result)))
                          renderers)))
      (str "No matching renderers found for data with keys: " (pr-str (keys data))))))

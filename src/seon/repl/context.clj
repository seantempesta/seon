(ns seon.repl.context
  "Context cockpit for AI agents.

   Wraps graph context builder with render pipeline to provide
   agent-friendly context retrieval from the knowledge graph.

   Example:
     (require '[seon.repl.context :as context])

     (context/for-function {::context/conn conn
                            ::context/qualified-name \"seon.health/log-workout!\"})
     ;; => \"## seon.health/log-workout! ...\"

     (context/for-namespace {::context/conn conn
                             ::context/namespace \"seon.health\"})
     ;; => \"# seon.health\\n## Functions\\n...\"

     (context/for-data {::context/conn conn
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

(schema/register! ::conn
                  [:any {:description "Datalevin connection"}])

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
     ::conn           - Required. Datalevin connection
     ::qualified-name - Required. Fully qualified function name (e.g. \"seon.health/log-workout!\")

   Returns:
     Context string suitable for AI agent consumption.

   Example:
     (for-function {::conn conn ::qualified-name \"seon.health/log-workout!\"})"
  [{::keys [conn qualified-name]}]
  (let [result (ctx/build {::ctx/conn conn
                           ::ctx/seed qualified-name
                           ::ctx/depth 2})]
    (::ctx/context-text result)))

(defn for-namespace
  "Get AI context for a namespace.

   Builds linearized context for all functions in the namespace and
   their immediate dependencies.

   Request keys:
     ::conn      - Required. Datalevin connection
     ::namespace - Required. Namespace name (string)

   Returns:
     Context string suitable for AI agent consumption.

   Example:
     (for-namespace {::conn conn ::namespace \"seon.health\"})"
  [{::keys [conn namespace]}]
  (let [result (ctx/build-for-namespace {::ctx/conn conn
                                          ::ctx/namespace namespace})]
    (::ctx/context-text result)))

(defn for-data
  "Given a data map, find relevant renderers and return context about them.

   Uses seon.render/find-renderer to discover renderers that match
   the data shape, and returns information about the matching renderers.

   Request keys:
     ::conn - Required. Datalevin connection
     ::data - Required. Data map to find renderers for

   Returns:
     Context string describing matching renderers, or a message if none found.

   Example:
     (for-data {::conn conn ::data {:seon.health/workout {...}}})"
  [{::keys [conn data]}]
  (let [ai-renderer (render/find-renderer conn data :ai)
        html-renderer (render/find-renderer conn data :html)
        renderers (remove nil? [ai-renderer html-renderer])]
    (if (seq renderers)
      (str "Matching renderers for data with keys " (pr-str (keys data)) ":\n"
           (str/join "\n" (map (fn [qn] (str "  - " qn)) renderers))
           "\n\n"
           ;; Build context for each renderer function
           (str/join "\n\n"
                     (map (fn [qn]
                            (let [result (ctx/build {::ctx/conn conn
                                                     ::ctx/seed qn
                                                     ::ctx/depth 1})]
                              (::ctx/context-text result)))
                          renderers)))
      (str "No matching renderers found for data with keys: " (pr-str (keys data))))))

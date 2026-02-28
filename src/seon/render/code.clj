(ns seon.render.code
  "Code and documentation rendering from the knowledge graph.

   Provides functions for discovering compatible functions, rendering
   namespace documentation at multiple detail levels, and building
   context for AI agents.

   Core concepts:
   - compatible-functions: find functions that can consume given data keys
   - render-ns-docs: render namespace documentation from the graph
   - resolve-docs: find custom documentation renderers via output key discovery
   - context-for-agent: combine docs + call graph context for AI agents

   Example:
     (require '[seon.render.code :as rc])

     ;; Find functions compatible with given keys
     (rc/compatible-functions {::rc/conn conn
                               ::rc/available-keys #{:seon.foo/x :seon.foo/y}})

     ;; Render namespace documentation
     (rc/render-ns-docs {::rc/conn conn ::rc/ns-name \"seon.graph.query\"})

     ;; Build full context for an agent
     (rc/context-for-agent {::rc/conn conn ::rc/ns-name \"seon.graph.query\"})"
  (:require [clojure.set :as cset]
            [clojure.string :as str]
            [datalevin.core :as d]
            [seon.graph.context :as graph-ctx]
            [seon.graph.query :as gq]
            [seon.render :as render]
            [seon.schema :as schema]
            [taoensso.timbre :as log]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

(schema/register! ::conn
                  [:any {:description "Datalevin connection"}])

(schema/register! ::available-keys
                  [:set :keyword {:description "Set of available data keys"}])

(schema/register! ::output-filter
                  [:keyword {:description "Only return fns whose output contains this key"}])

(schema/register! ::ns-name
                  [:string {:min 1 :description "Namespace name as string"}])

(schema/register! ::detail
                  [:enum :summary :interface :deep-dive])

(schema/register! ::max-entities
                  [:int {:min 1
                         :description "Entity cap (default 50)"}])

;;; ---------------------------------------------------------------------------
;;; compatible-functions
;;; ---------------------------------------------------------------------------

(defn compatible-functions
  "Find functions whose required input keys are a subset of available-keys.

   This discovers functions that CAN be called with the given data,
   regardless of what they produce.

   Note: No :malli/schema - conn is a runtime object.

   Request keys:
     ::conn           - Required. Datalevin connection
     ::available-keys - Required. Set of available data keys
     ::output-filter  - Optional. Only return fns whose output contains this key

   Returns:
     Vector of function entities with :required-keys, :optional-keys computed,
     sorted by specificity (most required keys first).

   Example:
     (compatible-functions {::conn conn
                            ::available-keys #{:seon.foo/x :seon.foo/y}})
     ;; => [{:seon.fn/qualified-name \"seon.foo/bar\"
     ;;      :required-keys #{:seon.foo/x}
     ;;      :optional-keys #{}} ...]"
  [{::keys [conn available-keys output-filter]}]
  (let [;; Get all functions with input specs
        all-fns (d/q '[:find ?e
                       :where
                       [?e :seon.fn/input-spec _]]
                     @conn)
        ;; Pull and compute required keys for each
        candidates
        (for [[eid] all-fns
              :let [pulled (d/pull @conn
                                   [:seon.fn/qualified-name :seon.fn/namespace
                                    :seon.fn/name :seon.fn/doc :seon.fn/updated-at
                                    {:seon.fn/input-spec [:seon.spec/contains-keys
                                                          :seon.spec/optional-keys]}
                                    {:seon.fn/output-spec [:seon.spec/contains-keys]}]
                                   eid)
                    input-spec (:seon.fn/input-spec pulled)
                    contains (set (:seon.spec/contains-keys input-spec))
                    optional (set (:seon.spec/optional-keys input-spec))
                    required (cset/difference contains optional)]
              ;; Filter: required keys must be subset of available keys
              :when (cset/subset? required available-keys)
              ;; Optional output filter
              :when (or (nil? output-filter)
                        (contains? (set (:seon.spec/contains-keys
                                         (:seon.fn/output-spec pulled)))
                                   output-filter))]
          (assoc pulled
                 :required-keys required
                 :optional-keys optional))]
    (->> candidates
         (sort-by (juxt (comp - count :required-keys)
                        :seon.fn/qualified-name))
         vec)))

;;; ---------------------------------------------------------------------------
;;; render-ns-docs
;;; ---------------------------------------------------------------------------

(defn- render-fn-summary
  "Render a function as a single line for :summary detail level."
  [fn-entity]
  (str "- " (:seon.fn/name fn-entity)))

(defn- render-fn-interface
  "Render a function with signature and key types for :interface detail level."
  [fn-entity]
  (let [name (:seon.fn/name fn-entity)
        args (:seon.fn/arglists fn-entity)
        doc (:seon.fn/doc fn-entity)
        input-spec (:seon.fn/input-spec fn-entity)
        output-spec (:seon.fn/output-spec fn-entity)
        in-keys (:seon.spec/contains-keys input-spec)
        out-keys (:seon.spec/contains-keys output-spec)
        first-line (if doc
                     (first (str/split-lines doc))
                     nil)]
    (str/join "\n"
              (cond-> [(str "### " name
                            (when args (str " " args)))]
                first-line (conj (str "  " first-line))
                (seq in-keys) (conj (str "  Input: " (str/join ", " (sort (map str in-keys)))))
                (seq out-keys) (conj (str "  Output: " (str/join ", " (sort (map str out-keys)))))))))

(defn- render-fn-deep-dive
  "Render a function with full docs and spec details for :deep-dive detail level."
  [fn-entity]
  (let [name (:seon.fn/name fn-entity)
        args (:seon.fn/arglists fn-entity)
        doc (:seon.fn/doc fn-entity)
        input-spec (:seon.fn/input-spec fn-entity)
        output-spec (:seon.fn/output-spec fn-entity)
        in-keys (:seon.spec/contains-keys input-spec)
        in-opt (:seon.spec/optional-keys input-spec)
        out-keys (:seon.spec/contains-keys output-spec)
        in-def (:seon.spec/definition input-spec)
        out-def (:seon.spec/definition output-spec)]
    (str/join "\n"
              (cond-> [(str "### " name
                            (when args (str " " args)))]
                doc (conj doc)
                in-def (conj (str "\n  Input spec: " in-def))
                (seq in-keys) (conj (str "  Input keys: " (str/join ", " (sort (map str in-keys)))))
                (seq in-opt) (conj (str "  Optional: " (str/join ", " (sort (map str in-opt)))))
                out-def (conj (str "  Output spec: " out-def))
                (seq out-keys) (conj (str "  Output keys: " (str/join ", " (sort (map str out-keys)))))))))

(defn- pull-fn-with-specs
  "Pull a function entity with input/output spec data."
  [conn eid]
  (d/pull @conn
          [:seon.fn/qualified-name :seon.fn/namespace
           :seon.fn/name :seon.fn/arglists :seon.fn/private
           :seon.fn/doc :seon.fn/row
           {:seon.fn/input-spec [:seon.spec/contains-keys
                                 :seon.spec/optional-keys
                                 :seon.spec/definition]}
           {:seon.fn/output-spec [:seon.spec/contains-keys
                                  :seon.spec/definition]}]
          eid))

(defn render-ns-docs
  "Render documentation for a namespace's public API.

   Uses the graph to discover functions, specs, and dependencies.
   No namespace cooperation required - works with any namespace that
   has functions with docstrings or schemas.

   Note: No :malli/schema - conn is a runtime object.

   Request keys:
     ::conn     - Required. Datalevin connection
     ::ns-name  - Required. Namespace name (string)
     ::detail   - Optional. :summary, :interface (default), or :deep-dive

   Returns:
     {:seon.render/documentation \"...text...\"}

   Example:
     (render-ns-docs {::conn conn ::ns-name \"seon.graph.query\"})
     ;; => {:seon.render/documentation \"## seon.graph.query\\n...\"}"
  [{::keys [conn ns-name detail]}]
  (let [detail (or detail :interface)
        ;; Get all functions in namespace
        fn-eids (d/q '[:find ?e
                       :in $ ?ns
                       :where
                       [?e :seon.fn/namespace ?ns]]
                     @conn ns-name)
        ;; Pull with specs
        fns (->> fn-eids
                 (map (fn [[eid]] (pull-fn-with-specs conn eid)))
                 (sort-by :seon.fn/name))
        ;; Filter to public unless deep-dive
        fns (if (= detail :deep-dive)
              fns
              (remove :seon.fn/private fns))
        ;; Get namespace docstring
        ns-doc (when-let [ns-eid (ffirst (d/q '[:find ?e
                                                 :in $ ?n
                                                 :where [?e :seon.ns/name ?n]]
                                               @conn ns-name))]
                 (:seon.ns/doc (d/pull @conn [:seon.ns/doc] ns-eid)))
        ;; Render based on detail level
        render-fn (case detail
                    :summary render-fn-summary
                    :interface render-fn-interface
                    :deep-dive render-fn-deep-dive)
        header (str "## " ns-name)
        body (if (empty? fns)
               "No functions found."
               (str/join "\n\n" (map render-fn fns)))]
    {:seon.render/documentation
     (str/join "\n\n"
               (cond-> [header]
                 ns-doc (conj ns-doc)
                 true (conj body)))}))

;;; ---------------------------------------------------------------------------
;;; resolve-docs
;;; ---------------------------------------------------------------------------

(defn resolve-docs
  "Find the best documentation renderer for a namespace.

   Looks for functions whose output spec contains :seon.render/documentation.
   If found and the function's required keys match the available data,
   uses that custom renderer. Otherwise returns nil (use default).

   Note: No :malli/schema - conn is a runtime object.

   Request keys:
     ::conn           - Required. Datalevin connection
     ::ns-name        - Required. Namespace name (string)
     ::available-keys - Optional. Data keys to match against custom renderers

   Returns:
     The resolved var, or nil (use default).

   Example:
     (resolve-docs {::conn conn ::ns-name \"seon.health.workout\"})"
  [{::keys [conn ns-name available-keys]}]
  (let [candidates (gq/functions-with-output-key
                    {::gq/conn conn
                     ::gq/output-key :seon.render/documentation})
        ;; Filter by namespace proximity (prefer same ns or child)
        nearby (->> candidates
                    (filter (fn [c]
                              (<= (render/namespace-proximity
                                   (:seon.fn/qualified-name c) ns-name) 2)))
                    ;; Filter by available keys if provided
                    (filter (fn [c]
                              (or (nil? available-keys)
                                  (cset/subset? (:required-keys c)
                                                available-keys)))))]
    (when (seq nearby)
      (let [best (->> nearby
                      (sort-by (juxt (fn [c] (render/namespace-proximity
                                              (:seon.fn/qualified-name c) ns-name))
                                     (comp - count :required-keys)
                                     :seon.fn/qualified-name))
                      first)
            qname (:seon.fn/qualified-name best)]
        (try
          (requiring-resolve (symbol qname))
          (catch Exception e
            (log/warn "Failed to resolve doc renderer" {:fn qname :error (.getMessage e)})
            nil))))))

;;; ---------------------------------------------------------------------------
;;; context-for-agent
;;; ---------------------------------------------------------------------------

(defn context-for-agent
  "Build context for an AI agent working on a namespace.

   Combines:
   - Namespace documentation (via resolve-docs or render-ns-docs)
   - Function signatures with specs
   - Call graph (who calls what)
   - Dependency information

   Note: No :malli/schema - conn is a runtime object.

   Request keys:
     ::conn          - Required. Datalevin connection
     ::ns-name       - Required. Target namespace
     ::max-entities  - Optional. Entity cap (default 50)

   Returns:
     {:seon.render/documentation \"...text...\"
      ::entity-count N}

   Example:
     (context-for-agent {::conn conn ::ns-name \"seon.health.workout\"})"
  [{::keys [conn ns-name max-entities]}]
  (let [;; Try custom documentation renderer first
        custom-renderer (resolve-docs {::conn conn ::ns-name ns-name})
        ;; Get documentation
        docs (if custom-renderer
               (try
                 (:seon.render/documentation (custom-renderer {}))
                 (catch Exception e
                   (log/warn "Custom doc renderer failed" {:ns ns-name :error (.getMessage e)})
                   nil))
               nil)
        docs (or docs
                 (:seon.render/documentation
                  (render-ns-docs {::conn conn ::ns-name ns-name ::detail :interface})))
        ;; Build call graph context
        graph-context (graph-ctx/build-for-namespace
                       {::graph-ctx/conn conn
                        ::graph-ctx/namespace ns-name
                        ::graph-ctx/max-entities (or max-entities 50)})
        context-text (::graph-ctx/context-text graph-context)
        entity-count (::graph-ctx/entity-count graph-context)]
    {:seon.render/documentation (str docs "\n\n---\n\n" context-text)
     ::entity-count entity-count}))

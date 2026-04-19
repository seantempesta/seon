(ns seon.repl
  "REPL form router.
   Receives forms, classifies, stores in Datalevin, routes eval through
   the infrastructure flow topology, updates the code index after each eval."
  (:require [clojure.core.async.flow :as flow]
            [edamame.core :as edamame]
            [seon.db :as db]
            [seon.db.schema :as db-schema]
            [seon.flow.msg :as msg]
            [seon.flow.pool :as pool]
            [seon.graph.analyzer :as analyzer]
            [seon.graph.ingest :as ingest]
            [seon.schema :as schema]
            [taoensso.timbre :as log])
  (:import [java.time Instant]
           [java.util Date UUID]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration (function params and response keys)
;;; ---------------------------------------------------------------------------

(schema/register! ::source
                  [:string {:min 1 :description "Clojure source form string"}])

(schema/register! ::namespace
                  [:or :symbol :string
                   {:description "Clojure namespace for form evaluation"}])

(schema/register! ::agent-id
                  [:string {:min 1 :description "Agent session ID"}])

(schema/register! ::db-name
                  [:keyword {:description "Database name keyword (e.g. :seon.runtime)"}])

(schema/register! ::nrepl-port
                  [:int {:min 7900 :max 7999
                         :description "nREPL port of agent JVM (optional)"}])

(schema/register! ::form-type
                  [:enum :defn :def :ns :require :expression
                   {:description "Classified form type"}])

(schema/register! ::form-name
                  [:maybe :string {:description "Name extracted from form (nil for expressions)"}])

(schema/register! ::version
                  [:int {:min 1 :description "Form version number"}])

(schema/register! ::result
                  [:any {:description "nREPL eval result value"}])

;;; ---------------------------------------------------------------------------
;;; Datalevin attribute registrations (enforcement layer requires these)
;;; ---------------------------------------------------------------------------

(schema/register! :form/id
                  [:uuid {:description "Unique form identifier"
                          :seon.db/identity true}])
(schema/register! :form/namespace
                  [:string {:min 1 :description "Namespace the form belongs to"}])
(schema/register! :form/type
                  [:keyword {:description "Classified form type"}])
(schema/register! :form/name
                  [:string {:min 1 :description "Name extracted from form"}])
(schema/register! :form/source
                  [:string {:min 1 :description "Clojure source code string"}])
(schema/register! :form/agent-id
                  [:string {:min 1 :description "Agent session ID"}])
(schema/register! :form/version
                  [:int {:min 1 :description "Form version number"}])
(schema/register! :form/created-at
                  [:inst {:description "Timestamp of form creation"}])

;;; ---------------------------------------------------------------------------
;;; Datalevin Entity Schema (Malli is the source of truth)
;;; ---------------------------------------------------------------------------

(def form-entity-schema
  "Malli schema for a REPL form entity stored in Datalevin.
   All persisted attrs have concrete types — no :any, no [:maybe X].
   :form/name is optional because expressions and requires have no name."
  [:map
   [:form/id :form/id]
   [:form/namespace :string]
   [:form/type :keyword]
   [:form/name {:optional true} :string]
   [:form/source :string]
   [:form/agent-id :string]
   [:form/version :int]
   [:form/created-at :inst]])

(db-schema/register-entity-schema! "seon.repl" form-entity-schema)

(def datalevin-schema
  "Datalevin schema for form storage. Derived from Malli.
   Merged with ingest schema at conn creation."
  (db-schema/malli-map->datalevin-schema form-entity-schema))

;;; ---------------------------------------------------------------------------
;;; Form Classification
;;; ---------------------------------------------------------------------------

(defn classify-form
  "Parse a form string and return its type and name.

   Request keys:
     ::source - Required. Clojure source code string

   Response keys:
     ::form-type - :defn, :def, :ns, :require, or :expression
     ::form-name - Extracted name (string) or nil for expressions

   Example:
     (classify-form {::source \"(defn ema [period data] ...)\"})
     ;; => {::form-type :defn ::form-name \"ema\"}"
  [{::keys [source]}]
  (try
    (let [form (edamame/parse-string source {:all true})]
      (if (and (sequential? form) (symbol? (first form)))
        (let [head (name (first form))]
          (cond
            (contains? #{"defn" "defn-"} head)
            {::form-type :defn
             ::form-name (str (second form))}

            (= "def" head)
            {::form-type :def
             ::form-name (str (second form))}

            (= "ns" head)
            {::form-type :ns
             ::form-name (str (second form))}

            (= "require" head)
            {::form-type :require
             ::form-name nil}

            :else
            {::form-type :expression
             ::form-name nil}))
        {::form-type :expression
         ::form-name nil}))
    (catch Exception e
      (log/warn "Failed to parse form for classification" {:error (.getMessage e)})
      {::form-type :expression
       ::form-name nil})))

;;; ---------------------------------------------------------------------------
;;; Versioning
;;; ---------------------------------------------------------------------------

(defn- next-version
  "Query Datalevin for the max version of a named form in a namespace.
   Returns max + 1, or 1 if no prior version exists."
  [db-name ns-str form-name]
  (if (nil? form-name)
    1
    (let [results (db/query db-name
                            '[:find ?v
                              :in $ ?ns ?name
                              :where
                              [?e :form/namespace ?ns]
                              [?e :form/name ?name]
                              [?e :form/version ?v]]
                            ns-str form-name)]
      (if (seq results)
        (inc (apply max (map first results)))
        1))))

;;; ---------------------------------------------------------------------------
;;; Form Storage
;;; ---------------------------------------------------------------------------

(defn- store-form!
  "Store a form in Datalevin with versioning. Returns the entity map."
  [db-name ns-str form-type form-name source agent-id]
  (let [version (next-version db-name ns-str form-name)
        entity (cond-> {:form/id (UUID/randomUUID)
                        :form/namespace ns-str
                        :form/type form-type
                        :form/source source
                        :form/agent-id agent-id
                        :form/version version
                        :form/created-at (Date.)}
                 form-name (assoc :form/name form-name))]
    (db/transact! db-name [entity])
    entity))

;;; ---------------------------------------------------------------------------
;;; Code Index Update
;;; ---------------------------------------------------------------------------

(defn- update-code-index!
  "Run analyzer + incremental ingest to update the knowledge graph."
  [db-name source]
  (try
    (let [analysis (analyzer/analyze-form {::analyzer/source source})]
      (when (::analyzer/success analysis)
        (let [entities (analyzer/extract-entities
                        {::analyzer/raw-analysis (::analyzer/raw-analysis analysis)})]
          (ingest/ingest-incremental! {::ingest/db-name db-name ::ingest/entities entities}))))
    (catch Exception e
      (log/warn "Code index update failed" {:error (.getMessage e)})
      nil)))

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(defn- get-infra-flow
  "Get the infrastructure flow object from the runtime flow registry.
   Uses requiring-resolve to avoid circular dependency."
  []
  (let [get-flow (requiring-resolve 'seon.runtime/get-flow)
        handle (get-flow {:seon.runtime/flow-id :seon.flow/infrastructure})]
    (when handle
      (:flow handle))))

(defn- get-pending-promises
  "Get the pending-promises atom from seon.flow.topology."
  []
  @(requiring-resolve 'seon.flow.topology/pending-promises))

(defn- eval-via-flow!
  "Route an eval through the infrastructure flow's :seon.flow/repl process.
   Throws if the infrastructure flow is not running — no fallbacks."
  [port source]
  (let [fl (get-infra-flow)]
    (when-not fl
      (throw (ex-info "Infrastructure flow not running — cannot eval"
                      {:fn "seon.repl/eval-via-flow!"})))
    (let [pending (get-pending-promises)
          request-id (random-uuid)
          p (promise)
          request {::msg/id request-id
                   ::msg/version 1
                   ::msg/type :request
                   ::msg/from-ns "seon.repl"
                   ::msg/payload {:form/source source
                                  :form/port port}
                   ::msg/created-at (Instant/now)}]
      (swap! pending assoc request-id p)
      (try
        (flow/inject fl [:seon.flow/repl :seon.flow.in/request] [request])
        (let [reply (deref p 30000 ::timed-out)]
          (if (= reply ::timed-out)
            (do
              (swap! pending dissoc request-id)
              (throw (ex-info "REPL eval timed out"
                              {::msg/status :timeout
                               ::msg/id request-id})))
            (case (::msg/status reply)
              :ok (::msg/value reply)
              (throw (ex-info (or (::msg/error-message reply) "REPL eval failed")
                              (select-keys reply [::msg/status ::msg/error-type
                                                  ::msg/error-message]))))))
        (catch Exception e
          (swap! pending dissoc request-id)
          (throw e))))))

(defn eval-form!
  "Main entry point. Classify, eval, store, and index a form.

   Routes eval through the infrastructure flow's :seon.flow/repl process
   for observability. Falls back to direct nREPL when the flow is not running.

   Note: No :malli/schema - db-name and nrepl-port are runtime objects.

   Request keys:
     ::source     - Required. Clojure source code string
     ::namespace  - Required. Target namespace (symbol or string)
     ::agent-id   - Required. Agent session ID
     ::db-name    - Required. Database name keyword (e.g. :seon.runtime)
     ::nrepl-port - Optional. Agent JVM nREPL port (skips eval if nil)

   Response keys:
     ::result    - nREPL eval result (nil if no port)
     ::form-type - Classified form type
     ::form-name - Extracted form name (nil for expressions)
     ::version   - Version number stored

   Example:
     (eval-form! {::source \"(defn ema [p d] ...)\"
                  ::namespace 'seon.trading.signals
                  ::agent-id \"a13b\"
                  ::db-name :seon.runtime
                  ::nrepl-port 7901})"
  [{::keys [source namespace agent-id db-name nrepl-port]}]
  (let [ns-str (str namespace)
        {:keys [::form-type ::form-name]} (classify-form {::source source})
        ;; Eval on agent JVM via flow topology
        eval-result (when nrepl-port
                      (eval-via-flow! nrepl-port source))
        ;; Store in Datalevin
        stored (store-form! db-name ns-str form-type form-name source agent-id)
        ;; Update code index
        _ (update-code-index! db-name source)]
    (log/debug "Form processed" {:type form-type :name form-name
                                 :version (:form/version stored)
                                 :namespace ns-str})
    {::result eval-result
     ::form-type form-type
     ::form-name form-name
     ::version (:form/version stored)}))

(defn current-forms
  "Query latest version of each named form in a namespace.

   Request keys:
     ::db-name   - Required. Database name keyword (e.g. :seon.runtime)
     ::namespace - Required. Namespace string or symbol

   Returns vector of form entity maps (latest version of each named form).

   Example:
     (current-forms {::db-name :seon.runtime ::namespace \"seon.trading.signals\"})"
  [{::keys [db-name namespace]}]
  (let [ns-str (str namespace)
        ;; Get all named forms with their entity ids, names, and versions
        results (db/query db-name
                          '[:find ?e ?name ?v
                            :in $ ?ns
                            :where
                            [?e :form/namespace ?ns]
                            [?e :form/name ?name]
                            [?e :form/version ?v]]
                          ns-str)
        ;; Group by name, pick max version
        by-name (group-by second results)
        latest (for [[_name entries] by-name
                     :let [[eid _ _] (apply max-key #(nth % 2) entries)]]
                 (db/entity-by-name db-name eid))]
    (vec latest)))

(defn form-history
  "All versions of a specific form.

   Request keys:
     ::db-name   - Required. Database name keyword (e.g. :seon.runtime)
     ::namespace - Required. Namespace string or symbol
     ::form-name - Required. Form name string

   Returns vector of form entity maps sorted by version ascending.

   Example:
     (form-history {::db-name :seon.runtime ::namespace \"seon.trading.signals\" ::form-name \"ema\"})"
  [{::keys [db-name namespace form-name]}]
  (let [ns-str (str namespace)
        results (db/query db-name
                          '[:find ?e ?v
                            :in $ ?ns ?name
                            :where
                            [?e :form/namespace ?ns]
                            [?e :form/name ?name]
                            [?e :form/version ?v]]
                          ns-str form-name)]
    (->> results
         (sort-by second)
         (mapv (fn [[eid _]] (db/entity-by-name db-name eid))))))

;;; ---------------------------------------------------------------------------
;;; REPL Exploration
;;; ---------------------------------------------------------------------------

(comment
  (classify-form {::source "(defn ema [period data] (reduce + data))"})
  ;; => {::form-type :defn, ::form-name "ema"}

  (classify-form {::source "(require '[clojure.string :as str])"})
  ;; => {::form-type :require, ::form-name nil}

  (classify-form {::source "(+ 1 2)"})
  ;; => {::form-type :expression, ::form-name nil}

  nil)

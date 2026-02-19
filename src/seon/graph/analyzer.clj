(ns seon.graph.analyzer
  "Code analysis for the knowledge graph.

   Provides two analysis modes:
   - Full project analysis via clj-kondo on src/ directories
   - Incremental per-form analysis via clj-kondo on strings

   The analysis output is transformed into structured entity maps ready
   for ingestion into Datalevin by seon.graph.ingest.

   Example:
     (require '[seon.graph.analyzer :as analyzer])

     ;; Full project analysis (startup)
     (analyzer/analyze-project! {::paths [\"src/\"]})
     ;; => {::success true ::raw-analysis {...} ::duration-ms 1200}

     ;; Per-form analysis (incremental)
     (analyzer/analyze-form {::source \"(defn ema [period data] (reduce + data))\"})
     ;; => {::success true ::raw-analysis {...}}

     ;; Transform raw analysis to graph entities
     (analyzer/extract-entities {::raw-analysis {...}})
     ;; => {::namespaces [...] ::functions [...] ::var-usages [...] ::namespace-usages [...]}"
  (:require [clj-kondo.core :as clj-kondo]
            [clojure.string :as str]
            [seon.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

(schema/register! ::paths
                  [:vector {:min 1 :description "Directories to analyze"}
                   :string])

(schema/register! ::source
                  [:string {:min 1 :description "Clojure source code string"}])

(schema/register! ::file-path
                  [:string {:description "Optional file path for context"}])

(schema/register! ::success
                  [:boolean {:description "Whether analysis succeeded"}])

(schema/register! ::error
                  [:string {:description "Error message if failed"}])

(schema/register! ::duration-ms
                  [:int {:min 0 :description "Analysis duration in milliseconds"}])

(schema/register! ::raw-analysis
                  [:map {:description "Raw clj-kondo analysis output"}])

;; Entity schemas for extract-entities output
(schema/register! ::namespace-entity
                  [:map
                   [:seon.ns/name :string]
                   [:seon.ns/file {:optional true} :string]
                   [:seon.ns/doc {:optional true} :string]
                   [:seon.ns/target {:optional true} :keyword]])

(schema/register! ::function-entity
                  [:map
                   [:seon.fn/qualified-name :string]
                   [:seon.fn/name :string]
                   [:seon.fn/namespace :string]
                   [:seon.fn/arglists {:optional true} :string]
                   [:seon.fn/private {:optional true} :boolean]
                   [:seon.fn/row {:optional true} :int]
                   [:seon.fn/doc {:optional true} :string]])

(schema/register! ::ns-dependency-entity
                  [:map
                   [:seon.ns.dep/from-ns :string]
                   [:seon.ns.dep/to-ns :string]
                   [:seon.ns.dep/alias {:optional true} :string]])

(schema/register! ::var-usage-entity
                  [:map
                   [:seon.call/from-fn :string]
                   [:seon.call/to-fn :string]
                   [:seon.call/row {:optional true} :int]])

(schema/register! ::namespaces
                  [:vector ::namespace-entity])

(schema/register! ::functions
                  [:vector ::function-entity])

(schema/register! ::var-usages
                  [:vector ::var-usage-entity])

(schema/register! ::namespace-usages
                  [:vector ::ns-dependency-entity])

(schema/register! ::extracted-entities
                  [:map
                   [::namespaces ::namespaces]
                   [::functions ::functions]
                   [::var-usages ::var-usages]
                   [::namespace-usages ::namespace-usages]])

;; Request/Response schemas
(schema/register! ::analyze-project-request
                  [:map
                   [::paths {:optional true} ::paths]])

(schema/register! ::analyze-project-response
                  [:map
                   [::success ::success]
                   [::raw-analysis {:optional true} ::raw-analysis]
                   [::duration-ms {:optional true} ::duration-ms]
                   [::error {:optional true} ::error]])

(schema/register! ::analyze-form-request
                  [:map
                   [::source ::source]
                   [::file-path {:optional true} ::file-path]])

(schema/register! ::analyze-form-response
                  [:map
                   [::success ::success]
                   [::raw-analysis {:optional true} ::raw-analysis]
                   [::error {:optional true} ::error]])

(schema/register! ::extract-entities-request
                  [:map
                   [::raw-analysis ::raw-analysis]])

;;; ---------------------------------------------------------------------------
;;; Private Implementation
;;; ---------------------------------------------------------------------------

(def ^:private project-kondo-config
  "clj-kondo config for full project analysis.
   Enables arglists, var-usages, and var-definitions extraction."
  {:output {:analysis {:arglists true
                       :var-usages true
                       :var-definitions {:shallow true}
                       :namespace-definitions {:shallow true}}}})

(defn- file->target
  "Derive target platform keyword from file extension.
   .clj -> :clj, .cljs -> :cljs, .cljc -> :cljc. Defaults to :clj."
  [filename]
  (cond
    (str/ends-with? filename ".cljs") :cljs
    (str/ends-with? filename ".cljc") :cljc
    :else :clj))

(defn- extract-namespace-entities
  "Transform clj-kondo namespace-definitions to namespace entities."
  [ns-defs]
  (->> (or ns-defs [])
       (map (fn [nd]
              (cond-> {:seon.ns/name (str (:name nd))}
                (:filename nd) (assoc :seon.ns/file (:filename nd)
                                      :seon.ns/target (file->target (:filename nd)))
                (:doc nd)      (assoc :seon.ns/doc (:doc nd)))))
       vec))

(defn- extract-function-entities
  "Transform clj-kondo var-definitions to function entities."
  [var-defs]
  (->> (or var-defs [])
       (map (fn [vd]
              (let [ns-str (str (:ns vd))
                    name-str (str (:name vd))]
                (cond-> {:seon.fn/qualified-name (str ns-str "/" name-str)
                         :seon.fn/name name-str
                         :seon.fn/namespace ns-str}
                  (:arglist-strs vd) (assoc :seon.fn/arglists (pr-str (vec (:arglist-strs vd))))
                  (some? (:private vd)) (assoc :seon.fn/private (:private vd))
                  (nil? (:private vd)) (assoc :seon.fn/private false)
                  (:row vd) (assoc :seon.fn/row (:row vd))
                  (:doc vd) (assoc :seon.fn/doc (:doc vd))))))
       vec))

(defn- extract-ns-dependency-entities
  "Transform clj-kondo namespace-usages to dependency entities."
  [ns-usages]
  (->> (or ns-usages [])
       (map (fn [nu]
              (cond-> {:seon.ns.dep/from-ns (str (:from nu))
                       :seon.ns.dep/to-ns (str (:to nu))}
                (:alias nu) (assoc :seon.ns.dep/alias (str (:alias nu))))))
       ;; Deduplicate: same from-ns -> to-ns can appear multiple times
       (distinct)
       vec))

(defn- extract-var-usage-entities
  "Transform clj-kondo var-usages to call entities."
  [var-usages]
  (->> (or var-usages [])
       (map (fn [vu]
              (let [from-ns (str (:from vu))
                    from-var (when (:from-var vu) (str (:from-var vu)))
                    to-ns (str (:to vu))
                    to-name (str (:name vu))]
                (cond-> {:seon.call/from-fn (if from-var
                                              (str from-ns "/" from-var)
                                              from-ns)
                         :seon.call/to-fn (str to-ns "/" to-name)}
                  (:row vu) (assoc :seon.call/row (:row vu))))))
       vec))

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(defn analyze-project!
  "Analyze the full project using clj-kondo.

   Runs clj-kondo on the specified source directories (defaults to [\"src/\"]).
   Returns the raw analysis data suitable for extract-entities and ingest.

   This is intended for initial graph population at startup.

   Request keys:
     ::paths - Optional. Source directories to analyze (default [\"src/\"])

   Response keys:
     ::success       - true if analysis succeeded
     ::raw-analysis  - Raw clj-kondo analysis map
     ::duration-ms   - Analysis duration in milliseconds
     ::error         - Error message if failed

   Example:
     (analyze-project! {})
     (analyze-project! {::paths [\"src/\" \"test/\"]})"
  [{::keys [paths]}]
  (let [paths (or paths ["src/"])
        start-time (System/currentTimeMillis)]
    (try
      (let [result (clj-kondo/run! {:lint paths
                                    :config project-kondo-config})
            duration (- (System/currentTimeMillis) start-time)]
        {::success true
         ::raw-analysis (:analysis result)
         ::duration-ms duration})
      (catch Exception e
        {::success false
         ::error (.getMessage e)
         ::duration-ms (- (System/currentTimeMillis) start-time)}))))

(defn analyze-form
  "Analyze a single Clojure form string using clj-kondo.

   This is used for incremental updates when agents eval forms.
   Returns analysis data suitable for extract-entities.

   Request keys:
     ::source    - Required. Clojure source code string
     ::file-path - Optional. File path for context

   Response keys:
     ::success       - true if analysis succeeded
     ::raw-analysis  - Raw analysis data from clj-kondo
     ::error         - Error message if failed

   Example:
     (analyze-form {::source \"(defn ema [period data] (reduce + data))\"})"
  [{::keys [source file-path]}]
  (try
    (let [file-path (or file-path "<stdin>")
          result (with-in-str source
                   (clj-kondo/run! {:lint ["-"]
                                    :filename file-path
                                    :config project-kondo-config
                                    :cache false}))]
      {::success true
       ::raw-analysis (:analysis result)})
    (catch Exception e
      {::success false
       ::error (.getMessage e)})))

(defn extract-entities
  "Transform raw clj-kondo analysis output into structured graph entities.

   Takes the raw analysis map from clj-kondo and produces entity maps
   suitable for Datalevin ingestion by seon.graph.ingest.

   Request keys:
     ::raw-analysis - Raw clj-kondo analysis map (from analyze-project! or analyze-form)

   Response keys:
     ::namespaces       - Vector of namespace entities
     ::functions        - Vector of function entities
     ::var-usages       - Vector of call entities
     ::namespace-usages - Vector of namespace dependency entities

   Example:
     (let [{::keys [raw-analysis]} (analyze-project! {})]
       (extract-entities {::raw-analysis raw-analysis}))"
  [{::keys [raw-analysis]}]
  {::namespaces (extract-namespace-entities (:namespace-definitions raw-analysis))
   ::functions (extract-function-entities (:var-definitions raw-analysis))
   ::var-usages (extract-var-usage-entities (:var-usages raw-analysis))
   ::namespace-usages (extract-ns-dependency-entities (:namespace-usages raw-analysis))})

;;; ---------------------------------------------------------------------------
;;; REPL Helpers
;;; ---------------------------------------------------------------------------

(comment
  ;; Full project analysis
  (def project (analyze-project! {}))
  (::success project)
  (::duration-ms project)

  ;; Extract entities
  (def entities (extract-entities {::raw-analysis (::raw-analysis project)}))
  (count (::namespaces entities))
  (count (::functions entities))
  (count (::var-usages entities))
  (count (::namespace-usages entities))

  ;; Check specific namespace
  (->> (::namespaces entities)
       (filter #(= "seon.ai.claude" (:seon.ns/name %)))
       first)

  ;; Per-form analysis
  (def form-result
    (analyze-form {::source "(defn ema [period data] (reduce + data))"}))
  (::success form-result)
  (extract-entities {::raw-analysis (::raw-analysis form-result)})

  nil)

(ns seon.dev.analysis
  "Unified code analysis using clj-kondo.

   Provides deep code understanding capabilities:
   - analyze-file - Parse file with clj-kondo, return analysis data
   - call-graph - Extract caller/callee relationships
   - format-file! - Auto-format with cljfmt

   This replaces scattered manual parsing with a single source of truth.
   clj-kondo provides AST analysis, var definitions, var usages (for call
   graphs), and namespace dependencies - all from one fast parse.

   Example usage:
     (require '[seon.dev.analysis :as analysis])

     ;; Analyze a file
     (analysis/analyze-file {::file-path \"src/seon/dev/hook.clj\"})
     ;; => {::success true
     ;;     ::namespace 'seon.dev.hook
     ;;     ::var-definitions [{:name 'process-hook-event! ...}]
     ;;     ::var-usages [...]
     ;;     ::namespace-usages [...]}

     ;; Get call graph for a function
     (analysis/callees-of {::analysis (analyze-file ...)
     ;;                     ::fn-name 'process-hook-event!})
     ;; => [{:to seon.dev.codebase :name clojure-file? :row 443} ...]

     ;; Auto-format a file
     (analysis/format-file! {::file-path \"src/seon/dev/hook.clj\"})"
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clj-kondo.core :as clj-kondo]
            [seon.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration (per CONVENTIONS.md)
;;; ---------------------------------------------------------------------------

(schema/register! ::file-path
                  [:string {:min 1
                            :description "Path to a Clojure file"}])

(schema/register! ::namespace
                  [:fn {:description "Namespace symbol"}
                   symbol?])

(schema/register! ::fn-name
                  [:fn {:description "Function/var name symbol"}
                   symbol?])

(schema/register! ::success
                  [:boolean {:description "Whether operation succeeded"}])

(schema/register! ::error
                  [:string {:description "Error message if failed"}])

(schema/register! ::duration-ms
                  [:int {:min 0
                         :description "Analysis duration in milliseconds"}])

;; Var definition from clj-kondo
(schema/register! ::var-definition
                  [:map
                   [:name ::fn-name]
                   [:ns {:optional true} ::namespace]
                   [:row {:optional true} :int]
                   [:col {:optional true} :int]
                   [:arglist-strs {:optional true} [:vector :string]]
                   [:doc {:optional true} :string]
                   [:private {:optional true} :boolean]
                   [:macro {:optional true} :boolean]])

(schema/register! ::var-definitions
                  [:vector ::var-definition])

;; Var usage from clj-kondo (for call graphs)
(schema/register! ::var-usage
                  [:map
                   [:from ::namespace]
                   [:to ::namespace]
                   [:name ::fn-name]
                   [:from-var {:optional true} ::fn-name]
                   [:row {:optional true} :int]
                   [:col {:optional true} :int]
                   [:arity {:optional true} :int]])

(schema/register! ::var-usages
                  [:vector ::var-usage])

;; Namespace usage (require/use)
(schema/register! ::namespace-usage
                  [:map
                   [:from ::namespace]
                   [:to ::namespace]
                   [:alias {:optional true} :symbol]
                   [:row {:optional true} :int]])

(schema/register! ::namespace-usages
                  [:vector ::namespace-usage])

;; Lint findings
(schema/register! ::finding
                  [:map
                   [:type :keyword]
                   [:level [:enum :error :warning :info]]
                   [:message :string]
                   [:row {:optional true} :int]
                   [:col {:optional true} :int]])

(schema/register! ::findings
                  [:vector ::finding])

;; Full analysis result
(schema/register! ::analysis
                  [:map
                   [::success ::success]
                   [::namespace {:optional true} ::namespace]
                   [::var-definitions {:optional true} ::var-definitions]
                   [::var-usages {:optional true} ::var-usages]
                   [::namespace-usages {:optional true} ::namespace-usages]
                   [::findings {:optional true} ::findings]
                   [::duration-ms {:optional true} ::duration-ms]
                   [::error {:optional true} ::error]])

;; Request/Response schemas
(schema/register! ::analyze-file-request
                  [:map
                   [::file-path ::file-path]])

(schema/register! ::analyze-file-response
                  ::analysis)

(schema/register! ::callees-request
                  [:map
                   [::analysis ::analysis]
                   [::fn-name ::fn-name]])

(schema/register! ::callees-response
                  [:map
                   [::callees ::var-usages]])

(schema/register! ::callers-request
                  [:map
                   [::analysis ::analysis]
                   [::fn-name ::fn-name]])

(schema/register! ::callers-response
                  [:map
                   [::callers [:vector ::fn-name]]])

(schema/register! ::format-file-request
                  [:map
                   [::file-path ::file-path]])

(schema/register! ::format-file-response
                  [:map
                   [::success ::success]
                   [::changed {:optional true} :boolean]
                   [::error {:optional true} ::error]])

;;; ---------------------------------------------------------------------------
;;; clj-kondo Integration (library-based, Phase 11a)
;;; ---------------------------------------------------------------------------

(def ^:private clj-kondo-lib-config
  "clj-kondo config for library usage.
   Enables analysis data extraction for var definitions, usages, and arglists."
  {:output {:analysis {:arglists true
                       :var-usages true
                       :var-definitions true}}})

(defn- run-clj-kondo-lib
  "Run clj-kondo as a library (in-process).
   Returns {:findings [...] :analysis {...} :summary {...}}."
  [file-path]
  (clj-kondo/run! {:lint [file-path]
                   :config clj-kondo-lib-config}))

(defn- extract-namespace
  "Extract namespace from clj-kondo result.
   Uses :namespace-definitions which has the actual ns declaration."
  [result]
  (some-> result
          :analysis
          :namespace-definitions
          first
          :name))

(defn- transform-var-definitions
  "Transform clj-kondo var-definitions to our schema."
  [var-defs]
  (mapv (fn [vd]
          (-> vd
              (select-keys [:name :ns :row :col :arglist-strs :doc :private :macro])
              (update :name symbol)))
        var-defs))

(defn- transform-var-usages
  "Transform clj-kondo var-usages to our schema."
  [var-usages]
  (mapv (fn [vu]
          (cond-> {:from (:from vu)
                   :to (:to vu)
                   :name (:name vu)}
            (:from-var vu) (assoc :from-var (:from-var vu))
            (:row vu) (assoc :row (:row vu))
            (:col vu) (assoc :col (:col vu))
            (:arity vu) (assoc :arity (:arity vu))))
        var-usages))

(defn- transform-namespace-usages
  "Transform clj-kondo namespace-usages to our schema."
  [ns-usages]
  (mapv (fn [nu]
          (cond-> {:from (:from nu)
                   :to (:to nu)}
            (:alias nu) (assoc :alias (:alias nu))
            (:row nu) (assoc :row (:row nu))))
        ns-usages))

(defn- transform-findings
  "Transform clj-kondo findings to our schema."
  [findings]
  (mapv (fn [f]
          (cond-> {:type (:type f)
                   :level (:level f)
                   :message (:message f)}
            (:row f) (assoc :row (:row f))
            (:col f) (assoc :col (:col f))))
        findings))

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(defn analyze-file
  "Analyze a Clojure file using clj-kondo.

   Returns rich analysis data including:
   - Namespace information
   - All var definitions (functions, defs)
   - All var usages (for call graph extraction)
   - Namespace dependencies (requires)
   - Lint findings

   Request keys:
     ::file-path - Path to the Clojure file

   Response keys:
     ::success          - true if analysis succeeded
     ::namespace        - Namespace symbol
     ::var-definitions  - Vector of var definitions
     ::var-usages       - Vector of var usages (for call graphs)
     ::namespace-usages - Vector of required namespaces
     ::findings         - Vector of lint findings
     ::duration-ms      - Analysis duration
     ::error            - Error message if failed

   Example:
     (analyze-file {::file-path \"src/seon/dev/hook.clj\"})
     ;; => {::success true
     ;;     ::namespace seon.dev.hook
     ;;     ::var-definitions [{:name process-hook-event! ...}]
     ;;     ::var-usages [{:from seon.dev.hook :to seon.dev.codebase ...}]
     ;;     ...}"
  {:malli/schema [:=> [:cat ::analyze-file-request] ::analyze-file-response]}
  [{::keys [file-path]}]
  (let [start-time (System/currentTimeMillis)
        f (io/file file-path)]
    (cond
      (not (.exists f))
      {::success false
       ::error "File does not exist"}

      (not (.isFile f))
      {::success false
       ::error "Path is not a regular file"}

      :else
      (try
        (let [result (run-clj-kondo-lib file-path)
              duration (- (System/currentTimeMillis) start-time)
              analysis (:analysis result)]
          {::success true
           ::namespace (extract-namespace result)
           ::var-definitions (transform-var-definitions (:var-definitions analysis))
           ::var-usages (transform-var-usages (:var-usages analysis))
           ::namespace-usages (transform-namespace-usages (:namespace-usages analysis))
           ::findings (transform-findings (:findings result))
           ::duration-ms duration})
        (catch Exception e
          {::success false
           ::error (.getMessage e)
           ::duration-ms (- (System/currentTimeMillis) start-time)})))))

(defn callees-of
  "Get all functions called by a specific function.

   Filters var-usages to find calls made from within the specified function.
   This is the 'outgoing' call graph - what does this function call?

   Request keys:
     ::analysis - Result from analyze-file
     ::fn-name  - Symbol of the function to analyze

   Response keys:
     ::callees - Vector of var-usages representing called functions

   Example:
     (callees-of {::analysis (analyze-file {...})
                  ::fn-name 'process-hook-event!})
     ;; => {::callees [{:to seon.dev.codebase :name clojure-file? :row 443}
     ;;                {:to seon.dev.hook :name stage-repair :row 477}
     ;;                ...]}"
  {:malli/schema [:=> [:cat ::callees-request] ::callees-response]}
  [{::keys [analysis fn-name]}]
  (let [usages (::var-usages analysis)
        callees (->> usages
                     (filter #(= (:from-var %) fn-name))
                     (map #(select-keys % [:to :name :row :col :arity]))
                     vec)]
    {::callees callees}))

(defn callers-of
  "Get all functions that call a specific function.

   Filters var-usages to find which functions call the target.
   This is the 'incoming' call graph - who calls this function?

   Request keys:
     ::analysis - Result from analyze-file
     ::fn-name  - Symbol of the function to analyze

   Response keys:
     ::callers - Vector of caller function name symbols

   Example:
     (callers-of {::analysis (analyze-file {...})
                  ::fn-name 'stage-repair})
     ;; => {::callers [process-hook-event!]}"
  {:malli/schema [:=> [:cat ::callers-request] ::callers-response]}
  [{::keys [analysis fn-name]}]
  (let [usages (::var-usages analysis)
        callers (->> usages
                     (filter #(= (:name %) fn-name))
                     (keep :from-var)
                     distinct
                     vec)]
    {::callers callers}))

(defn format-file!
  "Auto-format a Clojure file using cljfmt.

   Runs cljfmt fix on the file in place. This normalizes whitespace
   and indentation so agents don't waste tokens on formatting issues.

   Request keys:
     ::file-path - Path to the file to format

   Response keys:
     ::success - true if formatting succeeded
     ::changed - true if file was modified
     ::error   - Error message if failed

   Example:
     (format-file! {::file-path \"src/seon/dev/hook.clj\"})
     ;; => {::success true ::changed false}"
  {:malli/schema [:=> [:cat ::format-file-request] ::format-file-response]}
  [{::keys [file-path]}]
  (let [f (io/file file-path)]
    (cond
      (not (.exists f))
      {::success false
       ::error "File does not exist"}

      (not (.isFile f))
      {::success false
       ::error "Path is not a regular file"}

      :else
      (let [before-content (slurp f)
            {:keys [exit err]} (shell/sh "cljfmt" "fix" file-path)]
        (if (= exit 0)
          (let [after-content (slurp f)]
            {::success true
             ::changed (not= before-content after-content)})
          {::success false
           ::error (or err "cljfmt failed")})))))

(defn public-var-definitions
  "Get only public var definitions from analysis.

   Filters out private vars, returning only public API functions.

   Request keys:
     ::analysis - Result from analyze-file

   Response:
     Vector of public var definitions

   Example:
     (public-var-definitions {::analysis (analyze-file {...})})
     ;; => [{:name process-hook-event! :arglist-strs [\"[...]\"]}]"
  [{::keys [analysis]}]
  (->> (::var-definitions analysis)
       (remove :private)
       (remove :macro)
       vec))

(defn lint-issues
  "Get lint issues grouped by severity.

   Request keys:
     ::analysis - Result from analyze-file

   Response:
     Map with :errors :warnings :info counts and lists

   Example:
     (lint-issues {::analysis (analyze-file {...})})
     ;; => {:error-count 0 :warning-count 0 :errors [] :warnings []}"
  [{::keys [analysis]}]
  (let [findings (::findings analysis)
        errors (filterv #(= :error (:level %)) findings)
        warnings (filterv #(= :warning (:level %)) findings)
        infos (filterv #(= :info (:level %)) findings)]
    {:error-count (count errors)
     :warning-count (count warnings)
     :info-count (count infos)
     :errors errors
     :warnings warnings
     :info infos}))

;;; ---------------------------------------------------------------------------
;;; Development Helpers (REPL)
;;; ---------------------------------------------------------------------------

(comment
  ;; REPL exploration

  (require '[seon.dev.analysis :as analysis])

  ;; Analyze hook.clj
  (def hook-analysis
    (analysis/analyze-file {::file-path "src/seon/dev/hook.clj"}))

  ;; Check success
  (::success hook-analysis)
  ;; => true

  ;; Get namespace
  (::namespace hook-analysis)
  ;; => seon.dev.hook

  ;; Get timing
  (::duration-ms hook-analysis)
  ;; => ~35-45ms

  ;; List all functions defined in file
  (map :name (::var-definitions hook-analysis))

  ;; Get public functions only
  (map :name (public-var-definitions {::analysis hook-analysis}))

  ;; Get call graph for process-hook-event!
  (callees-of {::analysis hook-analysis
               ::fn-name 'process-hook-event!})

  ;; See what functions process-hook-event! calls
  (->> (::callees (callees-of {::analysis hook-analysis
                               ::fn-name 'process-hook-event!}))
       (map (juxt :to :name))
       distinct
       (sort-by second))

  ;; Find callers of stage-repair
  (callers-of {::analysis hook-analysis
               ::fn-name 'stage-repair})
  ;; => {::callers [process-hook-event!]}

  ;; Get namespace dependencies
  (->> (::namespace-usages hook-analysis)
       (map (juxt :to :alias)))
  ;; => [[clojure.string str] [seon.dev.codebase codebase] ...]

  ;; Check lint issues
  (lint-issues {::analysis hook-analysis})

  ;; Format a file (test)
  (format-file! {::file-path "src/seon/dev/analysis.clj"})

  nil)

(ns seon.fn.analyzer
  "Static Clojure source analysis for program-graph indexing."
  (:require [clj-kondo.core :as clj-kondo]
            [clj-kondo.impl.utils :as kondo.utils]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def ^:private config-directory ".clj-kondo")
(def ^:private cache-directory ".clj-kondo/.cache")

(def ^:private location-keys
  [:row :col :end-row :end-col
   :name-row :name-col :name-end-row :name-end-col])

(def ^:private analysis-config
  {:linters
   ;; clj-kondo's local type inference is useful review feedback, but it is
   ;; not a sound admission proof for database pulls and branch-sensitive
   ;; Malli contracts. Preserve each finding while syntax/name/arity errors
   ;; remain blocking.
   {:type-mismatch {:level :warning}}
   :analysis
   {:arglists true
    :var-usages true
    :keywords true
    :var-definitions {:shallow false
                      :meta true}
    :namespace-definitions {:shallow false
                            :meta true}}})

(def ^:private finding-keys
  [:filename :row :col :end-row :end-col :level :message :type
   :lang :cljc :langs])

(defn- present-values
  [entry selected-keys]
  (into {}
        (keep (fn [selected-key]
                (when-some [value (get entry selected-key)]
                  [(keyword "seon.fn.analyzer" (name selected-key)) value])))
        selected-keys))

(defn- deterministic-arities
  [entry]
  (cond-> entry
    (set? (::fixed-arities entry))
    (update ::fixed-arities (comp vec sort))))

(defn- location
  [entry]
  (present-values entry (into [:filename] location-keys)))

(defn- namespace-definition
  [entry]
  (merge
   (location entry)
   (present-values entry [:name :doc :meta :lang :in-ns])))

(defn- namespace-usage
  [entry]
  (merge
   (location entry)
   (present-values entry [:from :to :alias :lang])))

(defn- var-definition
  [entry]
  (deterministic-arities
   (merge
    (location entry)
    (present-values
     entry
     [:ns :name :defined-by :defined-by->lint-as :arglist-strs :doc :meta
      :private :macro :test :fixed-arities :varargs-min-arity :lang]))))

(defn- var-usage
  [entry]
  (deterministic-arities
   (merge
    (location entry)
    (present-values
     entry
     [:from :from-var :to :name :alias :refer :arity :macro :private
      :fixed-arities :varargs-min-arity :lang]))))

;; clj-kondo's analysis README documents `:keywords` without naming `:from`
;; or `:from-var`, but the implementation emits both for every keyword read
;; inside a var body (probed 2026-08-03 over `src/` + `test/`: 27,863 of
;; 34,968 keyword occurrences carry `:from-var`). The remainder sit in `ns`
;; forms and in var metadata, which clj-kondo attributes to the namespace
;; rather than to the var the metadata decorates.
(defn- keyword-usage
  [entry]
  (merge
   (location entry)
   (present-values
    entry
    [:ns :name :from :from-var :alias :auto-resolved :keys-destructuring
     :lang])))

(defn- entry-order
  [entry]
  [(get entry ::filename "")
   (get entry ::row 0)
   (get entry ::col 0)
   (str (get entry ::from ""))
   (str (get entry ::to ""))
   (str (get entry ::ns ""))
   (str (get entry ::name ""))])

(defn- normalized-entries
  [analysis analysis-key normalize]
  (->> (get analysis analysis-key)
       (map normalize)
       (sort-by entry-order)
       vec))

(defn- jvm-entry? [entry]
  (not= :cljs (::lang entry)))

(defn- finding [entry]
  (present-values entry finding-keys))

(defn- invoke-kondo
  [options]
  (clj-kondo/run!
   (merge {:lang :clj
           :config-dir config-directory
           :cache-dir cache-directory
           :repro true
           :config analysis-config}
          options)))

(defn analyze
  "Analyze complete source roots or individual files without evaluation."
  {:malli/schema
   [:=>
    [:cat
     [:map
      [::paths [:vector {:min 1} [:string {:min 1}]]]]]
    [:map
     [::namespace-definitions [:vector :map]]
     [::namespace-usages [:vector :map]]
     [::var-definitions [:vector :map]]
     [::var-usages [:vector :map]]
     [::keywords [:vector :map]]
     [::findings [:vector :map]]]]}
  [{::keys [paths]}]
  ;; A trusted diagnostic must preserve each call as one coherent record.
  ;; clj-kondo's parallel analysis has combined an outer call's location and
  ;; arity with an inner call's resolved var, then emitted the corruption
  ;; twice. Its sequential path retains the same linters and dependency cache.
  (let [result (invoke-kondo {:lint paths})
        analysis (:analysis result)]
    {::namespace-definitions
     (filterv jvm-entry?
              (normalized-entries analysis :namespace-definitions namespace-definition))
     ::namespace-usages
     (filterv jvm-entry?
              (normalized-entries analysis :namespace-usages namespace-usage))
     ::var-definitions
     (filterv jvm-entry?
              (normalized-entries analysis :var-definitions var-definition))
     ::var-usages
     (filterv jvm-entry?
              (normalized-entries analysis :var-usages var-usage))
     ::keywords
     (filterv jvm-entry?
              (normalized-entries analysis :keywords keyword-usage))
     ::findings
     (->> (:findings result)
          (map finding)
          (filter jvm-entry?)
          (sort-by entry-order)
          vec)}))

(defn- require-specs
  [{:seon.ns/keys [requires aliases refers]}]
  (let [aliases-by-target
        (group-by :seon.ns.alias/target-ns aliases)
        refers-by-target
        (group-by :seon.ns.refer/target-ns refers)
        required-targets
        (into #{}
              (map (fn [target]
                     (if (and (vector? target)
                              (= :seon.ns/name (first target)))
                       (second target)
                       target)))
              requires)
        targets
        (sort-by str
                 (into required-targets
                       (concat (keys aliases-by-target)
                               (keys refers-by-target))))]
    (mapcat
     (fn [target]
       (let [target-aliases
             (sort-by (comp str :seon.ns.alias/local)
                      (get aliases-by-target target))
             refer-rows
             (sort-by (juxt (comp str :seon.ns.refer/target-name)
                            (comp str :seon.ns.refer/local))
                      (get refers-by-target target))
             referred
             (into [] (comp (map :seon.ns.refer/target-name) (distinct))
                   refer-rows)
             renames
             (into (sorted-map-by #(compare (str %1) (str %2)))
                   (keep (fn [{:seon.ns.refer/keys [local target-name]}]
                           (when (not= local target-name)
                             [target-name local])))
                   refer-rows)
             base (cond-> [target]
                    (seq referred) (into [:refer referred])
                    (seq renames) (into [:rename renames]))]
         (if (seq target-aliases)
           (map-indexed
            (fn [index alias-row]
              (cond-> [target :as (:seon.ns.alias/local alias-row)]
                (and (zero? index) (seq referred))
                (into [:refer referred])
                (and (zero? index) (seq renames))
                (into [:rename renames])))
            target-aliases)
           [base])))
     targets)))

(defn- namespace-prelude
  [namespace-name namespace-row]
  (let [requires (vec (require-specs namespace-row))
        imports (->> (:seon.ns/imports namespace-row)
                     (keep :seon.ns.import/target-class)
                     (sort-by str)
                     vec)]
    (pr-str
     (apply list
            (cond-> ['ns namespace-name]
              (seq requires) (conj (apply list :require requires))
              (seq imports) (conj (apply list :import imports)))))))

(defn- stored-arglists
  [serialized]
  (when (string? serialized)
    (try
      (let [arglists (edn/read-string serialized)]
        (when (and (seq arglists) (every? vector? arglists))
          arglists))
      (catch Throwable _
        nil))))

(defn- function-stub
  [{:seon.fn/keys [sym private? arglists]}]
  (let [qualified (symbol sym)
        function-name (symbol (name qualified))
        operation (if private? 'defn- 'defn)]
    (if-let [arglists (stored-arglists arglists)]
      (if (= 1 (count arglists))
        (list operation function-name (first arglists) nil)
        (list* operation function-name
               (map #(list % nil) arglists)))
      ;; Historical rows can honestly lack analyzer arities. Keep those names
      ;; resolvable without claiming a fixed contract the database does not
      ;; contain.
      (list operation function-name '[& arguments] nil))))

(defn- program-prelude
  [available-functions]
  (->> available-functions
       (group-by #(some-> (:seon.fn/sym %) symbol namespace symbol))
       (sort-by (comp str key))
       (mapcat
        (fn [[namespace-name rows]]
          (cons (list 'ns namespace-name)
                (map function-stub (sort-by :seon.fn/sym rows)))))
       (map pr-str)
       (str/join "\n")))

(defn- referenced-program-namespaces
  [namespace-name sources]
  (try
    (into #{namespace-name}
          (comp
           (mapcat #(tree-seq (comp seq :children) :children
                              (kondo.utils/parse-string-all %)))
           (keep :value)
           (filter qualified-symbol?)
           (keep (comp symbol namespace)))
          sources)
    (catch Throwable _
      ;; clj-kondo remains the diagnostic owner for malformed input. A parser
      ;; disagreement must not broaden context or throw out of the agent loop.
      #{namespace-name})))

(defn- newline-count
  [source]
  (count (filter #{\newline} source)))

(defn- source-spans
  [first-row sources]
  (second
   (reduce
    (fn [[row spans] source]
      (let [end-row (+ row (newline-count source))]
        [(inc end-row) (conj spans [row end-row])]))
    [first-row []]
    sources)))

(defn- touches-span?
  [finding-row finding-end-row [source-row source-end-row]]
  (and (<= finding-row source-end-row)
       (<= source-row finding-end-row)))

(defn- relative-finding
  [finding-row source-row]
  (cond-> finding-row
    (::row finding-row) (update ::row - (dec source-row))
    (::end-row finding-row) (update ::end-row - (dec source-row))))

(defn analyze-forms
  "Analyze ordered source forms in one existing namespace context."
  {:malli/schema
   [:=>
    [:cat [:map {:closed true}
           [::namespace-name :symbol]
           [::namespace-row {:optional true} :map]
           [::available-functions {:optional true} [:vector :map]]
           [::sources [:vector {:min 1} :string]]]]
    [:vector
     [:map {:closed true}
      [::source :string]
      [::findings [:vector :map]]]]]}
  [{::keys [namespace-name namespace-row available-functions sources]}]
  (let [referenced-namespaces
        (referenced-program-namespaces namespace-name sources)
        available-functions
        (filterv #(contains? referenced-namespaces
                             (some-> (:seon.fn/sym %)
                                     symbol namespace symbol))
                 available-functions)
        available-namespaces
        (into #{}
              (comp (map :seon.fn/sym)
                    (map symbol)
                    (map namespace)
                    (remove nil?)
                    (map symbol)
                    (remove #{namespace-name}))
              available-functions)
        namespace-row
        (update (or namespace-row {}) :seon.ns/requires
                #(into (set %) available-namespaces))
        prelude (str (program-prelude available-functions)
                     (when (seq available-functions) "\n")
                     (namespace-prelude namespace-name namespace-row))
        first-row (+ 2 (newline-count prelude))
        spans (source-spans first-row sources)
        filename (str (str/replace (str namespace-name) "." "/") ".clj")
        result (with-in-str (str prelude "\n" (str/join "\n" sources))
                 (invoke-kondo {:cache false
                                :lint ["-"]
                                :filename filename}))
        findings (->> (:findings result)
                      (map finding)
                      (sort-by entry-order)
                      vec)]
    (mapv
     (fn [source span]
       {::source source
        ::findings
        (into []
              (comp
               (filter
                (fn [finding-row]
                  (or (nil? (::row finding-row))
                      (touches-span? (::row finding-row)
                                     (or (::end-row finding-row)
                                         (::row finding-row))
                                     span))))
               (map #(relative-finding % (first span))))
              findings)})
     sources spans)))

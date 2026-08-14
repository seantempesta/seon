(ns seon.fn
  "Build-time indexing of the Clojure program graph without evaluation."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [seon.db :as db]
            [seon.fn.analyzer :as analyzer]
            [seon.fn.schema-shape :as schema-shape]
            [seon.program :as program]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.schema.edn :as schema.edn]
            [seon.test.accretion :as accretion])
  (:import [java.nio.file Files]
           [java.security MessageDigest]))

(schema.edn/load! {})

(def source-roots
  "The Clojure source roots admitted to the program graph."
  ["src" "test"])

(defn- report-index-progress!
  [progress! line]
  (when progress!
    (progress! line))
  nil)

(def ^:private progress-line-budget 6)

(defn- progress-stride
  [total line-budget]
  (max 1 (quot (+ total (dec line-budget)) line-budget)))

(defn- require-committed!
  [result phase]
  (when (:seon.error/kind result)
    (throw
     (ex-info "Program indexing transaction was refused."
              {:seon.error/kind ::index-refused
               :seon.fn/index-phase phase
               :seon.fn/transaction-result result :seon.fn/index-refused true})))
  result)

(defn- project-root
  []
  (let [resource (io/resource "seon/fn.clj")]
    (when-not (= "file" (.getProtocol resource))
      (throw
       (ex-info
        "Program indexing requires a source checkout."
        {:seon.error/kind ::index-refused
         ::resource (str resource) :seon.fn/index-refused true})))
    (-> resource
        .toURI
        io/file
        .getParentFile
        .getParentFile
        .getParentFile
        .getCanonicalFile)))

(defn- rooted-file
  [root]
  (let [file (io/file root)]
    (.getCanonicalFile
     (if (.isAbsolute file)
       file
       (io/file (project-root) root)))))

(defn- source-file?
  [file]
  (and (.isFile ^java.io.File file)
       (or (str/ends-with? (.getName ^java.io.File file) ".clj")
           (str/ends-with? (.getName ^java.io.File file) ".cljc"))))

(defn- source-files
  [roots]
  (let [files
        (into []
              (mapcat (fn [root]
                        (->> (file-seq (rooted-file root))
                             (filter source-file?)
                             (sort-by (fn [file]
                                        (.getCanonicalPath
                                         ^java.io.File file))))))
              roots)]
    files))

(defn- many-or-component-attributes
  [attributes]
  (into #{}
        (keep (fn [{:db/keys [ident cardinality isComponent]}]
                (when (or (= :db.cardinality/many cardinality) isComponent)
                  ident)))
        (schema.datahike/malli->datahike-schema (sort attributes))))

(defn- sha-256
  [source-bytes]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") source-bytes)]
    (apply str (map #(format "%02x" (bit-and 0xff %)) digest))))

(defn- file-digest
  [file]
  (sha-256 (Files/readAllBytes (.toPath ^java.io.File file))))

(defn- exact-source [entry]
  (let [text (slurp (::analyzer/filename entry))
        line-starts
        (loop [matcher (re-matcher #"\n" text) starts [0]]
          (if (.find matcher)
            (recur matcher (conj starts (inc (.start matcher))))
            starts))
        row (::analyzer/row entry)
        col (::analyzer/col entry)
        end-row (::analyzer/end-row entry)
        end-col (::analyzer/end-col entry)]
    (when-not (every? some? [row col end-row end-col])
      (throw (ex-info "Static declaration has no exact source span."
                      {:seon.error/kind ::index-refused
                       ::analysis-entry entry :seon.fn/index-refused true})))
    (subs text
          (+ (nth line-starts (dec row)) (dec col))
          (+ (nth line-starts (dec end-row)) (dec end-col)))))

(defn- read-jvm-form [source]
  (binding [*read-eval* false]
    (read {:read-cond :allow :features #{:clj}}
          (java.io.PushbackReader. (java.io.StringReader. source)))))

(defn- import-bindings [spec]
  (cond
    (symbol? spec)
    [[(symbol (name spec)) spec]]

    (and (sequential? spec) (symbol? (first spec)))
    (map (fn [class-name]
           [class-name (symbol (str (first spec) "." class-name))])
         (rest spec))

    :else []))

(defn- namespace-ref
  [namespace-name]
  [:seon.ns/name namespace-name])

(defn- namespace-context [entry]
  (let [form (read-jvm-form (exact-source entry))]
    (reduce
     (fn [context clause]
       (case (first clause)
         :require
         (reduce
          (fn [context spec]
            (if-not (and (vector? spec) (symbol? (first spec)))
              context
              (let [target (first spec)
                    options (apply hash-map (rest spec))
                    require-alias (or (:as options) (:as-alias options))
                    renames (or (:rename options) {})
                    referred (if (vector? (:refer options))
                               (:refer options) [])]
                (cond-> (update context :requires conj target)
                  require-alias (assoc-in [:aliases require-alias] target)
                  (seq referred)
                  (update :refers into
                          (map (fn [target-name]
                                 [(get renames target-name target-name)
                                  (symbol (str target) (str target-name))]))
                          referred)))))
          context
          (rest clause))

         :import
         (update context :imports into (mapcat import-bindings (rest clause)))

         context))
     {:aliases {} :refers {} :imports {} :requires #{}}
     (filter seq? (drop 2 form)))))

(defn- qualify-schema-symbols [form {:keys [aliases refers]}]
  (walk/postwalk
   (fn [value]
     (if-not (symbol? value)
       value
       (if-let [symbol-ns (namespace value)]
         (if-let [target (get aliases (symbol symbol-ns))]
           (symbol (str target) (name value))
           value)
         (or (get refers value)
             (when (ns-resolve 'clojure.core value)
               (symbol "clojure.core" (name value)))
             value))))
   form))

(defn- namespace-row [entry]
  (let [namespace-name (::analyzer/name entry)
        {:keys [aliases refers imports requires]} (namespace-context entry)]
    (cond-> {:seon.ns/name namespace-name
             :seon.ns/source (exact-source entry)}
      (::analyzer/doc entry) (assoc :seon.ns/doc (::analyzer/doc entry))
      (seq requires)
      (assoc :seon.ns/requires (into #{} (map namespace-ref) requires))
      (seq aliases)
      (assoc :seon.ns/aliases
             (into #{} (map (fn [[local target]]
                              {:seon.ns.alias/local local
                               :seon.ns.alias/target-ns target})) aliases))
      (seq refers)
      (assoc :seon.ns/refers
             (into #{} (map (fn [[local target]]
                              {:seon.ns.refer/local local
                               :seon.ns.refer/target-ns (symbol (namespace target))
                               :seon.ns.refer/target-name (symbol (name target))})) refers))

      (seq imports)
      (assoc :seon.ns/imports
             (into #{} (map (fn [[local target]]
                              {:seon.ns.import/local local
                               :seon.ns.import/target-class target})) imports)))))

(defn- first-party-function-symbols
  [analysis]
  (into #{}
        (comp
         (filter #(and (seq (::analyzer/arglist-strs %))
                       (not (::analyzer/macro %))))
         (map #(str (symbol (str (::analyzer/ns %))
                            (str (::analyzer/name %))))))
        (::analyzer/var-definitions analysis)))

(defn- call-targets-by-caller
  [analysis first-party-functions]
  (reduce
   (fn [calls usage]
     (let [caller
           (when (and (::analyzer/from usage) (::analyzer/from-var usage))
             (str (symbol (str (::analyzer/from usage))
                          (str (::analyzer/from-var usage)))))
           target
           (when (and (::analyzer/to usage) (::analyzer/name usage))
             (str (symbol (str (::analyzer/to usage))
                          (str (::analyzer/name usage)))))]
       (if (and (contains? usage ::analyzer/arity)
                (contains? first-party-functions caller)
                (contains? first-party-functions target))
         (update calls caller (fnil conj #{}) target)
         calls)))
   {}
   (::analyzer/var-usages analysis)))

(defn- keywords-by-holder
  "Qualified keywords read literally inside each analyzed declaration body.

  clj-kondo resolves `::kw` and `::alias/kw` to their real namespaces before
  reporting them, so the projection is the keyword an editor would see, not
  the source text. Unqualified keywords are discarded: they are `:keys`
  destructuring, option names, and `cond` branches rather than declared
  attributes, and they carried 4,821 of the 19,469 measured edges without
  naming anything the schema registry owns."
  [analysis]
  (reduce
   (fn [used entry]
     (let [holder
           (when (and (::analyzer/from entry) (::analyzer/from-var entry))
             (str (symbol (str (::analyzer/from entry))
                          (str (::analyzer/from-var entry)))))
           keyword-namespace (::analyzer/ns entry)
           keyword-name (::analyzer/name entry)]
       (if (and holder keyword-namespace keyword-name)
         (update used holder (fnil conj #{})
                 (keyword (str keyword-namespace) (str keyword-name)))
         used)))
   {}
   (::analyzer/keywords analysis)))

;; A SET, because cardinality-many is a set by construction
;; (`reference-code/datahike/src/datahike/index/persistent_set.cljc:133`).
(defn- keyword-values
  [keywords-by-holder qualified]
  (when-let [used (seq (get keywords-by-holder (str qualified)))]
    (into (sorted-set) used)))

(defn- test-subject
  [metadata]
  (when-let [subject (:seon.test/subject metadata)]
    (when (or (qualified-symbol? subject)
              (and (string? subject)
                   (qualified-symbol? (symbol subject))))
      [:seon.fn/sym (str subject)])))

;; Emit one fact per keyword. Datahike reads a two-element collection beginning
;; with a unique-identity attribute as one lookup ref
;; (`reference-code/datahike/src/datahike/db/transaction.cljc:717-735`). The
;; honest two-edge set on `seon.ai/agent-overlay` therefore cannot travel as a
;; collection-valued entity-map entry: its two analyzer-produced keywords are
;; two independent cardinality-many facts.
(defn- keyword-facts
  [row]
  (let [program-identity (program/row-identity row)]
    (mapv (fn [used]
            [:db/add program-identity :seon.fn/keywords used])
          (:seon.fn/keywords row))))

(defn- capability-symbol
  [value]
  (if (and (seq? value)
           (= 'quote (first value))
           (nil? (next (next value))))
    (second value)
    value))

(defn- var-row [analysis calls-by-caller used-keywords entry]
  (let [namespace-name (::analyzer/ns entry)
        qualified (symbol (str namespace-name) (str (::analyzer/name entry)))
        metadata (::analyzer/meta entry)
        namespace-entry
        (first (filter #(= namespace-name (::analyzer/name %))
                       (::analyzer/namespace-definitions analysis)))
        source (exact-source entry)
        external-sink (:seon.fn/external-sink metadata)
        projection-boundary (:seon.fn/projection-boundary metadata)
        capability-declared? (contains? metadata :seon.effect/capability)
        capability (capability-symbol
                    (:seon.effect/capability metadata))]
    (when (and capability-declared? (not (qualified-symbol? capability)))
      (throw
       (ex-info
        "A capability marker must name one qualified handler symbol."
        {:seon.error/kind ::index-refused
         :seon.fn/capability-rule :invalid-handler-symbol
         :seon.fn/sym (str qualified)
         :seon.effect/capability capability :seon.fn/index-refused true})))
    (cond
      (::analyzer/test entry)
      (cond-> {:seon.test/sym (str qualified)
               :seon.test/ns [:seon.ns/name namespace-name]
               :seon.test/source source}
        (true? (:seon.test/usage metadata))
        (assoc :seon.test/usage true)
        (seq (get calls-by-caller (str qualified)))
        (assoc :seon.fn/calls
               (mapv (fn [target] [:seon.fn/sym target])
                     (sort (get calls-by-caller (str qualified)))))
        (keyword-values used-keywords qualified)
        (assoc :seon.fn/keywords (keyword-values used-keywords qualified))
        (test-subject metadata)
        (assoc :seon.test/subject (test-subject metadata)))

      (and (seq (::analyzer/arglist-strs entry))
           (not (::analyzer/macro entry)))
      (cond-> {:seon.fn/sym (str qualified)
               :seon.fn/ns [:seon.ns/name namespace-name]
               :seon.fn/source source
               :seon.fn/arglists (str "(" (str/join " " (::analyzer/arglist-strs entry)) ")")
               :seon.fn/private? (boolean (::analyzer/private entry))}
        (::analyzer/doc entry) (assoc :seon.fn/doc (::analyzer/doc entry))
        (:malli/schema metadata)
        (assoc :seon.fn/spec
               (pr-str (schema/canonical-definition
                        (qualify-schema-symbols
                         (:malli/schema metadata)
                        (namespace-context namespace-entry))
                        {})))
        (seq (get calls-by-caller (str qualified)))
        (assoc :seon.fn/calls
               (mapv (fn [target] [:seon.fn/sym target])
                     (sort (get calls-by-caller (str qualified)))))
        (keyword-values used-keywords qualified)
        (assoc :seon.fn/keywords (keyword-values used-keywords qualified))
        (test-subject metadata)
        (assoc :seon.test/subject (test-subject metadata))
        (contains? #{:io :compute} (:seon.workload metadata))
        (assoc :seon.fn/workload (:seon.workload metadata))
        (contains? #{:ai-visible-text :html-response :codec-storage}
                   external-sink)
        (assoc :seon.fn/external-sink external-sink)
        (contains? #{:seon.render/ai :seon.render/html :none}
                   projection-boundary)
        (assoc :seon.fn/projection-boundary projection-boundary)
        capability-declared?
        (assoc :seon.effect/capability capability))

      :else nil)))

(defn- stored-arglists
  [serialized]
  (when (string? serialized)
    (try
      (let [arglists (edn/read-string serialized)]
        (when (and (seq arglists) (every? vector? arglists))
          arglists))
      (catch Throwable _ nil))))

(defn- analysis-function-stub
  [{:seon.fn/keys [sym private? arglists]}]
  (let [qualified (symbol sym)
        function-name (symbol (name qualified))
        operation (if private? 'defn- 'defn)
        arglists (stored-arglists arglists)]
    (if (= 1 (count arglists))
      (list operation function-name (first arglists) nil)
      (list* operation function-name
             (or (map #(list % nil) arglists)
                 [(list '[& arguments] nil)])))))

(defn- analysis-program-prelude
  [function-rows]
  (->> function-rows
       (group-by #(some-> (:seon.fn/sym %) symbol namespace symbol))
       (sort-by (comp str key))
       (mapcat (fn [[namespace-name rows]]
                 (cons (list 'ns namespace-name)
                       (map analysis-function-stub
                            (sort-by :seon.fn/sym rows)))))
       (map pr-str)
       (str/join "\n")))

(defn- runtime-require-specs
  [{:seon.ns/keys [requires aliases refers]}]
  (let [aliases-by-target (group-by :seon.ns.alias/target-ns aliases)
        refers-by-target (group-by :seon.ns.refer/target-ns refers)
        targets
        (sort-by str
                 (into (set (map :seon.ns/name requires))
                       (concat (keys aliases-by-target)
                               (keys refers-by-target))))]
    (mapcat
     (fn [target]
       (let [target-aliases
             (sort-by (comp str :seon.ns.alias/local)
                      (get aliases-by-target target))
             referred
             (->> (get refers-by-target target)
                  (map :seon.ns.refer/target-name)
                  distinct
                  (sort-by str)
                  vec)
             base (cond-> [target]
                    (seq referred) (into [:refer referred]))]
         (if (seq target-aliases)
           (map-indexed
            (fn [index alias-row]
              (cond-> [target :as (:seon.ns.alias/local alias-row)]
                (and (zero? index) (seq referred))
                (into [:refer referred])))
            target-aliases)
           [base])))
     targets)))

(defn- runtime-namespace-form
  [namespace-name namespace-row function-rows]
  ;; SCI makes every program-graph function callable. clj-kondo resolves a
  ;; qualified call only when that namespace is in the current `ns` form, so
  ;; the analysis-only form derives a bare require from every function row.
  ;; The agent's real aliases/refers/imports stay intact, and none of these
  ;; synthetic requires become durable namespace facts.
  (let [program-requires
        (into #{}
              (comp
               (keep (fn [row]
                       (some-> (:seon.fn/sym row)
                               symbol
                               namespace
                               symbol)))
               (remove #{namespace-name})
               (map (fn [required-name]
                      {:seon.ns/name required-name})))
              function-rows)
        requires
        (vec (runtime-require-specs
              (update namespace-row :seon.ns/requires
                      #(into program-requires %))))
        imports (->> (:seon.ns/imports namespace-row)
                     (keep :seon.ns.import/target-class)
                     (sort-by str)
                     vec)]
    (apply list
           (cond-> ['ns namespace-name]
             (seq requires) (conj (apply list :require requires))
             (seq imports) (conj (apply list :import imports))))))

(defn- runtime-function-rows
  [database]
  (mapv #(db/pull database
                  [:seon.fn/sym :seon.fn/private? :seon.fn/arglists]
                  %)
        (sort (db/q '[:find [?function ...]
                      :where [?function :seon.fn/sym]]
                    database))))

(defn- runtime-analysis
  [database namespace-name source]
  (let [namespace-row
        (db/pull database
                 '[:seon.ns/name
                   {:seon.ns/requires [:seon.ns/name]}
                   {:seon.ns/aliases [*]}
                   {:seon.ns/imports [*]}
                   {:seon.ns/refers [*]}]
                 [:seon.ns/name namespace-name])
        function-rows (runtime-function-rows database)
        prelude (analysis-program-prelude function-rows)
        namespace-source (pr-str (runtime-namespace-form namespace-name
                                                         namespace-row
                                                         function-rows))
        prefix (str prelude (when (seq prelude) "\n") namespace-source "\n")
        first-source-row (inc (count (filter #{\newline} prefix)))
        analysis
        (with-in-str (str prefix source)
          (analyzer/analyze {::analyzer/paths ["-"]}))]
    {:seon.fn/analysis analysis
     :seon.fn/first-source-row first-source-row
     :seon.fn/function-rows function-rows}))

(defn- usage-target
  [usage]
  (when (and (contains? usage ::analyzer/arity)
             (::analyzer/to usage)
             (::analyzer/name usage))
    (str (symbol (str (::analyzer/to usage))
                 (str (::analyzer/name usage))))))

(defn- form-calls
  [analysis first-source-row first-party-functions]
  (into (sorted-set)
        (comp
         (filter #(>= (long (or (::analyzer/row %) 0)) first-source-row))
         (keep usage-target)
         (filter first-party-functions)
         (map (fn [target] [:seon.fn/sym target])))
        (::analyzer/var-usages analysis)))

(defn- form-keywords
  [analysis first-source-row]
  (into (sorted-set)
        (comp
         (filter #(>= (long (or (::analyzer/row %) 0)) first-source-row))
         (keep (fn [entry]
                 (when (and (::analyzer/ns entry) (::analyzer/name entry))
                   (keyword (str (::analyzer/ns entry))
                            (str (::analyzer/name entry)))))))
        (::analyzer/keywords analysis)))

(defn analyze-form
  "Analyze one planned form through the static program-graph owner."
  {:malli/schema
   [:=>
    [:catn
     [:database :seon.db/database-value]
     [:source :seon.cluster.run.form/source]
     [:namespace-ref :seon.cluster.run.form/ns]
     [:program-row [:maybe :seon.program/row]]]
    [:tuple
     [:map
      [:seon.fn/calls {:optional true} :seon.fn/calls]
      [:seon.fn/keywords {:optional true} :seon.fn/keywords]
      [:seon.test/subject {:optional true} :seon.test/subject]]
     [:maybe :seon.program/row]]]}
  [database source namespace-ref program-row]
  (let [namespace-name (:seon.ns/name
                        (db/pull database [:seon.ns/name] namespace-ref))
        {:seon.fn/keys [analysis first-source-row function-rows]}
        (runtime-analysis database namespace-name source)
        program-symbol (or (:seon.fn/sym program-row)
                           (:seon.test/sym program-row))
        first-party-functions
        (cond-> (into #{} (map :seon.fn/sym) function-rows)
          program-symbol (conj program-symbol))
        calls-by-caller
        (call-targets-by-caller analysis first-party-functions)
        used-keywords (keywords-by-holder analysis)
        program-facts
        (when program-symbol
          (let [qualified (symbol program-symbol)
                definition
                (some #(when (= program-symbol
                                (str (symbol (str (::analyzer/ns %))
                                             (str (::analyzer/name %)))))
                         %)
                      (::analyzer/var-definitions analysis))
                subject (or (:seon.test/subject program-row)
                            (test-subject (::analyzer/meta definition)))]
            (cond-> {}
              (seq (get calls-by-caller program-symbol))
              (assoc :seon.fn/calls
                     (mapv (fn [target] [:seon.fn/sym target])
                           (sort (get calls-by-caller program-symbol))))
              (keyword-values used-keywords qualified)
              (assoc :seon.fn/keywords
                     (keyword-values used-keywords qualified))
              subject (assoc :seon.test/subject subject))))
        form-facts
        (cond-> {}
          (seq (form-calls analysis first-source-row first-party-functions))
          (assoc :seon.fn/calls
                 (form-calls analysis first-source-row first-party-functions))
          (seq (form-keywords analysis first-source-row))
          (assoc :seon.fn/keywords
                 (form-keywords analysis first-source-row))
          (:seon.test/subject program-facts)
          (assoc :seon.test/subject (:seon.test/subject program-facts)))]
    [form-facts (when program-row (merge program-row program-facts))]))

(def ^:private load-refusal-finding-types
  #{:syntax
    :unresolved-symbol
    :unresolved-namespace
    :unresolved-var
    :private-call
    :invalid-arity})

(defn- load-refusal-finding?
  [finding]
  (contains? load-refusal-finding-types (::analyzer/type finding)))

(defn- publication-findings
  [analysis]
  (mapv #(cond-> %
           (not (load-refusal-finding? %))
           (assoc ::analyzer/level :warning))
        (::analyzer/findings analysis)))

(defn- blocking-findings
  [analysis]
  (filterv load-refusal-finding? (::analyzer/findings analysis)))

(defn- assert-clean-analysis!
  [analysis]
  (when (seq (blocking-findings analysis))
    (throw (ex-info "Static program analysis found blocking errors."
                    {:seon.error/kind ::index-refused
                     ::findings (publication-findings analysis) :seon.fn/index-refused true}))))

(defn- analysis-rows-by-file
  [analysis first-party-functions]
  (let [calls-by-caller
        (call-targets-by-caller analysis first-party-functions)
        used-keywords (keywords-by-holder analysis)]
    (reduce
     (fn [rows entry]
       (if-let [row (if (::analyzer/ns entry)
                      (var-row analysis calls-by-caller used-keywords entry)
                      (namespace-row entry))]
         (update rows (::analyzer/filename entry) (fnil conj []) row)
         rows))
     {}
     (concat (::analyzer/namespace-definitions analysis)
             (::analyzer/var-definitions analysis)))))

(defn- artifact
  [file rows findings]
  (let [canonical-path (.getCanonicalPath ^java.io.File file)
        canonical-rows (mapv program/canonical-row rows)]
    (cond->
     {:seon.fn.file/path canonical-path
      :seon.fn.file/digest (file-digest file)
      :seon.fn.file/rows canonical-rows
      :seon.fn.file/identities
      (->> canonical-rows
           (keep program/row-identity)
           (sort-by pr-str)
           vec)}
      (seq findings) (assoc :seon.fn.file/findings findings))))

(def ^:private request-symbol "seon.effect/request!")

(def ^:private test-reach-rules
  '[[(function-reaches ?function ?target)
     [?function :seon.fn/calls ?target]]
    [(function-reaches ?function ?target)
     [?function :seon.fn/calls ?called]
     (function-reaches ?called ?target)]
    [(test-reaches ?test ?target)
     [?test :seon.test/sym]
     [?test :seon.fn/calls ?target]]
    [(test-reaches ?test ?target)
     [?test :seon.test/sym]
     [?test :seon.fn/calls ?called]
     (function-reaches ?called ?target)]
    [(test-reaches ?test ?target)
     [?test :seon.test/sym]
     [?test :seon.test/subject ?target]]
    [(test-reaches ?test ?target)
     [?test :seon.test/sym]
     [?test :seon.test/subject ?subject]
     (function-reaches ?subject ?target)]
    [(test-gates-symbol ?test ?target ?target-symbol)
     (test-reaches ?test ?target)]
    [(test-gates-symbol ?test ?target ?target-symbol)
     [?test :seon.test/pending-subject ?target-symbol]]
    [(test-currently-failing ?test)
     [?test :seon.test/fail-count ?count]
     [(pos? ?count)]]
    [(test-currently-failing ?test)
     [?test :seon.test/error-count ?count]
     [(pos? ?count)]]])

(defn gate-set
  "Tests gating one function identity from one database value.

  Includes direct and transitive call reachability, resolved explicit
  subjects, and test-first rows whose pending subject names this identity."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.fn/sym]
                  [:vector :seon.test/sym]]}
  [database function-symbol]
  (let [target (or (:db/id (db/pull database [:db/id]
                                    [:seon.fn/sym function-symbol]))
                   -1)]
    (->> (db/q '[:find [?test-symbol ...]
                 :in $ % ?target ?target-symbol
                 :where
                 (test-gates-symbol ?test ?target ?target-symbol)
                 [?test :seon.test/sym ?test-symbol]]
               database test-reach-rules target function-symbol)
         sort
         vec)))

(defn tests-reaching
  "Compatibility spelling for the shared gate-set derivation."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.fn/sym]
                  [:vector :seon.test/sym]]}
  [database function-symbol]
  (gate-set database function-symbol))

(defn currently-failing-functions
  "Function symbols reached by a test whose latest committed result is red.

  A rerun replaces the test row's result facts, so this query answers current
  state without choosing a latest run from a separate history graph."
  {:malli/schema [:=> [:cat :seon.db/database-value]
                  [:vector :seon.fn/sym]]}
  [database]
  (->> (db/q '[:find [?function-symbol ...]
               :in $ %
               :where
               (test-currently-failing ?test)
               (test-reaches ?test ?function)
               [?function :seon.fn/sym ?function-symbol]]
             database test-reach-rules)
       sort
       vec))

(defn functions-without-tests
  "Indexed public functions reached by no indexed test.

  Coverage is the `test-reaches` graph derivation used by `tests-reaching`;
  this is set difference over query results, never a maintained roster."
  {:malli/schema [:=> [:cat :seon.db/database-value]
                  [:vector :seon.fn/sym]]}
  [database]
  (let [public-functions
        (set (db/q '[:find [?function-symbol ...]
                    :where
                    [?function :seon.fn/sym ?function-symbol]
                    [?function :seon.fn/private? false]]
                  database))
        tested-functions
        (set (db/q '[:find [?function-symbol ...]
                    :in $ %
                    :where
                    (test-reaches ?test ?function)
                    [?function :seon.fn/sym ?function-symbol]
                    [?function :seon.fn/private? false]]
                  database test-reach-rules))]
    (->> (set/difference public-functions tested-functions)
         sort
         vec)))

(defn functions-using
  "Function symbols whose indexed source reads `keyword` literally.

  This is the query the program graph exists to answer: an attribute's
  consumers are found by asking the database, never by maintaining a list.
  Membership is literal usage only — a caller that builds the keyword at
  runtime is absent, so an empty result means \"no declaration names it\",
  not \"nothing reaches it\"."
  {:malli/schema [:=> [:cat :seon.db/database-value :qualified-keyword]
                  [:vector :seon.fn/sym]]}
  [database keyword]
  (->> (db/q '[:find [?function-symbol ...]
               :in $ ?keyword
               :where
               [?function :seon.fn/keywords ?keyword]
               [?function :seon.fn/sym ?function-symbol]]
             database keyword)
       sort
       vec))

(def ^:private required-projection-by-sink
  {:ai-visible-text :seon.render/ai
   :html-response :seon.render/html
   :codec-storage :none})

(def ^:private visible-projections
  [:seon.render/ai :seon.render/html])

(defn- output-graph
  [database]
  (let [functions
        (->> (db/q '[:find [?symbol ...]
                     :where [_ :seon.fn/sym ?symbol]]
                   database)
             sort
             vec)
        calls
        (reduce
         (fn [by-caller [caller called]]
           (update by-caller caller (fnil conj []) called))
         {}
         (sort
          (db/q '[:find ?caller-symbol ?called-symbol
                  :where
                  [?caller :seon.fn/sym ?caller-symbol]
                  [?caller :seon.fn/calls ?called]
                  [?called :seon.fn/sym ?called-symbol]]
                database)))
        sinks
        (into {}
              (db/q '[:find ?symbol ?sink
                      :where
                      [?function :seon.fn/sym ?symbol]
                      [?function :seon.fn/external-sink ?sink]]
                    database))
        boundaries
        (into {}
              (db/q '[:find ?symbol ?boundary
                      :where
                      [?function :seon.fn/sym ?symbol]
                      [?function :seon.fn/projection-boundary ?boundary]]
                    database))]
    {:seon.fn.output.graph/functions functions
     :seon.fn.output.graph/calls calls
     :seon.fn.output.graph/sinks sinks
     :seon.fn.output.graph/boundaries boundaries}))

(defn- advance-output-state
  [state function-symbol boundary]
  (case boundary
    :none
    (-> state
        (update :seon.fn.output.state/seen conj :none)
        (update :seon.fn.output.state/bypassed
                into
                (remove (:seon.fn.output.state/seen state)
                        visible-projections))
        (update :seon.fn.output.state/first-bypass
                (fn [first-bypass]
                  (reduce
                   (fn [result required]
                     (if (or (contains? (:seon.fn.output.state/seen state)
                                        required)
                             (contains? result required))
                       result
                       (assoc result required function-symbol)))
                   first-bypass
                   visible-projections))))

    (:seon.render/ai :seon.render/html)
    (update state :seon.fn.output.state/seen conj boundary)

    state))

(defn- output-classification
  [state external-sink]
  (let [required (get required-projection-by-sink external-sink)
        seen (:seon.fn.output.state/seen state)
        bypassed (:seon.fn.output.state/bypassed state)]
    (cond
      (and (= :codec-storage external-sink)
           (contains? seen :none))
      :codec

      (contains? bypassed required)
      :bypass

      (contains? seen required)
      :projected

      :else
      :unresolved)))

(defn- source-output-paths
  [graph source]
  (let [calls (:seon.fn.output.graph/calls graph)
        sinks (:seon.fn.output.graph/sinks graph)
        boundaries (:seon.fn.output.graph/boundaries graph)
        initial-state
        {:seon.fn.output.state/seen #{}
         :seon.fn.output.state/bypassed #{}
         :seon.fn.output.state/first-bypass {}}]
    (loop [pending
           (conj clojure.lang.PersistentQueue/EMPTY
                 {:seon.fn.output.walk/function source
                  :seon.fn.output.walk/path [source]
                  :seon.fn.output.walk/state initial-state})
           visited #{}
           reports {}]
      (if (empty? pending)
        (vals reports)
        (let [{function-symbol :seon.fn.output.walk/function
               path :seon.fn.output.walk/path
               state :seon.fn.output.walk/state}
              (peek pending)
              pending (pop pending)
              state (advance-output-state
                     state function-symbol (get boundaries function-symbol))
              visit-key
              [function-symbol
               (:seon.fn.output.state/seen state)
               (:seon.fn.output.state/bypassed state)]]
          (if (contains? visited visit-key)
            (recur pending visited reports)
            (if-let [external-sink (get sinks function-symbol)]
              (let [required (get required-projection-by-sink external-sink)
                    classification (output-classification state external-sink)
                    report-key [function-symbol classification]
                    report
                    (cond->
                     {:seon.fn.output/source source
                      :seon.fn.output/sink function-symbol
                      :seon.fn.output/external-sink external-sink
                      :seon.fn.output/required-projection required
                      :seon.fn.output/classification classification
                      :seon.fn.output/path path}
                      (= :bypass classification)
                      (assoc :seon.fn.output/first-bypass
                             (get-in state
                                     [:seon.fn.output.state/first-bypass
                                      required])))]
                (recur pending
                       (conj visited visit-key)
                       (if (contains? reports report-key)
                         reports
                         (assoc reports report-key report))))
              (recur
               (reduce
                (fn [queue called]
                  (conj queue
                        {:seon.fn.output.walk/function called
                         :seon.fn.output.walk/path (conj path called)
                         :seon.fn.output.walk/state state}))
                pending
                (get calls function-symbol []))
               (conj visited visit-key)
               reports))))))))

(defn- shortest-call-path
  [calls source target]
  (loop [pending (conj clojure.lang.PersistentQueue/EMPTY [source])
         visited #{}]
    (when-not (empty? pending)
      (let [path (peek pending)
            pending (pop pending)
            current (peek path)]
        (cond
          (= current target) path
          (contains? visited current) (recur pending visited)
          :else
          (recur (reduce (fn [queue called]
                           (conj queue (conj path called)))
                         pending
                         (get calls current []))
                 (conj visited current)))))))

(defn- text-boundary-report
  [graph]
  (let [functions (:seon.fn.output.graph/functions graph)
        calls (:seon.fn.output.graph/calls graph)
        target "seon.print/bounded-text"
        render-seam "seon.print/fit"
        admission-seam "seon.sci.admit/admit"
        authorized-callers #{"seon.print/admit-string"
                             "seon.print/fit-text"}
        callers
        (->> calls
             (keep (fn [[caller callees]]
                     (when (some #{target} callees) caller)))
             sort
             vec)
        target-found?
        (boolean
         (or (some #{target} functions)
             (some #(some #{target} %) (vals calls))))]
    {:seon.fn.output/text-boundary-target-found?
     target-found?
     :seon.fn.output/text-boundary-callers callers
     :seon.fn.output/text-boundary-render-path
     (vec (or (shortest-call-path calls render-seam target) []))
     :seon.fn.output/text-boundary-admission-path
     (vec (or (shortest-call-path calls admission-seam target) []))
     :seon.fn.output/text-boundary-bypasses
     (->> callers
          (remove authorized-callers)
          sort
          vec)}))

(defn output-path-report
  "Classified external-sink reachability with shortest path evidence.

  One shortest representative is retained for each source, sink, and
  classification. A visible path is `:projected` only when its required
  projection occurs before any `:none` value-to-text boundary. A `:none`
  boundary before projection is a `:bypass`; a visible sink with neither is
  `:unresolved`. Codec paths require and cross `:none` by construction.

  This is the transition diagnostic, not the graduation assertion. The final
  universal-output-floor ladder step asserts zero bypasses and unresolved
  paths after every crossing has been converted and declared."
  {:malli/schema [:=> [:cat :seon.db/database-value]
                  :seon.fn.output/report]}
  [database]
  (let [graph (output-graph database)
        paths
        (->> (:seon.fn.output.graph/functions graph)
             (mapcat #(source-output-paths graph %))
             (sort-by (juxt :seon.fn.output/source
                            :seon.fn.output/sink
                            :seon.fn.output/classification
                            :seon.fn.output/path))
             vec)
        classification-counts
        (frequencies (map :seon.fn.output/classification paths))
        sink-counts (frequencies (map :seon.fn.output/external-sink paths))]
    {:seon.fn.output/totals
     {:seon.fn.output/sinks
      (count (:seon.fn.output.graph/sinks graph))
      :seon.fn.output/ai-paths (get sink-counts :ai-visible-text 0)
      :seon.fn.output/html-paths (get sink-counts :html-response 0)
      :seon.fn.output/codec-paths (get sink-counts :codec-storage 0)
      :seon.fn.output/projected (get classification-counts :projected 0)
      :seon.fn.output/unresolved (get classification-counts :unresolved 0)
     :seon.fn.output/bypasses (get classification-counts :bypass 0)}
     :seon.fn.output/paths paths
     :seon.fn.output/text-boundary (text-boundary-report graph)}))

(defn- capability-refused!
  [rule function-symbol data]
  (throw
   (ex-info
    "The declared capability graph is malformed."
    (merge {:seon.error/kind ::index-refused
            :seon.fn/capability-rule rule
            :seon.fn/sym function-symbol :seon.fn/index-refused true}
           data))))

(defn- reaches?
  [rows-by-symbol root target]
  (loop [pending [root]
         visited #{}]
    (if-let [function-symbol (first pending)]
      (cond
        (= target function-symbol) true
        (contains? visited function-symbol)
        (recur (subvec pending 1) visited)
        :else
        (let [called (mapv second
                           (:seon.fn/calls
                            (get rows-by-symbol function-symbol)))]
          (recur (into (subvec pending 1) called)
                 (conj visited function-symbol))))
      false)))

(defn- assert-capability-contracts!
  [artifacts]
  (let [function-rows
        (into []
              (comp (mapcat :seon.fn.file/rows)
                    (filter :seon.fn/sym))
              artifacts)
        rows-by-symbol (into {} (map (juxt :seon.fn/sym identity)) function-rows)
        marked (sort-by :seon.fn/sym
                        (filter :seon.effect/capability function-rows))]
    (doseq [{function-symbol :seon.fn/sym
             workload :seon.fn/workload}
            marked]
      (cond
        (nil? workload)
        (capability-refused! :marker-without-workload function-symbol {})

        (not= :io workload)
        (capability-refused! :capability-workload-not-io function-symbol
                             {:seon.fn/workload workload})))
    (doseq [{function-symbol :seon.fn/sym
             handler-symbol :seon.effect/capability}
            marked]
      (let [handler (get rows-by-symbol (str handler-symbol))]
        (cond
          (nil? handler)
          (capability-refused! :missing-handler function-symbol
                               {:seon.effect/capability handler-symbol})

          (not (:seon.fn/private? handler))
          (capability-refused! :public-handler function-symbol
                               {:seon.effect/capability handler-symbol})

          (nil? (:seon.fn/spec handler))
          (capability-refused! :unschemaed-handler function-symbol
                               {:seon.effect/capability handler-symbol})

          (:seon.effect/capability handler)
          (capability-refused! :capability-handler function-symbol
                               {:seon.effect/capability handler-symbol}))))
    (doseq [{function-symbol :seon.fn/sym
             capability :seon.effect/capability
             calls :seon.fn/calls}
            (sort-by :seon.fn/sym function-rows)]
      (when (and (nil? capability)
                 (some #(= request-symbol (second %)) calls))
        (capability-refused! :unmarked-request function-symbol {})))
    (doseq [{function-symbol :seon.fn/sym} marked]
      (when-not (reaches? rows-by-symbol function-symbol request-symbol)
        (capability-refused! :capability-without-request function-symbol {})))
    artifacts))

(defn build-artifact
  "Build one deterministic first-party file projection."
  {:malli/schema
   [:=>
    [:cat [:map
           [:seon.fn.file/path [:string {:min 1}]]
           [:seon.fn.file/first-party-functions
            [:vector [:string {:min 1}]]]]]
    :seon.fn.file/artifact]}
  [{path :seon.fn.file/path
    known-functions :seon.fn.file/first-party-functions}]
  (let [file (rooted-file path)]
    (when-not (source-file? file)
      (throw (ex-info "A file artifact requires one existing Clojure file."
                      {:seon.error/kind ::index-refused
                       :seon.fn.file/path (.getCanonicalPath file) :seon.fn/index-refused true})))
    (let [canonical-path (.getCanonicalPath file)
          analysis (analyzer/analyze {::analyzer/paths [canonical-path]})
          findings (publication-findings analysis)
          first-party-functions
          (into (set known-functions)
                (first-party-function-symbols analysis))]
      (assert-clean-analysis! analysis)
      (artifact file
                (get (analysis-rows-by-file analysis first-party-functions)
                     canonical-path
                     [])
                findings))))

(defn artifact-by-path
  "The manifest artifact carrying one canonical file path."
  {:malli/schema
   [:=>
    [:catn
     [:manifest :seon.fn.manifest/manifest]
     [:canonical-path [:string {:min 1}]]]
    [:maybe :seon.fn.file/artifact]]}
  [manifest canonical-path]
  (some #(when (= canonical-path (:seon.fn.file/path %)) %)
        (:seon.fn.manifest/artifacts manifest)))

(defn manifest-function-symbols
  "Sorted first-party function symbols contributed by a manifest."
  {:malli/schema [:=> [:catn [:manifest :seon.fn.manifest/manifest]]
                  [:vector [:string {:min 1}]]]}
  [manifest]
  (->> (:seon.fn.manifest/identities manifest)
       (filter #(= :seon.fn/sym (first %)))
       (map second)
       distinct
       sort
       vec))

(defn- manifest-data
  [roots artifacts]
  (let [artifacts (->> artifacts
                       (sort-by :seon.fn.file/path)
                       vec
                       assert-capability-contracts!)
        findings (into [] (mapcat :seon.fn.file/findings) artifacts)]
    (cond->
     {:seon.fn.manifest/roots roots
      :seon.fn.manifest/digest
      (sha-256 (.getBytes
                (pr-str (mapv (juxt :seon.fn.file/path
                                    :seon.fn.file/digest)
                              artifacts))
                java.nio.charset.StandardCharsets/UTF_8))
      :seon.fn.manifest/artifacts artifacts
      :seon.fn.manifest/identities
      (->> artifacts
           (mapcat :seon.fn.file/identities)
           (sort-by pr-str)
           vec)}
      (seq findings) (assoc :seon.fn.manifest/findings findings))))

(defn replace-manifest-artifacts
  "Replace file artifacts and recompute one deterministic manifest."
  {:malli/schema
   [:=>
    [:catn
     [:manifest :seon.fn.manifest/manifest]
     [:desired-artifacts [:vector :seon.fn.file/artifact]]]
    :seon.fn.manifest/manifest]}
  [manifest desired-artifacts]
  (when-let [duplicate-path
             (some (fn [[path n]] (when (> n 1) path))
                   (frequencies (map :seon.fn.file/path desired-artifacts)))]
    (throw (ex-info "Manifest replacement carries a duplicate file path."
                    {:seon.error/kind ::index-refused
                     :seon.fn.file/path duplicate-path :seon.fn/index-refused true})))
  (let [desired-by-path
        (into {} (map (juxt :seon.fn.file/path identity)) desired-artifacts)
        retained
        (remove #(contains? desired-by-path (:seon.fn.file/path %))
                (:seon.fn.manifest/artifacts manifest))]
    (manifest-data (:seon.fn.manifest/roots manifest)
                   (concat retained desired-artifacts))))

(defn build-manifest
  "Build deterministic artifacts for the complete first-party program."
  {:malli/schema
   [:=> [:cat [:map
              [:seon.fn/roots :seon.fn/roots]]]
    :seon.fn.manifest/manifest]}
  [request]
  (let [roots (:seon.fn/roots request)
        files (source-files roots)
        paths (mapv #(.getCanonicalPath ^java.io.File %) files)
        analysis (analyzer/analyze {::analyzer/paths paths})
        findings-by-file
        (group-by ::analyzer/filename (publication-findings analysis))
        first-party-functions (first-party-function-symbols analysis)
        rows-by-file (analysis-rows-by-file analysis first-party-functions)
        artifacts
        (mapv (fn [file]
                (artifact file
                          (get rows-by-file
                               (.getCanonicalPath ^java.io.File file)
                               [])
                          (get findings-by-file
                               (.getCanonicalPath ^java.io.File file)
                               [])))
              files)
        manifest
        (manifest-data
         (mapv #(.getCanonicalPath ^java.io.File (rooted-file %)) roots)
         artifacts)]
    (assert-clean-analysis! analysis)
    manifest))

(defn- row-by-identity
  [rows]
  (into {} (map (juxt program/row-identity identity)) rows))

(defn- changed-row-attributes
  [current desired]
  (into #{}
        (filter #(not= (get current %) (get desired %)))
        (into (set (keys current)) (keys desired))))

(defn- scalar-upsert-rows
  [current-rows desired]
  (into
   []
   (keep
    (fn [desired-row]
      (let [[identity-attribute identity-value :as program-identity]
            (program/row-identity desired-row)
            current-row (get current-rows program-identity)
            changed (changed-row-attributes current-row desired-row)]
        (when (seq changed)
          (assoc (select-keys desired-row changed)
                 identity-attribute identity-value)))))
   (:seon.fn.file/rows desired)))

(defn- full-rebuild
  [reasons details]
  (merge {:seon.fn.change/action :full-rebuild
          :seon.fn.change/reasons (vec (distinct reasons))}
         details))

(defn plan-file-change
  "Classify one file change as safe upserts or a clean rebuild."
  {:malli/schema
   [:=>
    [:cat [:map
           [:seon.fn.change/status
            [:enum :added :modified :deleted :moved :schema-resource
             :analysis-error]]
           [:seon.fn.change/current-artifact {:optional true} :map]
           [:seon.fn.change/desired-artifact {:optional true} :map]
           [:seon.fn.change/stale? {:optional true} :boolean]
           [:seon.fn.change/uncertain? {:optional true} :boolean]
           [:seon.fn.change/findings {:optional true} [:vector :map]]]]
    [:map]]}
  [{status :seon.fn.change/status
    current :seon.fn.change/current-artifact
    desired :seon.fn.change/desired-artifact
    stale? :seon.fn.change/stale?
    uncertain? :seon.fn.change/uncertain?
    findings :seon.fn.change/findings}]
  (let [current-identities (set (:seon.fn.file/identities current))
        desired-identities (set (:seon.fn.file/identities desired))
        current-rows (row-by-identity (:seon.fn.file/rows current))
        desired-rows (row-by-identity (:seon.fn.file/rows desired))
        shared-identities (set/intersection current-identities
                                            desired-identities)
        added-identities (set/difference desired-identities
                                         current-identities)
        changed-attributes
        (into #{}
              (mapcat (fn [program-identity]
                        (changed-row-attributes
                         (get current-rows program-identity)
                         (get desired-rows program-identity))))
              shared-identities)
        unsafe-attributes
        (many-or-component-attributes
         (into #{} (mapcat keys) (concat (vals current-rows)
                                         (vals desired-rows))))
        added-many-or-component?
        (some (fn [program-identity]
                (some #(contains? (get desired-rows program-identity) %)
                      unsafe-attributes))
              added-identities)
        reasons
        (cond-> []
          (contains? #{:deleted :moved :schema-resource :analysis-error} status)
          (conj status)
          (and (= :modified status) (nil? current))
          (conj :missing-artifact)
          (nil? desired)
          (conj :missing-desired-artifact)
          stale? (conj :stale-artifact)
          uncertain? (conj :uncertain-projection)
          (and current desired
               (not= (:seon.fn.file/path current)
                     (:seon.fn.file/path desired)))
          (conj :file-move)
          (seq (set/difference current-identities desired-identities))
          (conj :removed-identity)
          (seq added-identities)
          (conj :added-identity)
          (seq (set/intersection changed-attributes
                                 unsafe-attributes))
          (conj :component-or-cardinality-many-change)
          (contains? changed-attributes :seon.fn/spec)
          (conj :function-contract-change)
          added-many-or-component?
          (conj :component-or-cardinality-many-addition)
          (some (fn [program-identity]
                  (let [before (get current-rows program-identity)
                        after (get desired-rows program-identity)]
                    (some #(and (contains? before %)
                                (not (contains? after %)))
                          (changed-row-attributes before after))))
                shared-identities)
          (conj :attribute-retraction))]
    (if (seq reasons)
      (full-rebuild
       reasons
       (cond-> {:seon.fn.change/current-path
                (:seon.fn.file/path current)
                :seon.fn.change/desired-path
                (:seon.fn.file/path desired)
                :seon.fn.change/removed-identities
                (->> (set/difference current-identities desired-identities)
                     (sort-by pr-str)
                     vec)
                :seon.fn.change/added-identities
                (->> added-identities (sort-by pr-str) vec)
                :seon.fn.change/changed-attributes
                (vec (sort changed-attributes))}
         (seq findings) (assoc :seon.fn.change/findings findings)))
      {:seon.fn.change/action :incremental-upsert
       :seon.fn.change/path (:seon.fn.file/path desired)
       :seon.fn.change/digest (:seon.fn.file/digest desired)
       ;; The artifact is the complete analyzed file projection used to plan
       ;; the next edit. It must not be confused with the transaction delta.
       :seon.fn.change/artifact desired
       ;; Publish the exact scalar delta, not the whole analyzed row. Replaying
       ;; an unchanged namespace row would recreate anonymous component
       ;; children even though the planner had proved those fields unchanged.
       :seon.fn.change/rows
       (scalar-upsert-rows current-rows desired)
       :seon.fn.change/identities (:seon.fn.file/identities desired)})))

(defn rows
  "Canonical program rows discovered statically from exact JVM source."
  {:malli/schema [:=> [:cat :seon.fn/index-request] :seon.program/rows]}
  [request]
  (into []
        (mapcat :seon.fn.file/rows)
        (:seon.fn.manifest/artifacts
         (or (:seon.fn/manifest request)
             (when (seq (:seon.fn/roots request))
               (build-manifest request))
             (throw
              (ex-info "Program rows require a manifest or source roots."
                       {:seon.error/kind ::index-refused :seon.fn/index-refused true}))))))

(defn- assert-one-row-per-identity!
  [desired]
  (when-let [duplicate
             (some (fn [[program-identity n]]
                     (when (> n 1) program-identity))
                   (frequencies (map program/row-identity desired)))]
    (throw
     (ex-info
      "Source indexing refused a duplicate program identity."
      {:seon.error/kind ::index-refused
       ::identity duplicate :seon.fn/index-refused true}))))

(defn- assert-populated!
  [desired]
  (doseq [identity-attr [:seon.ns/name :seon.fn/sym]]
    (when-not (some identity-attr desired)
      (throw
       (ex-info
        (str "Source indexing produced no " identity-attr
             " rows; refusing a partial program graph.")
        {:seon.error/kind ::index-refused
         ::missing-population identity-attr :seon.fn/index-refused true})))))

(defn- add-contract-facts
  [rows progress!]
  (let [schema-forms
        (into (sorted-map)
              (keep (fn [{schema-key :seon.schema/key
                          form-string :seon.schema/form}]
                      (when schema-key
                        [schema-key (edn/read-string form-string)])))
              rows)
        function-contracts
        (into (sorted-map)
              (keep (fn [{function-symbol :seon.fn/sym
                          spec :seon.fn/spec}]
                      (when spec
                        [(symbol function-symbol) (edn/read-string spec)])))
              rows)
        _ (report-index-progress!
           progress!
           (str "contract projection started: "
                (count schema-forms) " schemas, "
                (count function-contracts) " functions"))
        projection
        (schema/build-projection
         schema-forms function-contracts
         {:seon.schema/validate-render-contracts? true})
        _ (report-index-progress! progress! "contract projection complete")
        compile-options (:seon.schema.projection/compile-options projection)
        predicate-functions
        (:seon.schema.projection/predicate-functions projection)
        schema-keys (set (keys schema-forms))
        aliases-by-namespace
        (into {}
              (keep (fn [{namespace-name :seon.ns/name
                          aliases :seon.ns/aliases}]
                      (when namespace-name
                        [namespace-name
                         (into {}
                               (map (juxt :seon.ns.alias/local
                                          :seon.ns.alias/target-ns))
                               aliases)])))
              rows)
        total (count rows)
        stride (progress-stride total progress-line-budget)
        parsed-rows
        (mapv (fn [index row]
                (let [parsed
                      (program/with-contract-facts
                       {:seon.program/row row
                        :seon.program/compile-options compile-options
                        :seon.program/predicate-functions predicate-functions
                        :seon.program/schema-keys schema-keys
                        :seon.program/schema-forms schema-forms
                        :seon.program/reader-aliases
                        (get aliases-by-namespace
                             (second (:seon.fn/ns row)) {})})
                      completed (inc index)]
                  (when (or (= completed total)
                            (zero? (mod completed stride)))
                    (report-index-progress!
                     progress!
                     (str "contract rows: " completed "/" total)))
                  parsed))
              (range)
              rows)]
    (schema-shape/assert-consistent!
     (filter :seon.schema.shape/fingerprint
             (mapcat #(filter map? (tree-seq coll? seq %)) parsed-rows)))
    parsed-rows))

(defn backfill-contract-facts!
  "Backfill every contracted function missing either parsed component root.

   All missing graphs commit in one transaction. A converged call performs no
   transaction; ordinary producers remain responsible for new and changed
   rows so their specs and parsed facts are atomic."
  {:malli/schema
   [:=>
    [:cat
     [:map
      [:seon.db/connection :seon.db/connection]
      [:seon.db/process {:optional true} :seon.db/ref]]]
    :seon.reconcile/result]}
  [{connection :seon.db/connection process :seon.db/process}]
  (let [db @connection
        projection (schema/projection-from-database db)
        compile-options (:seon.schema.projection/compile-options projection)
        predicate-functions
        (:seon.schema.projection/predicate-functions projection)
        schema-keys (set (keys (:seon.schema.projection/forms projection)))
        aliases-by-namespace
        (into {}
              (map (fn [[namespace-name namespace-row]]
                     [namespace-name
                      (into {}
                            (map (juxt :seon.ns.alias/local
                                       :seon.ns.alias/target-ns))
                            (:seon.ns/aliases namespace-row))]))
              (db/q '[:find ?namespace-name (pull ?namespace
                                                  [{:seon.ns/aliases [*]}])
                      :where
                      [?namespace :seon.ns/name ?namespace-name]]
                    db))
        contracted
        (db/q '[:find ?function ?function-symbol ?spec ?source ?arglists
                       ?namespace-name
               :where
               [?function :seon.fn/sym ?function-symbol]
               [?function :seon.fn/spec ?spec]
               [?function :seon.fn/source ?source]
               [?function :seon.fn/arglists ?arglists]
               [?function :seon.fn/ns ?namespace]
               [?namespace :seon.ns/name ?namespace-name]]
             db)
        missing
        (filterv
         (fn [[function]]
           (let [row (db/pull db
                              [:seon.fn/ast
                               {:seon.fn/arities
                                [:seon.fn.arity/argument-count
                                 :seon.fn.arity/return-schema]}]
                              function)
                 arities (:seon.fn/arities row)]
             (or (nil? (:seon.fn/ast row))
                 (empty? arities)
                 (some #(or (not (contains? % :seon.fn.arity/argument-count))
                            (not (contains? % :seon.fn.arity/return-schema)))
                       arities))))
         contracted)
        tx-data
        (into
         []
         (mapcat
          (fn [[function function-symbol spec source arglists namespace-name]]
            (let [current (db/pull db [:seon.fn/arities :seon.fn/ast]
                                  function)
                  parsed
                  (program/contract-facts
                   {:seon.program/function-symbol function-symbol
                    :seon.program/spec spec
                    :seon.program/source source
                    :seon.program/arglists arglists
                    :seon.program/compile-options compile-options
                    :seon.program/predicate-functions predicate-functions
                    :seon.program/schema-keys schema-keys
                    :seon.program/schema-forms
                    (:seon.schema.projection/forms projection)
                    :seon.program/reader-namespace namespace-name
                    :seon.program/reader-aliases
                    (get aliases-by-namespace namespace-name {})})]
              (concat
               (keep (fn [attribute]
                       (when (contains? current attribute)
                         [:db.fn/retractAttribute function attribute]))
                     [:seon.fn/arities :seon.fn/ast])
               [(assoc parsed :db/id function)]))))
         (sort-by second missing))]
    (when (seq tx-data)
      (require-committed!
       (db/transact! connection
                     (cond-> {:tx-data tx-data}
                       process (assoc :tx-meta {:seon.db/process process})))
       :seon.fn/backfill-contract-facts))
    {:seon.reconcile/converged? (empty? tx-data)
     :seon.reconcile/operations (count missing)}))

(defn- desired-rows
  [request progress!]
  (let [source-rows (rows request)
        packaged-forms (schema.edn/packaged-forms)
        canonical-schemas
        (mapv (partial accretion/schema-row packaged-forms)
              (schema/canonical-schema-rows packaged-forms))
        canonical-keys (into #{} (map :seon.schema/key) canonical-schemas)
        source-only
        (remove (fn [row]
                  (contains? canonical-keys (:seon.schema/key row)))
                source-rows)
        source-namespace-names
        (into #{} (keep :seon.ns/name) source-only)
        required-namespace-names
        (into #{}
              (comp
               (mapcat #(or (:seon.ns/requires %) []))
               (map second))
              source-only)
        external-namespace-rows
        (->> required-namespace-names
             (remove source-namespace-names)
             (sort-by str)
             (mapv (fn [namespace-name]
                     {:seon.ns/name namespace-name})))]
    (doseq [{schema-key :seon.schema/key
             form-string :seon.schema/form}
            (filter :seon.schema/key source-only)]
      (when-not (and form-string
                     (schema/malli-form? (edn/read-string form-string)))
        (throw
         (ex-info "Source indexing refused a non-Malli schema declaration."
                  {:seon.error/kind ::index-refused
                   :seon.schema/key schema-key :seon.fn/index-refused true}))))
    (add-contract-facts
     (mapv #(assoc % :seon.schema.admission/source :core)
           (into (into (vec source-only) external-namespace-rows)
                 canonical-schemas))
     progress!)))

(defn- commit-index-phase!
  [connection process progress! phase tx-data]
  (when (seq tx-data)
    (let [total (count tx-data)]
      (require-committed!
       (db/transact!
        connection
        (cond-> {:tx-data (vec tx-data)}
          process (assoc :tx-meta {:seon.db/process process})))
       phase)
      (report-index-progress!
       progress!
       (str (name phase) ": " total "/" total))))
  nil)

(defn index!
  "Populate one fresh source scratch branch from static analysis.

  The optional callback receives bounded progress lines while contract rows
  are derived and after each ordered phase commits. Observation never changes
  transaction boundaries; the scratch branch remains unpublished until every
  phase and the source seal commit."
  {:malli/schema
   [:function
    [:=> [:cat :seon.fn/index-request] :seon.reconcile/result]
    [:=> [:cat :seon.fn/index-request [:fn clojure.core/ifn?]]
     :seon.reconcile/result]]}
  ([request]
   (index! request nil))
  ([{connection :seon.db/connection process :seon.db/process :as request}
    progress!]
   (let [rows (desired-rows request progress!)
         _ (assert-one-row-per-identity! rows)
         _ (assert-populated! rows)
         existing (some (fn [identity-attribute]
                          (db/q '[:find ?entity .
                                 :in $ ?attribute
                                 :where [?entity ?attribute]]
                                @connection identity-attribute))
                        [:seon.ns/name :seon.fn/sym :seon.test/sym])]
     (when existing
       (throw (ex-info "Program indexing requires a fresh source scratch branch."
                       {:seon.error/kind ::index-refused
                        ::existing-program-entity existing :seon.fn/index-refused true})))
     (let [namespaces (filterv :seon.ns/name rows)
          namespace-bases
          (mapv #(dissoc % :seon.ns/requires) namespaces)
          namespace-relations
          (into []
                (keep (fn [row]
                        (when (seq (:seon.ns/requires row))
                          (select-keys row
                                       [:seon.ns/name :seon.ns/requires]))))
                namespaces)
          declarations (filterv #(not (:seon.ns/name %)) rows)
          declaration-bases
          (mapv #(dissoc % :seon.fn/calls :seon.test/subject :seon.fn/keywords)
                declarations)
          keyword-rows
          (into [] (mapcat keyword-facts) declarations)
          subject-rows
          (into []
                (keep (fn [row]
                        (when-some [subject (:seon.test/subject row)]
                          (let [[identity-attribute identity-value]
                                (program/row-identity row)]
                            {identity-attribute identity-value
                             :seon.test/subject subject}))))
                declarations)
          call-rows
          (into []
                (keep (fn [row]
                        (when (seq (:seon.fn/calls row))
                          (let [[identity-attribute identity-value]
                                (program/row-identity row)]
                            {identity-attribute identity-value
                             :seon.fn/calls (:seon.fn/calls row)}))))
                declarations)
          commit-phase! (partial commit-index-phase!
                                 connection process progress!)]
       ;; Datahike processes tx-data in order. Every identity therefore exists
       ;; before a requires lookup ref resolves it, including the shared
       ;; name-only rows for external namespaces.
       (commit-phase! :seon.fn/namespaces
                      (into namespace-bases namespace-relations))
       (commit-phase! :seon.fn/declarations declaration-bases)
       (commit-phase! :seon.test/subject subject-rows)
       (commit-phase! :seon.fn/keywords keyword-rows)
       (commit-phase! :seon.fn/calls call-rows)
       {:seon.reconcile/converged? false
        :seon.reconcile/operations (count rows)}))))

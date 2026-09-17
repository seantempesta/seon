(ns seon.fn
  "Build-time indexing of the Clojure program graph without evaluation."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [seon.db :as db]
            [seon.error :as error]
            [seon.fn.analyzer :as analyzer]
            [seon.fn.schema-shape :as schema-shape]
            [seon.id :as id]
            [seon.fs :as fs]
            [seon.program :as program]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.schema.edn :as schema.edn]
            [seon.schema.form :as schema.form]
            [seon.schema.internal :as schema.internal]
            [clj-kondo.impl.utils :as kondo.utils]
            [seon.test.accretion :as accretion])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files]
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
     (ex-info
      (str "Program indexing transaction was refused. "
           (:seon.error/message result) " "
           (pr-str (select-keys result [:seon.db/attribute :seon.db/offending]))
           " Entity: " (pr-str (get-in result [:seon.error/data :seon.db/entity])))
      (assoc result :seon.fn/index-phase phase :seon.fn/index-refused true))))
  result)

(defn- rooted-file
  [directory path]
  (io/file (fs/absolute-path directory path)))

(defn- source-file?
  [file]
  (and (.isFile ^java.io.File file)
       (or (str/ends-with? (.getName ^java.io.File file) ".clj")
           (str/ends-with? (.getName ^java.io.File file) ".cljc"))))

(defn- under-root?
  "True when this canonical file is a descendant of this canonical directory."
  [^java.io.File root-file ^java.io.File file]
  (loop [parent (.getParentFile file)]
    (cond
      (nil? parent) false
      (= parent root-file) true
      :else (recur (.getParentFile parent)))))

(defn- containing-root
  "The supplied root whose directory holds this file, or nil for none.
  THE one answer to \"which root did this file come from\": the complete walk
  and the changed-path seam both ask it, with the roots of their own request,
  so the same file under the same roots carries the same root fact by
  construction. Real directory ancestry of canonical files, never a rule about
  the path text, and the value is the root exactly as supplied."
  [directory roots ^java.io.File file]
  (let [file (.getCanonicalFile file)]
    (some (fn [root] (when (under-root? (rooted-file directory root) file) root)) roots)))

(defn- source-files
  [directory roots]
  (into []
        (mapcat (fn [root]
                  (->> (file-seq (rooted-file directory root))
                       (filter source-file?)
                       (sort-by (fn [file]
                                  (.getCanonicalPath ^java.io.File file))))))
        roots))

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

(defn- text-context
  [text]
  (let [source-bytes (.getBytes ^String text StandardCharsets/UTF_8)
        line-starts (into [0] (keep-indexed (fn [i c] (when (= \newline c) (inc i)))) text)]
    {:bytes source-bytes
     :seon.fn.file/digest (sha-256 source-bytes)
     :text text
     :line-starts line-starts
     ::byte-line-starts
     (vec (reductions + 0
                      (map (fn [[start end]]
                             (alength (.getBytes (subs text start end) StandardCharsets/UTF_8)))
                           (partition 2 1 line-starts))))}))

(defn- source-context
  [file]
  (text-context (String. (Files/readAllBytes (.toPath ^java.io.File file))
                         StandardCharsets/UTF_8)))

(defn- source-contexts
  [files]
  (into {}
        (map (fn [file]
               [(.getCanonicalPath ^java.io.File file)
                (source-context file)]))
        files))

(defn- current-file-digest
  [path]
  (let [file (java.io.File. ^String path)]
    (when (.isFile file)
      (sha-256 (Files/readAllBytes (.toPath file))))))

(defn- span-refused!
  "Refuse a declaration span that does not fit the source text it is read from.

  The analyzer reads every file itself, so its rows and columns describe a
  different read of the file than `source-contexts` captured. A concurrent edit
  between those two reads leaves a span addressing characters the captured text
  does not have. This names the file, the offending span, the captured digest
  and length, and the file's current digest, so publication reports a source
  change instead of a bare index exception."
  [contexts entry detail]
  (let [path (::analyzer/filename entry)
        context (get contexts path)]
    (throw
     (ex-info
      "Source changed during analysis; a declaration span does not fit the analyzed text."
      (merge {:seon.error/kind ::index-refused
              :seon.error/diagnostic-cause ::source-changed-during-analysis
              :seon.fn/index-refused true
              :seon.fn/source-path path
              :seon.fn.file/captured-digest (:seon.fn.file/digest context)
              :seon.fn.file/captured-length (count (:text context))
              ::analysis-span [(::analyzer/row entry) (::analyzer/col entry)
                               (::analyzer/end-row entry) (::analyzer/end-col entry)]}
             (when-let [digest (current-file-digest path)]
               {:seon.fn.file/current-digest digest})
             detail)))))

(defn- character-offset
  "The character offset of one analyzer row/column in the captured text.

  Total: a position the captured text cannot hold is the typed refusal, never
  an index exception."
  [contexts entry row col]
  (let [{:keys [text line-starts]} (get contexts (::analyzer/filename entry))]
    (when-not (and (pos? row) (<= row (count line-starts)))
      (span-refused! contexts entry {:seon.error/diagnostic-offending [row col]}))
    (let [offset (+ (nth line-starts (dec row)) (dec col))]
      (when-not (<= 0 offset (count text))
        (span-refused! contexts entry {:seon.error/diagnostic-offending [row col]}))
      offset)))

(defn- exact-source [contexts entry]
  (let [{:keys [text]}
        (get contexts (::analyzer/filename entry))
        row (::analyzer/row entry)
        col (::analyzer/col entry)
        end-row (::analyzer/end-row entry)
        end-col (::analyzer/end-col entry)]
    (when-not text
      (throw (ex-info "Static declaration has no source file content."
                      {:seon.error/kind ::index-refused
                       ::analysis-entry entry :seon.fn/index-refused true})))
    (when-not (every? some? [row col end-row end-col])
      (throw (ex-info "Static declaration has no exact source span."
                      {:seon.error/kind ::index-refused
                       ::analysis-entry entry :seon.fn/index-refused true})))
    (let [start (character-offset contexts entry row col)
          end (character-offset contexts entry end-row end-col)]
      (when-not (<= start end)
        (span-refused! contexts entry
                       {:seon.error/diagnostic-offending [end-row end-col]}))
      (subs text start end))))

(defn- exact-form-span
  [contexts entry]
  (let [{:keys [text line-starts] ::keys [byte-line-starts]} (get contexts (::analyzer/filename entry))
        offset (fn [row col]
                 ;; An analyzer column past the line's last character addresses
                 ;; that line's end; no source byte exists beyond it. A row the
                 ;; captured text does not have is the typed refusal: the file
                 ;; changed between the analyzer's read and this one.
                 (when-not (and (pos? row) (<= row (count line-starts)))
                   (span-refused! contexts entry
                                  {:seon.error/diagnostic-offending [row col]}))
                 (let [start (nth line-starts (dec row))
                       stop (min (+ start (dec col))
                                 (nth line-starts row (count text)))]
                   (+ (nth byte-line-starts (dec row))
                      (alength (.getBytes (subs text start stop)
                                          StandardCharsets/UTF_8)))))]
    [(offset (::analyzer/row entry) (::analyzer/col entry))
     (offset (::analyzer/end-row entry) (::analyzer/end-col entry))]))

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

(defn- namespace-context [contexts entry]
  (let [form (read-jvm-form (exact-source contexts entry))]
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

(defn- namespace-row [contexts context entry]
  (let [namespace-name (::analyzer/name entry)
        {:keys [aliases refers imports requires]} context]
    (cond-> {:seon.ns/name namespace-name
             :seon.ns/source (exact-source contexts entry)}
      (::analyzer/doc entry) (assoc :seon.ns/doc (::analyzer/doc entry))
      (true? (:seon.ns/context-relevant? (::analyzer/meta entry)))
      (assoc :seon.ns/context-relevant? true)
      (seq requires)
      (assoc :seon.ns/requires (set requires))
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

(defn- function-definition?
  [entry]
  (or (seq (::analyzer/arglist-strs entry))
      (= 'clojure.core/defmulti (::analyzer/defined-by->lint-as entry))))

(defn- first-party-function-symbols
  [analysis]
  (into #{}
        (comp
         (filter function-definition?)
         (map #(symbol (str (::analyzer/ns %))
                       (str (::analyzer/name %)))))
        (::analyzer/var-definitions analysis)))

(defn- usage-symbol
  "Return the real qualified target name clj-kondo resolved for `usage`.

  `:clj-kondo/unknown-namespace` means there is no target identity. Mapping it
  to the caller namespace fabricates first-party names for Java methods,
  special forms, gensyms, and unresolved aliases. The conservative file
  relation remains target-keyed and therefore carries only actual qualified
  target names; analysis provenance remains on the file digest."
  [usage]
  (when (and (::analyzer/to usage)
             (not= :clj-kondo/unknown-namespace (::analyzer/to usage))
             (::analyzer/name usage))
    (symbol (str (::analyzer/to usage))
            (str (::analyzer/name usage)))))

(defn- usage-caller
  [usage]
  (when (and (::analyzer/from usage) (::analyzer/from-var usage))
    (symbol (str (::analyzer/from usage))
            (str (::analyzer/from-var usage)))))

(defn- call-target
  [usage]
  (when (and (not (::analyzer/macro usage))
             (or (contains? usage ::analyzer/arity) (::analyzer/var-quote usage)))
    (usage-symbol usage)))

(defn- references-by-caller
  "Resolved target identities without a resolved call shape.
  Quoted symbols are references, never invented arities. A nil caller is
  retained when this analysis has no declaration to own the reference."
  ([analysis first-party-functions]
   (references-by-caller analysis first-party-functions first-party-functions))
  ([analysis _first-party-functions declared-callers]
   (reduce
    (fn [references usage]
      (let [caller (usage-caller usage)
            target (usage-symbol usage)]
        (if (and target
                 (or (::analyzer/macro usage)
                     (not (contains? usage ::analyzer/arity))
                     (not (declared-callers caller))))
          (update references (when (declared-callers caller) caller)
                  (fnil conj #{}) target)
          references)))
    {} (::analyzer/var-usages analysis))))

(defn- external-target?
  [first-party-functions target]
  (and target (not (contains? first-party-functions target))))

(defn- call-targets-by-caller
  ([analysis first-party-functions]
   (call-targets-by-caller analysis first-party-functions nil))
  ([analysis first-party-functions resolvable-targets]
  (reduce
   (fn [calls usage]
     (let [caller (usage-caller usage)
           target (call-target usage)]
       (if (and (contains? first-party-functions caller)
                target
                (or (nil? resolvable-targets)
                    (contains? resolvable-targets target)))
         (update calls caller (fnil conj #{}) target)
         calls)))
   {}
   (::analyzer/var-usages analysis))))

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
             (symbol (str (::analyzer/from entry))
                     (str (::analyzer/from-var entry))))
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
  (when-let [used (seq (get keywords-by-holder qualified))]
    (into (sorted-set) used)))

(def ^:private write-seam-symbols
  "The functions through which a first-party database write leaves the caller.

  `seon.db` is the one database namespace and `seon.db/transact!` its one
  write entry, so transaction data a declaration asserts is lexically inside
  a usage of that seam."
  #{'seon.db/transact!})

(defn- span-contains?
  "True when one analyzer entry's position lies inside another's form span."
  [span entry]
  (let [end-row (::analyzer/end-row span)
        end-col (::analyzer/end-col span)
        row (long (or (::analyzer/row entry) 0))
        col (long (or (::analyzer/col entry) 0))
        start-row (long (or (::analyzer/row span) 0))
        start-col (long (or (::analyzer/col span) 0))]
    (and (some? end-row)
         (some? end-col)
         (or (> row start-row)
             (and (= row start-row) (>= col start-col)))
         (or (< row (long end-row))
             (and (= row (long end-row)) (<= col (long end-col)))))))

(defn- writes-by-writer
  "Declared attributes each declaration asserts at a write-seam call site.

  The join is span containment over facts the analyzer already reports:
  a qualified keyword whose position lies inside the span of a
  `seon.db/transact!` usage is transaction data that call carries.
  `declared-key?` admits only attributes the schema population owns, so
  every emitted value resolves to a `:seon.schema/key` row."
  [analysis declared-key?]
  (let [keywords-by-file
        (group-by ::analyzer/filename (::analyzer/keywords analysis))]
    (reduce
     (fn [writes usage]
       (if-let [caller (and (contains? write-seam-symbols (usage-symbol usage))
                            (usage-caller usage))]
         (reduce
          (fn [writes entry]
            (let [keyword-namespace (::analyzer/ns entry)
                  keyword-name (::analyzer/name entry)
                  attribute (when (and keyword-namespace keyword-name)
                              (keyword (str keyword-namespace)
                                       (str keyword-name)))]
              (if (and attribute
                       (declared-key? attribute)
                       (span-contains? usage entry))
                (update writes caller (fnil conj (sorted-set)) attribute)
                writes)))
          writes
          (get keywords-by-file (::analyzer/filename usage)))
         writes))
     {}
     (::analyzer/var-usages analysis))))

(defn- call-arities-by-caller
  "The arities each stored call edge was actually invoked with.

  The population is exactly `calls-by-caller`: a tuple appears only for a
  caller and target the reach index already carries, so a refinement can
  never name a target with no program row."
  [analysis calls-by-caller]
  (reduce
   (fn [arities usage]
     (let [caller (usage-caller usage)
           target (call-target usage)]
       (if (and caller target (contains? usage ::analyzer/arity)
                (contains? (get calls-by-caller caller) target))
         (update arities caller (fnil conj (sorted-set))
                 [target (long (::analyzer/arity usage))])
         arities)))
   {}
   (::analyzer/var-usages analysis)))

(defn- write-refs
  [writes qualified]
  (when-let [attributes (seq (get writes qualified))]
    (set attributes)))

(defn- test-subject
  [metadata]
  (when-let [subject (:seon.test/subject metadata)]
    (when (or (qualified-symbol? subject)
              (and (string? subject)
                   (qualified-symbol? (symbol subject))))
      (symbol subject))))

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

(defn- function-value-schema?
  "Whether a declaration explicitly admits a function identity or symbol.
  Follow aliases and scalar alternatives, never map entry names."
  [forms form seen]
  (cond
    (= :seon.fn/sym form) true
    (contains? #{:symbol :qualified-symbol} form) true
    (and (keyword? form) (not (seen form)) (get forms form))
    (function-value-schema? forms (get forms form) (conj seen form))
    (vector? form)
    (or (= :seon.fn/sym (:seon.fn/reference-to (schema.form/attr-form-properties form)))
        (contains? #{:symbol :qualified-symbol} (first form))
        (and (contains? #{:or :and :schema} (first form))
             (some #(function-value-schema? forms % seen)
                   (remove map? (rest form)))))
    :else false))

(defn- declared-function-targets
  "Literal function-valued declarations, classified by their own schema.
  Consumers are joined by the attribute they use; no callable-key roster."
  [forms values first-party-functions]
  (let [attributes (into #{} (keep (fn [[k v]]
                                    (when (or (= :seon.fn/sym k)
                                              (function-value-schema? forms v #{k})) k)))
                         forms)]
    (reduce
     (fn [targets value]
       (if (map? value)
         (reduce-kv
          (fn [targets attribute value]
            (let [value (capability-symbol value)
                  target (cond
                           (qualified-symbol? value) value
                           (and (vector? value) (= :seon.fn/sym (first value)))
                           (second value))]
              (if (and (attributes attribute) (first-party-functions target))
                (update targets attribute (fnil conj #{}) target)
                targets)))
          targets value)
         targets))
     {} (mapcat #(tree-seq coll? seq %) values))))

(defn- declared-calls-by-caller
  [used-keywords targets]
  (update-vals used-keywords
               #(into #{} (mapcat targets) %)))

(defn- var-row
  [contexts namespace-contexts
   {:keys [calls-by-caller references used-keywords writes call-arities]} entry]
  (let [namespace-name (::analyzer/ns entry)
        qualified (symbol (str namespace-name) (str (::analyzer/name entry)))
        metadata (::analyzer/meta entry)
        namespace-metadata (:namespace-meta
                            (get namespace-contexts namespace-name))
        source (exact-source contexts entry)
        file [:seon.fn.file/relative-path (::analyzer/filename entry)]
        span (exact-form-span contexts entry)
        external-sink (:seon.fn/external-sink metadata)
        destroys (:seon.fn/destroys metadata)
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
         :seon.fn/sym qualified
         :seon.effect/capability capability :seon.fn/index-refused true})))
    (cond
      (::analyzer/test entry)
      (cond-> {:seon.test/sym qualified
               :seon.test/ns [:seon.ns/name namespace-name]
               :seon.test/source source
               :seon.program/analyzed-source-digest
               (:seon.fn.file/digest (get contexts (::analyzer/filename entry)))
               :seon.fn/file file
               :seon.fn/form-span span}
        (find metadata :seon.test/fixture-observation)
        (assoc :seon.test/fixture-observation (:seon.test/fixture-observation metadata))
        ;; A namespace of real-boot drills declares the cost once on the ns
        ;; form; the one rule lives in seon.program.
        true (merge (program/test-markers metadata namespace-metadata))
        (true? (:seon.test/usage metadata))
        (assoc :seon.test/usage true)
        (seq (get calls-by-caller qualified))
        (assoc :seon.fn/calls
               (into #{}
                     (map symbol)
                     (get calls-by-caller qualified)))
        (seq (get references qualified))
        (assoc :seon.fn/references
               (into #{} (map symbol)
                     (get references qualified)))
        (keyword-values used-keywords qualified)
        (assoc :seon.fn/keywords (keyword-values used-keywords qualified))
        (write-refs writes qualified)
        (assoc :seon.fn/writes (write-refs writes qualified))
        (seq (get call-arities qualified))
        (assoc :seon.fn/call-arities
               (into #{} (get call-arities qualified)))
        (test-subject metadata)
        (assoc :seon.test/subject (test-subject metadata)))

      (function-definition? entry)
      (cond-> {:seon.fn/sym qualified
               :seon.fn/ns [:seon.ns/name namespace-name]
               :seon.fn/source source
               :seon.program/analyzed-source-digest
               (:seon.fn.file/digest (get contexts (::analyzer/filename entry)))
               :seon.fn/file file
               :seon.fn/form-span span
               :seon.fn/arglists (str "(" (str/join " " (::analyzer/arglist-strs entry)) ")")
               :seon.fn/private? (boolean (::analyzer/private entry))}
        (true? (:seon.fn/internal? metadata)) (assoc :seon.fn/internal? true)
        (::analyzer/macro entry) (assoc :seon.fn/macro? true)
        ;; WHO WROTE THIS BODY IS A FACT, NOT A NAME. clj-kondo already tells
        ;; the indexer which form interned the var; keeping it means a
        ;; `deftype` constructor and a `defprotocol` method — vars with no
        ;; body to carry a contract — are excluded by query instead of by
        ;; guessing from their symbols.
        (qualified-symbol? (::analyzer/defined-by entry))
        (assoc :seon.fn/defined-by (::analyzer/defined-by entry))
        (:seon.fn/doc-order metadata) (assoc :seon.fn/doc-order (:seon.fn/doc-order metadata))
        (::analyzer/doc entry) (assoc :seon.fn/doc (::analyzer/doc entry))
        (:malli/schema metadata)
        (assoc :seon.fn/spec
               (pr-str (schema/canonical-definition
                        (qualify-schema-symbols
                         (:malli/schema metadata)
                        (get namespace-contexts namespace-name))
                        {})))
        (seq (get calls-by-caller qualified))
        (assoc :seon.fn/calls
               (into #{}
                     (map symbol)
                     (get calls-by-caller qualified)))
        (seq (get references qualified))
        (assoc :seon.fn/references
               (into #{} (map symbol)
                     (get references qualified)))
        (keyword-values used-keywords qualified)
        (assoc :seon.fn/keywords (keyword-values used-keywords qualified))
        (write-refs writes qualified)
        (assoc :seon.fn/writes (write-refs writes qualified))
        (seq (get call-arities qualified))
        (assoc :seon.fn/call-arities
               (into #{} (get call-arities qualified)))
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
        ;; ONE declaration of destructiveness, at the definition: what this
        ;; function deletes that it did not create. Every consumer derives
        ;; from it by `:seon.fn/calls` reach (`seon.test/host`), so no
        ;; roster of destructive owners or destructive tests exists.
        (and (string? destroys) (not (str/blank? destroys)))
        (assoc :seon.fn/destroys destroys)
        capability-declared?
        (assoc :seon.effect/capability capability)
        capability-declared?
        (update :seon.fn/calls (fnil conj #{}) capability))

      :else nil)))

(defn- runtime-require-specs
  [{:seon.ns/keys [requires aliases refers]}]
  (let [aliases-by-target (group-by :seon.ns.alias/target-ns aliases)
        refers-by-target (group-by :seon.ns.refer/target-ns refers)
        targets
        (sort-by str
                 (into (set requires)
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

(defn- interpreter-refer-rows
  "Refer rows for the bindings every SCI namespace resolves without a require.

  `seon.sci.eval/build-base-ctx` installs each declared binding's target in
  `clojure.core`, so a submitted form resolves `deftest`, `is`, `doc`, `dir`
  and `help` bare. A submitted form's ANALYSIS must resolve exactly what its
  EVALUATION resolves, or a bare `deftest` analyses to no var definition at
  all and its test row is never minted. The declared attribute name is the
  bare name the interpreter binds."
  [forms]
  (into []
        (keep (fn [[attribute definition]]
                (when-let [target (:seon.sci.binding/target
                                   (schema.form/attr-form-properties
                                    definition))]
                  (let [local (symbol (name attribute))]
                    {:seon.ns.refer/local local
                     :seon.ns.refer/target-ns (symbol (namespace target))
                     :seon.ns.refer/target-name local}))))
        forms))

(defn- runtime-namespace-form
  [namespace-name namespace-row referenced-namespaces]
  (let [program-requires
        (into #{}
              (remove #{namespace-name})
              referenced-namespaces)
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

(defn- resolvable-runtime-function-rows
  [database analysis requests]
  (let [batch-symbols
        (into #{}
              (keep (comp :seon.fn/sym :seon.program/row))
              requests)
        referenced-symbols
        (into #{} (keep usage-symbol) (::analyzer/var-usages analysis))
        existing-symbols
        (if-let [candidates (seq (set/difference referenced-symbols
                                                   batch-symbols))]
          (db/q '[:find [?symbol ...]
                  :in $ [?symbol ...]
                  :where [_ :seon.fn/sym ?symbol]]
                database (vec candidates))
          [])]
    (mapv (fn [function-symbol] {:seon.fn/sym function-symbol})
          (sort (into batch-symbols existing-symbols)))))

(defn- runtime-analysis-batch
  "Run the analyzer once for an ordered batch of submitted forms.

  Existing declarations supply the same namespace context as admission lint.
  Every form retains its exact source row span, excluding context declarations.
  Unknown-namespace usages carry no target identity and are excluded by the
  shared usage projection instead of being remapped to the calling namespace."
  [database requests]
  (let [namespace-names (vec (distinct (map :namespace-name requests)))
        available-functions
        (db/q '[:find [(pull ?f [:seon.fn/sym :seon.fn/arglists :seon.fn/private?]) ...]
                :in $ [?name ...]
                :where [?n :seon.ns/name ?name]
                       [?f :seon.fn/ns ?n] [?f :seon.fn/sym _]]
              database namespace-names)
        prelude (analyzer/program-prelude available-functions)
        interpreter-refers
        (interpreter-refer-rows
         (or (:seon.schema.projection/forms (db/carried-projection database))
             (schema/declaration-population)))
        {:keys [source spans]}
        (loop [remaining requests
               source (if (seq prelude) (str prelude "\n") "")
               spans []]
          (if-let [{:keys [namespace-name form-source]
                    supplied-namespace :seon.fn/namespace-row} (first remaining)]
            (let [namespace-row
                  (or supplied-namespace
                      (db/pull database
                           '[:seon.ns/name
                             :seon.ns/requires
                             {:seon.ns/aliases [*]}
                             {:seon.ns/imports [*]}
                             {:seon.ns/refers [*]}]
                           [:seon.ns/name namespace-name]))
                  referenced-namespaces
                  (analyzer/referenced-program-namespaces
                   namespace-name [form-source])
                  namespace-source
                  (pr-str (runtime-namespace-form
                           namespace-name
                           (update namespace-row :seon.ns/refers
                                   (fnil into []) interpreter-refers)
                           referenced-namespaces))
                  prefix (str source namespace-source "\n")
                  first-source-row (inc (count (filter #{\newline} prefix)))
                  last-source-row (+ first-source-row
                                     (count (filter #{\newline} form-source)))]
              (recur (next remaining)
                     (str prefix form-source "\n")
                     (conj spans [first-source-row last-source-row])))
            {:source source :spans spans}))
        analysis
        (with-in-str source
          (analyzer/analyze {::analyzer/paths ["-"]}))]
    {:seon.fn/analysis analysis
     :seon.fn/source source
     :seon.fn/source-spans spans
     :seon.fn/function-rows
     (resolvable-runtime-function-rows database analysis requests)}))

(declare source-span)

(defn- source-analysis
  "Keep only analyzer entries owned by one submitted form's row span."
  [analysis first-source-row last-source-row]
  (reduce
   (fn [projected analysis-key]
     (update projected analysis-key
             (fn [entries]
               (filterv #(<= first-source-row
                              (long (or (::analyzer/row %) 0))
                              last-source-row)
                        entries))))
   analysis
   [::analyzer/var-definitions
    ::analyzer/var-usages
    ::analyzer/keywords
    ::analyzer/findings]))

(defn- analyzed-form
  [analysis function-rows declared-key? program-row]
  (let [program-symbol (or (:seon.fn/sym program-row)
                           (:seon.test/sym program-row))
        first-party-functions
        (cond-> (into #{} (map :seon.fn/sym) function-rows)
          program-symbol (conj program-symbol))
        calls-by-caller
        (call-targets-by-caller analysis first-party-functions)
        references (references-by-caller analysis first-party-functions)
        used-keywords (keywords-by-holder analysis)
        writes (writes-by-writer analysis declared-key?)
        call-arities
        (call-arities-by-caller
         analysis
         calls-by-caller)
        program-facts
        (when program-symbol
          (let [qualified (symbol program-symbol)
                definition
                (some #(when (= program-symbol
                                (symbol (str (::analyzer/ns %))
                                        (str (::analyzer/name %))))
                         %)
                      (::analyzer/var-definitions analysis))
                subject (or (:seon.test/subject program-row)
                            (test-subject (::analyzer/meta definition)))]
            (cond-> {}
              (::analyzer/macro definition) (assoc :seon.fn/macro? true)
              (seq (get calls-by-caller program-symbol))
              (assoc :seon.fn/calls
                     (into #{}
                           (map symbol)
                           (get calls-by-caller program-symbol)))
              (seq (get references program-symbol))
              (assoc :seon.fn/references
                     (into #{} (map symbol)
                           (get references program-symbol)))
              (keyword-values used-keywords qualified)
              (assoc :seon.fn/keywords
                     (keyword-values used-keywords qualified))
              (write-refs writes qualified)
              (assoc :seon.fn/writes (write-refs writes qualified))
              (seq (get call-arities program-symbol))
              (assoc :seon.fn/call-arities
                     (into #{} (get call-arities program-symbol)))
              subject (assoc :seon.test/subject subject))))
        merged-row (when program-row
                     (merge (dissoc program-row :seon.fn/calls :seon.fn/references
                                    :seon.fn/keywords :seon.fn/writes :seon.fn/call-arities)
                            program-facts))]
    [(if program-row
       {}
       (let [calls (into #{}
                         (keep call-target)
                         (::analyzer/var-usages analysis))]
         (cond-> {} (seq calls) (assoc :seon.fn/calls calls))))
     merged-row]))

(defn analyze-forms
  "Analyze submitted forms in one kondo batch, returning form-local facts.

  A declaration row owns its edges. Without a declaration, the evaluation
  owns the edges in the first tuple member; no synthetic function is minted."
  {:malli/schema
   [:=> [:cat :seon.db/database-value
         [:vector [:map
                   [:seon.cluster.eval/source
                    :seon.cluster.eval/source]
                   [:seon.cluster.eval/ns
                    :seon.cluster.eval/ns]
                   [:seon.program/row {:optional true} :seon.program/row]]]]
    [:or [:vector [:tuple :map [:maybe :seon.program/row]]] :seon.error/value]]}
  [database requests]
  (let [resolved
        (mapv
         (fn [{source :seon.cluster.eval/source
               namespace-ref :seon.cluster.eval/ns
               :as request}]
           (let [namespace-row (db/pull database [:seon.ns/name] namespace-ref)]
             (if-let [namespace-name (:seon.ns/name namespace-row)]
               (assoc request :namespace-name namespace-name
                              :form-source source)
               (error/diagnostic
                {:seon.error/kind ::namespace-unresolvable
                 :seon.error/message
                 (str "Cannot analyze the form because its namespace reference "
                      (pr-str namespace-ref)
                      " does not resolve to :seon.ns/name.")
                 :seon.error/diagnostic-layer :program-analysis
                 :seon.error/diagnostic-operation 'seon.fn/analyze-forms
                 :seon.error/diagnostic-member :namespace-ref
                 :seon.error/diagnostic-expected :seon.ns/name
                 :seon.error/diagnostic-offending namespace-ref
                 :seon.error/diagnostic-cause ::namespace-unresolvable
                 :seon.error/diagnostic-evidence
                 {:seon.fn/namespace-ref namespace-ref
                  :seon.fn/namespace-row namespace-row}}))))
         requests)
        refusal (some #(when (:seon.error/kind %) %) resolved)]
    (if refusal
      refusal
      (let [{analysis :seon.fn/analysis
             spans :seon.fn/source-spans
             analyzed-source :seon.fn/source
             function-rows :seon.fn/function-rows}
            (runtime-analysis-batch database resolved)
            declared-key?
            (into #{}
                  (db/q '[:find [?key ...]
                          :where [_ :seon.schema/key ?key]]
                        database))]
        (mapv (fn [request [first-row last-row]]
                (analyzed-form
                 (source-analysis analysis first-row last-row)
                 function-rows
                 declared-key?
                 (some-> (:seon.program/row request)
                         (assoc :seon.program/analyzed-source-digest
                                (:seon.fn.file/digest (text-context analyzed-source))))))
              requests spans)))))

(defn analyze-form
  "Analyze one planned form through the static program-graph owner."
  {:malli/schema
   [:=>
    [:catn
     [:database :seon.db/database-value]
     [:source :seon.cluster.eval/source]
     [:namespace-ref :seon.cluster.eval/ns]
     [:program-row [:maybe :seon.program/row]]]
    [:or
     [:tuple
      [:map
       [:seon.fn/calls {:optional true} :seon.fn/calls]
       [:seon.fn/keywords {:optional true} :seon.fn/keywords]
       [:seon.test/subject {:optional true} :seon.test/subject]]
      [:maybe :seon.program/row]]
     :seon.error/value]]}
  [database source namespace-ref program-row]
  (let [result (analyze-forms
                database
                [(cond-> {:seon.cluster.eval/source source
                          :seon.cluster.eval/ns namespace-ref}
                   program-row (assoc :seon.program/row program-row))])]
    (if (:seon.error/kind result) result (first result))))

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

(def ^:private source-span-keys
  [::analyzer/filename ::analyzer/row ::analyzer/col
   ::analyzer/end-row ::analyzer/end-col])

(defn- source-span
  [entry]
  (select-keys entry source-span-keys))

(defn- external-usage-spans
  [analysis first-party-functions]
  (into #{}
        (comp
         (filter #(external-target? first-party-functions (usage-symbol %)))
         (map source-span))
        (::analyzer/var-usages analysis)))

(defn- admitted-external-finding?
  [external-spans finding]
  (and (= :unresolved-var (::analyzer/type finding))
       (contains? external-spans (source-span finding))))

(defn- publication-findings
  [analysis first-party-functions]
  (let [external-spans
        (external-usage-spans analysis first-party-functions)]
    (mapv #(cond-> %
             (or (not (load-refusal-finding? %))
                 (admitted-external-finding? external-spans %))
             (assoc ::analyzer/level :warning))
          (::analyzer/findings analysis))))

(defn- blocking-findings
  [analysis first-party-functions]
  (let [external-spans
        (external-usage-spans analysis first-party-functions)]
    (filterv #(and (load-refusal-finding? %)
                   (not (admitted-external-finding? external-spans %)))
             (::analyzer/findings analysis))))

(defn- assert-clean-analysis!
  [analysis first-party-functions]
  (let [findings (blocking-findings analysis first-party-functions)]
    (when (seq findings)
      (throw (ex-info (str "Static program analysis found blocking errors.\n"
                           (str/join
                            "\n"
                            (map (fn [finding]
                                   (str (::analyzer/filename finding) ":"
                                        (::analyzer/row finding) ":"
                                        (::analyzer/col finding) " "
                                        (::analyzer/type finding) " "
                                        (::analyzer/message finding)))
                                 findings)))
                      {:seon.error/kind ::index-refused
                       ::findings findings
                       :seon.fn/index-refused true})))))

(defn- analysis-rows-by-file
  "Rows by file, owning declared writes by the operation's own population.

  `declared-attributes` is the key set of the declaration population the
  operation resolved ONCE (see [[declaration-forms]]). Re-resolving it here
  re-read the authored resources a SECOND time per file, at 18 ms a call,
  against a population that cannot change while one operation runs
  (AGENTS.md 2.1)."
  ([analysis first-party-functions contexts declared-attributes]
   (analysis-rows-by-file analysis first-party-functions contexts declared-attributes {}))
  ([analysis first-party-functions contexts declared-attributes forms]
  (let [used-keywords (keywords-by-holder analysis)
        schema-targets (declared-function-targets forms (vals forms) first-party-functions)
        keywords-by-file (group-by ::analyzer/filename (::analyzer/keywords analysis))
        declared-calls
        (reduce-kv
         (fn [calls filename context]
           (let [values (map kondo.utils/sexpr
                             (:children (kondo.utils/parse-string-all (:text context))))
                 targets (merge-with set/union schema-targets
                                     (declared-function-targets forms values first-party-functions))
                 holders (keywords-by-holder
                          {::analyzer/keywords (get keywords-by-file filename)})]
             (merge-with set/union calls (declared-calls-by-caller holders targets))))
         {} contexts)
        calls-by-caller
        (merge-with set/union (call-targets-by-caller analysis first-party-functions)
                    declared-calls)
        references (references-by-caller analysis first-party-functions)
        declared-callers (first-party-function-symbols analysis)
        unresolved-by-file
        (into {} (map (fn [[filename usages]]
                        [filename (get (references-by-caller
                                        {::analyzer/var-usages usages}
                                        first-party-functions declared-callers) nil)]))
              (group-by ::analyzer/filename (::analyzer/var-usages analysis)))
        edges {:calls-by-caller calls-by-caller
               :references references
               :used-keywords used-keywords
               :writes (writes-by-writer analysis declared-attributes)
               :call-arities (call-arities-by-caller analysis calls-by-caller)}
        namespace-contexts
        (into {}
              (map (fn [entry]
                     [(::analyzer/name entry)
                      (assoc (namespace-context contexts entry)
                             :namespace-meta (::analyzer/meta entry))]))
              (::analyzer/namespace-definitions analysis))]
    (reduce
     (fn [rows entry]
       (if-let [row (if (::analyzer/ns entry)
                      (var-row contexts namespace-contexts edges entry)
                      (cond-> (namespace-row contexts
                                              (get namespace-contexts (::analyzer/name entry))
                                              entry)
                        (seq (get unresolved-by-file (::analyzer/filename entry)))
                        (assoc :seon.fn/unresolved-references
                               (get unresolved-by-file (::analyzer/filename entry)))))]
         (update rows (::analyzer/filename entry) (fnil conj []) row)
         rows))
     {}
     (concat (::analyzer/namespace-definitions analysis)
             (::analyzer/var-definitions analysis))))))

(defn source-rows
  "Construct source declarations through the indexer's analysis and row owners.

  The supplied namespace is the caller's current resolver context, including
  uncommitted aliases. Prelude declarations resolve calls but never become
  submitted rows. A submitted form has no file coordinates."
  {:malli/schema
   [:=> [:cat :seon.db/database-value :seon.program/shapes
         :seon.ns/ns
         :string [:set :keyword]]
     :seon.program/rows]}
  [database row-shapes namespace-row source declared-attributes]
  (let [namespace-name (:seon.ns/name namespace-row)
        {analysis :seon.fn/analysis
         text :seon.fn/source
         spans :seon.fn/source-spans
         function-rows :seon.fn/function-rows}
        (runtime-analysis-batch
         database [{:namespace-name namespace-name
                    :form-source source
                    :seon.fn/namespace-row namespace-row}])
        [first-row last-row] (first spans)
        functions (into (first-party-function-symbols analysis)
                        (map :seon.fn/sym) function-rows)
        rows (analysis-rows-by-file
              (source-analysis analysis first-row last-row)
              functions {"<stdin>" (text-context text)} declared-attributes
              (:seon.schema.projection/forms (db/carried-projection database)))]
    (into []
          (comp
           (filter #(or (:seon.fn/sym %) (:seon.test/sym %)))
           (map #(program/declaration-row
                  (program/canonical-row
                   row-shapes (dissoc % :seon.fn/file :seon.fn/form-span))
                  :all :agent)))
          (get rows "<stdin>"))))

(defn- lint-rows
  [directory file context rows findings]
  (let [path (.getCanonicalPath ^java.io.File file)
        declarations (filter :seon.fn/form-span rows)]
    (mapv
     (fn [finding]
       ;; clj-kondo reports positions as JDK Integers; these facts are longs.
       (let [row (long (::analyzer/row finding))
             col (long (::analyzer/col finding))
             position (first (exact-form-span
                              {path context}
                              {::analyzer/filename path
                               ::analyzer/row row ::analyzer/col col
                               ::analyzer/end-row row ::analyzer/end-col col}))
             ;; ONE containment rule, shared with the effect writer's
             ;; write-back attribution: a finding outside every span is
             ;; the typed refusal, and this caller reads it as a
             ;; file-scoped finding rather than fabricating a function.
             found (program/declaration-at declarations position)
             declaration (when-not (:seon.error/kind found) found)
             program-identity (program/row-identity declaration)
             owner (if program-identity (symbol (second program-identity)) (fs/relative-path directory path))
             finding-type (::analyzer/type finding)]
         (cond-> {:seon.lint/id (id/id [owner finding-type row col])
                  :seon.lint/file [:seon.fn.file/relative-path path]
                  :seon.lint/type finding-type
                  :seon.lint/level (::analyzer/level finding)
                  :seon.lint/message (::analyzer/message finding)
                  :seon.lint/row row :seon.lint/col col}
           program-identity (assoc :seon.lint/fn program-identity))))
     findings)))

(defn- declaration-forms
  "The declaration population ONE indexing operation owns program rows by.

  Resolved once and handed to every row: `seon.program`'s per-row questions
  answer from the population their caller holds (AGENTS.md 2.1), and a
  population the request supplies — an adoption's own projection, a
  regression's extra schema — decides ownership instead of anything this
  process cached. With none supplied the AUTHORED resources answer, which is
  the one classpath merge per operation the fallback advisory names as its
  floor."
  [request]
  (or (:seon.schema.projection/forms request)
      (schema.edn/packaged-forms)))

(defn- artifact
  [row-shapes directory file root context rows findings]
  (let [canonical-path (fs/relative-path directory (.getCanonicalPath ^java.io.File file))
        file-row (cond-> {:seon.fn.file/relative-path canonical-path
                          :seon.fn.file/digest (:seon.fn.file/digest context)}
                   root (assoc :seon.fn.file/relative-root (fs/relative-path directory root)))
        file-row (cond-> file-row
                   (some :seon.fn/unresolved-references rows)
                   (assoc :seon.fn/unresolved-references
                          (into #{} (mapcat :seon.fn/unresolved-references) rows)))
        rows (mapv #(dissoc % :seon.fn/unresolved-references) rows)
        rows (into (into [file-row] rows)
                   (lint-rows directory file context rows findings))
        rows (walk/postwalk
              (fn [value]
                (if (and (vector? value)
                         (= :seon.fn.file/relative-path (first value))
                         (= 2 (count value)))
                  [:seon.fn.file/relative-path (fs/relative-path directory (second value))]
                  value))
              rows)
        findings (mapv (fn [finding]
                         (-> finding
                             (assoc :seon.fn.finding/relative-path
                                    (fs/relative-path directory (::analyzer/filename finding)))
                             (dissoc ::analyzer/filename)))
                       findings)
        canonical-rows
        (mapv #(cond-> (assoc (program/canonical-row row-shapes %)
                              :seon.schema.admission/source :core)
                 (:seon.fn/calls %)
                 (update :seon.fn/calls set))
              rows)]
    (cond->
     {:seon.fn.file/relative-path canonical-path
      :seon.fn.file/digest (:seon.fn.file/digest context)
      :seon.fn.file/rows canonical-rows
      :seon.fn.file/identities
      (->> canonical-rows
           (keep program/row-identity)
           (sort-by pr-str)
           vec)}
      (seq findings) (assoc :seon.fn.file/findings findings))))

(def ^:private request-symbol 'seon.effect/request!)

(def ^:private declared-reference-rules
  '[[(declared-edge ?caller ?target)
     [?caller :seon.fn/sym]
     [?caller :seon.effect/capability ?symbol]
     [?target :seon.fn/sym ?symbol]]
    [(declared-edge ?caller ?target)
     [?declaration :seon.fn/reference-to :seon.fn/sym]
     [?declaration :seon.schema/key ?attribute]
     [?caller ?attribute ?target]
     [?caller :seon.fn/sym]
     [?target :seon.fn/sym]]
    [(declared-edge ?caller ?target)
     [?declaration :seon.fn/reference-to :seon.fn/sym]
     [?declaration :seon.schema/key ?attribute]
     [?holder ?attribute ?target]
     (not [?holder :seon.fn/sym])
     [?target :seon.fn/sym]
     [?caller :seon.fn/keywords ?attribute]]])

(def ^:private test-reach-rules
  (into declared-reference-rules
    '[[(call-edge ?function ?target)
       [?function :seon.fn/calls ?target-symbol]
       [?target :seon.fn/sym ?target-symbol]]
      [(call-edge ?function ?target)
       (declared-edge ?function ?target)]
      [(call-edge ?function ?target)
       [?function :seon.fn/references ?target-symbol]
       [?target :seon.fn/sym ?target-symbol]]
      [(call-edge ?test ?target)
       [?file :seon.fn/unresolved-references ?symbol]
       [?target :seon.fn/sym ?symbol]
       [?test :seon.fn/file ?file]
       [?test :seon.test/sym]]
      [(tested ?target)
       [?test :seon.test/sym]
       (call-edge ?test ?target)]
      [(tested ?target)
       [?test :seon.test/sym]
       [?test :seon.test/subject ?target-symbol]
       [?target :seon.fn/sym ?target-symbol]]
      [(tested ?target)
       (tested ?caller)
       (call-edge ?caller ?target)]
      [(test-currently-failing ?test)
       [?test :seon.test/fail-count ?count]
       [(pos? ?count)]]
      [(test-currently-failing ?test)
       [?test :seon.test/error-count ?count]
       [(pos? ?count)]]
      [(failing-function ?target)
       (test-currently-failing ?test)
       (call-edge ?test ?target)]
      [(failing-function ?target)
       (test-currently-failing ?test)
       [?test :seon.test/subject ?target-symbol]
       [?target :seon.fn/sym ?target-symbol]]
      [(failing-function ?target)
       (failing-function ?caller)
       (call-edge ?caller ?target)]]))

(defn- declared-reference-edges
  "A function declaration owns its declared function references. References
  on data rows retain conservative attribute-consumer reach because those
  rows do not identify a calling function. Keyword mentions never replace
  the known owner of a function declaration."
  {:malli/schema [:=> [:cat :seon.db/database-value]
                  [:or [:set [:tuple :int :int]] :seon.db/invalid-read-error :seon.schema/missing-projection-error]]}
  [database]
  (db/q '[:find ?caller ?target
          :in $ %
          :where
          (declared-edge ?caller ?target)]
        database declared-reference-rules))

(defn- gate-set-in
  "Reverse-walk names, including a seed whose definition has been removed."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       [:map-of :int :qualified-symbol] [:set :qualified-symbol]
                       [:map-of :qualified-symbol [:set :qualified-symbol]]
                       [:sequential :qualified-symbol]]
                  [:or [:vector :seon.test/sym] :seon.db/invalid-read-error :seon.schema/missing-projection-error]]}
  [database identities tests incoming seeds]
  (loop [pending (vec seeds) seen #{}]
    (if-let [target (peek pending)]
      (if (seen target)
        (recur (pop pending) seen)
        (let [calls (db/datoms database :avet :seon.fn/calls target)
              references (db/datoms database :avet :seon.fn/references target)
              subjects (db/datoms database :avet :seon.test/subject target)
              refusal (some #(when (error/error? %) %) [calls references subjects])]
          (if refusal
            refusal
            (let [referrers (into (get incoming target #{})
                                 (keep #(get identities (:e %)))
                                 (concat calls references subjects))]
              (recur (into (pop pending) referrers) (conj seen target))))))
      (vec (sort (set/intersection tests seen))))))

(defn- gate-sets-in
  "Select tests through surviving named edges in one immutable database.
   Acquire identity and genuine declaration-ref joins once per operation."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       [:sequential :qualified-symbol] :boolean]
                  [:or [:vector :seon.test/sym]
                   [:map-of :qualified-symbol [:vector :seon.test/sym]] :seon.db/invalid-read-error :seon.schema/missing-projection-error]]}
  [database function-symbols union?]
  (let [identity-rows (db/q '[:find ?entity ?symbol
                             :where (or [?entity :seon.fn/sym ?symbol]
                                        [?entity :seon.test/sym ?symbol])] database)
        test-symbols (db/q '[:find [?symbol ...]
                            :where [_ :seon.test/sym ?symbol]] database)
        declared (declared-reference-edges database)
        file-references (db/q '[:find ?test ?symbol
                                :where [?file :seon.fn/unresolved-references ?symbol]
                                       [?test :seon.fn/file ?file]
                                       [?test :seon.test/sym]] database)
        handlers (db/q '[:find ?caller ?symbol
                         :where [?caller :seon.fn/sym]
                                [?caller :seon.effect/capability ?symbol]] database)
        refusal (some #(when (error/error? %) %)
                      [identity-rows test-symbols declared file-references handlers])]
    (if refusal
      refusal
      (let [identities (into {} identity-rows)
            incoming (reduce (fn [result [caller target]]
                               (if-let [caller-symbol (get identities caller)]
                                 (update result target (fnil conj #{}) caller-symbol)
                                 result))
                             {} (concat (map (fn [[caller target]]
                                               [caller (get identities target)]) declared)
                                        file-references handlers))]
        (if union?
          (gate-set-in database identities (set test-symbols) incoming function-symbols)
          (reduce (fn [result function-symbol]
                  (let [selected (gate-set-in database identities (set test-symbols)
                                              incoming [function-symbol])]
                    (if (error/error? selected)
                      (reduced selected)
                      (assoc result function-symbol selected))))
                {} (distinct function-symbols)))))))

(defn gate-sets
  "Select tests through the shared reverse graph. The map arity seeds one
  frontier with all changed symbols; the positional arity retains per-seed results."
  {:malli/schema
   [:function
    [:=> [:cat :seon.fn/gate-request]
     [:or [:vector :seon.test/sym] :seon.db/invalid-read-error :seon.schema/missing-projection-error]]
    [:=> [:cat :seon.db/database-value [:sequential :seon.fn/sym]]
     [:or [:map-of :seon.fn/sym [:vector :seon.test/sym]] :seon.db/invalid-read-error :seon.schema/missing-projection-error]]]}
  ([{database :seon.db/db seeds :seon.fn/seeds}]
   (gate-sets-in database (vec seeds) true))
  ([database function-symbols]
   (gate-sets-in database function-symbols false)))

(defn unresolved-callers
  "Report named calls with no current definition, including external names.
   The analyzed population count makes an empty observation explicit. Missing
   names are evidence; this query does not invent target definitions. Recorded
   test reach to absent names is reported separately as stale historical evidence."
  {:malli/schema [:=> [:cat :seon.db/database-value]
                  [:or :seon.program/unresolved-report :seon.error/value]]}
  [database]
  (let [rows (db/q '[:find ?attribute ?caller ?callee
                     :in $ [?attribute ...]
                     :where [?entity ?attribute ?caller]
                            [?entity :seon.program/analyzed-source-digest]
                            [?entity :seon.fn/calls ?callee]
                            (not-join [?callee] [_ :seon.fn/sym ?callee])]
                   database [:seon.fn/sym :seon.test/sym])
        stale (db/q '[:find ?test ?callee
                      :where [?entity :seon.test/sym ?test]
                             [?entity :seon.test/reach ?callee]
                             (not-join [?callee] [_ :seon.fn/sym ?callee])]
                    database)
        population (db/q '[:find (count ?entity) .
                           :where [?entity :seon.program/analyzed-source-digest]] database)]
    (or (when (:seon.error/kind rows) rows)
        (when (:seon.error/kind stale) stale)
        (when (map? population) population)
        {:seon.db/basis-t (db/basis-t database)
         :seon.program/analyzed-count (or population 0)
         :seon.program/stale-test-reach
         (mapv (fn [[test callee]] {:seon.test/sym test :seon.fn/callee callee})
               (sort-by pr-str stale))
         :seon.program/unresolved-callers
         (mapv (fn [[attribute caller callee]]
                 {:seon.program/identity [attribute caller]
                  :seon.fn/callee callee})
               (sort-by pr-str rows))})))

(defn gate-set
  "Tests gating one function identity. Unknown identities match only an
  explicitly pending subject; unresolved file references select that file's
  tests. Use gate-sets when one operation asks about several identities."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.fn/sym]
                  [:or [:vector :seon.test/sym] :seon.db/invalid-read-error :seon.schema/missing-projection-error]]}
  [database function-symbol]
  (let [result (gate-sets database [function-symbol])]
    (if (error/error? result) result (get result function-symbol))))

(defn tests-reaching
  "Compatibility spelling for the shared gate-set derivation."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.fn/sym]
                  [:or [:vector :seon.test/sym] :seon.db/invalid-read-error :seon.schema/missing-projection-error]]}
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
               (failing-function ?function)
               [?function :seon.fn/sym ?function-symbol]]
             database test-reach-rules)
       sort
       vec))

(defn functions-without-tests
  "Indexed public functions reached by no indexed test.

  Coverage follows the same call and reference relation as `tests-reaching`.
  Derive the union reached from tests directly, without all function pairs."
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
                    (tested ?function)
                    [?function :seon.fn/sym ?function-symbol]
                    [?function :seon.fn/private? false]]
                  database test-reach-rules))]
    (->> (set/difference public-functions tested-functions)
         sort
         vec)))

(defn contract-findings
  "Rank incomplete function contracts by their number of current callers.

  The existing schema-admission checker owns permissive-position
  classification. This query adds graph-only evidence that admission cannot
  supply: missing specs, current caller counts, and registered attributes the
  Datahike bridge cannot store. Every function row participates, private
  included."
  {:malli/schema [:=> [:cat :seon.db/database-value]
                  [:or :seon.fn.contract/report :seon.error/value]]}
  [database]
  (let [function-symbols (db/q '[:find [?symbol ...]
                                 :where
                                 [_ :seon.fn/sym ?symbol]]
                               database)
        specs (db/q '[:find ?symbol ?spec
                      :where
                      [?function :seon.fn/sym ?symbol]
                      [?function :seon.fn/spec ?spec]]
                    database)
        caller-counts (db/q '[:find ?target (count ?caller)
                              :where
                              [?caller :seon.fn/sym]
                              [?caller :seon.fn/calls ?target]]
                            database)
        projection (or (db/carried-projection database)
                       (schema/projection-from-database database))]
    (or
     (when (:seon.error/kind function-symbols) function-symbols)
     (when (:seon.error/kind specs) specs)
     (when (:seon.error/kind caller-counts) caller-counts)
     (let [forms (:seon.schema.projection/forms projection)
           stored-attributes (set (schema.form/database-attributes forms))
           specs (into {} specs)
           caller-counts (into {} caller-counts)
           body (fn [form]
                  (let [xs (rest form)]
                    (if (map? (first xs)) (rest xs) xs)))
           schema-children
           (fn [form]
             (when (vector? form)
               (let [tag (first form)
                     children (vec (body form))]
                 (cond
                   (#{:enum := :fn :ref :re} tag) []
                   (#{:map :mapn :catn :altn :orn :multi} tag)
                   (into [] (keep #(when (vector? %) (peek %))) children)
                   :else children))))
           schema-nodes
           (fn [slot]
             (loop [pending [slot] nodes []]
               (if-let [form (peek pending)]
                 (recur (into (pop pending) (schema-children form))
                        (conj nodes form))
                 nodes)))
           structural-findings
           (fn [slot]
             (into []
                   (keep (fn [form]
                           (cond
                             (= :map form)
                             [:seon.fn.contract.finding/bare-map form]

                             (and (vector? form) (= :maybe (first form)))
                             [:seon.fn.contract.finding/maybe form])))
                   (schema-nodes slot)))
           permissive-findings
           (fn [slot guarded?]
             (into []
                   (comp
                    (remove :seon.schema/justified?)
                    (keep
                     (fn [{kind :seon.schema.advisory/kind
                           form :seon.schema/definition}]
                       (let [finding
                             (case kind
                               :undefined
                               (case form
                                 :any :seon.fn.contract.finding/any
                                 :some :seon.fn.contract.finding/some
                                 nil)
                               :bare-value
                               :seon.fn.contract.finding/bare-value
                               :value-tail
                               :seon.fn.contract.finding/bare-value
                               :unguarded-tail
                               (when-not guarded?
                                 :seon.fn.contract.finding/unguarded-variadic)
                               nil)]
                         (when finding [finding form])))))
                   (schema.internal/permissive-positions
                    {:seon.schema/definition slot
                     :seon.schema/forms forms})))
           slot-findings
           (fn [slot position guarded?]
             (let [permissive (permissive-findings slot guarded?)
                   structural (structural-findings slot)
                   unguarded
                   (when (and (not guarded?) (vector? slot)
                              (#{:* :+ :repeat} (first slot))
                              (not-any? #(= :seon.fn.contract.finding/unguarded-variadic
                                            (first %))
                                        permissive))
                     [[:seon.fn.contract.finding/unguarded-variadic slot]])
                   unstorable
                   (into []
                         (comp
                          (filter stored-attributes)
                          (remove #(schema.datahike/storable-attribute-in?
                                    projection %))
                          (map #(vector
                                 :seon.fn.contract.finding/unstorable-attribute
                                 %)))
                         (schema-nodes slot))]
               (mapv (fn [[finding form]]
                       {:seon.fn.contract/position position
                        :seon.fn.contract/form form
                        :seon.fn.contract/finding finding})
                     (concat permissive structural unguarded unstorable))))
           arity-findings
           (fn [spec]
             (let [arities (if (and (vector? spec) (= :function (first spec)))
                             (body spec)
                             [spec])]
               (into []
                     (mapcat
                      (fn [arity]
                        (let [[input output guard] (body arity)
                              guarded? (some? guard)
                              input-body (when (vector? input) (vec (body input)))
                              slots (case (first input)
                                      :cat input-body
                                      :catn (mapv peek input-body)
                                      [input])]
                          (concat
                           (mapcat (fn [index slot]
                                     (slot-findings
                                      slot
                                      [:seon.fn.contract.position/input index]
                                      guarded?))
                                   (range) slots)
                           (slot-findings output
                                          :seon.fn.contract.position/output
                                          guarded?))))
                      arities))))]
       (->> function-symbols
            (mapcat
             (fn [function-symbol]
               (let [caller-count (long (get caller-counts function-symbol 0))
                     spec-string (get specs function-symbol)
                     findings
                     (if spec-string
                       (arity-findings (edn/read-string spec-string))
                       [{:seon.fn.contract/position
                         :seon.fn.contract.position/contract
                         :seon.fn.contract/form :seon.fn.contract/missing
                         :seon.fn.contract/finding
                         :seon.fn.contract.finding/missing-spec}])]
                 (map #(assoc %
                              :seon.fn/sym function-symbol
                              :seon.fn.contract/caller-count caller-count)
                      findings))))
            distinct
            (sort-by (juxt (comp - :seon.fn.contract/caller-count)
                           (comp str :seon.fn/sym)
                           (comp pr-str :seon.fn.contract/position)
                           (comp str :seon.fn.contract/finding)
                           (comp pr-str :seon.fn.contract/form)))
            vec)))))

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

(defn arity-mismatches
  "Call sites whose source count no prepared arity of the callee admits.
   Uses the same report as final write admission, including coverage counts."
  {:malli/schema [:=> [:cat :seon.db/database-value]
                  [:or :seon.fn/arity-mismatch-report :seon.error/value]]}
  [database]
  (db/arity-mismatches database))

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
                  [?caller :seon.fn/calls ?called-symbol]]
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
     :seon.fn.output/paths paths}))

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
        (let [called (vec
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
      (let [handler (get rows-by-symbol handler-symbol)]
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
                 (contains? calls request-symbol))
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
           [:seon.fn/source-path [:string {:min 1}]]
           [:seon.fn.file/first-party-functions
            [:vector :qualified-symbol]]
           [:seon.fn/roots {:optional true} :seon.fn/roots]
           [:seon.fn/root {:optional true} :string]
           [:seon.schema.projection/forms {:optional true} :map]]]
    :seon.fn.file/artifact]}
  [{path :seon.fn/source-path
    known-functions :seon.fn.file/first-party-functions
    roots :seon.fn/roots :as request}]
  (let [directory (or (:seon.fn/root request) (fs/source-directory))
        file (rooted-file directory path)]
    (when-not (source-file? file)
      (throw (ex-info "A file artifact requires one existing Clojure file."
                      {:seon.error/kind ::index-refused
                       :seon.fn/source-path (.getCanonicalPath file) :seon.fn/index-refused true})))
    (let [canonical-path (.getCanonicalPath file)
          contexts (source-contexts [file])
          analysis (analyzer/analyze
                    {::analyzer/sources
                     {canonical-path (:text (get contexts canonical-path))}})
          first-party-functions
          (into (set known-functions)
                (first-party-function-symbols analysis))
          findings (publication-findings analysis first-party-functions)]
      (assert-clean-analysis! analysis first-party-functions)
      ;; ONE declaration world per operation: the population resolved once,
      ;; its shapes derived once, both carried to every row (AGENTS.md 2.1).
      (let [forms (declaration-forms request)]
        (artifact (program/shapes-in forms)
                  directory file
                  (containing-root directory (or roots source-roots) file)
                  (get contexts canonical-path)
                  (get (analysis-rows-by-file analysis first-party-functions
                                              contexts (set (keys forms)) forms)
                       canonical-path
                       [])
                  findings)))))

(defn artifact-by-path
  "The manifest artifact for a relative or absolute path under its root."
  {:malli/schema
   [:=>
    [:catn
     [:manifest :seon.fn.manifest/manifest]
     [:canonical-path [:string {:min 1}]]]
    [:maybe :seon.fn.file/artifact]]}
  [manifest canonical-path]
  (some #(when (= (fs/relative-path (:seon.fn.manifest/root manifest) canonical-path)
                    (:seon.fn.file/relative-path %)) %)
        (:seon.fn.manifest/artifacts manifest)))

(defn manifest-function-symbols
  "Sorted first-party function symbols contributed by a manifest."
  {:malli/schema [:=> [:catn [:manifest :seon.fn.manifest/manifest]]
                  [:vector :qualified-symbol]]}
  [manifest]
  (->> (:seon.fn.manifest/identities manifest)
       (filter #(= :seon.fn/sym (first %)))
       (map second)
       distinct
       sort
       vec))

(defn- manifest-data
  [directory roots artifacts]
  (let [artifacts (->> artifacts
                       (sort-by :seon.fn.file/relative-path)
                       vec
                       assert-capability-contracts!)
        findings (into [] (mapcat :seon.fn.file/findings) artifacts)]
    (cond->
     {:seon.fn.manifest/root directory
      :seon.fn.manifest/relative-roots roots
      :seon.fn.manifest/digest
      (sha-256 (.getBytes
                (pr-str (mapv (juxt :seon.fn.file/relative-path
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
                   (frequencies (map :seon.fn.file/relative-path desired-artifacts)))]
    (throw (ex-info "Manifest replacement carries a duplicate file path."
                    {:seon.error/kind ::index-refused
                     :seon.fn.file/relative-path duplicate-path :seon.fn/index-refused true})))
  (let [desired-by-path
        (into {} (map (juxt :seon.fn.file/relative-path identity)) desired-artifacts)
        retained
        (remove #(contains? desired-by-path (:seon.fn.file/relative-path %))
                (:seon.fn.manifest/artifacts manifest))]
    (manifest-data (:seon.fn.manifest/root manifest)
                   (:seon.fn.manifest/relative-roots manifest)
                   (concat retained desired-artifacts))))

(defn build-manifest
  "Build deterministic artifacts for the complete first-party program."
  {:malli/schema
   [:=> [:cat [:map
              [:seon.fn/roots :seon.fn/roots]
              [:seon.fn/root {:optional true} :string]
              [:seon.schema.projection/forms {:optional true} :map]]]
    :seon.fn.manifest/manifest]}
  [request]
  (let [forms (declaration-forms request)
        row-shapes (program/shapes-in forms)
        directory (fs/absolute-path (fs/source-directory) (or (:seon.fn/root request) "."))
        roots (:seon.fn/roots request)
        files (source-files directory roots)
        contexts (source-contexts files)
        analysis (analyzer/analyze
                  {::analyzer/sources (update-vals contexts :text)})
        first-party-functions (first-party-function-symbols analysis)
        _ (assert-clean-analysis! analysis first-party-functions)
        findings-by-file
        (group-by ::analyzer/filename
                  (publication-findings analysis first-party-functions))
        rows-by-file
        (analysis-rows-by-file analysis first-party-functions contexts
                               (set (keys forms)) forms)
        artifacts
        (mapv (fn [file]
                (artifact row-shapes
                          directory file
                          (containing-root directory roots file)
                          (get contexts (.getCanonicalPath ^java.io.File file))
                          (get rows-by-file
                               (.getCanonicalPath ^java.io.File file)
                               [])
                          (get findings-by-file
                               (.getCanonicalPath ^java.io.File file)
                               [])))
              files)
        manifest
        (manifest-data
         directory
         (mapv (partial fs/relative-path directory) roots)
         artifacts)]
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
  [current-rows desired unsafe-attributes]
  (into
   []
   (keep
    (fn [desired-row]
      (let [program-identity (program/row-identity desired-row)
            current-row (get current-rows program-identity)
            changed (changed-row-attributes current-row desired-row)]
        (when (seq changed)
          (apply dissoc desired-row unsafe-attributes)))))
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
               (not= (:seon.fn.file/relative-path current)
                     (:seon.fn.file/relative-path desired)))
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
                (:seon.fn.file/relative-path current)
                :seon.fn.change/desired-path
                (:seon.fn.file/relative-path desired)
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
       :seon.fn.change/relative-path (:seon.fn.file/relative-path desired)
       :seon.fn.change/digest (:seon.fn.file/digest desired)
       ;; The artifact is the complete analyzed file projection used to plan
       ;; the next edit. It must not be confused with the transaction delta.
       :seon.fn.change/artifact desired
       ;; Changed rows retain their scalar declaration, including required
       ;; admission provenance and namespace refs. Omit unchanged components
       ;; and many-valued attributes so publication cannot recreate children.
       :seon.fn.change/rows
       (scalar-upsert-rows current-rows desired unsafe-attributes)
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
        predicate-functions (schema/predicate-functions-in projection)
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
        predicate-functions (schema/predicate-functions-in projection)
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
                              [:seon.program/analyzed-source-digest
                               {:seon.fn/arities
                                [:seon.fn.arity/argument-count
                                 :seon.fn.arity/input-schema
                                 :seon.fn.arity/return-schema]}]
                              function)
                 arities (:seon.fn/arities row)]
             (or (nil? (:seon.program/analyzed-source-digest row))
                 (empty? arities)
                 (some #(or (not (contains? % :seon.fn.arity/argument-count))
                            (not (contains? % :seon.fn.arity/return-schema))
                            (not (contains? % :seon.fn.arity/input-schema)))
                       arities))))
         contracted)
        tx-data
        (into
         []
         (mapcat
          (fn [[function function-symbol spec source arglists namespace-name]]
            (let [current (db/pull db [:seon.fn/arities]
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
                     [:seon.fn/arities])
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
                source-rows)]
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
           (into (vec source-only) canonical-schemas))
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

(defn- index-tempids
  [rows identity-attributes]
  (let [identities
        (into #{}
              (comp
               (filter map?)
               (mapcat (fn [entity]
                         (let [identities
                               (keep (fn [attribute]
                                       (when-let [entry (find entity attribute)]
                                         [attribute (val entry)]))
                                     identity-attributes)]
                           (when (< 1 (count identities))
                             (throw
                              (ex-info
                               "Program indexing found multiple entity identities."
                               {:seon.error/kind ::index-refused
                                ::identity (vec identities)
                                :seon.fn/index-refused true})))
                           identities))))
              (mapcat #(filter map? (tree-seq coll? seq %)) rows))]
    (into {}
          (map-indexed (fn [index program-identity]
                         [program-identity (str "seon.fn.index/" index)]))
          (map second (sort-by first (map (juxt pr-str identity) identities))))))

(defn- compile-index-transaction
  "Flatten one program population through transaction-local identities.

  Datahike lookup refs resolve against the database before the transaction;
  string tempids instead let namespace, declaration, shape, and call refs all
  resolve inside one transaction. Identified nested maps are emitted once per
  population, rather than normalized again at every owning declaration."
  [projection rows identity-attributes]
  (let [identity-attribute-set (set identity-attributes)
        tempids (index-tempids rows identity-attributes)
        ref-attribute?
        (memoize
         (fn [attribute]
           (and (qualified-keyword? attribute)
                (schema.datahike/storable-attribute-in? projection attribute)
                (= :db.type/ref
                   (:db/valueType
                    (schema.datahike/malli->datahike-attr-in
                     projection attribute))))))
        entity-id
        (fn [entity]
          (or (some (fn [attribute]
                      (when-let [entry (find entity attribute)]
                        (get tempids [attribute (val entry)])))
                    identity-attributes)
              (let [db-id (:db/id entity)]
                (when (or (string? db-id)
                          (number? db-id)
                          (keyword? db-id)
                          (and (vector? db-id) (= 2 (count db-id))))
                  db-id))))
        lookup-tempid
        (fn [value]
          (when (and (vector? value) (= 2 (count value)))
            (get tempids value)))
        entities (volatile! {})]
    (letfn [(rewrite-reference [value]
              (cond
                (lookup-tempid value) (lookup-tempid value)
                (map? value) (rewrite-entity value)
                (vector? value) (mapv rewrite-reference value)
                (set? value) (into #{} (map rewrite-reference) value)
                (seq? value) (map rewrite-reference value)
                :else value))
            (rewrite-entity [value]
              (let [eid (entity-id value)
                    entity
                    (reduce-kv (fn [result attribute child]
                                 (cond
                                   (= :db/id attribute) result
                                   (contains? identity-attribute-set attribute)
                                   result
                                   :else
                                   (assoc result attribute
                                          (if (ref-attribute? attribute)
                                            (rewrite-reference child)
                                            child))))
                               (cond-> (empty value)
                                 eid (assoc :db/id eid))
                               value)]
                (if eid
                  (do
                    (when-let [prior (get @entities eid)]
                      (when-not (= prior entity)
                        (throw
                         (ex-info
                          "Program indexing found conflicting entity maps."
                          {:seon.error/kind ::index-refused
                           ::identity eid
                           :seon.fn/index-refused true}))))
                    (vswap! entities assoc eid entity)
                    eid)
                  entity)))]
      (let [keyword-operations
            (into []
                  (comp
                   (mapcat keyword-facts)
                   (map (fn [[operation program-identity attribute value]]
                          [operation (or (get tempids program-identity)
                                         program-identity)
                           attribute value])))
                  rows)]
        (doseq [row rows]
          (rewrite-entity (dissoc row :seon.fn/keywords)))
        {:seon.fn/index-entities
         (->> @entities (sort-by key) (mapv val))
         :seon.fn/index-identity-operations
         (into []
               (map (fn [[[attribute value] tempid]]
                      [:db/add tempid attribute value]))
               (sort-by (comp pr-str key) tempids))
         :seon.fn/index-keyword-operations keyword-operations}))))

(defn- normalized-index-row
  "Compare stored refs by identity and anonymous components by their values."
  [row-shapes database row identity-attributes entity]
  (let [row-identity
        (fn [row]
          (some (fn [attribute]
                  (when-let [entry (find row attribute)]
                    [attribute (val entry)]))
                identity-attributes))]
    (letfn [(normalize-many [values]
              (reduce (fn [result value]
                        (let [normalized (normalize-value value)]
                          (if (error/error? normalized)
                            (reduced normalized)
                            (conj result normalized))))
                      #{} values))
            (normalize-map [row]
              (if (error/error? row)
                row
                (reduce-kv
                 (fn [result attribute value]
                   (if (= :db/id attribute)
                     result
                     (let [normalized
                           (if (= :db.cardinality/many
                                  (get-in (:schema database)
                                          [attribute :db/cardinality]))
                             (normalize-many value)
                             (normalize-value value))]
                       (if (error/error? normalized)
                         (reduced normalized)
                         (assoc result attribute normalized)))))
                 {} row)))
            (normalize-value [value]
              (cond
                (map? value)
                (let [expanded (if (= #{:db/id} (set (keys value)))
                                 (entity (:db/id value))
                                 value)]
                  (or (row-identity expanded) (normalize-map expanded)))

                (vector? value) (mapv normalize-value value)
                (set? value) (into #{} (map normalize-value) value)
                :else value))]
      (normalize-map (program/canonical-row row-shapes row)))))

(defn- reconcile-tx-in
  "Replace source definitions, owning rows by the shapes `row-shapes` carries."
  {:malli/schema
   [:=> [:cat :seon.program/shapes :seon.db/database-value
         [:vector :map] [:sequential :seon.db/ref]]
    [:or :seon.db/tx-data :seon.error/value]]}
  [row-shapes database rows previous-identities]
  (let [projection (or (db/carried-projection database)
                       (schema/projection-from-database database))
        identity-attributes (db/identity-attributes database)
        entity (memoize #(db/pull database '[*] %))
        desired-identities (into #{} (map program/row-identity) rows)
        removed (remove desired-identities previous-identities)
        desired (vec rows)]
    (loop [pending desired changes []]
      (if-let [row (first pending)]
        (let [current (entity (program/row-identity row))]
          (if (error/error? current)
            current
            (let [normalized-current
                  (normalized-index-row row-shapes database current
                                        identity-attributes entity)
                  normalized-desired
                  (normalized-index-row row-shapes database row
                                        identity-attributes entity)
                  refusal (some #(when (error/error? %) %)
                                [normalized-current normalized-desired])]
              (cond
                refusal refusal
                (= normalized-current normalized-desired)
                (recur (next pending) changes)
                :else
                (recur
                 (next pending)
                 (conj changes
                       {:seon.program/row row
                        :seon.fn/retractions
                        (if-let [entity-id (:db/id current)]
                          (vec
                           (butlast
                            (program/exact-replacement-tx-in
                             row-shapes
                             (assoc normalized-current :db/id entity-id)
                             normalized-desired)))
                          [])}))))))
        (let [{entities :seon.fn/index-entities
               identity-operations :seon.fn/index-identity-operations
               keyword-operations :seon.fn/index-keyword-operations}
              (compile-index-transaction projection
                                         (mapv :seon.program/row changes)
                                         identity-attributes)]
          (into (into (into (into (mapv #(vector :db/retractEntity %) removed)
                                  (mapcat :seon.fn/retractions) changes)
                            identity-operations)
                      entities)
                keyword-operations))))))

(defn reconcile-tx
  "Replace source definitions while retaining identities and agent facts.

  Immutable source identities identify definitions removed by an edit.
  The current writer database decides their exact replacement, so a concurrent
  agent transaction cannot make an earlier read authoritative."
  {:malli/schema
   [:=> [:cat :seon.db/database-value [:vector :map]
          :seon.fn.file/identities]
    [:or [:vector :seon.schema/value] :seon.error/value]]}
  [database rows previous-identities]
  (reconcile-tx-in (program/shapes-in (declaration-forms nil))
                   database rows previous-identities))

(defn- published-index-rows
  "Read compiled rows with portable program refs and complete owned components."
  {:malli/schema
   [:=> [:cat :seon.db/database-value]
    [:or :seon.program/rows :seon.error/value]]}
  [database]
  (let [entity (memoize #(db/pull database '[*] %))]
   (letfn [(reference [value]
            (let [pulled (entity (:db/id value))]
              (if (error/error? pulled)
                pulled
                (or (program/row-identity pulled) (row pulled)))))
          (row [entity]
            (if (error/error? entity)
              entity
              (reduce-kv
               (fn [result attribute value]
                 (if (= :db/id attribute)
                   result
                   (let [portable
                         (if (= :db.type/ref
                                (get-in (:schema database)
                                        [attribute :db/valueType]))
                           (if (= :db.cardinality/many
                                  (get-in (:schema database)
                                          [attribute :db/cardinality]))
                             (loop [pending (seq value) values #{}]
                               (if-let [member (first pending)]
                                 (let [portable-member (reference member)]
                                   (if (error/error? portable-member)
                                     portable-member
                                     (recur (next pending)
                                            (conj values portable-member))))
                                 values))
                             (reference value))
                           value)]
                     (if (error/error? portable)
                       (reduced portable)
                       (assoc result attribute portable)))))
               {} entity)))]
    (loop [attributes
           (seq (filter #(get (:schema database) %)
                        program/identity-attributes))
           rows []]
      (if-let [attribute (first attributes)]
        (let [entity-ids
              (db/q '[:find [?entity ...] :in $ ?attribute
                      :where [?entity ?attribute]]
                    database attribute)]
          (if (error/error? entity-ids)
            entity-ids
            (let [attribute-rows
                  (loop [pending (seq entity-ids) result []]
                    (if-let [entity-id (first pending)]
                      (let [portable (row (entity entity-id))]
                        (if (error/error? portable)
                          portable
                          (recur (next pending) (conj result portable))))
                      result))]
              (if (error/error? attribute-rows)
                attribute-rows
                (recur (next attributes) (into rows attribute-rows))))))
        rows)))))

(defn index!
  "Populate one fresh source scratch branch from static analysis.

  The optional callback receives bounded progress lines while contract rows
  are derived and after each ordered phase commits. Observation never changes
  transaction boundaries; the scratch branch remains unpublished until every
  phase and the source seal commit. An explicit previous source database
  selects in-place reconciliation for an opted-in development cluster."
  {:malli/schema
   [:function
    [:=> [:cat :seon.fn/index-request]
     [:or :seon.reconcile/result :seon.error/value]]
    [:=> [:cat :seon.fn/index-request [:fn clojure.core/ifn?]]
     [:or :seon.reconcile/result :seon.error/value]]]}
  ([request]
   (index! request (constantly nil)))
  ([{connection :seon.db/connection process :seon.db/process
     previous-database :seon.source/previous-database
     source-database :seon.source/database :as request}
    progress!]
   (let [row-shapes (program/shapes-in (declaration-forms request))
         rows (if source-database
                (published-index-rows source-database)
                (desired-rows request progress!))]
     (if (error/error? rows)
       rows
       (let [_ (when source-database
                 (report-index-progress! progress! "development published rows read"))
             _ (assert-one-row-per-identity! rows)
             _ (assert-populated! rows)
             existing (some (fn [identity-attribute]
                              (db/q '[:find ?entity .
                                     :in $ ?attribute
                                     :where [?entity ?attribute]]
                                    @connection identity-attribute))
                            [:seon.ns/name :seon.fn/sym :seon.test/sym])]
     (when (and existing (not previous-database))
       (throw (ex-info "Program indexing requires a fresh source scratch branch."
                       {:seon.error/kind ::index-refused
                        ::existing-program-entity existing :seon.fn/index-refused true})))
     (if previous-database
       (let [previous-identities
             (into []
                   (mapcat
                    (fn [attribute]
                      (map (fn [value] [attribute value])
                           (db/q '[:find [?value ...]
                                   :in $ ?attribute
                                   :where
                                   [?entity ?attribute ?value]
                                   [?entity :seon.schema.admission/source :core]]
                                 previous-database attribute))))
                   (filter #(get (:schema previous-database) %)
                           program/identity-attributes))
             projection (schema/handed-projection)
             _ (report-index-progress! progress! "development reconciliation transaction")
             report
             (require-committed!
              (db/transact!
               connection
               (cond-> {:tx-data
                        [[:db.fn/call
                          (fn [database]
                            (let [tx-data
                                  (schema/call-with-projection
                                   projection
                                   #(reconcile-tx-in row-shapes database rows
                                                     previous-identities))]
                              (if (error/error? tx-data)
                                (throw
                                 (ex-info (:seon.error/message tx-data)
                                          tx-data))
                                tx-data)))]]}
                 process (assoc :tx-meta {:seon.db/process process})))
              :seon.fn/population)
             _ (report-index-progress! progress! "development changed definition comparison")
             changed-entities (into #{} (map :e) (:tx-data report))
             previous-entity (memoize #(db/pull previous-database '[*] %))
             current-entity (memoize #(db/pull (:db-after report) '[*] %))
             previous-identity-attributes (db/identity-attributes previous-database)
             current-identity-attributes (db/identity-attributes (:db-after report))
             source-changed-identities
             (into #{}
                   (keep (fn [row]
                           (let [identity (program/row-identity row)]
                             (when (not=
                                    (normalized-index-row
                                     row-shapes
                                     previous-database
                                     (when (get (:schema previous-database) (first identity))
                                       (previous-entity identity))
                                     previous-identity-attributes previous-entity)
                                    (normalized-index-row
                                     row-shapes (:db-after report) row
                                     current-identity-attributes current-entity))
                               identity))))
                   rows)
             removed-identities
             (remove (into #{} (map program/row-identity) rows)
                     previous-identities)
             changed-identities
             (into (into source-changed-identities removed-identities)
                   (keep #(program/row-identity
                           (db/pull (:db-after report) program/identity-attributes %)))
                   changed-entities)]
         {:seon.reconcile/converged? (empty? changed-identities)
          :seon.reconcile/operations (count changed-identities)
          :seon.program/identities (vec changed-identities)})
       (let [database @connection
             projection (or (db/carried-projection database)
                            (schema/projection-from-database database))
             identity-attributes (db/identity-attributes database)
             {entities :seon.fn/index-entities
              identity-operations :seon.fn/index-identity-operations
              keyword-operations :seon.fn/index-keyword-operations}
             (compile-index-transaction projection rows identity-attributes)]
       (report-index-progress!
        progress!
        (str "program population compiled: " (count entities)
             " entities, " (count identity-operations) " identities, "
             (count keyword-operations) " keyword facts"))
       (commit-index-phase! connection process progress! :seon.fn/population
                            (into (into identity-operations entities)
                                  keyword-operations))
       {:seon.reconcile/converged? false
        :seon.reconcile/operations (count rows)})))))))

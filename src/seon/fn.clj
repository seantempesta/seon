(ns seon.fn
  "Build-time indexing of the Clojure program graph without evaluation."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [seon.cluster.registry :as registry]
            [seon.db :as db]
            [seon.error :as error]
            [seon.error.refusal :as error.refusal]
            [seon.fn.analyzer :as analyzer]
            [sci.impl.namespaces :as sci.namespaces]
            [seon.fn.signature :as signature]
            [seon.id :as id]
            [seon.fs :as fs]
            [seon.program :as program]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.schema.edn :as schema.edn]
            [malli.core :as m]
            [malli.error :as me]
            [malli.registry :as mr]
            [seon.schema.internal :as schema.internal]
            [clj-kondo.impl.cache :as kondo.cache]
            [clj-kondo.impl.core :as kondo.core]
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
  (when (:seon.error/at result)
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
  [projection attributes]
  (into #{}
        (keep (fn [{:db/keys [ident cardinality isComponent]}]
                (when (or (= :db.cardinality/many cardinality) isComponent)
                  ident)))
        (schema.datahike/malli->datahike-schema-in projection (sort attributes))))

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
  "The captured text of one listed file. A file deleted between its listing
  and this read is the declared analysis source-change refusal
  (`:seon.fn/index-refused-error`), never a core fault: the next refresh sees
  the deletion as a change and removes its declarations."
  [file]
  (try
    (text-context (String. (Files/readAllBytes (.toPath ^java.io.File file))
                           StandardCharsets/UTF_8))
    (catch java.nio.file.NoSuchFileException missing
      (let [message "Source changed during analysis; a listed file was deleted before it was read."]
        (throw (ex-info message
                        {:seon.fn/index-refused true
                         :seon.cluster.boot/disposition :refused
                         :seon.cluster.source/phase :analysis
                         :seon.error/message message
                         :seon.source/changed-paths [(.getPath ^java.io.File file)]}
                        missing))))))

(defn- source-contexts
  [files]
  (into {}
        (map (fn [file]
               [(.getCanonicalPath ^java.io.File file)
                (source-context file)]))
        files))

(defn- character-offset
  "The character offset of one analyzer row/column in the text it analyzed."
  [contexts entry row col]
  (+ (nth (:line-starts (get contexts (::analyzer/filename entry))) (dec row))
     (dec col)))

(defn- exact-source
  {:malli/schema [:=> [:cat :map :map] :string]}
  [contexts entry]
  (let [{:keys [text]}
        (get contexts (::analyzer/filename entry))
        row (::analyzer/row entry)
        col (::analyzer/col entry)
        end-row (::analyzer/end-row entry)
        end-col (::analyzer/end-col entry)]
    (when-not text
      (throw (ex-info "Static declaration has no source file content."
                      {::analysis-entry entry :seon.fn/index-refused true})))
    (when-not (every? some? [row col end-row end-col])
      (throw (ex-info "Static declaration has no exact source span."
                      {::analysis-entry entry :seon.fn/index-refused true})))
    (subs text
          (character-offset contexts entry row col)
          (character-offset contexts entry end-row end-col))))

(defn- exact-form-span
  [contexts entry]
  (let [{:keys [text line-starts] ::keys [byte-line-starts]} (get contexts (::analyzer/filename entry))
        offset (fn [row col]
                 ;; An analyzer column past the line's last character addresses
                 ;; that line's end; no source byte exists beyond it.
                 (let [start (nth line-starts (dec row))
                       stop (min (+ start (dec col))
                                 (nth line-starts row (count text)))]
                   (+ (nth byte-line-starts (dec row))
                      (alength (.getBytes (subs text start stop)
                                          StandardCharsets/UTF_8)))))]
    [(offset (::analyzer/row entry) (::analyzer/col entry))
     (offset (::analyzer/end-row entry) (::analyzer/end-col entry))]))

;; A simple symbol in schema position that Malli's default registry names
;; (`ifn?`, `string?`) is that Malli schema and stays itself; qualifying it to
;; `clojure.core/ifn?` names no schema and refuses the whole projection.
;; Inside `[:fn …]` a symbol is a predicate and resolves as source does.
(defn- qualify-schema-symbols [form {:keys [aliases refers]}]
  (let [qualify
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
        malli-schemas (m/default-schemas)]
    ((fn walk [value]
       (cond
         (and (vector? value) (= :fn (first value))) (walk/postwalk qualify value)
         (and (simple-symbol? value) (contains? malli-schemas value)) value
         (coll? value) (walk/walk walk identity value)
         :else (qualify value)))
     form)))

(declare task-links)

(defn- namespace-entity
  {:malli/schema [:=> [:cat :seon.schema/projection :map :map :map] :map]}
  [projection contexts context entry]
  (let [namespace-name (::analyzer/name entry)
        {:keys [aliases refers imports requires]} context
        entity (cond-> {:seon.ns/name namespace-name
             :seon.fn/file [:seon.fn.file/relative-path (::analyzer/filename entry)]
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
                               :seon.ns.import/target-class target})) imports)))
        entity (merge entity (task-links projection context namespace-name entry))]
    (assoc entity :seon.program/definition-digest
           (program/definition-digest entity))))

(defn- function-definition?
  "Whether a clj-kondo var definition is a program declaration: a function or
  multimethod, or a value def (`def`/`defonce`, directly or through a macro
  linted as one), literal or computed. A computed or point-free def has no
  arglists; its initializer's calls and references are its edges. `declare`
  and the class-bound names of `deftype`/`defrecord`/`defprotocol` intern no
  body of their own and stay out."
  {:malli/schema [:=> [:cat :map] :boolean]}
  [entry]
  (boolean
   (or (::analyzer/literal-def? entry)
       (seq (::analyzer/arglist-strs entry))
       (#{'clojure.core/defmulti 'clojure.core/def 'clojure.core/defonce}
        (::analyzer/defined-by->lint-as entry)))))

(defn stored-namespace-context
  [{:seon.ns/keys [requires aliases refers imports]}]
  {:requires (set requires)
   :aliases (into {} (map (juxt :seon.ns.alias/local
                                :seon.ns.alias/target-ns)) aliases)
   :refers (into {} (map (fn [{:seon.ns.refer/keys [local target-ns target-name]}]
                            [local (symbol (str target-ns) (str target-name))])) refers)
   :imports (into {} (map (juxt :seon.ns.import/local
                                :seon.ns.import/target-class)) imports)})

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
  "The target a var usage exercises: an invocation, a macro use (its expansion
  runs in the caller), a var quote, or a value handed on (`apply`, `partial`,
  `comp`, a syntax-quoted body). A quoted symbol only names its target."
  [usage]
  (when-not (::analyzer/reference usage)
    (usage-symbol usage)))

(defn- references-by-caller
  "Quoted target symbols: named, not exercised (`call-target`). A nil caller
  is retained, with every usage it holds, when this analysis has no
  declaration to own it."
  ([analysis first-party-functions]
   (references-by-caller analysis first-party-functions first-party-functions))
  ([analysis _first-party-functions declared-callers]
   (reduce
    (fn [references usage]
      (let [caller (usage-caller usage)
            target (usage-symbol usage)]
        (if (and target
                 (or (::analyzer/reference usage)
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
  "Literal qualified keywords each declaration carries at a write-seam call site.

  The join is span containment over facts the analyzer already reports:
  a qualified keyword whose position lies inside the span of a
  `seon.db/transact!` usage is transaction data that call carries.
  These source facts survive absent schema declarations. Computed keys and
  transaction data constructed outside the call span are not inferred."
  {:malli/schema [:=> [:cat :map] [:map-of :qualified-symbol [:set :qualified-keyword]]]}
  [analysis]
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

(defn- seed-refused!
  "Refuse one `:seon.test/seed` entry by the admission rule it broke."
  {:malli/schema [:=> [:cat :seon.test.seed/refused :qualified-symbol
                       [:maybe :seon.test.seed/ordinal] :map] :nil]}
  [rule test-symbol ordinal data]
  (let [message (str "Seed entry " (or ordinal "vector") " of " test-symbol
                     " is refused: " (name rule) ".")]
    (throw (ex-info message
                    (cond-> (merge {:seon.test.seed/refused rule :seon.test/sym test-symbol
                                    :seon.fn/index-refused true :seon.error/message message}
                                   data)
                      ordinal (assoc :seon.test.seed/ordinal ordinal))))))

(defn- seed-symbol
  "The qualified name an authored symbol denotes through its namespace's aliases and refers."
  {:malli/schema [:=> [:cat :map :symbol] [:maybe :qualified-symbol]]}
  [{:keys [aliases refers]} authored]
  (if (qualified-symbol? authored)
    (if-let [target (get aliases (symbol (namespace authored)))]
      (symbol (str target) (name authored))
      authored)
    (get refers authored)))

(defn- literal-seed-writes
  "Attributes one literal entry's entity maps write, each value checked
  against its declaration in the supplied projection."
  {:malli/schema [:=> [:cat :seon.schema/projection :qualified-symbol :seon.test.seed/ordinal
                       [:sequential :map]]
                  [:set :qualified-keyword]]}
  [projection test-symbol ordinal entities]
  (let [registry (:seon.schema.projection/registry projection)]
    (into (sorted-set)
          (for [entity entities
                [attribute value] entity
                :when (not= :db/id attribute)]
            (cond
              (not (and (qualified-keyword? attribute) (mr/schema registry attribute)))
              (seed-refused! :undeclared-attribute test-symbol ordinal {:seon.schema/key attribute})
              (schema/explain-candidate-value projection attribute value)
              (seed-refused! :invalid-attribute-value test-symbol ordinal
                             {:seon.schema/key attribute :seon.error/offending value})
              :else attribute)))))

(defn- seed-occurrences
  "The deftest's ordered `:seon.test/seed` entries as owned occurrences.

  A symbol (form 2) is its qualified name; literal entity maps (form 1) keep
  the attributes they write; an inline `(fn [input] ...)` (form 3) keeps only
  its ordinal. Values stay in the test source."
  {:malli/schema [:=> [:cat :seon.schema/projection :map :qualified-symbol :any]
                  [:vector :seon.test.seed/occurrence]]}
  [projection context test-symbol entries]
  (when-not (vector? entries)
    (seed-refused! :unknown-form test-symbol nil {:seon.error/offending entries}))
  (into []
        (map-indexed
         (fn [ordinal entry]
           (assoc
            (cond
              (symbol? entry)
              {:seon.test.seed/function
               (or (when (qualified-symbol? entry) (seed-symbol context entry))
                   (seed-refused! :unqualified-symbol test-symbol ordinal
                                  {:seon.error/offending entry}))}

              ;; Its calls and reads are the test's own analysis; derive, never copy.
              (and (seq? entry) ('#{fn fn* clojure.core/fn} (first entry)))
              {}

              (or (map? entry) (and (vector? entry) (every? map? entry)))
              (let [writes (literal-seed-writes projection test-symbol ordinal
                                                (if (map? entry) [entry] entry))]
                (cond-> {} (seq writes) (assoc :seon.test.seed/writes writes)))

              :else
              (seed-refused! :unknown-form test-symbol ordinal {:seon.error/offending entry}))
            :seon.test.seed/ordinal ordinal)))
        entries))

(def ^:private seed-contract
  "The one contract a named seed function declares, in `:seon.fn/spec` form."
  (pr-str (schema/canonical-definition
           [:=> [:cat :seon.test.seed/input] :seon.test.seed/value])))

(defn- assert-seed-contracts!
  "Refuse a seed entry whose function the final program lacks or did not
  contract as a seed. `rows` are the batch's authored rows; `database`, when
  present, supplies every other current row; `removed` leave in this batch."
  {:malli/schema [:=> [:cat [:sequential :map] [:or :nil :seon.db/database-value]
                       [:set :qualified-symbol]] :nil]}
  [entities database removed]
  (let [authored (into {} (comp (filter :seon.fn/sym) (map (juxt :seon.fn/sym identity))) entities)
        read-entity (fn [function-symbol]
                   (when database
                     (let [entity (db/pull database [:seon.fn/sym :seon.fn/spec]
                                        [:seon.fn/sym function-symbol])]
                       (when (:seon.error/at entity) (throw (ex-info (:seon.error/message entity) entity)))
                       (when (:seon.fn/sym entity) entity))))]
    (doseq [{test-symbol :seon.test/sym seeds :seon.test/seed} entities
            {ordinal :seon.test.seed/ordinal function-symbol :seon.test.seed/function} seeds
            :when function-symbol
            :let [entity (when-not (removed function-symbol)
                        (or (get authored function-symbol) (read-entity function-symbol)))
                  data {:seon.test.seed/function function-symbol}]]
      (when-let [rule (cond (nil? entity) :missing-function
                            (nil? (:seon.fn/spec entity)) :uncontracted-function
                            (not= seed-contract (:seon.fn/spec entity)) :contract-mismatch)]
        (seed-refused! rule test-symbol ordinal (merge data (select-keys entity [:seon.fn/spec])))))))

;; Emit one fact per keyword. Datahike reads a two-element collection beginning
;; with a unique-identity attribute as one lookup ref
;; (`reference-code/datahike/src/datahike/db/transaction.cljc:717-735`). The
;; honest two-edge set on `seon.ai/agent-overlay` therefore cannot travel as a
;; collection-valued entity-map entry: its two analyzer-produced keywords are
;; two independent cardinality-many facts.
(defn- keyword-facts
  [entity]
  (let [program-identity (program/row-identity entity)]
    (mapv (fn [used]
            [:db/add program-identity :seon.fn/keywords used])
          (:seon.fn/keywords entity))))

(defn- capability-symbol
  [value]
  (if (and (seq? value)
           (= 'quote (first value))
           (nil? (next (next value))))
    (second value)
    value))

(defn task-refused!
  "Throw one task ingestion refusal: the error base plus the rule, holder and
  authored offending value, and any further declared members (spec 7.2-7.5, 1.15)."
  {:malli/schema [:=> [:cat :symbol :seon.task/refused :seon.task/holder :seon.schema/value :map] :nil]}
  [operation rule holder offending members]
  (let [refusal (error.refusal/diagnostic
                 (java.util.Date.) :seon.task/ingestion operation
                 (merge {:seon.error/message (str holder " is refused: " (name rule) ".")
                         :seon.task/refused rule :seon.task/holder holder :seon.error/offending offending}
                        members))]
    (throw (ex-info (:seon.error/message refusal) refusal (:seon.error/throwable members)))))

(defn- task-links
  "One holder's `:seon.task/annotation`, validated, as its `:seon.task/links`: task
  handles as string values; must-read/see-also quoted symbols resolved through the
  namespace's aliases. A map naming `:seon/tasks` after a deftest's name refuses."
  {:malli/schema [:=> [:cat :seon.schema/projection :map :symbol :map] :seon.task/links]}
  [projection context holder entry]
  (let [authored (select-keys (::analyzer/meta entry) [:seon/tasks :seon/must-read :seon/see-also])
        refuse! #(task-refused! 'seon.fn/task-links %1 holder %2 (assoc %3 :seon.fn/index-refused true))
        link #(some->> (get authored %) (map (fn [[_ target]] (seed-symbol context target))) seq (into (sorted-set)))]
    (when (and (::analyzer/test entry) (::analyzer/task-map-after-name? entry))
      (refuse! :metadata-after-test-name (::analyzer/name entry) {}))
    (when-let [explanation (schema/explain-candidate-value projection :seon.task/annotation authored)]
      (refuse! :malformed-annotation authored {:seon.task/explanation (me/humanize explanation)}))
    (let [must-read (link :seon/must-read) see-also (link :seon/see-also)]
     (cond-> {}
      (:seon/tasks authored) (assoc :seon.task/tasks (:seon/tasks authored))
      must-read (assoc :seon.fn/must-read must-read)
      see-also (assoc :seon.fn/see-also see-also)))))

(defn- declared-function-targets
  "Literal targets of explicitly declared invocation attributes."
  [attributes values first-party-functions]
  (let [maps (filter map? (mapcat #(tree-seq coll? seq %) values))]
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
     {} maps)))

(defn- declared-calls-by-caller
  [invocations targets]
  (update-vals invocations
               #(into #{} (mapcat targets) %)))

(defn- invocation-attributes
  "Read explicit invocation metadata; malformed declarations never disappear."
  {:malli/schema [:=> [:cat :map] :seon.fn/invokes]}
  [metadata]
  (if-let [[_ attributes] (find metadata :seon.fn/invokes)]
    (if (and (set? attributes) (every? qualified-keyword? attributes))
      attributes
      (throw (ex-info "Invocation metadata must be a set of qualified attribute names."
                      {:seon.fn/index-refused true :seon.fn/invokes attributes})))
    #{}))

(defn- with-seed-keywords
  "Union a deftest's seed attributes into its `:seon.fn/keywords`.
  Metadata keywords carry no `:from-var`, so kondo attributes them to no
  holder; seed attributes are nevertheless reach's schema edges."
  {:malli/schema [:=> [:cat :map [:maybe :map]] :map]}
  [entity metadata]
  (if-let [used (seq (filter qualified-keyword?
                             (tree-seq coll? seq (:seon.test/seed metadata))))]
    (update entity :seon.fn/keywords (fnil into (sorted-set)) used)
    entity))

(defn- var-entity
  {:malli/schema [:=> [:cat :seon.schema/projection :map :map :map :map] [:maybe :map]]}
  [projection contexts namespace-contexts
   {:keys [calls-by-caller references used-keywords writes call-arities]} entry]
  (let [namespace-name (::analyzer/ns entry)
        qualified (symbol (str namespace-name) (str (::analyzer/name entry)))
        metadata (::analyzer/meta entry)
        invokes (invocation-attributes (or metadata {}))
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
        {:seon.fn/capability-rule :invalid-handler-symbol
         :seon.fn/sym qualified
         :seon.effect/capability capability :seon.fn/index-refused true})))
    (let [entity
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
        (assoc :seon.test/subject (test-subject metadata))
        ;; An empty seed vector adds nothing, so its definition digest is unchanged.
        (when-let [[_ seeds] (find metadata :seon.test/seed)] (not= [] seeds))
        (assoc :seon.test/seed
               (seed-occurrences projection (get namespace-contexts namespace-name) qualified
                                 (:seon.test/seed metadata)))
        true (with-seed-keywords metadata))

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
        (seq invokes) (assoc :seon.fn/invokes invokes)
        (::analyzer/macro entry) (assoc :seon.fn/macro? true)
        ;; Explicit false distinguishes completed inline analysis from an older
        ;; entity without the fact. The compiler reads this metadata, not the head.
        (#{'clojure.core/defn 'clojure.core/defn-} (::analyzer/defined-by entry))
        (assoc :seon.fn/inline? (boolean (:inline metadata)))
        (::analyzer/literal-def? entry) (assoc :seon.fn/constant? true)
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
                        (get namespace-contexts namespace-name)))))
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

            :else nil)]
      (when-let [entity (some-> entity (merge (task-links projection (get namespace-contexts namespace-name) qualified entry)))]
        (assoc entity :seon.program/definition-digest
               (program/definition-digest
                entity
                (select-keys (get namespace-contexts namespace-name)
                             [:requires :aliases :refers :imports])))))))

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
            (fn [index alias-entity]
              (cond-> [target :as (:seon.ns.alias/local alias-entity)]
                (and (zero? index) (seq referred))
                (into [:refer referred])))
            target-aliases)
           [base])))
     targets)))

(defn- interpreter-refer-entities
  "Refer rows for the bindings every SCI namespace resolves without a require.

  `seon.sci.eval/build-base-ctx` installs each declared binding's target in
  `clojure.core`, so a submitted form resolves `deftest`, `is`, `doc`, `dir`
  and `help` bare. A submitted form's ANALYSIS must resolve exactly what its
  EVALUATION resolves, or a bare `deftest` analyses to no var definition at
  all and its test row is never minted. The declared attribute name is the
  bare name the interpreter binds."
  [projection]
  (into []
        (keep (fn [attribute]
                (when-let [target (:seon.sci.binding/target
                                   (m/properties (mr/schema (:seon.schema.projection/registry projection) attribute)))]
                  (let [local (symbol (name attribute))]
                    {:seon.ns.refer/local local
                     :seon.ns.refer/target-ns (symbol (namespace target))
                     :seon.ns.refer/target-name local}))))
        (keys (:seon.schema.projection/forms projection))))

(defn- runtime-namespace-form
  [namespace-name namespace-entity referenced-namespaces]
  (let [program-requires
        (into #{}
              (remove #{namespace-name})
              referenced-namespaces)
        requires
        (vec (runtime-require-specs
              (update namespace-entity :seon.ns/requires
                      #(into program-requires %))))
        imports (->> (:seon.ns/imports namespace-entity)
                     (keep :seon.ns.import/target-class)
                     (sort-by str)
                     vec)]
    (apply list
           (cond-> ['ns namespace-name]
             (seq requires) (conj (apply list :require requires))
             (seq imports) (conj (apply list :import imports))))))

(defn- resolvable-runtime-function-entities
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

(defn- program-namespaces
  "The namespaces among `named` that name a namespace: one of the batch's own,
  or one the program declares. A qualified symbol whose prefix names no
  namespace is a class member (`System/nanoTime`), as the Clojure compiler
  resolves it (`Compiler.java:7524` namesStaticMember, pinned 3bc2b3e9); requiring it
  would make clj-kondo read the member as a Var of an unanalyzed namespace."
  {:malli/schema [:=> [:cat :seon.db/database-value [:vector :symbol] [:set :symbol]]
                  [:set :symbol]]}
  [database batch-namespaces named]
  (into (set/intersection named (set batch-namespaces))
        (db/q '[:find [?name ...]
                :in $ [?name ...]
                :where [_ :seon.ns/name ?name]]
              database (vec named))))

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
        ;; A `declare` makes no entity; the caller names those Vars.
        prelude (analyzer/program-prelude
                 (into available-functions
                       (comp (mapcat :seon.fn/declared)
                             (map (fn [function-symbol] {:seon.fn/sym function-symbol})))
                       requests))
        interpreter-refers
        (interpreter-refer-entities
         (or (db/carried-projection database)
             (schema/projection-from-database database)))
        ;; One namespace form per run of consecutive requests in one
        ;; namespace: a later form then sees the aliases an earlier form of
        ;; the batch required, as SCI evaluates them.
        {:keys [source spans]}
        (reduce
         (fn [{:keys [source spans]} group]
           (let [{namespace-name :namespace-name
                  supplied-namespace :seon.fn/namespace-row} (first group)
                 namespace-entity
                 (or supplied-namespace
                     (db/pull database
                          '[:seon.ns/name
                            :seon.ns/requires
                            {:seon.ns/aliases [*]}
                            {:seon.ns/imports [*]}
                            {:seon.ns/refers [*]}]
                          [:seon.ns/name namespace-name]))
                 namespace-source
                 (pr-str (runtime-namespace-form
                          namespace-name
                          (update namespace-entity :seon.ns/refers
                                  (fnil into []) interpreter-refers)
                          (program-namespaces
                           database namespace-names
                           (analyzer/referenced-program-namespaces
                            namespace-name (mapv :form-source group)))))]
             (reduce (fn [{:keys [source spans]} {form-source :form-source}]
                       (let [first-source-row (inc (count (filter #{\newline} source)))]
                         {:source (str source form-source "\n")
                          :spans (conj spans [first-source-row
                                              (+ first-source-row
                                                 (count (filter #{\newline} form-source)))])}))
                     {:source (str source namespace-source "\n") :spans spans}
                     group)))
         {:source (if (seq prelude) (str prelude "\n") "") :spans []}
         (partition-by :namespace-name requests))
        analysis
        (with-in-str source
          (analyzer/analyze {::analyzer/paths ["-"]}))]
    {:seon.fn/analysis analysis
     :seon.fn/source source
     :seon.fn/source-spans spans
     :seon.fn/function-rows
     (resolvable-runtime-function-entities database analysis requests)}))

(declare source-span)

(def dependency-analysis-command
  "The one-time fill of the checkout's clj-kondo cache from the classpath the
  loaded JVM runs with (`seon.fn.analyzer/analyze-dependencies!`), evaluated at
  that JVM's REPL: a shell `clojure -Spath` names another alias's classpath."
  "(seon.fn.analyzer/analyze-dependencies!)")

(defn- unresolved-macro-refusal
  "Refuse calls whose target namespace SCI does not interpret and clj-kondo
  never analyzed: whether each is a macro is unknown, never false."
  {:malli/schema [:=> [:cat [:set :qualified-symbol]] :nil]}
  [targets]
  (when (seq targets)
    (let [message (str "Cannot classify host binding: clj-kondo holds no analysis of "
                       (str/join ", " (sort (into #{} (map namespace) targets)))
                       ". Fill the checkout's cache from this JVM's classpath at its REPL: " dependency-analysis-command)]
      ;; A platform fault, not the author's mistake: its own declared schema
      ;; (`seon.turn` stores and delivers it rather than returning it).
      (throw (ex-info message (with-meta (error.refusal/diagnostic
                                          (java.util.Date.) :seon.fn/analysis 'seon.fn/unresolved-macro-refusal
                                          {:seon.fn/index-refused true
                                           ::dependency-analysis-missing true
                                           :seon.cluster.source/phase :analysis
                                           :seon.error/message message
                                           ::unresolved-targets (vec (sort targets))})
                                {:seon.error/declared-schema ::dependency-analysis-missing-error}))))))

(defn- interpreted-namespaces
  "Namespaces whose macros expand in SCI: SCI's own (`sci.impl.namespaces/namespaces`),
   the declared interpreter bindings, and the first-party program's namespaces."
  {:malli/schema [:=> [:cat :map [:set :qualified-symbol] :seon.schema/projection] [:set :symbol]]}
  [analysis first-party-functions projection]
  (-> (set (keys sci.namespaces/namespaces))
      (into (map (comp symbol namespace)) (program/base-context-injected-symbols projection))
      (into (map (comp symbol namespace)) first-party-functions)
      (into (map ::analyzer/name) (::analyzer/namespace-definitions analysis))))

(defn- host-bound-callers
  "Declarations whose own body uses an operation SCI cannot interpret.
   Class usage is never one: the configured interpreter resolves every class
   on the classpath (`seon.sci.eval/build-base-ctx` `:classes {:allow :all}`;
   fork `interop/host-class-opts`), so a copied class list cannot decide it.
   Import calls remain host-bound under the program declaration rule. A JVM
   macro from a namespace SCI does not interpret is host-bound: SCI hands it
   SCI's `&env`, which `clojure.core.async/go` reads as compiler locals.
   Macro status is clj-kondo's `:macro` from the dependency's analysis, never
   the loaded JVM; a call into a namespace kondo never analyzed refuses by name."
  {:malli/schema [:=> [:cat :map [:set :qualified-symbol] :seon.schema/projection]
                  [:set :qualified-symbol]]}
  [analysis first-party-functions projection]
  (let [interpreted (interpreted-namespaces analysis first-party-functions projection)
        host-call? #(when-let [target (and (some? (::analyzer/arity %)) (usage-symbol %))]
                      (not (interpreted (symbol (namespace target)))))
        unanalyzed (::analyzer/unanalyzed-namespaces analysis)]
    (unresolved-macro-refusal
     (into #{} (comp (filter host-call?) (map usage-symbol)
                     (filter #(contains? unanalyzed (symbol (namespace %)))))
           (::analyzer/var-usages analysis)))
    (-> #{}
        (into
         (comp
          (filter #(some? (::analyzer/arity %)))
          (filter #(#{'clojure.core/reify 'clojure.core/proxy
                      'clojure.core/definterface 'clojure.core/import}
                    (usage-symbol %)))
          (keep usage-caller))
         (::analyzer/var-usages analysis))
        (into
         (comp
          (filter #(and (host-call? %) (::analyzer/macro %)))
          (keep usage-caller))
         (::analyzer/var-usages analysis)))))

(defn- host-bound-declaration?
  "The defining form and its own body facts decide; callers inherit nothing."
  {:malli/schema [:=> [:cat :map [:set :qualified-symbol]] :boolean]}
  [definition host-bound]
  (boolean
   (or (#{'clojure.core/reify 'clojure.core/proxy 'clojure.core/definterface
          'clojure.core/deftype 'clojure.core/defrecord}
        (::analyzer/defined-by definition))
       (when (and (::analyzer/ns definition) (::analyzer/name definition))
         (host-bound (symbol (str (::analyzer/ns definition))
                             (str (::analyzer/name definition))))))))

(defn- source-analysis
  "Keep only analyzer entries owned by one submitted form's entity span."
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
    ::analyzer/java-class-usages
    ::analyzer/keywords
    ::analyzer/findings]))

(defn- analyzed-form
  [analysis function-entities program-entity projection]
  (let [program-symbol (or (:seon.fn/sym program-entity)
                           (:seon.test/sym program-entity))
        first-party-functions
        (cond-> (into #{} (map :seon.fn/sym) function-entities)
          program-symbol (conj program-symbol))
        calls-by-caller
        (call-targets-by-caller analysis first-party-functions)
        references (references-by-caller analysis first-party-functions)
        used-keywords (keywords-by-holder analysis)
        host-bound (host-bound-callers analysis first-party-functions projection)
        writes (writes-by-writer analysis)
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
                subject (or (:seon.test/subject program-entity)
                            (test-subject (::analyzer/meta definition)))
                invokes (invocation-attributes (or (::analyzer/meta definition) {}))]
            (cond-> {}
              (:seon.fn/sym program-entity)
              (assoc :seon.fn/host-bound? (host-bound-declaration? (or definition {}) host-bound))
              (seq invokes) (assoc :seon.fn/invokes invokes)
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
              subject (assoc :seon.test/subject subject)
              ;; An SCI deftest settles through this re-analysis, not `var-entity`.
              (:seon.test/sym program-entity) (with-seed-keywords (::analyzer/meta definition)))))
        ;; The entity keeps the digest its constructor derived under the
        ;; evaluation's own namespace; the database's namespace here can
        ;; predate this reply's `ns` form.
        merged-entity (when program-entity
                     (merge (dissoc program-entity :seon.fn/calls :seon.fn/references
                                    :seon.fn/invokes
                                    :seon.fn/keywords :seon.fn/writes :seon.fn/call-arities)
                            program-facts))]
    ;; A declaration entity owns its edges; a form without one carries no
    ;; analysis facts (receipts assert no program attribute).
    [{} merged-entity]))

(defn- shared-dependency-analysis
  "The checkout's clj-kondo dependency analysis of `namespaces`, read and never
  written: a submitted form is analyzed without a cache, because kondo writes
  every namespace it lints into the cache it reads (clj_kondo/impl/cache.clj:165).
  Each entry is kondo's own cached namespace (`from-cache-1`, :23), absent when unanalyzed."
  {:malli/schema [:=> [:cat [:set :symbol]] [:map-of :symbol :map]]}
  [namespaces]
  (let [root (kondo.core/resolve-cache-dir analyzer/config-directory true nil)]
    (into {} (keep (fn [namespace-name]
                     (some->> (some #(kondo.cache/from-cache-1 root % namespace-name) [:clj :cljc])
                              (vector namespace-name))))
          namespaces)))

(defn- with-dependency-analysis
  "Resolve the usages a cacheless analysis left unanalyzed against `dependencies`:
  a supplied namespace is analyzed, and its cached `:macro` marks each usage."
  {:malli/schema [:=> [:cat :map [:map-of :symbol :map]] :map]}
  [analysis dependencies]
  (-> analysis
      (update ::analyzer/unanalyzed-namespaces #(into (sorted-set) (remove (set (keys dependencies))) %))
      (update ::analyzer/var-usages
              (partial mapv #(cond-> %
                               (get-in dependencies [(::analyzer/to %) (::analyzer/name %) :macro])
                               (assoc ::analyzer/macro true))))))

(defn analyze-forms
  "Analyze submitted forms in one kondo batch, returning form-local facts.

  A declaration row owns its edges. The first tuple member is empty: static
  form analysis without a declaration is not evidence that a call executed,
  and a receipt asserts no program attribute (`seon.turn` settlement analyzes
  declarations alone). No synthetic function is minted.

  `dependencies` is the dependency analysis, namespace to kondo's cached
  namespace; it defaults to the checkout's cache (`shared-dependency-analysis`).
  A host call into a namespace it lacks refuses as
  `:seon.fn/dependency-analysis-missing-error`."
  {:seon/tasks #{"56e90b0bf6b7"}
   :malli/schema
   [:function
    [:=> [:cat :seon.db/database-value
          [:vector [:map
                    [:seon.cluster.eval/source :seon.cluster.eval/source]
                    [:seon.cluster.eval/ns :seon.cluster.eval/ns]
                    [:seon.program/row {:optional true} :seon.program/row]]]]
     [:or [:vector [:tuple :map [:maybe :seon.program/row]]]
      :seon.error/value :seon.fn/namespace-unresolvable-error]]
    [:=> [:cat :seon.db/database-value
          [:vector [:map
                    [:seon.cluster.eval/source :seon.cluster.eval/source]
                    [:seon.cluster.eval/ns :seon.cluster.eval/ns]
                    [:seon.program/row {:optional true} :seon.program/row]]]
          [:maybe [:map-of :symbol :map]]]
     [:or [:vector [:tuple :map [:maybe :seon.program/row]]]
      :seon.error/value :seon.fn/namespace-unresolvable-error]]]}
  ([database requests] (analyze-forms database requests nil))
  ([database requests dependencies]
  (let [resolved
        (mapv
         (fn [{source :seon.cluster.eval/source
               namespace-ref :seon.cluster.eval/ns
               :as request}]
           (let [namespace-entity
                 (db/pull database [:seon.ns/name] namespace-ref)]
             (if-let [namespace-name (:seon.ns/name namespace-entity)]
               (assoc request :namespace-name namespace-name
                              :form-source source)
               (vary-meta {:seon.error/at (java.util.Date.)
                 :seon.error/layer :seon.fn/analysis
                 :seon.error/operation 'seon.fn/analyze-forms
                 ::namespace-unresolvable true
                 :seon.fn/source source
                 :seon.error/message (str "Cannot analyze the form because its namespace reference "
                      (pr-str namespace-ref)
                      " does not resolve to :seon.ns/name.")
                 :seon.error/expected :seon.ns/name
                 :seon.error/offending namespace-ref
                 :seon.error/data {:seon.fn/namespace-ref namespace-ref
                  :seon.fn/namespace-row namespace-entity}} assoc :seon.error/declared-schema :seon.fn/namespace-unresolvable-error))))
         requests)
        refusal (some #(when (::namespace-unresolvable %) %) resolved)]
    (if refusal
      refusal
      (let [{analysis :seon.fn/analysis
             spans :seon.fn/source-spans
             analyzed-source :seon.fn/source
             function-entities :seon.fn/function-rows}
            (runtime-analysis-batch database resolved)
            analysis (with-dependency-analysis
                      analysis
                      (or dependencies
                          (shared-dependency-analysis (::analyzer/unanalyzed-namespaces analysis))))
            projection (or (db/carried-projection database)
                           (schema/projection-from-database database))]
        (mapv (fn [request [first-row last-row]]
                (analyzed-form
                 (source-analysis analysis first-row last-row)
                 function-entities
                 (some-> (:seon.program/row request)
                         (assoc :seon.program/analyzed-source-digest
                                (:seon.fn.file/digest (text-context analyzed-source))))
                 projection))
              requests spans))))))

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
     :seon.error/value :seon.fn/namespace-unresolvable-error]]}
  [database source namespace-ref program-entity]
  (let [result (analyze-forms
                database
                [(cond-> {:seon.cluster.eval/source source
                          :seon.cluster.eval/ns namespace-ref}
                   program-entity (assoc :seon.program/row program-entity))])]
    (if (:seon.fn/namespace-unresolvable result) result (first result))))

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
  {:malli/schema [:=> [:cat :map [:set :qualified-symbol]] :nil]}
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
                      {::findings findings
                       :seon.fn/index-refused true})))))

(defn- invocations-by-definition
  "Explicit `:seon.fn/invokes` attributes of each analyzed definition."
  {:malli/schema [:=> [:cat :map] [:map-of :qualified-symbol :seon.fn/invokes]]}
  [analysis]
  (into {}
        (keep (fn [entry]
                (when-let [attributes (seq (invocation-attributes (or (::analyzer/meta entry) {})))]
                  [(symbol (str (::analyzer/ns entry)) (str (::analyzer/name entry)))
                   (set attributes)])))
        (::analyzer/var-definitions analysis)))

(defn- invoked-attributes
  "Every attribute an analyzed definition declares it invokes."
  {:malli/schema [:=> [:cat :map] [:set :qualified-keyword]]}
  [analysis]
  (into #{} (mapcat val) (invocations-by-definition analysis)))

(defn- population-targets
  "Declared targets of `attributes` in `projection`'s declaration population.

  One walk of the population, and none when no attribute is invoked."
  {:malli/schema [:=> [:cat :seon.schema/projection [:set :qualified-keyword]]
                  [:map-of :qualified-keyword [:set :qualified-symbol]]]}
  [projection attributes]
  (if (empty? attributes)
    {}
    (declared-function-targets attributes (vals (:seon.schema.projection/forms projection))
                               qualified-symbol?)))

(defn- program-targets
  "The population's declared targets that name a function of the program.

  The program is `functions` (the analyzed batch and what it already knows)
  plus every definition `database` holds: an invoker's targets live in the
  whole declaration population, never only in the files analyzed with it."
  {:malli/schema [:=> [:cat [:or :nil :seon.db/database-value]
                       [:map-of :qualified-keyword [:set :qualified-symbol]]
                       [:set :qualified-symbol]]
                  [:map-of :qualified-keyword [:set :qualified-symbol]]]}
  [database targets functions]
  (let [candidates (vec (remove functions (into #{} (mapcat val) targets)))
        known (if (and database (seq candidates))
                (db/q '[:find [?symbol ...] :in $ [?symbol ...]
                        :where [_ :seon.fn/sym ?symbol]]
                      database candidates)
                [])
        _ (when (map? known)
            (throw (ex-info (:seon.error/message known) known)))
        program (into functions known)]
    (into {} (keep (fn [[attribute symbols]]
                     (when-let [kept (seq (filter program symbols))]
                       [attribute (set kept)])))
          targets)))

(defn- namespace-context
  "Requires, aliases, refers and imports of one namespace (its `ns` form and
  top-level `require`s), from the clause facts the analyzer attached to its
  definition (`with-namespace-clauses`),
  never a second read of the file. An `:as-alias` require creates no load
  edge (clojure `load-lib`, core.clj:6074-6078); an import binds its simple
  class name, as `clojure.core/import` does. clj-kondo records no renamed
  local, so a `:rename` is refused by name."
  {:malli/schema [:=> [:cat :map]
                  [:map [:requires [:set :symbol]]
                   [:aliases [:map-of :symbol :symbol]]
                   [:refers [:map-of :symbol :qualified-symbol]]
                   [:imports [:map-of :symbol :symbol]]]]}
  [{::analyzer/keys [clause-usages clause-refers clause-imports clause-renames]}]
  (when-some [rename (first clause-renames)]
    (let [message "A require's :rename is not indexed: clj-kondo's analysis records no renamed local."]
      (throw (ex-info message {:seon.fn/index-refused true
                               :seon.cluster.source/phase :analysis
                               :seon.error/message message
                               ::analysis-entry rename}))))
  {:requires (into #{} (comp (remove ::analyzer/as-alias) (map ::analyzer/to)) clause-usages)
   :aliases (into {} (comp (filter ::analyzer/alias)
                           (map (juxt ::analyzer/alias ::analyzer/to))) clause-usages)
   :refers (into {} (map (fn [{::analyzer/keys [to] target-name ::analyzer/name}]
                           [target-name (symbol (str to) (str target-name))]))
                 clause-refers)
   :imports (into {} (map (fn [{class-name ::analyzer/class}]
                            [(symbol (subs class-name (inc (or (str/last-index-of class-name ".") -1))))
                             (symbol class-name)]))
                  clause-imports)})

(defn- analysis-entities-by-file
  "Rows by file, with source-observed writes independent of schema membership.

  `schema-targets` are the declaration population's targets of the invoked
  attributes, already restricted to program functions (`program-targets`)."
  [analysis first-party-functions contexts schema-targets projection]
  (let [used-keywords (keywords-by-holder analysis)
        host-bound (host-bound-callers analysis first-party-functions projection)
        invocations (invocations-by-definition analysis)
        invoked-attributes (into #{} (mapcat val) invocations)
        declared-calls
        (reduce-kv
         (fn [calls filename context]
           (let [values (map kondo.utils/sexpr
                             (:children (kondo.utils/parse-string-all (:text context))))
                 targets (merge-with set/union schema-targets
                                     (declared-function-targets invoked-attributes values first-party-functions))
                 holders (select-keys invocations
                                      (keep (fn [entry]
                                              (when (= filename (::analyzer/filename entry))
                                                (symbol (str (::analyzer/ns entry)) (str (::analyzer/name entry)))))
                                            (::analyzer/var-definitions analysis)))]
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
               :writes (writes-by-writer analysis)
               :call-arities (call-arities-by-caller analysis calls-by-caller)}
        namespace-contexts
        (into {}
              (map (fn [entry]
                     [(::analyzer/name entry)
                      (assoc (namespace-context entry)
                             :namespace-meta (::analyzer/meta entry))]))
              (::analyzer/namespace-definitions analysis))]
    (reduce
     (fn [entities entry]
       (if-let [entity (if (::analyzer/ns entry)
                      (var-entity projection contexts namespace-contexts edges entry)
                      (cond-> (namespace-entity projection contexts
                                              (get namespace-contexts (::analyzer/name entry))
                                              entry)
                        (seq (get unresolved-by-file (::analyzer/filename entry)))
                        (assoc :seon.fn/unresolved-references
                               (get unresolved-by-file (::analyzer/filename entry)))))]
         (let [entity (cond-> entity
                     (:seon.fn/sym entity)
                     (assoc :seon.fn/host-bound?
                            (host-bound-declaration? entry host-bound)))]
           (update entities (::analyzer/filename entry) (fnil conj []) entity))
         entities))
     {}
     (concat (::analyzer/namespace-definitions analysis)
             (::analyzer/var-definitions analysis)))))

(defn source-rows
  "Construct source declarations through the indexer's analysis and row owners.

  The supplied namespace is the caller's current resolver context, including
  uncommitted aliases. `declared` names that context's Vars declared without
  a program row (a `declare`); they join the prelude, so a call to one is an
  edge as it is in the file. Prelude declarations resolve calls but never
  become submitted rows. A submitted form has no file; each row's
  `:seon.fn/form-span` is the analyzer's exact byte span within `source`,
  transient provenance a caller drops before storing the row. Its contract's
  parsed facts are the finalizer's: [[contract-facts-row]] under the
  projection the definition is admitted in."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/database-value :seon.program/shapes
          :seon.ns/ns
          :string [:set :keyword]]
     :seon.program/rows]
    [:=> [:cat :seon.db/database-value :seon.program/shapes
          :seon.ns/ns
          :string [:set :keyword] [:vector :qualified-symbol]]
     :seon.program/rows]]}
  ([database entity-shapes resolver-namespace source declared-attributes]
   (source-rows database entity-shapes resolver-namespace source declared-attributes []))
  ([database entity-shapes namespace-entity source _declared-attributes declared]
  (let [namespace-name (:seon.ns/name namespace-entity)
        {analysis :seon.fn/analysis
         text :seon.fn/source
         spans :seon.fn/source-spans
         function-entities :seon.fn/function-rows}
        (runtime-analysis-batch
         database [{:namespace-name namespace-name
                    :form-source source
                    :seon.fn/namespace-row namespace-entity
                    :seon.fn/declared declared}])
        [first-row last-row] (first spans)
        context (text-context text)
        ;; The submitted form starts its own line after the prelude.
        base (nth (::byte-line-starts context) (dec first-row))
        functions (into (first-party-function-symbols analysis)
                        (map :seon.fn/sym) function-entities)
        submitted (source-analysis analysis first-row last-row)
        projection (or (db/carried-projection database)
                       (schema/projection-from-database database))
        entities (analysis-entities-by-file
              submitted
              functions {"<stdin>" context}
              (program-targets database
                               (population-targets
                                (db/carried-projection database)
                                (invoked-attributes submitted))
                               functions)
              projection)]
    (assert-seed-contracts! (get entities "<stdin>" []) database #{})
    (into []
          (comp
           (filter #(or (:seon.fn/sym %) (:seon.test/sym %)))
           ;; The one digest owner: the namespace's own facts, never the
           ;; analysis `ns` form widened by interpreter refers.
           (map #(assoc % :seon.program/definition-digest
                        (db/definition-digest-of projection % namespace-entity)))
           (map (fn [entity]
                  (cond-> (program/declaration-row
                           (db/carried-projection database)
                           (program/canonical-row
                            entity-shapes (dissoc entity :seon.fn/file :seon.fn/form-span))
                           :all :agent)
                    (:seon.fn/form-span entity)
                    (assoc :seon.fn/form-span
                           (mapv #(- % base) (:seon.fn/form-span entity)))))))
          (get entities "<stdin>")))))

(defn- lint-entities
  {:malli/schema [:=> [:cat :string [:fn seon.fn/source-file?] :map [:sequential :map] [:sequential :map]] [:vector :map]]}
  [directory file context entities findings]
  (let [path (.getCanonicalPath ^java.io.File file)
        declarations (filter :seon.fn/form-span entities)]
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
             declaration (when-not (:seon.program/declarations-examined found) found)
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

(defn- declaration-digests
  {:malli/schema [:=> [:cat :seon.program/rows] :seon.fn.file/declaration-digests]}
  [entities]
  (let [namespaces (into {} (keep #(when (:seon.ns/name %) [(:seon.ns/name %) %])) entities)]
    (into {}
          (keep
           (fn [entity]
             (let [identity (program/row-identity entity)
                   namespace-name (or (:seon.ns/name entity)
                                      (second (:seon.fn/ns entity))
                                      (second (:seon.test/ns entity)))
                   aliases (into {} (map (juxt :seon.ns.alias/local :seon.ns.alias/target-ns))
                                 (:seon.ns/aliases (get namespaces namespace-name)))
                   source (or (:seon.fn/source entity) (:seon.test/source entity) (:seon.ns/source entity))]
               (when (or source (:seon.schema/form entity))
                 [identity
                  (id/digest
                   64
                   [(select-keys entity [:seon.fn/sym :seon.test/sym :seon.ns/name
                                      :seon.schema/key :seon.schema/form :seon.fn/spec
                                      :seon.fn/doc :seon.ns/doc
                                      :seon.fn/arglists :seon.fn/private? :seon.fn/macro?
                                      :seon.fn/defined-by])
                    (when source (signature/declaration-metadata source (or namespace-name 'user) aliases))
                    (when (:seon.ns/name entity)
                      (into (sorted-set)
                            (keep #(when (and (or (:seon.fn/sym %) (:seon.test/sym %))
                                              (not (:seon.fn/private? %))
                                              (= namespace-name (second (or (:seon.fn/ns %) (:seon.test/ns %)))))
                                     (or (:seon.fn/sym %) (:seon.test/sym %)))) entities))])]))))
          entities)))

(defn- artifact
  [entity-shapes directory file root context entities findings]
  (let [canonical-path (fs/canonical-relative-path directory (.getCanonicalPath ^java.io.File file))
        file-entity (cond-> {:seon.fn.file/relative-path canonical-path
                          :seon.fn.file/digest (:seon.fn.file/digest context)}
                   root (assoc :seon.fn.file/relative-root (fs/canonical-relative-path directory root)))
        file-entity (cond-> file-entity
                   (some :seon.fn/unresolved-references entities)
                   (assoc :seon.fn/unresolved-references
                          (into #{} (mapcat :seon.fn/unresolved-references) entities)))
        entities (mapv #(dissoc % :seon.fn/unresolved-references) entities)
        entities (into (into [file-entity] entities)
                   (lint-entities directory file context entities findings))
        entities (walk/postwalk
              (fn [value]
                (if (and (vector? value)
                         (= :seon.fn.file/relative-path (first value))
                         (= 2 (count value)))
                  [:seon.fn.file/relative-path (fs/canonical-relative-path directory (second value))]
                  value))
              entities)
        findings (mapv (fn [finding]
                         (-> finding
                             (assoc :seon.fn.finding/relative-path
                                    (fs/canonical-relative-path directory (::analyzer/filename finding)))
                             (dissoc ::analyzer/filename)))
                       findings)
        canonical-entities
        (mapv #(cond-> (assoc (program/canonical-row entity-shapes %)
                              :seon.schema.admission/source :core)
                 (:seon.fn/calls %)
                 (update :seon.fn/calls set))
              entities)]
    (cond->
     {:seon.fn.file/relative-path canonical-path
      :seon.fn.file/digest (:seon.fn.file/digest context)
      :seon.fn.file/rows canonical-entities
      :seon.fn.file/declaration-digests (declaration-digests canonical-entities)
      :seon.fn.file/identities
      (->> canonical-entities
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
     [?caller :seon.fn/invokes ?attribute]
     [?holder ?attribute ?target]
     [?target :seon.fn/sym]]
    [(declared-edge ?caller ?target)
     [?caller :seon.fn/invokes ?attribute]
     [?holder ?attribute ?symbol]
     [(symbol? ?symbol)]
     [?target :seon.fn/sym ?symbol]]])

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
  "Owned handlers and explicit invokers reach their current declared targets.
  Data rows may name a target by symbol or reference; keyword readers do not
  become invokers. The indexed walk and Datalog consumers share these rules."
  {:malli/schema [:=> [:cat :seon.db/database-value]
                  [:or [:set [:tuple :int :int]] :seon.db/invalid-read-error]]}
  [database]
  (db/q '[:find ?caller ?target :in $ %
          :where (declared-edge ?caller ?target)]
        database declared-reference-rules))

(defn- gate-set-in
  "Reverse-walk names, including a seed whose definition has been removed."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       [:map-of :int :qualified-symbol] [:set :qualified-symbol]
                       [:map-of :qualified-symbol [:set :qualified-symbol]]
                       [:sequential :qualified-symbol]]
                  [:or :seon.fn/reverse-closure-result :seon.db/invalid-read-error]]}
  [database identities tests incoming seeds]
  (loop [pending (vec seeds) seen #{}]
    (if-let [target (peek pending)]
      (if (seen target)
        (recur (pop pending) seen)
        (let [calls (db/datoms database :avet :seon.fn/calls target)
              references (db/datoms database :avet :seon.fn/references target)
              subjects (db/datoms database :avet :seon.test/subject target)
              refusal (some #(when (:seon.db/invalid-read %) %) [calls references subjects])]
          (if refusal
            refusal
            (let [referrers (into (get incoming target #{})
                                 (keep #(get identities (:e %)))
                                 (concat calls references subjects))]
              (recur (into (pop pending) referrers) (conj seen target))))))
      {:seon.fn/affected (set/difference (set/intersection (set (vals identities)) seen) tests)
       :seon.fn/tests (vec (sort (set/intersection tests seen)))})))

(defn- gate-sets-in
  "Select tests through surviving named edges in one immutable database.
   Acquire identity and genuine declaration-ref joins once per operation."
  {:seon/tasks #{"e4c8eac6a69a"}
   :malli/schema [:=> [:cat :seon.db/database-value
                       [:sequential :qualified-symbol]]
                  [:or [:map-of :qualified-symbol :seon.fn/reverse-closure-result] :seon.db/invalid-read-error]]}
  [database function-symbols]
  (let [identity-entities (db/q '[:find ?entity ?symbol
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
        refusal (some #(when (:seon.db/invalid-read %) %)
                      [identity-entities test-symbols declared file-references handlers])]
    (if refusal
      refusal
      (let [identities (into {} identity-entities)
            incoming (reduce (fn [result [caller target]]
                               (if-let [caller-symbol (get identities caller)]
                                 (update result target (fnil conj #{}) caller-symbol)
                                 result))
                             {} (concat (map (fn [[caller target]]
                                               [caller (get identities target)]) declared)
                                        file-references handlers))]
        (reduce (fn [result function-symbol]
                  (let [selected (gate-set-in database identities (set test-symbols)
                                              incoming [function-symbol])]
                    (if (:seon.db/invalid-read selected)
                      (reduced selected)
                      (assoc result function-symbol selected))))
                {} (distinct function-symbols))))))

(defn- read-or-throw!
  "`value`, or a throw carrying it when it is a read refusal; a walk's reads
  stop at the first refusal and its catch answers that refusal."
  {:malli/schema [:=> [:cat :seon.schema/value] :seon.schema/value]}
  [value]
  (if (db/read-refusal? value) (throw (ex-info "Call reach unavailable." value)) value))

(defn- identity-names
  "`[attribute symbol]` of each identity entity in `entities`, in one bound query."
  {:malli/schema [:=> [:cat [:fn clojure.core/ifn?] :seon.db/database-value [:set :int]]
                  [:map-of :int [:tuple :keyword :qualified-symbol]]]}
  [read! database entities]
  (into {} (map (fn [[entity attribute named]] [entity [attribute named]]))
        (read! (db/q '[:find ?entity ?attribute ?symbol :in $ [?entity ...] [?attribute ...]
                       :where [?entity ?attribute ?symbol]]
                     database (vec entities) [:seon.fn/sym :seon.test/sym]))))

(defn- declared-callers
  "The caller entities of each declared invocation target, keyed by its symbol."
  {:malli/schema [:=> [:cat [:fn clojure.core/ifn?] :seon.db/database-value]
                  [:map-of :qualified-symbol [:set :int]]]}
  [read! database]
  (let [edges (read! (declared-reference-edges database))
        names (identity-names read! database (into #{} cat edges))]
    (reduce (fn [incoming [caller target]]
              (update incoming (second (names target)) (fnil conj #{}) caller))
            {} edges)))

(defn- call-reach
  "The tests exercising `seeds`: each test calling a seed or calling a function
  that calls one (owner 2026-09-26: the tests of A and of A's callers), and a
  seed that is itself a test. The edges are `:seon.fn/calls` (a capability
  owner's handler is one, `var-row`), the declared invocation edges
  (`declared-reference-edges`) and `:seon.test/subject`. A symbol mention
  (`:seon.fn/references`, a file's unresolved references) calls nothing, so it
  selects nothing: a test quoting `'a/b` is not a test of `a/b`."
  {:malli/schema [:=> [:cat :seon.db/database-value [:sequential :seon.fn/sym]]
                  [:or [:vector :seon.test/sym] :seon.db/invalid-read-error]]}
  [database seeds]
  (try
    (let [read! read-or-throw!
          named (fn [entities] (identity-names read! database entities))
          declared (declared-callers read! database)
          ;; Entities naming each target by `attribute`, one index read per pair.
          referrers (fn [attribute targets]
                      (into #{} (mapcat #(map :e (read! (db/datoms database :avet attribute %)))) targets))
          seed-callers (into (referrers :seon.fn/calls seeds) (mapcat declared) seeds)
          callers (into #{} (keep (fn [[_ [attribute symbol]]] (when (= :seon.fn/sym attribute) symbol)))
                        (named seed-callers))
          added (remove (set seeds) callers)
          frontier (into (set seeds) callers)
          reaching (-> seed-callers
                       (into (referrers :seon.fn/calls added))
                       (into (mapcat declared) added)
                       (into (referrers :seon.test/subject frontier)))]
      (vec (sort (into (into #{} (keep (fn [[_ [attribute symbol]]] (when (= :seon.test/sym attribute) symbol)))
                             (named reaching))
                       (read! (db/q '[:find [?symbol ...] :in $ [?symbol ...] :where [_ :seon.test/sym ?symbol]]
                                    database (vec seeds)))))))
    (catch clojure.lang.ExceptionInfo failure
      (if (db/read-refusal? (ex-data failure)) (ex-data failure) (throw failure)))))

(defn reverse-closure
  "The functions that transitively call `seeds`, and the tests exercising them.

  `:seon.fn/affected` walks call reach to its fixed point: each function that
  calls a seed or an already reached function through `:seon.fn/calls` or a
  declared invocation edge, plus each seed that is a function row. A symbol
  mention calls nothing (owner 2026-09-26), so a quoted `'a/b` reaches nothing:
  on default a leaf's mention-inclusive closure was 530 functions and its call
  closure 1 (perf-profile 2026-09-26). `:seon.fn/tests` is test selection's
  answer for the same seeds ([[call-reach]]). One bound query per level; no
  whole-program index."
  {:malli/schema [:=> [:cat :seon.fn/gate-request]
                  [:or :seon.fn/reverse-closure-result :seon.db/invalid-read-error]]}
  [{database :seon.db/db seeds :seon.fn/seeds}]
  (try
    (let [read! read-or-throw!
          seeds (vec seeds)
          declared (declared-callers read! database)
          functions (fn [entities]
                      (into #{} (keep (fn [[_ [attribute named]]]
                                        (when (= :seon.fn/sym attribute) named)))
                            (identity-names read! database entities)))
          present (set (read! (db/q '[:find [?symbol ...] :in $ [?symbol ...]
                                      :where [_ :seon.fn/sym ?symbol]]
                                    database seeds)))
          affected
          (loop [frontier seeds seen (set seeds) affected present]
            (if (empty? frontier)
              affected
              (let [callers (into (set (read! (db/q '[:find [?caller ...] :in $ [?target ...]
                                                      :where [?caller :seon.fn/calls ?target]]
                                                    database frontier)))
                                  (mapcat declared) frontier)
                    fresh (vec (remove seen (functions callers)))]
                (recur fresh (into seen fresh) (into affected fresh)))))
          tests (read! (call-reach database seeds))]
      {:seon.fn/affected affected :seon.fn/tests tests})
    (catch clojure.lang.ExceptionInfo failure
      (if (db/read-refusal? (ex-data failure)) (ex-data failure) (throw failure)))))

(defn gate-sets
  "Select the tests exercising changed definitions (`call-reach`).
  The map arity seeds one frontier with all changed symbols. The positional arity
  answers, per seed, the conservative reverse closure through calls,
  references and declared edges that destructive isolation reads
  (`seon.test/destructive-reach`), never selection."
  {:seon/tasks #{"e4c8eac6a69a"}
   :malli/schema
   [:function
    [:=> [:cat :seon.fn/gate-request]
     [:or [:vector :seon.test/sym] :seon.db/invalid-read-error]]
    [:=> [:cat :seon.db/database-value [:sequential :seon.fn/sym]]
     [:or [:map-of :seon.fn/sym [:vector :seon.test/sym]] :seon.db/invalid-read-error]]]}
  ([{database :seon.db/db seeds :seon.fn/seeds}]
   (call-reach database (vec seeds)))
  ([database function-symbols]
   (let [result (gate-sets-in database function-symbols)]
     (if (:seon.db/invalid-read result)
       result
       (into {} (map (fn [[sym closure]] [sym (:seon.fn/tests closure)])) result)))))

(defn unresolved-callers
  "Report calls into indexed namespaces with no current function definition.
   The analyzed population count makes an empty observation explicit. Missing
   names are evidence; this query does not invent target definitions. Recorded
   test reach to absent names is reported separately as stale historical evidence."
  {:malli/schema [:=> [:cat :seon.db/database-value]
                  [:or :seon.program/unresolved-report :seon.error/value :seon.db/invalid-read-error :seon.schema/validation-refusal]]}
  [database]
  (let [entities (db/q '[:find ?attribute ?caller ?callee
                     :in $ [?attribute ...]
                     :where [?entity ?attribute ?caller]
                            [?entity :seon.program/analyzed-source-digest]
                            [?entity :seon.fn/calls ?callee]
                            [(namespace ?callee) ?namespace-name]
                            [(clojure.core/symbol ?namespace-name) ?namespace]
                            [_ :seon.ns/name ?namespace]
                            (not-join [?callee] [_ :seon.fn/sym ?callee])]
                   database [:seon.fn/sym :seon.test/sym])
        stale (db/q '[:find ?test ?callee
                      :where [?entity :seon.test/sym ?test]
                             [?entity :seon.test/reach ?callee]
                             (not-join [?callee] [_ :seon.fn/sym ?callee])]
                    database)
        population (db/q '[:find (count ?entity) .
                           :where [?entity :seon.program/analyzed-source-digest]] database)]
    (or (when (db/read-refusal? entities) entities)
        (when (db/read-refusal? stale) stale)
        (when (db/read-refusal? population) population)
        {:seon.db/basis-t (db/basis-t database)
         :seon.program/analyzed-count (or population 0)
         :seon.program/stale-test-reach
         (mapv (fn [[test callee]] {:seon.test/sym test :seon.fn/callee callee})
               (sort-by pr-str stale))
         :seon.program/unresolved-callers
         (mapv (fn [[attribute caller callee]]
                 {:seon.program/identity [attribute caller]
                  :seon.fn/callee callee})
               (sort-by pr-str entities))})))

(defn gate-set
  "The tests exercising one function identity (`call-reach`).
  Use gate-sets when one operation asks about several identities."
  {:seon/tasks #{"300aca1ad57c"}
   :malli/schema [:=> [:cat :seon.db/database-value :seon.fn/sym]
                  [:or [:vector :seon.test/sym] :seon.db/invalid-read-error]]}
  [database function-symbol]
  (call-reach database [function-symbol]))

(defn tests-reaching
  "Compatibility spelling for the shared gate-set derivation."
  {:seon/tasks #{"300aca1ad57c"}
   :malli/schema [:=> [:cat :seon.db/database-value :seon.fn/sym]
                  [:or [:vector :seon.test/sym] :seon.db/invalid-read-error]]}
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
                  [:or :seon.fn.contract/report :seon.error/value :seon.db/invalid-read-error :seon.schema/validation-refusal]]}
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
     (when (db/read-refusal? function-symbols) function-symbols)
     (when (db/read-refusal? specs) specs)
     (when (db/read-refusal? caller-counts) caller-counts)
     (let [stored-attributes (set (schema.datahike/database-attributes-core-in projection))
           ;; A compiled slot carries the callables its predicates were bound to
           ;; (`schema/compilable-form`); a finding records the AUTHORED form, so
           ;; each bound callable reads back as the symbol it was bound from.
           predicate-names (into {} (map (fn [[predicate callable]] [callable predicate]))
                                 (schema/predicate-functions-in projection))
           authored (fn [form]
                      (walk/postwalk
                       (fn [value]
                         (cond (var? value) (symbol value)
                               (and (fn? value) (contains? predicate-names value)) (get predicate-names value)
                               :else value))
                       form))
           specs (into {} specs)
           caller-counts (into {} caller-counts)
           structural-findings
           (fn [slot]
             (schema.internal/fold-walk
              slot
              (fn [node _path]
                (cond
                  (and (= :map (m/type node)) (empty? (m/children node)))
                  [[:seon.fn.contract.finding/bare-map (m/form node)]]
                  (= :maybe (m/type node))
                  [[:seon.fn.contract.finding/maybe (m/form node)]]
                  :else []))
              {}))
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
                    {:seon.schema/compiled slot})))
           slot-findings
           (fn [slot position guarded?]
             (let [permissive (permissive-findings slot guarded?)
                   structural (structural-findings slot)
                   unguarded
                   (when (and (not guarded?)
                              (#{:* :+ :repeat} (m/type slot))
                              (not-any? #(= :seon.fn.contract.finding/unguarded-variadic
                                            (first %))
                                        permissive))
                     [[:seon.fn.contract.finding/unguarded-variadic (m/form slot)]])
                   unstorable
                   (schema.internal/fold-walk
                    slot
                    (fn [node _path]
                      (let [reference (when (m/-ref-schema? node) (m/-ref node))]
                        (if (and (stored-attributes reference)
                                 (not (schema.datahike/storable-attribute-in? projection reference)))
                          [[:seon.fn.contract.finding/unstorable-attribute reference]]
                          [])))
                    {})]
               (mapv (fn [[finding form]]
                       {:seon.fn.contract/position position
                        :seon.fn.contract/form (authored form)
                        :seon.fn.contract/finding finding})
                     (concat permissive structural unguarded unstorable))))
           arity-findings
           (fn [contract]
             (into []
                   (mapcat
                    (fn [arity]
                      (let [{:keys [input output guard]} (m/-function-info arity)
                            guarded? (some? guard)
                            slots (if (= :catn (m/type input))
                                    (mapv peek (m/children input))
                                    (m/children input))]
                        (concat
                         (mapcat (fn [index slot]
                                   (slot-findings slot [:seon.fn.contract.position/input index] guarded?))
                                 (range) slots)
                         (slot-findings output :seon.fn.contract.position/output guarded?)))))
                   (m/-function-schema-arities contract)))]
       (->> function-symbols
            (mapcat
             (fn [function-symbol]
               (let [caller-count (long (get caller-counts function-symbol 0))
                     spec-string (get specs function-symbol)
                     findings
                     (if spec-string
                       (arity-findings (mr/schema (:seon.schema.projection/registry projection) function-symbol))
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
                  [:or [:vector :seon.fn/sym] :seon.db/invalid-read-error]]}
  [database keyword]
  (let [result (db/q '[:find [?function-symbol ...]
               :in $ ?keyword
               :where
               [?function :seon.fn/keywords ?keyword]
               [?function :seon.fn/sym ?function-symbol]]
             database keyword)]
    (if (:seon.db/invalid-read result)
      result
      (vec (sort result)))))

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
  {:malli/schema [:=> [:cat :keyword :qualified-symbol :map] :nil]}
  [rule function-symbol data]
  (throw
   (ex-info
    "The declared capability graph is malformed."
    (merge {
            :seon.fn/capability-rule rule
            :seon.fn/sym function-symbol :seon.fn/index-refused true}
           data))))

(defn- reaches?
  [entities-by-symbol root target]
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
                            (entities-by-symbol function-symbol)))]
          (recur (into (subvec pending 1) called)
                 (conj visited function-symbol))))
      false)))

(defn- assert-capability-contracts!
  "Validate changed capability facts and their referrers against one database value."
  {:malli/schema [:=> [:cat [:vector :seon.fn.file/artifact]
                       [:or :nil :seon.db/database-value] [:set :qualified-symbol]]
                  [:vector :seon.fn.file/artifact]]}
  [artifacts database removed]
  (let [authored-entities
        (into []
              (comp (mapcat :seon.fn.file/rows)
                    (filter :seon.fn/sym))
              artifacts)
        authored (into {} (map (juxt :seon.fn/sym identity)) authored-entities)
        attributes [:seon.fn/sym :seon.fn/calls :seon.fn/private?
                    :seon.fn/spec :seon.fn/workload :seon.effect/capability]
        read-entity (fn [function-symbol]
                   (when database
                     (let [entity (db/pull database attributes [:seon.fn/sym function-symbol])]
                       (when (:seon.error/at entity) (throw (ex-info (:seon.error/message entity) entity)))
                       (when (:seon.fn/sym entity)
                         (cond-> (dissoc entity :db/id)
                           (:seon.fn/calls entity) (update :seon.fn/calls set))))))
        changed (when database
                  (into removed (keep (fn [[symbol entity]]
                                        (when (not= (select-keys entity attributes) (read-entity symbol))
                                          symbol))) authored))
        affected (when database
                   (loop [selected changed
                          pending selected]
                     (let [parents (db/q '[:find [?symbol ...]
                                            :in $ [?target ...] [?edge ...]
                                            :where [?caller ?edge ?target]
                                                   [?caller :seon.fn/sym ?symbol]]
                                          database (vec pending)
                                          [:seon.fn/calls :seon.effect/capability])
                           _ (when (:seon.error/at parents)
                               (throw (ex-info (:seon.error/message parents) parents)))
                           added (set/difference (set parents) selected)]
                       (if (seq added) (recur (into selected added) added) selected))))
        entities-by-symbol (fn [function-symbol]
                         (when-not (removed function-symbol)
                           (or (get authored function-symbol)
                               (read-entity function-symbol))))
        function-entities (if database (into [] (keep entities-by-symbol) affected) authored-entities)
        marked (sort-by :seon.fn/sym
                        (filter :seon.effect/capability function-entities))]
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
      (let [handler (entities-by-symbol handler-symbol)]
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
            (sort-by :seon.fn/sym function-entities)]
      (when (and (nil? capability)
                 (contains? calls request-symbol))
        (capability-refused! :unmarked-request function-symbol {})))
    (doseq [{function-symbol :seon.fn/sym} marked]
      (when-not (reaches? entities-by-symbol function-symbol request-symbol)
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
           [:seon.schema/projection {:optional true} :seon.schema/projection]
           [:seon.schema.projection/forms {:optional true} :map]]]
    :seon.fn.file/artifact]}
  [{path :seon.fn/source-path
    known-functions :seon.fn.file/first-party-functions
    roots :seon.fn/roots :as request}]
  (let [directory (or (:seon.fn/root request) (fs/source-directory))
        file (rooted-file directory path)]
    (when-not (source-file? file)
      (throw (ex-info "A file artifact requires one existing Clojure file."
                      {:seon.fn/source-path (.getCanonicalPath file) :seon.fn/index-refused true})))
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
      ;; its shapes derived once, both carried to every entity (AGENTS.md 2.1).
      (let [forms (declaration-forms request)
            projection (or (:seon.schema/projection request) (schema/build-projection forms))]
        (artifact (program/shapes-in projection)
                  directory file
                  (containing-root directory (or roots source-roots) file)
                  (get contexts canonical-path)
                  (get (analysis-entities-by-file analysis first-party-functions
                                              contexts
                                              (program-targets
                                               nil
                                               (population-targets
                                                projection
                                                (invoked-attributes analysis))
                                               first-party-functions)
                                              projection)
                       canonical-path
                       [])
                  findings)))))

(defn- manifest-data
  [directory roots artifacts]
  (let [artifacts (->> artifacts
                       (sort-by :seon.fn.file/relative-path)
                       vec)
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

(defn- analyzed-artifacts
  {:malli/schema [:function
                   [:=> [:cat :map :seon.fn/roots :string [:vector :string]
                         [:or :nil :seon.db/database-value] [:or :nil :string]]
                    [:vector :seon.fn.file/artifact]]
                   [:=> [:cat :map :seon.fn/roots :string [:vector :string]
                         [:or :nil :seon.db/database-value] [:or :nil :string]
                         [:maybe [:map-of :string :string]]]
                    [:vector :seon.fn.file/artifact]]
                   [:=> [:cat :map :seon.fn/roots :string [:vector :string]
                         [:or :nil :seon.db/database-value] [:or :nil :string]
                         [:maybe [:map-of :string :string]] [:set :qualified-symbol]]
                    [:vector :seon.fn.file/artifact]]]}
  ([projection roots directory paths database cache-root]
   (analyzed-artifacts projection roots directory paths database cache-root nil))
  ([projection roots directory paths database cache-root captured]
   (analyzed-artifacts projection roots directory paths database cache-root captured #{}))
  ;; `seed` names the functions an earlier batch of the SAME population
  ;; defines: definitions the previous database does not hold yet.
  ([projection roots directory paths database cache-root captured seed]
  (if (empty? paths)
    []
    (let [forms (:seon.schema.projection/forms projection)
          files (mapv io/file paths)
          contexts (if captured
                     (update-vals (select-keys captured paths) text-context)
                     (source-contexts files))
          analysis (analyzer/analyze
                    (cond-> {::analyzer/sources (update-vals contexts :text)
                             ::analyzer/config-root (str (io/file directory analyzer/config-directory))}
                      cache-root (assoc ::analyzer/cache-root cache-root)))
          _ (assert-clean-analysis! analysis (first-party-function-symbols analysis))
          schema-values (keep forms (into #{} (mapcat val) (keywords-by-holder analysis)))
          ;; The sources' own symbols come from the analyzer's one parse.
          mentioned (into (::analyzer/mentioned-symbols analysis)
                          (comp (mapcat #(tree-seq coll? seq %)) (filter qualified-symbol?))
                          schema-values)
          known (when database
                  (db/q '[:find [?symbol ...] :in $ [?symbol ...]
                          :where [_ :seon.fn/sym ?symbol]]
                        database (vec (into mentioned (keep usage-symbol (::analyzer/var-usages analysis))))))
          _ (when (:seon.error/at known) (throw (ex-info (:seon.error/message known) known)))
          functions (-> (set known)
                        (into (first-party-function-symbols analysis))
                        (into seed))
          _ (assert-clean-analysis! analysis functions)
          findings (group-by ::analyzer/filename (publication-findings analysis functions))
          entities (analysis-entities-by-file analysis functions contexts
                                      (program-targets
                                       database
                                       (population-targets projection (invoked-attributes analysis))
                                       functions)
                                      projection)
          entity-shapes (program/shapes-in projection)]
      (mapv (fn [file]
              (let [path (.getCanonicalPath ^java.io.File file)]
                (artifact entity-shapes directory file (containing-root directory roots file)
                          (get contexts path) (get entities path []) (get findings path []))))
            files)))))

(def ^:private signature-attributes
  "Facts a caller's publication lint reads from its callee (B1 §2a step 9): a
  body, docstring or contract edit changes none of them, so it relints no
  caller. Publication lints with every type-derived linter off
  (`seon.fn.analyzer/publication-config`), so a callee's Malli contract, its
  arity refs and the schemas they name never reach a caller's findings."
  #{:seon.fn/sym :seon.fn/arglists :seon.fn/arities :seon.fn/private?
    :seon.fn/macro? :seon.fn/inline? :seon.fn/defined-by :seon.fn/constant?
    :seon.fn.arity/arity :seon.fn.arity/min :seon.fn.arity/max
    :seon.fn.arity/argument-count :seon.fn.arity/order})

(defn caller-files
  "Direct caller files of declarations whose signature the committed report
  changed: identity, arglists, arities, privacy, macro or inline status.

  Costs: two reads proportional to the changed signatures and their direct
  callers; a contract or schema change selects nothing (measured 2026-09-26:
  four changed schema keys had selected 241 of the program's files, 10.7 s
  warm and most of a 47 s boot publication)."
  {:malli/schema [:=> [:cat :seon.db/transaction-report] [:set :string]]}
  [{database :db-after before :db-before :as report}]
  ;; Logical datoms: an arity moved or replaced in a cell reads on its function.
  (let [changed (into #{} (comp (filter #(signature-attributes (:a %))) (map :e)) (db/logical-datoms report))]
    (if (empty? changed)
      #{}
      (let [read! #(if (map? %) (throw (ex-info "Publication could not read changed signatures." %)) %)
            owners (mapcat #(read! (db/q '[:find [?f ...] :in $ [?e ...] :where [?f :seon.fn/arities ?e]]
                                         % (vec changed)))
                           [before database])
            symbols (into #{}
                          (comp (mapcat #(read! (db/pull-many % [:seon.fn/sym] (vec (into changed owners)))))
                                (keep :seon.fn/sym))
                          [before database])
            paths (db/q '[:find [?path ...]
                          :in $ [?symbol ...]
                          :where
                          [?caller :seon.fn/calls ?symbol]
                          [?caller :seon.fn/file ?caller-file]
                          [?caller-file :seon.fn.file/relative-path ?path]]
                        database (vec symbols))]
        (when (map? paths)
          (throw (ex-info "Publication could not read the changed declarations' callers." paths)))
        (set paths)))))

(declare file-entities)

(defn- removed-definition-caller-paths
  "Files outside `selected` holding a stored edge to a `removed` definition.

  One reverse-index read over the analyzer's edges, proportional to the
  removed definitions' callers."
  {:malli/schema [:=> [:cat :seon.db/database-value [:set :qualified-symbol] [:set :string]]
                  [:vector :string]]}
  [database removed selected]
  (let [edges (for [attribute [:seon.fn/calls :seon.fn/references]
                    symbol removed]
                (db/datoms database :avet attribute symbol))
        refusal (some #(when (map? %) %) edges)
        _ (when refusal
            (throw (ex-info "Publication could not read the removed definitions' callers." refusal)))
        ;; Direct index reads: a Datalog clause with both attribute and value
        ;; bound from input collections measured 550 ms on default where the
        ;; two AVET reads take under 1 ms.
        paths (db/q '[:find [?path ...]
                      :in $ [?caller ...]
                      :where
                      [?caller :seon.fn/file ?file]
                      [?file :seon.fn.file/relative-path ?path]]
                    database (into [] (comp cat (map :e) (distinct)) edges))]
    (when (map? paths)
      (throw (ex-info "Publication could not read the removed definitions' callers." paths)))
    (vec (sort (remove selected paths)))))

(defn- invoker-paths
  "Files of stored invokers whose declared targets differ between populations.

  An invoker's edges derive from the declaration population, so a schema
  change that adds, retargets or removes a target under an invoked attribute
  changes the invoker's edges although its own file did not change."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.schema/projection
                       :seon.schema/projection]
                  [:vector :string]]}
  [database before after]
  (if (identical? (:seon.schema.projection/forms before)
                  (:seon.schema.projection/forms after))
    []
    (let [attributes (db/q '[:find [?attribute ...] :where [_ :seon.fn/invokes ?attribute]]
                           database)
          _ (when (map? attributes)
              (throw (ex-info "Publication could not read the stored invocation attributes." attributes)))
          attributes (set attributes)
          old (population-targets before attributes)
          new (population-targets after attributes)
          changed (filterv #(not= (get old %) (get new %)) attributes)
          paths (if (seq changed)
                  (db/q '[:find [?path ...]
                          :in $ [?attribute ...]
                          :where
                          [?invoker :seon.fn/invokes ?attribute]
                          [?invoker :seon.fn/file ?file]
                          [?file :seon.fn.file/relative-path ?path]]
                        database changed)
                  [])]
      (when (map? paths)
        (throw (ex-info "Publication could not read the changed invokers' files." paths)))
      (vec paths))))

(defn- dependent-paths
  "Unselected files whose stored edges the selected change invalidates.

  The callers of `removed` definitions and the invokers of attributes whose
  declared targets changed between the `before` and `after` populations.
  Analysis re-analyzes exactly these; `index!` re-derives them and reconciles
  exactly these, so a supplied file row alone never widens a reconciliation."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.schema/projection
                       :seon.schema/projection [:set :qualified-symbol] [:set :string]]
                  [:vector :string]]}
  [database before after removed selected]
  (->> (concat (removed-definition-caller-paths database removed selected)
               (invoker-paths database before after))
       (remove selected)
       distinct
       sort
       vec))

(defn- analysis-projection
  "The declaration world one analysis of `selected` reads; `index!` asks the same."
  {:malli/schema [:=> [:cat :seon.fn/index-request [:or :nil :seon.schema/projection]
                       [:or :nil [:set :string]]]
                  :seon.schema/projection]}
  [request prior-projection selected]
  (let [schema-change? (or (nil? prior-projection)
                           (some #(str/starts-with? % "resources/seon/schemas/") selected))]
    (or (:seon.schema/projection request)
        (when-not schema-change? prior-projection)
        (schema/build-projection (if schema-change?
                                   (declaration-forms request)
                                   (:seon.schema.projection/forms prior-projection))))))

(defn- analyzed-files
  "Analyze selected files; the database supplies invalidation and removed declarations."
  {:malli/schema [:=> [:cat :seon.fn/index-request] [:vector :seon.fn.file/artifact]]}
  [request]
  (let [directory (fs/absolute-path (fs/source-directory) (or (:seon.fn/root request) "."))
        roots (:seon.fn/roots request)
        database (:seon.source/previous-database request)
        captured (::analyzer/sources request)
        selected (or (:seon.fn/changed-paths request)
                     (when captured (into #{} (map (partial fs/relative-path directory)) (keys captured))))
        files (if selected
                (into [] (comp (map #(rooted-file directory %))
                               (filter #(if captured (contains? captured (.getCanonicalPath ^java.io.File %))
                                           (source-file? %)))
                               (filter #(containing-root directory roots %))) (sort selected))
                (source-files directory roots))
        prior-projection (when database (db/carried-projection database))
        projection (analysis-projection request prior-projection (some-> selected set))
        cache-root (or (::analyzer/cache-root request)
                       (str (io/file directory analyzer/config-directory ".cache")))
        ;; Only the names the selected files declared: the namespaces whose
        ;; resolver entries are superseded and the definitions a removal
        ;; guard compares, never the files' whole entities.
        previous (if (and database selected)
                   (db/q '[:find ?attribute ?value
                           :in $ [?path ...] [?attribute ...]
                           :where [?file :seon.fn.file/relative-path ?path]
                                  [?entity :seon.fn/file ?file]
                                  [?entity ?attribute ?value]]
                         database (vec selected) [:seon.ns/name :seon.fn/sym])
                   [])
        _ (when (map? previous)
            (throw (ex-info (:seon.error/message previous) previous)))
        previous-names (fn [attribute] (into #{} (keep (fn [[a v]] (when (= attribute a) v))) previous))
        _ (analyzer/forget-namespaces! cache-root (previous-names :seon.ns/name))
        artifacts (analyzed-artifacts projection roots directory
                     (mapv #(.getCanonicalPath ^java.io.File %) files) database cache-root captured)
        removed (set/difference (previous-names :seon.fn/sym)
                               (into #{} (comp (mapcat :seon.fn.file/rows) (keep :seon.fn/sym)) artifacts))
        ;; A removed definition's edges live on its CALLERS, and an invoker's
        ;; edges derive from the whole declaration population. Recompute the
        ;; dependent files outside the selection so the deletion guard judges
        ;; their current edges: a caller whose source still names the removed
        ;; definition keeps the edge and the guard refuses by name. The batch
        ;; above seeds their first-party set, so a replacement it introduces
        ;; (absent from the previous database) keeps its new edge.
        dependents (if (and database selected)
                     (dependent-paths database prior-projection projection removed (set selected))
                     [])]
    (let [checked (assert-capability-contracts!
                   (into artifacts
                         (analyzed-artifacts projection roots directory
                                             (mapv #(fs/absolute-path directory %) dependents) database cache-root nil
                                             (into #{} (comp (mapcat :seon.fn.file/rows) (keep :seon.fn/sym)) artifacts)))
                   database removed)]
      (assert-seed-contracts! (into [] (mapcat :seon.fn.file/rows) checked) database removed)
      checked)))

(defn analyze-entities
  "Canonical entities from selected source analysis, without a manifest."
  {:malli/schema [:=> [:cat :seon.fn/index-request] :seon.program/rows]}
  [request]
  (into [] (mapcat :seon.fn.file/rows) (analyzed-files request)))

(declare database-manifest)

(defn build-manifest
  "B4 compatibility export of canonical analysis; file publication uses entities."
  {:malli/schema [:=> [:cat :seon.fn/index-request] :seon.fn.manifest/manifest]}
  [request]
  (let [directory (fs/absolute-path (fs/source-directory) (or (:seon.fn/root request) "."))
        roots (:seon.fn/roots request)
        artifacts (analyzed-files request)]
    (assoc (manifest-data directory (mapv (partial fs/relative-path directory) roots) artifacts)
           :seon.fn.manifest/declaration-digests
           (into {} (map (fn [[key form]] [[:seon.schema/key key] (id/digest 64 [form])]))
                 (declaration-forms request)))))

(defn- entity-by-identity
  [entities]
  (into {} (map (juxt program/row-identity identity)) entities))

(defn- changed-entity-attributes
  [current desired]
  (into #{}
        (filter #(not= (get current %) (get desired %)))
        (into (set (keys current)) (keys desired))))

(defn- scalar-upsert-entities
  [current-entities desired unsafe-attributes]
  (into
   []
   (keep
    (fn [desired-entity]
      (let [program-identity (program/row-identity desired-entity)
            current-entity (get current-entities program-identity)
            changed (changed-entity-attributes current-entity desired-entity)]
        (when (seq changed)
          (apply dissoc desired-entity unsafe-attributes)))))
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
           [:seon.schema/projection :seon.schema/projection]
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
    findings :seon.fn.change/findings
    projection :seon.schema/projection}]
  (let [current-identities (set (:seon.fn.file/identities current))
        desired-identities (set (:seon.fn.file/identities desired))
        current-entities (entity-by-identity (:seon.fn.file/rows current))
        desired-entities (entity-by-identity (:seon.fn.file/rows desired))
        shared-identities (set/intersection current-identities
                                            desired-identities)
        added-identities (set/difference desired-identities
                                         current-identities)
        changed-attributes
        (into #{}
              (mapcat (fn [program-identity]
                        (changed-entity-attributes
                         (get current-entities program-identity)
                         (get desired-entities program-identity))))
              shared-identities)
        unsafe-attributes
        (many-or-component-attributes
         projection
         (into #{} (mapcat keys) (concat (vals current-entities)
                                         (vals desired-entities))))
        added-many-or-component?
        (some (fn [program-identity]
                (some #(contains? (get desired-entities program-identity) %)
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
                  (let [before (get current-entities program-identity)
                        after (get desired-entities program-identity)]
                    (some #(and (contains? before %)
                                (not (contains? after %)))
                          (changed-entity-attributes before after))))
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
       ;; Changed entities retain their scalar declaration, including required
       ;; admission provenance and namespace refs. Omit unchanged components
       ;; and many-valued attributes so publication cannot recreate children.
       :seon.fn.change/rows
       (scalar-upsert-entities current-entities desired unsafe-attributes)
       :seon.fn.change/identities (:seon.fn.file/identities desired)})))

(defn program-entities
  "Canonical program entities, supplied by analysis or discovered from source."
  {:malli/schema [:=> [:cat :seon.fn/index-request] :seon.program/rows]}
  [request]
  (or (:seon.program/rows request)
      (when-let [manifest (:seon.fn/manifest request)]
        (into [] (mapcat :seon.fn.file/rows)
              (cond->> (:seon.fn.manifest/artifacts manifest)
                (:seon.fn/changed-paths request)
                (filter #((:seon.fn/changed-paths request) (:seon.fn.file/relative-path %))))))
      (analyze-entities request)))

(defn- assert-one-entity-per-identity!
  {:malli/schema [:=> [:cat :seon.program/rows] :nil]}
  [desired]
  (when-let [duplicate
             (some (fn [[program-identity n]]
                     (when (> n 1) program-identity))
                   (frequencies (map program/row-identity desired)))]
    (throw
     (ex-info
      "Source indexing refused a duplicate program identity."
      {::identity duplicate :seon.fn/index-refused true}))))

(defn- assert-populated!
  {:malli/schema [:=> [:cat :seon.program/rows] :nil]}
  [desired]
  (doseq [identity-attr [:seon.ns/name :seon.fn/sym]]
    (when-not (some identity-attr desired)
      (throw
       (ex-info
        (str "Source indexing produced no " identity-attr
             " entities; refusing a partial program graph.")
        {::missing-population identity-attr :seon.fn/index-refused true})))))

(defn contract-facts-row
  "`row` with the parsed facts of its `:seon.fn/spec` (its `:seon.fn/arities`)
  under `projection`, the one the definition is admitted in, reading its
  source with `resolver-namespace`'s aliases. A row without a contract is
  unchanged.

  # Costs
  About 0.65 ms for a one-arity contract (default, 2026-09-26, unarmed REPL)."
  {:malli/schema [:=> [:cat :seon.schema/projection :map :seon.program/declaration-row]
                  :seon.program/declaration-row]}
  [projection resolver-namespace entity]
  (program/with-contract-facts
   {:seon.program/row entity
    :seon.program/compile-options (:seon.schema.projection/compile-options projection)
    :seon.program/predicate-functions (schema/predicate-functions-in projection)
    :seon.program/schema-keys (set (keys (:seon.schema.projection/forms projection)))
    :seon.program/reader-aliases
    (into {} (map (juxt :seon.ns.alias/local :seon.ns.alias/target-ns))
          (:seon.ns/aliases resolver-namespace))}))

(defn- add-contract-facts
  ([entities progress!] (add-contract-facts entities progress! nil))
  ([entities progress! supplied-projection]
  (let [schema-forms
        (or (:seon.schema.projection/forms supplied-projection)
        (into (sorted-map)
              (keep (fn [{schema-key :seon.schema/key
                          form-string :seon.schema/form}]
                      (when schema-key
                        [schema-key (edn/read-string form-string)])))
              entities))
        function-contracts
        (into (sorted-map)
              (keep (fn [{function-symbol :seon.fn/sym
                          spec :seon.fn/spec}]
                      (when spec
                        [(symbol function-symbol) (edn/read-string spec)])))
              entities)
        _ (report-index-progress!
           progress!
           (str "contract projection started: "
                (count schema-forms) " schemas, "
                (count function-contracts) " functions"))
        projection
        (or supplied-projection
            (schema/build-projection
             schema-forms function-contracts
             {:seon.schema/validate-render-contracts? true}))
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
              entities)
        total (count entities)
        stride (progress-stride total progress-line-budget)
        parsed-entities
        (mapv (fn [index entity]
                (let [parsed
                      (program/with-contract-facts
                       {:seon.program/row entity
                        :seon.program/compile-options compile-options
                        :seon.program/predicate-functions predicate-functions
                        :seon.program/schema-keys schema-keys
                        :seon.program/reader-aliases
                        (get aliases-by-namespace
                             (second (:seon.fn/ns entity)) {})})
                      completed (inc index)]
                  (when (or (= completed total)
                            (zero? (mod completed stride)))
                    (report-index-progress!
                     progress!
                     (str "contract entities: " completed "/" total)))
                  parsed))
              (range)
              entities)]
    parsed-entities)))

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
              (map (fn [[namespace-name namespace-entity]]
                     [namespace-name
                      (into {}
                            (map (juxt :seon.ns.alias/local
                                       :seon.ns.alias/target-ns))
                            (:seon.ns/aliases namespace-entity))]))
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
           (let [entity (db/pull db
                              [:seon.program/analyzed-source-digest
                               {:seon.fn/arities [:seon.fn.arity/argument-count]}]
                              function)
                 arities (:seon.fn/arities entity)]
             (or (nil? (:seon.program/analyzed-source-digest entity))
                 (empty? arities)
                 (some #(not (contains? % :seon.fn.arity/argument-count)) arities))))
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

(defn- changed-schema-keys
  {:malli/schema [:=> [:cat :seon.fn/index-request] [:set :keyword]]}
  [request]
  (if (some #(str/starts-with? % "resources/seon/schemas/") (:seon.fn/changed-paths request))
    (let [before (:seon.schema.projection/forms
                  (db/carried-projection (:seon.source/previous-database request)))
          after (declaration-forms request)]
      (into #{} (filter #(not= (get before %) (get after %)))
            (concat (keys before) (keys after))))
    #{}))

(defn- incremental-projection
  "Reuse the prior declaration world for body edits; when its schema or
  contract population changes, derive from it by replacement, so only the
  changed declarations and their reverse closure recompile
  (`schema/build-projection` with the prior as its base). The bridge owns
  compilation.

  The prior's own admissions, exports and predicate bindings carry over
  (`schema/population-options`); the derived projection equals the whole
  build of the same forms and contracts in every other member.

  # Costs
  Armed, measured at default's REPL 2026-09-27: ten changed files (eight
  changed contracts) 854 ms as a whole build, 43 ms derived."
  {:malli/schema [:=> [:cat :seon.fn/index-request :seon.program/rows] [:or :nil :map]]}
  [request source-rows]
  (when-let [database (:seon.source/previous-database request)]
    (let [prior (or (db/carried-projection database) (schema/projection-from-database database))
          paths (:seon.fn/changed-paths request)
          old-entities (file-entities database (vec paths) :seon.fn/file)
          schema-keys (changed-schema-keys request)
          source-forms (into {} (keep #(when (:seon.schema/key %)
                                        [(:seon.schema/key %) (edn/read-string (:seon.schema/form %))]))
                             source-rows)
          prior-forms (:seon.schema.projection/forms prior)
          forms (if (or (seq schema-keys) (seq source-forms) (some :seon.schema/key old-entities))
                  (merge (apply dissoc prior-forms (concat (keep :seon.schema/key old-entities) schema-keys))
                         (select-keys (declaration-forms request) schema-keys) source-forms)
                  prior-forms)
          prior-contracts (:seon.schema.projection/function-contracts prior)
          authored-contracts (into {} (keep #(when (:seon.fn/spec %)
                                              [(:seon.fn/sym %) (edn/read-string (:seon.fn/spec %))]))
                                   source-rows)
          removed (set/difference (set (keep :seon.fn/sym old-entities)) (set (keys authored-contracts)))
          changed? (or (some #(contains? prior-contracts %) removed)
                       (some (fn [[symbol contract]] (not= contract (get prior-contracts symbol)))
                             authored-contracts))]
      (if (and (identical? forms prior-forms) (not changed?))
        prior
        (schema/build-projection forms
          (merge (apply dissoc prior-contracts removed) authored-contracts)
          (assoc (schema/population-options prior) :seon.schema/projection prior))))))

(defn- desired-entities
  {:malli/schema [:=> [:cat :seon.fn/index-request [:or :nil [:=> [:cat :string] :seon.schema/value]]] :seon.program/rows]}
  [request progress!]
  (let [source-rows (program-entities request)
        packaged-forms (declaration-forms request)
        partial? (some? (:seon.fn/changed-paths request))
        prior-entities
        (when partial?
          (into {} (map (juxt program/row-identity #(dissoc % :seon.fn/arities)))
                (file-entities (:seon.source/previous-database request)
                           (vec (:seon.fn/changed-paths request)) :seon.fn/file)))
        changed-entities
        (if partial?
          ;; File analysis records provenance on every declaration. A new file
          ;; digest alone does not change an otherwise identical declaration.
          (filterv #(not= (dissoc % :seon.program/analyzed-source-digest)
                          (dissoc (get prior-entities (program/row-identity %))
                                  :seon.program/analyzed-source-digest))
                   source-rows)
          source-rows)
        projection (or (when partial? (incremental-projection request source-rows))
                       (schema/build-projection packaged-forms))
        ;; A dependent schema entity re-derives with the schemas it reaches.
        selected-forms (if partial?
                         (select-keys packaged-forms
                                      (schema/dependent-schema-keys
                                       projection (into (changed-schema-keys request)
                                                        (keep :seon.schema/key) changed-entities)))
                         packaged-forms)
        ;; Generatability is judged in the packaged population, from its
        ;; compiled schemas (the population's projection, already built).
        canonical-schemas
        (if (seq selected-forms)
          (mapv (partial accretion/schema-row-in (schema/declaration-projection packaged-forms))
                (schema/canonical-schema-entities projection selected-forms)) [])
        canonical-keys (into #{} (map :seon.schema/key) canonical-schemas)
        source-only
        (remove (fn [entity]
                  (contains? canonical-keys (:seon.schema/key entity)))
                changed-entities)
        desired-identities (into #{} (map program/row-identity) (concat source-only canonical-schemas))]
    (doseq [{schema-key :seon.schema/key
             form-string :seon.schema/form}
            (filter :seon.schema/key source-only)]
      (when-not (and form-string
                     (schema/malli-form? (edn/read-string form-string)))
        (throw
         (ex-info "Source indexing refused a non-Malli schema declaration."
                  {:seon.schema/key schema-key :seon.fn/index-refused true}))))
    (filterv
     #(desired-identities (program/row-identity %))
     (add-contract-facts
      (mapv #(assoc % :seon.schema.admission/source :core)
            (into (into (vec source-only) canonical-schemas)
                  (filter #(and (:seon.ns/name %)
                                (not (desired-identities (program/row-identity %)))))
                  source-rows))
      progress!
      (when partial? projection)))))

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
  {:malli/schema [:=> [:cat [:vector :map] [:seqable :qualified-keyword]] [:map-of [:tuple :qualified-keyword :seon.schema/value] :string]]}
  [entities identity-attributes]
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
                               {::identity (vec identities)
                                :seon.fn/index-refused true})))
                           identities))))
              (mapcat #(filter map? (tree-seq coll? seq %)) entities))]
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
  {:malli/schema
   [:=> [:cat :seon.schema/projection [:vector :map] [:seqable :qualified-keyword]]
    [:map
     [:seon.fn/index-entities [:vector :map]]
     [:seon.fn/index-identity-operations :seon.store/transaction-data]
     [:seon.fn/index-keyword-operations :seon.store/transaction-data]]]}
  [projection entity-maps identity-attributes]
  (let [identity-attribute-set (set identity-attributes)
        tempids (index-tempids entity-maps identity-attributes)
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
                          {::identity eid
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
                  entity-maps)]
        (doseq [entity-map entity-maps]
          (rewrite-entity (dissoc entity-map :seon.fn/keywords)))
        {:seon.fn/index-entities
         (->> @entities (sort-by key) (mapv val))
         :seon.fn/index-identity-operations
         (into []
               (map (fn [[[attribute value] tempid]]
                      [:db/add tempid attribute value]))
               (sort-by (comp pr-str key) tempids))
         :seon.fn/index-keyword-operations keyword-operations}))))

(defn- normalized-index-entity
  "Compare stored refs by identity and anonymous components by their values."
  [entity-shapes database entity-map identity-attributes entity]
  (let [entity-identity
        (fn [entity-map]
          (some (fn [attribute]
                  (when-let [entry (find entity-map attribute)]
                    [attribute (val entry)]))
                identity-attributes))]
    (letfn [(normalize-many [values]
              (reduce (fn [result value]
                        (let [normalized (normalize-value value)]
                          (if (and (map? normalized)
                                   (contains? normalized :seon.error/at)
                                   (contains? normalized :seon.error/layer)
                                   (contains? normalized :seon.error/operation))
                            (reduced normalized)
                            (conj result normalized))))
                      #{} values))
            (normalize-map [entity-map]
              (if (and (map? entity-map)
                       (contains? entity-map :seon.error/at)
                       (contains? entity-map :seon.error/layer)
                       (contains? entity-map :seon.error/operation))
                entity-map
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
                       (if (and (map? normalized)
                                (contains? normalized :seon.error/at)
                                (contains? normalized :seon.error/layer)
                                (contains? normalized :seon.error/operation))
                         (reduced normalized)
                         (assoc result attribute normalized)))))
                 {} entity-map)))
            (normalize-value [value]
              (cond
                (map? value)
                (let [expanded (if (= #{:db/id} (set (keys value)))
                                 (entity (:db/id value))
                                 value)]
                  (or (entity-identity expanded) (normalize-map expanded)))

                (vector? value) (mapv normalize-value value)
                (set? value) (into #{} (map normalize-value) value)
                :else value))]
      (normalize-map (program/canonical-row entity-shapes entity-map)))))

(defn- seek
  "The datoms of one index seek; a refused read throws with its whole refusal."
  {:malli/schema [:=> [:cat :seon.db/database-value :keyword [:* :seon.schema/value]]
                  :seon.db/datoms]}
  [database index & components]
  (let [found (apply db/datoms database index components)]
    (when (:seon.error/at found)
      (throw (ex-info (:seon.error/message found) found)))
    found))

(defn- reconcile-tx-in
  "Replace source definitions, owning entities by the shapes `entity-shapes` carries."
  {:malli/schema
   [:=> [:cat :seon.program/shapes :seon.db/database-value
         [:vector :map] [:sequential :seon.db/ref]]
    [:or :seon.db/tx-data :seon.error/value]]}
  [entity-shapes database entity-maps previous-identities]
  (let [projection (or (db/carried-projection database)
                       (schema/projection-from-database database))
        identity-attributes (db/identity-attributes database)
        ;; ONE READ for every desired entity: inside the transaction each
        ;; `db/pull` paid its read's declaration lookup, 0.42 ms a call (6,106
        ;; calls, 2.56 s of a 306-file publication, default 2026-09-27).
        identities (mapv program/row-identity entity-maps)
        pulled (db/pull-many database '[*] identities)
        held (when (vector? pulled) (zipmap identities pulled))
        entity (memoize #(if-let [entry (find held %)] (val entry) (db/pull database '[*] %)))
        desired-identities (set identities)
        removed (remove desired-identities previous-identities)
        ;; A note citing a removed file is uncited text. Its citation
        ;; component's `:seon.issue.citation/file` is a required ref, so the
        ;; component leaves in the removal's own transaction; one AVET seek per
        ;; removed identity, never a scan of the notes.
        uncited (when (get (:schema database) :seon.issue.citation/file)
                  (for [[attribute value] removed
                        removed-datom (seek database :avet attribute value)
                        citation (seek database :avet :seon.issue.citation/file (:e removed-datom))]
                    (:e citation)))
        removed (into (vec removed) (sort uncited))
        desired (vec entity-maps)]
    (if-not held
      pulled
    (loop [pending desired changes []]
      (if-let [entity-map (first pending)]
        (let [current (entity (program/row-identity entity-map))]
          (if (and (map? current)
                   (contains? current :seon.error/at)
                   (contains? current :seon.error/layer)
                   (contains? current :seon.error/operation))
            current
            (let [normalized-current
                  (normalized-index-entity entity-shapes database current
                                        identity-attributes entity)
                  normalized-desired
                  (normalized-index-entity entity-shapes database entity-map
                                        identity-attributes entity)
                  refusal (some #(when (and (map? %)
                                            (contains? % :seon.error/at)
                                            (contains? % :seon.error/layer)
                                            (contains? % :seon.error/operation)) %)
                                [normalized-current normalized-desired])]
              (cond
                refusal refusal
                (= normalized-current normalized-desired)
                (recur (next pending) changes)
                :else
                (recur
                 (next pending)
                 (conj changes
                       {:seon.program/row
                        (if current
                          (select-keys entity-map
                            (conj (program/changed-attributes entity-shapes normalized-current normalized-desired)
                                  (first (program/row-identity entity-map))))
                          entity-map)
                        :seon.fn/retractions
                        (if-let [entity-id (:db/id current)]
                          (vec
                           (butlast
                            (program/exact-replacement-tx-in
                             entity-shapes
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
                keyword-operations)))))))

(defn reconcile-tx
  "Replace source definitions while retaining identities and agent facts.

  Immutable source identities identify definitions removed by an edit.
  The current writer database decides their exact replacement, so a concurrent
  agent transaction cannot make an earlier read authoritative."
  {:malli/schema
   [:=> [:cat :seon.db/database-value [:vector :map]
          :seon.fn.file/identities]
    [:or [:vector :seon.schema/value] :seon.error/value]]}
  [database entities previous-identities]
  (reconcile-tx-in (program/shapes-in (db/carried-projection database))
                   database entities previous-identities))

(defn report-identities
  "Identities touched by a writer report or datoms between two database values.
  The explicit arity also accepts composed report datoms and retained history."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/transaction-report]
     [:or :seon.reconcile/adopt-identities :seon.db/invalid-read-error]]
    [:=> [:cat :seon.db/database-value :seon.db/database-value
          [:vector :seon.db/transaction-report-datom]]
     [:or :seon.reconcile/adopt-identities :seon.db/invalid-read-error]]]}
  ([{before :db-before after :db-after :as report}]
   ;; Logical datoms: a vector element changed in a cell names its owner.
   (report-identities before after (vec (db/logical-datoms report))))
  ([before after datoms]
  (let [entities (into #{} (map :e) datoms)
        identities (fn [database]
                     (let [entity-maps (db/pull-many database
                                             (db/identity-attributes database)
                                             (vec entities))]
                       (if (:seon.db/invalid-read entity-maps)
                         entity-maps
                         (into #{} (mapcat #(dissoc % :db/id)) entity-maps))))
        previous (identities before)
        current (identities after)]
    (or (when (:seon.db/invalid-read previous) previous)
        (when (:seon.db/invalid-read current) current)
        (into (set previous) current)))))

(defn- reached-entities
  "Entities `identities` reach through refs, keyed by identity and `:db/id`, less
  `omitted` attributes. One `pull-many` per ref depth: each armed read costs ~0.1 ms,
  so one pull per entity made a whole-program read take seconds. Past the named
  rows the walk descends only through entities with a `program` attribute: a data
  entity such as a steward agent is read for its identity, never followed."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.fn.file/identities [:set :keyword]
                       [:set :qualified-keyword]]
                  [:or [:map-of :seon.schema/value [:maybe :map]] :seon.error/value]]}
  [database identities omitted program]
  (letfn [(walk [[found wanted] k entity]
            (let [entity (some-> entity (as-> e (apply dissoc e omitted)))
                  found (cond-> (assoc found k entity) entity (assoc (:db/id entity) entity))]
              (reduce-kv
               (fn [acc attribute value]
                 (if (= :db.type/ref (get-in (:schema database) [attribute :db/valueType]))
                   (reduce (fn [[found wanted :as acc] {id :db/id :as ref}]
                             (cond (or (contains? found id) (wanted id)) acc
                                   (< 1 (count ref)) (walk acc id ref)
                                   :else [found (conj wanted id)]))
                           acc (if (map? value) [value] value))
                   acc))
               [found wanted]
               (if (or (vector? k) (some program (keys entity))) entity {}))))]
    (loop [pending (vec (distinct identities)) found {}]
      (if (empty? pending)
        found
        (let [pulled (db/pull-many database '[*] pending)]
          (if (:seon.error/at pulled)
            pulled
            (let [[found wanted] (reduce (fn [acc [k entity]] (walk acc k entity))
                                         [found #{}] (map vector pending pulled))]
              (recur (vec (remove #(contains? found %) wanted)) found))))))))

(defn published-index-entities
  "Read compiled entities with portable program refs and complete owned components."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/database-value]
     [:or :seon.program/rows :seon.error/value]]
    [:=> [:cat :seon.db/database-value :seon.fn.file/identities]
     [:or :seon.program/rows :seon.error/value]]
    [:=> [:cat :seon.db/database-value :seon.fn.file/identities [:set :keyword]]
     [:or :seon.program/rows :seon.error/value]]]}
  ([database]
   (let [identities
         (reduce (fn [result attribute]
                   (let [values (db/q '[:find [?value ...] :in $ ?attribute
                                        :where [_ ?attribute ?value]] database attribute)]
                     (if (:seon.error/at values)
                       (reduced values)
                       (into result (map #(vector attribute %)) values))))
                 [] (filter #(get (:schema database) %) program/identity-attributes))]
     (if (:seon.error/at identities) identities
         (published-index-entities database identities))))
  ([database identities]
   (published-index-entities database identities #{}))
  ([database identities omitted]
  ;; The entity schemas' `:seon.program/partition` decides what an entity may inline:
  ;; a ref into a data entity (a steward agent, whose runtime and turns point back)
  ;; is written as that entity's declared identity.
  (let [program (program/program-attributes (db/carried-projection database))
        declared (db/identity-attributes database)
        entities (reached-entities database identities omitted program)
        entity (fn [identity] (if (:seon.error/at entities) entities (get entities identity)))]
   (letfn [(reference [value]
            (let [pulled (entity (:db/id value))]
              (if (and (map? pulled)
                       (contains? pulled :seon.error/at)
                       (contains? pulled :seon.error/layer)
                       (contains? pulled :seon.error/operation))
                pulled
                (or (program/row-identity pulled)
                    (when-not (some program (keys pulled))
                      (some #(when-some [v (get pulled %)] [% v]) declared))
                    (entity-map pulled)))))
          (entity-map [entity]
            (if (and (map? entity)
                     (contains? entity :seon.error/at)
                     (contains? entity :seon.error/layer)
                     (contains? entity :seon.error/operation))
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
                                   (if (and (map? portable-member)
                                            (contains? portable-member :seon.error/at)
                                            (contains? portable-member :seon.error/layer)
                                            (contains? portable-member :seon.error/operation))
                                     portable-member
                                     (recur (next pending)
                                            (conj values portable-member))))
                                 values))
                             (reference value))
                           (if (= :db.cardinality/many
                                  (get-in (:schema database)
                                          [attribute :db/cardinality]))
                             (set value)
                             value))]
                     (if (and (map? portable)
                              (contains? portable :seon.error/at)
                              (contains? portable :seon.error/layer)
                              (contains? portable :seon.error/operation))
                       (reduced portable)
                       (assoc result attribute portable)))))
               {} entity)))]
    (loop [pending (seq identities) entity-maps []]
      (if-let [identity (first pending)]
        (let [pulled (entity identity)]
          (cond
            (:seon.error/at pulled) pulled
            (empty? pulled) (recur (next pending) entity-maps)
            :else (let [portable (entity-map pulled)]
                    (if (:seon.error/at portable)
                      portable
                      (recur (next pending) (conj entity-maps portable))))))
        entity-maps))))))

(defn- file-identities
  {:malli/schema [:=> [:cat :seon.db/database-value [:vector :string]
                       [:enum :seon.fn/file :seon.lint/file]]
                  :seon.fn.file/identities]}
  [database paths file-attribute]
  ;; Index seeks, proportional to the named files' declarations: the Datalog
  ;; join on a variable file attribute took 990 ms for one 55-declaration file
  ;; (these seeks: 15 ms, the same set). One pull-many reads their identities.
  (let [entities (vec (for [path paths
                            file (seek database :avet :seon.fn.file/relative-path path)
                            entity (seek database :avet file-attribute (:e file))]
                        (:e entity)))
        pulled (db/pull-many database (filterv #(get (:schema database) %) program/identity-attributes)
                             entities)]
    (when (:seon.error/at pulled) (throw (ex-info (:seon.error/message pulled) pulled)))
    (into [] (distinct)
          (for [entity-map pulled attribute program/identity-attributes
                :let [value (get entity-map attribute)] :when (some? value)]
            [attribute value]))))

(defn file-entities
  "Read declarations or findings through file refs for only the named paths."
  {:malli/schema [:=> [:cat :seon.db/database-value [:vector :string]
                       [:enum :seon.fn/file :seon.lint/file]]
                  [:or :seon.program/rows :seon.error/value]]}
  [database paths file-attribute]
  (published-index-entities database (file-identities database paths file-attribute)))

(defn database-manifest
  "Derive selected file artifacts from published facts; nil paths is an explicit complete export."
  {:malli/schema [:=> [:cat :seon.db/database-value :string :seon.fn/roots
                       [:maybe [:vector :string]]]
                  :seon.fn.manifest/manifest]}
  [database directory roots paths]
  (let [analysis-paths (db/q '[:find [?path ...]
                               :in $ [?path ...]
                               :where [?file :seon.fn.file/relative-path ?path]
                                      [_ :seon.fn/file ?file]]
                             database
                             (or paths
                                 (db/q '[:find [?path ...]
                                          :where [_ :seon.fn/file ?file]
                                                 [?file :seon.fn.file/relative-path ?path]] database)))
        _ (when (:seon.error/at analysis-paths)
            (throw (ex-info (:seon.error/message analysis-paths) analysis-paths)))
        analysis-paths (vec analysis-paths)
        identities (into (into (mapv #(vector :seon.fn.file/relative-path %) analysis-paths)
                               (file-identities database analysis-paths :seon.fn/file))
                         (file-identities database analysis-paths :seon.lint/file))
        entities (published-index-entities database identities #{:seon.fn/arities})
        _ (when (:seon.error/at entities) (throw (ex-info (:seon.error/message entities) entities)))
        by-file (group-by #(or (:seon.fn.file/relative-path %)
                               (second (:seon.fn/file %))
                               (second (:seon.lint/file %))) entities)
        artifacts (mapv (fn [path]
                          (let [entities (get by-file path)
                                file (some #(when (:seon.fn.file/relative-path %) %) entities)]
                            {:seon.fn.file/relative-path path
                             :seon.fn.file/digest (:seon.fn.file/digest file)
                             :seon.fn.file/rows (vec entities)
                             :seon.fn.file/findings (filterv :seon.lint/id entities)
                             :seon.fn.file/declaration-digests (declaration-digests entities)
                             :seon.fn.file/identities (vec (sort-by pr-str (map program/row-identity entities)))}))
                        analysis-paths)
        projection (db/carried-projection database)
        schema-digests (if (or (nil? paths)
                               (some #(str/starts-with? % "resources/seon/schemas/") paths))
                         (into {} (map (fn [[key form]] [[:seon.schema/key key] (id/digest 64 [form])]))
                               (:seon.schema.projection/forms projection))
                         {})]
    (assoc (manifest-data directory (mapv (partial fs/relative-path directory) roots) artifacts)
           :seon.fn.manifest/declaration-digests schema-digests)))

(defn- reconciled-paths
  "Selected paths plus the dependent files this population must reconcile.

  Derived here, at the reconciliation authority, from the previous database,
  the selection and the supplied rows' definitions (`dependent-paths`), never
  from which file rows happen to be supplied. A dependent file the rows do
  not cover refuses by name instead of keeping its stale edges."
  {:malli/schema [:=> [:cat :seon.fn/index-request] [:or :nil [:set :string]]]}
  [{database :seon.source/previous-database supplied :seon.program/rows :as request}]
  (when-let [paths (some-> (:seon.fn/changed-paths request) set)]
    (let [identities (file-identities database (vec paths) :seon.fn/file)
          removed (set/difference (into #{} (keep (fn [[attribute value]]
                                                    (when (= :seon.fn/sym attribute) value)))
                                        identities)
                                  (into #{} (keep :seon.fn/sym) supplied))
          prior (db/carried-projection database)
          dependents (dependent-paths database prior (analysis-projection request prior paths)
                                      removed paths)
          covered (into #{} (keep :seon.fn.file/relative-path) supplied)
          missing (vec (remove covered dependents))]
      (when (seq missing)
        (throw (ex-info (str "Program population omits the analysis of dependent files: "
                             (pr-str missing))
                        {:seon.fn/index-refused true
                         :seon.fn/dependent-paths dependents
                         :seon.fn/missing-paths missing})))
      (into paths dependents))))

(defn- reconcile-call
  "The writer operation replacing `previous-identities` with `rows`: the writer
  hands its current db to `reconcile-tx-in`, so the authority decides the diff,
  and a refused diff throws with its whole value."
  {:malli/schema [:=> [:cat [:maybe :seon.schema/projection] :seon.program/shapes
                       [:vector :map] [:sequential :seon.db/ref]]
                  :seon.store/transaction-operation]}
  [projection entity-shapes entities previous-identities]
  [:db.fn/call
   (fn [database]
     (let [tx-data (schema/call-with-projection
                    projection
                    #(reconcile-tx-in entity-shapes database entities previous-identities))]
       (if (and (map? tx-data) (contains? tx-data :seon.error/at))
         (throw (ex-info (:seon.error/message tx-data) tx-data))
         tx-data)))])

(defn adoption-tx
  "Transaction data adopting the program `identities` from `source` — the
  published commit's rows — into whichever database the writer holds."
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.db/database-value
                       :seon.reconcile/adopt-identities]
                  [:or :seon.db/tx-data :seon.error/value]]}
  [projection source identities]
  (let [entities (published-index-entities source (vec identities))]
    (if (map? entities)
      entities
      (do (assert-one-entity-per-identity! entities)
          [(reconcile-call projection (program/shapes-in projection) entities (vec identities))]))))

(defn index!
  "Populate one fresh source scratch branch from static analysis.

  The optional callback receives bounded progress lines while contract rows
  are derived and after each ordered phase commits. Observation never changes
  transaction boundaries; the scratch branch remains unpublished until every
  phase and the source seal commit. An explicit previous source database
  selects in-place reconciliation for an opted-in development cluster.
  Fresh publication uses its supplied construction projection."
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
     source-database :seon.source/database
     expected-head :seon.source/expected-head :as request}
    progress!]
   (let [request (cond-> request
                   (not source-database) (assoc :seon.program/rows (program-entities request)))
         entity-shapes (program/shapes-in
                     (or (:seon.schema/projection request)
                         (schema/build-projection (declaration-forms request))))
         entity-maps (if source-database
                (if-let [identities (:seon.reconcile/adopt-identities request)]
                  (published-index-entities source-database (vec identities))
                  (published-index-entities source-database))
                (let [declarations (desired-entities request progress!)
                      file-paths (into #{} (map :seon.fn.file/relative-path)
                                       (filter :seon.fn.file/relative-path (program-entities request)))
                      paths (:seon.fn/changed-paths request)
                      inputs (:seon.source/relative-file-digests request)]
                  (into declarations
                        (keep (fn [[path digest]]
                                (when (and (not (file-paths path))
                                           (or (nil? paths) (paths path)))
                                  {:seon.fn.file/relative-path path
                                   :seon.fn.file/digest digest
                                   :seon.schema.admission/source :core})))
                        inputs)))]
     (if (and (map? entity-maps)
              (contains? entity-maps :seon.error/at)
              (contains? entity-maps :seon.error/layer)
              (contains? entity-maps :seon.error/operation))
       entity-maps
       (let [_ (assert-one-entity-per-identity! entity-maps)
             _ (when-not (or (:seon.fn/changed-paths request)
                             (:seon.reconcile/adopt-identities request))
                 (assert-populated! entity-maps))
             existing (some (fn [identity-attribute]
                              (db/q '[:find ?entity .
                                     :in $ ?attribute
                                     :where [?entity ?attribute]]
                                    @connection identity-attribute))
                            [:seon.ns/name :seon.fn/sym :seon.test/sym])]
     (when (and existing (not previous-database))
       (throw (ex-info "Program indexing requires a fresh source scratch branch."
                       {::existing-program-entity existing :seon.fn/index-refused true})))
     (if previous-database
       (let [reconciled (when-not (:seon.reconcile/adopt-identities request)
                          (reconciled-paths request))
             previous-identities
             (if-let [identities (:seon.reconcile/adopt-identities request)]
               (vec identities)
               (if-let [paths reconciled]
               (let [surviving (into #{} (map program/row-identity) (:seon.program/rows request))]
                 (into (vec (map #(vector :seon.schema/key %) (changed-schema-keys request)))
                       (remove surviving)
                       (concat (file-identities previous-database (vec paths) :seon.fn/file)
                               (file-identities previous-database (vec paths) :seon.lint/file))))
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
                           program/identity-attributes))))
             previous-identities
             (if-let [inputs (:seon.source/relative-file-digests request)]
               (into previous-identities
                     (comp (remove #(contains? inputs %))
                           (map #(vector :seon.fn.file/relative-path %)))
                     (:seon.fn/changed-paths request))
               previous-identities)
             projection (schema/handed-projection)
             _ (report-index-progress! progress! "development reconciliation transaction")
             report
             (require-committed!
              (db/transact!
               connection
               (cond-> {:tx-data
                        (cond->> [(reconcile-call projection entity-shapes entity-maps previous-identities)]
                          ;; Entities derived from one published commit refuse at
                          ;; the writer once another publication moved its head.
                          expected-head (cons [:db.fn/call registry/head-guard-tx expected-head])
                          true vec)}
                 process (assoc :tx-meta {:seon.db/process process})))
              :seon.fn/population)
             changed-identities (require-committed! (report-identities report)
                                                     :seon.fn/population)
             caller-paths (when (and (not source-database) (:seon.fn/changed-paths request))
                            (set/difference (caller-files report) reconciled))
             findings-report
             (when (seq caller-paths)
               (report-index-progress! progress! (str "analysis callers: " (count caller-paths) " files"))
               (let [directory (or (:seon.fn/root request)
                                   (get-in request [:seon.fn/manifest :seon.fn.manifest/root])
                                   (fs/source-directory))
                     database (:db-after report)
                     artifacts (analyzed-artifacts
                                (or (db/carried-projection database) projection)
                                (or (:seon.fn/roots request)
                                    (get-in request [:seon.fn/manifest :seon.fn.manifest/relative-roots])) directory
                                (mapv #(fs/absolute-path directory %) (sort caller-paths))
                                database
                                (str (io/file directory analyzer/config-directory ".cache")))
                     findings (into [] (comp (mapcat :seon.fn.file/rows) (filter :seon.lint/id)) artifacts)
                     previous (require-committed! (file-entities database (vec caller-paths) :seon.lint/file)
                                                  :seon.fn/population)
                     tx-data (require-committed!
                              (reconcile-tx-in entity-shapes database findings
                                               (mapv program/row-identity previous))
                              :seon.fn/population)]
                 ;; This unpublished connection is owned by this population;
                 ;; nothing can change its database between this diff and write.
                 (when (seq tx-data)
                   (report-index-progress! progress! "caller findings transaction")
                   (require-committed!
                    (db/transact! connection
                                  (cond-> {:tx-data tx-data}
                                    process (assoc :tx-meta {:seon.db/process process})))
                    :seon.fn/population))))
             changed-identities (into changed-identities
                                      (when findings-report
                                        (require-committed! (report-identities findings-report)
                                                            :seon.fn/population)))]
         {:seon.reconcile/converged? (empty? changed-identities)
          :seon.reconcile/operations (count changed-identities)
          :seon.db/transaction-report report
          :seon.reconcile/adopt-identities changed-identities})
       ;; Fresh branches have installed attributes, but their canonical schema
       ;; entities arrive in this transaction. Capture the supplied construction
       ;; projection instead of deriving an empty declaration world from @conn.
       (let [database (db/db connection)
             projection (or (db/carried-projection database)
                            (schema/projection-from-database database))
             identity-attributes (db/identity-attributes database)
             {entities :seon.fn/index-entities
              identity-operations :seon.fn/index-identity-operations
              keyword-operations :seon.fn/index-keyword-operations}
             (compile-index-transaction projection entity-maps identity-attributes)]
       (report-index-progress!
        progress!
        (str "program population compiled: " (count entities)
             " entities, " (count identity-operations) " identities, "
             (count keyword-operations) " keyword facts"))
       (commit-index-phase! connection process progress! :seon.fn/population
                            (into (into identity-operations entities)
                                  keyword-operations))
       {:seon.reconcile/converged? false
        :seon.reconcile/operations (count entity-maps)})))))))

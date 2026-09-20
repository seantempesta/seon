(ns seon.fn.analyzer
  "Static Clojure source analysis for program-graph indexing."
  (:require [clj-kondo.core :as clj-kondo]
            [clj-kondo.impl.cache :as kondo.cache]
            [clj-kondo.impl.core :as kondo.core]
            [clj-kondo.impl.utils :as kondo.utils]
            [clj-kondo.impl.rewrite-clj.reader :as kondo.reader]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [cognitect.transit :as transit]
            [clojure.string :as str]))

(def config-directory
  "The analyzer configuration input root, shared with publication identity."
  ".clj-kondo")
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
    :protocol-impls true
    :symbols true
    :keywords true
    :var-definitions {:shallow false
                      :meta true}
    :namespace-definitions {:shallow false
                            :meta true}}})

(def ^:private finding-keys
  [:filename :row :col :end-row :end-col :level :message :type
   :lang :cljc :langs])

(def ^:private publication-config
  ;; Malli owns runtime type admission. Kondo's inferred return types depend
  ;; on callee bodies, so they cannot identify declaration-keyed findings.
  ;; Other linters consume the same inferred argument/condition tags
  ;; (clj-kondo/impl/linters.clj, lint-arg-types! and
  ;; lint-deferred-conditions!). Disable at admission,
  ;; never by dropping findings from a completed analysis.
  (reduce #(assoc-in %1 [:linters %2 :level] :off)
          analysis-config
          [:type-mismatch :redundant-str-call :is-message-not-string
           :redundant-primitive-coercion :equals-float :not-empty?
           :constant-condition]))

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

(def ^:private mirror-directory "tmp/analysis-mirror")

(def ^:private mirror-prefix
  ;; One canonical prefix per process: `analyzed-source-path` runs once per
  ;; produced analysis entry, and the checkout does not move under a JVM.
  (delay (str (.getCanonicalPath (io/file "." mirror-directory))
              java.io.File/separator)))

(defn- analyzed-source-path
  "The checkout file a linted path carries the bytes of.

  `analyze` never hands clj-kondo a live source file: it writes the captured
  text to one private mirror under `tmp/analysis-mirror/<analysis>/` whose
  tail is the source's own absolute path, so the rows clj-kondo reports and
  the text they are sliced against are the same bytes by construction. Every
  rule that asks where an analyzed file came from — cache ownership, cache
  obsolescence, the filename on an emitted row — asks this, so a mirrored
  analysis answers exactly as a direct one would. A path that is not a mirror
  is its own answer."
  [^String path]
  (let [prefix @mirror-prefix]
    (if (str/starts-with? path prefix)
      (let [tail (subs path (count prefix))
            separator (str/index-of tail java.io.File/separator)]
        (if separator (subs tail separator) path))
      path)))

(defn- source-located
  [entry]
  (cond-> entry
    (string? (::filename entry)) (update ::filename analyzed-source-path)))

(defn- location
  [entry]
  (source-located (present-values entry (into [:filename] location-keys))))

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
      :fixed-arities :varargs-min-arity :defmethod :dispatch-val-str :var-quote :lang]))))

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
  (source-located (present-values entry finding-keys)))

(defn- manifest-source-roots
  "The canonical source-root directories this checkout's own manifest declares.
  clj-kondo keys its dependency cache by NAMESPACE NAME, so only source the
  checkout owns may write the checkout's cache. `deps.edn` is the ecosystem's
  own declaration of that ownership — `:paths` plus every alias's
  `:extra-paths` — never a list maintained here. The `.` entry of the test
  alias is discarded with every other candidate that is not strictly inside
  the checkout: a root containing the cache itself would admit every scratch
  file under `tmp/` (`deps.edn:137`)."
  [^java.io.File checkout]
  (let [checkout (.getCanonicalFile checkout)
        manifest (io/file checkout "deps.edn")
        declarations (when (.isFile manifest)
                       (try
                         (edn/read-string (slurp manifest))
                         (catch Throwable _ nil)))
        prefix (str (.getPath checkout) java.io.File/separator)]
    (into #{}
          (comp (filter string?)
                (map #(.getCanonicalFile (io/file checkout ^String %)))
                (filter #(.isDirectory ^java.io.File %))
                (filter #(str/starts-with? (.getPath ^java.io.File %) prefix))
                (map #(.getPath ^java.io.File %)))
          (concat (:paths declarations)
                  (mapcat :extra-paths (vals (:aliases declarations)))))))

(def ^:private source-roots-of
  (memoize (fn [checkout-path] (manifest-source-roots (io/file checkout-path)))))

(defn- checkout-source?
  "True when this analyzed path is the checkout's own declared source.
  A fixture root, a scratch file, or the synthesized `-` stdin buffer is not:
  its analysis is isolated by construction, so it can never leave a stub
  under a first-party namespace name in the shared dependency cache. A mirror
  answers for the checkout file whose captured bytes it carries, so mirroring
  a file does not change whether its analysis owns the cache."
  [path]
  (let [checkout (.getCanonicalFile (io/file "."))
        roots (source-roots-of (.getPath checkout))
        candidate (.getCanonicalPath (io/file (analyzed-source-path path)))]
    (boolean
     (some (fn [root]
             (or (= root candidate)
                 (str/starts-with? candidate (str root java.io.File/separator))))
           roots))))

(defn- discard-obsolete-cache-entries!
  "Drop cache entries the checkout's source no longer answers for.
  Still required with the ownership rule above: it repairs a cache an older
  build or another tool left behind — including a fixture stub written before
  that rule, whose recorded file is gone once the fixture root is swept."
  [canonical-sources]
  (let [root (kondo.core/resolve-cache-dir config-directory true cache-directory)]
    (when (.isDirectory root)
      (kondo.cache/with-thread-lock
        (kondo.cache/with-cache root 6
          (doall (for [directory (.listFiles root)
                  :when (and (.isDirectory directory)
                             (not (java.nio.file.Files/isSymbolicLink
                                   (.toPath directory))))
                  file (.listFiles directory)
                  :when (and (.isFile file)
                             (not (java.nio.file.Files/isSymbolicLink
                                   (.toPath file)))
                             (str/ends-with? (.getName file) ".transit.json"))
                  :let [entry (with-open [input (io/input-stream file)]
                                (transit/read (transit/reader input :json)))
                        filename (:filename entry)
                        source (when (string? filename)
                                 (analyzed-source-path filename))
                        namespace-name (symbol (subs (.getName file) 0
                                                     (- (count (.getName file))
                                                        (count ".transit.json"))))
                        canonical-source (get canonical-sources namespace-name)]
                  :when (and (string? filename)
                             (or (= "<stdin>" filename)
                                 (and canonical-source
                                      (not= (.getCanonicalPath (io/file source))
                                            (.getCanonicalPath (io/file canonical-source))))
                                 (and (not (str/includes? filename ".jar:"))
                                      (not (.exists (io/file source))))))]
            (java.nio.file.Files/deleteIfExists (.toPath file)))))))))

(defn- invoke-kondo
  [options]
  ;; Complete source is authoritative. Old synthesized entries and entries
  ;; whose source was removed cannot answer for an unanalysed language arm.
  (when-not (or (contains? options :cache-dir) (false? (:cache options)))
    (discard-obsolete-cache-entries! {}))
  (let [options (merge {:lang :clj
           :config-dir config-directory
           :cache-dir cache-directory
           :repro true
           :config analysis-config}
          options)
        result (clj-kondo/run! options)
        sources (into {}
                      (map (juxt :name #(let [filename (:filename %)]
                                          (cond-> filename
                                            (string? filename)
                                            analyzed-source-path))))
                      (get-in result [:analysis :namespace-definitions]))]
    ;; A current namespace declaration outranks a retained copy in any other
    ;; language's cache, even when that old build artifact still exists.
    (if (and (= cache-directory (:cache-dir options))
             (not (false? (:cache options)))
             (seq (discard-obsolete-cache-entries! sources)))
      (clj-kondo/run! options)
      result)))

(defn forget-namespaces!
  "Remove superseded declarations from one explicitly owned resolver cache."
  {:malli/schema [:=> [:cat :string [:set :symbol]] :nil]}
  [directory namespaces]
  (let [root (kondo.core/resolve-cache-dir config-directory true directory)]
    (kondo.cache/with-thread-lock
      (kondo.cache/with-cache root 6
        (doseq [language [:clj :cljc :cljs]
                namespace-name namespaces]
          (java.nio.file.Files/deleteIfExists
           (.toPath (kondo.cache/cache-file root language namespace-name)))))))
  nil)

(defn- delete-tree!
  "Delete one directory this namespace created, never following a symlink."
  [^java.io.File root]
  (let [path (.toPath root)]
    (when (java.nio.file.Files/exists
           path (into-array java.nio.file.LinkOption
                            [java.nio.file.LinkOption/NOFOLLOW_LINKS]))
      (java.nio.file.Files/walkFileTree
       path
       (proxy [java.nio.file.SimpleFileVisitor] []
         (visitFile [file _attributes]
           (java.nio.file.Files/deleteIfExists ^java.nio.file.Path file)
           java.nio.file.FileVisitResult/CONTINUE)
         (postVisitDirectory [directory _exception]
           (java.nio.file.Files/deleteIfExists ^java.nio.file.Path directory)
           java.nio.file.FileVisitResult/CONTINUE))))))

(defn- write-mirror!
  "Write each captured source text under one private analysis mirror.

  The mirror path keeps the source's own absolute path as its tail, so the
  namespace a file declares still matches the path clj-kondo sees and
  `analyzed-source-path` reads the source back out of it."
  [^java.io.File root sources]
  (mapv
   (fn [[^String path ^String text]]
     (let [relative (cond-> path
                      (str/starts-with? path (str java.io.File/separator))
                      (subs 1))
           mirror (io/file root relative)]
       (io/make-parents mirror)
       (java.nio.file.Files/write
        (.toPath mirror)
        (.getBytes text java.nio.charset.StandardCharsets/UTF_8)
        (into-array java.nio.file.OpenOption []))
       (.getCanonicalPath mirror)))
   (sort-by key sources)))

(defn- contains-position?
  [span entry]
  (let [start ((juxt :row :col) span)
        end ((juxt :end-row :end-col) span)
        position ((juxt :row :col) entry)]
    (and (= (:filename span) (:filename entry))
         (every? integer? (concat start end position))
         (not (pos? (compare start position)))
         (neg? (compare position end)))))

(defn- innermost-span
  [spans entry]
  (last (sort-by (juxt :row :col)
                (filter #(contains-position? % entry) spans))))

(defn- attributed-usages
  "Join implementation bodies to their dispatch identities using kondo spans.
  A defmethod target usage spans only its name; its containing macro call
  supplies the body span. Protocol implementations already carry that span."
  [analysis]
  (let [quoted (keep (fn [entry]
                       (let [target (:symbol entry)]
                         (when (qualified-symbol? target)
                           (assoc entry :to (or (:to entry) (symbol (namespace target)))
                                        :name (or (:name entry) (symbol (name target)))
                                        :reference true))))
                     (:symbols analysis))
        usages (into (vec (:var-usages analysis)) quoted)
        definitions (group-by :filename
                              (remove #(= 'clojure.core/declare (:defined-by->lint-as %))
                                      (:var-definitions analysis)))
        calls (group-by :filename (filter :arity usages))
        methods (keep (fn [usage]
                        (when (:defmethod usage)
                          (when-let [span (innermost-span (get calls (:filename usage)) usage)]
                            (assoc span :from (:to usage) :from-var (:name usage)
                                   :dispatch-val-str (:dispatch-val-str usage)))))
                      usages)
        implementations (map #(assoc % :from (:protocol-ns %)
                                      :from-var (:method-name %))
                             (:protocol-impls analysis))
        spans (group-by :filename (concat methods implementations))
        var-quotes (group-by :filename (:var-quotes analysis))]
    (mapv (fn [usage]
            (if-let [span (innermost-span (get spans (:filename usage)) usage)]
              (merge usage (select-keys span [:from :from-var]))
              (let [definition (when-not (:from-var usage)
                                 (innermost-span (get definitions (:filename usage)) usage))]
                (cond-> usage
                  definition (assoc :from (:ns definition) :from-var (:name definition))
                  (some #(contains-position? % usage)
                        (get var-quotes (:filename usage)))
                  (assoc :var-quote true)))))
          usages)))

(defn- var-quote-spans
  [filename source]
  (into []
        (comp (filter #(= :var (:tag %)))
              (map #(assoc (meta %) :filename filename)))
        (tree-seq (comp seq :children) :children
                  (binding [kondo.reader/*reader-exceptions* (atom [])]
                    (kondo.utils/parse-string-all source)))))

(defn analyze
  "Analyze captured source text, complete source roots, or individual files.

  `::sources` is the one race-free form: a map of canonical source path to the
  exact text the caller captured. clj-kondo reads that text from a private
  mirror instead of re-reading the file, so the rows and columns it reports
  and the text a caller slices with them are the same bytes even while the
  file is being edited. `::paths` remains for whole directories, the
  synthesized stdin buffer, and callers that own no capture."
  {:malli/schema
   [:=>
    [:cat
     [:map
      [::paths {:optional true} [:vector {:min 1} [:string {:min 1}]]]
      [::cache-root {:optional true} :string]
      [::config-root {:optional true} :string]
      [::sources {:optional true}
       [:map-of {:min 1} [:string {:min 1}] :string]]]]
    [:map
     [::namespace-definitions [:vector :map]]
     [::namespace-usages [:vector :map]]
     [::var-definitions [:vector :map]]
     [::var-usages [:vector :map]]
     [::protocol-impls [:vector :map]]
     [::keywords [:vector :map]]
     [::findings [:vector :map]]]]}
  [{::keys [paths sources cache-root config-root]}]
  (when-not (or (seq paths) (seq sources))
    (throw (ex-info "Analysis requires either captured sources or paths."
                    {:seon.error/kind ::analysis-refused})))
  ;; A trusted diagnostic must preserve each call as one coherent record.
  ;; clj-kondo's parallel analysis has combined an outer call's location and
  ;; arity with an inner call's resolved var, then emitted the corruption
  ;; twice. Its sequential path retains the same linters and dependency cache.
  (let [mirror-root (when (seq sources)
                      (let [parent (io/file "." mirror-directory)]
                        (.mkdirs parent)
                        (.toFile
                         (java.nio.file.Files/createTempDirectory
                          (.toPath parent) "analysis"
                          (into-array java.nio.file.attribute.FileAttribute [])))))
        lint-paths (if mirror-root (write-mirror! mirror-root sources) paths)
        stdin-source (when (some #{"-"} paths) (slurp *in*))
        result (try
                 (binding [*in* (if stdin-source
                                 (java.io.BufferedReader. (java.io.StringReader. stdin-source))
                                 *in*)]
                 (invoke-kondo
                  ;; ONE cache rule, decided here: only the checkout's own
                  ;; declared source may read or write the shared dependency
                  ;; cache. A synthesized stdin buffer (2026-08-29) and a
                  ;; fixture root under `tmp/` (2026-09-16) are the same
                  ;; defect — kondo keys the cache by namespace name, so a
                  ;; decoy `seon.error` would answer for the real one in every
                  ;; later analysis in this JVM. A mirror is not a decoy: it
                  ;; carries the checkout file's own captured bytes, and
                  ;; `checkout-source?` reads the source path back out of it.
                  (cond-> {:lint lint-paths :config publication-config}
                    cache-root (assoc :cache-dir cache-root)
                    config-root (assoc :config-dir config-root)
                    (and (nil? cache-root) (not (every? checkout-source? lint-paths)))
                    (assoc :cache false))))
                 (finally
                   (when mirror-root (delete-tree! mirror-root))))
        raw-analysis (:analysis result)
        quotes (mapcat (fn [filename]
                         (var-quote-spans
                          filename
                          (or (get sources (analyzed-source-path filename))
                              stdin-source
                              (slurp filename))))
                       (distinct (map :filename (:var-usages raw-analysis))))
        analysis (assoc raw-analysis :var-usages
                        (attributed-usages (assoc raw-analysis :var-quotes quotes)))]
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
     ::protocol-impls
     (normalized-entries analysis :protocol-impls
                         #(merge (location %)
                                 (present-values % [:protocol-ns :protocol-name
                                                    :method-name :impl-ns
                                                    :defined-by :defined-by->lint-as])))
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

(defn program-prelude
  "Render the supplied program declarations as kondo namespace context."
  {:malli/schema
   [:=> [:cat [:vector [:map
                       [:seon.fn/sym :seon.fn/sym]
                       [:seon.fn/arglists {:optional true} :seon.fn/arglists]
                       [:seon.fn/private? {:optional true} :seon.fn/private?]]]]
    :string]}
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

(defn referenced-program-namespaces
  "Namespaces named by qualified symbols in ordered source forms."
  {:malli/schema [:=> [:cat :symbol [:vector :string]] [:set :symbol]]}
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
    [:cat [:map
           [::namespace-name :symbol]
           [::namespace-row {:optional true} :map]
           [::available-functions {:optional true} [:vector :map]]
           [::sources [:vector {:min 1} :string]]]]
    [:vector
     [:map
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

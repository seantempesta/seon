(ns seon.fn.analyzer
  "Static Clojure source analysis for program-graph indexing."
  (:require [clj-kondo.core :as clj-kondo]
            [clj-kondo.impl.cache :as kondo.cache]
            [clj-kondo.impl.core :as kondo.core]
            [clj-kondo.impl.utils :as kondo.utils]
            [clj-kondo.impl.rewrite-clj.reader :as kondo.reader]
            [clojure.edn :as edn]
            [edamame.core :as edamame]
            [clojure.walk :as walk]
            [seon.schema.internal :as internal]
            [clj-kondo.impl.version :as kondo.version]
            [babashka.process :as process]
            [seon.cache :as cache]
            [seon.id :as id]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def config-directory
  "The analyzer configuration input root, shared with publication identity."
  ".clj-kondo")
(declare cache-directory)

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
   ;; The checkout's `:output :include-files` filters the CLI's printed
   ;; findings by filename; a submitted form is analyzed as stdin, whose
   ;; `<stdin>` matches none, so it would drop every finding of the REPL's
   ;; analysis. Analysis keeps every finding; the caller judges them.
   :output {:include-files ^:replace []}
   :analysis
   {:arglists true
    :var-usages true
    :java-class-usages true
    :protocol-impls true
    :symbols true
    :keywords true
    :var-definitions {:shallow false
                      :meta true}
    :namespace-definitions {:shallow false
                            :meta true}}})

(def ^:private finding-keys
  ;; `:ns`: the namespace an `:unresolved-namespace` finding names
  [:filename :row :col :end-row :end-col :level :message :type :ns
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
  "One require. clj-kondo marks an `:as-alias` require only on its alias
  symbol's metadata (`analyze-libspec`, clj_kondo/impl/analyzer/namespace.clj:243-262);
  `::as-alias` keeps that fact as data."
  [entry]
  (cond-> (merge
           (location entry)
           (present-values entry [:from :to :alias :lang]))
    (:as-alias (meta (:alias entry))) (assoc ::as-alias true)))

(defn- var-definition
  [entry]
  (deterministic-arities
   (merge
    (location entry)
    (present-values
     entry
     [:ns :name :defined-by :defined-by->lint-as :arglist-strs :doc :meta
      :private :macro :test :fixed-arities :varargs-min-arity :lang :literal-def?
      :task-map-after-name?]))))

(defn- var-usage
  [entry]
  (deterministic-arities
   (merge
    (location entry)
    (present-values
     entry
     [:from :from-var :to :name :alias :refer :arity :macro :private
      :fixed-arities :varargs-min-arity :defmethod :dispatch-val-str :var-quote :reference :lang]))))

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
  "Every `analyze` vector's sort key, leading with filename, row and column."
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

(defn- manifest-roots
  "The canonical directories this checkout's own manifest declares.

  `::source-roots`: clj-kondo keys its dependency cache by NAMESPACE NAME, so
  only source the checkout owns may write the checkout's cache. `deps.edn` is
  the ecosystem's own declaration of that ownership — `:paths` plus every
  alias's `:extra-paths` — never a list maintained here. The `.` entry of the
  test alias is discarded with every other candidate that is not strictly
  inside the checkout: a root containing the cache itself would admit every
  scratch file under `tmp/` (`deps.edn:137`).

  `::dependency-roots`: every `:local/root` the manifest names. A cache entry
  whose source lies inside the checkout answers only when it is under one of
  these two sets; a snapshot, fixture or scratch copy inside the checkout
  never does."
  {:malli/schema [:=> [:cat :string]
                  [:map [::source-roots [:set :string]] [::dependency-roots [:set :string]]]]}
  [checkout-path]
  (let [checkout (.getCanonicalFile (io/file checkout-path))
        manifest (io/file checkout "deps.edn")
        ;; The JVM was launched from this manifest; an unreadable one throws.
        declarations (if (.isFile manifest) (edn/read-string (slurp manifest)) {})
        prefix (str (.getPath checkout) java.io.File/separator)
        canonical (fn [paths]
                    (into #{}
                          (comp (filter string?)
                                (map #(.getCanonicalFile (io/file checkout ^String %)))
                                (filter #(.isDirectory ^java.io.File %))
                                (map #(.getPath ^java.io.File %)))
                          paths))
        local-roots (fn [dependencies]
                      (keep (fn [[_ coordinate]] (:local/root coordinate)) dependencies))
        aliases (vals (:aliases declarations))]
    {::source-roots
     (into #{} (filter #(str/starts-with? % prefix))
           (canonical (concat (:paths declarations) (mapcat :extra-paths aliases))))
     ::dependency-roots
     (canonical (concat (local-roots (:deps declarations))
                        (mapcat #(local-roots (:extra-deps %)) aliases)))}))

(def ^:private roots-of
  ;; A manifest change needs a replacement JVM (`seon.cluster.source/classify-paths`),
  ;; so one reading per checkout path serves the process.
  (memoize manifest-roots))

(defn- under?
  {:malli/schema [:=> [:cat [:set :string] :string] :boolean]}
  [roots ^String path]
  (boolean
   (some (fn [^String root]
           (or (= root path)
               (str/starts-with? path (str root java.io.File/separator))))
         roots)))

(defn- checkout-source?
  "True when this analyzed path is the checkout's own declared source.
  A fixture root, a scratch file, or the synthesized `-` stdin buffer is not:
  its analysis is isolated by construction, so it can never leave a stub
  under a first-party namespace name in the shared dependency cache. A mirror
  answers for the checkout file whose captured bytes it carries, so mirroring
  a file does not change whether its analysis owns the cache."
  [path]
  (let [checkout (.getCanonicalFile (io/file "."))]
    (under? (::source-roots (roots-of (.getPath checkout)))
            (.getCanonicalPath (io/file (analyzed-source-path path))))))

(defn analysis-cache-root
  "Select the resolver cache once from source ownership. Scratch inputs may
  use an explicitly private cache, never the shared cache or its descendants;
  canonical paths include aliases through symlinks. Nil disables caching."
  {:malli/schema [:=> [:cat [:sequential :string] [:maybe :string]] [:maybe :string]]}
  [paths requested]
  (let [directory (or requested cache-directory)]
    (when (or (every? checkout-source? paths)
              (not (under? #{(.getCanonicalPath (io/file config-directory ".cache"))}
                           (.getCanonicalPath (io/file directory)))))
      directory)))

(defn- invoke-kondo
  "Lint the supplied files once using the dependency-set cache."
  {:malli/schema [:=> [:cat [:map [:lint [:vector :string]]]] :map]}
  [options]
  (let [options (merge {:lang :clj :config-dir config-directory
                       :cache-dir cache-directory :cache true :repro true
                       :config analysis-config} options)]
    (cond-> (clj-kondo/run! options)
      (not (false? (:cache options)))
      (assoc ::cache-root (.getCanonicalPath ^java.io.File
                           (kondo.core/resolve-cache-dir (:config-dir options) true (:cache-dir options)))))))

(defn- unanalyzed-namespaces
  "Namespaces a var usage targets of which clj-kondo held no analysis: not
  defined by this run and absent from its cache and built-in resources, looked
  up in kondo's own order (clj_kondo/impl/cache.clj:23-31; :clj then :cljc,
  :185-187). Their vars' facts, `:macro` included, are unknown, never false.
  A nil root is an analysis run without a cache."
  {:malli/schema [:=> [:cat [:maybe :string] :map] [:set :symbol]]}
  [root analysis]
  (let [defined (into #{} (map :name) (:namespace-definitions analysis))
        analyzed? (fn [namespace-name]
                    (some #(or (and root (.isFile (kondo.cache/cache-file root % namespace-name)))
                               (kondo.cache/built-in-cache-resource % namespace-name))
                          [:clj :cljc]))]
    (into (sorted-set)
          (comp (keep :to) (filter symbol?) (distinct) (remove defined) (remove analyzed?))
          (:var-usages analysis))))

(defn- dependency-entries
  "The canonical classpath entries this JVM runs with (its alias's extra deps
  included) outside the checkout or under a `:local/root`: never a project root,
  whose namespaces are re-linted by boot or publication."
  {:malli/schema [:=> [:cat] [:vector :string]]}
  []
  (let [checkout (.getCanonicalPath (io/file "."))
        {::keys [dependency-roots]} (roots-of checkout)
        generated (.getCanonicalPath (io/file "target"))
        first-party? #(or (under? #{generated} %)
                          (and (or (= % checkout) (str/starts-with? % (str checkout java.io.File/separator)))
                               (not (under? dependency-roots %))))]
    (into [] (comp (map #(.getCanonicalPath (io/file ^String %))) (remove first-party?))
          (enumeration-seq (java.util.StringTokenizer. (System/getProperty "java.class.path")
                                                       java.io.File/pathSeparator)))))

(defn- dependency-cache-key
  "The dependency entries, kondo version and pinned declaration digest own one cache."
  {:malli/schema [:=> [:cat [:vector :string] :string :seon.schema/value] :string]}
  [entries version pins]
  (id/id [(sort entries) version pins]))

(def cache-directory
  "One cache per loaded dependency set. Pins travel with committed archives;
  classpath entries identify jars and git dependencies, and recorded gitlinks
  identify the local forks. Ordinary source edits do not change this identity."
  (str (io/file config-directory ".cache" "dependencies"
                (dependency-cache-key (dependency-entries) kondo.version/version
                                      [(if (.isFile (io/file "dependency-pins.txt"))
                                         (slurp "dependency-pins.txt")
                                         (:out (process/shell {:out :string :err :string}
                                                              "git" "ls-tree" "-r" "HEAD" "reference-code")))
                                       (into (sorted-map)
                                             (keep (fn [root]
                                                     (when (.exists (io/file root ".git"))
                                                       [root (:out (process/shell {:out :string :err :string}
                                                                                 "git" "-C" root "rev-parse" "HEAD"))])))
                                             (::dependency-roots (roots-of ".")))]))))


(defn analyze-dependencies!
  "Fill the current dependency-set directory. The completion file is written
  only after kondo returns; no namespace entry is inspected or repaired."
  {:malli/schema [:=> [:cat] :map]}
  []
  (let [result (clj-kondo/run! {:lint (dependency-entries) :dependencies true
                               :parallel true :copy-configs true
                               :config-dir config-directory :cache-dir cache-directory
                               :config {:output {:canonical-paths true}}})]
    (spit (io/file cache-directory "complete") "complete")
    (select-keys result [:summary])))

(declare delete-tree!)

(defn- roots-stamp
  "An id of the Clojure files under `roots` as their paths, sizes and
  modification times: it changes when a file there does."
  {:malli/schema [:=> [:cat [:vector :string]] :string]}
  [roots]
  (id/digest 64 (sort (for [root roots
                            ^java.io.File file (file-seq (io/file root))
                            :let [path (.getPath file)]
                            :when (and (.isFile file)
                                       (or (str/ends-with? path ".clj") (str/ends-with? path ".cljc")))]
                        [path (.length file) (.lastModified file)]))))

(defn ensure-dependency-analysis!
  "At boot, fill the current dependency-set directory if incomplete and mark it
  used. Another dependency set's directory is deleted only once unused for a
  day: JVMs on different classpaths (a replacement, a test or disposable root)
  share the parent, and deleting a live sibling cost its owner a ~6 s refill on
  every boot (waste-profile 2026-09-28). Lint non-program checkout roots every
  boot so script dependencies are current even when no program file changed."
  {:seon/tasks #{'seon.fn.analyzer/local-root-dependency-analysis-goes-stale}
   :malli/schema [:=> [:cat [:vector :string]] :map]}
  [program-roots]
  (let [current (io/file cache-directory)
        stale-before (- (System/currentTimeMillis) (* 24 60 60 1000))
        _ (doseq [directory (.listFiles (.getParentFile current))
                  :when (and (not= (.getName directory) (.getName current))
                             (< (.lastModified directory) stale-before))]
            (delete-tree! directory))
        filled (if (.isFile (io/file current "complete")) {} (analyze-dependencies!))
        _ (.setLastModified current (System/currentTimeMillis))
        program (set (map #(.getCanonicalPath (io/file %)) program-roots))
        other-roots (into (into [] (remove program) (::source-roots (roots-of ".")))
                          (comp (filter #(.isFile ^java.io.File %))
                                (map #(.getPath ^java.io.File %))
                                (filter #(or (str/ends-with? % ".clj") (str/ends-with? % ".cljc"))))
                          (.listFiles (io/file ".")))]
    ;; Linted once per state of those files: a restart or reset over unchanged
    ;; scripts finds its marker.
    (when (seq other-roots)
      (let [marker (io/file current (str "roots-" (roots-stamp other-roots)))]
        (when-not (.isFile marker)
          (clj-kondo/run! {:lint other-roots :cache-dir cache-directory
                           :config-dir config-directory :config analysis-config})
          (spit marker ""))))
    filled))

(defn cached-namespace
  "kondo's cached analysis of the dependency namespace `namespace-name`, read and never written; nil when unanalyzed.

  Its `:filename` is the file in the pinned extract; a Clojure namespace holds its vars beside it, a
  ClojureScript-capable one under `:clj`."
  {:malli/schema [:=> [:cat :symbol] [:maybe :map]]}
  [namespace-name]
  (let [root (kondo.core/resolve-cache-dir config-directory true cache-directory)]
    (some #(kondo.cache/from-cache-1 root % namespace-name) [:clj :cljc])))

(defn- defined-arglists
  "The parameter vectors of a `defn`, `defn-` or `defmacro` form, single or multi-arity; nil for any other form."
  {:malli/schema [:=> [:cat :seon.schema/value] [:maybe [:vector [:vector :seon.schema/value]]]]}
  [form]
  (when (and (seq? form) (#{'defn 'defn- 'defmacro} (first form)))
    (let [more (drop-while (complement #(or (vector? %) (seq? %))) (drop 2 form))]
      (if (vector? (first more))
        [(first more)]
        (not-empty (vec (keep #(when (and (seq? %) (vector? (first %))) (first %)) more)))))))

(defn- defined-doc
  "The docstring of a `def`-family form, or nil."
  {:malli/schema [:=> [:cat :seon.schema/value] [:maybe :string]]}
  [form]
  (when (seq? form)
    (let [doc (nth form 2 nil)]
      (when (string? doc) doc))))

(defn unloaded-definition
  "The definition of `qualified`, a var of a dependency namespace that is not loaded, from kondo's dependency
  cache and the one form it names, or nil.

  Gives the pinned file's URL, the definition's row, its source text, and the docstring and parameter vectors
  the form declares. Never loads the namespace."
  {:malli/schema [:=> [:cat :qualified-symbol]
                  [:maybe [:map [::url :string] [::row :int] [::source :string] [::arglists {:optional true} [:vector [:vector :seon.schema/value]]]
                           [::doc {:optional true} :string]]]]}
  [qualified]
  (let [namespace-name (symbol (namespace qualified))
        entry (cached-namespace namespace-name)
        {:keys [row col]} (or (get-in entry [:clj (symbol (name qualified))]) (get entry (symbol (name qualified))))
        base (-> (str namespace-name) (str/replace "-" "_") (str/replace "." "/"))
        url (or (some #(io/resource (str base %)) [".clj" ".cljc"])
                (let [file (io/file (str (:filename entry)))] (when (.isFile file) (.toURL file))))]
    (when (and row url)
      (let [lines (subvec (vec (str/split-lines (slurp url))) (dec row))
            text (str/join "\n" (cond-> lines (> col 1) (update 0 #(subs % (dec col)))))
            form (edamame/parse-string text {:all true :read-cond :allow :features #{:clj} :auto-resolve-ns true
                                             :readers (fn [_] identity)})
            source (str/join "\n" (take (inc (- (:end-row (meta form)) (:row (meta form)))) (str/split-lines text)))]
        (cond-> {::url (str url) ::row row ::source source}
          (defined-arglists form) (assoc ::arglists (defined-arglists form))
          (defined-doc form) (assoc ::doc (defined-doc form)))))))

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
  "Whether an entry starts inside the span, including its start and excluding its end."
  {:malli/schema [:=> [:cat :seon.schema/value :seon.schema/value] :boolean]}
  [span entry]
  (let [start-row (:row span) start-col (:col span)
        end-row (:end-row span) end-col (:end-col span)
        row (:row entry) col (:col entry)]
    (and (= (:filename span) (:filename entry))
         (integer? start-row) (integer? start-col) (integer? end-row) (integer? end-col)
         (integer? row) (integer? col)
         (or (< start-row row) (and (= start-row row) (<= start-col col)))
         (or (< row end-row) (and (= row end-row) (< col end-col))))))

(defn- span-index
  "`spans` in start order (stable) with, at each position, the latest end of
  the spans up to it, for [[innermost-span]]."
  {:malli/schema [:=> [:cat [:sequential :map]] :map]}
  [spans]
  (let [ordered (vec (sort-by (juxt :row :col) spans))]
    {:spans ordered
     :reach (vec (rest (reductions (fn [latest span]
                                     (let [end [(:end-row span) (:end-col span)]]
                                       (if (pos? (compare end latest)) end latest)))
                                   [nil nil] ordered)))}))

(defn- innermost-span
  "The containing span of `entry` that starts last in the start-ordered `index`
  (among equal starts, the last in the spans' order): a binary search for the
  spans starting at or before `entry`, then a walk back to the first that
  contains it, stopping once no earlier span reaches `entry`. Proportional to
  log n plus the spans passed over, not to every span of the file for every
  usage."
  {:malli/schema [:=> [:cat [:maybe :map] :map] [:maybe :map]]}
  [{:keys [spans reach]} entry]
  (let [position [(:row entry) (:col entry)]
        starting (loop [low 0 high (count spans)]
                   (if (< low high)
                     (let [middle (quot (+ low high) 2)
                           span (nth spans middle)]
                       (if (pos? (compare [(:row span) (:col span)] position))
                         (recur low middle)
                         (recur (inc middle) high)))
                     low))]
    (loop [i (dec starting)]
      (when (and (not (neg? i)) (pos? (compare (nth reach i) position)))
        (let [span (nth spans i)]
          (if (contains-position? span entry) span (recur (dec i))))))))

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
        usages (into (into (vec (:var-usages analysis)) quoted)
                     (:java-class-usages analysis))
        definitions (update-vals (group-by :filename
                                           (remove #(= 'clojure.core/declare (:defined-by->lint-as %))
                                                   (:var-definitions analysis)))
                                 span-index)
        calls (update-vals (group-by :filename (filter :arity usages)) span-index)
        methods (keep (fn [usage]
                        (when (:defmethod usage)
                          (when-let [span (innermost-span (get calls (:filename usage)) usage)]
                            (assoc span :from (:to usage) :from-var (:name usage)
                                   :dispatch-val-str (:dispatch-val-str usage)))))
                      usages)
        implementations (map #(assoc % :from (:protocol-ns %)
                                      :from-var (:method-name %))
                             (:protocol-impls analysis))
        spans (update-vals (group-by :filename (concat methods implementations)) span-index)
        var-quotes (update-vals (group-by :filename (:var-quotes analysis)) span-index)]
    (mapv (fn [usage]
            (if-let [span (innermost-span (get spans (:filename usage)) usage)]
              (merge usage (select-keys span [:from :from-var]))
              (let [definition (when-not (:from-var usage)
                                 (innermost-span (get definitions (:filename usage)) usage))]
                (cond-> usage
                  definition (assoc :from (:ns definition) :from-var (:name definition))
                  (innermost-span (get var-quotes (:filename usage)) usage)
                  (assoc :var-quote true)))))
          usages)))

(defn- literal-value?
  "Self-evaluating values and quoted data need no initializer execution."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (boolean
   (or (nil? value) (boolean? value) (number? value) (string? value)
       (char? value) (keyword? value)
       (and (seq? value) (= 'quote (first value)) (= 2 (count value)))
       (and (or (vector? value) (set? value)) (every? literal-value? value))
       (and (map? value) (every? literal-value? (mapcat identity value))))))

(defn- parse-facts
  "Use the existing kondo node parse for var quotes, `require` call spans and
  literal def initializers. The spans carry no filename: that is the caller's."
  {:malli/schema [:=> [:cat :string]
                  [:map [:seon.fn.analyzer/var-quotes [:vector :map]]
                   [:seon.fn.analyzer/require-forms [:vector :map]]
                   [:seon.fn.analyzer/literal-defs [:set [:tuple :int :int]]]
                   [:seon.fn.analyzer/task-maps-after-name [:set [:tuple :int :int]]]
                   [:seon.fn.analyzer/mentioned-symbols [:set :qualified-symbol]]
                   [:seon.fn.analyzer/namespaced-attribute-maps [:map-of [:tuple :int :int] :map]]]]}
  [source]
  (let [root (binding [kondo.reader/*reader-exceptions* (atom [])]
               (kondo.utils/parse-string-all source))
        nodes (tree-seq (comp seq :children) :children root)]
    {;; Every qualified symbol the file's top-level forms carry as data, from
     ;; this one parse (the program's `mentioned` symbols, seon.fn).
     ::mentioned-symbols
     (into #{} (comp (map kondo.utils/sexpr) (mapcat #(tree-seq coll? seq %)) (filter qualified-symbol?))
           (:children root))
     ::var-quotes
     (into [] (comp (filter #(= :var (:tag %)))
                    (map meta)) nodes)
     ::require-forms
     (into [] (comp (filter #(and (= :list (:tag %))
                                  (#{'require 'clojure.core/require}
                                   (:value (first (:children %))))))
                    (map meta)) nodes)
     ::literal-defs
     (into #{}
           (keep (fn [node]
                   ;; Only a def head is converted to data; other lists are skipped.
                   (when (and (= :list (:tag node))
                              (#{'def 'clojure.core/def} (:value (first (:children node)))))
                     (let [form (kondo.utils/sexpr node)]
                       (when (and (or (= 3 (count form))
                                      (and (= 4 (count form)) (string? (nth form 2))))
                                  (literal-value? (last form)))
                         [(:row (meta node)) (:col (meta node))])))))
           nodes)
     ;; A defining form whose attribute map is a namespaced map literal
     ;; (`#:malli{:schema ...}`, what `pr-str` prints): clj-kondo reports no
     ;; `:meta` for it, so its value is read from this parse, by position.
     ::namespaced-attribute-maps
     (into {}
           (keep (fn [node]
                   (let [[_ named first-form second-form] (:children node)
                         attributes (if (:lines first-form) second-form first-form)]
                     (when (and (= :list (:tag node)) (symbol? (:value named))
                                (= :namespaced-map (:tag attributes)))
                       [[(:row (meta node)) (:col (meta node))] (kondo.utils/sexpr attributes)]))))
           nodes)
     ;; A map naming :seon/tasks right after a defining form's name (and an
     ;; optional docstring): for a deftest it is a body value, not metadata.
     ::task-maps-after-name
     (into #{}
           (keep (fn [node]
                   (let [[_ named first-form second-form] (:children node)
                         attributes (if (:lines first-form) second-form first-form)]
                     (when (and (= :list (:tag node)) (symbol? (:value named)) (= :map (:tag attributes))
                                (some #(= :seon/tasks (:k %)) (:children attributes)))
                       [(:row (meta node)) (:col (meta node))]))))
           nodes)}))

(defn- parsed-facts
  "[[parse-facts]] of `source`, parsed once per content: the facts are a pure
  function of the text, so every analysis of it shares them (a mirror's path
  differs per analysis; only the spans' `filename` follows it)."
  {:malli/schema [:=> [:cat :string :string]
                  [:map [:seon.fn.analyzer/var-quotes [:vector :map]]
                   [:seon.fn.analyzer/require-forms [:vector :map]]
                   [:seon.fn.analyzer/literal-defs [:set [:tuple :int :int]]]
                   [:seon.fn.analyzer/task-maps-after-name [:set [:tuple :int :int]]]
                   [:seon.fn.analyzer/mentioned-symbols [:set :qualified-symbol]]
                   [:seon.fn.analyzer/namespaced-attribute-maps [:map-of [:tuple :int :int] :map]]]]}
  [filename source]
  (let [facts (cache/lookup ::parsed-facts source
                            ;; the key holds the source text; the facts are a few nodes per form
                            #(+ 512 (* 6 (count source)) (* 160 (count (::mentioned-symbols %))))
                            #(parse-facts source))
        stamp (fn [spans] (mapv #(assoc % :filename filename) spans))]
    (-> facts (update ::var-quotes stamp) (update ::require-forms stamp))))

(defn- with-namespace-clauses
  "Each `ns` definition's clause facts. clj-kondo attributes every require
  and `:refer` to the namespace that made it (`:from`), whether in the `ns`
  form or a top-level `require` (clj_kondo/impl/analyzer/namespace.clj:721,771;
  :refer usages :220-231). A class import and a bare `:rename` keyword inside
  the `ns` form are not attributed to the namespace it defines (:702), so
  they are taken by position within that form.
  A `:rename` inside a `require` call (`require-forms`, the parsed spans)
  carries the requiring namespace as `:from`, so it is that namespace's
  clause; one elsewhere in a body is data. clj-kondo records no renamed
  local. Each fact vector is indexed once."
  {:malli/schema [:=> [:cat [:vector :map] :map] :map]}
  [require-forms analysis]
  (let [unqualified #(update-keys % (comp keyword name))
        usages (group-by ::from (::namespace-usages analysis))
        refers (group-by ::from (filter ::refer (::var-usages analysis)))
        in-files (fn [entries] (group-by ::filename entries))
        imports (in-files (filter ::import (::java-class-usages analysis)))
        bare-renames (filter #(and (= "rename" (::name %)) (not (::ns %))) (::keywords analysis))
        renames (in-files bare-renames)
        within (fn [by-file definition]
                 (filterv #(contains-position? (unqualified definition) (unqualified %))
                          (get by-file (::filename definition))))
        requires (in-files require-forms)
        required-renames (group-by ::from
                                   (filter (fn [rename]
                                             (some #(contains-position? (unqualified %) (unqualified rename))
                                                   (get requires (::filename rename))))
                                           bare-renames))]
    (update analysis ::namespace-definitions
            (partial mapv
                     (fn [{namespace-name ::name :as definition}]
                       (assoc definition
                              ::clause-usages (get usages namespace-name [])
                              ::clause-refers (get refers namespace-name [])
                              ::clause-imports (within imports definition)
                              ::clause-renames
                              (into (within renames definition)
                                    (get required-renames namespace-name []))))))))

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
     [:and [:map
      [::paths {:optional true} [:vector {:min 1} [:string {:min 1}]]]
      [::cache-root {:optional true} :string]
      [::config-root {:optional true} :string]
      [::sources {:optional true}
       [:map-of {:min 1} [:string {:min 1}] :string]]]
      [:or [:map [::paths :some]] [:map [::sources :some]]]]]
    [:map
     [::namespace-definitions [:vector :map]]
     [::namespace-usages [:vector :map]]
     [::var-definitions [:vector :map]]
     [::var-usages [:vector :map]]
     [::java-class-usages [:vector :map]]
     [::protocol-impls [:vector :map]]
     [::keywords [:vector :map]]
     [::findings [:vector :map]]
     [::unanalyzed-namespaces [:set :symbol]]
     [::mentioned-symbols [:set :qualified-symbol]]]]}
  [{::keys [paths sources cache-root config-root]}]
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
        cache-root (analysis-cache-root lint-paths cache-root)
        stdin-source (when (some #{"-"} paths) (slurp *in*))
        result (try
                 (binding [*in* (if stdin-source
                                 (java.io.BufferedReader. (java.io.StringReader. stdin-source))
                                 *in*)]
                 (invoke-kondo
                  (cond-> {:lint lint-paths :config publication-config}
                    cache-root (assoc :cache-dir cache-root)
                    config-root (assoc :config-dir config-root)
                    (nil? cache-root) (assoc :cache false))))
                 (finally
                   (when mirror-root (delete-tree! mirror-root))))
        raw-analysis (:analysis result)
        spans (into {}
                    (map (fn [filename]
                           [filename
                            (parsed-facts filename
                                          (or (get sources (analyzed-source-path filename))
                                              stdin-source (slurp filename)))]))
                    ;; Every captured source is parsed here, once: its facts
                    ;; and its mentioned symbols (`::mentioned-symbols`).
                    (distinct (concat (when mirror-root lint-paths)
                                      (map :filename (concat (:var-usages raw-analysis)
                                                             (:var-definitions raw-analysis))))))
        raw-analysis (update raw-analysis :var-definitions
                             (fn [definitions]
                               (mapv (fn [entry]
                                       (cond-> entry
                                         (and (= 'clojure.core/def (:defined-by entry))
                                              (get-in spans [(:filename entry) ::literal-defs
                                                             [(:row entry) (:col entry)]]))
                                         (assoc :literal-def? true)
                                         (get-in spans [(:filename entry) ::task-maps-after-name
                                                        [(:row entry) (:col entry)]])
                                         (assoc :task-map-after-name? true)
                                         (nil? (:meta entry))
                                         (as-> entry
                                           (if-let [attributes (get-in spans [(:filename entry) ::namespaced-attribute-maps
                                                                              [(:row entry) (:col entry)]])]
                                             (assoc entry :meta attributes)
                                             entry)))) definitions)))
        quotes (mapcat ::var-quotes (vals spans))
        require-forms (into [] (comp (mapcat ::require-forms) (map location)) (vals spans))
        attributed (group-by #(if (:class %) :java-class-usages :var-usages)
                             (attributed-usages (assoc raw-analysis :var-quotes quotes)))
        analysis (merge raw-analysis attributed)]
    (with-namespace-clauses
    require-forms
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
     ::java-class-usages
     (filterv jvm-entry?
              (normalized-entries analysis :java-class-usages
                                  #(merge (location %)
                                          (present-values % [:class :method-name :call :lang
                                                             :from :from-var :import]))))
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
          vec)
     ::unanalyzed-namespaces (unanalyzed-namespaces (::cache-root result) raw-analysis)
     ::mentioned-symbols (into #{} (mapcat ::mentioned-symbols) (vals spans))})))

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
      ;; Historical entities can honestly lack analyzer arities. Keep those names
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
        (fn [[namespace-name entities]]
          (cons (list 'ns namespace-name)
                (map function-stub (sort-by :seon.fn/sym entities)))))
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

;; A simple symbol in schema position that Malli's default registry names
;; (`ifn?`, `string?`) is that Malli schema and stays itself; qualifying it to
;; `clojure.core/ifn?` names no schema and refuses the whole projection.
;; Inside `[:fn …]` a symbol is a predicate and resolves as source does.
(def ^:private malli-schema-symbols
  "The simple symbols Malli's default registry names, derived once."
  (into #{} (filter symbol?) (keys internal/default-schemas)))

(defn qualify-schema-symbols
  "The authored contract `form` with its symbols qualified through the namespace context, if any."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "An authored contract is arbitrary data until it compiles.", :gen/elements [nil 0 :k [] {}]}] [:maybe [:map [:aliases [:map-of :symbol :symbol]] [:refers [:map-of :symbol :qualified-symbol]]]]]
                  [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "An authored contract is arbitrary data until it compiles.", :gen/elements [nil 0 :k [] {}]}]]}
  [form {:keys [aliases refers]}]
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
                  value))))]
    ((fn walk [value]
       (cond
         (and (vector? value) (= :fn (first value))) (walk/postwalk qualify value)
         (and (simple-symbol? value) (contains? malli-schema-symbols value)) value
         (coll? value) (walk/walk walk identity value)
         :else (qualify value)))
     form)))

(defn namespace-context
  "The requires, aliases, refers and imports carried by a namespace analysis entry."
  {:malli/schema [:=> [:cat :map]
                  [:map [:requires [:set :symbol]]
                   [:aliases [:map-of :symbol :symbol]]
                   [:refers [:map-of :symbol :qualified-symbol]]
                   [:imports [:map-of :symbol :symbol]]]]}
  [{::keys [clause-usages clause-refers clause-imports clause-renames]}]
  (when-some [rename (first clause-renames)]
    (let [message "A require's :rename is not indexed: clj-kondo's analysis records no renamed local."]
      (throw (ex-info message {:seon.fn/index-refused true
                               :seon.cluster.source/phase :analysis
                               :seon.error/message message
                               :seon.fn/analysis-entry rename}))))
  {:requires (into #{} (comp (remove ::as-alias) (map ::to)) clause-usages)
   :aliases (into {} (comp (filter ::alias)
                           (map (juxt ::alias ::to))) clause-usages)
   :refers (into {} (map (fn [{::keys [to] target-name ::name}]
                           [target-name (symbol (str to) (str target-name))]))
                 clause-refers)
   :imports (into {} (map (fn [{class-name ::class}]
                            [(symbol (subs class-name (inc (or (str/last-index-of class-name ".") -1))))
                             (symbol class-name)]))
                  clause-imports)})

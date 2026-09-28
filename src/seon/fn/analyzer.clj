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
    :java-class-usages true
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

(defn expansion-hooks
  "clj-kondo `:analyze-call` hooks carrying the macro expansions an evaluation
  observed (the SCI fork's expansion observer): for each expanded macro, a
  hook answering the recorded expansion for the call node whose COMPLETE span
  is an observed occurrence's, and leaving every other call as written. Each
  occurrence's span is the form's own (its first line 1, first column 1);
  the form sits at `first-row` of the analyzed text. Nil when none was
  observed."
  {:malli/schema [:=> [:cat [:sequential [:map [:seon.batch/macro :qualified-symbol]
                                          [:seon.batch/call-span [:tuple pos-int? pos-int? pos-int? pos-int?]]
                                          [:seon.batch/expansion :seon.batch/form]]]
                       pos-int?]
                  [:maybe :map]]}
  [expansions first-row]
  (let [by-macro (group-by :seon.batch/macro expansions)
        row (fn [r] (+ r (dec first-row)))]
    (when (seq by-macro)
      {:__dangerously-allow-string-hooks__ true
       :analyze-call
       (into {} (for [[macro events] by-macro
                      :let [at (into {} (for [{[l c el ec] :seon.batch/call-span e :seon.batch/expansion} events]
                                          [[(row l) c (row el) ec] e]))]]
                  [macro (str "(require '[clj-kondo.hooks-api :as api])\n"
                              "(let [at '" (pr-str at) "]\n"
                              "  (fn [{:keys [node]}] (let [{:keys [row col end-row end-col]} (meta node)]"
                              " (when-some [e (get at [row col end-row end-col])] {:node (api/coerce e)}))))")]))})))

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

(defn- classpath-source
  "The directory file this JVM's classpath loads for `namespace-name`: a
  first-party file (the `:test` alias's `.` puts `dev_cache.clj` there) or a
  directory dependency's (`:local/root`, a git dep), never a list. A jar entry
  answers nil: kondo's skip mark owns jars (`ensure-dependency-analysis!`),
  while a directory carries no mark and is re-read from its current bytes
  (clj_kondo/impl/core.clj:489-506 marks only `.jar` paths). The system loader
  answers, not a thread's context loader (`seon.sci.eval`)."
  {:malli/schema [:=> [:cat :symbol] [:maybe :string]]}
  [namespace-name]
  (let [base (-> (str namespace-name) (str/replace "-" "_") (str/replace "." "/"))]
    (some (fn [extension]
            (when-let [url (.getResource (ClassLoader/getSystemClassLoader) (str base extension))]
              (when (= "file" (.getProtocol url))
                (.getCanonicalPath (io/file (.toURI url))))))
          [".clj" ".cljc"])))

(defn- stale-cache-entries
  "The cache entries this analysis could have read that do not answer for the
  current bytes of their source.

  clj-kondo loads a cached namespace by NAME with no content check
  (`reference-code/clj-kondo/src/clj_kondo/impl/cache.clj:127-144`,
  `load-when-missing`), and only for namespaces the linted files use, so
  only those entries are examined — the work is proportional to the analysis,
  not to the cache. An entry is stale when it was built from other bytes than
  its source now holds (the source was modified after the entry was written:
  the file's own modification time is the stat check), when its source is gone
  or is the stdin buffer, when a namespace this analysis defines now lives in
  another file, or when its source sits inside the checkout outside every
  declared source and dependency root and this analysis does not define it. Jar entries are immutable by
  coordinate; kondo names one `<jar>:<entry>` only under canonical paths
  (clj_kondo/impl/core.clj:289-291), so `seon.fn/dependency-analysis-command`
  writes them that way — a bare entry name reads as an absent source. Each stale entry names its checkout source when it has one, and a
  namespace this run does not define (first-party or a directory dependency's)
  names the file this classpath loads (`classpath-source`), so the caller can rebuild it.

  `shared?` is true for the checkout's own cache under `.clj-kondo/`. An
  explicitly supplied cache root belongs to its caller, whose every source
  may rebuild it and none of whose sources is foreign."
  {:malli/schema
   [:=> [:cat :string :boolean [:map [:analysis {:optional true} :map]]]
    [:map
     [::examined :int]
     [::stale [:vector [:map [::cache-file :string] [::namespace :symbol]
                        [::language [:enum :clj :cljc :cljs]]
                        [::reason [:enum ::stdin ::source-absent ::moved ::foreign ::modified]]
                        [::source {:optional true} :string]]]]]]}
  [root shared? result]
  (let [checkout (.getCanonicalFile (io/file "."))
        {::keys [source-roots dependency-roots]} (roots-of (.getPath checkout))
        prefix (str (.getPath checkout) java.io.File/separator)
        analysis (:analysis result)
        defined (into {}
                      (keep (fn [{namespace-name :name filename :filename}]
                              (when (string? filename)
                                [namespace-name
                                 (.getCanonicalPath (io/file (analyzed-source-path filename)))])))
                      (:namespace-definitions analysis))
        used (into (set (keep :to (:namespace-usages analysis)))
                   (keep :to (:var-usages analysis)))
        examined
        (for [namespace-name (sort-by str used)
              :when (symbol? namespace-name)
              language [:clj :cljc :cljs]
              :let [file (kondo.cache/cache-file root language namespace-name)]
              :when (and (.isFile file)
                         (not (java.nio.file.Files/isSymbolicLink (.toPath file))))]
          [namespace-name language file])]
    {::examined (count examined)
     ::stale
     (into []
        (for [[namespace-name language ^java.io.File file] examined
              :let [filename (:filename (with-open [input (io/input-stream file)]
                                          (transit/read (transit/reader input :json))))]
              :when (and (string? filename) (not (str/includes? filename ".jar:")))
              :let [source (io/file (analyzed-source-path filename))
                    path (.getCanonicalPath source)
                    current (get defined namespace-name)
                    reason (cond
                             (= "<stdin>" filename) ::stdin
                             (not (.isFile source)) ::source-absent
                             (and current (not= current path)) ::moved
                             ;; Foreign is a checkout file this run did not
                             ;; lint: the entry for a namespace this run defines
                             ;; at this path was just written from its bytes.
                             (and shared? (nil? current)
                                  (str/starts-with? path prefix)
                                  (not (under? source-roots path))
                                  (not (under? dependency-roots path))
                                  (not= path (classpath-source namespace-name))) ::foreign
                             (> (.lastModified source) (.lastModified file)) ::modified)
                    ;; A namespace this run does not define rebuilds from the
                    ;; file this classpath loads: the shared cache outlives the
                    ;; checkout, mirror and dependency revision that wrote it.
                    ;; A directory dependency's entry older than its source (a
                    ;; submodule bump) was deleted unrebuilt before, so boot
                    ;; refused every call into it (start --head 5bed888d0).
                    rebuilt (if (and (#{::modified ::moved} reason)
                                     (or (not shared?) (under? source-roots path)))
                              path
                              (when (nil? current)
                                (classpath-source namespace-name)))]
              :when reason]
          (cond-> {::cache-file (.getPath file)
                   ::namespace namespace-name
                   ::language language
                   ::reason reason}
            rebuilt (assoc ::source rebuilt))))}))

(declare unanalyzed-namespaces)

(defn- invoke-kondo
  "Run clj-kondo once, then rebuild any cache entry the run read that does not
  answer for current bytes and run once more.

  The rerun is the whole cost of a stale entry; with none, the check reads
  only the entries of the namespaces the analysis used. `::cache` reports
  what was examined, rebuilt and deleted. An entry still stale after the
  rebuild throws the declared analysis source-change refusal."
  {:malli/schema [:=> [:cat [:map [:lint [:vector :string]]]]
                  [:map [::cache [:map [::examined :int] [::stale [:vector :map]]
                                  [::rebuilt [:vector :string]]]]]]}
  [options]
  (let [options (merge {:lang :clj
                        :config-dir config-directory
                        :cache-dir cache-directory
                        :cache true
                        :repro true
                        :config analysis-config}
                       options)
        cached? (not (false? (:cache options)))
        root (when cached?
               (.getCanonicalPath ^java.io.File
                (kondo.core/resolve-cache-dir (:config-dir options) true (:cache-dir options))))
        ;; The checkout's own cache, however the caller spelled it: publication
        ;; hands `<root>/.clj-kondo/.cache` explicitly (`seon.fn/analyzed-files`).
        shared? (and root
                     (= root (.getCanonicalPath ^java.io.File
                              (kondo.core/resolve-cache-dir config-directory true cache-directory))))
        check (fn [result]
                (if root
                  (kondo.cache/with-thread-lock
                    (kondo.cache/with-cache root 6
                      (stale-cache-entries root shared? result)))
                  {::examined 0 ::stale []}))
        first-run (clj-kondo/run! options)
        ;; A namespace kondo holds no entry for (first-party outside the
        ;; publication roots, or a directory dependency's) is analyzed from
        ;; the file the classpath loads.
        classpath-files (when root
                      (into [] (keep classpath-source)
                            (unanalyzed-namespaces root (:analysis first-run))))
        result (if (seq classpath-files)
                 (do (clj-kondo/run! (assoc options :lint classpath-files))
                     (clj-kondo/run! options))
                 first-run)
        {::keys [examined stale]} (check result)]
    (if (empty? stale)
      (cond-> (assoc result ::cache {::examined examined ::stale [] ::rebuilt []})
        root (assoc ::cache-root root))
      (let [sources (into [] (comp (keep ::source) (distinct)) stale)]
        (kondo.cache/with-thread-lock
          (kondo.cache/with-cache root 6
            (doseq [{::keys [cache-file]} stale]
              (java.nio.file.Files/deleteIfExists (.toPath (io/file cache-file))))))
        ;; Rebuild each checkout-owned entry from its current bytes; this
        ;; run's own findings belong to its files' analyses, not to this one.
        (when (seq sources)
          (clj-kondo/run! (assoc options :lint sources)))
        (let [rerun (clj-kondo/run! options)
              remaining (::stale (check rerun))]
          ;; An entry still stale after its rebuild was changed by a concurrent
          ;; source edit: the declared analysis source-change refusal
          ;; (`:seon.fn/index-refused-error`), naming the changed paths. The
          ;; operator request returns it as refused (`seon.cluster.boot/request!`).
          (when (seq remaining)
            (throw (ex-info "Source changed during analysis; clj-kondo cache entries changed again."
                            {:seon.fn/index-refused true
                             :seon.cluster.boot/disposition :refused
                             :seon.cluster.source/phase :analysis
                             :seon.error/message "Source changed during analysis; clj-kondo cache entries changed again."
                             :seon.source/changed-paths
                             (into [] (comp (map #(or (::source %) (::cache-file %))) (distinct))
                                   remaining)
                             ::stale remaining
                             ::lint (:lint options)})))
          (assoc rerun ::cache {::examined examined ::stale stale ::rebuilt sources}
                 ::cache-root root))))))

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
  whose namespaces are first-party (`classpath-source`)."
  {:malli/schema [:=> [:cat] [:vector :string]]}
  []
  (let [checkout (.getCanonicalPath (io/file "."))
        {::keys [dependency-roots]} (roots-of checkout)
        first-party? #(and (or (= % checkout) (str/starts-with? % (str checkout java.io.File/separator)))
                           (not (under? dependency-roots %)))]
    (into [] (comp (map #(.getCanonicalPath (io/file ^String %))) (remove first-party?))
          (enumeration-seq (java.util.StringTokenizer. (System/getProperty "java.class.path")
                                                       java.io.File/pathSeparator)))))

(defn analyze-dependencies!
  "Fill the checkout's clj-kondo cache from `dependency-entries`. Kondo
  skip-marks each jar (clj_kondo/impl/core.clj:489-506). `--skip-lint` would
  only copy configs (clj_kondo/core.clj:126-129); canonical paths name a jar
  entry `<jar>:<entry>` (impl/core.clj:289-291), which `stale-cache-entries` keeps."
  {:malli/schema [:=> [:cat] :map]}
  []
  (select-keys (clj-kondo/run! {:lint (dependency-entries) :dependencies true :parallel true :copy-configs true
                                :config-dir config-directory :cache-dir cache-directory
                                :config {:output {:canonical-paths true}}})
               [:summary]))

(defn- unanalyzed-jars
  "Classpath jars kondo holds no skip mark for. A mark is named
  `<jar-name>.<config-hash>` and holds the last path linted under that name
  (clj_kondo/impl/core.clj:490-498, :603-607), so two coordinates sharing a jar
  name (com/lambdaisland/glogi and lambdaisland/glogi) share one mark: a jar
  is analyzed when its name is marked, whatever the path or config hash."
  {:malli/schema [:=> [:cat :string [:vector :string]] [:vector :string]]}
  [cache-dir entries]
  (let [skip (io/file (kondo.core/resolve-cache-dir config-directory true cache-dir) "skip")
        marked (into #{} (map #(let [mark (.getName ^java.io.File %)]
                                 (subs mark 0 (str/last-index-of mark "."))))
                     (.listFiles skip))]
    (into [] (comp (filter #(str/ends-with? % ".jar"))
                   (remove #(marked (.getName (io/file ^String %)))))
          entries)))

(defn ensure-dependency-analysis!
  "Boot's fill of the dependency analysis program analysis reads: run
  `analyze-dependencies!` in this JVM when any classpath jar is unmarked (an
  empty cache after a nuke, a fresh root, a new coordinate); nil when every jar
  is marked. A directory entry carries no mark: an analysis reaching one of
  its namespaces with no current entry rebuilds it from the file the classpath
  loads (`classpath-source`)."
  {:seon/tasks #{"547fc42c47a5"}
   :malli/schema [:=> [:cat] [:maybe [:map [::unanalyzed-jars [:vector :string]] [:summary :map]]]]}
  []
  (let [missing (unanalyzed-jars cache-directory (dependency-entries))]
    (when (seq missing)
      (assoc (analyze-dependencies!) ::unanalyzed-jars missing))))

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

(defn- span-index
  "`spans` in start order (stable), for [[innermost-span]]."
  [spans]
  (vec (sort-by (juxt :row :col) spans)))

(defn- innermost-span
  "The containing span of `entry` that starts last in the start-ordered `index`
  (among equal starts, the last in the spans' order): a binary search for the
  spans starting at or before `entry`, then a walk back to the first that
  contains it. Proportional to log n plus the spans passed over, not to every
  span of the file for every usage."
  [index entry]
  (let [position [(:row entry) (:col entry)]
        starting (loop [low 0 high (count index)]
                   (if (< low high)
                     (let [middle (quot (+ low high) 2)
                           span (nth index middle)]
                       (if (pos? (compare [(:row span) (:col span)] position))
                         (recur low middle)
                         (recur (inc middle) high)))
                     low))]
    (loop [i (dec starting)]
      (when-not (neg? i)
        (let [span (nth index i)]
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

(defn- parsed-facts
  "Use the existing kondo node parse for var quotes, `require` call spans and
  literal def initializers."
  {:malli/schema [:=> [:cat :string :string]
                  [:map [:seon.fn.analyzer/var-quotes [:vector :map]]
                   [:seon.fn.analyzer/require-forms [:vector :map]]
                   [:seon.fn.analyzer/literal-defs [:set [:tuple :int :int]]]
                   [:seon.fn.analyzer/task-maps-after-name [:set [:tuple :int :int]]]
                   [:seon.fn.analyzer/mentioned-symbols [:set :qualified-symbol]]
                   [:seon.fn.analyzer/namespaced-attribute-maps [:map-of [:tuple :int :int] :map]]]]}
  [filename source]
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
                    (map #(assoc (meta %) :filename filename))) nodes)
     ::require-forms
     (into [] (comp (filter #(and (= :list (:tag %))
                                  (#{'require 'clojure.core/require}
                                   (:value (first (:children %))))))
                    (map #(assoc (meta %) :filename filename))) nodes)
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
     [:map
      [::paths {:optional true} [:vector {:min 1} [:string {:min 1}]]]
      [::cache-root {:optional true} :string]
      [::config-root {:optional true} :string]
      [::hooks {:optional true :description "clj-kondo :hooks merged into the analysis config (expansion-hooks)."} :map]
      [::sources {:optional true}
       [:map-of {:min 1} [:string {:min 1}] :string]]]]
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
     [::mentioned-symbols [:set :qualified-symbol]]
     [::cache [:map [::examined :int] [::stale [:vector :map]]
               [::rebuilt [:vector :string]]]]]]}
  [{::keys [paths sources cache-root config-root hooks]}]
  (when-not (or (seq paths) (seq sources))
    (throw (ex-info "Analysis requires either captured sources or paths."
                    {})))
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
                  (cond-> {:lint lint-paths :config (cond-> publication-config hooks (assoc :hooks hooks))}
                    cache-root (assoc :cache-dir cache-root)
                    config-root (assoc :config-dir config-root)
                    (and (nil? cache-root) (not (every? checkout-source? lint-paths)))
                    (assoc :cache false))))
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
     ::mentioned-symbols (into #{} (mapcat ::mentioned-symbols) (vals spans))
     ;; Which cache entries the analysis found stale and rebuilt: the
     ;; publication's cache-miss count.
     ::cache (::cache result)})))

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

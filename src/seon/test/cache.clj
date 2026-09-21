(ns seon.test.cache
  "Prepare immutable test inputs under the development cache's existing lock."
  (:require [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [seon.test.bounds :as bounds])
  (:import [java.io File RandomAccessFile]
           [java.security MessageDigest]
           [java.lang Process ProcessHandle]
           [java.nio.file Files LinkOption]
           [java.time Instant]
           [java.util.concurrent TimeUnit]
           [java.util.concurrent.locks ReentrantLock]))

(def graph-roots
  "Roots whose files are represented in the program-graph manifest."
  ["src" "test"])

(defn worker-count
  "Size the existing isolated pool once, with the launcher's explicit override."
  {:malli/schema [:=> [:cat :int [:or :nil :string] [:int {:min 0}]]
                  [:int {:min 1}]]}
  [processors override namespace-count]
  (if (seq override)
    (let [n (parse-long override)]
      (when-not (and n (pos? n))
        (throw (ex-info "Prepared worker count must be positive."
                        {:seon.test.runner/worker-count-input override})))
      n)
    (cond-> (max 1 (min 3 (quot processors 2)))
      (pos? namespace-count) (min namespace-count))))

(def ^:private inventory-bound-ms
  "Bound for Git's local file inventory, matching the existing issue-history
  Git read allowance. Expiry refuses selection rather than omitting inputs."
  30000)

(defn- sha-256
  {:malli/schema [:=> [:cat :seon.blob/octet-array] :string]}
  [^bytes source-bytes]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") source-bytes)]
    (apply str (map #(format "%02x" (bit-and 0xff %)) digest))))

(defn input-paths
  "Git's input paths, or the exact inventory carried by an exported snapshot."
  {:malli/schema [:=> [:cat :string] [:vector :string]]}
  [directory]
  (let [root (io/file directory)
        recorded (io/file root "test-input-paths.txt")]
    (if (.isFile recorded)
      (vec (enumeration-seq (java.util.StringTokenizer. (slurp recorded) (str (char 0)))))
      (let [child (process/process
               ["git" "--work-tree" (.getPath root) "ls-files"
                "--cached" "--others" "--exclude-standard" "-z"]
               {:dir (.getPath root) :out :string :err :string})]
    (try
      (let [result (deref child inventory-bound-ms ::expired)]
        (when (or (= ::expired result) (not= 0 (:exit result)))
          (throw (ex-info "Git could not enumerate test inputs."
                          {:seon.test.cache/root (.getPath root)
                           :seon.test.cache/result result})))
        (vec (enumeration-seq
              (java.util.StringTokenizer. (:out result) (str (char 0))))))
      (finally (process/destroy-tree child)))))))

(defn- file-input-digests
  "Hash Git's tracked and non-ignored files by repository-relative path.
  Directory links and submodule directories are never traversed.
  The gate snapshot carries the source Git index but hashes its own bytes."
  {:malli/schema [:=> [:cat [:string {:min 1}]]
                  [:map-of [:string {:min 1}] [:string {:min 1}]]]}
  [root]
  (let [root-file (.getCanonicalFile (io/file root))]
    (into
     {}
     (comp
      (map #(io/file root-file %))
      (filter #(.isFile ^File %))
      (map (fn [^File file]
             [(str/replace (str (.relativize (.toPath root-file) (.toPath file))) File/separator "/")
              (sha-256 (Files/readAllBytes (.toPath file)))])))
     (input-paths (.getPath root-file)))))

(defn source-inputs
  "The program-source part of a gate's recorded input digests."
  {:malli/schema [:=> [:cat [:map-of :string :string]] [:map-of :string :string]]}
  [digests]
  (into {}
        (filter (fn [[path _]]
                  (some #(str/starts-with? path (str % "/")) graph-roots)))
        digests))

(defn changed-inputs
  "Repository-relative paths whose bytes differ from a recorded basis."
  {:malli/schema [:=> [:cat
                       [:map-of [:string {:min 1}] [:string {:min 1}]]
                       [:map-of [:string {:min 1}] [:string {:min 1}]]]
                  [:map [:seon.test.cache/changed [:vector [:string {:min 1}]]]
                   [:seon.test.cache/removed [:vector [:string {:min 1}]]]]]}
  [basis-digests current-digests]
  {:seon.test.cache/changed
   (->> current-digests
        (keep (fn [[path digest]]
                (when-not (= digest (get basis-digests path))
                  path)))
        sort
        vec)
   :seon.test.cache/removed
   (->> basis-digests
        (keep (fn [[path _]]
                (when-not (contains? current-digests path)
                  path)))
        sort
        vec)})

(defn- declared-input-roots
  "Checkout-relative roots deps.edn declares as gate inputs: `:paths`, the
  `:test` alias's `:extra-paths`, and every `:local/root` dependency (the
  vendored forks under reference-code/, whose gitlinks are inputs), without
  `.` (the checkout itself, never an input boundary) and without the
  program-graph roots."
  {:malli/schema [:=> [:cat [:string {:min 1}]] [:set [:string {:min 1}]]]}
  [root]
  (let [manifest (io/file root "deps.edn")
        ;; A checkout without a manifest declares no roots; the manifest's
        ;; own presence is a gate input file, so its removal still changes
        ;; the inventory digest.
        deps (if (.isFile manifest) (edn/read-string (slurp manifest)) {})
        local-roots (fn [dependencies]
                      (keep (fn [[_ coordinate]] (:local/root coordinate)) dependencies))]
    (into #{}
          (comp (map str)
                (remove #{"."})
                (remove (set graph-roots)))
          (concat (:paths deps)
                  (get-in deps [:aliases :test :extra-paths])
                  (local-roots (:deps deps))
                  (mapcat (fn [[_ alias]] (local-roots (:extra-deps alias)))
                          (:aliases deps))))))

(def gate-input-files
  "Files outside every root that decide what a gate runs or loads: the
  dependency manifest and the launchers. A change to one widens the gate."
  #{"deps.edn" "bin/test" "bin/test-fast" "bin/_test-slot" "bin/test-check"})

(def gate-input-directories
  "Directories outside the classpath whose files the gate or its fixtures
  read: shipped config manifests and analyzer configuration/hooks."
  #{"config" ".clj-kondo"})

(declare gitlink-digests)

(defn input-roots
  "Every checkout-relative root or file whose change is a gate input, for
  one checkout: deps.edn's declared roots and local/root dependencies, the
  pinned gitlinks Git records for that checkout (a vendored dependency is an
  input whether or not deps.edn names it), the config directory, the
  dependency manifest and the launchers. Computed once per question; a
  caller classifying many paths holds the set."
  {:malli/schema [:=> [:cat [:string {:min 1}]] [:set [:string {:min 1}]]]}
  [root]
  (into (into gate-input-files gate-input-directories)
        (concat (declared-input-roots root)
                (keys (gitlink-digests (.getCanonicalPath (io/file root)))))))

(defn input-path?
  "True when `path` is one of `roots` or lies under one of them."
  {:malli/schema [:=> [:cat [:set [:string {:min 1}]] [:string {:min 1}]] :boolean]}
  [roots path]
  (boolean
   (some (fn [root]
           (or (= path root)
               (str/starts-with? path (str root "/"))))
         roots)))

(defn widening-path?
  "True when a changed path is a gate input outside the program graph of
  THIS checkout: a file on a declared non-graph classpath root (resources,
  script), a vendored dependency's gitlink or file (deps.edn `:local/root`
  or a recorded gitlink), a shipped config manifest, the dependency manifest,
  or a launcher. A documentation note, a scratch file or a log is not an
  input and never widens a gate (2026-09-19: the previous complement-of-roots
  definition widened every gate, and published default, on each markdown
  edit). Classifying many paths: use [[input-roots]] once with [[input-path?]]."
  {:malli/schema [:=> [:cat [:string {:min 1}]] :boolean]}
  [path]
  (input-path? (input-roots ".") path))

(defn gitlink-digests
  "Hash Git pins; recorded snapshot pins take precedence over the live index."
  {:malli/schema [:=> [:cat :string] [:map-of :string :string]]}
  [root]
  (let [recorded (io/file root "dependency-pins.txt")
        text (if (.isFile recorded)
               (slurp recorded)
               (let [child (process/process
                            ["git" "--work-tree" root "ls-files" "--stage"]
                            {:dir root :out :string :err :string})]
                 (try
                   (let [result (deref child inventory-bound-ms ::expired)]
                     (when (or (= ::expired result) (not= 0 (:exit result)))
                       (throw (ex-info "Git could not enumerate input gitlinks."
                                       {::root root ::result result})))
                     (:out result))
                   (finally (process/destroy-tree child)))))]
    (into {}
          (keep (fn [line]
                  (when (str/starts-with? line "160000 ")
                    (let [tab (.indexOf ^String line "\t")
                          tokens (vec (enumeration-seq
                                       (java.util.StringTokenizer. (subs line 0 tab))))]
                      [(subs line (inc tab))
                       (sha-256 (.getBytes ^String (second tokens) "UTF-8"))]))))
          (str/split-lines text))))

(defn toolchain-dependencies
  "The gate's pinned dependencies plus the analyzer's declared configuration.
  Inputs come from the same Git/snapshot inventory; ignored caches are absent."
  {:malli/schema [:=> [:cat :string [:set :string]] [:map-of :string :string]]}
  [root configuration-roots]
  (let [root (.getCanonicalPath (io/file root))
        roots (conj configuration-roots "deps.edn")]
    (into (gitlink-digests root)
          (comp (filter #(input-path? roots %))
                (keep (fn [path]
                        (let [file (io/file root path)]
                          (when (.isFile file)
                            [path (sha-256 (Files/readAllBytes (.toPath file)))])))))
          (input-paths root))))

(defn input-digests
  "Path/content digests of snapshot files and pinned gitlinks.
  File links contribute their bytes; directory links are never traversed."
  {:malli/schema [:=> [:cat :string] [:map-of :string :string]]}
  [root]
  (let [root (.getCanonicalPath (io/file root))]
    (into (file-input-digests root) (gitlink-digests root))))

(defn test-input-digest
  "Digest the sorted inventory of gate inputs outside the program graph,
  gitlinks included, for one checkout (`root` names the checkout the
  `inputs` were inventoried from; the one-argument arity is the current
  checkout)."
  {:malli/schema [:function
                  [:=> [:cat [:map-of :string :string]] :seon.source/digest]
                  [:=> [:cat [:string {:min 1}] [:map-of :string :string]] :seon.source/digest]]}
  ([inputs] (test-input-digest "." inputs))
  ([root inputs]
   (let [roots (input-roots root)]
     (sha-256 (.getBytes (pr-str (into (sorted-map)
                                      (filter (fn [[path _]] (input-path? roots path)))
                                      inputs)) "UTF-8")))))

(defn- read-edn [file]
  (when (.isFile (io/file file))
    (edn/read-string (slurp file))))

(defn classpath
  "Resolve the tool-produced ordered roots against one checkout."
  {:malli/schema [:=> [:cat :seon.test/classpath :string] :string]}
  [basis checkout]
  (str/join java.io.File/pathSeparator
            (map (fn [path]
                   (let [file (io/file path)]
                     (.getPath (if (.isAbsolute file) file (io/file checkout path)))))
                 (:seon.test/classpath-roots basis))))

(defn- snapshot-git-sha [snapshot]
  (let [ledger (io/file snapshot "test-run.txt")]
    (when (.isFile ledger)
      (with-open [reader (io/reader ledger)]
        (some #(when (.startsWith ^String % "git=") (.substring ^String % 4))
              (enumeration-seq (java.util.StringTokenizer. (or (.readLine reader) ""))))))))

(defn- child!
  ([directory arguments]
   (child! directory arguments
           (+ (System/nanoTime)
              (.toNanos TimeUnit/SECONDS (bounds/silence-seconds (into {} (System/getenv)))))))
  ([directory arguments deadline]
  (let [remaining (- deadline (System/nanoTime))
        _ (when-not (pos? remaining)
            (throw (ex-info "Test input preparation exceeded its execution bound."
                            {::command arguments ::phase :worker-checkout})))
        child (process/process arguments
                               {:dir (str directory) :in :inherit :out :inherit :err :inherit
                                :shutdown (fn [child]
                                            (process/destroy-tree child)
                                            (when-not (.waitFor ^Process (:proc child) 10 TimeUnit/SECONDS)
                                              (.destroyForcibly ^Process (:proc child))
                                              (.waitFor ^Process (:proc child) 10 TimeUnit/SECONDS)))})]
    (try
      (let [result (deref child (max 1 (quot remaining 1000000)) ::expired)]
        (when (= ::expired result)
          (throw (ex-info "Test input preparation exceeded its execution bound."
                          {::command arguments ::phase :worker-checkout})))
        (when-not (zero? (:exit result))
          (throw (ex-info "Test input preparation failed."
                          {::command arguments ::exit (:exit result)}))))
      (finally
        (when (.isAlive ^Process (:proc child))
          (process/destroy-tree child)
          (when-not (.waitFor ^Process (:proc child) 10 TimeUnit/SECONDS)
            (.destroyForcibly ^Process (:proc child))
            (.waitFor ^Process (:proc child) 10 TimeUnit/SECONDS))))))))

(defn- copy-checkout! [snapshot checkout deadline]
  (let [snapshot (.getCanonicalFile (io/file snapshot))
        checkout (.getCanonicalFile (io/file checkout))]
  (.mkdirs checkout)
  (doseq [entry (.listFiles snapshot)
          :when (not (#{"data" "logs" "target" "tmp" "workers" ".cpcache"
                        "test-run.txt" "changed-paths.txt"} (.getName entry)))]
    (child! snapshot
            (if (= "Mac OS X" (System/getProperty "os.name"))
              ["/bin/cp" "-cRP" (str entry) (str checkout)]
              ["cp" "-a" "--reflink=auto" (str entry) (str checkout)])
            deadline))))

(defn- alive? [{::keys [pid started]}]
  (when (and pid started)
    (let [candidate (ProcessHandle/of (long pid))]
      (when (.isPresent candidate)
        (let [handle (.get candidate)]
          (and (.isAlive handle)
               (= started (str (.orElse (.startInstant (.info handle)) nil)))))))))

(defonce ^:private worker-checkout-lock (ReentrantLock.))

(defn worker-checkout!
  "Materialize an admitted worker's checkout from the immutable gate snapshot."
  {:malli/schema [:function
                  [:=> [:cat :string :string] :string]
                  [:=> [:cat :string :string [:int {:min 1}]] :string]]}
  ([snapshot checkout]
   (worker-checkout! snapshot checkout (bounds/silence-seconds (into {} (System/getenv)))))
  ([snapshot checkout seconds]
   (let [allowance (.toNanos TimeUnit/SECONDS seconds)
         deadline (+ (System/nanoTime) allowance)]
    (when-not (.tryLock ^ReentrantLock worker-checkout-lock allowance TimeUnit/NANOSECONDS)
      (throw (ex-info "Worker checkout preparation did not acquire its lock within the declared bound."
                      {::phase :worker-checkout ::seconds seconds ::checkout checkout})))
    (try
    (when-not (.isDirectory (io/file checkout "src"))
      (copy-checkout! snapshot checkout deadline)
      (doseq [directory ["data" "logs" "tmp" "target"]]
        (.mkdirs (io/file checkout directory)))
      (doseq [entry (.listFiles (io/file snapshot "target"))
              :when (Files/isSymbolicLink (.toPath entry))]
        (Files/createSymbolicLink
         (.toPath (io/file checkout "target" (.getName entry)))
         (Files/readSymbolicLink (.toPath entry))
         (make-array java.nio.file.attribute.FileAttribute 0))))
    checkout
    (finally (.unlock ^ReentrantLock worker-checkout-lock))))))

(defn- referenced? [directory]
  (boolean
   (some (fn [file]
           (if (alive? (read-edn file))
             true
             (do (io/delete-file file true) false)))
         (.listFiles (io/file directory "references")))))

(defn- reap! [parent]
  (let [parent (.getCanonicalFile (io/file parent))
        no-follow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])
        inactive (->> (.listFiles (io/file parent))
                      (filter #(Files/isDirectory (.toPath %) no-follow))
                      (remove referenced?)
                      (sort-by #(.lastModified (io/file % "ready.edn")) >))
        cutoff (- (System/currentTimeMillis) (* 24 60 60 1000))]
    (doseq [[rank directory] (map-indexed vector inactive)
            :when (or (>= rank 3)
                      (< (.lastModified (io/file directory "ready.edn")) cutoff))]
      ((requiring-resolve 'seon.fs/delete-recursively!) (str parent) (str directory)))))

(defn prepare-base!
  "Ask the root's live publisher for an export; boot only if no host exists."
  {:malli/schema [:=> [:cat :string :string :string :map] :string]}
  [source snapshot base basis]
  (let [source (.getCanonicalPath (io/file source))
        snapshot (.getCanonicalPath (io/file snapshot))
        base (.getCanonicalPath (io/file base))
        paths (input-paths snapshot)
        form (pr-str
               `(do
                  (require 'seon.cluster)
                  (with-bindings
                    {(ns-resolve 'seon.cluster (symbol "*source-progress!*"))
                     (fn [phase#] (println "bin/test: SOURCE" phase#) (flush))}
                    ((ns-resolve 'seon.cluster (symbol "refresh-source!"))
                     ~(str (io/file source "data/clusters")) ~paths nil ~snapshot)
                    ((ns-resolve 'seon.cluster (symbol "publication-base!"))
                     ~(str (io/file source "data/clusters")) ~snapshot ~base))))
        ;; BB loads the operator after this cache namespace is complete. The
        ;; same advertisement/send authority is used by result recording.
        live ((requiring-resolve 'seon.fresh-operator/live-root-value!)
              source form {:seon.fresh-operator/observe-output!
                           (fn [text] (print text) (flush))})]
    (if (:seon.fresh-operator/live-process? live)
      (when-not (= base (:seon.fresh-operator/value live))
        (throw (ex-info "The live publisher refused the requested base." live)))
      (child! snapshot
              (into ["clojure" "-Scp" (classpath basis snapshot)]
                    (concat (map #(str "-J" %) (:seon.test/jvm-options basis))
                            [(str "-J-Dseon.operator.root=" source)
                             (str "-J-Dseon.test.root=" snapshot)
                             (str "-J-Dseon.test.source-root=" source)
                             "-M:test" "-m" "seon.test.runner" "--prepare-base" source snapshot base]))))
    base))

(defn- ensure-base! [source snapshot digest basis-file pid]
  (let [parent (.getCanonicalFile (io/file source "target" "test-published-bases"))
        directory (io/file parent digest)
        base (io/file directory "base")
        ready (io/file directory "ready.edn")
        reference (io/file directory "references" (str pid ".edn"))
        handle (.orElseThrow (ProcessHandle/of (Long/parseLong pid)))
        lock (io/file source "target" "dev-dependency-cache.lock")
        basis (read-edn basis-file)
        started (System/nanoTime)]
    (.mkdirs (.getParentFile lock))
    (with-open [file (RandomAccessFile. lock "rw")
                channel (.getChannel file)]
      ;; Closing the owning channel releases its lock, including on failure.
      (.lock channel)
      (let [acquired (System/nanoTime)
            _ (println "bin/test: CACHE LOCK acquired wait-ms="
                       (quot (- acquired started) 1000000))
            inputs (read-edn (io/file source "target/test-classpaths"
                                      (str digest ".inputs.edn")))
            hit? (and (= digest (::digest (read-edn ready)))
                      (.isDirectory (io/file base "data" "store"))
                      (.isFile (io/file base "manifest.edn")))]
        (when-not hit?
          (when (.exists directory)
            ((requiring-resolve 'seon.fs/delete-recursively!) (str parent) (str directory)))
          (do
            (println "bin/test: PUBLISH cached base" digest "through the common source authority")
            (flush)
            (prepare-base! source snapshot (str base) basis)
            (when-not (and (.isDirectory (io/file base "data" "store"))
                           (.isFile (io/file base "manifest.edn")))
              (throw (ex-info "Publication exited without its store and manifest."
                              {::base (str base)})))
            (let [roots (input-roots snapshot)
                  expected (into {} (filter #(input-path? roots (key %))) (first inputs))
                  published (:seon.source/relative-file-digests
                             (read-edn (io/file base "build/current-src.edn")))
                  difference (changed-inputs (or published {}) expected)]
              (when (some seq (vals difference))
                (throw (ex-info "The exported publication does not contain the requested checkout inputs."
                                (assoc difference ::base (str base))))))
            (spit ready (pr-str (cond-> {::digest digest ::prepared-at (str (Instant/now))}
                                 (snapshot-git-sha snapshot) (assoc ::git-sha (snapshot-git-sha snapshot))
                                 inputs (assoc ::inputs inputs))))))
        (when hit?
          (let [manifest (read-edn (io/file base "manifest.edn"))]
            (if (seq (:seon.fn.manifest/artifacts manifest))
              (println "bin/test: findings:"
                       (count (:seon.fn.manifest/findings manifest))
                       "; added=0; resolved=0 (unchanged publication)")
              (println "bin/test: findings unavailable: cached manifest is absent or unreadable"))))
        (.mkdirs (.getParentFile reference))
        (spit reference (pr-str {::pid (Long/parseLong pid)
                                ::started (str (.orElse (.startInstant (.info handle)) nil))}))
        (.setLastModified ready (System/currentTimeMillis))
        (reap! parent)
        (println "bin/test:" (if hit? "REUSE cached base" "PREPARED cached base")
                 digest "elapsed-ms=" (quot (- (System/nanoTime) started) 1000000)
                 "work-ms=" (quot (- (System/nanoTime) acquired) 1000000))
        (flush)))))

(defn manifest
  "Read the exact program manifest produced with an immutable published base."
  {:malli/schema [:=> [:cat :string] :seon.fn.manifest/manifest]}
  [base]
  (or (read-edn (io/file base "manifest.edn"))
      (throw (ex-info "The published test base has no program manifest."
                      {::base base}))))

(defn head-manifest
  "Read a published base whose recorded program-source inputs match the snapshot."
  {:malli/schema [:=> [:cat :string [:map-of :string :string]] :seon.fn.manifest/manifest]}
  [source source-inputs]
  (or (some (fn [directory]
              (let [ready (read-edn (io/file directory "ready.edn"))
                    inputs (first (::inputs ready))
                    graph (io/file directory "base/manifest.edn")]
                (when (and (map? inputs)
                           (= source-inputs (seon.test.cache/source-inputs inputs))
                           (= (.getName directory) (::digest ready))
                           (.isFile graph))
                  (manifest (str (io/file directory "base"))))))
            (sort-by #(.getName %) (.listFiles (io/file source "target/test-published-bases"))))
      (throw (ex-info
              "No published program graph matches the source snapshot; orchestrator must run: bin/test --prepare-head-base"
              {::source-inputs source-inputs}))))

(defn newest-base
  "Resolve the published base shared by overlay admission and recording."
  {:malli/schema [:=> [:cat :string :string]
                  [:map [:seon.test.cache/base :string]
                   [:seon.test.cache/digest :seon.source/digest]]]}
  [source git-sha]
  (let [candidate
        (->> (.listFiles (io/file source "target/test-published-bases"))
             (keep (fn [directory]
                     (let [file (io/file directory "ready.edn")
                           ready (read-edn file)]
                       (when (and (= (.getName directory) (::digest ready))
                                  (.isFile (io/file directory "base/manifest.edn")))
                         [directory ready (if-let [at (::prepared-at ready)]
                                            (Instant/parse at)
                                            (Instant/ofEpochMilli (.lastModified file)))]))))
             (sort-by (juxt #(nth % 2) #(-> % second ::digest)))
             last)]
    (when-not candidate
      (throw (ex-info "No published program graph exists; orchestrator must run: bin/test --prepare-head-base" {})))
    (let [[directory ready] candidate
          age (if-let [base-sha (::git-sha ready)]
                (let [child (process/process ["git" "rev-list" "--count" (str base-sha ".." git-sha)]
                                             {:dir source :out :string :err :string})]
                  (try
                    (let [result (deref child (* 1000 (bounds/silence-seconds (into {} (System/getenv)))) ::expired)]
                      (if (and (map? result) (zero? (:exit result)))
                        (.trim ^String (:out result))
                        "unknown (commit unavailable)"))
                    (finally (process/destroy-tree child))))
                "unknown (legacy base has no Git provenance)")]
      (println "bin/test: overlay graph" (::digest ready) "age=" age "commits behind HEAD")
      {::base (str (io/file directory "base")) ::digest (::digest ready)})))

(defn newest-manifest
  "Read the newest published graph and announce its digest and commit age."
  {:malli/schema [:=> [:cat :string :string] :seon.fn.manifest/manifest]}
  [source git-sha]
  (manifest (::base (newest-base source git-sha))))

(defn -main
  "Prepare the selected snapshot's base; retain it while its launcher lives."
  {:malli/schema [:=> [:cat [:* {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Clojure's command-line entry point receives any number of string arguments; the command parser owns option combinations and their diagnostics.", :gen/elements [[]]} :string]] :nil]}
  [& arguments]
  (apply ensure-base! arguments))

(ns seon.test.cache
  "Prepare immutable test inputs under the development cache's existing lock."
  (:require [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [seon.fs :as fs]
            [seon.test.bounds :as bounds]
            [seon.test.selection :as selection])
  (:import [java.io RandomAccessFile]
           [java.lang Process ProcessHandle]
           [java.nio.file Files LinkOption]
           [java.time Instant]
           [java.util.concurrent TimeUnit]))

(defn- read-edn [file]
  (when (.isFile (io/file file))
    (edn/read-string (slurp file))))

(defn- child! [directory arguments]
  (let [seconds (bounds/silence-seconds (into {} (System/getenv)))
        child (process/process arguments
                               {:dir (str directory) :in :inherit :out :inherit :err :inherit
                                :shutdown (fn [child]
                                            (process/destroy-tree child)
                                            (when-not (.waitFor ^Process (:proc child) 10 TimeUnit/SECONDS)
                                              (.destroyForcibly ^Process (:proc child))
                                              (.waitFor ^Process (:proc child) 10 TimeUnit/SECONDS)))})]
    (try
      (let [result (deref child (* seconds 1000) ::expired)]
        (when (= ::expired result)
          (throw (ex-info "Test input preparation exceeded its execution bound."
                          {::command arguments ::seconds seconds})))
        (when-not (zero? (:exit result))
          (throw (ex-info "Test input preparation failed."
                          {::command arguments ::exit (:exit result)}))))
      (finally
        (when (.isAlive ^Process (:proc child))
          (process/destroy-tree child)
          (when-not (.waitFor ^Process (:proc child) 10 TimeUnit/SECONDS)
            (.destroyForcibly ^Process (:proc child))
            (.waitFor ^Process (:proc child) 10 TimeUnit/SECONDS)))))))

(defn- copy-checkout! [snapshot checkout]
  (let [snapshot (.getCanonicalFile (io/file snapshot))
        checkout (.getCanonicalFile (io/file checkout))]
  (.mkdirs checkout)
  (doseq [entry (.listFiles snapshot)
          :when (not (#{"data" "logs" "target" "tmp" "workers" ".cpcache"
                        "test-run.txt" "changed-paths.txt"} (.getName entry)))]
    (child! snapshot
            (if (= "Mac OS X" (System/getProperty "os.name"))
              ["/bin/cp" "-cRP" (str entry) (str checkout)]
              ["cp" "-a" "--reflink=auto" (str entry) (str checkout)])))))

(defn- alive? [{::keys [pid started]}]
  (when (and pid started)
    (let [candidate (ProcessHandle/of (long pid))]
      (when (.isPresent candidate)
        (let [handle (.get candidate)]
          (and (.isAlive handle)
               (= started (str (.orElse (.startInstant (.info handle)) nil)))))))))

(defn worker-checkout!
  "Materialize an admitted worker's checkout from the immutable gate snapshot."
  {:malli/schema [:=> [:cat :string :string] :string]}
  [snapshot checkout]
  (locking #'worker-checkout!
    (when-not (.isDirectory (io/file checkout "src"))
      (copy-checkout! snapshot checkout)
      (doseq [directory ["data" "logs" "tmp" "target"]]
        (.mkdirs (io/file checkout directory)))
      (doseq [entry (.listFiles (io/file snapshot "target"))
              :when (Files/isSymbolicLink (.toPath entry))]
        (Files/createSymbolicLink
         (.toPath (io/file checkout "target" (.getName entry)))
         (Files/readSymbolicLink (.toPath entry))
         (make-array java.nio.file.attribute.FileAttribute 0))))
    checkout))

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
      (fs/delete-recursively! (str parent) (str directory)))))

(defn- compatible-changes
  "Program paths differing between complete, corresponding cache inputs.
  Missing legacy evidence or any non-program change requires a full build."
  [before after]
  (when (and (vector? before) (= 3 (count before))
             (vector? after) (= 3 (count after))
             (map? (first before)) (map? (first after))
             (every? string? (subvec before 1))
             (every? string? (subvec after 1))
             (= (subvec before 1) (subvec after 1)))
    (let [changes (selection/changed-inputs (first before) (first after))
          paths (vec (sort (concat (::selection/changed changes)
                                   (::selection/removed changes))))]
      ;; The selector already declares which changed paths are outside the
      ;; program graph; re-deriving that boundary here would be a second
      ;; authority for the same question.
      (when-not (some selection/widening-path? paths)
        paths))))

(defn- retained-base
  [parent inputs]
  (->> (.listFiles (io/file parent))
       (keep (fn [directory]
               (let [ready (read-edn (io/file directory "ready.edn"))
                     changes (compatible-changes (::inputs ready) inputs)
                     base (io/file directory "base")]
                 (when (and changes
                            (= (.getName directory) (::digest ready))
                            (.isDirectory (io/file base "data" "store"))
                            (.isFile (io/file base "build" "current-src.edn")))
                   {::base base ::digest (::digest ready) ::changes changes}))))
       (sort-by (juxt #(count (::changes %)) ::digest))
       first))

(defn- clone-base!
  [seed base]
  (doseq [path ["data/store" "build/current-src.edn"]]
    (let [destination (io/file base path)]
      (.mkdirs (.getParentFile destination))
      (child! base
              (if (= "Mac OS X" (System/getProperty "os.name"))
                ["/bin/cp" "-cRP" (str (io/file seed path)) (str destination)]
                ["cp" "-a" "--reflink=auto" (str (io/file seed path)) (str destination)])))))

(defn- ensure-base! [source snapshot digest classpath pid]
  (let [parent (.getCanonicalFile (io/file source "target" "test-published-bases"))
        directory (io/file parent digest)
        base (io/file directory "base")
        checkout (io/file directory "checkout")
        ready (io/file directory "ready.edn")
        reference (io/file directory "references" (str pid ".edn"))
        handle (.orElseThrow (ProcessHandle/of (Long/parseLong pid)))
        lock (io/file source "target" "dev-dependency-cache.lock")
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
            (fs/delete-recursively! (str parent) (str directory)))
          (let [seed (retained-base parent inputs)]
            (copy-checkout! snapshot checkout)
            (when seed (clone-base! (::base seed) base))
            (println "bin/test: PUBLISH cached base" digest
                     (if seed "from retained base" "full: no compatible retained base")
                     (when seed (select-keys seed [::digest ::changes])))
            (flush)
            (child! checkout
                    (cond-> ["clojure" "-Scp" classpath
                             (str "-J-Dseon.operator.root=" base)
                             (str "-J-Dseon.test.root=" snapshot)
                             (str "-J-Dseon.test.source-root=" source)
                             "-M:test" "-m" "seon.test.runner" "--prepare-base" (str base)]
                      seed (conj (pr-str (::changes seed)))))
            (when-not (and (.isDirectory (io/file base "data" "store"))
                           (.isFile (io/file base "manifest.edn")))
              (throw (ex-info "Publication exited without its store and manifest."
                              {::base (str base)})))
            (spit ready (pr-str (cond-> {::digest digest ::prepared-at (str (Instant/now))}
                                 inputs (assoc ::inputs inputs))))))
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

(defn record-head!
  "Record the Git identity of a HEAD-only publication beside its cached base."
  {:malli/schema [:=> [:cat :string :string :string] :nil]}
  [source digest git-sha]
  (let [directory (io/file source "target/test-published-bases" digest)
        ready (io/file directory "ready.edn")
        record (read-edn ready)
        temporary (io/file directory (str "head-" (random-uuid) ".edn"))]
    (manifest (str (io/file directory "base")))
    (when-not (= digest (::digest record))
      (throw (ex-info "HEAD publication has no matching cache record." {::digest digest})))
    (spit temporary (pr-str (assoc record ::git-sha git-sha)))
    (when-not (.renameTo temporary ready)
      (throw (ex-info "Could not record HEAD publication." {::git-sha git-sha})))
    nil))

(defn head-manifest
  "Read a published program graph for exactly the requested Git commit."
  {:malli/schema [:=> [:cat :string :string] :seon.fn.manifest/manifest]}
  [source git-sha]
  (or (some (fn [directory]
              (let [ready (read-edn (io/file directory "ready.edn"))
                    graph (io/file directory "base/manifest.edn")]
                (when (and (= git-sha (::git-sha ready))
                           (= (.getName directory) (::digest ready))
                           (.isFile graph))
                  (let [value (read-edn graph)]
                    (when-not (vector? (:seon.fn.manifest/artifacts value))
                      (throw (ex-info "Published HEAD graph has no artifacts." {::git-sha git-sha})))
                    value))))
            (sort-by #(.getName %) (.listFiles (io/file source "target/test-published-bases"))))
      (throw (ex-info
              (str "No published program graph matches HEAD " git-sha
                   "; orchestrator must run: bin/test --prepare-head-base")
              {::git-sha git-sha}))))

(defn -main
  "Prepare the selected snapshot's base; retain it while its launcher lives."
  {:malli/schema [:=> [:cat [:* {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Clojure's command-line entry point receives any number of string arguments; the command parser owns option combinations and their diagnostics.", :gen/elements [[]]} :string]] :nil]}
  [& arguments]
  (apply ensure-base! arguments))

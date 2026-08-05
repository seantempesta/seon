(ns writer-class-loading-discriminants
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io BufferedReader InputStreamReader]
           [java.net Socket]
           [java.nio.file FileVisitOption Files LinkOption Path StandardCopyOption]
           [java.util UUID]))

(def ^:private result-prefix "WRITER_CLASS_LOADING_RESULT ")
(def ^:private ready-prefix "WRITER_CLASS_LOADING_READY ")
(def ^:private no-link-options (make-array LinkOption 0))

(defn- canonical-path
  [path]
  (.getCanonicalPath (io/file path)))

(defn- repository-root
  []
  (canonical-path "."))

(defn- run-root
  [label]
  (canonical-path
   (io/file "tmp" "writer-class-loading-incident-2026-08-05"
            (str label "-" (UUID/randomUUID)))))

(defn- cause-chain
  [error]
  (loop [current error
         seen #{}
         result []]
    (if (or (nil? current) (contains? seen current) (= 8 (count result)))
      result
      (let [message (ex-message current)]
        (recur (.getCause ^Throwable current)
               (conj seen current)
               (conj result
                     {:writer-class-loading/cause-class
                      (.getName (class current))
                      :writer-class-loading/cause-message
                      (when message (subs message 0 (min 240 (count message))))}))))))

(defn- class-info
  [value]
  (let [klass (class value)
        loader (.getClassLoader klass)
        source (some-> klass .getProtectionDomain .getCodeSource .getLocation str)]
    {:writer-class-loading/class (.getName klass)
     :writer-class-loading/loader (some-> loader class .getName)
     :writer-class-loading/loader-identity (some-> loader System/identityHashCode)
     :writer-class-loading/code-source source}))

(defn- process-output
  [arguments]
  (let [process (.start (doto (ProcessBuilder. ^java.util.List arguments)
                          (.directory (io/file (repository-root)))
                          (.redirectErrorStream true)))
        output (slurp (.getInputStream process))
        exit (.waitFor process)]
    {:writer-class-loading/exit exit
     :writer-class-loading/output output}))

(defn- parse-last-map
  [output]
  (->> (str/split-lines output)
       (keep (fn [line]
               (try
                 (let [value (edn/read-string line)]
                   (when (map? value) value))
                 (catch Throwable _ nil))))
       last))

(defn- selected-cache-path
  []
  (let [{:writer-class-loading/keys [exit output]}
        (process-output ["clojure" "-T:dev-cache" "ensure-cache"])
        result (parse-last-map output)
        path (:seon.dev-cache/path result)]
    (when-not (and (zero? exit) (string? path) (.isDirectory (io/file path)))
      (throw (ex-info "The dependency cache could not be selected."
                      {:writer-class-loading/exit exit
                       :writer-class-loading/result result})))
    (canonical-path path)))

(defn- copy-tree!
  [source target]
  (let [source-path (.toPath (io/file source))
        target-path (.toPath (io/file target))]
    (with-open [paths (Files/walk source-path (make-array FileVisitOption 0))]
      (doseq [^Path path (iterator-seq (.iterator paths))]
        (let [relative (.relativize source-path path)
              destination (.resolve target-path relative)]
          (if (Files/isDirectory path no-link-options)
            (Files/createDirectories destination (make-array java.nio.file.attribute.FileAttribute 0))
            (do
              (Files/createDirectories (.getParent destination)
                                       (make-array java.nio.file.attribute.FileAttribute 0))
              (Files/copy path destination
                          (into-array StandardCopyOption
                                      [StandardCopyOption/REPLACE_EXISTING]))))))))
  target)

(defn- substring-count
  [text fragment]
  (loop [offset 0
         matches 0]
    (let [found (.indexOf ^String text ^String fragment (int offset))]
      (if (neg? found)
        matches
        (recur (+ found (count fragment)) (inc matches))))))

(defn- delete-late-writer-classes!
  [cache-path]
  (let [writer-root (.toPath (io/file cache-path "datahike"))
        deleted
        (with-open [paths (Files/walk writer-root (make-array FileVisitOption 0))]
          (->> (iterator-seq (.iterator paths))
               (filter #(Files/isRegularFile ^Path % no-link-options))
               (filter (fn [^Path path]
                         (let [name (str (.getFileName path))]
                           (and (str/starts-with? name "writer$fn__")
                                (str/ends-with? name ".class")
                                (<= 2 (substring-count name "$fn__"))))))
               (mapv (fn [^Path path]
                       (Files/delete path)
                       (str (.getFileName path))))))]
    {:writer-class-loading/deleted-count (count deleted)
     :writer-class-loading/deleted-sample (vec (take 8 deleted))}))

(defn- read-until-prefix
  [^BufferedReader reader prefix]
  (loop [tail []]
    (if-let [line (.readLine reader)]
      (if (str/starts-with? line prefix)
        {:writer-class-loading/value
         (edn/read-string (subs line (count prefix)))
         :writer-class-loading/tail tail}
        (recur (vec (take-last 12 (conj tail line)))))
      (throw (ex-info "The child exited before publishing its result."
                      {:writer-class-loading/tail tail})))))

(defn- child-command
  [cache-path mode root]
  ["clojure"
   "-Sdeps" (pr-str {:aliases {:writer-class-loading-cache
                                {:extra-paths [cache-path]}}})
   (str "-J-Dseon.operator.root=" root)
   "-J-Dseon.operator.claimed=true"
   "-M:dev:writer-class-loading-cache"
   "docs/prds/sci-execution-runtime/research/scripts/writer-class-loading-discriminants.clj"
   mode])

(defn- mutable-cache-probe
  []
  (let [root (run-root "mutable-cache")
        mutable-cache (canonical-path (io/file root "mutable-cache"))
        source-cache (selected-cache-path)]
    (.mkdirs (io/file root))
    (copy-tree! source-cache mutable-cache)
    (let [process (.start
                   (doto (ProcessBuilder.
                          ^java.util.List
                          (child-command mutable-cache "mutable-cache-child" root))
                     (.directory (io/file (repository-root)))
                     (.redirectErrorStream true)))
          reader (BufferedReader. (InputStreamReader. (.getInputStream process)))
          writer (io/writer (.getOutputStream process))]
      (try
        (let [ready (:writer-class-loading/value
                     (read-until-prefix reader ready-prefix))
              deletion (delete-late-writer-classes! mutable-cache)
              _ (doto writer (.write "continue\n") .flush)
              outcome (:writer-class-loading/value
                       (read-until-prefix reader result-prefix))
              exit (.waitFor process)]
          {:writer-class-loading/experiment :mutable-cache-later-linkage
           :writer-class-loading/source-cache source-cache
           :writer-class-loading/root root
           :writer-class-loading/ready ready
           :writer-class-loading/deletion deletion
           :writer-class-loading/outcome outcome
           :writer-class-loading/child-exit exit})
        (finally
          (.close writer)
          (.close reader)
          (when (.isAlive process) (.destroy process)))))))

(defn- mutable-cache-child
  []
  (require 'datahike.api 'datahike.writer)
  (let [method (get-method @(resolve 'datahike.writer/create-database) :self)]
    (println ready-prefix
             (pr-str {:writer-class-loading/method (class-info method)
                      :writer-class-loading/max-heap-bytes
                      (.maxMemory (Runtime/getRuntime))
                      :writer-class-loading/input-arguments
                      (vec (.getInputArguments
                            (java.lang.management.ManagementFactory/getRuntimeMXBean)))}))
    (flush)
    (.readLine (BufferedReader. (InputStreamReader. System/in)))
    (let [api-create @(resolve 'datahike.api/create-database)
          config {:store {:backend :memory :id (UUID/randomUUID)}}
          result
          (try
            (api-create config)
            {:writer-class-loading/status :unexpected-success}
            (catch Throwable error
              {:writer-class-loading/status :failed
               :writer-class-loading/causes (cause-chain error)
               :writer-class-loading/process-still-running?
               (= "java.lang.String" (.getName String))}))]
      (println result-prefix (pr-str result))
      (flush))))

(defn- source-reload-child
  []
  (require 'datahike.api 'datahike.writer)
  (let [create-database @(resolve 'datahike.api/create-database)
        connect @(resolve 'datahike.api/connect)
        transact @(resolve 'datahike.api/transact)
        config {:store {:backend :memory :id (UUID/randomUUID)}}]
    (create-database config)
    (let [connection (connect config)
          baseline (transact connection [{:db/ident :writer-class-loading/value
                                           :db/valueType :db.type/long
                                           :db/cardinality :db.cardinality/one}])
          old-method (get-method @(resolve 'datahike.writer/create-writer) :self)
          reload-result
          (try
            (require 'datahike.writer :reload)
            {:writer-class-loading/status :reloaded}
            (catch Throwable error
              {:writer-class-loading/status :reload-failed
               :writer-class-loading/causes (cause-chain error)}))
          new-method (get-method @(resolve 'datahike.writer/create-writer) :self)
          completion (promise)
          transaction-thread
          (Thread/startVirtualThread
           (fn []
             (deliver
              completion
              (try
                (transact connection [{:writer-class-loading/value 42}])
                {:writer-class-loading/status :transacted}
                (catch Throwable error
                  {:writer-class-loading/status :transaction-failed
                   :writer-class-loading/causes (cause-chain error)})))))
          completed (deref completion 10000 ::silence)
          after-result
          (if (= ::silence completed)
            (do
              (.interrupt transaction-thread)
              {:writer-class-loading/status :transaction-did-not-complete
               :writer-class-loading/thread-alive? (.isAlive transaction-thread)})
            completed)]
      (println
       result-prefix
       (pr-str
        {:writer-class-loading/experiment :source-reload-generation
         :writer-class-loading/root (System/getProperty "seon.operator.root")
         :writer-class-loading/max-heap-bytes (.maxMemory (Runtime/getRuntime))
         :writer-class-loading/input-arguments
         (vec (.getInputArguments
               (java.lang.management.ManagementFactory/getRuntimeMXBean)))
         :writer-class-loading/baseline-datoms (count (:tx-data baseline))
         :writer-class-loading/old-method (class-info old-method)
         :writer-class-loading/reload reload-result
         :writer-class-loading/new-method (class-info new-method)
         :writer-class-loading/after after-result}))
      (flush))))

(defn- source-reload-probe
  []
  (let [root (run-root "source-reload")
        _ (.mkdirs (io/file root))
        arguments ["clojure"
                   (str "-J-Dseon.operator.root=" root)
                   "-J-Dseon.operator.claimed=true"
                   "-M:dev"
                   "docs/prds/sci-execution-runtime/research/scripts/writer-class-loading-discriminants.clj"
                   "source-reload-child"]
        {:writer-class-loading/keys [exit output]} (process-output arguments)
        result (some->> (str/split-lines output)
                        (filter #(str/starts-with? % result-prefix))
                        last
                        (#(subs % (count result-prefix)))
                        edn/read-string)]
    (or result
        {:writer-class-loading/experiment :source-reload-generation
         :writer-class-loading/status :child-produced-no-result
         :writer-class-loading/exit exit
         :writer-class-loading/output-tail
         (vec (take-last 12 (str/split-lines output)))})))

(defn- advertisement
  [root]
  (->> (file-seq (io/file root))
       (filter #(.isFile ^java.io.File %))
       (filter #(= "prepl.edn" (.getName ^java.io.File %)))
       (map #(edn/read-string (slurp %)))
       (filter #(and (:seon.boot/prepl-host %) (:seon.boot/prepl-port %)))
       first))

(defn- prepl-eval
  [advertisement form]
  (with-open [socket (Socket. ^String (:seon.boot/prepl-host advertisement)
                              (int (:seon.boot/prepl-port advertisement)))
              writer (io/writer (.getOutputStream socket))
              reader (BufferedReader. (InputStreamReader. (.getInputStream socket)))]
    (.write writer (str (pr-str form) "\n"))
    (.flush writer)
    (loop [events []]
      (if-let [line (.readLine reader)]
        (let [event (edn/read-string line)]
          (if (= :ret (:tag event))
            {:writer-class-loading/value (edn/read-string (:val event))
             :writer-class-loading/events (count events)}
            (recur (conj events event))))
        (throw (ex-info "The prepl closed before returning."
                        {:writer-class-loading/events events}))))))

(defn- current-operator-form
  []
  `(do
     (require 'datahike.api 'datahike.writer 'datahike.db.transaction)
     (let [cache-field# (.getDeclaredField clojure.lang.DynamicClassLoader
                                           "classCache")
           _# (.setAccessible cache-field# true)
           cache# (.get cache-field# nil)
           before# (.size ^java.util.Map cache#)
           method# (get-method datahike.writer/create-writer :self)
           validation# (deref #'datahike.db.transaction/validate-val)
           config# {:store {:backend :memory :id (java.util.UUID/randomUUID)}}]
       (.clear ^java.util.Map cache#)
       (datahike.api/create-database config#)
       (let [connection# (datahike.api/connect config#)
             report# (datahike.api/transact
                      connection#
                      [{:db/ident :writer-class-loading/value
                        :db/valueType :db.type/long
                        :db/cardinality :db.cardinality/one}
                       {:writer-class-loading/value 42}])
             result# {:writer-class-loading/experiment
                      :current-immutable-operator-cache
                      :writer-class-loading/operator-root
                      (System/getProperty "seon.operator.root")
                      :writer-class-loading/cache-path
                      (System/getProperty "seon.dependency-cache.path")
                      :writer-class-loading/cache-before before#
                      :writer-class-loading/cache-after (.size ^java.util.Map cache#)
                      :writer-class-loading/method-class
                      (.getName (class method#))
                      :writer-class-loading/method-loader
                      (some-> method# class .getClassLoader class .getName)
                      :writer-class-loading/method-code-source
                      (some-> method# class .getProtectionDomain .getCodeSource
                              .getLocation str)
                      :writer-class-loading/validation-class
                      (.getName (class validation#))
                      :writer-class-loading/validation-loader
                      (some-> validation# class .getClassLoader class .getName)
                      :writer-class-loading/validation-code-source
                      (some-> validation# class .getProtectionDomain .getCodeSource
                              .getLocation str)
                      :writer-class-loading/max-heap-bytes
                      (.maxMemory (Runtime/getRuntime))
                      :writer-class-loading/input-arguments
                      (vec (.getInputArguments
                            (java.lang.management.ManagementFactory/getRuntimeMXBean)))
                      :writer-class-loading/tx-datoms (count (:tx-data report#))}]
         (datahike.api/release connection#)
         (datahike.api/delete-database config#)
         result#))))

(defn- current-operator-probe
  []
  (let [root (run-root "current-operator")
        _ (.mkdirs (io/file root))
        init-result (process-output ["bin/seon" "--root" root "init"])
        start-result (process-output ["bin/seon" "--root" root "start"
                                      "writer-class-loading"])]
    (try
      (let [ad (advertisement root)]
        (if-not (and (zero? (:writer-class-loading/exit start-result)) ad)
          {:writer-class-loading/experiment :current-immutable-operator-cache
           :writer-class-loading/status :operator-did-not-start
           :writer-class-loading/init-exit (:writer-class-loading/exit init-result)
           :writer-class-loading/init-output-tail
           (vec (take-last 12
                           (str/split-lines
                            (:writer-class-loading/output init-result))))
           :writer-class-loading/start-exit (:writer-class-loading/exit start-result)
           :writer-class-loading/start-output-tail
           (vec (take-last 20
                           (str/split-lines
                            (:writer-class-loading/output start-result))))
           :writer-class-loading/advertisement ad}
          (assoc (prepl-eval ad (current-operator-form))
                 :writer-class-loading/experiment :current-immutable-operator-cache
                 :writer-class-loading/init-output-tail
                 (vec (take-last 12
                                 (str/split-lines
                                  (:writer-class-loading/output init-result))))
                 :writer-class-loading/start-output-tail
                 (vec (take-last 12
                                 (str/split-lines
                                  (:writer-class-loading/output start-result)))))))
      (finally
        (process-output ["bin/seon" "--root" root "down" "--force"])))))

(defn- emit!
  [value]
  (println (pr-str value))
  (flush))

(defn -main
  [& [mode]]
  (case mode
    "mutable-cache-child" (mutable-cache-child)
    "source-reload-child" (source-reload-child)
    "mutable-cache" (emit! (mutable-cache-probe))
    "source-reload" (emit! (source-reload-probe))
    "current-operator" (emit! (current-operator-probe))
    "all" (doseq [probe [mutable-cache-probe
                          source-reload-probe
                          current-operator-probe]]
            (emit! (probe)))
    (throw (ex-info "Expected all, mutable-cache, source-reload, or current-operator."
                    {:writer-class-loading/mode mode}))))

(apply -main *command-line-args*)

(ns seon.fresh-operator
  "The persisted-roster operator for the fresh JVM system."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [seon.dev.clj-kondo :as dev.kondo]
            [seon.dev.state :as state]
            [seon.operator.state :as operator.state])
  (:import [java.io PushbackReader RandomAccessFile]
           [java.net InetSocketAddress ServerSocket Socket
            SocketTimeoutException]
           [java.time Instant]
           [java.util.concurrent CompletableFuture ExecutionException
            LinkedBlockingQueue TimeUnit TimeoutException]
           [java.util.function Function Supplier]))

(def ^:private cluster-name-pattern
  #"\A[A-Za-z0-9](?:[A-Za-z0-9._-]{0,62})\z")
(def ^:private log-name "seon.log")
(def ^:private init-result-prefix "SEON-INIT-RESULT ")
(def ^:private roster-result-prefix "SEON-ROSTER-RESULT ")
(def ^:private cleanup-result-prefix "SEON-CLEANUP-RESULT ")

(defn- repository-root
  []
  ;; `bin/seon` loads this namespace from its checkout-relative `script/`.
  (-> (io/resource "seon/fresh_operator.clj")
      io/file
      .getCanonicalFile
      .getParentFile
      .getParentFile
      .getParentFile))

(def ^:private detach-python
  (str "import socket,subprocess,sys\n"
       "log=open(sys.argv[2],'ab',buffering=0)\n"
       "child='import os,socket,sys\\n'"
       "+'silence=float(sys.argv[2])/1000\\n'"
       "+'s=socket.create_connection((\"127.0.0.1\",int(sys.argv[1])),timeout=silence)\\n'"
       "+'s.settimeout(silence)\\n'"
       "+'adopted=s.recv(1)\\n'"
       "+'s.close()\\n'"
       "+'if not adopted: sys.exit(75)\\n'"
       "+'os.execvp(sys.argv[3],sys.argv[3:])\\n'\n"
       "p=subprocess.Popen([sys.executable,'-c',child,sys.argv[3],sys.argv[4],*sys.argv[5:]],cwd=sys.argv[1],"
       "stdin=subprocess.DEVNULL,stdout=log,stderr=subprocess.STDOUT,"
       "close_fds=True,start_new_session=True)\n"
       "print(p.pid,flush=True)\n"))

(defn- fail!
  [message data]
  (throw (ex-info message data)))

(def ^:private shipped-default-decisions
  (delay
    (edn/read-string
     (slurp (str (fs/path (repository-root) "config" "default.edn"))))))

(defn- operator-silence-backstop-ms
  [manifest]
  (let [attribute :seon.config.operator/event-silence-backstop-ms
        value (get manifest attribute (get @shipped-default-decisions attribute))]
    (when-not (pos-int? value)
      (fail! "The operator event-silence backstop is missing or invalid."
             {:seon.error/kind
              :seon.fresh-operator/invalid-event-silence-backstop
              :seon.config/attribute attribute
              :seon.config/value value}))
    value))

(defn- report-silence-backstop!
  [event silence-ms]
  (println
   (str "! operator event silence backstop fired: " event
        " was silent for " silence-ms " ms; config="
        ":seon.config.operator/event-silence-backstop-ms"))
  (flush))

(defn- parse-root
  [arguments]
  (let [[root remaining]
        (if (= "--seon-root" (first arguments))
          [(or (second arguments)
               (fail! "`--seon-root` requires a path." {}))
           (vec (drop 2 arguments))]
          [(System/getProperty "user.dir") (vec arguments)])]
    [(.getCanonicalPath (java.io.File. root)) remaining]))

(defn- cluster-root
  [root]
  (fs/path root "data" "clusters"))

(defn- cluster-directory
  [root name]
  (fs/path (cluster-root root) name))

(defn- advertisement-path
  [root name]
  (fs/path (cluster-directory root name) "prepl.edn"))

(defn- log-path
  [root name]
  (fs/path (cluster-directory root name) "logs" log-name))

(defn- process-record-directory
  [_root]
  (operator.state/process-claim-directory (repository-root)))

(defn- process-record-path
  [_root generation]
  (operator.state/process-claim-path (repository-root) generation))

(defn- dependency-cache-reference-path
  [generation]
  (fs/path (repository-root) "target" "dev-dependency-cache-processes"
           (str generation ".edn")))

(defn- valid-process-record?
  [record]
  (and (map? record)
       (uuid? (:seon.operator.process-record/generation record))
       (pos-int? (:seon.boot/pid record))
       (inst? (:seon.boot/start-instant record))
       (string? (:seon.operator.process-record/root record))
       (string? (:seon.operator.process-record/log record))
       (or (nil? (:seon.operator.process-record/cache-path record))
           (and (string? (:seon.operator.process-record/cache-path record))
                (fs/absolute? (:seon.operator.process-record/cache-path record))))))

(defn- read-process-records
  [root]
  (let [canonical-root (.getCanonicalPath (java.io.File. root))
        directory (process-record-directory canonical-root)]
    (if-not (fs/directory? directory)
      {:seon.fresh-operator/process-records []
       :seon.fresh-operator/process-record-errors []}
      (reduce
       (fn [result path]
         (try
           (let [record (state/read-edn path)]
            (if (and (valid-process-record? record)
                     (= canonical-root (:seon.operator.process-record/root record)))
              (update result :seon.fresh-operator/process-records conj record)
              (if (valid-process-record? record)
                result
                (update result :seon.fresh-operator/process-record-errors conj
                        {:seon.fresh-operator/path (str path)
                         :seon.fresh-operator/error
                         "The recorded child process file is invalid."}))))
           (catch Throwable error
             (update result :seon.fresh-operator/process-record-errors conj
                     {:seon.fresh-operator/path (str path)
                      :seon.fresh-operator/error (ex-message error)}))))
       {:seon.fresh-operator/process-records []
        :seon.fresh-operator/process-record-errors []}
       (sort-by str (fs/list-dir directory))))))

(defn- write-process-record!
  [root record]
  (when-not (valid-process-record? record)
    (fail! "Refusing to publish an invalid child process record."
           {:seon.fresh-operator/process-record record}))
  (state/write-edn!
   (process-record-path root (:seon.operator.process-record/generation record))
   record)
  (when (:seon.operator.process-record/cache-path record)
    (state/write-edn!
     (dependency-cache-reference-path
      (:seon.operator.process-record/generation record))
     record))
  record)

(defn- clear-process-record!
  [root record]
  (let [generation (:seon.operator.process-record/generation record)
        deleted? (state/delete-edn! (process-record-path root generation))]
    (state/delete-edn! (dependency-cache-reference-path generation))
    deleted?))

(defn- record-process-identity
  [record]
  (select-keys record
               [:seon.boot/pid :seon.boot/start-instant]))

(defn- record-alive?
  [record]
  (state/process-identity-alive? (record-process-identity record)))

(defn- record->boot-process
  [record]
  {:seon.boot/pid (:seon.boot/pid record)
   :seon.boot/start-instant (:seon.boot/start-instant record)
   :seon.fresh-operator/alive? (record-alive? record)})

(defn- process-record-matches-advertisement?
  [record advertisement]
  (and (= (:seon.boot/pid record)
          (:seon.boot/pid advertisement))
       (inst? (:seon.boot/start-instant advertisement))
       (= (inst-ms (:seon.boot/start-instant record))
          (inst-ms (:seon.boot/start-instant advertisement)))))

(defn- matching-process-handle
  [record]
  (try
    (let [optional
          (java.lang.ProcessHandle/of
           (long (:seon.boot/pid record)))]
      (when (.isPresent optional)
        (let [handle (.get optional)
              start (.startInstant (.info handle))]
          (when (and (.isAlive handle)
                     (.isPresent start)
                     (= (inst-ms (:seon.boot/start-instant record))
                        (.toEpochMilli (.get start))))
            handle))))
    (catch Throwable _ nil)))

(defn- with-operator-lock
  [_root transition]
  (let [directory (operator.state/control-root (repository-root))
        path (fs/path directory "lifecycle.lock")]
    (fs/create-dirs directory)
    (with-open [file (RandomAccessFile. (str path) "rw")
                channel (.getChannel file)]
      ;; The kernel publishes lock release when the current command finishes.
      ;; No clock stands in for that observable event.
      (.lock channel)
      (transition))))

(defn- unquote-value
  [value]
  (let [value (str/trim value)]
    (if (and (<= 2 (count value))
             (#{\' \"} (first value))
             (= (first value) (last value)))
      (subs value 1 (dec (count value)))
      value)))

(defn- dotenv-entry
  [line]
  (let [line (str/trim line)
        line (if (str/starts-with? line "export ")
               (subs line 7)
               line)]
    (when (and (not (str/blank? line))
               (not (str/starts-with? line "#")))
      (when-let [[_ env-key value]
                 (re-matches #"([A-Za-z_][A-Za-z0-9_]*)=(.*)" line)]
        [env-key (unquote-value value)]))))

(defn- dotenv
  [root]
  (let [path (fs/path root ".env")]
    (if (fs/regular-file? path)
      (into {} (keep dotenv-entry) (str/split-lines (slurp (str path))))
      {})))

(defn- child-environment
  [root]
  ;; The invoking environment wins. The file is data, never shell code.
  (merge (dotenv root) (into {} (System/getenv))))

(defn- start-child-jvm!
  [{:seon.fresh-operator/keys
    [root jvm-options arguments detach dependency-cache-path]}]
  (let [root (.getCanonicalPath (io/file root))
        cache-path (some-> dependency-cache-path io/file .getCanonicalPath)
        cache-options
        (when cache-path
          ["-Sdeps"
           (pr-str {:aliases
                    {:seon-cache {:extra-paths [cache-path]}}})])
        child-command
        (into ["clojure"]
              (concat cache-options
                      [(str "-J-Dseon.operator.root=" root)
                       "-J-Dseon.operator.claimed=true"
                       (str "-J-Dseon.repository.root=" (repository-root))]
                      (when cache-path
                        [(str "-J-Dseon.dependency-cache.path=" cache-path)])
                      jvm-options
                      [(if cache-path "-M:dev:seon-cache" "-M:dev")]
                      arguments))
        command
        (if detach
          (into ["python3" "-c" detach-python
                 (str (repository-root))
                 (:seon.fresh-operator/log detach)
                 (str (:seon.fresh-operator/adoption-port detach))
                 (str (:seon.fresh-operator/silence-backstop-ms detach))]
                child-command)
          child-command)
        builder (doto (ProcessBuilder. ^java.util.List command)
                  (.directory (repository-root))
                  (.redirectErrorStream true))
        _ (.putAll (.environment builder) (child-environment root))]
    (.start builder)))

(defn- ensure-dependency-cache!
  []
  (println "● boot dependency cache: checking inputs")
  (flush)
  (let [process
        (.start
         (doto
          (ProcessBuilder.
           ^java.util.List
           ["clojure" "-T:dev-cache" "ensure-cache"])
          (.directory (repository-root))
          (.redirectErrorStream true)))]
    (let [lines
          (with-open [reader (io/reader (.getInputStream process))]
            (reduce
             (fn [seen line]
               (println line)
               (flush)
               (conj seen line))
             []
             (line-seq reader)))
          exit (.waitFor process)]
      (when-not (zero? exit)
        (fail! "The dependency class cache could not be prepared."
               {:seon.fresh-operator/exit exit}))
      (let [result (try
                     (edn/read-string (last lines))
                     (catch Throwable error
                       (fail! "The dependency cache returned no selection."
                              {:seon.fresh-operator/cause
                               (ex-message error)})))
            path (:seon.dev-cache/path result)]
        (when-not (and (string? path)
                       (fs/absolute? path)
                       (fs/directory? path))
          (fail! "The dependency cache selected an invalid directory."
                 {:seon.dev-cache/path path}))
        result))))

(defn- valid-name!
  [name]
  (when-not (and (string? name)
                 (re-matches cluster-name-pattern name))
    (fail! "Cluster names use letters, digits, `.`, `_`, or `-`."
           {:seon.fresh-operator/name name}))
  name)

(defn- parse-start-arguments
  [arguments]
  (loop [remaining (seq arguments)
         selected {}]
    (if-not remaining
      (merge {:seon.fresh-operator/name "default"} selected)
      (let [argument (first remaining)]
        (cond
          (= "--config" argument)
          (let [path (second remaining)]
            (when (or (str/blank? path)
                      (str/starts-with? path "--"))
              (fail! "`--config` requires a path."
                     {:seon.fresh-operator/arguments arguments}))
            (recur (nnext remaining)
                   (assoc selected
                          :seon.fresh-operator/config-path path)))

          (str/starts-with? argument "--")
          (fail! "Unknown start option."
                 {:seon.fresh-operator/argument argument})

          (:seon.fresh-operator/name selected)
          (fail! "Use `start [CLUSTER] [--config PATH]`."
                 {:seon.fresh-operator/arguments arguments})

          :else
          (recur (next remaining)
                 (assoc selected
                        :seon.fresh-operator/name (valid-name! argument))))))))

(defn- parse-config-apply-arguments
  [arguments]
  (let [[name path]
        (case (count arguments)
          1 ["default" (first arguments)]
          2 [(valid-name! (first arguments)) (second arguments)]
          (fail! "Use `config apply [CLUSTER] PATH`."
                 {:seon.fresh-operator/arguments arguments}))]
    (when (str/blank? path)
      (fail! "The config path must not be blank."
             {:seon.fresh-operator/arguments arguments}))
    {:seon.fresh-operator/name name
     :seon.fresh-operator/config-path path}))

(defn- parse-stop-arguments
  [arguments]
  (loop [remaining (seq arguments)
         selected {:seon.fresh-operator/force? false}]
    (if-not remaining
      selected
      (let [argument (first remaining)]
        (cond
          (= "--force" argument)
          (recur (next remaining)
                 (assoc selected :seon.fresh-operator/force? true))

          (str/starts-with? argument "--")
          (fail! "Unknown stop option."
                 {:seon.fresh-operator/argument argument})

          (:seon.fresh-operator/name selected)
          (fail! "Use `stop [--force] [NAME]`."
                 {:seon.fresh-operator/arguments arguments})

          :else
          (recur (next remaining)
                 (assoc selected
                        :seon.fresh-operator/name
                        (valid-name! argument))))))))

(defn- parse-down-arguments
  [arguments]
  (case arguments
    [] {:seon.fresh-operator/force? false}
    ["--force"] {:seon.fresh-operator/force? true}
    (fail! "Use `down [--force]`; `down` always stops every recorded JVM."
           {:seon.fresh-operator/arguments arguments})))

(defn- parse-reset-arguments
  [arguments]
  (when-not (= ["--force"] arguments)
    (fail! "Use `reset --force`; reset destroys this operator root."
           {:seon.fresh-operator/arguments arguments}))
  {:seon.fresh-operator/force? true})

(defn- parse-init-arguments
  [arguments]
  (if (= "--changed" (first arguments))
    (let [paths (vec (rest arguments))]
      (when (or (empty? paths) (some str/blank? paths))
        (fail! "Use `init --changed PATH...`."
               {:seon.fresh-operator/arguments arguments}))
      {:seon.fresh-operator/changed-paths paths
       :seon.fresh-operator/force? false})
    (case (count arguments)
      0
      {:seon.fresh-operator/force? false}

      1
      (let [name (first arguments)]
        (when (= "--force" name)
          (fail! "Use `init [NAME [--force]]`."
                 {:seon.fresh-operator/arguments arguments}))
        {:seon.fresh-operator/name (valid-name! name)
         :seon.fresh-operator/force? false})

      2
      (let [[name option] arguments]
        (when-not (= "--force" option)
          (fail! "Use `init [NAME [--force]]`."
                 {:seon.fresh-operator/arguments arguments}))
        {:seon.fresh-operator/name (valid-name! name)
         :seon.fresh-operator/force? true})

      (fail! "Use `init`, `init --changed PATH...`, or `init NAME [--force]`."
             {:seon.fresh-operator/arguments arguments}))))

(defn- sparse-manifest
  [root path]
  (let [selected (fs/path path)
        selected (if (fs/absolute? selected)
                   selected
                   (fs/path root selected))]
    (when-not (fs/regular-file? selected)
      (fail! "The selected config manifest does not exist."
             {:seon.config/path (str selected)}))
    (let [manifest (edn/read-string (slurp (str selected)))]
      (when-not (map? manifest)
        (fail! "The selected config manifest must be one EDN map."
               {:seon.config/path (str selected)}))
      manifest)))

(defn- process-start-instant
  [pid]
  (try
    (let [optional (java.lang.ProcessHandle/of (long pid))]
      (when (.isPresent optional)
        (let [handle (.get optional)
              start (.startInstant (.info handle))]
          (when (and (.isAlive handle) (.isPresent start))
            (.get start)))))
    (catch Throwable _
      nil)))

(defn- alive?
  [advertisement]
  (let [recorded (:seon.boot/start-instant advertisement)
        current (process-start-instant (:seon.boot/pid advertisement))]
    (and (inst? recorded)
         current
         (= (inst-ms recorded)
            (.toEpochMilli ^Instant current)))))

(defn- read-advertisement-path
  [path]
  (try
    (let [value (edn/read-string (slurp (str path)))]
      (when (map? value) value))
    (catch Throwable _
      nil)))

(defn- advertisement
  [root name]
  (read-advertisement-path (advertisement-path root name)))

(defn- advertisement-observations
  [root]
  (let [root (.getCanonicalPath (java.io.File. root))
        directory (cluster-root root)]
    (if-not (fs/directory? directory)
      []
      (->> (fs/list-dir directory)
           (keep
            (fn [cluster-dir]
              (when (fs/directory? cluster-dir)
                (let [path (fs/path cluster-dir "prepl.edn")
                      advertised? (fs/regular-file? path)]
                  (when advertised?
                    (let [value (read-advertisement-path path)]
                      {:seon.fresh-operator/name
                       (str (fs/file-name cluster-dir))
                       :seon.fresh-operator/root root
                       :seon.fresh-operator/path (str path)
                       :seon.fresh-operator/advertised? true
                       :seon.fresh-operator/advertisement value
                       :seon.fresh-operator/process-alive?
                       (boolean (alive? value))}))))))
           (sort-by :seon.fresh-operator/name)
           vec))))

(defn- optional-value
  [optional]
  (when (.isPresent optional)
    (.get optional)))

(defn- operator-launch-process?
  [^java.lang.ProcessHandle handle]
  (let [info (.info handle)
        command (optional-value (.command info))
        arguments (some-> (optional-value (.arguments info)) vec)
        [main option form] (take-last 3 arguments)]
    (and (.isAlive handle)
         (string? command)
         (str/ends-with? command "/java")
         (= "clojure.main" main)
         (= "-e" option)
         (string? form)
         (str/includes? form "(seon.cluster/start!")
         (str/includes? form "\"ready — instrumented\"")
         (str/includes? form
                        "(clojure.core/deref (clojure.core/promise))"))))

(defn- legacy-operator-arguments?
  "True for the retired multi-process JVM roles that a fresh operator must
   report instead of losing outside its advertisement census."
  [arguments]
  (let [arguments (vec arguments)]
    (boolean
     (or
      (some
       (fn [argument]
         (and
          (string? argument)
          (some #(str/includes? argument %)
                ["seon.web.server"
                 "seon.host"
                 "seon-database-server-standalone.jar"])))
       arguments)
      (and (some #{"shadow.cljs.devtools.cli"} arguments)
           (some #{"watch"} arguments))))))

(defn- operator-process?
  [^java.lang.ProcessHandle handle]
  (or
   (operator-launch-process? handle)
   (let [info (.info handle)
         command (optional-value (.command info))
         arguments (some-> (optional-value (.arguments info)) vec)]
     (and (.isAlive handle)
          (string? command)
          (str/ends-with? command "/java")
          (legacy-operator-arguments? arguments)))))

(defn- seon-cluster-jvm?
  [^java.lang.ProcessHandle handle]
  (let [info (.info handle)
        command (optional-value (.command info))
        arguments (some-> (optional-value (.arguments info)) vec)
        form (last arguments)]
    (and (.isAlive handle)
         (string? command)
         (str/ends-with? command "/java")
         (string? form)
         (str/includes? form "seon.cluster")
         (str/includes? form "start!"))))

(defn- live-process-handle
  [pid]
  (try
    (let [optional (java.lang.ProcessHandle/of (long pid))]
      (when (.isPresent optional)
        (let [handle (.get optional)]
          (when (.isAlive handle) handle))))
    (catch Throwable _
      nil)))

(defn- process-property
  [^java.lang.ProcessHandle handle property-name]
  (let [arguments (some-> (optional-value (.arguments (.info handle))) vec)]
    (some
     (fn [argument]
       (let [prefix (str "-D" property-name "=")]
         (when (str/starts-with? argument prefix)
           (subs argument (count prefix)))))
     arguments)))

(defn- process-root-property
  [handle]
  (process-property handle "seon.operator.root"))

(defn- proc-working-directory
  [pid]
  (try
    (let [path (java.nio.file.Path/of
                (str "/proc/" pid "/cwd")
                (make-array String 0))]
      (when (java.nio.file.Files/isSymbolicLink path)
        (str (java.nio.file.Files/readSymbolicLink path))))
    (catch Throwable _
      nil)))

(defn- lsof-working-directory
  [pid]
  (try
    (let [process
          (.start
           (ProcessBuilder.
            ^java.util.List
            ["lsof" "-a" "-p" (str pid) "-d" "cwd" "-Fn"]))
          completed? (.waitFor process 3 TimeUnit/SECONDS)]
      (when-not completed?
        (.destroyForcibly process))
      (when completed?
        (some
         #(when (str/starts-with? % "n") (subs % 1))
         (str/split-lines (slurp (.getInputStream process))))))
    (catch Throwable _
      nil)))

(defn- process-root
  [^java.lang.ProcessHandle handle]
  (some-> (or (process-root-property handle)
              (proc-working-directory (.pid handle))
              (lsof-working-directory (.pid handle)))
          java.io.File.
          .getCanonicalPath))

(defn- operator-process-observations
  []
  (with-open [processes (java.lang.ProcessHandle/allProcesses)]
    (->> (iterator-seq (.iterator processes))
         (filter operator-process?)
         (keep
          (fn [^java.lang.ProcessHandle handle]
            (when-let [start (process-start-instant (.pid handle))]
              (let [generation-text
                    (process-property handle "seon.operator.generation")]
                {:seon.fresh-operator/root (process-root handle)
                 :seon.fresh-operator/generation
                 (when generation-text
                   (try (parse-uuid generation-text)
                        (catch Throwable _ nil)))
                 :seon.fresh-operator/log
                 (process-property handle "seon.operator.log")
                 :seon.fresh-operator/cache-path
                 (process-property handle "seon.dependency-cache.path")
                 :seon.fresh-operator/process
                 {:seon.boot/pid (.pid handle)
                  :seon.boot/start-instant (java.util.Date/from start)
                  :seon.fresh-operator/alive? true}}))))
         (sort-by #(get-in % [:seon.fresh-operator/process :seon.boot/pid]))
         vec)))

(defn- observation->process-record
  [observation]
  (when-let [root (:seon.fresh-operator/root observation)]
    (let [pid
          (get-in observation [:seon.fresh-operator/process :seon.boot/pid])
          start-instant
          (get-in observation
                  [:seon.fresh-operator/process
                   :seon.boot/start-instant])
          generation
          (or
           (:seon.fresh-operator/generation observation)
           (java.util.UUID/nameUUIDFromBytes
            (.getBytes (str root "\u0000" pid "\u0000" start-instant)
                       java.nio.charset.StandardCharsets/UTF_8)))]
      {:seon.operator.process-record/generation generation
       :seon.boot/pid pid
       :seon.boot/start-instant start-instant
       :seon.operator.process-record/root root
       :seon.operator.process-record/cache-path
       (:seon.fresh-operator/cache-path observation)
       :seon.operator.process-record/log
       (or (:seon.fresh-operator/log observation)
           (str (fs/path root "data" "clusters" "logs"
                         (str "recovered-" pid ".log"))))})))

(defn- reconcile-process-records!
  [root]
  (let [canonical-root (.getCanonicalPath (java.io.File. root))
        existing (read-process-records canonical-root)
        generations
        (into #{}
              (map :seon.operator.process-record/generation)
              (:seon.fresh-operator/process-records existing))]
    (doseq [observation (operator-process-observations)
            :when (= canonical-root (:seon.fresh-operator/root observation))
            :let [record (observation->process-record observation)]
            :when (and record
                       (not (contains? generations
                                       (:seon.operator.process-record/generation record))))]
      (write-process-record! canonical-root record))
    (read-process-records canonical-root)))

(defn- branch-cluster-name
  [branch]
  (let [branch-name (some-> branch name)]
    (when (and branch-name (str/starts-with? branch-name "cluster-"))
      (subs branch-name (count "cluster-")))))

(defn- jvm-snapshot-form
  []
  (pr-str
   '(do
      (require 'datahike.connections
               'seon.cluster
               'seon.cluster.process
               'seon.cluster.registry)
      (let [instances#
            @@(ns-resolve 'seon.cluster (symbol "running-instances"))
            configured-cluster-root#
            (fn [instance#]
              (some->
               (get-in instance# [:seon.boot/config :seon.boot/root])
               java.io.File.
               .getCanonicalFile))
            operator-root#
            (fn [instance#]
              (some-> (configured-cluster-root# instance#)
                      .getParentFile
                      .getParentFile
                      .getCanonicalPath))
            registered-roots#
            (into #{} (keep operator-root#) (vals instances#))
            held-stores#
            (into [] (keep :seon.store/store) (vals instances#))
            process-root#
            (or
             (when (= 1 (count registered-roots#))
               (first registered-roots#))
             (System/getProperty "seon.operator.root")
             (.getCanonicalPath
              (java.io.File. (System/getProperty "user.dir"))))]
        {:seon.fresh-operator/root process-root#
         :seon.fresh-operator/process
         (assoc (seon.cluster.process/current-identity)
                :seon.fresh-operator/alive? true)
         :seon.fresh-operator/registrations
         (into
          []
          (map
           (fn [[cluster-name# instance#]]
             (merge
              {:seon.fresh-operator/name cluster-name#
               :seon.fresh-operator/root
               (or (operator-root# instance#) process-root#)
               :seon.fresh-operator/reserved? (not (map? instance#))}
              (when (map? instance#)
                {:seon.fresh-operator/advertisement
                 (:seon.boot/advertisement instance#)
                 :seon.fresh-operator/configured-root
                 (some-> (configured-cluster-root# instance#)
                         .getCanonicalPath)}))))
          instances#)
         :seon.fresh-operator/branch-connections
         (into
          #{}
          (keep
           (fn [connection-id#]
             (let [branch# (when (vector? connection-id#)
                             (peek connection-id#))]
               (when (keyword? branch#) branch#))))
          (keys @datahike.connections/*connections*))
         :seon.fresh-operator/persisted-branches
         (into
          #{}
          (mapcat
           (fn [store#]
             (seon.cluster.registry/roster store#)))
          held-stores#)
         :seon.fresh-operator/persisted-branches-observed?
         (boolean (seq held-stores#))}))))

(defn- offline-roster-form
  [root]
  (pr-str
   `(do
      (require 'seon.cluster.registry 'seon.cluster.store)
      (let [store#
            (seon.cluster.store/open-store!
             {:seon.store/dir ~(str (fs/path (cluster-root root) "store"))})]
        (try
          (println ~roster-result-prefix
                   (pr-str (seon.cluster.registry/roster store#)))
          (finally
            (seon.cluster.store/release-store! store#)))))))

(defn- offline-roster
  [root]
  (if-not (fs/directory? (fs/path (cluster-root root) "store"))
    #{}
    (let [process
          (start-child-jvm!
           {:seon.fresh-operator/root root
            :seon.fresh-operator/arguments
            ["-e" (offline-roster-form root)]})
          output (slurp (.getInputStream process))
          exit (.waitFor process)]
      (when-not (zero? exit)
        (fail! "The persisted cluster roster could not be read."
               {:seon.fresh-operator/exit exit
                :seon.fresh-operator/output output}))
      (or
       (some
        (fn [line]
          (when (str/starts-with? line roster-result-prefix)
            (edn/read-string (subs line (count roster-result-prefix)))))
        (str/split-lines output))
       (fail! "The persisted cluster roster reader returned no result."
              {:seon.fresh-operator/output output})))))

(declare prepl-eval! terminal-value)

(defn- prepl-value!
  ([advertisement form]
   (prepl-value! advertisement form (operator-silence-backstop-ms {})))
  ([advertisement form timeout-ms]
   (edn/read-string
    (terminal-value (prepl-eval! advertisement form timeout-ms)))))

(defn- process-matches-advertisement?
  [process advertisement]
  (and (= (:seon.boot/pid process)
          (:seon.boot/pid advertisement))
       (inst? (:seon.boot/start-instant process))
       (inst? (:seon.boot/start-instant advertisement))
       (= (inst-ms (:seon.boot/start-instant process))
          (inst-ms (:seon.boot/start-instant advertisement)))))

(defn- probe-advertisement
  [process advertisements]
  (some
   (fn [observation]
     (let [value (:seon.fresh-operator/advertisement observation)]
       (when (and (:seon.fresh-operator/process-alive? observation)
                  (process-matches-advertisement? process value))
         value)))
   advertisements))

(defn- observe-jvm
  [process-observation advertisements]
  (let [process (:seon.fresh-operator/process process-observation)
        probe (probe-advertisement process advertisements)]
    (if-not probe
      (assoc process-observation :seon.fresh-operator/reachable? false)
      (try
        (merge
         process-observation
         (prepl-value! probe (jvm-snapshot-form)
                       (operator-silence-backstop-ms {}))
         {:seon.fresh-operator/probe-advertisement probe
          :seon.fresh-operator/reachable? true})
        (catch Throwable error
          (assoc process-observation
                 :seon.fresh-operator/probe-advertisement probe
                 :seon.fresh-operator/reachable? false
                 :seon.fresh-operator/error (ex-message error)))))))

(defn- source-observations
  ([root]
   (source-observations
    root {:seon.fresh-operator/probe-jvms? true}))
  ([root options]
   (let [root (.getCanonicalPath (java.io.File. root))
        probe-jvms?
        (not= false (:seon.fresh-operator/probe-jvms? options))
        {:seon.fresh-operator/keys
         [process-records process-record-errors]}
        (reconcile-process-records! root)
        recorded-processes
        (into
         []
         (map
          (fn [record]
            {:seon.fresh-operator/root (:seon.operator.process-record/root record)
             :seon.fresh-operator/process-record record
             :seon.fresh-operator/process (record->boot-process record)}))
         process-records)
        discovered-processes
        (filterv
         #(= root (:seon.fresh-operator/root %))
         (operator-process-observations))
        ;; An operator root is sovereign. Cross-root JVMs and advertisements
        ;; are outside this invocation's observation graph, not merely filtered
        ;; from its destructive selection after probing.
        roots #{root}
        advertisements (into [] (mapcat advertisement-observations) roots)
        advertised-processes
        (into
         []
         (keep
          (fn [observation]
            (when-let [handle
                       (and
                        (:seon.fresh-operator/process-alive? observation)
                        (live-process-handle
                         (get-in observation
                                 [:seon.fresh-operator/advertisement
                                  :seon.boot/pid])))]
              (when (seon-cluster-jvm? handle)
                {:seon.fresh-operator/root
                 (:seon.fresh-operator/root observation)
                 :seon.fresh-operator/process
                 (assoc
                  (select-keys
                   (:seon.fresh-operator/advertisement observation)
                   [:seon.boot/pid :seon.boot/start-instant])
                  :seon.fresh-operator/alive? true)}))))
         advertisements)
        processes
        (->> (concat discovered-processes advertised-processes
                     recorded-processes)
             (reduce
              (fn [by-process process]
                (assoc
                 by-process
                 [(get-in process [:seon.fresh-operator/process
                                   :seon.boot/pid])
                  (get-in process [:seon.fresh-operator/process
                                   :seon.boot/start-instant])]
                 process))
              {})
             vals
             (sort-by #(get-in % [:seon.fresh-operator/process
                                  :seon.boot/pid]))
             vec)
        jvms
        (into
         []
         (map #(if probe-jvms?
                 (observe-jvm % advertisements)
                 (assoc % :seon.fresh-operator/reachable? false)))
         processes)]
    {:seon.fresh-operator/advertisements advertisements
     :seon.fresh-operator/jvms jvms
     :seon.fresh-operator/process-records process-records
     :seon.fresh-operator/process-record-errors process-record-errors})))

(defn- expected-branch
  [cluster-name]
  (keyword (str "cluster-" cluster-name)))

(defn- inconsistency-values
  [name advertisement-observations registrations branch-jvms]
  (cond-> []
    (some #(and (:seon.fresh-operator/advertised? %)
                (not (:seon.fresh-operator/process-alive? %)))
          advertisement-observations)
    (conj :seon.fresh-operator/stale-advertisement)

    (some
     #(not= name
            (get-in % [:seon.fresh-operator/advertisement
                       :seon.boot/cluster-name]))
     (filter :seon.fresh-operator/advertisement
             advertisement-observations))
    (conj :seon.fresh-operator/misnamed-advertisement)

    (and (seq registrations) (empty? advertisement-observations))
    (conj :seon.fresh-operator/missing-advertisement)

    (and (seq branch-jvms) (empty? registrations))
    (conj :seon.fresh-operator/branch-without-registration)

    (and (seq registrations) (empty? branch-jvms))
    (conj :seon.fresh-operator/registration-without-branch)))

(defn- derive-cluster-truth
  [operator-root persisted-branches advertisements jvms]
  (let [operator-root (.getCanonicalPath (java.io.File. operator-root))
        registrations
        (into [] (mapcat :seon.fresh-operator/registrations) jvms)
        branch-pairs
        (into
         #{}
         (mapcat
          (fn [{root :seon.fresh-operator/root
                branches :seon.fresh-operator/branch-connections}]
            (keep
             (fn [branch]
               (when-let [cluster-name (branch-cluster-name branch)]
                 [root cluster-name]))
             branches)))
         jvms)
        persisted-pairs
        (into
         #{}
         (keep
          (fn [branch]
            (when-let [cluster-name (branch-cluster-name branch)]
              [operator-root cluster-name])))
         persisted-branches)
        pairs
        (into
         (into branch-pairs persisted-pairs)
         (concat
          (map (juxt :seon.fresh-operator/root
                     :seon.fresh-operator/name)
               advertisements)
          (map (juxt :seon.fresh-operator/root
                     :seon.fresh-operator/name)
               registrations)))]
    (->> pairs
         (map
          (fn [[root name]]
            (let [advertisement-observations
                  (filterv
                   #(and (= root (:seon.fresh-operator/root %))
                         (= name (:seon.fresh-operator/name %)))
                   advertisements)
                  registrations
                  (filterv
                   #(and (= root (:seon.fresh-operator/root %))
                         (= name (:seon.fresh-operator/name %)))
                   registrations)
                  branch (expected-branch name)
                  branch-jvms
                  (filterv
                   #(and (= root (:seon.fresh-operator/root %))
                         (contains?
                          (:seon.fresh-operator/branch-connections %)
                          branch))
                   jvms)
                  owning-jvms
                  (filterv
                   (fn [jvm]
                     (or
                      (some
                       #(process-matches-advertisement?
                         (:seon.fresh-operator/process jvm)
                         (:seon.fresh-operator/advertisement %))
                       advertisement-observations)
                      (some
                       (fn [registration]
                         (and
                          (= root (:seon.fresh-operator/root registration))
                          (= name
                             (:seon.fresh-operator/name registration))))
                       (:seon.fresh-operator/registrations jvm))
                      (and
                       (= root (:seon.fresh-operator/root jvm))
                       (contains?
                        (:seon.fresh-operator/branch-connections jvm)
                        branch))))
                   jvms)
                  advertisement
                  (some :seon.fresh-operator/advertisement
                        advertisement-observations)
                  registered-advertisement
                  (some :seon.fresh-operator/advertisement registrations)
                  transport-advertisement
                  (or
                   (some
                    (fn [observation]
                      (when (:seon.fresh-operator/process-alive? observation)
                        (:seon.fresh-operator/advertisement observation)))
                    advertisement-observations)
                   (some
                    (fn [jvm]
                      (when (:seon.fresh-operator/reachable? jvm)
                        (:seon.fresh-operator/probe-advertisement jvm)))
                    owning-jvms))
                  process
                  (or (some :seon.fresh-operator/process owning-jvms)
                      (when advertisement
                        (select-keys
                         advertisement
                         [:seon.boot/pid :seon.boot/start-instant])))
                  persisted?
                  (contains? persisted-branches branch)
                  inconsistencies
                  (inconsistency-values
                   name advertisement-observations registrations branch-jvms)]
              {:seon.fresh-operator/name name
               :seon.fresh-operator/root root
               :seon.fresh-operator/operator-root? (= operator-root root)
               :seon.fresh-operator/advertised?
               (boolean (seq advertisement-observations))
               :seon.fresh-operator/advertisement advertisement
               :seon.fresh-operator/advertisement-observations
               advertisement-observations
               :seon.fresh-operator/registered?
               (boolean (seq registrations))
               :seon.fresh-operator/registrations registrations
               :seon.fresh-operator/branch-open?
               (boolean (seq branch-jvms))
               :seon.fresh-operator/persisted? persisted?
               :seon.fresh-operator/branch branch
               :seon.fresh-operator/owning-jvms owning-jvms
               :seon.fresh-operator/process process
               :seon.fresh-operator/process-alive?
               (boolean
                (or
                 (some :seon.fresh-operator/process-alive?
                       advertisement-observations)
                 (some
                  #(get-in % [:seon.fresh-operator/process
                              :seon.fresh-operator/alive?])
                  owning-jvms)))
               :seon.fresh-operator/reachable?
               (boolean
                (some :seon.fresh-operator/reachable? owning-jvms))
               :seon.fresh-operator/registered-advertisement
               registered-advertisement
               :seon.fresh-operator/transport-advertisement
               transport-advertisement
               :seon.fresh-operator/inconsistencies inconsistencies})))
         (sort-by (juxt :seon.fresh-operator/root
                        :seon.fresh-operator/name))
         vec)))

(defn- cluster-truth
  ([root]
   (cluster-truth
    root {:seon.fresh-operator/read-offline-roster? true}))
  ([root {:seon.fresh-operator/keys [read-offline-roster? probe-jvms?]}]
   (let [{:seon.fresh-operator/keys
          [advertisements jvms process-records process-record-errors]}
         (if (nil? probe-jvms?)
           (source-observations root)
           (source-observations
            root {:seon.fresh-operator/probe-jvms? probe-jvms?}))
         canonical-root (.getCanonicalPath (java.io.File. root))
         live-roster
         (some
          (fn [jvm]
            (when (and (= canonical-root
                          (:seon.fresh-operator/root jvm))
                       (:seon.fresh-operator/reachable? jvm)
                       (:seon.fresh-operator/persisted-branches-observed?
                        jvm))
              (:seon.fresh-operator/persisted-branches jvm)))
          jvms)
         live-recorded-process?
         (boolean
          (some
           #(and (= canonical-root (:seon.fresh-operator/root %))
                 (:seon.fresh-operator/process-record %)
                 (get-in % [:seon.fresh-operator/process
                            :seon.fresh-operator/alive?]))
           jvms))
         roster-observation
         (cond
           live-roster
           {:seon.fresh-operator/roster-readable? true
            :seon.fresh-operator/roster-source :live-jvm
            :seon.fresh-operator/persisted-branches live-roster}

           live-recorded-process?
           {:seon.fresh-operator/roster-readable? false
            :seon.fresh-operator/roster-source :recorded-process
            :seon.fresh-operator/roster-error
            (str "A recorded JVM is alive but its prepl is unreachable; "
                 "the offline reader was not allowed to contend for its flock.")}

           (not read-offline-roster?)
           {:seon.fresh-operator/roster-readable? false
            :seon.fresh-operator/roster-source :skipped}

           :else
           (try
             {:seon.fresh-operator/roster-readable? true
              :seon.fresh-operator/roster-source :offline-jvm
              :seon.fresh-operator/persisted-branches
              (offline-roster canonical-root)}
             (catch Throwable error
               {:seon.fresh-operator/roster-readable? false
                :seon.fresh-operator/roster-source :offline-jvm
                :seon.fresh-operator/roster-error (ex-message error)
                :seon.fresh-operator/roster-error-data (ex-data error)})))
         persisted-branches
         (or (:seon.fresh-operator/persisted-branches roster-observation) #{})
         truth
         (derive-cluster-truth canonical-root persisted-branches
                               advertisements jvms)]
     (with-meta
       truth
       {:seon.fresh-operator/roster roster-observation
        :seon.fresh-operator/process-records process-records
        :seon.fresh-operator/process-record-errors process-record-errors}))))

(defn- own-cluster-truth
  [truth]
  (filterv :seon.fresh-operator/operator-root? truth))

(defn- existing-names
  [truth]
  (into []
        (comp
         (filter :seon.fresh-operator/operator-root?)
         (filter :seon.fresh-operator/persisted?)
         (map :seon.fresh-operator/name)
         (distinct))
        truth))

(defn- destructive-names
  [truth]
  (into []
        (comp
         (filter :seon.fresh-operator/operator-root?)
         (filter #(or (:seon.fresh-operator/persisted? %)
                      (:seon.fresh-operator/advertised? %)
                      (:seon.fresh-operator/registered? %)
                      (:seon.fresh-operator/branch-open? %)
                      (:seon.fresh-operator/process-alive? %)))
         (map :seon.fresh-operator/name)
         (distinct))
        truth))

(defn- select-anchor
  [truth]
  (first
   (filter
    #(and (:seon.fresh-operator/operator-root? %)
          (:seon.fresh-operator/registered? %)
          (:seon.fresh-operator/process-alive? %)
          (:seon.fresh-operator/reachable? %)
          (:seon.fresh-operator/transport-advertisement %))
    truth)))

(defn- named-cluster-row
  [truth name]
  (or
   (some
    #(when (and (:seon.fresh-operator/operator-root? %)
                (= name (:seon.fresh-operator/name %)))
       %)
    truth)
   (when-let [foreign
              (seq
               (into
                []
                (comp
                 (remove :seon.fresh-operator/operator-root?)
                 (filter #(= name (:seon.fresh-operator/name %)))
                 (map :seon.fresh-operator/root)
                 (distinct))
                truth))]
     (fail!
      (str "Cluster " name " belongs to foreign operator "
           (if (= 1 (count foreign)) "root " "roots ")
           (str/join ", " foreign) ".")
      {:seon.fresh-operator/name name
       :seon.fresh-operator/foreign-roots foreign}))
   (fail!
    (str "Cluster " name " does not exist in this operator root"
         (when-let [names (seq (existing-names truth))]
           (str "; existing clusters: " (str/join ", " names)))
         ".")
    {:seon.fresh-operator/name name
     :seon.fresh-operator/existing (existing-names truth)})))

(defn- select-destructive-name
  [truth requested-name command]
  (if requested-name
    (:seon.fresh-operator/name
     (named-cluster-row truth (valid-name! requested-name)))
    (let [candidates (destructive-names truth)]
      (case (count candidates)
        0 (fail! (str "There are no clusters to " command ".")
                 {:seon.fresh-operator/candidates []
                  :seon.fresh-operator/command command})
        1 (first candidates)
        (fail!
         (str "Refusing ambiguous `" command
              "` because it destroys cluster data; "
              "name one cluster: " (str/join ", " candidates) ".")
         {:seon.fresh-operator/candidates candidates
          :seon.fresh-operator/command command})))))

(defn- repair-actions
  [truth]
  (into
   []
   (mapcat
    (fn [row]
      (let [inconsistencies
            (set (:seon.fresh-operator/inconsistencies row))
            stale?
            (or
             (contains?
              inconsistencies
              :seon.fresh-operator/stale-advertisement)
             (contains?
              inconsistencies
              :seon.fresh-operator/misnamed-advertisement))
            remove-observations
            (when stale?
              (:seon.fresh-operator/advertisement-observations row))
            restore-advertisement
            (when (and
                   (contains?
                    inconsistencies
                    :seon.fresh-operator/missing-advertisement)
                   (alive?
                    (:seon.fresh-operator/registered-advertisement row)))
              (:seon.fresh-operator/registered-advertisement row))]
        (concat
         (map
          (fn [observation]
            {:seon.fresh-operator/action :delete-advertisement
             :seon.fresh-operator/name
             (:seon.fresh-operator/name row)
             :seon.fresh-operator/path
             (:seon.fresh-operator/path observation)})
          remove-observations)
         (when restore-advertisement
           [{:seon.fresh-operator/action :write-advertisement
             :seon.fresh-operator/name
             (:seon.fresh-operator/name row)
             :seon.fresh-operator/root
             (:seon.fresh-operator/root row)
             :seon.fresh-operator/advertisement
             restore-advertisement}])))))
   (own-cluster-truth truth)))

(defn- apply-repair!
  [{:seon.fresh-operator/keys [action name path root advertisement]}]
  (case action
    :delete-advertisement
    (do
      (fs/delete-if-exists path)
      (println (str "↻ repaired " name ": removed stale advertisement")))

    :write-advertisement
    (let [path (advertisement-path root name)]
      (fs/create-dirs (fs/parent path))
      (spit (str path) (str (pr-str advertisement) "\n"))
      (println (str "↻ repaired " name ": restored advertisement "
                    "from the live JVM registry")))))

(defn- reconciled-truth!
  ([root]
   (reconciled-truth!
    root {:seon.fresh-operator/read-offline-roster? true}))
  ([root truth-options]
   (loop [truth (cluster-truth root truth-options)]
     (let [repairs (repair-actions truth)]
       (if (seq repairs)
         (do
           (doseq [repair repairs]
             (apply-repair! repair))
           (recur (cluster-truth root truth-options)))
         truth)))))

(defn- require-live-row!
  [truth name]
  (let [row (named-cluster-row truth (valid-name! name))]
    (when-not (:seon.fresh-operator/process-alive? row)
      (fail! "The cluster has no live process."
             {:seon.fresh-operator/name name
              :seon.fresh-operator/inconsistencies
              (:seon.fresh-operator/inconsistencies row)}))
    row))

(defn- selected-name
  [_root argument]
  (valid-name! (or argument "default")))

(defn- prepl-eval!
  ([advertisement form]
   (prepl-eval! advertisement form (operator-silence-backstop-ms {})))
  ([advertisement form timeout-ms]
   (prepl-eval! advertisement form timeout-ms (constantly nil)))
  ([advertisement form timeout-ms observe!]
   (with-open [socket (Socket.)]
     (try
       (.connect socket
                 (InetSocketAddress.
                  ^String (:seon.boot/prepl-host advertisement)
                  (int (:seon.boot/prepl-port advertisement)))
                 timeout-ms)
       (catch SocketTimeoutException _
         (report-silence-backstop! "prepl connection" timeout-ms)
         (fail! (str "The prepl connection went silent for " timeout-ms
                     " ms.")
                {:seon.error/kind
                 :seon.fresh-operator/prepl-connection-silent
                 :seon.fresh-operator/phase :prepl-connection
                 :seon.fresh-operator/silence-backstop-ms timeout-ms
                 :seon.config/attribute
                 :seon.config.operator/event-silence-backstop-ms})))
     (.setSoTimeout socket timeout-ms)
     (with-open [writer (java.io.OutputStreamWriter.
                         (.getOutputStream socket)
                         java.nio.charset.StandardCharsets/UTF_8)
                 reader (PushbackReader.
                         (java.io.InputStreamReader.
                          (.getInputStream socket)
                          java.nio.charset.StandardCharsets/UTF_8))]
       (.write writer form)
       (.write writer "\n")
       (.flush writer)
       (try
         (loop [events []]
           (let [event (edn/read {:eof ::eof} reader)]
             (observe! event)
             (cond
               (= ::eof event)
               (fail! "The cluster closed its prepl before returning."
                      {:seon.fresh-operator/events events})

               (not (map? event))
               (fail! "The cluster returned malformed prepl data."
                      {:seon.fresh-operator/event event})

               (= :ret (:tag event))
               (let [events (conj events event)]
                 (when (:exception event)
                   (fail! "The cluster rejected the prepl operation."
                          {:seon.fresh-operator/events events}))
                 events)

               :else
               (recur (conj events event)))))
         (catch SocketTimeoutException _
           (report-silence-backstop! "prepl response" timeout-ms)
           (fail! (str "The prepl response went silent for " timeout-ms
                       " ms.")
                  {:seon.error/kind
                   :seon.fresh-operator/prepl-response-silent
                   :seon.fresh-operator/phase :prepl-response
                   :seon.fresh-operator/silence-backstop-ms timeout-ms
                   :seon.config/attribute
                   :seon.config.operator/event-silence-backstop-ms})))))))

(defn- terminal-value
  [events]
  (:val (peek events)))

(defn- instrument-form
  [instance-symbol name]
  `(let [dials#
         ((ns-resolve 'seon.config (symbol "effective"))
          @(get ~instance-symbol :seon.boot/cluster-connection)
          ~name)]
     ((ns-resolve 'seon.instrument (symbol "apply!"))
      {:seon.config/on-core-error
       (:seon.config/on-core-error dials#)
       :seon.sci.admit/caps
       (select-keys
        dials#
        [:seon.config.eval.result/max-depth
         :seon.config.eval.result/max-collection
         :seon.config.eval.result/max-string
         :seon.config.eval.result/max-nodes])})))

(defn- refresh-instrument-form
  []
  (let [instances (gensym "instances")
        anchor (gensym "anchor")
        anchor-name (gensym "anchor-name")]
    `(let [~instances
           @@(ns-resolve 'seon.cluster (symbol "running-instances"))
           ~anchor
           (first
            (filter
             (fn [instance#]
               (and (map? instance#)
                    (:seon.boot/cluster-connection instance#)))
             (vals ~instances)))
           ~anchor-name
           (get-in ~anchor
                   [:seon.boot/advertisement :seon.boot/cluster-name])]
       (when ~anchor
         ~(instrument-form anchor anchor-name)))))

(defn- launch-form
  [root name manifest ready-port]
  (let [instance (gensym "instance")]
    (pr-str
     `(do
        (with-open [socket# (java.net.Socket. "127.0.0.1" ~ready-port)
                    writer# (java.io.OutputStreamWriter.
                             (.getOutputStream socket#)
                             java.nio.charset.StandardCharsets/UTF_8)]
          (try
            (let [progress!#
                  ;; The socket line drives the waiting operator; the
                  ;; stdout line lands in the cluster log so a boot that
                  ;; never reaches readiness still says how far it got.
                  (fn [phase#]
                    (println (str "boot phase: "
                                  (clojure.core/name phase#)))
                    (flush)
                    (.write writer# (str (clojure.core/name phase#) "\n"))
                    (.flush writer#))]
              (progress!# :seon.boot.phase/namespaces)
              (require 'seon.cluster 'seon.config 'seon.instrument)
              (let [progress-var#
                    (ns-resolve 'seon.cluster (symbol "*boot-progress!*"))
                    ~instance
                    (with-bindings
                      {progress-var# progress!#}
                      ((ns-resolve 'seon.cluster (symbol "start!"))
                       {:seon.boot/root ~(str (cluster-root root))
                        :seon.boot/cluster-name ~name
                        :seon.config/manifest ~manifest}))
                    _# (require 'seon.operator)
                    dials#
                    ((ns-resolve 'seon.config (symbol "effective"))
                     @(get ~instance :seon.boot/cluster-connection)
                     ~name)
                    rotation#
                    ((ns-resolve 'seon.operator (symbol "rotate-logs!"))
                     {:seon.boot/log-dir
                      (get-in ~instance [:seon.boot/config :seon.boot/log-dir])
                      :seon.config.maintenance/log-max-bytes
                      (:seon.config.maintenance/log-max-bytes dials#)
                      :seon.config.maintenance/log-retained-files
                      (:seon.config.maintenance/log-retained-files dials#)})
                    applied# ~(instrument-form instance name)]
                (when (:seon.error/kind rotation#)
                  (throw (ex-info (:seon.error/message rotation#) rotation#)))
                (println "seon" ~name "ready — instrumented"
                         (:seon.instrument/instrumented applied#) "vars")
                (flush)
                (.write writer# "ready\n")
                (.flush writer#)))
            (catch Throwable failure#
              (let [face#
                    (clojure.core/pr-str
                     {:seon.fresh-operator/event :failure
                      :seon.fresh-operator/message
                      (or (clojure.core/ex-message failure#) (str failure#))
                      :seon.fresh-operator/error-kind
                      (:seon.error/kind (clojure.core/ex-data failure#))})]
                (println (str "boot failure: " face#))
                (flush)
                (.write writer# (str face# "\n"))
                (.flush writer#))
              (throw failure#))))
        @(promise)))))

(defn- add-form
  [root name manifest]
  (let [instance (gensym "instance")]
    (pr-str
     `(do
        (require 'seon.cluster
                 'seon.config
                 'seon.instrument)
        ;; The live JVM may still carry wrappers compiled from a schema
        ;; that source reload has replaced. Refresh against one already
        ;; running cluster's effective dial BEFORE current `start!`
        ;; enters that process-global wrapper. The selected new cluster
        ;; still reapplies its own dial after its config facts commit.
        ~(refresh-instrument-form)
        (let [progress-var#
              (ns-resolve 'seon.cluster (symbol "*boot-progress!*"))
              progress!#
              (fn [phase#]
                (println (str "● " ~name " boot: "
                              (clojure.core/name phase#)))
                (flush))
              ~instance
              (with-bindings
                {progress-var# progress!#}
                ((ns-resolve 'seon.cluster (symbol "start!"))
                 {:seon.boot/root ~(str (cluster-root root))
                  :seon.boot/cluster-name ~name
                  :seon.config/manifest ~manifest}))
              applied# ~(instrument-form instance name)]
          (println "seon" ~name "added — instrumented"
                   (:seon.instrument/instrumented applied#) "vars")
          ~name)))))

(defn- create-log!
  {:seon.fn/external-sink :codec-storage
   :seon.fn/projection-boundary :none}
  [root name]
  (let [path (log-path root name)]
    (fs/create-dirs (fs/parent path))
    (when-not (fs/exists? path)
      (spit (str path) ""))
    path))

(defn- launch!
  [root name manifest ready-port adoption-port silence-ms
   dependency-cache-path]
  (let [_ (operator.state/claim-root-under-lock!
           (repository-root) root false name)
        log (create-log! root name)
        generation (random-uuid)
        process
        (start-child-jvm!
         {:seon.fresh-operator/root root
          :seon.fresh-operator/dependency-cache-path dependency-cache-path
          :seon.fresh-operator/jvm-options
          [(str "-J-Dseon.operator.generation=" generation)
           (str "-J-Dseon.operator.log=" log)]
          :seon.fresh-operator/arguments
          ["-e" (launch-form root name manifest ready-port)]
          :seon.fresh-operator/detach
          {:seon.fresh-operator/log (str log)
           :seon.fresh-operator/adoption-port adoption-port
           :seon.fresh-operator/silence-backstop-ms silence-ms}})
        output (str/trim (slurp (.getInputStream process)))
        exit (.waitFor process)]
    (when-not (zero? exit)
      (fail! "The detached cluster launcher failed."
             {:seon.fresh-operator/exit exit
              :seon.fresh-operator/output output}))
    {:seon.fresh-operator/generation generation
     :seon.fresh-operator/pid (parse-long output)
     :seon.fresh-operator/cache-path dependency-cache-path
     :seon.fresh-operator/log (str log)}))

(defn- record-launched-process!
  [root ^ServerSocket adoption-server silence-ms
   {:seon.fresh-operator/keys [generation pid log cache-path]}]
  (let [start-instant (state/process-start-instant pid)]
    (when-not start-instant
      (fail! "The cluster JVM exited before its identity could be recorded."
             {:seon.boot/pid pid
              :seon.fresh-operator/log log}))
    (let [record
          {:seon.operator.process-record/generation generation
           :seon.boot/pid pid
           :seon.boot/start-instant start-instant
           :seon.operator.process-record/root (.getCanonicalPath (java.io.File. root))
           :seon.operator.process-record/cache-path
           (.getCanonicalPath (java.io.File. cache-path))
           :seon.operator.process-record/log log}]
      (try
        (write-process-record! root record)
        (operator.state/mark-root-created-under-lock! (repository-root) root)
        (let [handle
              (or (matching-process-handle record)
                  (fail! "The cluster JVM exited before adoption."
                         {:seon.error/kind
                          :seon.fresh-operator/detached-child-exited
                          :seon.boot/pid pid}))
              accepted
              (CompletableFuture/supplyAsync
               (reify Supplier
                 (get [_]
                   {:seon.fresh-operator/event :adoption-connection
                    :seon.fresh-operator/socket (.accept adoption-server)})))
              exited
              (.thenApply
               (.onExit handle)
               (reify Function
                 (apply [_ _]
                   {:seon.fresh-operator/event :exit})))
              winner
              (try
                (.get (CompletableFuture/anyOf
                       (into-array CompletableFuture [accepted exited]))
                      silence-ms TimeUnit/MILLISECONDS)
                (catch ExecutionException error
                  (throw (.getCause error)))
                (catch TimeoutException _
                  (report-silence-backstop!
                   "detached child adoption connection" silence-ms)
                  (fail!
                   (str "Detached child adoption went silent for " silence-ms
                        " ms.")
                   {:seon.error/kind
                    :seon.fresh-operator/detached-adoption-silent
                    :seon.fresh-operator/phase :detached-adoption
                    :seon.fresh-operator/silence-backstop-ms silence-ms
                    :seon.config/attribute
                    :seon.config.operator/event-silence-backstop-ms})))]
          (when (= :exit (:seon.fresh-operator/event winner))
            (fail! "The cluster JVM exited before adoption."
                   {:seon.error/kind
                    :seon.fresh-operator/detached-child-exited
                    :seon.boot/pid pid}))
          (with-open [socket ^Socket (:seon.fresh-operator/socket winner)
                      output (.getOutputStream socket)]
            (.write output (int 1))
            (.flush output)))
        record
        (catch Throwable error
          (throw
           (ex-info (ex-message error)
                    (assoc (ex-data error)
                           :seon.fresh-operator/process-record record)
                    error)))))))

(defn- signal-recorded-process!
  [^java.lang.ProcessHandle handle force?]
  (if force?
    (.destroyForcibly handle)
    (.destroy handle)))

(defn- terminate-recorded-process!
  "Terminate only the exact recorded process generation."
  ([record]
   (terminate-recorded-process!
    record (operator-silence-backstop-ms {})))
  ([record silence-ms]
    (if-let [handle (matching-process-handle record)]
      (do
        (signal-recorded-process! handle false)
        (try
          (.get (.onExit handle) silence-ms TimeUnit/MILLISECONDS)
          (catch TimeoutException _
            (report-silence-backstop! "exact process exit after SIGTERM"
                                      silence-ms)))
        ;; `onExit` is only notification for a detached/non-child process.
        ;; Re-read the recorded identity before escalation so PID reuse is
        ;; never signaled.
        (if-let [remaining (matching-process-handle record)]
          (do
            (signal-recorded-process! remaining true)
            (try
              (.get (.onExit remaining) silence-ms TimeUnit/MILLISECONDS)
              (catch TimeoutException _
                (report-silence-backstop! "exact process exit after SIGKILL"
                                          silence-ms)))
            (when (matching-process-handle record)
              (fail! "The recorded cluster JVM survived SIGKILL."
                     {:seon.operator.process-record/generation
                      (:seon.operator.process-record/generation record)
                      :seon.boot/pid (:seon.boot/pid record)}))
            :sigkill)
          :sigterm))
      (if (some? (state/process-start-instant
                  (:seon.boot/pid record)))
        :pid-reused
        :already-exited))))

(defn- terminate-observed-process!
  "Terminate the process generation observed at this call site."
  ([pid]
   (terminate-observed-process!
    pid (operator-silence-backstop-ms {})))
  ([pid silence-ms]
   (when-let [start-instant (state/process-start-instant pid)]
     (terminate-recorded-process!
      {:seon.operator.process-record/generation (random-uuid)
       :seon.boot/pid pid
       :seon.boot/start-instant start-instant
       :seon.operator.process-record/root ""
       :seon.operator.process-record/log ""}
      silence-ms))
   nil))

(defn- readiness-failure
  [value]
  (try
    (let [event (edn/read-string value)]
      (when (and (map? event)
                 (= :failure (:seon.fresh-operator/event event))
                 (string? (:seon.fresh-operator/message event)))
        event))
    (catch Throwable _
      nil)))

(defn- process-path-label
  [path]
  (case path
    :sigterm "SIGTERM"
    :sigkill "SIGKILL"
    :pid-reused "pid-reused"
    :already-exited "already-exited"
    (clojure.core/name path)))

(defn- await-advertisement!
  [root name pid ^ServerSocket ready-server silence-ms]
  (let [handle
        (or (live-process-handle pid)
            (fail! "The cluster JVM exited before readiness."
                   {:seon.fresh-operator/name name
                    :seon.boot/pid pid}))
        events (LinkedBlockingQueue.)]
    (CompletableFuture/runAsync
     (reify Runnable
       (run [_]
         (try
           (with-open [socket (.accept ready-server)
                       reader (java.io.BufferedReader.
                               (java.io.InputStreamReader.
                                (.getInputStream socket)
                                java.nio.charset.StandardCharsets/UTF_8))]
             (loop []
               (let [value (.readLine reader)
                     failure (readiness-failure value)
                     event
                     (cond
                       (nil? value)
                       {:seon.fresh-operator/event :closed}

                       (= "ready" value)
                       {:seon.fresh-operator/event :ready}

                       failure
                       failure

                       :else
                       {:seon.fresh-operator/event :phase
                        :seon.fresh-operator/phase value})]
                 (.offer events event)
                 (when (= :phase (:seon.fresh-operator/event event))
                   (recur)))))
           (catch Throwable error
             (.offer events
                     {:seon.fresh-operator/event :reader-error
                      :seon.fresh-operator/cause error}))))))
    (.thenRun
     (.onExit handle)
     (reify Runnable
       (run [_]
         (.offer events {:seon.fresh-operator/event :exit}))))
    (loop [phase "launch"]
      (let [event (.poll events silence-ms TimeUnit/MILLISECONDS)]
        (when-not event
          (report-silence-backstop!
           (str "cluster boot phase " phase) silence-ms)
          (fail! (str "Cluster boot phase " phase " went silent for "
                      silence-ms " ms.")
                 {:seon.error/kind
                  :seon.fresh-operator/boot-phase-silent
                  :seon.fresh-operator/name name
                  :seon.boot/pid pid
                  :seon.fresh-operator/phase phase
                  :seon.fresh-operator/silence-backstop-ms silence-ms
                  :seon.config/attribute
                  :seon.config.operator/event-silence-backstop-ms}))
        (case (:seon.fresh-operator/event event)
          :phase
          (let [next-phase (:seon.fresh-operator/phase event)]
            (println (str "● " name " boot: " next-phase))
            (flush)
            (recur next-phase))

          :ready nil

          :exit
          (fail! "The cluster JVM exited before readiness."
                 {:seon.error/kind
                  :seon.fresh-operator/boot-process-exited
                  :seon.fresh-operator/name name
                  :seon.boot/pid pid
                  :seon.fresh-operator/phase phase})

          :failure
          (fail! (:seon.fresh-operator/message event)
                 (cond->
                  {:seon.fresh-operator/name name
                   :seon.boot/pid pid
                   :seon.fresh-operator/phase phase}
                   (:seon.fresh-operator/error-kind event)
                   (assoc :seon.error/kind
                          (:seon.fresh-operator/error-kind event))))

          :closed
          (fail! "The cluster JVM closed readiness before READY."
                 {:seon.error/kind
                  :seon.fresh-operator/readiness-closed
                  :seon.fresh-operator/name name
                  :seon.boot/pid pid
                  :seon.fresh-operator/phase phase})

          :reader-error
          (throw (:seon.fresh-operator/cause event))

          (fail! "The cluster JVM sent malformed readiness."
                 {:seon.error/kind
                  :seon.fresh-operator/malformed-readiness
                  :seon.fresh-operator/name name
                  :seon.boot/pid pid
                  :seon.fresh-operator/event event})))))
  (let [value (advertisement root name)]
    (when-not (and value (alive? value) (:seon.render.web/url value))
      (fail! "The ready JVM did not publish a complete advertisement."
             {:seon.fresh-operator/name name}))
    value))

(defn- print-started!
  {:seon.fn/external-sink :ai-visible-text
   :seon.fn/projection-boundary :none}
  [root name value]
  (println (format "● %-20s %s  prepl=%s  log=%s"
                   name
                   (:seon.render.web/url value)
                   (:seon.boot/prepl-port value)
                   (log-path root name))))

(defn- start!
  [root arguments]
  (let [{:seon.fresh-operator/keys [name config-path]}
        (parse-start-arguments arguments)
        manifest (if config-path (sparse-manifest root config-path) {})
        silence-ms (operator-silence-backstop-ms manifest)
        truth
        (reconciled-truth!
         root {:seon.fresh-operator/read-offline-roster? false})
        existing
        (some
         #(when (and (:seon.fresh-operator/operator-root? %)
                     (= name (:seon.fresh-operator/name %)))
            %)
         truth)
        anchor (select-anchor truth)
        live-process-records
        (filterv record-alive?
                 (:seon.fresh-operator/process-records (meta truth)))]
    (when (and existing
               (or (:seon.fresh-operator/advertised? existing)
                   (:seon.fresh-operator/registered? existing)
                   (:seon.fresh-operator/branch-open? existing)))
      (fail! "The cluster already has live operator state."
             {:seon.fresh-operator/name name
              :seon.fresh-operator/inconsistencies
              (:seon.fresh-operator/inconsistencies existing)}))
    (when (and (seq live-process-records) (nil? anchor))
      (fail!
       (str "A recorded cluster JVM is alive but unreachable; refusing to "
            "launch another JVM into its flock. Use `down` or `down --force`.")
       {:seon.fresh-operator/process-records live-process-records}))
    (if anchor
      (let [anchor-ad
            (:seon.fresh-operator/transport-advertisement anchor)
            _
            (try
              (prepl-eval!
               anchor-ad
               (add-form root name manifest)
               silence-ms
               (fn [event]
                 (when (= :out (:tag event))
                   (print (:val event))
                   (flush))))
              (catch Throwable error
                ;; A start can fail above the REPL after registering the
                ;; partial instance. Reconcile before returning the failure,
                ;; so the next `stop NAME` addresses that carried value.
                (reconciled-truth! root)
                (throw
                 (ex-info
                  (str (ex-message error)
                       " The partial cluster remains addressable as "
                       name ".")
                  (assoc (ex-data error)
                         :seon.fresh-operator/name name)
                  error))))
            value
            (:seon.fresh-operator/registered-advertisement
             (named-cluster-row (reconciled-truth! root) name))]
        (when-not (and value (alive? value))
          (fail! "The added cluster did not publish live registry state."
                 {:seon.fresh-operator/name name}))
        (create-log! root name)
        (spit (str (log-path root name))
              (str "Cluster " name " joined the JVM advertised by "
                   (:seon.fresh-operator/name anchor)
                   "; that process owns its original stdout/stderr.\n"))
        (print-started! root name value))
      (let [dependency-cache (ensure-dependency-cache!)
            dependency-cache-path (:seon.dev-cache/path dependency-cache)]
        (with-open [ready-server
                    (ServerSocket.
                     0 1 (java.net.InetAddress/getLoopbackAddress))
                    adoption-server
                    (ServerSocket.
                     0 1 (java.net.InetAddress/getLoopbackAddress))]
          (let [launch-result
                (launch! root name manifest
                         (.getLocalPort ready-server)
                         (.getLocalPort adoption-server)
                         silence-ms
                         dependency-cache-path)
                pid (:seon.fresh-operator/pid launch-result)
                record
                (try
                  (record-launched-process!
                   root adoption-server silence-ms launch-result)
                  (catch Throwable failure
                    (when-let [failed-record
                               (:seon.fresh-operator/process-record
                                (ex-data failure))]
                      (terminate-recorded-process! failed-record)
                      (when-not (record-alive? failed-record)
                        (clear-process-record! root failed-record)))
                    (throw failure)))
                value
                (try
                  (await-advertisement!
                   root name pid ready-server silence-ms)
                  (catch Throwable failure
                    (terminate-recorded-process! record)
                    (when-not (record-alive? record)
                      (clear-process-record! root record))
                    (throw failure)))]
            (when-not (process-record-matches-advertisement? record value)
              (terminate-recorded-process! record)
              (when-not (record-alive? record)
                (clear-process-record! root record))
              (fail!
               "The ready advertisement does not match the launched JVM."
               {:seon.operator.process-record/generation
                (:seon.operator.process-record/generation record)
                :seon.fresh-operator/advertisement value}))
            (print-started! root name value)))))))

(defn- config-apply-form
  [name manifest]
  (pr-str
   `(let [instances# @@(ns-resolve 'seon.cluster
                                   (symbol "running-instances"))
          instance# (get instances# ~name)]
      (when-not instance#
        (throw (ex-info "The cluster is not running."
                        {:seon.boot/cluster-name ~name})))
      (seon.config/apply!
       {:seon.db/connection
        (:seon.boot/cluster-connection instance#)
        :seon.boot/cluster-name ~name
        :seon.config/manifest ~manifest}))))

(defn- config!
  [root arguments]
  (when-not (= "apply" (first arguments))
    (fail! "Use `config apply [CLUSTER] PATH`."
           {:seon.fresh-operator/arguments arguments}))
  (let [{:seon.fresh-operator/keys [name config-path]}
        (parse-config-apply-arguments (vec (rest arguments)))
        manifest (sparse-manifest root config-path)
        truth (reconciled-truth! root)
        row (require-live-row! truth name)
        advertisement
        (or (:seon.fresh-operator/transport-advertisement row)
            (fail! "The live cluster has no reachable JVM."
                   {:seon.fresh-operator/name name}))
        result (terminal-value
                (prepl-eval! advertisement
                             (config-apply-form name manifest)))]
    (println (str "● " name " config applied " result))))

(defn- named-init-form
  [root name force?]
  (let [store (gensym "store")
        source (gensym "source")
        instance (gensym "instance")
        branch (gensym "branch")
        request (gensym "request")
        operation
        (if force?
          `(if (map? ~instance)
             (seon.cluster/refork! ~instance)
             (do
               (seon.fs/delete-recursively!
                ~(str (cluster-root root))
                ~(str (cluster-directory root name)))
               (let [result# (seon.cluster.registry/reset-cluster! ~request)]
                 (seon.cluster.registry/collect! ~store (java.util.Date.))
                 result#)))
          `(seon.cluster.registry/ensure-cluster! ~request))]
    `(fn [~store ~source ~instance]
       (let [~branch (seon.cluster.registry/cluster-branch ~name)
             ~request {:seon.store/store ~store
                       :seon.boot/cluster-name ~name
                       :seon.source/commit-id
                       (:seon.source/commit-id ~source)}]
         (when (and (not ~force?)
                    (contains? (seon.cluster.registry/roster ~store) ~branch))
           (throw
            (ex-info
             (str "Cluster `" ~name
                  "` already exists; use `init " ~name
                  " --force` to destroy and refork it.")
             {:seon.fresh-operator/name ~name
              :seon.store/branch ~branch})))
         (assoc ~source
                :seon.fresh-operator/cluster
                ~operation)))))

(defn- init-form
  [root name force? changed-paths source-process?]
  (let [cluster-root (str (cluster-root root))
        changed-paths (mapv #(str (if (fs/absolute? (fs/path %))
                                    (fs/path %)
                                    (fs/path root %)))
                            changed-paths)
        operation
        (cond
          (seq changed-paths)
          `(seon.cluster/refresh-source! ~cluster-root ~changed-paths)

          (not name)
          `(seon.cluster/refresh-source! ~cluster-root)

          source-process?
          `(let [source# (seon.cluster/refresh-source! ~cluster-root)
                 store#
                 (seon.cluster.store/open-store!
                  {:seon.store/dir ~(str (fs/path cluster-root "store"))})]
             (try
               (~(named-init-form root name force?) store# source# nil)
               (finally
                 (seon.cluster.store/release-store! store#))))

          :else
          `(let [source# (seon.cluster/refresh-source! ~cluster-root)
                 instances#
                 @@(ns-resolve 'seon.cluster (symbol "running-instances"))
                 store# (some :seon.store/store (vals instances#))]
             (when-not store#
               (throw
                (ex-info "The live JVM has no process-root store." {})))
             (~(named-init-form root name force?)
              store# source# (get instances# ~name))))]
    (pr-str
     `(do
        ;; The live JVM owns the process-root store lock. Reload the
        ;; source-analysis owners before asking that JVM to publish
        ;; `current-src`; the running clusters and their program facts remain
        ;; untouched because their process state is held in defonce Vars.
        ;; Reload the schema loader before any namespace whose top-level forms
        ;; call `load!`, then reload predicate registration owners before
        ;; admission runs during publication.
        (require 'seon.schema.edn :reload)
        (require 'seon.fn.analyzer :reload)
        ;; `seon.fn` canonicalizes analyzed rows through `seon.program`.
        ;; Reload that owner first so newly recorded program attributes such
        ;; as call edges cannot be dropped by a stale long-lived Var.
        (require 'seon.program :reload)
        (require 'seon.fn :reload)
        ;; A live JVM may already have an older `seon.db` loaded, so
        ;; `requiring-resolve` cannot replay its new load-time registrations
        ;; during schema admission.
        (require 'seon.db :reload)
        (require 'seon.cluster.source :reload)
        ;; Reload the publication owner too: its source-root value changes
        ;; when schema resources move or the monolithic resource is removed.
        (require 'seon.cluster :reload)
        (require 'seon.cluster.registry
                 'seon.cluster.store
                 'seon.fs)
        ~(if source-process?
           `(println
             ~init-result-prefix
             (pr-str
              (try
                {:seon.fresh-operator/value ~operation}
                (catch Throwable failure#
                  {:seon.fresh-operator/message (ex-message failure#)
                   :seon.fresh-operator/data (ex-data failure#)}))))
           operation)))))

(defn- source-process-value!
  [root form]
  (let [process
        (start-child-jvm!
         {:seon.fresh-operator/root root
          :seon.fresh-operator/arguments ["-e" form]})
        output (slurp (.getInputStream process))
        exit (.waitFor process)]
    (when-not (zero? exit)
      (fail! "The initialization JVM exited unsuccessfully."
             {:seon.fresh-operator/exit exit
              :seon.fresh-operator/output output}))
    (or
     (some
      (fn [line]
        (when (str/starts-with? line init-result-prefix)
          (edn/read-string (subs line (count init-result-prefix)))))
      (str/split-lines output))
     (fail! "The initialization JVM returned no result."
            {:seon.fresh-operator/output output}))))

(declare stop-empty-jvm!)

(defn- init!
  [root arguments]
  (let [{:seon.fresh-operator/keys [name force? changed-paths]}
        (parse-init-arguments arguments)
        _ (operator.state/claim-root-under-lock!
           (repository-root) root false name)
        dependency-cache (dev.kondo/ensure-dependency-cache! root)
        _ (when (= :unavailable
                   (:seon.dev.clj-kondo/status dependency-cache))
            (fail! "The clj-kondo dependency cache could not be prepared."
                   dependency-cache))
        truth (reconciled-truth! root)
        anchor (select-anchor truth)
        live-target
        (when name
          (some #(when (and (:seon.fresh-operator/operator-root? %)
                            (= name (:seon.fresh-operator/name %))
                            (:seon.fresh-operator/registered? %))
                   %)
                truth))
        result
        (cond
          anchor
          (edn/read-string
           (terminal-value
            (prepl-eval!
             (:seon.fresh-operator/transport-advertisement anchor)
             (init-form root name force? changed-paths false))))

          (seq changed-paths)
          (fail! "Incremental source publication requires a running operator JVM."
                 {:seon.fresh-operator/changed-paths changed-paths})

          :else
          (let [outcome
                (source-process-value!
                 root (init-form root name force? changed-paths true))]
            (if-let [message (:seon.fresh-operator/message outcome)]
              (fail! message (:seon.fresh-operator/data outcome))
              (:seon.fresh-operator/value outcome))))
        source-branch (:seon.source/branch result)
        source-commit (:seon.source/commit-id result)
        digest (:seon.source/digest result)]
    (operator.state/mark-root-created-under-lock! (repository-root) root)
    (if name
      (println (str "● " name
                    (if force? " reforked " " forked ")
                    (get-in result
                            [:seon.fresh-operator/cluster
                             :seon.store/branch])
                    " from " source-branch
                    " commit " source-commit
                    " digest " digest))
      (println (str "● " source-branch
                    " commit " source-commit
                    " digest " digest)))
    (when (and force? live-target)
      (stop-empty-jvm!
       root
       (or (:seon.fresh-operator/transport-advertisement live-target)
           (:seon.fresh-operator/advertisement live-target)
           (:seon.fresh-operator/registered-advertisement live-target))
       name))))

(defn- row-state
  [row]
  (cond
    (and (or (:seon.fresh-operator/advertised? row)
             (:seon.fresh-operator/registered? row))
         (:seon.fresh-operator/process-alive? row))
    "alive"

    (and (:seon.fresh-operator/registered? row)
         (:seon.fresh-operator/process-alive? row))
    "reserved"

    (and (:seon.fresh-operator/branch-open? row)
         (:seon.fresh-operator/process-alive? row))
    "branch"

    (:seon.fresh-operator/process-alive? row)
    "drift"

    (:seon.fresh-operator/persisted? row)
    "stopped"

    :else
    "stale"))

(defn- status!
  {:seon.fn/external-sink :ai-visible-text
   :seon.fn/projection-boundary :none}
  [root arguments]
  (when (seq arguments)
    (fail! "`status` takes no arguments."
           {:seon.fresh-operator/arguments arguments}))
  (let [root (.getCanonicalPath (java.io.File. root))
        truth
        (reconciled-truth!
         root {:seon.fresh-operator/read-offline-roster? true
               :seon.fresh-operator/probe-jvms? false})
        rows (own-cluster-truth truth)
        roster (:seon.fresh-operator/roster (meta truth))
        process-records
        (:seon.fresh-operator/process-records (meta truth))
        process-record-errors
        (:seon.fresh-operator/process-record-errors (meta truth))
        associated-pids
        (into
         #{}
         (keep #(get-in % [:seon.fresh-operator/process :seon.boot/pid]))
         rows)
        orphan-pids
        (into
         []
         (comp
          (filter #(= root (:seon.fresh-operator/root %)))
          (map #(get-in % [:seon.fresh-operator/process :seon.boot/pid]))
          (remove associated-pids))
         (operator-process-observations))
        alive-count
        (count
        (filter
          #(and (or (:seon.fresh-operator/advertised? %)
                    (:seon.fresh-operator/registered? %))
                (:seon.fresh-operator/process-alive? %))
          rows))
        live-state-count
        (count
         (filter
          #(or (:seon.fresh-operator/advertised? %)
               (:seon.fresh-operator/registered? %)
               (:seon.fresh-operator/branch-open? %))
          rows))
        _ (doseq [row rows]
            (operator.state/claim-root-under-lock!
             (repository-root) root false
             (:seon.fresh-operator/name row)))
        _ (when (.exists (java.io.File. root))
            (operator.state/mark-root-created-under-lock!
             (repository-root) root))
        name-width (max 22 (reduce max 0 (map (comp count :seon.fresh-operator/name)
                                               rows)))
        row-format (str "%-" name-width "s %8s %-9s %7s %-24s %s")
        footprint (operator.state/record-footprint-under-lock!
                   (repository-root) root)]
    (println (format row-format
                     "CLUSTER" "PID" "STATE" "PREPL" "URL" "DRIFT"))
    (println (apply str (repeat (+ 90 name-width) "-")))
    (doseq [row rows]
      (let [{:seon.fresh-operator/keys
             [name advertisement registered-advertisement
              transport-advertisement inconsistencies]}
            row
            value (or advertisement registered-advertisement
                      transport-advertisement)]
      (println
       (format row-format
               name
               (or (get-in row [:seon.fresh-operator/process
                                :seon.boot/pid])
                   (:seon.boot/pid value)
                   "-")
               (row-state row)
               (or (:seon.boot/prepl-port value) "-")
               (or (:seon.render.web/url
                    (or advertisement registered-advertisement))
                   "-")
               (if (seq inconsistencies)
                 (str/join ", " (map clojure.core/name inconsistencies))
                 "-")))))
    (println (str alive-count "/" live-state-count " clusters alive"))
    (println
     (format "root footprint: %.2f GiB; filesystem usable: %.2f GiB (%.1f%%)"
             (/ (double (:seon.operator.footprint/bytes footprint))
                1073741824.0)
             (/ (double (:seon.operator.footprint/usable-bytes footprint))
                1073741824.0)
             (* 100.0 (:seon.operator.footprint/usable-ratio footprint))))
    (println
     (if (:seon.fresh-operator/roster-readable? roster)
       (str "roster readable via "
            (clojure.core/name (:seon.fresh-operator/roster-source roster)))
       (str "roster deferred"
            (when-let [reason (:seon.fresh-operator/roster-error roster)]
              (str ": "
                   (if (= :recorded-process
                          (:seon.fresh-operator/roster-source roster))
                     "a live JVM owns the store; status is derived from claims and advertisements"
                     reason))))))
    (doseq [record process-records]
      (println
       (str "recorded JVM pid " (:seon.boot/pid record)
            " generation " (:seon.operator.process-record/generation record)
            " " (if (record-alive? record) "alive" "not alive"))))
    (doseq [error process-record-errors]
      (println
       (str "record unreadable " (:seon.fresh-operator/path error)
            ": " (:seon.fresh-operator/error error))))
    (println
     (str "orphan seon JVMs: "
          (if (seq orphan-pids)
            (str/join ", " orphan-pids)
            "none")))))

(defn- open!
  [root arguments]
  (when (> (count arguments) 1)
    (fail! "Use `open [NAME]`."
           {:seon.fresh-operator/arguments arguments}))
  (let [name (selected-name root (first arguments))
        truth (reconciled-truth! root)
        row (require-live-row! truth name)
        url (or (:seon.render.web/url
                 (or (:seon.fresh-operator/advertisement row)
                     (:seon.fresh-operator/registered-advertisement row)))
                (fail! "The live cluster has not advertised a web URL."
                       {:seon.fresh-operator/name name}))
        process (doto (ProcessBuilder. ^java.util.List ["/usr/bin/open" url])
                  (.inheritIO))]
    (when-not (zero? (.waitFor (.start process)))
      (fail! "The browser opener failed."
             {:seon.fresh-operator/url url}))
    (println (str "● opened " name " → " url))))

(defn- stop-form
  [name]
  (pr-str
   `(let [instances# @@(ns-resolve 'seon.cluster
                                   (symbol "running-instances"))]
      (if-let [instance# (get instances# ~name)]
        (if (map? instance#)
          (do (seon.cluster/stop! instance#) :stopped)
          (do
            (swap! (var-get
                    (ns-resolve 'seon.cluster (symbol "running-instances")))
                   dissoc ~name)
            :released-reservation))
        :absent))))

(defn- sibling-names
  [truth pid target-name]
  (into []
        (comp
         (filter :seon.fresh-operator/operator-root?)
         (filter :seon.fresh-operator/process-alive?)
         (filter
          #(= pid
              (or
               (get-in % [:seon.fresh-operator/process :seon.boot/pid])
               (get-in % [:seon.fresh-operator/advertisement
                          :seon.boot/pid]))))
         (remove #(= target-name (:seon.fresh-operator/name %)))
         (map :seon.fresh-operator/name))
        truth))

(defn- process-record-for-advertisement
  [records advertisement]
  (some #(when (process-record-matches-advertisement? % advertisement) %)
        records))

(defn- sigterm!
  [root truth records name ad reason force?]
  (let [pid (:seon.boot/pid ad)
        siblings (sibling-names truth pid name)
        record
        (or (process-record-for-advertisement records ad)
            (fail! "Refusing to signal a JVM without its exact process record."
                   {:seon.fresh-operator/name name
                    :seon.boot/pid pid}))]
    (when (and (seq siblings) (not force?))
      (fail!
       (str "Refusing SIGTERM for shared JVM pid " pid
            "; sibling clusters would also stop: "
            (str/join ", " siblings)
            ". Escalate explicitly with `stop --force " name "`.")
       {:seon.fresh-operator/name name
        :seon.boot/pid pid
        :seon.fresh-operator/siblings siblings
        :seon.fresh-operator/force-command
        (str "stop --force " name)}))
    (println (str "! prepl unavailable (" reason "); SIGTERM pid " pid
                  " affects shared-JVM clusters: "
                  (str/join ", " (cons name siblings))))
    (let [path (terminate-recorded-process! record)]
      (when-not (record-alive? record)
        (clear-process-record! root record))
      (println (str "● " name " stop path=" (process-path-label path))))))

(defn- stop-empty-jvm!
  [root advertisement stopped-name]
  (let [pid (:seon.boot/pid advertisement)
        truth
        (reconciled-truth!
         root {:seon.fresh-operator/read-offline-roster? false})]
    (when (empty? (sibling-names truth pid stopped-name))
      (let [records (:seon.fresh-operator/process-records (meta truth))
            record (process-record-for-advertisement records advertisement)]
        (when record
          (let [path (terminate-recorded-process! record)]
            (when-not (record-alive? record)
              (clear-process-record! root record))
            (println (str "● empty JVM pid " pid " exited path="
                          (process-path-label path)))))))))

(defn- stop!
  [root arguments]
  (let [{requested-name :seon.fresh-operator/name
         force? :seon.fresh-operator/force?}
        (parse-stop-arguments arguments)
        truth
        (reconciled-truth!
         root {:seon.fresh-operator/read-offline-roster? false})
        records (:seon.fresh-operator/process-records (meta truth))
        name (select-destructive-name truth requested-name "stop")
        row (named-cluster-row truth name)
        ad
        (or (:seon.fresh-operator/transport-advertisement row)
            (:seon.fresh-operator/advertisement row)
            (fail! "The cluster has no reachable JVM."
                   {:seon.fresh-operator/name name
                    :seon.fresh-operator/inconsistencies
                    (:seon.fresh-operator/inconsistencies row)}))
        events
        (try
          (prepl-eval! ad (stop-form name))
          (catch Throwable error
            (sigterm! root truth records name ad (ex-message error) force?)
            nil))]
    (when events
      (let [value (terminal-value events)]
        (when-not (#{"stopped" "released-reservation"}
                   (some-> value edn/read-string clojure.core/name))
          (fail! "The live JVM did not own the advertised cluster."
                 {:seon.fresh-operator/name name
                  :seon.fresh-operator/result value}))
        (println (str "● " name " stop path=prepl"))
        ;; Reading :ret is the observable flush boundary. Only then may
        ;; the client terminate a JVM whose last advertisement is gone.
        (stop-empty-jvm! root ad name)))))

(defn- stop-all-form
  []
  (pr-str
   `(let [instances# @@(ns-resolve 'seon.cluster
                                   (symbol "running-instances"))
          names# (vec (sort (keys instances#)))]
      (doseq [[name# instance#] instances#]
        (if (map? instance#)
          (seon.cluster/stop! instance#)
          (swap! (var-get
                  (ns-resolve 'seon.cluster (symbol "running-instances")))
                 dissoc name#)))
      names#)))

(defn- advertisement-for-process-record
  [root record]
  (some
   (fn [observation]
     (let [advertisement (:seon.fresh-operator/advertisement observation)]
       (when (and (:seon.fresh-operator/process-alive? observation)
                  (process-record-matches-advertisement?
                   record advertisement))
         advertisement)))
   (advertisement-observations root)))

(defn- clear-stale-advertisements!
  [root]
  (doseq [observation (advertisement-observations root)
          :when (not (:seon.fresh-operator/process-alive? observation))]
    (fs/delete-if-exists (:seon.fresh-operator/path observation))
    (println
     (str "↻ repaired " (:seon.fresh-operator/name observation)
          ": removed stale advertisement"))))

(defn- require-readable-process-records!
  [root]
  (let [result (reconcile-process-records! root)
        errors (:seon.fresh-operator/process-record-errors result)]
    (when (seq errors)
      (fail! "Refusing destructive action with unreadable process records."
             {:seon.fresh-operator/process-record-errors errors}))
    (:seon.fresh-operator/process-records result)))

(defn- print-process-record-census!
  [root records record-errors]
  (println
   (str "PROCESS RECORD CENSUS root="
        (.getCanonicalPath (java.io.File. root))
        " records=" (count records)
        " unreadable=" (count record-errors)))
  (doseq [record (sort-by :seon.boot/pid records)]
    (println
     (str "  pid=" (:seon.boot/pid record)
          " start=" (:seon.boot/start-instant record)
          " generation=" (:seon.operator.process-record/generation record)
          " state=" (if (record-alive? record) "alive" "not-alive"))))
  (doseq [error record-errors]
    (println
     (str "  unreadable=" (:seon.fresh-operator/path error)
          " error=" (:seon.fresh-operator/error error)))))

(defn- down-recorded-processes!
  ([root force? verify-store?]
   (down-recorded-processes! root force? verify-store? false))
  ([root force? verify-store? discard-unreadable-after-flock?]
   (let [record-result (reconcile-process-records! root)
         record-errors
         (:seon.fresh-operator/process-record-errors record-result)
         records (:seon.fresh-operator/process-records record-result)
         _ (print-process-record-census! root records record-errors)
         _
         (when (seq record-errors)
           (if discard-unreadable-after-flock?
             (doseq [error record-errors]
               (println
                (str "! reset will discard unreadable process record "
                     (:seon.fresh-operator/path error)
                     " only after the store flock proves free")))
             (fail! "Refusing destructive action with unreadable process records."
                    {:seon.fresh-operator/process-record-errors
                     record-errors})))
         ]
    (if (seq records)
      (let [failures
            (reduce
             (fn [failures record]
               (let [pid (:seon.boot/pid record)]
                 (try
                   (let [advertisement
                         (when-not force?
                           (advertisement-for-process-record root record))
                         graceful
                         (when (and advertisement (record-alive? record))
                           (try
                             (prepl-eval! advertisement (stop-all-form))
                             :prepl
                             (catch Throwable error
                               (println
                                (str "! prepl unavailable for recorded JVM pid "
                                     pid " (" (ex-message error) ")"))
                               nil)))
                         signal-path (terminate-recorded-process! record)]
                     (when (record-alive? record)
                       (fail! "The exact recorded JVM remained alive after down."
                              {:seon.operator.process-record/generation
                               (:seon.operator.process-record/generation record)
                               :seon.boot/pid pid}))
                     (clear-process-record! root record)
                     (println
                      (str "● JVM pid " pid " path="
                           (if graceful
                             (str "prepl+" (process-path-label signal-path))
                             (process-path-label signal-path))))
                     failures)
                   (catch Throwable error
                     (conj failures
                           {:seon.operator.process-record/generation
                            (:seon.operator.process-record/generation record)
                            :seon.boot/pid pid
                            :seon.fresh-operator/error (ex-message error)})))))
             []
             records)]
        (when (seq failures)
          (fail! "One or more recorded JVMs could not be stopped."
                 {:seon.fresh-operator/process-failures failures})))
      (println "● no recorded JVMs to stop"))
    (clear-stale-advertisements! root)
    (when verify-store?
      (let [roster (offline-roster root)]
        (println (str "● flock free; roster readable ("
                      (count roster) " branches)")))))))

(defn- down!
  [root arguments]
  (let [{force? :seon.fresh-operator/force?}
        (parse-down-arguments arguments)]
    (down-recorded-processes! root force? true)))

(defn- cleanup-form
  [root]
  (pr-str
   `(do
      (require 'seon.operator)
      (println
       ~cleanup-result-prefix
       (pr-str
        ((ns-resolve 'seon.operator (symbol "cleanup-root!"))
         {:seon.operator/repository-root ~(str (repository-root))
          :seon.operator/managed-root ~root
          :seon.operator/control-lock-held? true}))))))

(defn- cleanup-managed-root!
  [root]
  (let [process
        (start-child-jvm!
         {:seon.fresh-operator/root root
          :seon.fresh-operator/arguments ["-e" (cleanup-form root)]})
        output (slurp (.getInputStream process))
        exit (.waitFor process)
        result
        (some
         (fn [line]
           (when (str/starts-with? line cleanup-result-prefix)
             (edn/read-string (subs line (count cleanup-result-prefix)))))
         (str/split-lines output))]
    (when-not (and (zero? exit)
                   (map? result)
                   (:seon.operator.cleanup/complete? result))
      (fail! "The operations owner did not completely clean the managed root."
             {:seon.fresh-operator/exit exit
              :seon.fresh-operator/output output
              :seon.operator/cleanup result}))
    result))

(defn- reset!
  [root arguments]
  (parse-reset-arguments arguments)
  (down-recorded-processes! root true false true)
  (when (seq (:seon.fresh-operator/process-records
              (reconcile-process-records! root)))
    (fail! "Recorded JVMs remain after forced down; reset refused."
           {:seon.fresh-operator/root root}))
  ;; The old store may be impossible to open because its persisted creation
  ;; config predates the current one. The operations owner therefore performs
  ;; unconditional no-follow deletion without opening Datahike. Exact process
  ;; reaping above and the external lifecycle lock make the whole transition
  ;; claim-first and race-free.
  (let [cleanup (cleanup-managed-root! root)]
    (println
     (str "● cleanup complete; reclaimed "
          (:seon.operator.cleanup/reclaimed-bytes cleanup)
          " bytes; removed "
          (str/join ", " (:seon.operator.cleanup/removed cleanup)))))
  (init! root [])
  (init! root ["default"])
  (println "● reset republished current-src and reforked default"))

(defn- logs!
  {:seon.fn/external-sink :ai-visible-text
   :seon.fn/projection-boundary :none}
  [root arguments]
  (when (> (count arguments) 1)
    (fail! "Use `logs [NAME]`."
           {:seon.fresh-operator/arguments arguments}))
  (let [name (selected-name root (first arguments))
        truth (reconciled-truth! root)
        _ (named-cluster-row truth name)
        path (log-path root name)]
    (when-not (fs/regular-file? path)
      (fail! "The cluster has no log."
             {:seon.fresh-operator/name name
              :seon.fresh-operator/path (str path)}))
    (let [builder (doto
                   (ProcessBuilder.
                    ^java.util.List ["tail" "-n" "200" (str path)])
                    (.inheritIO))
          exit (.waitFor (.start builder))]
      (when-not (zero? exit)
        (fail! "`tail` exited unsuccessfully."
               {:seon.fresh-operator/exit exit})))))

(defn- help!
  []
  (println
   (str
    "Usage: bin/seon [--root PATH] COMMAND\n\n"
    "  --root PATH    use an existing isolated operator root; its process\n"
    "                 records, advertisements, logs, and store are separate\n"
    "  start [CLUSTER] [--config PATH]\n"
    "                 start one cluster; absent cluster means default\n"
    "  config apply [CLUSTER] PATH\n"
    "                 reconcile one live cluster; absent cluster means default\n"
    "  init\n"
    "                 publish and print the current-src branch + commit ID\n"
    "  init --changed PATH...\n"
    "                 incrementally publish safe changed source files;\n"
    "                 fall back to one complete scratch publication\n"
    "  init NAME [--force]\n"
    "                 fork a dormant named cluster from current-src;\n"
    "                 refuse an existing cluster unless --force destroys it\n"
    "  status         reconcile and list every cluster in this operator root\n"
    "  open [NAME]    open the advertised web URL\n"
    "  stop [--force] [NAME]\n"
    "                 omit NAME only when exactly one cluster exists;\n"
    "                 force permits shared-JVM SIGTERM after prepl failure\n"
    "  down [--force]\n"
    "                 stop every recorded JVM; force skips graceful prepl\n"
    "  reset --force  down all JVMs, destroy the cluster root, republish,\n"
    "                 and refork default without opening the old store\n"
    "  logs [NAME]    show the cluster log\n")))

(defn -main
  "Run one fresh-system operator command."
  [& raw-arguments]
  (let [[root arguments] (parse-root raw-arguments)
        command (first arguments)
        command-arguments (vec (rest arguments))
        run-command
        (fn []
          (case command
            "start" (start! root command-arguments)
            "config" (config! root command-arguments)
            "init" (init! root command-arguments)
            "status" (status! root command-arguments)
            "open" (open! root command-arguments)
            "stop" (stop! root command-arguments)
            "down" (down! root command-arguments)
            "reset" (reset! root command-arguments)
            "logs" (logs! root command-arguments)
            ("help" "--help" "-h" nil) (help!)
            (fail! "Unknown fresh Seon command."
                   {:seon.fresh-operator/command command
                    :seon.fresh-operator/usage? true})))]
    (try
      (if (contains? #{"start" "config" "init" "status"
                       "stop" "down" "reset"}
                     command)
        (with-operator-lock root run-command)
        (run-command))
      (catch Throwable error
        (binding [*out* *err*]
          (println (str "✗ " (ex-message error)))
          (when-let [data (not-empty (ex-data error))]
            (prn data))
          (when (:seon.fresh-operator/usage? (ex-data error))
            (help!)))
        (System/exit 1)))))

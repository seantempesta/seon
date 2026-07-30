(ns seon.fresh-operator
  "The thin advertisement-derived operator for the fresh JVM system."
  (:refer-clojure :exclude [reset!])
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.io PushbackReader]
           [java.net InetSocketAddress ServerSocket Socket]
           [java.time Instant]
           [java.util.concurrent TimeUnit]))

(def ^:private cluster-name-pattern
  #"\A[A-Za-z0-9](?:[A-Za-z0-9._-]{0,62})\z")
(def ^:private advertisement-wait-ms 30000)
(def ^:private prepl-connect-ms 3000)
(def ^:private prepl-eval-ms 30000)
(def ^:private log-name "seon.log")
(def ^:private source-result-prefix "SEON-SOURCE-RESULT ")
(def ^:private detach-python
  (str "import subprocess,sys\n"
       "log=open(sys.argv[2],'ab',buffering=0)\n"
       "p=subprocess.Popen(sys.argv[3:],cwd=sys.argv[1],"
       "stdin=subprocess.DEVNULL,stdout=log,stderr=subprocess.STDOUT,"
       "close_fds=True,start_new_session=True)\n"
       "print(p.pid,flush=True)\n"))

(defn- fail!
  [message data]
  (throw (ex-info message data)))

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

(defn- explicit-start-name?
  [arguments]
  (loop [remaining (seq arguments)]
    (when remaining
      (let [argument (first remaining)]
        (cond
          (= "--config" argument) (recur (nnext remaining))
          (str/starts-with? argument "--") (recur (next remaining))
          :else true)))))

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

(defn- process-root-property
  [^java.lang.ProcessHandle handle]
  (let [arguments (some-> (optional-value (.arguments (.info handle))) vec)]
    (some
     (fn [argument]
       (when (str/starts-with? argument "-Dseon.operator.root=")
         (subs argument (count "-Dseon.operator.root="))))
     arguments)))

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
              {:seon.fresh-operator/root (process-root handle)
               :seon.fresh-operator/process
               {:seon.boot/pid (.pid handle)
                :seon.boot/start-instant (java.util.Date/from start)
                :seon.fresh-operator/alive? true}})))
         (sort-by #(get-in % [:seon.fresh-operator/process :seon.boot/pid]))
         vec)))

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
               'seon.cluster.process)
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
          (keys @datahike.connections/*connections*))}))))

(declare prepl-eval! terminal-value)

(defn- prepl-value!
  [advertisement form]
  (edn/read-string (terminal-value (prepl-eval! advertisement form))))

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
         (prepl-value! probe (jvm-snapshot-form))
         {:seon.fresh-operator/probe-advertisement probe
          :seon.fresh-operator/reachable? true})
        (catch Throwable error
          (assoc process-observation
                 :seon.fresh-operator/probe-advertisement probe
                 :seon.fresh-operator/reachable? false
                 :seon.fresh-operator/error (ex-message error)))))))

(defn- source-observations
  [root]
  (let [root (.getCanonicalPath (java.io.File. root))
        discovered-processes (operator-process-observations)
        roots (into #{root}
                    (keep :seon.fresh-operator/root)
                    discovered-processes)
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
        (->> (concat discovered-processes advertised-processes)
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
         (map #(observe-jvm % advertisements))
         processes)]
    {:seon.fresh-operator/advertisements advertisements
     :seon.fresh-operator/jvms jvms}))

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
  [operator-root advertisements jvms]
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
        pairs
        (into
         branch-pairs
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
  [root]
  (let [{:seon.fresh-operator/keys [advertisements jvms]}
        (source-observations root)]
    (derive-cluster-truth root advertisements jvms)))

(defn- own-cluster-truth
  [truth]
  (filterv :seon.fresh-operator/operator-root? truth))

(defn- existing-names
  [truth]
  (into []
        (comp
         (filter :seon.fresh-operator/operator-root?)
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
    (let [candidates (existing-names truth)]
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
  [root]
  (loop [truth (cluster-truth root)]
    (let [repairs (repair-actions truth)]
      (if (seq repairs)
        (do
          (doseq [repair repairs]
            (apply-repair! repair))
          (recur (cluster-truth root)))
        truth))))

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
  [advertisement form]
  (with-open [socket (Socket.)]
    (.connect socket
              (InetSocketAddress.
               ^String (:seon.boot/prepl-host advertisement)
               (int (:seon.boot/prepl-port advertisement)))
              prepl-connect-ms)
    (.setSoTimeout socket prepl-eval-ms)
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
      (loop [events []]
        (let [event (edn/read {:eof ::eof} reader)]
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
            (recur (conj events event))))))))

(defn- terminal-value
  [events]
  (:val (peek events)))

(defn- instrument-form
  [instance-symbol name]
  `(let [dials#
         (seon.config/effective
          @(get ~instance-symbol :seon.boot/cluster-connection)
          ~name)]
     (seon.instrument/apply!
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
  [name manifest ready-port]
  (let [instance (gensym "instance")]
    (pr-str
     `(do
        (require 'seon.cluster 'seon.config 'seon.instrument)
        (let [~instance
              (seon.cluster/start!
               {:seon.boot/cluster-name ~name
                :seon.config/manifest ~manifest})
              applied# ~(instrument-form instance name)]
          (println "seon" ~name "ready — instrumented"
                   (:seon.instrument/instrumented applied#) "vars")
          (flush)
          (with-open [socket# (java.net.Socket. "127.0.0.1" ~ready-port)
                      writer# (java.io.OutputStreamWriter.
                               (.getOutputStream socket#)
                               java.nio.charset.StandardCharsets/UTF_8)]
            (.write writer# "ready\n")
            (.flush writer#))
          @(promise))))))

(defn- add-form
  [name manifest]
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
        (let [~instance
              (seon.cluster/start!
               {:seon.boot/cluster-name ~name
                :seon.config/manifest ~manifest})
              applied# ~(instrument-form instance name)]
          (println "seon" ~name "added — instrumented"
                   (:seon.instrument/instrumented applied#) "vars")
          ~name)))))

(defn- create-log!
  [root name]
  (let [path (log-path root name)]
    (fs/create-dirs (fs/parent path))
    (spit (str path) "")
    path))

(defn- launch!
  [root name manifest ready-port]
  (let [log (create-log! root name)
        command ["python3" "-c" detach-python
                 (str (fs/path root)) (str log)
                 "clojure"
                 (str "-J-Dseon.operator.root=" root)
                 "-M:dev" "-e" (launch-form name manifest ready-port)]
        builder (doto (ProcessBuilder. ^java.util.List command)
                  (.directory (.toFile (fs/path root)))
                  (.redirectErrorStream true))
        _ (.putAll (.environment builder) (child-environment root))
        process (.start builder)
        output (str/trim (slurp (.getInputStream process)))
        exit (.waitFor process)]
    (when-not (zero? exit)
      (fail! "The detached cluster launcher failed."
             {:seon.fresh-operator/exit exit
              :seon.fresh-operator/output output}))
    {:seon.fresh-operator/pid (parse-long output)
     :seon.fresh-operator/log (str log)}))

(defn- await-advertisement!
  [root name ^ServerSocket ready-server]
  (.setSoTimeout ready-server advertisement-wait-ms)
  (try
    (with-open [socket (.accept ready-server)
                reader (java.io.BufferedReader.
                        (java.io.InputStreamReader.
                         (.getInputStream socket)
                         java.nio.charset.StandardCharsets/UTF_8))]
      (when-not (= "ready" (.readLine reader))
        (fail! "The cluster JVM sent malformed readiness."
               {:seon.fresh-operator/name name})))
    (catch java.net.SocketTimeoutException _
      (fail! "Timed out waiting for the cluster advertisement URL."
             {:seon.fresh-operator/name name
              :seon.fresh-operator/timeout-ms advertisement-wait-ms})))
  (let [value (advertisement root name)]
    (when-not (and value (alive? value) (:seon.render.web/url value))
      (fail! "The ready JVM did not publish a complete advertisement."
             {:seon.fresh-operator/name name}))
    value))

(defn- print-started!
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
        explicit-name? (boolean (explicit-start-name? arguments))
        manifest (if config-path (sparse-manifest root config-path) {})
        truth (reconciled-truth! root)
        existing
        (some
         #(when (and (:seon.fresh-operator/operator-root? %)
                     (= name (:seon.fresh-operator/name %)))
            %)
         truth)]
    (when existing
      (fail! "The cluster already has live operator state."
             {:seon.fresh-operator/name name
              :seon.fresh-operator/inconsistencies
              (:seon.fresh-operator/inconsistencies existing)}))
    (when (and
           explicit-name?
           (some
            #(and (not (:seon.fresh-operator/operator-root? %))
                  (= name (:seon.fresh-operator/name %)))
            truth))
      (named-cluster-row truth name))
    (if-let [anchor (select-anchor truth)]
      (let [anchor-ad
            (:seon.fresh-operator/transport-advertisement anchor)
            _
            (try
              (prepl-eval! anchor-ad (add-form name manifest))
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
      (with-open [ready-server
                  (ServerSocket.
                   0 1 (java.net.InetAddress/getLoopbackAddress))]
        (launch! root name manifest (.getLocalPort ready-server))
        (print-started! root name
                        (await-advertisement! root name ready-server))))))

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
       {:seon.config/connection
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

(defn- instance-form
  [name operation]
  (pr-str
   `(do
      (require 'seon.cluster)
      (let [instances# @@(ns-resolve 'seon.cluster
                                     (symbol "running-instances"))
            instance# (get instances# ~name)]
        (when-not (map? instance#)
          (throw
           (ex-info
            (str "Cluster `" ~name
                 "` has no addressable instance in this JVM.")
            {:seon.boot/cluster-name ~name})))
        (~operation instance#)))))

(defn- program-command-row!
  [root arguments]
  (let [name (valid-name! (first arguments))
        truth (reconciled-truth! root)
        row (require-live-row! truth name)
        advertisement
        (or (:seon.fresh-operator/transport-advertisement row)
            (fail!
             (str "Cluster " name
                  " has no reachable REPL for explicit source indexing.")
             {:seon.fresh-operator/name name}))]
    [name advertisement]))

(defn- baseline-form
  [root emit-process-result?]
  (let [operation
        `(seon.cluster/refresh-baseline!
          ~(str (cluster-root root)))]
    (pr-str
     `(do
        (require 'seon.cluster)
        ~(if emit-process-result?
           `(println ~source-result-prefix (pr-str ~operation))
           operation)))))

(defn- source-process-value!
  [root form]
  (let [builder
        (doto
         (ProcessBuilder.
          ^java.util.List
          ["clojure" "-M:dev" "-e" form])
          (.directory (.toFile (fs/path root)))
          (.redirectErrorStream true))
        _ (.putAll (.environment builder) (child-environment root))
        process (.start builder)
        output (slurp (.getInputStream process))
        exit (.waitFor process)]
    (when-not (zero? exit)
      (fail! "The source indexing JVM exited unsuccessfully."
             {:seon.fresh-operator/exit exit
              :seon.fresh-operator/output output}))
    (or
     (some
      (fn [line]
        (when (str/starts-with? line source-result-prefix)
          (edn/read-string (subs line (count source-result-prefix)))))
      (str/split-lines output))
     (fail! "The source indexing JVM returned no result."
            {:seon.fresh-operator/output output}))))

(defn- refresh-baseline!
  [root]
  (let [truth (reconciled-truth! root)
        result
        (if-let [anchor (select-anchor truth)]
          (terminal-value
           (prepl-eval!
            (:seon.fresh-operator/transport-advertisement anchor)
            (baseline-form root false)))
          (source-process-value! root (baseline-form root true)))]
    (println (str "● baseline source index " result))))

(defn- index!
  [root arguments]
  (case (count arguments)
    0
    (refresh-baseline! root)

    1
    (let [[name advertisement] (program-command-row! root arguments)
          result
          (terminal-value
           (prepl-eval! advertisement
                        (instance-form name 'seon.cluster/index!)))]
      (println (str "● " name " source index " result)))

    (fail! "Use `index` or `index CLUSTER`."
           {:seon.fresh-operator/arguments arguments})))

(declare stop-empty-jvm!)

(defn- reset!
  [root arguments]
  (when (> (count arguments) 1)
    (fail! "Use `reset [CLUSTER]`."
           {:seon.fresh-operator/arguments arguments}))
  (let [truth (reconciled-truth! root)
        name (select-destructive-name truth (first arguments) "reset")
        row (require-live-row! truth name)
        advertisement
        (or (:seon.fresh-operator/transport-advertisement row)
            (fail!
             (str "Cluster " name
                  " has no reachable REPL for destructive reset.")
             {:seon.fresh-operator/name name}))
        result
        (terminal-value
         (prepl-eval! advertisement
                      (instance-form name 'seon.cluster/reset!)))]
    (println (str "● " name " reset destroyed its old branch " result))
    (stop-empty-jvm! root (:seon.boot/pid advertisement) name)))

(defn- row-state
  [row]
  (cond
    (and (:seon.fresh-operator/registered? row)
         (:seon.fresh-operator/branch-open? row)
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

    :else
    "stale"))

(defn- status!
  [root arguments]
  (when (seq arguments)
    (fail! "`status` takes no arguments."
           {:seon.fresh-operator/arguments arguments}))
  (let [root (.getCanonicalPath (java.io.File. root))
        truth (reconciled-truth! root)
        rows (own-cluster-truth truth)
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
          #(and (:seon.fresh-operator/registered? %)
                (:seon.fresh-operator/process-alive? %))
          rows))]
    (println (format "%-22s %8s %-9s %7s %-24s %s"
                     "CLUSTER" "PID" "STATE" "PREPL" "URL" "DRIFT"))
    (println (apply str (repeat 112 "-")))
    (doseq [row rows]
      (let [{:seon.fresh-operator/keys
             [name advertisement registered-advertisement
              transport-advertisement inconsistencies]}
            row
            value (or advertisement registered-advertisement
                      transport-advertisement)]
      (println
       (format "%-22s %8s %-9s %7s %-24s %s"
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
    (println (str alive-count "/" (count rows) " clusters alive"))
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

(defn- sigterm!
  [truth name ad reason force?]
  (let [pid (:seon.boot/pid ad)
        siblings (sibling-names truth pid name)
        handle (java.lang.ProcessHandle/of (long pid))]
    (when-not (.isPresent handle)
      (fail! "The advertised process disappeared before SIGTERM."
             {:seon.fresh-operator/name name
              :seon.boot/pid pid}))
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
    (.destroy ^java.lang.ProcessHandle (.get handle))
    (println (str "● " name " stop path=SIGTERM"))))

(defn- stop-empty-jvm!
  [root pid stopped-name]
  (let [truth (reconciled-truth! root)]
    (when (empty? (sibling-names truth pid stopped-name))
      (when-let [handle
                 (let [optional (java.lang.ProcessHandle/of (long pid))]
                   (when (.isPresent optional) (.get optional)))]
        (when (.isAlive ^java.lang.ProcessHandle handle)
          (.destroy ^java.lang.ProcessHandle handle)
          (try
            (.get (.onExit ^java.lang.ProcessHandle handle)
                  5 TimeUnit/SECONDS)
            (catch java.util.concurrent.TimeoutException _
              (fail! "The empty cluster JVM did not exit after SIGTERM."
                     {:seon.boot/pid pid}))))
        (println (str "● empty JVM pid " pid " exited"))))))

(defn- stop!
  [root arguments]
  (let [{requested-name :seon.fresh-operator/name
         force? :seon.fresh-operator/force?}
        (parse-stop-arguments arguments)
        truth (reconciled-truth! root)
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
            (sigterm! truth name ad (ex-message error) force?)
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
        (stop-empty-jvm! root (:seon.boot/pid ad) name)))))

(defn- logs!
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
    "Usage: bin/seon COMMAND\n\n"
    "  start [CLUSTER] [--config PATH]\n"
    "                 start one cluster; absent cluster means default\n"
    "  config apply [CLUSTER] PATH\n"
    "                 reconcile one live cluster; absent cluster means default\n"
    "  index\n"
    "                 refresh the rolling baseline for future clusters;\n"
    "                 unlike other single-cluster verbs, absent means baseline,\n"
    "                 NOT the default cluster; existing clusters stay untouched\n"
    "  index CLUSTER\n"
    "                 preserve history and reconcile that cluster's\n"
    "                 source-owned program facts\n"
    "  reset [CLUSTER]\n"
    "                 DESTROY history and refork from the current ancestor;\n"
    "                 omit CLUSTER only when exactly one cluster exists\n"
    "  status         reconcile and list every cluster in this operator root\n"
    "  open [NAME]    open the advertised web URL\n"
    "  stop|down [--force] [NAME]\n"
    "                 omit NAME only when exactly one cluster exists;\n"
    "                 force permits shared-JVM SIGTERM after prepl failure\n"
    "  logs [NAME]    show the cluster log\n")))

(defn -main
  "Run one fresh-system operator command."
  [& raw-arguments]
  (let [[root arguments] (parse-root raw-arguments)
        command (first arguments)
        command-arguments (vec (rest arguments))]
    (try
      (case command
        "start" (start! root command-arguments)
        "config" (config! root command-arguments)
        "index" (index! root command-arguments)
        "reset" (reset! root command-arguments)
        "status" (status! root command-arguments)
        "open" (open! root command-arguments)
        "stop" (stop! root command-arguments)
        "down" (stop! root command-arguments)
        "logs" (logs! root command-arguments)
        ("help" "--help" "-h" nil) (help!)
        (fail! "Unknown fresh Seon command."
               {:seon.fresh-operator/command command
                :seon.fresh-operator/usage? true}))
      (catch Throwable error
        (binding [*out* *err*]
          (println (str "✗ " (ex-message error)))
          (when-let [data (not-empty (ex-data error))]
            (prn data))
          (when (:seon.fresh-operator/usage? (ex-data error))
            (help!)))
        (System/exit 1)))))

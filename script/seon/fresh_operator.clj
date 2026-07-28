(ns seon.fresh-operator
  "The thin advertisement-derived operator for the fresh JVM system."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.io PushbackReader]
           [java.net InetSocketAddress ServerSocket Socket]
           [java.time Instant]))

(def ^:private cluster-name-pattern
  #"\A[A-Za-z0-9](?:[A-Za-z0-9._-]{0,62})\z")
(def ^:private advertisement-wait-ms 30000)
(def ^:private prepl-connect-ms 3000)
(def ^:private prepl-eval-ms 30000)
(def ^:private log-name "seon.log")
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
  (if (= "--seon-root" (first arguments))
    [(or (second arguments)
         (fail! "`--seon-root` requires a path." {}))
     (vec (drop 2 arguments))]
    [(System/getProperty "user.dir") (vec arguments)]))

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

(defn- valid-name!
  [name]
  (when-not (and (string? name)
                 (re-matches cluster-name-pattern name))
    (fail! "Cluster names use letters, digits, `.`, `_`, or `-`."
           {:seon.fresh-operator/name name}))
  name)

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

(defn- advertisement-rows
  [root]
  (let [directory (cluster-root root)]
    (if-not (fs/directory? directory)
      []
      (->> (fs/list-dir directory)
           (keep
            (fn [cluster-dir]
              (when (fs/directory? cluster-dir)
                (let [path (fs/path cluster-dir "prepl.edn")
                      value (read-advertisement-path path)]
                  (when value
                    {:seon.fresh-operator/name
                     (str (:seon.boot/cluster-name value))
                     :seon.fresh-operator/advertisement value
                     :seon.fresh-operator/alive? (boolean (alive? value))})))))
           (sort-by :seon.fresh-operator/name)
           vec))))

(defn- require-advertisement!
  [root name]
  (valid-name! name)
  (let [value (advertisement root name)]
    (when-not value
      (fail! "The cluster has no advertisement."
             {:seon.fresh-operator/name name
              :seon.fresh-operator/path
              (str (advertisement-path root name))}))
    (when-not (alive? value)
      (fail! "The cluster advertisement is stale."
             {:seon.fresh-operator/name name
              :seon.boot/pid (:seon.boot/pid value)}))
    value))

(defn- selected-name
  [root argument]
  (if argument
    (valid-name! argument)
    (let [alive-names
          (into []
                (comp
                 (filter :seon.fresh-operator/alive?)
                 (map :seon.fresh-operator/name))
                (advertisement-rows root))]
      (or (when (some #{"default"} alive-names) "default")
          (first alive-names)
          "default"))))

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

(defn- launch-form
  [name ready-port]
  (let [instance (gensym "instance")]
    (pr-str
     `(do
        (require 'seon.cluster 'seon.config 'seon.instrument)
        (let [~instance
              (seon.cluster/start!
               {:seon.boot/cluster-name ~name})
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
  [name]
  (let [instance (gensym "instance")]
    (pr-str
     `(do
        (require 'seon.cluster 'seon.config 'seon.instrument)
        (let [~instance
              (seon.cluster/start!
               {:seon.boot/cluster-name ~name})
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
  [root name ready-port]
  (let [log (create-log! root name)
        command ["python3" "-c" detach-python
                 (str (fs/path root)) (str log)
                 "clojure" "-M:dev" "-e" (launch-form name ready-port)]
        process (.start
                 (doto (ProcessBuilder. ^java.util.List command)
                   (.directory (.toFile (fs/path root)))
                   (.redirectErrorStream true)))
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

(defn- generated-name
  [rows]
  (let [alive-names
        (into #{}
              (comp
               (filter :seon.fresh-operator/alive?)
               (map :seon.fresh-operator/name))
              rows)]
    (first
     (remove alive-names
             (map #(str "exp-" %) (iterate inc 1))))))

(defn- start-name
  [rows argument]
  (if argument
    (valid-name! argument)
    (if (some #(and (= "default" (:seon.fresh-operator/name %))
                    (:seon.fresh-operator/alive? %))
              rows)
      (generated-name rows)
      "default")))

(defn- print-started!
  [root name value]
  (println (format "● %-20s %s  prepl=%s  log=%s"
                   name
                   (:seon.render.web/url value)
                   (:seon.boot/prepl-port value)
                   (log-path root name))))

(defn- start!
  [root arguments]
  (when (> (count arguments) 1)
    (fail! "Use `start [NAME]`."
           {:seon.fresh-operator/arguments arguments}))
  (let [rows (advertisement-rows root)
        name (start-name rows (first arguments))
        existing (some #(and (= name (:seon.fresh-operator/name %))
                             (:seon.fresh-operator/alive? %))
                       rows)]
    (when existing
      (fail! "The cluster is already alive."
             {:seon.fresh-operator/name name}))
    (if-let [anchor (first (filter :seon.fresh-operator/alive? rows))]
      (let [anchor-ad (:seon.fresh-operator/advertisement anchor)
            _ (prepl-eval! anchor-ad (add-form name))
            value (require-advertisement! root name)]
        (create-log! root name)
        (spit (str (log-path root name))
              (str "Cluster " name " joined the JVM advertised by "
                   (:seon.fresh-operator/name anchor)
                   "; that process owns its original stdout/stderr.\n"))
        (print-started! root name value))
      (with-open [ready-server
                  (ServerSocket.
                   0 1 (java.net.InetAddress/getLoopbackAddress))]
        (launch! root name (.getLocalPort ready-server))
        (print-started! root name
                        (await-advertisement! root name ready-server))))))

(defn- status!
  [root arguments]
  (when (seq arguments)
    (fail! "`status` takes no arguments."
           {:seon.fresh-operator/arguments arguments}))
  (let [rows (advertisement-rows root)
        alive-count (count (filter :seon.fresh-operator/alive? rows))]
    (println (format "%-22s %8s %-7s %7s %s"
                     "CLUSTER" "PID" "STATE" "PREPL" "URL"))
    (println (apply str (repeat 78 "-")))
    (doseq [{:seon.fresh-operator/keys [name advertisement alive?]} rows]
      (println
       (format "%-22s %8s %-7s %7s %s"
               name
               (or (:seon.boot/pid advertisement) "-")
               (if alive? "alive" "stale")
               (or (:seon.boot/prepl-port advertisement) "-")
               (or (:seon.render.web/url advertisement) "-"))))
    (println (str alive-count "/" (count rows) " clusters alive"))))

(defn- open!
  [root arguments]
  (when (> (count arguments) 1)
    (fail! "Use `open [NAME]`."
           {:seon.fresh-operator/arguments arguments}))
  (let [name (selected-name root (first arguments))
        ad (require-advertisement! root name)
        url (or (:seon.render.web/url ad)
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
   `(do
      (require 'malli.instrument)
      (let [instances# @@(ns-resolve 'seon.cluster
                                     (symbol "running-instances"))
            stop-instance!#
            (malli.instrument/-original (var seon.cluster/stop!))]
      (if-let [instance# (get instances# ~name)]
          (do
            (stop-instance!# instance#)
            (when-not
             (seq @@(ns-resolve 'seon.cluster
                                (symbol "running-instances")))
              (future
                (Thread/sleep 100)
                (System/exit 0)))
            :stopped)
          :absent)))))

(defn- sibling-names
  [root pid]
  (into []
        (comp
         (filter :seon.fresh-operator/alive?)
         (filter #(= pid
                     (get-in % [:seon.fresh-operator/advertisement
                                :seon.boot/pid])))
         (map :seon.fresh-operator/name))
        (advertisement-rows root)))

(defn- sigterm!
  [root name ad reason]
  (let [pid (:seon.boot/pid ad)
        siblings (sibling-names root pid)
        handle (java.lang.ProcessHandle/of (long pid))]
    (when-not (.isPresent handle)
      (fail! "The advertised process disappeared before SIGTERM."
             {:seon.fresh-operator/name name
              :seon.boot/pid pid}))
    (println (str "! prepl unavailable (" reason "); SIGTERM pid " pid
                  " affects shared-JVM clusters: "
                  (str/join ", " siblings)))
    (.destroy ^java.lang.ProcessHandle (.get handle))
    (println (str "● " name " stop path=SIGTERM"))))

(defn- stop!
  [root arguments]
  (when (> (count arguments) 1)
    (fail! "Use `stop [NAME]`."
           {:seon.fresh-operator/arguments arguments}))
  (let [name (selected-name root (first arguments))
        ad (require-advertisement! root name)
        events
        (try
          (prepl-eval! ad (stop-form name))
          (catch java.io.IOException error
            (sigterm! root name ad (ex-message error))
            nil))]
    (when events
      (let [value (terminal-value events)]
        (when-not (= ":stopped" value)
          (fail! "The live JVM did not own the advertised cluster."
                 {:seon.fresh-operator/name name
                  :seon.fresh-operator/result value}))
        (println (str "● " name " stop path=prepl"))))))

(defn- logs!
  [root arguments]
  (when (> (count arguments) 1)
    (fail! "Use `logs [NAME]`."
           {:seon.fresh-operator/arguments arguments}))
  (let [name (selected-name root (first arguments))
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
    "Usage: bin/seon-fresh COMMAND\n\n"
    "  start [NAME]   start one cluster; generates a name when default is busy\n"
    "  status         list every advertisement and derived liveness\n"
    "  open [NAME]    open the advertised web URL\n"
    "  stop [NAME]    stop the cluster through its advertised prepl\n"
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
        "status" (status! root command-arguments)
        "open" (open! root command-arguments)
        "stop" (stop! root command-arguments)
        "logs" (logs! root command-arguments)
        ("help" "--help" "-h" nil) (help!)
        (fail! "Unknown fresh Seon command."
               {:seon.fresh-operator/command command}))
      (catch Throwable error
        (binding [*out* *err*]
          (println (str "✗ " (ex-message error)))
          (when-let [data (not-empty (ex-data error))]
            (prn data)))
        (System/exit 1)))))

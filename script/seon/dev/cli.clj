(ns seon.dev.cli
  "The single desired-state operator for a Seon source checkout."
  (:require [babashka.fs :as fs]
            [babashka.process :as shell]
            [clojure.string :as str]
            [seon.dev.artifact :as artifact]
            [seon.dev.config :as config]
            [seon.dev.changed-test :as changed-test]
            [seon.dev.process :as process]
            [seon.dev.skills :as skills]
            [seon.dev.state :as state]))

(defn- root-argument [arguments]
  (if (= "--seon-root" (first arguments))
    [(second arguments) (drop 2 arguments)]
    [(System/getProperty "user.dir") arguments]))

(defn- stop-development! [configuration]
  (doseq [id (reverse process/target-processes)]
    (process/stop! configuration id))
  nil)

(defn- legacy-database-path [configuration]
  (let [cluster (:seon.dev.config/cluster-dir configuration)
        database (fs/path cluster "db")
        legacy (fs/path cluster "store")]
    (when (and (fs/directory? legacy) (not (fs/exists? database)))
      (str legacy))))

(defn- assert-current-database-layout! [configuration]
  (when-let [legacy (legacy-database-path configuration)]
    (throw
      (ex-info
        "A legacy database exists, but the current database path is absent. Refusing to create a fresh database beside preserved evidence."
        {:seon.dev.target/failure
         :seon.dev.target.failure/legacy-database-layout
         :seon.dev.target/legacy-database-path legacy
         :seon.dev.target/database-path
         (str (fs/path (:seon.dev.config/cluster-dir configuration) "db"))})))
  configuration)

(defn- reconcile-development! [configuration]
  ;; A one-shot compiler must never share Shadow's mutable build cache with a
  ;; watcher, and the pod must never read a partially rebuilt output closure.
  ;; Quiesce both readers before building; the writer can safely keep running
  ;; from its already-loaded jar until its digest is known to have changed.
  (assert-current-database-layout! configuration)
  (process/stop! configuration process/watcher-id)
  (process/stop! configuration process/pod-id)
  (let [manifest (artifact/build! configuration)
        changed (:seon.dev.artifact/changed manifest)
        spec-map (process/specs configuration manifest)]
    (when (contains? changed :seon.dev.artifact/application)
      (process/stop! configuration process/pod-id))
    (when (contains? changed :seon.dev.artifact/writer)
      (process/stop! configuration process/pod-id)
      (process/stop! configuration process/writer-id))
    (doseq [id (process/start-order spec-map)]
      (println (str "▶ reconcile " (name id)))
      (process/ensure! configuration (get spec-map id))
      (println (str "  ● " (name id) " ready")))
    (assoc (process/status configuration manifest)
           :seon.dev.target/artifact-digest
           (:seon.dev.artifact/application-digest manifest))))

(defn- ordinary-agent-url [base-url]
  ;; The feed's first patch is immediate and contains the database-derived
  ;; fleet. Bound the stream and select the first non-root agent link.
  (let [result (shell/sh {:continue true :out :string :err :string
                          :cmd ["curl" "--compressed" "-sS" "-m" "1"
                                (str base-url "/agent/root/feed")]})
        ids (map second (re-seq #"href=\"/agent/([^/\"]+)\"" (:out result)))
        ordinary (first (remove #(= "root" %) ids))]
    (if ordinary
      (str base-url "/agent/" ordinary)
      (str base-url "/"))))

(defn- open-url! [url]
  (let [argv (cond
               (fs/executable? "/usr/bin/open") ["/usr/bin/open" url]
               (fs/which "xdg-open") ["xdg-open" url]
               :else nil)]
    (when-not argv
      (throw (ex-info "No browser opener is installed."
                      {:seon.dev.target/url url})))
    (shell/process {:out :discard :err :discard :cmd argv})
    nil))

(defn- print-ready! [target open?]
  (let [base-url (:seon.dev.target/url target)
        root-url (str base-url "/")
        agent-url (ordinary-agent-url base-url)]
    (println "")
    (println "◆ Seon is ready")
    (println (str "  agent: " agent-url))
    (println (str "  root:  " root-url))
    (println (str "  data:  " base-url "/data"))
    (when open? (open-url! agent-url))))

(defn- parse-start-options [arguments]
  (loop [remaining (seq arguments)
         options {:seon.dev.start/open? false}]
    (if-not remaining
      options
      (case (first remaining)
        "--open"
        (recur (next remaining) (assoc options :seon.dev.start/open? true))

        "--config"
        (let [path (second remaining)]
          (when (or (str/blank? path) (str/starts-with? path "--"))
            (throw (ex-info "`--config` requires a manifest path."
                            {:seon.dev.cli/arguments (vec arguments)})))
          (recur (nnext remaining)
                 (assoc options :seon.dev.start/config-path path)))

        (throw (ex-info "Unknown start option."
                        {:seon.dev.cli/arguments (vec arguments)
                         :seon.dev.cli/option (first remaining)}))))))

(defn- database-born? [configuration]
  (let [directory (fs/path (:seon.dev.config/cluster-dir configuration) "db")]
    (boolean
      (and (fs/directory? directory)
           (some fs/regular-file? (fs/list-dir directory))))))

(defn- select-config [configuration config-path]
  (let [root (:seon.dev.config/root configuration)
        explicit (when config-path
                   (let [path (fs/path config-path)]
                     (str (fs/normalize
                            (if (fs/absolute? path) path (fs/path root path))))))
        inherited (get-in configuration [:seon.dev.config/environment "SEON_CONFIG"])
        selected (or explicit inherited
                     (when-not (database-born? configuration)
                       (str (fs/path root "config/system.edn"))))]
    (when (and selected (not (fs/regular-file? selected)))
      (throw (ex-info "The selected Seon config manifest does not exist."
                      {:seon.config/path selected})))
    (cond-> configuration
      selected (assoc-in [:seon.dev.config/environment "SEON_CONFIG"] selected))))

(defn- up! [configuration arguments]
  (let [{:seon.dev.start/keys [open? config-path]}
        (parse-start-options arguments)
        configuration (select-config configuration config-path)
        target (state/with-lock configuration :stack 1800000
                                #(reconcile-development! configuration))]
    (print-ready! target open?)))

(defn- down! [configuration arguments]
  (when (seq arguments)
    (throw (ex-info "`down` takes no arguments."
                    {:seon.dev.cli/arguments (vec arguments)})))
  (state/with-lock configuration :stack 300000
                   #(stop-development! configuration))
  (println "○ Seon is down"))

(defn- restart! [configuration arguments]
  (let [{:seon.dev.start/keys [open? config-path]}
        (parse-start-options arguments)
        configuration (select-config configuration config-path)
        target
        (state/with-lock
          configuration :stack 1800000
          #(do (stop-development! configuration)
               (reconcile-development! configuration)))]
    (print-ready! target open?)))

(defn- config! [configuration arguments]
  (when-not (and (= "apply" (first arguments))
                 (second arguments)
                 (nil? (nth arguments 2 nil)))
    (throw (ex-info "Use `config apply <manifest-path>`."
                    {:seon.dev.cli/arguments (vec arguments)})))
  (let [configuration (select-config configuration (second arguments))
        target (state/with-lock configuration :stack 1800000
                                #(reconcile-development! configuration))]
    (print-ready! target false)))

(defn- status-value [configuration]
  (let [foreign (process/ownership-conflicts configuration)]
    (if-let [legacy (legacy-database-path configuration)]
      (cond->
        {:seon.dev.target/name :seon.dev.target/development
         :seon.dev.target/status :seon.dev.target.status/ownership-conflict
         :seon.dev.target/failure
         :seon.dev.target.failure/legacy-database-layout
         :seon.dev.target/cluster-name
         (:seon.dev.config/cluster-name configuration)
         :seon.dev.target/database-path
         (str (fs/path (:seon.dev.config/cluster-dir configuration) "db"))
         :seon.dev.target/legacy-database-path legacy}
        (seq foreign) (assoc :seon.dev.target/foreign-processes foreign))
    (if-let [manifest (artifact/read-manifest configuration)]
      (process/status configuration manifest)
      (cond->
        {:seon.dev.target/name :seon.dev.target/development
         :seon.dev.target/status
         (if (seq foreign)
           :seon.dev.target.status/ownership-conflict
           :seon.dev.target.status/down)
         :seon.dev.target/cluster-name
         (:seon.dev.config/cluster-name configuration)
         :seon.dev.target/database-path
         (str (fs/path (:seon.dev.config/cluster-dir configuration) "db"))
         :seon.dev.target/failure :seon.dev.target.failure/missing-artifact}
        (seq foreign) (assoc :seon.dev.target/foreign-processes foreign))))))

(defn- status! [configuration arguments]
  (let [edn? (= ["--edn"] (vec arguments))]
    (when (and (seq arguments) (not edn?))
      (throw (ex-info "`status` accepts only `--edn`."
                      {:seon.dev.cli/arguments (vec arguments)})))
    (let [status (status-value configuration)]
      (if edn?
        (prn status)
        (do
          (println (str (case (:seon.dev.target/status status)
                          :seon.dev.target.status/ready "●"
                          :seon.dev.target.status/degraded "◐"
                          "○")
                        " Seon " (name (:seon.dev.target/status status))))
          (when-let [url (:seon.dev.target/url status)]
            (println (str "  " url)))
          (doseq [[id value] (:seon.dev.target/processes status)]
            (println (str "  " (name id) "  "
                          (name (:seon.dev.process/status value))
                          (when-let [pid (:seon.dev.process/pid value)]
                            (str "  pid=" pid))
                          (when-not (:seon.dev.process/ready? value)
                            "  not-ready")))))))))

(defn- parse-log-id [value]
  (case value
    "watcher" process/watcher-id
    "writer" process/writer-id
    "pod" process/pod-id
    nil))

(defn- parse-log-options [arguments]
  (loop [arguments (seq arguments)
         options {:seon.dev.logs/follow? false
                  :seon.dev.logs/lines 200}]
    (if-not arguments
      options
      (case (first arguments)
        "--follow"
        (recur (next arguments) (assoc options :seon.dev.logs/follow? true))

        "--lines"
        (let [lines (some-> (second arguments) parse-long)]
          (when-not (and lines (pos? lines))
            (throw (ex-info "`--lines` requires a positive integer."
                            {:seon.dev.cli/arguments (vec arguments)})))
          (recur (nnext arguments) (assoc options :seon.dev.logs/lines lines)))

        (throw (ex-info "Unknown `logs` option."
                        {:seon.dev.cli/arguments (vec arguments)}))))))

(defn- logs! [configuration arguments]
  (let [first-argument (first arguments)
        id (some-> first-argument parse-log-id)
        _ (when (and first-argument (not id)
                     (not (str/starts-with? first-argument "--")))
            (throw (ex-info "Unknown process log."
                            {:seon.dev.cli/argument first-argument})))
        arguments (if id (rest arguments) arguments)
        {:seon.dev.logs/keys [follow? lines]} (parse-log-options arguments)
        ids (if id [id] process/target-processes)]
    (if follow?
      (do
        (when-not (= 1 (count ids))
          (throw (ex-info "`logs --follow` requires writer, watcher, or pod."
                          {:seon.dev.cli/arguments (vec arguments)})))
        (if-let [path (process/current-log configuration (first ids))]
          (shell/exec ["tail" "-n" (str lines) "-f" path])
          (throw (ex-info "That process has no current log."
                          {:seon.dev.process/id (first ids)}))))
      (doseq [process-id ids]
        (when-let [path (process/current-log configuration process-id)]
          (println (str "== " (name process-id) " · " path " =="))
          (let [result (shell/sh {:continue true :out :string :err :string
                                  :cmd ["tail" "-n" (str lines) path]})]
            (print (:out result))))))))

(defn- command-available? [command]
  (boolean (fs/which command)))

(defn- doctor-value [configuration]
  (let [manifest (artifact/read-manifest configuration)
        checks {:seon.dev.doctor/babashka? (command-available? "bb")
                :seon.dev.doctor/clj? (command-available? "clj")
                :seon.dev.doctor/clojure? (command-available? "clojure")
                :seon.dev.doctor/node? (command-available? "node")
                :seon.dev.doctor/npm? (command-available? "npm")
                :seon.dev.doctor/python? (command-available? "python3")
                :seon.dev.doctor/curl? (command-available? "curl")
                :seon.dev.doctor/java-26?
                (let [result (shell/sh {:continue true :out :string :err :string
                                        :cmd [(get-in configuration
                                                      [:seon.dev.config/environment "JAVA_CMD"]
                                                      "java") "-version"]})
                      major (some-> (re-find #"version \"([0-9]+)"
                                            (str (:out result) (:err result)))
                                    second parse-long)]
                  (= 26 major))}]
    {:seon.dev.doctor/healthy? (every? true? (vals checks))
     :seon.dev.doctor/checks checks
     :seon.dev.doctor/artifact-status
     (if manifest :seon.dev.artifact.status/published
                  :seon.dev.artifact.status/missing)}))

(defn- doctor! [configuration arguments]
  (let [edn? (= ["--edn"] (vec arguments))]
    (when (and (seq arguments) (not edn?))
      (throw (ex-info "`doctor` accepts only `--edn`."
                      {:seon.dev.cli/arguments (vec arguments)})))
    (let [result (doctor-value configuration)]
      (if edn?
        (prn result)
        (do
          (println (if (:seon.dev.doctor/healthy? result)
                     "● host prerequisites ready"
                     "✗ host prerequisites incomplete"))
          (doseq [[check ok?] (:seon.dev.doctor/checks result)]
            (println (str "  " (if ok? "●" "✗") " " (name check))))
          (println (str "  artifact "
                        (name (:seon.dev.doctor/artifact-status result)))))))))

(defn- reset-cluster! [configuration arguments]
  (let [cluster-name (first arguments)]
    (when (or (nil? cluster-name) (next arguments))
      (throw (ex-info "`cluster reset` requires exactly one cluster name."
                      {:seon.dev.cli/arguments (vec arguments)})))
    (when-not (= cluster-name (:seon.dev.config/cluster-name configuration))
      (throw (ex-info "This operator can reset only its explicitly configured cluster."
                      {:seon.dev.cluster/requested cluster-name
                       :seon.dev.cluster/configured
                       (:seon.dev.config/cluster-name configuration)})))
    (state/with-lock
      configuration :stack 1800000
      #(let [_ (assert-current-database-layout! configuration)
             database (fs/path (:seon.dev.config/cluster-dir configuration) "db")]
         (process/stop! configuration process/pod-id)
         (process/stop! configuration process/writer-id)
         (when (fs/exists? database) (fs/delete-tree database))
         (reconcile-development! (select-config configuration nil))))
    (println (str "● cluster " cluster-name " reset and ready"))))

(defn- cluster! [configuration arguments]
  (case (first arguments)
    "reset" (reset-cluster! configuration (rest arguments))
    (throw (ex-info "The supported cluster transition is `cluster reset <name>`."
                    {:seon.dev.cli/arguments (vec arguments)}))))

(defn- pod-test-arguments [arguments]
  (mapv #(if (str/starts-with? % "--") % (str "--test=" %)) arguments))

(defn- test-commands [configuration arguments]
  (let [root (:seon.dev.config/root configuration)
        target (first arguments)
        target-arguments (vec (rest arguments))
        pod-command (into [(str (fs/path root "bin/test-cljs"))]
                          (pod-test-arguments target-arguments))
        database-command (into [(str (fs/path root "bin/test-writer"))]
                               target-arguments)
        operator-command ["bb" "--config" (str (fs/path root "bb.edn"))
                          "--deps-root" root "-m" "seon.dev.test-runner"]]
    (case target
      "pod" [pod-command]
      "database" [database-command]
      "operator" [(into operator-command target-arguments)]
      "all"
      (do
        (when (seq target-arguments)
          (throw (ex-info "`test all` takes no selectors."
                          {:seon.dev.cli/arguments target-arguments})))
        [operator-command
         [(str (fs/path root "bin/test-writer"))]
         [(str (fs/path root "bin/test-cljs"))]])
      (throw (ex-info "Choose `test pod`, `test database`, `test operator`, or `test all`."
                      {:seon.dev.cli/arguments (vec arguments)})))))

(defn- test! [configuration arguments]
  (if (= "changed" (first arguments))
    (let [paths (loop [remaining (rest arguments) paths []]
                  (if-not (seq remaining)
                    paths
                    (if (and (= "--path" (first remaining))
                             (second remaining))
                      (recur (nnext remaining) (conj paths (second remaining)))
                      (throw (ex-info "Use `test changed --path PATH` one or more times."
                                      {:seon.dev.cli/arguments (vec arguments)})))))]
      (when-not (seq paths)
        (throw (ex-info "`test changed` requires at least one `--path PATH`."
                        {:seon.dev.cli/arguments (vec arguments)})))
      (println (changed-test/format-result
                 (changed-test/run-changed! configuration paths))))
    (doseq [argv (test-commands configuration arguments)]
      (println (str "▶ " (str/join " " argv)))
      (shell/shell {:dir (:seon.dev.config/root configuration)
                    :env (:seon.dev.config/environment configuration)
                    :cmd argv})))
  nil)

(defn- skills! [configuration arguments]
  (let [root (:seon.dev.config/root configuration)]
    (case (vec arguments)
      ["sync"]
      (let [result (skills/sync! root)]
        (println (str "● generated "
                      (count (:seon.dev.skills/synced result))
                      " shared skills into both tool adapters")))

      ["check"]
      (do (skills/check! root)
          (println "● generated skill adapters match seon-skills"))

      (throw (ex-info "Choose `skills sync` or `skills check`."
                      {:seon.dev.cli/arguments (vec arguments)})))))

(defn- help! []
  (println
    (str "Usage: bin/seon [up] [--open] [--config PATH]\n\n"
         "  up [--open] [--config PATH] build and reconcile the complete system\n"
         "  down                     drain the complete system\n"
         "  restart [--open] [--config PATH] drain, rebuild, and reconcile\n"
         "  config apply PATH        explicitly reconcile database config\n"
         "  status [--edn]           report live health\n"
         "  logs [writer|watcher|pod] [--lines N] [--follow]\n"
         "  doctor [--edn]           check host prerequisites\n"
         "  test changed --path PATH...  run affected tests from the warm graph\n"
         "  test pod|database|operator|all [selector]\n"
         "  skills sync|check        generate or verify tool-facing skill adapters\n"
         "  cluster reset <name>     drain and reset one named database\n")))

(defn -main
  "Run one Seon operator command."
  [& raw-arguments]
  (let [[root arguments] (root-argument raw-arguments)
        arguments (vec arguments)
        command (or (first arguments) "up")
        command-arguments (if (seq arguments) (subvec arguments 1) [])]
    (try
      (let [configuration (config/load! root)]
        (case command
          "up" (up! configuration command-arguments)
          "down" (down! configuration command-arguments)
          "restart" (restart! configuration command-arguments)
          "status" (status! configuration command-arguments)
          "logs" (logs! configuration command-arguments)
          "doctor" (doctor! configuration command-arguments)
          "test" (test! configuration command-arguments)
          "skills" (skills! configuration command-arguments)
          "config" (config! configuration command-arguments)
          "cluster" (cluster! configuration command-arguments)
          ("help" "--help" "-h") (help!)
          (throw (ex-info "Unknown Seon command."
                          {:seon.dev.cli/command command}))))
      (catch Throwable error
        (binding [*out* *err*]
          (println (str "✗ " (ex-message error)))
          (when-let [data (not-empty (ex-data error))]
            (prn data)))
        (System/exit 1)))))

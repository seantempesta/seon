(ns seon.dev.cli
  "The single desired-state operator for a Seon source checkout."
  (:require [babashka.fs :as fs]
            [babashka.process :as shell]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [seon.dev.artifact :as artifact]
            [seon.dev.branch :as branch]
            [seon.dev.cluster :as cluster]
            [seon.dev.config :as config]
            [seon.dev.changed-test :as changed-test]
            [seon.dev.process :as process]
            [seon.dev.restore :as restore]
            [seon.dev.restore-state :as restore-state]
            [seon.dev.skills :as skills]
            [seon.dev.state :as state]
            [seon.launch :as launch]))

(defn- root-argument [arguments]
  (if (= "--seon-root" (first arguments))
    [(second arguments) (drop 2 arguments)]
    [(System/getProperty "user.dir") arguments]))

(defn- stop-processes! [configuration operation targets]
  (process/clean-or-force!
   {:seon.dev.process/configuration configuration
    :seon.dev.process/operation operation
    :seon.dev.process/targets targets}))

(defn- stopped-targets [stop-results]
  (into #{}
        (map :seon.dev.process/id)
        (mapcat :seon.dev.process/results stop-results)))

(defn- stop-development! [configuration operation]
  (stop-processes! configuration operation (set process/target-processes)))

(defn- recover-dead-processes!
  [configuration]
  (let [targets
        (into #{}
              (filter
               (fn [id]
                 (let [record (process/read-process configuration id)]
                   (and record
                        (not= :seon.dev.process.status/alive
                              (process/reported-process-status record))))))
              process/target-processes)]
    (when (seq targets)
      (stop-processes!
       configuration :seon.dev.process.operation/recover targets))))

(defn- live-managed-process?
  [configuration id]
  (when-let [record (process/read-process configuration id)]
    (= :seon.dev.process.status/alive
       (process/reported-process-status record))))

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

(defn- retained-restore-intent [configuration]
  (let [cluster-dir (:seon.dev.config/cluster-dir configuration)
        path (restore/intent-path cluster-dir)]
    (when (fs/regular-file? path)
      (restore-state/read-intent! cluster-dir))))

(defn- require-no-retained-restore! [configuration operation]
  (when-let [intent (retained-restore-intent configuration)]
    (throw
     (ex-info
      "A retained restore intent must converge or be explicitly aborted first."
      {:seon.dev.cli/operation operation
       :seon.dev.restore/intent-id (:seon.dev.restore/intent-id intent)})))
  configuration)

(defn- resume-retained-restore! [configuration]
  (when-let [intent (retained-restore-intent configuration)]
    (let [target-branch
          (get-in intent [:seon.dev.restore/selected-target-descriptor
                          :seon.launch/database
                          :seon.db.branch/head
                          :seon.db.branch/name])
          request
          (cond-> {::restore-state/configuration configuration}
            (= :seon.dev.restore.operation/restore
               (::restore/operation intent))
            (assoc ::restore-state/branch-name (name target-branch)))]
      (println (str "▶ resume retained restore "
                    (:seon.dev.restore/intent-id intent)))
      (restore-state/resume! request))))

(defn- ensure-development-processes!
  [configuration manifest start-owned! stop-results]
  (let [spec-map (process/specs configuration manifest)]
    (doseq [id (process/start-order spec-map)]
      (println (str "▶ reconcile " (name id)))
      (process/ensure! configuration (get spec-map id) start-owned!)
      (println (str "  ● " (name id) " ready")))
    (assoc (process/status configuration manifest)
           :seon.dev.target/artifact-digest
           (:seon.dev.artifact/application-digest manifest)
           :seon.dev.target/stop-results stop-results)))

(defn- reconcile-development!
  ([configuration] (reconcile-development! configuration []))
  ([configuration prior-stop-results]
   ;; A one-shot compiler must never share Shadow's mutable build cache with a
   ;; watcher, and the pod must never read a partially rebuilt output closure.
   ;; Quiesce both readers before building; the writer can safely keep running
   ;; from its already-loaded jar until its digest is known to have changed.
   (assert-current-database-layout! configuration)
   (let [recovery (recover-dead-processes! configuration)
         prior-stop-results (cond-> (vec prior-stop-results)
                              recovery (conj recovery))]
   (process/with-startup-ownership
    configuration
    (fn [start-owned!]
      (if-let [manifest (when (live-managed-process?
                              configuration process/watcher-id)
                          (artifact/current-manifest configuration))]
        (let [late-recovery (recover-dead-processes! configuration)
              stop-results (cond-> prior-stop-results
                             late-recovery (conj late-recovery))]
          (ensure-development-processes!
           configuration manifest start-owned! stop-results))
        (let [readers #{process/pod-id process/watcher-id}
              already-stopped (stopped-targets prior-stop-results)
              readers-to-stop (set (remove already-stopped readers))
              reader-stop
              (when (seq readers-to-stop)
                (stop-processes!
                 configuration
                 :seon.dev.process.operation/rebuild-readers
                 readers-to-stop))
              stop-results (cond-> prior-stop-results
                             reader-stop (conj reader-stop))
              manifest (artifact/build!
                        configuration
                        #(process/prepare-watcher! configuration start-owned!))
              _ (process/admit-watcher-artifact! configuration manifest)
              changed (:seon.dev.artifact/changed manifest)
              stopped-after-readers (stopped-targets stop-results)
              writer-stop
              (when (and
                     (contains? changed :seon.dev.artifact/writer)
                     (not (contains? stopped-after-readers process/writer-id)))
                (stop-processes!
                 configuration
                 :seon.dev.process.operation/rebuild-writer
                 #{process/writer-id}))
              stop-results (cond-> stop-results
                             writer-stop (conj writer-stop))
              late-recovery (recover-dead-processes! configuration)
              stop-results (cond-> stop-results
                             late-recovery (conj late-recovery))]
          (ensure-development-processes!
           configuration manifest start-owned! stop-results))))))))

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

(defn- stop-component-line [result]
  (let [terminal (:seon.dev.process/terminal result)
        capture (:seon.dev.process/application-capture result)]
    (str "  " (name (:seon.dev.process/id result)) ": "
         (name (:seon.dev.process/classification result))
         (when-let [reason (:seon.dev.process/reason result)]
           (str " reason=" (name reason)))
         (when-let [generation
                    (:seon.dev.process.containment/generation terminal)]
           (str " generation=" generation))
         (when-let [trigger
                    (:seon.dev.process.containment/trigger terminal)]
           (str " trigger=" (name trigger)))
         (when-let [status
                    (:seon.dev.process.application-capture/status capture)]
           (str " capture=" (name status)))
         (when-let [sha256
                    (:seon.dev.process.application-capture/sha256 capture)]
           (str " sha256=" sha256))
         (when-let [bytes
                    (:seon.dev.process.application-capture/bytes capture)]
           (str " bytes=" bytes)))))

(defn- stop-evidence-lines [result]
  (into [(str (name (:seon.dev.process/operation result)) ": "
              (name (:seon.dev.process/classification result)))]
        (map stop-component-line)
        (take 3 (:seon.dev.process/results result))))

(defn- print-stop-evidence! [indent result]
  (doseq [line (stop-evidence-lines result)]
    (println (str indent line))))

(defn- print-ready! [target open?]
  (let [base-url (:seon.dev.target/url target)
        root-url (str base-url "/")
        agent-url (ordinary-agent-url base-url)]
    (println "")
    (println "◆ Seon is ready")
    (println (str "  agent: " agent-url))
    (println (str "  root:  " root-url))
    (println (str "  data:  " base-url "/data"))
    (doseq [result (:seon.dev.target/stop-results target)]
      (print-stop-evidence! "  " result))
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

(defn- select-config [configuration config-path]
  (config/select-manifest configuration config-path))

(defn- up! [configuration arguments]
  (let [{:seon.dev.start/keys [open? config-path]}
        (parse-start-options arguments)
        configuration (select-config configuration config-path)
        target
        (state/with-lock
         configuration :stack 1800000
         #(do
            (resume-retained-restore! configuration)
            (reconcile-development! configuration)))]
    (print-ready! target open?)))

(defn- down! [configuration arguments]
  (when (seq arguments)
    (throw (ex-info "`down` takes no arguments."
                    {:seon.dev.cli/arguments (vec arguments)})))
  (let [result
        (state/with-lock
         configuration :stack 300000
         #(do
            (require-no-retained-restore! configuration :down)
            (stop-development! configuration
                               :seon.dev.process.operation/down)))]
    (println "○ Seon is down")
    (print-stop-evidence! "  " result)))

(defn- restart! [configuration arguments]
  (let [{:seon.dev.start/keys [open? config-path]}
        (parse-start-options arguments)
        configuration (select-config configuration config-path)
        target
        (state/with-lock
          configuration :stack 1800000
          #(if (retained-restore-intent configuration)
             (do
               (resume-retained-restore! configuration)
               (reconcile-development! configuration))
             (let [stopped
                   (stop-development!
                    configuration :seon.dev.process.operation/restart)]
               (reconcile-development! configuration [stopped]))))]
    (print-ready! target open?)))

(defn- apply-live-config!
  "Send one explicit manifest operation to an already-ready compatible pod."
  [configuration]
  (let [manifest (artifact/read-manifest configuration)
        target   (when manifest (process/status configuration manifest))
        path     (get-in configuration
                         [:seon.dev.config/environment "SEON_CONFIG"])]
    (when-not (= :seon.dev.target.status/ready
                 (:seon.dev.target/status target))
      (throw (ex-info "Config apply requires a ready Seon target."
                      {:seon.dev.target/status
                       (:seon.dev.target/status target)})))
    (when-not path
      (throw (ex-info "Config apply requires an explicit manifest path." {})))
    (let [request (pr-str {:seon.config/path path})
          result  (shell/sh
                    {:continue true :out :string :err :string
                     :cmd ["curl" "--fail-with-body" "--silent" "--show-error"
                           "--request" "POST"
                           "--header" "Content-Type: application/edn"
                           "--data-binary" request
                           (str (:seon.dev.target/url target)
                                "/_seon/operator/config")]})
          response (when-not (str/blank? (:out result))
                     (try (edn/read-string (:out result))
                          (catch Exception _ nil)))]
      (when-not (and (zero? (:exit result))
                     (true? (:seon.state/ok? response)))
        (throw (ex-info "Live config apply failed."
                        {:seon.dev.config/path path
                         :seon.dev.config/exit (:exit result)
                         :seon.dev.config/response response
                         :seon.dev.config/error (str/trim (:err result))})))
      response)))

(defn- print-config-result! [result]
  (println "")
  (println "◆ Config applied")
  (println (str "  changed: " (:seon.state/changed? result)))
  (println (str "  operations: " (:seon.state/operations result)))
  (println (str "  basis-t: " (:seon.state/basis-t result))))

(defn- config! [configuration arguments]
  (when-not (and (= "apply" (first arguments))
                 (second arguments)
                 (nil? (nth arguments 2 nil)))
    (throw (ex-info "Use `config apply <manifest-path>`."
                    {:seon.dev.cli/arguments (vec arguments)})))
  (let [configuration (select-config configuration (second arguments))
        result
        (state/with-lock
         configuration :stack 300000
         #(do
            (require-no-retained-restore! configuration :config-apply)
            (apply-live-config! configuration)))]
    (print-config-result! result)))

(defn- status-value [configuration]
  (let [foreign (process/ownership-conflicts configuration)
        retained (retained-restore-intent configuration)
        base
        (cond->
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
        (seq foreign) (assoc :seon.dev.target/foreign-processes foreign))))
         (:seon.dev.config/launch-descriptor configuration)
         (assoc :seon.dev.target/branches (branch/inventory configuration)))]
    (cond-> base
      retained
      (assoc :seon.dev.target/status
             (if (= :seon.dev.target.status/ownership-conflict
                    (:seon.dev.target/status base))
               :seon.dev.target.status/ownership-conflict
               :seon.dev.target.status/degraded)
             :seon.dev.target/maintenance
             :seon.dev.target.maintenance/restore
             :seon.dev.restore/intent-id
             (::restore/intent-id retained)))))

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
                            "  not-ready"))))
          (doseq [retained (:seon.dev.target/branches status)]
            (println
             (str "  branch " (::branch/runtime-cluster retained) "  "
                  (name (:seon.dev.target/status retained)) "  "
                  (name (::branch/phase retained))
                  (when-let [url (:seon.dev.target/url retained)]
                    (str "  " url))))))))))

(defn- branch-request
  [configuration name]
  (branch/request {::branch/configuration configuration ::branch/name name}))

(defn- print-branch-result!
  [result]
  (let [target (::branch/target-private result)
        descriptor (::branch/launch-descriptor result)
        endpoint (when descriptor
                   (let [port-file (get-in descriptor [::launch/process
                                                       ::launch/http-port-file])
                         port (when (fs/regular-file? port-file)
                                (some-> (slurp port-file) str/trim parse-long))]
                     (when port (str "http://127.0.0.1:" port))))]
    (println (str "◆ Branch " (name (::branch/phase result))))
    (println (str "  runtime: " (::branch/runtime-cluster target)))
    (println (str "  database: " (::branch/target-database-name target)))
    (println (str "  branch: " (::branch/target-branch target)))
    (when endpoint (println (str "  web: " endpoint)))))

(defn- print-branch-status!
  [status]
  (println (str "◆ Branch " (::branch/runtime-cluster status) " "
                (name (:seon.dev.target/status status))))
  (println (str "  database: " (::branch/target-database-name status)))
  (println (str "  branch: " (::branch/target-branch status)))
  (println (str "  phase: " (name (::branch/phase status))))
  (when-let [url (:seon.dev.target/url status)]
    (println (str "  web: " url))))

(defn- branch!
  [configuration arguments]
  (let [[operation name & options] arguments]
    (when (str/blank? name)
      (throw (ex-info "Use `branch open|restart|close|status <name>`."
                      {:seon.dev.cli/arguments (vec arguments)})))
    (case operation
      "open"
      (do
        (when (seq options)
          (throw (ex-info "`branch open` takes one name."
                          {:seon.dev.cli/arguments (vec arguments)})))
        (print-branch-result!
         (state/with-lock
          configuration :stack 1800000
          #(do
             (require-no-retained-restore! configuration :branch-open)
             (branch/open! (branch-request configuration name))))))

      "restart"
      (do
        (when (seq options)
          (throw (ex-info "`branch restart` takes one name."
                          {:seon.dev.cli/arguments (vec arguments)})))
        (print-branch-result!
         (state/with-lock
          configuration :stack 1800000
          #(do
             (require-no-retained-restore! configuration :branch-restart)
             (branch/restart! (branch-request configuration name))))))

      "close"
      (do
        (when (seq options)
          (throw (ex-info "`branch close` takes one name."
                          {:seon.dev.cli/arguments (vec arguments)})))
        (let [open-request (branch-request configuration name)]
          (print-branch-result!
           (state/with-lock
            configuration :stack 1800000
            #(do
               (require-no-retained-restore! configuration :branch-close)
               (branch/close! {::branch/configuration configuration
                               ::branch/lifecycle-path
                               (::branch/lifecycle-path open-request)}))))))

      "status"
      (let [edn? (= ["--edn"] (vec options))]
        (when (and (seq options) (not edn?))
          (throw (ex-info "`branch status` accepts only `--edn`."
                          {:seon.dev.cli/arguments (vec arguments)})))
        (let [status (branch/status configuration name)]
          (if edn? (prn status) (print-branch-status! status))))

      (throw (ex-info "Use `branch open|restart|close|status <name>`."
                      {:seon.dev.cli/arguments (vec arguments)})))))

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
                :seon.dev.doctor/bun?
                (try
                  (artifact/bun-identity! configuration)
                  true
                  (catch Throwable _ false))
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
    (let [target
          (state/with-lock
           configuration :stack 1800000
           #(let [_ (assert-current-database-layout! configuration)
                  _ (require-no-retained-restore! configuration :cluster-reset)
                  database
                  (fs/path (:seon.dev.config/cluster-dir configuration) "db")
                  stopped
                  (stop-processes!
                   configuration
                   :seon.dev.process.operation/reset
                   #{process/pod-id process/writer-id})]
              (when (fs/exists? database) (fs/delete-tree database))
              (reconcile-development! (select-config configuration nil)
                                      [stopped])))]
      (doseq [result (:seon.dev.target/stop-results target)]
        (print-stop-evidence! "  " result))
      (println (str "● cluster " cluster-name " reset and ready")))))

(def ^:private restore-plan-byte-limit 1048576)

(defn- read-restore-plan! [path]
  (when-not (and (fs/regular-file? path)
                 (<= (fs/size path) restore-plan-byte-limit))
    (throw (ex-info "The restore plan file is absent or exceeds its byte bound."
                    {:seon.dev.cli/path path
                     :seon.dev.cli/max-bytes restore-plan-byte-limit})))
  (edn/read-string (slurp path)))

(defn- read-console-confirmation! [expected]
  (if-let [console (System/console)]
    (let [actual (.readLine console "%s" (to-array [(str expected "\n> ")]))]
      (when-not (= expected actual)
        (throw (ex-info "The typed restore confirmation did not match exactly."
                        {:seon.error/kind
                         :seon.dev.restore.error/confirmation-mismatch})))
      actual)
    (throw
     (ex-info
      "Interactive restore requires a real console; use --plan and --apply-plan for automation."
      {:seon.error/kind :seon.dev.restore.error/console-required}))))

(defn- print-restore-result! [branch-name result]
  (println (str "● restored retained branch " branch-name))
  (println (str "  intent: " (:seon.dev.restore/intent-id result)))
  (println (str "  branch head: "
                (pr-str (::restore-state/restored-branch-head result))))
  (println (str "  admin: "
                (name (::restore-state/admin-outcome result)))))

(def ^:private proof-crash-cuts
  {"after-intent-publication-before-force"
   :seon.dev.restore.proof-cut/after-intent-publication-before-force
   "after-force-before-completion"
   :seon.dev.restore.proof-cut/after-force-before-completion
   "after-completion-before-evidence-deletion"
   :seon.dev.restore.proof-cut/after-completion-before-evidence-deletion
   "after-evidence-deletion-before-autonomous-start"
   :seon.dev.restore.proof-cut/after-evidence-deletion-before-autonomous-start})

(defn- parse-proof-crash-cut [value]
  (or (get proof-crash-cuts value)
      (throw
       (ex-info "Unknown restore proof crash cut."
                {:seon.dev.restore-state/proof-crash-cut value
                 :seon.dev.restore-state/known-proof-crash-cuts
                 (vec (sort (keys proof-crash-cuts)))}))))

(defn- parse-apply-plan-options [options]
  (cond
    (and (= 4 (count options))
         (= ["--apply-plan" "--confirm"]
            [(nth options 0) (nth options 2)]))
    {::plan-path (nth options 1)
     ::confirmation (nth options 3)}

    (and (= 6 (count options))
         (= ["--apply-plan" "--confirm" "--proof-crash-cut"]
            [(nth options 0) (nth options 2) (nth options 4)]))
    {::plan-path (nth options 1)
     ::confirmation (nth options 3)
     ::proof-crash-cut (parse-proof-crash-cut (nth options 5))}

    :else nil))

(defn- apply-restore-plan!
  [configuration branch-name plan confirmation proof-crash-cut]
  (state/with-lock
   configuration :stack 1800000
   #(restore-state/apply!
     (cond-> {::restore-state/configuration configuration
              ::restore-state/branch-name branch-name
              ::restore/plan plan
              ::restore/confirmation-text confirmation}
       proof-crash-cut
       (assoc ::restore-state/proof-crash-cut proof-crash-cut)))))

(defn- abort-restore!
  [configuration branch-name confirmation]
  (state/with-lock
   configuration :stack 1800000
   #(restore-state/abort!
     {::restore-state/configuration configuration
      ::restore-state/branch-name branch-name
      ::restore/confirmation-text confirmation})))

(defn- apply-undo-plan!
  [configuration completion-id plan confirmation proof-crash-cut]
  (state/with-lock
   configuration :stack 1800000
   #(restore-state/apply-undo!
     (cond-> {::restore-state/configuration configuration
              ::restore-state/completion-id completion-id
              ::restore/plan plan
              ::restore/confirmation-text confirmation}
       proof-crash-cut
       (assoc ::restore-state/proof-crash-cut proof-crash-cut)))))

(defn- abort-undo!
  [configuration completion-id confirmation]
  (state/with-lock
   configuration :stack 1800000
   #(restore-state/abort-undo!
     {::restore-state/configuration configuration
      ::restore-state/completion-id completion-id
      ::restore/confirmation-text confirmation})))

(defn- retained-abort-confirmation [configuration]
  (let [intent
        (restore-state/read-intent!
         (:seon.dev.config/cluster-dir configuration))]
    (restore/confirmation-text
     {::restore/intent intent
      ::restore/confirmation-action
      :seon.dev.restore.confirmation/abort})))

(defn- restore-cluster! [configuration arguments]
  (let [branch-name (first arguments)
        options (vec (rest arguments))
        apply-options (parse-apply-plan-options options)]
    (when-not branch-name
      (throw (ex-info "`cluster restore` requires one retained branch name."
                      {:seon.dev.cli/arguments (vec arguments)})))
    (cond
      (= ["--plan" "--edn"] options)
      (prn
       (state/with-lock
        configuration :stack 1800000
        #(restore-state/plan!
          {::restore-state/configuration configuration
           ::restore-state/branch-name branch-name})))

      (empty? options)
      (let [plan
            (state/with-lock
             configuration :stack 1800000
             #(restore-state/plan!
               {::restore-state/configuration configuration
                ::restore-state/branch-name branch-name}))
            confirmation (::restore/confirmation-text plan)]
        (println (pr-str plan))
        (read-console-confirmation! confirmation)
        (print-restore-result!
         branch-name
         (apply-restore-plan!
          configuration branch-name plan confirmation nil)))

      apply-options
      (let [plan (read-restore-plan! (::plan-path apply-options))
            confirmation (::confirmation apply-options)]
        (print-restore-result!
         branch-name
         (apply-restore-plan!
          configuration branch-name plan confirmation
          (::proof-crash-cut apply-options))))

      (= ["--abort"] options)
      (let [confirmation
            (state/with-lock
             configuration :stack 1800000
             #(retained-abort-confirmation configuration))]
        (read-console-confirmation! confirmation)
        (println
         (pr-str (abort-restore!
                  configuration branch-name confirmation))))

      (and (= 3 (count options))
           (= ["--abort" "--confirm"] (subvec options 0 2)))
      (println
       (pr-str (abort-restore!
                configuration branch-name (nth options 2))))

      :else
      (throw
       (ex-info
        "Choose interactive restore, --plan --edn, --apply-plan PATH --confirm TEXT, or --abort --confirm TEXT."
        {:seon.dev.cli/arguments (vec arguments)})))))

(defn- undo-cluster! [configuration arguments]
  (let [completion-id (first arguments)
        options (vec (rest arguments))
        apply-options (parse-apply-plan-options options)]
    (when-not completion-id
      (throw (ex-info "`cluster undo` requires one completed restore id."
                      {:seon.dev.cli/arguments (vec arguments)})))
    (cond
      (= ["--plan" "--edn"] options)
      (prn
       (state/with-lock
        configuration :stack 1800000
        #(restore-state/plan-undo!
          {::restore-state/configuration configuration
           ::restore-state/completion-id completion-id})))

      (empty? options)
      (let [plan
            (state/with-lock
             configuration :stack 1800000
             #(restore-state/plan-undo!
               {::restore-state/configuration configuration
                ::restore-state/completion-id completion-id}))
            confirmation (::restore/confirmation-text plan)]
        (println (pr-str plan))
        (read-console-confirmation! confirmation)
        (print-restore-result!
         completion-id
         (apply-undo-plan!
          configuration completion-id plan confirmation nil)))

      apply-options
      (let [plan (read-restore-plan! (::plan-path apply-options))
            confirmation (::confirmation apply-options)]
        (print-restore-result!
         completion-id
         (apply-undo-plan!
          configuration completion-id plan confirmation
          (::proof-crash-cut apply-options))))

      (= ["--abort"] options)
      (let [confirmation
            (state/with-lock
             configuration :stack 1800000
             #(retained-abort-confirmation configuration))]
        (read-console-confirmation! confirmation)
        (println
         (pr-str (abort-undo!
                  configuration completion-id confirmation))))

      (and (= 3 (count options))
           (= ["--abort" "--confirm"] (subvec options 0 2)))
      (println
       (pr-str (abort-undo!
                configuration completion-id (nth options 2))))

      :else
      (throw
       (ex-info
        "Choose interactive undo, --plan --edn, --apply-plan PATH --confirm TEXT, or --abort --confirm TEXT."
        {:seon.dev.cli/arguments (vec arguments)})))))

(defn- cluster! [configuration arguments]
  (case (first arguments)
    "reset" (reset-cluster! configuration (rest arguments))
    "restore" (restore-cluster! configuration (rest arguments))
    "undo" (undo-cluster! configuration (rest arguments))
    (let [[operation cluster-name & options] arguments
          edn? (= ["--edn"] (vec options))]
      (when (str/blank? cluster-name)
        (throw
         (ex-info
          "Use `cluster open|restart|close|status <name>`."
          {:seon.dev.cli/arguments (vec arguments)})))
      (when (and (seq options) (or (not= "status" operation) (not edn?)))
        (throw
         (ex-info "Only `cluster status` accepts `--edn`."
                  {:seon.dev.cli/arguments (vec arguments)})))
      (let [request (cluster/request
                     {::cluster/configuration configuration
                      ::cluster/name cluster-name})]
        (case operation
          "open"
          (let [status
                (state/with-lock
                 configuration :stack 1800000
                 #(do
                    (require-no-retained-restore! configuration :cluster-open)
                    (cluster/open! request)))]
            (println (str "● cluster " cluster-name " ready"))
            (println (str "  database: "
                          (:seon.dev.target/database-path status)))
            (when-let [url (:seon.dev.target/url status)]
              (println (str "  web: " url))))

          "restart"
          (let [status
                (state/with-lock
                 configuration :stack 1800000
                 #(do
                    (require-no-retained-restore! configuration
                                                  :cluster-restart)
                    (cluster/restart! request)))]
            (println (str "● cluster " cluster-name " restarted"))
            (when-let [url (:seon.dev.target/url status)]
              (println (str "  web: " url))))

          "close"
          (let [result
                (state/with-lock
                 configuration :stack 1800000
                 #(do
                    (require-no-retained-restore! configuration :cluster-close)
                    (cluster/close! request)))]
            (println (str "● cluster " cluster-name " closed"))
            (print-stop-evidence! "  " result))

          "status"
          (let [status (cluster/status request)]
            (if edn?
              (prn status)
              (do
                (println (str "● cluster " cluster-name " "
                              (name (:seon.dev.target/status status))))
                (println (str "  database: "
                              (:seon.dev.target/database-path status)))
                (when-let [url (:seon.dev.target/url status)]
                  (println (str "  web: " url))))))

          (throw
           (ex-info
            "Choose `cluster open`, `cluster restart`, `cluster close`, `cluster status`, `cluster reset`, `cluster restore`, or `cluster undo`."
            {:seon.dev.cli/arguments (vec arguments)})))))))

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
         "  branch open|restart|close|status NAME [--edn]\n"
         "  logs [writer|watcher|pod] [--lines N] [--follow]\n"
         "  doctor [--edn]           check host prerequisites\n"
         "  test changed --path PATH...  run affected tests from the warm graph\n"
         "  test pod|database|operator|all [selector]\n"
         "  skills sync|check        generate or verify tool-facing skill adapters\n"
         "  cluster open|restart|close|status NAME [--edn]\n"
         "  cluster reset <name>     drain and reset one named database\n"
         "  cluster restore <branch> restore one exact retained branch head\n"
         "  cluster undo <completion-id> restore its exact retained undo head\n"
         "    --proof-crash-cut NAME test one durable apply-plan boundary\n")))

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
          "branch" (branch! configuration command-arguments)
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

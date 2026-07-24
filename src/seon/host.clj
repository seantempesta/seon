(ns seon.host
  "Serve the JVM execution protocol over one Unix-domain socket."
  (:require [clojure.edn :as edn]
            [my.blob.schema]
            [seon.agent.driver.host :as driver.host]
            [seon.ai.http :as ai.http]
            [seon.capability :as capability]
            [seon.db.branch :as db.branch]
            [seon.db.host :as db.host]
            [seon.db.transport.uds :as uds]
            [seon.error :as error]
            [seon.host.context :as context]
            [seon.host.eval :as eval]
            [seon.host.graduate :as graduate]
            [seon.host.instrument :as instrument]
            [seon.host.invoke :as invoke]
            [seon.host.sample :as sample]
            [seon.host.session :as session]
            [seon.schema :as schema]
            [taoensso.timbre :as log])
  (:import [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.file Files Path]
           [java.nio.channels ServerSocketChannel SocketChannel]
           [java.util.concurrent ExecutorService Executors ScheduledExecutorService TimeUnit]))

(set! *warn-on-reflection* true)

(schema/register! ::socket-path [:string {:min 1}])
(schema/register! ::eval-threads [:int {:min 1}])
(schema/register! ::database-pool-wait-timeout-ms [:int {:min 1}])
(schema/register!
 ::start-request
 [:map {:closed true}
  [::socket-path ::socket-path]
  [::context/writer-socket-path ::context/writer-socket-path]
  [::context/database-name ::context/database-name]
  [::context/backend {:optional true} ::context/backend]
  [::context/database-path {:optional true} ::context/database-path]
  [:my.blob/storage-view {:optional true} :my.blob/storage-view]
  [::database-pool-wait-timeout-ms
   {:optional true} ::database-pool-wait-timeout-ms]
  [:seon.execution/artifact-inventories
   {:optional true} ::capability/available-artifact-inventory]
  [:seon.startgate/release-digest [:re "^[0-9a-f]{64}$"]]
  [:seon.startgate/config-manifest-digest [:re "^[0-9a-f]{64}$"]]
  [:seon.startgate/base-projection-path [:string {:min 1}]]
  [:seon.startgate/base-projection-digest [:re "^[0-9a-f]{64}$"]]
  [::eval-threads {:optional true} ::eval-threads]])
(schema/register! ::server 'some?)
(schema/register! ::contexts 'some?)
(schema/register! ::base ::context/base)
(schema/register!
 ::host
 [:map
  [::server ::server]
  [::base ::base]
  [::contexts ::contexts]
  [::projection-state ::context/projection-state]])

(def ^:private default-eval-threads 10)
(def ^:private startup-read-timeout-ms
  "Startup-frame deadline; W1 moves it to a config fact."
  10000)

(defn- socket-accepting?
  [socket-path]
  (try
    (with-open [_channel (uds/connect! socket-path)] true)
    (catch Throwable _ false)))

(defn- delete-dead-socket!
  [socket-path]
  (when-not (socket-accepting? socket-path)
    (Files/deleteIfExists (Path/of socket-path (make-array String 0)))))

(defn- bind-server!
  [socket-path]
  (when (socket-accepting? socket-path)
    (throw
     (ex-info "A live listener already owns the host eval socket."
              {::socket-path socket-path
               :seon.error/kind :seon.host.error/socket-owned})))
  (delete-dead-socket! socket-path)
  (let [server (ServerSocketChannel/open StandardProtocolFamily/UNIX)]
    (try
      (.bind server (UnixDomainSocketAddress/of ^String socket-path))
      server
      (catch Throwable throwable
        (try (.close server) (catch Throwable _ nil))
        (throw throwable)))))

(defn- accept-startup!
  "Validate the session's first frame and answer ready, or refuse."
  [session host startup]
  (let [selection (:seon.execution/database-selection startup)]
    (cond
      (not (schema/valid-candidate-value? ::session/startup startup))
      (session/startup-error session "The execution child startup identity is invalid.")

      (not= (::context/database-name host)
            (:seon.db/database-name selection))
      (session/startup-error session
                     "The startup names another cluster database.")

      :else
      (let [head (context/resolve-head! (::writer host))]
        (if (:seon.error/message head)
          (session/startup-error session
                                 (:seon.error/message head))
          (let [agent-id (:seon.execution/agent-id startup)
                instrument-state (::instrument/state host)
                ctx
                (instrument/call-with-write-admission
                 instrument-state
                 (fn []
                   (if-let [existing (get @(::session/contexts session) agent-id)]
                     existing
                     (let [created (context/fork-context (::base host))]
            ;; Restore = fork the shared base + replay the agent's corpus
            ;; defs (design §2): a context is a cache of database facts,
            ;; so a fresh fork rebuilds the agent's home namespace from
            ;; its recorded `:seon.fn/source` rows. Replay failures are
            ;; values; a failed corpus read leaves an honest empty
            ;; context rather than refusing the session.
                       (binding [context/*agent-id* agent-id]
                         (context/restore-context-defs!
                          (::writer host) created (eval/agent-home-ns agent-id)))
                       (context/install-registered-wrappers!
                        {::context/registry (get-in host [::base ::context/registry])
                         ::context/ctx created
                         ::context/lib (eval/agent-home-ns agent-id)})
                       ;; Exact startup insertion: replay private defs, link
                       ;; shared registry vars, reconcile wrappers, publish the
                       ;; complete context population, then send READY.
                       (instrument/reconcile-current-context!
                        instrument-state created)
                       (swap! (::session/contexts session) assoc agent-id created)
                       created))))]
            (reset! (::session/startup session) startup)
            (session/send-frame!
             session
             {:seon.execution/message session/ready-message
              :seon.execution/protocol-version session/protocol-version
              :seon.execution/agent-id agent-id
              ;; The child schema names this field bun-version; the host
              ;; reports its JVM runtime there (rename rides the .cljc
              ;; promotion seam).
              :seon.execution/bun-version
              (str "jvm-" (System/getProperty "java.version"))
              :seon.execution/shadow-build-id
              (:seon.execution/shadow-build-id startup)
              :seon.execution/artifact-digest
              (:seon.execution/artifact-digest startup)
              :seon.db/db head})
            (assoc session ::session/ctx ctx)))))))

(defn- session-fault-log-value
  [throwable]
  (let [failure (error/->map throwable)]
    (assoc (select-keys failure
                        [:seon.error/message
                         :seon.error/kind
                         :seon.error/data])
           :seon.error/fault :core
           :seon.error/kind
           (or (:seon.error/kind failure) :core-bug))))

(defn- serve-session!
  "Run one pod session: startup handshake, then the message loop."
  [host ^SocketChannel channel]
  (let [session (assoc (session/session-map host channel)
                       ::instrument/state (::instrument/state host))
        input (::session/input session)]
    (let [startup-timed-out? (atom false)
          timeout-task
          (.schedule ^ScheduledExecutorService (::watchdog host)
                     ^Runnable
                     #(do (reset! startup-timed-out? true)
                          (try (.close channel) (catch Throwable _ nil)))
                     (long startup-read-timeout-ms) TimeUnit/MILLISECONDS)]
      (try
      (let [startup (try (uds/read-frame input)
                         (finally (.cancel timeout-task false)))]
        (when-let [ready-session
                   (and (map? startup)
                        (accept-startup! session host startup))]
          (loop []
            (let [message (uds/read-frame input)]
              (when (map? message)
                (case (:seon.execution/message message)
                  :seon.execution.message/invoke
                  (do (if (schema/valid-candidate-value? ::session/invoke message)
                        (invoke/begin-invocation! ready-session message)
                        (session/send-frame! ready-session
                                     (session/invalid-message-frame message)))
                      (recur))

                  :seon.execution.message/cancel
                  ;; A child process exits after cancel; the host ends the
                  ;; SESSION while the agent's context survives in-process.
                  (do (invoke/cancel-active!
                       ready-session
                       (:seon.execution/invocation-id message))
                      nil)

                  :seon.execution.message/value-sample
                  (do (if (sample/valid-value-sample? message)
                        (try
                          (sample/serve-value-sample! host ready-session message)
                          (catch Throwable throwable
                            (invoke/record-core-fault! throwable)
                            (session/send-frame!
                             ready-session
                             (session/sample-error-frame
                              (sample/safe-sample-correlation ready-session message)
                              "The value sample could not be produced."))))
                        (let [safe-sample
                              (sample/safe-sample-correlation ready-session message)]
                          (session/send-frame!
                           ready-session
                           (session/sample-error-frame
                            safe-sample
                            "The parent sent an invalid value sample."))))
                      (recur))

                  :seon.execution.message/shutdown
                  (invoke/shutdown-session! ready-session)

                  (do (session/send-frame!
                       ready-session
                       (if (contains? message :seon.execution/request-id)
                         (session/sample-error-frame
                          (sample/safe-sample-correlation ready-session message)
                          "The parent sent an invalid value sample.")
                         (session/invalid-message-frame message)))
                      (recur))))))))
      (catch Throwable throwable
        (when-not @startup-timed-out?
          (invoke/record-core-fault! throwable)
          (log/error (pr-str (session-fault-log-value throwable)))))
      (finally
        (.cancel timeout-task false)
        (reset! (::session/live-values session) {::session/order [] ::session/values {}})
        (try (.close channel) (catch Throwable _)))))))

(defn- accept-channel! [^ServerSocketChannel server]
  (.accept server))

(defn- start-session-thread! [host ^SocketChannel channel database-name]
  (doto (Thread. ^Runnable #(serve-session! host channel)
                 (str "seon-host-session-" database-name))
    (.setDaemon true)
    (.start)))

(defn start!
  "Start the agent host: shared base, contexts, and the UDS acceptor."
  {:malli/schema [:=> [:cat ::start-request] ::host]}
  [{::keys [socket-path eval-threads database-pool-wait-timeout-ms]
    :as request}]
  (let [^ServerSocketChannel server (bind-server! socket-path)]
    (try
      (let [writer
            (cond->
             (context/writer-session
              (select-keys request [::context/writer-socket-path
                                    ::context/database-name
                                    ::context/backend
                                    ::context/database-path]))
              database-pool-wait-timeout-ms
              (assoc ::db.host/pool-wait-timeout-ms
                     database-pool-wait-timeout-ms))
        _ (error/set-db-hooks!
           {:seon.error/transact!
            #(context/transact-writer! writer %)
            :seon.error/branch-head
            (fn []
              (let [database (context/resolve-head! writer)]
                (when-not (:seon/error database)
                  (db.branch/head-from-database-value database))))})
        base-artifact
        (context/load-base-projection!
         (:seon.startgate/base-projection-path request)
         (:seon.startgate/base-projection-digest request))
        base-projection (:seon.dev.artifact/base-projection base-artifact)
        _ (reset! (::context/base-projection writer) base-projection)
        database (context/resolve-head! writer)
        expected-identity
        {:seon.db.initialization/fingerprint
         (:seon.db.initialization/fingerprint base-artifact)
         :seon.db.initialization/release-digest
         (:seon.startgate/release-digest request)
         :seon.db.initialization/config-manifest-digest
         (:seon.startgate/config-manifest-digest request)}
        _ (context/verify-applied-identity!
           writer database (::context/database-name writer)
           expected-identity)
        base (context/build-base!
              writer (:seon.host.context/base-load-plan base-artifact))
        jvm-artifact-inventory
        (capability/installed-artifact-inventory
         (::context/tier-inventory base))
        artifact-inventories
        (capability/merge-artifact-inventories
         (cond-> [jvm-artifact-inventory]
           (:seon.execution/artifact-inventories request)
           (conj (:seon.execution/artifact-inventories request))))
        artifact-exports
        (into #{}
              (comp
               (mapcat val)
               (map symbol))
              (:seon.execution.inventory/exports-by-tier
               artifact-inventories))
        acquired-projection
        (context/acquire-preprocessed-projection!
         writer database base-projection artifact-exports)
        _ (when (:seon/error acquired-projection)
            (context/close-session! writer)
            (error/set-db-hooks! {})
            (throw
              (ex-info
                (get-in acquired-projection
                        [:seon/error :seon.error/message])
                {:seon.error/kind :core-bug
                 :seon.host/projection-error
                 (:seon/error acquired-projection)})))
        projection-state (::context/projection-state writer)
        _ (reset! projection-state acquired-projection)
        contexts (atom {})
        graduation-report
        (graduate/rebuild!
         {::context/base base
          ::context/registry (::context/registry base)
          ::context/writer writer})
        instrument-state
        (instrument/state
         {::context/registry (::context/registry base)
          ::context/projection-state projection-state
          ::contexts contexts})
        _ (instrument/call-with-write-admission
           instrument-state
           #(instrument/apply-projection!
             instrument-state (::context/projection acquired-projection)))
        eval-pool (Executors/newFixedThreadPool
                   (int (or eval-threads default-eval-threads)))
        watchdog (Executors/newScheduledThreadPool 2)
        host (merge writer
                    {::writer writer
                     ::server server
                     ::base base
                     ::instrument/state instrument-state
                     ::projection-state projection-state
                     ::graduation-report graduation-report
                     ::contexts contexts
                     ::eval-pool eval-pool
                     ::watchdog watchdog
                     :seon.execution/artifact-inventories
                     artifact-inventories
                     :seon.agent.driver/llm-transport! ai.http/complete
                     ::socket-path socket-path})
        acceptor
        (Thread.
         ^Runnable
         (fn []
           (loop []
             (when (.isOpen server)
               (let [accepted (atom nil)]
                 (try
                   (let [channel (accept-channel! server)]
                     (reset! accepted channel)
                     (start-session-thread!
                      host channel (::context/database-name writer))
                     (reset! accepted nil))
                   (catch Throwable throwable
                     (when-let [channel @accepted]
                       (try (.close ^SocketChannel channel)
                            (catch Throwable _ nil)))
                     (when (.isOpen server)
                       (invoke/record-core-fault! throwable))))
                 (recur)))))
         "seon-host-acceptor")]
        (.setDaemon acceptor true)
        (.start acceptor)
        (let [host (assoc host ::acceptor acceptor)]
          (cond-> host
            (:my.blob/storage-view request)
            (assoc ::driver
                   (driver.host/start!
                    host (:my.blob/storage-view request))))))
      (catch Throwable throwable
        (try (.close server) (catch Throwable _ nil))
        (try (delete-dead-socket! socket-path) (catch Throwable _ nil))
        (throw throwable)))))

(defn stop!
  "Stop the host acceptor and release its pools and socket."
  {:malli/schema [:=> [:cat ::host] :nil]}
  [{::keys [server eval-pool watchdog socket-path writer driver]}]
  (when-let [stop! (:seon.agent.driver/stop! driver)]
    (stop!))
  (try (.close ^ServerSocketChannel server) (catch Throwable _))
  (.shutdownNow ^ExecutorService eval-pool)
  (.shutdownNow ^ScheduledExecutorService watchdog)
  (error/set-db-hooks! {})
  (when writer (context/close-session! writer))
  (when socket-path
    (try (delete-dead-socket! socket-path) (catch Throwable _)))
  nil)

(defn -main
  "Run one agent host from an EDN configuration argument until killed.

   Usage:
     clojure -M:writer:host -m seon.host \\
       '{:seon.host/socket-path \"tmp/seon-host.sock\"
         :seon.host.context/writer-socket-path
         \"tmp/seon-cluster-default-req.sock\"
         :seon.host.context/database-name \"default\"}'"
  [& [configuration]]
  (let [request (edn/read-string configuration)
        host (start! request)
        report (get-in host [::base ::context/report])]
    (println (str "HOST READY " (::socket-path request)
                  " base-loaded=" (::context/loaded report)
                  "/" (::context/pure-blocks report)
                  " base-failed=" (::context/failed report)
                  " base-excluded=" (::context/excluded report)))
    (flush)
    @(promise)))

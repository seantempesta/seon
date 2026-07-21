(ns seon.host
  "Serve the JVM execution protocol over one Unix-domain socket."
  (:require [clojure.edn :as edn]
            [seon.db.branch :as db.branch]
            [seon.db.transport.uds :as uds]
            [seon.error :as error]
            [seon.host.context :as context]
            [seon.host.eval :as eval]
            [seon.host.graduate :as graduate]
            [seon.host.invoke :as invoke]
            [seon.host.sample :as sample]
            [seon.host.session :as session]
            [seon.schema :as schema])
  (:import [java.io File]
           [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels ServerSocketChannel SocketChannel]
           [java.util.concurrent ExecutorService Executors ScheduledExecutorService TimeUnit]))

(set! *warn-on-reflection* true)

(schema/register! ::socket-path [:string {:min 1}])
(schema/register! ::eval-threads [:int {:min 1}])
(schema/register!
 ::start-request
 [:map {:closed true}
  [::socket-path ::socket-path]
  [::context/writer-socket-path ::context/writer-socket-path]
  [::context/database-name ::context/database-name]
  [::context/backend {:optional true} ::context/backend]
  [::context/database-path {:optional true} ::context/database-path]
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
        (if (:seon/error head)
          (session/startup-error session
                         (get-in head [:seon/error :seon.error/message]))
          (let [agent-id (:seon.execution/agent-id startup)
                existing? (contains? @(::session/contexts session) agent-id)
                ctx (-> (swap! (::session/contexts session)
                               (fn [contexts]
                                 (if (contains? contexts agent-id)
                                   contexts
                                   (assoc contexts agent-id
                                          (context/fork-context
                                           (::base host))))))
                        (get agent-id))]
            ;; Restore = fork the shared base + replay the agent's corpus
            ;; defs (design §2): a context is a cache of database facts,
            ;; so a fresh fork rebuilds the agent's home namespace from
            ;; its recorded `:seon.fn/source` rows. Replay failures are
            ;; values; a failed corpus read leaves an honest empty
            ;; context rather than refusing the session.
            (when-not existing?
              (binding [context/*agent-id* agent-id]
                (context/restore-context-defs!
                 (::writer host) ctx (eval/agent-home-ns agent-id)))
              (context/install-registered-wrappers!
               {::context/registry (get-in host [::base ::context/registry])
                ::context/ctx ctx
                ::context/lib (eval/agent-home-ns agent-id)}))
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

(defn- serve-session!
  "Run one pod session: startup handshake, then the message loop."
  [host ^SocketChannel channel]
  (let [session (session/session-map host channel)
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
          (invoke/record-core-fault! throwable)))
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
  [{::keys [socket-path eval-threads]
    :as request}]
  (let [writer (context/writer-session
                (select-keys request [::context/writer-socket-path
                                      ::context/database-name
                                      ::context/backend
                                      ::context/database-path]))
        _ (error/set-db-hooks!
           {:seon.error/transact!
            #(context/transact-writer! writer %)
            :seon.error/branch-head
            (fn []
              (let [database (context/resolve-head! writer)]
                (when-not (:seon/error database)
                  (db.branch/head-from-database-value database))))})
        acquired-projection (context/acquire-committed-projection! writer)
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
        projection-state (atom acquired-projection)
        base (context/build-base! writer)
        graduation-report
        (graduate/rebuild!
         {::context/base base
          ::context/registry (::context/registry base)
          ::context/writer writer})
        contexts (atom {})
        eval-pool (Executors/newFixedThreadPool
                   (int (or eval-threads default-eval-threads)))
        watchdog (Executors/newScheduledThreadPool 2)
        _ (try (.delete (File. ^String socket-path)) (catch Throwable _))
        address (UnixDomainSocketAddress/of ^String socket-path)
        server (ServerSocketChannel/open StandardProtocolFamily/UNIX)
        host (merge writer
                    {::writer writer
                     ::server server
                     ::base base
                     ::projection-state projection-state
                     ::graduation-report graduation-report
                     ::contexts contexts
                     ::eval-pool eval-pool
                     ::watchdog watchdog
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
    (.bind server address)
    (.setDaemon acceptor true)
    (.start acceptor)
    (assoc host ::acceptor acceptor)))

(defn stop!
  "Stop the host acceptor and release its pools and socket."
  {:malli/schema [:=> [:cat ::host] :nil]}
  [{::keys [server eval-pool watchdog socket-path writer]}]
  (try (.close ^ServerSocketChannel server) (catch Throwable _))
  (.shutdownNow ^ExecutorService eval-pool)
  (.shutdownNow ^ScheduledExecutorService watchdog)
  (error/set-db-hooks! {})
  (when writer (context/close-session! writer))
  (when socket-path
    (try (.delete (File. ^String socket-path)) (catch Throwable _)))
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

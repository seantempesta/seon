(ns seon.host
  "Run the cluster JVM's database-bound agent driver."
  (:require [clojure.edn :as edn]
            [seon.ai.http :as ai.http]
            [seon.agent.driver :as driver]
            [seon.db.host :as db.host]
            [seon.db.protocol :as db.protocol]
            [seon.sci.eval :as sci.eval]))

(set! *warn-on-reflection* true)

(defn- evaluation-concurrency
  []
  (.availableProcessors (Runtime/getRuntime)))

(defn start!
  "Open the writer session and start the cluster JVM agent driver."
  [request]
  (let [writer
        (db.host/writer-session
         (select-keys request
                      (concat
                       [::db.host/writer-socket-path
                        ::db.host/database-name
                        ::db.host/backend
                        ::db.host/database-path
                        ::db.host/pool-wait-timeout-ms]
                       db.protocol/writer-connection-keys)))]
    (try
      (let [database (db.host/resolve-db! writer nil false)]
        (when (:seon.error/message database)
          (throw
           (ex-info (:seon.error/message database)
                    {:seon.error/kind :configuration
                     :seon/error database})))
        (sci.eval/open!
         {::sci.eval/concurrency (evaluation-concurrency)})
        (driver/start! writer
                       #(db.host/allocate! writer %)
                       (db.host/database-functions writer)
                       ai.http/complete)
        {::writer writer
         ::database database})
      (catch Throwable throwable
        (db.host/close-session! writer)
        (throw throwable)))))

(defn stop!
  "Close the cluster JVM's writer session."
  [{::keys [writer]}]
  (when writer
    (db.host/close-session! writer))
  nil)

(defn -main
  "Run the cluster JVM agent driver until the process is stopped."
  [& [configuration]]
  (start! (edn/read-string configuration))
  (println "HOST READY")
  (flush)
  @(promise))

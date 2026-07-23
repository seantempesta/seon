(ns seon.web.feed
  "JVM Datastar SSE connections with latest-complete-state delivery."
  (:require [seon.db.host :as db.host]
            [starfederation.datastar.clojure.adapter.common :as adapter]
            [starfederation.datastar.clojure.adapter.http-kit :as http-kit]
            [starfederation.datastar.clojure.api :as datastar]
            [starfederation.datastar.clojure.protocols :as protocols])
  (:import [java.util.concurrent ArrayBlockingQueue Executors ScheduledExecutorService
            Semaphore TimeUnit]
           [java.util.concurrent.atomic AtomicBoolean]))

(def ^:private stop-marker (Object.))

(defn- selected-write-profile
  [compression]
  (case compression
    :gzip http-kit/gzip-profile
    :identity http-kit/basic-profile))

(defn- enqueue-latest!
  [^ArrayBlockingQueue mailbox value]
  (.clear mailbox)
  (.offer mailbox value))

(defn- stop-connection!
  [{::keys [mailbox thread]}]
  (enqueue-latest! mailbox stop-marker)
  (when thread (.interrupt ^Thread thread))
  nil)

(defn- drain-connection!
  [{::keys [mailbox sse draining?]}]
  (loop []
    (let [value (.take ^ArrayBlockingQueue mailbox)]
      (when-not (identical? stop-marker value)
        (reset! draining? true)
        (try
          (datastar/patch-elements! sse value)
          (finally
            (reset! draining? false)))
        (recur)))))

(defn- heartbeat!
  [{::keys [connections]}]
  (doseq [{::keys [mailbox sse draining?]} (vals @connections)]
    (when (and (not @draining?) (.isEmpty ^ArrayBlockingQueue mailbox))
      ;; The SDK has no comment API. An empty custom event is inert to
      ;; Datastar while retaining SDK-owned SSE framing and compression.
      (protocols/send-event! sse "seon-heartbeat" [] {})))
  nil)

(defn start!
  "Start one database interest and shared heartbeat scheduler."
  [{::keys [writer render configuration]}]
  (let [connections (atom {})
        heartbeat-executor
        (Executors/newSingleThreadScheduledExecutor)
        service {::writer writer
                 ::render render
                 ::configuration configuration
                 ::connections connections
                 ::connection-permits
                 (Semaphore. (int (::maximum-connections configuration)))
                 ::heartbeat-executor heartbeat-executor}
        refresh!
        (fn [transaction-report]
          (let [database (:db-after transaction-report)
                groups (group-by ::render-key (vals @connections))]
            (doseq [[_ equivalent] groups]
              (when-let [connection (first equivalent)]
                (let [element (render database (::render-input connection))]
                  (run! #(enqueue-latest! (::mailbox %) element)
                        equivalent))))))]
    (db.host/listen!
     writer
     {:seon.db/key ::data-feed
      :seon.db/handler refresh!})
    (.scheduleAtFixedRate
     ^ScheduledExecutorService heartbeat-executor
     ^Runnable #(heartbeat! service)
     (long (::heartbeat-interval-ms configuration))
     (long (::heartbeat-interval-ms configuration))
     TimeUnit/MILLISECONDS)
    service))

(defn stop!
  "Stop the database interest, scheduler, and every open connection."
  [{::keys [writer connections heartbeat-executor]}]
  (db.host/unlisten! writer ::data-feed)
  (.shutdownNow ^ScheduledExecutorService heartbeat-executor)
  (run! stop-connection! (vals @connections))
  (reset! connections {})
  nil)

(defn open!
  "Return one SDK-owned SSE response for a database view."
  [{::keys [connections connection-permits configuration render writer]
    :as service}
   request render-key render-input]
  (let [connection-id (random-uuid)
        compression (::compression configuration)]
    (if-not (.tryAcquire ^Semaphore connection-permits)
      {:status 503
       :headers {"Content-Type" "text/plain; charset=utf-8"
                 "Cache-Control" "no-store"}
       :body "web-render connection capacity reached"}
      (let [released? (AtomicBoolean. false)
            release!
            #(when (.compareAndSet released? false true)
               (.release ^Semaphore connection-permits))]
        (try
          (http-kit/->sse-response
           request
           {adapter/write-profile (selected-write-profile compression)
            adapter/on-open
            (fn [sse]
              (let [mailbox
                    (ArrayBlockingQueue.
                     (int (::mailbox-depth configuration)))
                    connection
                    {::connection-id connection-id
                     ::render-key render-key
                     ::render-input render-input
                     ::mailbox mailbox
                     ::sse sse
                     ::draining? (atom false)}
                    thread
                    (Thread/startVirtualThread
                     (bound-fn []
                       (try
                         (drain-connection! connection)
                         (finally
                           (swap! connections dissoc connection-id)
                           (release!)))))
                    connection (assoc connection ::thread thread)
                    database (db.host/resolve-db! writer)
                    element (render database render-input)]
                (swap! connections assoc connection-id connection)
                (enqueue-latest! mailbox element)))
            adapter/on-close
            (fn [_sse & _]
              (if-let [connection (get @connections connection-id)]
                (do
                  (swap! connections dissoc connection-id)
                  (stop-connection! connection))
                (release!)))})
          (catch Throwable throwable
            (release!)
            (throw throwable)))))))

(defn snapshot
  "Return bounded feed-registry diagnostics."
  [{::keys [connections]}]
  {::connection-count (count @connections)
   ::draining-count (count (filter #(deref (::draining? %))
                                   (vals @connections)))})

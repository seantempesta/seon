(ns seon.web.feed
  "JVM Datastar SSE connections with latest-complete-state delivery."
  (:require [seon.config.resolve :as config.resolve]
            [seon.db :as db]
            [seon.reactive :as reactive]
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
  "Start one shared reactive feed and heartbeat scheduler."
  [{::keys [render configuration]}]
  (let [leaf db/*leaf*
        connections (atom {})
        heartbeat-executor
        (Executors/newSingleThreadScheduledExecutor)
        service {::service-id (random-uuid)
                 ::leaf leaf
                 ::render render
                 ::configuration configuration
                 ::connections connections
                 ::connection-permits
                 (Semaphore. (int (::maximum-connections configuration)))
                 ::heartbeat-executor heartbeat-executor}]
    (reactive/configure!
     (merge config.resolve/default-reactive-policy
            (::reactive-policy configuration)))
    (.scheduleAtFixedRate
     ^ScheduledExecutorService heartbeat-executor
     ^Runnable #(heartbeat! service)
     (long (::heartbeat-interval-ms configuration))
     (long (::heartbeat-interval-ms configuration))
     TimeUnit/MILLISECONDS)
    service))

(defn stop!
  "Stop reactive registrations, scheduler, and every open connection."
  [{::keys [leaf connections heartbeat-executor]}]
  (.shutdownNow ^ScheduledExecutorService heartbeat-executor)
  (run! stop-connection! (vals @connections))
  (reset! connections {})
  (binding [db/*leaf* leaf]
    (reactive/close!))
  nil)

(defn open!
  "Return one SDK-owned SSE response for a database view."
  [{::keys [service-id leaf connections connection-permits configuration render]
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
                           ;; SSE close interrupts the drain thread. Clear that
                           ;; local signal before the same virtual thread writes
                           ;; the observable database-interest release.
                           (Thread/interrupted)
                           (try
                             (binding [db/*leaf* leaf]
                               (reactive/unobserve!
                                {::reactive/key
                                 [::data-feed service-id render-key]
                                 ::reactive/consumer-key connection-id}))
                             (finally
                               (release!)))))))
                    connection (assoc connection ::thread thread)
                    reactive-key [::data-feed service-id render-key]]
                (swap! connections assoc connection-id
                       (assoc connection ::reactive-key reactive-key))
                (binding [db/*leaf* leaf]
                  (reactive/observe!
                   {::reactive/key reactive-key
                    ::reactive/consumer-key connection-id
                    ::reactive/compute
                    (fn [database]
                      {::db/value (render database render-input)
                       ::db/read-evidence :all})
                    ::reactive/notify #(enqueue-latest! mailbox %)}))))
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

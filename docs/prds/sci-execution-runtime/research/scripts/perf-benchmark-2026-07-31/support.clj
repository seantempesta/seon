(ns bench.support
  "Shared benchmark instrumentation, loaded into the live bench cluster JVM.

  The SSE clients are raw sockets opened INSIDE the cluster JVM. They are real
  HTTP/1.1 connections to the live http-kit server over loopback — the same
  path a browser tab takes — but they share one `System/nanoTime` with the
  committer, which is what makes a sub-millisecond commit-to-bytes measurement
  honest. A browser's own morph cost is NOT included; it is measured in
  render-pipeline-design-2026-07-29.md (1.2-1.5 ms for a 250-event morph)."
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.cluster.store :as store]))

(def instance (get @@(ns-resolve 'seon.cluster 'running-instances) "bench"))
(def connection (:seon.boot/cluster-connection instance))
(def routing (:seon.cluster.agent/routing instance))
(def web-port (get-in instance [:seon.boot/advertisement :seon.render.web/port]))
(def root-eid
  (d/q '[:find ?e . :where [?e :seon.cluster.agent/id "root"]] @connection))

(defn quantiles
  "Median / p95 / max over nanosecond samples plus the derived rate."
  [samples]
  (let [sorted (vec (sort samples))
        n (count sorted)
        at (fn [q] (nth sorted (min (dec n) (long (* q n)))))
        total (reduce + 0 sorted)
        ms (fn [v] (/ (Math/round (/ (double v) 1000.0)) 1000.0))]
    {:n n
     :median-ms (ms (at 0.5))
     :p95-ms (ms (at 0.95))
     :max-ms (ms (peek sorted))
     :per-s (Math/round (/ (* 1e9 n) (double (max 1 total))))}))

(defn timed
  "Run `f` `warm` times untimed, then `n` times, returning nanosecond samples."
  [warm n f]
  (dotimes [i warm] (f i))
  (mapv (fn [i] (let [s (System/nanoTime)] (f (+ warm i)) (- (System/nanoTime) s)))
        (range n)))

;;; the SSE client ------------------------------------------------------------

(defn sse-client
  "One real SSE connection to /feed/{id}.

  The reader is a virtual thread that timestamps every probe token the moment
  its bytes leave the socket read call. Delivering `::stalled` makes the
  connection STOP READING without closing it, which is the stalled-consumer
  case: the socket receive window fills, http-kit's pending bytes rise, and
  the server's connection-owned writer parks on its drain completion."
  [agent-id]
  (let [socket (java.net.Socket. "127.0.0.1" (int web-port))
        output (.getOutputStream socket)
        input (.getInputStream socket)
        state (atom {::bytes 0 ::events 0 ::tokens {} ::open true})
        ;; `::stalled` is the FLAG; `::release` is the block. A stalled reader
        ;; parks on the undelivered release promise, so no byte leaves the
        ;; socket receive buffer — which is what makes the server-side
        ;; backpressure real rather than simulated.
        stalled (atom false)
        release (promise)]
    (.write output (.getBytes (str "GET /feed/" agent-id " HTTP/1.1\r\n"
                                   "Host: 127.0.0.1:" web-port "\r\n"
                                   "Accept: text/event-stream\r\n"
                                   "Connection: keep-alive\r\n\r\n")
                              "UTF-8"))
    (.flush output)
    (.start
     (Thread/ofVirtual)
     (fn []
       (let [buffer (byte-array 262144)]
         (try
           (loop []
             (when @stalled @release)
             (let [taken (.read input buffer)]
               (when (pos? taken)
                 (let [text (String. buffer 0 taken "UTF-8")
                       now (System/nanoTime)
                       found (re-seq #"PROBE-[0-9a-z-]+" text)]
                   (swap! state
                          (fn [current]
                            (-> current
                                (update ::bytes + taken)
                                (update ::events + (count (re-seq #"event: " text)))
                                (update ::tokens
                                        (fn [seen]
                                          (reduce (fn [m t] (if (contains? m t) m (assoc m t now)))
                                                  seen found))))))
                   (recur)))))
           (catch Throwable _ nil)
           (finally (swap! state assoc ::open false))))))
    {::socket socket ::state state ::stalled stalled ::release release}))

(defn tokens
  "The token → arrival-nanos map this connection has observed."
  [client] (::tokens @(::state client)))

(defn byte-count
  "Bytes this connection has read."
  [client] (::bytes @(::state client)))

(defn event-count
  "SSE events this connection has read."
  [client] (::events @(::state client)))

(defn stall!
  "Make this connection stop reading without closing its socket."
  [client] (reset! (::stalled client) true))

(defn close-client!
  "Release a client, whether or not it is stalled."
  [client]
  (reset! (::stalled client) false)
  (deliver (::release client) :released)
  (.close ^java.net.Socket (::socket client)))

(defn await-initial-paint!
  "Block until every client has received its initial full paint."
  [clients]
  (doseq [client clients]
    (loop []
      (when (zero? (long (byte-count client)))
        (Thread/sleep 5)
        (recur)))))

;;; probes --------------------------------------------------------------------

(defn probe-message
  "One message row whose rendered content carries a unique token."
  [token]
  [{:seon.cluster.message/id (str "probe-" token)
    :seon.cluster.message/to root-eid
    :seon.cluster.message/content (str "PROBE-" token)
    :seon.cluster.message/at (java.util.Date.)}])

(defn run-probes!
  "Commit `probes` token-carrying facts; wait for every client to see each.

  Three timestamps per probe: t0 before the transaction, t1 when the report
  returns, t2 when the token's bytes leave a connection's socket read."
  [clients probes]
  (let [results (atom [])]
    (dotimes [i probes]
      (let [token (str (long (rand 1e12)) "-" i)
            marker (str "PROBE-" token)
            t0 (System/nanoTime)
            _ (store/transact! connection (probe-message token))
            t1 (System/nanoTime)
            deadline (+ t1 (* 20 1000000000))]
        (loop []
          (let [seen (mapv (fn [c] (get (tokens c) marker)) clients)]
            (if (or (every? some? seen) (> (System/nanoTime) deadline))
              (swap! results conj
                     {:t0 t0 :t1 t1 :commit (- t1 t0)
                      :seen (filterv some? seen)
                      :missing (count (filter nil? seen))})
              (do (Thread/onSpinWait) (recur)))))))
    @results))

(defn report
  "Print one probe run's distributions."
  [label results]
  (let [commit->wire (mapcat (fn [{:keys [t0 seen]}] (map #(- % t0) seen)) results)
        settled->wire (mapcat (fn [{:keys [t1 seen]}] (map #(- % t1) seen)) results)]
    (println label
             :connections (+ (count (:seen (first results)))
                             (long (:missing (first results))))
             :missing (reduce + (map :missing results))
             :commit (pr-str (quantiles (map :commit results)))
             :commit->wire (pr-str (quantiles commit->wire))
             :settled->wire (pr-str (quantiles settled->wire)))))

;;; process measurements ------------------------------------------------------

(defn memory
  "Resident set size (MiB, from ps) and JVM heap used/committed (MiB)."
  []
  (let [runtime (Runtime/getRuntime)
        pid (.pid (java.lang.ProcessHandle/current))
        rss (-> (:out (shell/sh "ps" "-o" "rss=" "-p" (str pid)))
                str/trim parse-long)]
    {:rss-mib (Math/round (/ (double rss) 1024.0))
     :heap-used-mib (Math/round (/ (double (- (.totalMemory runtime)
                                              (.freeMemory runtime)))
                                   1048576.0))
     :heap-committed-mib (Math/round (/ (double (.totalMemory runtime)) 1048576.0))
     :threads (.getThreadCount (java.lang.management.ManagementFactory/getThreadMXBean))}))

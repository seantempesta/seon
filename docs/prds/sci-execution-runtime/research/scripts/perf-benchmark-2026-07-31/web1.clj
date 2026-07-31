;; Section 2a — web infrastructure for the benchmark, plus page GET latency.
;;
;; The SSE clients are raw sockets opened INSIDE the cluster JVM. They are real
;; HTTP/1.1 connections to the live http-kit server over loopback — the same
;; path a browser tab takes — but they share one `System/nanoTime` with the
;; committer, which is what makes a sub-millisecond commit->bytes measurement
;; honest. A browser's own morph cost is NOT included and is measured
;; elsewhere (render-pipeline-design-2026-07-29.md: 1.2-1.5 ms).
(require '[datahike.api :as d]
         '[seon.cluster.store :as store]
         '[seon.cluster.agent :as agent]
         '[clojure.string :as str])

(def inst (get @@(ns-resolve 'seon.cluster 'running-instances) "bench"))
(def cconn (:seon.boot/cluster-connection inst))
(def routing (:seon.cluster.agent/routing inst))
(def web-port (get-in inst [:seon.boot/advertisement :seon.render.web/port]))
(def root-eid (d/q '[:find ?e . :where [?e :seon.cluster.agent/id "root"]] @cconn))

(defn quantiles [samples]
  (let [sorted (vec (sort samples))
        n (count sorted)
        at (fn [q] (nth sorted (min (dec n) (long (* q n)))))
        total (reduce + 0 sorted)]
    {:n n
     :median-ms (/ (Math/round (/ (at 0.5) 1000.0)) 1000.0)
     :p95-ms (/ (Math/round (/ (at 0.95) 1000.0)) 1000.0)
     :max-ms (/ (Math/round (/ (at 0.999) 1000.0)) 1000.0)
     :per-s (Math/round (/ (* 1e9 n) (double total)))}))

;;; the agent graph is STOPPED for the render measurements ---------------------
;; A committed message wakes the agent, and one real turn is a ~40 s local
;; model call that would compete with the very thing being measured. Disarming
;; leaves the wake ROUTE absent, so `wake/route!` offers to the armer instead
;; and the RENDER wake — which is unconditional, one per transaction report —
;; is unaffected. This measures the delivery pipeline, not the turn loop.
(agent/disarm! {:seon.cluster.agent/id "root"
                :seon.cluster.agent/routing routing})
(println :ARMED (keys (:seon.cluster.agent/armed @routing)))

;;; page GET latency ----------------------------------------------------------
(def http-client (java.net.http.HttpClient/newHttpClient))

(defn get-page [path]
  (let [request (-> (java.net.http.HttpRequest/newBuilder
                     (java.net.URI. (str "http://127.0.0.1:" web-port path)))
                    (.GET) (.build))]
    (.body (.send http-client request
                  (java.net.http.HttpResponse$BodyHandlers/ofString)))))

(doseq [path ["/" "/agent/root"]]
  (let [cold (let [s (System/nanoTime)] (get-page path) (- (System/nanoTime) s))
        body (get-page path)
        warm (mapv (fn [_] (let [s (System/nanoTime)]
                             (get-page path)
                             (- (System/nanoTime) s)))
                   (range 200))]
    (println :GET path :bytes (count body)
             :cold-ms (/ (Math/round (/ cold 1000.0)) 1000.0)
             (pr-str (quantiles warm)))))

;;; the SSE client ------------------------------------------------------------
(defn sse-client
  "One real SSE connection to /feed/{id}. Returns a state map.

  The reader is a virtual thread that timestamps EVERY probe token the
  moment its bytes leave the socket read call: `::tokens` maps the token
  to that `System/nanoTime`. `::stalled` (a promise) makes a connection
  STOP READING without closing, which is the stalled-consumer case."
  [agent-id]
  (let [socket (java.net.Socket. "127.0.0.1" (int web-port))
        out (.getOutputStream socket)
        in (.getInputStream socket)
        state (atom {::bytes 0 ::events 0 ::tokens {} ::open true})
        stalled (promise)]
    (.write out (.getBytes (str "GET /feed/" agent-id " HTTP/1.1\r\n"
                                "Host: 127.0.0.1:" web-port "\r\n"
                                "Accept: text/event-stream\r\n"
                                "Connection: keep-alive\r\n\r\n")
                           "UTF-8"))
    (.flush out)
    (.start
     (Thread/ofVirtual)
     (fn []
       (let [buffer (byte-array 65536)]
         (try
           (loop []
             (when (realized? stalled) (deref stalled))
             (let [read (.read in buffer)]
               (when (pos? read)
                 (let [text (String. buffer 0 read "UTF-8")
                       now (System/nanoTime)
                       tokens (re-seq #"PROBE-[0-9a-z-]+" text)]
                   (swap! state
                          (fn [current]
                            (-> current
                                (update ::bytes + read)
                                (update ::events + (count (re-seq #"event: " text)))
                                (update ::tokens
                                        (fn [seen]
                                          (reduce (fn [m t] (if (contains? m t) m (assoc m t now)))
                                                  seen tokens))))))
                   (recur)))))
           (catch Throwable _ nil)
           (finally (swap! state assoc ::open false))))))
    {::socket socket ::state state ::stalled stalled}))

(defn close-client! [client]
  (deliver (::stalled client) false)
  (.close ^java.net.Socket (::socket client)))

(defn await-initial-paint!
  "Block until every client has received its initial full paint."
  [clients]
  (doseq [client clients]
    (loop []
      (when (zero? (long (::bytes @(::state client))))
        (Thread/sleep 5)
        (recur)))))

(println :WEB1-READY :port web-port :root-eid root-eid)

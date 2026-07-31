;; Sustained churn and the stalled consumer.
;;
;; The churn shape UPSERTS ONE message row's content, so the page's byte size
;; stays constant and the measurement is not confounded by a growing
;; transcript. Coalescing is read from the render proc's own ping state
;; (`::passes`) against the number of commits.
(require '[bench.support :as s] '[seon.cluster.store :as store]
         '[seon.render.web :as web] '[seon.config :as config]
         '[clojure.core.async.flow :as flow] '[datahike.api :as d])
(def caps (config/result-caps (config/effective @s/connection "bench")))
(def graph (:seon.flow/graph s/instance))
(defn passes []
  (get-in (flow/ping graph 500) [:seon.render.web/render :clojure.core.async.flow/state ::web/passes]))
(defn churn-row [token]
  [{:seon.cluster.message/id "churn-row"
    :seon.cluster.message/to s/root-eid
    :seon.cluster.message/content (str "PROBE-" token)
    :seon.cluster.message/at (java.util.Date.)}])
(defn page-bytes []
  (reduce + 0 (map (comp count val)
                   (web/page-of {:seon.db/db @s/connection :seon.cluster.agent/id "root"
                                 :seon.sci.admit/caps caps
                                 :seon.cluster.run/live-processes #{}}))))
;; retract everything the earlier rounds accumulated so the churn page is small
(let [eids (mapv first (d/q '[:find ?e :where [?e :seon.cluster.message/id ?id]
                              [(clojure.string/starts-with? ?id "probe-")]] @s/connection))]
  (when (seq eids) (store/transact! s/connection (mapv (fn [e] [:db/retractEntity e]) eids))))
(println :PAGE-BYTES (page-bytes) :PASSES (passes))

(defn churn!
  "Commit for `seconds`, timestamping each token, and report delivery lag."
  [clients seconds]
  (let [start (System/nanoTime)
        deadline (+ start (* seconds 1000000000))
        passes-before (passes)
        emitted (atom [])]
    (loop [i 0]
      (when (< (System/nanoTime) deadline)
        (let [token (str "c" i "-" (long (rand 1e9)))
              t0 (System/nanoTime)]
          (store/transact! s/connection (churn-row token))
          (swap! emitted conj [(str "PROBE-" token) t0 (System/nanoTime)])
          (recur (inc i)))))
    (Thread/sleep 2000)
    {:commits (count @emitted)
     :seconds seconds
     :commits-per-s (/ (Math/round (/ (* 100.0 (count @emitted)) seconds)) 100.0)
     :render-passes (- (passes) passes-before)
     :delivered
     (mapv (fn [client]
             (let [seen (s/tokens client)
                   lags (keep (fn [[marker t0 _]] (when-let [t2 (get seen marker)] (- t2 t0)))
                              @emitted)]
               {:delivered (count lags)
                :dropped (- (count @emitted) (count lags))
                :lag (s/quantiles lags)}))
           clients)}))

(println :MEM-BEFORE (pr-str (s/memory)))
(def tabs (mapv (fn [_] (s/sse-client "root")) (range 10)))
(s/await-initial-paint! tabs)
(Thread/sleep 500)
(def churn-result (churn! tabs 60))
(println :CHURN (pr-str (dissoc churn-result :delivered)))
(doseq [d (take 3 (:delivered churn-result))] (println :CHURN-TAB (pr-str d)))
(println :CHURN-DELIVERED-ALL (pr-str (mapv :delivered (:delivered churn-result))))
(println :MEM-AFTER-CHURN (pr-str (s/memory)) :page-bytes (page-bytes))
(run! s/close-client! tabs)
(Thread/sleep 500)

;;; the stalled consumer -------------------------------------------------------
(def healthy (mapv (fn [_] (s/sse-client "root")) (range 5)))
(def stalled (s/sse-client "root"))
(s/await-initial-paint! (conj healthy stalled))
(Thread/sleep 500)
(s/stall! stalled)
(println :STALLED-BYTES-AT-STALL (s/byte-count stalled))
(def stalled-result (churn! healthy 30))
(println :STALL-CHURN (pr-str (dissoc stalled-result :delivered)))
(doseq [d (take 2 (:delivered stalled-result))] (println :STALL-HEALTHY-TAB (pr-str d)))
(println :STALLED-BYTES-AFTER (s/byte-count stalled)
         :stalled-open (::s/open @(::s/state stalled))
         :mem (pr-str (s/memory)))
(run! s/close-client! healthy)
(s/close-client! stalled)
(println :WEB5-DONE)

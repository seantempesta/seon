;; The stalled-consumer case, with a reader that genuinely stops reading.
(load-file "/Users/sean/src/seon/tmp/bench/support.clj")
(require '[bench.support :as s] '[seon.cluster.store :as store]
         '[seon.render.web :as web] '[seon.config :as config]
         '[clojure.core.async.flow :as flow] '[org.httpkit.server :as http])
(def caps (config/result-caps (config/effective @s/connection "bench")))
(def graph (:seon.flow/graph s/instance))
(defn passes []
  (get-in (flow/ping graph 500) [:seon.render.web/render :clojure.core.async.flow/state ::web/passes]))
(defn churn-row [token]
  [{:seon.cluster.message/id "churn-row" :seon.cluster.message/to s/root-eid
    :seon.cluster.message/content (str "PROBE-" token)
    :seon.cluster.message/at (java.util.Date.)}])
(defn churn! [clients seconds]
  (let [deadline (+ (System/nanoTime) (* seconds 1000000000))
        before (passes) emitted (atom [])]
    (loop [i 0]
      (when (< (System/nanoTime) deadline)
        (let [token (str "c" i "-" (long (rand 1e9))) t0 (System/nanoTime)]
          (store/transact! s/connection (churn-row token))
          (swap! emitted conj [(str "PROBE-" token) t0])
          (recur (inc i)))))
    (Thread/sleep 2000)
    {:commits (count @emitted) :render-passes (- (passes) before)
     :commits-per-s (/ (Math/round (/ (* 100.0 (count @emitted)) seconds)) 100.0)
     :delivered (mapv (fn [c]
                        (let [seen (s/tokens c)
                              lags (keep (fn [[m t0]] (when-let [t2 (get seen m)] (- t2 t0))) @emitted)]
                          {:delivered (count lags) :dropped (- (count @emitted) (count lags))
                           :lag (s/quantiles lags)}))
                      clients)}))
(def healthy (mapv (fn [_] (s/sse-client "root")) (range 5)))
(def stalled (s/sse-client "root"))
(s/await-initial-paint! (conj healthy stalled))
(Thread/sleep 500)
(s/stall! stalled)
(Thread/sleep 300)
(println :AT-STALL :stalled-bytes (s/byte-count stalled) :mem (pr-str (s/memory)))
(def result (churn! healthy 30))
(println :STALL-CHURN (pr-str (dissoc result :delivered)))
(doseq [d (take 2 (:delivered result))] (println :HEALTHY-TAB (pr-str d)))
(println :AFTER :stalled-bytes-read (s/byte-count stalled)
         :stalled-reader-open (::s/open @(::s/state stalled))
         :mem (pr-str (s/memory)))
;; release the stalled reader and see how much it was holding
(s/close-client! stalled)
(Thread/sleep 1000)
(println :AFTER-RELEASE :mem (pr-str (s/memory)))
(def result2 (churn! healthy 15))
(println :POST-STALL-CHURN (pr-str (dissoc result2 :delivered)))
(doseq [d (take 2 (:delivered result2))] (println :POST-HEALTHY-TAB (pr-str d)))
(run! s/close-client! healthy)
(println :WEB6-DONE)

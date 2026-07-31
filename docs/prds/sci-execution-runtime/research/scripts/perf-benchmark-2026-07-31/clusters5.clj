;; Cross-cluster interference, self-contained: fresh SSE clients on scale-0,
;; measured alone, then with four sibling clusters committing continuously in
;; the same JVM and process-root store.
(require '[bench.support :as s] '[seon.cluster.store :as store] '[datahike.api :as d])
(def targets user/targets)
(def measured (first targets))
(defn report2 [label results]
  (println label :probes (count results) :missing (reduce + (map :missing results))
           :commit (pr-str (s/quantiles (map (fn [r] (- (:t1 r) (:t0 r))) results)))
           :commit->wire (pr-str (s/quantiles (mapcat (fn [{:keys [t0 seen]}] (map #(- % t0) seen)) results)))
           :settled->wire (pr-str (s/quantiles (mapcat (fn [{:keys [t1 seen]}] (map #(- % t1) seen)) results)))))
(defn churn-row [root token]
  [{:seon.cluster.message/id "churn-row" :seon.cluster.message/to root
    :seon.cluster.message/content (str "PROBE-" token) :seon.cluster.message/at (java.util.Date.)}])
(defn probe-set! [target clients probes]
  (let [results (atom [])]
    (dotimes [i probes]
      (let [token (str "y" i "-" (long (rand 1e9))) marker (str "PROBE-" token)
            t0 (System/nanoTime)
            _ (store/transact! (:connection target) (churn-row (:root target) token))
            t1 (System/nanoTime) deadline (+ t1 (* 5 1000000000))]
        (loop []
          (let [seen (mapv (fn [c] (get (s/tokens c) marker)) clients)]
            (if (or (every? some? seen) (> (System/nanoTime) deadline))
              (swap! results conj {:t0 t0 :t1 t1 :seen (filterv some? seen)
                                   :missing (count (filter nil? seen))})
              (do (Thread/onSpinWait) (recur)))))))
    @results))
(alter-var-root #'s/web-port (constantly (:port measured)))
(def clients (mapv (fn [_] (s/sse-client "root")) (range 5)))
(s/await-initial-paint! clients)
(Thread/sleep 800)
(report2 :ALONE (probe-set! measured clients 20))
(def stop (atom false))
(def counted (atom 0))
(doseq [t (rest targets)]
  (.start (Thread/ofVirtual)
          (fn [] (loop [i 0]
                   (when-not @stop
                     (store/transact! (:connection t) (churn-row (:root t) (str "n" i)))
                     (swap! counted inc) (recur (inc i)))))))
(Thread/sleep 3000)
(reset! counted 0)
(def t0 (System/nanoTime))
(report2 :WITH-4-NEIGHBOURS (probe-set! measured clients 20))
(println :NEIGHBOUR-RATE-PER-S (/ (Math/round (* 100.0 (/ (* 1e9 @counted) (- (System/nanoTime) t0)))) 100.0))
(reset! stop true)
(Thread/sleep 3000)
(report2 :ALONE-AGAIN (probe-set! measured clients 20))
(println :MEM (pr-str (s/memory)))
(run! s/close-client! clients)
(alter-var-root #'s/web-port (constantly 7953))
(println :CLUSTERS5-DONE)

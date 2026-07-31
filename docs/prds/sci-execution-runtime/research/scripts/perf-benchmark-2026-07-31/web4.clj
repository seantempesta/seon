;; Controlled delta latency, ARMER PAUSED and root DISARMED so no turn runs
;; during the measurement. Probe messages are retracted between configurations
;; and the page's derived byte size is printed each time, so any residual drift
;; is visible rather than assumed away.
(require '[bench.support :as s] '[seon.cluster.store :as store]
         '[seon.render.web :as web] '[seon.config :as config] '[datahike.api :as d])
(def caps (config/result-caps (config/effective @s/connection "bench")))
(defn probe-eids []
  (mapv first (d/q '[:find ?e :where [?e :seon.cluster.message/id ?id]
                     [(clojure.string/starts-with? ?id "probe-")]] @s/connection)))
(defn reset-page! []
  (let [eids (probe-eids)]
    (when (seq eids)
      (store/transact! s/connection (mapv (fn [e] [:db/retractEntity e]) eids)))
    (count eids)))
(defn paint [] (web/page-of {:seon.db/db @s/connection :seon.cluster.agent/id "root"
                             :seon.sci.admit/caps caps
                             :seon.cluster.run/live-processes #{}}))
(defn page-cost []
  (let [page (paint)]
    {:blocks (count page) :bytes (reduce + 0 (map (comp count val) page))
     :derive-ms (:median-ms (s/quantiles (s/timed 3 20 (fn [_] (paint)))))}))
(println :RESET (reset-page!))
(println :PAGE-BASE (pr-str (page-cost)))
(doseq [n [1 10 50 1 10 50]]
  (reset-page!)
  (let [clients (mapv (fn [_] (s/sse-client "root")) (range n))]
    (s/await-initial-paint! clients)
    (Thread/sleep 400)
    (let [before (mapv s/byte-count clients)
          before-events (mapv s/event-count clients)
          results (s/run-probes! clients 25)]
      (s/report (keyword (str "DELTA-" n)) results)
      (println :TRAFFIC n :initial-paint-bytes (first before) :initial-events (first before-events)
               :delta-bytes-per-conn (- (s/byte-count (first clients)) (first before))
               :delta-events-per-conn (- (s/event-count (first clients)) (first before-events))
               :total-bytes-all-conns (reduce + (map s/byte-count clients)))
      (println :PAGE-AFTER n (pr-str (page-cost)) (pr-str (s/memory))))
    (run! s/close-client! clients)
    (Thread/sleep 600)))
(println :RESET-FINAL (reset-page!) (pr-str (page-cost)))
(println :WEB4-DONE)

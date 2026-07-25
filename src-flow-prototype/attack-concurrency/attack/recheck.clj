(ns attack.recheck
  "ATTACK 6 -- what exactly went wrong in the n=10 wake stampede?
   Each of 10 agents gets ONE message and ONE 1-step run. Correct end state:
   every :agent/counter = 1, every run has exactly one :ok receipt."
  (:require [datahike.api :as d]
            [flow.driver :as driver]
            [flow.store :as store]))

(defn one-step [_] ["{:note \"tick\"}"])

(defn -main [& [root]]
  (let [n 10
        conn (store/fresh! (str (or root "/private/tmp/attack") "/rc")
                           {:config/lease-ms 600000})]
    (doseq [i (range n)] (d/transact conn {:tx-data [{:agent/id (str "z" i) :agent/counter 0}]}))
    (reset! driver/claims-lost 0)
    (driver/wake! conn "c0" one-step nil)
    (d/transact conn {:tx-data (mapv (fn [i] {:message/id (str "m" i)
                                              :message/to [:agent/id (str "z" i)]
                                              :message/body "go"})
                                     (range n))})
    (let [deadline (+ (System/currentTimeMillis) 60000)]
      (loop [] (when (and (< (System/currentTimeMillis) deadline)
                          (or (seq (d/q '[:find ?r :where [?r :run/open? true]] (d/db conn)))
                              (< (count (d/q '[:find ?r :where [?r :run/id _]] (d/db conn))) n)))
                 (Thread/sleep 200) (recur))))
    (Thread/sleep 6000)
    (let [db (d/db conn)]
      (println "\n    counters:" (pr-str (into (sorted-map)
                                               (map (fn [i] [(str "z" i)
                                                             (:agent/counter (d/pull db [:agent/counter] [:agent/id (str "z" i)]))]))
                                               (range n))))
      (println "    runs:" (count (d/q '[:find ?r :where [?r :run/id _]] db))
               " receipts:" (pr-str (sort (d/q '[:find ?o (count ?e) :where [?e :seon.eval/outcome ?o]] db))))
      (println "    log lines per agent:"
               (pr-str (into (sorted-map)
                             (map (fn [i] [(str "z" i)
                                           (count (d/q '[:find [?l ...] :in $ ?a :where
                                                         [?e :agent/id ?a] [?e :agent/log ?l]] db (str "z" i)))]))
                             (range n))))
      (println "    lost CAS claims:" @driver/claims-lost))
    (System/exit 0)))

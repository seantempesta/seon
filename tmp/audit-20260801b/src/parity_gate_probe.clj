(ns parity-gate-probe
  (:require [clojure.test :as test]
            [seon.repl-parity-test :as parity]))

(defn- row-vars
  []
  (->> (ns-interns 'seon.repl-parity-test)
       vals
       (filter (comp :parity/row meta))))

(defn run-probe
  "Count every parity row and falsify both promotion and deletion behavior."
  [& _]
  (let [vars (vec (row-vars))
        tested-ids (set (map (comp :parity/row meta) vars))
        pending-ids (set (map :parity/row parity/pending-rows))
        all-ids (into tested-ids pending-ids)
        family-counts
        (into (sorted-map)
              (map (fn [[family ids]] [family (count ids)]))
              (group-by first all-ids))
        promotion-counters (ref test/*initial-report-counters*)
        deletion-counters (ref test/*initial-report-counters*)
        victim (first vars)
        victim-meta (meta victim)]
    (binding [test/*report-counters* promotion-counters]
      (#'seon.repl-parity-test/check-row!
       "AUDIT-PROMOTION"
       :known-divergence
       (fn [] {:parity/pass? true
               :parity/expected :stock
               :parity/actual :stock})))
    (try
      (alter-meta! victim dissoc :parity/row :parity/known-divergence)
      (binding [test/*report-counters* deletion-counters]
        (#'seon.repl-parity-test/report-fixture (fn [])))
      (finally
        (reset-meta! victim victim-meta)))
    (prn
     {:audit/tested (count tested-ids)
      :audit/known-divergences
      (count (filter (comp :parity/known-divergence meta) vars))
      :audit/passing
      (count (remove (comp :parity/known-divergence meta) vars))
      :audit/pending (count pending-ids)
      :audit/total (count all-ids)
      :audit/family-counts family-counts
      :audit/promotion-probe-counters @promotion-counters
      :audit/cardinality-loss-probe-counters @deletion-counters})))

(apply run-probe *command-line-args*)

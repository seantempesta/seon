(ns r3.bench-inc
  "Per-commit reconciler cost, incremental (observe + actions) against the full pass, on the
  synthetic main: jolt -A:test run -m r3.bench-inc N... (N work tasks, 2N tasks)."
  (:require [r3.fx :as fx] [seon.reconcile :as r] [r3.oracle :as oracle] [seon.task :as task] [seon.db :as pdb] [datahike.api :as d]))

(defn- now [] (System/nanoTime))
(defn- med [xs] (let [v (vec (sort xs))] (nth v (quot (count v) 2))))
(defn- p95 [xs] (let [v (vec (sort xs))] (nth v (min (dec (count v)) (long (* 0.95 (count v)))))))
(defn- r3 [x] (/ (Math/round (* 1000.0 x)) 1000.0))

(defn- txs
  "The writes a busy root makes, cycling: an attempt starts (task k), it ends, it is accepted,
  a task is released."
  [n k]
  (let [w (str "r3/p" (+ 9 (* 10 (mod k (max 1 (quot n 10))))))   ; tasks that had no attempt (k mod 10 = 9)
        aid (str w "#b" k)]
    [(task/attempt-start-tx aid w fx/boot :ab {:branch (task/default-branch w) :model nil :max-turns 8} 0 "p")
     [{:seon.attempt/id aid :seon.attempt/status :ended :seon.attempt/basis-t 1
       :seon.attempt/results [{:seon.proof/test (symbol "app.proofs" "p9-test") :seon.proof/status :pass}]}]
     [{:seon.attempt/id aid :seon.attempt/status :done}]
     (task/execution-tx [(str w "-merge")] :running)]))

(defn measure [n rounds]
  (let [conn (fx/load! n (max 1 (quot (* 3 n) 10)))
        t0 (now) idx (r/index @conn) list-ms (/ (- (now) t0) 1e6)
        _ (r/actions idx)
        samples (loop [k 0 idx idx acc []]
                  (if (= k rounds)
                    acc
                    (let [[idx acc] (reduce (fn [[idx acc] tx]
                                              (let [rep (d/transact conn {:tx-data (vec tx)})
                                                    ops (pdb/report->tx rep) db (:db-after rep)
                                                    a0 (now) idx' (r/observe idx db ops) a1 (now) _ (r/actions idx') a2 (now)]
                                                [idx' (conj acc {:observe (/ (- a1 a0) 1e6) :actions (/ (- a2 a1) 1e6) :ops (count ops)})]))
                                            [idx acc] (txs n k))]
                      (recur (inc k) idx acc))))
        db @conn
        full (vec (for [_ (range 3)] (let [a (now)] (oracle/reconcile db) (/ (- (now) a) 1e6))))
        tot (map #(+ (:observe %) (:actions %)) samples)]
    {:tasks (* 2 n) :attempts (count (:attempts idx)) :list-ms (r3 list-ms)
     :per-tx {:n (count samples) :ops-med (med (map :ops samples))
              :observe-med (r3 (med (map :observe samples))) :actions-med (r3 (med (map :actions samples)))
              :total-med (r3 (med tot)) :total-p95 (r3 (p95 tot))}
     :full-pass-med (r3 (med full))}))

(defn -main [& args]
  (println :guarantees (or (System/getenv "SEON_GUARANTEES") "on"))
  (doseq [n (map parse-long args)] (println (pr-str (measure n 10))))
  (System/exit 0))

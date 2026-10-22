(ns r3.bench-rec
  "Reconciler pass cost on the synthetic main at several sizes:
  jolt -A:test run -m r3.bench-rec N..."
  (:require [r3.fx :as fx] [r3.oracle :as r] [seon.task :as task] [clojure.string :as str]))

(defn- ms [f] (let [t (System/nanoTime)] (f) (/ (- (System/nanoTime) t) 1e6)))
(defn- med [xs] (let [v (vec (sort xs))] (nth v (quot (count v) 2))))
(defn- r2 [x] (/ (Math/round (* 100.0 x)) 100.0))

(defn measure [n reps]
  (fx/load! n (max 1 (quot (* 3 n) 10)) (fn [conn] (let [db @conn
        _ (dotimes [_ 2] (r/reconcile db))
        alloc0 (System/nanoTime)
        tasks (vec (for [_ (range reps)] (ms #(task/tasks db))))
        atts (vec (for [_ (range reps)] (ms #(task/attempts db))))
        views (vec (for [_ (range reps)] (ms #(r/view db))))
        v (r/view db)
        decide (vec (for [_ (range reps)] (ms #(r/reconcile-view v))))
        full (vec (for [_ (range reps)] (ms #(r/reconcile db))))
        acts (r/reconcile db)]
    {:n-tasks (* 2 n) :attempts (count (task/attempts db))
     :actions (frequencies (map :op acts))
     :tasks-ms (r2 (med tasks)) :attempts-ms (r2 (med atts)) :view-ms (r2 (med views))
     :decide-ms (r2 (med decide)) :pass-ms (r2 (med full)) :pass-min (r2 (apply min full))}))))

(defn -main [& args]
  (println :guarantees (or (System/getenv "SEON_GUARANTEES") "on"))
  (doseq [n (map parse-long args)]
    (println (pr-str (measure n (if (> n 2000) 3 7)))))
  (System/exit 0))

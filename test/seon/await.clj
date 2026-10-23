(ns seon.await
  "What a test waits on, as facts: a watch on main's connection, never a poll or a deadline. Alone in
  its namespace so a root (which imports src only) can load it by path: (load-file path)."
  (:require [seon.reconcile :as r] [datahike.api :as d]))

(defn await-db
  "Main's first db value (now or after a later commit) for which (pred db) holds: a watch
  on main's connection runs pred once per commit. No deadline, no polling."
  [sys pred]
  (let [p (promise) k (keyword "seon.await" (str "await-" (random-uuid)))
        a (:wrapped-atom (:conn sys))]
    (add-watch a k (fn [_ _ _ db] (when (pred db) (deliver p db))))
    (let [db @(:conn sys)] (when (pred db) (deliver p db)))
    (let [db @p] (remove-watch a k) db)))

(defn defect
  "A root proc's defect (a :platform problem) whose record main wrote after t, or nil:
  facts, not clocks."
  [db t]
  (some #(when (some (fn [dt] (> (:tx dt) t)) (d/datoms db :eavt (:e %) :seon.error/last-ms)) (into {} (d/entity db (:e %))))
        (filter #(= :platform (:v %)) (d/datoms db :aevt :seon.error/class))))

(defn await-quiet
  "The reconciler's quiescence report ({:t :root-causes}) for main at or after t: nothing runs,
  nothing is left to judge, nothing is ready. Waits as long as work goes on, or until a
  root proc's defect is recorded on main after t ({:ended :defect :problem p}): a step
  that threw dropped its outputs, so the flight it carried never ends and quiescence never
  comes. No clock."
  [sys t]
  (let [db (await-db sys #(or (when-let [q (r/quiescent %)] (>= (:t q) t)) (defect % t)))]
    (if-let [q (and (r/quiescent db) (>= (:t (r/quiescent db)) t) (r/quiescent db))]
      q
      (let [p (defect db t)] (println :defect p) {:ended :defect :problem p}))))

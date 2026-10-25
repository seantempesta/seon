(ns seon.await
  "What a test waits on, as facts: a watch on main's connection, never a poll or a deadline. Alone in
  its namespace so a root (which imports src only) can load it by path: (load-file path)."
  (:refer-clojure :exclude [defn])
  (:require [seon.contract :refer [defn]] [seon.reconcile :as r] [seon.root] [datahike.api :as d]))

(defn await-quiet
  "The reconciler's quiescence report ({:t :root-causes}) for main at or after t: nothing runs,
  nothing is left to judge, nothing is ready. Waits as long as work goes on, or until a
  root proc's defect is recorded on main after t ({:ended :defect :problem p}): a step
  that threw dropped its outputs, so the flight it carried never ends and quiescence never
  comes. No clock. A quiet spell that began before t and goes on writes no later report, so the
  reconciler's own quiet at or after t answers too (seon.root/await-db, named): that spell's
  report, committed before the answer, which stands for every t of the spell, at the t the
  reconciler was quiet at. A root that stopped answers its error."
  {:malli/schema [:=> [:cat [:map [:conn :seon.host/conn]] :int]
                  [:or :seon.root/quiescent [:map [:ended [:= :defect]] [:problem [:maybe :map]]] :seon.root/no-agent]]}
  [sys t]
  ;; a sys with no reconciler (a test branch's conn alone) has no quiet to ask: a commit ends it
  (let [db (seon.root/await-db sys (when (:judge sys) (str "the reconciler's quiescence at or after t " t))
                               #(or (when-let [q (r/quiescent %)] (>= (:t q) t)) (seon.root/defect % t)))]
    (cond (= :seon.root/quiet-before-true (:seon.error/type db)) (assoc (r/quiescent @(:conn sys)) :t (:t db))
          (= :seon.root/no-agent (:seon.error/type db)) db
          :else (if-let [q (and (r/quiescent db) (>= (:t (r/quiescent db)) t) (r/quiescent db))]
                  q
                  (let [p (seon.root/defect db t)] (println :defect p) {:ended :defect :problem p})))))

(ns seon.await
  "What a test waits on, as facts: a wait on main through seon.system/await-db, never a poll or a deadline. Alone in
  its namespace so a root (which imports src only) can load it by path: (load-file path)."
  (:refer-clojure :exclude [defn])
  (:require [seon.contract :as contract :refer [defn]] [seon.reconcile :as r] [seon.system] [datahike.api :as d]))

(defn quiet-answer
  "What await-quiet answers for main db after t, or nil while it waits on: the reconciler's quiescence report ({:t
  :root-causes}) when its :t is at or after t, else a root proc's defect recorded on main after t ({:ended :defect :problem
  p}): a step that threw dropped its outputs, so the flight it carried never ends and quiescence never comes."
  {:malli/schema [:=> [:cat :int :seon.host/db] [:maybe [:or :seon.system/quiescent [:map [:ended [:= :defect]] [:problem [:maybe :map]]]]]]}
  [t db]
  (if-let [q (and (r/quiescent db) (>= (:t (r/quiescent db)) t) (r/quiescent db))]
    q
    (when-let [p (seon.system/defect db t)] {:ended :defect :problem p})))

(defn await-quiet
  "quiet-answer for main at or after t, waited on through seon.system/await-db: no clock. A quiet spell that began before t
  and goes on writes no later report, so the reconciler's own quiet at or after t answers too (await-db, named): that
  spell's report, at the t the reconciler was quiet at. A root that stopped answers its error."
  {:malli/schema [:=> [:cat :seon.system/sys :int]
                  [:or :seon.system/quiescent [:map [:ended [:= :defect]] [:problem [:maybe :map]]] :seon.system/no-agent]]}
  [sys t]
  (let [db (seon.system/await-db sys (str "the reconciler's quiescence at or after t " t) #(some? (quiet-answer t %)))]
    (cond (= :seon.system/quiet-before-true (:seon.error/type db)) (assoc (r/quiescent @(:conn sys)) :t (:t db))
          (contract/error? db) db
          :else (quiet-answer t db))))

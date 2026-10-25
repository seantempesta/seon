(ns seon.await
  "What a test waits on, as facts: a watch on main's connection, never a poll or a deadline. Alone in
  its namespace so a root (which imports src only) can load it by path: (load-file path)."
  (:refer-clojure :exclude [defn])
  (:require [seon.contract :refer [defn]] [seon.reconcile :as r] [seon.root] [datahike.api :as d]))

(defn await-db
  "seon.root/await-db, the one wait on a fact on main (kept here for the tests that load this file)."
  {:malli/schema [:=> [:cat [:map [:conn :seon.host/conn]] ifn?] :seon.host/db]}
  [sys pred] (seon.root/await-db sys pred))

(defn defect
  "seon.root/defect, the one reading of a root proc's defect after t."
  {:malli/schema [:=> [:cat :seon.host/db :int] [:maybe :map]]}
  [db t] (seon.root/defect db t))

(defn await-quiet
  "The reconciler's quiescence report ({:t :root-causes}) for main at or after t: nothing runs,
  nothing is left to judge, nothing is ready. Waits as long as work goes on, or until a
  root proc's defect is recorded on main after t ({:ended :defect :problem p}): a step
  that threw dropped its outputs, so the flight it carried never ends and quiescence never
  comes. No clock."
  {:malli/schema [:=> [:cat [:map [:conn :seon.host/conn]] :int]
                  [:or :seon.root/quiescent [:map [:ended [:= :defect]] [:problem [:maybe :map]]]]]}
  [sys t]
  (let [db (await-db sys #(or (when-let [q (r/quiescent %)] (>= (:t q) t)) (defect % t)))]
    (if-let [q (and (r/quiescent db) (>= (:t (r/quiescent db)) t) (r/quiescent db))]
      q
      (let [p (defect db t)] (println :defect p) {:ended :defect :problem p}))))

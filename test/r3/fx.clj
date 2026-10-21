(ns r3.fx
  "R3's synthetic main: n work tasks, each with its merge task (seon.template/with-merge),
  attempts spread over the lifecycle the way a busy swarm holds them. Pure tx builders plus
  one loader; nothing here is product code."
  (:require [seon.task :as task] [seon.template :as tpl] [seon.db :as pdb] [datahike.api :as d]))

(def boot "r3-boot")

(defn decls [n]
  (tpl/with-merge
    (vec (for [k (range n)]
           {:seon.task/ns 'r3 :seon.task/name (str "p" k)
            :seon.task/plan (str "Make `app.par/p" k "` pass `app.proofs/p" k "-test`.")
            :seon.task/proofs #{(symbol "app.proofs" (str "p" k "-test"))}}))))

(defn- env [id] {:branch (task/default-branch (clojure.string/replace id #"-merge$" "")) :model nil :max-turns 8})

(defn- attempt [aid id status extra]
  (into (task/attempt-start-tx aid id boot :a0 (env id) 0)
        [(merge {:seon.attempt/id aid :seon.attempt/status status} extra)]))

(defn- results [k] [{:seon.proof/test (symbol "app.proofs" (str "p" k "-test")) :seon.proof/status :pass}])

(defn attempts-tx
  "The attempts of work task k and its merge, by k mod 10 (see ns doc)."
  [k t]
  (let [w (str "r3/p" k) m (str "r3/p" k "-merge")
        done-w (attempt (str w "#1") w :done {:seon.attempt/results (results k) :seon.attempt/basis-t 1})]
    (case (mod k 10)
      (0 1 2 3) (concat done-w (attempt (str m "#1") m :merged {:seon.attempt/results (results k) :seon.attempt/merged-t (+ t k) :seon.attempt/basis-t 1}))
      (4 5) (attempt (str w "#1") w :running {})
      6 (attempt (str w "#1") w :ended {:seon.attempt/results (results k) :seon.attempt/basis-t 1 :seon.attempt/outcome :green})
      7 (concat done-w (attempt (str m "#1") m :running {}))
      8 (concat done-w (attempt (str m "#1") m :ended {:seon.attempt/results (results k) :seon.attempt/basis-t 1 :seon.attempt/outcome :green}))
      9 [])))

(defn load!
  "A fresh in-memory main holding n work tasks (2n tasks) and their attempts; answers conn."
  [n max-running]
  (let [[_ conn] (pdb/test-branch!)
        ds (decls n)]
    (d/transact conn {:tx-data (task/boot-tx boot max-running)})
    (doseq [part (partition-all 400 ds)]
      (d/transact conn {:tx-data (task/create-tx @conn (vec part))}))
    (doseq [part (partition-all 400 (map #(task/task-id 'r3 (:seon.task/name %)) ds))]
      (d/transact conn {:tx-data (task/execution-tx (vec part) :running)}))
    (doseq [part (partition-all 200 (range n))]
      (d/transact conn {:tx-data (vec (mapcat #(attempts-tx % 1000) part))}))
    conn))

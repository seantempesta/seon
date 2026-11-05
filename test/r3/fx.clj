(ns r3.fx
  "R3's synthetic main: n work tasks, each with its merge task (seon.template/with-merge),
  the work of each spread over the lifecycle the way a busy swarm holds it. Pure tx builders plus
  one loader; nothing here is product code."
  (:require [seon.test-store :as ts] [seon.task :as task] [seon.template :as tpl] [seon.db :as pdb] [datahike.api :as d] [seon.config]))

(def boot "r3-boot")

(defn decls [n]
  (tpl/with-merge
    (vec (for [k (range n)]
           {:seon.task/ns 'r3 :seon.task/name (str "p" k)
            :seon.task/plan (str "Make `app.par/p" k "` pass `app.proofs/p" k "-test`.")
            :seon.task/proofs #{(symbol "app.proofs" (str "p" k "-test"))}}))))

(defn- env [id] {:branch (task/default-branch (clojure.string/replace id #"-merge$" "")) :model nil :max-turns (seon.config/value :seon.config.agent/max-turns)})

(defn- work
  "Task id's agent and how its work stands (outcome), as a root would have recorded them: what seon.task/start-tx writes
  and, beside the outcome, the members (the stored answer of its judging)."
  [id outcome members]
  (let [k (task/agent-key boot :a0) e (env id)]
    [(merge {:seon.task/id id :seon.task/outcome outcome :seon.task/agent k} members)
     {:db/id k :seon.agent/key k :seon.agent/id :a0 :seon.agent/task [:seon.task/id id] :seon.agent/branch (:branch e)
      :seon.agent/boot boot :seon.agent/started-ms 0 :seon.agent/max-turns (:max-turns e)}]))

(defn runs-tx
  "How the work of task k and its merge stands, by k mod 10 (see ns doc)."
  [k t]
  (let [w (str "r3/p" k) m (str "r3/p" k "-merge")]
    (case (mod k 10)
      (0 1 2 3) (concat (work w :done {}) (work m :merged {:seon.task/merged-t (+ t k)}))
      (4 5) (work w :running {})
      6 (work w :told {:seon.task/reasons [{:reason :proof-unknown :symbol (symbol "app.proofs" (str "p" k "-test"))}]})
      7 (concat (work w :done {}) (work m :running {}))
      8 (concat (work w :done {}) (work m :ready {}))
      9 [])))

(defn load!
  "(f conn) on a branch of the test store holding n work tasks (2n tasks) and how their
  work stands; the branch is deleted when f returns."
  [n max-running f]
  (ts/test-branch! (fn [_ conn] (let [ds (decls n)]
    (d/transact conn {:tx-data (task/boot-tx boot max-running)})
    (doseq [part (partition-all 400 ds)]
      (d/transact conn {:tx-data (task/create-tx @conn (vec part))}))
    (doseq [part (partition-all 400 (map #(task/task-id 'r3 (:seon.task/name %)) ds))]
      (d/transact conn {:tx-data (task/execution-tx (vec part) :running)}))
    (doseq [part (partition-all 200 (range n))]
      (d/transact conn {:tx-data (vec (mapcat #(runs-tx % 1000) part))}))
    (f conn)))))

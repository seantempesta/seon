(ns r3.inc-test
  "The incremental reconciler equals the full one: after every commit of a random history,
  (actions (observe idx db ops)) = (reconcile-view (view db)) and = (actions (index db))."
  (:require [clojure.test :refer [deftest is testing]] [r3.fx :as fx] [seon.reconcile :as r] [r3.oracle :as oracle] [seon.task :as task]
            [seon.db :as pdb] [datahike.api :as d] [seon.proofs :as proofs] [seon.config :as config]))

(defn- rand-step
  "One random write a root or observer makes on main (tx-data), drawn with rng."
  [db ^java.util.Random rng k]
  (let [;; the ids alone, from their indexes: the step draws one task, never a read of every one of them
        ts (vec (sort (map :v (d/datoms db :aevt :seon.task/id))))
        pick (fn [v] (when (seq v) (nth v (.nextInt rng (count v)))))
        t (pick ts)
        env (fn [id] (r/desired-env (task/task db id)))
        t0 (:max-tx db)]
    (case (.nextInt rng 9)
      0 (when t (task/execution-tx [t] (pick [:running :held :cancelled :running])))
      1 (when t (task/start-tx db t (pick ["r3-boot" "old-boot"]) (pick [:ax :ay]) (env t) (inc k)))
      2 (when t [{:seon.task/id t :seon.task/outcome (pick [:ready :gate :done :merged :parked :cap :told :closed :lost :dead :killed :behind :running])
                  :seon.task/merged-t (+ 900 (.nextInt rng 300))}])
      3 (let [nm (str "n" k) needs (set (remove nil? [t (pick ts)]))]
          (into (task/create-tx db [(cond-> {:seon.task/ns 'r3 :seon.task/name nm :seon.task/plan "p"
                                             :seon.task/proofs #{(symbol "app.proofs" (str "p" (.nextInt rng 30) "-test"))}}
                                      (seq needs) (assoc :seon.task/needs needs)
                                      (zero? (.nextInt rng 3)) (assoc :seon.task/kind :merge))])
                (task/execution-tx [(str "r3/" nm)] :running)))
      4 (when t (task/env-tx t {:max-turns (inc (.nextInt rng 9))}))
      5 [{:seon.root/id :root :seon.config.agent/max-running (inc (.nextInt rng 40)) :seon.config.merge/max-running 1}]
      6 (when (zero? (.nextInt rng 4)) [{:seon.root/id :root :seon.root/boot (pick ["r3-boot" "old-boot"])}])
      ;; a need onto a plain work task (they need nothing), from a task that is not one: no cycle
      7 (let [work (filterv #(re-find #"/p[0-9]+$" %) ts) src (pick (filterv #(not (re-find #"/p[0-9]+$" %)) ts))]
          (when (and src (seq work)) [[:db/add [:seon.task/id src] :seon.task/needs [:seon.task/id (pick work)]]]))
      8 [{:db/id "x" :ns/name (symbol (str "unrelated" k))}])))

(defn- acyclic? [db] (some? (try (r/actions (r/index db)) (catch Throwable _ nil))))

(defn- equals-full
  "A random history from seed, 150 commits: after each, the incremental reconciler's actions
  against the oracle's."
  [seed]
  (fx/load! 30 6 (fn [conn] (let [rng (java.util.Random. seed)
                    ;; one merge at a time: the octopus merge degenerates to M1's merge queue, the oracle's rule
          _ (d/transact conn {:tx-data [{:seon.root/id :root :seon.config.merge/max-running 1}]})]
      (loop [k 0 idx (r/index @conn) bad 0]
        (if (= k 150)
          (is (zero? bad) (str "seed " seed))
          (let [tx (rand-step @conn rng k)
                rep (when (seq tx) (try (d/transact conn {:tx-data (vec tx)}) (catch Throwable _ nil)))]
            (if (or (nil? rep) (not (acyclic? @conn)))
              (recur (inc k) (r/index @conn) bad)
              (let [_ (is (< (:max-tx (:db-before rep)) (:max-tx (:db-after rep))) "main's t moves forward with each commit")
                    idx' (r/observe idx (:db-after rep) (pdb/report->tx rep))
                    full (oracle/reconcile-view (oracle/view (:db-after rep)))
                    incr (r/actions idx')
                    merges (filterv #(= :merge (:op %)) full)
                    octopus (first (filter #(= :octopus-merge (:op %)) incr))
                    live-merge? (some (:live idx') (:merging idx'))
                    ok (and (= (remove #(= :merge (:op %)) full) (remove #(= :octopus-merge (:op %)) incr))
                            (if octopus
                              (= (mapv #(select-keys % [:task]) merges) (:members octopus))
                              (or (empty? merges) live-merge?)))]
                (when-not ok
                  (println :seed seed :k k :tx (pr-str tx))
                  (println :full (pr-str (remove (set incr) full)))
                  (println :inc (pr-str (remove (set full) incr))))
                (recur (inc k) idx' (if ok bad (inc bad))))))))))))

(deftest incremental-equals-full
  ;; one seed in the automatic run, five in the full one (:seon.config.proofs/seeds)
  (doseq [seed (let [s (proofs/test-seed)] (map #(+ s %) (range (config/value :seon.config.proofs/seeds))))]
    (equals-full seed)))

(deftest a-refused-work-end-gets-its-verdict-in-both
  ;; the seed whose history first held what slice 6' changed: a task ended :green with a
  ;; regression, which judging (seon.reconcile/judgment) sends back to its session and stores on the task, where both
  ;; reconcilers read it; 23 of its 140 commits disagreed when one derived it and the other did not
  (equals-full 913731333918154371))

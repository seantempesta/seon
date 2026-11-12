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
                                      ;; a merge task is on the branch of a task it needs (a merge step is read from data, never the kind)
                                      (zero? (.nextInt rng 3))
                                      (-> (assoc :seon.task/kind :merge)
                                          (cond-> (seq needs) (assoc :seon.task/branch (let [n (first needs)] (or (:seon.task/branch (d/pull db [:seon.task/branch] [:seon.task/id n])) (task/default-branch n)))))))])
                (task/execution-tx [(str "r3/" nm)] :running)))
      4 (when t (task/env-tx t {:max-turns (inc (.nextInt rng 9))}))
      5 (seon.test-store/root-tx {:seon.config.agent/max-running (inc (.nextInt rng 40)) :seon.config.merge/max-running 1})
      6 (when (zero? (.nextInt rng 4)) [{:seon.system/id :root :seon.system/boot (pick ["r3-boot" "old-boot"])}])
      ;; a need onto a plain work task (they need nothing), from a task that is not one: no cycle
      7 (let [work (filterv #(re-find #"/p[0-9]+$" %) ts) src (pick (filterv #(not (re-find #"/p[0-9]+$" %)) ts))]
          (when (and src (seq work)) [[:db/add [:seon.task/id src] :seon.task/needs [:seon.task/id (pick work)]]]))
      8 [{:db/id "x" :ns/name (symbol (str "unrelated" k))}])))

(defn- acyclic? [db] (some? (try (r/actions (r/index db)) (catch Throwable _ nil))))

(defn- adds-need?
  "Does tx add a :seon.task/needs edge (rand-step's kinds 3 and 7)? Only a need can close a cycle, so only after one is the
  whole db reindexed to check (acyclic?): a full index a commit was a third of this test's ticks."
  [tx]
  (boolean (some #(or (and (map? %) (contains? % :seon.task/needs)) (and (vector? %) (= :seon.task/needs (nth % 2 nil)))) tx)))

(defn- agrees
  "After commit rep (a transact's report), the incremental reconciler (idx observed) against the oracle's full one:
  [ok idx' full incr]."
  [idx rep]
  (let [idx' (r/observe idx (:db-after rep) (pdb/report->tx rep))
        full (oracle/reconcile-view (oracle/view (:db-after rep)))
        incr (r/actions idx')
        merges (filterv #(= :merge (:op %)) full)
        octopus (first (filter #(= :octopus-merge (:op %)) incr))
        live-merge? (some (:live idx') (:merging idx'))
        ok (and (= (remove #(= :merge (:op %)) full) (remove #(= :octopus-merge (:op %)) incr))
                (if octopus
                  (= (mapv #(select-keys % [:task]) merges) (:members octopus))
                  (or (empty? merges) live-merge?)))]
    [ok idx' full incr]))

(def ^:private commits
  "The commits of a random history: the depth the 1e8-tick cap allows at ~0.9M ticks a commit (the oracle's full reconcile and
  the incremental observe over a 4-task world growing to ~15). A history the pinned seed needed (140) is a scenario of its own."
  105)

(defn- equals-full
  "A random history from seed, `commits` long, in a world of n work tasks (r3.fx/world, a value in memory): after each, the
  incremental reconciler's actions against the oracle's."
  [n seed]
  (let [rng (java.util.Random. seed)
        ;; one merge at a time: the octopus merge degenerates to M1's merge queue, the oracle's rule
        db0 (d/db-with (fx/world n 6) (vec (seon.test-store/root-tx {:seon.config.merge/max-running 1})))]
    (loop [k 0 db db0 idx (r/index db0) bad 0]
      (if (= k commits)
        (is (zero? bad) (str "seed " seed))
        (let [tx (rand-step db rng k)
              rep (when (seq tx) (try (d/with db (vec tx)) (catch Throwable _ nil)))
              db' (if rep (:db-after rep) db)]
          (if (or (nil? rep) (and (adds-need? tx) (not (acyclic? db'))))
            (recur (inc k) db' (r/index db') bad)
            (let [_ (is (< (:max-tx (:db-before rep)) (:max-tx (:db-after rep))) "main's t moves forward with each commit")
                  [ok idx' full incr] (agrees idx rep)]
              (when-not ok
                (println :seed seed :k k :tx (pr-str tx))
                (println :full (pr-str (remove (set incr) full)))
                (println :inc (pr-str (remove (set full) incr))))
              (recur (inc k) db' idx' (if ok bad (inc bad))))))))))

(deftest ^{:seon.test/estimate {:ticks 100000000}} incremental-equals-full
  ;; one seed in the automatic run, five in the full one (:seon.config.proofs/seeds). The world is the fewest tasks every step kind
  ;; reaches: 2 work tasks (4 with their merges; kind 7 needs a plain work task and one that is not), in memory (r3.fx/world).
  ;; Estimate: 105 commits (`commits`) x ~0.9M ticks a commit (the oracle's named pull and reconcile ~0.25M, the incremental observe
  ;; ~0.4M: seon.task/entity-value pulls a changed task's whole schema, ~31k ticks a task, filed; the step's own reads) = ~95M
  (doseq [seed (let [s (proofs/test-seed)] (map #(+ s %) (range (config/value :seon.config.proofs/seeds))))]
    (equals-full 2 seed)))

(deftest ^{:seon.test/estimate {:ticks 5000000}} a-refused-work-end-gets-its-verdict-in-both
  ;; what slice 6' changed (pinned once by seed 913731333918154371 of a 30-task world, 140 commits): a work task that ended :green
  ;; with a regression is judged :told, its reasons stored on the task (seon.reconcile/judgment), and its agent waits for a message;
  ;; both reconcilers read the stored answer, so neither starts it again, where one that derived it disagreed (23 of 140 commits).
  ;; Estimate: the 4-task world and one commit compared, ~5M ticks of its own
  (let [db (fx/world 2 6)
        idx (r/index db)
        id (task/task-id 'r3 "p0")
        rep (d/with db [{:seon.task/id id :seon.task/outcome :told
                         :seon.task/reasons [{:reason :regression :symbol 'app.proofs/p1-test}]}])
        [ok _ full incr] (agrees idx rep)]
    (is (= :told (:seon.task/outcome (d/pull (:db-after rep) [:seon.task/outcome] [:seon.task/id id]))) "the verdict is stored on the task")
    (is ok (str "both reconcilers read the stored verdict: full " (pr-str full) " incremental " (pr-str incr)))
    (is (not-any? #(= id (:task %)) incr) "and neither starts the told task again: its agent waits for a message")))

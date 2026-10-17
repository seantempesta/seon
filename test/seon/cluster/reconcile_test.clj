(ns seon.cluster.reconcile-test
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [seon.agent :as-alias agent]
            [seon.cluster.reconcile :as reconcile]
            [seon.flow.actions :as-alias fa]
            [seon.schema :as schema]
            [seon.task :as-alias task]))

(defn- handle
  {:malli/schema [:=> [:cat [:int {:min 0}]] :seon.task/handle]}
  [i]
  (format "t%011d" i))

(defn- closed
  "`observed` with each action's first closing answer applied: the state its actor's write leaves."
  {:malli/schema [:=> [:cat :seon.flow.actions/observed :seon.flow.actions/reconcile-actions] :seon.flow.actions/observed]}
  [observed actions]
  (reduce (fn [o {::fa/keys [op] h ::task/handle b ::task/branch}]
            (if h
              (update-in o [::fa/tasks h ::fa/task-state] #(first (:closed (reconcile/effects op %))))
              (update-in o [::fa/branch-states b] #(first (:closed (reconcile/effects op %))))))
          observed
          actions))

(def ^:private nothing-yet
  {::fa/execution :held ::fa/needs :none ::fa/target :none ::fa/environment :complete ::fa/branch :absent
   ::fa/config :none ::fa/attempt :none ::fa/outcome :none ::fa/step :none ::fa/agent-env :none ::fa/graph :absent
   ::fa/publication :none})

(defn- observed
  "An observed value: the i-th of `changes` (each over a task with nothing yet) is the i-th oldest task, on its own
  branch unless `branches` names one; then `branch-states`."
  {:malli/schema [:=> [:cat [:sequential :map] [:sequential :seon.flow.actions/branch-state] [:map-of :int :seon.task/branch]]
                  :seon.flow.actions/observed]}
  [changes branch-states branches]
  {::fa/tasks (into {} (map-indexed (fn [i change]
                                      (let [s (merge nothing-yet change)]
                                        [(handle i) (cond-> {::fa/task-state s
                                                             ::task/branch (get branches i (keyword (str "task-" (handle i))))
                                                             ::fa/created-t i}
                                                      (not= :none (::fa/attempt s)) (assoc ::agent/id (str "agent-" i)))])))
                changes)
   ::fa/branch-states (into {} (map-indexed (fn [i s] [(keyword (str "branch-" i)) s])) branch-states)})

(defn- ops
  {:malli/schema [:=> [:cat [:sequential :seon.flow.actions/reconcile-action]]
                  [:vector [:tuple :seon.flow.actions/op [:maybe :seon.task/handle]]]]}
  [actions]
  (mapv (juxt ::fa/op ::task/handle) actions))

(deftest ^{:seon.test/long "Enumerates the 13,968 possible task states from the registry, matches each against every row, walks every state's closure and reconciles the whole observed value of 13,968 tasks to its fixpoint: 3,805 ms in a check JVM, 0.6 s of it the work itself and the rest the instrumented contracts of about 40,000 calls and each round's whole observed value."
           :seon.test/long-ms 10000}
  the-transition-table-is-exhaustive-deterministic-and-convergent
  (let [opts {:registry (:seon.schema.projection/registry (schema/handed-projection))}
        leaves (fn leaves [s] (case (m/type s)
                                :enum (m/children s)
                                (:or :and) (mapcat leaves (m/children s))
                                ::m/schema (leaves (m/deref s))
                                []))
        values (fn [k] (let [s (m/schema k opts)] (filterv (m/validator s) (distinct (leaves (m/deref s))))))
        ;; a state schema's dimensions in order and its named constraints, read from the registry
        space (fn [k] (let [[shape & constraints] (m/children (m/deref (m/schema k opts)))]
                        {:dimensions (mapv first (m/entries shape))
                         :constraints (mapv (fn [c] (let [[_ _ [_ & entries]] (m/form (m/deref c))]
                                                      {:id (m/form c) :when (into {} (for [[d v] entries] [d (set (rest v))]))}))
                                            constraints)}))
        violated (fn [constraints state]
                   (filterv (fn [{condition :when}] (every? (fn [[d vs]] (contains? vs (get state d))) condition)) constraints))
        ;; backtracking over the dimensions, pruning a partial state once a constraint whose dimensions are all assigned matches it
        enumerate (fn [{:keys [dimensions constraints]} value-of]
                    (let [position (zipmap dimensions (range))
                          by-last (group-by (fn [c] (apply max-key position (keys (:when c)))) constraints)]
                      (reduce (fn [states d]
                                (into [] (for [s states
                                               v (value-of d)
                                               :let [s' (assoc s d v)]
                                               :when (empty? (violated (by-last d) s'))]
                                           s')))
                              [{}]
                              dimensions)))
        matching (fn [table state]
                   (mapv ::fa/row (filter (fn [r] (every? (fn [[k allowed]] (if (set? allowed)
                                                                              (contains? allowed (get state k))
                                                                              (= allowed (get state k))))
                                                          (::fa/when r)))
                                          table)))
        successors (fn [table derive-state state external?]
                     (for [op (::fa/then (reconcile/row table (derive-state state)))
                           :let [{done :closed outside :external} (reconcile/effects op state)]
                           s' (if external? (concat done outside) done)]
                       [op s']))
        closure (fn [table derive-state constraints states]
                  (let [done (java.util.HashSet.)
                        walk (fn walk [cycles s on-path]
                               (cond (.contains done s) cycles
                                     (contains? on-path s) (conj cycles s)
                                     :else (let [cycles (reduce (fn [cs [_ s']] (walk cs s' (conj on-path s)))
                                                                cycles
                                                                (successors table derive-state s false))]
                                             (.add done s)
                                             cycles)))]
                    {:invalid (into [] (for [s states
                                             [op s'] (successors table derive-state s true)
                                             :let [ids (mapv :id (violated constraints s'))]
                                             :when (seq ids)]
                                         {:state s :op op :successor s' :violates ids}))
                     :cycles (reduce (fn [cs s] (walk cs s #{})) [] states)}))
        task-space (space :seon.flow.actions/task-state)
        branch-space (space :seon.flow.actions/branch-state)
        states (enumerate task-space values)
        branch-states (enumerate branch-space (constantly [false true]))]
    (testing "the state space is the twelve enums less the seventeen named constraints"
      (is (= 839808 (reduce * (map (comp count values) (:dimensions task-space)))))
      (is (= 17 (count (:constraints task-space))))
      (is (= 13968 (count states)))
      (is (= 8 (count branch-states))))
    (testing "1. every possible state matches exactly one row, and reconcile/row answers it"
      (is (= [] (into [] (comp (map (fn [s] [s (matching reconcile/transitions (reconcile/task-state s))]))
                               (remove (comp #{1} count second)))
                      states)))
      (is (= [] (into [] (comp (map (fn [s] [s (matching reconcile/branch-transitions s)]))
                               (remove (comp #{1} count second)))
                      branch-states)))
      (is (= [] (into [] (remove (fn [s] (let [derived (reconcile/task-state s)]
                                           (= (matching reconcile/transitions derived)
                                              [(::fa/row (reconcile/row reconcile/transitions derived))]))))
                      states))))
    (let [tasks (closure reconcile/transitions reconcile/task-state (:constraints task-space) states)
          branches (closure reconcile/branch-transitions identity (:constraints branch-space) branch-states)]
      (testing "2. every action, with every answer the world can give, leads only to possible states"
        (is (= [] (:invalid tasks)))
        (is (= [] (:invalid branches))))
      (testing "3. every state reaches a steady row without a cycle"
        (is (= [] (:cycles tasks)))
        (is (= [] (:cycles branches)))))
    (testing "4. plan, apply each action's closing answer, re-plan: the plan empties and stays empty"
      (let [cap (count states)
            [rounds plan replan] (loop [o (observed states branch-states {}) rounds 0]
                                   (let [plan (reconcile/reconcile o cap)]
                                     (if (or (empty? plan) (= 8 rounds))
                                       [rounds plan (reconcile/reconcile o cap)]
                                       (recur (closed o plan) (inc rounds)))))]
        (is (< 0 rounds 8))
        (is (= [] plan replan))))))

(deftest reconcile-closes-each-gap-once
  (let [ready {::fa/execution :running}
        working {::fa/execution :running ::fa/branch :current ::fa/config :converged ::fa/attempt :unended
                 ::fa/step :work-left ::fa/agent-env :same ::fa/graph :running}
        awaiting-done {::fa/execution :running ::fa/branch :current ::fa/config :converged ::fa/attempt :done
                       ::fa/step :unjudged ::fa/agent-env :same}]
    (doseq [[case-name changes cap branches expected]
            [["parallel start: two ready tasks both build" [ready ready] 4 {} [[:build (handle 0)] [:build (handle 1)]]]
             ["cap: five ready, cap 2, one open: only the oldest ready task starts"
              [working ready ready ready ready ready] 2 {} [[:build (handle 1)]]]
             ["cap 0: nothing starts, and an ended agent's graph still stops"
              [ready (assoc working ::fa/attempt :done) ready] 0 {} [[:stop (handle 1)]]]
             ["one branch: of two ready tasks on one branch, one starts" [ready ready] 4 {0 :shared 1 :shared}
              [[:build (handle 0)]]]
             ["held: nothing starts" [{}] 4 {} []]
             ["dependency wait: a need not yet done starts nothing" [(assoc ready ::fa/needs :pending)] 4 {} []]
             ["no re-run: an ended attempt awaiting its judgment is judged, not started again" [awaiting-done] 4 {}
              [[:judge (handle 0)]]]
             ["no re-run: a bailed task, held with a closed attempt, starts nothing"
              [(assoc awaiting-done ::fa/execution :held ::fa/outcome :closed ::fa/step :work-left)] 4 {} []]
             ["stale with work: an ended done attempt merges main forward, never rebuilds"
              [(assoc awaiting-done ::fa/target :main ::fa/branch :stale)] 4 {} [[:merge-main (handle 0)]]]
             ["cancel after end: closes as cancelled, never held" [(assoc awaiting-done ::fa/execution :cancelled)] 4 {}
              [[:close-cancelled (handle 0)]]]
             ["crash: an open unjudged attempt with no graph is judged"
              [(assoc working ::fa/graph :absent ::fa/step :unjudged)] 4 {} [[:judge (handle 0)]]]
             ["stop when undesired: a held task's running graph stops" [(assoc working ::fa/execution :held)] 4 {}
              [[:stop (handle 0)]]]
             ["restart, first half: a changed environment stops the graph" [(assoc working ::fa/agent-env :differs)] 4 {}
              [[:stop (handle 0)]]]
             ["restart, second half: the old attempt closes" [(assoc working ::fa/agent-env :differs ::fa/graph :absent)] 4 {}
              [[:close-environment (handle 0)]]]
             ["restart, fresh: a running task whose attempt closed starts its step again"
              [(assoc working ::fa/agent-env :differs ::fa/graph :absent ::fa/outcome :closed)] 4 {}
              [[:start-step (handle 0)]]]]]
      (testing case-name
        (let [o (observed changes [] branches)
              plan (reconcile/reconcile o cap)]
          (is (= expected (ops plan)))
          (is (not-any? (set (ops plan)) (ops (reconcile/reconcile (closed o plan) cap)))
              "each action's closing fact closes its gap"))))))

(deftest an-impossible-task-state-is-refused-naming-its-constraint
  (let [state (assoc nothing-yet ::fa/config :converged)
        refusal (try (reconcile/reconcile {::fa/tasks {"t00000000000" {::fa/task-state state ::task/branch :b
                                                                         ::fa/created-t 0}}
                                           ::fa/branch-states {}}
                                          4)
                     (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= :seon.instrument/contract-error (:seon.error/type refusal)))
    (is (= [:seon.flow.actions/config-without-branch] (mapv :seon.error/expected (:seon.error/problems refusal))))))

(ns seon.competency-grade-test
  "The context bench: a chain run is launched through `seon.task/chain!` and graded from its kept branch; its forms
  are classed, and tables compare by the noise rule."
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.cluster.registry :as registry]
            [seon.competency :as competency]
            [seon.config :as config]
            [seon.db :as db]
            [seon.flow :as flow]
            [seon.mine :as mine]
            [seon.batch]
            [seon.sci.eval :as sci.eval]
            [seon.turn.world :as world]
            [seon.test-support :as support]))

(defn- pass!
  "One db<->flow pass over `handle`, as root's proc runs it on a wake: synchronous."
  {:malli/schema [:=> [:cat :seon.agent/context-source] :nil]}
  [handle]
  (agent/db<->flow {:seon.turn.loop/cluster handle :seon.agent/routing (:seon.agent/routing handle)}
                   :seon.flow/plan :seon.agent/test-wake)
  nil)

(defn release-chain!
  "Stop the graphs db<->flow started on chain `branch` under `routing`, release their executions, retire the branch."
  {:malli/schema [:=> [:cat :seon.agent/routing :seon.store/store :seon.chain/branch] :nil]}
  [routing store branch]
  (when-let [child (get-in @routing [:seon.agent/children branch])]
    (doseq [started (keys (:seon.agent/started @child))]
      (agent/stop-flow! {:seon.agent/id started :seon.agent/routing child}))
    (doseq [execution (vals (:seon.agent/contexts @child))
            :when (or (:seon.agent/owns-connection? execution) (:seon.agent/owns-branch? execution))]
      (agent/release-context! execution)))
  (agent/release-chain-route! routing branch)
  (registry/retire-branch! {:seon.store/store store :seon.store/branch branch})
  nil)

(defn with-root
  "Call `body` with a cluster handle over `connection`, the executing member's own cluster with root homed on it,
  as boot arms root's."
  {:malli/schema [:=> [:cat :seon.db/connection [:=> [:cat :seon.agent/context-source] :seon.schema/value]] :seon.schema/value]}
  [connection body]
  (let [cluster-name (:seon.cluster/name (support/execution-handle connection))
        environment (support/environment cluster-name connection)]
    (support/transacted! connection (support/agent-tx (db/db connection) "root" 'user))
    (with-open [launcher (support/closeable
                          (flow/start-work-launcher!
                           {:seon.env/environment environment
                            :seon.flow/configuration (select-keys (config/effective (db/db connection) cluster-name)
                                                                  flow/flow-workload-attributes)})
                          flow/stop-work-launcher!)]
      (let [ctx (support/fork-cluster-ctx connection cluster-name)]
        (body (support/cluster-handle
               {:seon.env/environment (assoc environment :seon.flow/work-launcher @launcher)
                :seon.db/connection connection :seon.cluster/name cluster-name :seon.sci.eval/ctx ctx
                :seon.flow/executor (cluster/projection-executor (:seon.sci.eval/projection-state ctx))
                :seon.db.process/id cluster/boot-process-identity}))))))

(def ^:private fixture-task
  "A bench entry whose proof the harness defines red in the member's world and whose oracle redefines it green, as
  the db<->flow chain tests' fixed worlds do: launch and grade, not the bench tasks, are what it proves."
  {:seon.competency/id :fixture-proof
   :seon.competency/split :train
   :seon.competency/task {:seon.task/ns 'bench.fixture :seon.task/name "prove-it"
                          :seon.task/plan "Make `bench.fixture-proofs/proves-it` green."
                          :seon.task/proofs #{'bench.fixture-proofs/proves-it}}
   :seon.competency/oracle "(in-ns 'bench.fixture-proofs)\n(clojure.test/deftest proves-it (clojure.test/is (= 1 1)))"})

(defn launched
  "The grades of `runs` fixture-model runs of `fixture-task` launched on `handle`'s cluster, its proof defined red
  first, the model answering each request with `answer` (default: its oracle), while a thread runs db<->flow passes
  as root's proc would on each wake."
  {:malli/schema [:function [:=> [:cat :seon.agent/context-source :seon.db/connection pos-int?] [:vector :map]]
                  [:=> [:cat :seon.agent/context-source :seon.db/connection pos-int? [:fn ifn?]] [:vector :map]]]}
  ([handle connection runs] (launched handle connection runs (constantly (:seon.competency/oracle fixture-task))))
  ([handle connection runs answer]
   (support/transacted! connection (support/agent-tx (db/db connection) "bench-author" 'user))
   (support/define! (world/world {:seon.db/connection connection :seon.boot/cluster-name (:seon.cluster/name handle)
                                  :seon.sci.eval/ctx (sci.eval/fork-cluster-ctx (sci.eval/base-ctx (db/db connection))
                                                                                (db/db connection) connection)
                                  :seon.batch/sink (seon.batch/sink)
                                  :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms config/defaults)
                                  :seon.agent/id "bench-author"})
                    'bench.fixture-proofs
                    "(clojure.test/deftest proves-it (clojure.test/is (= 1 2)))\n")
   (support/with-provider
    (atom []) answer
    (fn [server]
      (let [done (async/promise-chan)
            ;; root's proc, woken by each chain's writes in a running cluster; a fixture cluster runs none
            root (async/thread (loop [] (when-not (async/poll! done) (pass! handle) (Thread/sleep 100) (recur)))
                               ::root-stopped)]
        (try
          (competency/launch!
           {:seon.db/connection connection :seon.store/store (agent/handle-store handle)
            :seon.competency/tasks [fixture-task]
            :seon.competency/variants
            [{:seon.competency/variant :fixture
              :seon.chain/config (assoc (support/provider-config server)
                                        :seon.config.ai/no-provider config/absent
                                        :seon.config.run/max-episode-runs 3)}]
            :seon.competency/runs runs :seon.competency/poll-ms 100
            :seon.competency/deadline-ms 50000})
          (finally
            (async/put! done true)
            (support/await-event! root ::root-stopped))))))))

(deftest ^{:seon.test/long "Two fixture-model chain runs of one task, each a chain fork, its prove pass, one model call and its proof step over db<->flow passes; measured 5.6 s."
           :seon.test/long-ms 20000}
  a-launch-grades-each-chain-run-from-its-branch
  (support/with-database
   (fn [connection]
     (with-root
      connection
      (fn [handle]
        (let [grades (launched handle connection 2)]
          (try
            (is (= (repeat 2 {:seon.competency/end :done :seon.competency/green? true :seon.competency/model-calls 1
                              :seon.competency/calls-to-green 1 :seon.competency/wasted-calls 0
                              :seon.competency/classes {:navigation 1 :progress 1} :seon.competency/cap 3})
                   (map #(select-keys % [:seon.competency/end :seon.competency/green? :seon.competency/model-calls
                                         :seon.competency/calls-to-green :seon.competency/wasted-calls
                                         :seon.competency/classes :seon.competency/cap])
                        grades))
                "each run's attempt ended done in one model call, read on its chain branch")
            (is (= [[:fixture-proof :fixture 2 2 1]]
                   (mapv (juxt :seon.competency/id :seon.competency/variant :seon.competency/runs
                               :seon.competency/green :seon.competency/score-median)
                         (competency/table grades)))
                "one table row per task and variant")
            (finally
              (doseq [branch (keep :seon.chain/branch grades)]
                (release-chain! (:seon.agent/routing handle) (agent/handle-store handle) branch))))))))))

(deftest ^{:seon.test/long "One fixture-model chain run as the bench launches it, then root's passes after its done."
           :seon.test/long-ms 20000}
  a-bench-chain-ends-done-unmerged-with-its-branch-kept
  (support/with-database
   (fn [connection]
     (with-root
      connection
      (fn [handle]
        (let [grades (launched handle connection 1)
              branch (:seon.chain/branch (first grades))
              assignment (:seon.competency/assignment (first grades))]
          (try
            (dotimes [_ 3] (pass! handle))
            (let [main (db/db connection)
                  head (db/branch-database main branch)]
              (is (false? (db/q '[:find ?m . :in $ ?b :where [?c :seon.chain/branch ?b] [?c :seon.chain/merge? ?m]] main branch))
                  "the chain fact on main records the decision")
              (is (nil? (:db/id (db/pull main [:db/id] [:seon.task/assignment assignment])))
                  "main holds no row of the attempt: no merge request was made and none bailed")
              (is (= :done (db/q '[:find ?e . :in $ ?k :where [?a :seon.task/assignment ?k] [?a :seon.task.attempt/end ?e]]
                                 head assignment))
                  "its end stays readable on the kept branch"))
            (finally
              (release-chain! (:seon.agent/routing handle) (agent/handle-store handle) branch)))))))))

(deftest forms-are-classed-by-what-they-did
  (is (= [:navigation :read :re-read :probe :repeat :progress :unresolved :contract-refusal :search :wait :progress]
         (mapv :seon.mine/class
               (mine/classify
                (mapv #(hash-map :seon.cluster.eval/source (first %) :seon.cluster.eval/error (second %))
                      [["(in-ns 'comp.geo)"]
                       ["(my.program/source 'comp.geo/area)"]
                       ["(my.program/source 'comp.geo/area)"]
                       ["(comp.geo/area 2 3)"]
                       ["(comp.geo/area 2 3)"]
                       ["(defn area [w h] (* w h))"]
                       ["(seon.db/database-value)" "Unable to resolve symbol: seon.db/database-value"]
                       ["(my.program/source \"area\")" "my.program/source refused argument 1 `subject`: expected a symbol."]
                       ["(dir comp.geo)"]
                       ["(my.turn/wait {:my.turn/note \"Done.\"})"]
                       ["(ns comp.app (:require [comp.util :as u]))"]]))))))

(deftest a-difference-counts-only-beyond-the-noise-floor
  (let [row (fn [green [lo hi]] {:seon.competency/id :t :seon.competency/variant :v :seon.competency/green green
                                 :seon.competency/score-median lo :seon.competency/score-range [lo hi]})]
    (is (= [:noise :better :worse :better]
           (mapv (fn [[before after]] (:seon.competency/verdict (first (competency/compare-tables [before] [after]))))
                 [[(row 3 [2 5]) (row 5 [2 4])]
                  [(row 1 [4 11]) (row 4 [2 3])]
                  [(row 5 [2 3]) (row 5 [5 7])]
                  [(row 2 [8 11]) (row 2 [3 6])]])))))

(deftest the-cached-share-is-the-percent-of-prompt-tokens-the-cache-served
  ;; live run 7's `fddd9dcbfe66` and live run 8's three chains
  (is (= [87 22 nil] (mapv #(apply mine/cached-share %) [[619000 540000] [2737800 605952] [0 0]]))))

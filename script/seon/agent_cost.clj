(ns seon.agent-cost
  "Chain request measurements through the MCP entrance, with branch-local tasks and tests."
  (:require [my.agent]
            [seon.cache :as cache]
            [seon.cluster.host :as host]
            [seon.context :as context]
            [seon.db :as db]
            [seon.mcp :as mcp]
            [seon.operator.runtime :as runtime]
            [seon.profile :as profile]
            [seon.render.value :as value]
            [seon.task :as task]
            [seon.test :as test]
            [seon.test.runner :as runner]
            [seon.test-support :as support])
  (:import [java.lang.management ManagementFactory MemoryPoolMXBean]
           [java.util.concurrent CountDownLatch]))

(defn source
  "A contracted function with `width` reaching tests in the probe namespace."
  {:malli/schema [:=> [:cat pos-int?] :string]}
  [width]
  (str "(ns agent.cost.probe (:require [clojure.test :refer [deftest is]] [seon.test :as test]))\n"
       "(defn value {:malli/schema [:=> [:cat :int] :int]} [x] x)\n"
       "(defn narrow {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 0))\n"
       "(deftest narrow-test (is (= 1 (narrow 1))))\n"
       "(deftest pending (is (nil? (test/task))))\n"
       (apply str (for [i (range width)]
                    (str "(deftest value-" i " (is (= " i " (value " i "))))\n")))))

(defn request
  "One complete MCP request, its reply cost and the surrounding profile window."
  {:malli/schema [:=> [:cat :string :seon.agent/id :string] :map]}
  [cluster agent-id code]
  (let [measured (profile/measure #(#'mcp/eval-clj {:seon.mcp/code code :seon.mcp/agent agent-id
                                                 :seon.mcp/cluster cluster :seon.mcp/mode "sci"}))
        [texts failed?] (:seon.profile/value measured)
        replies (mapv support/reply texts)]
    (assoc (dissoc measured :seon.profile/value :seon.profile/called)
           :agent agent-id :failed? failed? :replies replies
           :derivations (select-keys (:seon.profile/called measured)
                                    '[seon.cluster.source/merge-members seon.fn/gate-edges
                                      seon.test.runner/newest-results seon.db/entities-changed-after
                                      seon.test/run seon.test/bounded-result
                                      seon.test/carried-results seon.test/recorded-outcomes
                                      seon.test.runner/program-content-digest seon.program/digest-map])
           :request (some :seon.repl/request replies))))

(defn prepare!
  "An own-branch agent with a linked task and `width` tests reaching its changed function."
  {:malli/schema [:=> [:cat :string :seon.agent/id pos-int?] :map]}
  [cluster agent-id width]
  (let [main (get-in @runtime/running-instances [cluster :seon.turn.loop/cluster :seon.db/connection])
        _ (when-not (db/pull (db/db main) [:db/id] [:seon.agent/id "root"])
            (support/transacted! main [[:db.fn/call #'host/home-call "root" (get-in (db/db main) [:config :branch]) 'user cluster]]))
        {connection :seon.db/connection branch :seon.store/branch :as created}
        (support/own-branch-agent! cluster agent-id (source width))
        entities (#'task/chain-entities [{:seon.task/ns 'agent.cost.probe :seon.task/name "measure"
                                         :seon.task/plan "Measure a chain request with reaching tests."
                                         :seon.task/model :outside :seon.task/proofs #{'agent.cost.probe/pending}}
                                        {:seon.task/instructions "Keep the chain open for measurement."}])
        handles (mapv #(task/task-handle (:seon.task/ns %) (:seon.task/name %)) entities)]
    (db/with-branch {:seon.db/connection connection}
      #(do (support/transacted! connection [[:db.fn/call #'task/adopt-tasks-tx entities (set handles) {}]])
           (task/assign! {:seon.db/connection connection :seon.task/assigned (first handles)
                          :seon.task/agent [:seon.agent/id agent-id] :seon.task/branch branch})))
    (assoc created :agent agent-id :width width)))

(defn verdict
  "A measured verdict over the agent's held branch, with its status and member count."
  {:malli/schema [:=> [:cat :map] :map]}
  [{connection :seon.db/connection agent-id :agent}]
  (let [measured (profile/measure #(context/step-verdict (db/db connection) agent-id))
        value (:seon.profile/value measured)]
    (assoc (dissoc measured :seon.profile/value :seon.profile/called)
           :status (:seon.task/status value)
           :members (count (get-in value [:seon.source/judged :seon.source/members]))
           :refusals (:seon.source/refusals value))))

(defn cache-miss
  "Recover one evicted outcome through the production cross-branch result lookup."
  {:malli/schema [:=> [:cat :map] :map]}
  [{connection :seon.db/connection}]
  (let [database (db/db connection)
        test-symbol 'agent.cost.probe/narrow-test
        digest (get (runner/reach-digests database [test-symbol]) test-symbol)
        inputs (db/q '[:find ?inputs . :in $ ?symbol ?digest :where
                       [?member :seon.test.member/symbol ?symbol]
                       [?member :seon.test.member/reach-digest ?digest]
                       [?run :seon.test.run/members ?member] [?run :seon.test.run/input-digest ?inputs]]
                     database test-symbol digest)
        _ (cache/evict! :seon.test.runner/result [test-symbol digest inputs])
        measured (profile/measure #(#'test/carried-results database #{} [test-symbol] {test-symbol digest} inputs))]
    (assoc (dissoc measured :seon.profile/value :seon.profile/called)
           :members (count (:seon.profile/value measured))
           :derivations (select-keys (:seon.profile/called measured)
                                    '[seon.test/carried-results seon.test/recorded-outcomes seon.db/undecoded-datoms seon.db/undecoded-search]))))

(defn reach-miss
  "Read one current outcome through the runner's indexed reach lookup."
  {:malli/schema [:=> [:cat :map] :map]}
  [{connection :seon.db/connection}]
  (let [database (db/db connection)
        reaches (runner/reach-digests database ['agent.cost.probe/narrow-test])
        measured (profile/measure #(runner/result-at-reach database reaches))]
    (assoc (dissoc measured :seon.profile/value :seon.profile/called)
           :members (count (:seon.profile/value measured))
           :derivations (select-keys (:seon.profile/called measured)
                                    '[seon.test.runner/result-at-reach seon.db/datoms seon.db/undecoded-search]))))

(defn retire!
  "Retire the probe's owned branch through the agent owner."
  {:malli/schema [:=> [:cat :string :map] :map]}
  [cluster {branch :seon.store/branch}]
  (let [instance (get @runtime/running-instances cluster)
        handle (:seon.turn.loop/cluster instance)]
    (my.agent/retire-branch! {:seon.store/branch branch}
                            (assoc handle :seon.store/store (:seon.store/store instance)))))

(defn group-run
  "First and repeat requests from `k` simultaneous agents, with old-generation peak usage."
  {:malli/schema [:=> [:cat :string pos-int? pos-int?] :map]}
  [cluster width k]
  (let [stamp (random-uuid)
        agents (mapv #(prepare! cluster (str "agent-cost-" stamp "-" %) width) (range k))
        pools (filterv #(and (= java.lang.management.MemoryType/HEAP (.getType ^MemoryPoolMXBean %))
                              (.isCollectionUsageThresholdSupported ^MemoryPoolMXBean %))
                       (ManagementFactory/getMemoryPoolMXBeans))
        together (fn [code]
                   (let [start (CountDownLatch. 1)
                         jobs (mapv (fn [{agent-id :agent}]
                                      #_{:clj-kondo/ignore [:discouraged-var]}
                                      (future (.await start) (request cluster agent-id code))) agents)]
                     (.countDown start)
                     (mapv #(support/await-event! % :agent-cost/request-exited (constantly true) 120000) jobs)))]
    (try
      (run! #(.resetPeakUsage ^MemoryPoolMXBean %) pools)
      (let [first-requests (together "(in-ns 'agent.cost.probe)\n(defn value {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 0))")
            first-heads (mapv #(db/db (:seon.db/connection %)) agents)
            repeat-requests (together "(defn narrow {:malli/schema [:=> [:cat :int] :int]} [x] (+ 0 x))")
            second-heads (mapv #(db/db (:seon.db/connection %)) agents)
            graph-changes (when-let [revisions (ns-resolve 'seon.test 'selection-revisions)]
                            (mapv (fn [before after]
                                    (into {} (keep (fn [a]
                                                     (let [diff (db/datoms-diff before after :aevt [a])]
                                                       (when (some seq diff) [a diff]))))
                                          (keys (revisions after)))) first-heads second-heads))
            edge-requests (together "(defn narrow {:malli/schema [:=> [:cat :int] :int]} [x] (- x 0))")
            unchanged (together "(+ 1 2)")
            misses (mapv cache-miss agents)]
        {:k k :width width :graph-changes graph-changes :first first-requests :repeat repeat-requests :edge edge-requests :unchanged unchanged :miss misses :reach-miss (mapv reach-miss agents)
         :old-gen (mapv (fn [^MemoryPoolMXBean pool]
                          {:pool (.getName pool) :peak-bytes (.getUsed (.getPeakUsage pool))
                           :used-bytes (.getUsed (.getUsage pool)) :max-bytes (.getMax (.getUsage pool))}) pools)
         :verdict (mapv verdict agents)
         :profile (profile/summary (profile/cells) 20)})
      (finally (run! #(retire! cluster %) agents)))))

(defn run
  "Measure each requested concurrency on one running cluster."
  {:malli/schema [:=> [:cat :string pos-int? [:vector pos-int?]] [:vector :map]]}
  [cluster width concurrencies]
  (mapv #(group-run cluster width %) concurrencies))

(defn report
  "Write small measurement values through the value printer, one complete EDN value per line."
  {:malli/schema [:=> [:cat :map] :nil]}
  [result]
  (let [path (str "/tmp/change-cost-probe-" (:k result) ".edn")
        write! #(spit path (str (value/core-text %) "\n") :append true)]
    (spit path "")
    (doseq [stage [:first :repeat :edge :unchanged :miss :reach-miss :verdict :root]
            measured (get result stage)]
      (write! {:k (:k result) :stage stage
               :measurement (select-keys measured [:request :failed? :status :members
                                                   :seon.profile/ms :seon.profile/alloc-bytes
                                                   :seon.profile/reads :seon.profile/calls])})
      (write! {:k (:k result) :stage stage :derivations (:derivations measured)})
      (doseq [ranked (:seon.profile/ranked-by-self measured)]
        (write! {:k (:k result) :stage stage :ranked ranked})))
    (doseq [pool (:old-gen result)] (write! {:k (:k result) :old-gen pool}))
    (doseq [changes (:graph-changes result) [attribute diff] changes]
      (write! {:k (:k result) :attribute attribute :graph-change diff})))
  nil)

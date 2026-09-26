(ns seon.cluster.host-test
  "A branch-hosted agent (`seon.cluster.host`) through real acquisition, armed
  contracts and Flow: its runs land on its branch, siblings stay independent,
  an ordinary message wakes it, and retirement closes admission at the writer."
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.cluster.host :as host]
            [seon.cluster.message :as message]
            [seon.cluster.registry :as registry]
            [seon.config :as config]
            [seon.db :as db]
            [seon.eval.drive :as drive]
            [seon.flow :as flow]
            [seon.id :as id]
            [seon.test-support :as support]
            [seon.turn :as turn]))

(def ^:private cluster-name "host")

(def ^:private host-ns 'my.agents.host-probe)

(defn- with-host-world
  "Call `body` with a cluster handle over the executing member and a branch
  maker; every branch it made is retired afterwards, hosts first."
  [manifest body]
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection cluster-name (merge {:seon.config.ai/no-provider true} manifest))
     (let [member (support/execution-handle connection)
           store (:seon.store/store member)
           ctx (support/fork-cluster-ctx connection cluster-name)
           environment (support/environment cluster-name connection)
           made (atom [])]
       (with-open [launcher (support/closeable
                             (flow/start-work-launcher!
                              {:seon.env/environment environment
                               :seon.flow/configuration (select-keys (config/effective (db/db connection) cluster-name)
                                                                     flow/flow-workload-attributes)})
                             flow/stop-work-launcher!)]
         (let [handle (support/cluster-handle
                       {:seon.env/environment environment :seon.db/connection connection
                        :seon.cluster/name cluster-name :seon.sci.eval/ctx ctx
                        :seon.flow/work-launcher @launcher :seon.store/store store
                        :seon.flow/executor (cluster/projection-executor (:seon.sci.eval/projection-state ctx))
                        :seon.db.process/id cluster/boot-process-identity})
               branch! (fn []
                         (let [branch (keyword (str "host-test-" (id/id)))]
                           (registry/branch! {:seon.store/store store :seon.store/branch branch
                                              :seon.cluster.registry/from (db/commit-id (db/db connection))})
                           (swap! made conj branch)
                           branch))]
           (try
             (body {:handle handle :connection connection :branch! branch!})
             (finally
               (doseq [branch @made]
                 (some-> (host/hosted handle branch) host/retire!)
                 (registry/retire-branch! {:seon.store/store store :seon.store/branch branch}))))))))))

(defn- host-on!
  [handle branch agent-id]
  (host/host! {:seon.turn.loop/cluster handle :seon.agent/branch branch
               :seon.agent/id agent-id :seon.ns/name host-ns}))

(defn- as-host
  "Call `f` as the hosted branch's own work: under a test member's custody the
  writer refuses a write naming another branch, as the bridge's prepl, which
  binds none, never sees."
  [hosted f]
  (db/call-with-custody {:seon.db/connection (get-in hosted [:seon.turn.loop/cluster :seon.db/connection])} f))

(defn- hosted-run!
  "One ordinary run of the hosted agent: its evaluations' shown text, in order,
  or the refusal the submission answered."
  [hosted source]
  (let [custody (select-keys hosted [:seon.turn.loop/cluster :seon.agent/routing :seon.agent/id])
        result (as-host hosted
                 #(agent/evaluate-source! (assoc custody :seon.agent/segmenter 'seon.cluster.reply/code-sources
                                                 :seon.cluster.reply/text source)))]
    (if-let [run-id (:seon.turn/id result)]
      (mapv #(or (:seon.eval/shown %) (:seon.cluster.eval/error %))
            (drive/run-receipts @(get-in hosted [:seon.turn.loop/cluster :seon.db/connection]) [run-id]))
      result)))

(defn- refused-by?
  "Whether `value` carries the run writer's refusal `rule` anywhere in its data."
  [rule value]
  (boolean (some #{rule} (tree-seq coll? seq value))))

(deftest a-hosted-run-lands-on-its-branch-and-the-parent-head-is-unchanged
  (with-host-world {}
    (fn [{:keys [handle connection branch!]}]
      (let [branch (branch!)
            parent-commit (db/commit-id (db/db connection))
            hosted (host-on! handle branch "mcp/solo")
            probe [:seon.fn/sym (symbol (str host-ns) "host-probe")]]
        (is (= ["#'my.agents.host-probe/host-probe"]
               (hosted-run! hosted "(defn host-probe \"Add one.\" {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))")))
        (is (= ["2"] (hosted-run! hosted "(host-probe 1)")) "the agent's next run calls its def")
        (let [branch-db @(get-in hosted [:seon.turn.loop/cluster :seon.db/connection])]
          (is (= branch (get-in branch-db [:config :branch])))
          (is (some? (:db/id (db/pull branch-db [:db/id] probe))) "the row is on the branch"))
        (is (= parent-commit (db/commit-id (db/db connection))) "the parent head did not move")
        (is (nil? (:db/id (db/pull (db/db connection) [:db/id] probe))) "the parent never sees the row")
        (is (identical? (:seon.agent/routing hosted)
                        (get-in @(:seon.agent/routing handle) [:seon.agent/children branch]))
            "the host lives in the child roster the parent records")))))

(deftest a-host-retires-after-it-has-run
  ;; The live-host path: before the fix `retire!` wrote the admission ref as
  ;; `:db/current-tx`, which seon.db/transact! refuses, so a lane that had run
  ;; on its branch could never release it.
  (with-host-world {}
    (fn [{:keys [handle branch!]}]
      (let [branch (branch!)
            hosted (host-on! handle branch "mcp/retiring")
            connection (get-in hosted [:seon.turn.loop/cluster :seon.db/connection])]
        (is (= ["1"] (hosted-run! hosted "1")))
        (is (nil? (host/retire! hosted)))
        (is (some? (:seon.agent/admission-closed-tx
                    (db/pull @connection [:seon.agent/admission-closed-tx] [:seon.agent/id "mcp/retiring"]))))
        (is (nil? (host/hosted handle branch)) "the parent roster no longer records the host")))))

(deftest colliding-sibling-hosts-deliver-independently-and-one-retires-while-the-other-runs
  (with-host-world {}
    (fn [{:keys [handle branch!]}]
      (let [a (host-on! handle (branch!) "mcp/twin")
            b (host-on! handle (branch!) "mcp/twin")
            eid #(db/q '[:find ?e . :where [?e :seon.agent/id "mcp/twin"]]
                       @(get-in % [:seon.turn.loop/cluster :seon.db/connection]))]
        (is (= (eid a) (eid b)) "siblings forked from one commit give the new agent one entity id")
        (is (= ["1"] (hosted-run! a "(def twin-side 1) twin-side")) (pr-str (hosted-run! a "twin-side")))
        (is (= ["2"] (hosted-run! b "(def twin-side 2) twin-side")))
        (host/retire! a)
        (is (nil? (host/hosted handle (get-in a [:seon.turn.loop/cluster :seon.agent/branch]))))
        (is (= ["2"] (hosted-run! b "twin-side")) "the sibling still runs after the other retired")
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"admission is closed"
                              (host-on! handle (get-in a [:seon.turn.loop/cluster :seon.agent/branch]) "mcp/twin"))
            "a retired agent cannot be hosted again")))))

(deftest an-ordinary-message-wakes-the-idle-hosted-agent
  (with-host-world {}
    (fn [{:keys [handle branch!]}]
      (let [hosted (host-on! handle (branch!) "mcp/listener")
            connection (get-in hosted [:seon.turn.loop/cluster :seon.db/connection])
            closed-runs #(db/q '[:find (count ?run) . :where [?agent :seon.agent/id "mcp/listener"]
                                 [?run :seon.turn/agent ?agent] [?run :seon.turn/closed-tx _]]
                               %)]
        (is (= ["1"] (hosted-run! hosted "1")) "the first run arms the agent through the host's armer")
        (let [before (or (closed-runs @connection) 0)
              sent (db/call-with-custody {:seon.db/connection connection}
                     #(message/send! {:my.message/to "mcp/listener" :my.message/content "hello"} connection "root"))]
          (is (:seon.message/id sent) (pr-str sent))
          (support/await-event! connection ::message-answered
                                (fn [_] (< before (or (closed-runs @connection) 0))))
          (is (< before (closed-runs @connection)) "the branch's route listener woke the idle agent"))))))

(deftest retirement-closes-admission-at-the-writer-and-races-submission
  (with-host-world {}
    (fn [{:keys [handle branch!]}]
      (let [branch (branch!)
            hosted (host-on! handle branch "mcp/racer")
            _ (is (= ["1"] (hosted-run! hosted "1")))
            racing (async/thread (hosted-run! hosted "2"))
            _ (host/retire! hosted)
            raced (support/await-event! racing ::raced)]
        (testing "a submission racing retirement either settled first or was refused by name"
          (is (or (= ["2"] raced) (refused-by? :seon.turn/admission-closed raced)
                  (refused-by? :seon.agent/released? raced))
              (pr-str raced)))
        (testing "the writer refuses any later run for the agent"
          (let [execution (agent/acquire-context! handle nil {:seon.agent/branch branch})]
            (try
              (let [database (db/db (:seon.db/connection execution))]
                (is (nil? (db/q '[:find ?run . :where [?agent :seon.agent/id "mcp/racer"]
                                  [?run :seon.turn/agent ?agent] (not [?run :seon.turn/closed-tx _])]
                                database))
                    "no run is open after retirement")
                (is (refused-by? :seon.turn/admission-closed
                                 (try (turn/system-run-call
                                       database {:seon.agent/id "mcp/racer" :seon.turn/id "racer-late"
                                                 :seon.turn/opened-tx "datomic.tx"
                                                 :seon.turn/starting-ns [:seon.ns/name host-ns]
                                                 :seon.turn/sources [{:seon.cluster.eval/source "3"}]})
                                      (catch clojure.lang.ExceptionInfo refusal (ex-data refusal))))))
              (finally (agent/release-context! execution)))))))))

(deftest an-unsettled-host-run-keeps-ownership-until-it-exits
  ;; The evaluation limit ends the loop at one second, so the run is live when
  ;; the second submission arrives, and retirement joins it before releasing.
  (with-host-world {:seon.config.eval/time-limit-ms 1000}
    (fn [{:keys [handle branch!]}]
      (let [hosted (host-on! handle (branch!) "mcp/busy")
            custody (select-keys hosted [:seon.turn.loop/cluster :seon.agent/routing :seon.agent/id])
            submit! #(as-host hosted (fn [] (agent/submit-source! (assoc custody :seon.agent/segmenter 'seon.cluster.reply/code-sources
                                                                        :seon.cluster.reply/text %))))
            first-run (submit! "(loop [] (recur))")
            second-run (submit! "1")
            connection (get-in hosted [:seon.turn.loop/cluster :seon.db/connection])]
        (is (:seon.turn/id first-run) (pr-str first-run))
        (is (refused-by? :seon.turn/agent-already-running second-run) "the live run still owns the agent")
        (host/retire! hosted)
        (is (some? (:seon.turn/closed-tx (db/pull @connection [:seon.turn/closed-tx]
                                                  [:seon.turn/id (:seon.turn/id first-run)])))
            "retirement returned only after the run exited")))))

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
                       (merge
                       {:seon.env/environment environment :seon.db/connection connection
                        :seon.cluster/name cluster-name :seon.sci.eval/ctx ctx
                        :seon.flow/work-launcher @launcher :seon.store/store store
                        :seon.flow/executor (cluster/projection-executor (:seon.sci.eval/projection-state ctx))
                        :seon.db.process/id cluster/boot-process-identity}
                       ;; A host inherits its cluster handle's dials; the manifest's
                       ;; evaluation bound reaches the hosted agent through it.
                       (select-keys manifest [:seon.config.eval/time-limit-ms])))
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

(defn- branch-db
  "The current value of `branch`, read through a fresh acquisition released
  after the read: a retired host has released the connection it ran on."
  [handle branch]
  (let [execution (agent/acquire-context! handle nil {:seon.agent/branch branch})]
    (try (db/db (:seon.db/connection execution))
         (finally (agent/release-context! execution)))))

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
        (let [database @(get-in hosted [:seon.turn.loop/cluster :seon.db/connection])]
          (is (= branch (get-in database [:config :branch])))
          (is (some? (:db/id (db/pull database [:db/id] probe))) "the row is on the branch"))
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
            hosted (host-on! handle branch "mcp/retiring")]
        (is (= ["1"] (hosted-run! hosted "1")))
        (is (nil? (host/retire! hosted)))
        (is (some? (:seon.agent/admission-closed-tx
                    (db/pull (branch-db handle branch) [:seon.agent/admission-closed-tx] [:seon.agent/id "mcp/retiring"]))))
        (is (nil? (host/hosted handle branch)) "the parent roster no longer records the host")))))

(deftest colliding-sibling-hosts-deliver-independently-and-one-retires-while-the-other-runs
  (with-host-world {}
    (fn [{:keys [handle branch!]}]
      (let [a (host-on! handle (branch!) "mcp/twin")
            b (host-on! handle (branch!) "mcp/twin")
            eid #(db/q '[:find ?e . :where [?e :seon.agent/id "mcp/twin"]]
                       @(get-in % [:seon.turn.loop/cluster :seon.db/connection]))]
        (is (= (eid a) (eid b)) "siblings forked from one commit give the new agent one entity id")
        (is (= "1" (last (hosted-run! a "(def twin-side 1) twin-side"))))
        (is (= "2" (last (hosted-run! b "(def twin-side 2) twin-side"))))
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
        ;; A test member's world holds no agents but the ones it creates, so the
        ;; sender is created on the branch too.
        (as-host hosted #(support/transacted! connection (support/agent-tx @connection "listener-sender")))
        (let [before (or (closed-runs @connection) 0)
              sent (as-host hosted
                     #(message/send! {:my.message/to "mcp/listener" :my.message/content "hello"} connection "listener-sender"))]
          (is (:seon.message/id sent) (str (:seon.error/message sent) " " (vec (sort (keys sent)))))
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
      (let [branch (branch!)
            hosted (host-on! handle branch "mcp/busy")
            custody (select-keys hosted [:seon.turn.loop/cluster :seon.agent/routing :seon.agent/id])
            submit! #(as-host hosted (fn [] (agent/submit-source! (assoc custody :seon.agent/segmenter 'seon.cluster.reply/code-sources
                                                                        :seon.cluster.reply/text %))))
            first-run (submit! "(loop [] (recur))")
            second-run (submit! "1")]
        (is (:seon.turn/id first-run) (pr-str first-run))
        (is (refused-by? :seon.turn/agent-already-running second-run) "the live run still owns the agent")
        (host/retire! hosted)
        (is (some? (:seon.turn/closed-tx (db/pull (branch-db handle branch) [:seon.turn/closed-tx]
                                                  [:seon.turn/id (:seon.turn/id first-run)])))
            "retirement returned only after the run exited")))))

(deftest a-namespace-switch-declares-nothing
  ;; SCI's in-ns only switches the namespace. Before the fix an agent's
  ;; (in-ns 'seon.id) settled a declaration row replacing seon.id's file row,
  ;; retracting its file, doc and source (default, tx 536871848).
  (with-host-world {}
    (fn [{:keys [handle branch!]}]
      (let [hosted (host-on! handle (branch!) "mcp/switcher")
            database #(db/db (get-in hosted [:seon.turn.loop/cluster :seon.db/connection]))
            file-row #(db/pull (database) [:seon.ns/source :seon.ns/doc :seon.program/definition-digest
                                           {:seon.fn/file [:seon.fn.file/relative-path]}]
                               [:seon.ns/name 'seon.id])
            namespace-of #(get-in (db/pull (database) [{:seon.fn/ns [:seon.ns/name]}] [:seon.fn/sym %])
                                  [:seon.fn/ns :seon.ns/name])
            before (file-row)]
        (is (= "src/seon/id.clj" (get-in before [:seon.fn/file :seon.fn.file/relative-path])) (pr-str before))
        (is (= "#'seon.id/switch-probe"
               (last (hosted-run! hosted "(in-ns 'seon.id)\n(defn switch-probe \"Add one.\" {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))"))))
        (is (= before (file-row)) "the file namespace's row is unchanged")
        (is (= 'seon.id (namespace-of 'seon.id/switch-probe)) "the definition references the namespace")
        (testing "a namespace the switch brings into being exists for the definitions made in it"
          (is (= "#'my.agents.switch-fresh/fresh-probe"
                 (last (hosted-run! hosted "(in-ns 'my.agents.switch-fresh)\n(defn fresh-probe \"Add two.\" {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 2))"))))
          (is (= 'my.agents.switch-fresh (namespace-of 'my.agents.switch-fresh/fresh-probe)))
          (is (nil? (:seon.ns/source (db/pull (database) [:seon.ns/source] [:seon.ns/name 'my.agents.switch-fresh])))
              "the switch's text is not the namespace's source"))
        (testing "an ns form declares its namespace with its requires and aliases"
          (hosted-run! hosted "(ns my.agents.switch-declared (:require [clojure.string :as str]))")
          (is (= [#:seon.ns.alias{:local 'str :target-ns 'clojure.string}]
                 (:seon.ns/aliases (db/pull (database) [{:seon.ns/aliases [:seon.ns.alias/local :seon.ns.alias/target-ns]}]
                                            [:seon.ns/name 'my.agents.switch-declared])))))))))

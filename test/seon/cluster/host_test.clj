(ns seon.cluster.host-test
  "A branch-hosted agent (`seon.cluster.host`) through real acquisition, started
  contracts and Flow: its runs land on its branch, siblings stay independent,
  an ordinary message wakes it, and retirement closes admission at the writer."
  (:require [clojure.core.async :as async]
            ;; loaded so a hosted run can check one of its tests by symbol
            [my.task-test]
            [clojure.edn]
            [clojure.string :as str]
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
            [seon.turn :as turn]
            [seon.task :as task]
            [my.agent]
            [my.message]
            [my.program]
            [my.root]))

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
  (db/with-branch {:seon.db/connection (get-in hosted [:seon.turn.loop/cluster :seon.db/connection])} f))

(defn- assign-hosted!
  "Assign the hosted agent a task on its branch, where a test world without a
  root row keeps its tasks: a model turn needs an assignment."
  [hosted]
  (as-host hosted #(support/assign-task! (get-in hosted [:seon.turn.loop/cluster :seon.db/connection])
                                         (:seon.agent/id hosted))))

(defn- hosted-run-receipts
  "One ordinary run of the hosted agent: its settled receipts in order under
  its run id, or the refusal the submission answered."
  [hosted source]
  (let [custody (select-keys hosted [:seon.turn.loop/cluster :seon.agent/routing :seon.agent/id])
        result (as-host hosted
                 #(agent/evaluate-source! (assoc custody :seon.agent/segmenter 'seon.turn/code-sources
                                                 :seon.cluster.reply/text source)))]
    (if-let [run-id (:seon.turn/id result)]
      (cond-> {:seon.turn/id run-id
               ;; the run read on the reply's own value
               :receipts (drive/run-receipts (:seon.db/db result) [run-id])}
        ;; a run refused before it opened recorded no receipt
        (:seon.turn.loop/refusal result) (assoc :seon.turn.loop/refusal (:seon.turn.loop/refusal result)))
      result)))

(defn- hosted-run!
  "One ordinary run of the hosted agent: its evaluations' shown text, in order,
  or the refusal the submission answered (a run refused before it opened
  recorded none)."
  [hosted source]
  (let [{receipts :receipts :as result} (hosted-run-receipts hosted source)]
    (if (seq receipts)
      (mapv #(or (:seon.eval/shown %) (:seon.cluster.eval/error %)) receipts)
      result)))

(defn- recorded-proposals
  "The deletion proposals the refused write's error occurrence for `run-id`
  recorded, read back from the database, with its validator schema."
  [database run-id]
  (let [[edn validator] (db/q '[:find [?edn ?validator]
                                :in $ ?run-id
                                :where [?run :seon.turn/id ?run-id]
                                [?occurrence :seon.error.occurrence/turn ?run]
                                [?occurrence :seon.turn/deletion-proposals-edn ?edn]
                                [?occurrence :seon.db.write/validator-schema ?validator]]
                              database run-id)]
    {:proposals (some-> edn clojure.edn/read-string) :validator validator}))

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
      ;; Hosting writes the agent's home entity on the cluster by design
      ;; (dba08a8ab); the runs must not move the parent head after it.
      (let [branch (branch!)
            hosted (host-on! handle branch "mcp/solo")
            parent-commit (db/commit-id (db/db connection))
            probe [:seon.fn/sym (symbol (str host-ns) "host-probe")]]
        (is (= branch (:seon.agent/branch (db/pull (db/db connection) [:seon.agent/branch] [:seon.agent/id "mcp/solo"])))
            "hosting's one allowed parent write: the agent's home entity names its branch")
        (is (= ["{:seon.render.var/symbol my.agents.host-probe/host-probe}"]
               (hosted-run! hosted "(defn host-probe \"Add one.\" {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))")))
        (is (= ["2"] (hosted-run! hosted "(host-probe 1)")) "the agent's next run calls its def")
        (let [task-id (id/id)]
          (hosted-run! hosted (pr-str (list 'seon.db/transact!
                                            (list 'quote {:tx-data [(support/task-row task-id "written by a hosted form")]}))))
          (let [database @(get-in hosted [:seon.turn.loop/cluster :seon.db/connection])]
            (is (some? (db/q '[:find ?r . :in $ ?task-id :where [?n :seon.task/id ?task-id] [?n :seon.task/intent _ ?tx]
                               [?tx :seon.db/receipt ?r]] database task-id))
                "a form's own elided write lands on the branch, naming its evaluation")
            (is (nil? (db/q '[:find ?n . :in $ ?task-id :where [?n :seon.task/id ?task-id]] (db/db connection) task-id))
                "and never on the parent")))
        (let [database @(get-in hosted [:seon.turn.loop/cluster :seon.db/connection])]
          (is (= branch (get-in database [:config :branch])))
          (is (some? (:db/id (db/pull database [:db/id] probe))) "the entity is on the branch"))
        (is (= parent-commit (db/commit-id (db/db connection))) "the parent head did not move")
        (is (nil? (:db/id (db/pull (db/db connection) [:db/id] probe))) "the parent never sees the entity")
        (is (identical? (:seon.agent/routing hosted)
                        (get-in @(:seon.agent/routing handle) [:seon.agent/children branch]))
            "the host lives in the child registry the parent records")))))

(deftest a-definition-whose-body-expands-macros-persists
  ;; Before (2026-09-28 dogfood loop), a defn whose body used cond-> or case
  ;; returned its Var but wrote no entity: the positionless usages clj-kondo
  ;; records inside those expansions failed seon.turn.batch/span-of's contract.
  (with-host-world {}
    (fn [{:keys [handle branch!]}]
      (let [hosted (host-on! handle (branch!) "mcp/expander")
            probe [:seon.fn/sym (symbol (str host-ns) "expanding-probe")]]
        (is (= ["{:seon.render.var/symbol my.agents.host-probe/expanding-probe}"]
               (hosted-run! hosted (str "(defn- expanding-probe \"Bump positives.\" {:malli/schema [:=> [:cat :int] :int]}"
                                        " [x] (case x 0 0 (cond-> x (pos? x) inc)))"))))
        (is (some? (:seon.program/definition-digest
                    (db/pull @(get-in hosted [:seon.turn.loop/cluster :seon.db/connection])
                             [:seon.program/definition-digest] probe)))
            "the definition is an entity on the branch")))))

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
        (is (nil? (host/hosted handle branch)) "the parent registry no longer records the host")))))

(deftest stopping-the-cluster-releases-every-host-connection
  ;; A reset stops the instance in this JVM: a host left running kept its
  ;; branch connection, so its branch outlived the reforked cluster.
  (with-host-world {}
    (fn [{:keys [handle branch!]}]
      (let [store (:seon.store/store handle)
            branches [(branch!) (branch!)]]
        (doseq [[branch agent-id] (map vector branches ["mcp/stop-a" "mcp/stop-b"])]
          (host-on! handle branch agent-id))
        (is (every? #(registry/active-branch-connection {:seon.store/store store :seon.store/branch %}) branches)
            "each host holds its branch connection")
        (is (nil? (host/release-children! (:seon.agent/routing handle))))
        (is (empty? (:seon.agent/children @(:seon.agent/routing handle))))
        (is (not-any? #(registry/active-branch-connection {:seon.store/store store :seon.store/branch %}) branches)
            "no connection remains for a stopped host's branch")))))

(deftest one-identity-is-homed-on-one-branch-decided-by-the-writer
  ;; Astra H2: two hosts of one new agent on sibling branches both pre-read an
  ;; absent cluster entity; the cluster writer now decides, so exactly one homes it.
  (with-host-world {}
    (fn [{:keys [handle branch! connection]}]
      (let [branches [(branch!) (branch!)]
            start (java.util.concurrent.CountDownLatch. 1)
            racing (mapv (fn [branch]
                           (async/thread
                             (support/await-event! start ::start)
                             (try (host-on! handle branch "mcp/twin")
                                  (catch clojure.lang.ExceptionInfo refusal (ex-data refusal)))))
                         branches)
            _ (.countDown start)
            outcomes (mapv #(support/await-event! % ::hosted) racing)
            [winner] (filter :seon.cluster.host/execution outcomes)
            [loser] (remove :seon.cluster.host/execution outcomes)
            home (:seon.agent/branch (db/pull (db/db connection) [:seon.agent/branch] [:seon.agent/id "mcp/twin"]))]
        (is (some? winner))
        (is (= :seon.agent/branch (:seon.error/member loser)))
        (is (= home (get-in winner [:seon.turn.loop/cluster :seon.agent/branch]))
            "the cluster entity names the branch whose host was admitted")
        (is (= ["1"] (hosted-run! winner "1")))
        (host/retire! winner)
        ;; host! answers the writer's refusal as a value (253244dc7)
        (is (refused-by? :seon.agent/admission-closed-tx (host-on! handle home "mcp/twin"))
            "a retired agent cannot be hosted again: its admission is closed")))))

(deftest a-branch-answers-only-for-the-agent-it-hosts
  ;; Astra H3: the running host answered any requested agent id.
  (with-host-world {}
    (fn [{:keys [handle branch!]}]
      (let [branch (branch!)
            hosted (host-on! handle branch "mcp/first")]
        (is (identical? hosted (host-on! handle branch "mcp/first")))
        (is (= :seon.agent/id (:seon.error/member (host-on! handle branch "mcp/other")))
            "another agent id on a hosted branch refuses by name, as a value (253244dc7)")))))

(deftest an-ordinary-message-wakes-the-idle-hosted-agent
  (with-host-world {}
    (fn [{:keys [handle branch!]}]
      (let [hosted (host-on! handle (branch!) "mcp/listener")
            _ (assign-hosted! hosted)
            connection (get-in hosted [:seon.turn.loop/cluster :seon.db/connection])
            closed-runs #(db/q '[:find (count ?run) . :where [?agent :seon.agent/id "mcp/listener"]
                                 [?run :seon.turn/agent ?agent] [?run :seon.turn/closed-tx _]]
                               %)]
        (is (= ["1"] (hosted-run! hosted "1")) "the first run starts the agent through the host's db<->flow")
        ;; A test member's world holds no agents but the ones it creates, so the
        ;; sender is created on the branch too.
        (as-host hosted #(support/transacted! connection (support/agent-tx @connection "listener-sender")))
        (let [before (or (closed-runs @connection) 0)
              sent (as-host hosted
                     #(message/send! {:my.message/to "mcp/listener" :my.message/content "hello"} connection "listener-sender"))]
          (is (:seon.message/id sent))
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
        (testing "a submission racing retirement settled first, was refused by name, or its reply closed"
          ;; still on the request port when the graph joined: retirement closes
          ;; its reply (never left hanging) and no run id is claimed for it
          (is (or (= ["2"] raced) (refused-by? :seon.turn/admission-closed raced)
                  (refused-by? :seon.agent/released? raced)
                  (and (:seon.await/closed-operation raced) (not (:seon.turn/id raced))))))
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
  ;; The evaluation limit ends the loop at one second, so the run is live while
  ;; later submissions arrive: one waits on the agent's bounded request port,
  ;; the next is refused busy, and retirement joins the live run before
  ;; releasing. Every reply is answered or closed when retire! returns.
  (with-host-world {:seon.config.eval/time-limit-ms 1000}
    (fn [{:keys [handle branch!]}]
      (let [hosted (host-on! handle (branch!) "mcp/busy")
            custody (select-keys hosted [:seon.turn.loop/cluster :seon.agent/routing :seon.agent/id])
            submit! #(as-host hosted (fn [] (agent/submit-source! (assoc custody :seon.agent/segmenter 'seon.turn/code-sources
                                                                        :seon.cluster.reply/text %))))
            first-run (submit! "(loop [] (recur))")
            ;; the turn proc has taken the first submission off its port
            requests (:seon.agent/requests (agent/started (:seon.agent/routing custody) "mcp/busy"))
            _ (support/await-event! (async/go-loop []
                                      (if (zero? (count (.buf ^clojure.core.async.impl.channels.ManyToManyChannel requests)))
                                        true
                                        (do (async/<! (async/timeout 5)) (recur))))
                                    ::first-run-taken)
            second-run (submit! "1")
            third-run (submit! "2")
            done? #(= % (second (async/alts!! [% (async/timeout 0)] :priority true)))]
        (is (:seon.turn/id first-run))
        (is (:seon.turn/id second-run) "one submission waits on the request port")
        (is (= :seon.agent/requests (:seon.error/member third-run)) "a full request port refuses as busy")
        (host/retire! hosted)
        (is (done? (:seon.agent/reply first-run)) "retirement returned only after the live run exited")
        (is (done? (:seon.agent/reply second-run)) "the waiting submission is answered or closed, never left hanging")))))

(deftest a-namespace-switch-declares-nothing
  ;; SCI's in-ns only switches the namespace. Before the fix an agent's
  ;; (in-ns 'seon.id) settled a declaration entity replacing seon.id's file entity,
  ;; retracting its file, doc and source (default, tx 536871848).
  (with-host-world {}
    (fn [{:keys [handle branch!]}]
      (let [hosted (host-on! handle (branch!) "mcp/switcher")
            database #(db/db (get-in hosted [:seon.turn.loop/cluster :seon.db/connection]))
            file-entity #(db/pull (database) [:seon.ns/source :seon.ns/doc :seon.program/definition-digest
                                           {:seon.fn/file [:seon.fn.file/relative-path]}]
                               [:seon.ns/name 'seon.id])
            namespace-of #(get-in (db/pull (database) [{:seon.fn/ns [:seon.ns/name]}] [:seon.fn/sym %])
                                  [:seon.fn/ns :seon.ns/name])
            before (file-entity)]
        (is (= "src/seon/id.clj" (get-in before [:seon.fn/file :seon.fn.file/relative-path])))
        (is (= "{:seon.render.var/symbol seon.id/switch-probe}"
               (last (hosted-run! hosted "(in-ns 'seon.id)\n(defn switch-probe \"Add one.\" {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))"))))
        (is (= before (file-entity)) "the file namespace's entity is unchanged")
        (is (= 'seon.id (namespace-of 'seon.id/switch-probe)) "the definition references the namespace")
        (testing "a namespace the switch brings into being exists for the definitions made in it"
          (is (= "{:seon.render.var/symbol my.agents.switch-fresh/fresh-probe}"
                 (last (hosted-run! hosted "(in-ns 'my.agents.switch-fresh)\n(defn fresh-probe \"Add two.\" {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 2))"))))
          (is (= 'my.agents.switch-fresh (namespace-of 'my.agents.switch-fresh/fresh-probe)))
          (is (= "(ns my.agents.switch-fresh)"
                 (:seon.ns/source (db/pull (database) [:seon.ns/source] [:seon.ns/name 'my.agents.switch-fresh])))
              "the namespace's source is its generated ns form, never the switch's text"))
        (testing "a definition after a nested switch names a namespace no form declared"
          (is (= "{:seon.render.var/symbol my.agents.switch-nested/nested-probe}"
                 (last (hosted-run! hosted "(do (in-ns 'my.agents.switch-nested) nil)\n(defn nested-probe \"Add three.\" {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 3))"))))
          (is (= 'my.agents.switch-nested (namespace-of 'my.agents.switch-nested/nested-probe))))
        (testing "an ns form declares its namespace with its requires and aliases"
          (hosted-run! hosted "(ns my.agents.switch-declared (:require [clojure.string :as str]))")
          (is (= [#:seon.ns.alias{:local 'str :target-ns 'clojure.string}]
                 (:seon.ns/aliases (db/pull (database) [{:seon.ns/aliases [:seon.ns.alias/local :seon.ns.alias/target-ns]}]
                                            [:seon.ns/name 'my.agents.switch-declared])))))))))

(deftest a-run-gates-a-definition-on-its-earlier-schema
  ;; Before, the gate's disposable copy re-acquired the run's basis commit
  ;; (47d9d4419): the run's uncommitted `register!` was dropped from the copy's
  ;; projection and arming `run-v` refused the whole run by that schema's name.
  (with-host-world {}
    (fn [{:keys [handle branch!]}]
      (let [hosted (host-on! handle (branch!) "mcp/one-run-schema")
            {receipts :receipts}
            (hosted-run-receipts hosted (str "(seon.schema/register! :probe.run/v [:int {:min -8 :max 8}])\n"
                                             "(defn run-v \"Identity on v.\" {:malli/schema [:=> [:cat :probe.run/v] :int]} [x] x)\n"
                                             "(run-v 3)"))]
        (is (= 3 (count receipts)))
        (is (not-any? :seon.cluster.eval/error receipts))
        (is (= "3" (:seon.eval/shown (last receipts))))))))

(deftest ^{:seon.test/long "One nested seon.test/run (checkpoint world + one member); 60 s is merge-test's measured bound for one nested run."
           :seon.test/long-ms 60000}
  an-agents-check-runs-the-tests-reaching-its-change
  ;; Before, (my.test/check {}) on a branch was an :incremental request with no
  ;; all-green run: a first run, widened to every test and loading every test file.
  (with-host-world {}
    (fn [{:keys [handle branch!]}]
      (let [hosted (host-on! handle (branch!) "mcp/checker")]
        (hosted-run! hosted "(defn check-square \"Square x.\" {:malli/schema [:=> [:cat :int] :int]} [x] (* x x))")
        (hosted-run! hosted "(clojure.test/deftest check-square-test (clojure.test/is (= 16 (check-square 4))))")
        (let [shown (first (hosted-run! hosted "(let [r (my.test/check {})] [(:seon.test/passed? r) (mapv :seon.test/sym (:seon.test/results r))])"))]
          (is (= "[true [my.agents.host-probe/check-square-test]]" shown)
              "exactly the test reaching the change runs, green"))))))

(deftest an-agent-creates-a-task-at-its-repl-and-it-is-a-cluster-task
  ;; Before, no runtime entrance wrote a task (7.8): tasks came only from task
  ;; EDN files, so an agent could not record work at its REPL.
  (with-host-world {}
    (fn [{:keys [handle connection branch!]}]
      (support/transacted! connection (support/agent-tx @connection "root"))
      (let [hosted (host-on! handle (branch!) "mcp/creator")
            create #(str "(seon.task/create! {:seon.task/ns '" host-ns " :seon.task/name \"trim-titles\""
                         " :seon.task/kind :function :seon.task/intent " (pr-str %) "})")
            task-id (task/task-handle host-ns "trim-titles" :function)]
        (is (= ["true"] (hosted-run! hosted (str "(:seon.task/changed? " (create "Trim titles.") ")"))))
        (is (= ["false"] (hosted-run! hosted (str "(:seon.task/changed? " (create "Trim titles.") ")")))
            "the same declaration again finds the task and changes nothing")
        (is (= [(pr-str [:revision-conflict "Trim titles." "Trim every title."])]
               (hosted-run! hosted (str "((juxt :seon.task/refused (comp :seon.task/intent :seon.task/current)"
                                        " (comp :seon.task/intent :seon.task/proposed)) " (create "Trim every title.") ")")))
            "a different declaration without the revision I read refuses with current and proposed")
        (is (= {:seon.task/intent "Trim titles." :seon.task/created-by {:seon.agent/id "mcp/creator"}}
               (db/pull (db/db connection) [:seon.task/intent {:seon.task/created-by [:seon.agent/id]}] [:seon.task/id task-id]))
            "the task is on the cluster, written by the agent")))))

(deftest ^{:seon.test/long "Three nested seon.test/run requests of one member each (red, green, green again); 60 s per nested run is merge-test's measured bound."
           :seon.test/long-ms 180000}
  a-merge-request-waits-for-its-assigned-tasks-proofs-then-reaches-root
  ;; Before, request-merge! took any issue id the agent named, ran no test and
  ;; messaged root at once; root inferred pending merge requests from messages.
  (with-host-world {}
    (fn [{:keys [handle connection branch!]}]
      (support/transacted! connection (support/agent-tx @connection "root"))
      (let [branch (branch!)
            hosted (host-on! handle branch "mcp/proposer")
            refusal #(first (hosted-run! hosted "(:seon.task/refused (my.program/request-merge! {}))"))
            to-root #(set (db/q '[:find [?about ...] :where [?r :seon.agent/id "root"] [?m :seon.message/to ?r]
                                  [?m :seon.message/about ?about]] (db/db connection)))
            declaration {:seon.task/ns host-ns :seon.task/name "square" :seon.task/kind :function :seon.task/intent "Square."}
            task-id (read-string (first (hosted-run! hosted (str "(:seon.task/id (seon.task/create! '" (pr-str declaration) "))"))))
            as-root #(db/with-branch {:seon.db/connection connection} (fn [] (% (assoc %2 :seon.db/connection connection))))]
        (support/declare-proof! connection host-ns 'square-test (task/task-sym host-ns "square"))
        (is (= ":unassigned" (refusal)) "an agent root assigned nothing is refused by name")
        (is (nil? (:seon.error/message (as-root task/assign! {:seon.agent/context-source handle :seon.task/assigned task-id
                                                              :seon.task/agent [:seon.agent/id "mcp/proposer"] :seon.task/branch branch})))
            "root assigns the task while its proof is red")
        (hosted-run! hosted "(defn square \"Square x.\" {:malli/schema [:=> [:cat :int] :int]} [x] (+ x x))")
        (hosted-run! hosted (str "(clojure.test/deftest ^{:seon/tasks #{'" (task/task-sym host-ns "square") "}} square-test"
                                 " (clojure.test/is (= 9 (square 3))))"))
        (is (= ":unproven" (refusal)) "a red proof refuses")
        (is (empty? (to-root)) "and root hears nothing")
        (hosted-run! hosted "(defn square \"Square x.\" {:malli/schema [:=> [:cat :int] :int]} [x] (* x x))")
        (let [[about] (hosted-run! hosted "(:seon.task/merge (my.program/request-merge! {}))")
              mine #(filter (fn [open] (= branch (:seon.task/branch open))) (my.root/merge-requests {:seon.db/db (db/db connection)}))
              [open] (mine)]
          (is (string? (read-string about)) "the proven request is recorded on the agent's branch")
          (is (= [branch] (map :seon.task/branch (mine))) "root reads the open assignment, the merge request, from facts")
          (is (= [(list 'my.root/merge! {:seon.task/assignment (:seon.task/assignment open)})]
                 (take 1 (:seon.render/operations open)))
              "a merge request offers its merge by its assignment")
          (hosted-run! hosted "(my.program/request-merge! {})")
          (is (= [open] (mine)) "requesting again opens no second merge request"))
        (is (= {:seon.task/changed? true}
               (select-keys (as-root task/create! (assoc declaration :seon.agent/id "root" :seon.task/intent "Square it."
                                                         :seon.task/basis {task-id declaration}))
                            [:seon.task/changed?])))
        (is (not= ":revised-since-assignment" (refusal)) "a task root revised stays assigned at its new revision")))))

(deftest ^{:seon.test/long "One nested seon.test/run (checkpoint world + one member); 60 s is merge-test's measured bound for one nested run."
           :seon.test/long-ms 60000}
  a-branch-agents-test-member-writes-its-own-world
  ;; Before, the member's body thread inherited the calling agent's custody, so
  ;; a file test's fixture write on its member world was refused as a write
  ;; naming another branch than the agent's.
  (with-host-world {}
    (fn [{:keys [handle branch!]}]
      (let [hosted (host-on! handle (branch!) "mcp/member-custody")
            shown (first (hosted-run! hosted (str "(let [r (my.test/check {:seon.test/changed ['my.task-test/current-answers-the-held-task-with-its-proofs]})] "
                                                  "[(:seon.test/passed? r) (mapv (juxt :seon.test/sym :seon.test/error-count :seon.test/failure-message) (:seon.test/results r))])")))]
        (is (= "[true [[my.task-test/current-answers-the-held-task-with-its-proofs 0 nil]]]" shown))))))

(deftest ^{:seon/tasks #{'seon.cluster.host/a-refused-deletion-reaches-the-deleting-forms-receipt}}
  a-refused-deletion-is-its-forms-refusal-and-the-run-closes
  ;; Before, one refused declaration replaced every form's shown text with the
  ;; refusal; a refusal for a value the receipt could not store left the run open.
  ;; The fold refuses a deletion whose referrers survive at that form's own facts
  ;; (seon.turn.batch/removal-facts), so only the deleting form carries the
  ;; refusal, naming the referrer beside its value; its siblings settle.
  (with-host-world {}
    (fn [{:keys [handle branch!]}]
      (let [hosted (host-on! handle (branch!) "mcp/refused")
            connection (get-in hosted [:seon.turn.loop/cluster :seon.db/connection])
            helper (symbol (str host-ns) "refused-helper")]
        (hosted-run! hosted "(defn refused-helper \"Add one.\" {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))")
        (hosted-run! hosted "(defn refused-caller \"Call the helper.\" {:malli/schema [:=> [:cat :int] :int]} [x] (refused-helper x))")
        (let [{receipts :receipts}
              (hosted-run-receipts hosted "(+ 1 2)\n(ns-unmap 'my.agents.host-probe 'refused-helper)\n(+ 3 4)\n(/ 1 0)")
              errors (mapv :seon.cluster.eval/error receipts)]
          (is (= [0 1 2 3] (mapv :seon.cluster.eval/ordinal receipts)))
          (is (= ["3" "nil" "7"] (subvec (mapv :seon.eval/shown receipts) 0 3)) "every form keeps the value it evaluated")
          (is (str/includes? (str (nth errors 1)) (str helper)) "the deleting form is refused, naming its subject")
          (is (str/includes? (str (:seon.cluster.eval/diagnostics (nth receipts 1))) "refused-caller")
              "the surviving referrer is named beside the value")
          (is (= [nil nil] (map errors [0 2])) "the sibling forms settle")
          (is (str/includes? (str (nth errors 3)) "Divide by zero") "a form's own body error stays its error"))
        (is (some? (db/pull @connection [:seon.fn/sym] [:seon.fn/sym helper])) "the refused deletion removed nothing")
        (is (= ["2"] (hosted-run! hosted "(refused-caller 1)")) "the run closed and SCI still holds the helper")))))

(deftest ^{:seon.test/long "Eight ordinary runs of a hosted agent (five gated defns, two refused or repaired deletion runs, a complete repair; ~200-500 ms each with gate and settlement) plus five evaluate-sources batches: 5.9 s measured on default (tests-0627k, 2026-09-27)."
           :seon.test/long-ms 10000}
  a-refused-deletion-is-associated-only-with-the-one-evaluation-proposing-it
  ;; An association, never a cause: the writer names broken final relations,
  ;; so a deletion is associated only when exactly one settled entity deletes the
  ;; named identity and none declares it; otherwise the reason is named.
  (with-host-world {}
    (fn [{:keys [handle branch!]}]
      (let [agent-id "mcp/proposals"
            hosted (host-on! handle (branch!) agent-id)
            cluster (:seon.turn.loop/cluster hosted)
            connection (:seon.db/connection cluster)
            named #(symbol (str host-ns) %)
            fn-source (fn [n body] (str "(defn " n " \"Doc.\" {:malli/schema [:=> [:cat :int] :int]} [x] " body ")"))
            unmap #(str "(ns-unmap '" host-ns " '" % ")")
            proposals-of #(#'turn/deletion-proposals (db/carried-projection (db/db connection)) %1 %2)
            without-referrers (fn [proposals] (mapv #(dissoc % :seon.program/referrers) proposals))]
        (doseq [source [(fn-source "h" "(inc x)") (fn-source "k" "(dec x)")
                        (fn-source "c1" "(h x)") (fn-source "c2" "(h x)") (fn-source "ck" "(k x)")]]
          (is (= 1 (count (hosted-run! hosted source)))))
        (testing "a partial repair: h's callers go with it, k's caller survives; only k is named"
          (let [{run-id :seon.turn/id receipts :receipts}
                (hosted-run-receipts hosted (str/join "\n" [(unmap "h") (unmap "c1") (unmap "c2") (unmap "k")]))
                {:keys [proposals]} (recorded-proposals @connection run-id)]
            (is (= [{:seon.program/identity [:seon.fn/sym (named "k")] :seon.cluster.eval/ordinal 3}]
                   (without-referrers proposals)))
            (is (str/includes? (str (:seon.cluster.eval/error (nth receipts 3))) "Evaluation 3 proposed deleting"))
            (is (not-any? #(str/includes? (str (:seon.cluster.eval/error %)) "proposed") (take 3 receipts))
                "a repaired deletion is never associated")))
        (let [refusal (as-host hosted #(db/transact! connection [[:db/retractEntity [:seon.fn/sym (named "h")]]
                                                                 [:db/retractEntity [:seon.fn/sym (named "k")]]]))
              max-source (:seon.config.eval.result/max-source (config/result-caps (config/effective (db/db connection) cluster-name)))
              evaluate (fn [first-ordinal sources]
                         (as-host hosted
                           ;; the fold's change per form, as settle-batch! hands it
                           #(into [] (keep (fn [{ordinal :seon.cluster.eval/ordinal change :seon.batch/change}]
                                             (when change {:seon.batch/ordinal ordinal :seon.batch/change change})))
                                  (:seon.turn.loop/evaluated-sources
                                   (turn/evaluate-sources
                                   {:seon.turn.loop/cluster cluster :seon.sci.eval/ctx (:seon.sci.eval/ctx cluster)
                                    :seon.agent/id agent-id :seon.cluster.eval/ordinal first-ordinal :seon.ns/name host-ns
                                    :seon.cluster.reply/sources (turn/planned-sources (str/join "\n" sources) host-ns max-source)})))))]
          (testing "the writer's refusal declares itself and types each removed identity"
            (is (string? (:seon.db.write.attempt/request-id refusal)))
            (is (some? (:seon.error/basis refusal)))
            (is (= :seon.db.write/validation-refusal (:seon.error/type refusal)))
            (is (= :seon.program/deletion-refused-error (:seon.db.write/validator-schema refusal)))
            (is (= #{[:seon.fn/sym (named "h")] [:seon.fn/sym (named "k")]}
                   (set (map :seon.program/identity (:seon.program/referrers refusal))))))
          ;; The refusal is a value: the program may move on before the
          ;; association is judged over entities evaluated below.
          (testing "a complete repair in one run commits: neither the helper nor its callers remain"
            (let [shown (hosted-run! hosted (str/join "\n" [(unmap "h") (unmap "c1") (unmap "c2")]))]
              (is (= ["nil" "nil" "nil"] shown))
              (is (every? #(nil? (db/pull @connection [:db/id] [:seon.fn/sym (named %)])) ["h" "c1" "c2"]))))
          (evaluate 0 [(fn-source "h" "(inc x)")])
          (let [unmapped (evaluate 5 [(unmap "h")])]
            (is (= [5] (mapv :seon.batch/ordinal unmapped)) "the run's evaluation ordinals, not from zero")
            (is (= [(named "h")] (get-in (first unmapped) [:seon.batch/change :seon.batch/removals]))
                "the form's change removes the symbol; only the typed identity the writer names is associated")
            (let [proposals (proposals-of refusal unmapped)]
              (is (= [{:seon.program/identity [:seon.fn/sym (named "h")] :seon.cluster.eval/ordinal 5}
                      {:seon.program/identity [:seon.fn/sym (named "k")] :seon.turn/unattributed :no-deleting-form}]
                     (without-referrers proposals)))
              (is (= #{{:seon.fn/sym (named "c1")} {:seon.fn/sym (named "c2")}}
                     (set (map :seon.program/referrer (:seon.program/referrers (first proposals)))))
                  "two referrers of one identity are one proposal"))
            (testing "evidence that is not a supported deletion refusal associates nothing, or says why"
              (is (nil? (proposals-of (dissoc refusal :seon.db.write/validator-schema) unmapped)))
              (is (nil? (proposals-of (assoc refusal :seon.db.write/validator-schema :seon.db.write/validation-refusal) unmapped)))
              (is (= [{:seon.turn/unattributed :unknown-validator-schema}]
                     (proposals-of (assoc refusal :seon.db.write/validator-schema :my.nowhere/schema) unmapped)))
              (is (= [{:seon.turn/unattributed :invalid-deletion-evidence}]
                     (proposals-of (dissoc refusal :seon.program/referrers) unmapped)))
              (let [other (as-host hosted #(db/transact! connection [[:db/retract [:seon.agent/id agent-id] :seon.agent/id agent-id]]))]
                (is (string? (:seon.db.write.attempt/request-id other)))
                (is (nil? (proposals-of other unmapped)) "another refusal family is associated with no form"))))
          (testing "repeated changes to one identity are never associated"
            (is (= :declared-in-run
                   (:seon.turn/unattributed (first (proposals-of refusal (evaluate 7 [(fn-source "h" "(inc x)") (unmap "h")]))))))
            (is (= :several-deletions
                   (:seon.turn/unattributed
                    (first (proposals-of refusal (evaluate 9 [(fn-source "h" "(inc x)") (unmap "h")
                                                              (fn-source "h" "(inc x)") (unmap "h")])))))))
          (testing "a form declaring and removing in one is judged by the entity that reached settlement"
            (let [both (evaluate 13 [(str "(do " (fn-source "h" "(inc x)") " " (unmap "h") ")")])]
              (is (not-any? #(= 13 (:seon.cluster.eval/ordinal %)) (proposals-of refusal both))))))))))

(deftest a-branch-behind-the-loaded-program-runs-the-loaded-definitions
  ;; Before, every file-derived entity a branch held at another digest than the
  ;; loaded program counted as the branch's override, so a branch forked before
  ;; an adoption refused every form ("Host-bound declaration … must change
  ;; through the loaded source files"). Only an agent-authored entity overrides.
  (with-host-world {}
    (fn [{:keys [handle branch!]}]
      (let [branch (branch!)
            execution (agent/acquire-context! handle nil {:seon.agent/branch branch})
            connection (:seon.db/connection execution)]
        (try
          ;; The file entity as a publication the loaded program has moved past left it.
          (db/with-branch {:seon.db/connection connection}
            #(support/transacted!
              connection
              [(support/program-fn-entity
                (db/db connection) 'seon.id/digest
                "(defn digest \"An older digest.\" {:malli/schema [:=> [:cat [:int {:min 1, :max 64}] [:sequential :any]] [:string {:min 1, :max 64}]]} [length parts] \"stale\")"
                {:seon.schema.admission/source :core :seon.fn/host-bound? true})]))
          (finally (agent/release-context! execution)))
        (let [hosted (host-on! handle branch "mcp/behind")]
          (is (= ["3"] (hosted-run! hosted "(+ 1 2)")))
          (is (= [(pr-str (seon.id/digest 8 [:x]))] (hosted-run! hosted "(seon.id/digest 8 [:x])"))
              "the entity the branch did not author runs the loaded Var"))))))

(deftest a-named-change-reached-only-by-an-ineligible-test-refuses-by-name
  ;; Before, the eligibility filter dropped the reaching test and the request
  ;; answered passed? false with no members, no reason and no run record.
  (with-host-world {}
    (fn [{:keys [handle branch!]}]
      (let [branch (branch!)
            execution (agent/acquire-context! handle nil {:seon.agent/branch branch})
            connection (:seon.db/connection execution)]
        (try
          (db/with-branch {:seon.db/connection connection}
            #(do (support/transacted! connection [(support/namespace-entity (db/db connection) host-ns)])
                 (support/transacted! connection
                   [(support/program-fn-entity (db/db connection) 'my.agents.host-probe/dropped-callee
                                            "(defn dropped-callee \"Add one.\" {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))")])
                 ;; A file-less core test: neither agent-authored, under a test root, nor named.
                 (support/transacted! connection
                   [(support/program-entity (db/db connection) [:seon.test/sym 'my.agents.host-probe/dropped-check]
                                         "(clojure.test/deftest dropped-check (clojure.test/is (= 2 (dropped-callee 1))))"
                                         {:seon.schema.admission/source :core})])))
          (finally (agent/release-context! execution)))
        (let [hosted (host-on! handle branch "mcp/dropped")
              shown (first (hosted-run! hosted "(let [r (my.test/check {:seon.test/changed ['my.agents.host-probe/dropped-callee]})] [(:seon.test/selection-refusal r) (:seon.error/offending r)])"))]
          (is (= "[:seon.test/coverage-unknown [my.agents.host-probe/dropped-check]]" shown)))))))

(deftest a-refused-deletion-names-its-surviving-caller-and-a-plan-proposes-only-installed-forms
  ;; Before, breaks' plan proposed (my.program/launch! …), which does not exist,
  ;; and a refused ns-unmap! answered "expected a map (:seon.program/change), got nil nil".
  (with-host-world {}
    (fn [{:keys [handle branch!]}]
      (let [hosted (host-on! handle (branch!) "mcp/deleter")]
        (hosted-run! hosted "(defn breaks-helper \"Add one.\" {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))")
        (hosted-run! hosted "(defn breaks-caller \"Call the helper.\" {:malli/schema [:=> [:cat :int] :int]} [x] (breaks-helper x))")
        (is (= ["{:seon.program/callers #{my.agents.host-probe/breaks-caller}}"]
               (hosted-run! hosted "(:seon.error/offending (my.program/ns-unmap! 'my.agents.host-probe/breaks-helper))"))
            "the refusal names the surviving caller")))))

(deftest a-message-is-no-merge-request-and-a-branch-agent-merges-in-its-own-context
  ;; Before, root inferred a pending merge request from any message about an issue
  ;; from an agent on another branch. Owner ruling 2026-10-06: no merge
  ;; function refuses a caller for not being root; a branch agent's
  ;; merge acts on its own branch, and the merge's own checks answer
  ;; (here: the assignment it names is not an open merge request).
  (with-host-world {}
    (fn [{:keys [handle connection branch!]}]
      (support/transacted! connection (support/agent-tx @connection "root"))
      (let [branch (branch!)
            hosted (host-on! handle branch "mcp/proposer")]
        (hosted-run! hosted "(my.message/send {:my.message/to \"root\" :my.message/about \"a-fix\" :my.message/content \"Please merge.\"})")
        (is (= [] (my.root/merge-requests {:seon.db/db (db/db connection)})) "only a proven merge request is one")
        (is (= ["[:seon.task/assignment \"000000000000\"]"]
               (hosted-run! hosted "((juxt :seon.error/member :seon.error/offending) (my.root/merge! {:seon.task/assignment \"000000000000\"}))")))))))

;;; A restart loses every host and its branch connection while the branch stays
;;; listed and the agent admitted; `host/stop!` drops exactly that.

(defn- closed-runs-of
  "The number of closed runs of `agent-id` on `database`."
  [database agent-id]
  (or (db/q '[:find (count ?run) . :in $ ?id :where [?agent :seon.agent/id ?id]
              [?run :seon.turn/agent ?agent] [?run :seon.turn/closed-tx _]]
            database agent-id)
      0))

(defn- sender!
  "Create the agent `sender` on the cluster before any branch is made from it: a
  message is written on its recipient's home branch, which must hold the sender."
  [connection sender]
  (support/transacted! connection (support/agent-tx @connection sender)))

(defn- send-from-cluster!
  "`my.message/send` from `sender` (`sender!`) as the cluster's own work."
  [handle connection sender request]
  (db/with-branch {:seon.db/connection connection}
    #(my.message/send (merge {:seon.db/connection connection :seon.agent/id sender
                              :seon.agent/context-source handle}
                             request))))

(deftest ^{:seon/tasks #{'seon.cluster.host/a-message-resumes-a-stopped-agent}}
  a-message-to-a-stopped-admitted-agent-hosts-it-and-its-db<->flow-takes-it
  (with-host-world {}
    (fn [{:keys [handle branch! connection]}]
      (let [_ (sender! connection "resume-sender")
            branch (branch!)
            hosted (host-on! handle branch "mcp/sleeper")]
        (assign-hosted! hosted)
        (is (= ["1"] (hosted-run! hosted "1")))
        (is (nil? (host/stop! hosted)))
        (is (nil? (db/branch-connection (db/db connection) branch)) "the restart dropped the connection")
        (let [sent (send-from-cluster! handle connection "resume-sender"
                                       {:my.message/to "mcp/sleeper" :my.message/content "Why?"})
              resumed (host/hosted handle branch)
              branch-connection (get-in resumed [:seon.turn.loop/cluster :seon.db/connection])]
          (is (:seon.message/id sent) "the message was written")
          (is (some? resumed) "the send hosted the agent on the cluster registry")
          (when branch-connection
            (support/await-event! branch-connection ::resumed
                                  (fn [_] (< 1 (closed-runs-of @branch-connection "mcp/sleeper"))))
            (is (< 1 (closed-runs-of @branch-connection "mcp/sleeper")) "its db<->flow took the message")))))))

(deftest ^{:seon/tasks #{'seon.cluster.host/a-message-resumes-a-stopped-agent}}
  an-sci-send-from-the-cluster-supplies-its-handle-and-resumes-a-stopped-agent
  ;; The sender runs on the cluster as an ordinary SCI evaluation, so
  ;; `my.message/send` takes the cluster's handle from its world by schema name.
  (with-host-world {}
    (fn [{:keys [handle branch! connection]}]
      (let [_ (sender! connection "cluster-sender")
            branch (branch!)
            hosted (host-on! handle branch "mcp/waker")
            routing (:seon.agent/routing handle)]
        (assign-hosted! hosted)
        (is (= ["1"] (hosted-run! hosted "1")))
        (is (nil? (host/stop! hosted)))
        (try
          (let [sent (db/with-branch {:seon.db/connection connection}
                       #(agent/evaluate-source!
                         {:seon.turn.loop/cluster handle :seon.agent/routing routing :seon.agent/id "cluster-sender"
                          :seon.agent/segmenter 'seon.turn/code-sources
                          :seon.cluster.reply/text "(:seon.message/id (my.message/send {:my.message/to \"mcp/waker\" :my.message/content \"Why?\"}))"}))
                resumed (host/hosted handle branch)
                branch-connection (get-in resumed [:seon.turn.loop/cluster :seon.db/connection])]
            (is (string? (some-> (drive/run-receipts (:seon.db/db sent) [(:seon.turn/id sent)]) first :seon.eval/shown read-string))
                "the evaluation's send wrote the message")
            (is (some? resumed) "the send hosted the agent through the cluster handle its world supplied")
            (when branch-connection
              (support/await-event! branch-connection ::woken
                                    (fn [_] (< 1 (closed-runs-of @branch-connection "mcp/waker"))))
              (is (< 1 (closed-runs-of @branch-connection "mcp/waker")) "the resumed agent ran")))
          (finally
            (doseq [id (keys (:seon.agent/started @routing))]
              (agent/stop-flow! {:seon.agent/routing routing :seon.agent/id id}))))))))

(deftest ^{:seon/tasks #{'seon.cluster.host/retiring-a-branch-closes-its-agents-admission}}
  retiring-a-stopped-agents-branch-closes-its-admission
  (with-host-world {}
    (fn [{:keys [handle branch! connection]}]
      (let [branch (branch!)]
        (is (nil? (host/stop! (host-on! handle branch "mcp/retiree"))))
        (is (= {:seon.store/branch branch :my.agent/retired? true}
               (my.agent/retire-branch! {:seon.store/branch branch}
                                        {:seon.store/store (:seon.store/store handle) :seon.db/connection connection
                                         :seon.agent/routing (:seon.agent/routing handle)})))
        (is (some? (:seon.agent/admission-closed-tx
                    (db/pull (db/db connection) [:seon.agent/admission-closed-tx] [:seon.agent/id "mcp/retiree"])))
            "the agent whose home was retired is no longer admitted")))))

(deftest an-ended-taskless-agent-with-nothing-unmerged-has-its-branch-deleted
  ;; two MCP agents, each forked from main as `my.agent/create-branch!` forks, write their own end; only the
  ;; one whose branch holds nothing main lacks has its branch deleted by root's next db<->flow pass
  (with-host-world {}
    (fn [{:keys [handle connection]}]
      (let [store (:seon.store/store handle)
            routing (:seon.agent/routing handle)
            fork! (fn [] (:seon.store/branch (registry/fork! {:seon.store/store store :seon.store/branch (keyword (str "host-test-" (id/id)))
                                                              :seon.cluster.registry/from (db/commit-id (db/db connection))
                                                              :seon.db/db (db/db connection)})))
            ended (fork!)
            unmerged (fork!)
            end! #(hosted-run! % (str "(:my.agent/retiring? (my.agent/retire-branch! {:seon.store/branch "
                                      (get-in % [:seon.turn.loop/cluster :seon.agent/branch]) "}))"))]
        (try
          (let [first-agent (host-on! handle ended "mcp/ended")
                second-agent (host-on! handle unmerged "mcp/unmerged")]
            (is (= ["true"] (end! first-agent)) "the first agent writes its end on its own branch")
            (hosted-run! second-agent "(defn kept-probe \"Add one.\" {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))")
            (is (= ["true"] (end! second-agent)) "the second writes its end after one definition")
            (agent/db<->flow {:seon.turn.loop/cluster (assoc handle :seon.agent/routing routing) :seon.agent/routing routing}
                             :seon.flow/plan :seon.agent/test-wake)
            (is (empty? (db/q '[:find ?e :where [?e :seon.error/signature]] (db/db connection))) "the pass recorded no fault")
            (is (not (contains? (registry/branches store) ended)) "the branch holding nothing unmerged is deleted")
            (is (some? (:seon.agent/admission-closed-tx
                        (db/pull (db/db connection) [:seon.agent/admission-closed-tx] [:seon.agent/id "mcp/ended"])))
                "main's row holds its end")
            (is (contains? (registry/branches store) unmerged) "the branch holding a definition main lacks is kept")
            (is (= [] (filterv #(and (= unmerged (:seon.task/branch %)) (= :delete-branch (:seon.flow.plan/op %)))
                               (agent/next-actions (agent/lifecycle-facts (db/db connection) store (agent/registry-facts routing)))))
                "and no :delete-branch is derived for it"))
          (finally
            (doseq [branch [ended unmerged]]
              (some-> (host/hosted handle branch) host/retire!)
              (registry/retire-branch! {:seon.store/store store :seon.store/branch branch}))))))))

(deftest ^{:seon/tasks #{'seon.cluster.host/a-message-to-an-agent-whose-branch-is-gone-refuses-by-name}}
  a-message-to-an-admitted-agent-whose-branch-was-deleted-refuses-branch-absent
  (with-host-world {}
    (fn [{:keys [handle branch! connection]}]
      (let [_ (sender! connection "orphan-sender")
            branch (branch!)]
        (is (nil? (host/stop! (host-on! handle branch "mcp/orphan"))))
        ;; the branches deletion alone, as root's retirements before the fix left it
        (registry/retire-branch! {:seon.store/store (:seon.store/store handle) :seon.store/branch branch})
        (is (= branch (:seon.cluster.store/branch-absent
                       (send-from-cluster! handle connection "orphan-sender"
                                           {:my.message/to "mcp/orphan" :my.message/content "Why?"})))
            "the send's error names the absent home branch")))))

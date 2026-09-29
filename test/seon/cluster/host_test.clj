(ns seon.cluster.host-test
  "A branch-hosted agent (`seon.cluster.host`) through real acquisition, armed
  contracts and Flow: its runs land on its branch, siblings stay independent,
  an ordinary message wakes it, and retirement closes admission at the writer."
  (:require [clojure.core.async :as async]
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
            [my.program]))

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

(defn- hosted-run-receipts
  "One ordinary run of the hosted agent: its settled receipts in order under
  its run id, or the refusal the submission answered."
  [hosted source]
  (let [custody (select-keys hosted [:seon.turn.loop/cluster :seon.agent/routing :seon.agent/id])
        result (as-host hosted
                 #(agent/evaluate-source! (assoc custody :seon.agent/segmenter 'seon.turn/code-sources
                                                 :seon.cluster.reply/text source)))]
    (if-let [run-id (:seon.turn/id result)]
      {:seon.turn/id run-id
       ;; the run read on the reply's own value
       :receipts (drive/run-receipts (:seon.db/db result) [run-id])}
      result)))

(defn- hosted-run!
  "One ordinary run of the hosted agent: its evaluations' shown text, in order,
  or the refusal the submission answered."
  [hosted source]
  (let [result (hosted-run-receipts hosted source)]
    (if-let [receipts (:receipts result)]
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
        (is (= ["#'my.agents.host-probe/host-probe"]
               (hosted-run! hosted "(defn host-probe \"Add one.\" {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))")))
        (is (= ["2"] (hosted-run! hosted "(host-probe 1)")) "the agent's next run calls its def")
        (let [note-id (str "host-note-" (id/id))]
          (hosted-run! hosted (pr-str (list 'seon.db/transact!
                                            {:tx-data [{:my.note/id note-id :my.note/agent [:seon.agent/id "mcp/solo"]
                                                        :my.note/content "written by a hosted form"}]})))
          (let [database @(get-in hosted [:seon.turn.loop/cluster :seon.db/connection])]
            (is (some? (db/q '[:find ?r . :in $ ?note-id :where [?n :my.note/id ?note-id] [?n :my.note/content _ ?tx]
                               [?tx :seon.db/receipt ?r]] database note-id))
                "a form's own elided write lands on the branch, naming its evaluation")
            (is (nil? (db/q '[:find ?n . :in $ ?note-id :where [?n :my.note/id ?note-id]] (db/db connection) note-id))
                "and never on the parent")))
        (let [database @(get-in hosted [:seon.turn.loop/cluster :seon.db/connection])]
          (is (= branch (get-in database [:config :branch])))
          (is (some? (:db/id (db/pull database [:db/id] probe))) "the entity is on the branch"))
        (is (= parent-commit (db/commit-id (db/db connection))) "the parent head did not move")
        (is (nil? (:db/id (db/pull (db/db connection) [:db/id] probe))) "the parent never sees the entity")
        (is (identical? (:seon.agent/routing hosted)
                        (get-in @(:seon.agent/routing handle) [:seon.agent/children branch]))
            "the host lives in the child roster the parent records")))))

(deftest a-definition-whose-body-expands-macros-persists
  ;; Before (2026-09-28 dogfood loop), a defn whose body used cond-> or case
  ;; returned its Var but wrote no entity: the positionless usages clj-kondo
  ;; records inside those expansions failed seon.turn.batch/span-of's contract.
  (with-host-world {}
    (fn [{:keys [handle branch!]}]
      (let [hosted (host-on! handle (branch!) "mcp/expander")
            probe [:seon.fn/sym (symbol (str host-ns) "expanding-probe")]]
        (is (= ["#'my.agents.host-probe/expanding-probe"]
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
        (is (nil? (host/hosted handle branch)) "the parent roster no longer records the host")))))

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
            [winner] (filter :seon.flow/graph outcomes)
            [loser] (remove :seon.flow/graph outcomes)
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
            requests (:seon.agent/requests (agent/armed (:seon.agent/routing custody) "mcp/busy"))
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
        (is (= "#'seon.id/switch-probe"
               (last (hosted-run! hosted "(in-ns 'seon.id)\n(defn switch-probe \"Add one.\" {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))"))))
        (is (= before (file-entity)) "the file namespace's entity is unchanged")
        (is (= 'seon.id (namespace-of 'seon.id/switch-probe)) "the definition references the namespace")
        (testing "a namespace the switch brings into being exists for the definitions made in it"
          (is (= "#'my.agents.switch-fresh/fresh-probe"
                 (last (hosted-run! hosted "(in-ns 'my.agents.switch-fresh)\n(defn fresh-probe \"Add two.\" {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 2))"))))
          (is (= 'my.agents.switch-fresh (namespace-of 'my.agents.switch-fresh/fresh-probe)))
          (is (nil? (:seon.ns/source (db/pull (database) [:seon.ns/source] [:seon.ns/name 'my.agents.switch-fresh])))
              "the switch's text is not the namespace's source"))
        (testing "a definition after a nested switch names a namespace no form declared"
          (is (= "#'my.agents.switch-nested/nested-probe"
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
            create #(str "(seon.task/create! {:seon.task/ns '" host-ns " :seon.task/name \"trim titles\""
                         " :seon.task/kind :function :seon.task/intent " (pr-str %) "})")
            task-id (task/task-handle host-ns "trim titles" :function)]
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

(deftest ^{:seon.test/long "Two nested seon.test/run requests of one member each (red, then green), then root's prepare (one more nested run); 60 s per nested run is merge-test's measured bound."
           :seon.test/long-ms 180000}
  a-proposal-waits-for-its-assigned-tasks-proofs-then-reaches-root
  ;; Before, propose-merge! took any issue id the agent named, ran no test and
  ;; messaged root at once; root inferred pending proposals from messages.
  (with-host-world {}
    (fn [{:keys [handle connection branch!]}]
      (support/transacted! connection (support/agent-tx @connection "root"))
      (let [branch (branch!)
            hosted (host-on! handle branch "mcp/proposer")
            refusal #(first (hosted-run! hosted "(:seon.task/refused (my.program/propose-merge! {}))"))
            to-root #(set (db/q '[:find [?about ...] :where [?r :seon.agent/id "root"] [?m :seon.message/to ?r]
                                  [?m :seon.message/about ?about]] (db/db connection)))
            declaration {:seon.task/ns host-ns :seon.task/name "square" :seon.task/kind :function :seon.task/intent "Square."}
            task-id (read-string (first (hosted-run! hosted (str "(:seon.task/id (seon.task/create! '" (pr-str declaration) "))"))))
            as-root #(db/call-with-custody {:seon.db/connection connection} (fn [] (% (assoc %2 :seon.db/connection connection))))]
        (is (= ":unassigned" (refusal)) "an agent root assigned nothing is refused by name")
        (is (= '(my.program/propose-merge! {})
               (last (:seon.render/operations (as-root task/assign! {:seon.task/assigned task-id :seon.task/agent [:seon.agent/id "mcp/proposer"]
                                                                     :seon.task/branch branch}))))
            "the assignment offers the agent its proposal")
        (is (= ":no-proofs" (refusal)) "a task no test names cannot be proposed")
        (hosted-run! hosted "(defn square \"Square x.\" {:malli/schema [:=> [:cat :int] :int]} [x] (+ x x))")
        (hosted-run! hosted (str "(clojure.test/deftest ^{:seon/tasks #{" (pr-str task-id) "}} square-test"
                                 " (clojure.test/is (= 9 (square 3))))"))
        (is (= ":unproven" (refusal)) "a red proof refuses")
        (is (empty? (to-root)) "and root hears nothing")
        (hosted-run! hosted "(defn square \"Square x.\" {:malli/schema [:=> [:cat :int] :int]} [x] (* x x))")
        (let [[about] (hosted-run! hosted "(:seon.task/merge (my.program/propose-merge! {}))")
              open (my.program/proposals {:seon.db/db (db/db connection)})]
          (is (= #{(read-string about)} (to-root)) "a green proof tells root, naming the merge task")
          (is (= [[(read-string about) branch #{'my.agents.host-probe/square-test}]]
                 (mapv (juxt :seon.task/merge :seon.task/branch #(set (map first (:seon.task/proved %)))) open))
              "root reads the open proposal from facts")
          (hosted-run! hosted "(my.program/propose-merge! {})")
          (is (= #{(read-string about)} (set (map :seon.task/merge (my.program/proposals {:seon.db/db (db/db connection)}))))
              "proposing again finds the same merge task")
          ;; Before (2026-09-28 dogfood loop), root's prepare took an issue id, so
          ;; the proposal an agent made could not be prepared at all.
          (let [context (assoc (select-keys handle [:seon.sci.eval/ctx :seon.db/connection :seon.cluster/name
                                                    :seon.store/store :seon.env/environment])
                               :my.program/base-ctx (:seon.sci.eval/ctx handle))
                prepared (db/call-with-custody {:seon.db/connection connection}
                                               #(my.program/prepare-merge! (assoc (first open) :my.program/context context)))]
            (some->> (:seon.source/candidate prepared)
                     (hash-map :seon.store/store (:seon.store/store handle) :seon.store/branch)
                     registry/retire-branch!)
            (is (true? (:seon.test/passed? prepared)) "root prepares the proposal it read")
            (is (= [#{'my.agents.host-probe/square-test} (read-string about)]
                   [(:seon.task/proofs prepared) (:seon.task/merge prepared)])
                "the proposal's proofs gate the merge, and it names its merge task")
            (is (= [(list 'my.program/prepare-merge! {:seon.task/merge (read-string about)})]
                   (:seon.render/operations (first open)))
                "a proposal offers its prepare")
            (is (= 'my.program/accept-merge! (ffirst (:seon.render/operations prepared)))
                "the prepared merge offers its accept")
            (is (= {:seon.task/id (read-string about) :seon.task/outcome :declined}
                   (task/decide! connection (read-string about) :declined)))
            (is (empty? (my.program/proposals {:seon.db/db (db/db connection)})) "a decided merge task is closed")))
        (is (= {:seon.task/changed? true}
               (select-keys (as-root task/create! (assoc declaration :seon.agent/id "root" :seon.task/intent "Square it."
                                                         :seon.task/basis {task-id declaration}))
                            [:seon.task/changed?])))
        (is (= ":revised-since-assignment" (refusal)) "a task revised since root assigned it refuses")))))

(deftest ^{:seon.test/long "One nested seon.test/run (checkpoint world + one member); 60 s is merge-test's measured bound for one nested run."
           :seon.test/long-ms 60000}
  a-branch-agents-test-member-writes-its-own-world
  ;; Before, the member's body thread inherited the calling agent's custody, so
  ;; a file test's fixture write on its member world was refused as a write
  ;; naming another branch than the agent's.
  (with-host-world {}
    (fn [{:keys [handle branch!]}]
      (let [hosted (host-on! handle (branch!) "mcp/member-custody")
            shown (first (hosted-run! hosted (str "(let [r (my.test/check {:seon.test/changed ['seon.task-steps-test/step-appends-and-is-current-when-none-is]})] "
                                                  "[(:seon.test/passed? r) (mapv (juxt :seon.test/sym :seon.test/error-count :seon.test/failure-message) (:seon.test/results r))])")))]
        (is (= "[true [[seon.task-steps-test/step-appends-and-is-current-when-none-is 0 nil]]]" shown))))))

(deftest a-refused-deletion-is-its-forms-refusal-and-the-run-closes
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
            (is (= :seon.db.write/validation-refusal (:seon.error/declared-schema (meta refusal))))
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
          (db/call-with-custody {:seon.db/connection connection}
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
          (db/call-with-custody {:seon.db/connection connection}
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

(deftest a-message-is-no-proposal-and-only-roots-custody-accepts
  ;; Before, root inferred a pending proposal from any message about an issue
  ;; from an agent on another branch. A branch agent's custody neither accepts
  ;; a merge nor writes files: it refuses by name.
  (with-host-world {}
    (fn [{:keys [handle connection branch!]}]
      (support/transacted! connection (support/agent-tx @connection "root"))
      (let [branch (branch!)
            hosted (host-on! handle branch "mcp/proposer")]
        (hosted-run! hosted "(my.message/send {:my.message/to \"root\" :my.message/about \"a-fix\" :my.message/content \"Please merge.\"})")
        (is (= [] (my.program/proposals {:seon.db/db (db/db connection)})) "only a proven proposal is one")
        (is (= [(str [:my.program/context branch])]
               (hosted-run! hosted (str "((juxt :seon.error/member :my.program/held-branch) (my.program/accept-merge! "
                                        "{:seon.source/candidate :tested :seon.test.run/id \"run\" "
                                        ;; a complete request (0db8bf035 made proofs and the merge task required),
                                        ;; so the custody check is what answers
                                        ":seon.task/proofs #{} :seon.task/merge \"000000000000\" "
                                        ":seon.source/expected-digests {} :parents #{}}))"))))))))

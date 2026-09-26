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
        (testing "a definition after a nested switch names a namespace no form declared"
          (is (= "#'my.agents.switch-nested/nested-probe"
                 (last (hosted-run! hosted "(do (in-ns 'my.agents.switch-nested) nil)\n(defn nested-probe \"Add three.\" {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 3))"))))
          (is (= 'my.agents.switch-nested (namespace-of 'my.agents.switch-nested/nested-probe))))
        (testing "an ns form declares its namespace with its requires and aliases"
          (hosted-run! hosted "(ns my.agents.switch-declared (:require [clojure.string :as str]))")
          (is (= [#:seon.ns.alias{:local 'str :target-ns 'clojure.string}]
                 (:seon.ns/aliases (db/pull (database) [{:seon.ns/aliases [:seon.ns.alias/local :seon.ns.alias/target-ns]}]
                                            [:seon.ns/name 'my.agents.switch-declared])))))))))

(deftest a-run-reads-its-earlier-declarations
  ;; Before the run's working value, a redefinition that broke a test defined
  ;; earlier in the same run installed ungated: the gate read the connection,
  ;; where the run's rows land only at settlement.
  (with-host-world {}
    (fn [{:keys [handle branch!]}]
      (let [hosted (host-on! handle (branch!) "mcp/one-run")
            shown (hosted-run! hosted (str "(defn run-square \"Square x.\" {:malli/schema [:=> [:cat :int] :int]} [x] (* x x))\n"
                                           "(clojure.test/deftest run-square-test (clojure.test/is (= 16 (run-square 4))))\n"
                                           "(defn run-square \"Square x.\" {:malli/schema [:=> [:cat :int] :int]} [x] (* x x x))\n"
                                           "(run-square 4)"))]
        (is (= "#'my.agents.host-probe/run-square" (first shown)) (pr-str shown))
        (is (re-find #"install refused" (str (nth shown 2))) "the gate ran the earlier test and refused the break")
        (is (= "16" (last shown)) "the first body stays installed within the run")
        (is (= ["16"] (hosted-run! hosted "(run-square 4)")) "and after it settles")))))

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

(deftest a-hosted-agent-proposes-a-merge-and-root-receives-it-on-the-cluster
  ;; Before, a branch agent's message to root was written on its branch only:
  ;; root, on the cluster branch, never saw it, and the send answered success.
  (with-host-world {}
    (fn [{:keys [handle connection branch!]}]
      (support/transacted! connection (support/agent-tx @connection "root"))
      (let [branch (branch!)
            hosted (host-on! handle branch "mcp/proposer")
            on-cluster #(db/pull (db/db connection) [:seon.message/content {:seon.message/from [:seon.agent/id]}
                                                     {:seon.message/to [:seon.agent/id]}]
                                 [:seon.message/id %])]
        (is (= branch (:seon.agent/branch (db/pull (db/db connection) [:seon.agent/branch] [:seon.agent/id "mcp/proposer"])))
            "the hosted agent is a cluster row naming its home branch")
        (hosted-run! hosted "(defn proposed-square \"Square x.\" {:malli/schema [:=> [:cat :int] :int]} [x] (* x x))")
        (let [sent (first (hosted-run! hosted "(:seon.message/id (my.program/propose-merge! {:seon.issue/id \"proposed-square\"}))"))
              message (on-cluster (read-string sent))]
          (is (= {:seon.agent/id "mcp/proposer"} (:seon.message/from message)) (pr-str sent))
          (is (= {:seon.agent/id "root"} (:seon.message/to message)))
          (is (= [branch "proposed-square" true]
                 (let [content (read-string (:seon.message/content message))]
                   [(:seon.agent/branch content) (:seon.issue/id content)
                    (contains? (set (:seon.test/changed content)) [:seon.fn/sym 'my.agents.host-probe/proposed-square])]))))))))

(deftest ^{:seon.test/long "One nested seon.test/run (checkpoint world + one member); 60 s is merge-test's measured bound for one nested run."
           :seon.test/long-ms 60000}
  a-branch-agents-test-member-writes-its-own-world
  ;; Before, the member's body thread inherited the calling agent's custody, so
  ;; a file test's fixture write on its member world was refused as a write
  ;; naming another branch than the agent's.
  (with-host-world {}
    (fn [{:keys [handle branch!]}]
      (let [hosted (host-on! handle (branch!) "mcp/member-custody")
            shown (first (hosted-run! hosted (str "(let [r (my.test/check {:seon.test/changed ['my.plan-test/a-changed-title-is-an-ordinary-fact-update]})] "
                                                  "[(:seon.test/passed? r) (mapv (juxt :seon.test/sym :seon.test/error-count :seon.test/failure-message) (:seon.test/results r))])")))]
        (is (= "[true [[my.plan-test/a-changed-title-is-an-ordinary-fact-update 0 nil]]]" shown) shown)))))

(deftest a-refused-settlement-is-its-forms-refusal-and-the-run-closes
  ;; Before, one refused declaration replaced every form's shown text with the
  ;; refusal; a refusal for a value the receipt could not store left the run open.
  (with-host-world {}
    (fn [{:keys [handle branch!]}]
      (let [hosted (host-on! handle (branch!) "mcp/refused")]
        (hosted-run! hosted "(defn refused-helper \"Add one.\" {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))")
        (hosted-run! hosted "(defn refused-caller \"Call the helper.\" {:malli/schema [:=> [:cat :int] :int]} [x] (refused-helper x))")
        (let [shown (hosted-run! hosted "(+ 1 2)\n(ns-unmap 'my.agents.host-probe 'refused-helper)\n(+ 3 4)")]
          (is (= "3" (first shown)) (pr-str shown))
          (is (re-find #"surviving referrers" (str (second shown))) "the refusal is the deleting form's")
          (is (= "7" (last shown)) "the other forms keep their own results"))
        (is (= ["2"] (hosted-run! hosted "(refused-caller 1)")) "the run closed and the next run is admitted")))))

(deftest a-branch-behind-the-loaded-program-runs-the-loaded-definitions
  ;; Before, every file-derived row a branch held at another digest than the
  ;; loaded program counted as the branch's override, so a branch forked before
  ;; an adoption refused every form ("Host-bound declaration … must change
  ;; through the loaded source files"). Only an agent-authored row overrides.
  (with-host-world {}
    (fn [{:keys [handle branch!]}]
      (let [branch (branch!)
            execution (agent/acquire-context! handle nil {:seon.agent/branch branch})
            connection (:seon.db/connection execution)]
        (try
          ;; The file row as a publication the loaded program has moved past left it.
          (db/call-with-custody {:seon.db/connection connection}
            #(support/transacted!
              connection
              [(support/program-fn-row
                (db/db connection) 'seon.id/digest
                "(defn digest \"An older digest.\" {:malli/schema [:=> [:cat [:int {:min 1, :max 64}] [:sequential :any]] [:string {:min 1, :max 64}]]} [length parts] \"stale\")"
                {:seon.schema.admission/source :core :seon.fn/host-bound? true})]))
          (finally (agent/release-context! execution)))
        (let [hosted (host-on! handle branch "mcp/behind")]
          (is (= ["3"] (hosted-run! hosted "(+ 1 2)")))
          (is (= [(pr-str (seon.id/digest 8 [:x]))] (hosted-run! hosted "(seon.id/digest 8 [:x])"))
              "the row the branch did not author runs the loaded Var"))))))

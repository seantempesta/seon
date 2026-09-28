(ns seon.cluster.evaluate-source-test
  "An outside agent's code evaluated as one ordinary run of an agent
  (`seon.cluster.agent/evaluate-source!`), segmented as code."
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.config :as config]
            [seon.db :as db]
            [seon.eval.drive :as drive]
            [seon.flow :as flow]
            [seon.id :as id]
            [seon.test-support :as support]
            [seon.turn :as turn]
            [seon.print.value]))

(defn- shown
  "Each settled evaluation's shown text; a result with no run is asserted, never read."
  [connection result]
  (is (:seon.turn/id result) (seon.print.value/bounded-text result))
  (when (:seon.turn/id result)
    ;; the run is read on the reply's own value, answered when applied
    (mapv #(or (:seon.eval/shown %) (:seon.cluster.eval/error %))
          (drive/run-receipts (or (:seon.db/db result) (db/db connection)) [(:seon.turn/id result)]))))

(defn- receipt-id-of
  "The evaluation id of a one-form run's only form."
  [result]
  (turn/receipt-identity (:seon.turn/id result) 0))

(deftest ^{:seon.test/long "Fourteen ordinary agent runs (three defining, one gated redefinition, a writing form, a renderer fault), each one Flow pass and its settlement commit, plus arming and one stopped run: green in under 20 s on a quiet JVM, 32.4 s measured under concurrent test load on default (b32060df4, 2026-09-26; settlement commits ~120-150 ms each there)."
           :seon.test/long-ms 40000
           :seon/tasks #{"d741e6812a31" "62c45543598c" "c9e0cde3edb3"}}
  code-evaluates-as-one-ordinary-run-of-its-agent
  (let [target (support/execution-handle nil)
        connection (:seon.db/connection target)
        cluster-name (:seon.cluster/name target)
        agent-id (str "evaluate-source-" (id/id))
        environment (support/environment cluster-name connection)
        ctx (support/fork-cluster-ctx connection cluster-name)
        routing (support/attempt-routing connection)
        faults (async/chan (async/sliding-buffer 16))]
    (with-open [launcher (support/closeable
                          (flow/start-work-launcher!
                           {:seon.env/environment environment
                            :seon.flow/configuration (select-keys (config/effective (db/db connection) cluster-name)
                                                                  flow/flow-workload-attributes)})
                          flow/stop-work-launcher!)]
      (let [handle (support/cluster-handle
                    {:seon.env/environment environment :seon.db/connection connection :seon.cluster/name cluster-name
                     :seon.sci.eval/ctx ctx :seon.flow/work-launcher @launcher
                     :seon.flow/executor (cluster/projection-executor (:seon.sci.eval/projection-state ctx))
                     :seon.db.process/id cluster/boot-process-identity})
            request {:seon.turn.loop/cluster handle :seon.agent/routing routing :seon.agent/id agent-id}
            code #(agent/evaluate-source! (assoc request :seon.agent/segmenter 'seon.cluster.reply/code-sources
                                                 :seon.cluster.reply/text %))]
        (swap! routing assoc :seon.agent/fault-channel faults)
        (support/transacted! connection (support/agent-tx @connection agent-id))
        (agent/arm! request)
        (try
          (testing "every form of the code is one evaluation of the run, in order; nothing is read as prose"
            (let [result (code "1\n#?(:clj 2 :cljs 3)\n(str *ns*)")]
              (is (:seon.turn/id result) (seon.print.value/bounded-text result))
              (is (= ["1" "2" (pr-str (str "my.agents." agent-id))] (shown connection result)))))
          (testing "a run starts where the agent's previous run ended, as *ns* persists at a REPL (85b459845)"
            (is (= "\"seon.id\"" (last (shown connection (code "(in-ns 'seon.id)\n(str *ns*)"))))
                "the run's own later form reads the namespace its earlier form entered")
            (is (= ["\"seon.id\""] (shown connection (code "(str *ns*)")))
                "the next run starts where the last one ended")
            (is (= [(pr-str (str "my.agents." agent-id))]
                   (rest (shown connection (code (str "(in-ns 'my.agents." agent-id ")\n(str *ns*)")))))
                "in-ns moves the agent back")
            (is (= ["\"seon.id\""]
                   (shown connection (agent/evaluate-source! (assoc request :seon.cluster.reply/text "(str *ns*)"
                                                                    :seon.turn/starting-ns [:seon.ns/name 'seon.id]))))
                "a requested starting namespace is where the run starts (85b459845: no starting-namespace refusal)")
            ;; that run ended in seon.id, so the next one starts there: move the
            ;; agent back before the sections below define in its own namespace
            (is (= [(pr-str (str "my.agents." agent-id))]
                   (rest (shown connection (code (str "(in-ns 'my.agents." agent-id ")\n(str *ns*)")))))
                "the agent is back in its own namespace"))
          (testing "a defn then a require in one run settles: its entity digests against the namespace entity the run stores"
            (let [namespace-name (str "my.agents." agent-id)
                  defn-source "(defn run-inc \"Adds one.\" {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))"
                  settled (fn [sources]
                            (let [result (code sources)
                                  receipts (drive/run-receipts (:seon.db/db result) [(:seon.turn/id result)])]
                              (is (not-any? :seon.cluster.eval/error receipts) (seon.print.value/bounded-text receipts))
                              (shown connection result)))]
              (is (= [(str "#'" namespace-name "/run-inc") "nil"]
                     (settled (str defn-source "\n(require '[malli.core :as m])"))))
              (is (= ["nil" (str "#'" namespace-name "/run-inc2")]
                     (settled (str "(require '[clojure.set :as set])\n"
                                   (str/replace defn-source "run-inc " "run-inc2 ")))))))
          (testing "the reply planner stays the default: a bare number is prose there"
            (is (:seon.cluster.reply/no-forms (agent/evaluate-source! (assoc request :seon.cluster.reply/text "1")))))
          (testing "a segmenter without the planner's contract refuses by name before any run"
            (let [runs #(count (db/q '[:find ?r :in $ ?id :where [?a :seon.agent/id ?id] [?r :seon.turn/agent ?a]]
                                     (db/db connection) agent-id))
                  before (runs)
                  refused (agent/evaluate-source! (assoc request :seon.agent/segmenter 'clojure.core/str
                                                         :seon.cluster.reply/text "1"))]
              (is (= :seon.agent/segmenter (:seon.error/member refused)) (seon.print.value/bounded-text refused))
              (is (= before (runs)))))
          (testing "the answer carries the value its settlement left; the MCP observation reads it there"
            ;; eval-path cut 1 (audit M4, task branch-eval-answers-no-events):
            ;; the bridge re-read the connection and could answer no events.
            (let [observe #(agent/run-observation % agent-id cluster-name)
                  shown-of #(mapv (fn [e] (or (:seon.eval/shown e) (:seon.cluster.eval/error e))) (observe %))
                  value (code "(inc 1)")
                  failed (code "(throw (ex-info \"a form's own error\" {}))")]
              (is (some? (:seon.db/db value)) (seon.print.value/bounded-text (dissoc value :seon.db/db)))
              (is (= ["2"] (shown-of value)) "a successful value, from the settlement's own value")
              (is (= [0] (mapv :seon.cluster.eval/ordinal (observe value))))
              (is (some? (:seon.cluster.eval/error (first (observe failed))))
                  "a returned error value is its run's observation")
              (is (string? (:seon.repl/response (first (observe failed)))))
              (support/transacted! connection (support/agent-tx @connection (str agent-id "-later")))
              (is (< (db/basis-t (:seon.db/db value)) (db/basis-t (db/db connection)))
                  "a later commit moves the connection, not the reply's basis")
              (is (= ["2"] (shown-of value)) "and the reply's observation is unchanged")))
          (testing "a refused settlement answers its run's observation on the refusal's own value"
            (code "(defn refused-helper \"Add one.\" {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))")
            (code "(defn refused-caller \"Call.\" {:malli/schema [:=> [:cat :int] :int]} [x] (refused-helper x))")
            (let [result (code (str "(ns-unmap '" (str "my.agents." agent-id) " 'refused-helper)"))
                  observed (agent/run-observation result agent-id cluster-name)]
              (is (some? (:seon.db/db result)) (seon.print.value/bounded-text (dissoc result :seon.db/db)))
              (is (re-find #"surviving referrers" (str (:seon.cluster.eval/error (first observed))))
                  (seon.print.value/bounded-text (mapv #(select-keys % [:seon.cluster.eval/ordinal :seon.cluster.eval/error]) observed)))))
          (testing "a refused multi-form settlement answers its refusal beside every evaluated value (task a-refused-multi-form-settlement-returns-nothing)"
            ;; WANTED: the direct caller of an opened run keeps the refusal;
            ;; each form's value and the refusal's diagnostic are its receipts.
            (let [result (code (str "(+ 1 2)\n(ns-unmap 'my.agents." agent-id " 'refused-helper)\n(+ 3 4)"))
                  receipts (drive/run-receipts (:seon.db/db result) [(:seon.turn/id result)])]
              (is (:seon.error/message (:seon.turn.loop/refusal result)) (seon.print.value/bounded-text (dissoc result :seon.db/db)))
              (is (= ["3" "nil" "7"] (mapv :seon.eval/shown receipts)) (seon.print.value/bounded-text receipts))
              ;; `drive/run-receipts` selects no diagnostics, so the stored
              ;; receipts are read on the settlement's own value
              (is (= #{0 1 2} (set (db/q '[:find [?ordinal ...] :in $ ?id
                                           :where [?run :seon.turn/id ?id] [?receipt :seon.cluster.eval/run ?run]
                                           [?receipt :seon.cluster.eval/ordinal ?ordinal]
                                           [?receipt :seon.cluster.eval/diagnostics _]]
                                         (:seon.db/db result) (:seon.turn/id result))))
                  "the refusal is delivered beside each value")))
          (testing "an agent's refused settlements never park it: its next run settles (sweep 2026-09-27 item 2)"
            ;; A third refused run in a row reached the write-refusal bound (3):
            ;; the proc parked and the next ordinary run answered "awaited
            ;; channel closed" until restart (stabfix-a, 2026-09-27).
            (let [refused (code (str "(ns-unmap 'my.agents." agent-id " 'refused-helper)"))]
              (is (:seon.turn.loop/refusal refused) (seon.print.value/bounded-text (dissoc refused :seon.db/db))))
            (is (= ["3"] (shown connection (code "(+ 1 2)")))))
          (testing "a nested in-ns settles: both values return and the namespace SCI moved into exists (task a318b2d38)"
            (let [target (symbol (str "my.agents." agent-id ".nested"))
                  result (code (str "(do (in-ns '" target ") 1)\n(+ 1 2)"))]
              (is (= ["1" "3"] (shown connection result)))
              (is (some? (:db/id (db/pull (:seon.db/db result) [:db/id] [:seon.ns/name target]))))))
          (testing "nothing after evaluation realizes more of a value than its bounded print (task d1efccd3ab7e)"
            ;; WANTED: the value's one walk is the print inside the arm. A
            ;; post-evaluation walk realized all 200,000 elements here, and
            ;; `(range)` killed default (landing oom-63809).
            (code "(def realized (atom 0))\n(map (fn [x] (swap! realized inc) x) (range 200000))")
            (let [realized (parse-long (first (shown connection (code "@realized"))))]
              (is (< realized 1000) (str realized " elements realized after the print"))
              ;; The infinite case only once the finite one proves no later walk.
              (when (< realized 1000)
                (let [old-gen #(transduce (comp (filter (fn [pool] (str/includes? (.getName ^java.lang.management.MemoryPoolMXBean pool) "Old Gen")))
                                                (map (fn [pool] (.getUsed (.getUsage ^java.lang.management.MemoryPoolMXBean pool)))))
                                          + (java.lang.management.ManagementFactory/getMemoryPoolMXBeans))
                      before (old-gen)
                      shown-values (shown connection (code "(range)\n(map inc (range))"))]
                  (is (= 2 (count shown-values)))
                  (is (every? #(< (count %) 4000) shown-values) (seon.print.value/bounded-text shown-values))
                  (is (< (- (old-gen) before) (* 256 1024 1024)) "old gen stays flat")))))
          (testing "an unbound Var's value is a value, as in Clojure: nothing walks the result to classify it"
            (let [result (code "(declare unbound-probe)\n{:u unbound-probe}")]
              (is (not-any? :seon.cluster.eval/error
                            (drive/run-receipts (:seon.db/db result) [(:seon.turn/id result)])))))
          (testing "a form's own durable write opens its run first, so its provenance names a written evaluation"
            ;; Under one commit per form the run's entities were written only at
            ;; settlement, and every write a form made refused "Nothing found
            ;; for entity id [:seon.cluster.eval/id ...]" (task c9e0cde3edb3).
            (let [note-id (str "one-commit-note-" (id/id))
                  result (code (pr-str (list 'seon.db/transact!
                                             {:tx-data [{:my.note/id note-id
                                                         :my.note/agent [:seon.agent/id agent-id]
                                                         :my.note/content "written by a form"}]})))
                  receipts (drive/run-receipts (:seon.db/db result) [(:seon.turn/id result)])]
              (is (not-any? :seon.cluster.eval/error receipts) (seon.print.value/bounded-text receipts))
              (is (= (receipt-id-of result)
                     (db/q '[:find ?receipt-id . :in $ ?note-id
                             :where [?note :my.note/id ?note-id] [?note :my.note/content _ ?tx]
                             [?tx :seon.db/receipt ?receipt] [?receipt :seon.cluster.eval/id ?receipt-id]]
                           (:seon.db/db result) note-id))
                  "the write's transaction names the form's evaluation")))
          (testing "a fault recorded during a form names its run without refusing: the value is answered"
            ;; A renderer that throws is a later stage's failure: recorded as a
            ;; fault attributed to the run, beside the value, never a panic.
            (let [result (code "{:seon.render/ai (fn [_] (throw (ex-info \"render probe\" {}))) :probe 1}")]
              (is (:seon.turn/id result) (seon.print.value/bounded-text result))
              (is (= 1 (count (drive/run-receipts (:seon.db/db result) [(:seon.turn/id result)]))))))
          (testing "the run is written in the one transaction that settles it; submission commits nothing"
            ;; The reply is answered when its settlement is applied; its
            ;; durability follows (8cedbee81), so the connection may not yet
            ;; hold the previous run. The basis before is the previous reply's
            ;; own applied value, one commit behind this run's.
            (let [before (:seon.db/db (code "(+ 1 1)"))
                  result (code "(+ 1 2)")
                  ;; the reply's own value: the settlement applied, one past the basis before
                  after (:seon.db/db result)
                  txs (db/q '[:find ?opened ?shown ?closed :in $ ?id
                              :where [?run :seon.turn/id ?id ?opened] [?e :seon.cluster.eval/run ?run]
                              [?e :seon.eval/shown _ ?shown] [?run :seon.turn/closed-tx ?closed]]
                            after (:seon.turn/id result))
                  [[opened settled closed]] (vec txs)]
              (is (= ["3"] (shown connection result)))
              (is (= opened settled) "the run opens in the settlement transaction")
              (is (= opened closed) "and closes in it")
              (is (= 1 (- (db/basis-t after) (db/basis-t before))) "one commit per form")))
          (testing "a submission is a value on the agent's request port; a pass stopped before settlement leaves no half-written run"
            (let [submitted (agent/submit-source! (assoc request :seon.agent/segmenter 'seon.cluster.reply/code-sources
                                                         :seon.cluster.reply/text "(loop [] (recur))"))
                  reply (:seon.agent/reply submitted)
                  requests (:seon.agent/requests (agent/armed routing agent-id))]
              (is (:seon.turn/id submitted) (seon.print.value/bounded-text submitted))
              (support/await-event! (async/go-loop []
                                      (if (zero? (count (.buf ^clojure.core.async.impl.channels.ManyToManyChannel requests)))
                                        true
                                        (do (async/<! (async/timeout 5)) (recur))))
                                    ::request-taken)
              (agent/disarm! request)
              (is (= reply (second (async/alts!! [reply (async/timeout 0)] :priority true)))
                  "the caller's reply is answered or closed when the stop returns")
              (let [database (db/db connection)]
                (is (empty? (db/q '[:find ?run :in $ ?agent-id
                                    :where [?agent :seon.agent/id ?agent-id] [?run :seon.turn/agent ?agent]
                                    (not [?run :seon.turn/closed-tx _])]
                                  database agent-id))
                    "no open run remains")
                (is (empty? (db/q '[:find ?e :in $ ?id
                                    :where [?run :seon.turn/id ?id] [?e :seon.cluster.eval/run ?run]
                                    (not [?e :seon.eval/shown _]) (not [?e :seon.cluster.eval/error _])
                                    (not [?e :seon.cluster.eval/interrupted-at _])]
                                  database (:seon.turn/id submitted)))
                    "and no evaluation without a terminal fact"))))
          (finally
            (agent/disarm! request)
            (doseq [channel [faults (:seon.cluster.wake/channel handle)
                             (:seon.render/context-channel handle) (:seon.turn.loop/completion handle)]]
              (async/close! channel))))))))

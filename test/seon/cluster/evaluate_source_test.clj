(ns seon.cluster.evaluate-source-test
  "An outside agent's code evaluated as one ordinary run of an agent
  (`seon.cluster.agent/evaluate-source!`), segmented as code."
  (:require [clojure.core.async :as async]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.config :as config]
            [seon.ai.tokens :as tokens]
            [seon.db :as db]
            [seon.eval.drive :as drive]
            [seon.flow :as flow]
            [seon.id :as id]
            [seon.render :as render]
            [seon.sci.eval :as sci.eval]
            [seon.batch :as batch]
            [seon.cache]
            [seon.problems]
            [seon.test-support :as support]
            [seon.turn :as turn]
            [seon.turn.batch :as turn.batch]
            [seon.turn.world :as world]))

(defn- shown
  "Each settled evaluation's shown text; a result with no run is asserted, never read."
  [connection result]
  (is (:seon.turn/id result))
  (when (:seon.turn/id result)
    ;; the run is read on the reply's own value, answered when applied
    (mapv #(or (:seon.eval/shown %) (:seon.cluster.eval/error %))
          (drive/run-receipts (or (:seon.db/db result) (db/db connection)) [(:seon.turn/id result)]))))

(defn- receipt-id-of
  "The evaluation id of a one-form run's only form."
  [result]
  (turn/receipt-identity (:seon.turn/id result) 0))

(defn- with-started-agent
  "`f` called with a fresh started agent's runs (`:code` evaluates text as one ordinary
  MCP SCI run) on the execution's connection; the agent is stopped after."
  [f]
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
            code #(agent/evaluate-source! (assoc request :seon.agent/segmenter 'seon.turn/code-sources
                                                 :seon.cluster.reply/text %))]
        (swap! routing assoc :seon.agent/fault-channel faults)
        (support/transacted! connection (support/agent-tx @connection agent-id))
        (agent/start-flow! request)
        (try
          (f {:connection connection :cluster-name cluster-name :agent-id agent-id :routing routing
              :handle handle :request request :code code})
          (finally
            (agent/stop-flow! request)
            (doseq [channel [faults (:seon.cluster.wake/channel handle)
                             (:seon.render/context-channel handle) (:seon.turn.loop/completion handle)]]
              (async/close! channel))))))))

(deftest ^{:seon.test/long "Fourteen ordinary agent runs (three defining, one gated redefinition, a writing form, a renderer fault), each one Flow pass and its settlement commit, plus starting and one stopped run: green in under 20 s on a quiet JVM, 32.4 s measured under concurrent test load on default (b32060df4, 2026-09-26; settlement commits ~120-150 ms each there)."
           :seon.test/long-ms 40000
           :seon/tasks #{(quote seon.turn/a-turn-pass-derives-its-work-twice)}}
  code-evaluates-as-one-ordinary-run-of-its-agent
  (with-started-agent
   (fn [{:keys [connection cluster-name agent-id routing request code]}]
          (testing "every form of the code is one evaluation of the run, in order; nothing is read as prose"
            (let [result (code "1\n#?(:clj 2 :cljs 3)\n(str *ns*)")]
              (is (:seon.turn/id result))
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
                              (is (not-any? :seon.cluster.eval/error receipts))
                              (shown connection result)))]
              (is (= [{:seon.render.var/symbol (symbol namespace-name "run-inc")} nil]
                     (mapv read-string (settled (str defn-source "\n(require '[malli.core :as m])"))))
                  "a defn shows its Var as the Var render's symbol, the require nil")
              (is (= [nil {:seon.render.var/symbol (symbol namespace-name "run-inc2")}]
                     (mapv read-string (settled (str "(require '[clojure.set :as set])\n"
                                                     (str/replace defn-source "run-inc " "run-inc2 "))))))))
          (testing "a bare number line is code on the default planner too (reader-fix: only prose becomes comments)"
            (is (= ["1"] (shown connection (agent/evaluate-source! (assoc request :seon.cluster.reply/text "1"))))))
          (testing "a segmenter without the planner's contract refuses by name before any run"
            (let [runs #(count (db/q '[:find ?r :in $ ?id :where [?a :seon.agent/id ?id] [?r :seon.turn/agent ?a]]
                                     (db/db connection) agent-id))
                  before (runs)
                  refused (agent/evaluate-source! (assoc request :seon.agent/segmenter 'clojure.core/str
                                                         :seon.cluster.reply/text "1"))]
              (is (= :seon.agent/segmenter (:seon.error/member refused)))
              (is (= before (runs)))))
          (testing "the answer carries the value its settlement left and the receipts it saved"
            ;; eval-path cut 1 (audit M4, task branch-eval-answers-no-events):
            ;; the bridge re-read the connection and could answer no events.
            (let [saved #(mapv :seon.sci.eval/evaluation (:seon.turn.loop/evaluated-sources %))
                  value (code "(inc 1)")
                  failed (code "(throw (ex-info \"a form's own error\" {}))")]
              (is (some? (:seon.db/db value)))
              (is (= ["2"] (mapv :seon.eval/shown (saved value))) "a successful value, as its settlement saved it")
              (is (= [0] (mapv :seon.cluster.eval/ordinal (saved value))))
              (is (= ["(inc 1)"] (mapv (comp :seon.cluster.eval/source :seon.turn.loop/admitted-form)
                                       (:seon.turn.loop/evaluated-sources value)))
                  "each saved receipt travels with the form it settled")
              (is (some? (:seon.cluster.eval/error (first (saved failed))))
                  "a returned error value is its run's saved receipt")
              (is (= (shown connection value) (mapv :seon.eval/shown (saved value)))
                  "what the answer holds is what the run stored")))
          (testing "a deletion with surviving referrers answers its refusal as the form's value; plain ns-unmap deletes nothing"
            (let [namespace-name (str "my.agents." agent-id)]
              (code "(defn refused-helper \"Add one.\" {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))")
              (code "(defn refused-caller \"Call.\" {:malli/schema [:=> [:cat :int] :int]} [x] (refused-helper x))")
              (let [result (code (str "(-> (my.program/ns-unmap! '" namespace-name "/refused-helper)"
                                      " :seon.error/offending :seon.program/callers)"))]
                (is (some? (:seon.db/db result)))
                (is (= [#{(symbol namespace-name "refused-caller")}]
                       (mapv read-string (shown connection result)))
                    "the refusal names the surviving referrers; nothing was deleted"))
              (is (= ["3"] (shown connection (code "(+ 1 2)"))) "the refusal parks nothing: the next run settles")))
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
            (let [realized (parse-long (first (shown connection (code "@realized"))))
                  ;; the print steps at most half the budget's characters; a
                  ;; chunked seq realizes the rest of its last 32-element chunk
                  budget-chars (tokens/estimate-chars
                                (:seon.render.profile/token-budget
                                 (render/agent-render-profile (config/effective (db/db connection) cluster-name))))
                  stepped (+ 32 (inc (quot budget-chars 2)))]
              (is (<= realized stepped) "no elements are realized after the print")
              ;; The infinite case only once the finite one proves no later walk.
              (when (<= realized stepped)
                (let [old-gen #(transduce (comp (filter (fn [pool] (str/includes? (.getName ^java.lang.management.MemoryPoolMXBean pool) "Old Gen")))
                                                (map (fn [pool] (.getUsed (.getUsage ^java.lang.management.MemoryPoolMXBean pool)))))
                                          + (java.lang.management.ManagementFactory/getMemoryPoolMXBeans))
                      before (old-gen)
                      shown-values (shown connection (code "(range)\n(map inc (range))"))]
                  (is (= 2 (count shown-values)))
                  (is (every? #(<= (count %) budget-chars) shown-values) "each shows within the budget")
                  (is (< (- (old-gen) before) (* 256 1024 1024)) "old gen stays flat")))))
          (testing "an unbound Var's value is a value, as in Clojure: nothing walks the result to classify it"
            (let [result (code "(declare unbound-probe)\n{:u unbound-probe}")]
              (is (not-any? :seon.cluster.eval/error
                            (drive/run-receipts (:seon.db/db result) [(:seon.turn/id result)])))))
          (testing "a form's own durable write opens its run first, so its provenance names a written evaluation"
            ;; Under one commit per form the run's entities were written only at
            ;; settlement, and every write a form made refused "Nothing found
            ;; for entity id [:seon.cluster.eval/id ...]" (task c9e0cde3edb3).
            (let [;; a handle leading with a digit: the fixture's printed symbol still reads back
                  task-id (str "0" (subs (id/id) 1))
                  result (code (pr-str (list 'seon.db/transact!
                                             (list 'quote {:tx-data [(support/task-row task-id "written by a form")]}))))
                  receipts (drive/run-receipts (:seon.db/db result) [(:seon.turn/id result)])]
              (is (not-any? :seon.cluster.eval/error receipts))
              (is (= (receipt-id-of result)
                     (db/q '[:find ?receipt-id . :in $ ?task-id
                             :where [?task :seon.task/id ?task-id] [?task :seon.task/intent _ ?tx]
                             [?tx :seon.db/receipt ?receipt] [?receipt :seon.cluster.eval/id ?receipt-id]]
                           (:seon.db/db result) task-id))
                  "the write's transaction names the form's evaluation")))
          (testing "a fault recorded during a form names its run without refusing: the value is answered"
            ;; A renderer that throws is a later stage's failure: recorded as a
            ;; fault attributed to the run, beside the value, never a panic.
            (let [result (code "{:seon.render/ai (fn [_] (throw (ex-info \"render probe\" {}))) :probe 1}")]
              (is (:seon.turn/id result))
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
            (let [submitted (agent/submit-source! (assoc request :seon.agent/segmenter 'seon.turn/code-sources
                                                         :seon.cluster.reply/text "(loop [] (recur))"))
                  reply (:seon.agent/reply submitted)
                  requests (:seon.agent/requests (agent/started routing agent-id))]
              (is (:seon.turn/id submitted))
              (support/await-event! (async/go-loop []
                                      (if (zero? (count (.buf ^clojure.core.async.impl.channels.ManyToManyChannel requests)))
                                        true
                                        (do (async/<! (async/timeout 5)) (recur))))
                                    ::request-taken)
              (agent/stop-flow! request)
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
                    "and no evaluation without a terminal fact")))))))

(deftest ^{:seon/tasks #{'seon.turn.batch/a-jvm-batch-answers-the-forms-around-a-failed-read}}
  a-failed-read-is-its-forms-error-and-reading-continues-in-sci-and-jvm
  ;; A failed read is its form's read error; the forms around it run, and no
  ;; error rides another form.
  (with-started-agent
   (fn [{:keys [connection cluster-name agent-id code]}]
     (let [result (code "(+ 1 2)\n(* 2 3)\n[1 bad: (+ 2 3)]\n(+ 4 4)")
           receipts (drive/run-receipts (:seon.db/db result) [(:seon.turn/id result)])
           error (fn [r] (when (:seon.cluster.eval/error r) (edn/read-string (:seon.eval/shown r))))]
       (is (= ["3" "6" nil "8"] (mapv #(when-not (:seon.cluster.eval/error %) (:seon.eval/shown %)) receipts)))
       (is (= [nil nil :seon.sci.reader/form nil] (mapv (comp :seon.error/member error) receipts)))
       (is (empty? (db/q '[:find ?receipt :in $ ?id
                           :where [?run :seon.turn/id ?id] [?receipt :seon.cluster.eval/run ?run]
                           [?receipt :seon.cluster.eval/diagnostics _]]
                         (:seon.db/db result) (:seon.turn/id result)))
           "no form carries an error recorded while another was shown"))
     (let [text "(+ 1 1)\n(vector ::nope.alias/k)\n(+ 2 2)"
           sci (let [result (code text)] (drive/run-receipts (:seon.db/db result) [(:seon.turn/id result)]))
           jvm (:seon.batch/entries
                (batch/evaluate-batch
                 (dissoc (turn.batch/jvm-request {:seon.batch/world (world/world {:seon.boot/cluster-name cluster-name :seon.db/connection connection :seon.agent/id agent-id :seon.batch/sink (seon.batch/sink)})
                                                  :seon.batch/text text})
                         :seon.batch/settle :seon.batch/show)))]
       (is (= [2 nil 4] (mapv #(when-not (:seon.cluster.eval/error %) (edn/read-string (:seon.eval/shown %))) sci))
           "SCI: the forms around the failed read run")
       (is (= :seon.sci.reader/form (:seon.error/member (edn/read-string (:seon.eval/shown (second sci))))))
       (is (= [2 nil 4] (mapv :seon.batch/value jvm)) "JVM: the forms around the failed read run")
       (is (= :seon.sci.reader/form
              (:seon.error/member (:seon.batch/error-value (ex-data (:seon.batch/error (second jvm)))))))))))

(deftest ^{:seon/tasks #{'seon.call-preparation/a-positional-database-is-not-supplied}}
  a-run-whose-open-is-refused-still-answers-its-values
  ;; A form that closes its own agent's admission outside the run's custody
  ;; leaves the run unopenable and its settlement refused. The path is total:
  ;; the values return, unrecorded, the refusal beside them.
  (with-started-agent
   (fn [{:keys [agent-id code]}]
     (code (str "(defn close-my-admission! \"Closes my admission outside my run's custody.\""
                " {:malli/schema [:=> [:cat :seon.db/connection] :boolean]} [c]"
                " (some? (seon.db/with-branch {} #(seon.db/transact! c [[:db/add [:seon.agent/id \""
                agent-id "\"] :seon.agent/admission-closed-tx \"datomic.tx\"]]))))"))
     (let [result (code "(close-my-admission!)\n(+ 1 2)")]
       (is (= ["true" "3"] (mapv (comp :seon.eval/shown :seon.sci.eval/evaluation)
                                 (:seon.turn.loop/evaluated-sources result)))
           "every evaluated form's value is answered")
       (is (some? (:seon.error/type (:seon.turn.loop/refusal result)))
           "the refused settlement is answered beside the values")))))

(deftest ^{:seon/tasks #{'seon.error.refusal/an-interpreted-callback-is-blamed-on-the-compiled-function-calling-it}}
  an-agents-mistake-inside-a-compiled-callers-callback-records-no-fault
  ;; The agent's closure passes nil to an armed function; the compiled function
  ;; that called the closure did nothing wrong. The refusal is delivered on the
  ;; form and is never recorded as an open fault against first-party code.
  (with-started-agent
   (fn [{:keys [connection code]}]
     (let [result (code "(seon.db/with-branch {} #(seon.db/transact! nil []))")]
       (is (some? (:seon.cluster.eval/error (:seon.sci.eval/evaluation (first (:seon.turn.loop/evaluated-sources result)))))
           "the refusal is delivered on the form")
       (is (empty? (filterv #(= 'seon.db/with-branch (get-in % [:seon.error/fact :seon.error/producer]))
                            (seon.problems/open-faults (db/db connection))))
           "no open fault blames the compiled caller of the agent's closure")))))

(deftest a-compiled-functions-thrown-error-value-is-recorded-as-its-fault
  ;; The constructed error value names no producer (`seon.error.refusal/diagnostic`
  ;; attributes nothing); the kernel boundary (`seon.sci.kernel/failure-value`)
  ;; attributes the throw's first authored frame, so `seon.batch/produced-failures`
  ;; still blames the compiled function that threw, with no producer stubbed in.
  (with-started-agent
   (fn [{:keys [connection code]}]
     (let [blamed (fn [] (filterv #(= 'seon.fn/task-refused! (get-in % [:seon.error/fact :seon.error/producer]))
                                  (seon.problems/open-faults (db/db connection))))]
       (is (empty? (blamed)) "no fault blames the function before the form")
       (code "(seon.fn/task-refused! 'seon.fn/ingest :unknown-task 'probe.blame/holder 1 {})")
       (is (= 1 (count (blamed))) "the failure is recorded as the compiled function's open fault")))))

(deftest a-pass-captures-definitions-realized-after-the-form-body
  (with-started-agent
   (fn [{:keys [connection agent-id code]}]
     (let [namespace-name (str "my.agents." agent-id)
           source-of (fn [sym]
                       (:seon.fn/source (db/pull (db/db connection) '[:seon.fn/source]
                                                 [:seon.fn/sym (symbol namespace-name (name sym))])))]
       (testing "a definition made while the printer realizes the returned lazy value is a program entity"
         (code "(map (fn [_] (defn lazy-f [x] (inc x)) 0) [1])")
         (is (= "(defn lazy-f [x] (inc x))" (source-of 'lazy-f))))
       (testing "a print-time redefinition supersedes the body-time one"
         (code "(do (defn lazy-r [x] 1) (map (fn [_] (defn lazy-r [x] 2) 0) [1]))")
         (is (= "(defn lazy-r [x] 2)" (source-of 'lazy-r))))))))

(deftest a-started-agents-run-sees-a-program-change-committed-by-another-writer
  ;; Boot starts agents, then adoption commits the loaded files' entities: the
  ;; next run must evaluate against that program, so `(doc x)` and a call of
  ;; the new definition resolve in the agent's own context.
  (with-started-agent
   (fn [{:keys [connection cluster-name code]}]
     (code "(+ 1 2)")
     (support/define! (world/world {:seon.db/connection connection :seon.boot/cluster-name cluster-name
                                          :seon.sci.eval/ctx (sci.eval/fork-cluster-ctx (sci.eval/base-ctx (db/db connection)) (db/db connection) connection)
 :seon.batch/sink (seon.batch/sink) :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms seon.config/defaults) :seon.agent/id "later-writer"})
                      'my.agents.later-writer
                      "(defn later-inc \"Adds one.\" {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))")
     (let [result (code (str "(my.agents.later-writer/later-inc 1)\n"
                             "(:arglists (doc my.agents.later-writer/later-inc))\n"
                             "(:arglists (doc clojure.set/union))\n"
                             "(contains? (doc no-such-name) :seon.sci.eval/documentation-unavailable)"))
           receipts (drive/run-receipts (:seon.db/db result) [(:seon.turn/id result)])]
       (is (not-any? :seon.cluster.eval/error receipts) "every form resolves in the agent's run")
       (is (= [2 '([x]) '([] [s1] [s1 s2] [s1 s2 & sets]) true]
              (mapv #(edn/read-string (:seon.eval/shown %)) receipts)))))))

(deftest an-agents-second-run-and-a-settings-write-construct-no-base
  ;; One cluster base per loaded program: a new agent's first run forks the
  ;; cluster's, and neither its next run nor its settings write derives another.
  (with-started-agent
   (fn [{:keys [connection code]}]
     (let [loaded (db/commit-id (#'sci.eval/loaded-program @connection))
           cluster-bases #(into {} (filter (fn [[[_ commit :as key]]] (and (= 2 (count key)) (= loaded commit))))
                                (seon.cache/retained :seon.sci.eval/program-context))
           before (cluster-bases)
           runs [(code "(+ 1 2)")
                 (code "(+ 1 2)")
                 (code "(my.agent/settings! {:seon.config.run/max-episode-runs 3})")
                 (code "(+ 1 2)")]]
       (is (seq before) "the cluster's context already forked the loaded program's cluster base")
       (is (every? :seon.turn/id runs))
       (is (= before (cluster-bases))
           "a new agent's first run, its second run and a settings write derive no cluster base")))))

(ns seon.context-test
  "Contract proof for context derivation between runs."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is use-fixtures]]
            [seon.cluster.agent :as agent]
            [seon.cluster.host :as host]
            [seon.context :as context]
            [seon.db :as db]
            [seon.id]
            [seon.instrument :as instrument]
            [seon.mcp :as mcp]
            [seon.ai.tokens :as tokens]
            [seon.eval]
            [seon.repl :as repl]
            [seon.task :as task]
            [seon.turn :as turn]
            [seon.test-support :as test-support]))

(def ^:dynamic ^:private *connection* nil)

(defn- with-connection
  [body]
  (test-support/with-database
   (fn [connection]
     (binding [*connection* connection]
       (body)))))

(use-fixtures :each with-connection)

(deftest message-custody-accepts-an-absent-run-when-instrumented
  ;; Boot armed `message-custody`; the test re-arms nothing (a whole-JVM
  ;; `apply!` strips every production wrapper's supplied-default plan).
  (is (contains? (instrument/instrumented) #'context/message-custody))
  (is (= :seon.context/history
         (context/message-custody (db/db *connection*) nil "agent" 1))))

(defn- shown
  "The value of `code` evaluated as `agent-id` through `seon.mcp/call`, read back as data."
  {:malli/schema [:=> [:cat :string :seon.agent/id :string] :seon.schema/value]}
  [running agent-id code]
  (let [answer (json/read-str (mcp/call (json/write-str {"name" "eval_clj" "arguments" {"code" code "agent" agent-id "cluster" running}})))]
    (:seon.repl/value (test-support/mcp-reply answer))))

(defn- block
  "The current-state block of `agent-id` as `seon.mcp/call` answers it, read back as data."
  {:malli/schema [:=> [:cat :string :seon.agent/id] :seon.schema/value]}
  [running agent-id]
  (let [answer (json/read-str (mcp/call (json/write-str {"name" "eval_clj"
                                                         "arguments" {"code" "(seon.context/current-state)"
                                                                      "agent" agent-id "cluster" running}})))]
    (:seon.repl/value (test-support/mcp-reply answer))))

(deftest current-state-shows-the-current-task-declaration
  (test-support/with-running-agent
   (fn [running agent-id connection]
     (let [declaration {:seon.db/connection connection :seon.agent/id agent-id :seon.task/ns 'tq.cs
                        :seon.task/name "shape"}
           id (:seon.task/id (task/create! (assoc declaration :seon.task/plan "First.")))
           task-section #(:seon.context/task (block running agent-id))]
       (is (nil? (:seon.context/task (block running agent-id))) "an agent holding no task has no task section")
       (test-support/declare-proof! connection 'tq.cs-test 'shape 'tq.cs/shape)
       (task/assign! {:seon.agent/context-source (test-support/running-cluster running) :seon.db/connection connection :seon.task/assigned id
                      :seon.task/agent [:seon.agent/id agent-id] :seon.task/branch (get-in (db/db connection) [:config :branch])})
       (is (= {:seon.task/sym 'tq.cs/shape :seon.task/plan "First."} (task-section)))
       (is (not (contains? (block running agent-id) :seon.context/chain)) "a task with no subject, needs or successor has no chain")
       (is (= :working (:seon.task/status (:seon.context/turns (block running agent-id)))))
       (is (int? (:my.agent/turns-left (:seon.context/turns (block running agent-id)))))
       (task/create! (assoc declaration :seon.task/plan "Second."
                            :seon.task/basis {id (task/declaration (db/carried-projection @connection)
                                                                  (db/pull @connection '[*] [:seon.task/id id]))}))
       (is (= "Second." (:seon.task/plan (task-section))) "the revision shows without a reassignment")))))

(deftest current-state-shows-only-its-sections
  (test-support/with-running-agent
   (fn [running agent-id _connection]
     (is (= #{:seon.context/turns} (set (keys (block running agent-id))))
         "the block holds its sections, none of the evaluation's world"))))

(defn- evaluate
  "Evaluates `code` as the agent through `seon.mcp/call`."
  {:malli/schema [:=> [:cat :string :seon.agent/id :string] :string]}
  [running agent-id code]
  (mcp/call (json/write-str {"name" "eval_clj" "arguments" {"code" code "agent" agent-id "cluster" running}})))

(defn- proof-task
  "Creates and assigns the task `tq.cs/shape` for `agent-id`."
  {:malli/schema [:function [:=> [:cat :string :seon.db/connection :seon.agent/id] :nil]
                  [:=> [:cat :string :seon.db/connection :seon.agent/id :string] :nil]]}
  ([running connection agent-id] (proof-task running connection agent-id "First."))
  ([running connection agent-id intent]
  (let [_ (test-support/declare-proof! connection 'tq.cs-test 'shape 'tq.cs/shape)
        id (:seon.task/id (task/create! {:seon.db/connection connection :seon.agent/id agent-id :seon.task/ns 'tq.cs
                                         :seon.task/name "shape" :seon.task/plan intent}))]
    (task/assign! {:seon.agent/context-source (test-support/running-cluster running) :seon.db/connection connection :seon.task/assigned id
                   :seon.task/agent [:seon.agent/id agent-id] :seon.task/branch (get-in (db/db connection) [:config :branch])})
    nil)))

(deftest an-implement-step-names-its-owner-and-shows-the-whole-intent
  (test-support/with-running-agent
   (fn [running agent-id connection]
     ;; About 2,200 estimated tokens: past the old 2,000-token display budget, inside the agent profile's.
     (let [intent (apply str "Whole: " (repeat 1400 "word-"))]
       (proof-task running connection agent-id intent)
       (let [state (block running agent-id)]
         (is (= intent (:seon.task/plan (:seon.context/task state))) "the intent is shown whole")
         (is (= {:seon.render/operations ['(in-ns 'tq.cs)]} (:seon.context/start (:seon.context/step state)))
             "the step's start is the form that enters the owner namespace, a form to run, not data posing as a value"))))))

(def ^:private proof-test
  "(ns tq.cs-test (:require [clojure.test :refer [deftest is]] [tq.cs])) (deftest ^{:seon/tasks #{'tq.cs/shape}} shape (is (= 4 (tq.cs/f 2)) \"doubles\"))")

(def ^:private two-failure-proof-test
  "(ns tq.cs-test (:require [clojure.test :refer [deftest is]] [tq.cs])) (deftest ^{:seon/tasks #{'tq.cs/shape}} shape (is (= 4 (tq.cs/f 2)) \"doubles\")\n (is (= 9 (tq.cs/f 3)) \"triples\"))")

(deftest a-red-proof-shows-every-failing-assertion-with-its-claim-as-text
  (test-support/with-running-agent
   (fn [running agent-id connection]
     (proof-task running connection agent-id)
     (evaluate running agent-id "(ns tq.cs) (defn f [x] (inc x))")
     (evaluate running agent-id two-failure-proof-test)
     (evaluate running agent-id "(my.test/check {})")
     (let [proofs (:seon.context/proofs (block running agent-id))
           failures (:seon.context/proof-failures (first (:seon.context/proof-list proofs)))]
       (is (= {:red 1} (:seon.context/counts proofs)))
       (is (= ["doubles" "triples"] (sort (mapv :message failures))) "each failing assertion is shown")
       (is (every? #(= '= (first (:expected %))) failures) "the claim is the runner's stored printed form, shown as the form it printed")
       (is (every? :actual failures))))))

(deftest a-steps-refusals-are-data-without-a-read-stamp
  ;; outside-2 (`docs/research/agent-platform/2026-10-14/outside-2.md` Misleading 2): the step showed its merge
  ;; preview's refusals as error maps stamped with the read time and their tests' `:unknown` verdicts; the section
  ;; shows each refusal's members, and the test it names once, under proofs, with its state
  (test-support/with-running-agent
   (fn [running agent-id connection]
     (proof-task running connection agent-id)
     (evaluate running agent-id proof-test)
     (let [state (block running agent-id)
           step (:seon.context/step state)
           refusals (:seon.context/refusals step)]
       (is (seq refusals) "the working step names its refusals")
       (is (not-any? #(some (partial contains? %) [:seon.error/at :seon.error/layer :seon.source/red-tests]) refusals)
           "no refusal carries a read stamp or a verdict per test")
       (is (= ['tq.cs-test/shape] (mapv :seon.test/sym (get-in state [:seon.context/proofs :seon.context/proof-list])))
           "the test a refusal names shows once, under proofs")
       (is (= step (:seon.context/step (block running agent-id))) "two reads show the same step")))))

(def ^:private backslash-proof-test
  "(ns tq.cs-test (:require [clojure.test :refer [deftest is]] [tq.cs])) (deftest ^{:seon/tasks #{'tq.cs/shape}} shape (is (= 4 (tq.cs/f 2))))")

(deftest a-failure-claim-shows-its-saved-printed-value-once
  (test-support/with-running-agent
   (fn [running agent-id connection]
     (proof-task running connection agent-id)
     (evaluate running agent-id "(ns tq.cs) (defn f [x] (str \\a (char 92) \\b))")
     (evaluate running agent-id backslash-proof-test)
     (evaluate running agent-id "(my.test/check {})")
     (let [failure (first (:seon.context/proof-failures (first (:seon.context/proof-list (:seon.context/proofs (block running agent-id))))))]
       (is (= '(not (= 4 "a\\b")) (:actual failure)) "the string a\\b shows as the value printer prints it once, not escaped again inside a string")))))

(deftest the-block-resolves-the-names-a-task-references
  (test-support/with-running-agent
   (fn [running agent-id connection]
     (let [_ (test-support/declare-proof! connection 'tq.cs-test 'names 'tq.cs/names)
           id (:seon.task/id (task/create! {:seon.db/connection connection :seon.agent/id agent-id :seon.task/ns 'tq.cs
                                            :seon.task/name "names"
                                            :seon.task/plan "Use `seon.task/agent-task`, `seon.task/agent-attempt` and `:seon.task/plan`."}))]
       (task/assign! {:seon.agent/context-source (test-support/running-cluster running) :seon.db/connection connection :seon.task/assigned id
                      :seon.task/agent [:seon.agent/id agent-id] :seon.task/branch (get-in (db/db connection) [:config :branch])})
       (let [[names operations] (shown running agent-id
                                       "(let [reads (:seon.context/must-reads (seon.context/current-state))] [(mapv :seon.ref/name reads) (= :doc (:seon.ref/level (second reads)))])")]
         (is (= ['seon.task/agent-task 'seon.task/agent-attempt :seon.task/plan] names))
         (is (true? operations) "a function cited with one backtick shows its doc"))))))

(deftest every-reference-a-task-makes-is-in-the-block
  (test-support/with-running-agent
   (fn [running agent-id connection]
     (let [_ (test-support/declare-proof! connection 'tq.cs-test 'attributes 'tq.cs/attributes)
           base {:seon.db/connection connection :seon.agent/id agent-id :seon.task/ns 'tq.cs}
           id (:seon.task/id (task/create! (assoc base :seon.task/name "attributes"
                                                  :seon.task/plan "Use `seon.task/agent-task`; see `docs/seon/schedule/cards/TASK-CHAIN.md#7-slices`.")))]
       (task/assign! {:seon.agent/context-source (test-support/running-cluster running) :seon.db/connection connection :seon.task/assigned id
                      :seon.task/agent [:seon.agent/id agent-id] :seon.task/branch (get-in (db/db connection) [:config :branch])})
       (let [reads (shown running agent-id "(mapv #(select-keys % [:seon.ref/name :seon.ref/path :seon.task/plan]) (:seon.context/must-reads (seon.context/current-state)))")]
         (is (some #{{:seon.ref/name 'seon.task/agent-task}} reads) "the intent's names")
         (is (some #{{:seon.ref/path "docs/seon/schedule/cards/TASK-CHAIN.md"}} reads) "the paths the intent cites"))))))

(defn- linked-chain!
  "Writes `tasks` on `connection` as one chain linked by `:seon.task/next`, through the task writer as
  `seon.task/chain!` writes them on its branch; answers the handles in order."
  {:malli/schema [:=> [:cat :seon.db/connection [:vector {:min 1} :map]] [:vector :seon.task/handle]]}
  [connection tasks]
  (let [entities (#'task/chain-entities tasks)
        handles (mapv #(task/task-handle (:seon.task/ns %) (:seon.task/name %)) entities)]
    ;; the task writer names root as the chain's author, as `seon.task/chain!` does on main's cluster
    (when-not (db/pull (db/db connection) [:db/id] [:seon.agent/id "root"])
      (db/transact! connection [[:db.fn/call #'host/home-call "root" (get-in (db/db connection) [:config :branch]) 'user
                                 (:seon.cluster/name (test-support/execution-handle nil))]]))
    (test-support/transacted! connection [[:db.fn/call #'task/adopt-tasks-tx entities (set handles) {}]])
    handles))

(deftest a-steps-block-names-its-chain
  ;; the first task of a chain names the task after it through `:seon.task/next` and none before it; a middle
  ;; task's block is `a-chains-agents-block-shows-its-chain-and-its-own-done-refusals`
  (test-support/with-running-agent
   (fn [running agent-id connection]
     (let [[first-task] (linked-chain! connection [{:seon.task/ns 'tq.ch :seon.task/name "prove" :seon.task/plan "Scanning is total."
                                                    :seon.task/instructions "Add a row for the empty input."
                                                    :seon.task/proofs #{'tq.ch-test/handoff}}
                                                   {:seon.task/ns 'tq.ch :seon.task/name "ignored" :seon.task/plan "Scanning is total."
                                                    :seon.task/instructions "Make the rows green."}])
           _ (task/assign! {:seon.agent/context-source (test-support/running-cluster running) :seon.db/connection connection
                            :seon.task/assigned first-task :seon.task/agent [:seon.agent/id agent-id]
                            :seon.task/branch (get-in (db/db connection) [:config :branch])})
           state (block running agent-id)
           chain (:seon.context/chain state)]
       (is (= [{:seon.task/sym 'tq.ch/prove-2 :seon.task/instructions "Make the rows green."}] (:seon.context/after chain))
           "the first task names the task after it through next, and what that task will do")
       (is (not (contains? chain :seon.context/before)) "no task comes before it")
       (is (= {:seon.task/sym 'tq.ch/prove :seon.task/plan "Scanning is total." :seon.task/instructions "Add a row for the empty input."}
              (:seon.context/task state))
           "the task is its plan with its own instructions")))))

(deftest a-chains-agents-block-shows-its-chain-and-its-own-done-refusals
  ;; TASK-CHAIN S3a-2b (§2.3 Work, §2.4 Done): the block of a chain's middle task names the tasks before and after
  ;; it through `:seon.task/next`; its step shows the task's own done refusals (`seon.cluster.source/done-refusals`),
  ;; never a merge's; each red test is listed once, with its failing claim as data; its proof states are the latest
  ;; test step's
  (test-support/with-running-agent
   (fn [running agent-id connection]
     (let [[_ middle] (linked-chain! connection [{:seon.task/ns 'tq.cn :seon.task/name "design" :seon.task/plan "Design it."
                                                  :seon.task/instructions "Design the rows."}
                                                 {:seon.task/ns 'tq.cn :seon.task/name "ignored" :seon.task/plan "Prove it."
                                                  :seon.task/instructions "Prove the rows." :seon.task/proofs #{'tq.cn-test/rows}}
                                                 {:seon.task/ns 'tq.cn :seon.task/name "ignored" :seon.task/plan "Implement it."
                                                  :seon.task/instructions "Make the rows green."
                                                  :seon.task/proofs #{'tq.cn-test/rows 'tq.cn-test/edge}}])
           _ (task/assign! {:seon.agent/context-source (test-support/running-cluster running) :seon.db/connection connection
                            :seon.task/assigned middle :seon.task/agent [:seon.agent/id agent-id]
                            :seon.task/branch (get-in (db/db connection) [:config :branch])})
           merge-refusal? #(or (= :seon.source/merge (:seon.error/layer %))
                               (not= 'seon.cluster.source/done-refusals (:seon.error/operation %)))
           before-work (block running agent-id)
           chain (:seon.context/chain before-work)]
       (is (= ['tq.cn/design] (mapv :seon.task/sym (:seon.context/before chain))) "the task before it, through next")
       (is (= [{:seon.task/sym 'tq.cn/design-3 :seon.task/instructions "Make the rows green."}] (:seon.context/after chain))
           "the task after it, through next, with what it will do")
       (is (not-any? merge-refusal? (get-in before-work [:seon.context/step :seon.context/refusals]))
           "before any work the step shows no merge verdict or merge refusal")
       (let [reply (json/read-str (evaluate running agent-id (str "(ns tq.cn-test (:require [clojure.test :refer [deftest is]]))\n"
                                                                  "(deftest rows (is (= 1 2) \"one row\"))")))
             step-red (into #{} (mapcat :seon.turn.loop/red) (vals (test-support/mcp-tests reply)))
             state (block running agent-id)
             proof-list (get-in state [:seon.context/proofs :seon.context/proof-list])
             step (:seon.context/step state)
             listed (concat proof-list (:seon.context/red-tests step) (:seon.context/product-tests step))]
         (is (= #{'tq.cn-test/rows} step-red) "the test step ran the proof red")
         (is (= {'tq.cn-test/rows :red} (into {} (map (juxt :seon.test/sym :seon.context/proof-status)) proof-list))
             "the block's proof states are the latest test step's")
         (is (= 1 (count (filter #(= 'tq.cn-test/rows (:seon.test/sym %)) listed))) "the red proof is listed once")
         (is (= [{:message "one row" :expected '(= 1 2) :actual '(not (= 1 2))}]
                (mapv #(select-keys % [:message :expected :actual]) (:seon.context/proof-failures (first proof-list))))
             "with its last failing claim, as data")
         (is (= [:seon.source/red-test-error] (mapv :seon.error/type (:seon.context/refusals step))) "its own done refusal")
         (is (not-any? merge-refusal? (:seon.context/refusals step)) "and no merge's"))))))

(deftest a-chain-of-one-is-judged-by-the-done-rule
  ;; outside-2 (`docs/research/agent-platform/2026-10-14/outside-2.md` Broken 1): a chain's only task, linked by no
  ;; `:seon.task/next` either way, was judged by its merge's preview, so a reaching test with no recorded result held
  ;; its done; TASK-CHAIN §2.4: every chain task is judged by `seon.cluster.source/done-refusals`. The chain fact is
  ;; added by `seon.db/with`, so no root pass starts the chain's own attempt beside this agent
  (test-support/with-running-agent
   (fn [running agent-id connection]
     (let [[only] (linked-chain! connection [{:seon.task/ns 'tq.c1 :seon.task/name "only" :seon.task/plan "Implement it."
                                              :seon.task/instructions "Make the rows green."
                                              :seon.task/proofs #{'tq.c1-test/rows 'tq.c1-test/edge}}])
           branch (get-in (db/db connection) [:config :branch])
           _ (task/assign! {:seon.agent/context-source (test-support/running-cluster running) :seon.db/connection connection
                            :seon.task/assigned only :seon.task/agent [:seon.agent/id agent-id] :seon.task/branch branch})
           _ (evaluate running agent-id (str "(ns tq.c1-test (:require [clojure.test :refer [deftest is]]))\n"
                                             "(deftest rows (is (= 1 1) \"one row\"))\n"
                                             "(deftest edge (is (= 1 2) \"the edge\"))"))
           step (context/step-verdict (db/with (db/db connection) [{:seon.chain/first only :seon.chain/branch branch}])
                                      agent-id)]
       (is (= [['seon.cluster.source/done-refusals :seon.source/red-test-error ['tq.c1-test/edge]]]
              (mapv (juxt :seon.error/operation :seon.error/type #(mapv :seon.test/sym (:seon.source/red-tests %)))
                    (:seon.source/refusals step)))
           "its red proof is the done rule's refusal, never a merge's")))))

(deftest a-task-naming-a-missing-symbol-shows-a-problem
  (test-support/with-running-agent
   (fn [running agent-id connection]
     (let [_ (test-support/declare-proof! connection 'tq.cs-test 'missing 'tq.cs/missing)
           id (:seon.task/id (task/create! {:seon.db/connection connection :seon.agent/id agent-id :seon.task/ns 'tq.cs
                                            :seon.task/name "missing" :seon.task/plan "Fix `tq.cs/absent`."}))]
       (task/assign! {:seon.agent/context-source (test-support/running-cluster running) :seon.db/connection connection :seon.task/assigned id
                      :seon.task/agent [:seon.agent/id agent-id] :seon.task/branch (get-in (db/db connection) [:config :branch])})
       ;; the task itself is tq.cs/missing, a name that resolves to it; the intent names one that resolves to nothing
       (is (= [{:seon.ref/name 'tq.cs/absent :seon.ref/problem-kind :unresolved}]
              (mapv #(dissoc % :seon.ref/message) (shown running agent-id "(:seon.context/must-reads (seon.context/current-state))"))))
       (is (string? (:seon.ref/message (first (shown running agent-id "(:seon.context/must-reads (seon.context/current-state))"))))
           "the problem says what failed and what to do")))))

(deftest an-updated-plan-is-the-next-blocks-task-with-its-references-resolved
  (test-support/with-running-agent
   (fn [running agent-id connection]
     (proof-task running connection agent-id)
     (let [refined (shown running agent-id "(my.task/update! 'tq.cs/shape :seon.task/plan (constantly \"Use `seon.task/agent-attempt`.\"))")
           state (block running agent-id)]
       (is (true? (:seon.task/changed? refined)) "the agent's own branch holds the new intent")
       (is (= "Use `seon.task/agent-attempt`." (get-in state [:seon.context/task :seon.task/plan])))
       (is (some #{'seon.task/agent-attempt} (map :seon.ref/name (:seon.context/must-reads state)))
           "the next block resolves the reference the refinement added")
       (is (= '#{seon.task/agent-attempt}
              (shown running agent-id "(:seon.task/references (seon.db/pull (seon.db/db) [:seon.task/references] [:seon.task/sym 'tq.cs/shape]))"))
           "the writer re-derives the task's references from the refined intent")))))

(deftest an-update-naming-nothing-answers-its-problem
  (test-support/with-running-agent
   (fn [running agent-id connection]
     (proof-task running connection agent-id)
     (is (= [{:seon.ref/name 'tq.cs/absent :seon.ref/problem-kind :unresolved}]
            (mapv #(select-keys % [:seon.ref/name :seon.ref/problem-kind])
                  (:my.task/problems (shown running agent-id "(my.task/update! 'tq.cs/shape :seon.task/plan (constantly \"Fix `tq.cs/absent`.\"))"))))))))

(deftest a-proof-the-intent-names-is-shown-once-beside-its-status
  (test-support/with-running-agent
   (fn [running agent-id connection]
     (proof-task running connection agent-id "Make `tq.cs-test/shape` pass.")
     (let [state (block running agent-id)
           [proof] (get-in state [:seon.context/proofs :seon.context/proof-list])]
       (is (string? (:seon.test/source proof)) "the proof's resolution, its source, sits with its status")
       (is (not-any? #{'tq.cs-test/shape} (map :seon.ref/name (:seon.context/must-reads state)))
           "and nowhere else")))))

(deftest a-library-symbol-resolves-with-its-pinned-revision
  (test-support/with-running-agent
   (fn [running agent-id connection]
     (let [_ (test-support/declare-proof! connection 'tq.cs-test 'library 'tq.cs/library)
           id (:seon.task/id (task/create! {:seon.db/connection connection :seon.agent/id agent-id :seon.task/ns 'tq.cs
                                            :seon.task/name "library" :seon.task/plan "Validate with `malli.core/validator`."}))]
       (task/assign! {:seon.agent/context-source (test-support/running-cluster running) :seon.db/connection connection :seon.task/assigned id
                      :seon.task/agent [:seon.agent/id agent-id] :seon.task/branch (get-in (db/db connection) [:config :branch])})
       (let [[entry] (shown running agent-id "(:seon.context/must-reads (seon.context/current-state))")
             pin (:seon.ref/pin entry)
             path (mapv str (iterator-seq (.iterator (java.nio.file.Paths/get (.toURI (io/resource "malli/core.cljc"))))))]
         (is (= "malli" (:seon.ref/project pin)))
         (is (= 40 (count (:seon.ref/revision pin))))
         (is (some #{(:seon.ref/revision pin)} path))
         (is (= :summary (:seon.ref/level entry)) "a library Var is read at its summary from the start")
         (is (= ['(doc malli.core/validator)] (:seon.render/operations entry)) "with the form that reads its doc"))))))

(deftest each-must-read-shows-its-declared-render
  (test-support/with-running-agent
   (fn [running agent-id connection]
     (let [_ (test-support/declare-proof! connection 'tq.cs-test 'kinds 'tq.cs/kinds)
           base {:seon.db/connection connection :seon.agent/id agent-id :seon.task/ns 'tq.cs}
           _ (task/create! (assoc base :seon.task/name "cited" :seon.task/plan "A cited task."))
           id (:seon.task/id (task/create! (assoc base :seon.task/name "kinds"
                                                  :seon.task/plan (str "Change `seon.context/step-start`, proven like "
                                                                         "`seon.context-test/the-block-resolves-the-names-a-task-references`; "
                                                                         "the key `:seon.task/plan`; after `tq.cs/cited`; read "
                                                                         "`docs/seon/schedule/cards/TASK-CHAIN.md#7-slices`; validate with `malli.core/validator`."))))]
       (task/assign! {:seon.agent/context-source (test-support/running-cluster running) :seon.db/connection connection :seon.task/assigned id
                      :seon.task/agent [:seon.agent/id agent-id] :seon.task/branch (get-in (db/db connection) [:config :branch])})
       (let [state (block running agent-id)
             reads (:seon.context/must-reads state)
             entry (fn [n] (some #(when (= n (:seon.ref/name %)) %) reads))
             function (entry 'seon.context/step-start)
             library (entry 'malli.core/validator)]
         (is (= 6 (count reads)) "one must-read per cited name and path")
         (is (not-any? #(and (map? %) (or (contains? % :seon.render/ai) (contains? % :seon.render/html))) (tree-seq coll? seq state))
             "no must-read or proof shows the render keys it is rendered by")
         (is (= :doc (:seon.ref/level function)) "a function cited with one backtick shows its doc")
         (is (seq (:arglists function)) "its doc carries its arglists")
         (is (= :summary (:seon.ref/level library)) "a library Var is read at its summary"))))))

(deftest a-referenced-function-shows-its-doc-view
  ;; TASK-CHAIN §3a: bare is the summary, one backtick `(doc x)`, three or more `(source x)`
  (test-support/with-running-agent
   (fn [running agent-id connection]
     (let [_ (test-support/declare-proof! connection 'tq.cs-test 'levels 'tq.cs/levels)
           id (:seon.task/id (task/create! {:seon.db/connection connection :seon.agent/id agent-id :seon.task/ns 'tq.cs
                                            :seon.task/name "levels"
                                            :seon.task/plan (str "Start at seon.ref/section. Call `seon.ref/locate`. Read ```seon.ref/docstring``` whole. "
                                                                 "Mind `seon.ref/brief`, then ```seon.ref/brief```.\n\n"
                                                                 "```clojure\n(seon.ref/names \"x\")\n```\n")}))]
       (task/assign! {:seon.agent/context-source (test-support/running-cluster running) :seon.db/connection connection :seon.task/assigned id
                      :seon.task/agent [:seon.agent/id agent-id] :seon.task/branch (get-in (db/db connection) [:config :branch])})
       (let [reads (:seon.context/must-reads (block running agent-id))
             entries (fn [n] (filterv #(= n (:seon.ref/name %)) reads))
             [summary] (entries 'seon.ref/section)
             [code] (entries 'seon.ref/docstring)
             database (db/db connection)]
         (is (= #{:seon.ref/name :seon.ref/level :doc :seon.render/operations} (set (keys summary)))
             "a bare name is its summary: name, first sentence and the form that reads it")
         (is (= :summary (:seon.ref/level summary)))
         (is (true? (shown running agent-id
                           (str "(= (doc seon.ref/locate) (dissoc (some #(when (= 'seon.ref/locate (:seon.ref/name %)) %) "
                                "(:seon.context/must-reads (seon.context/current-state))) :seon.ref/name :seon.ref/level))")))
             "a one-backtick name's entry is (doc x)'s value")
         (is (= (:seon.fn/source (db/pull database [:seon.fn/source] [:seon.fn/sym 'seon.ref/docstring])) (:source code))
             "a three-backtick name's entry holds its whole source")
         (is (= [:code] (mapv :seon.ref/level (entries 'seon.ref/brief))) "a name at two levels appears once, at the deeper")
         (is (empty? (entries 'seon.ref/names)) "a fence stays code"))))))

(deftest a-plain-eval-renders-each-must-read-from-its-world
  (test-support/with-running-agent
   (fn [running agent-id _connection]
     (let [view (shown running agent-id
                       (str "(seon.context/current-state-ai (assoc (seon.context/current-state) :seon.context/must-reads "
                            "[{:seon.ref/name 'x :seon.render/ai 'seon.render.value/render-ai :seon.ref/references []}]))"))
           [entry] (:seon.context/must-reads view)]
       (is (= 'x (:seon.ref/name entry)) "the must-read is rendered beside its name")
       (is (not-any? #(and (map? %) (contains? % :seon.error/type)) (tree-seq coll? seq view))
           "the render's admission caps and time limit come from the evaluation's world")))))

(deftest ^{:seon/tasks #{'seon.context/a-message-to-the-agent-is-not-in-the-model-context}}
  unread-shows-each-undelivered-item-once-with-its-identity
  (test-support/with-running-agent
   (fn [running agent-id connection]
     (is (nil? (:seon.context/unread (block running agent-id))) "no unread message, no section")
     (shown running agent-id
            (str "(mapv (fn [content] (my.message/send {:my.message/to \"" agent-id "\" :my.message/content content})) [\"a\" \"b\"])"))
     ;; a message wakes its recipient, whose run then refuses a concurrent eval request: read the block's owner
     (let [unread (:seon.context/unread (context/current-state (db/db connection) agent-id))
           items (:seon.context/items unread)]
       (is (= ["a" "b"] (mapv :my.message/content items)))
       (is (= 2 (count (set (map :my.message/id items)))) "each item once, with its own identity")
       (is (every? :my.message/at items))
       (is (some #{'(my.message/inbox)} (:seon.render/operations unread)))))))

(deftest a-message-a-closed-turn-handled-leaves-the-unread-section
  (test-support/seed-cluster! *connection* "shown-once")
  (test-support/transacted! *connection* (agent/creation-tx {:seon.agent/id "shown-once"
                                                             :seon.ns/name 'my.agents.shown-once
                                                             :seon.cluster/name "shown-once"}))
  (let [agent-ref [:seon.agent/id "shown-once"]
        opening {:seon.turn/id "shown-first" :seon.turn/agent agent-ref :seon.turn/opened-tx "datomic.tx"}
        arrival (fn [id] {:seon.message/id id :seon.message/to agent-ref :seon.message/content id})
        unread #(mapv :my.message/id (get-in (context/current-state (db/db *connection*) "shown-once")
                                             [:seon.context/unread :seon.context/items]))]
    (test-support/transacted! *connection* [(arrival "shown")])
    (test-support/transacted! *connection* (turn/open-tx opening))
    (test-support/transacted! *connection* [(arrival "after-open")])
    (is (= ["shown" "after-open"] (unread)) "an open turn has handled nothing yet")
    (test-support/transacted! *connection* [[:db/add [:seon.turn/id "shown-first"] :seon.turn/reply-size 1]])
    (test-support/transacted! *connection* (turn/close-tx {:seon.turn/id "shown-first"}))
    (is (= ["after-open"] (unread)) "the closed turn's handled message is not unread in the next block")))

(deftest a-request-that-cannot-run-the-help-render-answers-a-refusal
  (test-support/seed-cluster! *connection* "unrendered")
  (test-support/transacted! *connection* (agent/creation-tx {:seon.agent/id "unrendered"
                                                             :seon.ns/name 'my.agents.unrendered
                                                             :seon.cluster/name "unrendered"}))
  ;; a profile but no context, caps or time limit: the help render cannot run
  (let [answer (context/model-context (db/db *connection*) {:seon.boot/cluster-name "unrendered"} "unrendered")]
    (is (= :seon.render/request-error (:seon.error/type answer)))
    (is (not (contains? answer :seon.ai/system)) "never the printed help map as the system message")))

(deftest the-blocks-attempt-sections-lead-and-its-turn-sections-follow-the-history
  (test-support/with-running-agent
   (fn [running agent-id connection]
     (let [handle (test-support/running-cluster running)
           _ (test-support/declare-proof! connection 'tq.cs-test 'split 'tq.cs/split)
           id (:seon.task/id (task/create! {:seon.db/connection connection :seon.agent/id agent-id :seon.task/ns 'tq.cs
                                            :seon.task/name "split"
                                            :seon.task/plan "Use `seon.task/agent-task`."}))
           _ (task/assign! {:seon.agent/context-source handle :seon.db/connection connection :seon.task/assigned id
                            :seon.task/agent [:seon.agent/id agent-id] :seon.task/branch (get-in (db/db connection) [:config :branch])})
           database (db/db connection)
           world {:seon.db/db database :seon.agent/id agent-id
                  :seon.sci.eval/ctx (:seon.sci.eval/ctx handle) :seon.sci.admit/caps (:seon.sci.admit/caps handle)
                  :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms handle)}
           [head view] (context/block-views (merge (context/current-state database agent-id) world)
                                            (context/attempt-start database agent-id))]
       (is (= #{:seon.context/task :seon.context/must-reads} (set (keys head)))
           "the task and its must-reads are the attempt's sections, laid before the history")
       (is (contains? view :seon.context/turns) "the turn's sections follow the history")
       (is (not-any? (set (keys head)) (keys view)) "each section is laid out once")
       (is (every? string? ((juxt :seon.ai/system :seon.ai/prompt)
                            (context/model-context database (dissoc world :seon.db/db :seon.agent/id) agent-id)))
           "the model pass lays both parts out")))))

(deftest the-system-text-holds-for-the-attempt-when-a-must-read-changes
  (test-support/with-running-agent
   (fn [running agent-id connection]
     (let [handle (test-support/running-cluster running)
           _ (evaluate running agent-id "(ns tq.stable) (defn cited \"One.\" {:malli/schema [:=> [:cat :int] :int]} [n] n)")
           _ (test-support/declare-proof! connection 'tq.stable-test 'held 'tq.stable/held)
           id (:seon.task/id (task/create! {:seon.db/connection connection :seon.agent/id agent-id :seon.task/ns 'tq.stable
                                            :seon.task/name "held"
                                            :seon.task/plan "Use `tq.stable/cited` and `clojure.core/subs`."}))
           _ (task/assign! {:seon.agent/context-source handle :seon.db/connection connection :seon.task/assigned id
                            :seon.task/agent [:seon.agent/id agent-id] :seon.task/branch (get-in (db/db connection) [:config :branch])})
           world #(hash-map :seon.sci.eval/ctx (:seon.sci.eval/ctx handle) :seon.sci.admit/caps (:seon.sci.admit/caps handle)
                            :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms handle))
           system #(:seon.ai/system (context/model-context (db/db connection) (world) agent-id))
           before (system)
           _ (evaluate running agent-id "(in-ns 'tq.stable) (defn cited \"Two, a changed docstring.\" {:malli/schema [:=> [:cat :int] :int]} [n] (inc n))")
           reads (into {} (map (juxt :seon.ref/name identity))
                       (:seon.context/must-reads (context/current-state (db/db connection) agent-id)))]
       (is (string? before))
       (is (= before (system)) "the attempt's sections read the attempt's starting value, so the cached prefix holds")
       (is (= :summary (get-in reads ['clojure.core/subs :seon.ref/level])) "a library Var is read at its summary")
       (is (not= :summary (get-in reads ['tq.stable/cited :seon.ref/level])) "a program function keeps its citation's level")))))

(deftest an-older-history-unit-over-the-dial-is-shown-by-reference
  (test-support/with-running-agent
   (fn [running agent-id connection]
     (evaluate running agent-id "(vec (range 4000))")
     (let [database (db/db connection)
           [evaluation] (seon.eval/of-agent database agent-id '[* {:seon.cluster.eval/ns [:db/id :seon.ns/name]}])
           emission (repl/entity-emission (assoc evaluation :seon.db/db database))
           handle (:seon.repl/handle emission)
           older (context/exchange-form emission :older 400)
           recent (context/exchange-form emission :recent 400)]
       (is (qualified-symbol? handle) "the evaluation holds a result handle")
       (is (= {:seon.repl/handle handle :seon.render/operations [(list 'get-in handle [])]}
              (dissoc (:seon.context/reference older) :seon.context/reply-tokens))
           "the older unit names its handle and the form that reads the value whole")
       (is (= (tokens/estimate (:seon.ai/reply recent)) (get-in older [:seon.context/reference :seon.context/reply-tokens]))
           "the reference carries the whole reply's size in estimated tokens")
       (is (< (tokens/estimate (:seon.ai/reply older)) 100) "the older unit carries no reply text")
       (is (not (contains? recent :seon.context/reference)) "a recent unit is shown whole")
       (is (not (contains? (context/exchange-form emission :older 1000000) :seon.context/reference))
           "a reply under the dial is shown whole")))))

(deftest the-block-shows-the-agents-cost-against-main
  (test-support/with-running-agent
   (fn [running agent-id connection]
     ;; main: a branch off the commit the agent was acquired at, so it holds none of the agent's definitions, named as root's home
     (let [parent (test-support/execution-handle connection)
           home (agent/acquire-context! parent nil {:seon.agent/isolate? true :seon.cluster.registry/from (:seon.source/commit-id parent)})]
       (try
         (db/transact! connection [[:db.fn/call #'host/home-call "root" (:seon.agent/branch home) 'user (:seon.cluster/name parent)]])
         (shown running agent-id "(in-ns 'ctx.cost) (defn summed \"Sum.\" {:malli/schema [:=> [:cat :int] :int]} [n] (reduce + (map identity (vec (range n))))) (dotimes [_ 30] (summed 5000))")
         (let [entries (:seon.context/cost (block running agent-id))
               entry (first (filter #(= 'ctx.cost/summed (:seon.cost.comparison/subject %)) entries))]
           (is (some? entry) "the function the agent defined and ran is in its block")
           (is (= :self-us (:seon.cost.comparison/measure entry)))
           (is (contains? (:seon.cost.comparison/flags entry) :new) "main stores nothing for it")
           (is (<= (count entries) 10) "at most the top dial"))
         (finally (agent/release-context! home)))))))

(deftest ^{:seon/tasks #{'seon.context/the-block-names-no-namespace}}
  the-block-names-the-agents-current-namespace
  (test-support/with-running-agent
   (fn [running agent-id connection]
     (proof-task running connection agent-id)
     (is (= 'tq.cs (get-in (context/current-state (db/db connection) agent-id) [:seon.context/turns :seon.context/namespace]))
         "an agent that evaluated nothing is in its task's namespace")
     (evaluate running agent-id "(ns tq.cs.elsewhere)")
     (is (= 'tq.cs.elsewhere (get-in (block running agent-id) [:seon.context/turns :seon.context/namespace]))
         "the namespace its latest form ended in"))))

(defn- end-entry
  "The inbox entry of `agent-id`'s end message about the task `task-id`."
  {:malli/schema [:=> [:cat :seon.agent/id :string] :my.message/inbox-entry]}
  [agent-id task-id]
  {:my.message/id (seon.id/id [agent-id task-id]) :my.message/from agent-id :my.message/at (java.util.Date.)
   :my.message/content "My task ended: done." :seon.message/about task-id :seon.task/end :done})

(defn- gone-agent!
  "Create agent `agent-id` on a seeded cluster whose branch is not in the store, as a retired branch is."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.agent/id] :nil]}
  [connection agent-id]
  (test-support/seed-cluster! connection "ctx-ended" {})
  (test-support/transacted! connection (test-support/agent-tx (db/db connection) agent-id))
  (test-support/transacted! connection [{:seon.agent/id agent-id :seon.agent/branch :retired-branch}])
  nil)

(deftest ^{:seon/tasks #{'seon.task/an-ended-summary-survives-retiring-its-branch}}
  an-ended-summary-survives-retiring-its-branch
  (test-support/with-database
   (fn [connection]
     (gone-agent! connection "ended-gone")
     (test-support/transacted! connection [{:seon.agent/id "ended-gone" :seon.agent/end-calls 7 :seon.agent/end-prompt-tokens 900
                                            :seon.agent/end-completion-tokens 80 :seon.agent/end-green 1 :seon.agent/end-red 2
                                            :seon.agent/end-red-proofs #{'tq.a/one 'tq.a/two}}])
     (let [ended (context/ended (db/db connection) (end-entry "ended-gone" "0123456789ab"))]
       (is (= {:seon.task/calls 7 :seon.task/prompt-tokens 900 :seon.task/completion-tokens 80
               :seon.task/proof-counts {:green 1 :red 2} :seon.task/red-proofs ['tq.a/one 'tq.a/two]}
              (select-keys ended [:seon.task/calls :seon.task/prompt-tokens :seon.task/completion-tokens
                                  :seon.task/proof-counts :seon.task/red-proofs])))
       (is (not (contains? ended :seon.task/end-unknown)))))))

(deftest ^{:seon/tasks #{'seon.task/an-ended-summary-survives-retiring-its-branch}}
  an-ended-summary-with-no-evidence-is-unknown-never-zero
  (test-support/with-database
   (fn [connection]
     (gone-agent! connection "ended-unknown")
     (let [ended (context/ended (db/db connection) (end-entry "ended-unknown" "ba9876543210"))]
       (is (true? (:seon.task/end-unknown ended)))
       (is (empty? (select-keys ended [:seon.task/calls :seon.task/prompt-tokens :seon.task/completion-tokens :seon.task/proof-counts]))
           "no counts are manufactured for evidence that is gone")
       (is (= [] (context/end-summary-tx (db/db connection) "ended-unknown")) "and nothing is recorded for it")))))

(deftest ^{:seon/tasks #{'seon.task/an-ended-summary-survives-retiring-its-branch}
           :seon.test/long "The assignment's proof run, the fix with its test step and the end's proposal run."
           :seon.test/long-ms 30000}
  the-outcome-recorded-at-the-end-equals-the-one-the-branch-shows
  (test-support/with-running-agent
   (fn [running agent-id connection]
     (evaluate running agent-id (str "(ns tq.cso (:require [clojure.test :refer [deftest is]]))\n"
                                     "(defn area {:malli/schema [:=> [:cat :int] :int]} [r] r)\n"
                                     "(deftest ^{:seon/tasks #{'tq.cso/area}} area-test (is (= 25 (area 5))))"))
     (task/assign! {:seon.agent/context-source (test-support/running-cluster running) :seon.db/connection connection
                    :seon.task/assigned (:seon.task/id (task/create! {:seon.db/connection connection :seon.agent/id agent-id
                                                                      :seon.task/ns 'tq.cso :seon.task/name "area"
                                                                      :seon.task/plan "Square."}))
                    :seon.task/agent [:seon.agent/id agent-id] :seon.task/branch (get-in (db/db connection) [:config :branch])})
     (evaluate running agent-id "(in-ns 'tq.cso)\n(defn area {:malli/schema [:=> [:cat :int] :int]} [r] (* r r))")
     (let [handle (task/agent-task (db/db connection) agent-id)
           entry (end-entry agent-id handle)
           from-branch (context/ended (db/db connection) entry)
           outcome [:seon.task/calls :seon.task/prompt-tokens :seon.task/completion-tokens :seon.task/proof-counts :seon.task/red-proofs]]
       (is (= :done (turn/task-end (db/db connection) agent-id)) "the fix turned the proof green")
       ;; the end observed as the agent's loop observes it
       (db/with-branch {:seon.db/connection connection}
                             #(#'turn/end-task! (test-support/running-cluster running) agent-id))
       (is (some? (:seon.agent/end-calls (db/pull (db/db connection) [:seon.agent/end-calls] [:seon.agent/id agent-id])))
           "the end recorded the agent's outcome")
       (is (= (select-keys from-branch outcome) (select-keys (context/ended (db/db connection) entry) outcome))
           "the recorded outcome is what the branch showed")
       (is (= {:green 1} (:seon.task/proof-counts from-branch)) "the task's one proof is green")))))

(defn- result
  "A recorded result of test `s` with `pass`, `fail` and `error` counts, and `reports` when red by a report."
  ([s pass fail error] (result s pass fail error nil))
  ([s pass fail error reports]
   (cond-> {:seon.test/sym s :seon.test/pass-count pass :seon.test/fail-count fail :seon.test/error-count error
            :seon.test/run-basis-t 1 :seon.test/run-at #inst "2026-10-13T00:00:00Z" :seon.test/run [:seon.test.run/id "a1b2c3d4e5f6"]}
     reports (assoc :seon.test.failure/reports reports))))

(deftest a-step-is-done-only-when-its-merge-would-accept
  ;; the step's status is the one results judgment the merge makes over its run, here over the branch's own
  ;; recorded results: done exactly when it refuses nothing, and a working step names each refusal's type
  (let [database (db/db *connection*)
        green #(result % 1 0 0)
        red #(result % 0 1 0 [{:type :fail}])
        judged (fn [step proofs product members results on-target]
                 (let [{status :seon.task/status refusals :seon.source/refusals}
                       (context/step-status
                        {:seon.task/step step :seon.task/proofs proofs :seon.task/product product :seon.context/head {}
                         :seon.context/candidate database :seon.context/main database
                         :seon.source/judged (cond-> {:seon.source/members members :seon.source/results results
                                                      :seon.task/proofs (into proofs product) :seon.program/identities #{}
                                                      :seon.test.run/id "a1b2c3d4e5f6" :seon.test.run/branch :tq-branch}
                                               (seq product) (assoc :seon.task/product product)
                                               (seq on-target) (assoc :seon.source/red-on-target on-target))})]
                   [status (mapv :seon.error/type refusals)]))]
    (doseq [[step proofs product members results on-target expected why]
            [[:implement #{'tq.rule/p} #{} #{'tq.rule/p} [(green 'tq.rule/p)] nil [:done []] "its proof green"]
             [:implement #{'tq.rule/p} #{} #{'tq.rule/p 'tq.rule/r} [(green 'tq.rule/p) (red 'tq.rule/r)] #{'tq.rule/r}
              [:working [:seon.source/red-on-target-error]] "a reaching test main already holds red stops the merge for root"]
             [:implement #{'tq.rule/p} #{} #{'tq.rule/p 'tq.rule/r} [(green 'tq.rule/p) (red 'tq.rule/r)] nil
              [:working [:seon.source/red-test-error]] "a reaching test the change turned red"]
             [:implement #{'tq.rule/p} #{} #{'tq.rule/p} [] nil [:working [:seon.source/red-test-error]] "its proof unrun at its reach is unknown, never green"]
             [:implement #{'tq.rule/p} #{} #{'tq.rule/p} [(red 'tq.rule/p)] nil [:working [:seon.source/red-test-error]] "its proof red"]
             [:implement #{} #{} #{} [] nil [:working [:seon.source/test-evidence-error]] "no proof proves nothing"]
             [:prove #{} #{'tq.rule/s} #{'tq.rule/s} [(red 'tq.rule/s)] nil [:done []] "its product red by a failing assertion"]
             [:prove #{} #{'tq.rule/s} #{'tq.rule/s} [(result 'tq.rule/s 0 1 0)] nil [:working [:seon.source/red-test-error]] "a red with no failing report ran nothing"]
             [:prove #{} #{'tq.rule/s 'tq.rule/l} #{'tq.rule/s 'tq.rule/l} [(red 'tq.rule/s)] nil [:working [:seon.source/red-test-error]] "a declared product test nobody wrote"]
             [:prove #{'tq.rule/q} #{'tq.rule/s} #{'tq.rule/q 'tq.rule/s} [(red 'tq.rule/q) (red 'tq.rule/s)] nil [:working [:seon.source/red-test-error]] "its own proof red"]]]
      (is (= expected (judged step proofs product members results on-target)) why))))

(deftest a-chains-step-is-done-only-when-the-done-rule-refuses-nothing
  ;; a chain task's status is the done rule (`seon.cluster.source/done-refusals`) over the branch's own recorded
  ;; results: done exactly when it refuses nothing, and a working step names each refusal's type
  (let [database (db/db *connection*)
        green #(result % 1 0 0)
        red #(result % 0 1 0 [{:type :fail}])
        judged (fn [proofs next-proofs members results on-target defined]
                 (let [{status :seon.task/status refusals :seon.source/refusals}
                       (context/step-status
                        {:seon.task/proofs proofs :seon.task/product next-proofs :seon.context/head {} :seon.context/chained true
                         :seon.context/candidate database :seon.context/main database
                         :seon.source/judged (cond-> {:seon.source/members members :seon.source/results results
                                                      :seon.task/proofs proofs
                                                      :seon.program/identities (into #{} (map #(vector :seon.test/sym %)) defined)
                                                      :seon.test.run/id "a1b2c3d4e5f6" :seon.test.run/branch :tq-branch}
                                               (seq next-proofs) (assoc :seon.source/next-proofs next-proofs)
                                               (seq on-target) (assoc :seon.source/green-on-target on-target))})]
                   [status (mapv :seon.error/type refusals)]))]
    (doseq [[proofs next-proofs members results on-target defined expected why]
            [[#{'tq.rule/p} #{} #{'tq.rule/p} [(green 'tq.rule/p)] nil #{} [:done []] "its proof green"]
             [#{'tq.rule/p} #{} #{'tq.rule/p 'tq.rule/r} [(green 'tq.rule/p) (red 'tq.rule/r)] #{'tq.rule/r} #{}
              [:working [:seon.source/red-test-error]] "a reaching test main holds green is red here"]
             [#{'tq.rule/p} #{} #{'tq.rule/p 'tq.rule/r} [(green 'tq.rule/p) (red 'tq.rule/r)] nil #{}
              [:done []] "a reaching red main holds no green result for is unknown, never a regression"]
             [#{'tq.rule/p} #{} #{'tq.rule/p} [] nil #{} [:working [:seon.source/test-evidence-error]] "its proof unrun at its reach is unknown, never green"]
             [#{'tq.rule/p} #{} #{'tq.rule/p} [(red 'tq.rule/p)] nil #{} [:working [:seon.source/red-test-error]] "its proof red"]
             [#{} #{} #{} [] nil #{} [:working [:seon.source/test-evidence-error]] "no proof proves nothing"]
             [#{'tq.rule/q} #{'tq.rule/s} #{'tq.rule/q 'tq.rule/s} [(green 'tq.rule/q) (red 'tq.rule/s)] nil #{'tq.rule/s}
              [:done []] "the next task's proof it wrote red is its product"]
             [#{'tq.rule/q} #{} #{'tq.rule/q 'tq.rule/s} [(green 'tq.rule/q) (red 'tq.rule/s)] nil #{'tq.rule/s}
              [:working [:seon.source/stray-red-error]] "a red test it defined that no next task proves"]
             [#{'tq.rule/q} #{'tq.rule/s} #{'tq.rule/q 'tq.rule/s} [(red 'tq.rule/q) (red 'tq.rule/s)] nil #{'tq.rule/s}
              [:working [:seon.source/red-test-error]] "its own proof red"]]]
      (is (= expected (judged proofs next-proofs members results on-target defined)) why))))

(defn- stable
  "`step` with each refusal's occurrence stamps (when and in which evaluation) removed: the data two reads share."
  [step]
  (update step :seon.context/refusals (partial mapv #(dissoc % :seon.error/at :seon.error/world))))

(deftest ^{:seon/tasks #{'seon.context/reached-reds-are-invisible-to-the-agent}
           :seon.test/long "An own-branch agent's setup, one change through its eval request with its test step and one fork-point run, and two reads of its block."
           :seon.test/long-ms 30000}
  broken-tests-show-with-their-failures
  ;; the change turns the proof green and breaks `identity-test`: the block and my.task/current show it with its
  ;; failing assertion beside the green proof, and the status they show is the one task-end reads
  (test-support/with-running-agent
   (fn [running _ connection]
     (let [agent-id (str "shows-broken-" (seon.id/id))
           _ (db/transact! connection [[:db.fn/call #'host/home-call "root" (get-in (db/db connection) [:config :branch]) 'user
                                        (:seon.cluster/name (test-support/execution-handle nil))]])
           _ (test-support/declare-source! connection 'tq.shb
                                           (str "(ns tq.shb (:require [clojure.test :refer [deftest is]]))\n"
                                                "(defn area {:malli/schema [:=> [:cat :int] :int]} [r] r)\n"
                                                "(deftest ^{:seon/tasks #{'tq.shb/area}} area-test (is (= 25 (area 5))))\n"
                                                "(deftest identity-test (is (= 3 (area 3)) \"area keeps its side\"))\n")
                                           "test")
           {branch :seon.store/branch target :seon.db/connection} (test-support/own-branch-agent! running agent-id "(ns tq.shb)")
           id (:seon.task/id (task/create! {:seon.db/connection connection :seon.agent/id "root"
                                            :seon.task/ns 'tq.shb :seon.task/name "area" :seon.task/plan "Square."}))]
       (task/assign! {:seon.agent/context-source (test-support/running-cluster running) :seon.db/connection connection
                      :seon.task/assigned id :seon.task/agent [:seon.agent/id agent-id] :seon.task/branch branch})
       (evaluate running agent-id "(in-ns 'tq.shb)\n(defn area {:malli/schema [:=> [:cat :int] :int]} [r] (* r r))")
       (let [step (:seon.context/step (block running agent-id))
             current (:seon.context/step (shown running agent-id "(my.task/current {})"))
             [broken] (:seon.context/red-tests step)]
         (is (= {:green 1} (:seon.context/counts (:seon.context/proofs (block running agent-id)))) "the proof is green")
         (is (= 'tq.shb/identity-test (:seon.test/sym broken)) "the broken test is shown")
         (is (= ["area keeps its side"] (mapv :message (:seon.context/proof-failures broken))) "with its failing assertion")
         (is (= (stable step) (stable current)) "my.task/current shows the same step")
         (is (= :working (:seon.task/status step)
                (:seon.task/status (:seon.context/turns (block running agent-id))))
             "the step is not done, and the block's status says so")
         (is (false? (context/task-done? (db/db target) agent-id)) "as the end reads it"))))))

(deftest ^{:seon.test/long "An own-branch agent's setup, one change through its eval request with its test step and one fork-point run, and one read of its block."
           :seon.test/long-ms 30000}
  an-uncontracted-change-keeps-an-implement-step-working
  ;; the change turns the proof green through a private helper with no contract: the merge would refuse it, so
  ;; the step is not done and its section names the helper
  (test-support/with-running-agent
   (fn [running _ connection]
     (let [agent-id (str "uncontracted-" (seon.id/id))
           _ (db/transact! connection [[:db.fn/call #'host/home-call "root" (get-in (db/db connection) [:config :branch]) 'user
                                        (:seon.cluster/name (test-support/execution-handle nil))]])
           _ (test-support/declare-source! connection 'tq.unc
                                           (str "(ns tq.unc (:require [clojure.test :refer [deftest is]]))\n"
                                                "(defn area {:malli/schema [:=> [:cat :int] :int]} [r] r)\n"
                                                "(deftest ^{:seon/tasks #{'tq.unc/area}} area-test (is (= 25 (area 5))))\n")
                                           "test")
           {branch :seon.store/branch target :seon.db/connection} (test-support/own-branch-agent! running agent-id "(ns tq.unc)")
           id (:seon.task/id (task/create! {:seon.db/connection connection :seon.agent/id "root"
                                            :seon.task/ns 'tq.unc :seon.task/name "area" :seon.task/plan "Square."}))]
       (task/assign! {:seon.agent/context-source (test-support/running-cluster running) :seon.db/connection connection
                      :seon.task/assigned id :seon.task/agent [:seon.agent/id agent-id] :seon.task/branch branch})
       (evaluate running agent-id (str "(in-ns 'tq.unc)\n(defn- squared [r] (* r r))\n"
                                       "(defn area {:malli/schema [:=> [:cat :int] :int]} [r] (squared r))"))
       (let [step (:seon.context/step (block running agent-id))]
         (is (= {:green 1} (:seon.context/counts (:seon.context/proofs (block running agent-id)))) "the proof is green")
         (is (= [:working ['tq.unc/squared]] [(:seon.task/status step) (some :seon.source/uncontracted (:seon.context/refusals step))])
             "the uncontracted helper keeps the step working, named by its merge's refusal")
         (is (false? (context/task-done? (db/db target) agent-id)) "as the end reads it"))))))

(deftest ^{:seon.test/long "An own-branch agent's setup, one change through its eval request with its test step and one fork-point run, and one read of its block."
           :seon.test/long-ms 30000}
  an-any-slot-contract-keeps-an-implement-step-working-and-names-the-slot
  (test-support/with-running-agent
   (fn [running _ connection]
     (let [agent-id (str "any-slot-" (seon.id/id))
           _ (db/transact! connection [[:db.fn/call #'host/home-call "root" (get-in (db/db connection) [:config :branch]) 'user
                                        (:seon.cluster/name (test-support/execution-handle nil))]])
           _ (test-support/declare-source! connection 'tq.anyslot
                                           (str "(ns tq.anyslot (:require [clojure.test :refer [deftest is]]))\n"
                                                "(defn area {:malli/schema [:=> [:cat :int] :int]} [r] r)\n"
                                                "(deftest ^{:seon/tasks #{'tq.anyslot/area}} area-test (is (= 25 (area 5))))\n")
                                           "test")
           {branch :seon.store/branch target :seon.db/connection} (test-support/own-branch-agent! running agent-id "(ns tq.anyslot)")
           id (:seon.task/id (task/create! {:seon.db/connection connection :seon.agent/id "root"
                                            :seon.task/ns 'tq.anyslot :seon.task/name "area" :seon.task/plan "Square."}))]
       (task/assign! {:seon.agent/context-source (test-support/running-cluster running) :seon.db/connection connection
                      :seon.task/assigned id :seon.task/agent [:seon.agent/id agent-id] :seon.task/branch branch})
       (evaluate running agent-id (str "(in-ns 'tq.anyslot)\n"
                                       "(defn area {:malli/schema [:=> [:cat :any] :int]} [r] (* r r))"))
       (let [step (:seon.context/step (block running agent-id))]
         (is (= :working (:seon.task/status step)) "the :any slot keeps the step working")
         (is (= [{:seon.fn/sym 'tq.anyslot/area :seon.fn.contract/finding :seon.fn.contract.finding/any
                  :seon.fn.contract/position [:seon.fn.contract.position/input 0]}]
                (mapv #(select-keys % [:seon.fn/sym :seon.fn.contract/finding :seon.fn.contract/position])
                      (some :seon.source/contract-gaps (:seon.context/refusals step))))
             "the section names the function, the slot and the finding")
         (is (false? (context/task-done? (db/db target) agent-id)) "as the end reads it"))))))

(deftest ^{:seon.test/long "An own-branch agent's setup, two eval requests with their test steps, and two reads of its block."
           :seon.test/long-ms 30000}
  an-uncontracted-helper-keeps-a-prove-step-working
  ;; done means the merge would accept, for a Prove step too: a scratch helper defined beside the red product
  ;; would be merged with it and refused for lacking a contract, so the step is working and its section names it
  (test-support/with-running-agent
   (fn [running _ connection]
     (let [agent-id (str "prove-uncontracted-" (seon.id/id))
           _ (db/transact! connection [[:db.fn/call #'host/home-call "root" (get-in (db/db connection) [:config :branch]) 'user
                                        (:seon.cluster/name (test-support/execution-handle nil))]])
           ;; the Prove step declares the one test it writes, so it is assigned before that test exists
           _ (task/index-tasks! {:seon.db/connection connection
                                 :seon.task/files {"resources/seon/tasks/tq.pvu.edn"
                                                   (str "[#:seon.task{:name \"prove\" :plan \"Prove it.\" :step :prove :product #{tq.pvu-test/rows}}\n"
                                                        " #:seon.task{:name \"implement\" :plan \"Implement it.\" :needs #{[:seon.task/sym tq.pvu/prove]}}]")}})
           id (:seon.task/id (db/pull (db/db connection) [:seon.task/id] [:seon.task/sym 'tq.pvu/prove]))
           {branch :seon.store/branch target :seon.db/connection} (test-support/own-branch-agent! running agent-id "(ns tq.pvu-test)")]
       (task/assign! {:seon.agent/context-source (test-support/running-cluster running) :seon.db/connection connection
                      :seon.task/assigned id :seon.task/agent [:seon.agent/id agent-id] :seon.task/branch branch})
       (evaluate running agent-id (str "(ns tq.pvu-test (:require [clojure.test :refer [deftest is]]))\n"
                                       "(deftest ^{:seon/tasks #{'tq.pvu/implement}} rows (is (= 1 2)))"))
       (is (= :done (:seon.task/status (:seon.context/step (block running agent-id)))) "the red product alone is done")
       (evaluate running agent-id "(in-ns 'tq.pvu-test)\n(defn rv [s] s)")
       (let [step (:seon.context/step (block running agent-id))]
         (is (= [:working ['tq.pvu-test/rv]] [(:seon.task/status step) (some :seon.source/uncontracted (:seon.context/refusals step))])
             "the helper keeps the step working, named by its merge's refusal")
         (is (false? (context/task-done? (db/db target) agent-id)) "as the end reads it"))))))

(deftest
  a-prove-steps-start-offers-the-owner-and-the-test-namespace-its-instructions-name
  (test-support/with-running-agent
   (fn [running agent-id connection]
     (test-support/declare-proof! connection 'tq.cs-test 'shape 'tq.cs/implement)
     (let [base {:seon.db/connection connection :seon.agent/id agent-id :seon.task/ns 'tq.cs}
           id (:seon.task/id (task/create! (assoc base :seon.task/name "prove" :seon.task/step :prove
                                                  :seon.task/instructions "Add a row to `tq.cs-test/shape`.")))
           _ (task/create! (assoc base :seon.task/name "implement" :seon.task/needs #{[:seon.task/sym 'tq.cs/prove]}
                                  :seon.task/instructions "Make the rows green."))
           _ (task/create! (assoc base :seon.task/name "subject" :seon.task/plan "Goal."
                                  :seon.task/children #{[:seon.task/sym 'tq.cs/prove] [:seon.task/sym 'tq.cs/implement]}))
           start #(#'context/step-start (db/db connection) {:seon.task/sym 'tq.cs/prove :seon.task/instructions %})]
       (task/assign! {:seon.agent/context-source (test-support/running-cluster running) :seon.db/connection connection
                      :seon.task/assigned id :seon.task/agent [:seon.agent/id agent-id]
                      :seon.task/branch (get-in (db/db connection) [:config :branch])})
       (is (= {:seon.render/operations ['(in-ns 'tq.cs) '(in-ns 'tq.cs-test)]}
              (:seon.context/start (:seon.context/step (block running agent-id))))
           "the block's Prove step offers the owner's namespace, then the test namespace its instructions name")
       (is (= {:seon.render/operations ['(in-ns 'tq.cs)]} (start "Add a row for the empty input."))
           "instructions naming no test offer only the owner's namespace")))))

(deftest ^{:seon.test/long "An own-branch agent's setup, an earlier attempt's assignment and end, one eval request with its test step, and one read of its block."
           :seon.test/long-ms 30000}
  a-rerun-attempts-block-shows-the-earlier-attempt-and-the-branchs-changes
  ;; TASK-CHAIN §2.7: root runs a held task again (`my.root/run!`) on the branch its capped attempt left; the new
  ;; attempt's block shows that attempt (its number, end, outcome, reason and model calls) and every declaration the
  ;; branch changed since its fork, by name with the form that reads it, so the agent sees its earlier work and its
  ;; scratch without reading again
  (test-support/with-running-agent
   (fn [running _ connection]
     (let [agent-id (str "rerun-" (seon.id/id))
           earlier (str "rerun-earlier-" (seon.id/id))
           _ (db/transact! connection [[:db.fn/call #'host/home-call "root" (get-in (db/db connection) [:config :branch]) 'user
                                        (:seon.cluster/name (test-support/execution-handle nil))]])
           _ (test-support/declare-source! connection 'tq.ra
                                           (str "(ns tq.ra (:require [clojure.test :refer [deftest is]]))\n"
                                                "(defn area {:malli/schema [:=> [:cat :int] :int]} [r] r)\n"
                                                "(defn spare \"Unused.\" {:malli/schema [:=> [:cat :int] :int]} [r] r)\n"
                                                "(deftest area-test (is (= 25 (area 5))))\n")
                                           "test")
           [first-task] (linked-chain! connection [{:seon.task/ns 'tq.ra :seon.task/name "implement" :seon.task/plan "Square."
                                                    :seon.task/instructions "Make area square." :seon.task/proofs #{'tq.ra/area-test}}])
           {branch :seon.store/branch target :seon.db/connection} (test-support/own-branch-agent! running agent-id "(ns tq.ra)")
           context-source (test-support/running-cluster running)
           assign! #(task/assign! {:seon.agent/context-source context-source :seon.db/connection connection
                                   :seon.task/assigned first-task :seon.task/agent [:seon.agent/id %] :seon.task/branch branch})
           ;; the earlier attempt's agent, on main and on the branch it worked
           earlier-tx (agent/creation-tx {:seon.agent/id earlier :seon.ns/name 'user :seon.agent/branch branch
                                          :seon.cluster/name (:seon.cluster/name (test-support/execution-handle nil))})
           _ (test-support/transacted! connection earlier-tx)
           _ (test-support/transacted! target earlier-tx)
           first-attempt (assign! earlier)
           capped (seon.id/id [first-task 1])
           ;; the earlier attempt capped and root bailed it (`seon.cluster.agent/bail-chain!`), then ran the task again
           _ (test-support/transacted! connection [[:db/add [:seon.task/assignment capped] :seon.task.attempt/end :capped]])
           _ (test-support/transacted! target [[:db/add [:seon.task/assignment capped] :seon.task.attempt/end :capped]
                                               [:db.fn/call #'task/hold-tx capped "The attempt ended :capped."]])
           second-attempt (assign! agent-id)]
       (is (= [nil nil] (mapv :seon.error/type [first-attempt second-attempt])) "both attempts are assigned")
       (evaluate running agent-id (str "(in-ns 'tq.ra)\n(defn squared {:malli/schema [:=> [:cat :int] :int]} [r] (* r r))\n"
                                       "(defn area {:malli/schema [:=> [:cat :int] :int]} [r] (+ r 0))\n(def scratch 1)\n(my.program/ns-unmap! 'tq.ra/spare)\n(seon.schema/register! :tq.ra/probe :string)"))
       (let [state (block running agent-id)
             changes (:seon.context/changes state)
             changed (into #{} (comp (mapcat val) (map :seon.ref/name)) (dissoc changes :seon.context/removed))
             squared (some #(when (= 'tq.ra/squared (:seon.ref/name %)) %) (:seon.context/functions changes))]
         (is (= [{:seon.task.attempt/number 1 :seon.task.attempt/end :capped :seon.task/outcome :closed
                  :seon.task/reason "The attempt ended :capped." :seon.task/calls 0}]
                (:seon.context/attempts state))
             "the earlier attempt, one entry: its number, end, outcome, reason and model calls")
         (is (every? changed ['tq.ra/squared 'tq.ra/scratch]) "the branch's new definitions, scratch included")
         (is (every? #(seq (:seon.render/operations %)) (mapcat val (dissoc changes :seon.context/removed)))
             "each with the form that reads it")
         ;; the removed and unused names are built, not quoted: a quoted symbol or a literal keyword in this test's
         ;; source is an analysed reference of it, which would refuse the removal and use the key
         (is (= [{:seon.ref/name (symbol "tq.ra" "spare")}] (:seon.context/removed changes))
             "a declaration the branch removed is named as removed, never as an unresolved problem")
         (is (= {:seon.ref/name 'tq.ra/squared :seon.context/reached-by-no-test true
                 :seon.render/operations ['(my.program/source 'tq.ra/squared) '(my.program/ns-unmap! 'tq.ra/squared)]}
                squared)
             "a function no test reaches says so, with the form that removes it, and no doc echo")
         (is (= [{:seon.ref/name 'tq.ra/area :seon.render/operations ['(my.program/source 'tq.ra/area)]}]
                (filterv #(= 'tq.ra/area (:seon.ref/name %)) (:seon.context/functions changes)))
             "a changed function the recorded run of a branch test tested carries no flag")
         (is (= [{:seon.ref/name (keyword "tq.ra" "probe") :seon.context/used-by-no-function-or-test true
                  :seon.render/operations [(list 'my.program/source (keyword "tq.ra" "probe"))]}]
                (:seon.context/schemas changes))
             "a schema key the branch added that no function or test uses says exactly that, with no removal form"))
       ;; a later attempt's block shows this one's last step: the note of the wait it ended its turn with
       (evaluate running agent-id "(my.turn/wait {:my.turn/note \"Stopped at the cycle probe.\"})")
       (is (= "Stopped at the cycle probe." (#'context/last-step (db/db target) agent-id))
           "an attempt's last step is the note of its last wait")))))

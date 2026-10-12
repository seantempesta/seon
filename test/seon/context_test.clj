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
                        :seon.task/name "shape" :seon.task/kind :function}
           id (:seon.task/id (task/create! (assoc declaration :seon.task/intent "First.")))
           task-section #(:seon.context/task (block running agent-id))]
       (is (nil? (:seon.context/task (block running agent-id))) "an agent holding no task has no task section")
       (test-support/declare-proof! connection 'tq.cs-test 'shape 'tq.cs/shape)
       (task/assign! {:seon.agent/context-source (test-support/running-cluster running) :seon.db/connection connection :seon.task/assigned id
                      :seon.task/agent [:seon.agent/id agent-id] :seon.task/branch (get-in (db/db connection) [:config :branch])})
       (is (= {:seon.task/sym 'tq.cs/shape :seon.task/intent "First."} (task-section)))
       (is (= :working (:seon.task/status (:seon.context/turns (block running agent-id)))))
       (is (int? (:my.agent/turns-left (:seon.context/turns (block running agent-id)))))
       (task/create! (assoc declaration :seon.task/intent "Second."
                            :seon.task/basis {id (#'task/declared (db/carried-projection @connection)
                                                                  (db/pull @connection '[*] [:seon.task/id id]))}))
       (is (= "Second." (:seon.task/intent (task-section))) "the revision shows without a reassignment")))))

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
  {:malli/schema [:=> [:cat :string :seon.db/connection :seon.agent/id] :nil]}
  [running connection agent-id]
  (let [_ (test-support/declare-proof! connection 'tq.cs-test 'shape 'tq.cs/shape)
        id (:seon.task/id (task/create! {:seon.db/connection connection :seon.agent/id agent-id :seon.task/ns 'tq.cs
                                         :seon.task/name "shape" :seon.task/kind :function :seon.task/intent "First."}))]
    (task/assign! {:seon.agent/context-source (test-support/running-cluster running) :seon.db/connection connection :seon.task/assigned id
                   :seon.task/agent [:seon.agent/id agent-id] :seon.task/branch (get-in (db/db connection) [:config :branch])})
    nil))

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
       (is (every? #(re-find #"\(= \d" (:expected-text %)) failures) "the claim is the runner's stored text, marked as text")
       (is (every? :actual-text failures))))))

(deftest the-block-resolves-the-names-a-task-references
  (test-support/with-running-agent
   (fn [running agent-id connection]
     (let [_ (test-support/declare-proof! connection 'tq.cs-test 'names 'tq.cs/names)
           id (:seon.task/id (task/create! {:seon.db/connection connection :seon.agent/id agent-id :seon.task/ns 'tq.cs
                                            :seon.task/name "names" :seon.task/kind :function
                                            :seon.task/intent "Use `seon.task/agent-attempt` and `:seon.task/intent`."
                                            :seon.task/must-read #{'seon.task/agent-task}}))]
       (task/assign! {:seon.agent/context-source (test-support/running-cluster running) :seon.db/connection connection :seon.task/assigned id
                      :seon.task/agent [:seon.agent/id agent-id] :seon.task/branch (get-in (db/db connection) [:config :branch])})
       (let [[names operations] (shown running agent-id
                                       "(let [reads (:seon.context/must-reads (seon.context/current-state))] [(mapv :seon.ref/name reads) (:seon.render/operations (second reads))])")]
         (is (= ['seon.task/agent-task 'seon.task/agent-attempt :seon.task/intent] names))
         (is (some #{'(doc seon.task/agent-attempt)} operations)))))))

(deftest a-task-naming-a-missing-symbol-shows-a-problem
  (test-support/with-running-agent
   (fn [running agent-id connection]
     (let [_ (test-support/declare-proof! connection 'tq.cs-test 'missing 'tq.cs/missing)
           id (:seon.task/id (task/create! {:seon.db/connection connection :seon.agent/id agent-id :seon.task/ns 'tq.cs
                                            :seon.task/name "missing" :seon.task/kind :function
                                            :seon.task/intent "Fix `tq.cs/missing`."}))]
       (task/assign! {:seon.agent/context-source (test-support/running-cluster running) :seon.db/connection connection :seon.task/assigned id
                      :seon.task/agent [:seon.agent/id agent-id] :seon.task/branch (get-in (db/db connection) [:config :branch])})
       (is (= [{:seon.ref/name 'tq.cs/missing :seon.ref/problem-kind :unresolved :seon.ref/line 1}]
              (shown running agent-id "(:seon.context/must-reads (seon.context/current-state))")))))))

(deftest a-library-symbol-resolves-with-its-pinned-revision
  (test-support/with-running-agent
   (fn [running agent-id connection]
     (let [_ (test-support/declare-proof! connection 'tq.cs-test 'library 'tq.cs/library)
           id (:seon.task/id (task/create! {:seon.db/connection connection :seon.agent/id agent-id :seon.task/ns 'tq.cs
                                            :seon.task/name "library" :seon.task/kind :function
                                            :seon.task/intent "Validate with `malli.core/validator`."}))]
       (task/assign! {:seon.agent/context-source (test-support/running-cluster running) :seon.db/connection connection :seon.task/assigned id
                      :seon.task/agent [:seon.agent/id agent-id] :seon.task/branch (get-in (db/db connection) [:config :branch])})
       (let [[entry] (shown running agent-id "(:seon.context/must-reads (seon.context/current-state))")
             pin (:seon.ref/pin entry)
             path (mapv str (iterator-seq (.iterator (java.nio.file.Paths/get (.toURI (io/resource "malli/core.cljc"))))))]
         (is (= "malli" (:seon.ref/project pin)))
         (is (= 40 (count (:seon.ref/revision pin))))
         (is (some #{(:seon.ref/revision pin)} path))
         (is (seq (:arglists entry))))))))

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

(deftest ^{:seon/tasks #{'seon.task/an-ended-summary-survives-retiring-its-branch
                         'seon.turn/a-landing-acquires-roots-world-when-root-has-no-branch-row}
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
                                                                      :seon.task/kind :function :seon.task/intent "Square."}))
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

(deftest ^{:seon/tasks #{'seon.turn/the-test-step-writes-nothing-on-main
                         'seon.context/a-prove-steps-red-proof-for-its-successor-is-not-a-reached-red}}
  each-step-kind-is-done-by-its-one-rule
  ;; head: each test's status at the step's branch head; fork point: the tests it held, true when red there
  (let [green {:seon.context/proof-status :green}
        red {:seon.context/proof-status :red :seon.context/proof-failures [{:type :fail}]}
        errored {:seon.context/proof-status :red :seon.context/proof-failures [{:type :error}]}
        unimplemented {:seon.context/proof-status :red :seon.context/proof-failures [{:type :error :seon.error/type :seon.error/unimplemented}]}
        pending {:seon.context/proof-status :pending}
        judged (fn [step proofs product head fork-point]
                 (context/step-status {:seon.task/step step :seon.task/proofs proofs :seon.task/product product
                                       :seon.context/head head :seon.context/fork-point fork-point}))]
    (doseq [[step proofs product head fork-point status broken new-red why]
            [[:implement #{'tq.rule/p} #{} {'tq.rule/p green} {'tq.rule/p true} :done #{} #{} "its proof green, nothing broken"]
             [:implement #{} #{} {} {} :working #{} #{} "no proof proves nothing"]
             [:implement #{'tq.rule/p} #{} {'tq.rule/p red} {'tq.rule/p true} :working #{} #{} "its proof red"]
             [:implement #{'tq.rule/p} #{} {'tq.rule/p pending} {'tq.rule/p true} :working #{} #{} "its proof unrun at its reach"]
             [:implement #{'tq.rule/p} #{} {'tq.rule/p green 'tq.rule/r red} {'tq.rule/p true 'tq.rule/r false} :working #{'tq.rule/r} #{} "a test green at the fork point is red"]
             [:implement #{'tq.rule/p} #{} {'tq.rule/p green 'tq.rule/r red} {'tq.rule/p true 'tq.rule/r true} :done #{} #{} "a test already red at the fork point is not broken"]
             [:implement #{'tq.rule/p} #{} {'tq.rule/p green 'tq.rule/n red} {'tq.rule/p true} :working #{} #{'tq.rule/n} "a new red is no implement step's work"]
             [:prove #{} #{'tq.rule/s} {'tq.rule/s red} {} :done #{} #{'tq.rule/s} "its product red by assertions, new"]
             [:prove #{'tq.rule/q} #{'tq.rule/s} {'tq.rule/q green 'tq.rule/s red} {'tq.rule/q true} :done #{} #{'tq.rule/s} "with its own proof green"]
             [:prove #{'tq.rule/q} #{'tq.rule/s} {'tq.rule/q red 'tq.rule/s red} {'tq.rule/q true} :working #{} #{'tq.rule/s} "its own proof red"]
             [:prove #{} #{'tq.rule/s} {'tq.rule/s unimplemented} {} :done #{} #{'tq.rule/s} "its product red by an unimplemented stub"]
             [:prove #{} #{} {} {} :working #{} #{} "no product"]
             [:prove #{} #{'tq.rule/s} {'tq.rule/s green} {} :working #{} #{} "its product green proves nothing"]
             [:prove #{} #{'tq.rule/s} {'tq.rule/s errored} {} :working #{} #{'tq.rule/s} "an error is no designed red"]
             [:prove #{} #{'tq.rule/s} {'tq.rule/s red} {'tq.rule/s true} :working #{} #{} "a product its fork point already held is not new"]
             [:prove #{} #{'tq.rule/s} {'tq.rule/s red 'tq.rule/r red} {'tq.rule/r false} :working #{'tq.rule/r} #{'tq.rule/s} "a product beside a broken test"]
             [:prove #{} #{'tq.rule/s} {'tq.rule/s red 'tq.rule/n red} {} :working #{} #{'tq.rule/s 'tq.rule/n} "a new red beyond its product"]]]
      (is (= {:seon.task/status status :seon.context/broken broken :seon.context/new-red new-red}
             (judged step proofs product head fork-point))
          why))))

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
                                            :seon.task/ns 'tq.shb :seon.task/name "area" :seon.task/kind :function
                                            :seon.task/intent "Square."}))]
       (task/assign! {:seon.agent/context-source (test-support/running-cluster running) :seon.db/connection connection
                      :seon.task/assigned id :seon.task/agent [:seon.agent/id agent-id] :seon.task/branch branch})
       (evaluate running agent-id "(in-ns 'tq.shb)\n(defn area {:malli/schema [:=> [:cat :int] :int]} [r] (* r r))")
       (let [step (:seon.context/step (block running agent-id))
             current (:seon.context/step (shown running agent-id "(my.task/current {})"))
             [broken] (:seon.context/broken-tests step)]
         (is (= {:green 1} (:seon.context/counts (:seon.context/proofs (block running agent-id)))) "the proof is green")
         (is (= 'tq.shb/identity-test (:seon.test/sym broken)) "the broken test is shown")
         (is (= ["area keeps its side"] (mapv :message (:seon.context/proof-failures broken))) "with its failing assertion")
         (is (= step current) "my.task/current shows the same step")
         (is (= :working (:seon.task/status step)
                (:seon.task/status (:seon.context/turns (block running agent-id))))
             "the step is not done, and the block's status says so")
         (is (false? (context/task-done? (db/db target) agent-id)) "as the end reads it"))))))

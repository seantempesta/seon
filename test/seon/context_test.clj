(ns seon.context-test
  "Contract proof for context derivation between runs."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is use-fixtures]]
            [datahike.api :as d]
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
                                                  :seon.task/plan "Use `seon.task/agent-task`; see `AGENTS.md#data-and-contracts`.")))]
       (task/assign! {:seon.agent/context-source (test-support/running-cluster running) :seon.db/connection connection :seon.task/assigned id
                      :seon.task/agent [:seon.agent/id agent-id] :seon.task/branch (get-in (db/db connection) [:config :branch])})
       (let [reads (shown running agent-id "(mapv #(select-keys % [:seon.ref/name :seon.ref/path :seon.task/plan]) (:seon.context/must-reads (seon.context/current-state)))")]
         (is (some #{{:seon.ref/name 'seon.task/agent-task}} reads) "the intent's names")
         (is (some #{{:seon.ref/path "AGENTS.md"}} reads) "the paths the intent cites"))))))

(defn- declaring-commit
  "The commit on `connection`'s branch whose transaction declared the test `sym`: walking back from the head, the
  last commit whose parent does not hold it."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.test/sym] :uuid]}
  [connection sym]
  (loop [commit (db/commit-id (db/db connection))]
    (let [database (d/commit-as-db connection commit)
          parent (first (d/parent-commit-ids database))
          before (d/commit-as-db connection parent)
          held? (seq (d/datoms before :avet :seon.test/sym sym))]
      (run! d/release-materialized-db [database before])
      (if held? (recur parent) commit))))

(deftest a-steps-block-names-its-chain
  (test-support/with-running-agent
   (fn [running agent-id connection]
     (let [base {:seon.db/connection connection :seon.agent/id agent-id :seon.task/ns 'tq.ch}
           prove (:seon.task/id (task/create! (assoc base :seon.task/name "prove" :seon.task/step :prove
                                                     :seon.task/instructions "Add a row for the empty input.")))
           implement (:seon.task/id (task/create! (assoc base :seon.task/name "implement" :seon.task/needs #{[:seon.task/sym 'tq.ch/prove]}
                                                         :seon.task/instructions "Make the rows green.")))
           _ (task/create! (assoc base :seon.task/name "subject" :seon.task/plan "Scanning is total."
                                  :seon.task/children #{[:seon.task/sym 'tq.ch/prove] [:seon.task/sym 'tq.ch/implement]}))
           ;; the Prove step's product, defined at the agent's REPL: a red deftest tagged with its successor;
           ;; the commit that declared it stands for main's merge commit carrying it
           _ (evaluate running agent-id (str "(ns tq.ch-test (:require [clojure.test :refer [deftest is]]))\n"
                                             "(deftest ^{:seon/tasks #{'tq.ch/implement}} rows (is (= 1 2)))"))
           merge-commit (declaring-commit connection 'tq.ch-test/rows)
           assign! #(task/assign! {:seon.agent/context-source (test-support/running-cluster running) :seon.db/connection connection
                                   :seon.task/assigned % :seon.task/agent [:seon.agent/id agent-id]
                                   :seon.task/branch (get-in (db/db connection) [:config :branch])})
           proving (:seon.task/assignment (assign! prove))
           prove-chain (:seon.context/chain (block running agent-id))]
       (is (= 'tq.ch/subject (:seon.context/subject prove-chain)) "the step names its subject, the goal's owner")
       (is (= [{:seon.task/sym 'tq.ch/implement :seon.task/instructions "Make the rows green."}]
              (:seon.context/after prove-chain))
           "the Prove step names the step after it, the name its product is tagged with, and what that step will do")
       (is (not (contains? prove-chain :seon.context/before)) "no step comes before it")
       ;; root's merge of the Prove assignment, as `seon.merge/merge!` records it on main
       (test-support/transacted! connection [[:db/add [:seon.task/assignment proving] :seon.task.attempt/end :done]
                                             [:db/add [:seon.task/assignment proving] :seon.task/outcome :merged]
                                             [:db/add [:seon.task/assignment proving] :seon.task/merge-commit merge-commit]])
       (assign! implement)
       (let [state (block running agent-id)
             [before] (get-in state [:seon.context/chain :seon.context/before])]
         (is (= {:seon.task/sym 'tq.ch/prove :seon.task/step :prove :seon.task/outcome :merged}
                (select-keys before [:seon.task/sym :seon.task/step :seon.task/outcome]))
             "the Implement step names the step before it as merged")
         (is (some #{'tq.ch-test/rows} (map :seon.ref/name (get-in before [:seon.context/merged :seon.context/tests])))
             "with the tests it merged, each a name with the form that reads it")
         (is (not-any? :seon.test/source (get-in before [:seon.context/merged :seon.context/tests]))
             "a merged declaration is shown one level, never its content")
         (is (= {:seon.task/sym 'tq.ch/implement :seon.task/plan "Scanning is total." :seon.task/instructions "Make the rows green."}
                (:seon.context/task state))
             "the task is the subject's goal with the step's own instructions"))))))

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

(deftest a-refined-intent-is-the-next-blocks-task-with-its-references-resolved
  (test-support/with-running-agent
   (fn [running agent-id connection]
     (proof-task running connection agent-id)
     (let [refined (shown running agent-id "(my.task/refine! {:seon.task/plan \"Use `seon.task/agent-attempt`.\"})")
           state (block running agent-id)]
       (is (true? (:seon.task/changed? refined)) "the agent's own branch holds the new intent")
       (is (= "Use `seon.task/agent-attempt`." (get-in state [:seon.context/task :seon.task/plan])))
       (is (some #{'seon.task/agent-attempt} (map :seon.ref/name (:seon.context/must-reads state)))
           "the next block resolves the reference the refinement added")
       (is (= '[seon.task/agent-attempt]
              (shown running agent-id "(:seon.task/references (seon.db/pull (seon.db/db) [:seon.task/references] [:seon.task/sym 'tq.cs/shape]))"))
           "the writer re-derives the task's references from the refined intent")))))

(deftest a-refinement-naming-nothing-answers-its-problem
  (test-support/with-running-agent
   (fn [running agent-id connection]
     (proof-task running connection agent-id)
     (is (= [{:seon.ref/name 'tq.cs/absent :seon.ref/problem-kind :unresolved}]
            (mapv #(select-keys % [:seon.ref/name :seon.ref/problem-kind])
                  (:my.task/problems (shown running agent-id "(my.task/refine! {:seon.task/plan \"Fix `tq.cs/absent`.\"})"))))))))

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
         (is (seq (:arglists entry))))))))

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
                                                                         "`AGENTS.md#data-and-contracts`; validate with `malli.core/validator`."))))]
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
         (is (seq (:arglists library)) "a library Var shows its arglists"))))))

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

(defn- stable
  "`step` with each refusal's occurrence stamps (when and in which evaluation) removed: the data two reads share."
  [step]
  (update step :seon.source/refusals (partial mapv #(dissoc % :seon.error/at :seon.error/world))))

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
         (is (= [:working ['tq.unc/squared]] [(:seon.task/status step) (some :seon.source/uncontracted (:seon.source/refusals step))])
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
                      (some :seon.source/contract-gaps (:seon.source/refusals step))))
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
         (is (= [:working ['tq.pvu-test/rv]] [(:seon.task/status step) (some :seon.source/uncontracted (:seon.source/refusals step))])
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

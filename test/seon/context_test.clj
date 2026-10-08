(ns seon.context-test
  "Contract proof for context derivation between runs."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is use-fixtures]]
            [seon.cluster.agent :as agent]
            [seon.context :as context]
            [seon.db :as db]
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
       (task/assign! {:seon.db/connection connection :seon.task/assigned id
                      :seon.task/agent [:seon.agent/id agent-id] :seon.task/branch :main})
       (is (= {:seon.task/sym 'tq.cs/shape :seon.task/intent "First."} (task-section)))
       (is (= :working (:seon.task/status (:seon.context/turns (block running agent-id)))))
       (is (int? (:my.agent/turns-left (:seon.context/turns (block running agent-id)))))
       (task/create! (assoc declaration :seon.task/intent "Second."
                            :seon.task/basis {id (#'task/declared (db/carried-projection @connection)
                                                                  (db/pull @connection '[*] [:seon.task/id id]))}))
       (is (= "Second." (:seon.task/intent (task-section))) "the revision shows without a reassignment")))))

(deftest ^{:seon/tasks #{'seon.context/current-state-prints-the-world-not-the-block}}
  current-state-shows-only-its-sections
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
  {:malli/schema [:=> [:cat :seon.db/connection :seon.agent/id] :nil]}
  [connection agent-id]
  (let [_ (test-support/declare-proof! connection 'tq.cs-test 'shape 'tq.cs/shape)
        id (:seon.task/id (task/create! {:seon.db/connection connection :seon.agent/id agent-id :seon.task/ns 'tq.cs
                                         :seon.task/name "shape" :seon.task/kind :function :seon.task/intent "First."}))]
    (task/assign! {:seon.db/connection connection :seon.task/assigned id
                   :seon.task/agent [:seon.agent/id agent-id] :seon.task/branch :main})
    nil))

(def ^:private proof-test
  "(ns tq.cs-test (:require [clojure.test :refer [deftest is]] [tq.cs])) (deftest ^{:seon/tasks #{'tq.cs/shape}} shape (is (= 4 (tq.cs/f 2)) \"doubles\"))")

(def ^:private two-failure-proof-test
  "(ns tq.cs-test (:require [clojure.test :refer [deftest is]] [tq.cs])) (deftest ^{:seon/tasks #{'tq.cs/shape}} shape (is (= 4 (tq.cs/f 2)) \"doubles\")\n (is (= 9 (tq.cs/f 3)) \"triples\"))")

(deftest ^{:seon/tasks #{'seon.context/a-red-proof-shows-printed-strings-and-only-its-first-failure}}
  a-red-proof-shows-every-failing-assertion-with-its-claim-as-text
  (test-support/with-running-agent
   (fn [running agent-id connection]
     (proof-task connection agent-id)
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
                                            :seon.task/intent "Use `seon.task/home` and `:seon.task/intent`."
                                            :seon.task/must-read #{'seon.task/agent-task}}))]
       (task/assign! {:seon.db/connection connection :seon.task/assigned id
                      :seon.task/agent [:seon.agent/id agent-id] :seon.task/branch :main})
       (let [[names operations] (shown running agent-id
                                       "(let [reads (:seon.context/must-reads (seon.context/current-state))] [(mapv :seon.ref/name reads) (:seon.render/operations (second reads))])")]
         (is (= ['seon.task/agent-task 'seon.task/home :seon.task/intent] names))
         (is (some #{'(doc seon.task/home)} operations)))))))

(deftest a-task-naming-a-missing-symbol-shows-a-problem
  (test-support/with-running-agent
   (fn [running agent-id connection]
     (let [_ (test-support/declare-proof! connection 'tq.cs-test 'missing 'tq.cs/missing)
           id (:seon.task/id (task/create! {:seon.db/connection connection :seon.agent/id agent-id :seon.task/ns 'tq.cs
                                            :seon.task/name "missing" :seon.task/kind :function
                                            :seon.task/intent "Fix `tq.cs/missing`."}))]
       (task/assign! {:seon.db/connection connection :seon.task/assigned id
                      :seon.task/agent [:seon.agent/id agent-id] :seon.task/branch :main})
       (is (= [{:seon.ref/name 'tq.cs/missing :seon.ref/problem-kind :unresolved :seon.ref/line 1}]
              (shown running agent-id "(:seon.context/must-reads (seon.context/current-state))")))))))

(deftest a-library-symbol-resolves-with-its-pinned-revision
  (test-support/with-running-agent
   (fn [running agent-id connection]
     (let [_ (test-support/declare-proof! connection 'tq.cs-test 'library 'tq.cs/library)
           id (:seon.task/id (task/create! {:seon.db/connection connection :seon.agent/id agent-id :seon.task/ns 'tq.cs
                                            :seon.task/name "library" :seon.task/kind :function
                                            :seon.task/intent "Validate with `malli.core/validator`."}))]
       (task/assign! {:seon.db/connection connection :seon.task/assigned id
                      :seon.task/agent [:seon.agent/id agent-id] :seon.task/branch :main})
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

(deftest ^{:seon/tasks #{'seon.context/a-handled-message-stays-unread-in-every-later-pass}}
  a-message-a-closed-turn-handled-leaves-the-unread-section
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

(deftest ^{:seon/tasks #{'seon.context/the-system-message-is-the-printed-help-map-not-its-render}}
  a-request-that-cannot-run-the-help-render-answers-a-refusal
  (test-support/seed-cluster! *connection* "unrendered")
  (test-support/transacted! *connection* (agent/creation-tx {:seon.agent/id "unrendered"
                                                             :seon.ns/name 'my.agents.unrendered
                                                             :seon.cluster/name "unrendered"}))
  ;; a profile but no context, caps or time limit: the help render cannot run
  (let [answer (context/model-context (db/db *connection*) {:seon.boot/cluster-name "unrendered"} "unrendered")]
    (is (= :seon.render/request-error (:seon.error/type answer)))
    (is (not (contains? answer :seon.ai/system)) "never the printed help map as the system message")))

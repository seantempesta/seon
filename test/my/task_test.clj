(ns my.task-test
  "An agent's task verbs: its task with its proofs, and giving the task back. Each test asserts one
  behavior on data."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [my.edit]
            [my.task :as my.task]
            [seon.cluster :as cluster]
            [seon.cluster.source]
            [seon.db :as db]
            [seon.mcp :as mcp]
            [seon.schema :as schema]
            [seon.task :as task]
            [seon.test-support :as test-support]))

(defn- with-root
  "Run `f` with a connection holding a seeded cluster and its root agent; `assigned?` gives root a task
  with an unrun proof."
  [assigned? f]
  (test-support/with-database
   (fn [connection]
     (test-support/seed-cluster! connection "my-task" {})
     (cluster/seed-root-agent! connection "my-task" cluster/boot-process-identity)
     (when assigned? (test-support/assign-task! connection "root"))
     (f connection))))

(deftest current-answers-the-held-task-with-its-proofs
  (with-root true
   (fn [connection]
     (let [database (db/db connection)
           current (my.task/current {:seon.db/db database :seon.agent/id "root"})]
       (is (= (task/agent-task database "root") (get-in current [:seon.context/task :seon.task/id]))
           "the task root holds")
       (is (seq (get-in current [:seon.context/proofs :seon.context/proof-list])) "its proofs, each with its status")))))

(deftest current-with-no-task-answers-the-agent-alone
  (with-root false
   (fn [connection]
     (is (= {:seon.agent/id "root"} (my.task/current {:seon.db/db (db/db connection) :seon.agent/id "root"}))))))

(defn- send-back-request
  "A send-back request as the world completes it: root's connection beside what the caller named."
  [connection named]
  (merge {:seon.db/connection connection :seon.agent/id "root"} named))

(deftest send-back-is-a-completed-disposition-carrying-its-reason-and-data
  (with-root true
   (fn [connection]
     (let [sent (my.task/send-back! (send-back-request connection
                                                        {:my.task/reason "The proof contradicts the intent."
                                                         :my.task/target 'tq.assigned/work-root :my.task/counterexample {:expected 1 :actual 2}}))]
       (is (= :completed (:my.turn/disposition sent)) "the disposition the turn derives :given-back from")
       (is (= "The proof contradicts the intent." (:my.turn/result sent)) "the reason is the reply")
       (is (= 'tq.assigned/work-root (:my.task/target sent)))
       (is (= {:expected 1 :actual 2} (:my.task/counterexample sent)))))))

(deftest send-back-holding-no-task-answers-its-error
  (with-root false
   (fn [connection]
     (is (= :my.task/no-task-error
            (:seon.error/type (my.task/send-back! (send-back-request connection {:my.task/reason "I cannot."}))))))))

(deftest a-send-back-with-positional-arguments-is-answered-with-its-example
  ;; chain experiment 2: an Implement agent called (my.task/send-back! target reason); the refusal named only the
  ;; destructured arglist, so its last calls were spent and the send-back never happened.
  (let [refusal (try (apply my.task/send-back! ['tq.assigned/work-root "The proof contradicts the intent."])
                     (catch Exception thrown (ex-data thrown)))]
    (is (= :seon.instrument/arity-error (:seon.error/type refusal)) "the call is refused by its contract")
    (is (= 'my.task/send-back! (first (first (:seon.instrument/examples refusal))))
        "the refusal offers the docstring's Example as a form to run")))

(deftest send-back-with-a-blank-reason-answers-its-error
  (with-root true
   (fn [connection]
     (is (:seon.error/at (my.task/send-back! (send-back-request connection {:my.task/reason "  "})))))))

(defn- updating
  "The update request of root's `sym` member `k` on `connection`: what `my.task/update!` builds, with the world's
  connection and agent named."
  [connection sym k f args]
  {:seon.db/connection connection :seon.agent/id "root" :my.task/task sym :my.task/member k :my.task/function f :my.task/arguments args})

(defn- with-task
  "Run `f` with a connection holding root's task `tq.upd/work` (plan `Scan.`) and a function that updates it."
  [f]
  (with-root false
   (fn [connection]
     (task/create! {:seon.db/connection connection :seon.agent/id "root" :seon.task/ns 'tq.upd :seon.task/name "work" :seon.task/plan "Scan."})
     (f connection
        (fn [k f & args] (@#'my.task/member-updated (updating connection 'tq.upd/work k f (vec args))))
        #(task/task-declaration (db/db connection) (task/task-handle 'tq.upd "work"))))))

(deftest update-applies-a-function-to-a-member
  (with-task
   (fn [_ update! declared]
     (is (true? (:seon.task/changed? (update! :seon.task/proofs (fnil into #{}) '[tq.upd-test/rows])))
         "an absent member is nil to f")
     (is (= #{'tq.upd-test/rows} (:seon.task/proofs (declared))) "the member holds f's value")
     (is (true? (:seon.task/changed? (update! :seon.task/plan my.edit/text "Scan." "Scan twice."))))
     (is (= "Scan twice." (:seon.task/plan (declared))) "my.edit/text changes one passage of the plan")))
  (test-support/with-running-agent
   (fn [running agent-id connection]
     (test-support/declare-proof! connection 'tq.upd-test 'rows 'tq.upd/work)
     (let [id (:seon.task/id (task/create! {:seon.db/connection connection :seon.agent/id agent-id :seon.task/ns 'tq.upd
                                            :seon.task/name "work" :seon.task/plan "old"}))
           declared #(task/task-declaration (db/db connection) (task/task-handle 'tq.upd "work"))
           shown #(:seon.repl/value (test-support/mcp-reply
                                     (json/read-str (mcp/call (json/write-str {"name" "eval_clj"
                                                                               "arguments" {"code" % "agent" agent-id "cluster" running}})))))]
       (task/assign! {:seon.agent/context-source (test-support/running-cluster running) :seon.db/connection connection :seon.task/assigned id
                      :seon.task/agent [:seon.agent/id agent-id] :seon.task/branch (get-in (db/db connection) [:config :branch])})
       (is (true? (:seon.task/changed? (shown "(my.task/update! 'tq.upd/work :seon.task/plan my.edit/text \"old\" \"new\")")))
           "the card's five-argument form, connection and agent filled from the evaluation")
       (is (= "new" (:seon.task/plan (declared))) "the passage changed on the agent's branch")
       (is (true? (:seon.task/changed? (shown "(my.task/update! 'tq.upd/work :seon.task/proofs (fnil into #{}) '[a/b])"))))
       (is (= #{'a/b} (:seon.task/proofs (declared))) "the set grew")))))

(deftest update-with-the-same-value-writes-no-transaction
  (with-task
   (fn [connection update! declared]
     (let [before (db/db connection)]
       (is (false? (:seon.task/changed? (update! :seon.task/plan identity))))
       (is (= (db/commit-id before) (db/commit-id (db/db connection))) "the database is the same value")
       (is (= "Scan." (:seon.task/plan (declared))))))))

(deftest update-answers-the-error-value-f-returns-and-writes-nothing
  (with-task
   (fn [_ update! declared]
     (is (= :my.edit/no-match-error (:seon.error/type (update! :seon.task/plan my.edit/text "absent" "x"))))
     (is (= "Scan." (:seon.task/plan (declared)))))))

(deftest update-refuses-an-invalid-value
  (with-task
   (fn [_ update! declared]
     (is (:seon.error/at (update! :seon.task/plan (constantly ""))))
     (is (= "Scan." (:seon.task/plan (declared)))))))

(deftest update-refuses-a-basis-that-changed-while-f-ran
  (with-task
   (fn [connection update! declared]
     (let [answer (update! :seon.task/plan
                           (fn [plan]
                             (task/create! {:seon.db/connection connection :seon.agent/id "root" :seon.task/ns 'tq.upd
                                            :seon.task/name "work" :seon.task/plan "Changed meanwhile."
                                            :seon.task/basis {(task/task-handle 'tq.upd "work") (declared)}})
                             (str plan " Mine.")))]
       (is (= :revision-conflict (:seon.task/refused answer)) "the writer's conflict")
       (is (= ["Scan." "Changed meanwhile." "Scan. Mine."]
              (mapv #(:seon.task/plan (get answer %)) [:seon.task/base :seon.task/current :seon.task/proposed]))
           "base, current and proposed")
       (is (= "Changed meanwhile." (:seon.task/plan (declared))) "the other write stands")))))

(deftest only-content-members-are-update-keys
  (let [valid? (schema/projection-validator (schema/handed-projection) :my.task/content-key)]
    (is (every? valid? [:seon.task/plan :seon.task/instructions :seon.task/proofs]))
    (is (not-any? valid? [:seon.task/id :seon.task/ns :seon.task/name :seon.task/next :seon.task/execution]))))

(deftest update-keeps-a-write-naming-an-unresolved-reference-and-answers-its-problem
  (with-task
   (fn [_ update! declared]
     (let [answer (update! :seon.task/plan (constantly "Fix `tq.upd/absent`."))]
       (is (= [{:seon.ref/name 'tq.upd/absent :seon.ref/problem-kind :unresolved}]
              (mapv #(select-keys % [:seon.ref/name :seon.ref/problem-kind]) (:my.task/problems answer))))
       (is (= "Fix `tq.upd/absent`." (:seon.task/plan (declared))) "the write is kept")))))

(deftest update-of-an-unknown-task-answers-its-error
  (with-root false
   (fn [connection]
     (is (= :my.task/unknown-task-error
            (:seon.error/type (@#'my.task/member-updated (updating connection 'tq.upd/absent :seon.task/plan (constantly "x") []))))))))

(deftest update-preserves-every-declared-member
  (with-root false
   (fn [connection]
     (test-support/declare-proof! connection 'tq.members-test 'rows 'tq.members/prove)
     (let [projection (db/carried-projection (db/db connection))
           handle #(task/task-handle 'tq.members %)
           declared #(task/task-declaration (db/db connection) (handle %))
           create! #(task/create! (assoc % :seon.db/connection connection :seon.agent/id "root"))
           full {:seon.task/ns 'tq.members :seon.task/name "prove" :seon.task/step :prove
                 :seon.task/plan "Scan." :seon.task/instructions "Add rows to `tq.members-test/rows`."
                 :seon.task/children #{[:seon.task/sym 'tq.members/child]} :seon.task/needs #{[:seon.task/sym 'tq.members/first]}
                 :seon.task/product #{'tq.members-test/rows} :seon.task/proofs #{'tq.members-test/rows}}]
       (is (= (set (map first (task/declaration-members projection))) (set (keys full)))
           "the fixture declares every member of the declaration schema")
       (create! {:seon.task/ns 'tq.members :seon.task/name "first" :seon.task/plan "First."})
       (create! {:seon.task/ns 'tq.members :seon.task/name "child" :seon.task/plan "Child."})
       (create! full)
       (is (= full (declared "prove")) "create! writes every declared member")
       (let [path "resources/seon/tasks/tq.members.edn"
             text (@#'seon.cluster.source/task-file-text projection "[]" (mapv (fn [n] [n (declared n)]) ["first" "child" "prove"]))
             ingested (into {} (map (juxt :seon.task/name (partial task/declaration projection))) (task/file-declarations projection path text))]
         (is (= full (get ingested "prove")) "the written task file re-ingests the same declaration"))
       (task/assign! {:seon.db/connection connection :seon.task/assigned (handle "prove")
                      :seon.task/agent [:seon.agent/id "root"] :seon.task/branch (get-in (db/db connection) [:config :branch])
                      :seon.agent/context-source (test-support/context-source connection)})
       (@#'my.task/member-updated (updating connection 'tq.members/prove :seon.task/instructions (constantly "Add more rows.") []))
       (is (= (assoc full :seon.task/instructions "Add more rows.") (declared "prove"))
           "an update keeps every member it does not change")
       (@#'my.task/member-updated (updating connection 'tq.members/prove :seon.task/proofs conj ['tq.members-test/more]))
       (is (= #{'tq.members-test/rows 'tq.members-test/more} (:seon.task/proofs (declared "prove")))
           "an updated member is written on the task named")))))

(deftest only-set-members-are-normalized-to-sets
  (with-root false
   (fn [connection]
     (let [projection (db/carried-projection (db/db connection))
           extended (schema/projection-with-schema
                     projection :seon.task/declaration
                     (conj (get (:seon.schema.projection/forms projection) :seon.task/declaration)
                           [:tq.members/examples {:optional true} [:vector :string]])
                     {:seon.schema.admission/source :agent})]
       (is (= {:seon.task/ns 'tq.members :seon.task/name "t" :tq.members/examples ["b" "a"] :seon.task/product #{'x/y}}
              (task/declaration extended {:seon.task/ns 'tq.members :seon.task/name "t" :tq.members/examples ["b" "a"]
                                          :seon.task/product ['x/y] :seon.task/id "not-a-member"}))
           "a vector member stays a vector; a set member read as a vector is a set")))))

(ns seon.cluster.run-test
  "Writer transitions verified against a pure state model and canonical Datahike.
  Open means no closed-at. Boot closes open turns and interrupts unfinished
  evaluations; settled outcomes remain unchanged."
  (:require [clojure.main :as main]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [seon.db :as db]
            [seon.cluster.run :as run]
            [seon.error :as error]
            [seon.fn :as seon.fn]
            [seon.repl :as repl]
            [seon.schema]
            [seon.test-support :as test-support]))

(deftest evaluation-ai-is-only-the-repl-session
  (let [rendered
        (repl/render-ai
         {:db/id 7042
          :seon.cluster.eval/source "(+ 40 2)"
          :seon.cluster.eval/ordinal 0
          :seon.cluster.eval/ns {:seon.ns/name 'my.probe}
          :seon.eval/value
          "42"})
        lines (str/split-lines rendered)]
    (is (= "my.probe=> (+ 40 2)\n#:seon.repl{:value 42, :result result/e7042}"
           rendered)
        "the handle is the evaluation's own entity id, never its ordinal")
    ;; THE RESPONSE IS ONE DATUM. It may wrap when a value is wide, but the
    ;; prompt line is followed by the map and by nothing else: a second
    ;; line that is not a continuation of that map would be a second grammar,
    ;; and a comment-shaped one would be something the agent should write.
    (is (= 2 (count lines)))
    (is (str/starts-with? (second lines) "#:seon.repl{"))
    (is (str/ends-with? (last lines) "}"))
    (is (not-any? #(str/starts-with? (str/trim %) ";") (rest lines))))
  (is (nil? (repl/render-ai {}))
      "an evaluation with no source is not a REPL entry")
  ;; AN INTERRUPTED EVALUATION SAYS SO. `interrupted-at` is an inst, not a
  ;; flag, and since the REPL grammar renders it the response is the instant
  ;; the boot cut the evaluation — never a bare prompt that reads exactly
  ;; like a form still running.
  (let [interrupted (repl/render-ai
                     {:seon.cluster.eval/source "(side-effect)"
                      :seon.cluster.eval/interrupted-at
                      (java.util.Date. 1785500000000)})]
    (is (str/starts-with? interrupted "user=> (side-effect)"))
    (is (str/includes? interrupted ":interrupted #inst") interrupted)
    (is (not (str/includes? interrupted ":value"))
        "an interrupted evaluation answers no value"))
  (let [triage-edn
        (try
          (/ 1 0)
          (catch Throwable throwable
            (pr-str (main/ex-triage (Throwable->map throwable)))))
        rendered
        (repl/render-ai
         {:seon.cluster.eval/source "(/ 1 0)"
          :seon.cluster.eval/ordinal 0
          :seon.cluster.eval/error "Divide by zero"
          :seon.cluster.eval/triage-edn triage-edn})]
    (is (str/includes? rendered ":error \"Execution error (ArithmeticException) at"))
    (is (str/includes? rendered "Divide by zero"))
    (is (not (str/includes? rendered "Form ")))
    (is (not (str/includes? rendered "failed:")))))

;;; ---------------------------------------------------------------------------
;;; In-memory database fixture
;;; ---------------------------------------------------------------------------

(defn- with-model-database [body]
  (test-support/with-database body))

(deftest test-first-subjects-resolve-when-the-function-arrives
  (with-model-database
    (fn [connection]
      (let [namespace-name 'fixture.pending
            test-symbol "fixture.pending/target-test"
            function-symbol "fixture.pending/target"
            now (java.util.Date. 1785000000000)
            settle-row!
            (fn [run-id agent-id row]
              (db/transact!
               connection
               [{:seon.cluster.agent/id agent-id}
                {:seon.cluster.run/id run-id
                 :seon.cluster.run/agent
                 [:seon.cluster.agent/id agent-id]
                 :seon.cluster.run/opened-at now}])
              (db/transact!
               connection
               (run/receipt-start-tx
                {::run/id run-id
                 :seon.cluster.eval/ordinal 0
                 :seon.cluster.eval/at now}))
              (db/transact!
               connection
               (run/receipt-settle-tx
                {::run/id run-id
                 :seon.cluster.eval/ordinal 0
                 :seon.eval/value "nil"
                 :seon.program/row row})))]
        (db/transact! connection [{:seon.ns/name namespace-name}])
        (settle-row!
         "pending-test-run" "pending-test-agent"
         {:seon.test/sym test-symbol
          :seon.test/ns [:seon.ns/name namespace-name]
          :seon.test/source "(clojure.test/deftest target-test)"
          :seon.schema.admission/source :agent
          :seon.test/subject [:seon.fn/sym function-symbol]})
        (let [pending (db/pull @connection '[*]
                               [:seon.test/sym test-symbol])]
          (is (= function-symbol (:seon.test/pending-subject pending)))
          (is (nil? (:seon.test/subject pending)))
          (is (= [test-symbol]
                 (seon.fn/gate-set @connection function-symbol))))
        (settle-row!
         "pending-function-run" "pending-function-agent"
         {:seon.fn/sym function-symbol
          :seon.schema.admission/source :agent
          :seon.fn/ns [:seon.ns/name namespace-name]
          :seon.fn/source
          "(defn ^{:malli/schema [:=> [:cat] :int]} target [] 1)"
          :seon.fn/arglists "([])"
          :seon.fn/private? false
          :seon.fn/spec "[:=> [:cat] :int]"})
        (let [resolved (db/pull @connection
                                '[:seon.test/pending-subject
                                  {:seon.test/subject [:seon.fn/sym]}]
                                [:seon.test/sym test-symbol])]
          (is (nil? (:seon.test/pending-subject resolved)))
          (is (= function-symbol
                 (get-in resolved [:seon.test/subject :seon.fn/sym])))
          (is (= [test-symbol]
                 (seon.fn/gate-set @connection function-symbol))))))))

;; Deterministic clock: every generated time is an offset from t0.
(def ^:private t0-ms 1785000000000)
(defn- at [offset-ms] (java.util.Date. (long (+ t0-ms offset-ms))))
(def ^:private t0 (at 0))
(def ^:private t1 (at 300000))
(def ^:private t2 (at 1800000))

(defn- deepest-ex-data [error]
  (loop [throwable error
         found nil]
    (if throwable
      (recur (ex-cause throwable)
             (or (not-empty (ex-data throwable)) found))
      found)))

(defn- transact-or-refusal
  "Commit tx-data; a refusal returns its deepest ex-data as a value."
  [connection tx-data]
  (try
    (let [result (db/transact! connection tx-data)]
      (if (:seon.error/kind result)
        result
        ::committed))
    (catch Exception e
      (or (deepest-ex-data e)
          {::opaque (ex-message e)}))))

(defn- run-entity [connection run-id]
  (db/pull (db/db connection) '[*] [:seon.cluster.run/id run-id]))

(defn- open-run-id [connection agent-id]
  (run/open-for-agent (db/db connection)
                      [:seon.cluster.agent/id agent-id]))

;;; ---------------------------------------------------------------------------
;;; Derivations — state is computed from primitives, never stored
;;; ---------------------------------------------------------------------------

(deftest state-is-derived-from-primitives
  (testing "open is the absence of closed-at"
    (is (true? (run/open? {})))
    (is (false? (run/open? {::run/closed-at t2}))))
  )

(deftest interrupted-warning-is-one-derived-value
  (testing "clean evaluations derive no warning at all"
    (is (nil? (run/interrupted-warning
               [{:seon.cluster.eval/ordinal 0
                 :seon.eval/value "1"}
                {:seon.cluster.eval/ordinal 1}
                {:seon.cluster.eval/ordinal 2}]))))
  (testing "an interrupted evaluation derives exactly one warning naming
            the first interrupted ordinal and the missing tail"
    (let [warning (run/interrupted-warning
                   [{:seon.cluster.eval/ordinal 0
                     :seon.eval/value "1"}
                    {:seon.cluster.eval/ordinal 1
                     :seon.cluster.eval/interrupted-at t1}
                    {:seon.cluster.eval/ordinal 2}])]
      (is (= 1 (:seon.cluster.eval/ordinal warning)))
      (is (= 2 (:seon.cluster.run/missing-results warning))))))

(deftest a-frozen-run-holds-exactly-one-evaluation-per-ordinal-and-no-form-entity
  ;; THE MERGE, ASSERTED AS A COUNT. A frozen plan of N sources leaves N
  ;; evaluation entities and nothing else: no twin form entity, no second
  ;; `:db.unique/identity` attribute that a derived identity string could
  ;; resolve against, and no run-side component list mirroring the back-edge
  ;; the evaluations already carry.
  (with-model-database
    (fn [connection]
      (db/transact! connection [{:seon.cluster.agent/id "merged-agent"}])
      (db/transact!
       connection
       (run/open-tx {::run/id "merged"
                     ::run/agent [:seon.cluster.agent/id "merged-agent"]
                     ::run/opened-at t0}))

      (is (= ::committed
             (transact-or-refusal
              connection
              (run/plan-tx {::run/id "merged"
                            :seon.db.process/id "p1"
                            ::run/starting-ns [:seon.ns/name 'user]
                            ::run/plan-digest "merged-digest"
                            :seon.cluster.eval/at t1
                            ::run/sources
                            [{:seon.cluster.eval/source "(+ 1 1)"}
                             {:seon.cluster.eval/source "(+ 2 2)"}
                             {:seon.cluster.eval/source "(+ 3 3)"}]}))))
      (let [database (db/db connection)
            run (run-entity connection "merged")
            evaluations
            (db/q '[:find [(pull ?evaluation [*]) ...]
                    :in $ ?run
                    :where [?evaluation :seon.cluster.eval/run ?run]]
                  database (:db/id run))]
        (is (= 3 (count evaluations))
            "one entity per (run, ordinal), never two")
        (is (= [0 1 2] (sort (map :seon.cluster.eval/ordinal evaluations))))
        (is (= 3 (count (set (map :db/id evaluations))))
            "three ordinals name three entities, not six")
        (is (= (set (map #(run/receipt-identity "merged" %) [0 1 2]))
               (set (map :seon.cluster.eval/id evaluations)))
            "one identity derivation names every frozen ordinal")
        (is (= ["(+ 1 1)" "(+ 2 2)" "(+ 3 3)"]
               (mapv :seon.cluster.eval/source
                     (sort-by :seon.cluster.eval/ordinal evaluations)))
            "the frozen source is an attribute of the evaluation itself")
        (is (every? #(= :agent (:seon.cluster.eval/author %)) evaluations))
        (is (not-any? run/terminal? evaluations)
            "the freeze asserts no terminal fact; that absence IS running")
        (is (empty?
             (filter #(and (keyword? %)
                           (= "seon.cluster.run.form" (namespace %)))
                     (keys (:schema database))))
            "no attribute of the deleted form family is installed")
        (is (nil? (find run :seon.cluster.run/forms))
            "the run keeps no component mirror of the back-edge")))))

;;; ---------------------------------------------------------------------------
;;; Teaching examples — the call shapes, one committed lifecycle
;;; ---------------------------------------------------------------------------

(deftest one-run-lifecycle-teaches-the-call-shapes
  (with-model-database
    (fn [connection]
      (db/transact! connection [{:seon.cluster.agent/id "teacher"}])
      (testing "open: run entity + agent pointer from ONE agent ref"
        (is (= ::committed
               (transact-or-refusal
                connection
                (run/open-tx {::run/id "lesson"
                              ::run/agent [:seon.cluster.agent/id "teacher"]
                              ::run/opened-at t0}))))
        (is (= "lesson" (open-run-id connection "teacher"))))

      (testing "plan freezes once, with its ordered owned forms"
        (is (= ::committed
               (transact-or-refusal
                connection
                (run/plan-tx {::run/id "lesson"
                              :seon.db.process/id "p1"
                              ::run/starting-ns [:seon.ns/name 'user]
                              ::run/plan-digest "digest-a"
                              ::run/sources
                              [{:seon.cluster.eval/source "(+ 1 1)"}
                               {:seon.cluster.eval/source "(+ 2 2)"}]}))))
        (is (= ["(+ 1 1)" "(+ 2 2)"]
               (->> (db/q '[:find ?ordinal ?source
                           :in $ ?run-id
                           :where
                           [?run :seon.cluster.run/id ?run-id]
                           [?form :seon.cluster.eval/run ?run]
                           [?form :seon.cluster.eval/ordinal ?ordinal]
                           [?form :seon.cluster.eval/source ?source]]
                         (db/db connection) "lesson")
                    (sort-by first)
                    (mapv second)))))
        (is (= #{:agent}
               (set
                (db/q '[:find [?author ...]
                        :in $ ?run-id
                        :where
                        [?run :seon.cluster.run/id ?run-id]
                        [?form :seon.cluster.eval/run ?run]
                        [?form :seon.cluster.eval/author ?author]]
                      @connection "lesson"))))
      (testing "close settles the run and retracts the pointer it
                derived from the run's own agent connection"
        (is (= ::committed
               (transact-or-refusal
                connection
                (run/close-tx {::run/id "lesson"
                               :seon.db.process/id "p1"
                               ::run/closed-at t2}))))
        (let [entity (run-entity connection "lesson")]
          (is (false? (run/open? {::run/closed-at
                                  (::run/closed-at entity)})))
          )
        (is (nil? (open-run-id connection "teacher")))))))

(deftest generated-system-runs-grow-only-after-their-settled-prefix
  (with-model-database
    (fn [connection]
      (db/transact!
       connection
       [{:seon.ns/name 'my.agents.generated}
        {:seon.cluster.agent/id "generated-agent"
         :seon.cluster.agent/namespace
         [:seon.ns/name 'my.agents.generated]}])
      (is (= ::committed
             (transact-or-refusal
              connection
              (run/generated-run-tx
               @connection
               {:seon.cluster.agent/id "generated-agent"
                ::run/id "generated-run"
                :seon.db.process/id "generated-process"
                ::run/opened-at t0
                ::run/starting-ns [:seon.ns/name 'my.agents.generated]}))))
      (is (= {:seon.cluster.work/situation :generate
              ::run/starting-ns {:seon.ns/name 'my.agents.generated}}
             (db/pull @connection
                      '[:seon.cluster.work/situation
                        {:seon.cluster.run/starting-ns [:seon.ns/name]}]
                      [::run/id "generated-run"])))
      (is (empty?
           (db/q '[:find ?form
                   :where
                   [?run :seon.cluster.run/id "generated-run"]
                   [?form :seon.cluster.eval/run ?run]]
                 @connection)))
      (is (= ::committed
             (transact-or-refusal
              connection
              (run/append-generated-tx
               {::run/id "generated-run"
                :seon.db.process/id "generated-process"
                :seon.cluster.eval/at t0
                :seon.cluster.eval/ordinal 0
                :seon.cluster.eval/source "(help)"
                :seon.ns/name 'my.agents.generated}))))
      (is (= {:seon.cluster.eval/ordinal 0
              :seon.cluster.eval/at t0
              :seon.cluster.eval/source "(help)"
              :seon.cluster.eval/ns {:seon.ns/name 'my.agents.generated}}
             (db/pull @connection
                      '[:seon.cluster.eval/ordinal :seon.cluster.eval/at
                        :seon.cluster.eval/source
                        {:seon.cluster.eval/ns [:seon.ns/name]}]
                      [:seon.cluster.eval/id
                       (run/receipt-identity "generated-run" 0)]))
          "the durable generated form and running evaluation are atomic")
      (is (= ::run/generated-prefix-unsettled
             (::run/rule
              (transact-or-refusal
               connection
               (run/append-generated-tx
                {::run/id "generated-run"
                 :seon.db.process/id "generated-process"
                 :seon.cluster.eval/at t0
                 :seon.cluster.eval/ordinal 1
                 :seon.cluster.eval/source "(dir 'my.run)"
                 :seon.ns/name 'my.agents.generated})))))
      (db/transact!
       connection
       (run/receipt-settle-tx
        {::run/id "generated-run"
         :seon.cluster.eval/ordinal 0
         :seon.eval/value "{:introduced 'my.run}"}))
      (is (= ::committed
             (transact-or-refusal
              connection
              (run/append-generated-tx
               {::run/id "generated-run"
                :seon.db.process/id "generated-process"
                :seon.cluster.eval/at t1
                :seon.cluster.eval/ordinal 1
                :seon.cluster.eval/source "(dir 'my.run)"
                :seon.ns/name 'my.agents.generated}))))
      (is (= [[0 :system "(help)"]
              [1 :system "(dir 'my.run)"]]
             (db/q {:query
                    '[:find ?ordinal ?author ?source
                      :where
                      [?run :seon.cluster.run/id "generated-run"]
                      [?form :seon.cluster.eval/run ?run]
                      [?form :seon.cluster.eval/ordinal ?ordinal]
                      [?form :seon.cluster.eval/author ?author]
                      [?form :seon.cluster.eval/source ?source]]
                    :args [@connection]
                    :order-by '[?ordinal :asc]})))
      (is (nil? (::run/plan-digest
                 (run-entity connection "generated-run")))))))

(deftest run-derives-its-opening-database-and-starting-namespace
  (with-model-database
    (fn [connection]
      (db/transact!
       connection
       [{:seon.ns/name 'replay.start}
        {:seon.cluster.agent/id "replay-agent"
         :seon.cluster.agent/namespace
         [:seon.ns/name 'replay.start]}])
      (db/transact!
         connection
         (run/system-run-tx
          @connection
          {:seon.cluster.agent/id "replay-agent"
           ::run/id "replay-run"
           :seon.db.process/id "replay-process"
           ::run/opened-at t0
           ::run/starting-ns [:seon.ns/name 'replay.start]
           ::run/plan-digest "replay-digest"
           ::run/sources
           [{:seon.cluster.eval/source "(def replayed 1)"}]}))
        (let [run (db/pull
                   @connection
                   '[* {:seon.cluster.run/starting-ns [:seon.ns/name]}]
                   [::run/id "replay-run"])]
          (db/transact! connection [{:seon.ns/name 'replay.later}
                                    {::run/id "replay-run"
                                     ::run/opened-at t1}])
          (let [opening (run/opening-db @connection "replay-run")]
            (is (= "replay-run"
                   (::run/id (db/pull opening [::run/id]
                                     [::run/id "replay-run"]))))
            (is (= t0
                   (::run/opened-at
                    (db/pull opening [::run/opened-at]
                             [::run/id "replay-run"]))))
            (is (= 'replay.start
                   (:seon.ns/name (db/pull opening [:seon.ns/name]
                                          [:seon.ns/name 'replay.start]))))
            (is (nil? (db/pull opening [:seon.ns/name]
                              [:seon.ns/name 'replay.later]))))
          (is (:seon.error/kind (run/opening-db @connection "absent-run")))
          (is (= 'replay.start
                 (get-in run [::run/starting-ns :seon.ns/name]))))
      (is (= :system
             (db/q '[:find ?author .
                     :where
                     [?run :seon.cluster.run/id "replay-run"]
                     [?form :seon.cluster.eval/run ?run]
                     [?form :seon.cluster.eval/author ?author]]
                   @connection)))
      (is (= 'replay.start
             (db/q '[:find ?namespace-name .
                     :where
                     [?run :seon.cluster.run/id "replay-run"]
                     [?form :seon.cluster.eval/run ?run]
                     [?form :seon.cluster.eval/ordinal 0]
                     [?form :seon.cluster.eval/ns ?namespace]
                     [?namespace :seon.ns/name ?namespace-name]]
                   @connection)))
      (is (= [{:seon.cluster.eval/ordinal 0
               :seon.cluster.eval/source "(def replayed 1)"
               :seon.ns/name 'replay.start}]
             (db/q '[:find ?ordinal ?source ?namespace-name
                     :keys seon.cluster.eval/ordinal
                           seon.cluster.eval/source
                           seon.ns/name
                     :where
                     [?run :seon.cluster.run/id "replay-run"]
                     [?evaluation :seon.cluster.eval/run ?run]
                     [?evaluation :seon.cluster.eval/ordinal ?ordinal]
                     [?evaluation :seon.cluster.eval/source ?source]
                     [?evaluation :seon.cluster.eval/ns ?namespace]
                     [?namespace :seon.ns/name ?namespace-name]]
                   @connection))
          "the system run records its complete pending evaluation intent"))))

(deftest system-run-refuses-a-concurrent-namespace-reassignment
  (with-model-database
    (fn [connection]
      (db/transact!
       connection
       [{:seon.ns/name 'my.agents.before}
        {:seon.ns/name 'my.agents.after}
        {:seon.cluster.agent/id "moving-agent"
         :seon.cluster.agent/namespace
         [:seon.ns/name 'my.agents.before]}])
      (let [before @connection
            outcome
            (db/transact!
             connection
             (into
              [[:db/add [:seon.cluster.agent/id "moving-agent"]
                :seon.cluster.agent/namespace
                [:seon.ns/name 'my.agents.after]]]
              (run/system-run-tx
               before
               {:seon.cluster.agent/id "moving-agent"
                ::run/id "moving-run"
                :seon.db.process/id "moving-process"
                ::run/opened-at t0
                ::run/starting-ns [:seon.ns/name 'my.agents.before]
                ::run/plan-digest "moving-digest"
                ::run/sources
                [{:seon.cluster.eval/source "(+ 1 1)"}]})))]
        (is (= ::run/starting-namespace-changed (::run/rule outcome)))
        (is (= 'my.agents.before
               (db/q '[:find ?namespace-name .
                       :where
                       [?agent :seon.cluster.agent/id "moving-agent"]
                       [?agent :seon.cluster.agent/namespace ?namespace]
                       [?namespace :seon.ns/name ?namespace-name]]
                     @connection))
            "the refusing transaction also rolls back reassignment")
        (is (nil? (db/pull @connection [:db/id]
                           [::run/id "moving-run"])))))))

(deftest settlement-mints-rows-for-unindexed-call-targets
  ;; Resolvable calls point only at the complete program population.
  ;; Unresolvable mentions settle as errors without inventing graph edges.
  (test-support/with-database
    (fn [connection]
      (db/transact!
       connection
       [{:seon.ns/name 'my.macro-caller}
        {:seon.cluster.agent/id "macro-caller"
         :seon.cluster.agent/namespace [:seon.ns/name 'my.macro-caller]}])
      (db/transact!
       connection
       (run/system-run-tx
        @connection
        {:seon.cluster.agent/id "macro-caller"
         ::run/id "macro-call-run"
         :seon.db.process/id "macro-call-process"
         ::run/opened-at t0
         ::run/starting-ns [:seon.ns/name 'my.macro-caller]
         ::run/plan-digest "macro-call-digest"
         ::run/sources
         [{:seon.cluster.eval/source "(seon.bootstrap/help)"}
          {:seon.cluster.eval/source "(missing.target/nope)"}
          {:seon.cluster.eval/source
           "(require 'unindexed.required)"}]}))
      (let [macro-row
            (db/pull @connection
                     [:db/id :seon.fn/source :seon.fn/macro?]
                     [:seon.fn/sym "seon.bootstrap/help"])]
        (is (:db/id macro-row) "publication supplies the macro identity")
        (is (string? (:seon.fn/source macro-row)))
        (is (true? (:seon.fn/macro? macro-row))))
      (let [result
            (db/transact!
             connection
             (run/receipt-settle-tx
              @connection
              {::run/id "macro-call-run"
               :seon.cluster.eval/ordinal 0
               :seon.eval/value "nil"}))]
        (is (not (:seon.error/kind result))
            "the settlement transaction commits"))
      (let [form (db/pull @connection
                          [{:seon.fn/calls [:seon.fn/sym]}]
                          [:seon.cluster.eval/id
                           (run/receipt-identity "macro-call-run" 0)])]
        (is (empty? (:seon.fn/calls form))
            "an ordinary evaluation does not duplicate program-graph call edges"))
      (let [result
            (db/transact!
             connection
             (run/receipt-settle-tx
              @connection
              {::run/id "macro-call-run"
               :seon.cluster.eval/ordinal 1
               :seon.cluster.eval/error "Could not resolve missing.target/nope"}))]
        (is (not (:seon.error/kind result))
            "the error settlement commits without a dangling lookup ref"))
      (is (empty?
           (db/q '[:find ?target
                   :in $ ?form-id
                   :where
                   [?form :seon.cluster.eval/id ?form-id]
                   [?form :seon.fn/calls ?target]]
                 @connection
                 (run/receipt-identity "macro-call-run" 1)))
          "an unresolvable mention is not a call edge")
      (is (nil? (:db/id (db/pull @connection [:db/id]
                                 [:seon.ns/name 'unindexed.required]))))
      (let [result
            (db/transact!
             connection
             (run/receipt-settle-tx
              @connection
              {::run/id "macro-call-run"
               :seon.cluster.eval/ordinal 2
               :seon.eval/value "nil"
               :seon.program/row
               {:seon.ns/name 'my.macro-caller
                :seon.ns/source "(require 'unindexed.required)"
                :seon.ns/requires
                #{[:seon.ns/name 'unindexed.required]}}}))]
        (is (not (:seon.error/kind result))
            "the required namespace identity precedes its lookup ref"))
      (is (:db/id (db/pull @connection [:db/id]
                           [:seon.ns/name 'unindexed.required]))
          "settlement mints the required namespace identity"))))

(deftest refreshes-only-terminal-system-reads-once
  (test-support/with-database
    (fn [connection]
      (let [namespace-name 'my.refresh
            agent-namespace-name 'my.refresh.agent
            process "refresh-process"
            evidence
            [{:seon.db/source-argument-position 0
              :datahike.read/dependency-plan :all
              :datahike.read/revision
              {:datahike.read/attributes :all
               :datahike.read/cache-eligible? false}}]
            settle!
            (fn [run-id start?]
              (when start?
                (db/transact!
                 connection
                 (run/receipt-start-tx
                  {::run/id run-id
                   :seon.cluster.eval/ordinal 0
                   :seon.cluster.eval/at t0})))
              (db/transact!
               connection
               (run/receipt-settle-tx
                {::run/id run-id
                 :seon.cluster.eval/ordinal 0
                 :seon.eval/value "42"
                 :seon.cluster.eval/read-evidence evidence})))
            close!
            (fn [run-id]
              (db/transact!
               connection
               (run/close-tx {::run/id run-id
                                            ::run/closed-at t1})))]
        (db/transact!
         connection
         [{:seon.ns/name namespace-name}
          {:seon.ns/name agent-namespace-name}
          {:seon.cluster.agent/id "system-refresh"
           :seon.cluster.agent/namespace
           [:seon.ns/name namespace-name]}
          {:seon.cluster.agent/id "agent-refresh"
           :seon.cluster.agent/namespace
           [:seon.ns/name agent-namespace-name]}])
        (db/transact!
         connection
         (run/system-run-tx
          @connection
          {:seon.cluster.agent/id "system-refresh"
           ::run/id "system-source"
           :seon.db.process/id process
           ::run/opened-at t0
           ::run/starting-ns [:seon.ns/name namespace-name]
           ::run/plan-digest "system-source-digest"
           ::run/sources
           [{:seon.cluster.eval/source
             "{:my.refresh/value 42}"}]}))
        (settle! "system-source" false)
        (close! "system-source")
        (let [prior-id (run/receipt-identity "system-source" 0)]
          (is (= evidence
                 (mapv #(dissoc % :db/id)
                       (:seon.cluster.eval/read-evidence
                        (db/pull @connection
                                 '[{:seon.cluster.eval/read-evidence [*]}]
                                 [:seon.cluster.eval/id
                                  (run/receipt-identity
                                   "system-source" 0)])))))
          (is (= ::committed
                 (transact-or-refusal connection (run/refresh-tx prior-id))))
          (let [successor
                (db/pull
                 @connection
                 '[:seon.cluster.eval/source
                   :seon.cluster.eval/author
                   {:seon.cluster.eval/ns [:seon.ns/name]}
                   {:seon.cluster.eval/refreshes
                    [:seon.cluster.eval/id]}]
                 (db/q '[:find ?successor .
                         :in $ ?prior
                         :where
                         [?prior-form :seon.cluster.eval/id ?prior]
                         [?successor :seon.cluster.eval/refreshes
                          ?prior-form]]
                       @connection prior-id))]
            (is (= :system (:seon.cluster.eval/author successor)))
            (is (= "{:my.refresh/value 42}"
                   (:seon.cluster.eval/source successor)))
            (is (= namespace-name
                   (get-in successor
                           [:seon.cluster.eval/ns :seon.ns/name])))
            (is (= prior-id
                   (get-in successor
                           [:seon.cluster.eval/refreshes
                            :seon.cluster.eval/id]))))
          (is (= ::run/refresh-successor-exists
                 (::run/rule
                  (transact-or-refusal connection
                                       (run/refresh-tx prior-id))))))
        (db/transact!
         connection
         (run/open-tx {::run/id "agent-source"
                       ::run/agent
                       [:seon.cluster.agent/id "agent-refresh"]
                       ::run/opened-at t0}))

        (db/transact!
         connection
         (run/plan-tx {::run/id "agent-source"
                              ::run/starting-ns
                       [:seon.ns/name agent-namespace-name]
                       ::run/plan-digest "agent-source-digest"
                       ::run/sources
                       [{:seon.cluster.eval/source "(+ 1 1)"}]}))
        (settle! "agent-source" true)
        (close! "agent-source")
        (is (= ::run/refresh-agent-authored
               (::run/rule
                (transact-or-refusal
                 connection
                 (run/refresh-tx
                  (run/receipt-identity "agent-source" 0))))))))))

(deftest receipt-transitions-preserve-one-terminal-outcome
  (let [start-tx (ns-resolve 'seon.cluster.run 'receipt-start-tx)
        settle-tx (ns-resolve 'seon.cluster.run 'receipt-settle-tx)]
    (is (some? start-tx) "the run owner provides the receipt-start transition")
    (is (some? settle-tx) "the run owner provides the receipt-settle transition")
    (when (and start-tx settle-tx)
      (with-model-database
        (fn [connection]
          (db/transact! connection [{:seon.cluster.agent/id "receipt-agent"}])
          (db/transact! connection
                      (run/open-tx {::run/id "receipts"
                                    ::run/agent
                                    [:seon.cluster.agent/id "receipt-agent"]
                                    ::run/opened-at (at -120000)}))

          (let [start {::run/id "receipts"
                       :seon.cluster.eval/ordinal 0
                       :seon.cluster.eval/at t0}
                settle {::run/id "receipts"
                        :seon.cluster.eval/ordinal 0
                        :seon.eval/value "42"
                        :seon.cluster.eval/triage-edn
                        "{:clojure.error/cause \"triage evidence\"}"}]
            (is (= ::committed
                   (transact-or-refusal connection (start-tx start))))
            (is (not= ::committed
                      (transact-or-refusal connection (start-tx start)))
                "duplicate start is refused")
            (is (not= ::committed
                      (transact-or-refusal
                       connection
                       (settle-tx (dissoc settle
                                          :seon.eval/value))))
                "a settle carrying no terminal fact is refused")
            ;; MISSING IS A TERMINAL FACT LIKE THE REST. An evaluation whose
            ;; value went over the storage bound RAN; reading its absent
            ;; result-edn as "still running" would re-attempt it.
            (is (run/terminal? {:seon.eval/missing :over-bound})
                "the missing marker settles an evaluation on its own")
            (is (= ::committed
                   (transact-or-refusal connection (settle-tx settle))))
            (doseq [terminal [settle
                              (assoc settle
                                     :seon.eval/value
                                     "{:seon.error/kind :x}"
                                     :seon.cluster.eval/error "changed")]]
              (is (not= ::committed
                        (transact-or-refusal connection
                                             (settle-tx terminal)))
                  "a terminal receipt cannot settle again"))
            (let [receipt (db/pull @connection
                                  '[*]
                                  [:seon.cluster.eval/id
                                   (pr-str ["receipts" 0])])]
              (is (= "42" (:seon.eval/value receipt))
                  "the first terminal outcome is preserved")
              (is (nil? (:seon.eval/missing receipt))
                  "a stored value carries no missing marker")
              (is (= "{:clojure.error/cause \"triage evidence\"}"
                     (:seon.cluster.eval/triage-edn receipt)))
              (is (nil? (:seon.cluster.eval/error receipt))
                  "and the refused re-settle changed nothing")))
          ;; the takeover interleaving, re-expressed from the epoch era:
          ;; a running receipt under a DEAD holder is stamped by the
          ;; takeover itself, so the dead pass's late settle refuses by
          ;; PRESENCE — the fence the epoch used to claim to be.
          (let [start {::run/id "receipts"
                       :seon.cluster.eval/ordinal 1
                       :seon.cluster.eval/at t0}]
            (is (= ::committed
                   (transact-or-refusal connection (start-tx start))))
            (is (= ::committed
                   (transact-or-refusal
                    connection
                    (run/recover-tx {::run/id "receipts" ::run/now t1})))
                "a run held by a dead process is taken over")

            (is (= t1 (:seon.cluster.eval/interrupted-at
                       (db/pull @connection '[*]
                               [:seon.cluster.eval/id
                                (pr-str ["receipts" 1])])))
                "the takeover stamped the dead custody's running receipt
                 in the SAME transaction — the intermediate state never
                 exists")
            (is (not= ::committed
                      (transact-or-refusal
                       connection
                       (settle-tx {::run/id "receipts"
                                   :seon.cluster.eval/ordinal 1
                                   :seon.eval/value "1"})))
                "the dead pass's late settle refuses — by presence")
            (is (= ::run/run-closed
                   (::run/rule (transact-or-refusal
                                connection
                                (start-tx start))))
                "and `(run, ordinal)` identity makes re-execution
                 unrepresentable: an ordinal that ever had a receipt
                 refuses forever, across any custody change")))))))

(deftest settlement-refuses-a-divergent-program-change-since-opening
  (with-model-database
    (fn [connection]
      (let [namespace-name 'my.defs.shared
            agent-a "def-agent-a"
            agent-b "def-agent-b"
            run-a "defs-run-a"
            run-b "defs-run-b"
            qualified-id "my.defs.shared/scratch"
            agent-ref (fn [agent-id]
                        [:seon.cluster.agent/id agent-id])
            function-row
            (fn [result]
              ;; `:seon.fn/fn` declares its admission source, so a row that
              ;; omits it is a shape the declared contract forbids.
              {:seon.fn/sym qualified-id
               :seon.schema.admission/source :agent
               :seon.fn/ns [:seon.ns/name namespace-name]
               :seon.fn/source
               (str "(defn ^{:malli/schema [:=> [:cat] :int]} "
                    "scratch [] " result ")")
               :seon.fn/arglists "([])"
               :seon.fn/private? false
               :seon.fn/spec "[:=> [:cat] :int]"})
            start!
            (fn [run-id ordinal]
              (db/transact!
               connection
               (run/receipt-start-tx
                {::run/id run-id
                 :seon.cluster.eval/ordinal ordinal
                 :seon.cluster.eval/at (at ordinal)})))
            settle!
            (fn [run-id ordinal request]
              (transact-or-refusal
               connection
               (run/receipt-settle-tx
                (merge {::run/id run-id
                        :seon.cluster.eval/ordinal ordinal
                        :seon.eval/value "nil"}
                       request))))]
        (db/transact!
         connection
         [{:seon.ns/name namespace-name
           :seon.schema.admission/source :agent}
          {:seon.cluster.agent/id agent-a}
          {:seon.cluster.agent/id agent-b}])
        (doseq [[run-id agent-id] [[run-a agent-a] [run-b agent-b]]]
          (db/transact!
           connection
           (run/open-tx {::run/id run-id
                         ::run/agent (agent-ref agent-id)
                         ::run/opened-at t0})))

        (start! run-a 0)
        (is (= ::committed
               (settle! run-a 0 {:seon.program/row (function-row 1)})))

        (testing "a divergent definition from an older opening basis refuses"
          (start! run-b 1)
          (is (= ::run/program-row-changed-after-open
                 (::run/rule
                  (settle! run-b 1
                           {:seon.program/row (function-row 2)}))))
          (is (= (:seon.fn/source (function-row 1))
                 (:seon.fn/source
                  (db/pull @connection '[*]
                           [:seon.fn/sym qualified-id]))))
          (is (= ::committed
                 (settle! run-b 1
                          {:seon.program/row (function-row 1)}))
              "an identical declaration is an assertion-free success"))))))

;;; ---------------------------------------------------------------------------
;;; The state machine — generated command sequences against a pure model
;;; ---------------------------------------------------------------------------

(def ^:private agent-ids ["a1" "a2"])

(def ^:private run-ids ["r1" "r2" "r3"])

(def ^:private command-gen
  "One generated command. Times are monotonic per sequence position:
  the runner assigns now = (at (* index 60000))."
  (gen/one-of
   [(gen/tuple (gen/return :open)
               (gen/elements run-ids)
               (gen/elements agent-ids))

    (gen/tuple (gen/return :close)
               (gen/elements run-ids)
)
    (gen/tuple (gen/return :plan)
               (gen/elements run-ids)

               (gen/elements ["digest-a" "digest-b"]))
    (gen/tuple (gen/return :receipt-start)
               (gen/elements run-ids)
               (gen/choose 0 3))
    ;; the settled kind names WHICH terminal fact the settle asserts:
    ;; :done → result-edn, :error → error, :interrupted → interrupted-at
    (gen/tuple (gen/return :receipt-settle)
               (gen/elements run-ids)
               (gen/choose 0 3)
               (gen/elements [:done :error :interrupted]))
    (gen/return [:recover])]))

(def ^:private commands-gen
  (gen/one-of [(gen/vector command-gen 1 15)
               (gen/return [[:open "r1" "a1"]
                            [:receipt-start "r1" 0]
                            [:recover]
                            [:receipt-settle "r1" 0 :done]])]))

(defn- model-eligible?
  "The pure oracle: must this command COMMIT against `model`?"
  [model [op & args]]
  (let [run-of #(get-in model [:runs %])]
    (case op
      :open (let [[run-id agent-id] args]
              (and (nil? (run-of run-id))
                   (nil? (get-in model [:pointers agent-id]))))

      :close
      (let [[run-id] args
            {:keys [closed] :as entry} (run-of run-id)]
        (and (some? entry)
             (not closed)))
      :plan (let [[run-id _digest] args
                  {:keys [closed digest] :as entry} (run-of run-id)
                  ordinal (count (filter #(= run-id (:run %))
                                         (vals (:receipts model))))]
              (and (some? entry)
                   (not closed)
                   (nil? (get-in model [:receipts [run-id ordinal]]))
                   (nil? digest)))
      :receipt-start
      (let [[run-id ordinal] args
            entry (run-of run-id)]
        (and (some? entry)
             (not (:closed entry))
             (nil? (get-in model [:receipts [run-id ordinal]]))))
      :receipt-settle
      (let [[run-id ordinal _kind] args
            entry (run-of run-id)
            receipt (get-in model [:receipts [run-id ordinal]])]
        (and (some? entry)
             (not (:closed entry))
             ;; running IS the absence of a settled fact
             (some? receipt)
             (nil? (:settled receipt))))
      :recover true)))

(defn- stamp-running-receipts
  "Settle every running receipt of `run-id` as :interrupted."
  [model run-id]
  (update model :receipts
          #(into {}
                 (map (fn [[[receipt-run ordinal :as k] receipt]]
                        [k (if (and (= receipt-run run-id)
                                    (nil? (:settled receipt)))
                             (assoc receipt :settled :interrupted)
                             receipt)]))
                 %)))

(defn- model-apply
  "Advance the pure model by one COMMITTED command."
  [model [op & args]]
  (case op
    :open (let [[run-id agent-id] args]
            (-> model
                (assoc-in [:runs run-id] {:agent agent-id})
                (assoc-in [:pointers agent-id] run-id)))

    :close (let [[run-id] args
                 agent-id (get-in model [:runs run-id :agent])]
             (-> model
                 (update-in [:runs run-id]
                            #(-> % (assoc :closed true)
                                 (dissoc :process)))
                 (update :pointers dissoc agent-id)))
    :plan (let [[run-id digest] args
                ordinal (count (filter #(= run-id (:run %))
                                       (vals (:receipts model))))]
            (-> model
                (assoc-in [:runs run-id :digest] digest)
                (assoc-in [:receipts [run-id ordinal]]
                          {:id (pr-str [run-id ordinal])
                           :run run-id :ordinal ordinal})))
    :receipt-start (let [[run-id ordinal] args]
                     ;; no :settled key: a started receipt is running
                     ;; by the absence of any terminal fact
                     (assoc-in model [:receipts [run-id ordinal]]
                               {:id (pr-str [run-id ordinal])
                                :run run-id
                                :ordinal ordinal}))
    :receipt-settle (let [[run-id ordinal kind] args]
                      (assoc-in model
                                [:receipts [run-id ordinal] :settled]
                                kind))
    :recover (reduce
                (fn [acc [run-id entry]]
                    (cond
                      (:closed entry) acc
                      :else
                      (let [agent-id (:agent entry)]
                        (-> (stamp-running-receipts acc run-id)
                            (update-in [:runs run-id]
                                       #(-> %
                                            (assoc :closed true)
                                            ;; recovery marks what it cut
                                            (dissoc :process)))
                            (update :pointers dissoc agent-id)))))
                model
                (:runs model))))

(defn- execute!
  "Run one command against the real database. Returns ::committed or
  refusal data."
  [connection [op & args] now]
  (case op
    :open (let [[run-id agent-id] args]
            (transact-or-refusal
             connection
             (run/open-tx {::run/id run-id
                           ::run/agent [:seon.cluster.agent/id agent-id]
                           ::run/opened-at now})))

    :close (let [[run-id] args]
             (transact-or-refusal
              connection
              (run/close-tx
               {::run/id run-id
                ::run/closed-at now})))
    :plan (let [[run-id digest] args]
            (transact-or-refusal
             connection
             (run/plan-tx
              {::run/id run-id
               ::run/starting-ns [:seon.ns/name 'user]
               ::run/plan-digest digest
               ::run/sources
                [{:seon.cluster.eval/source "(+ 1 1)"}]})))
    :receipt-start (let [[run-id ordinal] args]
                     (transact-or-refusal
                      connection
                      (run/receipt-start-tx
                       {::run/id run-id
                        :seon.cluster.eval/ordinal ordinal
                        :seon.cluster.eval/at now})))
    :receipt-settle (let [[run-id ordinal kind] args]
                      (transact-or-refusal
                       connection
                       (run/receipt-settle-tx
                        (merge {::run/id run-id
                                :seon.cluster.eval/ordinal ordinal}
                               ;; the settle IS the terminal fact
                               (case kind
                                 :done {:seon.eval/value "42"}
                                 :error {:seon.cluster.eval/error "boom"}
                                 :interrupted
                                 {:seon.cluster.eval/interrupted-at now})))))
    :recover (let [[live] args]
               (transact-or-refusal
                connection
                (into []
                      (mapcat
                       (fn [run-id]
                         ;; recover-call reads the run and its receipts
                         ;; AT TRANSACTION TIME; a missing or closed
                         ;; run contributes nothing
                         (run/recover-tx
                          {::run/id run-id

                           ::run/now now})))
                      run-ids)))))

(defn- invariants-hold?
  "Durable-fact invariants checked after EVERY command."
  [connection model]
  (let [database-receipts
        ;; the settled kind is DERIVED from which terminal fact is
        ;; present — the same derivation every reader now performs
        (into #{}
              (map (fn [eid]
                     (let [receipt (db/pull @connection '[*] eid)
                           run-id (:seon.cluster.run/id
                                   (db/pull @connection
                                           [:seon.cluster.run/id]
                                           (:db/id
                                            (:seon.cluster.eval/run
                                             receipt))))]
                       (cond-> {:id (:seon.cluster.eval/id receipt)
                                :run run-id
                                :ordinal (:seon.cluster.eval/ordinal receipt)}
                         (:seon.eval/value receipt)
                         (assoc :settled :done)
                         (:seon.cluster.eval/error receipt)
                         (assoc :settled :error)
                         (:seon.cluster.eval/interrupted-at receipt)
                         (assoc :settled :interrupted)))))
              (db/q '[:find [?receipt ...]
                     :where [?receipt :seon.cluster.eval/id _]]
                   @connection))]
    (and
     (every?
      identity
      (for [run-id run-ids
            :let [entry (get-in model [:runs run-id])]
            :when (some? entry)
            :let [entity (run-entity connection run-id)]]
        (and
         ;; the database agrees with the model on custody and closure
         (= (boolean (:closed entry))
            (some? (::run/closed-at entity)))
         ;; the agent pointer exists exactly while its run is open
         (let [agent-id (:agent entry)
               pointer (open-run-id connection agent-id)]
           (if (:closed entry)
             (not= run-id pointer)
             (= run-id pointer)))
         ;; plan digest is write-once
         (= (:digest entry) (::run/plan-digest entity)))))
     (= (set (vals (:receipts model))) database-receipts))))

(deftest transitions-agree-with-the-model
  ;; One FRESH database per trial (and per shrink step): the pure model
  ;; resets every trial, so the world it reasons about must too.
  (let [check
        (tc/quick-check
         60
         (prop/for-all [commands commands-gen]
           (with-model-database
             (fn [connection]
               (db/transact! connection
                           (mapv (fn [id] {:seon.cluster.agent/id id})
                                 agent-ids))
               (loop [commands commands
                      model {:runs {} :pointers {} :receipts {}}
                      index 0]
                 (if (empty? commands)
                   true
                   (let [command (first commands)
                         now (at (* (inc index) 60000))
                         expected (model-eligible? model command)
                         result (execute! connection command now)
                         committed? (= ::committed result)]
                     (cond
                       (not= expected committed?)
                       (do (println "ORACLE DISAGREEMENT"
                                    {:command command :now now
                                     :expected (if expected
                                                 :commit :refuse)
                                     :actual result
                                     :model model})
                           false)

                       :else
                       (let [model' (if committed?
                                      (model-apply model command)
                                      model)]
                         (if (invariants-hold? connection model')
                           (recur (rest commands) model' (inc index))
                           (do (println "INVARIANT VIOLATION"
                                        {:command command :model model'})
                               false))))))))))
         :seed 20260727)]
    (is (true? (:result check))
        (str "state-machine property failed: " (pr-str check)))))

;;; ---------------------------------------------------------------------------
;;; Recovery preserves every terminal receipt byte-for-byte
;;; ---------------------------------------------------------------------------

(def ^:private receipt-state-gen
  ;; which terminal fact (if any) each seeded receipt carries: running
  ;; is the ABSENCE of all three — never a stored label
  (gen/elements [:running :done :error]))

(deftest recovery-preserves-terminal-receipts-exactly
  ;; One FRESH database per trial (the shared-database class, already
  ;; fixed once in the state machine, does not get a second life), and
  ;; ids derive from generated values only — replayable under the seed.
  (let [pull-receipts
        (fn [connection run-id]
          (->> (db/q '[:find [?r ...]
                      :in $ ?run-id
                      :where
                      [?run :seon.cluster.run/id ?run-id]
                      [?r :seon.cluster.eval/run ?run]]
                    (db/db connection) run-id)
               (mapv #(db/pull (db/db connection) '[*] %))))
        pull-terminals
        (fn [connection run-id]
          (->> (pull-receipts connection run-id)
               (filterv #(or (:seon.eval/value %)
                             (:seon.cluster.eval/error %)))
               (sort-by :seon.cluster.eval/ordinal)
               vec))
        check
        (tc/quick-check
         30
         (prop/for-all [states (gen/vector receipt-state-gen 1 5)
                        generated? gen/boolean
                        round gen/nat]
           (with-model-database
             (fn [connection]
               (let [run-id (str "keep-" round "-" (count states)
)
                     agent-id (str "keeper-" run-id)
]
                 (db/transact! connection
                             [{:seon.cluster.agent/id agent-id}])
                 (db/transact! connection
                             (run/open-tx
                              {::run/id run-id
                               ::run/agent [:seon.cluster.agent/id agent-id]
                               ::run/opened-at t0}))

                 (db/transact!
                  connection
                  (vec (map-indexed
                        (fn [ordinal state]
                          (cond-> {:seon.cluster.eval/id
                                   (pr-str [run-id ordinal])
                                   :seon.cluster.eval/run
                                   [:seon.cluster.run/id run-id]
                                   :seon.cluster.eval/ordinal ordinal
                                   :seon.cluster.eval/at t1}
                            (= :done state)
                            (assoc :seon.eval/value
                                   (str ordinal))
                            (= :error state)
                            (assoc :seon.cluster.eval/error
                                   (str "boom-" ordinal))))
                        states)))
                 (when generated?
                   (db/transact! connection
                                 [{::run/id run-id
                                   :seon.cluster.work/situation :generate}]))
                 (let [terminals-before (pull-terminals connection run-id)
                       recovery
                       (run/recover-tx
                        {::run/id run-id

                         ::run/now t2})
                       _ (db/transact! connection recovery)
                       ;; recovery is IDEMPOTENT: running it again from
                       ;; current facts commits nothing new
                       _ (db/transact! connection recovery)
                       entity (run-entity connection run-id)]
                   (and
                    (= (count states) (count (pull-receipts connection run-id)))
                    ;; settled receipts are IDENTICAL, whole entities
                    (= terminals-before (pull-terminals connection run-id))
                    (every? run/terminal?
                            (pull-receipts connection run-id))
                    (= t2 (::run/closed-at entity))
                    (empty? (run/recover-call @connection
                                             {::run/id run-id ::run/now t2}))
                    (nil? (open-run-id connection agent-id))))))))
         :seed 20260727)]
    (is (true? (:result check))
        (str "recovery property failed: " (pr-str check)))))

(deftest recovery-cannot-stamp-a-settled-receipt
  ;; the zombie audit's order-B soft spot, made unrepresentable
  ;; (custody revision, Revision 4): `recover-call` reads the receipt
  ;; AT TRANSACTION TIME, so there is no stale caller basis from which
  ;; a settled receipt could be stamped `interrupted-at`.
  (with-model-database
    (fn [connection]
      (db/transact! connection [{:seon.cluster.agent/id "orderer"}])
      (db/transact! connection
                  (run/open-tx {::run/id "order-b"
                                ::run/agent [:seon.cluster.agent/id "orderer"]
                                ::run/opened-at t0}))

      (db/transact! connection
                  (run/receipt-start-tx {::run/id "order-b"
                                         :seon.cluster.eval/ordinal 0
                                         :seon.cluster.eval/at t0}))
      ;; the settle lands FIRST; recovery then runs against whatever
      ;; the transaction sees — which includes that settle
      (db/transact! connection
                  (run/receipt-settle-tx {::run/id "order-b"
                                          :seon.cluster.eval/ordinal 0
                                          :seon.eval/value "2"}))
      (db/transact! connection
                  (run/recover-tx {::run/id "order-b"

                                   ::run/now t2}))
      (let [receipt (db/pull @connection '[*]
                            [:seon.cluster.eval/id (pr-str ["order-b" 0])])]
        (is (= "2" (:seon.eval/value receipt)))
        (is (nil? (:seon.cluster.eval/interrupted-at receipt))
            "the settled receipt is byte-untouched — no contradiction
             fact can exist"))

      (is (some? (::run/closed-at (run-entity connection "order-b")))
          "the interrupted run is ended")
      (is (nil? (open-run-id connection "orderer"))
          "and the agent pointer is retracted"))))

(deftest recovery-closes-a-turn-with-no-evaluations
  (with-model-database
    (fn [connection]
      (db/transact! connection [{:seon.cluster.agent/id "cut"}])
      (db/transact! connection
                    (run/open-tx {::run/id "cut-run"
                                  ::run/agent [:seon.cluster.agent/id "cut"]
                                  ::run/opened-at t0}))
      (is (= ::committed
             (transact-or-refusal connection
                                  (run/recover-tx {::run/id "cut-run"
                                                   ::run/now t2}))))
      (let [cut (run-entity connection "cut-run")]
        (is (= "cut-run" (::run/id cut)))
        (is (= t2 (::run/closed-at cut)))
        (is (nil? (::run/reply cut)))
        (is (empty? (db/q '[:find [?e ...]
                            :where [?e :seon.cluster.eval/id]] @connection)))
        (is (str/includes? (run/render-ai (assoc cut :seon.db/db @connection))
                           "interrupted before the reply arrived"))))))

;;; ---------------------------------------------------------------------------
;;; Schema admissibility — the model refuses what it must
;;; ---------------------------------------------------------------------------

(deftest run-schema-admits-and-refuses
  (is (seon.schema/valid-candidate-value?
       :seon.cluster.run/run
       {::run/id "r1"
        ::run/agent [:seon.cluster.agent/id "runner"]
        ::run/opened-at t0}))
  (is (not (seon.schema/valid-candidate-value?
            :seon.cluster.run/run
            {::run/agent [:seon.cluster.agent/id "runner"]
             ::run/opened-at t0}))
      "identity is required")
  (is (not (seon.schema/valid-candidate-value?
            :seon.cluster.run/run
            {::run/id ""
             ::run/agent [:seon.cluster.agent/id "runner"]
             ::run/opened-at t0}))
      "a blank identity is refused"))

(deftest open-turn-is-derived-without-an-agent-pointer
  (with-model-database
    (fn [connection]
      (db/transact! connection [{:seon.cluster.agent/id "derived"}])
      (db/transact! connection
                    (run/open-tx {::run/id "derived-turn"
                                  ::run/agent [:seon.cluster.agent/id "derived"]
                                  ::run/opened-at t0}))
      (is (= "derived-turn" (open-run-id connection "derived")))
      (doseq [attribute [:seon.cluster.agent/run :seon.cluster.agent/cluster
                         :seon.cluster.agent/instructions]]
        (is (nil? (seon.schema/schema-definition attribute))))
      (is (= #{:db/id :seon.cluster.agent/id}
             (set (keys (db/pull @connection '[*]
                                 [:seon.cluster.agent/id "derived"])))))
      (db/transact! connection
                    [[:db/add [:seon.cluster.run/id "derived-turn"]
                      :seon.cluster.run/closed-at t1]])
      (is (nil? (open-run-id connection "derived"))))))

;; THE FAULTS UNIT lives on `:seon.error/agent`, whose renderer needs a run to
;; link to; that is why its regression sits beside the run model rather than in
;; the pure `seon.error-test`, which opens no database by design.
(deftest fault-unit-lists-agent-faults-newest-first-with-run-links
  (test-support/with-database
    (fn [connection]
      (db/transact!
       connection
       [{:seon.cluster.agent/id "juno"}
        {:seon.cluster.run/id "run-1"
         :seon.cluster.run/agent [:seon.cluster.agent/id "juno"]
         :seon.cluster.run/opened-at (java.util.Date. 1700000000000)}
        {:seon.error/id "fault-old"
         :seon.error/kind :seon.instrument/contract-violated
         :seon.error/message "older fault"
         :seon.error/at (java.util.Date. 1700000000000)
         :seon.error/agent [:seon.cluster.agent/id "juno"]
         :seon.error/run [:seon.cluster.run/id "run-1"]}
        {:seon.error/id "fault-new"
         :seon.error/kind :seon.instrument/contract-violated
         :seon.error/message "newer fault"
         :seon.error/at (java.util.Date. 1700000060000)
         :seon.error/agent [:seon.cluster.agent/id "juno"]}])
      (let [database @connection
            faults (db/pull-many
                    database '[*]
                    (mapv :db/id
                          (:seon.error/_agent
                           (db/pull database [:seon.error/_agent]
                                    [:seon.cluster.agent/id "juno"]))))
            rendered (error/render-faults-html faults database)]
        (is (= [:section {:class "seon-family-entry seon-error-faults"}
                [:h2 "Faults (2)"]]
               (subvec rendered 0 3)))
        (is (= ["newer fault" "older fault"]
               (mapv #(last (nth (nth % 3) 2)) (subvec rendered 3)))
            "newest first, and each fault keeps the one error card")
        (is (= [":seon.instrument/contract-violated"
                ":seon.instrument/contract-violated"]
               (mapv #(last (nth % 2)) (subvec rendered 3)))
            "each card names the fault's kind")
        (is (str/includes? (pr-str (last (nth rendered 4)))
                           "in run run-1")
            "a fault that names a run links to it by its stable id")
        (is (= 5 (count (nth rendered 3)))
            "and the newest fault, which names no run, has no run line")
        (is (not (str/includes? (pr-str rendered) "seon.error/signature"))
            "a card states the fault, not every stored attribute of it")
        (is (= [:section {:class "seon-family-entry seon-error-faults"}
                [:h2 "Faults (0)"]
                [:p {:class "seon-error-faults-empty"}
                 "No fault is recorded against this agent."]]
               (error/render-faults-html [] database))
            "an agent with no faults renders an empty state, never an error")))))

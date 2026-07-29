(ns seon.cluster.loop-test
  "Sealed acceptance draft for the run loop (N3, C9).

  DRAFT FOR ORCHESTRATOR SEAL (drafted 2026-07-27). Two surfaces, for
  two different reasons:

  1. THE PURE PARTS are tested directly — the committed-attribute set
     (which the wake suite's disjointness property consumes), the
     disposition reader, and the ONE terminal transaction.
  2. THE CRASH WALK is driven as KILL POSITIONS OVER FACTS: each row of
     n3-plan §9.3 is the exact committed state a kill at that point
     leaves, and the assertion is what the loop does next. This is
     deterministic and needs no child JVM, because the rows are defined
     by what is committed — not by how the process died. The live
     `kill -9` falsifier against a real child stays the orchestrator's
     integration proof, in the style of `store_child.clj`; it proves
     the process boundary, and this proves the derivation."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [my.run :as my.run]
            [seon.cluster.loop :as cluster.loop]
            [seon.cluster.wake :as wake]
            [seon.cluster.work :as work]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike])
  (:import [java.util Date]))

;;; ---------------------------------------------------------------------------
;;; The pure parts
;;; ---------------------------------------------------------------------------

(def ^:private now (Date. 1700000000000))
(def ^:private process "process/one")

(deftest the-committed-set-is-computed-and-covers-what-the-loop-writes
  (let [committed (cluster.loop/committed-attributes)]
    (is (set? committed))
    (testing "every family the turn commits is in it"
      (is (some #(= "seon.cluster.run" (namespace %)) committed))
      (is (some #(= "seon.cluster.run.form" (namespace %)) committed))
      (is (some #(= "seon.cluster.eval" (namespace %)) committed))
      (is (some #(= "seon.ai.attempt" (namespace %)) committed)
          "including the model-attempt chain — a durable row per call,
           so a family boot never learned about is caught here rather
           than by a live drive that loses its whole transaction"))
    (testing "and the trigger is NOT — that is the wake, not our write"
      (is (not (contains? committed :seon.cluster.message/to))))))

(deftest a-disposition-is-read-only-when-it-really-is-one
  (is (= (my.run/wait "later") (cluster.loop/disposition (my.run/wait "later"))))
  (is (= (my.run/complete "done")
         (cluster.loop/disposition (my.run/complete "done"))))
  (testing "and anything else is not a disposition"
    (doseq [value [42 nil "done" {:my.run/disposition :invented}
                   {:seon.error/message "boom" :seon.error/kind :x}
                   {:my.run/disposition :completed}]]
      (is (nil? (cluster.loop/disposition value))
          (str "must not read as a disposition: " (pr-str value))))))

(deftest the-terminal-transaction-is-one-transaction
  (let [base {:seon.cluster.run/id "run-1"
              :seon.cluster.run/process "process/one"
              :seon.cluster.run.form/ordinal 0
              :seon.cluster.eval/result-edn "1"}
        without (cluster.loop/terminal-tx base now)
        with (cluster.loop/terminal-tx
              (assoc base :my.run/value (my.run/complete "all done"))
              now)]
    (is (vector? without))
    (is (seq without) "a receipt is always written")
    (testing "the disposition rides in the SAME tx-data, never a second one"
      (is (> (count with) (count without))
          "the completion's facts are in this vector, not a later commit")
      (is (some #(and (sequential? %)
                      (= :db.fn/call (first %)))
                with)
          "and it goes through a transition, so the run's own fence
           applies to the close as much as to the claim")
      (is (= [now]
             (keep (comp :seon.cluster.run/closed-at last)
                   with))
          "the close receives the exact pass instant, not a second clock"))))

;;; ---------------------------------------------------------------------------
;;; THE CLASS-KILLER: what boot installs must cover what the loop writes
;;;
;;; The live drive died in its first second on `Bad entity attribute
;;; :seon.cluster.message/to`. Every suite was green, because every
;;; fixture installs an EXPLICIT attribute list and so bypasses the rule
;;; the boot path actually uses: `canonical-database-attributes`
;;; installs entity-map entries by construction and standalone forms
;;; only when they carry a persistence facet. Four families had no
;;; entity map and therefore installed exactly one attribute each.
;;;
;;; These two tests are the recurring surface for that class. The subset
;;; assertion is cheap and states the invariant; the transact-against-a
;;; -boot-built-database test is the one with teeth, because it uses the
;;; SAME derivation boot uses and then writes the rows the turn writes.
;;; ---------------------------------------------------------------------------

(deftest everything-the-loop-writes-is-installable-by-boot
  (let [installable (set (schema/canonical-database-attributes))]
    (testing "every attribute the loop commits"
      (is (empty? (remove installable (cluster.loop/committed-attributes)))
          "an attribute the loop writes that boot cannot install is a
           run that dies on its first transaction"))
    (testing "and every attribute the wake listens for"
      (is (empty? (remove installable (wake/wake-attributes)))
          "a wake attribute boot cannot install can never be committed,
           so the loop would never wake at all"))))

(deftest a-boot-built-database-takes-every-row-the-turn-writes
  ;; NO explicit attribute list: the schema comes from the same
  ;; derivation the ancestor build uses, so this database is the one the
  ;; live drive boots onto.
  (let [configuration {:store {:backend :memory :id (random-uuid)}
                       :schema-flexibility :write}
        _ (d/create-database configuration)
        connection (d/connect configuration)]
    (try
      (d/transact connection
                  (schema.datahike/malli->datahike-schema
                   (schema/canonical-database-attributes)))
      (testing "the trigger — the exact transact the live drive failed on"
        (is (map? (d/transact connection
                              [{:seon.cluster.agent/id "alice"}
                               {:seon.cluster.message/id "m-live"
                                :seon.cluster.message/to
                                [:seon.cluster.agent/id "alice"]
                                :seon.cluster.message/content "count the widgets"
                                :seon.cluster.message/at now}]))))
      (testing "the run, its agent pointer, and the trigger as tx-meta"
        (is (map? (d/transact
                   connection
                   {:tx-data [{:seon.cluster.run/id "run-live"
                               :seon.cluster.run/agent
                               [:seon.cluster.agent/id "alice"]
                               :seon.cluster.run/opened-at now
                               :seon.cluster.run/process "process/one"
                               :seon.cluster.run/plan-digest
                               (apply str (repeat 64 "a"))}
                              {:seon.cluster.agent/id "alice"
                               :seon.cluster.agent/run
                               [:seon.cluster.run/id "run-live"]}]
                    :tx-meta {:seon.db/trigger
                              [:seon.cluster.message/id "m-live"]}}))))
      (testing "one frozen form"
        (is (map? (d/transact connection
                              [{:seon.cluster.run.form/id "f-0"
                                :seon.cluster.run.form/run
                                [:seon.cluster.run/id "run-live"]
                                :seon.cluster.run.form/ordinal 0
                                :seon.cluster.run.form/source "(+ 1 1)"}]))))
      (testing "a running receipt (no terminal fact) and its settlement"
        (is (map? (d/transact connection
                              [{:seon.cluster.eval/id "e-0"
                                :seon.cluster.eval/run
                                [:seon.cluster.run/id "run-live"]
                                :seon.cluster.eval/ordinal 0
                                :seon.cluster.eval/at now}])))
        (is (map? (d/transact connection
                              [{:seon.cluster.eval/id "e-0"
                                :seon.cluster.eval/result-edn "2"}]))))
      (testing "the model-attempt chain: a failed primary carrying its
      transport evidence, and the backup that points back at it"
        (is (map? (d/transact connection
                              [{:seon.ai.attempt/id "run-live-attempt-0"
                                :seon.ai.attempt/run
                                [:seon.cluster.run/id "run-live"]
                                :seon.ai.attempt/ordinal 0
                                :seon.ai.attempt/at now
                                :seon.ai/endpoint "https://example.invalid/v1"
                                :seon.ai/model "primary-probe"
                                :seon.ai/http-status 503
                                :seon.ai/request-transmitted? false
                                :seon.ai/response-started? false
                                :seon.ai/output-observed? false}])))
        (is (map? (d/transact connection
                              [{:seon.ai.attempt/id "run-live-attempt-1"
                                :seon.ai.attempt/run
                                [:seon.cluster.run/id "run-live"]
                                :seon.ai.attempt/ordinal 1
                                :seon.ai.attempt/at now
                                :seon.ai/endpoint "https://example.invalid/v2"
                                :seon.ai/model "backup-probe"
                                :seon.ai.attempt/delay-ms 0
                                :seon.ai.attempt/failover-from
                                [:seon.ai.attempt/id "run-live-attempt-0"]}]))))
      (testing "and an error receipt, whose result and error both land"
        (is (map? (d/transact connection
                              [{:seon.cluster.eval/id "e-1"
                                :seon.cluster.eval/run
                                [:seon.cluster.run/id "run-live"]
                                :seon.cluster.eval/ordinal 1
                                :seon.cluster.eval/at now
                                :seon.cluster.eval/error "boom"
                                :seon.cluster.eval/result-edn "{:seon.error/kind :x}"}]))))
      (testing "the refs really are refs — a follow, not a string"
        (is (= "alice"
               (d/q '[:find ?id .
                      :where
                      [?m :seon.cluster.message/id "m-live"]
                      [?m :seon.cluster.message/to ?agent]
                      [?agent :seon.cluster.agent/id ?id]]
                    @connection))))
      (finally
        (d/release connection)
        (d/delete-database configuration)))))

;;; ---------------------------------------------------------------------------
;;; The crash walk, as kill positions over facts
;;; ---------------------------------------------------------------------------

(def ^:private attributes
  [:seon.cluster.agent/id :seon.cluster.agent/run
   :seon.cluster.run/id :seon.cluster.run/agent :seon.cluster.run/opened-at
   :seon.cluster.run/closed-at :seon.cluster.run/process
   :seon.cluster.run/plan-digest :seon.cluster.run/forms
   :seon.cluster.run.form/id :seon.cluster.run.form/run
   :seon.cluster.run.form/ordinal :seon.cluster.run.form/source
   :seon.cluster.eval/id :seon.cluster.eval/run :seon.cluster.eval/ordinal
   :seon.cluster.eval/at
   :seon.cluster.eval/interrupted-at :seon.cluster.eval/result-edn
   :seon.cluster.eval/error
   :seon.cluster.message/id :seon.cluster.message/to
   :seon.cluster.message/content :seon.cluster.message/at
   :seon.db/trigger])

(def ^:private request
  "The AGENT-SCOPED request (F2 §3.2): the kill positions below are
  per-agent facts and always were, so the crash walk derives through
  `next-agent-work` with the same rows and the same expected
  situations."
  {:seon.cluster.agent/id "agent-a"
   :seon.cluster.run/process process
   :seon.cluster.work/now now})

(defn- with-database [body]
  (let [configuration {:store {:backend :memory :id (random-uuid)}
                       :schema-flexibility :write}
        _ (d/create-database configuration)
        connection (d/connect configuration)]
    (try
      (d/transact connection (schema.datahike/malli->datahike-schema attributes))
      (d/transact connection
                  [{:seon.cluster.agent/id "agent-a"}
                   {:seon.cluster.message/id "m-1"
                    :seon.cluster.message/to [:seon.cluster.agent/id "agent-a"]
                    :seon.cluster.message/content "go"
                    :seon.cluster.message/at now}])
      (body connection)
      (finally
        (d/release connection)
        (d/delete-database configuration)))))

(defn- commit-run! [connection {:keys [held? planned? receipts closed?]}]
  (d/transact
   connection
   {:tx-data
    (cond-> [(cond-> {:seon.cluster.run/id "run-1"
                      :seon.cluster.run/agent [:seon.cluster.agent/id "agent-a"]
                      :seon.cluster.run/opened-at now}
               held? (assoc :seon.cluster.run/process process)
               planned? (assoc :seon.cluster.run/plan-digest
                               (apply str (repeat 64 "a")))
               closed? (assoc :seon.cluster.run/closed-at now))]
      (not closed?)
      (conj {:seon.cluster.agent/id "agent-a"
             :seon.cluster.agent/run [:seon.cluster.run/id "run-1"]})

      planned?
      (into (map (fn [ordinal]
                   {:seon.cluster.run.form/id (str "f-" ordinal)
                    :seon.cluster.run.form/run [:seon.cluster.run/id "run-1"]
                    :seon.cluster.run.form/ordinal ordinal
                    :seon.cluster.run.form/source "(+ 1 1)"})
                 (range 2)))

      (seq receipts)
      ;; the receipt's state is WHICH terminal fact it carries: :done →
      ;; result-edn, :interrupted → interrupted-at, none → running
      (into (map (fn [[ordinal state]]
                   (cond-> {:seon.cluster.eval/id (str "e-" ordinal)
                            :seon.cluster.eval/run
                            [:seon.cluster.run/id "run-1"]
                            :seon.cluster.eval/ordinal ordinal
                            :seon.cluster.eval/at now}
                     (= :done state)
                     (assoc :seon.cluster.eval/result-edn "2")
                     (= :interrupted state)
                     (assoc :seon.cluster.eval/interrupted-at now)))
                 receipts)))
    :tx-meta {:seon.db/trigger [:seon.cluster.message/id "m-1"]}}))

;;; The F2 sealed suite — kill-positions-per-agent-test, seed 2026072827.
;;; ORACLE: the crash-walk rows 1-10, re-grounded — `next-agent-work`
;;; derives the same expected situation per row under the AGENT-SCOPED
;;; request. Boot-recovery rows are absent here because recovery now
;;; closes interrupted runs before this derivation can see them. The
;;; rows were always per-agent facts; the global pass just asked the
;;; question badly.

(deftest kill-positions-per-agent-test
  (doseq [[row state expected]
          [["1 — trigger only" nil :open]
           ["2-4 — claimed, no plan, custody died" {} nil]
           ["5 — planned, no receipts" {:held? true :planned? true} :resume]
           ["8 — one terminal receipt"
            {:held? true :planned? true :receipts [[0 :done]]} :resume]
           ["9 — every receipt terminal, run open"
            {:held? true :planned? true
             :receipts [[0 :done] [1 :done]]} :close]
           ["10 — closed"
            {:held? true :planned? true :closed? true
             :receipts [[0 :done] [1 :done]]} nil]]]
    (with-database
      (fn [connection]
        (when state (commit-run! connection state))
        (let [derived (work/next-agent-work (d/db connection) request)]
          (testing (str "work derivation row " row)
            (is (= expected (:seon.cluster.work/situation derived)))))))))

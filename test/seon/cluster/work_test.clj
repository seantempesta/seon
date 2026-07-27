(ns seon.cluster.work-test
  "Sealed acceptance draft for the resume derivation (N3, C8).

  DRAFT FOR ORCHESTRATOR SEAL (drafted 2026-07-27). The implementation
  lane makes these green by implementing `seon.cluster.work` ONLY —
  schemas and tests are byte-sealed.

  The acceptance surface is EXHAUSTIVE, not sampled. `next-work`'s
  domain is small and enumerable — the run's custody and plan state
  crossed with the trigger's answeredness — so every state is
  constructed as real committed facts in a real in-memory database and
  checked against an independently written expectation. A random walk
  would visit some of these; enumeration visits all of them, and
  totality is the property that matters most for a derivation that
  replaces a recovery procedure.

  Each state is one fresh database (per-trial isolation by
  construction). The crash-walk rows of n3-plan §9.3 are named in the
  state table rather than tested twice: every row IS one of these
  states, and the comment on each row says which."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.cluster.run :as run]
            [seon.cluster.work :as work]
            [seon.schema]
            [seon.schema.datahike :as schema.datahike])
  (:import [java.util Date]))

;;; ---------------------------------------------------------------------------
;;; A real database, one per state
;;; ---------------------------------------------------------------------------

(def ^:private attributes
  [:seon.cluster.agent/id
   :seon.cluster.agent/run
   :seon.cluster.run/id
   :seon.cluster.run/agent
   :seon.cluster.run/opened-at
   :seon.cluster.run/closed-at
   :seon.cluster.run/process
   :seon.cluster.run/claim-epoch
   :seon.cluster.run/lease-until
   :seon.cluster.run/plan-digest
   :seon.cluster.run/forms
   :seon.cluster.run.form/id
   :seon.cluster.run.form/run
   :seon.cluster.run.form/ordinal
   :seon.cluster.run.form/source
   :seon.cluster.eval/id
   :seon.cluster.eval/run
   :seon.cluster.eval/ordinal
   :seon.cluster.eval/claim-epoch
   :seon.cluster.eval/at
   :seon.cluster.eval/status
   :seon.cluster.eval/result-edn
   :seon.cluster.eval/error
   :seon.cluster.message/id
   :seon.cluster.message/to
   :seon.cluster.message/content
   :seon.cluster.message/at
   :seon.db/trigger])

(def ^:private process "process/one")
(def ^:private other-process "process/two")
(def ^:private agent-id "agent-a")
(def ^:private run-id "run-1")
(def ^:private message-id "message-1")
(def ^:private now (Date. 1700000000000))
(def ^:private digest (apply str (repeat 64 "a")))

(defn- with-database [body]
  (let [configuration {:store {:backend :memory :id (random-uuid)}
                       :schema-flexibility :write}
        _ (d/create-database configuration)
        connection (d/connect configuration)]
    (try
      (d/transact connection
                  (schema.datahike/malli->datahike-schema attributes))
      (d/transact connection [{:seon.cluster.agent/id agent-id}])
      (body connection)
      (finally
        (d/release connection)
        (d/delete-database configuration)))))

(defn- add-trigger!
  "Commit one trigger message for the agent."
  [connection]
  (d/transact connection
              [{:seon.cluster.message/id message-id
                :seon.cluster.message/to [:seon.cluster.agent/id agent-id]
                :seon.cluster.message/content "do the thing"
                :seon.cluster.message/at now}]))

(defn- open-run!
  "Open a run, optionally claimed by `holder`, optionally planned.
  The run-opening transaction carries the trigger as TX-META when one
  exists — the ruling's one deliberate provenance extension, and the
  only thing that makes a trigger answered."
  [connection {:keys [holder planned? triggered?]}]
  (d/transact
   connection
   (cond-> {:tx-data
            (cond-> [(cond-> {:seon.cluster.run/id run-id
                              :seon.cluster.run/agent
                              [:seon.cluster.agent/id agent-id]
                              :seon.cluster.run/opened-at now
                              :seon.cluster.run/claim-epoch 1}
                       holder (assoc :seon.cluster.run/process holder
                                     :seon.cluster.run/lease-until
                                     (Date. (+ (inst-ms now) 60000)))
                       planned? (assoc :seon.cluster.run/plan-digest digest))
                     {:seon.cluster.agent/id agent-id
                      :seon.cluster.agent/run [:seon.cluster.run/id run-id]}]
              planned?
              (into (map (fn [ordinal]
                           {:seon.cluster.run.form/id (str run-id "-" ordinal)
                            :seon.cluster.run.form/run
                            [:seon.cluster.run/id run-id]
                            :seon.cluster.run.form/ordinal ordinal
                            :seon.cluster.run.form/source (str "(+ " ordinal " 1)")})
                         (range 2))))}
     triggered?
     (assoc :tx-meta
            {:seon.db/trigger [:seon.cluster.message/id message-id]}))))

(defn- terminal-receipt!
  [connection ordinal]
  (d/transact connection
              [{:seon.cluster.eval/id (str run-id "-" ordinal)
                :seon.cluster.eval/run [:seon.cluster.run/id run-id]
                :seon.cluster.eval/ordinal ordinal
                :seon.cluster.eval/claim-epoch 1
                :seon.cluster.eval/at now
                :seon.cluster.eval/status :done
                :seon.cluster.eval/result-edn "1"}]))

(defn- close-run! [connection]
  (d/transact connection
              [[:db/add [:seon.cluster.run/id run-id]
                :seon.cluster.run/closed-at now]
               [:db/retract [:seon.cluster.agent/id agent-id]
                :seon.cluster.agent/run [:seon.cluster.run/id run-id]]]))

(def ^:private request
  {:seon.cluster.run/process process
   :seon.cluster.work/now now})

;;; ---------------------------------------------------------------------------
;;; The enumeration
;;; ---------------------------------------------------------------------------

(def ^:private states
  "Every state the derivation must answer, with its crash-walk row."
  [{::label "idle: no trigger, no run"
    ::build (fn [_connection] nil)
    ::expect nil}

   {::label "row 1 — trigger committed, nothing else (also the boot pass)"
    ::build (fn [connection] (add-trigger! connection))
    ::expect {:seon.cluster.work/situation :open
              :seon.cluster.agent/id agent-id
              :seon.cluster.message/id message-id}}

   {::label "claimed here, unplanned — the ONE paid call, not yet made"
    ::build (fn [connection]
              (add-trigger! connection)
              (open-run! connection {:holder process :triggered? true}))
    ::expect {:seon.cluster.work/situation :call
              :seon.cluster.run/id run-id
              :seon.cluster.agent/id agent-id}}

   {::label "row 5 — planned, no receipts: fold from ordinal 0"
    ::build (fn [connection]
              (add-trigger! connection)
              (open-run! connection {:holder process :planned? true
                                     :triggered? true}))
    ::expect {:seon.cluster.work/situation :resume
              :seon.cluster.run/id run-id
              :seon.cluster.agent/id agent-id
              :seon.cluster.run.form/ordinal 0}}

   {::label "row 8 — one terminal receipt: fold from ordinal 1"
    ::build (fn [connection]
              (add-trigger! connection)
              (open-run! connection {:holder process :planned? true
                                     :triggered? true})
              (terminal-receipt! connection 0))
    ::expect {:seon.cluster.work/situation :resume
              :seon.cluster.run/id run-id
              :seon.cluster.agent/id agent-id
              :seon.cluster.run.form/ordinal 1}}

   {::label "row 9 — every receipt terminal, run still open: the fold is
             done, and that is its OWN instruction (seal revision)"
    ::build (fn [connection]
              (add-trigger! connection)
              (open-run! connection {:holder process :planned? true
                                     :triggered? true})
              (terminal-receipt! connection 0)
              (terminal-receipt! connection 1))
    ::expect {:seon.cluster.work/situation :close
              :seon.cluster.run/id run-id
              :seon.cluster.agent/id agent-id}}

   {::label "rows 2-4 — dead custody released, unplanned: NOT work.
             The paid call is lost and nothing re-calls it."
    ::build (fn [connection]
              (add-trigger! connection)
              (open-run! connection {:triggered? true}))
    ::expect nil}

   {::label "planned run whose custody died: resume is still correct —
             committed work continues, and that is not a retry"
    ::build (fn [connection]
              (add-trigger! connection)
              (open-run! connection {:planned? true :triggered? true}))
    ::expect {:seon.cluster.work/situation :resume
              :seon.cluster.run/id run-id
              :seon.cluster.agent/id agent-id
              :seon.cluster.run.form/ordinal 0}}

   {::label "another process holds it: not ours to touch"
    ::build (fn [connection]
              (add-trigger! connection)
              (open-run! connection {:holder other-process :planned? true
                                     :triggered? true}))
    ::expect nil}

   {::label "row 10 — closed run, answered trigger: idle"
    ::build (fn [connection]
              (add-trigger! connection)
              (open-run! connection {:holder process :planned? true
                                     :triggered? true})
              (terminal-receipt! connection 0)
              (terminal-receipt! connection 1)
              (close-run! connection))
    ::expect nil}

   {::label "closed run, and a NEW unanswered trigger: a fresh turn"
    ::build (fn [connection]
              (add-trigger! connection)
              (open-run! connection {:holder process :planned? true
                                     :triggered? true})
              (close-run! connection)
              (d/transact connection
                          [{:seon.cluster.message/id "message-2"
                            :seon.cluster.message/to
                            [:seon.cluster.agent/id agent-id]
                            :seon.cluster.message/content "again"
                            :seon.cluster.message/at now}]))
    ::expect {:seon.cluster.work/situation :open
              :seon.cluster.agent/id agent-id
              :seon.cluster.message/id "message-2"}}

   {::label "a held run outranks a waiting trigger — finishing what is
             started is what makes the busy fence mean anything"
    ::build (fn [connection]
              (add-trigger! connection)
              (open-run! connection {:holder process :planned? true
                                     :triggered? true})
              (d/transact connection
                          [{:seon.cluster.message/id "message-2"
                            :seon.cluster.message/to
                            [:seon.cluster.agent/id agent-id]
                            :seon.cluster.message/content "again"
                            :seon.cluster.message/at now}]))
    ::expect {:seon.cluster.work/situation :resume
              :seon.cluster.run/id run-id
              :seon.cluster.agent/id agent-id
              :seon.cluster.run.form/ordinal 0}}])

(deftest the-derivation-is-total-over-every-state
  (doseq [{::keys [label build expect]} states]
    (with-database
      (fn [connection]
        (build connection)
        (let [db (d/db connection)
              derived (work/next-work db request)]
          (testing label
            (if (nil? expect)
              (is (nil? derived) "must derive idle")
              (is (= (into {} (remove (comp nil? val)) expect)
                     (into {} (remove (comp nil? val)) derived))))
            (testing "and more-work? never disagrees with it"
              (is (= (some? derived) (work/more-work? db request))))
            (testing "and the situation validates against its own schema"
              (when derived
                (is (seon.schema/valid-candidate-value?
                     :seon.cluster.work/next derived))))))))))

;;; ---------------------------------------------------------------------------
;;; The two derivations that are NOT work
;;; ---------------------------------------------------------------------------

(deftest an-unplanned-orphan-run-is-settled-not-resumed
  (with-database
    (fn [connection]
      (add-trigger! connection)
      (open-run! connection {:triggered? true})
      (let [db (d/db connection)]
        (is (nil? (work/next-work db request))
            "it is not work — nothing re-calls a lost paid call")
        (is (= run-id (:seon.cluster.run/id (work/interruption db agent-id)))
            "it IS an interruption the loop must settle")))))

(deftest a-planned-orphan-run-is-work-not-an-interruption
  (with-database
    (fn [connection]
      (add-trigger! connection)
      (open-run! connection {:planned? true :triggered? true})
      (let [db (d/db connection)]
        (is (nil? (work/interruption db agent-id))
            "a planned run continues; it is not wreckage")
        (is (= :resume (:seon.cluster.work/situation
                        (work/next-work db request))))))))

(deftest answeredness-is-transaction-metadata
  (with-database
    (fn [connection]
      (add-trigger! connection)
      (testing "a trigger no run-opening transaction points at is unanswered"
        (is (= [message-id]
               (mapv :seon.cluster.message/id
                     (work/unanswered-triggers (d/db connection) agent-id)))))
      (open-run! connection {:holder process :triggered? true})
      (testing "opening a run against it answers it — with no flag anywhere"
        (is (empty? (work/unanswered-triggers (d/db connection) agent-id))))
      (testing "and a run opened WITHOUT the tx-meta answers nothing"
        (close-run! connection)
        (is (empty? (work/unanswered-triggers (d/db connection) agent-id))
            "the first trigger stays answered")
        (d/transact connection
                    [{:seon.cluster.message/id "message-3"
                      :seon.cluster.message/to
                      [:seon.cluster.agent/id agent-id]
                      :seon.cluster.message/content "third"
                      :seon.cluster.message/at now}])
        (is (= ["message-3"]
               (mapv :seon.cluster.message/id
                     (work/unanswered-triggers (d/db connection) agent-id))))))))

(deftest triggers-come-back-oldest-first
  (with-database
    (fn [connection]
      (doseq [[id at] [["m-2" (Date. 2000)] ["m-1" (Date. 1000)]
                       ["m-3" (Date. 3000)]]]
        (d/transact connection
                    [{:seon.cluster.message/id id
                      :seon.cluster.message/to [:seon.cluster.agent/id agent-id]
                      :seon.cluster.message/content id
                      :seon.cluster.message/at at}]))
      (is (= ["m-1" "m-2" "m-3"]
             (mapv :seon.cluster.message/id
                   (work/unanswered-triggers (d/db connection) agent-id)))
          "commit order is not arrival order; the fact carries the time"))))

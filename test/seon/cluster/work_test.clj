(ns seon.cluster.work-test
  "Sealed acceptance draft for the resume derivation (N3, C8).

  DRAFT FOR ORCHESTRATOR SEAL (drafted 2026-07-27). The implementation
  lane makes these green by implementing `seon.cluster.work` ONLY —
  schemas and tests are byte-sealed.

  The acceptance surface is EXHAUSTIVE, not sampled. `next-agent-work`'s
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
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [seon.db :as db]
            [seon.cluster.work :as work]
            [seon.schema]
            [seon.test-support :as support])
  (:import [java.util Date]))

;;; ---------------------------------------------------------------------------
;;; A real database, one per state
;;; ---------------------------------------------------------------------------

(def ^:private process "process/one")
(def ^:private other-process "process/two")
(def ^:private agent-id "agent-a")
(def ^:private run-id "run-1")
(def ^:private message-id "message-1")
(def ^:private now (Date. 1700000000000))
(def ^:private digest (apply str (repeat 64 "a")))
(def ^:private lint-refusal
  {:seon.error/kind :seon.cluster.loop/lint-rejected
   :seon.error/message "Static analysis rejected this source form."
   :seon.error/data {:seon.fn.analyzer/findings
                     [{:seon.fn.analyzer/level :error}]}})

(defn- with-database [body]
  (support/with-database
   (fn [connection]
      (db/transact! connection [{:seon.cluster.agent/id agent-id}])
      (body connection))))

(defn- add-trigger!
  "Commit one trigger message for the agent."
  [connection]
  (db/transact! connection
              [{:seon.cluster.message/id message-id
                :seon.cluster.message/to [:seon.cluster.agent/id agent-id]
                :seon.cluster.message/content "do the thing"
                :seon.cluster.message/at now}]))

(defn- open-run!
  "Open a run, optionally claimed by `holder`, optionally planned."
  [connection {:keys [holder planned? triggered?]}]
  (db/transact!
   connection
   (cond-> {:tx-data
            (cond-> [(cond-> {:seon.cluster.run/id run-id
                              :seon.cluster.run/agent
                              [:seon.cluster.agent/id agent-id]
                              :seon.cluster.run/opened-at now}
                       triggered?
                       (assoc :seon.cluster.run/trigger
                              [:seon.cluster.message/id message-id])
                       holder (assoc :seon.cluster.run/process holder)
                       true (assoc :seon.cluster.work/situation :call)
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
                         (range 2))))})))

(defn- terminal-receipt!
  ([connection ordinal]
   (terminal-receipt! connection ordinal "1"))
  ([connection ordinal result-edn]
   (db/transact! connection
               [{:seon.cluster.eval/id (str run-id "-" ordinal)
                 :seon.cluster.eval/run [:seon.cluster.run/id run-id]
                 :seon.cluster.eval/ordinal ordinal
                 :seon.cluster.eval/at now
                 ;; the result's presence IS the terminal state
                 :seon.cluster.eval/result-edn result-edn}])))

(defn- close-run! [connection]
  (db/transact! connection
              [[:db/add [:seon.cluster.run/id run-id]
                :seon.cluster.run/closed-at now]
               [:db/retract [:seon.cluster.agent/id agent-id]
                :seon.cluster.agent/run [:seon.cluster.run/id run-id]]]))

(defn- configure-cap!
  [connection limit]
  (db/transact! connection
              [{:seon.config/cluster "work-test"
                :seon.config.run/max-episode-runs limit}]))

(defn- add-outside-trigger!
  [connection id at]
  (db/transact! connection
              [{:seon.cluster.message/id id
                :seon.cluster.message/to [:seon.cluster.agent/id agent-id]
                :seon.cluster.message/content id
                :seon.cluster.message/at at}]))

(defn- closed-run!
  "Commit one complete turn, with each supplied value as a receipt result."
  [connection id trigger-id result-values at]
  (db/transact!
   connection
   {:tx-data
    (into [{:seon.cluster.run/id id
            :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
            :seon.cluster.run/trigger
            [:seon.cluster.message/id trigger-id]
            :seon.cluster.run/opened-at at
            :seon.cluster.run/process process
            :seon.cluster.run/plan-digest digest}
           {:seon.cluster.agent/id agent-id
            :seon.cluster.agent/run [:seon.cluster.run/id id]}]
          (map-indexed
           (fn [ordinal _]
             {:seon.cluster.run.form/id (str id "-form-" ordinal)
              :seon.cluster.run.form/run [:seon.cluster.run/id id]
              :seon.cluster.run.form/ordinal ordinal
              :seon.cluster.run.form/source (str "(+ " ordinal " 1)")})
           result-values))})
  (db/transact!
   connection
   (map-indexed
    (fn [ordinal value]
      {:seon.cluster.eval/id (str id "-receipt-" ordinal)
       :seon.cluster.eval/run [:seon.cluster.run/id id]
       :seon.cluster.eval/ordinal ordinal
       :seon.cluster.eval/at at
       :seon.cluster.eval/result-edn (pr-str value)})
    result-values))
  (db/transact! connection
              [[:db/add [:seon.cluster.run/id id]
                :seon.cluster.run/closed-at at]
               [:db/retract [:seon.cluster.agent/id agent-id]
                :seon.cluster.agent/run [:seon.cluster.run/id id]]]))

(def ^:private request
  "The AGENT-SCOPED request (F2 §3.2). The global one died with the
  central pass; the totality property is unchanged in strength — it
  always was a per-agent question, and now it says so."
  {:seon.cluster.agent/id agent-id
   :seon.cluster.run/process process})

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
              (db/transact! connection
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
              (db/transact! connection
                          [{:seon.cluster.message/id "message-2"
                            :seon.cluster.message/to
                            [:seon.cluster.agent/id agent-id]
                            :seon.cluster.message/content "again"
                            :seon.cluster.message/at now}]))
    ::expect {:seon.cluster.work/situation :resume
              :seon.cluster.run/id run-id
              :seon.cluster.agent/id agent-id
              :seon.cluster.run.form/ordinal 0}}])

(deftest the-request-declares-exactly-the-dependencies-the-derivation-reads
  ;; The class: a required argument no code reads. It cannot be passed
  ;; wrongly, so it can only ever be forgotten — and
  ;; seon.cluster.curate/execute-revision! forgot it, turning every
  ;; session-curation proof into an opaque ::proof-fault. The derivation is
  ;; pure over committed facts and reads no clock, so the request now says
  ;; exactly that, and an unread required key cannot be reintroduced without
  ;; failing here.
  (let [complete {:seon.cluster.agent/id agent-id
                  :seon.cluster.run/process process}]
    (is (true? (seon.schema/valid-candidate-value?
                :seon.cluster.work/agent-request complete))
        "the two facts the derivation reads are the whole request")
    (is (true? (seon.schema/valid-candidate-value?
                :seon.cluster.work/agent-request
                (assoc complete :seon.cluster.work/now (Date.))))
        "a caller still passing a clock is accreted, never refused")
    (doseq [required (keys complete)]
      (is (false? (seon.schema/valid-candidate-value?
                   :seon.cluster.work/agent-request
                   (dissoc complete required)))
          (str required " is genuinely required")))))

(deftest the-derivation-is-total-over-every-state
  (doseq [{::keys [label build expect]} states]
    (with-database
      (fn [connection]
        (build connection)
        (let [db (db/db connection)
              derived (work/next-agent-work db request)]
          (testing label
            (if (nil? expect)
              (is (nil? derived) "must derive idle")
              (is (= (into {} (remove (comp nil? val)) expect)
                     (into {} (remove (comp nil? val)) derived))))
            (testing "and more-agent-work? never disagrees with it"
              (is (= (some? derived) (work/more-agent-work? db request))))
            (testing "and the situation validates against its own schema"
              (when derived
                (is (seon.schema/valid-candidate-value?
                     :seon.cluster.work/next derived))))))))))

(deftest a-generated-run-resumes-then-requests-one-more-form
  (with-database
    (fn [connection]
      (open-run! connection {:holder process})
      (db/transact!
       connection
       [[:db/retract [:seon.cluster.run/id run-id]
         :seon.cluster.work/situation :call]
        [:db/add [:seon.cluster.run/id run-id]
         :seon.cluster.work/situation :generate]
        {:seon.cluster.run.form/id "generated-form-0"
         :seon.cluster.run.form/run [:seon.cluster.run/id run-id]
         :seon.cluster.run.form/ordinal 0
         :seon.cluster.run.form/author :system
         :seon.cluster.run.form/source "(help)"}])
      (is (= {:seon.cluster.work/situation :resume
              :seon.cluster.run/id run-id
              :seon.cluster.agent/id agent-id
              :seon.cluster.run.form/ordinal 0}
             (work/next-agent-work @connection request)))
      (terminal-receipt! connection 0 "{:introduced 'my.run}")
      (let [derived (work/next-agent-work @connection request)]
        (is (= {:seon.cluster.work/situation :generate
                :seon.cluster.run/id run-id
                :seon.cluster.agent/id agent-id}
               derived))
        (is (seon.schema/valid-candidate-value?
             :seon.cluster.work/next derived))))))

(deftest comment-only-input-is-recorded-but-never-becomes-eval-work
  (with-database
    (fn [connection]
      (add-trigger! connection)
      (open-run! connection {:holder process :planned? true
                             :triggered? true})
      (db/transact!
       connection
       [[:db/add [:seon.cluster.run.form/id (str run-id "-0")]
         :seon.cluster.run.form/source "; pure prose"]])
      (is (= {:seon.cluster.work/situation :resume
              :seon.cluster.run/id run-id
              :seon.cluster.agent/id agent-id
              :seon.cluster.run.form/ordinal 1}
             (work/next-agent-work @connection request)))
      (terminal-receipt! connection 1)
      (is (= {:seon.cluster.work/situation :close
              :seon.cluster.run/id run-id
              :seon.cluster.agent/id agent-id}
             (work/next-agent-work @connection request)))
      (is (empty?
           (db/q '[:find [?receipt ...]
                  :in $ ?run-id
                  :where
                  [?run :seon.cluster.run/id ?run-id]
                  [?receipt :seon.cluster.eval/run ?run]
                  [?receipt :seon.cluster.eval/ordinal 0]]
                @connection run-id))))))

(deftest a-lint-refusal-derives-an-immediate-corrective-turn
  (doseq [[label results]
          [["all forms were refused" [lint-refusal lint-refusal]]
           ["one form succeeded and one was refused" [1 lint-refusal]]]]
    (testing label
      (with-database
        (fn [connection]
          (configure-cap! connection 3)
          (add-outside-trigger! connection message-id now)
          (closed-run! connection "refused-run" message-id results now)
          (let [db @connection
                derived (work/next-agent-work db request)]
            (is (empty? (work/unanswered-triggers db agent-id))
                "no external message manufactures the corrective turn")
            (is (= {:seon.cluster.work/situation :open
                    :seon.cluster.agent/id agent-id
                    :seon.cluster.message/id message-id}
                   derived)
                "the existing open situation reuses the original trigger")
            (add-outside-trigger! connection "concurrent-message"
                                  (Date. 1700000000001))
            (is (= message-id
                   (:seon.cluster.message/id
                    (work/next-agent-work @connection request)))
                "immediate correction precedes a concurrent outside trigger")
            (is (true? (work/more-agent-work? db request)))
            (is (seon.schema/valid-candidate-value?
                 :seon.cluster.work/next derived))))))))

(deftest a-clean-correction-retires-the-previous-lint-refusal
  (with-database
    (fn [connection]
      (configure-cap! connection 3)
      (add-outside-trigger! connection message-id now)
      (closed-run! connection "refused-run" message-id [lint-refusal] now)
      (is (= message-id
             (:seon.cluster.message/id
              (work/next-agent-work @connection request))))
      (closed-run! connection "clean-correction" message-id [1]
                   (Date. 1700000000001))
      (is (= 2 (work/episode-runs @connection agent-id)))
      (is (nil? (work/next-agent-work @connection request))
          "only the latest closed turn can derive continuation"))))

(deftest lint-refusal-continuation-stops-at-the-existing-episode-cap
  (with-database
    (fn [connection]
      (configure-cap! connection 3)
      (add-outside-trigger! connection message-id now)
      (doseq [ordinal (range 3)]
        (closed-run! connection
                     (str "refused-run-" ordinal)
                     message-id
                     [lint-refusal]
                     (Date. (+ (.getTime now) ordinal)))
        (is (= (inc ordinal) (work/episode-runs @connection agent-id))
            "reusing an outside trigger does not reset its episode"))
      (is (nil? (work/next-agent-work @connection request))
          "a refusal at the cap does not continue")
      (is (false? (work/more-agent-work? @connection request)))
      (testing "a genuinely new outside trigger still resets the episode"
        (add-outside-trigger! connection "message-2" (Date. 1700000000100))
        (is (= {:seon.cluster.work/situation :open
                :seon.cluster.agent/id agent-id
                :seon.cluster.message/id "message-2"}
               (work/next-agent-work @connection request)))
        (closed-run! connection "outside-reset" "message-2" [1]
                     (Date. 1700000000100))
        (is (= 1 (work/episode-runs @connection agent-id)))
        (is (nil? (work/next-agent-work @connection request))
            "the older refusal cannot resurface after a clean latest turn")))))

;;; ---------------------------------------------------------------------------
;;; The two derivations that are NOT work
;;; ---------------------------------------------------------------------------

;;; ---------------------------------------------------------------------------
;;; The F2 sealed suite — situation-totality-property, seed 2026072829
;;; ---------------------------------------------------------------------------

(deftest situation-totality-property
  ;; ORACLE, re-sealed agent-scoped after the central pass died: over
  ;; GENERATED run/receipt/trigger states, `next-agent-work` is TOTAL —
  ;; it returns only the four situations or nil, `more-agent-work?`
  ;; never disagrees with it, and `:resume` always carries the FIRST
  ;; unsettled ordinal.
  ;;
  ;; The enumeration above visits every state by construction; this
  ;; visits combinations the table does not name — receipts landing out
  ;; of order, a claimed-and-closed run, a trigger arriving after the
  ;; run that would answer it. Enumeration proves the table; generation
  ;; guards the CLASS, which is what makes this the one choke-point
  ;; regression for the situation enum.
  (let [check
        (tc/quick-check
         200
         (prop/for-all
          [holder (gen/elements [nil process other-process])
           planned? gen/boolean
           closed? gen/boolean
           triggered? gen/boolean
           trigger-first? gen/boolean
           lint-ordinal (gen/elements [nil 0 1])
           receipts (gen/vector-distinct (gen/elements [0 1]) {:max-elements 2})]
          (with-database
            (fn [connection]
              (configure-cap! connection 3)
              (when trigger-first? (add-trigger! connection))
              (open-run! connection {:holder holder
                                     :planned? planned?
                                     :triggered? (and triggered?
                                                      trigger-first?)})
              (when (and triggered? (not trigger-first?))
                (add-trigger! connection))
              (doseq [ordinal receipts]
                (terminal-receipt!
                 connection ordinal
                 (if (= ordinal lint-ordinal)
                   (pr-str lint-refusal)
                   "1")))
              (when closed? (close-run! connection))
              (let [db (db/db connection)
                    derived (work/next-agent-work db request)
                    situation (:seon.cluster.work/situation derived)
                    answered-closed? (and closed? triggered? trigger-first?)
                    refused? (contains? (set receipts) lint-ordinal)]
                (and
                 ;; TOTAL: only the four situations, or idle
                 (contains? #{:resume :call :open :close nil} situation)
                 ;; the rewake predicate never drifts from the derivation
                 (= (some? derived) (work/more-agent-work? db request))
                 ;; a derived situation always validates its own schema
                 (or (nil? derived)
                     (seon.schema/valid-candidate-value?
                      :seon.cluster.work/next derived))
                 ;; An answered closed turn is idle unless a committed
                 ;; receipt contains a lint refusal; that presence derives
                 ;; the existing corrective :open situation.
                 (or (not answered-closed?)
                     (= (if refused? :open nil) situation))
                 ;; :resume carries the FIRST ordinal with no terminal
                 ;; receipt — never one already settled, which is what
                 ;; "nothing re-executes" means in the derivation
                 (or (not= :resume situation)
                     (let [ordinal (:seon.cluster.run.form/ordinal derived)]
                       (and (not (contains? (set receipts) ordinal))
                            (= ordinal
                               (first (remove (set receipts)
                                              (range 2))))))))))))
         :seed 2026072829)]
    (is (true? (:result check))
        (str "situation totality failed: " (pr-str check)))))

(deftest an-unplanned-orphan-run-is-settled-not-resumed
  (with-database
    (fn [connection]
      (add-trigger! connection)
      (open-run! connection {:triggered? true})
      (let [db (db/db connection)]
        (is (nil? (work/next-agent-work db request))
            "it is not work — nothing re-calls a lost paid call")
        (is (= run-id (:seon.cluster.run/id (work/interruption db agent-id)))
            "it IS an interruption the turn proc must settle")))))

(deftest a-planned-orphan-run-is-interruption-not-work
  (with-database
    (fn [connection]
      (add-trigger! connection)
      (open-run! connection {:planned? true :triggered? true})
      (let [db (db/db connection)]
        (is (nil? (work/next-agent-work db request))
            "an unheld plan never cold resumes")
        (is (= run-id (:seon.cluster.run/id
                       (work/interruption db agent-id)))
            "it is wreckage to bury, not work to continue")))))

(deftest answeredness-is-a-recorded-run-ref
  (with-database
    (fn [connection]
      (add-trigger! connection)
      (testing "a trigger no run points at is unanswered"
        (is (= [message-id]
               (mapv :seon.cluster.message/id
                     (work/unanswered-triggers (db/db connection) agent-id)))))
      (open-run! connection {:holder process :triggered? true})
      (testing "opening a run against it answers it — with no flag anywhere"
        (is (empty? (work/unanswered-triggers (db/db connection) agent-id))))
      (testing "and a run opened without a trigger ref answers nothing"
        (close-run! connection)
        (is (empty? (work/unanswered-triggers (db/db connection) agent-id))
            "the first trigger stays answered")
        (db/transact! connection
                    [{:seon.cluster.message/id "message-3"
                      :seon.cluster.message/to
                      [:seon.cluster.agent/id agent-id]
                      :seon.cluster.message/content "third"
                      :seon.cluster.message/at now}])
        (is (= ["message-3"]
               (mapv :seon.cluster.message/id
                     (work/unanswered-triggers (db/db connection) agent-id))))))))

(deftest triggers-come-back-oldest-first
  (with-database
    (fn [connection]
      (doseq [[id at] [["m-2" (Date. 2000)] ["m-1" (Date. 1000)]
                       ["m-3" (Date. 3000)]]]
        (db/transact! connection
                    [{:seon.cluster.message/id id
                      :seon.cluster.message/to [:seon.cluster.agent/id agent-id]
                      :seon.cluster.message/content id
                      :seon.cluster.message/at at}]))
      (is (= ["m-1" "m-2" "m-3"]
             (mapv :seon.cluster.message/id
                   (work/unanswered-triggers (db/db connection) agent-id)))
          "commit order is not arrival order; the fact carries the time"))))

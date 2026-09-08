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

(defn- model-attempt
  "The row that makes a turn an ANSWERING turn.

  A wake is answered only by a turn whose reply came from a model
  attempt, and an attempt row carrying no `:seon.ai.attempt/error` IS
  that success. Every fixture turn below is a model turn, so each one
  writes this beside the run exactly as `record-attempt!` does."
  [run-id at]
  {:seon.ai.attempt/id (str run-id "-attempt-0")
   :seon.ai.attempt/run [:seon.cluster.run/id run-id]
   :seon.ai.attempt/ordinal 0
   :seon.ai.attempt/at at
   :seon.ai/endpoint "https://fixture.invalid/v1/chat"
   :seon.ai/model "fixture-model"
   :seon.ai.attempt/settings-edn "{}"})

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
                      :seon.cluster.agent/run [:seon.cluster.run/id run-id]}
                     (model-attempt run-id now)]
              planned?
              (into (map (fn [ordinal]
                           {:seon.cluster.eval/id (str run-id "-" ordinal)
                            :seon.cluster.eval/run
                            [:seon.cluster.run/id run-id]
                            :seon.cluster.eval/ordinal ordinal
                            :seon.cluster.eval/source (str "(+ " ordinal " 1)")})
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
            :seon.cluster.agent/run [:seon.cluster.run/id id]}
           (model-attempt id at)]
          (map-indexed
           (fn [ordinal _]
             {:seon.cluster.eval/id (str id "-" ordinal)
              :seon.cluster.eval/run [:seon.cluster.run/id id]
              :seon.cluster.eval/ordinal ordinal
              :seon.cluster.eval/source (str "(+ " ordinal " 1)")})
           result-values))})
  ;; ONE ENTITY PER (run, ordinal): the terminal fact accretes onto the
  ;; evaluation the freeze minted, under the same identity.
  ;; `vec`, because `seon.db/transact!` declares transaction data and a
  ;; lazy seq is not it — a fixture that hands one gets a typed refusal
  ;; the test then reads as a hang in whatever it asserts next.
  (db/transact!
   connection
   (vec
    (map-indexed
     (fn [ordinal value]
       {:seon.cluster.eval/id (str id "-" ordinal)
        :seon.cluster.eval/run [:seon.cluster.run/id id]
        :seon.cluster.eval/ordinal ordinal
        :seon.cluster.eval/at at
        :seon.cluster.eval/result-edn (pr-str value)})
     result-values)))
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
              :seon.cluster.eval/ordinal 0}}

   {::label "row 8 — one terminal receipt: fold from ordinal 1"
    ::build (fn [connection]
              (add-trigger! connection)
              (open-run! connection {:holder process :planned? true
                                     :triggered? true})
              (terminal-receipt! connection 0))
    ::expect {:seon.cluster.work/situation :resume
              :seon.cluster.run/id run-id
              :seon.cluster.agent/id agent-id
              :seon.cluster.eval/ordinal 1}}

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
              :seon.cluster.eval/ordinal 0}}])

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
        ;; EVERY ROW CONFIGURES THE TURN DIAL. The bound is fail-closed on
        ;; an absent dial — with no `:seon.config.run/max-episode-runs`
        ;; fact, no wake opens a turn at all, which is the shipped
        ;; configuration's business and not this table's subject. The
        ;; capped rows below set their own smaller dial over this one.
        (configure-cap! connection 100)
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
        {:seon.cluster.eval/id (str run-id "-0")
         :seon.cluster.eval/run [:seon.cluster.run/id run-id]
         :seon.cluster.eval/ordinal 0
         :seon.cluster.eval/author :system
         :seon.cluster.eval/source "(help)"}])
      (is (= {:seon.cluster.work/situation :resume
              :seon.cluster.run/id run-id
              :seon.cluster.agent/id agent-id
              :seon.cluster.eval/ordinal 0}
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
       [[:db/add [:seon.cluster.eval/id (str run-id "-0")]
         :seon.cluster.eval/source "; pure prose"]])
      (is (= {:seon.cluster.work/situation :resume
              :seon.cluster.run/id run-id
              :seon.cluster.agent/id agent-id
              :seon.cluster.eval/ordinal 1}
             (work/next-agent-work @connection request)))
      (terminal-receipt! connection 1)
      (is (= {:seon.cluster.work/situation :close
              :seon.cluster.run/id run-id
              :seon.cluster.agent/id agent-id}
             (work/next-agent-work @connection request)))
      ;; ONE ENTITY PER (run, ordinal): the comment-only ordinal HAS an
      ;; evaluation entity — that is the durable input — and it never
      ;; acquires a terminal fact, which is what "never becomes work" means.
      (is (empty?
           (db/q '[:find [?evaluation ...]
                  :in $ ?run-id
                  :where
                  [?run :seon.cluster.run/id ?run-id]
                  [?evaluation :seon.cluster.eval/run ?run]
                  [?evaluation :seon.cluster.eval/ordinal 0]
                  (or [?evaluation :seon.cluster.eval/result-edn _]
                      [?evaluation :seon.cluster.eval/error _]
                      [?evaluation :seon.cluster.eval/interrupted-at _])]
                @connection run-id))))))

(deftest a-lint-refusal-is-terminal-until-a-new-trigger-arrives
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
                "the refused run answered its external trigger")
            (is (nil? derived)
                "a refusal does not self-wake a corrective turn")
            (is (false? (work/more-agent-work? db request)))
            (add-outside-trigger! connection "concurrent-message"
                                  (Date. 1700000000001))
            (let [next-trigger (work/next-agent-work @connection request)]
              (is (= {:seon.cluster.work/situation :open
                      :seon.cluster.agent/id agent-id
                      :seon.cluster.message/id "concurrent-message"}
                     next-trigger)
                  "only a new outside trigger starts another turn")
              (is (seon.schema/valid-candidate-value?
                   :seon.cluster.work/next next-trigger)))))))))

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
                    answered-closed? (and closed? triggered? trigger-first?)]
                (and
                 ;; TOTAL: only the four situations, or idle
                 (contains? #{:resume :call :open :close nil} situation)
                 ;; the rewake predicate never drifts from the derivation
                 (= (some? derived) (work/more-agent-work? db request))
                 ;; a derived situation always validates its own schema
                 (or (nil? derived)
                     (seon.schema/valid-candidate-value?
                      :seon.cluster.work/next derived))
                 ;; Every answered closed turn is idle. Receipt content cannot
                 ;; manufacture a new trigger or corrective turn.
                 (or (not answered-closed?)
                     (nil? situation))
                 ;; :resume carries the FIRST ordinal with no terminal
                 ;; receipt — never one already settled, which is what
                 ;; "nothing re-executes" means in the derivation
                 (or (not= :resume situation)
                     (let [ordinal (:seon.cluster.eval/ordinal derived)]
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

(deftest answeredness-is-the-turns-own-transaction
  ;; THE CLASS: answeredness used to be a stored reference from the run
  ;; to the one message it answered. It is now the turn's own `:t`, and
  ;; nothing is stored — no claim, no flag, and no way for a turn to
  ;; answer a wake its context could not have contained. One connection
  ;; throughout, so every verdict is against real committed facts.
  (with-database
    (fn [connection]
      (add-trigger! connection)
      (testing "a wake newer than every turn is unanswered"
        (is (= [message-id]
               (mapv :seon.cluster.message/id
                     (work/unanswered-triggers (db/db connection) agent-id))))
        (is (= 0 (work/latest-answering-turn-t (db/db connection) agent-id))
            "no turn, no basis"))
      (let [wake-t (:seon.wake/t
                    (first (work/unanswered-wakes
                            (db/db connection) agent-id {})))]
        (open-run! connection {:holder process})
        (testing "opening a turn answers it — the turn's own transaction
                  is the basis, and no reference was written"
          (let [database (db/db connection)]
            (is (empty? (work/unanswered-triggers database agent-id)))
            (is (< wake-t (work/latest-answering-turn-t database agent-id))
                "the wake arrived before the turn that answered it")
            (is (nil? (:seon.cluster.run/trigger
                       (db/pull database [:seon.cluster.run/trigger]
                                [:seon.cluster.run/id run-id])))
                "and no run attribute records the answer"))))
      (testing "a wake asserted after the turn is unanswered again"
        (close-run! connection)
        (is (empty? (work/unanswered-triggers (db/db connection) agent-id))
            "the first wake stays answered")
        (db/transact! connection
                    [{:seon.cluster.message/id "message-3"
                      :seon.cluster.message/to
                      [:seon.cluster.agent/id agent-id]
                      :seon.cluster.message/content "third"
                      :seon.cluster.message/at now}])
        (is (= ["message-3"]
               (mapv :seon.cluster.message/id
                     (work/unanswered-triggers (db/db connection) agent-id))))))))

(deftest only-a-turn-whose-reply-came-from-a-model-attempt-answers
  ;; THE CLASS, measured live before it was fixed: a turn that never
  ;; showed the wakes to a model still consumed them, and nothing said
  ;; so — no refusal, no fault, no log line. Three turns produce the
  ;; three shapes, all with a `:t` after the wake:
  ;;
  ;;   no attempts at all  — a source submission, which also STORES a
  ;;                         reply, so a reply-join would admit it;
  ;;   a failed attempt    — the provider never answered;
  ;;   a successful one    — the model saw the context.
  ;;
  ;; Only the third answers. The turn bound is what keeps the reopening
  ;; finite; it counts turns TAKEN, so all three spend it.
  (with-database
    (fn [connection]
      (configure-cap! connection 100)
      (add-trigger! connection)
      (testing "a turn with no attempts — a source submission — answers nothing"
        (db/transact!
         connection
         [{:seon.cluster.run/id "source-run"
           :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
           :seon.cluster.run/opened-at now
           ;; a source submission stores the submitted text as the reply
           :seon.cluster.run/reply "(+ 1 1)"
           :seon.cluster.run/closed-at now}])
        (is (= [message-id]
               (mapv :seon.cluster.message/id
                     (work/unanswered-triggers (db/db connection) agent-id)))
            "the message it had nothing to do with is still unanswered")
        (is (zero? (work/latest-answering-turn-t (db/db connection)
                                                 agent-id))))
      (testing "a turn whose only attempt failed answers nothing"
        (db/transact!
         connection
         [{:seon.error/id "provider-failure"
           :seon.error/kind :seon.ai/no-credential
           :seon.error/message "no credential"
           :seon.error/at now}
          {:seon.cluster.run/id "failed-run"
           :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
           :seon.cluster.run/opened-at now
           :seon.cluster.run/closed-at now}
          (assoc (model-attempt "failed-run" now)
                 :seon.ai.attempt/error [:seon.error/id "provider-failure"])])
        (is (= [message-id]
               (mapv :seon.cluster.message/id
                     (work/unanswered-triggers (db/db connection) agent-id)))
            "the wake was never shown to a model")
        (is (zero? (work/latest-answering-turn-t (db/db connection)
                                                 agent-id))))
      (testing "and the wakes still open the next turn"
        (is (= :open
               (:seon.cluster.work/situation
                (work/next-agent-work (db/db connection) request)))))
      (testing "a turn whose attempt succeeded answers"
        (open-run! connection {:holder process})
        (let [database (db/db connection)]
          (is (empty? (work/unanswered-triggers database agent-id)))
          (is (pos? (work/latest-answering-turn-t database agent-id))))))))

(deftest two-wakes-in-one-transaction-are-one-turn
  ;; THE CLASS: selecting ONE unanswered item per turn paid the model
  ;; twice for a context that already contained both. Measured live on
  ;; the fixture that ships with this program: three messages, two of
  ;; them in one commit, three paid turns. A turn's `:t` answers every
  ;; wake at or before it, so the second call cannot happen.
  (with-database
    (fn [connection]
      (configure-cap! connection 100)
      (db/transact!
       connection
       [{:seon.cluster.message/id "pair-a"
         :seon.cluster.message/to [:seon.cluster.agent/id agent-id]
         :seon.cluster.message/content "a"
         :seon.cluster.message/at now}
        {:seon.cluster.message/id "pair-b"
         :seon.cluster.message/to [:seon.cluster.agent/id agent-id]
         :seon.cluster.message/content "b"
         :seon.cluster.message/at now}])
      (let [wakes (work/unanswered-wakes (db/db connection) agent-id {})]
        (is (= 2 (count wakes)) "both are unanswered")
        (is (apply = (map :seon.wake/t wakes))
            "and they share one transaction, which is the whole point"))
      (is (= :open (:seon.cluster.work/situation
                    (work/next-agent-work (db/db connection)
                                          {:seon.cluster.agent/id agent-id
                                           :seon.cluster.run/process process})))
          "one turn opens")
      (open-run! connection {:holder process})
      (close-run! connection)
      (let [database (db/db connection)]
        (is (empty? (work/unanswered-wakes database agent-id {}))
            "and that ONE turn answered both")
        (is (nil? (work/next-agent-work database
                                        {:seon.cluster.agent/id agent-id
                                         :seon.cluster.run/process process}))
            "no second paid call")))))

(deftest a-wake-arriving-mid-turn-opens-the-next-turn
  ;; THE OTHER HALF of the `:t` rule, and the reason it is `>` and not
  ;; `>=`: a wake whose transaction is newer than the open turn's was
  ;; never in that turn's context, so it must not be swallowed by it.
  (with-database
    (fn [connection]
      (configure-cap! connection 100)
      (add-trigger! connection)
      (open-run! connection {:holder process})
      (db/transact!
       connection
       [{:seon.cluster.message/id "mid-turn"
         :seon.cluster.message/to [:seon.cluster.agent/id agent-id]
         :seon.cluster.message/content "arrived while the turn was open"
         :seon.cluster.message/at (Date. 1700000000002)}])
      (is (= ["mid-turn"]
             (mapv :seon.cluster.message/id
                   (work/unanswered-triggers (db/db connection) agent-id)))
          "it is newer than the open turn's own transaction")
      (close-run! connection)
      (is (= {:seon.cluster.work/situation :open
              :seon.cluster.agent/id agent-id
              :seon.cluster.message/id "mid-turn"}
             (work/next-agent-work (db/db connection)
                                   {:seon.cluster.agent/id agent-id
                                    :seon.cluster.run/process process}))
          "and it opens the next turn"))))

(deftest the-turn-bound-is-turns-since-the-latest-outside-wake
  ;; THE CLASS: an agent whose own activity wakes it must not be able to
  ;; refill its own budget. A fault routed to a steward and an agent-sent
  ;; message are declared INSIDE wakes, so they spend the bound and never
  ;; reset it; a human message is outside and does.
  (with-database
    (fn [connection]
      (configure-cap! connection 2)
      (add-outside-trigger! connection "human-1" now)
      (is (zero? (work/episode-runs (db/db connection) agent-id))
          "an outside wake arrived and no turn has answered it")
      (closed-run! connection "turn-1" "human-1" [1] now)
      (is (= 1 (work/episode-runs (db/db connection) agent-id)))
      (closed-run! connection "turn-2" "human-1" [1] now)
      ;; an agent-sent message, asserted AFTER the second turn: an
      ;; INSIDE wake by its own declaration, unanswered by `:t`
      (db/transact!
       connection
       [{:seon.cluster.message/id "self-1"
         :seon.cluster.message/to [:seon.cluster.agent/id agent-id]
         :seon.cluster.message/from [:seon.cluster.agent/id agent-id]
         :seon.cluster.message/content "keep going"
         :seon.cluster.message/at (Date. 1700000000002)}])
      (let [database (db/db connection)]
        (is (= 2 (work/episode-runs database agent-id))
            "the inside wake did not refill the bound")
        (is (nil? (work/next-agent-work database
                                        {:seon.cluster.agent/id agent-id
                                         :seon.cluster.run/process process}))
            "AT the cap an inside wake opens nothing — this is what stops
             a fault about an agent's own code looping forever")
        (is (= ["self-1"]
               (mapv :seon.cluster.message/id
                     (work/deferred-triggers database agent-id)))
            "it is deferred, and the deferral is a derivation with
             nothing stored"))
      (db/transact!
       connection
       [{:seon.cluster.message/id "human-2"
         :seon.cluster.message/to [:seon.cluster.agent/id agent-id]
         :seon.cluster.message/content "new instruction"
         :seon.cluster.message/at (Date. 1700000000003)}])
      (let [database (db/db connection)]
        (is (zero? (work/episode-runs database agent-id))
            "an outside wake ARRIVING is the reset; there is no reset code")
        (is (= :open (:seon.cluster.work/situation
                      (work/next-agent-work
                       database {:seon.cluster.agent/id agent-id
                                 :seon.cluster.run/process process})))
            "and the agent hears it")))))

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

(ns seon.error-test
  "Error identity, normalization and recording behavior."
  (:require [seon.error.refusal]
            [clojure.core.async.flow :as-alias flow]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datahike.db]
            [seon.config :as config]
            [seon.error :as error]
            [seon.error.refusal :as error.refusal]
            [seon.instrument :as instrument]
            [seon.problems]
            [seon.cluster.status]
            [seon.render.transcript]
            [malli.core]
            [seon.id]
            [sci.core]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.schema.internal :as schema.internal]
            [malli.registry :as mr]
            [seon.schema.datahike :as schema.datahike]
            [seon.sci.eval :as sci.eval]
            [seon.test-support :as test-support]
            [seon.db :as db]
            [seon.program :as program]))

(declare commit-request with-db)

(deftest recording-ignores-transient-inline-members-when-finding-components
  (test-support/with-database
   (fn [connection]
     (let [projection (schema/handed-projection)
           value {:seon.error/at (java.util.Date.)
                  :seon.error/layer :seon.error-test/recording
                  :seon.error/operation 'seon.error-test/recording
                  :seon.error/message "An observation with an in-flight object."
                  :seon.error/offending (Object.)}
           restored (#'error/stored-observation projection value)
           recording (error/recording (db/db connection) (commit-request value {}))
           report (test-support/transacted! connection (:seon.db/tx-data recording))]
       (is (identical? (:seon.error/offending value) (:seon.error/offending restored)))
       (is (:db-after report))
       (is (:seon.error/signature
            (db/pull (:db-after report) '[*] (:seon.error/ref recording))))))))

(deftest entity-acquisition-observations-have-no-program-digest-promise
  (test-support/with-database
   (fn [connection]
     (doseq [lookup [[:seon.fn/sym 'seon.id/id] [:seon.ns/name 'seon.id]]]
      (let [database (db/db connection)
           projection (schema/handed-projection)
           entity (db/pull database '[*] lookup)
           observation (test-support/refusal-data
                        #(@#'sci.eval/acquisition-refusal entity
                          (ex-info "entity installation failed" {:seon.error-test/cause 42})))]
       (is (= (second lookup) (:seon.sci.eval/row-symbol observation)))
       (is (= "entity installation failed"
              (get-in observation [:seon.sci.eval/acquisition-observation
                                   :seon.error.evidence/value])))
       (is (not (contains? observation :seon.sci.eval/requested-program)))
       (is (schema/valid-candidate-value? projection :seon.sci.eval/row-acquisition-error observation))
       (is (not (schema/valid-candidate-value? projection :seon.sci.eval/acquisition-error observation)))
       (when (:seon.sci.eval/row-symbol observation)
         ;; the producer names its declared schema (required since a86e93e21);
         ;; the writer keeps that schema's distinguishing members
         (let [recording (error/recording database
                                          (commit-request observation
                                                          {:seon.error/declared-schema
                                                           :seon.sci.eval/row-acquisition-error}))
               report (test-support/transacted! connection (:seon.db/tx-data recording))
               root (db/pull (:db-after report) (error/observation-selector projection)
                             (:seon.error/ref recording))
               stored (error/latest-fact root)]
           (is (schema/valid-candidate-value? projection :seon.sci.eval/row-acquisition-error stored))
           (is (= (:seon.sci.eval/acquisition-observation observation)
                  (dissoc (:seon.sci.eval/acquisition-observation stored) :db/id)))
           (is (= (second lookup) (:seon.sci.eval/row-symbol stored)))
           (is (not (contains? stored :seon.sci.eval/requested-program))))))))))

(deftest error-identity-and-occurrences-are-owned-by-the-writer
  (test-support/with-database
    (fn [connection]
      (let [failure (doto (ex-info "same error" {})
                      (.setStackTrace (into-array StackTraceElement
                                                 [(StackTraceElement. "my.error_graph$raise" "invokeStatic" "error_graph.clj" 42)])))
            at (java.util.Date.)
            later (java.util.Date. (inc (.getTime at)))
            request (fn [agent process instant]
                      (commit-request failure {:seon.agent/id agent :seon.turn/id "error-graph-turn"
                                               :seon.error/process process :seon.error/at instant
                                               :seon.config.error/recurrence-limit 100}))
            _ (test-support/seed-cluster! connection "error-graph")
            _ (is (:db-after (db/transact! connection
                                          ;; The steward is written by its writer (`steward-call`, through
                                          ;; the steward agent's creation), never on the declaration entity,
                                          ;; which does not own it (seon.ns.edn `:seon.program/written-by`).
                                          (-> (test-support/agents-tx @connection ["error-graph-a" "error-graph-b"])
                                              (into [(program/declaration-row
                                                      (db/carried-projection @connection)
                                                      {:seon.ns/name 'my.error-graph :seon.ns/source "(ns my.error-graph)"}
                                                      :all :agent)
                                                     (test-support/program-fn-entity
                                                      (db/db connection) 'my.error-graph/raise
                                                      "(defn raise [] nil)")
                                                     {:seon.turn/id "error-graph-turn" :seon.turn/agent [:seon.agent/id "error-graph-a"] :seon.turn/opened-tx "datomic.tx"}])
                                              (into (test-support/agent-tx @connection "error-graph-steward" 'my.error-graph))))))
            a (error/recording (db/db connection) (request "error-graph-a" "error-graph-process-1" at))
            b (error/recording (db/db connection) (request "error-graph-b" "error-graph-process-1" at))
            read-error #(db/pull (db/db connection)
                                 '[* {:seon.error/occurrences [* {:seon.error.occurrence/agent [:seon.agent/id]}]}]
                                 (:seon.error/ref a))]
        (is (= (:seon.error/ref a) (:seon.error/ref b)))
        (let [written (db/transact! connection (:seon.db/tx-data a))] (is (:db-after written)))
        (is (:db-after (db/transact! connection (:seon.db/tx-data b))))
        (let [entity (read-error)]
          (is (= 1 (count (db/q '[:find ?e :where [?e :seon.error/signature]] (db/db connection)))))
          (is (= [1 1] (sort (map :seon.error.occurrence/count (:seon.error/occurrences entity)))))
          (is (= 'my.error-graph/raise (:seon.error/producer entity)) "the throwing frame produced it")
          (is (= "error-graph-steward" (error/steward (db/db connection) entity)))
          (is (nil? (:seon.error/steward entity))))
        (let [again (error/recording (db/db connection) (request "error-graph-a" "error-graph-process-1" later))]
          (is (:db-after (db/transact! connection (:seon.db/tx-data again))))
          ;; occurrences are bounded slots per signature and producer digest
          ;; (7781d115c): the recurrence is one more counted occurrence
          (let [occurrences (:seon.error/occurrences (read-error))]
            (is (= 3 (reduce + (map :seon.error.occurrence/count occurrences))))
            (is (= at (first (sort (map :seon.error.occurrence/first-at occurrences)))))
            (is (= later (last (sort (map :seon.error.occurrence/last-at occurrences)))))))
        (let [restarted (error/recording (db/db connection)
                                       (dissoc (request "error-graph-a" "error-graph-process-2" later) :seon.turn/id))]
          (is (= (:seon.error/ref a) (:seon.error/ref restarted)))
          (is (:db-after (db/transact! connection (:seon.db/tx-data restarted))))
          (is (= 1 (count (db/q '[:find ?e :where [?e :seon.error/signature]] (db/db connection))))))
        (let [before (db/db connection)
              first-call (error/recording before (request "error-graph-a" "error-graph-process-1" later))
              second-call (error/recording before (request "error-graph-a" "error-graph-process-1" later))]
          (is (:db-after (db/transact! connection (into (:seon.db/tx-data first-call) (:seon.db/tx-data second-call)))))
          (is (= 6 (reduce + (map :seon.error.occurrence/count (:seon.error/occurrences (read-error)))))
              "two recordings from one basis both count: the writer chooses each one's slot"))
        (let [entity (read-error)
              database (db/db connection)
              summary (first (#'seon.problems/error-signatures database))
              unit {:seon.db/db database :seon.render/value [:seon.agent/id "error-graph-steward"]}
              read-form (:seon.repl/form (error/faults-form unit))]
          (is (= 6 (:seon.error/occurrence-count (error/latest-fact entity))))
          (is (= 6 (:seon.problems/occurrences summary)))
          (let [status (seon.cluster.status/snapshot {:seon.db/db database :seon.db/connection connection})]
            (is ((schema/projection-validator (schema/handed-projection) :seon.error/base) (:seon.cluster.status/store-bytes status)))
            (is (some? (:seon.cluster.status/unavailable-observation (:seon.cluster.status/store-bytes status)))
                "store bytes are an unavailable observation, never a scanned number"))
          (is (= 6 (:seon.error/occurrence-count (error/render-ai entity))))
          (is (some #{"Open"} (test-support/hiccup-leaves (error/render-html {:seon.db/db database :seon.render/value entity}))))
          (is (= '(seon.error/for-agent (seon.db/db) "error-graph-steward") read-form))
          (let [observed (error/for-agent database "error-graph-steward")]
            (is (= 1 (count observed))))
          (is (str/includes? (pr-str (error/render-faults-html [:seon.agent/id "error-graph-steward"] database)) "same error"))
          (is (:db-after (db/transact! connection [[:db/add (:seon.error/ref a) :seon.error/resolved-tx "datomic.tx"]])))
          (is (some #{"Resolved"} (test-support/hiccup-leaves (error/render-html {:seon.db/db (db/db connection) :seon.render/value (read-error)}))))
          ;; A render always has a database (`:seon.render/unit` requires it): one
          ;; omitted is supplied from this body's own branch as it is now.
          (is (some #{"Resolved"} (test-support/hiccup-leaves (error/render-html (read-error)))))
          (is ((schema/projection-validator (schema/handed-projection) :seon.error/fact) (:seon.error/fact (first (#'seon.problems/error-signatures (db/db connection)))))))
        (let [flat (error/recording (db/db connection)
                                  (commit-request {:seon.error/message "flat error", :seon.error/operation (quote seon.id/valid?)} {}))]
          (is (:seon.error/ref flat))
          (is (= 'seon.id/valid? (:seon.error/producer (:seon.error/fact flat))) "a declared value's operation produced it")
          (is (not (contains? (:seon.error/fact flat) :seon.error/exception-class)))
          (is (:db-after (db/transact! connection (:seon.db/tx-data flat)))))))))

(deftest agent-fault-read-is-a-short-call-that-shows-each-recorded-message
  ;; The opening once printed the whole observation selector (81,805 chars on
  ;; root, five times per prompt), and a capped occurrence rendered as
  ;; "Error evidence is unavailable." because the capped evidence's remainder
  ;; shadowed the recorded message.
  (with-db
    (fn [connection]
      (let [failure (ex-info "capped evidence message" {:seon.error-test/rows (vec (range 20000))})
            recorded (error/recording (db/db connection)
                                      (commit-request failure {:seon.agent/id "agent-3"
                                                               :seon.error/process "agent-fault-read"}))
            _ (test-support/transacted! connection (:seon.db/tx-data recorded))
            database (db/db connection)
            form (:seon.repl/form (error/faults-form {:seon.db/db database
                                                      :seon.render/value [:seon.agent/id "agent-3"]}))
            errors (error/for-agent database "agent-3")]
        (is (= '(seon.error/for-agent (seon.db/db) "agent-3") form))
        (is (> 300 (count (pr-str form))))
        (is (= #{(second (:seon.error/ref recorded))}
               (set (map :seon.error/signature errors))
               (set (db/q '[:find [?sig ...] :in $ ?id
                            :where [?a :seon.agent/id ?id] [?e :seon.error/signature ?sig]
                            [?o :seon.error.occurrence/agent ?a] [?e :seon.error/occurrences ?o]]
                          database "agent-3"))))
        (is (:seon.error/capped? (error/latest-fact (first errors))))
        (is (= "capped evidence message" (:seon.error/message (error/render-ai (first errors))))
            "a capped occurrence still shows its recorded message")
        ;; An unstewarded fault is delivered to its escalation owner: the
        ;; writer notifies root, so root's faults hold exactly this one.
        (is (= #{(second (:seon.error/ref recorded))}
               (set (map :seon.error/signature (error/for-agent database "root")))))))))

(deftest a-body-on-its-own-branch-is-supplied-that-branchs-database
  ;; Defect 4 (red-triage-3): a body that makes its own world and omits
  ;; `:seon.db/db` is supplied the custody in force at the call, never the
  ;; member's database as of the body's start. A renderer whose unit requires
  ;; the database, called without it, sees this branch's latest write.
  (with-db
    (fn [connection]
      (let [recorded (error/recording (db/db connection)
                                      (commit-request (ex-info "own branch" {})
                                                      {:seon.agent/id "agent-3"
                                                       :seon.error/process "own-branch"}))
            _ (test-support/transacted! connection (:seon.db/tx-data recorded))
            _ (test-support/transacted! connection [[:db/add (:seon.error/ref recorded)
                                                     :seon.error/resolved-tx "datomic.tx"]])
            entity (db/pull (db/db connection) '[*] (:seon.error/ref recorded))]
        (is (= (:max-tx (db/db connection))
               (:max-tx (:seon.db/db (instrument/calling-environment)))))
        (is (some #{"Resolved"} (test-support/hiccup-leaves (error/render-html entity))))))))

;;; ---------------------------------------------------------------------------
;;; Fixtures — the three families, plus arbitrary observed values
;;; ---------------------------------------------------------------------------

(def ^:private caps
  (config/result-caps config/defaults))

(def ^:private process "test-cluster-4242-1753650000000")

(def ^:private evidence-bytes
  ;; the shipped `:seon.config.error/max-evidence-bytes` decision
  16384)

(defn- request
  "A normalize request over `source`, with optional attribution."
  ([source] (request source {}))
  ([source extra]
   (merge {:seon.schema/projection (schema/handed-projection)
           :seon.error/source source
           :seon.error/declared-schema :seon.error/normalization-error
           :seon.error/id "err-1"
           :seon.error/at #inst "2026-07-27T21:00:00.000-00:00"
           :seon.error/process process
           :seon.sci.admit/caps caps
           ;; THE FAULT FAMILY'S BOUND IS A DECLARED MEMBER of the request,
           ;; exactly like production's committer supplies it: `prepare` no
           ;; longer falls back to a bootstrap number when a caller omits it.
           :seon.config.error/max-evidence-bytes evidence-bytes}
          extra)))

(deftest diagnostic-construction-preserves-domain-members
  (test-support/with-database
   (fn [_]
     (let [observation
           {:seon.error/at #inst "2026-09-19T00:00:00Z"
             :seon.error/layer :seon.agent/acquisition
             :seon.error/operation 'seon.error-test/check
             :seon.error/message "The agent is unavailable."
             :seon.agent/error-agent-id "absent"
             :seon.error-test/context {:seon.error-test/retained true}}]
       (is ((schema/projection-validator (schema/handed-projection) :seon.agent/error)
            observation))
       (is (= {:seon.error-test/retained true} (:seon.error-test/context observation)))
       (let [at (:seon.error/at (request observation))
             intermediate (error.refusal/diagnostic at :seon.error/normalization 'seon.error-test/check {})
             prepared (error/prepare (request observation {:seon.error/declared-schema :seon.agent/error}))]
         ;; the calling world's reference is the common part `diagnostic`
         ;; supplies (7781d115c); the constructed members are exactly these
         (is (= {:seon.error/at at :seon.error/layer :seon.error/normalization
                 :seon.error/operation 'seon.error-test/check} (dissoc intermediate :seon.error/world)))
         (is (identical? at (:seon.error/at intermediate)))
         (is (= observation (select-keys (:seon.error/source prepared) (keys observation)))
             "every member the producer stated is preserved beside what the recorder supplies")
         (is (= :seon.agent/error (get-in prepared [:seon.error/fact :seon.error/declared-schema]))))))))

(defn- cyclic-state
  "A proc state shaped like the run loop's, holding a live-object stand-in
  that CANNOT be printed: an atom holding the map that holds it.
  `pr-str` of this raises StackOverflowError — an Error, which a
  `catch Exception` does not see. The production value is worse (a live
  Datahike connection and executors, `loop.cljc:226-229`); this is the
  same class, buildable without a store."
  []
  (let [connection (atom nil)
        state {:seon.turn.loop/cluster {:seon.db/connection connection}
               :seon.agent/turns 7}]
    (reset! connection state)
    state))

(defn- transform-error
  "Shape 1 — a transform threw (`impl.clj:312-316`). The only shape
  carrying `:op` and `:msg`."
  [throwable]
  {::flow/pid :seon.turn.loop/loop
   ::flow/status :running
   ::flow/state (cyclic-state)
   ::flow/count 12
   ::flow/cid :wake
   ::flow/msg {:seon.cluster.wake/at #inst "2026-07-27T20:59:00.000-00:00"}
   ::flow/op :step
   ::flow/step [:some :step]
   ::flow/ex throwable})

(defn- proc-loop-error
  "Shape 2 — anything else in the proc loop threw (`impl.clj:317-320`).
  No `:cid`, no `:msg`, no `:op`."
  [throwable]
  {::flow/pid :seon.turn.loop/loop
   ::flow/status :running
   ::flow/state (cyclic-state)
   ::flow/count 12
   ::flow/ex throwable})

(defn- xform-error
  "Shape 3 — a channel xform threw (`impl.clj:106-110`). No `:status`,
  no `:state`, no `:count`."
  [throwable]
  {::flow/ex throwable
   ::flow/pid :seon.turn.loop/loop
   ::flow/cid :wake
   ::flow/xform :some-xform})

(defn- refused-chain
  "A Throwable whose DEEPEST ex-data carries the rule, the way a
  transition refusal arrives through Datahike's writer wrappers
  (`store.clj:398-412`)."
  [kind]
  (ex-info "wrapper"
           {}
           (ex-info "writer"
                    {:error :transact/cas}
                    (ex-info "the transition refused"
                             {:seon.turn/rule kind
                              :seon.turn/id "run-9"}))))

;;; ---------------------------------------------------------------------------
;;; Generators — honest, by constructing the real things
;;; ---------------------------------------------------------------------------

(def ^:private throwable-gen
  (gen/fmap #(ex-info % {}) (gen/not-empty gen/string-alphanumeric)))

(def ^:private flow-error-gen
  (gen/fmap (fn [[shape throwable]] (shape throwable))
            (gen/tuple (gen/elements [transform-error
                                      proc-loop-error
                                      xform-error])
                       throwable-gen)))

(def ^:private flat-value-gen
  (gen/fmap (fn [[message data]]
              (cond-> {:seon.error/at #inst "2026-09-19T00:00:00Z"
                       :seon.error/layer :seon.error-test/observation
                       :seon.error/operation 'seon.error-test/observed
                       :seon.error/message message}
                data (assoc :seon.error/data data)))
            (gen/tuple (gen/not-empty gen/string-alphanumeric)
                       (gen/one-of [(gen/return nil)
                                    (gen/map gen/keyword-ns gen/small-integer)]))))

(def ^:private refusal-gen
  (gen/fmap (fn [kind] (ex-data (ex-cause (ex-cause (refused-chain kind)))))
            (gen/elements [:seon.turn/not-the-holder
                           :seon.turn/run-closed
                           :seon.turn/agent-pointer-broken])))

(def ^:private unclassifiable-gen
  "Family 4 by exclusion — nothing recognizes these, and normalization
  must still produce a fact."
  (gen/one-of [(gen/return nil)
               (gen/return 42)
               (gen/return "a bare string nobody classified")
               (gen/return {:not-an-error true})
               throwable-gen]))

(def ^:private source-gen
  (gen/one-of [flow-error-gen flat-value-gen refusal-gen unclassifiable-gen]))

;;; ---------------------------------------------------------------------------
;;; THE TOTALITY PROPERTY — the standing suite member
;;; ---------------------------------------------------------------------------

(deftest normalization-is-total
  (let [result
        (tc/quick-check
         200
         (prop/for-all
          [source source-gen
           attributed? gen/boolean]
          (let [fact (error/normalize
                      (request source
                               (when attributed?
                                 {:seon.turn/id "run-9"
                                  :seon.agent/id "agent-3"})))
                ;; the codec ran: the projection READS BACK as EDN. The
                ;; read is the assertion — a projection that did not
                ;; survive the codec throws here and fails the trial —
                ;; and its VALUE is not, because `nil` is a perfectly
                ;; good projection of a source that was nil
                _read-back (edn/read-string (:seon.error/data-edn fact))]
            (and ((seon.schema/projection-validator (seon.schema/handed-projection) :seon.error/fact) fact)
                 ((seon.schema/projection-validator (seon.schema/handed-projection) :seon.error/base) (error/value fact))
                 ((schema/projection-validator (schema/handed-projection) :seon.error/base) fact)
                 (re-matches #"^[0-9a-f]{64}$" (:seon.error/signature fact))
                 ;; attribution rides exactly when it was supplied
                 (= attributed? (contains? fact :seon.error/run))
                 (= attributed? (contains? fact :seon.error/agent)))))
         :seed 20260727)]
    (test-support/assert-check! result "Error normalization was not total.")))

;;; ---------------------------------------------------------------------------
;;; The codec, and what must never escape
;;; ---------------------------------------------------------------------------

(deftest the-proc-state-never-escapes-raw
  ;; Asserted structurally over the read-back value rather than as a
  ;; substring of the print.
  (let [prepared (error/prepare
                  (request (transform-error (ex-info "boom" {}))))
        fact (:seon.error/fact prepared)
        read-back (edn/read-string (:seon.error/data-edn fact))]
    (testing "a value pr-str cannot survive reads back as EDN"
      (is (map? read-back)))
    (testing "disposable proc state is omitted before admission"
      (is (not (contains? read-back ::flow/state))))
    (testing "and the Throwable is the one error shape's cause chain, never
    the live Throwable nor its every frame"
      (is (not (contains? read-back ::flow/ex)))
      (is (= [{:seon.error/throwable-class "clojure.lang.ExceptionInfo"
               :seon.error/message "boom" :seon.error/data {}}]
             (mapv #(dissoc % :seon.error/frames) (:seon.error/chain read-back)))))))

(deftest capping-is-honest
  ;; `capped?` is admission's own signal and means ELIDED OR TRUNCATED,
  ;; not "contains a marker": a reference that was named rather than
  ;; entered is a complete projection of an unprojectable thing. Both
  ;; halves are asserted so the meaning cannot drift into the other one.
  (let [small (error/normalize (request {:seon.error/message "slow"}))
        wide (error/normalize
              (request {:seon.error/message "slow", :seon.error/data {:rows (vec (range 10000))}}))]
    (is (false? (:seon.error/capped? small))
        "a source that fits is not reported as capped")
    (is (true? (:seon.error/capped? wide))
        "a source wider than the caps says so")))

(deftest over-bound-fault-evidence-retains-its-classifying-base
  (let [source {:seon.error/operation (quote seon.turn/step), :seon.error/message "classified failure", :seon.error/layer :seon.agent/turn, :seon.error/member :seon.test/acquisition, :seon.error/data {:rows (vec (range 10000))}}
        prepared (error/prepare
                  (-> (request source)
                      (assoc-in [:seon.sci.admit/caps
                                 :seon.config.eval.result/max-bytes]
                                1024)))
        fact (:seon.error/fact prepared)
        retained (edn/read-string (:seon.error/data-edn fact))]
    (is (true? (:seon.error/capped? fact)))
    (is (= (select-keys source [:seon.error/layer :seon.error/operation :seon.error/member :seon.error/message])
           (select-keys retained [:seon.error/layer :seon.error/operation :seon.error/member :seon.error/message])))
    (is (= :over-bound
           (get-in retained [:seon.sci.admit/remainder
                             :seon.sci.admit/reason])))))

(deftest over-bound-fault-evidence-keeps-its-cause-chain
  ;; open-errors-triage-2026-09-24 item 3: an over-bound Throwable's whole
  ;; evidence was only the remainder marker, so no class, message or frame
  ;; of any cause survived even in the blob.
  (let [failure (ex-info "outer" {:rows (vec (range 10000))}
                         (ex-info "middle" {} (IllegalStateException. "root cause")))
        prepared (error/prepare
                  (assoc-in (request (transform-error failure))
                            [:seon.sci.admit/caps :seon.config.eval.result/max-bytes] 16384))
        content (edn/read-string (:seon.error/data-edn (:seon.error/fact prepared)))]
    (is (= :over-bound (get-in content [:seon.sci.admit/remainder :seon.sci.admit/reason])))
    (is (= [["clojure.lang.ExceptionInfo" "outer"] ["clojure.lang.ExceptionInfo" "middle"]
            ["java.lang.IllegalStateException" "root cause"]]
           (mapv (juxt :seon.error/throwable-class :seon.error/message)
                 (:seon.error/chain content))))))

(defn ^{:malli/schema [:=> [:cat :int]
                       [:map
                        [:seon.error-test/payload [:vector :string]]
                        [:seon.error-test/id :string]]]}
  wide-return-narrow-violation
  "A real armed function whose RETURN breaks its contract at one small leaf.

  The live fault this reproduces (`7710efbc…` on `default`, 2026-09-17) was
  `seon.sci.eval/evaluate` refusing its own return at
  `[:seon.cluster.eval/error]`: the arm's value is the whole evaluation, which
  carries a context and does not fit the fault's evidence bound, while the
  value that actually broke the contract is a two-element vector."
  [_]
  {:seon.error-test/payload (vec (repeat 20000 "wide evidence"))
   :seon.error-test/id [:seon.ns/name 'user]})

(deftest a-contract-violations-fault-keeps-the-value-that-broke-it
  ;; WHAT BROKE THE CONTRACT IS A QUERY. Before this the fault named the
  ;; violation's path and kept NO copy of the value at it: the arm's whole
  ;; value went over the evidence bound and became the marker, so the repair
  ;; was reached by reproducing the diagnostic instead of reading the fact
  ;; (`docs/prds/steward-platform/research/over-bound-evaluation-contract-2026-09-17.md`,
  ;; "Second finding").
  ;; Only this suite's Var is armed (changed identities), and the scope
  ;; restores it: an `apply!` without changed identities re-armed every
  ;; production Var without its supplied-default plan, and nothing restored
  ;; them (red-triage 2026-09-27: 2,247 of 2,249 wrappers lost their plans).
  (test-support/with-database
   (fn [_connection]
     (test-support/preserving-instrumentation-state
      (fn []
       (is (= 1 (:seon.instrument/registered
                 (instrument/apply!
                  {:seon.instrument/changed-identities
                   #{[:seon.fn/sym 'seon.error-test/wide-return-narrow-violation]}}))))
       (let [thrown (try (wide-return-narrow-violation 1)
                         (catch Throwable throwable throwable))
             prepared (error/prepare (request thrown))
             fact (:seon.error/fact prepared)
             fact-bytes (alength (.getBytes (pr-str fact) "UTF-8"))]
         (is ((schema/projection-validator (schema/handed-projection) :seon.instrument/contract-error) (ex-data thrown)))
         (is (= [:seon.ns/name 'user] (edn/read-string (:seon.instrument/actual fact)))
             "the value at the violation path is kept: the leaf, never the request it rode in")
         (is (pos? (:seon.instrument/actual-size fact))
             "the offending value's own size is recorded")
         (is (< (:seon.instrument/actual-size fact)
                (:seon.error/data-size fact))
             "the offending value is measured, never the source it rode in")
         (is (<= fact-bytes evidence-bytes)
             "stored fault fact was UTF-8 bytes")))))))

(deftest fault-preparation-bounds-the-fact-and-omits-disposable-flow-state
  ;; THE CLASS PROOF for B1. A fault's evidence is a STORED value and goes
  ;; through the same streaming admission every stored value does, under the
  ;; fault family's own declared byte bound. Before this the inline fitting
  ;; was a token-budget search over a render profile, so when presentation
  ;; limits were disabled ONE fault fact reached 915,655 bytes against its
  ;; own declared 4,096 (measured 2026-09-07).
  (let [inline-limit 4096
        large (apply str (repeat 100000 "e"))
        disposable (str "DISPOSABLE-PROC-STATE-" large)
        failure
        (ex-info large
                 {:seon.instrument/schema large, :seon.instrument/fn (quote seon.render.data/at), :seon.instrument/args large, :seon.instrument/arm :input})
        prepared
        (error/prepare
         (assoc (request (assoc (transform-error failure)
                                ::flow/state {:cached-render disposable}))
                :seon.config.error/max-evidence-bytes inline-limit))
        fact (:seon.error/fact prepared)
        fact-bytes (alength (.getBytes (pr-str fact) "UTF-8"))]
    (is (> (:seon.error/data-size fact) inline-limit))
    (is (<= fact-bytes inline-limit)
        "stored fault fact was UTF-8 bytes")
    (is (<= (alength (.getBytes (:seon.error/data-edn fact) "UTF-8"))
            inline-limit))
    (is (not (str/includes? (:seon.error/data-edn fact) "DISPOSABLE-PROC-STATE"))
        "disposable flow state is omitted")
    ;; A FIELD OVER THE FAULT'S OWN DECLARED BYTE BOUND IS MARKED, never
    ;; silently kept: the same `:seon.sci.admit/reason` data every other surface
    ;; reports an absent value with.
    (is (= [:over-bound :over-bound :over-bound]
           (mapv #(:seon.sci.admit/reason (edn/read-string (get fact %)))
                 [:seon.error/message :seon.instrument/expected :seon.instrument/args]))
        "each field over the bound is the over-bound marker")
    (is (true? (:seon.error/capped? fact))
        "a fact whose evidence was replaced says so")
    (is (every? #(some? (get fact %))
                [:seon.error/id :seon.error/message :seon.error/signature :seon.error/data-edn :seon.error/data-size]))))

(deftest fitting-can-require-a-blob-below-the-content-size-threshold
  (let [inline-limit 4096
        near-limit (apply str (repeat 2000 "q"))
        prepared
        (error/prepare
         (assoc (request {:seon.error/message near-limit})
                :seon.config.error/max-evidence-bytes inline-limit))
        fact (:seon.error/fact prepared)
        small
        (error/prepare
         (assoc (request {:seon.error/message "short"})
                :seon.config.error/max-evidence-bytes inline-limit))]
    (is (<= (:seon.error/data-size fact) inline-limit))
    (is (true? (:seon.error/capped? fact))
        "whole-fact fitting can shorten evidence below the size threshold")
    (is (false? (get-in small [:seon.error/fact :seon.error/capped?])))))

(deftest normalization-never-throws
  ;; the recursion fence, stated as a test: a source whose realization
  ;; throws must still produce a fact. Admission is called in :record
  ;; mode unconditionally, so there is no dial under which this becomes
  ;; a second error.
  (let [exploding (lazy-seq (throw (ex-info "realizing me throws" {})))
        fact (error/normalize (request {:seon.error/message "rejected", :seon.error/data {:rows exploding}}))]
    (is ((seon.schema/projection-validator (seon.schema/handed-projection) :seon.error/fact) fact))
    (is (some :seon.sci.admit/projection-error
              (filter map? (tree-seq coll? seq (edn/read-string (:seon.error/data-edn fact)))))
        "the realization that threw is named in the stored value")))

;;; ---------------------------------------------------------------------------
;;; The three shapes lift exactly what they carry
;;; ---------------------------------------------------------------------------

(deftest flow-keys-ride-exactly-when-the-shape-carries-them
  (let [throwable (ex-info "boom" {})
        one (error/normalize (request (transform-error throwable)))
        two (error/normalize (request (proc-loop-error throwable)))
        three (error/normalize (request (xform-error throwable)))]
    (testing "a transform throw carries pid, op and cid"
      (is (= :seon.turn.loop/loop (:seon.error/proc one)))
      (is (= :step (:seon.error/op one)))
      (is (= :wake (:seon.error/cid one))))
    (testing "a proc-loop throw carries neither op nor cid — absence is the state"
      (is (= :seon.turn.loop/loop (:seon.error/proc two)))
      (is (not (contains? two :seon.error/op)))
      (is (not (contains? two :seon.error/cid))))
    (testing "an xform throw carries cid but no op"
      (is (= :wake (:seon.error/cid three)))
      (is (not (contains? three :seon.error/op))))
    (testing "all three name the Throwable's class"
      (is (= "clojure.lang.ExceptionInfo"
             (:seon.error/throwable-class one)))
      (is (= "clojure.lang.ExceptionInfo"
             (:seon.error/throwable-class three))))))

(deftest a-value-that-was-never-a-throwable-has-no-class
  (let [fact (error/normalize (request {:seon.error/message "unset"}))]
    (is (not (contains? fact :seon.error/throwable-class)))
    (is ((schema/projection-validator (schema/handed-projection) :seon.error/base) fact))
    (is (= "unset" (:seon.error/message fact)))))

(deftest the-message-comes-from-the-rule-not-the-wrapper
  ;; found by READING the first real projection: the fact said
  ;; "An error stopped work: wrapper", because the outermost Throwable
  ;; in a Datahike-wrapped refusal carries a useless word while the rule
  ;; sits at the bottom of the chain. The chain is not recoverable from
  ;; data-edn either — admission projects a Throwable to an opaque
  ;; marker by design — so this string is the only place the real
  ;; sentence can appear.
  (let [fact (error/normalize
              (request (transform-error
                        (refused-chain :seon.turn/not-the-holder))))]
    (is (= "the transition refused" (:seon.error/message fact)))))

(deftest the-observed-rule-comes-from-the-deepest-ex-data
  (is (= :seon.turn/not-the-holder
         (:seon.turn/rule (error/refusal (refused-chain :seon.turn/not-the-holder))))
      "The wrappers carry :error and {}; the observed rule is at the bottom."))

(deftest an-unclassifiable-source-is-fail-closed-never-absent
  (doseq [source [42 "a string" {:not-an-error true} nil]]
    (let [fact (error/normalize (request source))]
      (is ((schema/projection-validator (schema/handed-projection) :seon.error/base) fact))
      (is ((seon.schema/projection-validator (seon.schema/handed-projection) :seon.error/fact) fact)))))

(deftest attribution-is-a-lookup-ref-or-nothing
  (let [with (error/normalize (request {:seon.error/message "no"}
                                       {:seon.turn/id "run-9"
                                        :seon.agent/id "agent-3"}))
        without (error/normalize (request {:seon.error/message "no"}))]
    (is (= [:seon.turn/id "run-9"] (:seon.error/run with)))
    (is (= [:seon.agent/id "agent-3"] (:seon.error/agent with)))
    (is (not (contains? without :seon.error/run)))
    (is (not (contains? without :seon.error/agent))
        "no attributable agent is a state, not a nil")))

;;; ---------------------------------------------------------------------------
;;; The signature is content, and recurrence must be countable
;;; ---------------------------------------------------------------------------

(deftest the-signature-ignores-the-message
  (let [signature (fn [message]
                    (:seon.error/signature
                     (error/normalize
                      (request {:seon.error/message message}))))]
    (is (= (signature "run 8b1c failed at 21:00:01")
           (signature "run 44de failed at 21:00:09"))
        "an id or a timestamp in the message must not make every occurrence unique")))

(deftest the-signature-separates-different-violated-schemas
  (let [signature (fn [expected]
                    (:seon.error/signature
                     (error/normalize (request {:seon.error/expected-key expected
                                                :seon.error/message "same"}))))]
    (is (not= (signature :seon.db/rejected) (signature :seon.ai/timeout)))))

;;; ---------------------------------------------------------------------------
;;; Projections, through the ONE router
;;; ---------------------------------------------------------------------------

(defn- fact []
  (error/normalize (request (transform-error (refused-chain
                                              :seon.turn/not-the-holder))
                            {:seon.turn/id "run-9"
                             :seon.agent/id "agent-3"})))

(deftest notices-carry-structured-projection-evidence
  (test-support/assert-check!
   (tc/quick-check
    80
    (prop/for-all [reason (gen/elements [nil :recurring])]
      (let [fact (fact)
            notice (error/notice
                    (cond-> {:seon.error/fact fact}
                      reason (assoc :seon.error/reason reason)))
            human (error/ai-prose notice)]
        (and
         ((seon.schema/projection-validator (seon.schema/handed-projection) :seon.error/notice) notice)
         (= fact (:seon.error/fact notice))
         (= [:seon.error/id (:seon.error/id fact)]
            (:seon.error/evidence notice))
         (not (contains? notice :seon.render/ai))
         (and (string? human) (not (str/blank? human))))))
    :seed 202607280901)
   "structured error projections"))

(deftest the-projection-keys-are-derived-never-stored
  (let [fact (fact)]
    (is (not (contains? fact :seon.render/ai)))))

(deftest instrumentation-evidence-survives-normalization
  (let [violation {:seon.error/message "bad call", :seon.instrument/schema ":seon.error/fact", :seon.instrument/fn (quote seon.error/value), :seon.instrument/args "[\"not a fact\"]", :seon.instrument/arm :input}
        fact (error/normalize
              (request (transform-error
                        (ex-info "bad call" violation))))]
    (is (= 'seon.error/value (:seon.instrument/fn fact)))
    (is (= :input (:seon.instrument/arm fact)))
    (is (= ":seon.error/fact" (:seon.instrument/expected fact)))
    (is (= "[\"not a fact\"]" (:seon.instrument/args fact)))
    (let [notice (error/notice {:seon.error/fact fact})
          prose (error/ai-prose notice)]
      (is (not (str/blank? prose))))))

(deftest the-flat-value-projects-from-the-fact
  (let [fact (fact)
        value (error/value fact)]
    (is ((seon.schema/projection-validator (seon.schema/handed-projection) :seon.error/base) value))
    (is (= (select-keys fact [:seon.error/at :seon.error/layer :seon.error/operation])
           (select-keys value [:seon.error/at :seon.error/layer :seon.error/operation])))
    (is (= (:seon.error/message fact) (:seon.error/message value)))
    (is (= (:seon.error/id fact) (:seon.error/id value))
        "the value points at the durable evidence rather than copying it")))

;;; ---------------------------------------------------------------------------
;;; The commit — pure over a database value, so the whole rule is testable
;;; ---------------------------------------------------------------------------

;;; A real in-memory database with the canonical attributes installed,
;;; because who-gets-told depends on which agents EXIST — and because
;;; `canonical-database-attributes` is the live boot derivation, not a
;;; hand-listed fixture set (the fixture-vs-live-boot class).
(defn- with-db
  "A database with this cluster's CONFIG applied, exactly like production.

  The config carries `:seon.config/initialization`'s supplied-default rows,
  and without them call preparation has nothing to prepare: an
  attribute-declared producer contracted `[value database]` was then invoked
  with one argument and answered `ArityException`, which the walk recorded as
  a renderer failure. A fixture that omits a declared input is the defect,
  not the producer's contract (§5.1)."
  [body]
  (test-support/with-database
    (fn [connection]
      (test-support/seed-cluster! connection "error-test")
      (test-support/transacted! connection (test-support/agents-tx @connection ["root" "agent-3"]))
      (body connection))))

(defn- commit-request
  [source extra]
  (merge {:seon.schema/projection (schema/handed-projection)
           :seon.error/source source
          :seon.error/declared-schema :seon.error/normalization-error
          :seon.error/id (str (random-uuid))
          :seon.error/at #inst "2026-07-27T21:00:00.000-00:00"
          :seon.error/process process
          :seon.sci.admit/caps caps
          :seon.config.error/recurrence-limit 3
          :seon.config.error/max-evidence-bytes evidence-bytes
          :seon.config.error/escalate-to "root"}
         extra))

(deftest a-recording-without-a-declared-schema-is-kept
  ;; root 2026-09-27: turn-test's injected write refusal carried no
  ;; declared-schema metadata and `recording` refused it, so the fault was
  ;; lost. The positional form records an undeclared value as normalized
  ;; escaped data.
  (test-support/with-database
   (fn [connection]
     (let [source {:seon.error/at (java.util.Date.)
                   :seon.error/layer :seon.error-test/recording
                   :seon.error/operation 'seon.error-test/undeclared
                   :seon.error/message "An undeclared refusal."}
           recording (error/recording {:seon.sci.admit/caps caps
                                       :seon.config.error/max-evidence-bytes evidence-bytes
                                       :seon.db.process/id process}
                                      (db/db connection) nil source (java.util.Date.) {})
           report (test-support/transacted! connection (:seon.db/tx-data recording))]
       (is (= :seon.error/normalization-error
              (get-in recording [:seon.error/fact :seon.error/declared-schema])))
       (is (= source (select-keys (edn/read-string (get-in recording [:seon.error/fact :seon.error/data-edn]))
                                  (keys source)))
           "the evidence reads back as the value")
       (is (:seon.error/signature (db/pull (:db-after report) '[*] (:seon.error/ref recording))))))))

(defn- commit!
  "Commit one error and return [fact-count messages-by-recipient]."
  [connection source extra]
  (test-support/transacted! connection
                          (error/commit-tx (db/db connection) (commit-request source extra)))
  (let [db (db/db connection)]
    [(count (db/q '[:find ?e :where [?e :seon.error/id _]] db))
     ;; ?message is bound so two messages to one recipient are two
     ;; entities: a `[?to ...]` find returns a SET and would have counted
     ;; a four-message storm as one
     (frequencies
      (map second
           (db/q '[:find ?message ?to
                  :where
                  [?message :seon.message/about _]
                  [?message :seon.message/to ?agent]
                  [?agent :seon.agent/id ?to]]
                db)))]))

(deftest recurrence-counting-does-not-require-a-notification-threshold
  (let [applications (atom 0)
        apply-config config/apply!]
   (with-redefs [config/apply! (fn [request]
                                (when (= "error-test" (:seon.boot/cluster-name request))
                                  (swap! applications inc))
                                (apply-config request))]
    (with-db
    (fn [connection]
      (let [request (dissoc (commit-request (transform-error (ex-info "boom" {})) {})
                            :seon.config.error/recurrence-limit)]
        (test-support/transacted! connection (error/commit-tx (db/db connection) request))
        (test-support/transacted! connection (error/commit-tx (db/db connection) request))
        (is (= 2 (db/q '[:find (sum ?n) . :where [_ :seon.error.occurrence/count ?n]]
                        (db/db connection))))
        (is (= 1 (count (db/q '[:find ?m :where [?m :seon.message/about]]
                              (db/db connection)))))))))
   (is (= 1 @applications) "The canonical cluster seed is the sole config writer.")))

(deftest the-writer-not-the-evidence-shape-chooses-who-acts
  ;; Audit 2026-09-26 §2.5 item 2: a Throwable's presence used to route an
  ;; attributed fault to the agent it happened to, and the same fault as a
  ;; flat value to nobody. Both now reach the escalation owner the producer
  ;; names, as its value; the attributed agent is never mailed about it.
  (doseq [[label source] [[:throwable (transform-error (ex-info "boom" {}))]
                          [:flat-value {:seon.error/message "the run is held by another process"}]]]
    (with-db
      (fn [connection]
        (testing (str label " attributed to agent-3")
          (let [[facts messages] (commit! connection source {:seon.agent/id "agent-3"
                                                             :seon.turn/id "run-9"})
                content (db/q '[:find ?content . :where [?m :seon.message/about _]
                                                        [?m :seon.message/content ?content]]
                              (db/db connection))
                delivered (edn/read-string content)]
            (is (= 1 facts))
            (is (= {"root" 1} messages))
            (is (= #{:seon.error/at :seon.error/layer :seon.error/operation :seon.error/message
                     :seon.error/signature :seon.error/id}
                   (into #{} (filter #{:seon.error/at :seon.error/layer :seon.error/operation
                                       :seon.error/message :seon.error/signature :seon.error/id})
                         (keys delivered)))
                "the notification is the error's value, readable as data")))))
    (with-db
      (fn [connection]
        (testing (str label " from a producer naming no escalation owner: an agent's own mistake")
          (test-support/transacted!
           connection
           (error/commit-tx (db/db connection)
                            (dissoc (commit-request source {:seon.agent/id "agent-3"})
                                    :seon.config.error/escalate-to)))
          (is (empty? (db/q '[:find ?m :where [?m :seon.message/about _]] (db/db connection)))))))))

(deftest an-agent-this-cluster-does-not-have-is-no-attribution-at-all
  ;; review-caught: attribution is read off the FACT, not the request.
  ;; Asking the request would take the :your-run branch — which then
  ;; addresses nobody, because the agent does not exist — while
  ;; suppressing the escalation, leaving an interrupting error recorded
  ;; and told to NOBODY.
  (with-db
    (fn [connection]
      (let [[facts messages] (commit! connection
                                      (transform-error (ex-info "boom" {}))
                                      {:seon.agent/id "ghost"})]
        (is (= 1 facts))
        (is (= {"root" 1} messages)
            "it escalates exactly as an unattributable error does")))))

(deftest an-unattributable-throwable-goes-to-the-escalation-owner
  (with-db
    (fn [connection]
      (let [[_ messages] (commit! connection
                                  (transform-error (ex-info "boom" {}))
                                  {})]
        (is (= {"root" 1} messages)))))
  (with-db
    (fn [connection]
      (testing "and an escalation dial naming an agent this cluster does not
      have costs the message, never the record"
        (let [[facts messages]
              (commit! connection
                       (transform-error (ex-info "boom" {}))
                       {:seon.config.error/escalate-to "nobody"})]
          (is (= 1 facts))
          (is (= {} messages)))))))

(deftest an-unattributable-fault-without-a-throwable-is-delivered
  ;; open-errors-triage-2026-09-24 item 2: `504062b8` ("The selected renderer
  ;; did not return an observation.") had no exception class and no steward,
  ;; so it was recorded and told to nobody.
  ;; Counted by this fault's own occurrence: a test world inherits the live
  ;; cluster's error entities and messages. Escalation is set by `commit-request`.
  (with-db
    (fn [connection]
      (let [text (str "The selected renderer did not return an observation. " (random-uuid))]
        (test-support/transacted!
         connection
         (error/commit-tx (db/db connection)
                          (commit-request {:seon.error/message text}
                                          {:seon.config.error/escalate-to "root"})))
        (is (= #{["root" 1]}
               (set (db/q '[:find ?to (count ?message)
                       :in $ ?text
                       :where
                       [?occurrence :seon.error.occurrence/message ?text]
                       [?error :seon.error/occurrences ?occurrence]
                       [?error :seon.error/signature ?signature]
                       [?message :seon.message/about ?signature]
                       [?message :seon.message/to ?agent]
                       [?agent :seon.agent/id ?to]]
                     (db/db connection) text))))))))

(deftest a-core-fault-wakes-the-configured-escalation-owner-root-by-default
  ;; owner ruling 2026-09-25 20:50Z: core faults deliver to root by default.
  ;; The shipped config names root; a cluster that decides the dial absent
  ;; still records the fault and mails nobody.
  ;; Each arm has its own operation, hence its own signature, and counts only
  ;; its own entities: the test branch inherits the cluster's live faults.
  (with-db
    (fn [connection]
      (let [shipped (select-keys (config/effective (db/db connection) "error-test")
                                 [:seon.config.error/escalate-to])
            record! (fn [operation dial]
                      (let [recording (error/recording
                                       (db/db connection)
                                       (merge (dissoc (commit-request {:seon.error/message "an unowned core fault"
                                                                       :seon.error/operation operation}
                                                                      {})
                                                      :seon.config.error/escalate-to)
                                              dial))
                            signature (get-in recording [:seon.error/fact :seon.error/signature])
                            _ (test-support/transacted! connection (:seon.db/tx-data recording))
                            database (db/db connection)]
                        [(count (:seon.error/occurrences (db/pull database '[:seon.error/occurrences]
                                                                  (:seon.error/ref recording))))
                         (frequencies (map second
                                           (db/q '[:find ?message ?to :in $ ?signature
                                                   :where [?message :seon.message/about ?signature]
                                                          [?message :seon.message/to ?agent]
                                                          [?agent :seon.agent/id ?to]]
                                                 database signature)))]))]
        (is (= {:seon.config.error/escalate-to "root"} shipped) "the shipped default names root")
        (is (= [1 {"root" 1}] (record! 'seon.error-test/unowned-fault-with-the-shipped-dial shipped))
            "recorded, and root is mailed")
        (is (= [1 {}] (record! 'seon.error-test/unowned-fault-with-no-owner-named {}))
            "a request naming no escalation owner is recorded and mails nobody")))))

(deftest the-storm-is-bounded-by-the-signature-count
  ;; the live falsifier's unit twin: one signature repeated forever must
  ;; not mail forever, because a message is a commit and a commit wakes
  ;; the loop that faulted
  (with-db
    (fn [connection]
      (let [source (transform-error (ex-info "the same bug" {}))
            outcomes (mapv (fn [_] (commit! connection source {})) (range 6))
            [facts messages] (last outcomes)]
        (is (= 1 facts) "one entity represents the repeated error")
        (is (= 6 (db/q '[:find (sum ?n) . :with ?o :where [?o :seon.error.occurrence/count ?n]] (db/db connection))))
        (is (= {"root" 1} messages)
            "D13: one notification for the root, independent of repeat count")))))

(deftest a-prepared-message-keeps-its-id-when-the-transaction-repeats
  (with-db
    (fn [connection]
      (let [source (transform-error (ex-info "boom" {}))
            request (commit-request source {:seon.agent/id "agent-3"
                                            :seon.turn/id "run-9"})
            tx (error/commit-tx (db/db connection) request)]
        ;; the SAME request committed twice: re-execution after a crash
        ;; must upsert, never double-send
        (test-support/transacted! connection tx)
        (test-support/transacted! connection tx)
        (let [db (db/db connection)]
          (is (= 1 (count (db/q '[:find ?e :where [?e :seon.error/id _]] db))))
          (is (= 1 (count (db/q '[:find ?m :where
                                 [?m :seon.message/about _]]
                               db))))))))) 

(deftest the-message-points-at-the-fact-it-explains
  (with-db
    (fn [connection]
      (commit! connection (transform-error (ex-info "boom" {})) {})
      (let [db (db/db connection)
            about (db/q '[:find ?id .
                         :where
                         [?message :seon.message/about ?signature]
                         [?error :seon.error/signature ?signature]
                         [?error :seon.error/id ?id]]
                       db)]
        (is (some? about)
            "the tempid resolved: fact and message land in ONE transaction")))))

(deftest a-fault-observes-the-function-name-without-minting-an-identity
  (with-db
    (fn [connection]
      (let [target (symbol "my.mint" "broken")
            failure (doto (IllegalStateException. "a real Java class")
                      (.setStackTrace
                       (into-array StackTraceElement
                                   [(StackTraceElement. "my.mint$broken" "invokeStatic"
                                                        "mint.clj" 11)])))
            recording (error/recording (db/db connection)
                                       (commit-request (transform-error failure) {}))]
        (test-support/transacted! connection (:seon.db/tx-data recording))
        (let [database (db/db connection)
              stored (db/pull database '[*] (:seon.error/ref recording))]
          (is (= 'java.lang.IllegalStateException (:seon.error/exception-class stored)))
          (is (= target (:seon.error/producer stored)) "the throwing frame's function, observed by name")
          (is (nil? (db/pull database [:db/id] [:seon.fn/sym target])))
          (is (nil? (db/pull database [:db/id] [:seon.ns/name 'my.mint]))))))))


(deftest declared-error-schema-reports-missing-members
  (let [projection (schema/handed-projection)
        value {:seon.error/at #inst "2026-09-22T00:00:00Z"
               :seon.error/layer :seon.agent/read
               :seon.error/operation 'seon.agent/by-id
               :seon.agent/error-agent-id "missing-agent"}
        missing (dissoc value :seon.agent/error-agent-id)]
    (is ((schema/projection-validator projection :seon.agent/error) value))
    (is (false? ((schema/projection-validator projection :seon.agent/error) missing)))
    (is (some #(= [:seon.agent/error-agent-id] (:in %))
              (:errors ((schema/projection-explainer projection :seon.agent/error) missing))))))

(deftest arity-declared-schema-preserves-real-refusal-observations
  (test-support/with-database
   (fn [connection]
     (let [projection (db/carried-projection (db/db connection))
           observed-at (java.util.Date.)
           refusal (test-support/refusal-data #(apply seon.id/valid? []))
           data refusal
           contract (:malli/schema (meta #'seon.id/valid?))
           info (malli.core/-function-info
                 (malli.core/schema contract (:seon.schema.projection/compile-options projection)))
           value {:seon.error/at observed-at :seon.error/layer :seon.instrument/invocation
                  :seon.error/operation 'seon.id/valid?
                  :seon.instrument/fn (:seon.instrument/fn data)
                  :seon.instrument/arity (:seon.instrument/arity data)
                  :seon.instrument/declared-arity-count 1
                  :seon.instrument/declared-arities
                  #{(cond-> {:seon.instrument.arity/ordinal 0 :seon.instrument.arity/min (:min info)}
                      (:max info) (assoc :seon.instrument.arity/max (:max info)))}}
           validate (schema/projection-validator projection :seon.instrument/arity-error)]
       (is (= :malli.core/invalid-arity (:seon.instrument/malli data)))
       (is (= 0 (:seon.instrument/arity data)))
       (is (validate value))
       (is (false? (validate (assoc value :seon.instrument/arity (:min info)))))
       (is (false? (validate (assoc value :seon.instrument/declared-arity-count 2))))))))

(deftest complete-error-children-validate-through-the-writer
  (let [forms (assoc (schema.edn/packaged-forms)
                     ::manifest-id [:string {:seon.db/identity true}]
                     ::manifest-location [:and {:seon.db/component true
                                               :seon.db/component-schema :seon.error.location/entity} :seon.db/ref]
                     ::manifest-observation [:and {:seon.db/component true
                                                   :seon.db/component-schema :seon.agent/error} :seon.db/ref]
                     ::manifest-root [:map {:seon.db/attributes true :seon.program/partition :seon.data}
                                      [::manifest-id ::manifest-id]
                                      [::manifest-location ::manifest-location]
                                      [::manifest-observation {:optional true} ::manifest-observation]])
        projection (schema/build-projection forms)]
    (test-support/with-database
     {::test-support/extra-schema
      (schema.datahike/malli->datahike-schema-in projection [::manifest-id ::manifest-location ::manifest-observation])}
     (fn [connection]
       (db/carry-connection-projection-state!
        connection (sci.eval/projection-state (db/db connection) projection))
       ;; The writer derives its projection from stored schema entities, so the
       ;; synthetic manifest schemas are declared as entities, not only carried.
       (test-support/transacted!
        connection
        (test-support/schema-entities
         (db/db connection)
         (mapv #(vector % (get forms %))
               [::manifest-id ::manifest-location ::manifest-observation ::manifest-root])))
       (test-support/transacted! connection [{::manifest-id "root-path"
                                              ::manifest-location {:seon.error.location/length 0}}])
       (test-support/transacted!
        connection [{::manifest-id "missing-key"
                     ::manifest-location
                     {:seon.error.location/length 2
                      :seon.error.location/segments
                      #{{:seon.error.location.segment/ordinal 0
                         :seon.error.location.segment/key
                         {:seon.error.key/projection "nil" :seon.error.key/capped? false
                          :seon.error.key/bound-bytes 256}}
                        {:seon.error.location.segment/ordinal 1
                         :seon.error.location.segment/key
                         {:seon.error.key/projection ":plain" :seon.error.key/scalar :plain
                          :seon.error.key/capped? false :seon.error.key/bound-bytes 256}}}}}])
       (let [observation {:seon.error/at #inst "2026-09-18T00:00:00Z"
                          :seon.error/layer :seon.agent/lifecycle
                          :seon.error/operation 'seon.agent/by-id
                          :seon.agent/error-agent-id "same-observed-agent"}
             large-location
             {:seon.error.location/length 1001
              :seon.error.location/segments
              (set (for [n (range 1001)]
                     {:seon.error.location.segment/ordinal n
                      :seon.error.location.segment/key
                      {:seon.error.key/projection (str n) :seon.error.key/scalar n
                       :seon.error.key/capped? false :seon.error.key/bound-bytes 256}}))}
             report (test-support/transacted!
                     connection [{::manifest-id "large-path" ::manifest-location large-location
                                  ::manifest-observation observation}
                                 {::manifest-id "another-observation"
                                  ::manifest-location {:seon.error.location/length 0}
                                  ::manifest-observation observation}])
             database (db/db connection)
             root (:db/id (db/pull database [:db/id] [::manifest-id "large-path"]))
             location (:v (first (db/datoms database :eavt root ::manifest-location)))
             ; This observation is non-unique and unindexed; AEVT contains every attribute datom.
             observations (filter #(= "same-observed-agent" (:v %))
                                  (db/datoms database :aevt :seon.agent/error-agent-id))]
         (is (= 1001 (count (db/datoms database :eavt location :seon.error.location/segments))))
         (is (= 2 (count (set (map :e observations)))) "Observed identity tokens cannot upsert two errors together.")
         (is (seq (:tx-data report)))
         (println "ERROR-MANIFEST stored-large-value"
                  {:entities (count (set (map :e (:tx-data report)))) :datoms (count (:tx-data report))
                   :segments 1001 :observations (count observations)}))
       (let [database (db/db connection)
             root (:db/id (db/pull database [:db/id] [::manifest-id "missing-key"]))
             location (:v (first (db/datoms database :eavt root ::manifest-location)))
             segment (:v (first (db/datoms database :eavt location :seon.error.location/segments)))
             before (db/basis-t database)
             result (db/transact! connection [[:db/add segment :seon.error.location.segment/ordinal 3]])]
         (is ((schema/projection-validator (schema/handed-projection) :seon.db.write/validation-refusal) result))
         (is (= [[:db/add segment :seon.error.location.segment/ordinal 3]]
                (get-in result [:seon.error/data :seon.db.write.attempt/transaction])))
         (is (= before (get-in result [:seon.error/basis :seon.error.basis/t])))
         (is (= before (db/basis-t (db/db connection))))
         (is (= 2 (count (db/datoms database :eavt location :seon.error.location/segments))))
         (let [recording (error/recording database (commit-request result {:seon.error/declared-schema :seon.db.write/validation-refusal}))
               report (test-support/transacted! connection (:seon.db/tx-data recording))
               stored (db/pull (:db-after report) (error/observation-selector projection)
                               (:seon.error/ref recording))
               occurrence (first (:seon.error/occurrences stored))
               attempt (:seon.db.write/attempt occurrence)]
           (is (seq (:seon.error/occurrences stored)))
           (is (= (:seon.db.write.attempt/request-id result)
                  (:seon.db.write.attempt/request-id attempt)))
           (is (= before (get-in occurrence [:seon.error/basis :seon.error.basis/t])))
           (is (= (mapv :v (db/datoms database :eavt segment :seon.error.location.segment/ordinal))
                  (mapv :v (db/datoms (:db-after report) :eavt segment :seon.error.location.segment/ordinal)))
               "The recorder stores the submitted transaction as evidence; it never executes it.")
           (is (= (get-in result [:seon.error/data :seon.db.write.attempt/transaction])
                  (edn/read-string (get-in attempt [:seon.db.write.attempt/operations :seon.instrument/actual]))))
           (is (false? (get-in attempt [:seon.db.write.attempt/operations :seon.error/capped?])))
           (is (not (contains? occurrence :seon.db.write.attempt/request-id)))))))))


(deftest schema-refusals-are-admitted-at-the-recorder
  (test-support/with-database
   (fn [connection]
     (let [projection (schema/handed-projection)
           unavailable {:seon.error-test/unavailable true}
           ;; The producer itself: projection-from-database now meets
           ;; seon.db/carried-projection's input contract before this refusal.
           raw (#'schema/refuse-projection-source unavailable)
           recording (error/recording (db/db connection) (commit-request raw {:seon.error/declared-schema :seon.schema/validation-refusal}))
           report (test-support/transacted! connection (:seon.db/tx-data recording))
           root (db/pull (:db-after report) (error/observation-selector projection)
                         (:seon.error/ref recording))
           occurrence (first (:seon.error/occurrences root))]
       (is ((schema/projection-validator projection :seon.schema/validation-refusal) raw))
       (is ((schema/projection-validator projection :seon.schema/error) (error/latest-fact root)))
       (is (= unavailable
              (edn/read-string (get-in occurrence [:seon.schema/error-declaration :seon.instrument/actual]))))
       (is (= :seon.db/database-value
              (edn/read-string (get-in occurrence [:seon.schema/declaration-expectation :seon.instrument/actual]))))
       (is (not (contains? occurrence :seon.schema/refused-value)))
       (is (= (second (:seon.error/ref recording))
              (:seon.error/signature (error/normalize (request (error/latest-fact root)
                                                         {:seon.error/declared-schema :seon.schema/error})))))))))

(deftest cause-chain-reading-preserves-the-deepest-complete-observation
  (let [observation {:seon.error/at #inst "2026-09-20T00:00:00Z"
                     :seon.error/layer :seon.error-test/cause-chain
                     :seon.error/operation 'seon.error-test/check
                     :seon.error/message "A complete refusal"
                     :seon.agent/error-agent-id "observed"}
        underlying (ex-info "Underlying evidence" {:seon.error-test/evidence "retained"})
        refusal (ex-info "Refused" observation underlying)]
    (is (= observation (error/refusal (ex-info "Wrapper" {:seon.error-test/wrapper true} refusal))))))

(deftest error-declared-schemas-persist-through-the-real-occurrence-owner
  (test-support/with-database
   (fn [connection]
     (test-support/seed-cluster! connection "error-family-1a")
     (let [projection (db/carried-projection (db/db connection))
           observed {:seon.error/at #inst "2026-09-19T00:00:00Z"
                     :seon.error/layer :seon.agent/lifecycle
                     :seon.error/operation 'seon.agent/by-id
                     :seon.agent/error-agent-id "error-family-observed"
                     :seon.turn/error-turn-id "error-family-turn"}
           before (db/basis-t (db/db connection))
           unowned (test-support/refusal-data
                    #(test-support/transacted! connection [observed]))
           armed (test-support/refusal-data #(apply seon.id/valid? []))]
       (is ((schema/projection-validator projection :seon.turn/error) observed))
       (is (= before (db/basis-t (db/db connection))))
       (prn {::unowned-refusal unowned})
       (is ((schema/projection-validator projection :seon.instrument/arity-error) armed))
       (doseq [[source declared-schema]
               [[observed :seon.turn/error]
                [armed :seon.instrument/arity-error]
                [(assoc observed :seon.error/location
                        {:seon.error.location/length 1001
                         :seon.error.location/segments
                         (set (for [ordinal (range 1001)]
                                {:seon.error.location.segment/ordinal ordinal
                                 :seon.error.location.segment/key
                                 {:seon.error.key/projection (str ordinal)
                                  :seon.error.key/capped? false
                                  :seon.error.key/bound-bytes 256}}))})
                 :seon.turn/error]]]
         (let [recording (error/recording (db/db connection)
                                           (commit-request source {:seon.error/at (:seon.error/at source)
                                                                   :seon.error/declared-schema declared-schema}))
               report (test-support/transacted! connection (:seon.db/tx-data recording))
               database (db/db connection)
               root (db/pull database (error/observation-selector projection) (:seon.error/ref recording))
               occurrence (first (:seon.error/occurrences root))]
           (is (seq (:tx-data report)))
           (is ((schema/projection-validator projection declared-schema) source))
           ;; Stored entity contracts and pull-result contracts have distinct
           ;; collection grammars. Validate the read through its derived form.
           (doseq [declared-schema [declared-schema]]
             (let [occurrence-selector
                   (some #(when (map? %)
                            (get % [:seon.error/occurrences :limit nil]))
                         (error/observation-selector projection))
                   pulled-form (schema/pulled-form-in projection declared-schema occurrence-selector)
                   registry (:seon.schema.projection/registry projection)
                   scalar-attributes
                   (remove #(some-> (mr/schema registry %) malli.core/properties
                                :seon.db/component)
                           (map first (schema.internal/entity-entries (mr/schema registry declared-schema))))]
               (is (true? (malli.core/validate pulled-form occurrence
                                               (:seon.schema.projection/compile-options projection))))
               ;; every member the producer stated persists; the recorder adds
               ;; the producer it attributes (7781d115c) beside them
               (is (= (select-keys source scalar-attributes)
                      (select-keys occurrence (filter (set (keys source)) scalar-attributes))))))
           (when-let [bounds (:seon.instrument/declared-arities source)]
             (is (= bounds (set (map #(dissoc % :db/id)
                                    (:seon.instrument/declared-arities occurrence))))))
           (let [acquired (db/pull database (error/observation-selector projection)
                                   (:seon.error/ref recording))
                 latest (error/latest-fact acquired)]
             (is (= (:seon.error/layer source) (:seon.error/layer latest)))
             (when-let [location (:seon.error/location source)]
               (is (= (:seon.error.location/length location)
                      (count (get-in latest [:seon.error/location :seon.error.location/segments])))))
             (is (= latest (#'error/rendered-error-value
                            {:seon.db/db database :seon.render/value root}))))
           (let [latest (error/latest-fact root)]
             (is (= (:seon.error/operation source) (:seon.error/operation latest)))
             (is (= (:seon.instrument/declared-arities occurrence)
                    (:seon.instrument/declared-arities latest)))
             ;; Only the declared schema's members persist: `:seon.turn/error`
             ;; declares `:seon.turn/error-turn-id`, not `:seon.agent/error-agent-id`.
             (is (= (:seon.turn/error-turn-id source)
                    (:seon.turn/error-turn-id latest))))
           (is (some #(= (:db/id occurrence) (:db/id %)) (:seon.error/occurrences root)))
           ;; Record the acquisition question without asserting that a stored
           ;; entity validator is a pull-result validator.
           (is (= declared-schema (:seon.error/declared-schema root)))))))))

(deftest recurrence-identity-uses-declared-schema-and-distinguishing-evidence
  (test-support/with-database
   (fn [connection]
     (test-support/seed-cluster! connection "error-family-d13")
     (let [projection (db/carried-projection (db/db connection))
           observed {:seon.error/at #inst "2026-09-19T00:00:00Z"
                     :seon.error/layer :seon.agent/lifecycle
                     :seon.error/operation 'seon.agent/by-id
                     :seon.error/expected-key :seon.agent/id
                     :seon.agent/error-agent-id "observed-agent"}
           record! (fn [source declared-schema process]
                     (let [recorded (error/recording
                                     (db/db connection)
                                     (commit-request source
                                      {:seon.error/process process
                                       :seon.error/declared-schema declared-schema}))]
                       (test-support/transacted! connection (:seon.db/tx-data recorded))
                       recorded))
           root (fn [recorded]
                  (db/pull (db/db connection) (error/observation-selector projection)
                           (:seon.error/ref recorded)))
           first-record (record! observed :seon.agent/error "d13-process-a")
           second-record (record! observed :seon.agent/error "d13-process-a")
           after-two (root first-record)]
       (is (= (:seon.error/ref first-record) (:seon.error/ref second-record)))
       (is (= :seon.agent/error (:seon.error/declared-schema after-two)))
       (is (= 2 (reduce + (map :seon.error.occurrence/count (:seon.error/occurrences after-two))))
           "one signature counts both, over its bounded occurrences")
       (is (= (second (:seon.error/ref first-record))
              (:seon.error/signature
               (error/normalize (request (error/latest-fact after-two)
                                 {:seon.error/declared-schema :seon.agent/error})))))
       (let [changed-incidental
             (assoc observed :seon.error/at #inst "2026-09-20T00:00:00Z"
                             :seon.error/message "Different explanation"
                             :seon.error/offending "different bytes"
                             :seon.agent/error-agent-id "another-observed-agent")
             third-record (record! changed-incidental :seon.agent/error "d13-process-b")]
         (is (= (:seon.error/ref first-record) (:seon.error/ref third-record)))
         (is (= 3 (:seon.error/occurrence-count (error/latest-fact (root first-record))))))
       (let [before (root first-record)
             other-declaration (record! (-> observed
                                            (dissoc :seon.agent/error-agent-id)
                                            (assoc :seon.turn/error-turn-id "observed-turn"))
                                        :seon.turn/error "d13-process-a")
             other-expected (record! (assoc observed :seon.error/expected-key :seon.turn/id)
                                     :seon.agent/error "d13-process-a")
             other-path (record! (assoc observed :seon.error/location
                                       {:seon.error.location/length 1
                                        :seon.error.location/segments
                                        #{{:seon.error.location.segment/ordinal 0
                                           :seon.error.location.segment/key
                                           {:seon.error.key/scalar :seon.agent/id
                                            :seon.error.key/projection ":seon.agent/id"
                                            :seon.error.key/capped? false
                                            :seon.error.key/bound-bytes 256}}}})
                                 :seon.agent/error "d13-process-a")]
         (is (= 4 (count (set (map :seon.error/ref
                                   [first-record other-declaration other-expected other-path])))))
         (is (= before (root first-record)) "Other identities leave this error's occurrences untouched.")
         (is (= 4 (count (db/q '[:find ?root :where [?root :seon.error/signature]]
                               (db/db connection))))))))))

(deftest every-error-owner-function-declares-its-input-and-output
  (test-support/with-database
   (fn [_connection]
     (is (= [] (vec (for [[function candidate] (ns-interns 'seon.error)
                          :when (and (bound? candidate) (fn? @candidate)
                                     (nil? (:malli/schema (meta candidate))))]
                      function)))))))

(deftest recording-refuses-an-unavailable-complete-observation
  (test-support/with-database
   (fn [connection]
     (let [database (db/db connection)
           request (commit-request {:seon.error/signature (apply str (repeat 64 "0"))} {})
           result (error/recording database request)]
       (is ((schema/projection-validator (schema/projection-from-database database)
                                          :seon.error/base) result))
       (is (= 'seon.error/recording (:seon.error/operation result)))
       (is (= :seon.error/error (:seon.error/expected-key result)))
       (is (= result (error/commit-tx database request)))
       (is (empty? (db/q '[:find ?error :where [?error :seon.error/signature]] database)))))))


(deftest the-default-renderers-accept-an-attribute-shaped-error
  (test-support/with-database
   (fn [_]
     (let [value {:seon.error/at #inst "2026-09-22T00:00:00Z"
                  :seon.error/layer ::rendering
                  :seon.error/operation 'seon.error-test/rendering
                  :my.fs/error-path "/tmp/missing.edn"
                  :seon.error/message "No file exists at that path."}
           ai (error/render-ai value)
           html (error/render-html value)]
       (is (= (:seon.error/message value) ai)
           "AI explains the failure without dumping internal evidence")
       (is (= :article (first html)))
       (is ((schema/projection-validator (schema/handed-projection) :seon.render/hiccup) html))
       (is (some #{(:seon.error/message value)} (test-support/hiccup-leaves html)))))))

(deftest an-unstored-error-renders-its-own-message
  ;; A refusal carries its signature before, or without, its recording: the
  ;; database holds no entity for it, and both faces show the value itself,
  ;; with no appended contract finding (whether a return is declared is the
  ;; producer's armed output contract's decision, not the renderer's).
  (test-support/with-database
   (fn [connection]
     (let [value {:seon.error/at #inst "2026-09-22T00:00:00Z"
                  :seon.error/layer ::rendering
                  :seon.error/operation 'seon.error-test/rendering
                  :seon.error/signature (str/join (repeat 64 \0))
                  :seon.error/message "The refusal's own message."}
           unit {:seon.render/value value :seon.db/db (db/db connection)}
           ai (error/render-ai unit)
           html (error/render-html unit)]
       (is (= (:seon.error/message value) ai) "the AI face is the message, with no appended finding")
       (is (some #{(:seon.error/message value)} (test-support/hiccup-leaves html)))))))

(deftest a-held-error-observation-renders-as-held-without-a-read
  ;; Render-specificity audit slice 1: the pair used to re-pull every value
  ;; carrying a signature, touching data the caller already held. Only a
  ;; bare identity (a signature without occurrences or message) is read.
  (test-support/with-database
   (fn [connection]
     (let [reads (atom [])
           bare-reads (atom [])
           value {:seon.error/at #inst "2026-09-22T00:00:00Z"
                  :seon.error/layer ::rendering
                  :seon.error/operation 'seon.error-test/rendering
                  :seon.error/signature (str/join (repeat 64 \1))
                  :seon.error/message "The held observation's message."}
           unit {:seon.render/value value :seon.db/db (db/db connection)}
           ai (binding [db/*read-evidence-sink* reads] (error/render-ai unit))
           bare (binding [db/*read-evidence-sink* bare-reads]
                  (error/render-ai (assoc unit :seon.render/value
                                          (dissoc value :seon.error/message))))]
       (is (= (:seon.error/message value) ai))
       (is (some? bare))
       (is (empty? @reads) "the held observation is not read again")
       (is (seq @bare-reads) "a bare identity is read")))))

(deftest the-default-html-face-links-committed-evidence
  (let [html (error/render-html
              {:seon.error/at #inst "2026-09-22T00:00:00Z"
                  :seon.error/layer ::rendering
                  :seon.error/operation 'seon.error-test/rendering
                  :seon.error/id "err-42"
               :seon.error/message "Nothing recognized this error."})
        href (get-in (last html) [2 1 :href])]
    (is (str/starts-with? href "/data?"))
    (is (str/includes? href "%3Aseon.error%2Fid"))))

(deftest a-declared-refusal-prints-its-own-message-and-members
  ;; A refusal whose `:seon.error/expected` is no Malli schema is not a
  ;; schema problem: it leads with its own message and shows its members,
  ;; with no fabricated "expected the declared requirement (nil)" frame and
  ;; no filler lines (error-text-opus, 2026-09-26).
  (let [reply (error/reply-value
               nil
               {:seon.error/at #inst "2026-09-26T00:00:00Z"
                :seon.error/layer ::rendering
                :seon.error/operation 'seon.cluster.source/write-back!
                :seon.error/message "The staged file base changed."
                :seon.error/offending {:seon.fs/path "src/a.clj"}})]
    (is (= {:seon.error/message "The staged file base changed."
            :seon.error/offending {:seon.fs/path "src/a.clj"}}
           reply))))

(deftest ^{:seon.test/golden "The specialist *-prose functions produce prose by definition; their text is their output."}
  specialist-renderers-use-their-declared-evidence
  (test-support/with-database
   (fn [_connection]
    (testing "a stored contract fault names what was expected and received"
    (let [reply (error/reply-value
                 nil
                 {:seon.error/at #inst "2026-09-22T00:00:00Z"
                  :seon.error/layer ::rendering
                  :seon.error/operation 'my.fs/read
                  :seon.instrument/fn 'my.fs/read
                  :seon.instrument/arm :input
                  :seon.instrument/expected ":my.fs/read-request"
                  :seon.instrument/args "[{:my.fs/path 42}]"
                  :seon.error/message "The call violated its contract."})]
      (is (= {:seon.error/message "The call violated its contract."
              :seon.error/expected ":my.fs/read-request"
              :seon.error/offending "[{:my.fs/path 42}]"}
             reply))))
  (testing "AI attempt prose exposes the decision attributes"
    (let [prose (error/ai-prose
                 {:seon.error/at #inst "2026-09-22T00:00:00Z"
                  :seon.error/layer ::rendering
                  :seon.error/operation 'seon.error-test/rendering
                  :seon.ai/request-transmitted? false
                  :seon.ai/response-started? false
                  :seon.ai/output-observed? false
                  :seon.error/message "The provider connection failed."})]
      (is (str/includes? prose "request transmitted: false"))
      (is (str/includes? prose "response started: false"))
      (is (str/includes? prose "output observed: false"))
      (is (str/includes? prose "No request was transmitted."))))
  (testing "time-limit prose explains the diagnostic without treating it as a limit"
    (let [prose (error/time-limit-prose
                 {:seon.error/at #inst "2026-09-22T00:00:00Z"
                  :seon.error/layer ::rendering
                  :seon.error/operation 'seon.error-test/rendering
                  :seon.eval/fn-entries 271000000
                  :seon.error/message "Evaluation reached its time limit."})]
      (is (str/includes? prose "Recorded function-body entries: 271000000"))
      (is (str/includes? prose "indicate a spin"))))
  (testing "edit prose asks for a narrower source selection"
    (let [prose (error/edit-prose
                 {:seon.error/at #inst "2026-09-22T00:00:00Z"
                  :seon.error/layer ::rendering
                  :seon.error/operation 'seon.error-test/rendering
                  :my.edit/error-path "src/seon/error.clj"
                  :my.edit/edit-observation {:seon.error.evidence/attribute :my.edit/from-line
                                             :seon.error.evidence/value 1}
                  :seon.error/message "More than one form matched."})]
      (is (str/includes? prose "src/seon/error.clj"))
      (is (str/includes? prose "narrow the edit selection"))))
  (testing "render-walk elision stays neutral in both projections"
    (let [value {:seon.error/at #inst "2026-09-22T00:00:00Z"
                  :seon.error/layer ::rendering
                  :seon.error/operation 'seon.error-test/rendering
                  :seon.error/message "The bounded walk omitted content."}
          prose (error/elision-prose value)
          html (error/elision-html value)]
      (is (str/includes? prose "content was elided"))
      (is (not (str/includes? prose "error")))
      (is (= :aside (first html)))
      (is (= "seon-family-entry seon-render-elision"
             (get-in html [1 :class])))))
  (testing "unavailable domain evidence is explicit"
    (let [prose (error/unclassified-prose
                 {:seon.error/at #inst "2026-09-22T00:00:00Z"
                  :seon.error/layer ::rendering
                  :seon.error/operation 'seon.error-test/rendering
                  :seon.error/offending {:unexpected/value 7}
                  :seon.error/message "Nothing recognized the source."})]
      (is (str/includes? prose "did not supply complete domain evidence"))
      (is (str/includes? prose "boundary contract"))))
  (testing "MCP lookup prose keeps the requested value identity"
    (let [digest (apply str (repeat 64 "a"))
          prose (error/mcp-prose
                 {:seon.error/at #inst "2026-09-22T00:00:00Z"
                  :seon.error/layer ::rendering
                  :seon.error/operation 'seon.error-test/rendering
                  :seon.dev.mcp/error-cluster "fixture"
                  :seon.dev.mcp/request-observation
                  {:seon.error.evidence/attribute :seon.blob/digest
                   :seon.error.evidence/value digest}
                  :seon.error/message "The value was absent."})]
      (is (str/includes? prose digest))
      (is (str/includes? prose "current cluster status"))))
  (testing "index refusal prose names the stopped phase"
    (let [prose (error/index-refusal-prose
                 {:seon.error/at #inst "2026-09-22T00:00:00Z"
                  :seon.error/layer ::rendering
                  :seon.error/operation 'seon.error-test/rendering
                  :seon.fn/analysis-phase :seon.fn/schema
                  :seon.fn/error-subject {:seon.instrument/actual "source.clj"
                                          :seon.error.projection/bound-bytes 256
                                          :seon.error/capped? false}
                  :seon.error/message "Schema indexing was refused."})]
      (is (str/includes? prose ":seon.fn/schema"))
      (is (str/includes? prose "rerun initialization")))))))

(deftest recorded-identity-uses-the-producer-declared-schema
  (let [projection (schema/handed-projection)
        observation {:seon.error/at #inst "2026-09-22T00:00:00Z"
                     :seon.error/layer ::identity
                     :seon.error/operation 'seon.error-test/identity
                     :seon.agent/error-agent-id "agent"}
        signature (fn [schema value]
                    (#'error/signature projection value schema nil nil))]
    (is (= (signature :seon.agent/error observation)
           (signature :seon.agent/error
                      (assoc observation :seon.error/message "Changed prose"
                                         :seon.error/at #inst "2026-09-23T00:00:00Z"))))
    (is (not= (signature :seon.agent/error observation)
              (signature :seon.turn/error observation)))
    (is (= (signature :seon.agent/error observation)
           (signature :seon.agent/error (assoc observation :seon.turn/error-turn-id "incidental"))))))

(deftest a-fifty-thousand-entry-offending-value-stores-bounded-text
  ;; The fact keeps text bounded by the fault family's bound and no second copy
  ;; of the value.
  (test-support/with-database
   (fn [_connection]
     (let [offending (into {} (map (fn [i] [(keyword "entry" (str "k" i)) i])) (range 50000))
           prepared (error/prepare
                     (request {:seon.error/message "wide"
                               :seon.error/offending offending}))
           {fact :seon.error/fact staged :seon.blob/staged-writes} prepared]
       (is (true? (:seon.error/capped? fact)))
       (is (<= (count (.getBytes ^String (:seon.error/data-edn fact) "UTF-8")) evidence-bytes))
       (is (empty? staged) "no copy of the value is staged")))))

(deftest a-fault-site-survives-recompilation-and-moved-lines
  ;; One runner fault became four signatures and one turn backstop fault five
  ;; on default (2026-09-26): the compiler's class numbering and the line
  ;; entered the identity, so each restart or edit above re-opened the fault.
  (let [site #'seon.error/frame-site]
    (is (= (site '[seon.test$run$own_write_BANG___16186 invoke "test.clj" 2353])
           (site '[seon.test$run$own_write_BANG___16130 invoke "test.clj" 2342])
           ["seon.test$run$own_write_BANG_" "invoke" "test.clj"]))
    (is (= (site '[seon.turn$turn_completion_backstop_failure invokeStatic "turn.clj" 5561])
           (site '[seon.turn$turn_completion_backstop_failure invokeStatic "turn.clj" 5612])))
    (is (= ["seon.call_preparation$preparing" "doInvoke" "call_preparation.clj"]
           (site '[seon.call_preparation$preparing$fn__6604 doInvoke "call_preparation.clj" 40])))
    (is (= ["user$eval" "invokeStatic" "NO_SOURCE_FILE"]
           (site '[user$eval987622 invokeStatic "NO_SOURCE_FILE" 3])))
    (testing "distinct named functions stay distinct"
      (is (not= (site '[seon.test$run invoke "test.clj" 1])
                (site '[seon.test$run_BANG_ invoke "test.clj" 1]))))))

(deftest a-refused-name-read-is-shown-as-its-refusal
  ;; An unresolved name's reply lists the names its namespace declares. When
  ;; the database refuses that read (here a value with no attributes
  ;; installed), the reply carries the refusal itself: never the refusal's map
  ;; entries sorted as names, never an empty list (Astra e9e15d610 §3).
  (let [unresolved {:seon.error/message "Unable to resolve symbol: aera"
                    :seon.error/chain [{:seon.error/message "Unable to resolve symbol: aera"
                                        :seon.error/data {:sci.impl/symbol 'aera}}]
                    :seon.sci.eval/stacktrace '[[scratch.fbr 1 1]]}
        names (get-in (error/reply-value (datahike.db/empty-db) unresolved)
                      [:seon.error/in-namespace 'scratch.fbr])]
    (is (db/read-refusal? names))
    (is (= :seon.ns/name (get-in names [:seon.error/data :seon.db/attribute])))))

(deftest a-contract-problem-shows-an-example-never-a-synthesized-call
  ;; The reply shows the failing path, its expectation, the offending value
  ;; and one generated example labelled as an example; it never rewrites the
  ;; caller's input into a call (root ruling on Astra e9e15d610 §5).
  (let [refusal {:seon.error/message "scratch.frr/f refused argument 0"
                 :seon.instrument/fn 'scratch.frr/f
                 :seon.instrument/check :input
                 :seon.error/offending ["4"]
                 :seon.error/problems [{:seon.error/schema-path [0] :seon.error/path []
                                        :seon.error/argument "argument 0"
                                        :seon.error/expected :int :seon.error/offending "4"}]}
        reply (error/reply-value nil refusal)]
    (is (= "scratch.frr/f refused argument 0" (:seon.error/message reply)))
    (is (= [{:seon.error/path [] :seon.error/expected :int :seon.error/offending "4"}]
           (mapv #(dissoc % :seon.error/example) (:seon.error/problems reply))))
    (is (int? (get-in reply [:seon.error/problems 0 :seon.error/example])))
    (is (not-any? #(contains? reply %) [:seon.error/fix-form :seon.error/offending]))))

(deftest a-thrown-failure-shows-its-cause-chain-to-the-agent
  ;; Owner 2026-09-28: an error indicates what went wrong. An agent's own
  ;; `ex-info` over a JVM cause showed only its message; the data it threw
  ;; and the root cause were one requery away.
  (let [thrown (error.refusal/diagnostic
                {:seon.error/at (java.util.Date.) :seon.error/layer :seon.error-test/probe
                 :seon.error/operation 'seon.error-test/probe
                 :seon.error/throwable (ex-info "probe: nested" {:probe/k 1}
                                                (IllegalStateException. "root cause"))})
        reply (error/reply-value nil thrown)]
    (is (= [{:seon.error/throwable-class "clojure.lang.ExceptionInfo" :seon.error/data {:probe/k 1}}
            {:seon.error/throwable-class "java.lang.IllegalStateException"
             :seon.error/message "root cause"}]
           (:seon.error/chain reply))
        "each link's class, message and data, without frames; the shown message is not repeated")
    (testing "a bare message has nothing more to say"
      (is (not (contains? (error/reply-value nil (error.refusal/diagnostic
                                                  {:seon.error/at (java.util.Date.)
                                                   :seon.error/layer :seon.error-test/probe
                                                   :seon.error/operation 'seon.error-test/probe
                                                   :seon.error/throwable (ex-info "only a message" {})}))
                          :seon.error/chain))))
    (testing "a structural refusal keeps its problems and shows no chain"
      (is (not (contains? (error/reply-value nil (assoc thrown :seon.error/offending ["4"]))
                          :seon.error/chain))))))

(deftest an-error-reply-names-its-type-and-says-each-thing-once
  (testing "the variant's type is part of the reply"
    (is (= :seon.instrument/contract-error
           (:seon.error/type (error/reply-value nil {:seon.error/type :seon.instrument/contract-error
                                                     :seon.error/message "f refused argument 0"})))))
  (testing "a cause link does not repeat the shown message"
    (let [reply (error/reply-value nil (error.refusal/diagnostic
                                        {:seon.error/at (java.util.Date.) :seon.error/layer :seon.error-test/probe
                                         :seon.error/operation 'seon.error-test/probe
                                         :seon.error/throwable (ex-info "Bad entity attribute :x/y" {:attribute :x/y})}))]
      (is (= "Bad entity attribute :x/y" (:seon.error/message reply)))
      (is (= [{:attribute :x/y}] (mapv :seon.error/data (:seon.error/chain reply))))
      (is (not-any? :seon.error/message (:seon.error/chain reply)))))
  (testing "an arity refusal shows its arglists, not their arity bounds again"
    (let [reply (error/reply-value nil {:seon.error/message "f refused argument count"
                                        :seon.instrument/arglists '([a b])
                                        :seon.instrument/declared-arities
                                        #{{:seon.instrument.arity/ordinal 0 :seon.instrument.arity/min 2
                                           :seon.instrument.arity/max 2}}})]
      (is (= '([a b]) (:seon.instrument/arglists reply)))
      (is (not (contains? reply :seon.instrument/declared-arities))))))

(deftest a-missing-name-with-no-near-names-has-no-namespace-list
  (let [database (seon.db/db)]
    (is (not (contains? (seon.error/reply-value database {:seon.error/message "Could not resolve symbol: nosuch.zz/undefined-sym"
                                                          :seon.program/not-found 'nosuch.zz/undefined-sym})
                        :seon.error/in-namespace))
        "an empty name list is no key")
    (is (= 'pull (first (get-in (seon.error/reply-value database {:seon.error/message "Could not resolve symbol: seon.db/pul"
                                                                  :seon.program/not-found 'seon.db/pul})
                                [:seon.error/in-namespace 'seon.db]))))))

(deftest ^{:seon/tasks #{'seon.error/a-missing-key-message-reads-badly}}
  a-missing-key-and-an-equals-schema-read-as-one-expectation
  (let [missing (:seon.error/expected-description
                 (seon.error/explain-problem {:seon.error/problem (first (:errors ((requiring-resolve 'malli.core/explain) [:map [:at inst?]] {})))
                                              :seon.error/path [:at] :seon.error/argument "value" :seon.error/parent {}}))
        equals (:seon.error/expected-description
                (seon.error/explain-problem {:seon.error/problem (first (:errors ((requiring-resolve 'malli.core/explain) [:map [:k [:= {:description "d"} true]]] {:k false})))
                                             :seon.error/path [:k] :seon.error/argument "value" :seon.error/parent {:k false}}))]
    (is (clojure.string/includes? missing ":at") "the missing key is named")
    (is (clojure.string/includes? equals "true") "the := schema's value is named")
    (doseq [description [missing equals]
            phrase ["satisfying should" "with should"]]
      (is (not (clojure.string/includes? description phrase)) "Malli's sentence is not glued after a connective"))))

(deftest an-equals-or-enum-expectation-carries-no-properties
  (let [expected (fn [schema value]
                   (:seon.error/expected
                    (seon.error/explain-problem {:seon.error/problem (first (:errors ((requiring-resolve 'malli.core/explain) schema value)))
                                                 :seon.error/path [] :seon.error/argument "value" :seon.error/parent value})))]
    (is (= [:= true] (expected [:= {:seon.config/display-label "No provider" :description "Use virtual turns"} true] false)))
    (is (= [:enum :a :b] (expected [:enum {:description "x"} :a :b] :c)))
    (is (= [:int {:min 1}] (expected [:int {:min 1}] 0)) "a bound is part of the expectation")))

(deftest a-map-for-a-sequential-is-refused-as-the-map
  (let [described (fn [value]
                    (seon.error/explain-problem {:seon.error/problem (first (:errors ((requiring-resolve 'malli.core/explain) [:sequential :map] value)))
                                                 :seon.error/path [] :seon.error/argument "xs" :seon.error/parent value}))]
    (is (= {:a 1} (:seon.error/offending (described {:a 1}))))
    (is (= [:sequential :map] (:seon.error/expected (described {:a 1}))))
    (is (= 2 (:seon.error/offending (described [{:a 1} 2]))) "a bad member of a sequence is still named")))

(deftest the-faults-read-of-a-value-that-names-no-entity-is-absent
  (with-db
    (fn [connection]
      (let [database (db/db connection)
            unit (fn [value attribute]
                   (cond-> {:seon.db/db database :seon.render/value value}
                     attribute (assoc :seon.render.walk/attribute attribute)))
            sci-var (sci.core/new-var 'faults-pull-probe 1)]
        (is (nil? (error/faults-form (unit sci-var :seon.error/of-steward))) "an SCI Var names no agent")
        (is (nil? (error/render-faults-ai (unit sci-var :seon.error/agent))) "its AI face shows nothing")
        (is (nil? (error/faults-form (unit nil nil))))))))

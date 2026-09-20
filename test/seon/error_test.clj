(ns seon.error-test
  "Sealed acceptance draft for the ONE error normalizer and its
  projections.

  DRAFT FOR ORCHESTRATOR SEAL (drafted 2026-07-27, step 1 of the
  error-wiring order). The implementation lane makes these green by
  implementing `seon.error` and `seon.render` ONLY — schemas and tests
  are byte-sealed; friction is reported, never resolved by weakening.

  THE STANDING PROPERTY is `normalization-is-total`: over all three
  input families, every normalization validates `:seon.error/fact`,
  projects to a valid flat `:seon.error/base`, and prints a `data-edn`
  that READS BACK through `clojure.edn/read-string`. That last clause
  is what proves the one codec ran — a raw `pr-str` of a flow report
  carrying `::flow/state` does not read back, and for a state holding a
  reference cycle it does not even return (`admit.clj:82-92`, probed).
  The normalizer excludes that disposable proc state before admission.
  Fixed seed 20260727, per-trial isolation by construction: the
  normalizer is pure, opens nothing and writes nothing, so a trial's
  only state is the source it is handed.

  THE FLOW SHAPES ARE BUILT LITERALLY, and that is deliberate. Their
  authority is `reference-code/core.async/.../flow/impl.clj:106-110`
  (xform) and `:312-320` (transform / proc-loop), which
  `test/seon/flow_test.clj:496-522` already proves flow really emits;
  re-proving flow here would test flow, not the normalizer, and would
  hide the point — that the three shapes do NOT share a key set and the
  normalizer must be total over all of them anyway.

  Normalization is pure. Writer and reader regressions use the canonical
  database fixture under armed contracts."
  (:require [clojure.core.async.flow :as-alias flow]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [seon.config :as config]
            [seon.error :as error]
            [seon.instrument :as instrument]
            [seon.problems]
            [seon.cluster.status]
            [seon.render.transcript]
            [malli.core]
            [seon.id]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.schema.internal :as schema.internal]
            [malli.registry :as mr]
            [seon.schema.datahike :as schema.datahike]
            [seon.sci.eval :as sci.eval]
            [seon.sci.admit :as admit]
            [seon.test-support :as test-support]
            [seon.db :as db]))

(declare commit-request)

(deftest row-acquisition-observations-have-no-program-digest-promise
  (test-support/with-database
   (fn [connection]
     (doseq [lookup [[:seon.fn/sym 'seon.id/id] [:seon.ns/name 'seon.id]]]
      (let [database (db/db connection)
           projection (schema/handed-projection)
           row (db/pull database '[*] lookup)
           observation (test-support/refusal-data
                        #(@#'sci.eval/acquisition-refusal row
                          (ex-info "row installation failed" {:seon.error-test/cause 42})))]
       (is (= (second lookup) (:seon.sci.eval/row-member observation)))
       (is (= "row installation failed"
              (get-in observation [:seon.sci.eval/acquisition-observation
                                   :seon.error.evidence/value])))
       (is (not (contains? observation :seon.sci.eval/requested-program)))
       (is (schema/valid-candidate-value? projection :seon.sci.eval/row-acquisition-error observation))
       (is (not (schema/valid-candidate-value? projection :seon.sci.eval/acquisition-error observation)))
       (when (:seon.sci.eval/row-member observation)
         (let [recording (error/recording database (commit-request observation {}))
               report (test-support/transacted! connection (:seon.db/tx-data recording))
               root (db/pull (:db-after report) (error/observation-selector projection)
                             (:seon.error/ref recording))
               stored (error/latest-fact root)]
           (is (schema/valid-candidate-value? projection :seon.sci.eval/row-acquisition-error stored))
           (is (= (:seon.sci.eval/acquisition-observation observation)
                  (dissoc (:seon.sci.eval/acquisition-observation stored) :db/id)))
           (is (= (second lookup) (:seon.sci.eval/row-member stored)))
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
                                          [{:seon.agent/id "error-graph-a"}
                                           {:seon.agent/id "error-graph-b"}
                                           {:seon.agent/id "error-graph-steward"}
                                           {:seon.ns/name 'my.error-graph
                                            :seon.ns/steward [:seon.agent/id "error-graph-steward"]}
                                           (test-support/program-fn-row
                                            (db/db connection) 'my.error-graph/raise
                                            "(defn raise [] nil)")
                                           {:seon.turn/id "error-graph-turn" :seon.turn/agent [:seon.agent/id "error-graph-a"] :seon.turn/opened-tx "datomic.tx"}])))
            a (error/recording (db/db connection) (request "error-graph-a" "error-graph-process-1" at))
            b (error/recording (db/db connection) (request "error-graph-b" "error-graph-process-1" at))
            read-error #(db/pull (db/db connection)
                                 '[* {:seon.error/occurrences [* {:seon.error.occurrence/agent [:seon.agent/id]}]}]
                                 (:seon.error/ref a))]
        (is (= (:seon.error/ref a) (:seon.error/ref b)))
        (is (not= (:seon.error.occurrence/ref a) (:seon.error.occurrence/ref b)))
        (let [written (db/transact! connection (:seon.db/tx-data a))] (is (:db-after written) (pr-str (dissoc written :db-before :db-after)) ))
        (is (:db-after (db/transact! connection (:seon.db/tx-data b))))
        (let [row (read-error)]
          (is (= 1 (count (db/q '[:find ?e :where [?e :seon.error/signature]] (db/db connection)))))
          (is (= [1 1] (sort (map :seon.error.occurrence/count (:seon.error/occurrences row)))))
          (is (= 'my.error-graph/raise (:seon.instrument/fn row)))
          (is (= "error-graph-steward" (error/steward (db/db connection) row)))
          (is (nil? (:seon.error/steward row))))
        (let [again (error/recording (db/db connection) (request "error-graph-a" "error-graph-process-1" later))]
          (is (:db-after (db/transact! connection (:seon.db/tx-data again))))
          (let [row (db/pull (db/db connection) '[*] (:seon.error.occurrence/ref again))]
            (is (= 2 (:seon.error.occurrence/count row)))
            (is (= at (:seon.error.occurrence/first-at row)))
            (is (= later (:seon.error.occurrence/last-at row)))))
        (let [restarted (error/recording (db/db connection)
                                       (dissoc (request "error-graph-a" "error-graph-process-2" later) :seon.turn/id))]
          (is (= (:seon.error/ref a) (:seon.error/ref restarted)))
          (is (:db-after (db/transact! connection (:seon.db/tx-data restarted))))
          (is (= 1 (count (db/q '[:find ?e :where [?e :seon.error/signature]] (db/db connection))))))
        (let [before (db/db connection)
              first-call (error/recording before (request "error-graph-a" "error-graph-process-1" later))
              second-call (error/recording before (request "error-graph-a" "error-graph-process-1" later))]
          (is (:db-after (db/transact! connection (into (:seon.db/tx-data first-call) (:seon.db/tx-data second-call)))))
          (is (= 4 (:seon.error.occurrence/count (db/pull (db/db connection) '[*] (:seon.error.occurrence/ref a))))))
        (let [row (read-error)
              database (db/db connection)
              summary (first (#'seon.problems/error-signatures database))
              unit {:seon.db/db database :seon.render/value [:seon.agent/id "error-graph-steward"]}
              read-form (:seon.repl/form (error/faults-form unit))]
          (is (= 6 (:seon.error/occurrence-count (error/latest-fact row))))
          (is (= 6 (:seon.problems/occurrences summary)))
          (let [status (seon.cluster.status/snapshot {:seon.db/db database :seon.db/connection connection})]
            (is (= 6 (get (into {} (:seon.cluster.status/faults status))
                          (second (:seon.error/ref a)))))
            (is ((schema/projection-validator (schema/handed-projection) :seon.error/base) (:seon.cluster.status/store-bytes status)))
            (is (str/includes? (:seon.error/message (:seon.cluster.status/store-bytes status))
                               "not scanned")))
          (is (str/includes? (pr-str (#'seon.render.transcript/fault-problems database "error-graph-steward" [])) "occurrences: 6"))
          (is (str/includes? (error/log-line (error/notice {:seon.error/fact (:seon.error/fact summary) :seon.error/occurrence-count 6})) "occurrences=6"))
          (is (str/includes? (error/render-ai row) "Occurrences: 6"))
          (is (str/includes? (pr-str (error/render-html row)) "Open"))
          (let [observed (db/q (second (second read-form)) database (last read-form))]
            (is (= 1 (count observed)) (pr-str observed)))
          (is (str/includes? (pr-str (error/render-faults-html [:seon.agent/id "error-graph-steward"] database)) "same error"))
          (is (= 1 (:seon.render.transcript/count (first (#'seon.render.transcript/fault-problems database "error-graph-steward" [])))))
          (is (:db-after (db/transact! connection [[:db/add (:seon.error/ref a) :seon.error/resolved-tx "datomic.tx"]])))
          (is (str/includes? (pr-str (error/render-html (read-error))) "Resolved"))
          (is ((schema/projection-validator (schema/handed-projection) :seon.error/fact) (:seon.error/fact (first (#'seon.problems/error-signatures (db/db connection)))))))
        (let [flat (error/recording (db/db connection)
                                  (commit-request {:seon.error/message "flat error", :seon.error/data {:seon.error/diagnostic-operation (quote seon.id/valid?)}} {}))]
          (is (:seon.error/ref flat))
          (is (= 'seon.id/valid? (:seon.instrument/fn (:seon.error/fact flat))))
          (is (not (contains? (:seon.error/fact flat) :seon.error/exception-class)))
          (is (:db-after (db/transact! connection (:seon.db/tx-data flat)))))))))

(deftest dropped-fault-counts-accumulate-in-the-occurrence
  (test-support/with-database
    (fn [connection]
      (doseq [n [3 5]]
        (let [request (commit-request {:seon.error/message "dropped deliveries"} {})
              fact (assoc (error/normalize request)
                          :seon.error/dropped-fault-count n
                          :seon.error/dropped-fault-digest (apply str (repeat 64 "a")))
              result (db/transact! connection
                                  (error/commit-tx (db/db connection) (assoc request :seon.error/fact fact)))]
          (is (:db-after result))))
      (let [database (db/db connection)
            row (first (db/q '[:find [(pull ?o [*]) ...]
                              :where [?o :seon.error/dropped-fault-count]] database))]
        (is (= 2 (:seon.error.occurrence/count row)))
        (is (= 8 (:seon.error/dropped-fault-count row)))
        (is (= (apply str (repeat 64 "a")) (:seon.error/dropped-fault-digest row)))))))





;;; ---------------------------------------------------------------------------
;;; Fixtures — the three families, plus arbitrary observed values
;;; ---------------------------------------------------------------------------

(def ^:private caps
  (config/result-caps (config/defaults)))

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
           (error/diagnostic
            {:seon.error/at #inst "2026-09-19T00:00:00Z"
             :seon.error/layer :seon.agent/acquisition
             :seon.error/operation 'seon.error-test/check
             :seon.error/message "The agent is unavailable."
             :seon.error/diagnostic-layer :seon.agent/acquisition
             :seon.error/diagnostic-operation 'seon.error-test/check
             :seon.error/diagnostic-member :seon.agent/id
             :seon.error/diagnostic-expected :seon.agent/id
             :seon.error/diagnostic-offending "absent"
             :seon.error/diagnostic-cause :seon.agent/unavailable
             :seon.error/diagnostic-evidence {:seon.agent/id "absent"}
             :seon.agent/error-agent-id "absent"
             :seon.error-test/context {:seon.error-test/retained true}})]
       (is ((schema/projection-validator (schema/handed-projection) :seon.agent/error)
            observation))
       (is (= {:seon.error-test/retained true} (:seon.error-test/context observation)))))))

(deftest diagnostic-construction-is-evidence-complete
  (let [complete
        (error/diagnostic
         {:seon.error/diagnostic-evidence {:seon.error-test/path [0]}, :seon.error/operation (quote seon.error-test/check), :seon.error/diagnostic-expected :int, :seon.error/diagnostic-member :seon.error-test/value, :seon.error/message "The call was invalid.", :seon.error/layer :seon.error-test/diagnostic, :seon.error/diagnostic-layer :agent-boundary, :seon.error/data {:seon.error-test/context :kept}, :seon.error/diagnostic-offending "not-an-int", :seon.error/diagnostic-operation (quote seon.error-test/check), :seon.error/at (java.util.Date.), :seon.error/diagnostic-cause :seon.error-test/schema-mismatch})
        unavailable
        (error/diagnostic
         {:seon.error/diagnostic-evidence nil, :seon.error/operation (quote seon.error-test/check), :seon.error/diagnostic-expected nil, :seon.error/diagnostic-member nil, :seon.error/message "The evidence could not be observed.", :seon.error/layer :seon.error-test/diagnostic, :seon.error/diagnostic-layer nil, :seon.error/data {:seon.error-test/context :kept, :seon.error/diagnostic-evidence-availability :cannot-replace, :seon.error/diagnostic-layer :cannot-replace}, :seon.error/diagnostic-offending nil, :seon.error/diagnostic-operation nil, :seon.error/at (java.util.Date.), :seon.error/diagnostic-cause nil})]
    (is ((schema/projection-validator (schema/handed-projection) :seon.error/base) complete))
    (is (= {:seon.error-test/context :kept
            :seon.error/diagnostic-layer :agent-boundary
            :seon.error/diagnostic-operation 'seon.error-test/check
            :seon.error/diagnostic-member :seon.error-test/value
            :seon.error/diagnostic-expected :int
            :seon.error/diagnostic-offending "not-an-int"
            :seon.error/diagnostic-cause :seon.error-test/schema-mismatch
            :seon.error/diagnostic-evidence-availability :seon.error/known
            :seon.error/diagnostic-evidence {:seon.error-test/path [0]}}
           (:seon.error/data complete)))
    (is (= :kept (get-in unavailable
                          [:seon.error/data :seon.error-test/context])))
    (is (= (zipmap [:seon.error/diagnostic-layer
                    :seon.error/diagnostic-operation
                    :seon.error/diagnostic-member
                    :seon.error/diagnostic-expected
                    :seon.error/diagnostic-offending
                    :seon.error/diagnostic-cause
                    :seon.error/diagnostic-evidence-availability
                    :seon.error/diagnostic-evidence]
                   (repeat :seon.error/unknown))
           (select-keys (:seon.error/data unavailable)
                        [:seon.error/diagnostic-layer
                         :seon.error/diagnostic-operation
                         :seon.error/diagnostic-member
                         :seon.error/diagnostic-expected
                         :seon.error/diagnostic-offending
                         :seon.error/diagnostic-cause
                         :seon.error/diagnostic-evidence-availability
                         :seon.error/diagnostic-evidence]))
        "unavailable evidence is typed and boundary context cannot replace it")))



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
        read-back (#'admit/semantic-value
                   (edn/read-string (:seon.error/data-edn fact)))
        full-read-back (#'admit/semantic-value
                        (edn/read-string
                         (:seon.error/data-content prepared)))]
    (testing "a value pr-str cannot survive reads back as EDN"
      (is (map? read-back)))
    (testing "disposable proc state is omitted before admission"
      (is (not (contains? read-back ::flow/state)))
      (is (not (contains? full-read-back ::flow/state))))
    (testing "and the Throwable is the printer's bounded data projection,
    never the live Throwable; the complete meaningful projection remains
    available for blob retrieval even when the inline preview elides it"
      (let [projected (get full-read-back ::flow/ex)]
        (is (map? projected))
        (is (= "boom" (:cause projected)))
        (is (= {} (:data projected)))))))

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
        retained (#'admit/semantic-value
                  (edn/read-string (:seon.error/data-edn fact)))]
    (is (true? (:seon.error/capped? fact)))
    (is (= (select-keys source [:seon.error/layer :seon.error/operation :seon.error/member :seon.error/message])
           (select-keys retained [:seon.error/layer :seon.error/operation :seon.error/member :seon.error/message])))
    (is (= :over-bound
           (get-in retained [:seon.sci.admit/remainder
                             :seon.sci.admit/reason])))))

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
  (test-support/with-database
   (fn [_connection]
     (try
       (instrument/apply! {:seon.config/on-core-error :panic})
       (let [thrown (try (wide-return-narrow-violation 1)
                         (catch Throwable throwable throwable))
             prepared (error/prepare (request thrown))
             fact (:seon.error/fact prepared)
             fact-bytes (alength (.getBytes (pr-str fact) "UTF-8"))]
         (is ((schema/projection-validator (schema/handed-projection) :seon.instrument/contract-error) (ex-data thrown))
             (pr-str fact))
         (is (str/includes? (:seon.instrument/actual fact) ":seon.ns/name")
             "the value at the violation path is kept, bounded by what it is")
         (is (not (str/includes? (:seon.instrument/actual fact) "wide evidence"))
             "the offending value is the leaf, never the request it rode in")
         (is (pos? (:seon.instrument/actual-size fact))
             "the offending value's own size is recorded")
         (is (< (:seon.instrument/actual-size fact)
                (:seon.error/data-size fact))
             "the offending value is measured, never the source it rode in")
         (is (<= fact-bytes evidence-bytes)
             (str "stored fault fact was " fact-bytes " UTF-8 bytes")))
       (finally (instrument/remove!))))))

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
                 {:seon.error/data {:seon.instrument/schema large, :seon.instrument/fn (quote seon.render.data/at), :seon.instrument/args large, :seon.instrument/arm :input}})
        prepared
        (error/prepare
         (assoc (request (assoc (transform-error failure)
                                ::flow/state {:cached-render disposable}))
                :seon.config.error/max-evidence-bytes inline-limit))
        fact (:seon.error/fact prepared)
        content (:seon.error/data-content prepared)
        fact-bytes (alength (.getBytes (pr-str fact) "UTF-8"))]
    (is (> (:seon.error/data-size fact) inline-limit))
    (is (<= fact-bytes inline-limit)
        (str "stored fault fact was " fact-bytes " UTF-8 bytes"))
    (is (<= (alength (.getBytes (:seon.error/data-edn fact) "UTF-8"))
            inline-limit))
    (is (not (str/includes? content "DISPOSABLE-PROC-STATE-")))
    (is (str/includes? content "seon.render.data/at"))
    (is (str/includes? content large)
        "the original diagnostic string remains available for blob retrieval")
    ;; A FIELD OVER THE FAULT'S OWN DECLARED BYTE BOUND IS MARKED, never
    ;; silently kept: the same `:seon.sci.admit/reason` data every other surface
    ;; reports an absent value with, and the whole field stays reachable in
    ;; the evidence content beside it.
    (is (str/includes? (:seon.error/message fact) ":reason :over-bound"))
    (is (str/includes? (:seon.instrument/expected fact) ":reason :over-bound"))
    (is (str/includes? (:seon.instrument/args fact) ":reason :over-bound"))
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
    (is (not= (:seon.error/data-edn fact)
              (:seon.error/data-content prepared))
        "whole-fact fitting can shorten evidence below the size threshold")
    (is (true? (:seon.error/capped? fact)))
    (is (= (get-in small [:seon.error/fact :seon.error/data-edn])
           (:seon.error/data-content small))
        "small evidence is not changed gratuitously")
    (is (false? (get-in small [:seon.error/fact :seon.error/capped?])))))

(deftest normalization-never-throws
  ;; the recursion fence, stated as a test: a source whose realization
  ;; throws must still produce a fact. Admission is called in :record
  ;; mode unconditionally, so there is no dial under which this becomes
  ;; a second error.
  (let [exploding (lazy-seq (throw (ex-info "realizing me throws" {})))
        fact (error/normalize (request {:seon.error/message "rejected", :seon.error/data {:rows exploding}}))]
    (is ((seon.schema/projection-validator (seon.schema/handed-projection) :seon.error/fact) fact))
    (is (str/includes? (:seon.error/data-edn fact) "seon.print/failed"))))

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
      (is ((schema/projection-validator (schema/handed-projection) :seon.error/base) fact)
          (str "source: " (pr-str source)))
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

(defn- rendered
  [notice output]
  (if (= output :ai)
    (error/ai-prose notice)
    (error/log-line notice)))

(deftest notices-carry-structured-projection-evidence
  (test-support/assert-check!
   (tc/quick-check
    80
    (prop/for-all [reason (gen/elements
                           [nil :your-run :no-attributable-agent
                            :recurring :failover])]
      (let [fact (fact)
            notice (error/notice
                    (cond-> {:seon.error/fact fact}
                      reason (assoc :seon.error/reason reason)))
            human (rendered notice :ai)
            line (rendered notice :log)]
        (and
         ((seon.schema/projection-validator (seon.schema/handed-projection) :seon.error/notice) notice)
         (= fact (:seon.error/fact notice))
         (= [:seon.error/id (:seon.error/id fact)]
            (:seon.error/evidence notice))
         (qualified-symbol? (:seon.render/ai notice))
         (and (string? human) (not (str/blank? human)))
         (and (string? line) (not (str/blank? line)))
         (not (str/includes? line "\n")))))
    :seed 202607280901)
   "structured error projections"))

(deftest the-projection-keys-are-derived-never-stored
  (let [fact (fact)]
    (is (not (contains? fact :seon.render/ai)))))

(deftest instrumentation-evidence-survives-normalization
  (let [violation {:seon.error/message "bad call", :seon.error/data {:seon.instrument/schema ":seon.error/fact", :seon.instrument/fn (quote seon.error/value), :seon.instrument/args "[\"not a fact\"]", :seon.instrument/arm :input}}
        fact (error/normalize
              (request (transform-error
                        (ex-info "bad call" violation))))]
    (is (= 'seon.error/value (:seon.instrument/fn fact)))
    (is (= :input (:seon.instrument/arm fact)))
    (is (= ":seon.error/fact" (:seon.instrument/expected fact)))
    (is (= "[\"not a fact\"]" (:seon.instrument/args fact)))
    (let [notice (error/notice {:seon.error/fact fact})
          prose (rendered notice :ai)]
      (is (= 'seon.error/instrumentation-prose (:seon.render/ai notice)))
      (is (not (str/blank? prose))))))

(deftest the-default-renderers-accept-an-attribute-shaped-error
  (test-support/with-database
   (fn [_]
     (let [value {:my.fs/error-path "/tmp/missing.edn"
                  :seon.error/message "No file exists at that path."}
           ai (error/render-ai value)
           html (error/render-html value)]
       (is (str/includes? ai (:seon.error/message value))
           "AI explains the failure without dumping internal evidence")
       (is (= :article (first html)))
       (is ((schema/projection-validator (schema/handed-projection) :seon.render/hiccup) html))
       (is (str/includes? (pr-str html) (:seon.error/message value)))))))

(deftest the-default-html-face-links-committed-evidence
  (let [html (error/render-html
              {:seon.error/id "err-42"
               :seon.error/message "Nothing recognized this error."})
        href (get-in (last html) [2 1 :href])]
    (is (str/starts-with? href "/data?"))
    (is (str/includes? href "%3Aseon.error%2Fid"))))

(deftest specialist-renderers-use-their-declared-evidence
  (test-support/with-database
   (fn [_connection]
    (testing "instrumentation names the failed arm and received value"
    (let [prose (error/instrumentation-prose
                 {:seon.instrument/fn 'my.fs/read
                  :seon.instrument/arm :input
                  :seon.instrument/expected ":my.fs/read-request"
                  :seon.instrument/args "[{:my.fs/path 42}]"
                  :seon.error/message "The call violated its contract."})]
      (is (str/includes? prose "Contract violation in my.fs/read input"))
      (is (str/includes? prose "path 42"))))
  (testing "refusal names the transition, rule, and atomic result"
    (let [prose (error/refusal-prose
                 {:seon.turn/id "run-7"
                  :seon.turn/rule :seon.turn/not-holder
                  :seon.turn/transition :seon.turn/close
                  :seon.error/message "The run is held elsewhere."})]
      (is (str/includes? prose "close of run-7"))
      (is (str/includes? prose "Nothing from this close committed"))))
  (testing "AI attempt prose exposes the decision attributes"
    (let [prose (error/ai-prose
                 {:seon.ai/request-transmitted? false
                  :seon.ai/response-started? false
                  :seon.ai/output-observed? false
                  :seon.error/message "The provider connection failed."})]
      (is (str/includes? prose "request transmitted: false"))
      (is (str/includes? prose "response started: false"))
      (is (str/includes? prose "output observed: false"))
      (is (str/includes? prose "configured failover may be safe"))))
  (testing "time-limit prose explains the diagnostic without treating it as a limit"
    (let [prose (error/time-limit-prose
                 {:seon.eval/fn-entries 271000000
                  :seon.error/message "Evaluation reached its time limit."})]
      (is (str/includes? prose "Recorded function-body entries: 271000000"))
      (is (str/includes? prose "indicate a spin"))))
  (testing "edit prose asks for a narrower source selection"
    (let [prose (error/edit-prose
                 {:my.edit/error-path "src/seon/error.clj"
                  :my.edit/edit-observation {:seon.error.evidence/attribute :my.edit/from-line
                                             :seon.error.evidence/value 1}
                  :seon.error/message "More than one form matched."})]
      (is (str/includes? prose "src/seon/error.clj"))
      (is (str/includes? prose "narrow the edit selection"))))
  (testing "render-walk elision stays neutral in both projections"
    (let [value {:seon.error/message "The bounded walk omitted content."}
          prose (error/elision-prose value)
          html (error/elision-html value)]
      (is (str/includes? prose "content was elided"))
      (is (not (str/includes? prose "error")))
      (is (= :aside (first html)))
      (is (= "seon-family-entry seon-render-elision"
             (get-in html [1 :class])))))
  (testing "unavailable domain evidence is explicit"
    (let [prose (error/unclassified-prose
                 {:seon.error/source {:unexpected/value 7}
                  :seon.error/message "Nothing recognized the source."})]
      (is (str/includes? prose "did not supply complete domain evidence"))
      (is (str/includes? prose "boundary contract"))))
  (testing "MCP lookup prose keeps the requested value identity"
    (let [digest (apply str (repeat 64 "a"))
          prose (error/mcp-prose
                 {:seon.dev.mcp/error-cluster "fixture"
                  :seon.dev.mcp/request-observation
                  {:seon.error.evidence/attribute :seon.blob/digest
                   :seon.error.evidence/value digest}
                  :seon.error/message "The value was absent."})]
      (is (str/includes? prose digest))
      (is (str/includes? prose "current cluster status"))))
  (testing "index refusal prose names the stopped phase"
    (let [prose (error/index-refusal-prose
                 {:seon.fn/analysis-phase :seon.fn/schema
                  :seon.fn/error-subject {:seon.instrument/actual "source.clj"
                                          :seon.error.projection/bound-bytes 256
                                          :seon.error/capped? false}
                  :seon.error/message "Schema indexing was refused."})]
      (is (str/includes? prose ":seon.fn/schema"))
      (is (str/includes? prose "rerun initialization")))))))

(deftest the-log-line-is-one-line-and-derived
  (let [fact (fact)
        line (rendered (error/notice {:seon.error/fact fact}) :log)]
    (is (not (str/includes? line "\n")) "a log line that wraps is two log lines")
    (is (not (str/blank? line)))))

(deftest the-flat-value-projects-from-the-fact
  (let [fact (fact)
        value (error/value fact)]
    (is ((seon.schema/projection-validator (seon.schema/handed-projection) :seon.error/base) value))
    (is (= (select-keys fact [:seon.error/at :seon.error/layer :seon.error/operation])
           (select-keys value [:seon.error/at :seon.error/layer :seon.error/operation])))
    (is (= (:seon.error/message fact) (:seon.error/message value)))
    (is (= (:seon.error/id fact) (:seon.error/id (:seon.error/data value)))
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
      (test-support/transacted! connection [{:seon.agent/id "root"}
                                          {:seon.agent/id "agent-3"}])
      (body connection))))

(defn- commit-request
  [source extra]
  (merge {:seon.schema/projection (schema/handed-projection)
           :seon.error/source source
          :seon.error/id (str (random-uuid))
          :seon.error/at #inst "2026-07-27T21:00:00.000-00:00"
          :seon.error/process process
          :seon.sci.admit/caps caps
          :seon.config.error/recurrence-limit 3
          :seon.config.error/max-evidence-bytes evidence-bytes
          :seon.config.error/escalate-to "root"}
         extra))

(defn- commit!
  "Commit one error and return [fact-count messages-by-recipient]."
  [connection source extra]
  (test-support/transacted! connection
                          (error/commit-tx @connection (commit-request source extra)))
  (let [db @connection]
    [(count (db/q '[:find ?e :where [?e :seon.error/id _]] db))
     ;; ?message is bound so two messages to one recipient are two
     ;; rows: a `[?to ...]` find returns a SET and would have counted
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
        (test-support/transacted! connection (error/commit-tx @connection request))
        (test-support/transacted! connection (error/commit-tx @connection request))
        (is (= 2 (db/q '[:find (sum ?n) . :where [_ :seon.error.occurrence/count ?n]]
                        (db/db connection))))
        (is (= 1 (count (db/q '[:find ?m :where [?m :seon.message/about]]
                              (db/db connection)))))))))
   (is (= 1 @applications) "The canonical cluster seed is the sole config writer.")))

(deftest only-a-throwable-tells-the-attributed-agent
  (with-db
    (fn [connection]
      (testing "a Throwable interrupted the agent's run, and it cannot know
      unless told"
        (let [[_ messages] (commit! connection
                                    (transform-error (ex-info "boom" {}))
                                    {:seon.agent/id "agent-3"
                                     :seon.turn/id "run-9"})]
          (is (= {"agent-3" 1} messages))))))
  (with-db
    (fn [connection]
      (testing "a refused transition is a VALUE: the run's own facts already
      say what happened, so the fact is recorded and nobody is mailed"
        (let [[facts messages]
              (commit! connection
                       {:seon.error/message "the run is held by another process"}
                       {:seon.agent/id "agent-3"
                        :seon.turn/id "run-9"})]
          (is (= 1 facts))
          (is (= {} messages)))))))

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
        (is (= 6 (db/q '[:find (sum ?n) . :where [_ :seon.error.occurrence/count ?n]] (db/db connection))))
        (is (= {"root" 1} messages)
            "D13: one notification for the root, independent of repeat count")))))

(deftest a-prepared-message-keeps-its-id-when-the-transaction-repeats
  (with-db
    (fn [connection]
      (let [source (transform-error (ex-info "boom" {}))
            request (commit-request source {:seon.agent/id "agent-3"
                                            :seon.turn/id "run-9"})
            tx (error/commit-tx @connection request)]
        ;; the SAME request committed twice: re-execution after a crash
        ;; must upsert, never double-send
        (test-support/transacted! connection tx)
        (test-support/transacted! connection tx)
        (let [db @connection]
          (is (= 1 (count (db/q '[:find ?e :where [?e :seon.error/id _]] db))))
          (is (= 1 (count (db/q '[:find ?m :where
                                 [?m :seon.message/about _]]
                               db))))))))) 

(deftest the-message-points-at-the-fact-it-explains
  (with-db
    (fn [connection]
      (commit! connection (transform-error (ex-info "boom" {})) {})
      (let [db @connection
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
          (is (= target (:seon.instrument/fn stored)))
          (is (nil? (db/pull database [:db/id] [:seon.fn/sym target])))
          (is (nil? (db/pull database [:db/id] [:seon.ns/name 'my.mint]))))))))


(deftest new-error-facets-compose-and-report-missing-members
  (test-support/with-database
   (fn [connection]
     (let [projection (schema/projection-from-database (db/db connection))
           base {:seon.error/at #inst "2026-09-18T00:00:00Z"
                 :seon.error/layer :seon.db/read :seon.error/operation 'seon.db/q}
           matching (partial error/facets projection)
           read-error (gen/generate error/read-operation-agrees-generator 4 20260918)
           combined (assoc read-error :seon.agent/error-agent-id "manifest-agent"
                           :seon.turn/error-turn-id "manifest-turn")
           missing (dissoc combined :seon.agent/error-agent-id)]
       (is ((schema/projection-validator projection :seon.error/base) base))
       (is (= #{} (matching base)) "Valid base-only error: no domain facet matched.")
       (is (= #{} (matching (dissoc combined :seon.error/operation)))
           "A malformed base cannot satisfy a facet.")
       (is (not ((matching combined) :seon.error/base)))
       (is (identical? (error/facet-keys projection) (error/facet-keys projection))
           "Only projection-derived population is memoised.")
       (is (every? (matching combined) [:seon.db.read/error :seon.turn/error :seon.agent/error]))
       (is (false? ((schema/projection-validator projection :seon.turn/error) missing)))
       (is (some #(= [:seon.agent/error-agent-id] (:in %))
                 (:errors ((schema/projection-explainer projection :seon.turn/error) missing))))))))

(deftest arity-facet-preserves-real-refusal-observations
  (test-support/with-database
   (fn [connection]
     (let [projection (schema/projection-from-database (db/db connection))
           observed-at (java.util.Date.)
           refusal (test-support/refusal-data #(apply seon.id/valid? []))
           data (:seon.error/data refusal)
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
       (is (validate value) (pr-str ((schema/projection-explainer projection :seon.instrument/arity-error) value)))
       (is (false? (validate (assoc value :seon.instrument/arity (:min info)))))
       (is (false? (validate (assoc value :seon.instrument/declared-arity-count 2))))))))

(deftest complete-error-children-validate-through-the-writer
  (let [forms (assoc (schema.edn/packaged-forms)
                     ::manifest-id [:string {:seon.db/identity true}]
                     ::manifest-location [:and {:seon.db/component true
                                               :seon.db/component-schema :seon.error.location/entity} :seon.db/ref]
                     ::manifest-observation [:and {:seon.db/component true
                                                   :seon.db/component-schema :seon.agent/error} :seon.db/ref]
                     ::manifest-explanations [:and {:seon.db/component true
                                                    :seon.db/component-schema :seon.instrument.explanations/entity} :seon.db/ref]
                     ::manifest-root [:map {:seon.db/attributes true}
                                      [::manifest-id ::manifest-id]
                                      [::manifest-location ::manifest-location]
                                      [::manifest-observation {:optional true} ::manifest-observation]
                                      [::manifest-explanations {:optional true} ::manifest-explanations]])
        projection (schema/build-projection forms)]
    (test-support/with-database
     {::test-support/extra-schema
      (schema.datahike/malli->datahike-schema-in projection [::manifest-id ::manifest-location ::manifest-observation ::manifest-explanations])}
     (fn [connection]
       (db/carry-connection-projection-state!
        connection (sci.eval/projection-state @connection projection))
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
             explanations
             {:seon.instrument.explanations/count 2
              :seon.instrument.explanations/items
              (set (for [n (range 2)]
                     {:seon.instrument.explanation/ordinal n
                      :seon.instrument.explanation/schema-location {:seon.error.location/length 0}
                      :seon.instrument.explanation/value-location {:seon.error.location/length 0}
                      :seon.instrument.explanation/expected-shape (apply str (repeat 64 "0"))
                      :seon.instrument.explanation/humanized {:seon.instrument.humanized/message-count 0}}))}
             report (test-support/transacted!
                     connection [{::manifest-id "large-path" ::manifest-location large-location
                                  ::manifest-observation observation ::manifest-explanations explanations}
                                 {::manifest-id "another-observation"
                                  ::manifest-location {:seon.error.location/length 0}
                                  ::manifest-observation observation}])
             database (db/db connection)
             root (:db/id (db/pull database [:db/id] [::manifest-id "large-path"]))
             location (:v (first (db/datoms database :eavt root ::manifest-location)))
             problems (:v (first (db/datoms database :eavt root ::manifest-explanations)))
             ; This observation is non-unique and unindexed; AEVT contains every attribute datom.
             observations (filter #(= "same-observed-agent" (:v %))
                                  (db/datoms database :aevt :seon.agent/error-agent-id))]
         (is (= 1001 (count (db/datoms database :eavt location :seon.error.location/segments))))
         (is (= 2 (count (db/datoms database :eavt problems :seon.instrument.explanations/items))))
         (is (= 2 (count (set (map :e observations)))) "Observed identity tokens cannot upsert two errors together.")
         (is (seq (:tx-data report)))
         (println "ERROR-MANIFEST stored-large-value"
                  {:entities (count (set (map :e (:tx-data report)))) :datoms (count (:tx-data report))
                   :segments 1001 :problems 2 :observations (count observations)}))
       (let [database (db/db connection)
             root (:db/id (db/pull database [:db/id] [::manifest-id "missing-key"]))
             location (:v (first (db/datoms database :eavt root ::manifest-location)))
             segment (:v (first (db/datoms database :eavt location :seon.error.location/segments)))
             before (db/basis-t database)
             result (db/transact! connection [[:db/add segment :seon.error.location.segment/ordinal 3]])]
         (is ((schema/projection-validator (schema/handed-projection) :seon.db.write/validation-refusal) result) (pr-str result))
         (is (= [[:db/add segment :seon.error.location.segment/ordinal 3]]
                (get-in result [:seon.error/data :seon.db.write.attempt/transaction])))
         (is (= before (get-in result [:seon.error/basis :seon.error.basis/t])))
         (is (= before (db/basis-t (db/db connection))))
         (is (= 2 (count (db/datoms database :eavt location :seon.error.location/segments))))
         (let [recording (error/recording database (commit-request result {}))
               report (test-support/transacted! connection (:seon.db/tx-data recording))
               stored (db/pull (:db-after report) (error/observation-selector projection)
                               (:seon.error/ref recording))
               occurrence (first (:seon.error/occurrences stored))
               attempt (:seon.db.write/attempt occurrence)]
           (is (seq (:seon.error/occurrences stored)) (pr-str stored))
           (is (= (:seon.db.write.attempt/request-id result)
                  (:seon.db.write.attempt/request-id attempt)))
           (is (= before (get-in occurrence [:seon.error/basis :seon.error.basis/t])))
           (is (= (mapv :v (db/datoms database :eavt segment :seon.error.location.segment/ordinal))
                  (mapv :v (db/datoms (:db-after report) :eavt segment :seon.error.location.segment/ordinal)))
               "The recorder stores the submitted transaction as evidence; it never executes it.")
           (is (= (get-in result [:seon.error/data :seon.db.write.attempt/transaction])
                  (admit/semantic-value
                   (edn/read-string (get-in attempt [:seon.db.write.attempt/operations :seon.instrument/actual])))))
           (is (false? (get-in attempt [:seon.db.write.attempt/operations :seon.error/capped?])))
           (is (not (contains? occurrence :seon.db.write.attempt/request-id)))))))))


(deftest schema-refusals-are-admitted-at-the-recorder
  (test-support/with-database
   (fn [connection]
     (let [projection (schema/handed-projection)
           unavailable {:seon.error-test/unavailable true}
           raw (try (schema/projection-from-database unavailable)
                    (catch clojure.lang.ExceptionInfo failure (ex-data failure)))
           recording (error/recording (db/db connection) (commit-request raw {}))
           report (test-support/transacted! connection (:seon.db/tx-data recording))
           root (db/pull (:db-after report) (error/observation-selector projection)
                         (:seon.error/ref recording))
           occurrence (first (:seon.error/occurrences root))]
       (is ((schema/projection-validator projection :seon.schema/validation-refusal) raw) (pr-str raw))
       (is ((schema/projection-validator projection :seon.schema/error) (error/latest-fact root)))
       (is (= unavailable
              (admit/semantic-value
               (edn/read-string (get-in occurrence [:seon.schema/error-declaration :seon.instrument/actual])))))
       (is (= :seon.db/database-value
              (admit/semantic-value
               (edn/read-string (get-in occurrence [:seon.schema/declaration-expectation :seon.instrument/actual])))))
       (is (not (contains? occurrence :seon.schema/refused-value)))
       (is (= (second (:seon.error/ref recording))
              (:seon.error/signature (error/normalize (request (error/latest-fact root))))))))))

(deftest cause-chain-reading-preserves-the-deepest-complete-observation
  (let [observation {:seon.error/at #inst "2026-09-20T00:00:00Z"
                     :seon.error/layer :seon.error-test/cause-chain
                     :seon.error/operation 'seon.error-test/check
                     :seon.error/message "A complete refusal"
                     :seon.agent/error-agent-id "observed"}
        underlying (ex-info "Underlying evidence" {:seon.error-test/evidence "retained"})
        refusal (ex-info "Refused" observation underlying)]
    (is (= observation (error/refusal (ex-info "Wrapper" {:seon.error-test/wrapper true} refusal))))))

(deftest error-facets-persist-through-the-real-occurrence-owner
  (test-support/with-database
   (fn [connection]
     (test-support/seed-cluster! connection "error-family-1a")
     (let [projection (schema/projection-from-database (db/db connection))
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
       (is (= :seon.db/unowned-entity
              (get-in unowned [:seon.error/data :seon.db/diagnostic-cause]))
           (pr-str unowned))
       (is (= before (db/basis-t (db/db connection))))
       (prn {::unowned-refusal unowned})
       (is ((schema/projection-validator projection :seon.instrument/arity-error) armed)
           (pr-str armed))
       (doseq [[source required-facets]
               [[observed #{:seon.agent/error :seon.turn/error}]
                [armed #{:seon.instrument/arity-error}]
                [(assoc observed :seon.error/location
                        {:seon.error.location/length 1001
                         :seon.error.location/segments
                         (set (for [ordinal (range 1001)]
                                {:seon.error.location.segment/ordinal ordinal
                                 :seon.error.location.segment/key
                                 {:seon.error.key/projection (str ordinal)
                                  :seon.error.key/capped? false
                                  :seon.error.key/bound-bytes 256}}))})
                 #{:seon.agent/error :seon.turn/error}]]]
         (let [recording (error/recording (db/db connection)
                                           (commit-request source {:seon.error/at (:seon.error/at source)}))
               report (test-support/transacted! connection (:seon.db/tx-data recording))
               database (db/db connection)
               root (db/pull database (error/observation-selector projection) (:seon.error/ref recording))
               occurrence (first (:seon.error/occurrences root))]
           (is (seq (:tx-data report)))
           (is (= required-facets (error/facets projection source)))
           ;; Stored entity contracts and pull-result contracts have distinct
           ;; collection grammars. Validate the read through its derived form.
           (doseq [facet required-facets]
             (let [occurrence-selector
                   (some #(when (map? %)
                            (get % [:seon.error/occurrences :limit nil]))
                         (error/observation-selector projection))
                   pulled-form (schema/pulled-form-in projection facet occurrence-selector)
                   registry (:seon.schema.projection/registry projection)
                   scalar-attributes
                   (remove #(some-> (mr/schema registry %) malli.core/properties
                                :seon.db/component)
                           (map first (schema.internal/entity-entries (mr/schema registry facet))))]
               (is (true? (malli.core/validate pulled-form occurrence
                                               (:seon.schema.projection/compile-options projection)))
                   (pr-str {:facet facet :location-length
                            (get-in occurrence [:seon.error/location :seon.error.location/length])}))
               (is (= (select-keys source scalar-attributes)
                      (select-keys occurrence scalar-attributes)))))
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
             (is (= (:seon.agent/error-agent-id source)
                    (:seon.agent/error-agent-id latest))))
           (is (some #(= (:db/id occurrence) (:db/id %)) (:seon.error/occurrences root)))
           ;; Record the acquisition question without asserting that a stored
           ;; entity validator is a pull-result validator.
           (when-not (:seon.error/location source)
             (prn {::stored-occurrence occurrence ::required-facets required-facets
                   ::authored-facets (error/facets projection source)
                   ::pulled-facets (error/facets projection occurrence)}))))))))

(deftest recurrence-identity-is-the-complete-observations-stable-evidence
  (test-support/with-database
   (fn [connection]
     (test-support/seed-cluster! connection "error-family-d13")
     (let [projection (schema/projection-from-database (db/db connection))
           observed {:seon.error/at #inst "2026-09-19T00:00:00Z"
                     :seon.error/layer :seon.agent/lifecycle
                     :seon.error/operation 'seon.agent/by-id
                     :seon.error/expected-key :seon.agent/id
                     :seon.agent/error-agent-id "observed-agent"}
           record! (fn [source process]
                     (let [recorded (error/recording
                                     (db/db connection)
                                     (commit-request source {:seon.error/process process}))]
                       (test-support/transacted! connection (:seon.db/tx-data recorded))
                       recorded))
           root (fn [recorded]
                  (db/pull (db/db connection) (error/observation-selector projection)
                           (:seon.error/ref recorded)))
           first-record (record! observed "d13-process-a")
           second-record (record! observed "d13-process-a")
           after-two (root first-record)]
       (is (= (:seon.error/ref first-record) (:seon.error/ref second-record)))
       (is (= 2 (:seon.error.occurrence/count (first (:seon.error/occurrences after-two)))))
       (is (= (second (:seon.error/ref first-record))
              (:seon.error/signature
               (error/normalize (request (error/latest-fact after-two)))))
           "A complete acquired observation has the same identity as its authored value.")
       (let [changed-incidental
             (assoc observed :seon.error/at #inst "2026-09-20T00:00:00Z"
                             :seon.error/message "Different explanation"
                             :seon.error/data {:seon.error/diagnostic-offending "different bytes"}
                             :seon.agent/error-agent-id "another-observed-agent")
             third-record (record! changed-incidental "d13-process-b")]
         (is (= (:seon.error/ref first-record) (:seon.error/ref third-record)))
         (is (= 3 (:seon.error/occurrence-count (error/latest-fact (root first-record))))))
       (let [before (root first-record)
             added-facet (record! (assoc (error/latest-fact before)
                                        :seon.turn/error-turn-id "observed-turn")
                                 "d13-process-a")
             other-facet (record! (-> observed
                                     (dissoc :seon.agent/error-agent-id)
                                     (assoc :seon.turn/error-turn-id "observed-turn"))
                                 "d13-process-a")
             other-schema (record! (assoc observed :seon.error/expected-key :seon.turn/id)
                                  "d13-process-a")
             other-path (record! (assoc observed :seon.error/location
                                       {:seon.error.location/length 1
                                        :seon.error.location/segments
                                        #{{:seon.error.location.segment/ordinal 0
                                           :seon.error.location.segment/key
                                           {:seon.error.key/scalar :seon.agent/id
                                            :seon.error.key/projection ":seon.agent/id"
                                            :seon.error.key/capped? false
                                            :seon.error.key/bound-bytes 256}}}})
                                "d13-process-a")]
         (is (= 5 (count (set (map :seon.error/ref
                                   [first-record added-facet other-facet other-schema other-path])))))
         (is (= before (root first-record)) "Adding a facet leaves prior occurrences untouched.")
         (is (= 5 (count (db/q '[:find ?root :where [?root :seon.error/signature]]
                               (db/db connection))))))))))

(deftest every-error-owner-function-declares-its-input-and-output
  (test-support/with-database
   (fn [_connection]
     (doseq [[function candidate] (ns-interns 'seon.error)
             :when (and (bound? candidate) (fn? @candidate))]
       (is (some? (:malli/schema (meta candidate))) (str function))))))

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

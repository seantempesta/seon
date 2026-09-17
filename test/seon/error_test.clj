(ns seon.error-test
  "Sealed acceptance draft for the ONE error normalizer and its
  projections.

  DRAFT FOR ORCHESTRATOR SEAL (drafted 2026-07-27, step 1 of the
  error-wiring order). The implementation lane makes these green by
  implementing `seon.error` and `seon.render` ONLY — schemas and tests
  are byte-sealed; friction is reported, never resolved by weakening.

  THE STANDING PROPERTY is `normalization-is-total`: over all three
  input families, every normalization validates `:seon.error/fact`,
  projects to a valid flat `:seon.error/value`, and prints a `data-edn`
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
            [seon.problems]
            [seon.cluster.status]
            [seon.render.transcript]
            [seon.schema :as schema]
            [seon.sci.admit :as admit]
            [seon.test-support :as test-support]
            [seon.db :as db]))

(declare commit-request)

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
                                           {:seon.turn/id "error-graph-turn" :seon.turn/agent [:seon.agent/id "error-graph-a"] :seon.turn/opened-tx "datomic.tx"}])))
            a (error/recording (db/db connection) (request "error-graph-a" "error-graph-process-1" at))
            b (error/recording (db/db connection) (request "error-graph-b" "error-graph-process-1" at))
            read-error #(db/pull (db/db connection)
                                 '[* {:seon.error/fn [:seon.fn/sym {:seon.fn/ns [:seon.ns/name {:seon.ns/steward [:seon.agent/id]}]}]}
                                   {:seon.error/occurrences [* {:seon.error.occurrence/agent [:seon.agent/id]}]}]
                                 (:seon.error/ref a))]
        (is (= (:seon.error/ref a) (:seon.error/ref b)))
        (is (not= (:seon.error.occurrence/ref a) (:seon.error.occurrence/ref b)))
        (let [written (db/transact! connection (:seon.db/tx-data a))] (is (:db-after written) (pr-str (dissoc written :db-before :db-after)) ))
        (is (:db-after (db/transact! connection (:seon.db/tx-data b))))
        (let [row (read-error)]
          (is (= 1 (count (db/q '[:find ?e :where [?e :seon.error/signature]] (db/db connection)))))
          (is (= [1 1] (sort (map :seon.error.occurrence/count (:seon.error/occurrences row)))))
          (is (= "my.error-graph/raise" (get-in row [:seon.error/fn :seon.fn/sym])))
          (is (= 'my.error-graph (get-in row [:seon.error/fn :seon.fn/ns :seon.ns/name])))
          (is (= "error-graph-steward" (get-in row [:seon.error/fn :seon.fn/ns :seon.ns/steward :seon.agent/id])))
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
          (is (= 6 (get (into {} (:seon.cluster.status/faults (seon.cluster.status/snapshot {:seon.db/db database :seon.db/connection connection})))
                        (second (:seon.error/ref a)))))
          (is (str/includes? (pr-str (#'seon.render.transcript/fault-problems database "error-graph-steward" [])) "occurrences: 6"))
          (is (str/includes? (error/log-line (error/notice {:seon.error/fact (:seon.error/fact summary) :seon.error/occurrence-count 6})) "occurrences=6"))
          (is (str/includes? (error/render-ai row) "Occurrences: 6"))
          (is (str/includes? (pr-str (error/render-html row)) "Open"))
          (is (= 1 (count (db/q (second (second read-form)) database (last read-form)))))
          (is (str/includes? (pr-str (error/render-faults-html [:seon.agent/id "error-graph-steward"] database)) "same error"))
          (is (= 6 (:seon.render.transcript/count (first (#'seon.render.transcript/fault-problems database "error-graph-steward" [])))))
          (is (:db-after (db/transact! connection [[:db/add (:seon.error/ref a) :seon.error/resolved-tx "datomic.tx"]])))
          (is (str/includes? (pr-str (error/render-html (read-error))) "Resolved"))
          (is (schema/valid-candidate-value? :seon.error/fact (:seon.error/fact (first (#'seon.problems/error-signatures (db/db connection)))))))
        (let [flat (error/recording (db/db connection)
                                  (commit-request {:seon.error/kind :seon.error/unclassified
                                                   :seon.error/message "flat error"
                                                   :seon.error/data {:seon.error/diagnostic-operation 'seon.id/valid?}} {}))]
          (is (:seon.error/ref flat))
          (is (= [:seon.fn/sym "seon.id/valid?"] (:seon.error/fn (:seon.error/fact flat))))
          (is (not (contains? (:seon.error/fact flat) :seon.error/exception-class)))
          (is (:db-after (db/transact! connection (:seon.db/tx-data flat)))))))))

(deftest dropped-fault-counts-accumulate-in-the-occurrence
  (test-support/with-database
    (fn [connection]
      (doseq [n [3 5]]
        (let [request (commit-request {:seon.error/kind :seon.flow/fault-channel-overflow
                                       :seon.error/message "dropped deliveries"} {})
              fact (assoc (error/normalize request)
                          :seon.error/dropped-fault-count n
                          :seon.error/dropped-fault-digest (apply str (repeat 64 "a")))
              result (db/transact! connection
                                  (error/commit-tx (db/db connection) (assoc request :seon.error/fact fact)))]
          (is (:db-after result))))
      (let [database (db/db connection)
            row (first (db/q '[:find [(pull ?o [*]) ...]
                              :where [?e :seon.error/kind :seon.flow/fault-channel-overflow]
                                     [?e :seon.error/occurrences ?o]] database))]
        (is (= 2 (:seon.error.occurrence/count row)))
        (is (= 8 (:seon.error/dropped-fault-count row)))
        (is (= (apply str (repeat 64 "a")) (:seon.error/dropped-fault-digest row)))))))

(deftest error-class-recognition-uses-the-active-registry
  (let [projection (schema/build-projection (schema/registered-schemas))]
    (with-redefs [schema/current-projection (constantly projection)]
      (is (true? (error/error? {:my.fs/not-found "tmp/absent"
                                :seon.error/message "File not found."})))
      (is (false? (error/error? {:seon.error/message "Only a message."})))
      (is (false? (error/error? :not-a-map))))))

(deftest error-class-recognition-has-a-registry-free-leaf-fallback
  (with-redefs [schema/current-projection (constantly nil)]
    (is (true? (error/error? {:seon.error/message "Reader refusal."})))
    (is (false? (error/error? {})))
    (is (false? (error/error? "Reader refusal.")))))

;;; ---------------------------------------------------------------------------
;;; Fixtures — the three families, plus the hostile values
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
   (merge {:seon.error/source source
           :seon.error/id "err-1"
           :seon.error/at #inst "2026-07-27T21:00:00.000-00:00"
           :seon.error/process process
           :seon.sci.admit/caps caps
           ;; THE FAULT FAMILY'S BOUND IS A DECLARED MEMBER of the request,
           ;; exactly like production's committer supplies it: `prepare` no
           ;; longer falls back to a bootstrap number when a caller omits it.
           :seon.config.error/max-evidence-bytes evidence-bytes}
          extra)))

(deftest diagnostic-construction-is-evidence-complete
  (let [complete
        (error/diagnostic
         {:seon.error/kind :seon.error-test/invalid-call
          :seon.error/message "The call was invalid."
          :seon.error/diagnostic-layer :agent-boundary
          :seon.error/diagnostic-operation 'seon.error-test/check
          :seon.error/diagnostic-member :seon.error-test/value
          :seon.error/diagnostic-expected :int
          :seon.error/diagnostic-offending "not-an-int"
          :seon.error/diagnostic-cause :seon.error-test/schema-mismatch
          :seon.error/diagnostic-evidence {:seon.error-test/path [0]}
          :seon.error/data {:seon.error-test/context :kept}})
        unavailable
        (error/diagnostic
         {:seon.error/kind :seon.error-test/unavailable
          :seon.error/message "The evidence could not be observed."
          :seon.error/diagnostic-layer nil
          :seon.error/diagnostic-operation nil
          :seon.error/diagnostic-member nil
          :seon.error/diagnostic-expected nil
          :seon.error/diagnostic-offending nil
          :seon.error/diagnostic-cause nil
          :seon.error/diagnostic-evidence nil
          :seon.error/data
          {:seon.error/diagnostic-layer :cannot-replace
           :seon.error/diagnostic-evidence-availability :cannot-replace
           :seon.error-test/context :kept}})]
    (is (schema/valid-candidate-value? :seon.error/value complete))
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

(deftest exact-dispatch-producers-carry-their-class-markers
  ;; THE SUBJECT IS THE CLASS MARKER, not the diagnostic constructor.
  ;; `seon.error/diagnostic` declares every diagnostic field required, so
  ;; building these representatives through it handed it a shape its contract
  ;; forbids; each is an ordinary flat error value carrying one class marker,
  ;; which is exactly what `error?` dispatches on.
  (let [representatives
        [ {:my.fs/stale-digest "tmp/stale"
          :seon.error/kind :my.fs/stale-digest
           :seon.error/message "stale"}
          {:my.fs/invalid-utf8-window "tmp/not-utf8"
          :seon.error/kind :my.fs/invalid-utf8-window
           :seon.error/message "not UTF-8"}
          {:seon.cluster.reply/refused-tag 'secret/tag
          :seon.error/kind :seon.cluster.reply/refused-tag
           :seon.error/message "tag refused"}
          {:seon.db/transaction-outcome-unknown true
          :seon.error/kind :seon.db/unknown-failure
           :seon.error/message "outcome unknown"}
          {:seon.instrument/contract-violated "sample/fn"
          :seon.error/kind :seon.instrument/contract-violated
           :seon.error/message "contract violated"}
          {:seon.render.walk/elided true
          :seon.error/kind :seon.render.walk/elided
           :seon.error/message "elided"}
          {:seon.ai/stream-truncated true
          :seon.error/kind :seon.ai/stream-truncated
           :seon.error/message "stream truncated"}
          {:seon.cluster.reply/unreadable "["
          :seon.error/kind :seon.cluster.reply/unreadable
           :seon.error/message "unreadable"}
          {:seon.turn.loop/phase-failed true
          :seon.error/kind :seon.turn.loop/phase-failed
           :seon.error/message "phase failed"}
          {:seon.turn.loop/lint-rejected true
          :seon.error/kind :seon.turn.loop/lint-rejected
           :seon.error/message "lint rejected"}
          {:seon.operator/collection-incomplete true
          :seon.error/kind :seon.operator/collection-incomplete
           :seon.error/message "collection incomplete"}]
        projection (schema/build-projection (schema/registered-schemas))]
    (with-redefs [schema/current-projection (constantly projection)]
      (doseq [value representatives]
        (is (true? (error/error? value))
            (str "class marker was not recognized for "
                 (:seon.error/kind value)))))))

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
                             {:seon.error/kind kind
                              :seon.turn/id "run-9"}))))

;;; ---------------------------------------------------------------------------
;;; Generators — honest, by constructing the real things
;;; ---------------------------------------------------------------------------

(def ^:private throwable-gen
  (gen/fmap (fn [[message kind]]
              (ex-info message {:seon.error/kind kind}))
            (gen/tuple (gen/not-empty gen/string-alphanumeric)
                       (gen/elements [:seon.turn/refused
                                      :seon.db/rejected
                                      :seon.ai/timeout
                                      :seon.boot/refused]))))

(def ^:private flow-error-gen
  (gen/fmap (fn [[shape throwable]] (shape throwable))
            (gen/tuple (gen/elements [transform-error
                                      proc-loop-error
                                      xform-error])
                       throwable-gen)))

(def ^:private flat-value-gen
  (gen/fmap (fn [[kind message data]]
              (cond-> {:seon.error/kind kind :seon.error/message message}
                data (assoc :seon.error/data data)))
            (gen/tuple (gen/elements [:seon.ai/no-credential
                                      :seon.db/unknown-failure
                                      :seon.config/refused])
                       (gen/not-empty gen/string-alphanumeric)
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
            (and (seon.schema/valid-candidate-value? :seon.error/fact fact)
                 (seon.schema/valid-candidate-value? :seon.error/value
                                                     (error/value fact))
                 ;; fail-closed, so a kind query can never silently miss
                 (keyword? (:seon.error/kind fact))
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
  (let [small (error/normalize (request {:seon.error/kind :seon.ai/timeout
                                         :seon.error/message "slow"}))
        wide (error/normalize
              (request {:seon.error/kind :seon.ai/timeout
                        :seon.error/message "slow"
                        :seon.error/data {:rows (vec (range 10000))}}))]
    (is (false? (:seon.error/capped? small))
        "a source that fits is not reported as capped")
    (is (true? (:seon.error/capped? wide))
        "a source wider than the caps says so")))

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
                 {:seon.error/kind :seon.instrument/contract-violated
                  :seon.error/data
                  {:seon.instrument/fn "seon.render.data/at"
                   :seon.instrument/arm :input
                   :seon.instrument/schema large
                   :seon.instrument/args large}})
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
                [:seon.error/id :seon.error/kind :seon.error/message
                 :seon.error/signature :seon.error/data-edn
                 :seon.error/data-size]))))

(deftest fitting-can-require-a-blob-below-the-content-size-threshold
  (let [inline-limit 4096
        near-limit (apply str (repeat 2000 "q"))
        prepared
        (error/prepare
         (assoc (request {:seon.error/kind :seon.error-test/near-limit
                          :seon.error/message near-limit})
                :seon.config.error/max-evidence-bytes inline-limit))
        fact (:seon.error/fact prepared)
        small
        (error/prepare
         (assoc (request {:seon.error/kind :seon.error-test/short
                          :seon.error/message "short"})
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
        fact (error/normalize (request {:seon.error/kind :seon.db/rejected
                                        :seon.error/message "rejected"
                                        :seon.error/data {:rows exploding}}))]
    (is (seon.schema/valid-candidate-value? :seon.error/fact fact))
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
  (let [fact (error/normalize (request {:seon.error/kind :seon.ai/no-credential
                                        :seon.error/message "unset"}))]
    (is (not (contains? fact :seon.error/throwable-class)))
    (is (= :seon.ai/no-credential (:seon.error/kind fact)))
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

(deftest the-kind-comes-from-the-deepest-ex-data
  (let [fact (error/normalize
              (request (transform-error
                        (refused-chain :seon.turn/not-the-holder))))]
    (is (= :seon.turn/not-the-holder (:seon.error/kind fact))
        "the wrappers carry :error and {} — the rule is at the bottom")))

(deftest an-unclassifiable-source-is-fail-closed-never-absent
  (doseq [source [42 "a string" {:not-an-error true} nil]]
    (let [fact (error/normalize (request source))]
      (is (= :seon.error/unclassified (:seon.error/kind fact))
          (str "source: " (pr-str source)))
      (is (seon.schema/valid-candidate-value? :seon.error/fact fact)))))

(deftest attribution-is-a-lookup-ref-or-nothing
  (let [with (error/normalize (request {:seon.error/kind :seon.db/rejected
                                        :seon.error/message "no"}
                                       {:seon.turn/id "run-9"
                                        :seon.agent/id "agent-3"}))
        without (error/normalize (request {:seon.error/kind :seon.db/rejected
                                           :seon.error/message "no"}))]
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
                      (request {:seon.error/kind :seon.db/rejected
                                :seon.error/message message}))))]
    (is (= (signature "run 8b1c failed at 21:00:01")
           (signature "run 44de failed at 21:00:09"))
        "an id or a timestamp in the message must not make every occurrence unique")))

(deftest the-signature-separates-different-errors
  (let [signature (fn [kind]
                    (:seon.error/signature
                     (error/normalize (request {:seon.error/kind kind
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
         (seon.schema/valid-candidate-value? :seon.error/notice notice)
         (= (:seon.error/kind fact) (:seon.error/kind notice))
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
  (let [violation {:seon.error/kind :seon.instrument/contract-violated
                   :seon.error/message "bad call"
                   :seon.error/data
                   {:seon.instrument/fn "seon.error/value"
                    :seon.instrument/arm :input
                    :seon.instrument/schema ":seon.error/fact"
                    :seon.instrument/args "[\"not a fact\"]"}}
        fact (error/normalize
              (request (transform-error
                        (ex-info "bad call" violation))))]
    (is (= "seon.error/value" (:seon.instrument/fn fact)))
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
     (let [value {:my.fs/not-found "/tmp/missing.edn"
                  :my.fs/path "/tmp/missing.edn"
                  :seon.error/message "No file exists at that path."}
           ai (error/render-ai value)
           html (error/render-html value)]
       (is (str/includes? ai (:seon.error/message value))
           "AI explains the failure without dumping internal evidence")
       (is (= :article (first html)))
       (is (schema/valid-candidate-value? :seon.render/hiccup html))
       (is (str/includes? (pr-str html) (:seon.error/message value)))))))

(deftest the-default-html-face-links-committed-evidence
  (let [html (error/render-html
              {:seon.error/unclassified true
               :seon.error/id "err-42"
               :seon.error/message "Nothing recognized this error."})
        href (get-in (last html) [2 1 :href])]
    (is (str/starts-with? href "/data?"))
    (is (str/includes? href "%3Aseon.error%2Fid"))))

(deftest specialist-class-renderers-accept-flat-error-values
  (testing "instrumentation names the failed arm and received value"
    (let [prose (error/instrumentation-prose
                 {:seon.instrument/contract-violated true
                  :seon.instrument/fn "my.fs/read"
                  :seon.instrument/arm :input
                  :seon.instrument/expected ":my.fs/read-request"
                  :seon.instrument/args "[{:my.fs/path 42}]"
                  :seon.error/message "The call violated its contract."})]
      (is (str/includes? prose "Contract violation in my.fs/read input"))
      (is (str/includes? prose "path 42"))))
  (testing "refusal names the transition, rule, and atomic result"
    (let [prose (error/refusal-prose
                 {:seon.turn/refused true
                  :seon.turn/id "run-7"
                  :seon.turn/rule :seon.turn/not-holder
                  :seon.turn/transition :seon.turn/close
                  :seon.error/message "The run is held elsewhere."})]
      (is (str/includes? prose "close of run-7"))
      (is (str/includes? prose "Nothing from this close committed"))))
  (testing "AI attempt prose exposes the decision attributes"
    (let [prose (error/ai-prose
                 {:seon.ai/transport-failure true
                  :seon.ai/request-transmitted? false
                  :seon.ai/response-started? false
                  :seon.ai/output-observed? false
                  :seon.error/message "The provider connection failed."})]
      (is (str/includes? prose "request transmitted: false"))
      (is (str/includes? prose "response started: false"))
      (is (str/includes? prose "output observed: false"))
      (is (str/includes? prose "configured failover may be safe"))))
  (testing "time-limit prose explains the diagnostic without treating it as a limit"
    (let [prose (error/time-limit-prose
                 {:seon.sci.eval/time-limit 271000000
                  :seon.error/message "Evaluation reached its time limit."})]
      (is (str/includes? prose "Recorded function-body entries: 271000000"))
      (is (str/includes? prose "indicate a spin"))))
  (testing "edit prose asks for a narrower source selection"
    (let [prose (error/edit-prose
                 {:my.edit/ambiguous-match "src/seon/error.clj"
                  :seon.error/message "More than one form matched."})]
      (is (str/includes? prose "src/seon/error.clj"))
      (is (str/includes? prose "narrow the edit selection"))))
  (testing "render-walk elision stays neutral in both projections"
    (let [value {:seon.render.walk/elided true
                 :seon.error/message "The bounded walk omitted content."}
          prose (error/elision-prose value)
          html (error/elision-html value)]
      (is (str/includes? prose "content was elided"))
      (is (not (str/includes? prose "error")))
      (is (= :aside (first html)))
      (is (= "seon-family-entry seon-render-elision"
             (get-in html [1 :class])))))
  (testing "unclassified prose says that the declaration is missing"
    (let [prose (error/unclassified-prose
                 {:seon.error/unclassified true
                  :seon.error/source {:unexpected/value 7}
                  :seon.error/message "Nothing recognized the source."})]
      (is (str/includes? prose "No registered error class recognized"))
      (is (str/includes? prose "declare the missing class"))))
  (testing "MCP lookup prose keeps the requested value identity"
    (let [digest (apply str (repeat 64 "a"))
          prose (error/mcp-prose
                 {:seon.dev.mcp/value-not-found digest
                  :seon.error/message "The value was absent."})]
      (is (str/includes? prose digest))
      (is (str/includes? prose "current cluster status"))))
  (testing "index refusal prose names the stopped phase"
    (let [prose (error/index-refusal-prose
                 {:seon.fn/index-transaction-refused :schema
                  :seon.error/message "Schema indexing was refused."})]
      (is (str/includes? prose ":schema"))
      (is (str/includes? prose "rerun initialization")))))

(deftest the-log-line-is-one-line-and-derived
  (let [fact (fact)
        line (rendered (error/notice {:seon.error/fact fact}) :log)]
    (is (not (str/includes? line "\n")) "a log line that wraps is two log lines")
    (is (not (str/blank? line)))))

(deftest the-flat-value-projects-from-the-fact
  (let [fact (fact)
        value (error/value fact)]
    (is (seon.schema/valid-candidate-value? :seon.error/value value))
    (is (= (:seon.error/kind fact) (:seon.error/kind value)))
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
      (config/apply! {:seon.db/connection connection
                      :seon.boot/cluster-name "error-test"})
      (test-support/seed-cluster! connection "error-test")
      (test-support/transacted! connection [{:seon.agent/id "root"}
                                          {:seon.agent/id "agent-3"}])
      (body connection))))

(defn- commit-request
  [source extra]
  (merge {:seon.error/source source
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

(deftest a-missing-recurrence-limit-refuses-at-the-declared-contract
  ;; the recursion fence extended to OUR bugs: `(> 1 nil)` thrown out of
  ;; the recorder would mean an error that destroyed its own record. The
  ;; fence is now the DECLARED CONTRACT — `:seon.config.error/recurrence-limit`
  ;; is a required member of `:seon.error/commit-tx-request` — and under the
  ;; contracts every cluster arms it refuses first, at the same crossing,
  ;; naming the same function and the offending argument. This asserts that
  ;; refusal as the value it is; nothing invents a default and nothing
  ;; reaches the recorder's comparison.
  (with-db
    (fn [connection]
      (let [refusal
            (test-support/refusal-data
             #(error/commit-tx
               @connection
               (dissoc (commit-request (transform-error (ex-info "boom" {})) {})
                       :seon.config.error/recurrence-limit)))]
        (is (= :seon.instrument/contract-violated (:seon.error/kind refusal)))
        (is (= 'seon.error/commit-tx
               (:seon.error/diagnostic-operation
                (:seon.error/data refusal))))
        (is (empty? (db/q '[:find ?e :where [?e :seon.error/id _]] @connection))
            "and the refused call committed nothing")))))

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
                       {:seon.error/kind :seon.turn/not-the-holder
                        :seon.error/message "the run is held by another process"}
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
        (is (= {"root" 3} messages)
            "two ordinary escalations, one final message at the limit, then
             silence")))))

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
                         [?message :seon.message/about ?error]
                         [?error :seon.error/id ?id]]
                       db)]
        (is (some? about)
            "the tempid resolved: fact and message land in ONE transaction")))))

(deftest a-fault-mints-the-failing-functions-identity-without-inventing-its-admission
  ;; THE CLASS: the fault-committing path refers to the failing function by
  ;; REF, so it mints `[:seon.fn/sym …]` when the program graph has no row
  ;; for it. `:seon.fn/fn` REQUIRES `:seon.schema.admission/source`, and the
  ;; minted row carried none, so the whole-entity validator rejected every
  ;; such fault transaction — `:db-after` nil, the steward never woken, and
  ;; the writer's only signal a `:transaction/validation-rejected` log line.
  ;; The admission source is equally not this seam's to assert for a function
  ;; the graph already knows, so the decision is made at the writer's own
  ;; database rather than pre-read here.
  (with-db
    (fn [connection]
      (test-support/transacted! connection
                                [{:seon.agent/id "mint-steward"}
                                 {:seon.ns/name 'my.mint
                                  :seon.ns/steward [:seon.agent/id "mint-steward"]}])
      (let [failure (doto (IllegalStateException. "a real Java class")
                      (.setStackTrace
                       (into-array StackTraceElement
                                   [(StackTraceElement. "my.mint$broken" "invokeStatic"
                                                        "mint.clj" 11)])))
            recording (error/recording (db/db connection)
                                       (commit-request (transform-error failure) {}))]
        (is (:db-after (db/transact! connection (:seon.db/tx-data recording)))
            "the fault transaction is accepted")
        (let [database (db/db connection)
              stored (db/pull database '[*] (:seon.error/ref recording))
              minted (db/pull database '[*] [:seon.fn/sym "my.mint/broken"])]
          (testing "the exception class is stored as a symbol, never a string"
            (is (= 'java.lang.IllegalStateException
                   (:seon.error/exception-class stored)))
            (is (symbol? (:seon.error/exception-class stored))))
          (testing "the minted identity is a complete `:seon.fn/fn` row"
            (is (= :agent (:seon.schema.admission/source minted)))
            (is (= 'my.mint (:seon.ns/name (db/pull database '[:seon.ns/name]
                                                    (:db/id (:seon.fn/ns minted)))))))
          (testing "the steward of the failing function's namespace is derived"
            (is (= "mint-steward" (error/steward database stored)))))
        (testing "a second fault never re-decides an admitted function's source"
          (test-support/transacted! connection
                                    [{:seon.fn/sym "my.mint/broken"
                                      :seon.schema.admission/source :core}])
          (let [again (error/recording (db/db connection)
                                       (commit-request (transform-error failure) {}))]
            (is (:db-after (db/transact! connection (:seon.db/tx-data again))))
            (is (= :core (:seon.schema.admission/source
                          (db/pull (db/db connection) '[*]
                                   [:seon.fn/sym "my.mint/broken"]))))))))))

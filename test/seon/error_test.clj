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

  Nothing here needs a database, a cluster, a store, or sci: the whole
  unit under test is pure."
  (:require [clojure.core.async.flow :as-alias flow]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [seon.config :as config]
            [seon.error :as error]
            [seon.render.walk :as walk]
            [seon.schema :as schema]
            [seon.sci.admit :as admit]
            [seon.test-support :as test-support]
            [seon.db :as db]))

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

(defn- request
  "A normalize request over `source`, with optional attribution."
  ([source] (request source {}))
  ([source extra]
   (merge {:seon.error/source source
           :seon.error/id "err-1"
           :seon.error/at #inst "2026-07-27T21:00:00.000-00:00"
           :seon.error/process process
           :seon.sci.admit/caps caps}
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
  (let [representatives
        [(error/diagnostic
          {:my.fs/stale-digest "tmp/stale"
          :seon.error/kind :my.fs/stale-digest
           :seon.error/message "stale"})
         (error/diagnostic
          {:my.fs/invalid-utf8-window "tmp/not-utf8"
          :seon.error/kind :my.fs/invalid-utf8-window
           :seon.error/message "not UTF-8"})
         (error/diagnostic
          {:seon.cluster.reply/refused-tag 'secret/tag
          :seon.error/kind :seon.cluster.reply/refused-tag
           :seon.error/message "tag refused"})
         (error/diagnostic
          {:seon.db/transaction-outcome-unknown true
          :seon.error/kind :seon.db/unknown-failure
           :seon.error/message "outcome unknown"})
         (error/diagnostic
          {:seon.instrument/contract-violated "sample/fn"
          :seon.error/kind :seon.instrument/contract-violated
           :seon.error/message "contract violated"})
         (error/diagnostic
          {:seon.render.walk/elided true
          :seon.error/kind :seon.render.walk/elided
           :seon.error/message "elided"})
         (error/diagnostic
          {:seon.ai/stream-truncated true
          :seon.error/kind :seon.ai/stream-truncated
           :seon.error/message "stream truncated"})
         (error/diagnostic
          {:seon.cluster.loop/trigger-already-answered true
          :seon.error/kind :seon.cluster.loop/trigger-already-answered
           :seon.error/message "already answered"})
         (error/diagnostic
          {:seon.cluster.reply/unreadable "["
          :seon.error/kind :seon.cluster.reply/unreadable
           :seon.error/message "unreadable"})
         (error/diagnostic
          {:seon.cluster.loop/phase-failed true
          :seon.error/kind :seon.cluster.loop/phase-failed
           :seon.error/message "phase failed"})
         (error/diagnostic
          {:seon.cluster.loop/lint-rejected true
          :seon.error/kind :seon.cluster.loop/lint-rejected
           :seon.error/message "lint rejected"})
         (error/diagnostic
          {:seon.operator/collection-incomplete true
          :seon.error/kind :seon.operator/collection-incomplete
           :seon.error/message "collection incomplete"})]
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
        state {:seon.cluster.loop/cluster {:seon.db/connection connection}
               :seon.cluster.agent/turns 7}]
    (reset! connection state)
    state))

(defn- transform-error
  "Shape 1 — a transform threw (`impl.clj:312-316`). The only shape
  carrying `:op` and `:msg`."
  [throwable]
  {::flow/pid :seon.cluster.loop/loop
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
  {::flow/pid :seon.cluster.loop/loop
   ::flow/status :running
   ::flow/state (cyclic-state)
   ::flow/count 12
   ::flow/ex throwable})

(defn- xform-error
  "Shape 3 — a channel xform threw (`impl.clj:106-110`). No `:status`,
  no `:state`, no `:count`."
  [throwable]
  {::flow/ex throwable
   ::flow/pid :seon.cluster.loop/loop
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
                              :seon.cluster.run/id "run-9"}))))

;;; ---------------------------------------------------------------------------
;;; Generators — honest, by constructing the real things
;;; ---------------------------------------------------------------------------

(def ^:private throwable-gen
  (gen/fmap (fn [[message kind]]
              (ex-info message {:seon.error/kind kind}))
            (gen/tuple (gen/not-empty gen/string-alphanumeric)
                       (gen/elements [:seon.cluster.run/refused
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
            (gen/elements [:seon.cluster.run/not-the-holder
                           :seon.cluster.run/run-closed
                           :seon.cluster.run/agent-pointer-broken])))

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
                                 {:seon.cluster.run/id "run-9"
                                  :seon.cluster.agent/id "agent-3"})))
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
  ;; asserted STRUCTURALLY over the read-back value rather than as a
  ;; substring of the print: `*print-namespace-maps*` is true here, so
  ;; the marker prints `#:seon.sci.admit{:reference …}` and a string
  ;; match would be testing the printer's settings
  (let [fact (error/normalize (request (transform-error (ex-info "boom" {}))))
        read-back (#'admit/semantic-value
                   (edn/read-string (:seon.error/data-edn fact)))]
    (testing "a value pr-str cannot survive reads back as EDN"
      (is (map? read-back)))
    (testing "the live reference is OPAQUE, never entered — which is what
    makes the cycle unrepresentable rather than detected"
      (is (= {:seon.sci.admit/opaque "clojure.lang.Atom"}
             (get-in read-back [::flow/state
                                :seon.cluster.loop/cluster
                                :seon.db/connection]))))
    (testing "and the Throwable is the printer's bounded data projection,
    never the live Throwable"
      (let [projected (get read-back ::flow/ex)]
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
      (is (= :seon.cluster.loop/loop (:seon.error/proc one)))
      (is (= :step (:seon.error/op one)))
      (is (= :wake (:seon.error/cid one))))
    (testing "a proc-loop throw carries neither op nor cid — absence is the state"
      (is (= :seon.cluster.loop/loop (:seon.error/proc two)))
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
                        (refused-chain :seon.cluster.run/not-the-holder))))]
    (is (= "the transition refused" (:seon.error/message fact)))))

(deftest the-kind-comes-from-the-deepest-ex-data
  (let [fact (error/normalize
              (request (transform-error
                        (refused-chain :seon.cluster.run/not-the-holder))))]
    (is (= :seon.cluster.run/not-the-holder (:seon.error/kind fact))
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
                                       {:seon.cluster.run/id "run-9"
                                        :seon.cluster.agent/id "agent-3"}))
        without (error/normalize (request {:seon.error/kind :seon.db/rejected
                                           :seon.error/message "no"}))]
    (is (= [:seon.cluster.run/id "run-9"] (:seon.error/run with)))
    (is (= [:seon.cluster.agent/id "agent-3"] (:seon.error/agent with)))
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
                                              :seon.cluster.run/not-the-holder))
                            {:seon.cluster.run/id "run-9"
                             :seon.cluster.agent/id "agent-3"})))

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
  (let [projection (schema/build-projection (schema/registered-schemas))]
    (with-redefs [schema/current-projection (constantly projection)]
      (let [value {:my.fs/not-found "/tmp/missing.edn"
                   :my.fs/path "/tmp/missing.edn"
                   :seon.error/message "No file exists at that path."}
            ai (error/render-ai value)
            html (error/render-html value)]
        (testing "the AI face separates failure subject from sibling evidence"
          (is (str/includes? ai "No file exists at that path."))
          (is (str/includes? ai "Failed: :my.fs/not-found=\"/tmp/missing.edn\""))
          (is (str/includes? ai "Evidence: :my.fs/path=\"/tmp/missing.edn\""))
          (is (str/includes? ai "Re-read the current facts")))
        (testing "the HTML face has distinct marker and evidence rows"
          (is (= :article (first html)))
          (is (schema/valid-candidate-value? :seon.render/hiccup html))
          (is (= "No file exists at that path." (get-in html [2 2])))
          (is (= "seon-error-marker" (get-in html [3 1 :class])))
          (is (= ":my.fs/not-found" (get-in html [3 2 2 1])))
          (is (= "seon-error-evidence" (get-in html [4 1 :class])))
          (is (str/includes? (pr-str html) ":my.fs/path")))))))

(deftest the-default-html-face-links-committed-evidence
  (let [html (error/render-html
              {:seon.error/unclassified true
               :seon.error/id "err-42"
               :seon.error/message "Nothing recognized this error."})
        href (get-in html [4 2 1 :href])]
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
                 {:seon.cluster.run/refused true
                  :seon.cluster.run/id "run-7"
                  :seon.cluster.run/rule :seon.cluster.run/not-holder
                  :seon.cluster.run/transition :seon.cluster.run/close
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
  [body]
  (test-support/with-database
    (fn [connection]
      (db/transact! connection [{:seon.cluster.agent/id "root"}
                              {:seon.cluster.agent/id "agent-3"}])
      (body connection))))

(defn- commit-request
  [source extra]
  (merge {:seon.error/source source
          :seon.error/id (str (random-uuid))
          :seon.error/at #inst "2026-07-27T21:00:00.000-00:00"
          :seon.error/process process
          :seon.sci.admit/caps caps
          :seon.config.error/recurrence-limit 3
          :seon.config.error/escalate-to "root"}
         extra))

(defn- commit!
  "Commit one error and return [fact-count messages-by-recipient]."
  [connection source extra]
  (db/transact! connection
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
                  [?message :seon.cluster.message/about _]
                  [?message :seon.cluster.message/to ?agent]
                  [?agent :seon.cluster.agent/id ?to]]
                db)))]))

(deftest a-committed-fault-renders-its-evidence-without-renderer-failure-prose
  (with-db
    (fn [connection]
      (db/transact!
       connection
       (error/commit-tx
        @connection
        (commit-request
         (transform-error (ex-info "walk evidence" {}))
         {:seon.cluster.agent/id "agent-3"})))
      (let [db @connection
            units (walk/neighborhood
                  {:seon.db/db db
                   :seon.sci.eval/ctx
                   (test-support/fork-cluster-ctx connection)
                   :seon.render.walk/lookup
                   [:seon.cluster.agent/id "agent-3"]
                   :seon.render/output :seon.render/ai
                   :seon.render/distance 1
                   :seon.sci.admit/caps caps
                   :seon.sci.eval/time-limit-ms 2000
                   :seon.config/on-core-error :panic})
            text (walk/prose db units)]
        (is (str/includes? text "The loop :step failed"))
        (is (str/includes? text "Inspect error"))
        (is (not (str/includes? text "projection threw")))
        (is (not (str/includes? text "violated its contract")))))))

(deftest a-missing-recurrence-limit-records-and-stays-silent
  ;; the recursion fence extended to OUR bugs: requiredness is a
  ;; contract, contracts are unenforced until instrumentation is on, and
  ;; `(> 1 nil)` thrown out of the recorder would mean an error that
  ;; destroyed its own record. No invented default — the conservative
  ;; half of the storm fence.
  (with-db
    (fn [connection]
      (let [[facts messages]
            (commit! connection
                     (transform-error (ex-info "boom" {}))
                     {:seon.config.error/recurrence-limit nil})]
        (is (= 1 facts) "the fact is committed exactly as always")
        (is (= {} messages) "and nobody is mailed on a caller we cannot trust")))))

(deftest only-a-throwable-tells-the-attributed-agent
  (with-db
    (fn [connection]
      (testing "a Throwable interrupted the agent's run, and it cannot know
      unless told"
        (let [[_ messages] (commit! connection
                                    (transform-error (ex-info "boom" {}))
                                    {:seon.cluster.agent/id "agent-3"
                                     :seon.cluster.run/id "run-9"})]
          (is (= {"agent-3" 1} messages))))))
  (with-db
    (fn [connection]
      (testing "a refused transition is a VALUE: the run's own facts already
      say what happened, so the fact is recorded and nobody is mailed"
        (let [[facts messages]
              (commit! connection
                       {:seon.error/kind :seon.cluster.run/not-the-holder
                        :seon.error/message "the run is held by another process"}
                       {:seon.cluster.agent/id "agent-3"
                        :seon.cluster.run/id "run-9"})]
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
                                      {:seon.cluster.agent/id "ghost"})]
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
        (is (= 6 facts) "every occurrence is still evidence")
        (is (= {"root" 3} messages)
            "two ordinary escalations, one final message at the limit, then
             silence")))))

(deftest a-message-id-is-derived-so-delivery-is-idempotent
  (with-db
    (fn [connection]
      (let [source (transform-error (ex-info "boom" {}))
            request (commit-request source {:seon.cluster.agent/id "agent-3"
                                            :seon.cluster.run/id "run-9"})
            tx (error/commit-tx @connection request)]
        ;; the SAME request committed twice: re-execution after a crash
        ;; must upsert, never double-send
        (db/transact! connection tx)
        (db/transact! connection tx)
        (let [db @connection]
          (is (= 1 (count (db/q '[:find ?e :where [?e :seon.error/id _]] db))))
          (is (= 1 (count (db/q '[:find ?m :where
                                 [?m :seon.cluster.message/about _]]
                               db))))))))) 

(deftest the-message-points-at-the-fact-it-explains
  (with-db
    (fn [connection]
      (commit! connection (transform-error (ex-info "boom" {})) {})
      (let [db @connection
            about (db/q '[:find ?id .
                         :where
                         [?message :seon.cluster.message/about ?error]
                         [?error :seon.error/id ?id]]
                       db)]
        (is (some? about)
            "the tempid resolved: fact and message land in ONE transaction")))))

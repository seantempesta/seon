(ns seon.test.accretion-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [sci.core :as sci]
            [seon.config :as config]
            [seon.db :as db]
            [seon.env :as env]
            [seon.fn :as seon.fn]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.sci.eval :as sci.eval]
            [seon.test-support :as test-support]
            [seon.test.accretion :as accretion]))

(defn- admit!
  [connection rows]
  (let [result (db/transact! connection rows)]
    (when-not (:db-after result)
      (throw (ex-info "Auto-check fixture admission failed." result)))
    result))

(defn- candidate!
  "Evaluate `request`'s definition once in its context, then prove it in a fork."
  [request]
  (sci.eval/evaluate-candidate
   (assoc request :seon.test.accretion/evaluation (sci.eval/evaluate request))))

(deftest refused-install-feedback-is-complete-grouped-and-renderable
  (let [test-result
        (fn [test-symbol]
          {:seon.test/sym test-symbol
           :seon.test/pass-count 0
           :seon.test/fail-count 1
           :seon.test/error-count 0
           :seon.test/failing-assertions [(apply str (repeat 64 "a"))]
           :seon.test/failure-message "expected: 2\n  actual: 1"})
        report
        (accretion/gate-report
         {:seon.fn/sym 'fixture.feedback/target
          :seon.test.accretion/results
          (mapv test-result (map #(symbol "fixture.feedback" (str "red-" %)) (range 5)))
          :seon.test.accretion/auto-check
          {:seon.test.accretion/seed 42
           :seon.test.accretion/case-count 25
           :seon.test.accretion/executed-count 7
           :seon.test.accretion/capabilities #{}
           :seon.test.accretion/status :failed
           :seon.test.accretion/failure
           {:seon.test.accretion/arguments [0]
            :seon.test.accretion/actual "wrong"
            :seon.test.accretion/expected ":int"
            :seon.test.accretion/pass? false}}})
        refusal (accretion/install-refusal report)
        rendered (accretion/render-ai refusal)]
    (is (= "Gate: 5 tests · 0 passed · 5 failed · auto-check 7/25 · install refused"
           (:seon.test.accretion/orientation report)))
    (is (= :seon.test.accretion/install-refused-error
           (:seon.error/declared-schema (meta refusal)))
        "the producer declares the identity its renderer pair is declared on")
    (is (= 6
           (reduce + (map (comp count :seon.test.accretion/failures)
                          (:seon.test.accretion/failure-groups report))))
        "the complete value retains every example and auto-check failure")
    (test-support/with-database
      (fn [connection]
        (is (schema/valid-candidate-value?
             (seon.db/carried-projection @connection)
             :seon.test.accretion/install-refused-error refusal))))
    (is (= 3 (count (re-seq #"Test fixture.feedback/red-" rendered))))
    (is (re-find #"2 more in the complete gate-report blob" rendered))
    (is (re-find #"Auto-check seed 42" rendered))
    (is (= [:article {:class "seon-family-entry seon-test-gate-refusal"}
            [:pre rendered]]
           (accretion/render-html refusal)))
    (is (= (accretion/seed-for "receipt-id")
           (accretion/seed-for "receipt-id")))))

(deftest refusal-renderers-accept-the-declaring-error-shape
  (is (= [:=> [:cat :seon.test.accretion/install-refused-error]
          :seon.render/ai]
         (:malli/schema (meta #'accretion/render-ai))))
  (is (= [:=> [:cat :seon.test.accretion/install-refused-error]
          :seon.render/hiccup]
         (:malli/schema (meta #'accretion/render-html)))))

(deftest source-harvest-keeps-refusal-render-contracts-coherent
  (let [rows (seon.fn/rows {:seon.fn/roots seon.fn/source-roots})
        contracts
        (into {}
              (keep (fn [{function-symbol :seon.fn/sym
                          contract :seon.fn/spec}]
                      (when contract
                        [(symbol function-symbol)
                         (edn/read-string contract)])))
              rows)
        projection
        (schema/build-projection
         (schema.edn/packaged-forms)
         contracts
         {:seon.schema/validate-render-contracts? true})]
    (is (= [:=> [:cat :seon.test.accretion/install-refused-error]
            :seon.render/ai]
           (get-in projection
                   [:seon.schema.projection/function-contracts
                    'seon.test.accretion/render-ai])))
    (is (= 'seon.test.accretion/render-ai
           (get-in projection
                   [:seon.schema.projection/shape-rows
                    :seon.test.accretion/install-refused-error
                    :seon.render/ai])))))

(deftest generatability-is-derived-by-malli-generator-construction
  (testing "test.chuck enables Malli regex generation on the runtime classpath"
    (is (true? (accretion/generatable? [:re #"[a-z]+"]))))
  (testing "an unannotated predicate honestly has no generator"
    (is (false? (accretion/generatable?
                 [:fn {:error/message "must be an integer"} int?])))))

(deftest schema-rows-record-the-derived-fact-and-teaching-advisory
  (let [generatable
        (accretion/schema-row
         {:fixture/token [:re #"[a-z]+"]}
         {:seon.schema/key :fixture/token
          :seon.schema/form "[:re #\"[a-z]+\"]"})
        non-generatable
        {:seon.schema/key :fixture/custody
         :seon.schema/form "[:fn fixture/custody?]"
         :seon.schema/generatable? false}]
    (is (true? (:seon.schema/generatable? generatable)))
    (is (nil? (accretion/non-generatable-advisory generatable)))
    (is (= "Schema :fixture/custody has no Malli generator; functions using it will skip auto-check."
           (accretion/non-generatable-advisory non-generatable)))))

(deftest one-gate-set-query-includes-edges-subjects-and-pending-tests
  (test-support/with-database
    (fn [connection]
      (admit!
       connection
       [(test-support/namespace-row @connection 'fixture.gate)])
      (let [source "(defn target [] 1)\n(defn caller [] (target))"]
        (admit! connection
                [(test-support/program-fn-row @connection 'fixture.gate/target source)
                 (test-support/program-fn-row @connection 'fixture.gate/caller source)]))
      (let [test-row (fn [test-symbol source overrides]
                       (test-support/program-row @connection [:seon.test/sym test-symbol] source overrides))]
        (admit! connection
                [(test-row 'fixture.gate/direct-test "(clojure.test/deftest direct-test (target))" {})
                 (test-row 'fixture.gate/caller-test "(clojure.test/deftest caller-test (caller))" {})
                 (test-row 'fixture.gate/subject-test "(clojure.test/deftest subject-test)"
                           {:seon.test/subject 'fixture.gate/target})
                 (test-row 'fixture.gate/pending-test "(clojure.test/deftest pending-test)"
                           {:seon.test/subject 'fixture.gate/future})]))
      ;; The branch also holds this test's own row, which quotes the fixture symbols.
      (is (= ['fixture.gate/caller-test
              'fixture.gate/direct-test
              'fixture.gate/subject-test]
             (filterv #(= "fixture.gate" (namespace %))
                      (seon.fn/gate-set @connection 'fixture.gate/target))))
      (is (= ['fixture.gate/pending-test]
             (filterv #(= "fixture.gate" (namespace %))
                      (seon.fn/gate-set @connection 'fixture.gate/future))))
      (is (= (seon.fn/gate-set @connection 'fixture.gate/target)
             (seon.fn/tests-reaching @connection 'fixture.gate/target))))))

(deftest a-definition-evaluates-once-and-its-gate-proves-that-root
  ;; CLASS (M2, 2026-09-26): the gate's world is a fork of the context that
  ;; evaluated the definition, never a second evaluation chosen by reader
  ;; syntax. `(do (defn f ...) (f 1))` and a top-level defn take one path: the
  ;; root the gate tests is the root acceptance keeps, and a refused install
  ;; restores the evaluating context through the evaluation's own restore.
  (test-support/with-database
    (fn [connection]
      (admit! connection (test-support/agent-tx @connection "candidate-author" 'fixture.candidate))
      (let [source (str "(defn ^{:malli/schema [:=> [:cat :int] :int]} target [x] x)\n"
                        "(clojure.test/deftest green-test (clojure.test/is (= 2 (target 1))))\n"
                        "(clojure.test/deftest red-test (clojure.test/is (= 3 (target 1))))")]
        (admit! connection
                [(test-support/program-fn-row @connection 'fixture.candidate/target source)
                 (test-support/program-row @connection [:seon.test/sym 'fixture.candidate/green-test] source {})
                 (test-support/program-row @connection [:seon.test/sym 'fixture.candidate/red-test] source {})]))
      (let [parent (test-support/fork-cluster-ctx connection)
            _ (sci.eval/acquire! {:seon.sci.eval/ctx parent :seon.db/db @connection})
            request (fn [source]
                      {:seon.sci.eval/ctx parent :seon.db/db @connection
                       :seon.db/connection connection :seon.agent/id "candidate-author"
                       :seon.cluster.eval/ns [:seon.ns/name 'fixture.candidate]
                       :seon.cluster.eval/source source
                       :seon.test.accretion/gate-set ['fixture.candidate/green-test 'fixture.candidate/red-test]
                       :seon.sci.eval/time-limit-ms 2000
                       :seon.sci.admit/caps (config/result-caps config/defaults)})
            held (sci/resolve parent 'fixture.candidate/target)
            wrapped (request (str "(do (defn ^{:malli/schema [:=> [:cat :int] :int]} "
                                  "target [x] (inc x)) (target 1))"))
            evaluation (sci.eval/evaluate wrapped)
            result (sci.eval/evaluate-candidate
                    (assoc wrapped :seon.test.accretion/evaluation evaluation))
            results (:seon.test.accretion/results result)]
        (testing "a definition inside do is gated like a top-level one"
          (is (= 2 (:seon.sci.admit/value evaluation)) (pr-str evaluation))
          (is (= 'fixture.candidate/target
                 (get-in evaluation [:seon.program/row :seon.fn/sym])))
          (is (= ['fixture.candidate/green-test 'fixture.candidate/red-test]
                 (mapv :seon.test/sym results)))
          (is (= [0 1] (mapv :seon.test/fail-count results)) (pr-str results)))
        (testing "the gate proves the root the evaluating context holds"
          (is (= 2 (sci/eval-string* (:seon.test.accretion/candidate-ctx result)
                                     "(fixture.candidate/target 1)")))
          (is (= 2 (sci/eval-string* parent "(fixture.candidate/target 1)"))))
        (testing "a refused install restores the evaluating context"
          (let [refused (sci.eval/refuse-install wrapped evaluation
                                                 (accretion/install-refusal
                                                  (accretion/gate-report
                                                   {:seon.fn/sym 'fixture.candidate/target
                                                    :seon.test.accretion/results results})))]
            ;; Shown through its declared teaching face, orientation first,
            ;; never the raw map a value budget may cut before it.
            (is (= 'seon.test.accretion/render-ai (:seon.eval/renderer refused)) (pr-str refused))
            (is (re-find #"install refused" (:seon.eval/shown refused)) (:seon.eval/shown refused)))
          (is (identical? held (sci/resolve parent 'fixture.candidate/target)))
          (is (= 1 (sci/eval-string* parent "(fixture.candidate/target 1)"))))))))

(deftest a-gate-test-runs-with-its-authors-cluster-custody
  ;; A GATE TEST IS THE AUTHOR'S OWN WORK. It is evaluated in the candidate
  ;; ctx of the agent's own evaluation, so an elided `seon.db` arity inside it
  ;; is the same documented affordance as everywhere else in that evaluation
  ;; (AGENTS §3). `run-candidate-test!` therefore hands `run-var!` the
  ;; connection its request already carries, instead of leaving the body with
  ;; whatever the thread happened to inherit — the class this and
  ;; `my.test/run` share.
  (test-support/with-database
    (fn [connection]
      (admit!
       connection
       (test-support/agent-tx @connection "custody-author" 'fixture.custody))
      (admit! connection
              [(test-support/program-row
                @connection [:seon.test/sym 'fixture.custody/reads-its-own-cluster]
                (str "(clojure.test/deftest reads-its-own-cluster "
                     "(clojure.test/is (int? (seon.db/basis-t (seon.db/db)))))")
                {})])
      (let [parent (test-support/fork-cluster-ctx connection)
            result
            (candidate!
             {:seon.sci.eval/ctx parent
              :seon.db/db @connection
              :seon.db/connection connection
              :seon.agent/id "custody-author"
              :seon.cluster.eval/source
              (str "(defn ^{:malli/schema [:=> [:cat :int] :int]} "
                   "target [x] x)")
              :seon.test.accretion/gate-set
              ['fixture.custody/reads-its-own-cluster]
              :seon.sci.eval/time-limit-ms 2000
              :seon.sci.admit/caps (config/result-caps config/defaults)})
            gate (first (:seon.test.accretion/results result))]
        ;; Messages name the few members read: a result holds SCI contexts
        ;; and roster atoms, and clojure.test evaluates a message even on pass.
        (is (= 'fixture.custody/reads-its-own-cluster
               (:seon.test/sym gate))
            (pr-str (mapv :seon.test/sym (:seon.test.accretion/results result))))
        (is (= 1 (:seon.test/pass-count gate))
            (str "the gate test's elided read reaches its author's cluster "
                 "instead of refusing with no connection bound: "
                 (pr-str (select-keys gate [:seon.test/sym :seon.test/pass-count :seon.test/fail-count :seon.test/error-count]))))
        (is (= 0 (+ (:seon.test/fail-count gate)
                    (:seon.test/error-count gate)))
            (pr-str (select-keys gate [:seon.test/sym :seon.test/pass-count :seon.test/fail-count :seon.test/error-count])))))))

(deftest auto-check-is-seeded-shrunk-and-derived-pure
  (test-support/with-database
    (fn [connection]
      (admit!
       connection
       (test-support/agent-tx @connection "auto-check-author" 'fixture.auto-check))
      (admit! connection
              [(test-support/program-fn-row @connection 'fixture.auto-check/capability
                                            "(defn capability [] nil)"
                                            {:seon.effect/capability 'fixture.auto-check/handler})])
      (let [parent (test-support/fork-cluster-ctx connection)
            candidate-request
            (fn [source]
              (candidate!
               {:seon.sci.eval/ctx parent
                :seon.db/db @connection
                :seon.db/connection connection
                :seon.agent/id "auto-check-author"
                :seon.cluster.eval/source source
                :seon.test.accretion/gate-set []
                :seon.sci.eval/time-limit-ms 2000
                :seon.sci.admit/caps
                (config/result-caps config/defaults)}))
            check-request
            (fn [candidate row]
              {:seon.sci.eval/ctx candidate
               :seon.db/db @connection
               :seon.program/row row
               :seon.config.test/auto-check-cases 25
               :seon.test.accretion/seed 424242
               :seon.sci.eval/time-limit-ms 2000
               :seon.sci.admit/caps
               (config/result-caps config/defaults)})
            bad
            (candidate-request
             (str "(defn ^{:malli/schema [:=> [:cat :int] :int]} "
                  "bad [x] \"wrong\")"))
            bad-row
            (get-in bad [:seon.test.accretion/evaluation :seon.program/row])
            _ (when-not (schema/valid-candidate-value? (schema/handed-projection) :seon.program/row bad-row)
                (throw (ex-info "The candidate did not return an admissible function." bad)))
            bad-request
            (check-request (:seon.test.accretion/candidate-ctx bad) bad-row)
            first-failure (sci.eval/auto-check-candidate bad-request)
            reproduced (sci.eval/auto-check-candidate bad-request)
            shape-only
            (candidate-request
             (str "(defn ^{:malli/schema "
                  "[:=> [:cat :int :int] :int]} add [a b] 1)"))
            shape-only-row
            (get-in shape-only
                    [:seon.test.accretion/evaluation :seon.program/row])
            shape-only-check
            (sci.eval/auto-check-candidate
             (check-request
              (:seon.test.accretion/candidate-ctx shape-only)
              shape-only-row))
            effectful-check
            (sci.eval/auto-check-candidate
             (check-request
              (:seon.test.accretion/candidate-ctx bad)
              (assoc bad-row
                     :seon.fn/calls
                     #{'fixture.auto-check/capability})))]
        (testing "the same seed reproduces the shrunk failure"
          (is (= :failed (:seon.test.accretion/status first-failure))
              (pr-str first-failure))
          (is (= 424242 (:seon.test.accretion/seed first-failure)))
          (is (= (:seon.test.accretion/failure first-failure)
                 (:seon.test.accretion/failure reproduced)))
          (is (seq (get-in first-failure
                           [:seon.test.accretion/failure
                            :seon.test.accretion/arguments])))
          (is (string?
               (get-in first-failure
                       [:seon.test.accretion/failure
                        :seon.test.accretion/explanation]))))
        (testing "contract shape does not invent semantic properties"
          (is (= :passed (:seon.test.accretion/status shape-only-check))
              (pr-str shape-only-check))
          (is (= 25
                 (:seon.test.accretion/executed-count shape-only-check))))
        (testing "capability reachability skips auto-check honestly"
          (is (= :skipped-effectful
                 (:seon.test.accretion/status effectful-check)))
          (is (= 0
                 (:seon.test.accretion/executed-count effectful-check)))
          (is (= #{'fixture.auto-check/capability}
                 (:seon.test.accretion/capabilities effectful-check))))))))

(deftest auto-check-calls-in-the-agents-world-and-keeps-a-throw-whole
  ;; Dogfood cycle 4: a data-reading function was called with a generated
  ;; empty database that lacked every installed attribute, so no such function
  ;; could install; and a call that threw reported the callee symbol as its
  ;; return value. The database argument is now the one call preparation
  ;; supplies from the candidate's own environment, and a throw is reported
  ;; as its whole error, never checked against the output schema.
  (test-support/with-database
    (fn [connection]
      (admit! connection (test-support/agent-tx @connection "world-author" 'fixture.world))
      (let [parent (test-support/fork-cluster-ctx connection)
            check (fn [source]
                    (let [candidate (candidate!
                                     {:seon.sci.eval/ctx parent :seon.db/db @connection
                                      :seon.db/connection connection :seon.agent/id "world-author"
                                      :seon.cluster.eval/source source :seon.test.accretion/gate-set []
                                      :seon.sci.eval/time-limit-ms 2000
                                      :seon.sci.admit/caps (config/result-caps config/defaults)})]
                      (sci.eval/auto-check-candidate
                       {:seon.sci.eval/ctx (:seon.test.accretion/candidate-ctx candidate)
                        :seon.db/db @connection
                        :seon.program/row (get-in candidate [:seon.test.accretion/evaluation :seon.program/row])
                        :seon.config.test/auto-check-cases 25 :seon.test.accretion/seed 424242
                        :seon.sci.eval/time-limit-ms 2000
                        :seon.sci.admit/caps (config/result-caps config/defaults)})))
            reader (check (str "(defn ^{:malli/schema [:=> [:cat :seon.db/database-value] [:int {:min 1}]]} "
                               "agent-count [db] (count (seon.db/q '[:find [?id ...] :where [_ :seon.agent/id ?id]] db)))"))
            entry (check (str "(defn ^{:malli/schema [:=> [:cat [:map [:seon.db/db :seon.db/database-value]]] [:int {:min 1}]]} "
                              "request-count [{db :seon.db/db}] (count (seon.db/q '[:find [?id ...] :where [_ :seon.agent/id ?id]] db)))"))
            thrower (check "(defn ^{:malli/schema [:=> [:cat :int] :int]} halve-by-zero [x] (quot x 0))")
            failure (:seon.test.accretion/failure thrower)]
        (testing "a data-reading function reads the agent's own database, positionally and as a map entry"
          (is (= [:passed 25] ((juxt :seon.test.accretion/status :seon.test.accretion/executed-count) reader))
              (pr-str reader))
          (is (= [:passed 25] ((juxt :seon.test.accretion/status :seon.test.accretion/executed-count) entry))
              (pr-str entry)))
        (testing "a throwing call reports its exception, not the callee or an output mismatch"
          (is (= :failed (:seon.test.accretion/status thrower)) (pr-str thrower))
          (is (re-find #"ArithmeticException" (str (get-in failure [:seon.test.accretion/actual :seon.error/message])))
              (pr-str failure))
          (is (map? (:seon.test.accretion/actual failure)) "the whole error, never the callee symbol")
          (is (nil? (:seon.test.accretion/explanation failure)) (pr-str failure))
          (is (seq (:seon.test.accretion/arguments failure))))))))

(deftest ^{:seon.test/long "Constructs a generator for every packaged schema (3,350): 2.5 s at the REPL, 7.4 s under a request on a scratch cluster at 0e53eb8b1."
           :seon.test/long-ms 12000}
  every-packaged-schema-answers-generatability-as-a-boolean
  ;; 169 packaged schemas (e.g. :seon.sci.eval/evaluation) named their
  ;; generator by symbol in `:gen/gen`; malli handed the symbol to gen/fmap and
  ;; threw an AssertionError that only a catch-everything hid. The fork now
  ;; resolves the symbol, and any other "cannot generate" is a typed ex-info.
  (let [forms (schema.edn/packaged-forms)
        answers (into {} (map (fn [schema-key]
                                [schema-key (:seon.schema/generatable?
                                             (accretion/schema-row forms {:seon.schema/key schema-key}))]))
                      (keys forms))]
    (is (every? boolean? (vals answers)))
    (is (true? (answers :seon.sci.eval/evaluation))
        "a map whose member names its generator by symbol generates")
    (is (true? (answers :seon.test/var)))))

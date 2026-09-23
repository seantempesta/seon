(ns seon.problems-test
  "What `problems` says, and — harder and more important — what it does
  not say.

  The failure this suite exists to prevent is a derivation that reports
  something about facts that are absent: an empty family as an empty
  vector, a `:healthy? true`, a wedged run derived from a clock, a
  count of zero. Every one of those is a status somebody then has to
  maintain, and the whole point of deriving is that nobody does.

  So the shape of the suite is: one fixture per database-fact family,
  the empty cluster, an exhaustive absence check over those fixtures,
  and one process-image interaction proof for stale Vars. Whatever is
  absent produces nothing and whatever is present produces exactly its
  own family. All 16 family subsets, one canonical database branch per subset,
  and the attributes come from
  `canonical-database-attributes` — the live boot derivation, not a
  hand-listed fixture set."
  (:require [malli.registry :as mr]
            [seon.schema.internal] [malli.core] [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.db :as db]
            [seon.config :as config]
            [seon.error :as error]
            [seon.problems :as problems]
            [seon.render.hiccup :as hiccup]
            [seon.schema]
            [seon.schema.edn :as schema.edn]
            [seon.test-support :as test-support]))

(def ^:private live "9999-1785191833372")
(def ^:private now #inst "2026-07-27T21:00:00.000-00:00")

(def ^:private caps
  (config/result-caps config/defaults))

(defn- with-db
  [body]
  (test-support/with-database
    (fn [connection]
      (config/apply! {:seon.boot/cluster-name "default" :seon.db/connection connection})
      (test-support/transacted! connection (test-support/agent-tx @connection "agent-a"))
      (body connection))))

(defn problems-surface
  "The problems block as a producer wires it: a projection that supplies
  the liveness set the pipeline will thread from the process. Package 2
  owns that threading; here the seam is explicit so the test proves the
  block, not the refusal card."
  [unit]
  (problems/block unit))

(defn- found
  [connection]
  (problems/problems @connection
                     {}))

;;; ---------------------------------------------------------------------------
;;; The database fixtures — each commits ONLY its own family's facts
;;; ---------------------------------------------------------------------------

(defn- commit-error!
  ;; The diagnostic cause is what varies, not the message: the signature
  ;; deliberately excludes the message so that an id or a timestamp in
  ;; it cannot make every occurrence unique. Two errors differing only
  ;; in wording are the same problem, and this fixture would be lying if
  ;; it pretended otherwise (it did, first time round).
  ([connection] (commit-error! connection :seon.db/rejected))
  ([connection cause]
   (db/transact!
    connection
    (error/commit-tx
     @connection
     {:seon.error/declared-schema :seon.error/normalization-error
      :seon.error/source
      {:seon.error/at now
       :seon.error/layer :seon.problems-test/fixture
       :seon.error/operation (symbol "seon.problems-test" (name cause))
       :seon.error/message "boom"
       :seon.error/member :seon.error/source
       :seon.error/expected "a fixture diagnostic"
       :seon.error/offending cause}
      ;; DETERMINISTIC, because this fixture runs inside a property: a
      ;; random id would make a shrunk counterexample unreplayable even
      ;; though nothing here reads the id (review-caught)
      :seon.error/id (str "err-" (name cause) "-"
                          (count (db/q '[:find ?e :where [?e :seon.error/id _]]
                                      @connection)))
      :seon.error/at now
      :seon.error/process live
      :seon.sci.admit/caps caps
      ;; no escalate-to: this suite is about the DERIVATION, and a
      ;; message would only add facts the derivation does not read
      :seon.config.error/recurrence-limit 3
      :seon.config.error/max-evidence-bytes 16384}))))



(defn- commit-failed-run!
  [connection]
  (test-support/transacted! connection
                            [{:seon.turn/id "run-failed"
                              :seon.turn/agent [:seon.agent/id "agent-a"]
                              :seon.turn/opened-tx "datomic.tx"
                              :seon.turn/closed-tx "datomic.tx"}])
  (test-support/transacted! connection
                            (error/commit-tx
                             (db/db connection)
                             {:seon.error/declared-schema :seon.error/normalization-error
      :seon.error/source {:seon.error/at now
                                                  :seon.error/layer :seon.ai/provider
                                                  :seon.error/operation 'seon.ai/complete
                                                  :seon.error/message "the model did not answer"}
                              :seon.error/id "run-failed-error" :seon.error/at now
                              :seon.error/process live :seon.sci.admit/caps caps
                              :seon.config.error/max-evidence-bytes 16384
                              :seon.config.error/recurrence-limit 3
                              :seon.turn/id "run-failed" :seon.agent/id "agent-a"})))

(defn- commit-errored-receipt!
  [connection]
  (test-support/transacted! connection
                          [{:seon.turn/id "run-with-receipt" :seon.turn/agent [:seon.agent/id "agent-a"] :seon.turn/opened-tx "datomic.tx" :seon.turn/closed-tx "datomic.tx"}
                           {:seon.cluster.eval/id "receipt-1"
                            :seon.cluster.eval/run [:seon.turn/id "run-with-receipt"]
                            :seon.cluster.eval/ordinal 0
                            :seon.cluster.eval/at now
                            ;; the error's presence IS the errored state
                            :seon.cluster.eval/error "Unable to resolve symbol: widgets"
                            :seon.cluster.eval/source "(widgets)"}]))

(defn- commit-missing-model!
  [connection]
  (test-support/transacted! connection
                            [[:db/add [:seon.config/cluster "default"] :seon.config.ai/model "missing-model"]]))

(def ^:private families
  {:seon.problems/error-signatures commit-error!
   :seon.problems/failed-runs commit-failed-run!
   :seon.problems/errored-receipts commit-errored-receipt!
   :seon.problems/missing-models commit-missing-model!})

(def ^:private optional-error-evidence
  {:seon.error/throwable-class "clojure.lang.ExceptionInfo"
   :seon.error/proc :seon.problems-test/proc
   :seon.error/op :seon.problems-test/op
   :seon.error/cid :seon.problems-test/cid
   :seon.error/basis-t 7
   :seon.instrument/fn "seon.problems-test/generated"
   :seon.instrument/arm :output
   :seon.instrument/expected ":seon.error/fact"
   :seon.instrument/args "[{:generated true}]"})

(def ^:private error-attribution-cases
  [[false false]
   [true false]
   [false true]
   [true true]])

(defn- generated-error-fact
  [ordinal optional-attributes [run? agent?]]
  (cond-> (merge
           {:seon.error/id (apply str (repeat 64 (nth "abcd" ordinal)))
            :seon.error/at now
            :seon.error/process live
            :seon.error/declared-schema :seon.error/normalization-error
            :seon.error/message (str "generated error " ordinal)
            :seon.error/signature
            (apply str (repeat 64 (nth "abcd" ordinal)))
            :seon.error/data-edn "{}"
            :seon.error/capped? false}
           (select-keys optional-error-evidence optional-attributes))
    run? (assoc :seon.error/run
                [:seon.turn/id "generated-error-run"])
    agent? (assoc :seon.error/agent
                  [:seon.agent/id "agent-a"])))

;;; ---------------------------------------------------------------------------
;;; A healthy cluster says nothing at all
;;; ---------------------------------------------------------------------------

(deftest a-healthy-cluster-derives-an-empty-value
  (with-db
    (fn [connection]
      (let [value (found connection)]
        (is (= {} value)
            "not an empty vector per family, not :healthy? true, not a
             count of zero — nothing, because nothing is wrong")
        (is ((seon.schema/projection-validator (seon.schema/handed-projection) :seon.problems/problems) value))))))

(deftest stale-var-findings-declare-no-render-pair
  ;; A finding row whose ONLY attribute is another family's
  ;; `:db.unique/identity` may not declare a render pair. Maps are open, so
  ;; the pair this row used to declare was selected for every `:seon.fn`
  ;; row an agent pulled, and the published program graph answered
  ;; "Restart the JVM to remove stale loaded Var ..." instead of the row.
  ;; The producers stay — `ai-prose` and `html-report` call them directly
  ;; on the rows `stale-vars` derived, which is the one place the staleness
  ;; is actually known.
  (let [properties
        (-> (schema.edn/packaged-forms)
            (get :seon.problems/stale-var)
            (comp seon.schema.internal/entity-properties seon.schema/structural-schema))]
    (is (nil? (:seon.render/ai properties)))
    (is (nil? (:seon.render/html properties)))
    (is (= [:seon.fn/sym]
           (mapv first (seon.schema.internal/entity-entries (mr/schema (:seon.schema.projection/registry (seon.schema/handed-projection)) :seon.problems/stale-var))))
        "the shape itself is unchanged; only the selection path is gone")))

(deftest missing-model-findings-declare-their-render-producers
  (let [properties
        (-> (schema.edn/packaged-forms)
            (get :seon.problems/missing-model)
            (comp seon.schema.internal/entity-properties seon.schema/structural-schema))]
    (is (= `problems/missing-model-ai (:seon.render/ai properties)))
    (is (= `problems/missing-model-html (:seon.render/html properties)))))

(deftest configured-models-without-registry-rows-are-derived
  (with-db
    (fn [connection]
      (test-support/transacted!
                   connection
                   [[:db/add [:seon.config/cluster "default"] :seon.config.ai/model "z-cluster-model"]
                    {:seon.agent/id "agent-a"
                     :seon.config.ai/model "a-agent-model"}
                    {:seon.agent/id "agent-b"
                     :seon.config.ai/model "z-cluster-model"}])
      (let [value (found connection)
            entries (:seon.problems/missing-models value)]
        (is (= [{:seon.config.ai/model "a-agent-model"}
                {:seon.config.ai/model "z-cluster-model"}]
               entries)
            "cluster and agent assertions deduplicate and sort by model id")
        (is (str/includes? (problems/ai-prose value) "a-agent-model"))
        (is (str/includes? (problems/log-report value) "z-cluster-model"))
        (is (str/includes?
             (hiccup/->string (problems/html-report value))
             "no registry row"))
        (is ((seon.schema/projection-validator (seon.schema/handed-projection) :seon.problems/problems) value))

        (is (:db-after
              (db/transact!
               connection
               [{:seon.ai.model/id "a-agent-model" :seon.ai.model/provider [:seon.ai.model/provider-id "deepseek"]}
                {:seon.ai.model/id "z-cluster-model" :seon.ai.model/provider [:seon.ai.model/provider-id "deepseek"]}])))
        (is (nil? (:seon.problems/missing-models (found connection)))
            "adding matching registry rows makes the finding disappear")))))

(deftest a-matching-model-registry-row-prevents-the-finding
  (with-db
    (fn [connection]
      (test-support/transacted! connection
                                [[:db/add [:seon.config/cluster "default"] :seon.config.ai/model "registered-model"]])
      (is (= [{:seon.config.ai/model "registered-model"}]
             (:seon.problems/missing-models (found connection))))
      (is (:db-after
           (db/transact! connection
                         [{:seon.ai.model/id "registered-model" :seon.ai.model/provider [:seon.ai.model/provider-id "deepseek"]}])))
      (is (nil? (:seon.problems/missing-models (found connection)))))))

(deftest missing-model-values-accrete-extra-attributes
  (is ((seon.schema/projection-validator (seon.schema/handed-projection) :seon.problems/missing-model) {:seon.config.ai/model "open-model"
        :seon.test/extra "ignored until declared"})))

(deftest a-deleted-function-var-is-derived-from-the-loaded-image
  (with-db
    (fn [connection]
      (let [database @connection
            namespace-name 'seon.problems-test
            intern-name 'slice-3-stale-var-proof
            qualified-name 'seon.problems-test/slice-3-stale-var-proof
            qualified-text (str qualified-name)
            loaded-namespace (the-ns namespace-name)
            derive-problems #(problems/problems database {})]
        (is (nil? (ns-resolve loaded-namespace intern-name)))
        (is (nil? (:seon.problems/stale-vars (derive-problems)))
            "the synchronized source image and program graph are clean")
        (when-not (ns-resolve loaded-namespace intern-name)
          (let [created
                (binding [*ns* loaded-namespace]
                  (eval (list 'defn intern-name [] :stale)))]
            (try
              (let [value (derive-problems)
                    stale-vars (:seon.problems/stale-vars value)]
                (is (= [{:seon.fn/sym qualified-name}] stale-vars))
                (is ((seon.schema/projection-validator (seon.schema/handed-projection) :seon.problems/problems) value))
                (is (str/includes? (problems/ai-prose value) qualified-text))
                (is (str/includes? (problems/log-report value) qualified-text))
                (is (str/includes?
                     (hiccup/->string (problems/html-report value))
                     qualified-text)))
              (finally
                (when (identical? created
                                  (ns-resolve loaded-namespace intern-name))
                  (ns-unmap loaded-namespace intern-name))))))
        (is (nil? (ns-resolve loaded-namespace intern-name)))
        (is (nil? (:seon.problems/stale-vars (derive-problems)))
            "removing the process-local Var makes the finding disappear")))))

;;; ---------------------------------------------------------------------------
;;; One family at a time
;;; ---------------------------------------------------------------------------

(deftest errors-are-grouped-by-signature-not-listed-one-by-one
  (with-db
    (fn [connection]
      (dotimes [_ 3] (commit-error! connection))
      (let [value (found connection)
            entries (:seon.problems/error-signatures value)
            entry (first entries)]
        (is (= 1 (count entries))
            "three occurrences of one signature is ONE problem")
        (is (= 3 (:seon.problems/occurrences entry)))
        (is (= 'seon.problems-test/rejected (:seon.error/operation entry)))
        (is ((seon.schema/projection-validator (seon.schema/handed-projection) :seon.error/fact) (:seon.error/fact entry))
            "the latest occurrence rides along in full, so a digger needs
             no second lookup")
        (is ((seon.schema/projection-validator (seon.schema/handed-projection) :seon.problems/problems) value)))))
  (with-db
    (fn [connection]
      (testing "and two different KINDS stay two problems"
        (commit-error! connection :seon.db/rejected)
        (commit-error! connection :seon.db/rejected)
        (commit-error! connection :seon.ai/timeout)
        (let [entries (:seon.problems/error-signatures (found connection))]
          (is (= 2 (count entries)))
          (is (= [2 1] (mapv :seon.problems/occurrences entries))
              "worst-recurring first, so the pattern is the first thing
               read rather than something to scan for"))))))

(deftest every-committed-error-fact-shape-is-projectable
  (with-db
    (fn [connection]
      (test-support/transacted! connection [{:seon.turn/id "generated-error-run"
                                            :seon.turn/agent [:seon.agent/id "agent-a"]
                                            :seon.turn/opened-tx "datomic.tx"}])
      (doseq [[ordinal attribution] (map-indexed vector error-attribution-cases)]
        (let [fact (generated-error-fact ordinal (keys optional-error-evidence) attribution)
              result (db/transact! connection
                                  (error/commit-tx (db/db connection)
                                                  {:seon.error/declared-schema :seon.error/normalization-error
                                                   :seon.error/fact fact :seon.error/source {}
                                                   :seon.error/id (:seon.error/id fact)
                                                   :seon.error/at now :seon.error/process live
                                                   :seon.sci.admit/caps caps
                                                   :seon.config.error/max-evidence-bytes 16384
                                                   :seon.config.error/recurrence-limit 100}))]
          (is (:db-after result) (pr-str (dissoc result :db-before :db-after)))))
      (let [value (found connection)
            facts (mapv :seon.error/fact (:seon.problems/error-signatures value))]
        (is (= 4 (count facts)))
        (is (every? #((seon.schema/projection-validator (seon.schema/handed-projection) :seon.error/fact) %) facts))
        (is ((seon.schema/projection-validator (seon.schema/handed-projection) :seon.problems/problems) value))
        (is (= 4 (count (str/split-lines (problems/log-report value)))))
        (is (hiccup/hiccup? (problems/html-report value)))))))

(deftest incomplete-error-entities-are-refused
  (with-db
    (fn [connection]
      (let [result (db/transact! connection
                                 [{:seon.error/signature (apply str (repeat 64 "a"))}])]
        (is (= 'seon.db/transact! (:seon.error/operation result)))
        (is (= :seon.db/database-write (:seon.error/layer result)))
        (is (empty? (:seon.problems/error-signatures (found connection))))))))

(deftest a-run-that-closed-with-an-error-says-why
  (with-db
    (fn [connection]
      (commit-failed-run! connection)
      (let [entry (first (:seon.problems/failed-runs (found connection)))]
        (is (= "run-failed" (:seon.turn/id entry)))
        (is (= "the model did not answer" (:seon.error/message entry)))))))

(deftest an-errored-receipt-is-a-problem-without-being-a-fault
  (with-db
    (fn [connection]
      (commit-errored-receipt! connection)
      (let [value (found connection)
            entry (first (:seon.problems/errored-receipts value))]
        (is (= "receipt-1" (:seon.cluster.eval/id entry)))
        (is (= "run-with-receipt" (:seon.turn/id entry)))
        (is (= 0 (:seon.cluster.eval/ordinal entry)))
        (is (str/includes? (:seon.cluster.eval/error entry) "widgets"))
        (is (= "(widgets)" (:seon.cluster.eval/source entry)))
        (is (str/includes?
             (problems/ai-prose value)
             "Form 0 failed during evaluation"))
        (is (nil? (:seon.problems/error-signatures value))
            "an agent's own mistake never became an error FACT, and the
             distinction survives into the value")))))

;;; ---------------------------------------------------------------------------
;;; THE ABSENCE PROPERTY — the standing one
;;; ---------------------------------------------------------------------------

(deftest absent-facts-produce-no-entries
  (let [subsets (reduce (fn [sets family]
                          (into sets (map #(conj % family) sets)))
                        [#{}] (keys families))
        exercised (atom [])
        acquisitions (atom 0)
        render-calls (atom {})
        acquire with-db
        log-report problems/log-report
        html-report problems/html-report
        verify-value
        (fn [connection present occurrences]
          (let [value (found connection)
                expected (cond-> present
                           (present :seon.problems/failed-runs)
                           (conj :seon.problems/error-signatures))
                _ (reset! render-calls {})
                log (problems/log-report value)
                html (problems/html-report value)
                rows (filter
                      (fn [node]
                        (and (vector? node)
                             (= "seon-problems-row" (:class (nth node 1 nil)))))
                      (tree-seq sequential? seq html))
                row-count (+ (count present)
                             (if (present :seon.problems/failed-runs) 1 0))]
            (is (= {:log 1 :html 1} @render-calls)
                "Each derived value reaches each renderer exactly once.")
            (is (every? #(seq (get value %)) present))
            (is (= expected (set (keys value))))
            (is (= (empty? present) (= {} value)))
            (is ((seon.schema/projection-validator (seon.schema/handed-projection) :seon.problems/problems) value))
            (is (= present (set/intersection present (set (keys value)))))
            (is (= row-count (count (remove str/blank? (str/split-lines log)))))
            (is (= row-count (count rows)))
            (is (hiccup/hiccup? html))
            (when (present :seon.problems/error-signatures)
              (let [signatures (:seon.problems/error-signatures value)
                    signature (first (filter #(= 'seon.problems-test/rejected
                                                  (:seon.error/operation %)) signatures))]
                (is (= occurrences (:seon.problems/occurrences signature)))
                (is (= (if (present :seon.problems/failed-runs) 2 1)
                       (count signatures)))))))]
    (with-redefs [with-db (fn [body] (swap! acquisitions inc) (acquire body))
                  problems/log-report
                  (fn [value] (swap! render-calls update :log (fnil inc 0))
                    (log-report value))
                  problems/html-report
                  (fn [value] (swap! render-calls update :html (fnil inc 0))
                    (html-report value))]
      (doseq [present subsets]
        (with-db
          (fn [connection]
            (swap! exercised conj present)
            (doseq [family present] ((families family) connection))
            (verify-value connection present 1))))
      (with-db
        (fn [connection]
          (commit-error! connection)
          (verify-value connection #{:seon.problems/error-signatures} 1)
          (dotimes [_ 4] (commit-error! connection))
          (verify-value connection #{:seon.problems/error-signatures} 5))))
    (is (= 16 (count @exercised) (count (set @exercised))))
    (is (= 17 @acquisitions))))

(deftest the-block-derives-at-the-units-own-database-value
  (with-db
    (fn [connection]
      (let [healthy @connection
            _ (commit-failed-run! connection)
            broken @connection
            render-at (fn [db]
                        (hiccup/->string
                         (problems/block
                          {:seon.db/db db
                           })))]
        (is (str/includes? (render-at healthy) "nothing is wrong")
            "the healthy surface still occupies its space")
        (is (str/includes? (render-at broken) "the model did not answer"))
        (is (not (str/includes? (render-at broken) "nothing is wrong")))))))

(deftest missing-projection-is-a-refusal-not-query-rows
  (test-support/with-database
   (fn [connection]
     (let [raw @connection
           warnings (java.io.StringWriter.)
           result (binding [*err* warnings]
                    (seon.schema/call-with-projection-state
                     (atom {}) #(problems/problems raw {})))]
       (is (= 'seon.problems/problems (:seon.error/operation result)))
       (is (= :seon.db/projection (:seon.error/layer result)))
       (is (= 'seon.problems/problems
              (get-in result [:seon.error/data :seon.db/operation])))
       (is (= 1 (count (str/split-lines (str warnings)))))))))

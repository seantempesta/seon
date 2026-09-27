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
            [seon.agent :as agent]
            [seon.db :as db]
            [seon.config :as config]
            [seon.error :as error]
            [seon.ai.tokens :as tokens]
            [seon.problems :as problems]
            [seon.render.hiccup :as hiccup]
            [seon.schema]
            [seon.schema.edn :as schema.edn]
            [seon.test]
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

(defn- world-namespaces
  "The namespaces the member's own world leaves unstewarded. The test world
  carries default's agent-authored namespace rows without their agents
  (S2c), so those rows are the world's findings, never a fixture's."
  {:malli/schema [:=> [:cat] [:set [:map [:seon.ns/name :seon.ns/name]]]]}
  []
  ;; Only this family is read: the whole derivation per `found` doubled the cost.
  (set (#'problems/unstewarded-namespaces (db/db (:seon.db/connection seon.test/*member*)))))

(defn- found
  "The problems the fixture's own facts derive: the world's findings removed."
  [connection]
  (let [value (problems/problems @connection {})
        own (vec (remove (world-namespaces) (:seon.problems/unowned-namespaces value)))]
    (cond (:seon.error/at value) value
          (seq own) (assoc value :seon.problems/unowned-namespaces own)
          :else (dissoc value :seon.problems/unowned-namespaces))))

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
  ([connection cause] (commit-error! connection cause now))
  ([connection cause at]
   (db/transact!
    connection
    (error/commit-tx
     @connection
     {:seon.error/declared-schema :seon.error/normalization-error
      :seon.error/source
      {:seon.error/at at
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
      :seon.error/at at
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
   :seon.instrument/fn 'seon.problems-test/generated
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
            :seon.error/layer ::generated
            :seon.error/operation 'seon.problems-test/generated
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
  ;; A finding entity whose ONLY attribute is another family's
  ;; `:db.unique/identity` may not declare a render pair. Maps are open, so
  ;; the pair this entity used to declare was selected for every `:seon.fn`
  ;; entity an agent pulled, and the published program graph answered
  ;; "Restart the JVM to remove stale loaded Var ..." instead of the entity.
  ;; The producers stay — `ai-prose` and `html-report` call them directly
  ;; on the entities `stale-vars` derived, which is the one place the staleness
  ;; is actually known.
  (let [properties
        (-> (schema.edn/packaged-forms)
            (get :seon.problems/stale-var)
            seon.schema/structural-schema
            seon.schema.internal/entity-properties)]
    (is (nil? (:seon.render/ai properties)))
    (is (nil? (:seon.render/html properties)))
    (is (= [:seon.fn/sym]
           (mapv first (seon.schema.internal/entity-entries (mr/schema (:seon.schema.projection/registry (seon.schema/handed-projection)) :seon.problems/stale-var))))
        "the shape itself is unchanged; only the selection path is gone")))

(deftest missing-model-findings-declare-their-render-producers
  (let [properties
        (-> (schema.edn/packaged-forms)
            (get :seon.problems/missing-model)
            seon.schema/structural-schema
            seon.schema.internal/entity-properties)]
    (is (= `problems/missing-model-ai (:seon.render/ai properties)))
    (is (= `problems/missing-model-html (:seon.render/html properties)))))

(deftest configured-models-without-registry-entities-are-derived
  (with-db
    (fn [connection]
      (test-support/transacted!
       connection
       (into [[:db/add [:seon.config/cluster "default"] :seon.config.ai/model "z-cluster-model"]]
             (test-support/agent-tx @connection "agent-b")))
      ;; An agent's model override is its one owned settings component.
      (is (= {:seon.config.ai/model "a-agent-model"}
             (agent/settings! {:seon.config.ai/model "a-agent-model"} connection "agent-a")))
      (is (= {:seon.config.ai/model "z-cluster-model"}
             (agent/settings! {:seon.config.ai/model "z-cluster-model"} connection "agent-b")))
      (let [value (found connection)
            entries (:seon.problems/missing-models value)]
        (is (= [{:seon.config.ai/model "a-agent-model"}
                {:seon.config.ai/model "z-cluster-model"}]
               entries)
            "cluster and agent assertions deduplicate and sort by model id")
        (is (str/includes? (problems/ai-prose value) "a-agent-model"))
        (is (str/includes?
             (hiccup/->string (problems/html-report value))
             "no registry entity"))
        (is ((seon.schema/projection-validator (seon.schema/handed-projection) :seon.problems/problems) value))

        (is (:db-after
              (db/transact!
               connection
               [{:seon.ai.model/id "a-agent-model" :seon.ai.model/provider [:seon.ai.model/provider-id "deepseek"]}
                {:seon.ai.model/id "z-cluster-model" :seon.ai.model/provider [:seon.ai.model/provider-id "deepseek"]}])))
        (is (nil? (:seon.problems/missing-models (found connection)))
            "adding matching registry entities makes the finding disappear")))))

(deftest a-matching-model-registry-entity-prevents-the-finding
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

(deftest a-resolved-fault-stays-closed-until-it-recurs
  (with-db
    (fn [connection]
      (let [open (fn [] (->> (:seon.problems/error-signatures (found connection))
                             (problems/open-faults @connection)
                             (filterv #(= 'seon.problems-test/rejected (:seon.error/operation %)))))
            _ (is (:db-after (commit-error! connection)))
            [entry] (open)
            signature (:seon.error/signature entry)]
        (is (= 1 (count (open))) "a recorded fault with no resolution is open")
        (is (:db-after (problems/resolve! connection signature)))
        (is (= [] (open)) "a resolution after the latest occurrence closes it")
        (is (= 1 (count (:seon.problems/error-signatures (found connection))))
            "history is kept: the signature is still a stored fact")
        (is (:db-after (commit-error! connection :seon.db/rejected (java.util.Date.))))
        (is (= [signature] (mapv :seon.error/signature (open)))
            "a later occurrence re-opens the same signature")
        (is (:seon.error/at (problems/resolve! connection (apply str (repeat 64 "0"))))
            "resolving an unrecorded signature is refused, not a silent no-op")))))

(deftest a-fault-resolves-when-its-function-changes-and-reopens-on-recurrence
  (with-db
    (fn [connection]
      (let [function (symbol "my.resolution.fixture" "target")
            define! (fn [source]
                      (test-support/transacted!
                       connection [(test-support/namespace-entity @connection 'my.resolution.fixture)
                                   (test-support/program-fn-entity @connection function source)]))
            fault! (fn []
                     (test-support/transacted!
                      connection
                      (error/commit-tx
                       @connection
                       {:seon.schema/projection (seon.schema/handed-projection)
                        :seon.error/source {:clojure.core.async.flow/ex
                                            (ex-info "boom" {:seon.instrument/fn function :seon.instrument/arm :input})}
                        :seon.error/declared-schema :seon.flow/exception-error
                        :seon.error/id (str (random-uuid)) :seon.error/at (java.util.Date.) :seon.error/process live
                        :seon.sci.admit/caps caps :seon.config.error/escalate-to "agent-a"
                        :seon.config.error/recurrence-limit 100 :seon.config.error/max-evidence-bytes 16384})))
            signature #(db/q '[:find ?s . :in $ ?f :where [?e :seon.error/signature ?s] [?e :seon.instrument/fn ?f]]
                             @connection function)
            open? #(= [(signature)] (mapv :seon.error/signature
                                          (problems/open-faults @connection (vec (:seon.problems/error-signatures (found connection))))))
            messages #(db/q '[:find (count ?m) . :in $ ?s :where [?m :seon.message/about ?s]] @connection (signature))]
        (define! "(defn target [] 1)")
        (fault!)
        (is (string? (signature)) "the recorded fault names its failing function")
        (is (open?) "a fault whose function is unchanged is open")
        (is (= 1 (messages)))
        (fault!)
        (is (open?) "a repeat under the same code keeps it open")
        (is (= 1 (messages)) "and is not delivered again")
        (define! "(defn target [] 2)")
        (let [resolved (error/resolution @connection (signature))]
          (is (= :seon.program/definition-digest (:seon.error/resolved-by resolved)) (pr-str resolved))
          (is (= function (:seon.instrument/fn resolved)))
          (is (not= (:seon.error/digest-then resolved) (:seon.error/digest-now resolved))))
        (is (not (open?)) "a changed definition resolves it without resolve!")
        (let [value (found connection)
              entry (some #(when (= (signature) (:seon.error/signature %)) %) (:seon.problems/error-signatures value))]
          (is (= :seon.program/definition-digest (get-in entry [:seon.error/resolution :seon.error/resolved-by]))
              "the problems value keeps the resolved signature and says why")
          (is ((seon.schema/projection-validator (seon.schema/handed-projection) :seon.problems/problems) value))
          (is (str/includes? (pr-str (problems/html-report value)) "by change to my.resolution.fixture/target")
              "the problems page shows the resolution, so a false one can be checked"))
        (fault!)
        (is (open?) "a recurrence under the changed code reopens it")
        (is (= 2 (messages)) "the reopening is delivered once")
        (fault!)
        (is (= 2 (messages)) "a repeat under the changed code is not delivered again")))))

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
        ;; Each world is an ordinary fixture acquisition, counted here: no
        ;; Var is redefined.
        acquire (fn [body] (swap! acquisitions inc) (with-db body))
        verify-value
        (fn [connection present occurrences]
          (let [value (found connection)
                expected (cond-> present
                           (present :seon.problems/failed-runs)
                           (conj :seon.problems/error-signatures))
                html (problems/html-report value)
                entities (filter
                      (fn [node]
                        (and (vector? node)
                             (= "seon-problems-row" (:class (nth node 1 nil)))))
                      (tree-seq sequential? seq html))
                entity-count (+ (count present)
                             (if (present :seon.problems/failed-runs) 1 0))]
            (is (every? #(seq (get value %)) present))
            (is (= expected (set (keys value))))
            (is (= (empty? present) (= {} value)))
            (is ((seon.schema/projection-validator (seon.schema/handed-projection) :seon.problems/problems) value))
            (is (= present (set/intersection present (set (keys value)))))
            (is (= entity-count (count entities)))
            (is (hiccup/hiccup? html))
            (when (present :seon.problems/error-signatures)
              (let [signatures (:seon.problems/error-signatures value)
                    signature (first (filter #(= 'seon.problems-test/rejected
                                                  (:seon.error/operation %)) signatures))]
                (is (= occurrences (:seon.problems/occurrences signature)))
                (is (= (if (present :seon.problems/failed-runs) 2 1)
                       (count signatures)))))))]
    (doseq [present subsets]
      (acquire
       (fn [connection]
         (swap! exercised conj present)
         (doseq [family present] ((families family) connection))
         (verify-value connection present 1))))
    (acquire
     (fn [connection]
       (commit-error! connection)
       (verify-value connection #{:seon.problems/error-signatures} 1)
       (dotimes [_ 4] (commit-error! connection))
       (verify-value connection #{:seon.problems/error-signatures} 5)))
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
        (is (= (render-at (db/db (:seon.db/connection seon.test/*member*))) (render-at healthy))
            "the fixture's healthy value renders exactly its world's surface")
        (is (not (str/includes? (render-at healthy) "the model did not answer")))
        (is (str/includes? (render-at broken) "the model did not answer"))))))

(deftest six-hundred-failed-tests-render-within-the-profile-bound
  ;; 649 live failed tests were 313,589 characters of prose with no bound.
  (let [profile {:seon.render.profile/id :seon.render.profile/agent
                 :seon.render.profile/token-budget 15000
                 :seon.render.profile/max-depth 8
                 :seon.render.profile/max-children 32
                 :seon.render.profile/composition :multiline}
        failed (mapv (fn [i]
                       {:seon.test/sym (symbol "seon.fake-test" (str "t-" i))
                        :seon.test/pass-count 0 :seon.test/fail-count 1 :seon.test/error-count 0
                        :seon.test/failure-message (str "expected: (= " i " 0)\n" (str/join (repeat 400 "x")))})
                     (range 600))
        text (problems/ai-prose {:seon.problems/failed-tests failed :seon.render/profile profile})
        shown (count (re-seq #"Test seon\.fake-test/t-\d+" text))
        omitted (some->> text (re-find #"…(\d+)") second parse-long)]
    (is (<= (tokens/estimate text) 15000) "the prose fits the profile's token budget")
    (is (pos? shown) "whole failed-test lines are shown")
    (is (= 600 (+ shown (or omitted 0))) "the elision names every omitted test")
    (is (str/includes? text "(seon.problems/problems (seon.db/db) {})")
        "the cut's continuation reads the full failed-test list")))

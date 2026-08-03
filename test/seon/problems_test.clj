(ns seon.problems-test
  "What `problems` says, and — harder and more important — what it does
  not say.

  The failure this suite exists to prevent is a derivation that reports
  something about facts that are absent: an empty family as an empty
  vector, a `:healthy? true`, a wedged run derived from a clock, a
  count of zero. Every one of those is a status somebody then has to
  maintain, and the whole point of deriving is that nobody does.

  So the shape of the suite is: one fixture per family, the empty
  cluster, and a GENERATIVE ABSENCE PROPERTY over the power set of the
  four families — whatever is absent produces nothing, whatever is
  present produces exactly its own family, for all sixteen
  combinations. Fixed seed 20260727, one fresh in-memory database per
  trial, and the attributes come from
  `canonical-database-attributes` — the live boot derivation, not a
  hand-listed fixture set."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [seon.db :as db]
            [seon.config :as config]
            [seon.error :as error]
            [seon.problems :as problems]
            [seon.render :as render]
            [seon.render.hiccup :as hiccup]
            [seon.schema]
            [seon.test-support :as test-support]))

(def ^:private live "9999-1785191833372")
(def ^:private dead "1234-1700000000000")
(def ^:private now #inst "2026-07-27T21:00:00.000-00:00")

(def ^:private caps
  (config/result-caps (config/defaults)))

(defn- with-db
  [body]
  (test-support/with-database
    (fn [connection]
      (db/transact! connection [{:seon.cluster.agent/id "agent-a"}])
      (body connection))))

(defn problems-surface
  "The problems block as a producer wires it: a projection that supplies
  the liveness set the pipeline will thread from the process. Package 2
  owns that threading; here the seam is explicit so the test proves the
  block, not the refusal card."
  [unit]
  (problems/block (assoc unit :seon.cluster.run/live-processes #{live})))

(defn- found
  [connection]
  (problems/problems @connection
                     {:seon.cluster.run/live-processes #{live}}))

;;; ---------------------------------------------------------------------------
;;; The four fixtures — each one commits ONLY its own family's facts
;;; ---------------------------------------------------------------------------

(defn- commit-error!
  ;; the KIND is what varies, not the message: the signature
  ;; deliberately excludes the message so that an id or a timestamp in
  ;; it cannot make every occurrence unique. Two errors differing only
  ;; in wording are the same problem, and this fixture would be lying if
  ;; it pretended otherwise (it did, first time round).
  ([connection] (commit-error! connection :seon.db/rejected))
  ([connection kind]
   (db/transact!
    connection
    (error/commit-tx
     @connection
     {:seon.error/source (ex-info "boom" {:seon.error/kind kind})
      ;; DETERMINISTIC, because this fixture runs inside a property: a
      ;; random id would make a shrunk counterexample unreplayable even
      ;; though nothing here reads the id (review-caught)
      :seon.error/id (str "err-" (name kind) "-"
                          (count (db/q '[:find ?e :where [?e :seon.error/id _]]
                                      @connection)))
      :seon.error/at now
      :seon.error/process live
      :seon.sci.admit/caps caps
      ;; no escalate-to: this suite is about the DERIVATION, and a
      ;; message would only add facts the derivation does not read
      :seon.config.error/recurrence-limit 3}))))

(defn- commit-wedged-run!
  [connection]
  (db/transact! connection
              [{:seon.cluster.run/id "run-wedged"
                :seon.cluster.run/agent [:seon.cluster.agent/id "agent-a"]
                :seon.cluster.run/opened-at now
                :seon.cluster.run/process dead}]))

(defn- commit-failed-run!
  [connection]
  (db/transact! connection
              [{:seon.cluster.run/id "run-failed"
                :seon.cluster.run/agent [:seon.cluster.agent/id "agent-a"]
                :seon.cluster.run/opened-at now
                :seon.cluster.run/closed-at now
                :seon.cluster.run/error "the model did not answer"}]))

(defn- commit-errored-receipt!
  [connection]
  (db/transact! connection
              [{:seon.cluster.run/id "run-with-receipt"
                :seon.cluster.run/agent [:seon.cluster.agent/id "agent-a"]
               :seon.cluster.run/opened-at now
                :seon.cluster.run/closed-at now}
               {:seon.cluster.run.form/id "run-with-receipt:0"
                :seon.cluster.run.form/run
                [:seon.cluster.run/id "run-with-receipt"]
                :seon.cluster.run.form/ordinal 0
                :seon.cluster.run.form/source "(widgets)"}
               {:seon.cluster.eval/id "receipt-1"
                :seon.cluster.eval/run [:seon.cluster.run/id "run-with-receipt"]
                :seon.cluster.eval/ordinal 0
                :seon.cluster.eval/at now
                ;; the error's presence IS the errored state
                :seon.error/kind :seon.sci.eval/evaluation-failed
                :seon.cluster.eval/error "Unable to resolve symbol: widgets"}]))

(def ^:private families
  {:seon.problems/error-signatures commit-error!
   :seon.problems/wedged-runs commit-wedged-run!
   :seon.problems/failed-runs commit-failed-run!
   :seon.problems/errored-receipts commit-errored-receipt!})

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
        (is (empty? (render/kinds value))
            "and it declares no projection: there is nothing to say")
        (is (seon.schema/valid-candidate-value? :seon.problems/problems value))))))

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
        (is (= :seon.db/rejected (:seon.error/kind entry)))
        (is (seon.schema/valid-candidate-value? :seon.error/fact
                                                (:seon.error/fact entry))
            "the latest occurrence rides along in full, so a digger needs
             no second lookup")
        (is (seon.schema/valid-candidate-value? :seon.problems/problems value)))))
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

(deftest a-run-held-by-a-dead-process-is-wedged
  (with-db
    (fn [connection]
      (commit-wedged-run! connection)
      (let [entry (first (:seon.problems/wedged-runs (found connection)))]
        (is (= "run-wedged" (:seon.cluster.run/id entry)))
        (is (= "agent-a" (:seon.cluster.agent/id entry)))
        (is (= dead (:seon.cluster.run/process entry))
            "the holder is named, because the next question is always
             which process")
        (testing "and the SAME run held by a LIVE process is not a problem —
        wedged is derived from liveness, never from a clock"
          (is (empty? (:seon.problems/wedged-runs
                       (problems/problems
                        @connection
                        {:seon.cluster.run/live-processes #{live dead}})))))))))

(deftest a-run-that-closed-with-an-error-says-why
  (with-db
    (fn [connection]
      (commit-failed-run! connection)
      (let [entry (first (:seon.problems/failed-runs (found connection)))]
        (is (= "run-failed" (:seon.cluster.run/id entry)))
        (is (= "the model did not answer" (:seon.cluster.run/error entry)))))))

(deftest an-errored-receipt-is-a-problem-without-being-a-fault
  (with-db
    (fn [connection]
      (commit-errored-receipt! connection)
      (let [value (found connection)
            entry (first (:seon.problems/errored-receipts value))]
        (is (= "receipt-1" (:seon.cluster.eval/id entry)))
        (is (= "run-with-receipt" (:seon.cluster.run/id entry)))
        (is (= 0 (:seon.cluster.eval/ordinal entry)))
        (is (str/includes? (:seon.cluster.eval/error entry) "widgets"))
        (is (= "(widgets)" (:seon.cluster.run.form/source entry)))
        (is (= :seon.sci.eval/evaluation-failed (:seon.error/kind entry)))
        (is (str/includes?
             (:seon.render/output
              (render/render {:seon.render/unit value
                              :seon.render/kind :seon.render/ai}))
             "Form 0 failed during evaluation"))
        (is (nil? (:seon.problems/error-signatures value))
            "an agent's own mistake never became an error FACT, and the
             distinction survives into the value")))))

;;; ---------------------------------------------------------------------------
;;; THE ABSENCE PROPERTY — the standing one
;;; ---------------------------------------------------------------------------

(deftest absent-facts-produce-no-entries
  (let [result
        (tc/quick-check
         40
         (prop/for-all
          [present (gen/set (gen/elements (keys families)))]
          (with-db
            (fn [connection]
              (doseq [family present] ((families family) connection))
              (let [value (found connection)]
                (and
                 ;; every family that has facts is reported, once
                 (every? (fn [family] (seq (get value family))) present)
                 ;; and NOTHING else appears — this is the half that a
                 ;; stored status would fail
                 ;; `log` and `html` ride together — anything wrong is
                 ;; worth a line AND worth a surface; `ai` only rides
                 ;; when there is something to steer
                 (empty? (remove (cond-> (conj present
                                               :seon.render/log
                                               :seon.render/html)
                                   (contains? present
                                              :seon.problems/errored-receipts)
                                   (conj :seon.render/ai))
                                 (keys value)))
                 ;; empty means empty: `{}`, never `{family []}`
                 (= (empty? present) (= {} value))
                 (seon.schema/valid-candidate-value? :seon.problems/problems
                                                     value))))))
         :seed 20260727)]
    (test-support/assert-check! result "Absent facts produced entries.")))

;;; ---------------------------------------------------------------------------
;;; Structured twins — one family value, two presentations
;;; ---------------------------------------------------------------------------

(deftest projection-twins-preserve-the-generated-family-structure
  (test-support/assert-check!
   (tc/quick-check
    24
    (prop/for-all [present
                   (gen/not-empty
                    (gen/set (gen/elements (vec (keys families)))))
                   error-occurrences (gen/choose 1 5)]
      (with-db
        (fn [connection]
          (doseq [family present]
            (if (= :seon.problems/error-signatures family)
              (dotimes [_ error-occurrences] (commit-error! connection))
              ((get families family) connection)))
          (let [value (found connection)
                log (problems/log-report value)
                html (problems/html-report value)
                routed-log
                (:seon.render/output
                 (render/render {:seon.render/unit value
                                 :seon.render/kind :seon.render/log}))
                routed-html
                (:seon.render/output
                 (render/render {:seon.render/unit value
                                 :seon.render/kind :seon.render/html}))
                rows
                (filter
                 (fn [node]
                   (and (vector? node)
                        (= "seon-problems-row"
                           (:class (nth node 1 nil)))))
                 (tree-seq sequential? seq html))]
            (and
             (seon.schema/valid-candidate-value?
              :seon.problems/problems value)
             (= present (set/intersection present (set (keys value))))
             (= (count present) (count (str/split-lines log)))
             (= (count present) (count rows))
             (= log routed-log)
             (= html routed-html)
             (hiccup/hiccup? html)
             (or (not (contains? present
                                 :seon.problems/error-signatures))
                 (let [[signature]
                       (:seon.problems/error-signatures value)]
                   (and (= error-occurrences
                           (:seon.problems/occurrences signature))
                        (= 1
                           (count
                            (:seon.problems/error-signatures value)))))))))))
    :seed 202607280902)
   "problems projection twins"))

(deftest the-block-derives-at-the-units-own-database-value
  (with-db
    (fn [connection]
      (let [healthy @connection
            _ (commit-wedged-run! connection)
            broken @connection
            render-at (fn [db]
                        (hiccup/->string
                         (problems/block
                          {:seon.db/db db
                           :seon.cluster.run/live-processes #{live}})))]
        (is (str/includes? (render-at healthy) "nothing is wrong")
            "the healthy surface still occupies its space")
        (is (str/includes? (render-at broken) "not alive"))
        (is (not (str/includes? (render-at broken) "nothing is wrong")))))))

(deftest the-block-refuses-legibly-when-liveness-is-not-supplied
  ;; The one input a database cannot answer. Defaulting it would either
  ;; invent problems (#{} makes every held run wedged) or hide them.
  (with-db
    (fn [connection]
      (commit-wedged-run! connection)
      (let [refused (problems/block {:seon.db/db @connection})]
        (is (hiccup/hiccup? refused))
        (is (str/includes? (hiccup/->string refused) "live-processes"))))))

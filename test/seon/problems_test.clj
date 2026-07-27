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
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datahike.api :as d]
            [seon.error :as error]
            [seon.problems :as problems]
            [seon.render :as render]
            [seon.schema]
            [seon.schema.datahike :as schema.datahike]))

(def ^:private live "9999-1785191833372")
(def ^:private dead "1234-1700000000000")
(def ^:private now #inst "2026-07-27T21:00:00.000-00:00")

(def ^:private caps
  {:seon.config.eval.result/max-depth 6
   :seon.config.eval.result/max-collection 8
   :seon.config.eval.result/max-string 64
   :seon.config.eval.result/max-nodes 512})

(defn- with-db [body]
  (let [configuration {:store {:backend :memory :id (random-uuid)}
                       :schema-flexibility :write}
        _ (d/create-database configuration)
        connection (d/connect configuration)]
    (try
      (d/transact connection
                  (schema.datahike/malli->datahike-schema
                   (seon.schema/canonical-database-attributes)))
      (d/transact connection [{:seon.cluster.agent/id "agent-a"}])
      (body connection)
      (finally
        (d/release connection)
        (d/delete-database configuration)))))

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
   (d/transact
    connection
    (error/commit-tx
     @connection
     {:seon.error/source (ex-info "boom" {:seon.error/kind kind})
      ;; DETERMINISTIC, because this fixture runs inside a property: a
      ;; random id would make a shrunk counterexample unreplayable even
      ;; though nothing here reads the id (review-caught)
      :seon.error/id (str "err-" (name kind) "-"
                          (count (d/q '[:find ?e :where [?e :seon.error/id _]]
                                      @connection)))
      :seon.error/at now
      :seon.error/process live
      :seon.sci.admit/caps caps
      ;; no escalate-to: this suite is about the DERIVATION, and a
      ;; message would only add facts the derivation does not read
      :seon.config.error/recurrence-limit 3}))))

(defn- commit-wedged-run!
  [connection]
  (d/transact connection
              [{:seon.cluster.run/id "run-wedged"
                :seon.cluster.run/agent [:seon.cluster.agent/id "agent-a"]
                :seon.cluster.run/opened-at now
                :seon.cluster.run/process dead
                :seon.cluster.run/claim-epoch 1}]))

(defn- commit-failed-run!
  [connection]
  (d/transact connection
              [{:seon.cluster.run/id "run-failed"
                :seon.cluster.run/agent [:seon.cluster.agent/id "agent-a"]
                :seon.cluster.run/opened-at now
                :seon.cluster.run/closed-at now
                :seon.cluster.run/error "the model did not answer"}]))

(defn- commit-errored-receipt!
  [connection]
  (d/transact connection
              [{:seon.cluster.run/id "run-with-receipt"
                :seon.cluster.run/agent [:seon.cluster.agent/id "agent-a"]
                :seon.cluster.run/opened-at now
                :seon.cluster.run/closed-at now}
               {:seon.cluster.eval/id "receipt-1"
                :seon.cluster.eval/run [:seon.cluster.run/id "run-with-receipt"]
                :seon.cluster.eval/ordinal 0
                :seon.cluster.eval/claim-epoch 1
                :seon.cluster.eval/at now
                :seon.cluster.eval/status :error
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
                 (empty? (remove (conj present :seon.render/log)
                                 (keys value)))
                 ;; empty means empty: `{}`, never `{family []}`
                 (= (empty? present) (= {} value))
                 (seon.schema/valid-candidate-value? :seon.problems/problems
                                                     value))))))
         :seed 20260727)]
    (is (:pass? result) (pr-str (:shrunk result)))))

;;; ---------------------------------------------------------------------------
;;; The log projection composes, and routes
;;; ---------------------------------------------------------------------------

(deftest the-log-report-composes-the-per-fact-lines
  (with-db
    (fn [connection]
      (commit-error! connection)
      (commit-wedged-run! connection)
      (commit-failed-run! connection)
      (commit-errored-receipt! connection)
      (let [value (found connection)
            report (problems/log-report value)
            lines (str/split-lines report)
            fact (:seon.error/fact
                  (first (:seon.problems/error-signatures value)))]
        (is (= 4 (count lines)) "one line per problem, one problem per line")
        (is (str/starts-with? (first lines)
                              (error/log-line
                               (error/notice {:seon.error/fact fact})))
            "an error's line IS seon.error's line — one owner decides what
             an error looks like in a log, and problems composes it")
        (is (every? #(str/includes? % "run-") (rest lines)))
        (testing "and it routes through the ONE projection router, because
        a problems value is a unit like any other"
          (is (= report
                 (:seon.render/output
                  (render/render {:seon.render/unit value
                                  :seon.render/kind :seon.render/log})))))))))

(deftest a-healthy-cluster-logs-nothing
  (with-db
    (fn [connection]
      (is (= "" (problems/log-report (found connection)))
          "a cheerful `no problems` line is noise in a log that exists to
           be grepped"))))

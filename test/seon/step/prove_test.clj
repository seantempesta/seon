(ns seon.step.prove-test
  "The Prove stage's handoff (`docs/seon/schedule/cards/TASK-CHAIN.md#prove-seonstepprovetemplate`)."
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.step.prove :as sut]
            [seon.test]
            [seon.test-support :as test-support]))

(def ^:private source
  (str "(ns tq.prv (:require [clojure.test :refer [deftest is]]))\n"
       "(defn other {:malli/schema [:=> [:cat :int] :int]} [x] x)\n"
       "(defn producer {:malli/schema [:=> [:cat :int] :int]} [x] (inc (other x)))\n"
       "(deftest assertion-red (is (= 3 (producer 1))))\n"
       "(deftest body-error (is (= 1 (producer \"x\"))))\n"
       "(deftest green (is (= 2 (producer 1))))\n"
       "(deftest green-nothing (is (= 1 1)))\n"
       "(deftest unreaching-red (is (= 1 2)))\n"
       "(deftest never-run (is (= 1 (producer 0))))\n"))

(def ^:private approach "## Approach\n\nChange `tq.prv/producer`.")

(def ^:private rows
  "Row name, the plan's text, the successor's proofs (nil: no successor) and the exact refusals: each check with what
  it names. Every function of the fixture counts as changed (the test world has no fork point), so a function no
  proof reaches is unreached, whether the plan cites it or not."
  [["no successor" approach nil [[:no-proofs 'tq.prv/r1]]]
   ["empty proofs" approach #{} [[:no-proofs 'tq.prv/r2]]]
   ["not a deftest" approach '#{tq.prv/no-such-test} [[:not-a-deftest '[tq.prv/no-such-test]]
                                                      [:unreached '[tq.prv/other tq.prv/producer]]]]
   ["a green proof" approach '#{tq.prv/green} [[:not-designed-red '[tq.prv/green]]]]
   ["a proof never run" approach '#{tq.prv/never-run} [[:not-designed-red '[tq.prv/never-run]]]]
   ["assertion red reaching the producer" approach '#{tq.prv/assertion-red} []]
   ["body error from the code under test" approach '#{tq.prv/body-error} []]
   ["red reaching nothing" approach '#{tq.prv/unreaching-red} [[:unreached '[tq.prv/other tq.prv/producer]]]]
   ["every failed check is reported" approach '#{tq.prv/green-nothing}
    [[:not-designed-red '[tq.prv/green-nothing]] [:unreached '[tq.prv/other tq.prv/producer]]]]])

(defn- chain
  "The task maps of row number `i`: the Prove task `tq.prv/r<i>` and, when the row has proofs, its successor."
  {:malli/schema [:=> [:cat :int :string [:maybe :string] [:maybe [:set :symbol]]] [:vector :map]]}
  [i _ plan proofs]
  (let [id (- (* 2 i)) handle (str "r" i)]
    (cond-> [(cond-> {:db/id id :seon.task/ns 'tq.prv :seon.task/name handle :seon.task/plan plan}
               proofs (assoc :seon.task/next (dec id)))]
      proofs (conj (cond-> {:db/id (dec id) :seon.task/ns 'tq.prv :seon.task/name (str handle "-next")}
                     (seq proofs) (assoc :seon.task/proofs proofs))))))

(deftest handoff
  (test-support/with-database
   (fn [connection]
     (test-support/declare-source! connection 'tq.prv source)
     (seon.test/run {:seon.test/execution (test-support/execution-handle connection)
                     :seon.test/recording-connection connection
                     :seon.test/policy :named :seon.test/rerun? true
                     :seon.test/identities '#{tq.prv/assertion-red tq.prv/body-error tq.prv/green tq.prv/green-nothing tq.prv/unreaching-red}})
     (let [numbered (map-indexed (fn [i row] (into [(inc i)] row)) rows)
           input (partial test-support/task-input connection)
           _ (input (into [] (mapcat (fn [[i n plan proofs]] (chain i n plan proofs))) numbered) 'tq.prv/r1)]
       (doseq [[i row-name _ _ expected] numbered
               :let [{task :seon.test/task database :seon.test/branch} (input [] (symbol "tq.prv" (str "r" i)))]]
         (is (= expected (mapv (juxt :seon.step.prove/check :seon.error/offending) (sut/handoff database task))) row-name)))))
  ;; (b) the task the runner supplies, when it supplies one
  (when-let [{task :seon.test/task branch :seon.test/branch} (seon.test/task)]
    (is (= [] (mapv (juxt :seon.step.prove/check :seon.error/offending) (sut/handoff branch task))) "the task the runner supplies satisfies its own handoff")))

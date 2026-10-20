(ns seon.proofs
  "Proof results from a clojure.test run's report data (proto.agent's run-tests-forked
  answer): one status per test var. Used by the agent's turn loop and by root's judge,
  so both read a run the same way."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [clojure.string :as str]))

(defn test-symbol
  "The qualified symbol of a report's :var (\"#'ns/name\" or \"ns/name\"), or nil."
  {:malli/schema [:=> [:cat [:maybe :string]] [:maybe :qualified-symbol]]}
  [v]
  (when v
    (let [s (str/replace v #"^#'" "")]
      (when (str/includes? s "/") (symbol s)))))

(defn results
  "{test-symbol status} of a run: :error or :fail when any assertion of the test was,
  else :pass. A test with no assertion has no entry (a proof missing here is :none)."
  {:malli/schema [:=> [:cat [:map [:reports [:sequential :map]]]] :seon.proof/results]}
  [run]
  (reduce (fn [acc {:keys [type var]}]
            (if-let [t (test-symbol var)]
              (let [rank {:pass 0 :fail 1 :error 2}]
                (update acc t #(if (and % (>= (rank %) (rank type))) % type)))
              acc))
          {} (:reports run)))

(defn red-reports
  "The failing assertions of run for the tests in `tests`, as data for the context."
  {:malli/schema [:=> [:cat [:map [:reports [:sequential :map]]] [:set :qualified-symbol]] [:vector :map]]}
  [run tests]
  (vec (for [{:keys [type var] :as r} (:reports run)
             :when (and (#{:fail :error} type) (tests (test-symbol var)))]
         (-> (select-keys r [:type :message :expected :actual]) (assoc :test (test-symbol var))))))

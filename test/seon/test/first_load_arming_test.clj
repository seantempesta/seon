(ns seon.test.first-load-arming-test
  "A test namespace no boot or adoption loads: the test request that first loads
  it arms its own contracted Vars before its body runs (`seon.test/run`).
  Requires nothing beyond clojure.test, so the request's load is its first."
  (:require [clojure.test :refer [deftest is]]))

(defn- identity-int
  {:malli/schema [:=> [:cat :int] :int]}
  [value]
  value)

(deftest a-namespace-first-loaded-by-a-request-has-its-contracts-armed
  (let [outcome (try (identity-int "not an integer")
                     (catch Throwable failure failure))
        checks (keep (comp :seon.instrument/check ex-data)
                     (take-while some? (iterate ex-cause outcome)))]
    (is (:malli.instrument/original (meta @#'identity-int)) "the Var root is a wrapper")
    (is (instance? Throwable outcome) (str "an invalid input returned " (pr-str outcome)))
    (is (= :input (first checks)) (pr-str (ex-data outcome)))))

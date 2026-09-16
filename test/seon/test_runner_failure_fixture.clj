(ns ^{:seon.test/fixture
      "Deliberate failure evidence: `failing-example` asserts (= 5 (+ 2 2)) so
       runner tests have a genuinely red test to assert over. A gate that ran
       it would be permanently red."}
  seon.test-runner-failure-fixture
  "Selected explicitly by runner tests; excluded from the bare gate by its
  declared `:seon.test/fixture` reason."
  (:require [clojure.test :refer [deftest is]]))

(deftest passing-example
  (is (= 4 (+ 2 2))))

(deftest failing-example
  (is (= 5 (+ 2 2)) "the deliberate broken-test evidence"))

(deftest assertionless-example
  nil)

(defn- refusal
  [error-class signature]
  (doto (ex-info "one repeated refusal"
                 {:seon.error/kind error-class
                  :seon.error/signature signature})
    (.setStackTrace
     (into-array
      StackTraceElement
      (repeat 200
              (StackTraceElement. "seon.fixture.Owner"
                                  "refuse"
                                  "failure_fixture.clj"
                                  19))))))

(deftest repeated-identical-error
  (let [failure (refusal :seon.fixture/refused
                         (apply str (repeat 64 "a")))]
    (dotimes [_ 7]
      (clojure.test/do-report
       {:type :error
        :message "the same refusal reached the reporter again"
        :expected nil
        :actual failure}))))

(deftest distinct-error
  (throw (refusal :seon.fixture/different
                  (apply str (repeat 64 "b")))))

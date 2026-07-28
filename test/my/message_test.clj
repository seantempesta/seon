(ns my.message-test
  "The agent-facing message value: one function, and it is pure.

  Short by construction, exactly like the disposition suite: the whole
  contract is that the shape validates, that it is the ONLY function,
  that a bad argument comes back as a value an agent can read rather
  than a throw it cannot, and — the one thing this suite adds over
  `my.run`'s — that the error value is a REAL `:seon.error/value`, so
  the declared output schema is one the function actually keeps."
  (:require [clojure.test :refer [deftest is testing]]
            [my.message :as message]
            [my.run :as run]
            [seon.schema]))

(deftest a-message-is-an-ordinary-value
  (testing "send carries the recipient and the content, and nothing else"
    (let [value (message/send "bob" "how many primes under 100?")]
      (is (seon.schema/valid-candidate-value? :my.message/message value))
      (is (= "bob" (:my.message/to value)))
      (is (= "how many primes under 100?" (:my.message/content value)))
      (is (= #{:my.message/to :my.message/content} (set (keys value)))
          "no id, no timestamp, no sender — the driver owns all three")))
  (testing "one send and a vector of sends both validate as the union"
    (is (seon.schema/valid-candidate-value?
         :my.message/value (message/send "bob" "hello")))
    (is (seon.schema/valid-candidate-value?
         :my.message/value [(message/send "bob" "hello")
                            (message/send "carol" "hello")]))))

(deftest a-bad-argument-is-an-error-value-never-a-throw
  (doseq [bad [nil "" "   " "\n\t" 123 :bob {:a 1} ["bob"]]]
    (testing (str "recipient " (pr-str bad))
      (let [value (message/send bad "content")]
        (is (string? (:seon.error/message value)))
        (is (not (seon.schema/valid-candidate-value?
                  :my.message/value value))
            "and the loop cannot mistake it for a delivery")))
    (testing (str "content " (pr-str bad))
      (is (string? (:seon.error/message (message/send "bob" bad)))))))

(deftest the-error-value-is-the-registered-one
  ;; `:seon.error/value` REQUIRES a kind. A function whose declared
  ;; output is `[:or … :seon.error/value]` and which returns a bare
  ;; `{:seon.error/message …}` is outside its own contract — which is
  ;; where `my.run` is today (filed:
  ;; docs/seon/issues/my-run-error-values-omit-their-kind.md). This
  ;; assertion is what stops the same hole opening here.
  (doseq [value [(message/send "" "content") (message/send "bob" "")]]
    (is (seon.schema/valid-candidate-value? :seon.error/value value)
        "the error path keeps the output schema too"))
  (testing "and the reference case is still broken, so the issue is live"
    ;; deliberately asserting the DEFECT: when my.run is fixed this
    ;; fails, and the failure is the reminder to close the issue and
    ;; delete this testing block rather than a mystery.
    (is (not (seon.schema/valid-candidate-value?
              :seon.error/value (run/complete ""))))))

(deftest the-surface-is-exactly-one-function
  ;; countable, like the disposition ruling: fan-out is the vector, so
  ;; there is no send-many, and delivery is the driver's, so there is
  ;; no send!
  (is (= #{'send} (set (keys (ns-publics 'my.message))))))

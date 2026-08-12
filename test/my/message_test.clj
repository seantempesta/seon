(ns my.message-test
  "The agent-facing message value: one function, and it is pure.

  Short by construction, exactly like the disposition suite: the whole
  contract is that both shapes validate, that they are the ONLY
  functions, that a bad argument comes back as a value an agent can
  read rather than a throw it cannot, and — the one thing this suite
  adds over `my.run`'s — that the error value is a REAL
  `:seon.error/value`, so the declared output schemas are ones the
  functions actually keep."
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
  (testing "the optional third argument carries a fact identity"
    (let [value (message/send "bob" "repair this" "failure-17")]
      (is (seon.schema/valid-candidate-value? :my.message/message value))
      (is (= {:my.message/to "bob"
              :my.message/content "repair this"
              :my.message/about "failure-17"}
             value))))
  (testing "one send and a vector of sends both validate as the union"
    (is (seon.schema/valid-candidate-value?
         :my.message/value (message/send "bob" "hello")))
    (is (seon.schema/valid-candidate-value?
         :my.message/value [(message/send "bob" "hello")
                            (message/send "carol" "hello")]))))

(deftest a-declination-is-an-ordinary-value
  (let [value (message/decline "planner" "failure-17"
                               "The dependency contract is missing.")]
    (is (= {:my.message/to "planner"
            :my.message/about "failure-17"
            :my.message/reason "The dependency contract is missing."}
           value))
    (is (seon.schema/valid-candidate-value? :my.message/declination value))
    (is (seon.schema/valid-candidate-value? :my.message/value value))
    (is (seon.schema/valid-candidate-value?
         :my.message/value
         [(message/send "bob" "repair this" "failure-17") value])
        "one form may return messages and declinations together")))

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
  (doseq [bad [nil "" "   " "\n\t" 123 :failure {:id 1}]]
    (testing (str "about " (pr-str bad))
      (is (string? (:seon.error/message
                    (message/send "bob" "content" bad))))))
  (doseq [[label invoke expected-kind]
          [["recipient"
            #(message/decline % "failure-17" "Cannot repair.")
            :my.message/no-recipient]
           ["about"
            #(message/decline "planner" % "Cannot repair.")
            :my.message/no-about]
           ["reason"
            #(message/decline "planner" "failure-17" %)
            :my.message/no-reason]]
          bad [nil "" "   " "\n\t" 123 :failure {:id 1}]]
    (testing (str "declination " label " " (pr-str bad))
      (let [value (invoke bad)]
        (is (= expected-kind (:seon.error/kind value)))
        (is (= #{:seon.error/kind :seon.error/message}
               (set (keys value))))
        (is (seon.schema/valid-candidate-value? :seon.error/value value)))))

(deftest the-error-value-is-the-registered-one
  ;; `:seon.error/value` REQUIRES a kind. A function whose declared
  ;; output is `[:or … :seon.error/value]` and which returns a bare
  ;; `{:seon.error/message …}` is outside its own contract. This
  ;; assertion is what stops that hole opening here — and `my.run`'s
  ;; own error values now satisfy the same schema (the canary that
  ;; deliberately asserted its defect fired when 932ff55fb fixed it,
  ;; exactly as designed, and was deleted with the issue's archival).
  (doseq [value [(message/send "" "content") (message/send "bob" "")
                 (message/send "bob" "content" "")
                 (message/decline "" "failure-17" "Cannot repair.")
                 (message/decline "planner" "" "Cannot repair.")
                 (message/decline "planner" "failure-17" "")
                 (run/complete "")]]
    (is (seon.schema/valid-candidate-value? :seon.error/value value)
        "the error path keeps the output schema too")))

(deftest the-surface-is-exactly-two-functions
  ;; Countable, like the disposition ruling: fan-out is the vector, so
  ;; there is no send-many or decline-many, and delivery is the
  ;; driver's, so neither function has a `!`.
  (is (= #{'decline 'send} (set (keys (ns-publics 'my.message)))))
  (is (.contains ^String (:doc (meta (the-ns 'my.message)))
                 "inter-agent message protocol"))
  (is (.contains ^String (:doc (meta #'message/send))
                 "Use `send` when")))

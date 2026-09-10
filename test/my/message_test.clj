(ns my.message-test
  "The agent-facing message value: one function, and it is pure.

  Short by construction, exactly like the disposition suite: the whole
  contract is that both shapes validate, that they are the ONLY
  functions, that a bad argument comes back as a value an agent can
  read rather than a throw it cannot, and — the one thing this suite
  adds over `my.turn`'s — that the error value is a REAL
  `:seon.error/value`, so the declared output schemas are ones the
  functions actually keep."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.cluster.message :as message]
            [my.message]
            [seon.run :as run]
            [seon.db :as db]
            [seon.schema]
            [seon.test-support :as support]))

(defn- with-messages
  [f]
  (support/with-database
    (fn [connection]
      (db/transact! connection
                    [{:seon.agent/id "alice"}
                     {:seon.agent/id "bob"}])
      (let [before (db/basis-t @connection)]
        (db/transact! connection
                      [{:seon.message/id "m-1" :seon.message/to [:seon.agent/id "bob"] :seon.message/from [:seon.agent/id "alice"] :seon.message/content "First message" :seon.message/inbox [:seon.agent/id "bob"]}
                       {:seon.message/id "m-2" :seon.message/to [:seon.agent/id "bob"] :seon.message/content (apply str (repeat 200 "x")) :seon.message/inbox [:seon.agent/id "bob"]}])
        (f connection before)))))

(deftest ^{:seon.test/usage true} inbox-lists-this-agents-messages-newest-last
  (with-messages
    (fn [connection before]
      (is (= [{:my.message/id "m-1"
               :my.message/from "alice"
               :my.message/at (db/q '[:find ?at . :where [?m :seon.message/id "m-1"] [?m :seon.message/to _ ?tx] [?tx :db/txInstant ?at]] @connection)
               :my.message/content "First message"}
              {:my.message/id "m-2"
               :my.message/at (db/q '[:find ?at . :where [?m :seon.message/id "m-2"] [?m :seon.message/to _ ?tx] [?tx :db/txInstant ?at]] @connection)
               :my.message/content (apply str (repeat 200 "x"))}]
             (message/inbox @connection "bob")))
      (is (= ["m-1" "m-2"]
             (mapv :my.message/id
                   (message/inbox {:seon.db/since before}
                                  @connection "bob")))))))

(deftest ^{:seon.test/usage true} read-pulls-one-admitted-message-row
  (with-messages
    (fn [connection _before]
      (let [value (message/read "m-1" @connection)]
        (is (= {:seon.message/id "m-1" :seon.message/to [:seon.agent/id "bob"] :seon.message/from [:seon.agent/id "alice"] :seon.message/content "First message" :seon.message/inbox [:seon.agent/id "bob"]}
               value))
        (is (seon.schema/valid-candidate-value?
             :seon.message/message value))))))

(deftest a-message-is-an-ordinary-value
  (testing "send carries the recipient and the content, and nothing else"
    (let [value (message/send "bob" "how many primes under 100?")]
      (is (seon.schema/valid-candidate-value? :my.message/message value))
      (is (= "bob" (:my.message/to value)))
      (is (= "how many primes under 100?" (:my.message/content value)))
      (is (= #{:my.message/to :my.message/content :seon.message/id} (set (keys value)))
          "send mints the event identity; delivery resolves sender and recipient")))
  (testing "the optional third argument carries a fact identity"
    (let [value (message/send "bob" "repair this" "failure-17")]
      (is (seon.schema/valid-candidate-value? :my.message/message value))
      (is (= {:my.message/to "bob"
              :my.message/content "repair this"
              :my.message/about "failure-17"}
             (dissoc value :seon.message/id)))
      (is (= 8 (count (:seon.message/id value))))))
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
  ;; `:my.message/to`, `/content`, `/about` and `/reason` all admit any
  ;; non-empty string, so a BLANK one reaches the function and its own typed
  ;; refusal is the subject here. Everything the declared contract forbids —
  ;; the empty string and every wrong type — is asserted at the boundary an
  ;; agent actually calls, below.
  (doseq [bad ["   " "\n\t"]]
    (testing (str "recipient " (pr-str bad))
      (let [value (message/send bad "content")]
        (is (string? (:seon.error/message value)))
        (is (not (seon.schema/valid-candidate-value?
                  :my.message/value value))
            "and the loop cannot mistake it for a delivery")))
    (testing (str "content " (pr-str bad))
      (is (string? (:seon.error/message (message/send "bob" bad)))))
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
          bad ["   " "\n\t"]]
    (testing (str "declination " label " " (pr-str bad))
      (let [value (invoke bad)]
        ;; the class IS the marker's presence — no exact key census
        ;; (breaks on accretion), no kind dependence (deleted in W4/W5)
        (is (true? (get value expected-kind ::absent)))
        (is (string? (:seon.error/message value)))
        (is (seon.schema/valid-candidate-value? :seon.error/value value))))))

(deftest a-contract-forbidden-argument-is-a-value-the-agent-reads
  ;; AN AGENT NEVER INVOKES THE VAR. Its reply is read into forms and each
  ;; one crosses `seon.sci.eval/evaluate`, so the claim under test — a bad
  ;; argument comes back as something the agent can read, never a throw — is
  ;; a claim about THAT boundary. Under the contracts every cluster arms the
  ;; declared contract refuses these arguments before the function body runs,
  ;; and the kernel still hands the agent a flat value. A direct call here
  ;; would assert a branch the contract makes unreachable.
  (support/with-database
   (fn [connection]
     (let [ctx (support/fork-cluster-ctx connection)]
       (doseq [bad ["nil" "\"\"" "123" ":bob" "{:a 1}" "[\"bob\"]"]
               source [(str "(seon.cluster.message/send " bad " \"content\")")
                       (str "(seon.cluster.message/send \"bob\" " bad ")")
                       (str "(seon.cluster.message/send \"bob\" \"content\" " bad ")")
                       (str "(seon.cluster.message/decline " bad
                            " \"failure-17\" \"Cannot repair.\")")
                       (str "(seon.cluster.message/decline \"planner\" " bad
                            " \"Cannot repair.\")")
                       (str "(seon.cluster.message/decline \"planner\" \"failure-17\" "
                            bad ")")]]
         (let [value (support/agent-value ctx source)]
           (is (map? value) source)
           (is (keyword? (:seon.error/kind value)) source)
           (is (string? (:seon.error/message value)) source)
           (is (not (seon.schema/valid-candidate-value?
                     :my.message/value value))
               "and the loop cannot mistake it for a delivery")))))))

(deftest the-error-value-is-the-registered-one
  ;; `:seon.error/value` REQUIRES a kind. A function whose declared
  ;; output is `[:or … :seon.error/value]` and which returns a bare
  ;; `{:seon.error/message …}` is outside its own contract. This
  ;; assertion is what stops that hole opening here — and `my.turn`'s
  ;; own error values now satisfy the same schema (the canary that
  ;; deliberately asserted its defect fired when 932ff55fb fixed it,
  ;; exactly as designed, and was deleted with the issue's archival).
  (doseq [value [(message/send "   " "content") (message/send "bob" "   ")
                 (message/send "bob" "content" "   ")
                 (message/decline "   " "failure-17" "Cannot repair.")
                 (message/decline "planner" "   " "Cannot repair.")
                 (message/decline "planner" "failure-17" "   ")
                 (run/complete "   ")]]
    (is (seon.schema/valid-candidate-value? :seon.error/value value)
        "the error path keeps the output schema too")))

(deftest the-surface-is-exactly-four-functions
  ;; Countable, like the disposition ruling: fan-out is the vector, so
  ;; there is no send-many or decline-many, and delivery is the
  ;; driver's, so neither function has a `!`.
  (is (= #{'decline 'inbox 'read 'send}
         (set (keys (ns-publics 'my.message)))))
  (is (.contains ^String (:doc (meta (the-ns 'my.message)))
                 "inter-agent message protocol"))
  (is (.contains ^String (:doc (meta #'message/send))
                 "Use `send` when")))

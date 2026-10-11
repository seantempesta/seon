(ns my.message-test
  "Message transaction inputs and durable agent-facing writes.

  Short by construction, exactly like the disposition suite: the whole
  contract is that both shapes validate, that they are the ONLY
  functions, that a bad argument comes back as a value an agent can
  read rather than a throw it cannot, and — the one thing this suite
  adds over `my.turn`'s — that the error value is a REAL
  `:seon.error/value`, so the declared output schemas are ones the
  functions actually keep."
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [seon.cluster.agent :as cluster.agent]
            [seon.cluster.message :as message]
            [my.message]
            [seon.config :as config]
            [seon.run :as run]
            [seon.db :as db]
            [seon.schema]
            [seon.test-support :as support]))

(defn- with-messages
  [f]
  (support/with-database
    (fn [connection]
      (support/transacted! connection
                           (support/agents-tx @connection ["alice" "bob"]))
      (let [before (db/basis-t @connection)]
        (support/transacted! connection
                             [{:seon.message/id "m-1" :seon.message/to [:seon.agent/id "bob"] :seon.message/from [:seon.agent/id "alice"] :seon.message/content "First message"}
                              {:seon.message/id "m-2" :seon.message/to [:seon.agent/id "bob"] :seon.message/content (apply str (repeat 200 "x"))}])
        (f connection before)))))

(deftest ^{:seon.test/usage true} inbox-lists-this-agents-messages-newest-last
  (with-messages
    (fn [connection before]
      (is (= [{:my.message/id "m-1"
               :my.message/from "alice"
               :my.message/at (db/q '[:find ?at . :where [?m :seon.message/id "m-1"] [?m :seon.message/to _ ?tx] [?tx :db/txInstant ?at]] @connection)
               :my.message/content "First message"
               :seon.render/operations ['(my.message/received! "m-1")]}
              {:my.message/id "m-2"
               :my.message/at (db/q '[:find ?at . :where [?m :seon.message/id "m-2"] [?m :seon.message/to _ ?tx] [?tx :db/txInstant ?at]] @connection)
               :my.message/content (apply str (repeat 200 "x"))
               :seon.render/operations ['(my.message/received! "m-2")]}]
             (message/inbox @connection "bob")))
      (is (= ["m-1" "m-2"]
             (mapv :my.message/id
                   (message/inbox {:seon.db/since before}
                                  @connection "bob")))))))

(deftest ^{:seon.test/usage true} read-pulls-one-admitted-message-entity
  (with-messages
    (fn [connection _before]
      (let [value (message/read "m-1" @connection)]
        (is (= {:seon.message/id "m-1" :seon.message/to [:seon.agent/id "bob"] :seon.message/from [:seon.agent/id "alice"] :seon.message/content "First message"}
               value))
        (is ((seon.schema/projection-validator (seon.schema/handed-projection) :seon.message/message) value))))))

(deftest a-message-is-an-ordinary-value
  (testing "send carries the recipient and the content, and nothing else"
    (let [value (message/send "bob" "how many primes under 100?")]
      (is ((seon.schema/projection-validator (seon.schema/handed-projection) :my.message/message) value))
      (is (= "bob" (:my.message/to value)))
      (is (= "how many primes under 100?" (:my.message/content value)))
      (is (= #{:my.message/to :my.message/content :seon.message/id} (set (keys value)))
          "send mints the event identity; delivery resolves sender and recipient")))
  (testing "the optional third argument carries a fact identity"
    (let [value (message/send "bob" "repair this" "failure-17")]
      (is ((seon.schema/projection-validator (seon.schema/handed-projection) :my.message/message) value))
      (is (= {:my.message/to "bob"
              :my.message/content "repair this"
              :my.message/about "failure-17"}
             (dissoc value :seon.message/id)))
      (is (seq (:seon.message/id value)))
      (is (not= (:seon.message/id value)
                (:seon.message/id (message/send "bob" "repair this" "failure-17"))))))
  (testing "one send and a vector of sends both validate as the union"
    (is ((seon.schema/projection-validator (seon.schema/handed-projection) :my.message/value) (message/send "bob" "hello")))
    (is ((seon.schema/projection-validator (seon.schema/handed-projection) :my.message/value) [(message/send "bob" "hello")
                            (message/send "carol" "hello")]))))

(deftest a-declination-is-an-ordinary-value
  (let [value (message/decline "planner" "failure-17"
                               "The dependency contract is missing.")]
    (is (= {:my.message/to "planner"
            :my.message/assignment "failure-17"
            :my.message/reason "The dependency contract is missing."}
           (dissoc value :seon.message/id)))
    (is (seq (:seon.message/id value)))
    (is ((seon.schema/projection-validator (seon.schema/handed-projection) :my.message/declination) value))
    (is ((seon.schema/projection-validator (seon.schema/handed-projection) :my.message/value) value))
    (is ((seon.schema/projection-validator (seon.schema/handed-projection) :my.message/value) [(message/send "bob" "repair this" "failure-17") value])
        "one form may return messages and declinations together")))

(deftest delivery-preserves-the-returned-message-identity
  (with-messages
    (fn [connection _]
      (doseq [value [(message/send "bob" "The check passed.")
                     (message/decline "bob" "m-1" "The input is missing.")]]
        (let [delivery (message/delivery @connection
                                         {:my.message/value value
                                          :seon.agent/id "alice"
                                          :seon.turn/id "message-examples"
                                          :seon.cluster.eval/ordinal 0
                                          :seon.config.message/max-chain 10})
              written (db/transact! connection (:seon.message/rows delivery))]
          (is (empty? (:seon.error/values delivery)))
          (is (:db-after written))
          (is (= (:seon.message/id value)
                 (:seon.message/id
                  (db/pull (:db-after written) [:seon.message/id]
                           [:seon.message/id (:seon.message/id value)])))))))))

(deftest send-preserves-routing-until-turn-settlement
  (with-messages
    (fn [connection _]
      (let [inbox (async/chan 1)
            routing (atom {:seon.agent/started {"alice" {:seon.agent/inbox inbox}}})
            before (db/basis-t @connection)]
        (try
          (let [sent (my.message/send {:my.message/to "alice" :my.message/content "Verified."
                                       :my.message/about "m-1" :seon.agent/routing routing
                                       :seon.db/connection connection :seon.agent/id "bob"})
                offered (async/poll! inbox)]
            (is (true? (:seon.message/accepted sent)))
            (is (= (:seon.message/id sent) (:seon.message/id offered)))
            (is (= [:seon.agent/id "alice"] (:seon.message/to offered)))
            (is (= [:seon.agent/id "bob"] (:seon.message/from offered)))
            (is (= before (db/basis-t @connection))))
          (let [missing (my.message/send {:my.message/to "absent" :my.message/content "No recipient."
                                          :seon.agent/routing routing :seon.db/connection connection
                                          :seon.agent/id "bob"})]
            (is (= :seon.message/undeliverable (:seon.error/type missing)))
            (is (= "absent" (:seon.message/recipient missing)))
            (is (= before (db/basis-t @connection))))
          (finally (async/close! inbox)))))))

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
        (is (not ((seon.schema/projection-validator (seon.schema/handed-projection) :my.message/value) value))
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
           ["assignment"
            #(message/decline "planner" % "Cannot repair.")
            :my.message/no-assignment]
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
        (is ((seon.schema/projection-validator (seon.schema/handed-projection) :seon.error/value) value))))))

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
            (is (map? value))
            (is (= :seon.instrument/invocation
                   (:seon.error/layer value)))
            (is (= :input (:seon.instrument/check value)))
            (is (string? (:seon.error/message value)))
            (is (not ((seon.schema/projection-validator (seon.schema/handed-projection) :my.message/value) value))
                "and the loop cannot mistake it for a delivery")))))))

(deftest the-error-value-is-the-registered-one
  ;; Every returned refusal satisfies its declared declared-schema and base. A function
  ;; that returns a bare message map is outside its own contract. This
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
    (is ((seon.schema/projection-validator (seon.schema/handed-projection) :seon.error/value) value)
        "the error path keeps the output schema too")))

(deftest received!-acknowledges-once-and-leaves-the-unread-set
  ;; Unread = addressed to me without :seon.message/received-tx (design §9).
  ;; The operations query finds received! by the message's own identity key.
  (let [parent (support/execution-handle db/*conn*)
        connection (:seon.db/connection parent)
        _ (support/transacted! connection
                               (cluster.agent/creation-tx
                                {:seon.agent/id "ack-reader" :seon.ns/name 'my.agents.ack-reader
                                 :seon.cluster/name "default"}))
        _ (support/transacted! connection
                               [{:seon.message/id "ack-1" :seon.message/to [:seon.agent/id "ack-reader"]
                                 :seon.message/content "Acknowledge me."}])
        handle (cluster.agent/acquire-context! parent "ack-reader" {:seon.agent/isolate? true})
        evaluate #(let [result (support/evaluate
                                {:seon.sci.eval/ctx (:seon.sci.eval/ctx handle)
                                 :seon.db/db (db/db (:seon.db/connection handle))
                                 :seon.agent/id "ack-reader"
                                 :seon.cluster.eval/ns [:seon.ns/name 'user]
                                 :seon.cluster.eval/source %
                                 :seon.sci.eval/time-limit-ms 5000
                                 :seon.sci.admit/caps (config/result-caps config/defaults)})]
                    (or (:seon.cluster.eval/error result) (:seon.sci.admit/value result)))
        unread #(set (db/q '[:find [?id ...]
                             :where [?agent :seon.agent/id "ack-reader"]
                                    [?message :seon.message/to ?agent]
                                    [?message :seon.message/id ?id]
                                    (not [?message :seon.message/received-tx _])]
                           (db/db (:seon.db/connection handle))))]
    (try
      (is (contains? (set (map symbol (db/q '[:find [?sym ...]
                                              :where [?arity :seon.fn.arity/input-refs :seon.message/id]
                                                     [?function :seon.fn/arities ?arity]
                                                     [?function :seon.fn/sym ?sym]]
                                            @connection)))
                     'my.message/received!))
      (is (= #{"ack-1"} (unread)))
      (is (= ["ack-1"] (mapv :my.message/id (evaluate "(my.message/inbox)")))
          "the inbox lists the unacknowledged message")
      (let [acknowledged (evaluate "(my.message/received! \"ack-1\")")
            tx (get-in acknowledged [:seon.message/received-tx :db/id])]
        (is (int? tx))
        (is (= "ack-1" (:seon.message/id acknowledged)))
        (is (= #{} (unread)))
        (is (= [] (evaluate "(my.message/inbox)"))
            "an acknowledged message leaves the inbox")
        (is (= acknowledged (evaluate "(my.message/received! \"ack-1\")"))
            "acknowledging again is inert"))
      (is (= "absent" (:my.message/not-found (evaluate "(my.message/received! \"absent\")"))))
      (finally (cluster.agent/release-context! handle)))))

(deftest the-surface-is-exactly-four-functions
  ;; The established names remain; send and received! write immediately.
  (is (= #{'inbox 'read 'received! 'send}
         (set (keys (ns-publics 'my.message)))))
  (is (.contains ^String (:doc (meta (the-ns 'my.message)))
                 "inter-agent message protocol"))
  (is (.contains ^String (:doc (meta #'message/send))
                 "Use `my.message/send`")))

(ns seon.cluster.message-test
  "Delivery: the driver half of `my.message`, against real facts.

  Every state here is committed into a real in-memory database built
  the way boot builds one — `canonical-database-attributes`, never an
  explicit attribute list — because the class that hid longest in this
  system was a fixture installing attributes the live boot path never
  had.

  The suite's spine is the CONVERSATION BOUND, and it is driven as a
  simulation rather than sampled: alice and bob ping-pong until
  delivery refuses, and the property is that it refuses — a polite
  infinite conversation is the failure mode this rung introduces, and
  the one thing that must be proven dead."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.set :as set]
            [datahike.api :as d]
            [my.message :as my.message]
            [seon.cluster.loop :as cluster.loop]
            [seon.cluster.message :as message]
            [seon.cluster.wake :as wake]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike])
  (:import [java.util Date]))

(def ^:private now (Date. 1700000000000))
(def ^:private limit 16)

(defn- with-database
  [body]
  (let [configuration {:store {:backend :memory :id (random-uuid)}
                       :schema-flexibility :write}
        _ (d/create-database configuration)
        connection (d/connect configuration)]
    (try
      (d/transact connection
                  (schema.datahike/malli->datahike-schema
                   (schema/canonical-database-attributes)))
      (d/transact connection
                  [{:seon.cluster.agent/id "alice"}
                   {:seon.cluster.agent/id "bob"}])
      (body connection)
      (finally
        (d/release connection)
        (d/delete-database configuration)))))

(defn- ask!
  "Commit one message from OUTSIDE the agent population — a human's.
  No `from`, and no triggering transaction: the head of a chain."
  [connection id to content]
  (d/transact connection
              [{:seon.cluster.message/id id
                :seon.cluster.message/to [:seon.cluster.agent/id to]
                :seon.cluster.message/content content
                :seon.cluster.message/at now}])
  id)

(defn- deliver!
  "Commit a delivery the way the loop commits one: the rows, and the
  trigger as transaction metadata. Returns the delivery."
  [connection {:keys [sender trigger run ordinal value chain]
               :or {ordinal 0 chain limit}}]
  (let [delivery (message/delivery
                  @connection
                  (cond-> {:my.message/value value
                           :seon.cluster.agent/id sender
                           :seon.cluster.run/id run
                           :seon.cluster.run.form/ordinal ordinal
                           :seon.cluster.message/at now
                           :seon.config.message/max-chain chain}
                    trigger (assoc :seon.cluster.message/trigger trigger)))
        rows (:seon.cluster.message/rows delivery)]
    (when (seq rows)
      (d/transact connection
                  (cond-> {:tx-data rows}
                    trigger
                    (assoc :tx-meta
                           {:seon.db/trigger
                            [:seon.cluster.message/id trigger]}))))
    delivery))

;;; ---------------------------------------------------------------------------
;;; Delivery IS the wake — asserted from the direction that can break
;;; ---------------------------------------------------------------------------

(deftest a-delivered-message-wakes-the-recipient-by-construction
  ;; The loop's C2 property says routine bookkeeping must NOT intersect
  ;; the wake set. This is its necessary complement: what a delivery
  ;; writes MUST intersect it, or messaging would commit facts that
  ;; wake nobody and every agent would sit on an unread message until
  ;; something unrelated happened to wake it. Both directions computed,
  ;; neither a reviewed list.
  (with-database
    (fn [connection]
      (ask! connection "m-0" "alice" "hello")
      (let [rows (:seon.cluster.message/rows
                  (deliver! connection
                            {:sender "alice" :trigger "m-0" :run "r-1"
                             :value (my.message/send "bob" "hello bob")}))
            written (into #{} (mapcat keys) rows)]
        (is (seq (set/intersection written (wake/wake-attributes)))
            "a delivery writes a wake attribute — that IS the transport")
        (is (empty? (set/intersection written
                                      (cluster.loop/committed-attributes)))
            "and it shares nothing with the loop's routine bookkeeping,
             so an ordinary turn still cannot wake itself")))))

;;; ---------------------------------------------------------------------------
;;; The rows
;;; ---------------------------------------------------------------------------

(deftest a-delivery-records-who-sent-it
  (with-database
    (fn [connection]
      (ask! connection "m-0" "alice" "ask bob")
      (deliver! connection {:sender "alice" :trigger "m-0" :run "r-1"
                            :value (my.message/send "bob" "how many?")})
      (let [pulled (d/q '[:find (pull ?message [*]) .
                          :where
                          [?message :seon.cluster.message/content "how many?"]]
                        @connection)]
        (is (= "alice"
               (d/q '[:find ?id .
                      :in $ ?eid
                      :where [?eid :seon.cluster.agent/id ?id]]
                    @connection
                    (:db/id (:seon.cluster.message/from pulled))))
            "from resolves to the sending agent")
        (is (some? (:seon.cluster.message/to pulled)))
        (is (= now (:seon.cluster.message/at pulled)))))))

(deftest a-message-from-outside-has-no-sender
  (with-database
    (fn [connection]
      (ask! connection "m-0" "alice" "hello")
      (is (nil? (d/q '[:find ?from .
                       :where
                       [?message :seon.cluster.message/id "m-0"]
                       [?message :seon.cluster.message/from ?from]]
                     @connection))
          "absence is the state, and it is what makes a human nudge
           structurally distinguishable from an agent's reply"))))

(deftest a-vector-fans-out-and-ids-are-derived
  (with-database
    (fn [connection]
      (d/transact connection [{:seon.cluster.agent/id "carol"}])
      (ask! connection "m-0" "alice" "ask both")
      (let [delivery (deliver! connection
                               {:sender "alice" :trigger "m-0" :run "r-1"
                                :ordinal 2
                                :value [(my.message/send "bob" "one")
                                        (my.message/send "carol" "two")]})
            rows (:seon.cluster.message/rows delivery)]
        (is (= 2 (count rows)))
        (is (= ["r-1-2-message-0" "r-1-2-message-1"]
               (mapv :seon.cluster.message/id rows))
            "identity is a function of (run, ordinal, index) — nothing
             allocates a uuid and a re-delivery would upsert")))))

;;; ---------------------------------------------------------------------------
;;; The refusals, each a fact
;;; ---------------------------------------------------------------------------

(deftest a-stranger-costs-its-own-message-and-nothing-else
  (with-database
    (fn [connection]
      (ask! connection "m-0" "alice" "ask both")
      (let [delivery (message/delivery
                      @connection
                      {:my.message/value [(my.message/send "nobody" "hi")
                                          (my.message/send "bob" "hi")]
                       :seon.cluster.agent/id "alice"
                       :seon.cluster.run/id "r-1"
                       :seon.cluster.run.form/ordinal 0
                       :seon.cluster.message/at now
                       :seon.cluster.message/trigger "m-0"
                       :seon.config.message/max-chain limit})]
        (is (= 1 (count (:seon.cluster.message/rows delivery)))
            "the deliverable one is delivered")
        (is (= [:seon.cluster.message/unknown-recipient]
               (mapv :seon.error/kind (:seon.error/values delivery))))
        (is (every? #(schema/valid-candidate-value? :seon.error/value %)
                    (:seon.error/values delivery))
            "a refusal is the ONE registered error shape, so the
             recorder can commit it with no translation")))))

(deftest an-absent-bound-delivers-nothing
  ;; fail CLOSED: a messaging path with no bound is exactly the runaway
  ;; the bound exists for, and `(> 1 nil)` would throw into the loop
  (with-database
    (fn [connection]
      (let [delivery (message/delivery
                      @connection
                      {:my.message/value (my.message/send "bob" "hi")
                       :seon.cluster.agent/id "alice"
                       :seon.cluster.run/id "r-1"
                       :seon.cluster.run.form/ordinal 0
                       :seon.cluster.message/at now
                       :seon.config.message/max-chain nil})]
        (is (empty? (:seon.cluster.message/rows delivery)))
        (is (= [:seon.cluster.message/no-limit]
               (mapv :seon.error/kind (:seon.error/values delivery))))))))

;;; ---------------------------------------------------------------------------
;;; The chain — derived, and the human barrier that comes free with it
;;; ---------------------------------------------------------------------------

(deftest a-message-from-outside-is-depth-zero
  (with-database
    (fn [connection]
      (ask! connection "m-0" "alice" "hello")
      (is (zero? (message/chain-depth @connection "m-0"))))))

(deftest each-answering-hop-is-one-more
  (with-database
    (fn [connection]
      (ask! connection "m-0" "alice" "ask bob")
      (deliver! connection {:sender "alice" :trigger "m-0" :run "r-1"
                            :value (my.message/send "bob" "how many?")})
      (is (= 1 (message/chain-depth @connection "r-1-0-message-0")))
      (deliver! connection {:sender "bob" :trigger "r-1-0-message-0"
                            :run "r-2"
                            :value (my.message/send "alice" "25")})
      (is (= 2 (message/chain-depth @connection "r-2-0-message-0"))))))

(deftest a-fresh-human-message-starts-a-new-chain
  ;; THE BARRIER IS FREE. The quarry needed an explicit rule — count
  ;; only inbound messages newer than the latest human message — and
  ;; got it wrong once (a global count deadlocked routine delegation).
  ;; Here a human message simply has no triggering transaction to walk
  ;; back through, so the walk stops.
  (with-database
    (fn [connection]
      (ask! connection "m-0" "alice" "round one")
      (deliver! connection {:sender "alice" :trigger "m-0" :run "r-1"
                            :value (my.message/send "bob" "one")})
      (deliver! connection {:sender "bob" :trigger "r-1-0-message-0"
                            :run "r-2"
                            :value (my.message/send "alice" "two")})
      (is (= 2 (message/chain-depth @connection "r-2-0-message-0")))
      (ask! connection "m-1" "alice" "round two")
      (is (zero? (message/chain-depth @connection "m-1"))
          "a human's nudge resets the conversation by construction"))))

(deftest a-run-with-no-recorded-trigger-starts-at-one
  (with-database
    (fn [connection]
      (let [delivery (message/delivery
                      @connection
                      {:my.message/value (my.message/send "bob" "hi")
                       :seon.cluster.agent/id "alice"
                       :seon.cluster.run/id "r-1"
                       :seon.cluster.run.form/ordinal 0
                       :seon.cluster.message/at now
                       :seon.config.message/max-chain 1})]
        (is (= 1 (count (:seon.cluster.message/rows delivery)))
            "one hop is allowed at a limit of one")))))

(deftest the-trigger-of-a-run-is-read-from-its-own-transaction
  (with-database
    (fn [connection]
      (ask! connection "m-0" "alice" "hello")
      (d/transact connection
                  {:tx-data [{:seon.cluster.run/id "r-1"
                              :seon.cluster.run/agent
                              [:seon.cluster.agent/id "alice"]
                              :seon.cluster.run/opened-at now}]
                   :tx-meta {:seon.db/trigger
                             [:seon.cluster.message/id "m-0"]}})
      (is (= "m-0" (message/trigger @connection "r-1"))
          "nothing is stored on the run for this — the night ruling")
      (is (nil? (message/trigger @connection "no-such-run"))))))

;;; ---------------------------------------------------------------------------
;;; THE CLASS-KILLER: the polite infinite conversation terminates
;;; ---------------------------------------------------------------------------

(deftest a-two-agent-ping-pong-cannot-run-forever
  (with-database
    (fn [connection]
      (ask! connection "m-0" "alice" "start talking to bob")
      (let [outcome
            (loop [hop 0
                   sender "alice"
                   recipient "bob"
                   trigger "m-0"
                   delivered 0]
              (if (> hop 200)
                ;; the failure this test exists to make impossible: if
                ;; the walk ever stopped counting, this loop would spin
                ;; to its own arbitrary stop and report it
                {:outcome :never-refused :delivered delivered}
                (let [run (str "r-" hop)
                      delivery (deliver!
                                connection
                                {:sender sender :trigger trigger :run run
                                 :value (my.message/send
                                         recipient
                                         (str "hop " hop
                                              " — and how about this?"))})
                      rows (:seon.cluster.message/rows delivery)]
                  (if (seq rows)
                    (recur (inc hop) recipient sender
                           (:seon.cluster.message/id (first rows))
                           (inc delivered))
                    {:outcome :refused
                     :delivered delivered
                     :kinds (mapv :seon.error/kind
                                  (:seon.error/values delivery))}))))]
        (is (= :refused (:outcome outcome))
            "an unattended agent-to-agent conversation stops itself")
        (is (= limit (:delivered outcome))
            "and it stops at exactly the configured number of hops")
        (is (= [:seon.cluster.message/chain-limit] (:kinds outcome))))))

  (testing "and a human message in the middle buys a full budget again"
    (with-database
      (fn [connection]
        (ask! connection "m-0" "alice" "start")
        (let [run-to-refusal
              (fn [head]
                (loop [hop 0
                       sender "alice"
                       recipient "bob"
                       trigger head
                       delivered 0]
                  (let [delivery (deliver!
                                  connection
                                  {:sender sender :trigger trigger
                                   :run (str head "-r-" hop)
                                   :value (my.message/send recipient "…")})
                        rows (:seon.cluster.message/rows delivery)]
                    (if (and (seq rows) (< hop 200))
                      (recur (inc hop) recipient sender
                             (:seon.cluster.message/id (first rows))
                             (inc delivered))
                      delivered))))]
          (is (= limit (run-to-refusal "m-0")))
          (ask! connection "m-1" "alice" "carry on")
          (is (= limit (run-to-refusal "m-1"))
              "the budget is per-conversation, not per-cluster and not
               per-lifetime"))))))

;;; ---------------------------------------------------------------------------
;;; The completion reply — and the bounce it must not become
;;; ---------------------------------------------------------------------------

(deftest a-completed-run-answers-the-agent-that-asked
  (with-database
    (fn [connection]
      (ask! connection "m-0" "alice" "ask bob")
      (deliver! connection {:sender "alice" :trigger "m-0" :run "r-1"
                            :value (my.message/send "bob" "how many?")})
      (is (= {:my.message/to "alice" :my.message/content "25"}
             (message/reply @connection
                            {:my.run/result "25"
                             :seon.cluster.agent/id "bob"
                             :seon.cluster.message/trigger
                             "r-1-0-message-0"}))
          "bob completing a run alice triggered owes alice the answer —
           derived from the trigger, never remembered by the agent"))))

(deftest a-completed-run-answering-a-human-replies-to-nobody
  (with-database
    (fn [connection]
      (ask! connection "m-0" "alice" "how many?")
      (is (nil? (message/reply @connection
                               {:my.run/result "25"
                                :seon.cluster.agent/id "alice"
                                :seon.cluster.message/trigger "m-0"}))
          "delivery to a human is a surface, not a message to an agent
           that does not exist"))))

(deftest a-reply-is-not-a-question-so-completing-does-not-bounce
  ;; THE SECOND LIVE DRIVE'S CORRECTION. alice delegated, bob answered,
  ;; and alice's completion — the sentence meant for the human — was
  ;; delivered straight back to bob, who opened a run to consider it.
  ;; Only the chain limit would have stopped the bounce. The trigger is
  ;; an answer to us exactly when the message that CAUSED it was ours,
  ;; and that is the walk the depth bound already does.
  (with-database
    (fn [connection]
      (ask! connection "m-0" "alice" "ask bob")
      (deliver! connection {:sender "alice" :trigger "m-0" :run "r-1"
                            :value (my.message/send "bob" "how many?")})
      (deliver! connection {:sender "bob" :trigger "r-1-0-message-0"
                            :run "r-2"
                            :value (my.message/send "alice" "25")})
      (is (nil? (message/reply @connection
                               {:my.run/result "There are 25."
                                :seon.cluster.agent/id "alice"
                                :seon.cluster.message/trigger
                                "r-2-0-message-0"}))
          "alice completing on bob's ANSWER owes bob nothing — the
           delegation ends when the delegator completes, which is what
           puts the chain bound back to being a backstop")))

  (testing "but a genuine second question from the same peer is answered"
    (with-database
      (fn [connection]
        (ask! connection "m-0" "bob" "ask alice something")
        (deliver! connection {:sender "bob" :trigger "m-0" :run "r-1"
                              :value (my.message/send "alice" "how many?")})
        (is (= "bob" (:my.message/to
                      (message/reply @connection
                                     {:my.run/result "25"
                                      :seon.cluster.agent/id "alice"
                                      :seon.cluster.message/trigger
                                      "r-1-0-message-0"})))
            "bob's message was caused by the HUMAN's, not by alice's")))))

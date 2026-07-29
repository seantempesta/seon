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
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [clojure.set :as set]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datahike.api :as d]
            [my.message :as my.message]
            [seon.cluster.loop :as cluster.loop]
            [seon.cluster.message :as message]
            [seon.cluster.wake :as wake]
            [seon.schema :as schema]
            [seon.test-support :as test-support])
  (:import [java.util Date]))

(def ^:private now (Date. 1700000000000))
(def ^:private limit 16)

(defn- inbound-request
  ([id content]
   (inbound-request id content now))
  ([id content at]
   {:seon.cluster.agent/id id
    :seon.cluster.message/inbound-content content
    :seon.cluster.message/at at
    :seon.config.eval.result/max-string 1024}))

(defn- commit-inbound!
  [connection request]
  (d/transact connection
              [[:db.fn/call #'message/inbound-tx request]]))

(defn- with-database
  [body]
  (test-support/with-database
    (fn [connection]
      (d/transact connection
                  [{:seon.cluster.agent/id "alice"}
                   {:seon.cluster.agent/id "bob"}])
      (body connection))))

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

(def ^:private agent-ids ["alice" "bob" "carol" "dana"])

(def ^:private message-command-generator
  (gen/frequency
   [[1 (gen/let [recipient gen/nat]
         {::command :human ::recipient recipient})]
    [4 (gen/let [sender gen/nat
                 recipients (gen/vector gen/nat 1 4)
                 ordinal (gen/choose 0 3)
                 trigger? gen/boolean
                 trigger-index gen/nat]
         {::command :send
          ::sender sender
          ::recipients recipients
          ::ordinal ordinal
          ::trigger? trigger?
          ::trigger-index trigger-index})]]))

(def ^:private message-scenario-generator
  (gen/let [population-size (gen/choose 2 4)
            chain-limit (gen/one-of [(gen/return nil) (gen/choose 1 5)])
            commands (gen/vector message-command-generator 1 14)]
    {::population-size population-size
     ::chain-limit chain-limit
     ::commands commands}))

(defn- model-depth
  [messages message-id]
  (loop [id message-id depth 0 seen #{}]
    (if-let [parent (when-not (contains? seen id)
                      (::parent (get messages id)))]
      (recur parent (inc depth) (conj seen id))
      depth)))

(defn- command-data
  [{::keys [population chain-limit message-order]} command command-index]
  (let [population-size (count population)]
    (case (::command command)
      :human
      {::message-id (str "human-" command-index)
       ::to (nth population (mod (::recipient command) population-size))
       ::content (str "human content " command-index)}

      :send
      (let [trigger (when (and (::trigger? command) (seq message-order))
                      (nth message-order
                           (mod (::trigger-index command)
                                (count message-order))))
            sender (nth population
                        (mod (::sender command) population-size))
            run-id (str "generated-run-" command-index)
            recipients
            (mapv (fn [candidate-index raw-index]
                    (let [known? (< (mod raw-index (inc population-size))
                                    population-size)]
                      {::candidate-index candidate-index
                       ::to (if known?
                              (nth population
                                   (mod raw-index population-size))
                              (str "unknown-" command-index "-"
                                   candidate-index))
                       ::known? known?
                       ::content (str "content-" command-index "-"
                                     candidate-index)}))
                  (range)
                  (::recipients command))]
        {::sender sender
         ::trigger trigger
         ::run-id run-id
         ::ordinal (::ordinal command)
         ::chain-limit chain-limit
         ::recipients recipients}))))

(defn- model-command
  [{::keys [messages] :as model} command command-index]
  (let [{::keys [message-id to content sender trigger run-id ordinal
                 chain-limit recipients] :as data}
        (command-data model command command-index)]
    (if (= :human (::command command))
      [(-> model
           (assoc-in [::messages message-id]
                     {::id message-id ::to to ::content content
                      ::depth 0})
           (update ::message-order conj message-id))
       {::error-kinds []}]
      (let [depth (if trigger (inc (model-depth messages trigger)) 1)
            refused-kind (cond
                           (not (pos-int? chain-limit))
                           :seon.cluster.message/no-limit
                           (> depth chain-limit)
                           :seon.cluster.message/chain-limit)
            deliverable (if refused-kind
                          []
                          (filterv ::known? recipients))
            rows (mapv (fn [{::keys [candidate-index to content]}]
                         (let [id (str run-id "-" ordinal "-message-"
                                       candidate-index)]
                           (cond-> {::id id ::to to ::from sender
                                    ::content content
                                    ::depth (if trigger depth 0)}
                             trigger (assoc ::parent trigger))))
                       deliverable)
            unknown-count (if refused-kind
                            0
                            (count (remove ::known? recipients)))
            error-kinds (if refused-kind
                          [refused-kind]
                          (vec (repeat unknown-count
                                       :seon.cluster.message/unknown-recipient)))
            next-model (reduce
                        (fn [current row]
                          (-> current
                              (assoc-in [::messages (::id row)] row)
                              (update ::message-order conj (::id row))))
                        model
                        rows)]
        [next-model
         {::data data
          ::rows rows
          ::error-kinds error-kinds}]))))

(defn- actual-messages
  [db]
  (into {}
        (map
         (fn [entity]
           (let [id (:seon.cluster.message/id entity)
                 parent
                 (d/q '[:find ?parent-id .
                        :in $ ?id
                        :where
                        [?message :seon.cluster.message/id ?id ?tx]
                        [?tx :seon.db/trigger ?parent]
                        [?parent :seon.cluster.message/id ?parent-id]]
                      db id)]
             [id
              (cond-> {::id id
                       ::to (d/q '[:find ?agent-id .
                                   :in $ ?to
                                   :where [?to :seon.cluster.agent/id
                                           ?agent-id]]
                                 db
                                 (:db/id (:seon.cluster.message/to entity)))
                       ::content (:seon.cluster.message/content entity)
                       ::depth (message/chain-depth db id)}
                (:seon.cluster.message/from entity)
                (assoc ::from (message/sender db id))
                parent (assoc ::parent parent))]))
         (d/q '[:find [(pull ?message [*]) ...]
                :where [?message :seon.cluster.message/id _]]
              db))))

(defn- execute-command!
  [connection model command command-index]
  (let [[next-model expected] (model-command model command command-index)]
    (if (= :human (::command command))
      (let [{::keys [message-id to content]}
            (command-data model command command-index)]
        (ask! connection message-id to content)
        [next-model
         (= (::messages next-model) (actual-messages @connection))])
      (let [{::keys [sender trigger run-id ordinal chain-limit recipients]}
            (::data expected)
            value (mapv (fn [{::keys [to content]}]
                          (my.message/send to content))
                        recipients)
            request (cond-> {:my.message/value value
                             :seon.cluster.agent/id sender
                             :seon.cluster.run/id run-id
                             :seon.cluster.run.form/ordinal ordinal
                             :seon.cluster.message/at now
                             :seon.config.message/max-chain chain-limit}
                      trigger
                      (assoc :seon.cluster.message/trigger trigger))
            delivery (message/delivery @connection request)
            rows (:seon.cluster.message/rows delivery)]
        (when (seq rows)
          (d/transact connection
                      (cond-> {:tx-data rows}
                        trigger
                        (assoc :tx-meta
                               {:seon.db/trigger
                                [:seon.cluster.message/id trigger]}))))
        [next-model
         (and
          (or (nil? chain-limit)
              (schema/valid-candidate-value?
               :seon.cluster.message/delivery-request request))
          (= (mapv ::id (::rows expected))
             (mapv :seon.cluster.message/id rows))
          (= (::error-kinds expected)
             (mapv :seon.error/kind (:seon.error/values delivery)))
          (every? #(schema/valid-candidate-value? :seon.error/value %)
                  (:seon.error/values delivery))
          (= (::messages next-model) (actual-messages @connection))
          (or (nil? chain-limit)
              (every? #(<= (::depth %) chain-limit)
                      (vals (::messages next-model)))))]))))

(defn- generated-history-agrees-with-database?
  [{::keys [population-size chain-limit commands] :as scenario}]
  (let [population (subvec agent-ids 0 population-size)
        database-id
        (java.util.UUID/nameUUIDFromBytes
         (.getBytes (pr-str scenario) java.nio.charset.StandardCharsets/UTF_8))]
    (test-support/with-database
      {:seon.test-support/database-id database-id}
      (fn [connection]
        (d/transact connection
                    (mapv (fn [id] {:seon.cluster.agent/id id})
                          population))
        (second
         (reduce
          (fn [[model valid?] [command-index command]]
            (let [[next-model step-valid?]
                  (execute-command! connection model command command-index)]
              [next-model (and valid? step-valid?)]))
          [{::population population
            ::chain-limit chain-limit
            ::messages {}
            ::message-order []}
           true]
          (map-indexed vector commands)))))))

(deftest generated-message-histories-preserve-identity-fanout-and-depth
  (test-support/assert-check!
   (tc/quick-check
    60
    (prop/for-all [scenario message-scenario-generator]
      (generated-history-agrees-with-database? scenario))
    :seed 202607280501)
   "Generated message history diverged from durable facts."))

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

;;; ---------------------------------------------------------------------------
;;; The refusals, each a fact
;;; ---------------------------------------------------------------------------

;;; ---------------------------------------------------------------------------
;;; The chain — derived, and the human barrier that comes free with it
;;; ---------------------------------------------------------------------------

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

;;; ---------------------------------------------------------------------------
;;; Slice 1 — the outside message is ordinary transaction data
;;; ---------------------------------------------------------------------------

(deftest inbound-tx-omits-from-test
  (with-database
    (fn [connection]
      (commit-inbound! connection (inbound-request "bob" "hello bob"))
      (let [row (d/q '[:find (pull ?message [*]) .
                       :where
                       [?message :seon.cluster.message/content "hello bob"]]
                     @connection)]
        (is (string? (:seon.cluster.message/id row)))
        (is (= "bob"
               (d/q '[:find ?agent-id .
                      :in $ ?message-id
                      :where
                      [?message :seon.cluster.message/id ?message-id]
                      [?message :seon.cluster.message/to ?agent]
                      [?agent :seon.cluster.agent/id ?agent-id]]
                    @connection
                    (:seon.cluster.message/id row))))
        (is (= now (:seon.cluster.message/at row)))
        (is (not (contains? row :seon.cluster.message/from))
            "absence, not a human/origin stamp, is the outside contract")))))

(deftest inbound-identity-is-unique-under-burst-test
  ;; seed 2026072901 — the pre-handler probe, retained as a recurring gate.
  (with-database
    (fn [connection]
      (doseq [index (range 64)]
        (commit-inbound!
         connection
         (inbound-request "bob" (str "burst-" index))))
      (let [ids (d/q '[:find [?id ...]
                       :where [?message :seon.cluster.message/id ?id]]
                     @connection)
            inbound-ids (filter #(clojure.string/starts-with?
                                  % "inbound-")
                                ids)]
        (is (= 64 (count inbound-ids)))
        (is (= 64 (count (distinct inbound-ids)))
            "every accepted writer basis yields a distinct identity")))))

(deftest inbound-wakes-the-named-agent-test
  ;; seed 2026072902 — no delivery step between commit and route!.
  (with-database
    (fn [connection]
      (let [alice-eid (d/q '[:find ?agent .
                             :where [?agent :seon.cluster.agent/id "alice"]]
                           @connection)
            bob-eid (d/q '[:find ?agent .
                           :where [?agent :seon.cluster.agent/id "bob"]]
                         @connection)
            alice (async/chan (async/sliding-buffer 1))
            bob (async/chan (async/sliding-buffer 1))
            armer (async/chan (async/sliding-buffer 1))
            render (async/chan (async/sliding-buffer 1))
            faults (async/chan (async/sliding-buffer 1))
            key (wake/route!
                 {:seon.cluster.wake/connection connection
                  :seon.cluster.wake/channels
                  (fn [] {alice-eid alice bob-eid bob})
                  :seon.cluster.wake/fenced? (fn [_ _] false)
                  :seon.cluster.wake/armer-channel armer
                  :seon.cluster.wake/render-channel render
                  :seon.cluster.wake/fault-channel faults
                  :seon.cluster.wake/key ::inbound-route})]
        (try
          (commit-inbound! connection (inbound-request "bob" "wake bob"))
          (is (some? (test-support/await-event! bob "bob inbound wake")))
          (is (nil? (async/poll! alice))
              "the other agent receives no mailbox wake")
          (is (nil? (async/poll! faults)))
          (finally
            (wake/unlisten! {:seon.cluster.wake/connection connection
                             :seon.cluster.wake/key key})))))))

(deftest blank-and-unknown-are-values-not-throws-test
  (with-database
    (fn [connection]
      (let [requests [(inbound-request "bob" " ")
                      (assoc (inbound-request "bob" "too large")
                             :seon.config.eval.result/max-string 3)
                      (inbound-request "nobody" "hello")]
            results (mapv #(message/inbound-tx @connection %) requests)]
        (is (= [::message/blank-content
                ::message/content-too-large
                ::message/unknown-recipient]
               (mapv :seon.error/kind results)))
        (is (every? #(schema/valid-candidate-value?
                      :seon.error/value %)
                    results))
        (is (not-any? vector? results)
            "every refusal is a flat value and produces no rows")))))

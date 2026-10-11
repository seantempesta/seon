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
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.set :as set]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [seon.db :as db]
            [seon.id :as id]
            [seon.turn :as turn]
            [seon.cluster.message :as message]

            [seon.cluster.wake :as wake]
            [seon.config :as config]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.test-support :as test-support])
  (:import [java.util Date]))

(def ^:private now (Date. 1700000000000))
(def ^:private limit 16)

(defn- inbound-request
  ([id content]
   (inbound-request id content now))
  ([id content at]
   {:seon.agent/id id :seon.message/inbound-content content :seon.config.eval.result/max-string 1024}))

(defn- commit-inbound!
  [connection request]
  (test-support/transacted! connection
                          [[:db.fn/call #'message/inbound-tx request]]))

(defn- with-database
  [body]
  (test-support/with-database
    (fn [connection]
      (test-support/transacted! connection
                              (test-support/agents-tx @connection ["alice" "bob"]))
      (body connection))))

(deftest message-render-resolves-supported-agent-ref-shapes
  (with-database
    (fn [connection]
      (let [database @connection
            alice-eid (:db/id (db/pull database '[:db/id]
                                       [:seon.agent/id "alice"]))]
        (is (= "Agent alice said to bob: hello"
               (message/format-ai
                {:seon.db/db database
                 :seon.message/content "hello"
                 :seon.message/from
                 [:seon.agent/id "alice"]
                 :seon.message/to
                 {:seon.agent/id "bob"}})))
        (is (= "Agent alice said to bob: hello"
               (message/format-ai
                {:seon.db/db database
                 :seon.message/content "hello"
                 :seon.message/from {:db/id alice-eid}
                 :seon.message/to
                 [:seon.agent/id "bob"]})))
        (is (= "An unresolved sender [:seon.agent/id \"nobody\"] said to bob: hello"
               (message/format-ai
                {:seon.db/db database
                 :seon.message/content "hello"
                 :seon.message/from
                 [:seon.agent/id "nobody"]
                 :seon.message/to
                 [:seon.agent/id "bob"]})))
        (is (= "From outside this cluster to bob: hello"
               (message/format-ai
                {:seon.db/db database
                 :seon.message/content "hello"
                 :seon.message/to
                 [:seon.agent/id "bob"]})))))))

(deftest message-ai-is-data-from-the-viewing-agent-side
  ;; Design §9/§5c.4: the recipient is offered the acknowledgement as data,
  ;; the sender sees whether it was received. The render reads the value it
  ;; was handed, so a reply renders the message its write returned even when
  ;; the unit's database predates that write.
  (with-database
    (fn [connection]
      (let [before @connection
            message {:seon.message/id "message-1" :seon.message/from [:seon.agent/id "alice"]
                     :seon.message/to [:seon.agent/id "bob"] :seon.message/content "hello"}
            view message/render-ai]
        (is (= {:seon.message/id "message-1" :seon.message/to "bob" :seon.message/lines ["hello"]
                :seon.message/received? false}
               (view (assoc message :seon.db/db before :seon.agent/id "alice")))
            "the sender, before the write reached its database")
        (test-support/transacted! connection [message])
        (is (= {:seon.message/id "message-1" :seon.message/from "alice"
                :seon.message/lines ["hello"]
                :seon.render/operations ['(my.message/received! "message-1")]}
               (dissoc (view (assoc message :seon.db/db @connection :seon.agent/id "bob"))
                       :seon.message/at))
            "the recipient is offered the acknowledgement")
        (let [acknowledged (message/received! "message-1" connection "bob")
              tx (get-in acknowledged [:seon.message/received-tx :db/id])]
          (is (int? tx))
          (is (= tx (:seon.message/received-tx
                     (view (assoc acknowledged :seon.db/db @connection :seon.agent/id "bob"))))
              "the acknowledgement's reply shows its transaction")
          (is (true? (:seon.message/received?
                      (view (assoc message :seon.db/db @connection :seon.agent/id "alice"))))
              "the sender sees it received")
          (is (= acknowledged (message/received! "message-1" connection "bob"))
              "acknowledging again is inert"))))))

(deftest inbox-has-one-request-map-arity-and-supplied-defaults
  (with-database
    (fn [connection]
      (config/apply! {:seon.db/connection connection
                     :seon.boot/cluster-name "message-test"})
      (test-support/transacted!
                   connection
                   [{:seon.message/id "message-older" :seon.message/from [:seon.agent/id "alice"] :seon.message/to [:seon.agent/id "bob"] :seon.message/content "older"}
                    {:seon.message/id "message-newer" :seon.message/to [:seon.agent/id "bob"] :seon.message/content "newer"}])
      (let [ctx (test-support/fork-cluster-ctx connection "message-test")
            evaluate-form
            (fn [source]
              (test-support/evaluate
               {:seon.cluster.eval/source source
                :seon.cluster.eval/ns [:seon.ns/name 'my.agents.bob]
                :seon.agent/id "bob"
                :seon.db/db @connection
                :seon.db/connection connection
                :seon.sci.eval/ctx ctx
                :seon.sci.admit/caps
                (config/result-caps (test-support/effective-config))
                :seon.sci.eval/time-limit-ms 5000}))
            indexed
            (db/pull @connection
                     '[{:seon.fn/arities
                        [:seon.fn.arity/argument-count
                         :seon.fn.arity/input-refs]}]
                     [:seon.fn/sym 'my.message/inbox])
            argless (evaluate-form "(my.message/inbox)")
            listing (fn [evaluation]
                      (mapv #(select-keys %
                                          [:my.message/id
                                           :my.message/from
                                           :my.message/content])
                            (:seon.sci.admit/value evaluation)))
            expected [{:my.message/id "message-newer" :my.message/content "newer"}
                      {:my.message/id "message-older" :my.message/from "alice" :my.message/content "older"}]]
        (is (= #{1}
               (into #{}
                     (map :seon.fn.arity/argument-count)
                     (:seon.fn/arities indexed)))
            "the agent surface has one request-map arity")
        (is (some #(and (= 1 (:seon.fn.arity/argument-count %))
                        (= #{:my.message/inbox-request}
                           (set (:seon.fn.arity/input-refs %))))
                  (:seon.fn/arities indexed))
            "the request-map arity records its one named request input")
        (is (= expected
               (listing {:seon.sci.admit/value
                         (message/inbox
                          {:seon.db/db @connection
                           :seon.agent/id "bob"})}))
            "the request-map arity reads the same messages as the pair")
        (is (= expected
               (listing {:seon.sci.admit/value
                         (message/inbox @connection "bob")}))
            "the positional arity is unchanged by the request-map accretion")
        (is (= expected (listing argless))
            "the bare call uses the same declared request-map defaults")
        (is (nil? (:seon.cluster.eval/error argless)))))))

(deftest message-terminal-formatter-preserves-database-errors
  ;; A read refusal handed in place of a unit passes through unchanged. The
  ;; fixture is the database owner's own refusal (its :seon.error/kind
  ;; stand-in was retired in bc8152438).
  (let [failure (db/projection-fallback 'seon.cluster.message-test/read)]
    (is (db/read-refusal? failure))
    (is (= failure (message/format-ai failure)))))

(deftest inbox-unit-renders-both-projections-of-the-same-messages
  (with-database
    (fn [connection]
      (test-support/transacted!
                   connection
                   [{:seon.message/id "inbox/2" :seon.message/from [:seon.agent/id "alice"] :seon.message/to [:seon.agent/id "bob"] :seon.message/content "second"}
                    {:seon.message/id "inbox/1" :seon.message/from [:seon.agent/id "alice"] :seon.message/to [:seon.agent/id "bob"] :seon.message/content "first"}])
      (let [database @connection
            reverse-value
            (:seon.message/_to
             (db/pull database [:seon.message/_to]
                      [:seon.agent/id "bob"]))
            messages (db/pull-many database '[*]
                                   (mapv :db/id reverse-value))
            rendered (message/render-inbox-html messages database)]
        (let [inbox (message/render-inbox-ai messages database)]
          (is (= ["inbox/1" "inbox/2"] (mapv :seon.message/id (:seon.message/unread inbox)))
              "the unread, oldest first, as the recipient sees them")
          (is (= [['(my.message/received! "inbox/1")] ['(my.message/received! "inbox/2")]]
                 (mapv :seon.render/operations (:seon.message/unread inbox))))
          (is (= 0 (:seon.message/received inbox))))
        (message/received! "inbox/1" connection "bob")
        (let [inbox (message/render-inbox-ai (db/pull-many @connection '[*] (mapv :db/id reverse-value))
                                             @connection)]
          (is (= ["inbox/2"] (mapv :seon.message/id (:seon.message/unread inbox))))
          (is (= 1 (:seon.message/received inbox)) "an acknowledged message leaves the unread"))
        (is (not (str/includes? (pr-str rendered)
                                "my.message/send")))
        (is (= [:section {:class "seon-family-entry seon-message-inbox"}
                [:h2 "Messages (2)"]]
               (subvec rendered 0 3)))
        (is (= ["first" "second"]
               (mapv #(last (nth % 3)) (subvec rendered 3)))
            "oldest first, so the newest message is last in both projections")
        (is (every? some? (flatten rendered))
            "no absent optional element leaves a nil child behind"))))

  (testing "an agent with no messages renders an empty state, not an error"
    (with-database
      (fn [connection]
        (is (= [:section {:class "seon-family-entry seon-message-inbox"}
                [:h2 "Messages (0)"]
                [:p {:class "seon-message-inbox-empty"}
                 "No message is addressed to this agent yet."]]
               (message/render-inbox-html [] @connection)))))))

;; Class (E1, 89629b13c; data renders 2026-09-27): the agent's
;; `:seon.message/_to` concern hands its recipient reference, and the render
;; reads the inbox itself, as data. Its read is the shown unit's read
;; evidence, so a new or acknowledged message changes that read and the next
;; context refreshes it; a render that read nothing would never refresh.
(deftest an-inbox-reference-renders-the-unread-inbox-and-its-read-refreshes
  (with-database
    (fn [connection]
      (test-support/transacted!
       connection
       [{:seon.message/id "inbox/1" :seon.message/from [:seon.agent/id "alice"]
         :seon.message/to [:seon.agent/id "bob"] :seon.message/content "first"}])
      (let [recipient [:seon.agent/id "bob"]
            captured (atom [])
            shown (binding [db/*read-evidence-sink* captured]
                    (message/render-inbox-ai recipient @connection))
            evidence (db/read-evidence @captured)]
        (is (= [{:my.message/id "inbox/1" :my.message/from "alice" :seon.message/lines ["first"]
                 :seon.render/operations ['(my.message/received! "inbox/1")]}]
               shown)
            "the unread, oldest first: sender, content and the acknowledgement offered")
        (is (= '(my.message/inbox {:seon.agent/id "bob"}) (message/inbox-form recipient))
            "the form face is the read that finds this data again")
        (let [html (message/render-inbox-html recipient @connection)
              forms (filter #(and (vector? %) (= :form (first %))) (tree-seq vector? seq html))]
          (is (= [:h2 "Unread messages (1)"] (nth html 2))
              "HTML shows the same unread messages as the AI face (owner ruling: symmetric faces)")
          (is (= [["received" "inbox/1"]]
                 (mapv (fn [form] (mapv #(get-in % [1 :value]) (filter #(and (vector? %) (= :input (first %))) form)))
                       forms))
              "each unread message offers its received! as a button"))
        (is (true? (db/read-evidence-current? @connection evidence)))
        (test-support/transacted!
         connection
         [{:seon.message/id "inbox/2" :seon.message/from [:seon.agent/id "alice"]
           :seon.message/to [:seon.agent/id "bob"] :seon.message/content "second"}])
        (is (false? (db/read-evidence-current? @connection evidence))
            "a new message changes the shown read")
        (let [captured (atom [])
              _ (binding [db/*read-evidence-sink* captured]
                  (message/render-inbox-ai recipient @connection))
              evidence (db/read-evidence @captured)]
          (message/received! "inbox/1" connection "bob")
          (is (false? (db/read-evidence-current? @connection evidence))
              "an acknowledgement changes the shown read")
          (is (= ["inbox/2"] (mapv :my.message/id (message/render-inbox-ai recipient @connection)))))))))

(deftest message-html-separates-attribution-time-and-authored-content
  (with-database
    (fn [connection]
      (test-support/transacted! connection [{:db/id "datomic.tx" :db/txInstant now}
                                            {:seon.message/id "message-1"
                                             :seon.message/to [:seon.agent/id "bob"]
                                             :seon.message/content "first line\nsecond line"}])
      (let [database @connection
            rendered
            (message/render-html
             {:seon.db/db database :seon.message/id "message-1" :seon.message/content "first line\nsecond line" :seon.message/from [:seon.agent/id "alice"] :seon.message/to [:seon.agent/id "bob"] :seon.message/about "alice" :seon.message/caused-by {:seon.message/id "message-0"}})]
        (is (str/includes? (pr-str rendered) "first line\\nsecond line"))
        (is (str/includes? (pr-str rendered) "unread"))
        (is (str/includes? (pr-str rendered) "2023-11-14T22:13:20Z"))
        (is (not (str/includes? (pr-str rendered) "my.message/send")))
        (is (= "From outside this cluster to bob: first line\nsecond line"
               (message/format-ai
                {:seon.db/db database
                 :seon.message/content "first line\nsecond line"
                 :seon.message/to [:seon.agent/id "bob"]}))
            "the AI projection remains the existing exact sentence")
        (is (str/includes?
             (pr-str
              (message/render-html
               {:seon.db/db database :seon.message/content "hello" :seon.message/from [:seon.agent/id "nobody"] :seon.message/to [:seon.agent/id "bob"]}))
             "Unresolved sender")
            "unresolved attribution is explicit without raw reference data")))))

(defn- ask!
  "Commit one message from OUTSIDE the agent population — a human's.
  No `from`, and no triggering transaction: the head of a chain."
  [connection id to content]
  (test-support/transacted! connection
                          [{:seon.message/id id :seon.message/to [:seon.agent/id to] :seon.message/content content}])
  id)

(defn- deliver!
  "Commit a delivery the way the loop commits its ordinary entities."
  [connection {:keys [sender trigger run ordinal value chain]
               :or {ordinal 0 chain limit}}]
  (let [delivery (message/delivery
                  @connection
                  (cond-> {:my.message/value value :seon.agent/id sender :seon.turn/id run :seon.cluster.eval/ordinal ordinal :seon.config.message/max-chain chain}
                    trigger (assoc :seon.message/trigger trigger)))
        entities (:seon.message/rows delivery)]
    (when (seq entities)
      (test-support/transacted! connection entities))
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
            chain-limit (gen/choose 1 5)
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
  {:malli/schema [:=> [:cat :map :map :int] [:tuple :map :map]]}
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
       {::refusals []}]
      (let [depth (if trigger (inc (model-depth messages trigger)) 1)
            refused-kind (cond
                           (not (pos-int? chain-limit))
                           :seon.message/no-limit
                           (> depth chain-limit)
                           :seon.message/chain-limit)
            deliverable (if refused-kind
                          []
                          (filterv ::known? recipients))
            entities (mapv (fn [{::keys [candidate-index to content]}]
                         (let [id (id/id (random-uuid) 8)]
                           (cond-> {::id id ::to to ::from sender
                                    ::content content
                                    ::depth (if trigger depth 0)}
                             trigger (assoc ::parent trigger))))
                       deliverable)
            refusals (if refused-kind
                       [(if (= :seon.message/no-limit refused-kind)
                          {:seon.message/no-limit true}
                          {:seon.message/chain-limit chain-limit})]
                       (mapv (fn [recipient]
                               {:seon.message/unknown-recipient (::to recipient)})
                             (remove ::known? recipients)))
            next-model (reduce
                        (fn [current entity]
                          (-> current
                              (assoc-in [::messages (::id entity)] entity)
                              (update ::message-order conj (::id entity))))
                        model
                        entities)]
        [next-model
         {::data data
          ::rows entities
          ::refusals refusals}]))))

(defn- actual-messages
  "Every stored message as the model states it: one pull for the stored
  facts, and the owners' own reads (`chain-depth`, `sender`) for the
  derived ones under test."
  [db]
  (into {}
        (map
         (fn [{id :seon.message/id :as entity}]
           [id
            (cond-> {::id id
                     ::to (get-in entity [:seon.message/to :seon.agent/id])
                     ::content (:seon.message/content entity)
                     ::depth (message/chain-depth db id)}
              (:seon.message/from entity)
              (assoc ::from (message/sender db id))
              (:seon.message/caused-by entity)
              (assoc ::parent (get-in entity [:seon.message/caused-by :seon.message/id])))]))
        (db/q '[:find [(pull ?message [:seon.message/id :seon.message/content
                                        {:seon.message/to [:seon.agent/id]}
                                        {:seon.message/from [:seon.agent/id]}
                                        {:seon.message/caused-by [:seon.message/id]}]) ...]
                :where [?message :seon.message/id _]]
              db)))

(defn- with-entities
  "`database` with `rows` applied by the writer's own checks (`seon.db/with`);
  a refusal is thrown whole, as a fixture write refusal is."
  [database entities]
  (let [after (db/with database entities)]
    (if (:seon.error/message after)
      (throw (ex-info (str (:seon.error/message after)) after))
      after)))

(defn- execute-command!
  "One generated command against the database VALUE `database`: the step's
  next model, whether the stored facts agree with it, and the next value."
  {:malli/schema [:=> [:cat :seon.db/database-value :map :map :int]
                  [:tuple :map :boolean :seon.db/database-value]]}
  [database model command command-index]
  (let [[next-model expected] (model-command model command command-index)]
    (if (= :human (::command command))
      (let [{::keys [message-id to content]}
            (command-data model command command-index)
            after (with-entities database [{:seon.message/id message-id
                                      :seon.message/to [:seon.agent/id to]
                                      :seon.message/content content}])]
        [next-model
         (= (::messages next-model) (actual-messages after))
         after])
      (let [{::keys [sender trigger run-id ordinal chain-limit recipients]}
            (::data expected)
            value (mapv (fn [{::keys [to content]}]
                          (cond-> (seon.cluster.message/send to content)
                            (some #(= content (::content %)) (::rows expected))
                            (assoc :seon.message/id
                                   (::id (first (filter #(= content (::content %)) (::rows expected)))))))
                        recipients)
            request (cond-> {:my.message/value value :seon.agent/id sender :seon.turn/id run-id :seon.cluster.eval/ordinal ordinal :seon.config.message/max-chain chain-limit}
                      trigger
                      (assoc :seon.message/trigger trigger))
            delivery (message/delivery database request)
            entities (:seon.message/rows delivery)
            after (if (seq entities) (with-entities database entities) database)]
        [next-model
         (and
          (or (nil? chain-limit)
              (schema/valid-candidate-value? (schema/handed-projection)
               :seon.message/delivery-request request))
          (= (mapv ::id (::rows expected))
             (mapv :seon.message/id entities))
          (= (::refusals expected)
             (mapv #(select-keys % [:seon.message/no-limit :seon.message/chain-limit :seon.message/unknown-recipient]) (:seon.error/values delivery)))
          (every? #(schema/valid-candidate-value? (schema/handed-projection) :seon.error/value %)
                  (:seon.error/values delivery))
          (= (::messages next-model) (actual-messages after))
          (or (nil? chain-limit)
              (every? #(<= (::depth %) chain-limit)
                      (vals (::messages next-model)))))
         after]))))

(defn- generated-history-agrees-with-database?
  "Run one scenario from `base`, the prepared world holding every agent id.
  Recipients are drawn only from the scenario's population or from
  never-created ids, so agents beyond the population change nothing."
  [base {::keys [population-size chain-limit commands]}]
  (let [population (subvec agent-ids 0 population-size)]
    (second
     (reduce
      (fn [[model valid? database] [command-index command]]
        (let [[next-model step-valid? after]
              (execute-command! database model command command-index)]
          [next-model (and valid? step-valid?) after]))
      [{::population population
        ::chain-limit chain-limit
        ::messages {}
        ::message-order []}
       true
       base]
      (map-indexed vector commands)))))

(deftest generated-message-histories-preserve-identity-fanout-and-depth
  ;; The world is prepared once (every agent, one commit); each trial folds
  ;; its commands over database VALUES with the writer's checks
  ;; (`seon.db/with`), so trials share that read and never see each other's
  ;; writes. A committed fixture branch per trial cost ~350 ms each (branch
  ;; acquisition ~34 ms, ~50 ms per commit; JVM profile, tests-0627r:
  ;; 20,980 ms for 60 trials).
  (test-support/with-database
    (fn [connection]
      (test-support/transacted! connection (test-support/agents-tx @connection agent-ids))
      (let [base @connection]
        (is (= #{} (set (keys (actual-messages base))))
            "the prepared world holds no message the model does not know")
        (test-support/assert-check!
         (tc/quick-check
          60
          (prop/for-all [scenario message-scenario-generator]
            (generated-history-agrees-with-database? base scenario))
          :seed 202607280501)
         "Generated message history diverged from durable facts.")))))

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
      (let [entities (:seon.message/rows
                  (deliver! connection
                            {:sender "alice" :trigger "m-0" :run "r-1"
                             :value (seon.cluster.message/send "bob" "hello bob")}))
            written (into #{} (mapcat keys) entities)]
        (is (seq (set/intersection written
                                   (wake/wake-attributes (db/db connection))))
            "a delivery writes a wake attribute — that IS the transport")
        (is (empty? (set/intersection written
                                      (turn/committed-attributes (seon.schema/handed-projection))))
            "and it shares nothing with the loop's routine bookkeeping,
             so an ordinary turn still cannot wake itself")))))

;;; ---------------------------------------------------------------------------
;;; The entities
;;; ---------------------------------------------------------------------------

(deftest a-delivery-records-who-sent-it
  (with-database
    (fn [connection]
      (ask! connection "m-0" "alice" "ask bob")
      (deliver! connection {:sender "alice" :trigger "m-0" :run "r-1"
                            :value (seon.cluster.message/send "bob" "how many?")})
      (let [pulled (db/q '[:find (pull ?message [*]) .
                          :where
                          [?message :seon.message/content "how many?"]]
                        @connection)]
        (is (= "alice"
               (db/q '[:find ?id .
                      :in $ ?eid
                      :where [?eid :seon.agent/id ?id]]
                    @connection
                    (:db/id (:seon.message/from pulled))))
            "from resolves to the sending agent")
        (is (some? (:seon.message/to pulled)))))))

;;; ---------------------------------------------------------------------------
;;; The refusals, each a fact
;;; ---------------------------------------------------------------------------

;;; ---------------------------------------------------------------------------
;;; The chain — derived, and the human barrier that comes free with it
;;; ---------------------------------------------------------------------------

(deftest the-trigger-of-a-run-is-a-recorded-ref
  (with-database
    (fn [connection]
      (ask! connection "m-0" "alice" "hello")
      (test-support/transacted! connection
                              [{:seon.turn/id "r-1" :seon.turn/agent [:seon.agent/id "alice"] :seon.turn/trigger [:seon.message/id "m-0"] :seon.turn/opened-tx "datomic.tx"}])
      (is (= "m-0" (message/trigger @connection "r-1"))
          "the cause is an ordinary run fact")
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
                                 :value (message/send
                                         recipient
                                         (str "hop " hop
                                              " — and how about this?"))})
                      entities (:seon.message/rows delivery)]
                  (if (seq entities)
                    (recur (inc hop) recipient sender
                           (:seon.message/id (first entities))
                           (inc delivered))
                    {:outcome :refused
                     :delivered delivered
                     :limits (mapv :seon.message/chain-limit
                                  (:seon.error/values delivery))}))))]
        (is (= :refused (:outcome outcome))
            "an unattended agent-to-agent conversation stops itself")
        (is (= limit (:delivered outcome))
            "and it stops at exactly the configured number of hops")
        (is (= [limit] (:limits outcome))))))

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
                                   :value (seon.cluster.message/send recipient "…")})
                        entities (:seon.message/rows delivery)]
                    (if (and (seq entities) (< hop 200))
                      (recur (inc hop) recipient sender
                             (:seon.message/id (first entities))
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
                            :value (seon.cluster.message/send "bob" "how many?")})
      (is (= {:my.message/to "alice" :my.message/content "25"
               :my.message/about (db/q '[:find ?id . :where [?m :seon.message/id ?id] [?m :seon.message/content "how many?"]] @connection)}
             (message/reply @connection
                            {:my.turn/result "25"
                             :seon.agent/id "bob"
                             :seon.message/trigger
                             (db/q '[:find ?id . :where [?m :seon.message/id ?id] [?m :seon.message/content "how many?"]] @connection)}))
          "bob completing a run alice triggered owes alice the answer —
           derived from the trigger, never remembered by the agent"))))

(deftest a-completed-run-answering-a-human-replies-to-nobody
  (with-database
    (fn [connection]
      (ask! connection "m-0" "alice" "how many?")
      (is (nil? (message/reply @connection
                               {:my.turn/result "25"
                                :seon.agent/id "alice"
                                :seon.message/trigger "m-0"}))
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
                            :value (seon.cluster.message/send "bob" "how many?")})
      (deliver! connection {:sender "bob" :trigger (db/q '[:find ?id . :where [?m :seon.message/id ?id] [?m :seon.message/content "how many?"]] @connection)
                            :run "r-2"
                            :value (seon.cluster.message/send "alice" "25")})
      (is (nil? (message/reply @connection
                               {:my.turn/result "There are 25."
                                :seon.agent/id "alice"
                                :seon.message/trigger
                                (db/q '[:find ?id . :where [?m :seon.message/id ?id] [?m :seon.message/content "25"]] @connection)}))
          "alice completing on bob's ANSWER owes bob nothing — the
           delegation ends when the delegator completes, which is what
           puts the chain bound back to being a backstop")))

  (testing "but a genuine second question from the same peer is answered"
    (with-database
      (fn [connection]
        (ask! connection "m-0" "bob" "ask alice something")
        (deliver! connection {:sender "bob" :trigger "m-0" :run "r-1"
                              :value (seon.cluster.message/send "alice" "how many?")})
        (is (= "bob" (:my.message/to
                      (message/reply @connection
                                     {:my.turn/result "25"
                                      :seon.agent/id "alice"
                                      :seon.message/trigger
                                      (db/q '[:find ?id . :where [?m :seon.message/id ?id] [?m :seon.message/content "how many?"]] @connection)})))
            "bob's message was caused by the HUMAN's, not by alice's")))))

;;; ---------------------------------------------------------------------------
;;; Slice 1 — the outside message is ordinary transaction data
;;; ---------------------------------------------------------------------------

(deftest inbound-tx-omits-from-test
  (with-database
    (fn [connection]
      (commit-inbound! connection (inbound-request "bob" "hello bob"))
      (let [entity (db/q '[:find (pull ?message [*]) .
                       :where
                       [?message :seon.message/content "hello bob"]]
                     @connection)]
        (is (string? (:seon.message/id entity)))
        (is (= "bob"
               (db/q '[:find ?agent-id .
                      :in $ ?message-id
                      :where
                      [?message :seon.message/id ?message-id]
                      [?message :seon.message/to ?agent]
                      [?agent :seon.agent/id ?agent-id]]
                    @connection
                    (:seon.message/id entity))))
        (is (nil? (get (:schema @connection) :seon.message/inbox)))
        (is (not (contains? entity :seon.message/from))
            "absence, not a human/origin stamp, is the outside contract")))))

(deftest inbound-identity-is-unique-under-burst-test
  ;; seed 2026072901 — the pre-handler probe, retained as a recurring gate.
  (with-database
    (fn [connection]
      (doseq [index (range 64)]
        (commit-inbound!
         connection
         (inbound-request "bob" (str "burst-" index))))
      (let [inbound-ids (db/q '[:find [?id ...]
                               :where [?message :seon.message/id ?id]
                               [?message :seon.message/to ?recipient]
                               [?recipient :seon.agent/id "bob"]
                               (not [?message :seon.message/from])]
                             @connection)]
        (is (= 64 (count inbound-ids)))
        (is (= 64 (count (distinct inbound-ids)))
            "every accepted writer basis yields a distinct identity")))))

(deftest inbound-wakes-the-named-agent-test
  ;; seed 2026072902 — no delivery step between commit and route!.
  (with-database
    (fn [connection]
      (let [alice-eid (db/q '[:find ?agent .
                             :where [?agent :seon.agent/id "alice"]]
                           @connection)
            bob-eid (db/q '[:find ?agent .
                           :where [?agent :seon.agent/id "bob"]]
                         @connection)
            alice (async/chan (async/sliding-buffer 1))
            bob (async/chan (async/sliding-buffer 1))
            db<->flow (async/chan (async/sliding-buffer 1))
            render (async/chan (async/sliding-buffer 1))
            faults (async/chan (async/sliding-buffer 1))
            key (wake/route!
                 {:seon.cluster.wake/connection connection
                  :seon.cluster.wake/channels
                  (fn [] {alice-eid alice bob-eid bob})
                  :seon.cluster.wake/fenced? (fn [_ _] false)
                  :seon.flow/plan-channel db<->flow
                  :seon.cluster.wake/render-channel render
                  :seon.render.web/interest (atom :all)
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
        (is (= [true 9 "nobody"]
               [(:seon.message/blank-content (first results))
                (:seon.message/content-too-large (second results))
                (:seon.message/unknown-recipient (nth results 2))]))
        (is (every? #(schema/valid-candidate-value? (schema/handed-projection)
                      :seon.error/value %)
                    results))
        (is (not-any? vector? results)
            "every refusal is a flat value and produces no entities")))))

(deftest reverse-agent-concerns-are-schema-declarations
  (let [forms (schema.edn/packaged-forms)
        units (:seon.render/units (second (:seon.agent/agent forms)))]
    (is (some #{:seon.message/_to} units))
    (is (not-any? (set units)
                  [:seon.def/_agent :seon.def/_ns
                   :seon.schema.admission/_source :seon.render.route/_data]))))

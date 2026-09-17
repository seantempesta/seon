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
            [datahike.api :as d]
            [seon.db :as db]
            [seon.id :as id]
            [seon.turn :as turn]
            [seon.cluster.message :as message]

            [seon.cluster.wake :as wake]
            [seon.config :as config]
            [seon.render.route :as route]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.schema.datahike :as schema.datahike]
            [seon.sci.eval :as sci.eval]
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
                              [{:seon.agent/id "alice"}
                               {:seon.agent/id "bob"}])
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

(deftest message-ai-source-evaluates-through-the-agent-database
  (with-database
    (fn [connection]
      (config/apply! {:seon.db/connection connection
                     :seon.boot/cluster-name "message-test"})
      (test-support/transacted!
                   connection
                   [{:seon.message/id "message-1" :seon.message/from [:seon.agent/id "alice"] :seon.message/to [:seon.agent/id "bob"] :seon.message/content "hello"}])
      (let [source (message/render-ai {:seon.message/id "message-1"})
            planned (turn/planned-sources source 'my.agents.alice 4096)
            evaluated
            (sci.eval/evaluate
             {:seon.cluster.eval/source
              (:seon.cluster.eval/source (first planned))
              :seon.cluster.eval/ns [:seon.ns/name 'my.agents.alice]
              :seon.db/db @connection
              :seon.db/connection connection
              :seon.agent/id "alice"
              :seon.sci.eval/ctx (test-support/fork-cluster-ctx connection "message-test")
              :seon.sci.admit/caps
              (config/result-caps (test-support/effective-config))
              :seon.sci.eval/time-limit-ms 5000
              :seon.config/on-core-error :panic})]
        (is (= 1 (count planned)))
        (is (= (message/read "message-1" @connection)
               (:seon.sci.admit/value evaluated)))
        (is (nil? (:seon.cluster.eval/error evaluated)))))))

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
              (sci.eval/evaluate
               {:seon.cluster.eval/source source
                :seon.cluster.eval/ns [:seon.ns/name 'my.agents.bob]
                :seon.agent/id "bob"
                :seon.db/db @connection
                :seon.db/connection connection
                :seon.sci.eval/ctx ctx
                :seon.sci.admit/caps
                (config/result-caps (test-support/effective-config))
                :seon.sci.eval/time-limit-ms 5000
                :seon.config/on-core-error :panic}))
            indexed
            (db/pull @connection
                     '[{:seon.fn/arities
                        [:seon.fn.arity/argument-count
                         {:seon.fn.arity/input-refs [:seon.schema/key]}]}]
                     [:seon.fn/sym "my.message/inbox"])
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
                        (= [:my.message/inbox-request]
                           (mapv :seon.schema/key
                                 (:seon.fn.arity/input-refs %))))
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
  (let [failure {:seon.error/kind ::read-failed
                 :seon.error/message "message read failed"}]
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
        (is (= (str/join "\n\n"
                             (map message/render-ai (sort-by :seon.message/id messages)))
               (message/render-inbox-ai messages)))
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

  (testing "one message's own recipient reference still renders as a reference"
    (with-database
      (fn [connection]
        (let [database @connection
              rendered (message/render-inbox-html
                        [:seon.agent/id "bob"] database)]
          (is (= [:p [:a {:href (route/path
                                 ::route/data {}
                                 {:entity (pr-str
                                           [:seon.agent/id "bob"])})}
                      "bob"]]
                 (nth rendered 3)))))))

  (testing "an agent with no messages renders an empty state, not an error"
    (with-database
      (fn [connection]
        (is (= [:section {:class "seon-family-entry seon-message-inbox"}
                [:h2 "Messages (0)"]
                [:p {:class "seon-message-inbox-empty"}
                 "No message is addressed to this agent yet."]]
               (message/render-inbox-html [] @connection)))))))

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
             {:seon.db/db database :seon.message/id "message-1" :seon.message/content "first line\nsecond line" :seon.message/from [:seon.agent/id "alice"] :seon.message/to [:seon.agent/id "bob"] :seon.message/about [:seon.agent/id "alice"] :seon.message/caused-by {:seon.message/id "message-0"}})]
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
  "Commit a delivery the way the loop commits its ordinary rows."
  [connection {:keys [sender trigger run ordinal value chain]
               :or {ordinal 0 chain limit}}]
  (let [delivery (message/delivery
                  @connection
                  (cond-> {:my.message/value value :seon.agent/id sender :seon.turn/id run :seon.cluster.eval/ordinal ordinal :seon.config.message/max-chain chain}
                    trigger (assoc :seon.message/trigger trigger)))
        rows (:seon.message/rows delivery)]
    (when (seq rows)
      (test-support/transacted! connection rows))
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
                           :seon.message/no-limit
                           (> depth chain-limit)
                           :seon.message/chain-limit)
            deliverable (if refused-kind
                          []
                          (filterv ::known? recipients))
            rows (mapv (fn [{::keys [candidate-index to content]}]
                         (let [id (id/id (random-uuid) 8)]
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
                                       :seon.message/unknown-recipient)))
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
           (let [id (:seon.message/id entity)
                 parent
                 (db/q '[:find ?parent-id .
                        :in $ ?id
                        :where
                        [?message :seon.message/id ?id]
                        [?message :seon.message/caused-by ?parent]
                        [?parent :seon.message/id ?parent-id]]
                      db id)]
             [id
              (cond-> {::id id
                       ::to (db/q '[:find ?agent-id .
                                   :in $ ?to
                                   :where [?to :seon.agent/id
                                           ?agent-id]]
                                 db
                                 (:db/id (:seon.message/to entity)))
                       ::content (:seon.message/content entity)
                       ::depth (message/chain-depth db id)}
                (:seon.message/from entity)
                (assoc ::from (message/sender db id))
                parent (assoc ::parent parent))]))
         (db/q '[:find [(pull ?message [*]) ...]
                :where [?message :seon.message/id _]]
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
                          (cond-> (seon.cluster.message/send to content)
                            (some #(= content (::content %)) (::rows expected))
                            (assoc :seon.message/id
                                   (::id (first (filter #(= content (::content %)) (::rows expected)))))))
                        recipients)
            request (cond-> {:my.message/value value :seon.agent/id sender :seon.turn/id run-id :seon.cluster.eval/ordinal ordinal :seon.config.message/max-chain chain-limit}
                      trigger
                      (assoc :seon.message/trigger trigger))
            delivery (message/delivery @connection request)
            rows (:seon.message/rows delivery)]
        (when (seq rows)
          (test-support/transacted! connection rows))
        [next-model
         (and
          (or (nil? chain-limit)
              (schema/valid-candidate-value?
               :seon.message/delivery-request request))
          (= (mapv ::id (::rows expected))
             (mapv :seon.message/id rows))
          (= (::error-kinds expected)
             (mapv :seon.error/kind (:seon.error/values delivery)))
          (every? #(schema/valid-candidate-value? :seon.error/value %)
                  (:seon.error/values delivery))
          (= (::messages next-model) (actual-messages @connection))
          (or (nil? chain-limit)
              (every? #(<= (::depth %) chain-limit)
                      (vals (::messages next-model)))))]))))

(defn- generated-history-agrees-with-database?
  [{::keys [population-size chain-limit commands]}]
  (let [population (subvec agent-ids 0 population-size)]
    (test-support/with-database
      (fn [connection]
        (test-support/transacted! connection
                                (mapv (fn [id] {:seon.agent/id id})
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
      (let [rows (:seon.message/rows
                  (deliver! connection
                            {:sender "alice" :trigger "m-0" :run "r-1"
                             :value (seon.cluster.message/send "bob" "hello bob")}))
            written (into #{} (mapcat keys) rows)]
        (is (seq (set/intersection written
                                   (wake/wake-attributes (db/db connection))))
            "a delivery writes a wake attribute — that IS the transport")
        (is (empty? (set/intersection written
                                      (turn/committed-attributes)))
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

(deftest a-non-temporal-run-retains-its-trigger-without-a-transaction-entity
  (let [configuration {:store {:backend :memory :id (random-uuid)}
                       :keep-history? false
                       :schema-flexibility :write}
        _ (d/create-database configuration)
        connection (d/connect configuration)]
    (try
      (test-support/transacted!
                   connection
                   (schema.datahike/malli->datahike-schema
                    (schema/canonical-database-attributes)))
      (test-support/transacted! connection [{:seon.agent/id "alice"}])
      (ask! connection "m-0" "alice" "hello")
      (let [report
            (db/transact!
             connection
             (turn/open-tx
              {:seon.turn/id "r-1" :seon.turn/agent [:seon.agent/id "alice"] :seon.turn/trigger [:seon.message/id "m-0"] :seon.turn/opened-tx "datomic.tx"}))
            transaction (:max-tx (:db-after report))]
        (is (nil? (db/pull @connection '[*] transaction))
            "the HISTORY-OFF database has no transaction entity")
        (is (= "m-0" (message/trigger @connection "r-1"))
            "the run's ordinary trigger ref survives independently"))
      (finally
        (d/release connection)
        (d/delete-database configuration)))))

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
                      rows (:seon.message/rows delivery)]
                  (if (seq rows)
                    (recur (inc hop) recipient sender
                           (:seon.message/id (first rows))
                           (inc delivered))
                    {:outcome :refused
                     :delivered delivered
                     :kinds (mapv :seon.error/kind
                                  (:seon.error/values delivery))}))))]
        (is (= :refused (:outcome outcome))
            "an unattended agent-to-agent conversation stops itself")
        (is (= limit (:delivered outcome))
            "and it stops at exactly the configured number of hops")
        (is (= [:seon.message/chain-limit] (:kinds outcome))))))

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
                        rows (:seon.message/rows delivery)]
                    (if (and (seq rows) (< hop 200))
                      (recur (inc hop) recipient sender
                             (:seon.message/id (first rows))
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
      (let [row (db/q '[:find (pull ?message [*]) .
                       :where
                       [?message :seon.message/content "hello bob"]]
                     @connection)]
        (is (string? (:seon.message/id row)))
        (is (= "bob"
               (db/q '[:find ?agent-id .
                      :in $ ?message-id
                      :where
                      [?message :seon.message/id ?message-id]
                      [?message :seon.message/to ?agent]
                      [?agent :seon.agent/id ?agent-id]]
                    @connection
                    (:seon.message/id row))))
        (is (nil? (get (:schema @connection) :seon.message/inbox)))
        (is (not (contains? row :seon.message/from))
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
        (is (= [:seon.message/blank-content
                :seon.message/content-too-large
                :seon.message/unknown-recipient]
               (mapv :seon.error/kind results)))
        (is (every? #(schema/valid-candidate-value?
                      :seon.error/value %)
                    results))
        (is (not-any? vector? results)
            "every refusal is a flat value and produces no rows")))))

(deftest reverse-agent-concerns-are-schema-declarations
  (let [forms (schema.edn/packaged-forms)
        units (:seon.render/units (second (:seon.agent/agent forms)))]
    (is (some #{:seon.message/_to} units))
    (is (not-any? (set units)
                  [:seon.def/_agent :seon.def/_ns
                   :seon.schema.admission/_source :seon.render.route/_data]))))

(ns seon.cluster.wake-test
  "The wake, through the ONE listener that survives: `route!`.

  RE-GROUNDED AT F2 §3.3. `listen!`'s one-global-channel delivery and
  its `wake?` predicate are deleted; the six classes they proved are
  proved here against routing delivery instead — wake-on-routed-set
  only, C2 disjointness between two COMPUTED sets, one interested
  delivery per commit, drop-never-park, fault-never-hang, and unlisten
  idempotence.
  Not one oracle is looser than it was; each now runs against the
  listener production actually registers.

  The two hazards are LIVE here, not described: a handler that throws
  must not hang the committing caller (probe A killed a JVM proving it
  does), and a saturated channel must drop rather than park. Both are
  asserted against a real in-memory connection with a real `transact`
  on the test's own thread — a mock listener would prove nothing about
  the one thing that matters, which is what datahike does INSIDE the
  transaction's go block before it delivers."
  (:require [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as async.protocols]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [seon.db :as db]
            [seon.turn :as turn]
            [seon.cluster.wake :as wake]
            [seon.config :as config]
            [seon.error :as error]
            [seon.schema]
            [seon.test-support :as test-support])
  (:import [java.util Date]))

(def ^:private result-caps
  (config/result-caps (test-support/effective-config)))

(defn- with-connection [body]
  (test-support/with-database
    (fn [connection]
      (test-support/transacted! connection [{:seon.agent/id "agent-a"}])
      (body connection))))

(defn- agent-eid
  "The recipient's ENTITY ID — what a `:seon.message/to` datom
  carries as its value, and therefore the key `route!` looks up."
  [connection]
  (db/q '[:find ?e . :where [?e :seon.agent/id "agent-a"]]
       @connection))

(defn- message-tx [id]
  [{:seon.message/id id :seon.message/to [:seon.agent/id "agent-a"] :seon.message/content "hello"}])

(defn- run-tx
  "A commit of attributes a TURN writes — the other side of C2."
  [id]
  [{:seon.turn/id id :seon.turn/agent [:seon.agent/id "agent-a"] :seon.turn/opened-tx "datomic.tx"}])

(defn- route-probe!
  "Register the production listener with this test's own channels.
  Returns the channels plus the registered key, so every test speaks to
  the same wiring `arm-agents!` builds."
  ([connection mailbox]
   (route-probe! connection mailbox (fn [_ _] false)))
  ([connection mailbox fenced?]
   (route-probe! connection mailbox fenced? (atom :all)))
  ([connection mailbox fenced? interest]
   (route-probe! connection mailbox fenced? interest
                 (async/chan (async/sliding-buffer 1))))
  ([connection mailbox fenced? interest render]
   (let [recipient-eid (agent-eid connection)
         channels {recipient-eid mailbox}
         armer (async/chan (async/sliding-buffer 1))
         search (async/chan (async/sliding-buffer 1))
         faults (async/chan (async/sliding-buffer 1))]
     {:mailbox mailbox
      :armer armer
      :render render
      :search search
      :faults faults
      :key (wake/route! {:seon.cluster.wake/connection connection
                         :seon.cluster.wake/channels
                         (constantly channels)
                         :seon.cluster.wake/fenced? fenced?
                         :seon.cluster.wake/armer-channel armer
                         :seon.cluster.wake/render-channel render
                         :seon.render.web/interest interest
                         :seon.cluster.wake/search-channel search
                         :seon.cluster.wake/fault-channel faults
                         :seon.cluster.wake/key ::probe})})))

;;; ---------------------------------------------------------------------------
;;; C1 — the routed set and its three traps
;;; ---------------------------------------------------------------------------

(deftest a-routed-attribute-wakes-its-agent-and-nothing-else-does
  (with-connection
    (fn [connection]
      (let [mailbox (async/chan (async/sliding-buffer 1))
            {:keys [render search key]} (route-probe! connection mailbox)]
        (try
          (test-support/transacted! connection (message-tx "m-1"))
          (is (some? (test-support/await-event! mailbox "mailbox wake"))
              "a message to this agent reaches ITS mailbox")
          (let [report (test-support/await-event! search
                                                  "search transaction report")]
            (is (= (:max-tx (:db-after report))
                   (:max-tx @connection))
                "the same listener forwards the exact committed basis"))
          (testing "a commit of attributes only a TURN writes wakes no
                    mailbox — the trap that would spin an idle cluster"
            (test-support/transacted! connection (run-tx "run-1"))
            (is (nil? (async/poll! mailbox))))
          (testing "a transaction instant is in EVERY report and routes
                    nowhere by itself"
            ;; the run commit above carried one, and the mailbox stayed
            ;; empty; this probe publishes cold `:all` render interest,
            ;; so its passive render consumer accepts the report
            (is (some? (async/poll! render))
                "every commit is render interest"))
          (finally
            (wake/unlisten! {:seon.cluster.wake/connection connection
                             :seon.cluster.wake/key key})))))))

;;; ---------------------------------------------------------------------------
;;; C2 — disjointness, computed on both sides
;;; ---------------------------------------------------------------------------

(deftest runtime-listens-route-and-refresh-with-entity-and-value-constraints
  (test-support/with-database
   {::test-support/extra-schema
    [{:db/ident :example/amount :db/valueType :db.type/long
      :db/cardinality :db.cardinality/one}
     {:db/ident :example/other :db/valueType :db.type/long
      :db/cardinality :db.cardinality/one}]}
   (fn [connection]
     (is (not (:seon.error/kind
               (db/transact! connection
                             [{:seon.agent/id "agent-a"}
                              {:db/id "runtime" :seon.runtime/agent [:seon.agent/id "agent-a"]}
                              [:db/add [:seon.agent/id "agent-a"] :seon.agent/runtime "runtime"]
                              {:seon.message/id "order-a" :seon.message/content "a"
                               :seon.message/to [:seon.agent/id "agent-a"]}
                              {:seon.message/id "order-b" :seon.message/content "b"
                               :seon.message/to [:seon.agent/id "agent-a"]}]))))
     (let [mailbox (async/chan (async/sliding-buffer 1))
           {:keys [key faults armer render search]} (route-probe! connection mailbox)
           write! (fn [tx]
                    (let [result (db/transact! connection tx)]
                      (is (nil? (:seon.error/kind result)) (pr-str result))
                      result))]
       (try
         (write! [{:seon.runtime/agent [:seon.agent/id "agent-a"]
                   :seon.runtime/listens [{:seon.listen/attribute :example/amount}]}])
         (is (nil? (async/poll! mailbox)))
         (write! [[:db/add [:seon.message/id "order-a"] :example/other 1]])
         (is (nil? (async/poll! mailbox)))
         (write! [[:db/add [:seon.message/id "order-a"] :example/amount 1]])
         (is (= ::wake/wake (test-support/await-event! mailbox "authored amount listen")))
         (let [listen (db/q '[:find ?listen . :where [_ :seon.runtime/listens ?listen]]
                            @connection)]
           (write! [[:db/add listen :seon.listen/entity [:seon.message/id "order-a"]]
                    [:db/add listen :seon.listen/value 3]])
           (write! [[:db/add [:seon.message/id "order-b"] :example/amount 3]])
           (is (nil? (async/poll! mailbox)) "different entity")
           (write! [[:db/add [:seon.message/id "order-a"] :example/amount 2]])
           (is (nil? (async/poll! mailbox)) "different value")
           (write! [[:db/add [:seon.message/id "order-a"] :example/amount 3]])
           (is (= ::wake/wake (test-support/await-event! mailbox "constrained amount listen")))
           (write! [[:db/retract [:seon.message/id "order-a"] :example/amount 3]])
           (is (= ::wake/wake (test-support/await-event! mailbox "listen matches retraction")))
           (write! [[:db.fn/retractEntity listen]])
           (write! [[:db/add [:seon.message/id "order-a"] :example/amount 3]])
           (is (nil? (async/poll! mailbox)) "removed listen no longer routes"))
         (is (nil? (async/poll! faults)))
         (finally
           (wake/unlisten! {:seon.cluster.wake/connection connection :seon.cluster.wake/key key})
           (doseq [channel [mailbox faults armer render search]] (async/close! channel))))))))

(deftest a-constrained-listen-matcher-reads-both-declared-datom-shapes
  ;; A compiled matcher is a predicate over ONE datom, and the system has two
  ;; declared datom shapes for the same fact: the host `Datom` a transaction
  ;; report carries in `:tx-data`, and the `:seon.db/datom` map
  ;; `seon.db/datoms` returns (`resources/seon/schemas/seon.db.edn:173`).
  ;; Reading either by position refused the map with `nth not supported on
  ;; this type: PersistentArrayMap`; reading it through the declared keys
  ;; answers both. Both a REF-valued and a scalar-valued constraint are
  ;; covered, because they take the two different encoding branches.
  (test-support/with-database
   {::test-support/extra-schema
    [{:db/ident :example/amount :db/valueType :db.type/long
      :db/cardinality :db.cardinality/one}]}
   (fn [connection]
     (test-support/transacted!
      connection
      [{:seon.agent/id "agent-a"}
       {:db/id "runtime" :seon.runtime/agent [:seon.agent/id "agent-a"]}
       [:db/add [:seon.agent/id "agent-a"] :seon.agent/runtime "runtime"]
       {:seon.message/id "order-a" :seon.message/content "a"
        :seon.message/to [:seon.agent/id "agent-a"]}])
     (let [recipient (agent-eid connection)]
       (test-support/transacted!
        connection
        [{:seon.runtime/agent [:seon.agent/id "agent-a"]
          :seon.runtime/listens
          [{:seon.listen/attribute :example/amount :seon.listen/value 3}
           {:seon.listen/attribute :seon.message/to
            :seon.listen/value [:seon.agent/id "agent-a"]}]}])
       (let [scalar-report
             (test-support/transacted!
              connection [[:db/add [:seon.message/id "order-a"] :example/amount 3]])
             ref-report
             (test-support/transacted!
              connection [{:seon.message/id "order-b" :seon.message/content "b"
                           :seon.message/to [:seon.agent/id "agent-a"]}])
             database (db/db connection)
             matchers (#'wake/wake-matchers database)
             fires (fn [attribute datom]
                     (vec (keep #(% datom)
                                (get-in matchers [attribute ::wake/matches]))))
             committed (fn [report attribute]
                         (first (filter #(= attribute (:a %)) (:tx-data report))))
             order-b (db/q '[:find ?e . :where [?e :seon.message/id "order-b"]]
                           database)]
         (testing "the report's own host datom"
           (let [scalar (committed scalar-report :example/amount)
                 reference (committed ref-report :seon.message/to)]
             (is (some? scalar))
             (is (some? reference))
             (is (= [recipient] (fires :example/amount scalar))
                 "a scalar value constraint fires on the committed datom")
             (is (= [recipient] (fires :seon.message/to reference))
                 "a ref value constraint fires on the committed datom")
             (is (= [] (fires :example/amount
                              {:e (:e scalar) :a (:a scalar) :v 4
                               :tx (:tx scalar) :added true}))
                 "a non-matching value still never fires")))
         (testing "the ordinary :seon.db/datom map seon.db/datoms returns"
           (let [scalar (first (db/datoms database :eavt
                                          (:e (committed scalar-report :example/amount))
                                          :example/amount))
                 reference (first (db/datoms database :eavt order-b
                                             :seon.message/to))]
             (is (map? scalar) (pr-str scalar))
             (is (map? reference) (pr-str reference))
             (is (= [recipient] (fires :example/amount scalar))
                 "the same scalar fact read as a map fires the same matcher")
             (is (= [recipient] (fires :seon.message/to reference))
                 "the same ref fact read as a map fires the same matcher"))))))))

(deftest a-turn-never-wakes-itself
  (with-connection
    (fn [connection]
      (let [wakes (wake/wake-attributes (db/db connection))
            commits (turn/committed-attributes (seon.schema/handed-projection))]
        (is (seq wakes) "the routed set is not empty")
        (is (seq commits) "and neither is the committed set")
        (is (empty? (set/intersection wakes commits))
            "an agent that wakes on its own commits spins forever — and both
             sides of this are computed, never a reviewed list")))))

(defn- declaring
  "The attributes whose CANONICAL SCHEMA ROW carries `property`.

  The expected side of the wake sets, read from the schema resources on
  the classpath rather than written down here. A literal would be exactly
  the hand list these derivations exist to abolish, and it went stale the
  first time a family was declared (`:seon.issue/agent`, 2026-09-16).
  Comparing resources against the installed database is a real oracle:
  it fails when a declaration does not reach its row, when a row is not
  installed, and when a family is added or removed without anyone
  deciding it here."
  [property]
  (into #{}
        (comp (filter (fn [row] (true? (get row property))))
              (map :seon.schema/key))
        (seon.schema/canonical-schema-rows)))

(deftest the-listened-set-is-declared-not-listed
  ;; THE CLASS: a hand list of wake attributes drifts from the families
  ;; that own them. The set is one query over `:seon.wake/listen`, and a
  ;; synthetic attribute declaring it joins with no code change (the
  ;; live proof of that is `a-declared-attribute-wakes-with-no-code-change`).
  (with-connection
    (fn [connection]
      (let [database (db/db connection)
            listened (wake/wake-attributes database)
            opening (wake/turn-opening-attributes database)
            inside (wake/inside-attributes database)
            arming (wake/arming-attributes database)]
        (is (= (declaring :seon.wake/listen) listened)
            "the families that wake an agent, derived from their
             own declarations")
        (is (= (declaring :seon.wake/opens-turn?) opening)
            "and the ones whose unanswered wake may pay for a model call")
        (is (= (declaring :seon.wake/inside) inside)
            "and the population's own activity never refills the turn
             bound it spends")
        (is (= (declaring :seon.wake/arms) arming)
            "and the one that means a new agent exists to arm")
        ;; The RULINGS the declarations encode, each stated where a reader
        ;; meets it. These are membership claims about one family, never a
        ;; second copy of the set.
        (is (contains? opening :seon.message/to)
            "a message is an ask, so it opens a turn")
        (is (contains? opening :seon.issue/agent)
            "so is an issue assignment: it is a one-time explicit ask, and
             a worker that never turns about its own issue is the whole
             reason the assignment became a wake")
        (is (and (contains? listened :seon.schedule.fire/agent)
                 (not (contains? opening :seon.schedule.fire/agent)))
            "a schedule firing surfaces in the next context; it never
             pays for a model call by itself")
        (is (and (contains? arming :seon.agent/id)
                 (not (contains? listened :seon.agent/id)))
            "creating an agent arms it and wakes nothing: the armer
             derives (agents in facts) - (armed set), so the datom's
             value is not a recipient and nothing is routed")))))

;;; ---------------------------------------------------------------------------
;;; C3 — the handler's two absolute prohibitions, live
;;; ---------------------------------------------------------------------------

(deftest an-unrouted-recipient-reaches-the-armer
  ;; TWO WAYS TO THE ARMER, and neither is a second arming path.
  ;;
  ;; 1. CREATION ITSELF. `:seon.agent/id` is declared `:seon.wake/arms`,
  ;;    so committing an agent offers the armer a payload-free wake. Before
  ;;    that declaration an agent created and not addressed waited for the
  ;;    next boot while `next-agent-work` reported work nobody could run
  ;;    (`a-worker-started-while-the-cluster-runs-is-never-armed`).
  ;; 2. THE BELT. An agent created and addressed in ONE commit has no
  ;;    routing entry yet, so its message ALSO falls through here.
  ;;
  ;; Both end in the same derive-all pass: (agents in facts) − (armed set).
  (with-connection
    (fn [connection]
      (let [mailbox (async/chan (async/sliding-buffer 1))
            {:keys [armer key]} (route-probe! connection mailbox)]
        (try
          (test-support/transacted! connection [{:seon.agent/id "agent-b"}])
          (is (some? (test-support/await-event! armer "creation arm wake"))
              "creating an agent asserts its arming attribute, so the
               armer takes a pass without anyone addressing it")
          (test-support/transacted!
                       connection
                       [{:seon.message/id "m-to-b" :seon.message/to [:seon.agent/id "agent-b"] :seon.message/content "hello"}])
          (is (some? (test-support/await-event! armer "armer wake"))
              "the armer derives (agents in facts) − (armed set)")
          (is (nil? (async/poll! mailbox))
              "and agent-a's mailbox is untouched — the wake's VALUE is
               the recipient")
          (finally
            (wake/unlisten! {:seon.cluster.wake/connection connection
                             :seon.cluster.wake/key key})))))))

(deftest a-declared-attribute-wakes-with-no-code-change
  ;; THE CLASS: a new wake source used to be two hand-edited lists in
  ;; `route!`. It is now one schema property. A synthetic ref attribute
  ;; declaring `:seon.wake/listen true` — installed exactly the way a
  ;; genuinely new family would be — routes to the agent it points at,
  ;; and not one line of this namespace's production code knows it
  ;; exists.
  (test-support/with-database
    {::test-support/extra-schema
     [{:db/ident ::notice
       :db/valueType :db.type/ref
       :db/cardinality :db.cardinality/one
       ;; A LISTENED ATTRIBUTE MUST BE INDEXED. Datahike's `:avet` index
       ;; holds only the attributes declared `:db/index true`, and the
       ;; wake derivations seek that index; without it every wake on
       ;; this attribute would read as absent. `route!` refuses the
       ;; declaration rather than routing into that silence — proven by
       ;; `an-unindexed-listened-attribute-is-refused-at-registration`.
       :db/index true}
      {:seon.schema/key ::notice
       :seon.schema.admission/source :core
       :seon.schema/form ":seon.db/ref"
       :seon.wake/listen true
       :seon.wake/opens-turn? true}]}
    (fn [connection]
      (test-support/transacted! connection [{:seon.agent/id "agent-a"}])
      (let [recipient (agent-eid connection)
            mailbox (async/chan (async/sliding-buffer 1))
            {:keys [key]} (route-probe! connection mailbox)]
        (try
          (is (contains? (wake/wake-attributes (db/db connection))
                         ::notice)
              "the derived set learned the new attribute from its own
               declaration")
          (test-support/transacted! connection [{::notice recipient}])
          (is (some? (test-support/await-event! mailbox "declared wake"))
              "and a datom on it woke the agent it points at")
          (finally
            (wake/unlisten! {:seon.cluster.wake/connection connection
                             :seon.cluster.wake/key key})))))))

(deftest an-unindexed-listened-attribute-is-refused-at-registration
  ;; THE CLASS: absence of signal read as health. Datahike's `:avet`
  ;; index contains ONLY attributes declared `:db/index true`
  ;; (`reference-code/datahike/src/datahike/db.cljc:932`), and the turn
  ;; bound and the unanswered-wake derivation both seek that index. A
  ;; listened attribute without the index answers every seek with an
  ;; empty sequence — no exception, no refusal — so the agent would
  ;; simply never turn. The declaration is refused where it is
  ;; registered instead.
  (test-support/with-database
    {::test-support/extra-schema
     [{:db/ident ::unindexed-notice
       :db/valueType :db.type/ref
       :db/cardinality :db.cardinality/one}
      {:seon.schema/key ::unindexed-notice
       :seon.schema.admission/source :core
       :seon.schema/form ":seon.db/ref"
       :seon.wake/listen true
       :seon.wake/opens-turn? true}]}
    (fn [connection]
      (is (= ::wake/unindexed-listened-attribute
             (:seon.error/kind
              (wake/declarations-refusal (db/db connection))))
          "the derivation names the attribute the index does not hold")
      (is (contains? (wake/unindexed-listened-attributes (db/db connection))
                     ::unindexed-notice))
      (let [refusal (try (route-probe! connection
                                       (async/chan (async/sliding-buffer 1)))
                         (catch clojure.lang.ExceptionInfo failure
                           (ex-data failure)))]
        (is (= ::wake/unindexed-listened-attribute
               (:seon.error/kind refusal))
            "and registration refuses rather than routing into silence")))))

(deftest a-fault-wakes-the-steward-of-the-failing-functions-namespace
  (test-support/with-database
    (fn [connection]
      (test-support/transacted! connection [{:seon.agent/id "agent-a"}
                                           {:seon.ns/name 'my.agents.agent-a
                                            :seon.ns/steward [:seon.agent/id "agent-a"]}])
      (test-support/transacted!
       connection
       [(test-support/program-fn-row (db/db connection) 'my.agents.agent-a/broken
                                     "(defn broken [] true)")])
      (let [mailbox (async/chan (async/sliding-buffer 1))
            {:keys [key]} (route-probe! connection mailbox)
            recording (error/recording
                       (db/db connection)
                       {:seon.error/source
                        {:seon.error/kind :seon.instrument/contract-violated
                         :seon.error/message "the function violated its contract"
                         :seon.error/data {:seon.instrument/fn 'my.agents.agent-a/broken
                                           :seon.instrument/arm :input}}
                        :seon.error/id "fault-wake" :seon.error/at (Date.)
                        :seon.error/process "wake-test-process"
                        :seon.sci.admit/caps result-caps
                        :seon.config.error/max-evidence-bytes 4096
                        :seon.config.error/recurrence-limit 3
                        :seon.agent/id "agent-a"})]
        (try
          (is (:db-after (db/transact! connection (:seon.db/tx-data recording))))
          (let [fact (db/pull (db/db connection) '[*] (:seon.error/ref recording))]
            (is (= "agent-a" (error/steward (db/db connection) fact)))
            (is (nil? (:seon.error/steward fact))))
          (is (some? (test-support/await-event! mailbox "derived steward notification")))
          (is (contains? (wake/inside-attributes (db/db connection)) :seon.message/from))
          (is (not (contains? (wake/inside-attributes (db/db connection)) :seon.message/about)))
          (is (not (contains? (wake/wake-attributes (db/db connection)) :seon.error/steward)))
          (finally
            (wake/unlisten! {:seon.cluster.wake/connection connection :seon.cluster.wake/key key})
            (async/close! mailbox)))))))

(deftest render-wake-intersects-the-current-published-interest
  (with-connection
    (fn [connection]
      (let [mailbox (async/chan (async/sliding-buffer 1))
            interest (atom #{:seon.message/content})
            counting-render (async/chan 8)
            {:keys [render key]}
            (route-probe! connection mailbox (fn [_ _] false)
                          interest counting-render)]
        (try
          (testing "an irrelevant commit completes without a render wake"
            (test-support/transacted! connection (run-tx "irrelevant-run"))
            ;; Datahike delivers the transaction report only after the
            ;; listener returns. `poll!` is therefore an ordering assertion,
            ;; not a timing verdict about a possibly pending callback.
            (is (nil? (async/poll! render))))
          (testing "one intersecting report offers exactly one wake"
            (test-support/transacted! connection (message-tx "relevant-message"))
            (is (some? (test-support/await-event! render "render wake")))
            (is (nil? (async/poll! render))
                "several matching datoms still produce one report wake"))
          (testing "the cold `:all` sentinel accepts every changed attribute"
            (reset! interest :all)
            (test-support/transacted! connection (run-tx "cold-run"))
            (is (some? (test-support/await-event! render
                                                   "cold render wake")))
            (is (nil? (async/poll! render))))
          (finally
            (wake/unlisten! {:seon.cluster.wake/connection connection
                             :seon.cluster.wake/key key})))))))

(deftest a-saturated-mailbox-drops-and-never-parks
  ;; the handler runs on the committing caller's critical path: probe B
  ;; measured an 800 ms listener adding 804 ms to `transact`
  (with-connection
    (fn [connection]
      (let [mailbox (async/chan (async/sliding-buffer 1))
            {:keys [key]} (route-probe! connection mailbox)]
        (try
          ;; Nobody is reading while the transactions run. Completion
          ;; of the returned Future is the proof that the listener did
          ;; not park the writer; no elapsed-time threshold is involved.
          (let [committed
                (future
                  (mapv (fn [n]
                          (db/transact! connection
                                      (message-tx (str "m-" n))))
                        (range 5)))]
            (is (= 5
                   (count
                    (test-support/await-event!
                     committed "saturated-listener transactions")))))
          (is (some? (async/poll! mailbox))
              "and the newest wake is still there — sliding, not blocking")
          (finally
            (wake/unlisten! {:seon.cluster.wake/connection connection
                             :seon.cluster.wake/key key})))))))

(deftest delivery-requires-both-a-closed-offer-and-the-derived-fence
  ;; `offer!` establishes transport state; the route owner establishes
  ;; lifecycle meaning. Closedness alone cannot turn a broken render
  ;; route into an intentional agent fence.
  (let [closed (async/chan 1)
        full (async/chan 1)
        open (async/chan 1)
        sliding (async/chan (async/sliding-buffer 1))]
    (async/close! closed)
    (async/offer! full :fill)
    (async/offer! sliding :fill)
    (is (= :seon.cluster.wake/fenced
           (wake/delivery (async/offer! closed :wake) true))
        "a closed, still-routed agent mailbox is the fence")
    (is (= :seon.cluster.wake/refused
           (wake/delivery false false))
        "the same closed transport without a derived fence stays loud")
    (is (= :seon.cluster.wake/refused
           (wake/delivery (async/offer! full :wake) false))
        "a LIVE route that cannot take it is the loud case")
    (is (= :seon.cluster.wake/delivered
           (wake/delivery (async/offer! open :wake) false)))
    (is (= :seon.cluster.wake/delivered
           (wake/delivery (async/offer! sliding :wake) false))
        "a sliding buffer is never full, so `refused` cannot arise on a
         mailbox and the classification is total there")))

(deftest a-fenced-mailbox-is-recognized-and-never-hangs-the-writer
  ;; THE hazard is unchanged: datahike fires listeners inside the
  ;; transaction's go block BEFORE (deliver p tx-report)
  ;; (writer.cljc:384-386), so an escaping exception means the
  ;; committing caller waits forever. What CHANGED is the verdict on a
  ;; closed mailbox. `seon.turn`'s terminal settlement fence
  ;; closes an agent's mailbox in place so it takes no further pass over
  ;; a still-running receipt; committing that very fault also commits
  ;; its explanation message, so routing used to turn the quarantine
  ;; into a fresh core fault about itself. It is a lifecycle state, and
  ;; the router now says so.
  (with-connection
    (fn [connection]
      (let [recipient-eid (agent-eid connection)
            mailbox (async/chan (async/sliding-buffer 1))
            {:keys [faults key]}
            (route-probe!
             connection mailbox
             (fn [recipient channel]
               (and (= recipient recipient-eid)
                    (identical? channel mailbox)
                    (async.protocols/closed? channel))))]
        (try
          (async/close! mailbox)
          (let [committed (future (db/transact! connection (message-tx "m-9")))]
            (is (map? (test-support/await-event!
                       committed "fenced-mailbox transaction"))
                "the committing caller returned — the handler swallowed
                 its own failure instead of hanging the writer forever")
            ;; The listener runs to completion INSIDE the transaction
            ;; before the caller is delivered, so once the future has a
            ;; report the handler has already decided. `poll!` is an
            ;; ordering fact here, not a race with a sleep in it.
            (is (nil? (async/poll! faults))
                "and the fence produced NO core fault about itself"))
          (is (= ["m-9"]
                 (db/q '[:find [?id ...]
                        :where [_ :seon.message/id ?id]]
                      @connection))
              "the message stays a durable fact — the fresh mailbox
               `arm!` builds after recovery derives it from facts")
          (finally
            (wake/unlisten! {:seon.cluster.wake/connection connection
                             :seon.cluster.wake/key key})))))))

(deftest a-live-but-non-accepting-route-still-faults-loudly
  ;; The half that must NOT go quiet. A route that is open and cannot
  ;; take the wake is losing a wake nobody will re-derive, which is the
  ;; invisible-failure class the fault fact exists for.
  (with-connection
    (fn [connection]
      (let [mailbox (async/chan 1)
            {:keys [faults key]} (route-probe! connection mailbox)]
        (try
          ;; fixed-1, saturated and unread: the next offer! must park,
          ;; so it returns nil rather than false
          (async/offer! mailbox ::filler)
          (let [committed (future (db/transact! connection (message-tx "m-f")))]
            (is (map? (test-support/await-event!
                       committed "saturated-route transaction")))
            (let [fault (test-support/await-event! faults "route fault")]
              (is (= :seon.cluster.wake/undeliverable-wake
                     (:seon.error/kind (ex-data fault)))
                  "one classifier, one kind — no second fault path")
              (is (= :seon.cluster.wake/mailbox
                     (:seon.cluster.wake/route (ex-data fault)))
                  "and it names WHICH route, so the fault is actionable")))
          (finally
            (wake/unlisten! {:seon.cluster.wake/connection connection
                             :seon.cluster.wake/key key})))))))

(deftest a-closed-render-channel-delivers-a-fault
  ;; Production shutdown unlistens before closing render. A closed
  ;; render route while this listener is live is therefore a broken
  ;; delivery, not an agent quarantine.
  (with-connection
    (fn [connection]
      (let [mailbox (async/chan (async/sliding-buffer 1))
            {:keys [render faults key]} (route-probe! connection mailbox)]
        (try
          (async/close! render)
          (let [committed (future (db/transact! connection (message-tx "m-r")))]
            (is (map? (test-support/await-event!
                       committed "closed-render transaction"))
                "the writer still returned")
            (let [fault (test-support/await-event! faults "render fault")]
              (is (= :seon.cluster.wake/undeliverable-wake
                     (:seon.error/kind (ex-data fault))))
              (is (= :seon.cluster.wake/render
                     (:seon.cluster.wake/route (ex-data fault))))))
          (finally
            (wake/unlisten! {:seon.cluster.wake/connection connection
                             :seon.cluster.wake/key key})))))))

;;; ---------------------------------------------------------------------------
;;; The F2 sealed suite — route-render-wake-and-disjointness-property
;;; seed 2026072826
;;; ---------------------------------------------------------------------------

(deftest route-render-wake-and-disjointness-property
  ;; ORACLE: over generated commit batches — cold `:all` interest makes
  ;; the render channel receive a wake for every report; mailbox routing
  ;; is unchanged by the added delivery; and the C2 property holds over
  ;; the re-grounded COMPUTED sets, with the message/to delivery asserted
  ;; from the other direction (an attribute that routes to a mailbox is
  ;; one no turn commits).
  (let [check
        (tc/quick-check
         50
         (prop/for-all
          [commits (gen/vector (gen/elements [:message :agent :run]) 1 8)]
          (test-support/with-database
            (fn [connection]
              (test-support/transacted! connection [{:seon.agent/id "agent-a"}])
              (let [recipient-eid (agent-eid connection)
                    mailbox (async/chan 64)
                    channels {recipient-eid mailbox}
                    armer (async/chan 64)
                    ;; a COUNTING render channel: production slides,
                    ;; because a wake says only "look" and coalescing is
                    ;; free — here every delivery is kept so the
                    ;; per-report claim can be counted at all
                    render (async/chan 256)
                    faults (async/chan (async/sliding-buffer 1))
                    key (wake/route!
                         {:seon.cluster.wake/connection connection
                          :seon.cluster.wake/channels
                          (constantly channels)
                          :seon.cluster.wake/fenced? (fn [_ _] false)
                          :seon.cluster.wake/armer-channel armer
                          :seon.cluster.wake/render-channel render
                          :seon.render.web/interest (atom :all)
                          :seon.cluster.wake/fault-channel faults
                          :seon.cluster.wake/key ::property})]
                (try
                  (doseq [[commit index] (map vector commits (range))]
                    (case commit
                      :message (db/transact! connection (message-tx
                                                       (str "pm-" index)))
                      ;; a bare agent creation asserts its ARMING
                      ;; attribute: it routes to nobody's mailbox and
                      ;; offers the armer exactly one wake
                      :agent (db/transact! connection
                                         [{:seon.agent/id
                                           (str "pa-" index)}])
                      :run (db/transact! connection (run-tx
                                                   (str "pr-" index)))))
                  (let [drain (fn [channel]
                                (loop [n 0]
                                  (if (async/poll! channel)
                                    (recur (inc n))
                                    n)))
                        rendered (drain render)
                        mailed (drain mailbox)
                        armed (drain armer)]
                    (and
                     ;; one render wake per REPORT, every report
                     (= (count commits) rendered)
                     ;; routing is unchanged by the added delivery
                     (= (count (filter #{:message} commits)) mailed)
                     ;; and the armer hears every agent creation, and
                     ;; nothing else: a turn's own commits reach neither
                     (= (count (filter #{:agent} commits)) armed)
                     ;; and no fault was raised on any healthy path
                     (nil? (async/poll! faults))))
                  (finally
                    (wake/unlisten!
                     {:seon.cluster.wake/connection connection
                      :seon.cluster.wake/key key})))))))
         :seed 2026072826)]
    (is (true? (:result check))
        (str "routing/render-wake property failed: " (pr-str check))))

  (testing "C2 from the other direction: every attribute that routes to
            a mailbox is one no turn commits"
    (with-connection
      (fn [connection]
        (let [wakes (wake/wake-attributes (db/db connection))
              commits (turn/committed-attributes (seon.schema/handed-projection))]
          (is (every? (fn [attribute] (not (contains? commits attribute)))
                      wakes))
          (is (every? (fn [attribute] (not (contains? wakes attribute)))
                      commits)))))))

(deftest unlisten-is-idempotent-and-stops-delivery
  (with-connection
    (fn [connection]
      (let [mailbox (async/chan (async/sliding-buffer 1))
            {:keys [key]} (route-probe! connection mailbox)
            request {:seon.cluster.wake/connection connection
                     :seon.cluster.wake/key key}]
        (is (nil? (wake/unlisten! request)))
        (is (nil? (wake/unlisten! request)) "removing an absent listener
                                             is a no-op, because stop may
                                             arrive after a release")
        (test-support/transacted! connection (message-tx "m-after"))
        (is (nil? (async/poll! mailbox))
            "nothing is delivered after unlisten")))))

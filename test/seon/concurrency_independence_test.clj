(ns ^{:seon.test/long
      "136.721 s pool: one real cluster folds 5- and 10-agent plans concurrently and verifies every receipt."}
  seon.concurrency-independence-test
  "Fact-space proof that many agents independently fold on one branch.

  Every scenario creates all agents and caller-provided plans in one
  transaction. The live armer then runs the model-free plans through the
  production per-agent graphs. Assertions read receipts, custody history,
  program rows, message connections, transcript inputs, and committed test
  rows from the database; logs are never an oracle."
  (:require [clojure.core.async :as async]
            [clojure.core.async.flow :as flow]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.ai :as ai]
            [seon.bootstrap :as bootstrap]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.cluster.loop :as loop]
            [seon.cluster.run :as run]
            [seon.cluster.work :as work]
            [seon.config :as config]
            [seon.db :as db]
            [seon.eval.drive :as drive]
            [seon.render.transcript :as transcript]
            [seon.schema :as schema]
            [seon.test-support :as test-support])
  (:import [java.util Date UUID]
           [java.util.concurrent CountDownLatch]))

(set! *warn-on-reflection* true)

(def ^:private rows-per-agent 3)
(def ^:private form-count 6)
(def ^:private send-ordinal 4)
(def ^:private test-git-sha (apply str (repeat 40 "a")))
(def ^:private scenario-sizes [5 5 5 10 10 10])

(defn- digest-value
  [value]
  (schema/sha-256 [(.getBytes (pr-str value) "UTF-8")]))

(defn- await-channel!
  "Return the next published value, or refuse a closed event source."
  [event-source event]
  (or (async/<!! event-source)
      (throw
       (ex-info "The test channel closed before its required event."
                {::event event}))))

(defn- await-fact
  "Return the first truthy value `probe` derives from a database value."
  [connection probe]
  (let [events (async/promise-chan)
        listener-key (keyword (str (ns-name *ns*))
                              (str (UUID/randomUUID)))]
    (d/listen connection listener-key
              (fn [report]
                (when-let [value (probe (:db-after report))]
                  (async/offer! events value))))
    (try
      ;; Register interest before deriving current state. A commit between
      ;; those operations is necessarily observed on one side.
      (when-let [value (probe @connection)]
        (async/offer! events value))
      (await-channel! events "database fact")
      (finally
        (d/unlisten connection listener-key)))))

(defn- await-bootstrap
  [connection]
  (await-fact
   connection
   (fn [database]
     (db/q '[:find ?closed-at .
             :in $ ?run-id
             :where
             [?run :seon.cluster.run/id ?run-id]
             [?run :seon.cluster.run/closed-at ?closed-at]]
           database (bootstrap/run-id "root")))))

(defn- with-cluster
  [body]
  (let [root "tmp/concurrency-independence-test"
        cluster-name "concurrency-independence"]
    (test-support/delete-recursively! root)
    (test-support/populate-published-root! root)
    (let [instance (cluster/start! {:seon.boot/cluster-name cluster-name
                                    :seon.boot/root root})]
      (try
        (await-bootstrap (:seon.boot/cluster-connection instance))
        (body instance)
        (finally
          (cluster/stop! instance))))))

(defn- initial-spec
  [scenario index now]
  (let [agent-id (str "stress-" scenario "-agent-" index)
        namespace-name
        (symbol (str "my.agents.concurrency." scenario ".a" index))
        run-id (str "stress-" scenario "-run-" index)
        function-name (symbol (str "stress-f-" scenario "-" index))
        row-ids (mapv #(str "stress-" scenario "-row-" index "-" %)
                      (range rows-per-agent))
        rows (mapv (fn [row-id]
                     {:seon.test.run/id row-id
                      :seon.test.run/at now
                      :seon.test.run/git-sha test-git-sha})
                   row-ids)]
    {::agent-id agent-id
     ::namespace namespace-name
     ::run-id run-id
     ::function-name function-name
     ::function-qualified (str namespace-name "/" function-name)
     ::row-ids row-ids
     ::rows rows
     ::outbound-message-id
     (str run-id "-" send-ordinal "-message-0")}))

(defn- plan-sources
  [spec]
  (let [namespace-name (::namespace spec)
        agent-id (::agent-id spec)
        next-agent-id (::next-agent-id spec)
        function-name (::function-name spec)
        row-ids (::row-ids spec)
        row-transaction
        {:tx-data (::rows spec)
         :tx-meta {:seon.db/user [:seon.cluster.agent/id agent-id]}}
        sources
        [(str "(seon.db/transact! " (pr-str row-transaction) ")")
         (str "(seon.db/q "
              "'[:find [?id ...] :in $ [?id ...] "
              ":where [_ :seon.test.run/id ?id]] "
              (pr-str row-ids) ")")
         (str "(defn ^{:malli/schema [:=> [:cat :int] :int]} "
              function-name " [x] (+ x " (+ 1000 (::index spec)) "))")
         (str "(" function-name " 1)")
         (str "(my.message/send " (pr-str next-agent-id) " "
              (pr-str (::payload spec)) ")")
         (str "(my.run/complete "
              (pr-str (str "complete|" agent-id "|")) ")")]]
    (mapv (fn [source]
            {:seon.ns/name namespace-name
             :seon.cluster.run.form/source source})
          sources)))

(defn- scenario-specs
  [scenario agent-count now]
  (let [initial (mapv #(assoc (initial-spec scenario % now) ::index %)
                      (range agent-count))]
    (mapv
     (fn [index spec]
       (let [next-spec (nth initial (mod (inc index) agent-count))
             previous-spec (nth initial (mod (dec index) agent-count))
             payload (str "ring|" scenario "|" (::agent-id spec)
                          "|to|" (::agent-id next-spec) "|")
             planned (assoc spec
                            ::next-agent-id (::agent-id next-spec)
                            ::incoming-message-id
                            (::outbound-message-id previous-spec)
                            ::payload payload)]
         (assoc planned ::sources (plan-sources planned))))
     (range agent-count)
     initial)))

(defn- create-scenario-agents!
  [instance specs]
  (let [connection (:seon.boot/cluster-connection instance)
        cluster-name (get-in instance [:seon.boot/config
                                       :seon.boot/cluster-name])
        creation-tx
        (mapcat (fn [spec]
                  (agent/creation-tx
                   {:seon.cluster.agent/id (::agent-id spec)
                    :seon.ns/name (::namespace spec)
                    :seon.cluster/name cluster-name}))
                specs)
        result
        (db/transact!
         connection
         {:tx-data (into [] creation-tx)})]
    (is (not (:seon.error/kind result))
        (str "agent creation was refused: " (pr-str result)))
    result))

(defn- pause-scenario-mailboxes!
  [instance specs]
  (let [handle (:seon.cluster.loop/cluster instance)
        routing (:seon.cluster.agent/routing instance)
        quiesced (async/promise-chan)]
    ;; The armer channel orders this request after the creation wake. Its
    ;; acknowledgement therefore proves every scenario agent has an entry.
    (async/>!! (:seon.cluster.wake/channel handle)
               {::agent/quiesce quiesced})
    (await-channel! quiesced "scenario agents armed")
    (doseq [spec specs]
      (let [entry (agent/armed routing (::agent-id spec))
            graph (:seon.flow/graph entry)]
        (is (some? entry) "the armer published the scenario agent")
        (flow/pause-proc graph ::agent/mailbox)
        (is (= :paused
               (::flow/status (flow/ping-proc graph ::agent/mailbox)))
            "the mailbox is paused before planned work can deliver a wake")))))

(defn- seed-scenario-runs!
  [instance specs]
  (let [connection (:seon.boot/cluster-connection instance)
        database @connection
        process (get-in instance [:seon.cluster.loop/cluster
                                  :seon.cluster.run/process])
        now (Date.)
        run-tx
        (mapcat
         (fn [spec]
           (run/system-run-tx
            database
            {:seon.cluster.agent/id (::agent-id spec)
             :seon.cluster.run/id (::run-id spec)
             :seon.cluster.run/process process
             :seon.cluster.run/opened-at now
             :seon.cluster.run/starting-ns
             [:seon.ns/name (::namespace spec)]
             :seon.cluster.run/plan-digest
             (digest-value (::sources spec))
             :seon.cluster.run/sources (::sources spec)}))
         specs)
        result
        (db/transact!
         connection
         {:tx-data (into [] run-tx)
          :tx-meta {:seon.db/process
                    [:seon.db.process/id process]}})]
    (is (not (:seon.error/kind result))
        (str "scenario run transaction was refused: " (pr-str result)))
    result))

(defn- fold-scenario-runs!
  [instance specs]
  (let [connection (:seon.boot/cluster-connection instance)
        handle (:seon.cluster.loop/cluster instance)
        process (:seon.cluster.run/process handle)
        work-items
        (mapv
         (fn [spec]
           (let [work-item (work/next-agent-work
                            @connection
                            {:seon.cluster.agent/id (::agent-id spec)
                             :seon.cluster.run/process process})]
             (is (= :resume (:seon.cluster.work/situation work-item))
                 "a caller-planned run begins at the resume boundary")
             work-item))
         specs)
        start (CountDownLatch. 1)
        completed (async/chan (count work-items))]
    (doseq [work-item work-items]
      (future
        (.await start)
        (async/>!!
         completed
         (try
           {:seon.cluster.loop/report
            (loop/turn {:seon.cluster.loop/cluster handle
                        :seon.cluster.work/next work-item}
                       (Date.))}
           (catch Throwable failure
             {:seon.cluster.loop/failure failure})))))
    (.countDown start)
    (dotimes [_ (count work-items)]
      (let [outcome
            (await-channel! completed "concurrent planned fold")
            report (:seon.cluster.loop/report outcome)]
        (is (nil? (:seon.cluster.loop/failure outcome))
            (some-> (:seon.cluster.loop/failure outcome) Throwable->map pr-str))
        (is (= :closed (:seon.cluster.loop/outcome report)))
        (is (= form-count (:seon.cluster.loop/forms-run report)))))))

(defn- await-runs-closed
  [connection run-ids]
  (await-fact
   connection
   (fn [database]
     (let [closed
           (db/q '[:find [?run-id ...]
                   :in $ [?run-id ...]
                   :where
                   [?run :seon.cluster.run/id ?run-id]
                   [?run :seon.cluster.run/closed-at _]]
                 database run-ids)]
       (when (= (set run-ids) (set closed))
         closed)))))

(defn- receipt-rows
  [database run-ids]
  (db/q '[:find ?receipt-id ?run-id ?agent-id ?ordinal
          :in $ [?run-id ...]
          :where
          [?run :seon.cluster.run/id ?run-id]
          [?run :seon.cluster.run/agent ?agent]
          [?agent :seon.cluster.agent/id ?agent-id]
          [?receipt :seon.cluster.eval/run ?run]
          [?receipt :seon.cluster.eval/id ?receipt-id]
          [?receipt :seon.cluster.eval/ordinal ?ordinal]]
        database run-ids))

(def ^:private receipt-failure-attributes
  [:seon.cluster.eval/error
   :seon.error/kind
   :seon.cluster.eval/interrupted-at])

(defn- receipt-failures
  [database run-ids]
  (into
   #{}
   (mapcat
    (fn [attribute]
      (map (fn [[run-id ordinal value]]
             [run-id ordinal attribute value])
           (db/q '[:find ?run-id ?ordinal ?value
                   :in $ [?run-id ...] ?attribute
                   :where
                   [?run :seon.cluster.run/id ?run-id]
                   [?receipt :seon.cluster.eval/run ?run]
                   [?receipt :seon.cluster.eval/ordinal ?ordinal]
                   [?receipt ?attribute ?value]]
                 database run-ids attribute)))
    receipt-failure-attributes)))

(defn- assert-receipts!
  [database specs]
  (let [run-ids (mapv ::run-id specs)
        actual (set (receipt-rows database run-ids))
        expected
        (into #{}
              (mapcat
               (fn [spec]
                 (map (fn [ordinal]
                        [(pr-str [(::run-id spec) ordinal])
                         (::run-id spec)
                         (::agent-id spec)
                         ordinal])
                      (range form-count))))
              specs)
        failures (receipt-failures database run-ids)]
    (is (= expected actual)
        "every receipt identity maps to exactly its owning agent's run")
    (is (empty? failures)
        (str "all receipts must settle successfully: " (pr-str failures)))))

(defn- assert-custody!
  [database process specs]
  (let [run-ids (mapv ::run-id specs)
        custody
        (db/q '[:find ?run-id ?holder
                :in $ [?run-id ...]
                :where
                [?run :seon.cluster.run/id ?run-id]
                [?run :seon.cluster.run/process ?holder _ true]]
              (db/history database) run-ids)
        holders-by-run
        (reduce (fn [result [run-id holder]]
                  (update result run-id (fnil conj #{}) holder))
                {}
                custody)]
    (doseq [spec specs]
      (let [run-record
            (db/pull database
                     [:seon.cluster.run/id
                      :seon.cluster.run/process
                      :seon.cluster.run/closed-at]
                     [:seon.cluster.run/id (::run-id spec)])]
        (is (= #{process} (get holders-by-run (::run-id spec)))
            "one process held the run throughout its complete history")
        (is (some? (:seon.cluster.run/closed-at run-record)))
        (is (nil? (:seon.cluster.run/process run-record))
            "closed custody is retracted rather than left dangling")))))

(defn- assert-concurrent-progress!
  [database specs]
  (let [run-ids (mapv ::run-id specs)
        rows
        (db/q '[:find ?run-id ?receipt-tx ?close-tx
                :in $ [?run-id ...]
                :where
                [?run :seon.cluster.run/id ?run-id]
                [?run :seon.cluster.run/closed-at _ ?close-tx]
                [?receipt :seon.cluster.eval/run ?run]
                [?receipt :seon.cluster.eval/result-edn _ ?receipt-tx]]
              database run-ids)
        first-close (apply min (map #(nth % 2) rows))
        begun-before-first-close
        (into #{}
              (comp (filter #(< (nth % 1) first-close))
                    (map first))
              rows)]
    (is (= (set run-ids) begun-before-first-close)
        "facts show every run committed progress before the first run closed")))

(defn- assert-owned-rows!
  [database specs]
  (let [row-ids (into [] (mapcat ::row-ids) specs)
        actual
        (db/q '[:find ?row-id ?agent-id
                :in $ [?row-id ...]
                :where
                [?row :seon.test.run/id ?row-id ?tx true]
                [?tx :seon.db/user ?agent ?tx true]
                [?agent :seon.cluster.agent/id ?agent-id]]
              (db/history database) row-ids)
        expected
        (into #{}
              (mapcat (fn [spec]
                        (map (fn [row-id]
                               [row-id (::agent-id spec)])
                             (::row-ids spec))))
              specs)]
    (is (= expected (set actual))
        "every committed stress row carries its owning agent as tx provenance")
    (is (= (* rows-per-agent (count specs)) (count actual))
        "the one writer lost none of the concurrent agent transactions")))

(defn- assert-rows!
  [database specs]
  (doseq [spec specs]
    (let [definition-source
          (:seon.cluster.run.form/source (nth (::sources spec) 2))
          row
          (db/pull database
                   [:seon.fn/sym
                    :seon.fn/source
                    :seon.fn/spec
                    :seon.schema.admission/source
                    {:seon.fn/ns [:seon.ns/name]}]
                   [:seon.fn/sym (::function-qualified spec)])]
      (is (= (::function-qualified spec) (:seon.fn/sym row)))
      (is (= (::namespace spec)
             (get-in row [:seon.fn/ns :seon.ns/name])))
      (is (= definition-source (:seon.fn/source row)))
      (is (= :agent (:seon.schema.admission/source row)))
      (is (string? (:seon.fn/spec row))
          "the durable function retained its declared contract"))))

(defn- ring-facts
  [database specs]
  (let [message-ids (mapv ::outbound-message-id specs)]
    (db/q '[:find ?message-id ?from-id ?to-id
            :in $ [?message-id ...]
            :where
            [?message :seon.cluster.message/id ?message-id]
            [?message :seon.cluster.message/from ?from]
            [?from :seon.cluster.agent/id ?from-id]
            [?message :seon.cluster.message/to ?to]
            [?to :seon.cluster.agent/id ?to-id]]
          database message-ids)))

(defn- assert-ring!
  [database specs]
  (let [actual (set (ring-facts database specs))
        expected
        (into #{}
              (map (fn [spec]
                     [(::outbound-message-id spec)
                      (::agent-id spec)
                      (::next-agent-id spec)]))
              specs)]
    (is (= expected actual)
        "all N triggerless ring messages are delivered exactly once")
    (doseq [spec specs]
      (is (= #{(::incoming-message-id spec)}
             (into #{}
                   (map :seon.cluster.message/id)
                   (work/unanswered-triggers database (::agent-id spec))))
          "the paused mailbox leaves exactly the declared incoming ring message"))))

(defn- agent-message-ids
  [database agent-id]
  (set
   (db/q '[:find [?message-id ...]
           :in $ ?agent-id
           :where
           [?agent :seon.cluster.agent/id ?agent-id]
           (or-join [?message ?agent]
                    [?message :seon.cluster.message/to ?agent]
                    [?message :seon.cluster.message/from ?agent])
           [?message :seon.cluster.message/id ?message-id]]
         database agent-id)))

(defn- render-transcript
  [instance database agent-id]
  (let [cluster-name (get-in instance [:seon.boot/config
                                       :seon.boot/cluster-name])
        settings (config/effective database cluster-name)]
    (transcript/render-ai
     {:seon.db/db database
      :seon.db/connection
      (:seon.boot/cluster-connection instance)
      :seon.sci.eval/ctx (:seon.sci.eval/ctx instance)
      :seon.cluster.agent/id agent-id
      :seon.sci.admit/caps (config/result-caps settings)
      :seon.sci.eval/time-limit-ms
      (:seon.config.eval/time-limit-ms settings)
      :seon.config/on-core-error (:seon.config/on-core-error settings)
      ::transcript/token-budget 1000000000})))

(defn- assert-transcripts!
  [instance database specs]
  (doseq [spec specs]
    (let [incoming
          (first (filter #(= (::outbound-message-id %)
                            (::incoming-message-id spec))
                         specs))
          expected-message-ids
          #{(::outbound-message-id spec) (::incoming-message-id spec)}
          rendered (render-transcript instance database (::agent-id spec))
          foreign-specs
          (remove #(= (::agent-id %) (::agent-id spec)) specs)]
      (is (= expected-message-ids
             (agent-message-ids database (::agent-id spec)))
          "the transcript message input is exactly the incoming/outgoing ring pair")
      (doseq [source (map :seon.cluster.run.form/source (::sources spec))]
        (is (str/includes? rendered source)
            "the transcript includes every form belonging to its own run"))
      (is (str/includes? rendered (::payload spec)))
      (is (str/includes? rendered (::payload incoming)))
      (doseq [foreign foreign-specs]
        (is (not (str/includes? rendered (::function-qualified foreign)))
            "another agent's contracted definition never enters this transcript")
        (doseq [row-id (::row-ids foreign)]
          (is (not (str/includes? rendered row-id))
              "another agent's committed rows never enter this transcript"))
        (when-not (contains? #{(::agent-id spec) (::agent-id incoming)}
                             (::agent-id foreign))
          (is (not (str/includes? rendered (::payload foreign)))
              "unrelated ring traffic never enters this transcript"))))))

(defn- assert-plan-results!
  [database specs]
  (let [receipts-by-run
        (group-by :seon.cluster.run/id
                  (drive/run-receipts database (mapv ::run-id specs)))]
    (doseq [spec specs]
      (let [by-ordinal
            (into {}
                  (map (juxt :seon.cluster.eval/ordinal identity))
                  (get receipts-by-run (::run-id spec)))
            query-result
            (:seon.eval.drive/value (get by-ordinal 1))
            call-result
            (:seon.eval.drive/value (get by-ordinal 3))
            completion
            (:seon.eval.drive/value (get by-ordinal 5))]
        (is (= (set (::row-ids spec)) (set query-result))
            "each agent queried exactly the rows it had just committed")
        (is (= (+ 1001 (::index spec)) call-result)
            "the contracted function call used this agent's own definition")
        (is (= {:my.run/disposition :completed
                :my.run/result (str "complete|" (::agent-id spec) "|")}
               completion))))))

(defn- run-scenario!
  [instance scenario agent-count]
  (let [connection (:seon.boot/cluster-connection instance)
        specs (scenario-specs scenario agent-count (Date.))
        run-ids (mapv ::run-id specs)
        process (get-in instance [:seon.cluster.loop/cluster
                                  :seon.cluster.run/process])
        started (System/nanoTime)]
    (create-scenario-agents! instance specs)
    (pause-scenario-mailboxes! instance specs)
    (seed-scenario-runs! instance specs)
    (fold-scenario-runs! instance specs)
    (await-runs-closed connection run-ids)
    (let [elapsed-ms (/ (double (- (System/nanoTime) started)) 1000000.0)
          database @connection]
      (testing (str scenario " with N=" agent-count)
        (assert-receipts! database specs)
        (assert-custody! database process specs)
        (assert-concurrent-progress! database specs)
        (assert-owned-rows! database specs)
        (assert-rows! database specs)
        (assert-ring! database specs)
        (assert-transcripts! instance database specs)
        (assert-plan-results! database specs))
      {::scenario scenario
       ::agent-count agent-count
       ::elapsed-ms elapsed-ms})))

(deftest receipt-diagnostic-selects-only-present-failure-facts
  (test-support/with-database
    (fn [connection]
      (let [run-id "receipt-diagnostic-run"
            interrupted-at (Date.)]
        (db/transact!
         connection
         [{:seon.cluster.run/id run-id}
          {:seon.cluster.eval/id (pr-str [run-id 0])
           :seon.cluster.eval/run [:seon.cluster.run/id run-id]
           :seon.cluster.eval/ordinal 0
           :seon.cluster.eval/result-edn "42"}
          {:seon.cluster.eval/id (pr-str [run-id 1])
           :seon.cluster.eval/run [:seon.cluster.run/id run-id]
           :seon.cluster.eval/ordinal 1
           :seon.cluster.eval/error "failed"}
          {:seon.cluster.eval/id (pr-str [run-id 2])
           :seon.cluster.eval/run [:seon.cluster.run/id run-id]
           :seon.cluster.eval/ordinal 2
           :seon.error/kind :user-input}
          {:seon.cluster.eval/id (pr-str [run-id 3])
           :seon.cluster.eval/run [:seon.cluster.run/id run-id]
           :seon.cluster.eval/ordinal 3
           :seon.cluster.eval/interrupted-at interrupted-at}])
        (is (= #{[run-id 1 :seon.cluster.eval/error "failed"]
                 [run-id 2 :seon.error/kind :user-input]
                 [run-id 3 :seon.cluster.eval/interrupted-at interrupted-at]}
               (receipt-failures @connection [run-id])))))))

(deftest n-agents-fold-independently-on-one-live-cluster
  (let [model-calls (atom [])]
    (with-redefs [ai/complete
                  (fn [request]
                    (swap! model-calls conj request)
                    {:seon.ai/text
                     "(my.run/complete \"unexpected model call\")"})]
      (with-cluster
        (fn [instance]
          (let [timings
                (mapv (fn [index agent-count]
                        (run-scenario! instance
                                       (str "s" index "-n" agent-count)
                                       agent-count))
                      (range)
                      scenario-sizes)]
            (is (empty? @model-calls)
                "all work came from system-authored plans, never a provider")
            (println "concurrency-independence timings"
                     (pr-str timings))))))))

(ns seon.db-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as d]
            [datahike.pull-api :as pull-api]
            [seon.cluster.message :as message]
            [seon.config :as config]
            [seon.turn :as turn]
            [seon.db :as db]
            [seon.instrument :as instrument]
            [seon.render :as render]
            [seon.render.value :as render.value]
            [seon.sci.admit :as admit]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.test-support :as test-support]))

(def ^:private schema-delta (schema/begin-registration-delta))

(schema/call-with-registration-delta
 schema-delta
 {:seon.schema.admission/source :core}
 (fn []
   (schema/register! ::ai-declaration
                     [:and {:seon.db/index true}
                      [:or :string :qualified-symbol]])
   (schema/register! ::html-declaration
                     [:or [:vector :any] :qualified-symbol])
   (schema/register! ::row-id [:string {:seon.db/identity true}])
   (schema/register! ::component-root-id [:string {:seon.db/identity true}])
   (schema/register! ::component-child [:and {:seon.db/component true} :seon.db/ref])
   (schema/register! ::component-value :string)))

(def ^:private fixture-projection
  (schema/build-projection
   @(:seon.schema.delta/candidate-forms schema-delta)))

(use-fixtures
 :each
 (fn [test-body]
   (schema/call-with-registration-delta
    schema-delta
    {:seon.schema.admission/source :core}
    #(schema/call-with-projection fixture-projection test-body))))

(def ^:private exam-query
  '[:find (count ?key) .
    :where
    [_ :seon.schema/key ?key]
    [(namespace ?key) ?namespace]
    [(= ?namespace "my.message")]])

(def ^:private schema-family-query
  '[:find [?key ...]
    :where
    [?reference :seon.schema/key :seon.message/id]
    [?schema :seon.schema/references ?reference]
    [?schema :seon.db/attributes true]
    [?schema :seon.schema/key ?key]])

(def ^:private uncached-schema-family-query
  '[:find [?key ...]
    :in $ ?sample
    :where
    [?reference :seon.schema/key :seon.message/id]
    [?schema :seon.schema/references ?reference]
    [?schema :seon.db/attributes true]
    [?schema :seon.schema/key ?key]])

(def ^:private projection-carrier-symbols
  '[*candidate-forms-overlay* *projection* *projection-state* *packaged-forms*])

(defn- without-handed-projection
  [thunk]
  (with-bindings
    (into {}
          (map (fn [sym] [(ns-resolve 'seon.schema sym) nil]))
          projection-carrier-symbols)
    (thunk)))

(defn- elapsed-nanos
  [thunk]
  (let [started (System/nanoTime)
        value (thunk)]
    {:seon.db-test/elapsed-nanos (- (System/nanoTime) started)
     :seon.db-test/value value}))

(deftest ten-unhanded-queries-stay-within-twice-raw-query-cost
  (test-support/with-database
   (fn [connection]
     (without-handed-projection
      (fn []
        (let [database @connection
              expected [:seon.message/message]
              _ (db/q schema-family-query database)
              raw
              (mapv
               (fn [sample]
                 (elapsed-nanos
                  #(d/q uncached-schema-family-query database sample)))
               (range 10))
              wrapped
              (mapv
               (fn [sample]
                 (elapsed-nanos
                  #(db/q uncached-schema-family-query database sample)))
               (range 10 20))
              raw-total (reduce + (map ::elapsed-nanos raw))
              wrapped-total (reduce + (map ::elapsed-nanos wrapped))]
          (is (every? #(= expected (::value %)) wrapped))
          (is (<= wrapped-total (* 2 raw-total))
              (str "ten seon.db/q calls took " wrapped-total
                   " ns versus " raw-total " ns raw"))))))))

(deftest handed-family-query-stays-below-five-milliseconds
  (test-support/with-database
   (fn [connection]
     ;; The canonical fixture binds the same cluster-owned projection-state
     ;; that seon.sci.eval binds around a door evaluation.
     (db/q schema-family-query @connection)
     (let [samples
           (mapv (fn [_]
                   (elapsed-nanos #(db/q schema-family-query @connection)))
                 (range 10))]
       (is (every? #(= [:seon.message/message] (::value %)) samples))
       (is (every? #(< (::elapsed-nanos %) 5000000) samples)
           (str "handed query samples in ns: "
                (pr-str (mapv ::elapsed-nanos samples))))))))

(def ^:private nonzero-source-query
  '[:find (count ?key) .
    :in ?wanted-namespace $
    :where
    [_ :seon.schema/key ?key]
    [(namespace ?key) ?namespace]
    [(= ?namespace ?wanted-namespace)]])

(def ^:private schema-pattern
  [:seon.schema/key :seon.schema/form])

(def ^:private schema-ref
  [:seon.schema/key :my.message/content])

(def ^:private missing-schema-ref
  [:seon.schema/key :seon.db-test/missing])

(def ^:private component-evidence-schema
  (schema.datahike/malli->datahike-schema-in
   fixture-projection [::component-root-id ::component-child ::component-value]))

(def ^:private diagnostic-fields
  #{:seon.error/diagnostic-layer
    :seon.error/diagnostic-operation
    :seon.error/diagnostic-member
    :seon.error/diagnostic-expected
    :seon.error/diagnostic-offending
    :seon.error/diagnostic-cause
    :seon.error/diagnostic-evidence-availability
    :seon.error/diagnostic-evidence})

(deftest transaction-wrappers-cannot-hide-a-classified-refusal
  (test-support/with-database
   (fn [connection]
     (let [refusal {:seon.error/kind :seon.turn/refused
                    :seon.turn/rule
                    :seon.turn/agent-already-running}
           wrapped (ex-info "classified transition refusal"
                            refusal
                            (ex-info "transaction wrapper"
                                     {:seon.turn/id nil}))]
       (with-redefs [d/transact (fn [& _] (throw wrapped))]
         (is (= (assoc refusal :seon.error/message "classified transition refusal")
                (db/transact! connection []))
             "the classified exception supplies its message; a deeper wrapper cannot replace it")))
     (db/transact! connection
                   [{:seon.agent/id "busy-agent"}])
     (is (map? (db/transact!
                connection
                (turn/open-tx
                 {:seon.turn/id "already-open" :seon.turn/agent [:seon.agent/id "busy-agent"] :seon.turn/opened-tx "datomic.tx"}))))
     (let [result
           (db/transact!
            connection
            (turn/open-tx
             {:seon.turn/id "contending-run" :seon.turn/agent [:seon.agent/id "busy-agent"] :seon.turn/opened-tx "datomic.tx"}))]
       (is (= :seon.turn/refused (:seon.error/kind result)))
       (is (= :seon.turn/agent-already-running
              (:seon.turn/rule result))
           "real run contention preserves the rule needed by source preview")
       (is (= "run transition refused: agent-already-running"
              (:seon.error/message result)))
       (is (schema/valid-candidate-value? :seon.error/value result)
           "classified transaction refusals satisfy the same error contract as their callers")))))

(defn- with-codec-database
  [options body]
  (test-support/with-database
   options
   (fn [connection]
     (schema/call-with-projection fixture-projection #(body connection)))))

(deftest edn-backed-reads-return-distinguishable-logical-values
  (with-codec-database
   {:seon.test-support/extra-schema
    (schema.datahike/malli->datahike-schema-in
     fixture-projection
     [::ai-declaration ::html-declaration ::row-id])}
   (fn [connection]
     (let [producer 'example.render/ai
           literal-text "example.render/ai"
           literal-html [:p "Hello"]]
       (is (contains?
            (db/transact!
             connection
             [{::row-id "producer"
               ::ai-declaration producer
               ::html-declaration producer}
              {::row-id "literal"
               ::ai-declaration literal-text
               ::html-declaration literal-html}])
            :db-after))
       (testing "a qualified-symbol producer survives a query read"
         (is (= producer
                (db/q '[:find ?declaration .
                        :where
                        [?entity ::row-id "producer"]
                        [?entity ::ai-declaration ?declaration]]
                      @connection))))
       (testing "both literal arms survive pull and entity reads"
         (is (= {::ai-declaration literal-text
                 ::html-declaration literal-html}
                (db/pull @connection
                         [::ai-declaration ::html-declaration]
                         [::row-id "literal"])))
         (is (= literal-text
                (::ai-declaration
                 (db/entity @connection [::row-id "literal"])))))
       (testing "one mixed population keeps text and symbol distinguishable"
         (is (= #{literal-text producer}
                (set
                 (db/q '[:find [?declaration ...]
                         :where [_ ::ai-declaration ?declaration]]
                       @connection))))
         (is (= #{literal-text producer}
                (set
                 (db/q '[:find [?declaration ...]
                         :in $ ?attribute
                         :where [_ ?attribute ?declaration]]
                       @connection
                       ::ai-declaration))))
         (is (= #{literal-html producer}
                (set
                 (db/q '[:find [?declaration ...]
                         :where [_ ::html-declaration ?declaration]]
                       @connection)))))
       (testing "datom projections decode from their explicit attribute"
         (is (= #{literal-text producer}
                (into #{}
                      (map :v)
                      (db/datoms @connection :avet ::ai-declaration)))))))))

(deftest invalid-edn-backed-storage-is-a-flat-read-error
  (doseq [[stored rule]
          [["[" ::schema.datahike/malformed-edn]
           ["true" ::schema.datahike/schema-invalid]]]
    (with-codec-database
     {:seon.test-support/extra-schema
      [(schema.datahike/malli->datahike-attr-in
        fixture-projection ::ai-declaration)]}
     (fn [connection]
       (d/transact connection [{::ai-declaration stored}])
       (let [result
             (db/q '[:find ?declaration .
                     :where [_ ::ai-declaration ?declaration]]
                   @connection)]
         (is (= :seon.db/invalid-read (:seon.error/kind result)))
         (is (= rule
                (get-in result
                        [:seon.error/data
                         :seon.db/dependency-data
                         ::schema.datahike/rule]))))))))

(deftest instrumented-wildcard-pull-keeps-unparsed-database-fields-ordinary
  (test-support/with-database
   (fn [connection]
     (db/transact! connection [{:seon.agent/id "wildcard-agent"}])
     (test-support/preserving-instrumentation-state
      (fn []
       (instrument/apply! {:seon.config/on-core-error :panic
                          :seon.schema/projection (schema/handed-projection)})
         (is (= "wildcard-agent"
                (:seon.agent/id
                 (db/pull @connection '[*]
                          [:seon.agent/id "wildcard-agent"])))))))))

(deftest explicit-and-current-database-forms-are-equivalent
  (test-support/with-database
   (fn [connection]
     (let [database @connection
           expected (d/q exam-query database)]
       (testing "explicit database forms never consult ambient custody"
         (binding [db/*conn* nil]
           (is (= expected (db/q database exam-query)))
           (is (= expected (db/q exam-query database)))))
       (binding [db/*conn* connection]
         (testing "db resolves explicit and ambient connections"
           (is (= (db/db connection) (db/db))))
         (testing "q inserts current database at source position zero"
           (is (= expected (db/q exam-query)))
           (is (= (db/q database exam-query)
                  (db/q exam-query))))
         (testing "q accepts Datahike's argument map explicitly and ambiently"
           (is (= expected
                  (db/q database {:query exam-query})
                  (db/q {:query exam-query})))
           (is (= (db/q database nonzero-source-query "my.message")
                  (db/q database {:query nonzero-source-query
                                  :args ["my.message"]})
                  (db/q {:query nonzero-source-query
                         :args ["my.message"]}))))
         (testing "q inserts current database at the parsed source position"
           (is (= (db/q database nonzero-source-query "my.message")
                  (db/q nonzero-source-query "my.message"))))
         (testing "pull has equivalent explicit and current forms"
           (is (= (db/pull database schema-pattern schema-ref)
                  (db/pull schema-pattern schema-ref)))
           (is (= (db/pull database {:selector schema-pattern
                                     :eid schema-ref})
                  (db/pull {:selector schema-pattern
                            :eid schema-ref}))))
         (testing "pull-many has equivalent explicit and current forms"
           (is (= (db/pull-many database schema-pattern
                                [schema-ref missing-schema-ref schema-ref])
                  (db/pull-many schema-pattern
                                [schema-ref missing-schema-ref schema-ref])))
           (is (= (db/pull-many
                   database
                   {:selector schema-pattern
                    :eids [schema-ref missing-schema-ref schema-ref]})
                  (db/pull-many
                   {:selector schema-pattern
                    :eids [schema-ref missing-schema-ref schema-ref]})))))))))

(deftest return-map-queries-preserve-ordering-and-limit
  (with-codec-database
   {:seon.test-support/extra-schema
    (schema.datahike/malli->datahike-schema-in
     fixture-projection [::row-id])}
   (fn [connection]
     (db/transact! connection
                   [{::row-id "charlie"}
                    {::row-id "alpha"}
                    {::row-id "bravo"}])
     (is (= [{:id "alpha"}
             {:id "bravo"}]
            (db/q {:query '[:find ?id
                            :keys id
                            :where [_ ::row-id ?id]]
                   :args [@connection]
                   :order-by '?id
                   :limit 2}))))))

(deftest positional-query-acceptance-remains-datahikes-contract
  (test-support/with-database
   (fn [connection]
     (let [database @connection
           forms
           [['[:find [?id ...]
               :where [?entity :seon.agent/id ?id]]
             [database]]
            ['[:find ?result .
               :where
               [?evaluation :seon.cluster.eval/ordinal 1]
               [?evaluation :seon.eval/shown ?result]]
             [database]]
            ['[:find ?entity .
               :in $ ?id
               :where [?entity :seon.agent/id ?id]]
             [database "missing-agent"]]]]
       (doseq [[query arguments] forms]
         (is (= (apply d/q query arguments)
                (apply db/q query arguments))
             "Seon passes every Datahike-accepted positional shape through"))))))

(deftest database-identities-support-explicit-and-current-custody
  (test-support/with-database
   (fn [connection]
     (let [database @connection]
       (binding [db/*conn* connection]
         (is (= (db/commit-id database) (db/commit-id)))
         (is (uuid? (db/commit-id database)))
         (is (= (db/committed-value-identity database)
                (db/committed-value-identity)))
         (is (= #{:datahike.value/connection-id
                  :datahike.value/generation
                  :datahike.value/commit-id}
                (set (keys (db/committed-value-identity database))))))))))

(deftest current-database-resolves-once-per-call
  (test-support/with-database
   (fn [connection]
     (let [calls (atom 0)
           datahike-db d/db]
       (with-redefs [d/db (fn [bound-connection]
                            (swap! calls inc)
                            (datahike-db bound-connection))]
         (binding [db/*conn* connection]
           (db/q exam-query)
           (is (= 1 @calls))
           (db/pull schema-pattern schema-ref)
           (is (= 2 @calls))
           (db/pull-many schema-pattern [schema-ref])
           (is (= 3 @calls))))))))

(deftest unbound-current-database-is-a-flat-error
  (let [result (binding [db/*conn* nil]
                 (db/q exam-query))]
    (is (= :seon.db/missing-connection-binding
           (:seon.error/kind result)))
    (is (str/includes? (:seon.error/message result)
                       "(seon.operator/connection \"default\")"))
    (is (= 'seon.db/*conn*
           (get-in result [:seon.error/data :seon.db/binding])))))

(deftest every-public-read-preserves-an-upstream-database-error
  (let [upstream {:seon.error/kind :seon.db-test/upstream
                  :seon.error/message "The earlier database read failed."
                  :seon.error/data {:seon.db-test/stage :opening-basis}}
        reads
        [[:connection-identity #(apply db/connection-identity [upstream])]
         [:database-value-identity #(db/database-value-identity upstream)]
         [:read-evidence-current? #(db/read-evidence-current? upstream [])]
         [:db #(db/db upstream)]
         [:q-database-first #(db/q upstream exam-query)]
         [:q-source-argument #(db/q exam-query upstream)]
         [:q-request-source
          #(db/q {:query exam-query :args [upstream]})]
         [:pull-options #(db/pull upstream {:selector schema-pattern
                                            :eid schema-ref})]
         [:pull-positional #(db/pull upstream schema-pattern schema-ref)]
         [:pull-many-options
          #(db/pull-many upstream {:selector schema-pattern
                                   :eids [schema-ref]})]
         [:pull-many-positional
          #(db/pull-many upstream schema-pattern [schema-ref])]
         [:entity #(db/entity upstream schema-ref)]
         [:datoms #(db/datoms upstream :avet)]
         [:commit-id #(db/commit-id upstream)]
         [:committed-value-identity
          #(db/committed-value-identity upstream)]
         [:history #(db/history upstream)]
         [:as-of #(db/as-of upstream 0)]
         [:since #(db/since upstream 0)]]]
    (doseq [[operation read-call] reads]
      (testing (clojure.core/name operation)
        (is (identical? upstream (read-call))
            "the exact upstream error value returns before dependency work")))))

(deftest query-and-pull-append-evidence-only-when-captured
  (test-support/with-database
   (fn [connection]
     (let [entries (atom [])]
       (binding [db/*conn* connection]
         (db/q exam-query)
         (is (empty? @entries)))
       (binding [db/*conn* connection
                 db/*read-evidence-sink* entries]
         (db/q exam-query)
         (db/pull schema-pattern schema-ref)
         (db/pull-many schema-pattern [schema-ref missing-schema-ref]))
       (is (= [0 0 0]
              (mapv :seon.db/source-argument-position @entries)))
       (is (every? #(contains? % :datahike.read/dependency-plan)
                   @entries))
       (is (not-any?
            #(and (map? %) (contains? % :datahike.pull/plan))
            (tree-seq coll? seq @entries))
           "captured pull replay arguments retain ordinary selector data")
       (is (every? #(schema/valid-candidate-value?
                     :seon.db/captured-read %)
                   @entries))))))

(deftest retained-read-evidence-invalidates-only-on-a-depended-attribute
  (test-support/with-database
   (fn [connection]
     (db/transact! connection [[:db/add "evidence-a" :seon.cluster/name "evidence-a"]])
     (let [captured (atom [])]
       (binding [db/*read-evidence-sink* captured]
         (db/q '[:find [?name ...]
                 :where [_ :seon.cluster/name ?name]]
               @connection))
       (let [evidence (db/read-evidence @captured)]
         (is (every? #(schema/valid-candidate-value?
                       :seon.db/read-evidence %)
                     evidence))
         (db/transact! connection
                       [{:seon.agent/id "unrelated-agent"}])
         (is (db/read-evidence-current? @connection evidence)
             "an unrelated attribute revision retains the renderer read")
         (db/transact! connection [[:db/add "evidence-b" :seon.cluster/name "evidence-b"]])
         (is (not (db/read-evidence-current? @connection evidence))
             "a depended attribute revision makes the retained read stale"))))))

(deftest durable-pull-digests-replay-without-retaining-read-results
  (test-support/with-database
   (fn [connection]
     (let [subject [:seon.ns/name 'seon.flow]
           captured (atom [])
           original (binding [db/*read-evidence-sink* captured]
                      (db/pull @connection
                               {:selector '[*]
                                :eid subject
                                :max-work 4000
                                :max-results 4000
                                :max-result-weight 4000}))
           durable (db/read-evidence @captured)
           process-local
           (db/read-evidence @captured
                             {:seon.db/retain-read-results? true})]
       (is (map? original))
       (is (not-any? #(find % :seon.db/read-result) durable)
           "default evidence cannot inline read payloads into evaluation facts")
       (is (every? #(= 64 (count (:seon.db/read-result-digest %))) durable)
           "durable pull evidence retains only a fixed-size result digest")
       (is (every? #(find % :seon.db/read-result) process-local)
           "the explicit process-local cache retains stable replay values")
       (db/transact! connection
                     [{:seon.message/id "semantic-replay-unrelated"
                       :seon.message/content "unrelated"}])
       (is (true? (db/read-evidence-current? @connection durable))
           "an equal wildcard replay survives an unrelated transaction")
       (is (true? (db/read-evidence-current? @connection process-local))
           "an equal bounded replay keeps the process-local read current")
       (db/transact! connection
                     [{:seon.ns/name 'seon.flow
                       :seon.ns/doc "semantic-replay-changed"}])
       (is (false? (db/read-evidence-current? @connection process-local))
           "a changed selected value invalidates the retained read")))))

(deftest wildcard-pull-digests-cover-empty-large-and-canonical-results
  (test-support/with-database
   (fn [connection]
     (let [digest @(ns-resolve 'seon.db 'read-result-digest)]
       (is (= (digest {:b #{3 2} :a 1})
              (digest {:a 1 :b #{2 3}}))
           "canonical map and set ordering does not alter the digest"))
     (doseq [subject [[:seon.ns/name 'seon.db-test/missing]
                      [:seon.message/id "large-digest"]]]
       (when (= :seon.message/id (first subject))
         (db/transact! connection
                       [{:seon.message/id (second subject)
                         :seon.message/content
                         (apply str (repeat 100000 "x"))}]))
       (let [captured (atom [])]
         (binding [db/*read-evidence-sink* captured]
           (db/pull @connection '[*] subject))
         (let [evidence (db/read-evidence @captured)]
           (is (= 64 (count (:seon.db/read-result-digest (first evidence))))
               "nil and large pull results both retain a digest")
           (is (< (count (pr-str evidence)) 2000)
               "durable evidence size does not scale with the pull result")
           (when (= :seon.message/id (first subject))
             (db/transact!
              connection
              [{:seon.cluster.eval/id "digest-persistence"
                :seon.cluster.eval/run "datomic.tx"
                :seon.cluster.eval/ordinal 0
                :seon.cluster.eval/at #inst "2026-09-09T00:00:00Z"
                :seon.cluster.eval/read-evidence
                [(assoc (first evidence) :db/id "digest-persistence/0")]}])
             (is (= (:seon.db/read-result-digest (first evidence))
                    (get-in
                     (db/pull
                      @connection
                      '[{:seon.cluster.eval/read-evidence
                         [:seon.db/read-result-digest]}]
                      [:seon.cluster.eval/id "digest-persistence"])
                     [:seon.cluster.eval/read-evidence 0
                      :seon.db/read-result-digest]))
                 "the fixed-size digest survives the durable component codec"))))))))

(deftest process-local-replay-declines-unbounded-and-opaque-read-results
  (test-support/with-database
   (fn [connection]
     (let [unbounded-captured (atom [])]
       (binding [db/*read-evidence-sink* unbounded-captured]
         (db/q '[:find [?name ...]
                 :where [_ :seon.ns/name ?name]]
               @connection))
       (is (not-any? #(find % :seon.db/read-result)
                     (db/read-evidence
                      @unbounded-captured
                      {:seon.db/retain-read-results? true}))
           "an unbounded read cannot enter semantic replay")))))

(deftest semantic-replay-declines-opaque-and-lazy-values-without-realizing
  (let [stabilize @(ns-resolve 'seon.db 'stable-read-result)
        request {:seon.db/read-operation :q
                 :seon.db/query-request
                 {:query '[:find ?value . :in $ ?value]
                  :args []
                  :max-work 10
                  :max-results 10
                  :max-result-weight 10}}
        source-touched? (atom false)
        dangerous (lazy-seq (reset! source-touched? true) [1])]
    (is (= [true [1 #uuid "00000000-0000-0000-0000-000000000000"]]
           (stabilize request
                      (list 1
                            #uuid "00000000-0000-0000-0000-000000000000")))
        "a finite counted EDN sequence remains eligible")
    (is (= [false nil] (stabilize request (Object.)))
        "an opaque object is not semantic replay evidence")
    (is (= [false nil] (stabilize request dangerous))
        "an uncounted sequence is not semantic replay evidence")
    (is (false? @source-touched?)
        "declining a lazy value never touches its source")))

(deftest component-expanded-pull-evidence-detects-a-child-only-change
  (with-codec-database
   {:seon.test-support/extra-schema component-evidence-schema}
   (fn [connection]
     (db/transact! connection
                   [{::component-root-id "root"
                     ::component-child "child"}
                    {:db/id "child"
                     ::component-value "before"}])
     (let [captured (atom [])
           result (binding [db/*read-evidence-sink* captured]
                    (db/pull @connection
                             [::component-child]
                             [::component-root-id "root"]))
           child-id (get-in result [::component-child :db/id])
           evidence (db/read-evidence @captured)]
       (is (= "before"
              (get-in result [::component-child ::component-value])))
       (is (= :all
              (get-in evidence
                      [0 :datahike.read/revision
                       :datahike.read/attributes]))
           "automatic component expansion retains every attribute read")
       (db/transact! connection
                     [[:db/add child-id ::component-value "after"]])
       (is (false? (db/read-evidence-current? @connection evidence))
           "a component-child-only change makes the retained pull stale")
       (let [changed-captured (atom [])]
         (binding [db/*read-evidence-sink* changed-captured]
           (db/pull @connection
                    [::component-child]
                    [::component-root-id "root"]))
         (let [changed-evidence (db/read-evidence @changed-captured)]
           (db/transact! connection
                         [[:db/retract child-id ::component-value]])
           (is (false? (db/read-evidence-current?
                        @connection changed-evidence))
               "retracting a component child attribute makes it stale")))))))

;;; THE class regression for "a database value read through a reader that is
;;; not total over its shapes" (2026-08-08 live drive, two instances). Datahike
;;; has exactly four value shapes a caller can hold, and the run loop holds a
;;; non-current one on every turn — it renders at its run's opening basis,
;;; which is an as-of value. A reader that only answers for the current shape
;;; therefore breaks the whole agent, and it breaks it SILENTLY: reading
;;; `:cache-context` as a map key off an AsOfDB yields nil, `select-keys`
;;; yields {}, and the first complaint arrives frames later at an output
;;; contract. Both instances — `read-evidence` and `database-value-identity` —
;;; are covered here by asserting the WANTED behavior for all four shapes at
;;; once, so a fifth reader with the same defect fails this table rather than
;;; an agent's prompt.
(defn- four-view-table
  [database]
  {:current database
   :as-of (db/as-of database (dec (long (db/basis-t database))))
   :since (db/since database 0)
   :history (db/history database)})

(deftest every-database-value-reader-answers-for-all-four-view-shapes
  (test-support/with-database
   (fn [connection]
     (db/transact! connection [{:seon.cluster/name "four-view"}])
     (let [views (four-view-table @connection)]
       (is (= #{:current :as-of :since :history} (set (keys views))))
       (doseq [[view database] views]
         (testing (clojure.core/name view)
           (testing "basis-t answers with a transaction number"
             (is (int? (db/basis-t database))))
           (testing "database-value-identity answers, never throws"
             (let [answer (db/database-value-identity database)]
               (is (or (schema/valid-candidate-value?
                        :seon.db/database-value-identity answer)
                       (= :seon.db/uncommitted-database-value
                          (:seon.error/kind answer)))
                   "a committed identity or a flat error value")))
           (testing "read-evidence produces a well-formed dependency revision"
             (let [captured (atom [])]
               (binding [db/*read-evidence-sink* captured]
                 (db/q '[:find [?name ...]
                         :where [_ :seon.cluster/name ?name]]
                       database))
               (let [evidence (db/read-evidence @captured)]
                 (is (seq evidence) "the read was captured")
                 (is (every? #(schema/valid-candidate-value?
                               :seon.db/read-evidence %)
                             evidence)
                     "no view shape may violate read-evidence's contract")
                 (is (true? (db/read-evidence-current? database evidence))
                     "the very value that produced the evidence is current"))))))))))

(deftest an-as-of-view-is-keyed-on-its-own-fixed-point
  (test-support/with-database
   (fn [connection]
     (db/transact! connection [{:seon.cluster/name "fixed-point-a"}])
     (let [database @connection
           earlier (db/as-of database (dec (long (db/basis-t database))))
           revision (fn [value]
                      (let [captured (atom [])]
                        (binding [db/*read-evidence-sink* captured]
                          (db/q '[:find [?name ...]
                                  :where [_ :seon.cluster/name ?name]]
                                value))
                        (-> (db/read-evidence @captured)
                            first
                            :datahike.read/revision)))]
       (is (not= (revision database) (revision earlier))
           "two views of one origin never share a revision")
       (is (= (revision earlier)
              (revision (db/as-of database
                                  (dec (long (db/basis-t database))))))
           "the same fixed point derives the same revision")
       ;; A run renders at the instant it opens, when its opening transaction
       ;; IS the origin's max-tx. Datahike's own cache-key guard is strictly
       ;; past and excludes exactly that value; taking it literally sent the
       ;; 2026-08-08 re-drive's prompt back to the 509-character error on its
       ;; first turn. An as-of at any committed point is a fixed point.
       (is (false?
            (boolean
             (:datahike.read/cache-eligible?
              (revision (db/as-of database (long (db/basis-t database)))))))
           "an as-of at the origin's own max-tx still has an identity")))))

(deftest pull-many-preserves-input-alignment-with-one-shared-plan
  (test-support/with-database
   (fn [connection]
     (let [database @connection
           entity-ids [schema-ref missing-schema-ref schema-ref]
           calls (atom [])
           pull-many-with-evidence pull-api/pull-many-plan-with-evidence
           entries (atom [])]
       (with-redefs [pull-api/pull-many-plan-with-evidence
                     (fn [db-value pattern eids]
                       (swap! calls conj [db-value pattern eids])
                       (pull-many-with-evidence db-value pattern eids))]
         (binding [db/*read-evidence-sink* entries]
           (let [result (db/pull-many database schema-pattern entity-ids)]
             (is (= [(d/pull database schema-pattern schema-ref)
                     nil
                     (d/pull database schema-pattern schema-ref)]
                    result))
             (is (= 3 (count result))))))
       (is (= 1 (count @calls)))
       (is (= [[database schema-pattern entity-ids]] @calls))
       (is (= 1 (count @entries)))
       (is (= (:datahike.read/dependency-plan
               (pull-many-with-evidence database schema-pattern entity-ids))
              (:datahike.read/dependency-plan (first @entries))))))))

(deftest entity-and-datoms-return-eager-ordinary-data
  (test-support/with-database
   (fn [connection]
     (let [database @connection
           explicit-entity (db/entity database schema-ref)
           ambient-entity (binding [db/*conn* connection]
                            (db/entity schema-ref))
           explicit-datoms (db/datoms database :avet :seon.schema/key)
           mapped-datoms (db/datoms database
                                    {:index :avet
                                     :components [:seon.schema/key]})
           ambient-datoms (binding [db/*conn* connection]
                            (db/datoms {:index :avet
                                       :components [:seon.schema/key]}))]
       (is (= explicit-entity ambient-entity))
       (is (map? explicit-entity))
       (is (not-any? #(instance? datahike.impl.entity.Entity %)
                     (tree-seq coll? seq explicit-entity)))
       (is (= explicit-datoms mapped-datoms ambient-datoms))
       (is (vector? explicit-datoms))
       (is (seq explicit-datoms))
       (is (every? #(= #{:e :a :v :tx :added} (set (keys %)))
                   explicit-datoms))
       (is (not-any? #(instance? datahike.datom.Datom %)
                     explicit-datoms))))))

(deftest nested-native-reports-admit-bounded-reference-identities
  (test-support/with-database
   (fn [connection]
     (let [effective (config/defaults)
           request
           {:seon.sci.admit/value
            {:probe/report (db/transact! connection [])}
            :seon.sci.admit/interrupt-fn (fn [])
            :seon.sci.admit/caps (config/result-caps effective)
            :seon.config/on-core-error :record}
           admitted (admit/admit request)
           semantic (:seon.sci.admit/value admitted)
           report (:probe/report semantic)
           before (:db-before report)
           after (:db-after report)
           admitted-artifact
           (render.value/artifact-edn (render.value/artifact admitted))
           inline-ceiling
           (:seon.config.eval.result/blob-threshold effective)]
       (is (= #{:db-name :t :datahike/commit-id} (set (keys before))))
       (is (= #{:db-name :t :datahike/commit-id} (set (keys after))))
       (is (uuid? (:datahike/commit-id before)))
       (is (uuid? (:datahike/commit-id after)))
       (is (not-any? db/database-value?
                     (tree-seq coll? seq semantic)))
       (is (< (count admitted-artifact) inline-ceiling)
           "identity admission keeps the native report inline")
       (is (not (contains? admitted :seon.sci.admit/capped?))
           "the retired key is absent, never a stored false")))))

(deftest an-agent-namespace-is-shared-and-is-not-an-identity
  ;; ASSIGNMENT IS NOT IDENTITY. This test used to assert a unique rejection
  ;; on `:seon.agent/namespace`; the declaration says the opposite —
  ;; "not unique — several agents may share one" — and the installed schema
  ;; agrees with the declaration, so the expectation was stale rather than
  ;; the behaviour wrong. The one agent a namespace answers for is its
  ;; `:seon.ns/steward`, which is a different attribute entirely.
  (test-support/with-database
   (fn [connection]
     (db/transact!
      connection
      [{:seon.ns/name 'my.agents.db-shared}
       {:seon.agent/id "db-shared-first"
        :seon.agent/namespace
        [:seon.ns/name 'my.agents.db-shared]}])
     (let [accepted
           (binding [db/*conn* connection]
             (db/transact!
              [{:seon.agent/id "db-shared-second"
                :seon.agent/namespace
                [:seon.ns/name 'my.agents.db-shared]}]))]
       (is (nil? (:seon.error/kind accepted))
           "a second agent assigned the same namespace commits")
       (is (nil? (get-in (:schema @connection)
                         [:seon.agent/namespace :db/unique]))
           "because the installed schema declares no uniqueness on it")
       (is (= #{"db-shared-first" "db-shared-second"}
              (set (db/q '[:find [?id ...]
                           :in $ ?namespace-name
                           :where
                           [?namespace :seon.ns/name ?namespace-name]
                           [?agent :seon.agent/namespace ?namespace]
                           [?agent :seon.agent/id ?id]]
                         @connection 'my.agents.db-shared)))
           "and both agents are found by the namespace they share")))))

(deftest unique-rejection-names-the-existing-owner-as-data
  (test-support/with-database
   (fn [connection]
     (db/transact!
      connection
      [{:seon.ns/name 'my.agents.db-conflict}
       [:db/add "db-conflict-owner" :seon.cluster.eval/id "db-conflict-owner"]
       [:db/add "db-conflict-owner" :seon.cluster.eval/refreshes
        [:seon.ns/name 'my.agents.db-conflict]]])
     (let [rejected
           (binding [db/*conn* connection]
             (db/transact!
              [[:db/add "db-conflict-contender" :seon.cluster.eval/id "db-conflict-contender"]
               [:db/add "db-conflict-contender" :seon.cluster.eval/refreshes
                [:seon.ns/name 'my.agents.db-conflict]]]))
           conflict (:seon.error/data rejected)]
       (is (= :seon.db/rejected (:seon.error/kind rejected)))
       (is (true? (:seon.db/transaction-refused rejected)))
       (is (= {:error :transact/unique
               :attribute :seon.cluster.eval/refreshes}
              (select-keys conflict [:error :attribute])))
       (is (instance? datahike.datom.Datom (:datom conflict)))
       (is (= {:seon.db/conflict-attribute
               :seon.cluster.eval/refreshes
               :seon.db/conflict-owner
               [:seon.cluster.eval/id "db-conflict-owner"]}
              (select-keys conflict
                           [:seon.db/conflict-attribute
                            :seon.db/conflict-owner])))
       (is (str/includes? (:seon.error/message rejected)
                          "db-conflict-owner"))
       (is (not (str/includes? (:seon.error/message rejected)
                               "ExceptionInfo")))
       (is (= (:seon.error/message rejected)
              (db/render-rejection-ai rejected)))
       (is (str/includes? (pr-str (db/render-rejection-html rejected))
                          "db-conflict-owner"))
       (is (= 'seon.db/render-rejection-ai
              (->> (schema/matching-shapes rejected)
                   (some #(when (= :seon.db/transaction-refused-error
                                   (:seon.schema/key %))
                            (:seon.render/ai %))))))))))

(deftest non-unique-writer-rejections-retain-their-datahike-data
  (test-support/with-database
   (fn [connection]
     (db/transact!
      connection
      [{:seon.agent/id "db-cas-owner"}])
     (let [rejected
           (db/transact!
            connection
            [[:db.fn/cas
              [:seon.agent/id "db-cas-owner"]
              :seon.agent/id
              "not-the-current-id"
              "db-cas-replacement"]])
           data (:seon.error/data rejected)]
       (is (= :seon.db/rejected (:seon.error/kind rejected)))
       (is (= {:error :transact/cas
               :expected "not-the-current-id"
               :new "db-cas-replacement"}
              (select-keys data [:error :expected :new])))
       (is (instance? datahike.datom.Datom (:old data)))))))

(deftest temporal-reads-use-explicit-and-ambient-database-values
  (test-support/with-database
   (fn [connection]
     (let [before @connection
           ;; THE VOCABULARY'S OWN NAME. `(:t database)` is not a key on a
           ;; Datahike database value, so this read was nil — a present nil
           ;; that both `as-of` calls accepted while comparing two views of
           ;; nothing, until the armed contract asked what a time point is.
           before-t (db/basis-t before)]
       (db/transact! connection
                     [{:seon.message/id "db-test-temporal"}])
       (let [after @connection]
         (binding [db/*conn* connection]
           (is (= (db/q exam-query (db/history after))
                  (db/q exam-query (db/history))))
           (is (= (db/q exam-query (db/as-of after before-t))
                  (db/q exam-query (db/as-of before-t))))
           (is (= (db/q '[:find [?id ...]
                          :where [_ :seon.message/id ?id]]
                        (db/since after before-t))
                  (db/q '[:find [?id ...]
                          :where [_ :seon.message/id ?id]]
                        (db/since before-t))))))))))

(defn- seed-diff-messages!
  [connection]
  (db/transact!
   connection
   [{:seon.agent/id "db-diff-alice"}
    {:seon.agent/id "db-diff-bob"}
    {:seon.message/id "db-diff-m1" :seon.message/to [:seon.agent/id "db-diff-bob"] :seon.message/from [:seon.agent/id "db-diff-alice"] :seon.message/content "hello" :seon.message/inbox [:seon.agent/id "db-diff-bob"]}
    {:seon.message/id "db-diff-m2" :seon.message/to [:seon.agent/id "db-diff-bob"] :seon.message/content "removed" :seon.message/inbox [:seon.agent/id "db-diff-bob"]}]))

(deftest ^{:seon.test/usage true} diff-replays-one-read-by-derived-identity
  (test-support/with-database
   (fn [connection]
     (seed-diff-messages! connection)
     (let [before (db/basis-t @connection)]
       (db/transact!
        connection
        [[:db/add [:seon.message/id "db-diff-m1"]
          :seon.message/content "hello, edited"]
         [:db.fn/retractEntity
          [:seon.message/id "db-diff-m2"]]
         {:seon.message/id "db-diff-m3" :seon.message/to [:seon.agent/id "db-diff-bob"] :seon.message/content "added" :seon.message/inbox [:seon.agent/id "db-diff-bob"]}])
       (binding [db/*conn* connection]
         (let [result (db/diff before #'message/inbox "db-diff-bob")
               current (db/basis-t @connection)]
           (is (= before (:seon.db/basis-t result)))
           (is (= current (:seon.db/current-basis-t result)))
           (is (= ["db-diff-m3"]
                  (mapv :my.message/id (:seon.db.diff/added result))))
           (is (= ["db-diff-m2"]
                  (mapv :my.message/id (:seon.db.diff/removed result))))
           (is (= [{:seon.db.diff/identity "db-diff-m1"
                    :seon.db.diff/changed-attributes [:my.message/content]
                    :seon.db.diff/before
                    {:my.message/id "db-diff-m1"
                     :my.message/from "db-diff-alice"
                     :my.message/at
                     #inst "2026-08-13T20:00:00.000-00:00"
                     :my.message/content "hello"}
                    :seon.db.diff/after
                    {:my.message/id "db-diff-m1"
                     :my.message/from "db-diff-alice"
                     :my.message/at
                     #inst "2026-08-13T20:00:00.000-00:00"
                     :my.message/content "hello, edited"}}]
                  (:seon.db.diff/changed result)))
           (is (= result (eval (:seon.db.diff/requery-id result)))
               "the rendered requery form replays verbatim for an agent")
           (let [rendered (db/render-diff-ai result)]
             (is (str/includes? rendered "+1 -1 ~1"))
             (is (str/includes? rendered "db-diff-m1"))
             (is (str/includes? rendered ":my.message/content"))
             (is (str/includes? rendered "approximately"))
             (is (str/includes? rendered "requery by")))))))))

(deftest diff-no-change-is-empty
  (test-support/with-database
   (fn [connection]
     (seed-diff-messages! connection)
     (binding [db/*conn* connection]
       (let [basis (db/basis-t @connection)
             result (db/diff basis #'message/inbox "db-diff-bob")]
         (is (= [] (:seon.db.diff/added result)))
         (is (= [] (:seon.db.diff/removed result)))
         (is (= [] (:seon.db.diff/changed result))))))))

(deftest diff-refuses-missing-identity-and-external-sinks
  (test-support/with-database
   (fn [connection]
     (binding [db/*conn* connection]
       (let [basis (db/basis-t @connection)
             identity-refusal (db/diff basis #'config/effective)
             database-refusal (db/diff basis #'db/render-diff-ai {})
             impurity-refusal (db/diff basis #'render/render-ai {})]
         (doseq [refusal [identity-refusal database-refusal
                          impurity-refusal]]
           (is (true? (:seon.db/diff-refused refusal)))
           (is (= diagnostic-fields
                  (set (keys (:seon.error/data refusal))))))
         (is (= :seon.db/row-identity-absent
                (get-in identity-refusal
                        [:seon.error/data :seon.error/diagnostic-cause])))
         (is (= :seon.db/database-input-absent
                (get-in database-refusal
                        [:seon.error/data :seon.error/diagnostic-cause])))
         (is (= :seon.db/external-sink-reachable
                (get-in impurity-refusal
                        [:seon.error/data :seon.error/diagnostic-cause])))
         (is (= #{:ai-visible-text}
                (get-in impurity-refusal
                        [:seon.error/data :seon.error/diagnostic-offending]))))))))

(deftest non-temporal-reads-return-one-flat-error-before-datahike
  (let [configuration
        {:store {:backend :memory :id (random-uuid)}
         :keep-history? false}
        _ (d/create-database configuration)
        connection (d/connect configuration)]
    (try
      (let [database @connection
            time-point (:max-tx database)
            results [(db/history database)
                     (db/as-of database time-point)
                     (db/since database time-point)]]
        (doseq [result results]
          (is (= :seon.db/non-temporal-database
                 (:seon.error/kind result)))
          (is (= :seon.db/temporal-read
                 (get-in result [:seon.error/data :seon.db/operation])))
          (is (not (contains? (:seon.error/data result)
                              :seon.db/dependency-data))))
        (binding [db/*conn* connection]
          (is (= :seon.db/non-temporal-database
                 (:seon.error/kind (db/history))))))
      (finally
        (d/release connection)
        (d/delete-database configuration)))))

(deftest the-exam-query-returns-the-fixtures-true-count
  (test-support/with-database
   (fn [connection]
     (let [database @connection
           fixture-count (d/q exam-query database)]
       (is (pos-int? fixture-count))
       (binding [db/*conn* connection]
         (is (= fixture-count (db/q exam-query))))))))

(deftest malformed-reads-return-flat-errors
  (test-support/with-database
   (fn [connection]
     ;; A QUERY VECTOR IS A QUERY VECTOR to the declared contract, so a
     ;; `:find` with no bindings reaches the read and `seon.db`'s own typed
     ;; refusal is what an agent gets.
     (let [result (db/q @connection '[:find])]
       (is (= :seon.db/invalid-read (:seon.error/kind result)))
       (is (string? (:seon.error/message result)))
       (is (map? (:seon.error/data result))))
     ;; AN ENTITY IDENTIFIER IS NOT A BARE STRING, and the declared contract
     ;; says so, so under the contracts every cluster arms the contract
     ;; refuses first — same crossing, same function named, and the agent
     ;; still reads a flat value because its forms cross the SCI kernel
     ;; (`my.turn-test`, `my.message-test` prove that boundary).
     (let [refusal (test-support/refusal-data
                    #(db/pull-many @connection schema-pattern ["not-an-eid"]))]
       (is (= :seon.instrument/contract-violated (:seon.error/kind refusal)))
       (is (= 'seon.db/pull-many
              (:seon.error/diagnostic-operation (:seon.error/data refusal))))
       (is (string? (:seon.error/message refusal)))))))

(deftest invalid-read-identities-are-diagnostics-never-absence
  (test-support/with-database
   (fn [connection]
     (db/transact! connection
                   [{:seon.agent/id "identity-admission-present"}])
     (let [unknown-attribute
           (db/q '[:find ?entity
                   :where [?entity :seon.agent/idd _]]
                 @connection)
           wrong-pull
           (db/pull @connection '[*]
                    [:seon.agent/id 'identity-admission-present])
           wrong-entity
           (db/entity @connection
                      [:seon.agent/id 'identity-admission-present])]
       (testing "an uninstalled query attribute names registered candidates"
         (is (= :seon.db/invalid-read (:seon.error/kind unknown-attribute)))
         (is (= diagnostic-fields
                (set (keys (:seon.error/data unknown-attribute)))))
         (is (= 'seon.db/q
                (get-in unknown-attribute
                        [:seon.error/data
                         :seon.error/diagnostic-operation])))
         (is (= :seon.agent/idd
                (get-in unknown-attribute
                        [:seon.error/data :seon.error/diagnostic-member])))
         (is (some #{:seon.agent/id}
                   (get-in unknown-attribute
                           [:seon.error/data :seon.error/diagnostic-evidence
                            :seon.db/registered-candidates]))))
       (testing "wrong-typed lookup refs retain the installed declaration"
         (doseq [[operation result]
                 [['seon.db/pull wrong-pull]
                  ['seon.db/entity wrong-entity]]]
           (is (= :seon.db/invalid-read (:seon.error/kind result)))
           (is (= operation
                  (get-in result
                          [:seon.error/data
                           :seon.error/diagnostic-operation])))
           (is (= :db.type/string
                  (get-in result
                          [:seon.error/data :seon.error/diagnostic-expected
                           :db/valueType])))
           (is (= {:seon.db/attribute :seon.agent/id
                   :seon.db/value 'identity-admission-present}
                  (get-in result
                          [:seon.error/data
                           :seon.error/diagnostic-offending])))))
       (testing "valid absence remains ordinary absence"
         (is (nil? (db/pull @connection '[*]
                            [:seon.agent/id
                             "identity-admission-missing"])))
         (is (nil? (db/entity @connection
                              [:seon.agent/id
                               "identity-admission-missing"])))
         (is (= #{}
                (db/q '[:find ?entity
                        :where
                        [?entity :seon.agent/id
                         "identity-admission-missing"]]
                      @connection))))))))

(deftest uninstalled-pull-attributes-refuse-without-writing
  (test-support/with-database
   (fn [connection]
     (db/transact! connection
                   [[:db/add "diagnostic-pull-run" :seon.turn/id "diagnostic-pull-run"]])
     (let [database @connection
           basis-before (db/basis-t database)
           uninstalled [:seon.turn/generated-at
                        :seon.turn/generation-complete-at]
           results
           (mapv (fn [attribute]
                   (db/pull database
                            [:seon.turn/id attribute]
                            [:seon.turn/id "diagnostic-pull-run"]))
                 uninstalled)]
       (is (every? #(not (contains? (:schema database) %)) uninstalled))
       (doseq [[attribute result] (map vector uninstalled results)]
         (is (= :seon.db/invalid-read (:seon.error/kind result)))
         (is (= attribute
                (get-in result
                        [:seon.error/data :seon.db/dependency-data :attribute]))))
       (is (= basis-before (db/basis-t @connection))
           "diagnostic reads cannot advance the database basis")
       (is (= {:seon.turn/id "diagnostic-pull-run"}
              (db/pull @connection
                       [:seon.turn/id]
                       [:seon.turn/id "diagnostic-pull-run"])))))))

(deftest temporal-database-identities-use-the-origin-schema
  (test-support/with-database
   (fn [connection]
     (db/transact! connection
                   [{:seon.agent/id "temporal-schema-present"}])
     (let [database @connection
           basis (db/basis-t database)
           views [(db/history database)
                  (db/as-of database basis)
                  (db/since database (dec basis))]]
       (doseq [view views]
         (testing (str (class view))
           (let [installed
                 (db/q '[:find ?entity
                         :where [?entity :seon.agent/id _]]
                       view)
                 uninstalled
                 (db/q '[:find ?entity
                         :where [?entity :seon.agent/idd _]]
                       view)]
             (is (not= :seon.db/invalid-read (:seon.error/kind installed))
                 "an installed attribute is never classified as uninstalled")
             (is (= :seon.db/invalid-read (:seon.error/kind uninstalled)))
             (is (= :seon.db/attribute-not-installed
                    (get-in uninstalled
                            [:seon.error/data
                             :seon.error/diagnostic-cause]))))))))))

(deftest malformed-public-database-requests-name-the-public-operation
  (test-support/with-database
   (fn [connection]
     (let [cases
           [[(db/q @connection {:args []}) 'seon.db/q :query]
            [(db/q @connection
                   '[:find ?entity
                     :where
                     [?entity :seon.agent/id "root"
                      ?transaction true :extra]])
             'seon.db/q
             '[?entity :seon.agent/id "root"
               ?transaction true :extra]]]]
       (doseq [[result operation member] cases]
         (is (contains? #{:seon.db/invalid-read
                          :seon.db/invalid-request}
                        (:seon.error/kind result)))
         (is (= diagnostic-fields
                (set (keys (:seon.error/data result)))))
         (is (= operation
                (get-in result
                        [:seon.error/data
                         :seon.error/diagnostic-operation])))
         (is (= member
                (get-in result
                        [:seon.error/data :seon.error/diagnostic-member]))))
       ;; `seon.db/pull` declares `:selector` required and `seon.db/transact!`
       ;; declares the shape of `:tx-data`, so under the contracts every
       ;; cluster arms the refusal lands one frame before the body — and it
       ;; is the STRONGER proof: the offending argument in the diagnostic is
       ;; the caller's own request, unreplaced, observed before the body
       ;; could compute anything about it.
       (doseq [[thunk operation path]
               [[#(db/pull @connection
                           {:eid [:seon.agent/id "root"]})
                 'seon.db/pull [:selector]]
                [#(db/transact! connection {:not-tx-data []})
                 'seon.db/transact! [:tx-data]]]]
         (let [refusal (test-support/refusal-data thunk)]
           (is (= :seon.instrument/contract-violated
                  (:seon.error/kind refusal)))
           (is (= operation
                  (get-in refusal [:seon.error/data
                                   :seon.error/diagnostic-operation])))
           (is (= path
                  (first (get-in refusal
                                 [:seon.error/data
                                  :seon.instrument/problem-paths]))))))
       (let [malformed (first (last cases))]
         (is (= [:entity :attribute :value :transaction :added]
                (get-in malformed
                        [:seon.error/data
                         :seon.error/diagnostic-expected])))
         (is (not (str/includes? (pr-str malformed)
                                 "resolve-pattern-lookup-entity-id"))))))))

(deftest a-write-naming-another-clusters-branch-is-refused-naming-both
  ;; THE CLASS: the write seam — not a caller pre-read — decides custody, so
  ;; every path that reaches `transact!` while a cluster's connection is the
  ;; writing custody is covered by this one decision. The refusal names both
  ;; branches, and the foreign branch is left untouched.
  (test-support/with-database
   (fn [writing-connection]
     (test-support/with-database
      (fn [foreign-connection]
        (binding [db/*conn* writing-connection]
          (let [explicit (db/transact! writing-connection
                                       [{:seon.agent/id "own"}])
                elided (db/transact! [{:seon.agent/id "elided"}])
                refused (db/transact! foreign-connection
                                      [{:seon.agent/id "foreign"}])
                message-ids
                (fn [connection]
                  (set (db/q '[:find [?id ...]
                               :where [_ :seon.agent/id ?id]]
                             @connection)))
                branch
                (fn [connection]
                  (second
                   (:datahike/connection-id
                    (db/connection-identity connection))))
                data (:seon.error/data refused)]
            (is (nil? (:seon.error/kind explicit))
                "the writing cluster's own connection still commits")
            (is (nil? (:seon.error/kind elided))
                "the elided arity still commits through the writing custody")
            (is (= :seon.db/foreign-connection (:seon.error/kind refused)))
            (is (true? (:seon.db/foreign-connection refused))
                "the refusal carries its class marker")
            (is (= (branch writing-connection)
                   (:seon.db/ambient-branch data))
                "the refusal names the writing cluster's branch")
            (is (= (branch foreign-connection)
                   (:seon.db/explicit-branch data))
                "the refusal names the branch the write tried to reach")
            (is (str/includes?
                 (:seon.error/message refused)
                 (pr-str (branch foreign-connection))))
            (is (str/includes?
                 (:seon.error/message refused)
                 (pr-str (branch writing-connection))))
            (is (= #{"own" "elided"} (message-ids writing-connection)))
            (is (empty? (message-ids foreign-connection))
                "nothing reaches the foreign branch"))))))))

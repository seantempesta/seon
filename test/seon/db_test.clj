(ns seon.db-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.config :as config]
            [seon.db :as db]
            [seon.instrument :as instrument]
            [seon.render.value :as render.value]
            [seon.sci.admit :as admit]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.test-support :as test-support]))

(schema/register! ::ai-declaration
                  [:and {:seon.db/index true}
                   [:or :string :qualified-symbol]])
(schema/register! ::html-declaration [:or [:vector :any] :qualified-symbol])
(schema/register! ::row-id [:string {:seon.db/identity true}])

(def ^:private exam-query
  '[:find (count ?key) .
    :where
    [_ :seon.schema/key ?key]
    [(namespace ?key) ?namespace]
    [(= ?namespace "my.message")]])

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

(deftest edn-backed-reads-return-distinguishable-logical-values
  (test-support/with-database
   {:seon.test-support/extra-schema
    (schema.datahike/malli->datahike-schema
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
    (test-support/with-database
     {:seon.test-support/extra-schema
      [(schema.datahike/malli->datahike-attr ::ai-declaration)]}
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
     (db/transact! connection [{:seon.cluster.agent/id "wildcard-agent"}])
     (instrument/apply! {:seon.config/on-core-error :panic
                         :seon.sci.admit/caps nil})
     (try
       (is (= "wildcard-agent"
              (:seon.cluster.agent/id
               (db/pull @connection '[*]
                        [:seon.cluster.agent/id "wildcard-agent"]))))
       (finally
         (instrument/remove!))))))

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
  (test-support/with-database
   {:seon.test-support/extra-schema
    (schema.datahike/malli->datahike-schema [::row-id])}
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
    (is (string? (:seon.error/message result)))
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
       (is (every? #(schema/valid-candidate-value?
                     :seon.db/captured-read %)
                   @entries))))))

(deftest retained-read-evidence-invalidates-only-on-a-depended-attribute
  (test-support/with-database
   (fn [connection]
     (db/transact! connection [{:seon.cluster/name "evidence-a"}])
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
                       [{:seon.cluster.agent/id "unrelated-agent"}])
         (is (db/read-evidence-current? @connection evidence)
             "an unrelated attribute revision retains the renderer read")
         (db/transact! connection [{:seon.cluster/name "evidence-b"}])
         (is (not (db/read-evidence-current? @connection evidence))
             "a depended attribute revision makes the retained read stale"))))))

(deftest pull-many-preserves-input-alignment-with-one-shared-plan
  (test-support/with-database
   (fn [connection]
     (let [database @connection
           entity-ids [schema-ref missing-schema-ref schema-ref]
           calls (atom [])
           pull-many-with-evidence d/pull-many-with-evidence
           entries (atom [])]
       (with-redefs [d/pull-many-with-evidence
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

(deftest agent-transactions-return-one-bounded-useful-report
  (test-support/with-database
   (fn [connection]
     (db/transact!
      connection
      [{:seon.ns/name 'my.agents.root}
       {:seon.cluster.agent/id "root"
        :seon.cluster.agent/namespace [:seon.ns/name 'my.agents.root]}
       {:seon.config.eval.result/max-collection 8192}])
     (let [system-report
           (db/transact!
            connection
            [{:seon.cluster.message/id "db-test-system-explicit"}])
           message
           {:seon.cluster.message/id "db-test-agent-report"
            :seon.cluster.message/to [:seon.cluster.agent/id "root"]
            :seon.cluster.message/from [:seon.cluster.agent/id "root"]
            :seon.cluster.message/content "bounded report"
            :seon.cluster.message/at #inst "2026-08-04T22:00:00Z"
            :my.message/reason "test"}
           full-report
           (binding [db/*conn* connection]
             (db/transact! {:tx-data [message]}))
           explicit-agent-report
           (binding [db/*conn* connection]
             (db/transact! connection {:tx-data []}))]
       (testing "the explicit system arity retains exact database values"
         (is (db/database-value? (:db-before system-report)))
         (is (db/database-value? (:db-after system-report))))
       (testing "the ambient arity returns the useful seven-datom projection"
         (is (= 7 (:seon.db/datom-count full-report)))
         (is (= 7 (count (:tx-data full-report))))
         (is (int? (:tx full-report)))
         (is (uuid? (:datahike/commit-id full-report)))
         (is (map? (:tempids full-report)))
         (is (not-any? #(contains? full-report %)
                       [:db-before :db-after]))
         (is (every? #(= #{:e :a :v :tx :added} (set (keys %)))
                     (:tx-data full-report)))
         (is (schema/valid-candidate-value?
              :seon.db/transaction-report full-report))
         (is (= 'seon.db/render-transaction-ai
                (->> (schema/matching-shapes full-report)
                     (some #(when (= :seon.db/transaction-report
                                     (:seon.schema/key %))
                              (:seon.render/ai %))))))
         (is (str/includes? (db/render-transaction-ai full-report)
                            "with 7 datoms")))
       (testing "the explicit arity under ambient custody has the same face"
         (is (schema/valid-candidate-value?
              :seon.db/transaction-report explicit-agent-report))
         (is (not-any? #(contains? explicit-agent-report %)
                       [:db-before :db-after])))
       (testing "the configured collection ceiling bounds committed datoms"
         (let [config-entity
               (d/q '[:find ?entity .
                      :where
                      [?entity :seon.config.eval.result/max-collection _]]
                    @connection)]
           (db/transact!
            connection
            [[:db/add config-entity
              :seon.config.eval.result/max-collection 2]])
           (let [bounded
                 (binding [db/*conn* connection]
                   (db/transact!
                    [{:seon.cluster.message/id "db-test-bounded-report"
                      :seon.cluster.message/to
                      [:seon.cluster.agent/id "root"]
                      :seon.cluster.message/content "bounded"}]))]
             (is (< (count (:tx-data bounded))
                    (:seon.db/datom-count bounded)))
             (is (= 2 (count (:tx-data bounded)))))))))))

(deftest nested-native-reports-admit-reference-identities-not-database-walks
  (test-support/with-database
   (fn [connection]
     (let [effective (config/defaults)
           request
           {:seon.sci.admit/value
            {:probe/report (db/transact! connection [])}
            :seon.sci.admit/interrupt-fn (fn [])
            :seon.sci.admit/caps (config/result-caps effective)
            :seon.config/on-core-error :record}
           walked
           (with-redefs [schema/identity-only-projection (constantly nil)]
             (admit/admit request))
           admitted (admit/admit request)
           semantic (:seon.sci.admit/value admitted)
           report (:probe/report semantic)
           before (:db-before report)
           after (:db-after report)
           walked-artifact
           (render.value/artifact-edn (render.value/artifact walked))
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
       (is (< (* 10 (count admitted-artifact))
              (count walked-artifact))
           "identity admission removes at least one order of magnitude")
       (is (< (count admitted-artifact) inline-ceiling)
           "the nested report falls out of the blob artifact size class")
       (is (false? (:seon.sci.admit/capped? admitted)))))))

(deftest unique-rejection-names-the-existing-owner-as-data
  (test-support/with-database
   (fn [connection]
     (db/transact!
      connection
      [{:seon.ns/name 'my.agents.db-conflict}
       {:seon.cluster.agent/id "db-conflict-owner"
        :seon.cluster.agent/namespace
        [:seon.ns/name 'my.agents.db-conflict]}])
     (let [rejected
           (binding [db/*conn* connection]
             (db/transact!
              [{:seon.cluster.agent/id "db-conflict-contender"
                :seon.cluster.agent/namespace
                [:seon.ns/name 'my.agents.db-conflict]}]))
           conflict (:seon.error/data rejected)]
       (is (= :seon.db/rejected (:seon.error/kind rejected)))
       (is (true? (:seon.db/transaction-refused rejected)))
       (is (= {:error :transact/unique
               :attribute :seon.cluster.agent/namespace}
              (select-keys conflict [:error :attribute])))
       (is (instance? datahike.datom.Datom (:datom conflict)))
       (is (= {:seon.db/conflict-attribute
               :seon.cluster.agent/namespace
               :seon.db/conflict-value
               [:seon.ns/name 'my.agents.db-conflict]
               :seon.db/conflict-owner
               [:seon.cluster.agent/id "db-conflict-owner"]}
              (select-keys conflict
                           [:seon.db/conflict-attribute
                            :seon.db/conflict-value
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
      [{:seon.cluster.agent/id "db-cas-owner"}])
     (let [rejected
           (db/transact!
            connection
            [[:db.fn/cas
              [:seon.cluster.agent/id "db-cas-owner"]
              :seon.cluster.agent/id
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
           before-t (:t before)]
       (db/transact! connection
                     [{:seon.cluster.message/id "db-test-temporal"}])
       (let [after @connection]
         (binding [db/*conn* connection]
           (is (= (db/q exam-query (db/history after))
                  (db/q exam-query (db/history))))
           (is (= (db/q exam-query (db/as-of after before-t))
                  (db/q exam-query (db/as-of before-t))))
           (is (= (db/q '[:find [?id ...]
                          :where [_ :seon.cluster.message/id ?id]]
                        (db/since after before-t))
                  (db/q '[:find [?id ...]
                          :where [_ :seon.cluster.message/id ?id]]
                        (db/since before-t))))))))))

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
     (doseq [result [(db/q @connection '[:find])
                     (db/pull-many @connection schema-pattern ["not-an-eid"])]]
       (is (= :seon.db/invalid-read (:seon.error/kind result)))
       (is (string? (:seon.error/message result)))
       (is (map? (:seon.error/data result)))))))

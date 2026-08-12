(ns seon.db-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as d]
            [datahike.pull-api :as pull-api]
            [seon.config :as config]
            [seon.db :as db]
            [seon.instrument :as instrument]
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
   (schema/register! ::row-id [:string {:seon.db/identity true}])))

(use-fixtures
 :each
 (fn [test-body]
   (schema/call-with-registration-delta
    schema-delta
    {:seon.schema.admission/source :core}
    test-body)))

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

(def ^:private component-evidence-schema
  [{:db/ident ::component-root-id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident ::component-child
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/isComponent true}
   {:db/ident ::component-value
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}])

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

(deftest positional-query-acceptance-remains-datahikes-contract
  (test-support/with-database
   (fn [connection]
     (let [database @connection
           forms
           [['[:find [?id ...]
               :where [?entity :seon.cluster.agent/id ?id]]
             [database]]
            ['[:find ?result .
               :where
               [?receipt :seon.cluster.eval/ordinal 1]
               [?receipt :seon.cluster.eval/result-edn ?result]]
             [database]]
            ['[:find ?entity .
               :in $ ?id
               :where [?entity :seon.cluster.agent/id ?id]]
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

(deftest component-expanded-pull-evidence-detects-a-child-only-change
  (test-support/with-database
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
           "a component-child-only change makes the retained pull stale")))))

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

(ns seon.db-test
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as d]
            [datahike.core :as datahike]
            [datahike.db]
            [datahike.pull-api :as pull-api]
            [datahike.tools :as datahike.tools]
            [datahike.writer :as datahike.writer]
            [datahike.writing :as datahike.writing]
            [malli.core :as m]
            [malli.instrument :as mi]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.config :as config]
            [seon.turn :as turn]
            [seon.db :as db]
            [clojure.set]
            [seon.env :as env]
            [seon.fn :as seon.fn]
            [seon.id :as id]
            [seon.instrument :as instrument]
            [seon.program :as program]
            [seon.render :as render]
            [seon.render.value :as render.value]
            [seon.schedule :as schedule]
            [seon.sci.admit :as admit]
            [seon.sci.eval :as sci.eval]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.test-support :as test-support]
            [taoensso.trove :as trove])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

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
   (schema/register! ::component-value :string)
   (schema/register! ::component-child
                     [:and {:seon.db/component true :seon.db/component-schema ::component-row} :seon.db/ref])
   (schema/register! ::component-row
                     [:map {:seon.db/attributes true} [::component-value ::component-value]])))


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
    [?schema :seon.schema/references :seon.message/id]
    [?schema :seon.db/attributes true]
    [?schema :seon.schema/key ?key]])

(def ^:private uncached-schema-family-query
  '[:find [?key ...]
    :in $ ?sample
    :where
    [?schema :seon.schema/references :seon.message/id]
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

(deftest supplied-database-pulls-carry-the-projection-across-unbound-reads
  (test-support/with-database
   (fn [connection]
     (let [projection (schema/handed-projection)
           environment (env/environment
                        {:seon.boot/cluster-name "documentation-cost"
                         :seon.db/connection connection
                         :seon.schema/projection projection})
           ids-query '[:find [?function ...]
                       :in $ ?name
                       :where [?namespace :seon.ns/name ?name]
                              [?function :seon.fn/ns ?namespace]
                              [?function :seon.fn/private? false]]
           raw @connection
           ids (d/q ids-query raw 'my.message)
           database (db/supplied-database-value environment)
           allocation-bean ^com.sun.management.ThreadMXBean
           (java.lang.management.ManagementFactory/getThreadMXBean)
           thread-id (.getId (Thread/currentThread))
           warnings (java.io.StringWriter.)]
       (is (.isThreadAllocatedMemorySupported allocation-bean))
       (is (.isThreadAllocatedMemoryEnabled allocation-bean)
           "disabled allocation counters must not satisfy the cost bound")
       (is (seq ids) "the measured namespace must contain public functions")
       (is (identical? projection
                       (:seon.schema/projection
                        @(:seon.sci.eval/projection-state (meta database)))))
       (is (= (d/committed-value-identity raw)
              (d/committed-value-identity database)))
       (is (= :datahike.cache.outcome/hit
              (get-in (d/q-with-evidence ids-query database 'my.message)
                      [:datahike.query/cache-evidence :datahike.cache/outcome])))
       (binding [*err* warnings]
         (without-handed-projection
          (fn []
            (doseq [selector [@#'sci.eval/program-documentation-selector '[*]]]
              ;; Warm the actual per-projection codecs, then include supplier
              ;; acquisition in every measured call. No dynamic projection is
              ;; present at either acquisition or decoding.
              (dotimes [_ 3]
                (db/pull-many (db/supplied-database-value environment) selector ids))
              (dotimes [_ 3]
                (let [before (.getThreadAllocatedBytes allocation-bean thread-id)
                      started (System/nanoTime)
                      rows (db/pull-many (db/supplied-database-value environment)
                                         selector ids)
                      elapsed (- (System/nanoTime) started)
                      allocated (- (.getThreadAllocatedBytes allocation-bean thread-id)
                                   before)]
                  (is (= (count ids) (count rows)))
                  (is (every? :seon.fn/sym rows))
                  (is (< allocated 10000000) (str "allocated bytes: " allocated))
                  (is (< elapsed 20000000) (str "elapsed ns: " elapsed))))))))
       (is (= "" (str warnings)))))))

(deftest uncarried-read-reports-one-projection-fallback-per-call
  (test-support/with-database
   (fn [connection]
     (let [database @connection
           validate-refusal (schema/projection-validator
                             (schema/handed-projection) :seon.schema/validation-refusal)
           ids (d/q '[:find [?function ...]
                      :where [?function :seon.fn/sym my.message/send]]
                    database)]
       (is (seq ids))
       (without-handed-projection
        (fn []
          (dotimes [_ 2]
            (let [warnings (java.io.StringWriter.)
                  rows (binding [*err* warnings]
                         (db/pull-many database
                                       @#'sci.eval/program-documentation-selector ids))
                  lines (str/split-lines (str warnings))]
              (is (validate-refusal rows))
              (is (= :seon.schema/projection (:seon.schema/expected-value rows)))
              (is (= {:seon.db/operation 'seon.db/pull-many}
                     (:seon.schema/refused-value rows)))
              (is (= 1 (count lines)))
              (is (str/includes? (first lines)
                                 "seon.db/projection-fallback caller= seon.db/pull-many"))
              (is (str/includes? (first lines) "count=1"))))))))))

(deftest temporal-reads-use-the-carried-origins-projection
  (test-support/with-database
   (fn [connection]
     (let [environment (env/environment
                        {:seon.boot/cluster-name "documentation-temporal"
                         :seon.db/connection connection
                         :seon.schema/projection (schema/handed-projection)})
           database (db/supplied-database-value environment)
           state (:seon.sci.eval/projection-state (meta database))
           warnings (java.io.StringWriter.)]
       (binding [*err* warnings]
         (without-handed-projection
          (fn []
            (doseq [view [(db/history database)
                          (db/as-of database (:max-tx database))
                          (db/since database 0)]]
              (is (identical? database (db/schema-database view)))
              (is (identical? state (:seon.sci.eval/projection-state (meta view)))))
            (is (= {:seon.ns/name 'my.message}
                   (db/pull (db/as-of database (:max-tx database))
                            [:seon.ns/name] [:seon.ns/name 'my.message]))))))
       (is (= "" (str warnings)))))))

(deftest carried-queries-stay-within-the-five-millisecond-budget
  (test-support/with-database
   (fn [connection]
    (let [database (db/db connection)]
     (without-handed-projection
      (fn []
        (let [expected [:seon.message/message]
              ;; Warm the exact query plan being measured on both paths.
              ;; Warming schema-family-query leaves the wrapped decode plan
              ;; for this different :in form cold on its first timed sample.
              _ (db/q uncached-schema-family-query database -1)
              _ (d/q uncached-schema-family-query database -2)
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
          (println {::stage :carried-query-cost
                    ::samples 10 ::raw-nanos raw-total
                    ::wrapped-nanos wrapped-total
                    ::wrapped-raw-ratio (/ (double wrapped-total) raw-total)})
          (is (every? #(= expected (::value %)) wrapped))
          (is (every? #(<= (::elapsed-nanos %) 5000000) wrapped)
              (str "ten seon.db/q calls took " wrapped-total
                   " ns versus " raw-total " ns raw")))))))))

(deftest relation-only-queries-need-no-database-schema
  (is (= [:sample/fail]
         (db/q '[:find [?key ...] :in [[?key ?passed]]
                 :where [(false? ?passed)]]
               [[:sample/pass true] [:sample/fail false]]))))

(deftest handed-family-query-stays-below-five-milliseconds
  (test-support/with-database
   (fn [connection]
     ;; The canonical fixture binds the same cluster-owned projection-state
     ;; that seon.sci.eval binds around an SCI evaluation.
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

(deftest transaction-success-and-refusal-shapes-are-declared
  (test-support/with-database
   (fn [connection]
     (let [projection (schema/handed-projection)
           options {:registry (:seon.schema.projection/registry projection)}
           report (db/transact! connection [])
           result (binding [db/*conn* connection]
                    (db/transact! []))
           refusal (db/transact! connection [{:seon.agent/id 42}])
           findings (->> (seon.fn/contract-findings (db/db connection))
                         (filterv #(= "seon.db" (namespace (:seon.fn/sym %)))))
           transaction-findings
           (filterv #(#{'seon.db/transact! 'seon.db/transact-call
                        'seon.db/transaction-result}
                      (:seon.fn/sym %))
                    findings)]
       (println {:seon.db-test/contract-finding-count (count findings)
                 :seon.db-test/contract-finding-kinds
                 (frequencies (map :seon.fn.contract/finding findings))
                 :seon.db-test/transaction-contract-findings
                 transaction-findings})
       (is (m/validate :seon.db/transaction-report report options)
           (pr-str (m/explain :seon.db/transaction-report report options)))
       (is (m/validate :seon.db/transaction-result result options)
           (pr-str (m/explain :seon.db/transaction-result result options)))
       (is (m/validate :seon.error/value refusal options))
       (is (string? (:seon.db.write.attempt/request-id refusal)))
       (is (= [] transaction-findings))
       (is (not (instance? Throwable refusal)))))))

(deftest transaction-wrappers-cannot-hide-a-classified-refusal
  (test-support/with-database
   (fn [connection]
     (let [refusal {:seon.error/at #inst "2026-09-20T00:00:00Z"
                    :seon.error/layer :seon.turn/transition
                    :seon.error/operation 'seon.turn/open-call
                    :seon.turn/rule
                    :seon.turn/no-such-agent}
           wrapped (ex-info "classified transition refusal"
                            refusal
                            (ex-info "transaction wrapper"
                                     {:seon.turn/id nil}))]
       (with-redefs [d/transact!
                     (fn [& _]
                       (let [result (datahike.tools/throwable-promise)]
                         (result wrapped)
                         result))]
         (let [result (db/transact! connection [])]
           (is (= (assoc refusal :seon.error/message "classified transition refusal")
                  (select-keys result (conj (vec (keys refusal)) :seon.error/message)))
               "the classified exception supplies its message; a deeper wrapper cannot replace it")
           (is (schema/valid-candidate-value? (schema/handed-projection)
                                             :seon.turn/refused-error result))
           (is (schema/valid-candidate-value? (schema/handed-projection)
                                             :seon.db.write/validation-refusal result)))))
     (test-support/transacted! connection
                               [{:seon.agent/id "busy-agent"}])
     (is (map? (db/transact!
                connection
                (turn/open-tx
                 {:seon.turn/id "already-open" :seon.turn/agent [:seon.agent/id "busy-agent"] :seon.turn/opened-tx "datomic.tx"}))))
     (let [result
           (db/transact!
            connection
            (turn/open-tx
             {:seon.turn/id "contending-run" :seon.turn/agent [:seon.agent/id "missing-agent"] :seon.turn/opened-tx "datomic.tx"}))]
       (is (schema/valid-candidate-value? (schema/handed-projection)
                                         :seon.turn/refused-error result))
       (is (= :seon.turn/no-such-agent
              (:seon.turn/rule result))
           "a real invalid opening preserves its writer rule")
       (is (= "run transition refused: no-such-agent"
              (:seon.error/message result)))
       (is ((schema/projection-validator (schema/handed-projection) :seon.error/value) result)
           "classified transaction refusals satisfy the same error contract as their callers")))))

(defn- with-codec-database
  [options body]
  (test-support/with-database
   options
   (fn [connection]
     (db/carry-connection-projection-state!
      connection (sci.eval/projection-state @connection fixture-projection))
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
         (is (true? (:seon.db/invalid-read result)))
         (is (= rule
                (get-in result
                        [:seon.error/data
                         :seon.db/dependency-data
                         ::schema.datahike/rule]))))))))

(deftest instrumented-wildcard-pull-keeps-unparsed-database-fields-ordinary
  (test-support/with-database
   (fn [connection]
     (test-support/transacted! connection [{:seon.agent/id "wildcard-agent"}])
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
     (test-support/transacted! connection
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
    (is (schema/valid-candidate-value? (schema/handed-projection)
                                      :seon.schema/validation-refusal result))
    (is (= :seon.db/connection (:seon.schema/expected-value result)))
    (is (nil? (:seon.schema/refused-value result)))
    (is (str/includes? (:seon.error/message result)
                       "(seon.cluster.boot/connection \"default\")"))
    (is (= 'seon.db/*conn*
           (get-in result [:seon.error/data :seon.db/binding])))))

(deftest every-public-read-preserves-an-upstream-database-error
  (let [upstream (db/projection-fallback 'seon.db/q)
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
       (is (every? #((schema/projection-validator (schema/handed-projection) :seon.db/captured-read) %)
                   @entries))))))

(deftest retained-read-evidence-invalidates-only-on-a-depended-attribute
  (test-support/with-database
   (fn [connection]
     (test-support/seed-cluster! connection "evidence-a")
     (let [captured (atom [])]
       (binding [db/*read-evidence-sink* captured]
         (db/q '[:find [?name ...]
                 :where [_ :seon.cluster/name ?name]]
               @connection))
       (let [evidence (db/read-evidence @captured)]
         (is (every? #((schema/projection-validator (schema/handed-projection) :seon.db/read-evidence) %)
                     evidence))
         (test-support/transacted! connection
                                   [{:seon.agent/id "unrelated-agent"}])
         (is (db/read-evidence-current? @connection evidence)
             "an unrelated attribute revision retains the renderer read")
         (test-support/transacted!
          connection
          [[:db/add [:seon.cluster/name "evidence-a"] :seon.cluster/name "evidence-b"]])
         (is (not (db/read-evidence-current? @connection evidence))
             "a depended attribute revision makes the retained read stale"))))))

(deftest collection-bound-query-evidence-covers-every-input-attribute
  (test-support/with-database
   (fn [connection]
     (let [attributes [:seon.agent/id :seon.cluster/name]
           capture (fn []
                     (let [captured (atom [])]
                       (binding [db/*read-evidence-sink* captured]
                         (db/q '[:find ?attribute (count ?entity)
                                 :in $ [?attribute ...]
                                 :where [?entity ?attribute _]]
                               @connection attributes))
                       (db/read-evidence @captured)))
           evidence (capture)
           patterns (mapcat :seon.db/read-index-patterns
                            (mapcat #(get-in % [:datahike.read/dependency-plan
                                                :datahike.query.dependency/sources]) evidence))]
       (is (seq evidence))
       (is (= (set attributes) (set (map :seon.db/pattern-attribute patterns))))
       (is (every? :seon.db/pattern-attribute patterns)
           "no unbound all-attribute pattern survives a collection input")
       (test-support/transacted! connection
                                 [[:db/add [:seon.ns/name 'seon.flow] :seon.ns/doc
                                   "An unrelated attribute changed."]])
       (is (db/read-evidence-current? @connection evidence))
       (doseq [attribute attributes]
         (let [before (capture)]
           (case attribute
             :seon.agent/id (test-support/transacted!
                            connection [{:seon.agent/id "bound-agent"}])
             :seon.cluster/name (test-support/seed-cluster! connection "bound-cluster"))
           (is (false? (db/read-evidence-current? @connection before))
               (str "the bound attribute must invalidate its evidence: " attribute))))))))

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
       (test-support/transacted!
                    connection
                    [{:seon.agent/id "db-test-recipient"}
                     {:seon.message/id "semantic-replay-unrelated"
                      :seon.message/to [:seon.agent/id "db-test-recipient"]
                      :seon.message/content "unrelated"}])
       (is (true? (db/read-evidence-current? @connection durable))
           "an equal wildcard replay survives an unrelated transaction")
       (is (true? (db/read-evidence-current? @connection process-local))
           "an equal bounded replay keeps the process-local read current")
       (test-support/transacted! connection
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
         (test-support/transacted!
                      connection
                      [{:seon.agent/id "db-test-recipient"}
                       {:seon.message/id (second subject)
                        :seon.message/to [:seon.agent/id "db-test-recipient"]
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
             (test-support/transacted!
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
     (test-support/transacted! connection
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
       (test-support/transacted! connection
                                 [[:db/add child-id ::component-value "after"]])
       (is (false? (db/read-evidence-current? @connection evidence))
           "a component-child-only change makes the retained pull stale")
       (let [changed-captured (atom [])]
         (binding [db/*read-evidence-sink* changed-captured]
           (db/pull @connection
                    [::component-child]
                    [::component-root-id "root"]))
         (let [changed-evidence (db/read-evidence @changed-captured)]
           (test-support/transacted! connection
                                     [[:db/retractEntity child-id]])
           (is (false? (db/read-evidence-current?
                        @connection changed-evidence))
               "retracting the owned child makes the expanded pull stale")))))))

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
     (test-support/seed-cluster! connection "four-view")
     (let [views (four-view-table @connection)]
       (is (= #{:current :as-of :since :history} (set (keys views))))
       (doseq [[view database] views]
         (testing (clojure.core/name view)
           (testing "basis-t answers with a transaction number"
             (is (int? (db/basis-t database))))
           (testing "database-value-identity answers, never throws"
             (let [answer (db/database-value-identity database)]
               (is (or ((schema/projection-validator (schema/handed-projection) :seon.db/database-value-identity) answer)
                       (and (schema/valid-candidate-value? (schema/handed-projection)
                                                           :seon.schema/validation-refusal answer)
                            (= :seon.db/database-value-identity
                               (:seon.schema/expected-value answer))))
                   "a committed identity or a flat error value")))
           (testing "read-evidence produces a well-formed dependency revision"
             (let [captured (atom [])]
               (binding [db/*read-evidence-sink* captured]
                 (db/q '[:find [?name ...]
                         :where [_ :seon.cluster/name ?name]]
                       database))
               (let [evidence (db/read-evidence @captured)]
                 (is (seq evidence) "the read was captured")
                 (is (every? #((schema/projection-validator (schema/handed-projection) :seon.db/read-evidence) %)
                             evidence)
                     "no view shape may violate read-evidence's contract")
                 (is (true? (db/read-evidence-current? database evidence))
                     "the very value that produced the evidence is current"))))))))))

(deftest an-as-of-view-is-keyed-on-its-own-fixed-point
  (test-support/with-database
   (fn [connection]
     (test-support/seed-cluster! connection "fixed-point-a")
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
     (let [effective config/defaults
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
     (test-support/transacted!
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
       (is (some? (:db-after accepted))
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

(defn- seed-open-turn! [connection turn-id]
  (test-support/seed-cluster! connection turn-id)
  (test-support/transacted!
   connection
   (agent/creation-tx {:seon.agent/id turn-id
                       :seon.ns/name (symbol (str "my.agents." turn-id))
                       :seon.cluster/name turn-id}))
  (test-support/transacted!
   connection
   (turn/open-tx {:seon.turn/id turn-id
                  :seon.turn/agent [:seon.agent/id turn-id]
                  :seon.turn/opened-tx "datomic.tx"})))

(deftest unique-rejection-names-the-existing-owner-as-data
  (test-support/with-database
   (fn [connection]
     (seed-open-turn! connection "db-conflict-turn")
     (let [owner-id (id/evaluation "db-conflict-turn" 0)
           contender-id (id/evaluation "db-conflict-turn" 1)
           request {:seon.turn/id "db-conflict-turn"
                    :seon.cluster.eval/at (java.util.Date.)}]
       (test-support/transacted!
        connection
        (into (turn/receipt-start-tx
               (assoc request :seon.cluster.eval/ordinal 0))
              (turn/receipt-start-tx
               (assoc request :seon.cluster.eval/ordinal 1))))
       (let [before (db/basis-t @connection)
             contender (:db/id (db/pull @connection [:db/id]
                                       [:seon.cluster.eval/id contender-id]))
             rejected
             (binding [db/*conn* connection]
               (db/transact!
                [[:db/add contender :seon.cluster.eval/id owner-id]]))
             conflict (:seon.error/data rejected)]
         (is (= before (db/basis-t @connection)))
         (is (= contender (:db/id (db/pull @connection [:db/id]
                                          [:seon.cluster.eval/id contender-id]))))
         (is (schema/valid-candidate-value? (schema/handed-projection)
                                           :seon.db.write/validation-refusal rejected))
         (is (string? (:seon.db.write.attempt/request-id rejected)))
         (is (= {:error :transact/unique
                 :attribute :seon.cluster.eval/id}
                (select-keys conflict [:error :attribute])))
         (is (instance? datahike.datom.Datom (:datom conflict)))
         (is (= {:seon.db/conflict-attribute
                 :seon.cluster.eval/id
                 :seon.db/conflict-owner
                 [:seon.cluster.eval/id owner-id]}
                (select-keys conflict
                             [:seon.db/conflict-attribute
                              :seon.db/conflict-owner])))
         (is (str/includes? (:seon.error/message rejected)
                            owner-id))
         (is (not (str/includes? (:seon.error/message rejected)
                                 "ExceptionInfo")))
         (is (str/includes? (db/render-rejection-ai rejected)
                            "seon.db/transact! refused transaction data"))
         (is (str/includes? (db/render-rejection-ai rejected) owner-id))
         (is (str/includes? (pr-str (db/render-rejection-html rejected))
                            owner-id))
         (is (= 'seon.db/render-rejection-ai
                (->> (schema/matching-shapes rejected)
                     (some #(when (= :seon.db.write/validation-refusal
                                     (:seon.schema/key %))
                              (:seon.render/ai %)))))))))))

(deftest non-unique-writer-rejections-retain-their-datahike-data
  (test-support/with-database
   (fn [connection]
     (test-support/transacted!
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
       (is (schema/valid-candidate-value? (schema/handed-projection)
                                         :seon.db.write/validation-refusal rejected))
       (is (= {:error :transact/cas
               :expected "not-the-current-id"
               :new "db-cas-replacement"}
              (select-keys data [:error :expected :new])))
       (is (instance? datahike.datom.Datom (:old data)))))))

(deftest an-agent-write-that-does-not-deliver-refuses-at-the-declared-bound
  ;; Ruling 1r (owner, 2026-09-18): the dial bounds AGENT/turn writes. The
  ;; provenance user this transaction carries is what selects it.
  (test-support/with-database
   (fn [connection]
     (let [cluster-name "bounded-write-deref"
           write-time-limit-ms 25
           started (CountDownLatch. 1)
           release (CountDownLatch. 1)
           old-writer (:writer @connection)]
       (test-support/transacted!
        connection [{:seon.agent/id "bounded-write-deref-agent"}])
       (test-support/apply-config!
        connection cluster-name
        {:seon.config.db/write-time-limit-ms write-time-limit-ms})
       (test-support/await-event!
        (datahike.writer/shutdown old-writer)
        "the canonical fixture writer to stop before replacement")
       (let [blocked-writer
             (datahike.writer/create-writer
              {:backend :self
               :write-fn-map
               {'transact!
                (fn [database request]
                  (.countDown started)
                  (when-not (.await release
                                    test-support/event-backstop-seconds
                                    TimeUnit/SECONDS)
                    (throw
                     (ex-info "The test did not release its blocked writer."
                              {:seon.test/event :blocked-writer-release})))
                  (datahike.writing/transact! database request))}}
              connection)]
         (swap! (:wrapped-atom connection) assoc :writer blocked-writer)
         (try
           (let [before (db/basis-t @connection)
                 outcome (future
                           (db/transact!
                            connection
                            {:tx-data [{:seon.agent/id "bounded-write-deref-target"}]
                             :tx-meta {:seon.db/user
                                       [:seon.agent/id "bounded-write-deref-agent"]}}))]
             (test-support/await-event!
              started "the real Datahike writer to enter the blocked transaction")
             (let [refusal
                   (test-support/await-event!
                    outcome "the declared database write bound to fire")
                   data (:seon.error/data refusal)]
               (is (true? (:seon.db/transaction-outcome-unknown refusal)))
               (is (= write-time-limit-ms
                      (:seon.config.db/write-time-limit-ms data)))
               (is (<= write-time-limit-ms
                       (:seon.db/write-wait-elapsed-ms data)
                       (* 1000 test-support/event-backstop-seconds)))
               (is (= (db/connection-identity connection)
                      (:seon.db/connection-identity data)))
               (is (= (:branch (:config @connection))
                      (:seon.store/branch data)))
               (is (= before (db/basis-t @connection))
                   "the writer is still blocked when the caller's bound fires"))
             (.countDown release)
             (test-support/await-event!
              connection "the timed-out transaction to settle later"
              #(> (db/basis-t %) before))
             (is (= "bounded-write-deref-target"
                    (:seon.agent/id
                     (db/pull @connection
                              [:seon.agent/id]
                              [:seon.agent/id "bounded-write-deref-target"])))
                 "the unknown transaction may commit after the caller stops waiting"))
           (finally
             (.countDown release))))))))

(deftest a-system-write-carries-no-per-write-bound
  ;; Ruling 1r, the other half: "root access (system) for no limits, and then
  ;; agents; if they fail, root can re-run whatever transaction it is." A write
  ;; whose provenance names no agent waits for its own operation's lifecycle
  ;; deadline; the database dial does not stop it. The same blocked writer that
  ;; refuses an agent write above therefore does not refuse this one.
  (test-support/with-database
   (fn [connection]
     (let [cluster-name "unbounded-system-write"
           write-time-limit-ms 25
           started (CountDownLatch. 1)
           release (CountDownLatch. 1)
           old-writer (:writer @connection)]
       (test-support/apply-config!
        connection cluster-name
        {:seon.config.db/write-time-limit-ms write-time-limit-ms})
       (test-support/await-event!
        (datahike.writer/shutdown old-writer)
        "the canonical fixture writer to stop before replacement")
       (let [blocked-writer
             (datahike.writer/create-writer
              {:backend :self
               :write-fn-map
               {'transact!
                (fn [database request]
                  (.countDown started)
                  (when-not (.await release
                                    test-support/event-backstop-seconds
                                    TimeUnit/SECONDS)
                    (throw
                     (ex-info "The test did not release its blocked writer."
                              {:seon.test/event :blocked-writer-release})))
                  (datahike.writing/transact! database request))}}
              connection)]
         (swap! (:wrapped-atom connection) assoc :writer blocked-writer)
         (try
           (let [outcome (future
                           (db/transact!
                            connection
                            [{:seon.agent/id "unbounded-system-write-target"}]))]
             (test-support/await-event!
              started "the real Datahike writer to enter the blocked transaction")
             (is (= ::still-waiting
                    (deref outcome (* 40 write-time-limit-ms) ::still-waiting))
                 "a system write is not refused at the agent dial")
             (.countDown release)
             (let [report (test-support/await-event!
                           outcome "the unbounded system write to settle")]
               (is ((schema/projection-validator (schema/handed-projection) :seon.db/transaction-report) report)
                   "the system write settles as a report, never a bound refusal")
               (is (= "unbounded-system-write-target"
                      (:seon.agent/id
                       (db/pull @connection
                                [:seon.agent/id]
                                [:seon.agent/id "unbounded-system-write-target"]))))))
           (finally
             (.countDown release))))))))

(deftest a-throwing-datahike-listener-cannot-strand-a-committed-write
  ;; THE PROPERTY IS A COMPLETION EVENT, NOT A DEADLINE. Datahike delivers the
  ;; result promise BEFORE it notifies any listener
  ;; (`reference-code/datahike/src/datahike/writer.cljc:410` delivers, `:427`
  ;; notifies), so a throwing listener structurally cannot strand the write.
  ;; An earlier shape asserted the report beat a tuned 250 ms
  ;; `:seon.config.db/write-time-limit-ms` this test applied itself; the cold
  ;; gate's first write took 254 ms and the tuned constant — standing in for
  ;; the observable event, which AGENTS §2.3 forbids — reported a bound
  ;; refusal instead of a defect. The shipped write bound now governs and the
  ;; ONE clock is the fixture's declared `event-backstop-seconds`, which names
  ;; the wait it failed.
  (test-support/with-database
   (fn [connection]
     (let [listener-key ::throwing-listener
           listener-error (ex-info "synthetic listener failure"
                                   {:seon.test/failure :throwing-listener})
           diagnostic (atom nil)
           log-fn
           (fn [_logger-ns _coordinates level id lazy-data]
             (let [data (force (force lazy-data))
                   payload (or (:msg data) (:data data))]
               (when (= :datahike/listener-error id)
                 (reset! diagnostic {:seon.test/level level
                                     :seon.test/payload payload}))))]
       (datahike/listen! connection listener-key
                         (fn [_] (throw listener-error)))
       (try
         (binding [trove/*log-fn* log-fn]
           (let [submission
                 (future
                   (db/transact!
                    connection
                    [{:seon.agent/id "throwing-listener-agent"}]))
                 report
                 (test-support/await-event!
                  submission
                  "seon.db/transact! to realize its answer while a listener throws")
                 logged
                 (test-support/await-event!
                  diagnostic
                  "Datahike's throwing-listener diagnostic"
                  some?)]
             (is (contains? report :db-after)
                 (str "the realized answer is the committed report: "
                      (pr-str report)))
             (is (not (true? (:seon.db/transaction-outcome-unknown report)))
                 "the declared write bound never stands in for the completion event")
             (is (= :error (:seon.test/level logged)))
             (is (= listener-key
                    (get-in logged
                            [:seon.test/payload :listener-key])))
             (is (identical? listener-error
                             (get-in logged
                                     [:seon.test/payload :exception])))
             (is (= "throwing-listener-agent"
                    (:seon.agent/id
                     (db/pull @connection
                              [:seon.agent/id]
                              [:seon.agent/id "throwing-listener-agent"])))
                 "the report and durable fact describe the same commit")
             (let [next-report
                   (test-support/transacted!
                    connection
                    [{:seon.agent/id "after-throwing-listener-agent"}])]
               (is (contains? next-report :db-after)
                   "the write after a thrown listener commits through the same writer")
               (is (< (db/basis-t (:db-before next-report))
                      (db/basis-t (:db-after next-report)))
                   "the following write advanced the branch head"))))
         (finally
           (datahike/unlisten! connection listener-key)))))))

(deftest temporal-reads-use-explicit-and-ambient-database-values
  (test-support/with-database
   (fn [connection]
     (let [before @connection
           ;; THE VOCABULARY'S OWN NAME. `(:t database)` is not a key on a
           ;; Datahike database value, so this read was nil — a present nil
           ;; that both `as-of` calls accepted while comparing two views of
           ;; nothing, until the armed contract asked what a time point is.
           before-t (db/basis-t before)]
       (test-support/transacted!
                    connection
                    [{:seon.agent/id "db-test-recipient"}
                     {:seon.message/id "db-test-temporal"
                      :seon.message/to [:seon.agent/id "db-test-recipient"]
                      :seon.message/content "temporal"}])
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
          (is (schema/valid-candidate-value? (schema/handed-projection)
                                            :seon.config/error result))
          (is (= :seon.config.db/keep-history? (:seon.config/error-key result)))
          (is (false? (get-in result [:seon.error/data :seon.config.db/keep-history?])))
          (is (= :seon.db/temporal-read
                 (get-in result [:seon.error/data :seon.db/operation])))
          (is (not (contains? (:seon.error/data result)
                              :seon.db/dependency-data))))
        (binding [db/*conn* connection]
          (is (schema/valid-candidate-value? (schema/handed-projection)
                                            :seon.config/error (db/history)))))
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
       (is (true? (:seon.db/invalid-read result)))
       (is (string? (:seon.error/message result)))
       (is (map? (:seon.error/data result))))
     ;; AN ENTITY IDENTIFIER IS NOT A BARE STRING, and the declared contract
     ;; says so, so under the contracts every cluster arms the contract
     ;; refuses first — same crossing, same function named, and the agent
     ;; still reads a flat value because its forms cross the SCI kernel
     ;; (`my.turn-test`, `my.message-test` prove that boundary).
     (let [refusal (test-support/refusal-data
                    #(db/pull-many @connection schema-pattern ["not-an-eid"]))]
       (is ((schema/projection-validator (schema/handed-projection) :seon.instrument/contract-error) refusal))
       (is (= :input (:seon.instrument/check refusal)))
       (is (= 'seon.db/pull-many
              (:seon.error/operation refusal)))
       (is (string? (:seon.error/message refusal)))))))

(deftest invalid-read-identities-are-diagnostics-never-absence
  (test-support/with-database
   (fn [connection]
     (test-support/transacted! connection
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
         (is (true? (:seon.db/invalid-read unknown-attribute)))
         (is ((schema/projection-validator (schema/handed-projection) :seon.db/invalid-read-error) unknown-attribute))
         (is (= 'seon.db/q
                (get-in unknown-attribute
                        [:seon.error/operation])))
         (is (= :seon.agent/idd
                (get-in unknown-attribute
                        [:seon.error/data :seon.error/member])))
         (is (some #{:seon.agent/id}
                   (get-in unknown-attribute
                           [:seon.error/data :seon.db/registered-candidates]))))
       (testing "wrong-typed lookup refs retain the installed declaration"
         (doseq [[operation result]
                 [['seon.db/pull wrong-pull]
                  ['seon.db/entity wrong-entity]]]
           (is (true? (:seon.db/invalid-read result)))
           (is (= operation
                  (get-in result
                          [:seon.error/operation])))
           (is (= :db.type/string
                  (get-in result
                          [:seon.error/expected
                           :db/valueType])))
           (is (= {:seon.db/attribute :seon.agent/id
                   :seon.db/value 'identity-admission-present}
                  (get-in result
                          [:seon.error/offending])))))
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

(deftest missing-reference-diagnostics-describe-the-declared-value
  (test-support/with-database
   (fn [connection]
     (doseq [[row attribute]
             [[{:seon.cluster/name "missing-reference-cluster"} :seon.cluster/config]
              [{:seon.turn/id "missing-reference-turn"} :seon.turn/agent]
              [{:seon.cluster.eval/id "missing-reference-evaluation"}
               :seon.cluster.eval/run]]]
       (let [before (db/basis-t (db/db connection))
             refusal (db/transact! connection [row])
             problem (first (get-in refusal [:seon.error/data :seon.error/problems]))]
         (is (string? (:seon.db.write.attempt/request-id refusal)))
         (is (= attribute (:seon.db/attribute refusal)))
         (is (= (str "the required key " attribute
                     " with an integer or a string or a tuple with 2 entries or a map")
                (:seon.error/expected-description problem)))
         (is (= before (db/basis-t (db/db connection)))))))))

(deftest uninstalled-pull-attributes-refuse-without-writing
  (test-support/with-database
   (fn [connection]
     (seed-open-turn! connection "diagnostic-pull-run")
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
         (is (true? (:seon.db/invalid-read result)))
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
     (test-support/transacted! connection
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
             (is (not (true? (:seon.db/invalid-read installed)))
                 "an installed attribute is never classified as uninstalled")
             (is (true? (:seon.db/invalid-read uninstalled)))
             (is (= :seon.error/unknown
                    (get-in uninstalled [:seon.error/expected :seon.db/installed-declaration]))))))))))

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
         (is (true? (:seon.db/invalid-read result)))
         (if (= :query member)
           (is (= :parser/find
                  (get-in result [:seon.error/data :seon.db/dependency-data :error])))
           (is (nil? (:seon.db/missing-request-member result))))
         (is ((schema/projection-validator (schema/handed-projection) :seon.db/invalid-read-error) result))
         (is (= operation
                (get-in result
                        [:seon.error/operation])))
         (when-not (= :query member)
           (is (= member
                  (get-in result [:seon.error/data :seon.error/member])))))
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
           (is ((schema/projection-validator (schema/handed-projection) :seon.instrument/contract-error) refusal))
           (is (= :input (:seon.instrument/check refusal)))
           (is (= operation
                  (get-in refusal [:seon.error/operation])))
           (is (= path
                  (first (:seon.instrument/problem-paths refusal))))))
       (let [malformed (first (last cases))]
         (is (= [:entity :attribute :value :transaction :added]
                (get-in malformed
                        [:seon.error/expected])))
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
            (is (some? (:db-after explicit))
                "the writing cluster's own connection still commits")
            (is (some? (:db-after elided))
                "the elided arity still commits through the writing custody")
            (is (schema/valid-candidate-value? (schema/handed-projection)
                                              :seon.db.write/validation-refusal refused))
            (is (= [{:seon.agent/id "foreign"}]
                   (get-in refused [:seon.error/data :seon.db.write.attempt/transaction]))
                "the refusal carries the actual submitted write")
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

(deftest final-entity-keyword-sets-are-not-lookup-refs
  (test-support/with-database
   (fn [connection]
     (let [row (test-support/program-fn-row (db/db connection) 'seon.db/final-keyword-set-fixture "(defn final-keyword-set-fixture [] true)")
           function-id [:seon.fn/sym (:seon.fn/sym row)]
           keywords #{:seon.agent/id :seon.db/db}]
       (test-support/transacted!
        connection
        [row
         [:db/add function-id :seon.fn/keywords :seon.agent/id]
         [:db/add function-id :seon.fn/keywords :seon.db/db]])
       (is (= keywords (set (:seon.fn/keywords
                            (db/pull (db/db connection) [:seon.fn/keywords]
                                     function-id)))))))))

(deftest all-transaction-grammars-validate-the-resulting-entity
  (test-support/with-database
   (fn [connection]
     (let [complete {:seon.schedule/id "f2-existing"
                     :seon.schedule/expression "0 4 * * *"
                     :seon.schedule/zone-id "UTC"}]
       (test-support/transacted! connection [complete])
       (test-support/transacted! connection
                                 [{:seon.schedule/id "f2-existing"
                                   :seon.schedule/expression "7 4 * * *"}])
       (is (= "UTC" (:seon.schedule/zone-id
                      (db/pull (db/db connection) '[*]
                               [:seon.schedule/id "f2-existing"]))))
       (is (= "7 4 * * *" (:seon.schedule/expression
                           (db/pull (db/db connection) '[*]
                                    [:seon.schedule/id "f2-existing"]))))
       (doseq [transaction [[{:seon.schedule/id "f2-invalid"
                              :seon.schedule/expression "0 4 * * *"}]
                            [[:db/add -1 :seon.schedule/id "f2-invalid"]
                             [:db/add -1 :seon.schedule/expression "0 4 * * *"]]
                            [(assoc complete :seon.schedule/id "f2-valid-mixed")
                             [:db/add -1 :seon.schedule/id "f2-invalid"]
                             [:db/add -1 :seon.schedule/expression "0 4 * * *"]]]]
         (let [basis (:max-tx (db/db connection))
               refusal (db/transact! connection transaction)]
           (is (string? (:seon.db.write.attempt/request-id refusal)) (pr-str refusal))
           (is (= basis (:max-tx (db/db connection))))
           (is (= {:seon.schedule/id "f2-invalid"}
                  (get-in refusal [:seon.error/data :seon.db/entity])))
           (is (= :seon.schedule/zone-id (:seon.db/attribute refusal)))
           (is (= :seon.error/unknown (:seon.db/offending refusal)))
           (is (nil? (:db/id (db/pull (db/db connection) '[:db/id]
                                     [:seon.schedule/id "f2-invalid"]))))
           (is (nil? (:db/id (db/pull (db/db connection) '[:db/id]
                                     [:seon.schedule/id "f2-valid-mixed"]))))))
       (let [basis (:max-tx (db/db connection))
             refusal (db/transact!
                      connection
                      [[:db/retract [:seon.schedule/id "f2-existing"]
                        :seon.schedule/zone-id "UTC"]])]
         (is (string? (:seon.db.write.attempt/request-id refusal)))
         (is (= basis (:max-tx (db/db connection)))))
       (let [basis (db/basis-t (db/db connection))
             refusal (db/transact!
                      connection
                      [[:db.fn/call
                        (fn [_]
                          [[:db/add [:seon.schedule/id "f2-existing"]
                            :seon.schedule/zone-id ""]
                           [:db/add [:seon.schedule/id "f2-existing"]
                            :seon.schedule/zone-id "UTC"]])]])]
         (is (string? (:seon.db.write.attempt/request-id refusal))
             "expanded invalid assertions refuse even when a later operation repairs the row")
         (is (= :seon.schedule/zone-id (:seon.db/attribute refusal)))
         (is (= basis (db/basis-t (db/db connection))) "the expanded transaction is atomic"))
       (let [calls (atom 0)]
         (test-support/transacted!
          connection
          [{:seon.schedule/id "f2-composed" :seon.schedule/expression "0 4 * * *"}
           [:db.fn/call
            (fn [_]
              (swap! calls inc)
              [[:db.fn/call
                (fn [_]
                  [[:db/add [:seon.schedule/id "f2-composed"]
                    :seon.schedule/zone-id "UTC"]])]])]])
         (is (= 1 @calls))
         (is (= "UTC" (:seon.schedule/zone-id
                        (db/pull (db/db connection) '[*]
                                 [:seon.schedule/id "f2-composed"])))))))))

(deftest a-create-is-validated-complete-while-an-upsert-validates-the-merged-row
  (test-support/with-database
   (fn [connection]
     (let [existing (db/pull (db/db connection) '[*] [:seon.fn/sym (quote seon.id/id)])]
       (is (some? (:db/id existing)) "the canonical fixture carries a complete program row")
       (test-support/transacted! connection
                                 [{:seon.fn/sym (quote seon.id/id)
                                   :seon.fn/doc "updated documentation"}])
       (let [updated (db/pull (db/db connection) '[*] [:seon.fn/sym (quote seon.id/id)])]
         (is (= "updated documentation" (:seon.fn/doc updated))
             "a sparse upsert of an existing row is the ruled partial-upsert behaviour")
         (is (= (:db/id (:seon.fn/ns existing)) (:db/id (:seon.fn/ns updated)))
             "the merged row still carries the keys the submission omitted")))
     (doseq [[entity identities]
             [[{:seon.fn/sym (quote seon.source.test/incomplete) :seon.fn/doc "incomplete"}
               {:seon.fn/sym (quote seon.source.test/incomplete)}]
              [{:seon.test/sym (quote seon.source.test/incomplete-test)}
               {:seon.test/sym (quote seon.source.test/incomplete-test)}]]]
       (let [basis (db/basis-t (db/db connection))
             refusal (db/transact! connection [entity])]
         (is (string? (:seon.db.write.attempt/request-id refusal)) (pr-str refusal))
         (is (= :seon.program/analyzed-source-digest (:seon.db/attribute refusal))
             "the refusal names the missing required key")
         (is (= :seon.error/unknown (:seon.db/offending refusal)))
         (is (= identities (get-in refusal [:seon.error/data :seon.db/entity]))
             "the refusal names the entity by its identity attributes")
         (is (= basis (db/basis-t (db/db connection))) "the incomplete create commits nothing")))
     (test-support/transacted! connection [{:seon.ns/name 'seon.source.test.complete}])
     (is (some? (:db/id (db/pull (db/db connection) '[:db/id]
                                 [:seon.ns/name 'seon.source.test.complete])))
         "a create carrying every required key of its schema is admitted")
     (test-support/transacted! connection
                               [{:seon.schedule/id "create-probe"
                                 :seon.schedule/expression "0 4 * * *"
                                 :seon.schedule/zone-id "UTC"}])
     (test-support/transacted! connection
                               [[:db/retractEntity [:seon.schedule/id "create-probe"]]])
     (is (nil? (:db/id (db/pull (db/db connection) '[:db/id]
                                [:seon.schedule/id "create-probe"])))
         "an entity retracted to nothing is skipped by whole-entity validation"))))

(deftest required-program-relations-name-the-surviving-referrer
  (test-support/with-database
   (fn [connection]
     (let [namespace-name (symbol "reset.required")
           function (symbol (str namespace-name) "target")]
       (test-support/transacted!
        connection [{:seon.agent/id "root"}
                    {:seon.ns/name namespace-name}
                    (test-support/program-fn-row (db/db connection) function "(defn target [] nil)")
                    [:db.fn/call #'schedule/root-maintenance-seed-call]])
       (let [task-id (first (sort (db/q '[:find [?id ...] :where [_ :seon.schedule.task/id ?id]]
                                        (db/db connection))))]
         (is (some? task-id))
         (test-support/transacted!
          connection [[:db/add [:seon.schedule.task/id task-id]
                       :seon.schedule.task/function [:seon.fn/sym function]]])
         (doseq [[target attribute expected]
                 [[[:seon.ns/name namespace-name] :seon.fn/ns function]
                  [[:seon.fn/sym function] :seon.schedule.task/function task-id]]]
           (let [basis (db/basis-t (db/db connection))
                 refusal (db/transact! connection [[:db/retractEntity target]])
                 entity (get-in refusal [:seon.error/data :seon.db/entity])]
             (is (string? (:seon.db.write.attempt/request-id refusal)) (pr-str refusal))
             (is (= attribute (:seon.db/attribute refusal)))
             (is (some #{expected} (vals entity)) (pr-str refusal))
             (is (= basis (db/basis-t (db/db connection)))))))))))

(deftest arity-components-and-callers-are-checked-in-the-final-state
  (test-support/with-database
   (fn [connection]
     (let [projection (db/carried-projection (db/db connection))
           forms (:seon.schema.projection/forms projection)
           callee 'sample.arity/target
           caller 'sample.arity/caller
           row (program/with-contract-facts
                {:seon.program/row
                 (assoc (test-support/program-fn-row (db/db connection) callee "(defn target [x] x)")
                        :seon.schema.admission/source :agent
                        :seon.fn/source "(defn target [x] x)"
                        :seon.fn/arglists "([x])"
                        :seon.fn/private? false
                        :seon.fn/spec "[:=> [:cat :int] :int]")
                 :seon.program/compile-options
                 (:seon.schema.projection/compile-options projection)
                 :seon.program/predicate-functions (schema/predicate-functions-in projection)
                 :seon.program/schema-keys (set (keys forms))
                 :seon.program/schema-forms forms})]
       (test-support/transacted!
        connection
        [{:seon.ns/name 'sample.arity}
         row
         (assoc (test-support/program-fn-row (db/db connection) caller "(defn caller [x] (target x))")
                :seon.schema.admission/source :agent
                :seon.fn/source "(defn caller [x] (target x))"
                :seon.fn/arglists "([x])"
                :seon.fn/private? false
                :seon.fn/call-arities #{[callee 1]})])
       (let [arity (db/q '[:find ?arity . :in $ ?callee
                          :where [?f :seon.fn/sym ?callee]
                          [?f :seon.fn/arities ?arity]] (db/db connection) callee)
             edits [[:db/add arity :seon.fn.arity/min 2]
                    [:db/add arity :seon.fn.arity/max 2]
                    [:db/add arity :seon.fn.arity/argument-count 2]]
             basis (:max-tx @connection)
             refusal (db/transact! connection edits)]
         (is (string? (:seon.db.write.attempt/request-id refusal)) (pr-str refusal))
         (is (= [{:seon.fn/caller caller :seon.fn/callee callee
                  :seon.fn/call-arity 1
                  :seon.fn/declared-arities [{:seon.fn.arity/min 2 :seon.fn.arity/max 2}]
                  :seon.fn/prepared-arities [{:seon.fn.arity/min 2 :seon.fn.arity/max 2}]}]
                (get-in refusal [:seon.error/data :seon.fn/arity-mismatches])))
         (is (= basis (:max-tx @connection)))
         (test-support/transacted!
          connection
          (into edits [[:db/retract [:seon.fn/sym caller] :seon.fn/call-arities [callee 1]]
                       [:db/add [:seon.fn/sym caller] :seon.fn/call-arities [callee 2]]]))
         (is (empty? (:seon.fn/arity-mismatches (db/arity-mismatches (db/db connection))))))))))

(deftest render-declarations-require-their-final-function-row
  (test-support/with-database
   (fn [connection]
     (let [renderer (symbol "sample.render" "ai")
           schema-key ::render-target
           definition [:map {:seon.render/ai renderer}]
           projection (schema/build-projection
                       (assoc (:seon.schema.projection/forms (db/carried-projection (db/db connection)))
                              schema-key definition))
           row (first (schema/canonical-schema-rows projection {schema-key definition}))
           basis (:max-tx @connection)
           refusal (db/transact! connection [row])
           expected [{:seon.schema/key schema-key
                      :seon.render/property :seon.render/ai
                      :seon.render/function renderer}]]
       (is (string? (:seon.db.write.attempt/request-id refusal)) (pr-str refusal))
       (is (= expected (get-in refusal [:seon.error/data :seon.render/declarations])))
       (is (= basis (:max-tx @connection)))
       (test-support/transacted!
        connection
        [{:seon.ns/name 'sample.render}
         (assoc (test-support/program-fn-row (db/db connection) renderer "(defn ai [value] value)")
                :seon.schema.admission/source :agent
                :seon.fn/source "(defn ai [value] value)"
                :seon.fn/arglists "([value])"
                :seon.fn/private? false)
         row])
       (let [refusal (db/transact! connection [[:db/retractEntity [:seon.fn/sym renderer]]])]
         (is (string? (:seon.db.write.attempt/request-id refusal)) (pr-str refusal))
         (is (= expected (get-in refusal [:seon.error/data :seon.render/declarations]))))
       (test-support/transacted!
        connection
        [[:db/retractEntity [:seon.schema/key schema-key]]
         [:db/retractEntity [:seon.fn/sym renderer]]])
       (is (nil? (:db/id (db/pull (db/db connection) [:db/id]
                                 [:seon.fn/sym renderer]))))))))

(deftest query-symbol-values-use-the-declared-codec-in-every-binding-shape
  (with-codec-database
   {:seon.test-support/extra-schema
    (schema.datahike/malli->datahike-schema-in
     fixture-projection [::ai-declaration ::html-declaration ::row-id])}
   (fn [connection]
     (test-support/transacted! connection
       [{::row-id "codec-symbol" ::ai-declaration 'example.render/ai
         ::html-declaration 'example.render/html}])
     (let [database (db/db connection)]
       (is (= "codec-symbol"
              (db/q database '[:find ?id . :where
                               [?e ::ai-declaration example.render/ai] [?e ::row-id ?id]])))
       (is (= "codec-symbol"
              (db/q database '[:find ?id . :in $ ?value :where
                               [?e ::ai-declaration ?value] [?e ::row-id ?id]] 'example.render/ai)))
       (is (= ['example.render/ai]
              (db/q database '[:find [?value ...] :in $ [?value ...] :where
                               [?e ::ai-declaration ?value]] ['example.render/ai])))
       (is (= #{[::ai-declaration 'example.render/ai] [::row-id "codec-symbol"]}
              (db/q database '[:find ?attribute ?value :in $ [?attribute ...] :where
                               [?e ::row-id "codec-symbol"] [?e ?attribute ?value]]
                    [::ai-declaration ::row-id])))
       (is (= #{'example.render/ai 'example.render/html}
              (set (db/q database '[:find [?value ...] :in $ [?attribute ...] :where
                                    [?e ::row-id "codec-symbol"] [?e ?attribute ?value]]
                         [::ai-declaration ::html-declaration]))))
       (is (integer? (db/q database '[:find ?e . :where [?e :seon.ns/name seon.db]])))
       (is (= ['seon.db]
              (db/q database '[:find [?name ...] :in $ [?name ...]
                               :where [?e :seon.ns/name ?name]] ['seon.db])))))))



;;; ---------------------------------------------------------------------------
;;; Read seams: the declarations table, the replayed operation, the pulled form
;;; ---------------------------------------------------------------------------

(deftest a-refused-declarations-read-refuses-decoding
  ;; CLASS: absence of signal read as health. `read-declarations` answered with
  ;; a table whose installed schema was `nil` whenever the supplied value was
  ;; not a database, and `edn-encoded?` then answered false for EVERY
  ;; attribute: each decoded value silently lost its declared decoding, with no
  ;; signal anywhere (critical finding #18). The refusal is the answer now, and
  ;; no decode continuation runs behind it.
  (test-support/with-database
   (fn [connection]
     (let [database @connection
           decoded? (atom false)
           refusal (@#'db/read-declarations {:not :a-database} 'seon.db/pull)
           data (:seon.error/data refusal)]
       (is (= 'seon.db/pull (:seon.db.read/unreadable-declarations refusal)))
       (is (= :seon.db/installed-schema
              (:seon.error/member data)))
       (is (= 'seon.db/pull (:seon.error/operation data)))
       (let [observed (@#'db/with-declarations
                       {:not :a-database} 'seon.db/pull
                       (fn [_] (reset! decoded? true) ::decoded))]
         (is (inst? (:seon.error/at observed)))
         (is (= (dissoc refusal :seon.error/at)
                (dissoc observed :seon.error/at))
             "Separate observations preserve the refusal evidence with their own timestamps."))
       (is (false? @decoded?)
           "no decode continuation may run on a refused declarations read")
       (is (= ::decoded
              (@#'db/with-declarations database 'seon.db/pull
               (constantly ::decoded)))
           "a real database still hands its declarations to the decoder")))))

(deftest database-operations-preserve-refusals-at-their-declared-boundary
  (test-support/with-database
   (fn [connection]
     (let [refusal (config/effective (db/db connection) "absent-kind-review-cluster")]
       (is (= :seon.config/cluster (:seon.config/error-key refusal)))
       (doseq [result [(db/q refusal '[:find ?e :where [?e :seon.agent/id]])
                       (db/pull refusal [:db/id] 1)
                       (db/datoms refusal :eavt)
                       (db/history refusal)
                       (db/as-of refusal 0)
                       (db/db refusal)]]
         (is (true? (:seon.db/invalid-read result)))
         (is (= refusal (dissoc result :seon.db/invalid-read :seon.db/refused-read-operation))))
       (let [result (db/transact! refusal {:tx-data []})]
         (is (true? (:seon.db/transaction-refused result)))
         (is (= refusal (dissoc result :seon.db/transaction-refused :seon.db.write.attempt/request-id))))))))

(deftest an-unknown-read-operation-refuses-naming-the-attribute
  ;; CLASS: no-matching-clause (critical finding #19). `replay-read`'s `case`
  ;; had four arms and no default, so a fifth value threw
  ;; IllegalArgumentException naming nothing into the since-diff that runs
  ;; before every agent turn.
  (test-support/with-database
   (fn [connection]
     (let [database @connection
           replay (mi/-f->original @#'db/replay-read)
           unknown (replay database
                           {:seon.db/read-operation :seon.db/not-an-operation})
           data (:seon.error/data unknown)]
       (is (= :seon.db/not-an-operation (:seon.db.read/unknown-read-operation unknown)))
       (is (= :seon.db/read-operation (:seon.error/member data)))
       (is (= :seon.db/not-an-operation
              (:seon.error/offending data)))))))

(deftest pull-checks-only-the-caller-named-schema
  (test-support/with-database
   (fn [connection]
     (test-support/transacted!
      connection
      (agent/creation-tx {:seon.agent/id "a2-pull-agent"
                          :seon.ns/name 'my.agents.a2-pull-agent
                          :seon.cluster/name "default"}))
     (let [database (db/db connection)
           selector [:seon.ns/name]
           schema-key :seon.ns/ns
           projection (schema/handed-projection)]
       (is (= {:seon.agent/id "a2-pull-agent"}
              (db/pull database [:seon.agent/id] [:seon.agent/id "a2-pull-agent"])))
       (is (= {:seon.ns/name 'my.message}
              (db/pull database {:selector selector :schema-key schema-key
                                 :eid [:seon.ns/name 'my.message]})))
       (is (nil? (db/pull database {:selector selector :schema-key schema-key
                                   :eid [:seon.ns/name 'a2.absent]})))
       (let [refusal (#'db/validate-pulled-result
                      projection 'seon.db/pull schema-key selector
                      {:seon.ns/name "my.message"})]
         (is (= (schema/pulled-schema-key schema-key selector)
                (:seon.db.read/invalid-pulled-result refusal)))
         (is (= {:seon.ns/name "my.message"} (:seon.error/offending refusal))))))))

(deftest the-write-bound-derives-from-the-writes-own-provenance
  ;; Ruling 1r (owner, 2026-09-18): root/system writes carry NO per-write
  ;; timeout — their operation's own lifecycle deadline is the bound that
  ;; reports — while agent/turn writes keep the short database dial. The
  ;; decision is the `:seon.db/user` the transaction already carries; there is
  ;; no second access-control mechanism.
  (test-support/with-database
   (fn [connection]
     (let [database @connection]
       (is (true? (@#'db/agent-provenance?
                   database
                   {:tx-meta {:seon.db/user [:seon.agent/id "any-agent"]}})))
       (is (false? (@#'db/agent-provenance?
                    database
                    {:tx-meta {:seon.db/process
                               [:seon.db.process/id "publication"]}}))
           "a system process write carries no agent user and no per-write bound")
       (is (false? (@#'db/agent-provenance? database {}))
           "a write with no provenance user at all is a system write")))))

(deftest turn-write-schema-does-not-include-error-observations
  (test-support/with-database
   (fn [connection]
     (let [projection (db/carried-projection (db/db connection))
           schemas (#'db/write-entity-schemas projection)]
       (is (= #{:seon.turn/turn} (set (get schemas :seon.turn/id))))
       (is (= #{:seon.test/test} (set (get schemas :seon.test/sym))))
       (is (= #{:seon.fn/fn} (set (get schemas :seon.fn/sym))))))))

(deftest identity-less-unowned-values-retain-the-write-disposition
  (test-support/with-database
   (fn [connection]
     (let [before (db/basis-t @connection)
           result (db/transact! connection [{:seon.error/at #inst "2026-09-19T00:00:00Z"
                                            :seon.error/layer :seon.agent/lifecycle
                                            :seon.error/operation 'seon.agent/by-id}])]
       (is (= :seon.db/unowned-entity (:seon.db/owned-value-refusal result)) (pr-str result))
       (is ((schema/projection-validator (schema/handed-projection) :seon.db/error-result) result))
       (is (= before (db/basis-t @connection)))))))

(deftest a-declared-but-uninstalled-write-key-keeps-the-missing-key-hint
  (test-support/with-database
   (fn [connection]
     (let [database @connection
           projection (schema/handed-projection)
           attribute :seon.message/content
           drifted (assoc database :schema (dissoc (:schema database) attribute))
           row {:seon.message/id "drifted-message" attribute "Uninstalled content"}
           candidates (#'db/write-key-candidates projection row)
           refusal (#'db/write-map-error drifted projection row [])]
       (is (some? (get (:seon.schema.projection/forms projection) attribute)))
       (is (= #{:seon.message/to} (set candidates)))
       (is (= :seon.error/unknown (:seon.schema/form refusal)))
       (is (= (vec candidates) (:seon.db/registered-candidates refusal)))
       (is (str/includes? (get-in refusal [:seon.error/data :seon.error/problems 0 :seon.error/fix])
                          "missing declared key"))))))

(deftest shown-value-diff-round-trips-changed-paths
  (doseq [[before after] [[{:a 1 :b 2} {:a 3}]
                          [[1 2 3] [1 4]]
                          [nil {:a [1 2]}]
                          [{:a 1} {:a 1}]]]
    (let [changes (db/diff {:seon.db.diff/before before
                            :seon.db.diff/after after})]
      (is (= after (db/apply-diff before changes)))
      (is (= (= before after) (empty? changes))))))

(deftest ^{:seon.test/long "Armed fixture creation, stored system opening and changed-read preview measured 7849 ms on 2026-09-22."
           :seon.test/long-ms 10000}
  system-turn-renders-a-changed-read-with-value-diff
(test-support/with-database
 (fn [connection]
   (test-support/seed-cluster! connection "a2-diff")
   (test-support/transacted!
    connection
    (agent/creation-tx {:seon.agent/id "a2-reader"
                        :seon.ns/name 'my.agents.a2-reader
                        :seon.cluster/name "a2-diff"}))
   (test-support/transacted! connection
    [{:seon.message/id "a2-read" :seon.message/to [:seon.agent/id "a2-reader"]
      :seon.message/content "before"}])
   (let [handle (test-support/cluster-handle
                 {:seon.env/environment (test-support/environment "a2-diff" connection)
                  :seon.db/connection connection :seon.cluster/name "a2-diff"
                  :seon.db.process/id cluster/boot-process-identity
                  :seon.sci.eval/ctx (test-support/fork-cluster-ctx connection)})
         request {:seon.turn.loop/cluster handle :seon.agent/id "a2-reader"
                  :seon.turn/write? true}]
     (try
       (let [opening (turn/system-turn request)]
         (is (string? (:seon.turn/id opening)) (pr-str opening))
         (test-support/transacted! connection
          [[:db/add [:seon.message/id "a2-read"] :seon.message/content "after"]])
         (let [preview (turn/system-turn (assoc request :seon.turn/write? false))
               changed (filterv #(= :changed (:seon.turn/status %))
                                 (:seon.turn/forms preview))]
           (is (= 1 (count changed)) (pr-str preview))
           (is (seq (:seon.turn/changes (first changed))))
           (is (str/includes? (:seon.turn/text (first changed) "") "after"))
           (select-keys (first changed) [:seon.cluster.eval/source :seon.turn/text :seon.turn/changes])))
       (finally
         (doseq [channel-key [:seon.cluster.wake/channel :seon.render/context-channel :seon.turn.loop/completion]]
           (async/close! (get handle channel-key)))))))))

(deftest pull-budget-refusal-is-an-explicit-named-error
  (test-support/with-database
   (fn [connection]
     (let [database (db/db connection)
           request {:selector [:seon.schema/key] :eid schema-ref :max-work 1}
           dependency (try (d/pull database request)
                           (catch clojure.lang.ExceptionInfo cause (ex-data cause)))
           result (db/pull database request)]
       (is (= {:seon.schema/key (second schema-ref)}
              (db/pull database (dissoc request :max-work))))
       (is (true? (:datahike/budget-exceeded dependency)))
       (is ((schema/projection-validator (schema/handed-projection)
                                          :seon.db/pull-budget-error) result))
       (is (= (select-keys dependency [:datahike.budget/name
                                       :datahike.budget/observed :datahike.budget/allowed])
              (select-keys result [:datahike.budget/name
                                   :datahike.budget/observed :datahike.budget/allowed])))
       (is (= 'seon.db/pull (:seon.error/operation result)))
       (is (not-any? #(contains? result %)
                     [:seon.schema/key :seon.print/elision :seon.print/omitted-count]))
       (let [text (render.value/render-ai
                   {:seon.render/value result :seon.render.block/name :a2/budget
                    :seon.db/db database :seon.schema/projection (schema/handed-projection)
                    :seon.render/profile (render/agent-render-profile config/defaults)})]
         (doseq [member [":datahike.budget/name" ":datahike.budget/observed"
                         ":datahike.budget/allowed" "seon.db/pull"]]
           (is (str/includes? text member))))))))

;; A projection is a function of the declaration datoms it reads. Two values
;; holding the same datoms are the same population whatever their connection,
;; branch or committed identity, so they share one derivation.
(defn- counting-derivations
  {:malli/schema [:=> [:cat [:=> [:cat] :seon.schema/value]] [:tuple :int :seon.schema/value]]}
  [body]
  (let [derive-projection schema/load-projection
        derivations (atom 0)
        result (with-redefs [schema/load-projection
                             (fn ([value] (derive-projection value))
                               ([value base] (swap! derivations inc) (derive-projection value base)))]
                 (body))]
    [@derivations result]))

(deftest equal-declaration-content-shares-one-projection-across-values
  (test-support/with-database
    (fn [outer]
      (let [committed (db/carried-projection (db/db outer))]
        (test-support/with-database
          (fn [inner]
            (let [[derivations [branch in-transaction]]
                  (counting-derivations
                   #(let [started (System/nanoTime)
                          branch (db/carried-projection (db/db inner))]
                      [[branch (/ (- (System/nanoTime) started) 1e6)]
                       (db/carried-projection (:db-after (d/with (db/db inner) [])))]))]
              (is (= (d/commit-id (db/db outer)) (d/commit-id (db/db inner)))
                  "both fixtures branch off the executing commit")
              (is (identical? committed (first branch))
                  "a new branch at an equal commit reads the committed population")
              (is (< (second branch) 50.0) "and does not derive it")
              (is (identical? committed in-transaction)
                  "an in-transaction value with the same declarations reads it too")
              (is (zero? derivations)))))))))

(deftest an-in-transaction-declaration-derives-its-population-once
  (test-support/with-database
    (fn [connection]
      (let [database (db/db connection)
            committed (db/carried-projection database)
            staged (:db-after (d/with database
                                      [[:db.fn/call #'turn/row-tx {}
                                        {:seon.schema/key ::staged :seon.schema/form (pr-str :int)}]]))
            [derivations [first-read second-read]]
            (counting-derivations #(vector (db/carried-projection staged) (db/carried-projection staged)))]
        (is (nil? (datahike.db/committed-value-identity staged))
            "the speculative value has no committed identity")
        (is (false? (:datahike.cache/committed? (:cache-context staged)))
            "its revision context is never committed")
        (is (contains? (:seon.schema.projection/forms first-read) ::staged)
            "its own declaration datoms select its population")
        (is (not (contains? (:seon.schema.projection/forms committed) ::staged)))
        (is (identical? first-read second-read))
        (is (= 1 derivations) "repeated reads of one in-transaction value derive once")))))

(deftest equal-revisions-prove-a-read-current-without-scanning-history
  (test-support/with-database
   (fn [connection]
     (let [flow [:seon.ns/name 'seon.flow]
           captured (atom [])
           doc! (fn [entity attribute text]
                  (test-support/transacted! connection [[:db/add entity attribute text]]))]
       (binding [db/*read-evidence-sink* captured]
         (db/datoms @connection :eavt flow :seon.ns/doc))
       (let [evidence (db/read-evidence @captured)
             scans (atom 0)
             history d/history]
         (doc! [:seon.fn/sym 'seon.db/transact!] :seon.fn/doc "An unrelated attribute changed.")
         (with-redefs [d/history (fn [database] (swap! scans inc) (history database))]
           (is (true? (db/read-evidence-current? @connection evidence))
               "an unrelated write leaves the read's attribute revision equal"))
         (is (zero? @scans) "equal revisions answer before any history scan")
         (doc! [:seon.ns/name 'seon.db] :seon.ns/doc "Another entity's value changed.")
         (is (true? (db/read-evidence-current? @connection evidence))
             "a write to another entity's value of the attribute leaves this pattern intact")
         (doc! flow :seon.ns/doc "The read value changed.")
         (is (false? (db/read-evidence-current? @connection evidence))))))))

(deftest an-index-page-depends-on-its-prefix-attribute
  (test-support/with-database
   (fn [connection]
     (let [captured (atom [])
           doc! (fn [entity attribute text]
                  (test-support/transacted! connection [[:db/add entity attribute text]]))]
       (binding [db/*read-evidence-sink* captured]
         (db/index-page @connection {:index :aevt :components [:seon.ns/doc]
                                     :direction :forward :limit 10 :max-result-weight 100000}))
       (let [evidence (db/read-evidence @captured)]
         (is (= #{:seon.ns/doc}
                (d/dependency-plan-attributes (:datahike.read/dependency-plan (first evidence)) 0)))
         (doc! [:seon.fn/sym 'seon.db/transact!] :seon.fn/doc "An unrelated attribute changed.")
         (is (true? (db/read-evidence-current? @connection evidence))
             "a commit outside the prefix's attribute keeps the page current")
         (let [on-page (:e (first (:datahike.index-page/datoms (:seon.db/read-result (first @captured)))))]
           (doc! on-page :seon.ns/doc "A datom on the page changed."))
         (is (false? (db/read-evidence-current? @connection evidence))
             "a changed datom on the page makes it stale"))))))

(deftest a-write-user-datahike-cannot-parse-names-no-agent
  (test-support/with-database
   (fn [connection]
     (let [provenance? @#'seon.db/agent-provenance?
           database (db/db connection)]
       (doseq [user [[:seon.fn/doc "not unique"] [:a 1 2] "a-string" {:map 1} :no-such-ident]]
         (is (false? (provenance? database {:tx-meta {:seon.db/user user}}))
             (str "Datahike's declared unparseable entity id answers no agent: " (pr-str user))))
       (is (true? (provenance? database {:tx-meta {:seon.db/user [:seon.agent/id "any"]}})))))))

(defn- declare-entity!
  {:malli/schema [:=> [:cat :seon.db/connection :qualified-keyword] :map]}
  [connection schema-key]
  (test-support/transacted!
   connection
   [[:db.fn/call #'turn/row-tx {}
     {:seon.schema/key schema-key :seon.schema/form (pr-str [:map])}]]))

(deftest a-declaration-write-parses-only-the-declarations-it-wrote
  (test-support/with-database
   (fn [connection]
     (let [render-check @#'seon.db/write-render-target-error
           rendered (atom [])]
       (with-redefs [seon.db/write-render-target-error
                     (fn ([database] (swap! rendered conj :all) (render-check database))
                         ([database declarations] (swap! rendered conj (count declarations))
                          (render-check database declarations)))]
         (declare-entity! connection ::rendered))
       (is (= [1] @rendered) "the render gate reads the one written declaration")))))

(deftest a-report-touching-no-function-reuses-the-committed-arity-comparison
  ;; The arity gate compares only call edges and bounds under a caller or
  ;; callee this report touched; every other edge is its committed basis's.
  ;; A plain write to a declaration row runs the gate (its root is a schema
  ;; key) and leaves the arity attributes' revisions equal. A transaction
  ;; function's commit advances Datahike's conservative revision instead
  ;; (datahike/writer.cljc:249), so that basis is compared again.
  (test-support/with-database
   (fn [connection]
     (let [compare-edges @#'seon.db/arity-comparison
           compared (atom [])
           toggle! (fn [value]
                     (test-support/transacted!
                      connection
                      [[:db/add [:seon.schema/key :seon.error/base] :seon.schema/generatable? value]]))]
       (toggle! false)
       (toggle! true)
       (with-redefs [seon.db/arity-comparison
                     (fn [edges bounds] (swap! compared conj (count edges)) (compare-edges edges bounds))]
         (toggle! false))
       (is (empty? @compared) "the committed basis's comparison is reused")))))

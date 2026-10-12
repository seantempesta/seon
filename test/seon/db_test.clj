(ns seon.db-test
  (:require [seon.cache]
            [clojure.core.cache.wrapped :as cache]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as d]
            [datahike.constants :as constants]
            [datahike.core :as datahike]
            [datahike.db]
            [datahike.db.utils :as db.utils]
            [datahike.pull-api :as pull-api]
            [datahike.store :as datahike.store]
            [datahike.tools :as datahike.tools]
            [datahike.writer :as datahike.writer]
            [datahike.writing :as datahike.writing]
            [malli.core :as m]
            [malli.instrument :as mi]
            [seon.batch]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.cluster.registry :as registry]
            [seon.cluster.store :as store]
            [seon.config :as config]
            [seon.turn :as turn]
            [seon.db :as db]
            [clojure.set]
            [seon.env :as env]
            [seon.fn :as seon.fn]
            [seon.id :as id]
            [seon.instrument :as instrument]
            [seon.profile :as profile]
            [seon.program :as program]
            [seon.render :as render]
            [seon.render.value :as render.value]
            [seon.schedule :as schedule]
            [seon.sci.admit :as admit]
            [seon.sci.eval :as sci.eval]
            [seon.sci.lookup :as lookup]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.test-support :as test-support]
            [taoensso.trove :as trove])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(def ^:private schema-delta (schema/begin-registration-delta))

(deftest ^{:seon/tasks #{'seon.db/a-reopened-commit-carries-its-content-revisions}}
  a-reopened-commit-carries-its-content-revisions
  (test-support/with-database
   (fn [connection]
     (let [database (db/db connection)
           store (:seon.store/store (test-support/execution-handle connection))
           branch (keyword (str "revisions-reopen-" (id/id)))
           request {:seon.store/store store :seon.store/branch branch
                    :seon.cluster.registry/from (db/commit-id database) :seon.db/db database}
           attributes (vec (program/program-attributes (schema/projection-from-database database)))
           written [:seon.branch/fork-id :seon.branch/name :seon.branch/target :seon.branch/base-t]
           ;; each opening is a fresh Datahike connection reading the stored head
           opened (fn []
                    (let [fork (store/open-branch! store branch)]
                      (try
                        (let [value (db/db fork)
                              [_ calls] (test-support/armed-calls
                                         '#{seon.db/declaration-content-key seon.schema/load-projection}
                                         #(db/carried-projection value))]
                          (is (every? zero? (vals calls))
                              "reopening an unchanged declaration population reuses its projection")
                          {:head (db/commit-id value)
                           :program (db/content-revisions value attributes)
                           :written (db/content-revisions value written)})
                        (finally (store/release-branch! fork)))))]
       (is (:seon.cluster/created? (registry/fork! request)))
       (try
         (let [{:keys [head program] :as first-opening} (opened)]
           (is (seq attributes))
           (is (= (db/content-revisions database attributes) program)
               "a fork that changed no program attribute names main's commits")
           (is (= #{head} (set (vals (:written first-opening))))
               "the fork's own write names the fork head")
           (is (= first-opening (opened)) "a second opening reads the same revisions"))
         (finally (registry/retire-branch! (select-keys request [:seon.store/store :seon.store/branch]))))))))

(deftest ^{:seon/tasks #{'seon.db/a-failed-projection-derivation-stays-cached-under-its-commit}}
  a-failed-projection-cell-does-not-poison-its-next-read
  (test-support/with-database
   (fn [connection]
     (test-support/transacted! connection [(test-support/task-row "projfailcell" "Exercise a failed derivation.")])
     (let [database (db/db connection)
           builder (#'db/projection-builder database)
           commit-key [:seon.db/commit (db/commit-id database) builder]
           revision-key (assoc (#'db/projection-cache-key database) :seon.db/builder builder)
           held @#'db/projection-cache
           failure (ex-info "Projection derivation failed." {::failed true})
           cell (delay (throw failure))]
       (doseq [k [commit-key revision-key]]
         (cache/evict held k)
         (cache/lookup-or-miss held k (constantly cell)))
       (try
         (is (= {::failed true}
                (try (db/carried-projection database) (catch Exception e (ex-data e)))))
         (is (not-any? #(identical? cell %) (vals @held)))
         (is (map? (try (db/carried-projection database) (catch Exception _ nil))))
         (finally
           (doseq [k [commit-key revision-key]] (cache/evict held k))))))))

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
            (doseq [selector [@#'lookup/program-documentation-selector '[*]]]
              ;; Warm the actual per-projection codecs, then include supplier
              ;; acquisition in every measured call. No dynamic projection is
              ;; present at either acquisition or decoding.
              (dotimes [_ 3]
                (db/pull-many (db/supplied-database-value environment) selector ids))
              (dotimes [_ 3]
                (let [before (.getThreadAllocatedBytes allocation-bean thread-id)
                      started (System/nanoTime)
                      entities (db/pull-many (db/supplied-database-value environment)
                                         selector ids)
                      elapsed (- (System/nanoTime) started)
                      allocated (- (.getThreadAllocatedBytes allocation-bean thread-id)
                                   before)]
                  (is (= (count ids) (count entities)))
                  (is (every? :seon.fn/sym entities))
                  (is (pos? allocated) "the body's thread counts its allocation")
                  (is (< allocated 10000000))
                  (is (< elapsed 20000000))))))))
       (is (= "" (str warnings)))))))

(deftest uncarried-read-reports-one-projection-fallback-per-call
  ;; A read takes its projection from the value it reads: a value holding
  ;; declaration entities derives it with nothing handed, and only a value with
  ;; no declaration entities (a cold construction boundary) refuses by name,
  ;; once per call.
  (test-support/with-database
   (fn [connection]
     (let [database @connection
           ids (d/q '[:find [?function ...]
                      :where [?function :seon.fn/sym my.message/send]]
                    database)]
       (is (seq ids))
       (without-handed-projection
        (fn []
          (let [warnings (java.io.StringWriter.)
                entities (binding [*err* warnings]
                       (db/pull-many database
                                     @#'lookup/program-documentation-selector ids))]
            (is (= (count ids) (count entities)))
            (is (= "" (str warnings))
                "a value that declares its program derives its own projection"))))
       (let [configuration {:store {:backend :memory :id (random-uuid)} :schema-flexibility :write}
             _ (d/create-database configuration)
             bare (d/connect configuration)
             validate-refusal (schema/projection-validator
                               (db/carried-projection database) :seon.schema/validation-refusal)]
    (try
      (d/transact bare [{:db/ident :sample.bare/id :db/valueType :db.type/string
                         :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}])
      (without-handed-projection
       (fn []
         (dotimes [_ 2]
           (let [warnings (java.io.StringWriter.)
                 refusal (binding [*err* warnings]
                           (db/pull @bare '[*] [:sample.bare/id "absent"]))
                 lines (str/split-lines (str warnings))]
             (is (validate-refusal refusal))
             (is (= :seon.schema/projection (:seon.schema/expected-value refusal)))
             (is (= 'seon.db/pull (:seon.db/refused-read-operation refusal)))
             (is (= 1 (count lines)))
             (is (str/includes? (first lines) "seon.db/projection-fallback"))
             (is (str/includes? (first lines) "count=1"))))))
      (finally
        (d/release bare)
        (d/delete-database configuration))))))))

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
              "seon.db/q stays close to the raw query"))))))))

(deftest nil-query-bindings-refuse-before-reading-the-index
  (test-support/with-database
   (fn [connection]
     (let [database (db/db connection)
           scalar '[:find [?e ...] :in $ ?id :where [?e :seon.agent/id ?id]]
           collection '[:find [?e ...] :in $ [?id ...] :where [?e :seon.agent/id ?id]]]
       (doseq [[query input] [[scalar nil]
                             [collection nil]
                             [collection [nil]]
                             ['[:find [?sym ...] :in $ ?e :where [?e :seon.fn/sym ?sym]] nil]
                             ['[:find [?e ...] :in $ [?id] :where [?e :seon.agent/id ?id]] [nil]]
                             ['[:find [?e ...] :in $ [[?id]] :where [?e :seon.agent/id ?id]] [[nil]]]]]
         (let [result (db/q query database input)]
           (is (true? (:seon.db/invalid-read result)))
           (is (= :in (get-in result [:seon.error/data :seon.error/member])))
           (is ((schema/projection-validator (db/carried-projection database)
                                             :seon.db/invalid-read-error) result))))
       (doseq [n [1 10 100]]
         (let [[result calls]
               (test-support/armed-calls
                '#{seon.db/nil-query-binding? seon.db/vector-query-call}
                #(db/q '[:find (count ?sym) . :in $ [?e ...] :where [?e :seon.fn/sym ?sym]]
                       database (conj (vec (repeat (dec n) [:seon.fn/sym 'seon.db/q])) nil)))]
           (is (true? (:seon.db/invalid-read result)))
           (is (zero? (get calls 'seon.db/vector-query-call 0)))
           (is (<= (get calls 'seon.db/nil-query-binding? 0) (+ n 3)))))
       (is (= [] (db/q collection database [])))
       (is (= [] (db/q scalar database "not-a-recorded-agent")))))))

(deftest ^{:seon/tasks #{'seon.db/q-lets-a-stack-overflow-error-escape}}
  a-stack-overflow-in-a-query-is-answered-as-a-refusal
  (test-support/with-database
   (fn [connection]
     (let [result (db/q '[:find [?t ...] :in $ %
                          :where (done ?t)]
                        (db/db connection)
                        '[[(done ?t) [?t :seon.agent/id _]
                           [?t :seon.agent/id ?c] (not (done ?c))]])]
       (is (true? (:seon.db/invalid-read result)))
       (is (= 'seon.db/q (:seon.db/refused-read-operation result)))
       (is (= "java.lang.StackOverflowError"
              (get-in result [:seon.error/data :seon.db/exception-class])))))))

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
       (is (every? #(< (::elapsed-nanos %) 5000000) samples))))))

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
           ;; A canonical agent entity whose id the writer's schema refuses.
           refusal (db/transact! connection
                                 (mapv #(cond-> % (and (map? %) (:seon.agent/id %))
                                          (assoc :seon.agent/id 42))
                                       (test-support/agent-tx @connection "refused-shape")))
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
       (is (m/validate :seon.db/transaction-report report options))
       (is (m/validate :seon.db/transaction-result result options))
       (is (m/validate :seon.error/value refusal options))
       (is (string? (:seon.db.write.attempt/request-id refusal)))
       (is (= [] transaction-findings))
       (is (not (instance? Throwable refusal)))))))

(deftest a-transaction-report-carries-its-processing-cost
  (test-support/with-database
   (fn [connection]
     (let [meter (db/writer-meter)
           report (binding [seon.batch/*world* {:seon.cost/writer-meter meter}]
                    (db/transact! connection [{:db/ident :seon.db-test/writer-cost}]))
           {writer :seon.cost/writer commit :seon.cost/commit} (db/writer-cost meter)]
       (is (pos? (get-in report [:datahike/processing :alloc-bytes] 0)) "the writer thread counted the report's processing")
       (is (pos? (get-in report [:datahike/commit :alloc-bytes] 0)) "the commit's one thread counted its storage writes")
       (is (= 1 (:seon.cost/transactions writer)))
       (is (pos? (:seon.cost/alloc-mb writer 0.0)) "the world's writer cost holds the processing")
       (is (pos? (:seon.cost/alloc-mb commit 0.0)) "and its share of the commit")))))

(deftest a-shared-commit-is-charged-in-equal-shares
  (test-support/with-database
   (fn [connection]
     (let [^java.util.concurrent.atomic.AtomicLongArray meter (db/writer-meter)
           world {:seon.cost/writer-meter meter}
           ;; concurrent writers queue behind one another's commits: Datahike
           ;; commits what queued as one batch
           pool (java.util.concurrent.Executors/newFixedThreadPool 8)
           reports (try
                     (->> (range 8)
                          (mapv (fn [i] ^java.util.concurrent.Callable
                                  (fn [] (binding [seon.batch/*world* world]
                                           (db/transact! connection [{:db/ident (keyword "seon.db-test" (str "shared-" i))}])))))
                          (.invokeAll pool)
                          (mapv #(.get ^java.util.concurrent.Future %)))
                     (finally (.shutdown pool)))
           commits (vals (group-by :commit-id (map :datahike/commit reports)))
           durable (transduce (map (comp :alloc-bytes first)) + 0 commits)]
       (is (= 8 (.get meter 0)) "every transaction is charged")
       (is (every? #(= (count %) (:tx-count (first %))) commits) "a commit's batch is the reports that share it")
       (is (<= (- durable 8) (.get meter 3) durable) "the shares add up to the commits, within integer division")))))

(deftest a-stored-cost-is-rechecked-outside-the-top-set
  (test-support/with-database
   (fn [connection]
     (let [sink (seon.batch/sink)
           world {:seon.batch/sink sink}
           digest (apply str (repeat 64 "c"))
           probe 'seon.db-test/cost-cold
           stored #(:seon.fn.cost/warm-self-us (db/pull (db/db connection) '[:seon.fn.cost/warm-self-us] [:seon.fn.cost/key [% :seon.profile/host]]))
           work! (fn [sym calls self-us]
                   (let [^java.util.concurrent.atomic.AtomicLongArray counter
                         (.computeIfAbsent ^java.util.concurrent.ConcurrentHashMap sink [sym digest] profile/new-adder)
                         nanos (long (* calls self-us 1000))]
                     (.addAndGet counter 0 calls) (.addAndGet counter 1 nanos) (.addAndGet counter 2 nanos)))
           ;; the probe's work, beside `heavier` new definitions that spend more, then one transaction
           step! (fn [self-us heavier]
                   (work! probe 100 self-us)
                   (when (pos? heavier) (work! 'seon.db-test/cost-light-new 100 self-us))
                   (dotimes [i heavier] (work! (symbol "seon.db-test" (str "cost-heavy-" i)) 100 400.0))
                   (binding [seon.batch/*world* world] (db/transact! connection []))
                   [(stored probe) (stored 'seon.db-test/cost-light-new)])]
       (clojure.test/are [self-us heavier expected] (= expected (step! self-us heavier))
         ;; cold: the probe spends 4.6 ms and ranks in the top
         46.0 0  [46.0 nil]
         ;; warm (1.5 ms, over the floor) and outside the top, ten heavier:
         ;; re-checked anyway; a new definition doing the same is not stored
         15.0 10 [15.0 nil]
         15.0 10 [15.0 nil])
       (is (nil? (stored 'seon.db-test/cost-never))
           "nothing is stored for what never ran")
       (is (= {:seon.db/process {:seon.db.process/id "seon.db.process/cost"}}
              (db/pull (db/db connection)
                       [{:seon.db/process [:seon.db.process/id]}]
                       [:seon.fn.cost/key [probe :seon.profile/host]])))))))

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
                               (test-support/agent-tx @connection "busy-agent"))
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
  "The fixture attributes also land as schema rows: the writer encodes through
  the projection derived from the branch's rows (`db/carried-projection`)."
  [options body]
  (test-support/with-database
   options
   (fn [connection]
     (db/carry-connection-projection-state!
      connection (sci.eval/projection-state @connection fixture-projection
                                            (:seon.cluster/name (test-support/execution-handle connection))))
     (schema/call-with-projection
      fixture-projection
      (fn []
        ;; A component attribute's declared child schema is an entity too: the
        ;; writer validates the owned value against it.
        (test-support/transacted!
         connection
         (let [forms (:seon.schema.projection/forms fixture-projection)
               idents (keep :db/ident (:seon.test-support/extra-schema options))
               children (keep #(let [form (get forms %)]
                                 (when (and (vector? form) (map? (second form)))
                                   (:seon.db/component-schema (second form))))
                              idents)]
           (test-support/schema-entities
            (db/db connection)
            (mapv (fn [schema-key] [schema-key (get forms schema-key)])
                  (concat idents children)))))
        (body connection))))))

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
                      (db/datoms @connection :avet ::ai-declaration)))))
       (testing "a function-bound map still decodes its nested attribute values"
         (is (= {::ai-declaration producer}
                (db/q '[:find ?result . :in $ ?input
                        :where [(identity ?input) ?result]]
                      @connection {::ai-declaration literal-text}))))))))

(deftest committed-edn-reads-as-written-unless-its-storage-is-malformed
  ;; A committed value outside the current write schema (a legacy dependency
  ;; plan past the 128-pattern bound) reads as written; only storage that is
  ;; not EDN is a read error.
  (with-codec-database
   {:seon.test-support/extra-schema
    [(schema.datahike/malli->datahike-attr-in
      fixture-projection ::ai-declaration)]}
   (fn [connection]
     (d/transact connection [{::ai-declaration "true"}])
     (is (true? (db/q '[:find ?declaration .
                        :where [_ ::ai-declaration ?declaration]]
                      @connection)))))
  (doseq [[stored rule]
          [["[" ::schema.datahike/malformed-edn]]]
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
     (test-support/transacted! connection (test-support/agent-tx @connection "wildcard-agent"))
     ;; Boot armed `db/pull`; the test re-arms nothing (a whole-JVM
     ;; `apply!` strips every production wrapper's supplied-default plan).
     (is (contains? (instrument/instrumented) #'db/pull))
     (is (= "wildcard-agent"
            (:seon.agent/id
             (db/pull @connection '[*]
                      [:seon.agent/id "wildcard-agent"])))))))

(deftest ^{:seon/tasks #{'seon.db/a-query-derives-its-read-plan-once}}
  a-query-with-nothing-to-decode-returns-datahikes-value
  ;; Walking a native result rebuilt every tuple: 5 ms / 21 MB for 19,232
  ;; cached tuples (2026-09-24). Pattern variables over native attributes
  ;; return the very value Datahike's query cache holds.
  (test-support/with-database
   (fn [connection]
     (let [database @connection]
       (doseq [query ['[:find ?entity ?key :where [?entity :seon.schema/key ?key]]
                      '[:find [?key ...] :where [_ :seon.schema/key ?key]]
                      '[:find ?entity ?key :where (or [?entity :seon.schema/key ?key])]
                      '[:find ?entity ?key ?private :where
                        [?entity :seon.schema/key ?key]
                        [(get-else $ ?entity :seon.fn/private? false) ?private]]
                      '[:find [(count ?entity)] :where [?entity :seon.schema/key]]]]
         (is (seq (d/q query database)))
         (let [measured (profile/measure #(db/q query database))]
           (is (identical? (d/q query database) (:seon.profile/value measured)))
           (is (= 1 (get-in measured [:seon.profile/called 'seon.db/query-patterns])))
           (is (= 1 (get-in measured [:seon.profile/called 'seon.db/query-variable-attributes])))))))))

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
     ;; Only this thread's resolutions count: `with-redefs` replaces the
     ;; root for the whole JVM, and members run concurrently, so a
     ;; JVM-wide count (34 and 62, tests-0627q) measured other members'
     ;; `d/db` calls, never this call's.
     (let [calls (atom 0)
           datahike-db d/db
           this-thread (Thread/currentThread)]
       (with-redefs [d/db (fn [bound-connection]
                            (when (identical? this-thread (Thread/currentThread))
                              (swap! calls inc))
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
    (is (str/includes? (:seon.error/fix result)
                       "(seon.cluster.boot/connection \"default\")"))
    (is (= 'seon.db/*conn*
           (get-in result [:seon.error/data :seon.db/binding])))))

(deftest every-public-read-preserves-an-upstream-database-error
  (let [upstream (db/projection-fallback 'seon.db/q)
        reads
        [[:connection-identity #(apply db/connection-identity [upstream])]
         [:database-value-identity #(db/database-value-identity upstream)]
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

;; `read-evidence-current?` declares its database by schema name, so an
;; agent call omitting it is supplied one (as `q`'s is); an upstream error in
;; that slot is refused by its contract instead of passed through.
(deftest read-evidence-currency-declares-its-database-by-schema-name
  (let [[_ inputs] (:malli/schema (meta #'db/read-evidence-current?))]
    (is (= :seon.db/database-value (second inputs)))))

;; CLASS: speculative values evicting committed projections. A batch of `with`
;; values that each wrote a declaration inserted its content projection into the
;; committed cache (24 entries): the next committed write's derivation found no
;; near base and rebuilt (1,757 ms against 266 ms before the batch, measured on
;; a disposable root; one-path spec 12, ruling B).
(deftest speculative-declarations-never-enter-the-committed-projection-cache
  (test-support/with-database
   (fn [connection]
     (let [row (fn [database k] (program/declaration-row (db/carried-projection database)
                                                         {:seon.schema/key k :seon.schema/form "[:string]"}
                                                         :all :agent))
           database (db/db connection)
           _ (db/carried-projection database)
           last-value (reduce (fn [value i]
                                (let [written (db/with value [(row value (keyword "probe.speculative" (str "k" i)))])]
                                  (is (not (:seon.error/at written)))
                                  (db/carried-projection written)
                                  written))
                              database (range 6))]
       (is (contains? (:seon.schema.projection/forms (db/carried-projection last-value)) :probe.speculative/k5)
           "each speculative value reads its own declarations")
       ;; Other members share the cache, so the check names this test's own
       ;; declarations: no committed entry derived them.
       (is (not-any? (fn [cell] (and (realized? cell)
                                     (contains? (:seon.schema.projection/forms @cell) :probe.speculative/k0)))
                     (vals @@#'db/projection-cache))
           "no speculative projection entered the committed cache")))))

;; CLASS: a definition write's projection read every declaration datom of
;; the program. Each contracted `defn` keyed its speculative value and then
;; its commit by `declaration-content-key` (about 30,000 datoms copied per
;; call, four calls per defn request on default), then `load-projection`
;; walked three whole AEVT ranges to find the one changed row.
(deftest a-definition-write-keys-its-projection-by-its-own-datoms
  (test-support/with-database
   (fn [connection]
     (is (some? (:db-after (db/transact! connection [(test-support/namespace-entity (db/db connection)
                                                                                      'sample.projection-key)]))))
     (let [sym 'sample.projection-key/call
           members [:seon.schema.projection/forms :seon.schema.projection/function-contracts
                    :seon.schema.projection/schema-admissions :seon.schema.projection/function-admissions
                    :seon.schema.projection/definition-strings]]
       (doseq [output [:int :string]]
         (let [head (db/db connection)
               _ (db/carried-projection head)
               tx-data (test-support/replacement-tx
                        head [:seon.fn/sym sym]
                        (str "(defn call {:malli/schema [:=> [:cat :int] " output "]} [x] x)") {})
               [projection calls] (test-support/armed-calls
                                   '#{seon.db/declaration-content-key seon.schema/load-projection}
                                   #(when (:db-after (db/transact! connection tx-data))
                                      (db/carried-projection (db/db connection))))
               stored (db/pull (db/db connection) [:seon.fn/spec] [:seon.fn/sym sym])]
           (is (some? projection) "the definition commits")
           (is (= (:seon.fn/spec stored) (get-in projection [:seon.schema.projection/definition-strings sym]))
               "the committed value reads the written contract")
           (is (zero? (get calls 'seon.db/declaration-content-key)) "no declaration datom is copied for a key")
           (is (zero? (get calls 'seon.schema/load-projection)) "no declaration range is walked")
           (is (= (select-keys (schema/load-projection (db/db connection)) members)
                  (select-keys projection members))
               "the advanced projection is the one a full derivation gives")))))))

;; CLASS: a per-read whole-program derivation during boot. Before a boot
;; instance names its commit, an uncommitted value's builder identity was
;; derived on every projection read: a transaction function comparing stored
;; collections paid a reach query per read (298 of 581 writer samples in one
;; cold boot publication).
(deftest an-uncommitted-values-builder-is-derived-once
  (test-support/with-database
   (fn [connection]
     (let [value (db/with (db/db connection) [(test-support/namespace-entity (db/db connection) (quote sample.builder))])
           reads 20
           [digests calls] (test-support/armed-calls
                            '#{seon.db/builder-digest}
                            #(vec (repeatedly reads (fn [] (@#'db/loaded-builder value {} nil)))))]
       (is (not (:seon.error/at value)))
       (is (= 1 (count (set digests))))
       (is (< (get calls 'seon.db/builder-digest 0) reads)
           "the identity is derived once per revision set, not per read")))))

(deftest equal-reads-record-one-evidence-entry
  ;; An evaluation that read one thing 440,605 times stored 440,605 evidence
  ;; rows, 8,266 of them distinct, and its recording took about 66 s.
  (test-support/with-database
   (fn [connection]
     (let [captured (atom [])]
       (binding [db/*conn* connection
                 db/*read-evidence-sink* captured]
         (dotimes [_ 200] (db/q exam-query))
         (db/q schema-family-query))
       (is (= 201 (count @captured)))
       (is (= 2 (count (db/read-evidence @captured)))
           "repeated equal reads are one dependency")))))

(deftest storing-and-reading-a-value-prints-it-once
  ;; Each keyword and symbol was printed to check that it reads back, and
  ;; decoding printed the value again to judge it canonical.
  (let [value [{:seon.ns/name 'seon.turn
                :datahike.read/attributes (into #{} (map #(keyword "probe.codec" (str "a" %))) (range 300))
                (keyword "probe.codec" "an unreadable") (symbol "also unreadable")}]
        _ (schema.datahike/encode-attribute-value-in fixture-projection ::html-declaration value)
        [stored calls] (test-support/armed-calls
                        '#{seon.id/printed seon.schema.datahike/encode-attribute-value-in}
                        #(schema.datahike/encode-attribute-value-in fixture-projection ::html-declaration value))
        [decoded read-calls] (test-support/armed-calls
                              '#{seon.id/printed seon.schema.datahike/decode-attribute-value-in}
                              #(schema.datahike/decode-attribute-value-in
                                fixture-projection ::html-declaration stored))]
    (is (= value decoded) "the value reads back, unreadable identifiers included")
    (is (= 1 (get calls 'seon.schema.datahike/encode-attribute-value-in)) "the encoder is armed and counted")
    (is (<= (get calls 'seon.id/printed 0) 3) "the stored text is the one print")
    (is (= 1 (get read-calls 'seon.schema.datahike/decode-attribute-value-in)) "the decoder is armed and counted")
    (is (<= (get read-calls 'seon.id/printed 0) 2) "a read never prints")))

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
            (tree-seq coll? seq (mapv :seon.db/read-request @entries)))
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
                                   (test-support/agent-tx @connection "unrelated-agent"))
         (is (db/read-evidence-current? @connection evidence)
             "an unrelated attribute revision retains the renderer read")
         (test-support/transacted!
          connection
          [[:db/add [:seon.cluster/name "evidence-a"] :seon.cluster/name "evidence-b"]])
         (is (not (db/read-evidence-current? @connection evidence))
             "a depended attribute revision makes the retained read stale"))))))

(deftest a-retained-read-whose-plan-names-no-source-is-answered-by-replay
  ;; A declared `:all` dependency plan carries no source at the evidence's
  ;; position; currency falls through to replay instead of handing the index
  ;; check a nil source (docs/seon/issues/read-evidence-currency-hands-a-nil-source-to-index-evidence.md).
  (test-support/with-database
   (fn [connection]
     (test-support/seed-cluster! connection "evidence-all")
     (let [captured (atom [])]
       (binding [db/*read-evidence-sink* captured]
         (db/q '[:find ?e . :where [?e :seon.cluster/name "evidence-all"]] @connection))
       (let [evidence (db/read-evidence
                       (mapv #(-> % (assoc :datahike.read/dependency-plan :all)
                                  (dissoc :seon.db/read-index-patterns))
                             @captured)
                       {:seon.db/retain-read-results? true})]
         (test-support/transacted!
          connection [(test-support/revision @connection [:seon.fn/sym 'seon.db/transact!] {:seon.fn/doc "unrelated"})])
         (is (true? (db/read-evidence-current? @connection evidence))
             "an unchanged result replays equal")
         (test-support/transacted!
          connection
          [[:db/add [:seon.cluster/name "evidence-all"] :seon.cluster/name "evidence-renamed"]])
         (is (false? (db/read-evidence-current? @connection evidence))
             "a changed result replays unequal"))))))

(deftest stored-read-evidence-references-the-read-and-never-copies-its-request
  ;; A request is code: `#"…"` is a host Pattern, which no stored value can
  ;; hold. The evidence an evaluation stores keeps the dependency plan and
  ;; revision; only process-local retained evidence keeps the request for its
  ;; own replay.
  (test-support/with-database
   (fn [connection]
     (test-support/seed-cluster! connection "evidence-regex")
     (let [captured (atom [])
           query '[:find (count ?n) . :where [_ :seon.cluster/name ?n] [(str ?n) ?t] [(re-find #"regex" ?t)]]
           answer (binding [db/*read-evidence-sink* captured] (db/q query @connection))
           stored (db/read-evidence @captured)
           retained (db/read-evidence @captured {:seon.db/retain-read-results? true})]
       (is (= 1 answer))
       (is (seq stored))
       (is (not-any? #(find % :seon.db/read-request) stored))
       (is (not-any? #(find % :seon.db/read-result-digest) stored))
       (is (every? (schema/projection-validator (schema/handed-projection)
                                                :seon.db/read-evidence)
                   stored)
           "evidence is valid read evidence")
       (is (= stored (edn/read-string (pr-str stored)))
           "stored evidence survives the EDN codec it is written through")
       (is (some #(find % :seon.db/read-request) retained)
           "retained evidence keeps its request for process-local replay")
       (is (true? (db/read-evidence-current? @connection stored)))))))

(deftest a-mistyped-pattern-value-refuses-as-a-read-and-as-a-replay
  ;; A quoted `'sym` inside a quoted query is the list `(quote sym)`. Replaying
  ;; such a retained read threw Datahike's ClassCastException out of the
  ;; since-diff before root's turns (2026-09-23, seven occurrences).
  (test-support/with-database
   (fn [connection]
     (let [query '[:find ?f . :where [?f :seon.fn/sym 'seon.db/transact!]]
           captured (atom [])
           refused (binding [db/*read-evidence-sink* captured]
                     (db/q query @connection))
           evidence (db/read-evidence
                     [{:seon.db/db @connection
                       :seon.db/source-argument-position 0
                       :datahike.read/dependency-plan :all
                       :seon.db/read-request
                       {:seon.db/read-operation :q
                        :seon.db/query-request {:query query :args [::db/database]}}
                       :seon.db/read-result nil}]
                     {:seon.db/retain-read-results? true})]
       (is (= :db.type/symbol
              (get-in refused [:seon.error/data :seon.error/source ::db/value-type]))
           "the read refuses naming the installed value type")
       (is (empty? @captured) "a refused read leaves no evidence")
       (test-support/transacted!
        connection [(test-support/revision @connection [:seon.fn/sym 'seon.db/transact!] {:seon.fn/doc "moved"})])
       (is (false? (db/read-evidence-current? @connection evidence))
           "evidence retained before the check replays as a refusal")))))

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
                            connection (test-support/agent-tx @connection "bound-agent"))
             :seon.cluster/name (test-support/seed-cluster! connection "bound-cluster"))
           (is (false? (db/read-evidence-current? @connection before))
               "the bound attribute must invalidate its evidence")))))))

(deftest collection-pull-evidence-walks-each-selector-once
  (test-support/with-database
   (fn [connection]
     (let [database (db/db connection)
           entities (vec (take 100 (sort (db/q '[:find [?e ...] :where [?e :seon.fn/sym]] database))))
           query '[:find (pull ?e [:seon.fn/sym]) :in $ [?e ...] :where [?e :seon.fn/sym]]]
       (is (= 100 (count entities)) "the canonical program supplies the growing input")
       (doseq [n [1 10 100]]
         (let [inputs (subvec entities 0 n)
               captured (atom [])
               [answer calls]
               (test-support/armed-calls
                '#{seon.db/pull-index-patterns}
                #(binding [db/*read-evidence-sink* captured]
                   (db/q query database inputs)))
               patterns (set (mapcat :seon.db/read-index-patterns @captured))]
           (is (= n (count answer)))
           (is (= (set inputs) (set (map :seon.db/pattern-entity patterns))))
           (is (= 1 (get calls 'seon.db/pull-index-patterns))
               "one pull dependency walk regardless of collection input size")))
       (let [[_ calls] (test-support/armed-calls
                        '#{seon.db/pull-index-patterns}
                        #(binding [db/*read-evidence-sink* nil]
                           (doseq [n [1 10 100]]
                             (db/pull-many database [:seon.fn/sym] (subvec entities 0 n)))))]
         (is (zero? (get calls 'seon.db/pull-index-patterns))
             "uncaptured pulls do no dependency walk"))))))

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
       ;; b96d6e684: stored evidence references its read; only process-local
       ;; retained evidence keeps the request, result and digest for replay.
       (is (not-any? #(or (find % :seon.db/read-result) (find % :seon.db/read-result-digest)) durable)
           "default evidence cannot inline read payloads or their digests into evaluation facts")
       (is (every? #(= 64 (count (:seon.db/read-result-digest %))) process-local)
           "process-local pull evidence retains a fixed-size result digest")
       (is (every? #(find % :seon.db/read-result) process-local)
           "the explicit process-local cache retains stable replay values")
       (test-support/transacted!
                    connection
                    (into (test-support/agent-tx @connection "db-test-recipient")
                      [{:seon.message/id "semantic-replay-unrelated"
                        :seon.message/to [:seon.agent/id "db-test-recipient"]
                        :seon.message/content "unrelated"}]))
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
     (let [digest (comp @(ns-resolve 'seon.db 'read-result-digest)
                        @(ns-resolve 'seon.db 'stable-value))]
       (is (= (digest {:b #{3 2} :a 1})
              (digest {:a 1 :b #{2 3}}))
           "canonical map and set ordering does not alter the digest"))
     (doseq [subject [[:seon.ns/name 'seon.db-test/missing]
                      [:seon.message/id "large-digest"]]]
       (when (= :seon.message/id (first subject))
         (test-support/transacted!
                      connection
                      (into (test-support/agent-tx @connection "db-test-recipient")
                        [{:seon.message/id (second subject)
                          :seon.message/to [:seon.agent/id "db-test-recipient"]
                          :seon.message/content
                          (apply str (repeat 100000 "x"))}])))
       (let [captured (atom [])]
         (binding [db/*read-evidence-sink* captured]
           (db/pull @connection '[*] subject))
         (let [evidence (db/read-evidence @captured)
               retained (db/read-evidence @captured {:seon.db/retain-read-results? true})]
           (is (= 64 (count (:seon.db/read-result-digest (first retained))))
               "nil and large pull results both retain a process-local digest")
           (is (< (count (pr-str evidence)) 2000)
               "durable evidence size does not scale with the pull result")
           (is (not-any? #(find % :seon.db/read-result-digest) evidence)
               "stored evidence carries no result digest (b96d6e684)")))))))

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
  (let [stabilize @(ns-resolve 'seon.db 'stable-value)
        source-touched? (atom false)
        dangerous (lazy-seq (reset! source-touched? true) [1])]
    (is (= [true [1 #uuid "00000000-0000-0000-0000-000000000000"]]
           (stabilize (list 1
                            #uuid "00000000-0000-0000-0000-000000000000")))
        "a finite counted EDN sequence remains eligible")
    (is (= [false nil] (stabilize (Object.)))
        "an opaque object is not semantic replay evidence")
    (is (= [false nil] (stabilize dangerous))
        "an uncounted sequence is not semantic replay evidence")
    (is (false? @source-touched?)
        "declining a lazy value never touches its source")))

;; Class: a read result's replay walk costs a fraction of digesting it. Each
;; node once paid three `satisfies?` misses (`db?`), 98% of the walk and ~6x
;; the canonical encoding over one form's 468 namespace-entity pulls.
(deftest the-stable-walk-costs-less-than-encoding-the-read-result
  (test-support/with-database
   (fn [connection]
     (let [stabilize @(ns-resolve 'seon.db 'stable-value)
           result (vec (for [n (range 2000)]
                         (into {:db/id n} (map (fn [k] [(keyword "seon.db-test" (str "k" k)) (str n "-" k)]))
                               (range 10))))
           median-ms (fn [f] (nth (sort (repeatedly 5 #(let [started (System/nanoTime)]
                                                         (f)
                                                         (/ (- (System/nanoTime) started) 1e6))))
                                  2))
           walk (median-ms #(stabilize result))
           encoding (median-ms #(id/id result 64))]
       (is (= [true result] (stabilize result)))
       (is (< walk encoding)
           "the walk costs no more than encoding the same value")
       (is (false? (first (stabilize [{:db @connection}]))) "a database value declines")
       (is (false? (first (stabilize {:connection connection}))) "a connection declines")))))

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
                 ;; A since or history view has no committed revision, so only
                 ;; replay can prove it; since b96d6e684 only process-local
                 ;; retained evidence keeps the request to replay.
                 (is (true? (db/read-evidence-current?
                             database (db/read-evidence @captured {:seon.db/retain-read-results? true})))
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
           ;; The request names its world's projection: the writer stopped
           ;; stamping reports' database values with one (9b8c5b405), so the
           ;; identity-only declaration of a database is read from here.
           request
           {:seon.sci.admit/value
            {:probe/report (db/transact! connection [])}
            :seon.schema/projection (db/carried-projection @connection)
            :seon.sci.admit/interrupt-fn (fn [])
            :seon.sci.admit/caps (config/result-caps effective)}
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
      connection (test-support/agent-tx @connection "db-shared-first" 'my.agents.db-shared))
     (let [accepted
           (binding [db/*conn* connection]
             (db/transact!
              (test-support/agent-tx @connection "db-shared-second" 'my.agents.db-shared)))]
       (is (some? (:seon.db/tx accepted))
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
         (is (= [:seon.cluster.eval/id owner-id]
                (get-in (db/render-rejection-ai rejected)
                        [:seon.error/problems 0 :seon.error/offending :seon.db/conflict-owner]))
             "the AI face names the existing owner as data")
         (is (some #(str/includes? (str %) owner-id)
                   (test-support/hiccup-leaves (db/render-rejection-html rejected)))
             "the HTML face shows the existing owner")
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
                  (test-support/agent-tx @connection "db-cas-owner"))
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

(deftest ^{:seon/tasks #{'seon.sci.eval/a-test-namespace-def-that-fails-to-interpret-is-used-as-its-refusal}}
  a-merge-shares-the-write-fence-and-records-immutable-lineage
  (with-codec-database
   {:seon.test-support/extra-schema (schema.datahike/malli->datahike-schema-in fixture-projection [::row-id])}
   (fn [connection]
     (let [configuration (:config @connection)
           candidate (keyword "seon.db-test" (str (name (:branch configuration)) "-merge"))
           _ (d/branch! connection (:branch configuration) candidate)
           candidate-connection (d/connect (assoc configuration :branch candidate))
           merge-write (fn [basis-t entity]
                         (db/transact! connection {:tx-data [entity] :datahike/expected-basis-t basis-t
                                                   :parents #{(d/commit-id @candidate-connection)}}))]
       (try
         (d/transact candidate-connection [{::row-id "merged"}])
         (let [c (d/commit-id @candidate-connection)
               stale-t (:max-tx @connection)
               h (do (test-support/transacted! connection [{::row-id "moved"}])
                     (d/commit-id @connection))]
           (is (= :transaction/stale-basis
                  (get-in (merge-write stale-t {::row-id "merged"}) [:seon.error/data :error])))
           (is (= {:seon.fn/sym 'seon.source.test/incomplete} ; the final owning-value validator
                  (get-in (merge-write (:max-tx @connection) {:seon.fn/sym 'seon.source.test/incomplete})
                          [:seon.error/data :seon.db/entity])))
           (is (= h (d/commit-id @connection)) "refused merges leave the head and its datoms")
           (let [report (merge-write (:max-tx @connection) {::row-id "merged"})]
             (is (= #{h c} (set (d/parent-commit-ids @connection))))
             (is (= (d/commit-id (:db-after report)) (d/commit-id @connection)))
             (is (= c (d/commit-id @candidate-connection)) "the merged parent is unchanged")))
         (finally (d/release candidate-connection) (d/delete-branch! connection candidate)))))))

(deftest a-system-write-the-writer-never-acknowledges-returns-outcome-unknown-at-the-bound
  ;; Every write, system writes included, is bounded by the declared dial
  ;; (lane-flow-owns-running-machinery.md N3, R6): this write carries no agent
  ;; provenance, and the real writer is held by a latch past the bound.
  (test-support/with-database
   (fn [connection]
     (let [cluster-name "bounded-write-deref"
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
           (let [before (db/basis-t @connection)
                 outcome (future
                           (db/transact!
                            connection
                            [(test-support/revision @connection [:seon.fn/sym 'seon.db/transact!] {:seon.fn/doc "bounded-write-deref-target"})]))]
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
                    (:seon.fn/doc
                     (db/pull @connection
                              [:seon.fn/doc]
                              [:seon.fn/sym 'seon.db/transact!])))
                 "the unknown transaction may commit after the caller stops waiting"))
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
                    (test-support/agent-tx @connection "throwing-listener-agent")))
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
                 "the realized answer is the committed report")
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
                    (test-support/agent-tx @connection "after-throwing-listener-agent"))]
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
                    (into (test-support/agent-tx @connection "db-test-recipient")
                      [{:seon.message/id "db-test-temporal"
                        :seon.message/to [:seon.agent/id "db-test-recipient"]
                        :seon.message/content "temporal"}]))
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

(deftest connection-identity-answers-a-released-connection-with-its-typed-value
  (let [configuration {:store {:backend :memory :id (random-uuid)}
                       :keep-history? false}
        _ (d/create-database configuration)
        connection (d/connect configuration)]
    (try
      (is (= {:datahike/connection-id
              (datahike.store/connection-id (:config @connection))}
             (db/connection-identity connection))
          "a live connection answers the dependency's own identity")
      (d/release connection)
      (let [released (db/connection-identity connection)]
        (is (schema/valid-candidate-value? (schema/handed-projection)
                                          :seon.error/value released))
        (is (true? (:seon.db/released-connection released))
            "a released connection answers the declared value, never a throw")
        (is (= 'seon.db/connection-identity (:seon.error/operation released))))
      (finally
        (d/delete-database configuration)))))

(deftest the-exam-query-returns-the-fixtures-true-count
  (test-support/with-database
   (fn [connection]
     (let [database @connection
           fixture-count (d/q exam-query database)]
       (is (pos-int? fixture-count))
       (binding [db/*conn* connection]
         (is (= fixture-count (db/q exam-query))))))))

(deftest a-read-classifies-only-declared-refusals
  ;; Datahike and its parser refuse a read with an ExceptionInfo; that is the
  ;; declared case a read classifies. Anything else a read meets — the
  ;; orderly stop's interrupt, a host failure — propagates unchanged, whole.
  (test-support/with-database
   (fn [connection]
     (let [query '[:find ?e :in $ ?f :where [?e :seon.agent/id ?v] [(?f ?v)]]
           throwing (fn [throwable] (fn [_] (throw throwable)))
           interrupt (InterruptedException. "the stop's interrupt")
           host (IllegalStateException. "a host failure")]
       (test-support/transacted! connection (test-support/agent-tx @connection "classify" 'my.agents.classify))
       (is (identical? interrupt
                       (try (db/q query @connection (throwing interrupt)) nil
                            (catch InterruptedException thrown thrown)))
           "an interrupt is the stop's sentinel, never a refused read")
       (is (identical? host
                       (try (db/q query @connection (throwing host)) nil
                            (catch IllegalStateException thrown thrown))))
       (is (true? (:seon.db/invalid-read (db/q @connection '[:find])))
           "a Datahike parser refusal is still a flat, typed refusal")))))

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
                               (test-support/agent-tx @connection "identity-admission-present"))
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
     (doseq [[entity attribute]
             [[{:seon.cluster/name "missing-reference-cluster"} :seon.cluster/config]
              [{:seon.turn/id "missing-reference-turn"} :seon.turn/agent]
              [{:seon.cluster.eval/id "missing-reference-evaluation"}
               :seon.cluster.eval/run]]]
       (let [before (db/basis-t (db/db connection))
             refusal (db/transact! connection [entity])
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
                               (test-support/agent-tx @connection "temporal-schema-present"))
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
                                       (test-support/agent-tx @writing-connection "own"))
                elided (db/transact! (test-support/agent-tx @writing-connection "elided"))
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
            ;; The elided arity answers resolved changes, not a report (07ed79283).
            (is (int? (::db/tx elided))
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
            (is (= #{"own" "elided"} (message-ids writing-connection)))
            (is (empty? (message-ids foreign-connection))
                "nothing reaches the foreign branch"))))))))

(deftest final-entity-keyword-sets-are-not-lookup-refs
  (test-support/with-database
   (fn [connection]
     (let [entity (test-support/program-fn-entity (db/db connection) 'seon.db/final-keyword-set-fixture "(defn final-keyword-set-fixture [] true)")
           function-id [:seon.fn/sym (:seon.fn/sym entity)]
           keywords #{:seon.agent/id :seon.db/db}]
       (test-support/transacted!
        connection
        [entity
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
           (is (string? (:seon.db.write.attempt/request-id refusal)))
           (is (= basis (:max-tx (db/db connection))))
           (is (= {:seon.schedule/id "f2-invalid"}
                  (get-in refusal [:seon.error/data :seon.db/entity])))
           (is (= :seon.schedule/zone-id (:seon.db/attribute refusal)))
           (is (= "f2-invalid" (:seon.schedule/id (:seon.db/offending refusal)))
               "a missing key's offending value is the entity missing it")
           (is (not (contains? (:seon.db/offending refusal) :seon.schedule/zone-id)))
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
             "expanded invalid assertions refuse even when a later operation repairs the entity")
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

(deftest a-create-is-validated-complete-while-an-upsert-validates-the-merged-entity
  (test-support/with-database
   (fn [connection]
     (let [existing (db/pull (db/db connection) '[*] [:seon.fn/sym (quote seon.id/id)])]
       (is (some? (:db/id existing)) "the canonical fixture carries a complete program entity")
       (test-support/transacted! connection
                                 [(test-support/revision (db/db connection) [:seon.fn/sym (quote seon.id/id)]
                                                         {:seon.fn/doc "updated documentation"})])
       (let [updated (db/pull (db/db connection) '[*] [:seon.fn/sym (quote seon.id/id)])]
         (is (= "updated documentation" (:seon.fn/doc updated))
             "a sparse upsert of an existing entity is the ruled partial-upsert behaviour")
         (is (= (:db/id (:seon.fn/ns existing)) (:db/id (:seon.fn/ns updated)))
             "the merged entity still carries the keys the submission omitted")))
     (doseq [[entity identities]
             [[{:seon.fn/sym (quote seon.source.test/incomplete) :seon.fn/doc "incomplete"}
               {:seon.fn/sym (quote seon.source.test/incomplete)}]
              [{:seon.test/sym (quote seon.source.test/incomplete-test)}
               {:seon.test/sym (quote seon.source.test/incomplete-test)}]]]
       (let [basis (db/basis-t (db/db connection))
             refusal (db/transact! connection [entity])]
         (is (string? (:seon.db.write.attempt/request-id refusal)))
         (is (= :seon.program/definition-digest (:seon.db/attribute refusal))
             "the refusal names the missing required key")
         (is (map? (:seon.db/offending refusal)) "the offending value is the entity missing the key")
         (is (not (contains? (:seon.db/offending refusal) :seon.program/definition-digest)))
         (is (some #(= :seon.program/definition-digest (last (:seon.error/path %)))
                   (get-in refusal [:seon.error/data :seon.error/problems]))
             "every failing path is a problem")
         (is (= identities (get-in refusal [:seon.error/data :seon.db/entity]))
             "the refusal names the entity by its identity attributes")
         (is (= basis (db/basis-t (db/db connection))) "the incomplete create commits nothing")))
     (test-support/transacted! connection [(test-support/namespace-entity @connection 'seon.source.test.complete)])
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
        connection (into (test-support/agent-tx @connection "root")
                     [(test-support/namespace-entity @connection namespace-name)
                      (test-support/program-fn-entity (db/db connection) function "(defn target [] nil)")
                      [:db.fn/call #'schedule/root-maintenance-seed-call]]))
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
             (is (string? (:seon.db.write.attempt/request-id refusal)))
             (is (= attribute (:seon.db/attribute refusal)))
             (is (some #{expected} (vals entity)))
             (is (= basis (db/basis-t (db/db connection)))))))))))

(deftest a-report-compares-only-the-written-callers-edges
  (test-support/with-database
   (fn [connection]
     (let [database (db/db connection) projection (db/carried-projection database)
           caller (:db/id (db/pull database [:db/id] [:seon.fn/sym 'seon.id/evaluation]))
           existing (count (d/datoms database :eavt caller :seon.fn/call-arities))]
       (doseq [n [1 10 100]]
         (let [tx (into [[:db/add caller :seon.fn/calls 'seon.id/id]]
                        (map #(vector :db/add caller :seon.fn/call-arities ['seon.id/id (+ 1000 %)])) (range n))
               report (d/with database tx)
               [comparison calls] (test-support/armed-calls
                                   '#{seon.db/pull-many}
                                   #(#'db/report-arity-comparison projection report))]
           (is (= (+ existing n) (::db/edges comparison))
               "only the written caller's edges are compared")
           (is (= n (count (::db/candidates comparison))))
           (is (= 2 (get calls 'seon.db/pull-many))
               "one caller batch and one bound batch serve the whole write")))))))

;; CLASS: a silent empty from an index the attribute does not have. Datahike
;; answers an AVET read of an unindexed attribute with no datoms, which every
;; reader takes for "no such fact" (a test's members would read as never run).
(deftest an-avet-read-of-an-unindexed-attribute-refuses
  (test-support/with-database
   (fn [connection]
     (let [database (db/db connection)]
       (is (false? (db.utils/indexing? database :seon.fn/keywords))
           "the subject attribute is installed without an index")
       (let [answer (db/datoms database :avet :seon.fn/keywords :seon.fn/sym)]
         (is (map? answer) "the read answers an error value, not an empty seq")
         (is (:seon.error/at answer)))
       (is (vector? (db/datoms database :avet :seon.fn/calls 'clojure.core/str))
           "an indexed attribute still answers its datoms")))))

(deftest a-first-population-is-judged-by-the-declarations-it-installs
  ;; CLASS: a writer read that derives its projection from a value holding no
  ;; declaration rows. A fresh store's first program write (a first
  ;; population, a nuke's boot) was refused "This operation requires a carried
  ;; schema projection": the arity gate read its `:db-before`, which declares
  ;; nothing yet, by that value's own projection (r6 triage E5).
  (test-support/with-database
   {:seon.test-support/fresh-store? true}
   (fn [connection]
     (is (nil? (db/q '[:find ?key . :where [_ :seon.schema/key ?key]] (db/db connection)))
         "the fresh store holds no declaration rows")
     (test-support/transacted!
      connection
      [(test-support/namespace-entity (db/db connection) 'sample.first)
       (test-support/program-fn-entity (db/db connection) 'sample.first/caller "(defn caller [x] (str x))"
                                       {:seon.fn/call-arities #{['clojure.core/str 1]}})])
     (is (= 'sample.first/caller
            (db/q '[:find ?sym . :where [_ :seon.fn/sym ?sym]] (db/db connection)))
         "the first program write is admitted"))))

(deftest arity-components-and-callers-are-checked-in-the-final-state
  (test-support/with-database
   (fn [connection]
     (let [projection (db/carried-projection (db/db connection))
           forms (:seon.schema.projection/forms projection)
           callee 'sample.arity/target
           caller 'sample.arity/caller
           entity (program/with-contract-facts
                {:seon.program/row
                 (test-support/program-fn-entity (db/db connection) callee
                                              "(defn target {:malli/schema [:=> [:cat :int] :int]} [x] x)")
                 :seon.program/compile-options
                 (:seon.schema.projection/compile-options projection)
                 :seon.program/predicate-functions (schema/predicate-functions-in projection)
                 :seon.program/schema-keys (set (keys forms))
                 :seon.program/schema-forms forms})]
       (test-support/transacted!
        connection
        [(test-support/namespace-entity (db/db connection) 'sample.arity)
         ;; The contract facts are part of the final entity its digest covers.
         (test-support/revised-entity (db/db connection) entity {})
         ;; An edge is both facts, as every analyzed row holds them: since
         ;; 88c65fc19 the gate finds a changed callee's callers by
         ;; :seon.fn/calls.
         (test-support/program-fn-entity (db/db connection) caller "(defn caller [x] (target x))"
                                      {:seon.fn/calls #{'clojure.core/defn callee}
                                       :seon.fn/call-arities #{[callee 1]}})])
       (let [arity (db/q '[:find ?arity . :in $ ?callee
                          :where [?f :seon.fn/sym ?callee]
                          [?f :seon.fn/arities ?arity]] (db/db connection) callee)
             edits [[:db/add arity :seon.fn.arity/min 2]
                    [:db/add arity :seon.fn.arity/max 2]
                    [:db/add arity :seon.fn.arity/argument-count 2]]
             basis (:max-tx @connection)
             refusal (db/transact! connection edits)]
         (is (string? (:seon.db.write.attempt/request-id refusal)))
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

(deftest a-program-entity-keeps-only-the-definition-digest-it-produces
  ;; CLASS: an identity every reader trusts that the writer never checked. A
  ;; entity whose source changed but whose stored digest did not read "unchanged"
  ;; to acquisition, install and test reach. The writer refuses it by name.
  (test-support/with-database
   (fn [connection]
     (let [function 'sample.digest/target
           entity (test-support/program-fn-entity (db/db connection) function "(defn target [x] x)")]
       (test-support/transacted!
        connection [(test-support/namespace-entity (db/db connection) 'sample.digest) entity])
       (let [basis (db/basis-t (db/db connection))
             stale (assoc entity :seon.fn/source "(defn target [x] (inc x))")
             refusal (db/transact! connection [stale])
             produced (:seon.program/definition-digest
                       (test-support/program-fn-entity (db/db connection) function (:seon.fn/source stale)))]
         (is (= :seon.program/definition-digest (:seon.error/member refusal)))
         (is (= 'seon.db/transact! (:seon.error/operation refusal)))
         (is (= (:seon.program/definition-digest entity) (:seon.error/offending refusal)))
         (is (= produced (:seon.error/expected refusal)))
         (is (= {:seon.fn/sym function} (get-in refusal [:seon.error/data :seon.db/entity])))
         (is (= basis (db/basis-t (db/db connection))))
         (test-support/transacted!
          connection [(test-support/program-fn-entity (db/db connection) function (:seon.fn/source stale))])
         (is (= produced (:seon.program/definition-digest
                          (db/pull (db/db connection) [:seon.program/definition-digest]
                                   [:seon.fn/sym function])))))))))

(deftest evidence-on-a-declaration-is-not-its-definition
  ;; CLASS: the digest check read attributes the digest does not own. A result
  ;; count written onto a test declaration was refused, so no test evidence
  ;; could be recorded. Only an attribute the family owns is checked.
  (test-support/with-database
   (fn [connection]
     (let [test-symbol 'sample.evidence/smoke]
       (test-support/transacted!
        connection [(test-support/namespace-entity (db/db connection) 'sample.evidence)
                    (test-support/program-entity (db/db connection) [:seon.test/sym test-symbol]
                                              "(clojure.test/deftest smoke (clojure.test/is true))")])
       (test-support/transacted!
        connection [[:db/add [:seon.test/sym test-symbol] :seon.test/pass-count 7]])
       (is (= 7 (:seon.test/pass-count (db/pull (db/db connection) [:seon.test/pass-count]
                                                [:seon.test/sym test-symbol]))))
       (is (= :seon.program/definition-digest
              (:seon.error/member
               (db/transact! connection [[:db/add [:seon.test/sym test-symbol] :seon.test/long "An owned attribute."]])))
           "an owned attribute is still checked")))))

(deftest a-branch-switch-to-another-connection-carries-no-receipt
  ;; An evaluation's receipt is a lookup ref valid only on its own branch
  ;; (`seon.sci.eval` binds it); a switch to another connection's custody must
  ;; not stamp it onto that branch's transactions.
  (test-support/with-database
   (fn [connection]
     (test-support/with-database
      (fn [other]
        (let [receipt [:seon.cluster.eval/id "custody-receipt"]
              stamp #(get (last (:tx-data (#'db/stamp-transaction (db/db connection) [] nil))) ::db/receipt)]
          (is (not (identical? connection other)))
          (with-bindings {#'db/*conn* connection #'db/*receipt* receipt}
            (is (= receipt (stamp))
                "the evaluation's own custody stamps its receipt")
            (is (= receipt
                   (db/with-branch {:seon.db/connection connection} stamp))
                "the same connection keeps it")
            (is (nil? (db/with-branch {:seon.db/connection other} stamp))
                "another connection's custody carries none")
            (is (nil? (db/with-branch {} stamp))
                "no custody carries none"))))))))

(deftest a-store-without-program-schema-admits-its-writes
  ;; CLASS: the digest check read `:seon.fn/sym` on every write, and Datahike
  ;; refuses a datom read naming an attribute its schema lacks. Only a family
  ;; the store installed is checked.
  (let [root (java.io.File. "tmp" (str "db-test-schemaless-" (random-uuid)))
        opened (store/open-store! {:seon.store/dir (str root "/store")})]
    (try
      (let [connection (:seon.store/connection-object opened)]
        ;; An identity-less entity must have an owner, so the dial is written
        ;; under an identity the store also installs.
        (test-support/transacted! connection [{:db/ident :sample.schemaless/id
                                               :db/valueType :db.type/string
                                               :db/cardinality :db.cardinality/one
                                               :db/unique :db.unique/identity}
                                              {:db/ident :seon.config.eval.result/blob-threshold
                                               :db/valueType :db.type/long
                                               :db/cardinality :db.cardinality/one}])
        (test-support/transacted! connection [{:sample.schemaless/id "dial"
                                               :seon.config.eval.result/blob-threshold 4096}])
        (is (= [4096] (d/q '[:find [?v ...] :where [_ :seon.config.eval.result/blob-threshold ?v]]
                           @connection))))
      (finally
        (store/release-store! opened)
        (test-support/delete-recursively! root)))))

(deftest ordered-seed-occurrences-keep-the-digest-their-indexer-produced
  ;; CLASS: the writer read a declared vector of components back as a set, so
  ;; every indexed seeded test was refused. The declared collection is kept.
  (test-support/with-database
   (fn [connection]
     (let [entity (test-support/program-entity
                (db/db connection) '[:seon.test/sym sample.seedorder/seeded]
                (str "(clojure.test/deftest ^{:seon.test/seed [{:seon.test/skip-reason \"a\"}"
                     " {:seon.test/skip-reason \"b\"}]} seeded (clojure.test/is true))"))]
       (is (= [:literal :literal] (mapv :seon.test.seed/form (:seon.test/seed entity))))
       (test-support/transacted!
        connection [(test-support/namespace-entity (db/db connection) 'sample.seedorder) entity])
       (is (= (:seon.program/definition-digest entity)
              (:seon.program/definition-digest
               (db/pull (db/db connection) [:seon.program/definition-digest]
                        [:seon.test/sym 'sample.seedorder/seeded]))))))))

(deftest one-owner-digests-a-definition-with-its-stored-namespace
  ;; CLASS: producers digested with resolver contexts other than the stored
  ;; namespace (an SCI context, a widened analysis `ns` form), so settled REPL
  ;; definitions were refused. `db/definition-digest-of` is the one owner.
  (test-support/with-database
   (fn [connection]
     (let [database (db/db connection)
           projection (db/carried-projection database)
           namespace-entity (test-support/namespace-entity database 'sample.context)
           widened (assoc namespace-entity :seon.ns/refers
                          #{{:seon.ns.refer/local 'is :seon.ns.refer/target-ns 'clojure.test
                             :seon.ns.refer/target-name 'is}})
           ;; The digest keeps only the resolver context a definition uses
           ;; (9b6df1ff4): `f` calls the refer's target, `g` uses nothing.
           entity (test-support/program-fn-entity database 'sample.context/f "(defn f [x] (is x))"
                                            {:seon.fn/calls #{'clojure.test/is}})
           unused (test-support/program-fn-entity database 'sample.context/g "(defn g [x] x)")
           digested #(assoc entity :seon.program/definition-digest (db/definition-digest-of projection entity %))]
       (is (= (:seon.program/definition-digest entity) (:seon.program/definition-digest (digested namespace-entity))))
       (is (= (db/definition-digest-of projection unused namespace-entity)
              (db/definition-digest-of projection unused widened))
           "a refer the definition does not use leaves its digest unchanged")
       (is (= :seon.program/definition-digest
              (:seon.error/member (db/transact! connection [namespace-entity (digested widened)]))))
       (test-support/transacted! connection [namespace-entity (digested namespace-entity)])))))

(deftest a-namespace-entity-change-re-derives-its-declarations-digests
  ;; CLASS: a function's digest includes its namespace's resolver context, so
  ;; a require added to the namespace changes its meaning; the declaration kept
  ;; its old digest because only touched entities were checked. The writer
  ;; re-derives every declaration of a namespace entity it writes, in the same
  ;; transaction.
  (test-support/with-database
   (fn [connection]
     (let [namespace-entity (test-support/namespace-entity (db/db connection) 'sample.rederive)
           ;; `f` uses the namespace the require adds; `g` uses nothing, so
           ;; its digest keeps (9b6df1ff4: only the used resolver context).
           entity (test-support/program-fn-entity (db/db connection) 'sample.rederive/f "(defn f [x] (clojure.set/union x))"
                                            {:seon.fn/calls #{'clojure.set/union}})
           unused (test-support/program-fn-entity (db/db connection) 'sample.rederive/g "(defn g [x] x)")
           _ (test-support/transacted! connection [namespace-entity entity unused])
           required (program/declaration-row (db/carried-projection (db/db connection))
                                             {:seon.ns/name 'sample.rederive :seon.ns/requires #{'clojure.set}
                                              :seon.ns/source "(ns sample.rederive (:require [clojure.set]))"}
                                             :all :agent)
           _ (test-support/transacted! connection [required])
           database (db/db connection)
           stored (db/pull database '[*] [:seon.fn/sym 'sample.rederive/f])
           stored-namespace (@#'seon.db/declaration-value database (db/identity-attributes database)
                             (:db/id (db/pull database [:db/id] [:seon.ns/name 'sample.rederive])))]
       (is (not= (:seon.program/definition-digest entity) (:seon.program/definition-digest stored)))
       (is (= (db/definition-digest-of (db/carried-projection database) entity stored-namespace)
              (:seon.program/definition-digest stored)))
       (is (= (:seon.program/definition-digest unused)
              (:seon.program/definition-digest (db/pull database [:seon.program/definition-digest]
                                                        [:seon.fn/sym 'sample.rederive/g]))))))))

(deftest a-retraction-of-a-declarations-own-data-re-derives-its-digest
  ;; CLASS: an attribute's replacement on adoption drops its data from the
  ;; declarations holding it (a value-type change of `:seon.task/tasks`), and
  ;; the writer refused their now-stale digests, so the live store could not
  ;; adopt. A row losing family-owned data by operation, its digest unwritten,
  ;; is re-derived in the same transaction.
  (test-support/with-database
   (fn [connection]
     (let [namespace-entity (test-support/namespace-entity (db/db connection) 'sample.dropped)
           entity (test-support/program-fn-entity (db/db connection) 'sample.dropped/f "(defn f [x] x)"
                                                  {:seon.task/tasks #{'sample.dropped/a-task}})
           _ (test-support/transacted! connection [namespace-entity entity])
           eid (:db/id (db/pull (db/db connection) [:db/id] [:seon.fn/sym 'sample.dropped/f]))
           _ (test-support/transacted! connection [[:db/retract eid :seon.task/tasks 'sample.dropped/a-task]])
           database (db/db connection)
           stored (db/pull database [:seon.task/tasks :seon.program/definition-digest] [:seon.fn/sym 'sample.dropped/f])]
       (is (nil? (:seon.task/tasks stored)))
       (is (not= (:seon.program/definition-digest entity) (:seon.program/definition-digest stored)))
       (is (= (db/definition-digest-of (db/carried-projection database) (dissoc entity :seon.task/tasks)
                                       (@#'seon.db/declaration-value database (db/identity-attributes database)
                                        (:db/id (db/pull database [:db/id] [:seon.ns/name 'sample.dropped]))))
              (:seon.program/definition-digest stored)))))))

(deftest render-declarations-require-their-final-function-entity
  (test-support/with-database
   (fn [connection]
     (let [renderer (symbol "sample.render" "ai")
           schema-key ::render-target
           definition [:map {:seon.render/ai renderer}]
           projection (schema/build-projection
                       (assoc (:seon.schema.projection/forms (db/carried-projection (db/db connection)))
                              schema-key definition))
           entity (first (schema/canonical-schema-entities projection {schema-key definition}))
           basis (:max-tx @connection)
           refusal (db/transact! connection [entity])
           expected [{:seon.schema/key schema-key
                      :seon.render/property :seon.render/ai
                      :seon.render/function renderer}]]
       (is (string? (:seon.db.write.attempt/request-id refusal)))
       ;; The final value's projection refuses first (e6b559711 judges a
       ;; report by the declarations it leaves; `render-contract-refusal!`
       ;; names a renderer with no declared contract), before the writer's
       ;; own render-target check.
       (is (= renderer (get-in refusal [:seon.error/data :seon.render/function])))
       (is (= basis (:max-tx @connection)))
       (test-support/transacted!
        connection
        [(test-support/namespace-entity (db/db connection) 'sample.render)
         (test-support/program-fn-entity
          (db/db connection) renderer
          "(defn ai {:malli/schema [:=> [:cat :seon.schema/value] :string]} [value] (pr-str value))")
         entity])
       (let [refusal (db/transact! connection [[:db/retractEntity [:seon.fn/sym renderer]]])]
         (is (string? (:seon.db.write.attempt/request-id refusal)))
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
           refusal (@#'db/read-declarations {:not :a-database} 'seon.db/pull)]
       (is (= 'seon.db/pull (:seon.db.read/unreadable-declarations refusal)))
       (is (= :seon.db/installed-schema (:seon.error/member refusal)))
       (is (= 'seon.db/pull (:seon.error/operation refusal))
           "the refusal names its operation (an absent one threw an NPE in `diagnostic`)")
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
         ;; the read names its own variant; the refusal's evidence is kept
         (is (= :seon.db/invalid-read-error (:seon.error/type result)))
         (is (true? (:seon.db/invalid-read result)))
         (is (= (dissoc refusal :seon.error/type)
                (dissoc result :seon.db/invalid-read :seon.db/refused-read-operation :seon.error/type))))
       ;; A read refusal handed on keeps the read that produced it, verbatim.
       (let [refused (db/q refusal '[:find ?e :where [?e :seon.agent/id]])]
         (is (= 'seon.db/q (:seon.db/refused-read-operation refused)))
         (doseq [result [(db/pull refused [:db/id] 1) (db/datoms refused :eavt) (db/as-of refused 0)
                         (db/db refused) (db/q refused '[:find ?e :where [?e :seon.agent/id]])]]
           (is (= refused result))))
       (let [result (db/transact! refusal {:tx-data []})]
         (is (true? (:seon.db/transaction-refused result)))
         (is (= :seon.db.write/validation-refusal (:seon.error/type result)))
         (is (= (dissoc refusal :seon.error/type)
                (dissoc result :seon.db/transaction-refused :seon.db.write.attempt/request-id :seon.error/type))))))))

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
                           {:seon.db/read-operation :seon.db/not-an-operation})]
       ;; One flat error: member and offending sit on the refusal itself.
       (is (= :seon.db/not-an-operation (:seon.db.read/unknown-read-operation unknown)))
       (is (= :seon.db/read-operation (:seon.error/member unknown)))
       (is (= :seon.db/not-an-operation
              (:seon.error/offending unknown)))))))

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
       (is (= :seon.db/unowned-entity (:seon.db/owned-value-refusal result)))
       (is ((schema/projection-validator (schema/handed-projection) :seon.db/error-result) result))
       (is (= before (db/basis-t @connection)))))))

(deftest a-declared-but-uninstalled-write-key-keeps-the-missing-key-hint
  (test-support/with-database
   (fn [connection]
     (let [database @connection
           projection (schema/handed-projection)
           attribute :seon.message/content
           drifted (assoc database :schema (dissoc (:schema database) attribute))
           entity {:seon.message/id "drifted-message" attribute "Uninstalled content"}
           candidates (#'db/write-key-candidates projection entity)
           refusal (#'db/write-map-error drifted projection entity [])]
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
;; branch or committed identity, so they share one derivation. A derivation
;; builds a new projection object, so identity observes it on this thread
;; alone: no deriver is redefined.
(deftest ^{:seon/tasks #{'seon.db/equal-declaration-fixtures-branch-off-different-commits}}
  equal-declaration-content-shares-one-projection-across-values
  (test-support/with-database
    (fn [outer]
      (let [committed (db/carried-projection (db/db outer))]
        (test-support/with-database
          (fn [inner]
            (let [branch (let [started (System/nanoTime)
                               projection (db/carried-projection (db/db inner))]
                           [projection (/ (- (System/nanoTime) started) 1e6)])
                  in-transaction (db/carried-projection (:db-after (d/with (db/db inner) [])))]
              (is (= (d/commit-id (db/db outer)) (d/commit-id (db/db inner)))
                  "both fixtures branch off the executing commit")
              (is (identical? committed (first branch))
                  "a new branch at an equal commit reads the committed population")
              (is (< (second branch) 50.0) "and does not derive it")
              (is (identical? committed in-transaction)
                  "an in-transaction value with the same declarations reads it too, deriving nothing"))))))))

(deftest an-in-transaction-declaration-derives-its-population-once
  (test-support/with-database
    (fn [connection]
      (let [database (db/db connection)
            committed (db/carried-projection database)
            staged (:db-after (d/with database
                                      [[:db.fn/call #'turn/row-tx {}
                                        {:seon.schema/key ::staged :seon.schema/form (pr-str :int)}]]))
            first-read (db/carried-projection staged)
            second-read (db/carried-projection staged)]
        (is (nil? (datahike.db/committed-value-identity staged))
            "the speculative value has no committed identity")
        (is (false? (:datahike.cache/committed? (:cache-context staged)))
            "its revision context is never committed")
        (is (contains? (:seon.schema.projection/forms first-read) ::staged)
            "its own declaration datoms select its population")
        (is (not (contains? (:seon.schema.projection/forms committed) ::staged)))
        (is (identical? first-read second-read)
            "repeated reads of one in-transaction value derive once")))))

;; Class: the projection reads schema forms and function contracts, never a
;; function's source text. A write changing only `:seon.fn/source` (adoption of
;; every edited body) re-derived the whole projection, 51 ms, to the identical value.
(deftest a-function-body-write-reads-its-basis-projection
  (test-support/with-database
    (fn [connection]
      (let [database (db/db connection)
            committed (db/carried-projection database)
            function (:e (first (d/datoms database :avet :seon.fn/sym 'seon.blob/digest)))
            source (:v (first (d/datoms database :eavt function :seon.fn/source)))
            edited (:db-after (d/with database [[:db/add function :seon.fn/source (str source "\n")]]))
            started (System/nanoTime)
            projection (db/carried-projection edited)
            elapsed-ms (/ (- (System/nanoTime) started) 1e6)]
        (is (string? source) "the fixture holds the function's source entity")
        (is (identical? committed projection))
        (is (< elapsed-ms 10.0)
            "a body-only write keeps every declaration revision, so it hits")))))

(deftest an-as-of-view-of-an-in-transaction-value-reuses-its-population
  ;; The writer's `:db.fn/call` argument is uncommitted; the test recorder reads
  ;; it as of the run's basis on every pull. Each such read re-read every
  ;; declaration datom (17-26 ms) before its key named the view's revisions.
  (test-support/with-database
    (fn [connection]
      (let [database (db/db connection)
            basis (:max-tx database)
            committed (db/carried-projection database)
            staged (:db-after (d/with database
                                      [[:db.fn/call #'turn/row-tx {}
                                        {:seon.schema/key ::as-of-staged :seon.schema/form (pr-str :int)}]]))
            views (mapv (fn [n] (db/as-of (:db-after (d/with database [{:seon.agent/id (str "as-of-view-" n)}])) basis))
                        (range 21))
            first-view (db/carried-projection (first views))
            started (System/nanoTime)
            later (mapv db/carried-projection (rest views))
            elapsed-ms (/ (- (System/nanoTime) started) 1e6)]
        (is (identical? committed first-view)
            "an in-transaction value's as-of view reads the population it held at that point")
        (is (every? #(identical? committed %) later))
        (is (< elapsed-ms 50.0)
            "twenty more views at one point share the first view's key")
        (is (not (contains? (:seon.schema.projection/forms (db/carried-projection (db/as-of staged basis)))
                            ::as-of-staged))
            "a view before the value's own declaration write lacks it")
        (is (contains? (:seon.schema.projection/forms
                        (db/carried-projection (db/as-of staged (inc (long (:max-tx staged))))))
                       ::as-of-staged)
            "a view at the write holds it")))))

;; Class: a memoized projection answers before `declares-program?`. On an
;; as-of view that check's first `:seon.schema/key` datom merges the temporal
;; index, 1.6 ms per call, which every rendered node paid (186k calls).
(deftest a-cached-as-of-projection-answers-without-reading-its-declarations
  (test-support/with-database
   (fn [connection]
     (let [database (db/db connection)
           view (db/as-of database (:max-tx database))
           first-read (db/carried-projection view)
           elapsed-ms (fn [f] (let [started (System/nanoTime)]
                                (dotimes [_ 50] (f))
                                (/ (- (System/nanoTime) started) 1e6)))
           hits (elapsed-ms #(db/carried-projection view))
           declaration-reads (elapsed-ms #(first (d/datoms view :avet :seon.schema/key)))]
       (is (identical? first-read (db/carried-projection view)))
       (is (< hits (/ declaration-reads 4))
           "cached reads stay within their bound")))))

;; Class: a read with no attribute set and no patterns is stale after any
;; write, decided without scanning history. Its `{}` pattern scanned the whole
;; since-history index per stale read (1400 ms and 2.4 GB each on default; 27 s
;; for one 480-read evaluation).
(deftest an-unnarrowed-read-is-stale-after-any-write-without-a-history-scan
  (test-support/with-database
   (fn [connection]
     (let [captured (atom [])
           flow (:db/id (db/pull @connection [:db/id] [:seon.ns/name 'seon.flow]))
           _ (binding [db/*read-evidence-sink* captured]
               (db/q '[:find [?a ...] :in $ ?e :where [?e ?a _]] @connection flow))
           ;; The retained patterns and replay inputs removed as data: what
           ;; remains is the stale `:all` read the index cannot narrow.
           unnarrowed (mapv (fn [evidence]
                              (-> evidence
                                  (dissoc :seon.db/read-request :seon.db/read-result-digest)
                                  (update-in [:datahike.read/dependency-plan :datahike.query.dependency/sources]
                                             (fn [sources] (mapv #(dissoc % :seon.db/read-index-patterns) sources)))))
                            (db/read-evidence @captured))
           _ (test-support/transacted!
              connection
              (into (test-support/agent-tx @connection "db-test-recipient")
                    [{:seon.message/id "unnarrowed-witness"
                      :seon.message/to [:seon.agent/id "db-test-recipient"]
                      :seon.message/content "unrelated"}]))
           started (System/nanoTime)
           current (db/read-evidence-current? @connection unnarrowed)
           elapsed-ms (/ (- (System/nanoTime) started) 1e6)]
       (is (= :all (get-in unnarrowed [0 :datahike.read/dependency-plan :datahike.query.dependency/sources
                                       0 :datahike.query.source/attributes])))
       (is (false? current))
       (is (< elapsed-ms 100.0) "currency stays within its bound")))))

;; Class: a retained pattern is witnessed by the matcher its query used. A
;; read over an attribute set naming uninstalled attributes (the contract-finding
;; report entity's keys, 2026-09-26) matched nothing; its witness through
;; `d/datoms` refused the attribute and root's graph panicked at every turn.
(deftest a-pattern-over-an-uninstalled-attribute-witnesses-no-change
  (test-support/with-database
   (fn [connection]
     (let [captured (atom [])
           _ (binding [db/*read-evidence-sink* captured]
               (db/q '[:find [?f ...] :where [?f :seon.fn/sym _]] @connection))
           uninstalled :seon.fn.contract/finding
           widened (mapv (fn [evidence]
                           (-> evidence
                               (dissoc :seon.db/read-request :seon.db/read-result-digest)
                               (update-in [:datahike.read/dependency-plan :datahike.query.dependency/sources]
                                          (fn [sources]
                                            (mapv #(update % :seon.db/read-index-patterns conj
                                                           {:seon.db/pattern-attribute uninstalled})
                                                  sources)))))
                         (db/read-evidence @captured))]
       (is (not (contains? (:schema @connection) uninstalled)))
       ;; A new function entity moves `:seon.fn/sym` (a revision of a stored entity
       ;; writes only the attributes it changes).
       (test-support/transacted!
        connection [(test-support/namespace-entity (db/db connection) 'sample.witness)
                    (test-support/program-fn-entity (db/db connection) 'sample.witness/f "(defn f [x] x)")])
       (is (seq widened))
       (is (false? (db/read-evidence-current? @connection widened))
           "the installed pattern witnesses its change")
       (is (true? (db/read-evidence-current?
                   @connection
                   (mapv (fn [evidence]
                           (update-in evidence [:datahike.read/dependency-plan :datahike.query.dependency/sources]
                                      (fn [sources]
                                        (mapv #(assoc % :seon.db/read-index-patterns
                                                      [{:seon.db/pattern-attribute uninstalled}])
                                              sources))))
                         widened)))
           "the uninstalled pattern witnesses none, and never throws")))))

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
         (test-support/transacted! connection [(test-support/revision @connection [:seon.fn/sym 'seon.db/transact!] {:seon.fn/doc "An unrelated attribute changed."})])
         (with-redefs [d/history (fn [database] (swap! scans inc) (history database))]
           (is (true? (db/read-evidence-current? @connection evidence))
               "an unrelated write leaves the read's attribute revision equal"))
         (is (zero? @scans) "equal revisions answer before any history scan")
         (doc! [:seon.ns/name 'seon.db] :seon.ns/doc "Another entity's value changed.")
         (is (true? (db/read-evidence-current? @connection evidence))
             "a write to another entity's value of the attribute leaves this pattern intact")
         (doc! flow :seon.ns/doc "The read value changed.")
         (is (false? (db/read-evidence-current? @connection evidence))))))))

(deftest an-entity-pull-is-checked-only-on-the-attributes-that-changed
  (doseq [retain? [false true]]
    (test-support/with-database
     (fn [connection]
       (let [digest (fn [n] (format "%064x" n))
             entity [:seon.dev.mcp.artifact/id (digest 1)]
             captured (atom [])
             _ (test-support/transacted! connection [{:seon.dev.mcp.artifact/id (digest 1)
                                                       :seon.dev.mcp.artifact/digest (digest 2)}])
             eid (:db/id (db/pull @connection [:db/id] entity))
             _ (binding [db/*read-evidence-sink* captured]
                 (db/pull @connection [:db/id :seon.dev.mcp.artifact/digest :seon.ns/doc] eid))
             evidence (db/read-evidence @captured {:seon.db/retain-read-results? retain?})]
         (doseq [[tx expected]
                 [[[(test-support/revision @connection [:seon.fn/sym 'seon.db/transact!]
                                           {:seon.fn/doc "An unrelated attribute changed."})] true]
                  [[[:db/add [:seon.ns/name 'seon.flow] :seon.ns/doc "Another entity changed."]] true]
                  [[{:seon.dev.mcp.artifact/id (digest 3)
                     :seon.dev.mcp.artifact/digest (digest 4)}] true]
                  [[[:db/add entity :seon.dev.mcp.artifact/digest (digest 5)]] false]
                  [[[:db/add entity :seon.dev.mcp.artifact/digest (digest 2)]] true]
                  [[[:db/retractEntity entity]] false]]]
           (test-support/transacted! connection tx)
           (let [[current? calls] (test-support/armed-calls
                                  '#{seon.db/replay-read}
                                  #(db/read-evidence-current? @connection evidence))]
             (is (= expected current?))
             (is (zero? (get calls 'seon.db/replay-read 0))))))))))

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
         (test-support/transacted! connection [(test-support/revision @connection [:seon.fn/sym 'seon.db/transact!] {:seon.fn/doc "An unrelated attribute changed."})])
         (is (true? (db/read-evidence-current? @connection evidence))
             "a commit outside the prefix's attribute keeps the page current")
         (let [on-page (:e (first (:datahike.index-page/datoms (:seon.db/read-result (first @captured)))))]
           (doc! on-page :seon.ns/doc "A datom on the page changed."))
         (is (false? (db/read-evidence-current? @connection evidence))
             "a changed datom on the page makes it stale"))))))

(deftest removing-a-renderer-refuses-by-the-declarations-it-breaks
  ;; CLASS: a schema's render face is a ref to its function; retracting the
  ;; function sweeps every such ref (datahike/db/transaction.cljc:1003-1014),
  ;; so the writer reads only the rows the report touched, never the whole
  ;; schema population, and refuses by name while a schema still declares it.
  (test-support/with-database
   (fn [connection]
     (let [database (db/db connection)
           renderer (:e (first (d/datoms database :avet :seon.fn/sym 'seon.error/render-ai)))
           users (count (d/datoms database :avet :seon.render/ai renderer))
           refused (db/transact! connection [[:db/retractEntity renderer]])
           declarations (get-in refused [:seon.error/data :seon.render/declarations])]
       (is (< 1 users) "errors derived from :seon.error/base store the inherited face")
       (is (:seon.db/transaction-refused refused))
       (is (some #{{:seon.schema/key :seon.error/base
                    :seon.render/property :seon.render/ai
                    :seon.render/function 'seon.error/render-ai}}
                 declarations))
       (is (= renderer (:e (first (d/datoms (db/db connection) :avet :seon.fn/sym 'seon.error/render-ai))))
           "refused, nothing was removed")))))

(deftest an-unrelated-report-reads-no-arity-edges
  (test-support/with-database
   (fn [connection]
     (let [database (db/db connection) projection (db/carried-projection database)
           report (d/with database [[:db/add [:seon.fn/sym 'seon.id/evaluation]
                                    :seon.fn/doc "An unrelated declaration observation."]])]
       (doseq [n [1 10 100]]
         (let [[comparisons calls] (test-support/armed-calls
                                    '#{seon.db/call-edges seon.db/pull-many}
                                    #(mapv (fn [_] (#'db/report-arity-comparison projection report)) (range n)))]
           (is (every? #(= {::db/candidates #{} ::db/checked 0 ::db/edges 0 ::db/bounds {}} %) comparisons))
           (is (every? zero? (vals calls)) "unrelated writes read no program edges or bounds")))))))

(deftest call-reach-includes-the-root-ends-cycles-and-treats-absent-symbols-as-leaves
  (is (= '#{a/x a/y a/z} (db/call-reach '{a/x [a/y] a/y [a/x a/z]} 'a/x)))
  (is (= '#{a/absent} (db/call-reach {} 'a/absent))))

(deftest a-changed-projection-builder-misses-the-projection-cache
  ;; A projection derives from the builder's code as well as the declaration
  ;; entities: a changed builder derives anew, an unchanged one reuses.
  (test-support/with-database
    (fn [connection]
      (let [digest #(#'db/builder-digest (db/db connection))
            restamp! (fn [function-symbol]
                       (test-support/transacted!
                        connection [(test-support/revision (db/db connection) [:seon.fn/sym function-symbol]
                                                           {:seon.fn/doc (str "Restamped " (random-uuid))})]))
            database (db/db connection)
            before (digest)
            projection (#'db/content-projection before database)]
        (is (identical? projection (#'db/content-projection before database)) "an unchanged builder reuses")
        (restamp! `cluster/serve!)
        (is (= before (digest)) "a declaration outside the builder's reach changes no projection")
        (restamp! 'seon.schema/shape-entity-in)
        (let [after (digest)]
          (is (not= before after) "a changed building declaration changes the builder")
          (is (not (identical? projection (#'db/content-projection after database)))
              "and the equal declaration content derives anew"))))))

(deftest a-retained-branch-reads-with-the-jvm-builder-not-its-recorded-program
  ;; A branch's adoption record names its historical program; the loaded code
  ;; builds the projection, so a record naming another builder keys nothing.
  (test-support/with-database
    (fn [connection]
      (let [database (db/db connection)
            projection (db/carried-projection database)
            entity [:seon.fn/sym 'seon.schema/shape-entity-in]
            original (db/pull database [:seon.fn/doc] entity)
            record (or (:e (first (d/datoms database :aevt :seon.source/commit-id)))
                       (db/q '[:find ?e . :where [?e :seon.cluster/name]] database))
            _ (test-support/transacted!
               connection [(test-support/revision database entity {:seon.fn/doc "Another builder."})])
            other-program (db/commit-id (db/db connection))
            ;; Restore the original documentation and derive its final digest.
            _ (test-support/transacted!
               connection [(test-support/revision (db/db connection) entity original)
                           {:db/id record :seon.source/commit-id other-program}])
            retained (db/db connection)]
        (is (not= (#'db/builder-digest database)
                  (#'db/builder-digest (d/commit-as-db retained other-program {:secondary-indices? false})))
            "the retained branch's record names another builder")
        (is (identical? projection (db/carried-projection retained))
            "its projection is the loaded builder's, not one keyed by its recorded program")))))

(deftest transaction-report-datom-law
  ;; The leaf of every :seon.db/transaction-report contract: true for each
  ;; datom a real write reports, false for anything else.
  (test-support/with-database
   (fn [connection]
     (let [report (test-support/transacted! connection (test-support/agent-tx (db/db connection) "datom-law"))
           datom (first (:tx-data report))]
       (is (seq (:tx-data report)))
       (is (every? db/transaction-report-datom? (:tx-data report)))
       (is (not-any? db/transaction-report-datom?
                     [nil false 0 "" :k [] {}
                      [(:e datom) (:a datom) (:v datom) (:tx datom) (:added datom)]
                      {:e (:e datom) :a (:a datom) :v (:v datom) :tx (:tx datom) :added (:added datom)}]))))))

(deftest a-missing-connection-refusal-carries-its-fix-as-data
  (let [refusal (#'seon.db/missing-connection-error "a database value")]
    (is (clojure.string/includes? (str (:seon.error/fix refusal)) "seon.cluster.boot/connection"))
    (is (not (clojure.string/includes? (:seon.error/message refusal) "seon.cluster.boot/connection"))
        "the message says what was refused; the fix is its own key")))

(deftest ^{:seon/tasks #{'seon.db/an-explicit-eid-outside-the-allocators-range-is-refused}}
  an-explicit-eid-outside-the-allocators-range-is-refused
  (test-support/with-database
   (fn [connection]
     (let [projection (schema/handed-projection)
           options {:registry (:seon.schema.projection/registry projection)}
           {:keys [max-eid max-tx]} (db/db connection)
           ident (fn [row] (keyword "seon.db-test" (str "explicit-eid-" (name row))))
           explicit (fn [row eid] (db/transact! connection [[:db/add eid :db/ident (ident row)]]))
           ;; a transaction id not yet issued and one above emax name nothing
           ;; (an issued transaction id names its transaction's metadata)
           refused [[:unissued-transaction (+ max-tx 7)]
                    [:above-emax (bit-or (bit-shift-left 7 32) 1)]]
           unissued (+ max-eid 100000)]
       (doseq [[row eid] refused]
         (testing (name row)
           (is (m/validate :seon.error/value (explicit row eid) options))))
       (is (= max-eid (:max-eid (db/db connection))) "a refused eid moves no allocator")
       ;; an eid below tx0 the allocator never issued is observed: the next fresh entity allocates above it
       (testing "unissued"
         (is (m/validate :seon.db/transaction-report (explicit :unissued unissued) options))
         (db/transact! connection [{:db/ident (ident :fresh)}])
         (is (< unissued (d/q '[:find ?e . :in $ ?k :where [?e :db/ident ?k]] (db/db connection) (ident :fresh)))))))))

(deftest ^{:seon/tasks #{'seon.db/entities-born-on-two-branches-of-one-commit-have-different-ids}}
  entities-born-on-two-branches-of-one-commit-have-different-ids
  (test-support/with-database
   (fn [connection]
     (let [database (db/db connection)
           store (:seon.store/store (test-support/execution-handle connection))
           request (fn [branch] {:seon.store/store store :seon.store/branch branch
                                 :seon.cluster.registry/from (db/commit-id database) :seon.db/db database})
           requests (mapv #(request (keyword (str "eid-birth-" % "-" (id/id)))) [:a :b])
           ;; one fresh entity per fork, found by its ident
           born (fn [{:seon.store/keys [branch]}]
                  (let [k (keyword "seon.db-test" (str "born-on-" (name branch)))
                        fork (store/open-branch! store branch)]
                    (try
                      ;; a write lands on the branch whose custody it runs under
                      (db/with-branch {:seon.db/connection fork}
                                            #(db/transact! fork [{:db/ident k}]))
                      (d/q '[:find ?e . :in $ ?k :where [?e :db/ident ?k]] (db/db fork) k)
                      (finally (store/release-branch! fork)))))]
       (try
         (doseq [request requests] (is (:seon.cluster/created? (registry/fork! request))))
         (let [[a b] (mapv born requests)]
           (is (every? int? [a b]))
           (is (< a constants/tx0))
           (is (< b constants/tx0))
           (is (not= a b) "two forks of one commit allocate distinct entity ids"))
         (finally (doseq [request requests]
                    (registry/retire-branch! (select-keys request [:seon.store/store :seon.store/branch])))))))))

(deftest a-store-without-the-write-time-limit-dial-refuses-a-write-by-its-name
  ;; CLASS: `transact-call` dereferenced the dial's nil, so a store declaring
  ;; a program but not the dial answered a NullPointerException refusal.
  (let [root (java.io.File. "tmp" (str "db-test-undeclared-dial-" (random-uuid)))
        opened (store/open-store! {:seon.store/dir (str root "/store")})]
    (try
      (let [connection (:seon.store/connection-object opened)
            executing (:seon.db/connection (test-support/execution-handle nil))]
        (test-support/transacted! connection
                                  [{:db/ident :sample.undeclared/id
                                    :db/valueType :db.type/string
                                    :db/cardinality :db.cardinality/one
                                    :db/unique :db.unique/identity}
                                   {:db/ident :seon.schema/key :db/valueType :db.type/keyword
                                    :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
                                   {:db/ident :seon.schema/form :db/valueType :db.type/string
                                    :db/cardinality :db.cardinality/one}])
        (test-support/transacted!
         connection
         (mapv #(db/pull (db/db executing) [:seon.schema/key :seon.schema/form] [:seon.schema/key %])
               [:seon.blob/digest :seon.config.db/validation-node-limit]))
        (let [refusal (db/transact! connection [{:sample.undeclared/id "x"}])]
          (is (= :seon.config.db/write-time-limit-ms (:seon.error/expected refusal)))
          (is (nil? (:seon.error/throwable refusal)))))
      (finally
        (store/release-store! opened)
        (test-support/delete-recursively! root)))))

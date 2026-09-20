(ns ^{:seon.test/platform
       "Moving part: source publication and its activation closure."}
    seon.cluster.source-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.cluster :as cluster]
            [seon.cluster.registry :as registry]
            [seon.cluster.source :as source]
            [seon.cluster.store :as store]
            [seon.db :as db]
            [seon.fn :as fn]
            [seon.fs :as fs]
            [seon.program :as program]
            [seon.schema.datahike]
            [sci.core :as sci]
            [seon.sci.eval :as sci.eval]
            [seon.schema :as schema]
            [seon.test.runner :as runner]
            [seon.test.cache :as cache]
            [seon.test-support :as test-support])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(def ^:private probe-schema
  [{:db/ident :seon.source.test/marker
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :seon.source.test/tags
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/many}])

(def ^:private digest-a (apply str (repeat 64 "a")))
(def ^:private digest-b (apply str (repeat 64 "b")))
(def ^:private digest-c (apply str (repeat 64 "c")))

(defonce ^:private blocked-entered (atom nil))
(defonce ^:private blocked-release (atom nil))
(def ^:dynamic *activation-missing* [])

(defn- populate-schema!
  {:malli/schema [:=> [:cat [:map [:seon.db/connection :seon.db/connection]]] :nil]}
  [{connection :seon.db/connection prior :seon.source/previous-database :as request}]
  ;; Subsequent synthetic publications change only the probe row. The real
  ;; program population is already inherited from the published branch.
  (when-not prior
    (cluster/populate-source! (assoc request :seon.fn/manifest @test-support/source-manifest))
    (test-support/transacted! connection probe-schema))
  nil)

(defn populate!
  [{:keys [:seon.db/connection :seon.source/digest] :as request}]
  (populate-schema! request)
  (test-support/transacted! connection
    (conj (mapv (fn [entity] [:db/retractEntity entity])
                (db/q '[:find [?entity ...] :where [?entity :seon.source.test/marker]] @connection))
          {:seon.source.test/marker digest})))

(defn populate-fails!
  [_]
  (throw (ex-info "population failed" {::injected true})))

(defn populate-from-data!
  [{:keys [:seon.db/connection :seon.source.test/marker] :as request}]
  (populate-schema! request)
  (test-support/transacted! connection [{:seon.source.test/marker marker}]))

(defn populate-blocked!
  [request]
  (.countDown ^CountDownLatch @blocked-entered)
  (.await ^CountDownLatch @blocked-release)
  (populate! request)
  nil)

(defn activation
  [{source-digest :seon.source/digest}]
  {:seon.activation/closure
   {:seon.activation/source-digest source-digest
    :seon.activation/schema-keys #{:seon.source/digest}
    :seon.activation/required-attributes #{:seon.source/digest}
    :seon.activation/config-defaults #{}
    :seon.activation/config-required #{}
    :seon.activation/executable-symbols #{}
    :seon.activation/lookup-refs []}
   :seon.activation/lookup-rows []
   :seon.activation/missing *activation-missing*})

(defn- with-store
  [body]
  (let [root (str "tmp/source-test/" (random-uuid))
        dir (str root "/store")]
    (.mkdirs (io/file root))
    (let [opened (store/open-store! {:seon.store/dir dir})]
      (try
        (body opened)
        (finally
          (store/release-store! opened)
          (test-support/delete-recursively! root))))))

(defn- publish
  ([opened digest]
   (publish opened digest 'seon.cluster.source-test/populate!))
  ([opened digest populate]
   (source/publish! {:seon.store/store opened
                     :seon.source/digest digest
                     :seon.source/populate populate
                     :seon.source/activation
                     'seon.cluster.source-test/activation}))
  ([opened digest populate populate-request]
   (source/publish! {:seon.store/store opened
                     :seon.source/digest digest
                     :seon.source/populate populate
                     :seon.source/activation
                     'seon.cluster.source-test/activation
                     :seon.source/populate-request populate-request
                     :seon.source/progress! @#'cluster/*source-progress!*})))

(defn- upsert
  [opened expected-commit digest rows]
  (source/upsert! {:seon.store/store opened
                   :seon.source/expected-commit-id expected-commit
                   :seon.source/digest digest
                   :seon.source/upsert-rows rows
                   :seon.source/activation
                   'seon.cluster.source-test/activation}))

(defn- markers
  [connection]
  (set (db/q '[:find [?marker ...]
              :where [_ :seon.source.test/marker ?marker]]
            @connection)))

(defn- source-digests
  [connection]
  (set (db/q '[:find [?digest ...]
              :where [_ :seon.source/digest ?digest]]
            @connection)))

(defn- scratch-branches
  [opened]
  (filter #(str/starts-with? (name %) "building-source-")
          (registry/roster opened)))

(defn- refusal
  [thunk]
  (try
    (thunk)
    ::committed
    (catch Exception failure
      (loop [throwable failure, found nil]
        (if throwable
          (recur (ex-cause throwable)
                 (or (not-empty (ex-data throwable)) found))
          found)))))

(deftest digest-is-stable-and-refuses-an-absent-root
  (let [root (str "tmp/source-test/roots/" (random-uuid))]
    (.mkdirs (io/file root))
    (try
      (spit (io/file root "a.clj") "(def a 1)")
      (let [first-digest (source/digest {:seon.source/roots [root]})]
        (is (= first-digest
               (source/digest {:seon.source/roots [(str "./" root)]})))
        (spit (io/file root "notes.txt") "ignored")
        (is (= first-digest (source/digest {:seon.source/roots [root]})))
        (spit (io/file root "a.clj") "(def a 2)")
        (is (not= first-digest (source/digest {:seon.source/roots [root]}))))
      (finally
        (test-support/delete-recursively! root))))
  (is (= :seon.cluster.source/root-absent
         (:seon.cluster.source/rule
          (refusal #(source/digest
                     {:seon.source/roots
                      [(str "tmp/source-test/absent-" (random-uuid))]}))))))

(deftest concurrent-source-refresh-is-bounded-and-names-the-holder-phase
  (test-support/with-database
    (fn [_connection]
      (let [entered (CountDownLatch. 1)
            release (CountDownLatch. 1)
            acquisition-bound-ms 50
            acquisition-bound-var
            (ns-resolve 'seon.cluster 'source-refresh-acquisition-bound-ms)
            resolve-bootstrap-var (ns-resolve 'seon.cluster 'resolve-bootstrap)]
        (with-redefs-fn
          {acquisition-bound-var (constantly acquisition-bound-ms)
           resolve-bootstrap-var
           (fn [_]
             (.countDown entered)
             (test-support/await-event! release "release first source refresh")
             (throw (ex-info "first refresh stopped after holding the monitor"
                             {::first-refresh-stopped true})))}
          (fn []
            (let [first-refresh
                  (future (refusal #(cluster/refresh-source! "tmp/source-refresh-first")))]
              (test-support/await-event! entered "first source refresh acquired monitor")
              (try
                (let [second-refresh
                      (future (refusal #(cluster/refresh-source! "tmp/source-refresh-second")))
                      second-result
                      (test-support/await-event!
                       second-refresh "second source refresh completed or refused")
                      holder (:seon.operator.lock/holder second-result)]
                  (is (= :seon.cluster/source-refresh-acquisition-timeout
                         (:seon.error/kind second-result)))
                  (is (= "bootstrap configuration"
                         (:seon.operator.lock/phase holder)))
                  (is (<= acquisition-bound-ms
                          (:seon.operator.lock/waited-ms second-result)))
                  (is (= acquisition-bound-ms
                         (:seon.operator.lock/acquisition-timeout-ms second-result)))
                  (is (re-find #"bootstrap configuration"
                               (:seon.error/message second-result))))
                (finally
                  (.countDown release)
                  (is (= {::first-refresh-stopped true}
                         (test-support/await-event!
                          first-refresh "first source refresh released"))))))))))))

(deftest activation-refusal-bounds-the-operator-face
  (let [missing
        (mapv (fn [ordinal]
                {:seon.activation/executable-symbol
                 (symbol "missing" (str "function-" ordinal))})
              (range 12))
        face (source/activation-refusal missing)
        elision (:seon.activation/missing-elision face)]
    (is (= 12 (:seon.activation/missing-count face)))
    (is (= (subvec missing 0 10) (:seon.activation/missing face)))
    (is (= 2 (:seon.print/omitted elision)))
    (is (= 12 (:seon.render.data/total elision)))
    (is (= 10 (:seon.render.data/next-offset elision)))
    (is (< (count (:seon.error/message face)) 1000))))

(deftest flat-scratch-write-refusal-retires-the-candidate
  (with-store
    (fn [opened]
      (let [result
            (with-redefs [db/transact!
                          (fn [& _]
                            {:seon.error/kind :seon.db/invalid-transaction
                             :seon.error/message "injected refusal"})]
              (refusal #(publish opened digest-a)))]
        (is (= :seon.cluster.source/scratch-schema-refused
               (:seon.cluster.source/rule result)))
        (is (= :seon.db/invalid-transaction
               (get-in result
                       [:seon.source/transaction-result :seon.error/kind])))
        (is (= #{:db} (set (registry/roster opened))))
        (is (empty? (scratch-branches opened)))))))

(deftest incremental-upsert-seals-one-activation-on-the-expected-commit
  (with-store
    (fn [opened]
      (let [a (publish opened digest-a)
            commit-a (:seon.source/commit-id a)
            max-a (:max-tx
                   (d/branch-as-db (:seon.store/connection-object opened)
                                   source/current-branch))
            b (upsert opened commit-a digest-b
                      [{:seon.source.test/marker "incremental"}])
            current-db (d/branch-as-db (:seon.store/connection-object opened)
                                       source/current-branch)]
        (is (= digest-b (:seon.source/digest b)))
        (is (= :db.unique/identity
               (get-in current-db
                       [:schema :seon.source/digest :db/unique]))
            "the source seal has one physical identity")
        (is (= (+ 3 max-a) (:max-tx current-db))
            "row application and issue indexing are followed by one activation seal")
        (is (= #{digest-b}
               (set (db/q '[:find [?digest ...]
                           :where [_ :seon.source/digest ?digest]]
                         current-db)))
            "exactly one source digest remains")
        (is (= #{digest-a "incremental"}
               (set (db/q '[:find [?marker ...]
                           :where [_ :seon.source.test/marker ?marker]]
                         current-db))))
        (is (= #{commit-a} (d/parent-commit-ids current-db)))
        (is (empty? (scratch-branches opened)))))))

(deftest incremental-first-party-publication-retains-complete-scalar-rows
  (let [root (io/file "tmp/publication-provenance" (str (random-uuid)))
        file (io/file root "id.clj")
        manifest @test-support/source-manifest
        original (slurp (io/resource "seon/id.clj"))
        revised (-> original
                    (str/replace "One identity entry:" "The identity entry:")
                    (str/replace "SHA-256 of (pr-str data), truncated"
                                 "SHA-256 of (pr-str data), shortened"))
        artifact #(fn/build-artifact
                   {:seon.fn/source-path (.getCanonicalPath file)
                    :seon.fn.file/first-party-functions
                    (fn/manifest-function-symbols manifest)})]
    (.mkdirs root)
    (try
      (spit file original)
      (let [before (artifact)
            _ (spit file revised)
            after (artifact)
            plan (fn/plan-file-change
                  {:seon.fn.change/status :modified
                   :seon.fn.change/current-artifact before
                   :seon.fn.change/desired-artifact after})
            rows (:seon.fn.change/rows plan)]
        (is (not= original revised))
        (is (= :incremental-upsert (:seon.fn.change/action plan)))
        (is (= (into #{[:seon.ns/name 'seon.id]
                       [:seon.fn.file/relative-path
                        (fs/relative-path (fs/source-directory)
                                          (.getCanonicalPath file))]}
                     (map (fn [function-symbol]
                            [:seon.fn/sym function-symbol]))
                     (filter #(= "seon.id" (namespace %))
                             (fn/manifest-function-symbols manifest)))
               (set (map program/row-identity rows))))
        (is (every? #(= :core (:seon.schema.admission/source %)) rows))
        (test-support/with-database
          (fn [connection]
            (let [existing (db/pull (db/db connection) '[*] [:seon.fn/sym 'seon.id/id])
                  sparse [{:seon.fn/sym 'seon.id/id :seon.fn/doc "updated documentation"}]
                  updated (db/transact! connection sparse)
                  basis (db/basis-t (db/db connection))
                  refused (db/transact! connection
                                       [{:seon.fn/sym 'seon.source.test/incomplete
                                         :seon.fn/doc "incomplete"}])]
              (is (some? (:db/id existing)) "the sparse write updates a complete fixture row")
              (is (some? (:db-after updated)) (pr-str updated))
              (is (= :seon.db/invalid-write (:seon.error/kind refused))
                  "an incomplete create still refuses under whole-entity validation")
              (is (= basis (db/basis-t (db/db connection))) "refusal commits nothing")
              (let [report (db/transact! connection rows)]
                (is (some? (:db-after report)) (pr-str report))))))
        (with-store
          (fn [opened]
            (let [published (let [started (System/nanoTime)
                                  clock (volatile! ["publication start" started])
                                  progress! (fn [phase]
                                              (let [[previous at] @clock
                                                    now (System/nanoTime)]
                                                (println "publication-phase" (pr-str previous)
                                                         "elapsed-ms" (quot (- now at) 1000000))
                                                (vreset! clock [phase now])))
                                  result (binding [cluster/*source-progress!* progress!]
                                           (publish opened digest-a 'seon.cluster/populate-source!
                                                    {:seon.fn/manifest manifest}))
                                  _ (progress! "publication complete")]
                              (println "complete-program-publication-ms"
                                       (/ (- (System/nanoTime) started) 1e6))
                              result)
                  database-before (source/database opened (:seon.source/commit-id published))
                  selector [{:seon.ns/aliases [:seon.ns.alias/local
                                               :seon.ns.alias/target-ns]}
                            {:seon.ns/imports [:seon.ns.import/local
                                               :seon.ns.import/target-class]}
                            {:seon.fn/arities [:seon.fn.arity/order
                                               :seon.fn.arity/input-schema
                                               :seon.fn.arity/return-schema]}]
                  expected-function-symbols
                  #{'seon.id/sha-256 'seon.id/id 'seon.id/digest
                    'seon.id/symbol-in 'seon.id/evaluation 'seon.id/valid?}
                  identities [[:seon.ns/name 'seon.id] [:seon.fn/sym 'seon.id/id]]
                  components #(mapv (fn [program-identity]
                                      (db/pull % selector program-identity)) identities)
                  published-after (upsert opened (:seon.source/commit-id published)
                                          digest-b rows)
                  database-after (source/database opened (:seon.source/commit-id published-after))]
              (is (= expected-function-symbols
                     (set (db/q '[:find [?symbol ...]
                                  :where [?function :seon.fn/sym ?symbol]
                                         [?function :seon.fn/ns ?namespace]
                                         [?namespace :seon.ns/name seon.id]]
                                database-before))))
              (is (= #{'str}
                     (set (map :seon.ns.alias/local
                               (:seon.ns/aliases (first (components database-before)))))))
              (is (seq (:seon.fn/arities (second (components database-before)))))
              (is (= (:seon.source/commit-id published-after)
                     (:seon.source/commit-id (source/current opened))))
              (is (not= (:seon.source/commit-id published)
                        (:seon.source/commit-id published-after)))
              (is (= (components database-before) (components database-after))
                  "scalar publication preserves existing component identities")
              (doseq [row rows]
                (let [stored (db/pull database-after '[*] (program/row-identity row))]
                  (is (= :core (:seon.schema.admission/source stored)))
                  (is (= (or (:seon.fn/source row) (:seon.ns/source row))
                         (or (:seon.fn/source stored) (:seon.ns/source stored))))))))))
      (finally
        (test-support/delete-recursively! root)))))

(deftest incremental-upsert-derives-scalar-safety-from-the-installed-schema
  (with-store
    (fn [opened]
      (let [published (publish opened digest-a)
            data (refusal
                  #(upsert opened
                           (:seon.source/commit-id published)
                           digest-b
                           [{:seon.source.test/marker digest-a
                             :seon.source.test/tags ["unsafe"]}]))]
        (is (= :seon.cluster.source/unsafe-incremental-rows
               (:seon.cluster.source/rule data)))
        (is (= [:seon.source.test/tags]
               (:seon.source/unsafe-attributes data)))
        (is (= (:seon.source/commit-id published)
               (:seon.source/commit-id (source/current opened))))
        (let [unknown
              (refusal
               #(upsert opened
                        (:seon.source/commit-id published)
                        digest-b
                        [{:seon.source.test/marker digest-a
                          :seon.source.test/not-installed "unsafe"}]))]
          (is (= :seon.cluster.source/unsafe-incremental-rows
                 (:seon.cluster.source/rule unknown)))
          (is (= [:seon.source.test/not-installed]
                 (:seon.source/unsafe-attributes unknown)))
          (is (= (:seon.source/commit-id published)
                 (:seon.source/commit-id (source/current opened)))))))))

(deftest incremental-publication-does-not-change-an-existing-cluster
  (with-store
    (fn [opened]
      (let [a (publish opened digest-a)]
        (registry/ensure-cluster!
         {:seon.store/store opened
          :seon.boot/cluster-name "incremental-a"
          :seon.source/commit-id (:seon.source/commit-id a)})
        (let [b (upsert opened (:seon.source/commit-id a) digest-b
                        [{:seon.source.test/marker "upserted"}])]
          (registry/ensure-cluster!
           {:seon.store/store opened
            :seon.boot/cluster-name "incremental-b"
            :seon.source/commit-id (:seon.source/commit-id b)})
          (doseq [[cluster expected-digest expected-markers]
                  [["incremental-a" #{digest-a} #{digest-a}]
                   ["incremental-b" #{digest-b} #{digest-a "upserted"}]]]
            (let [connection
                  (store/open-branch! opened (registry/cluster-branch cluster))]
              (try
                (is (= expected-digest (source-digests connection)))
                (is (= expected-markers (markers connection)))
                (finally
                  (d/release connection))))))))))

(deftest source-tombstone-provenance-does-not-prevent-live-removal
  (test-support/with-database
    (fn [connection]
      (let [ctx (sci.eval/build-base-ctx (schema/handed-projection))
            function-symbol 'source-deletion-probe/value
            fn-identity [:seon.fn/sym function-symbol]]
        (test-support/transacted! connection
                                  [{:seon.ns/name 'source-deletion-probe}
                                   (test-support/program-fn-row
                                    (db/db connection) function-symbol
                                    "(ns source-deletion-probe) (defn value [] 1)")])
        (sci/eval-string* ctx "(ns source-deletion-probe) (defn value [] 1)")
        (is (= 1 (sci/eval-string* ctx "(source-deletion-probe/value)")))
        (test-support/transacted!
         connection
         [[:db/retractEntity
           [:seon.test/sym
            'seon.cluster.source-test/source-tombstone-provenance-does-not-prevent-live-removal]]
          [:db/retractEntity fn-identity]])
        (is (= 1 (:seon.sci.eval/installed
                  (sci.eval/install-row!
                   {:seon.sci.eval/ctx ctx :seon.db/db @connection
                    :seon.program/row
                    {:seon.program/delete-identities [fn-identity]
                     :seon.program/ns [:seon.ns/name 'source-deletion-probe]
                     :seon.program/source "(ns-unmap 'source-deletion-probe 'value)"}}))))
        (is (nil? (sci/eval-string* ctx "(resolve 'source-deletion-probe/value)")))
        (is (nil? (db/pull @connection '[*] fn-identity)))))))

(deftest activation-seal-preserves-unchanged-facts
  (test-support/with-database
    (fn [connection]
      (test-support/transacted!
       connection
       (seon.schema.datahike/malli->datahike-schema @#'source/source-attributes))
      (let [seal #'source/activation-seal-tx
            requested #{'seon.cluster/derive-activation}
            initial-digest (or (db/q '[:find ?digest .
                                      :where [_ :seon.source/digest ?digest]] @connection)
                               digest-a)
            initial (seal connection initial-digest requested cluster/derive-activation)]
        (when (seq initial) (test-support/transacted! connection initial))
        (let [before @connection
              unchanged (seal connection initial-digest requested cluster/derive-activation)]
          (is (= [] unchanged))
          (is (= (:max-tx before) (:max-tx @connection))
              "an unchanged seal needs no transaction")
          (let [changed (seal connection digest-b requested cluster/derive-activation)
                report (test-support/transacted! connection changed)
                datoms (filter #(> (:tx %) (:max-tx before))
                               (d/datoms (d/history @connection) :eavt))]
            (is (seq (:tx-data report)))
            (is (= (inc (:max-tx before)) (:max-tx @connection)))
            (is (< (count datoms) 2000) (str "seal datoms: " (count datoms)))
            (is (= #{digest-b}
                   (set (db/q '[:find [?digest ...]
                                :where [_ :seon.source/digest ?digest]] @connection))))))))))

(deftest publication-input-digests-include-unreported-edits-and-deletions
  (let [before {"src/a.clj" "a1" "src/b.clj" "b1"}
        after {"src/a.clj" "a2" "src/new.clj" "n1"}]
    (is (= {:seon.test.cache/changed ["src/a.clj" "src/new.clj"]
            :seon.test.cache/removed ["src/b.clj"]}
           (seon.test.cache/changed-inputs before after)))))

(deftest an-activation-closure-with-empty-member-collections-seals
  ;; A cardinality-many attribute with NO members emits no datoms, so the
  ;; resulting entity cannot carry the key at all. A closure whose config
  ;; dials, executable symbols and lookup refs are honestly empty must seal;
  ;; emptiness is decided at the authority that still sees the supplied
  ;; value, never by a required key the medium cannot represent.
  (with-store
    (fn [opened]
      (let [published (publish opened digest-a)
            database (source/database opened (:seon.source/commit-id published))
            closure (db/pull database
                             '[* {:seon.source/activation-closure [*]}]
                             [:seon.source/digest digest-a])
            stored (:seon.source/activation-closure closure)]
        (is (true? (:seon.source/built? published)))
        (is (= digest-a (:seon.activation/source-digest stored)))
        (is (= #{:seon.source/digest} (set (:seon.activation/schema-keys stored))))
        (doseq [absent [:seon.activation/config-defaults
                        :seon.activation/config-required
                        :seon.activation/executable-symbols
                        :seon.activation/lookup-refs]]
          (is (= ::absent (get stored absent ::absent))
              (str "an empty member collection stores no datom: " absent)))))))

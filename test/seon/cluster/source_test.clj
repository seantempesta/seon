(ns ^{:seon.test/platform
       "Moving part: source publication and its activation closure."}
    seon.cluster.source-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.cluster.registry :as registry]
            [seon.cluster.source :as source]
            [seon.cluster.store :as store]
            [seon.db :as db]
            [seon.schema]
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

(defn populate!
  [{:keys [:seon.db/connection :seon.source/digest]}]
  (db/transact! connection probe-schema)
  (db/transact! connection
              [{:seon.source.test/marker digest}]))

(defn populate-fails!
  [_]
  (throw (ex-info "population failed" {::injected true})))

(defn populate-from-data!
  [{:keys [:seon.db/connection :seon.source.test/marker]}]
  (db/transact! connection probe-schema)
  (db/transact! connection [{:seon.source.test/marker marker}]))

(defn populate-blocked!
  [request]
  (populate! request)
  (.countDown ^CountDownLatch @blocked-entered)
  (.await ^CountDownLatch @blocked-release)
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
                     :seon.source/populate-request populate-request})))

(defn- upsert
  [opened expected-commit digest rows]
  (source/upsert! {:seon.store/store opened
                   :seon.source/expected-commit-id expected-commit
                   :seon.source/digest digest
                   :seon.program/rows rows
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

(deftest publication-refuses-each-missing-activation-prerequisite-before-fork
  (doseq [[prerequisite missing]
          [[:schema {:seon.activation/schema-key :missing/schema}]
           [:attribute
            {:seon.activation/required-attribute :missing/attribute}]
           [:default {:seon.activation/config-dial :missing/default}]
           [:lookup-ref
            {:seon.activation/lookup-attribute :missing/identity
             :seon.activation/lookup-value "absent"}]
           [:program-symbol
            {:seon.activation/executable-symbol "missing/function"}]]]
    (testing (name prerequisite)
      (with-store
        (fn [opened]
          (let [data
                (binding [*activation-missing* [missing]]
                  (refusal #(publish opened digest-a)))]
            (is (= :seon.cluster.source/activation-incomplete
                   (:seon.cluster.source/rule data)))
            (is (= #{:db} (set (registry/roster opened)))
                "no current-src or cluster branch exists after preflight")
            (is (empty? (scratch-branches opened)))
            (is (= [missing] (:seon.activation/missing data))
                "the refusal names the missing fact")))))))

(deftest activation-refusal-bounds-the-operator-face
  (let [missing
        (mapv (fn [ordinal]
                {:seon.activation/executable-symbol
                 (str "missing/function-" ordinal)})
              (range 12))
        face (source/activation-refusal missing)
        elision (:seon.activation/missing-elision face)]
    (is (= 12 (:seon.activation/missing-count face)))
    (is (= (subvec missing 0 10) (:seon.activation/missing face)))
    (is (= 2 (:seon.print/omitted elision)))
    (is (= 12 (:seon.render.data/total elision)))
    (is (= 10 (:seon.render.data/next-offset elision)))
    (is (< (count (:seon.error/message face)) 1000))))

(deftest publication-advances-one-branch-and-retires-scratch
  (with-store
    (fn [opened]
      (let [a (publish opened digest-a
                       'seon.cluster.source-test/populate-from-data!
                       {:seon.source.test/marker "from-populate-request"})
            b (publish opened digest-b)]
        (is (= :current-src (:seon.source/branch a)
               (:seon.source/branch b)))
        (is (= digest-a (:seon.source/digest a)))
        (is (= digest-b (:seon.source/digest b)))
        (is (not= (:seon.source/commit-id a)
                  (:seon.source/commit-id b)))
        (is (= #{(:seon.source/commit-id a)}
               (d/parent-commit-ids
                (d/branch-as-db (:seon.store/connection-object opened)
                                source/current-branch)))
            "published history follows prior current-src, not scratch")
        (is (= #{:db :current-src} (registry/roster opened)))
        (is (empty? (scratch-branches opened)))
        (is (= {:seon.source/branch :current-src
                :seon.source/commit-id (:seon.source/commit-id b)}
               (source/current opened)))
        (testing "a complete publication never trusts digest equality alone"
          (let [connection (store/open-branch! opened source/current-branch)]
            (try
              (db/transact! connection
                          [[:db/retract
                            [:seon.source.test/marker digest-b]
                            :seon.source.test/marker
                            digest-b]
                           {:seon.source.test/marker "stale-row"}])
              (finally
                (d/release connection))))
          (let [again (publish opened digest-b)
                current-db (d/branch-as-db
                            (:seon.store/connection-object opened)
                            source/current-branch)]
            (is (true? (:seon.source/built? again)))
            (is (= digest-b (:seon.source/digest again)))
            (is (not= (:seon.source/commit-id b)
                      (:seon.source/commit-id again)))
            (is (= #{digest-b}
                   (set (db/q '[:find [?marker ...]
                               :where [_ :seon.source.test/marker ?marker]]
                             current-db)))
                "complete population repairs stale rows under an equal digest")))))))

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
        (is (= (+ 2 max-a) (:max-tx current-db))
            "private row application is followed by one activation seal")
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

(deftest stale-incremental-upsert-preserves-the-newer-publication
  (with-store
    (fn [opened]
      (let [a (publish opened digest-a)
            b (publish opened digest-b)
            data (refusal
                  #(upsert opened (:seon.source/commit-id a) digest-c
                           [{:seon.source.test/marker "stale"}]))]
        (is (= :stale-branch-head (:type data)))
        (is (= (:seon.source/commit-id b)
               (:seon.source/commit-id (source/current opened))))
        (let [connection
              (store/open-branch! opened source/current-branch)]
          (try
            (is (= #{digest-b} (source-digests connection)))
            (is (= #{digest-b} (markers connection)))
            (finally
              (d/release connection))))
        (is (empty? (scratch-branches opened)))))))

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

(deftest failed-and-stale-builds-preserve-the-published-head
  (with-store
    (fn [opened]
      (let [a (publish opened digest-a)
            commit-a (:seon.source/commit-id a)]
        (is (= {::injected true}
               (refusal #(publish opened digest-b
                                   'seon.cluster.source-test/populate-fails!))))
        (is (= commit-a (:seon.source/commit-id (source/current opened))))
        (is (empty? (scratch-branches opened)))
        (let [entered (CountDownLatch. 1)
              release (CountDownLatch. 1)]
          (reset! blocked-entered entered)
          (reset! blocked-release release)
          (let [stale (future
                        (refusal #(publish opened digest-b
                                           'seon.cluster.source-test/populate-blocked!)))]
            (is (.await entered 10 TimeUnit/SECONDS))
            (let [c (try
                      (publish opened digest-c)
                      (finally
                        (.countDown release)))
                  stale-result
                  (test-support/await-event! stale "stale source publication")]
              (is (= :stale-branch-head (:type stale-result)))
              (is (= (:seon.source/commit-id c)
                     (:seon.source/commit-id (source/current opened))))
              (is (empty? (scratch-branches opened))))))))))

(deftest existing-clusters-remain-on-their-chosen-source-commit
  (with-store
    (fn [opened]
      (let [a (publish opened digest-a)]
        (registry/ensure-cluster!
         {:seon.store/store opened
          :seon.boot/cluster-name "a"
          :seon.source/commit-id (:seon.source/commit-id a)})
        (let [b (publish opened digest-b)]
          (registry/ensure-cluster!
           {:seon.store/store opened
            :seon.boot/cluster-name "b"
            :seon.source/commit-id (:seon.source/commit-id b)})
          (doseq [[cluster expected] [["a" #{digest-a}]
                                      ["b" #{digest-b}]]]
            (let [connection
                  (store/open-branch! opened (registry/cluster-branch cluster))]
              (try
                (is (= expected (markers connection)))
                (finally
                  (d/release connection))))))))))

(ns seon.cluster.source-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [seon.cluster :as cluster]
            [seon.cluster.registry :as registry]
            [seon.cluster.source :as source]
            [seon.cluster.store :as store]
            [seon.db :as db]
            [seon.fn :as fn]
            [seon.schema.datahike]
            [sci.core :as sci]
            [seon.sci.eval :as sci.eval]
            [seon.schema :as schema]
            [seon.test.cache :as cache]
            [seon.test-support :as test-support])
  (:import [java.util.concurrent CountDownLatch]))

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

(defonce ^:private blocked-entered (atom nil))
(defonce ^:private blocked-release (atom nil))


(defn populate-program!
  "Publish only the fixture files against their canonical published ancestor."
  {:malli/schema [:=> [:cat [:map [:seon.db/connection :seon.db/connection]
                             [:seon.fn/manifest :seon.fn.manifest/manifest]]]
                  [:or :seon.reconcile/result :seon.error/value]]}
  [{:keys [:seon.db/connection :seon.fn/manifest] :as request}]
  (let [database (or (:seon.source/previous-database request) (db/db connection))
        projection (or (db/carried-projection database) (schema/handed-projection))]
    (fn/index! (assoc request
                     :seon.schema/projection projection
                     :seon.schema.projection/forms (:seon.schema.projection/forms projection)
                     :seon.source/previous-database database
                     :seon.fn/previous-manifest (assoc manifest :seon.fn.manifest/artifacts [])
                     :seon.fn/changed-paths (set (map :seon.fn.file/relative-path
                                                     (:seon.fn.manifest/artifacts manifest)))))))

(defn populate!
  [{:keys [:seon.db/connection :seon.source/digest]}]
  (test-support/transacted! connection
    (conj (mapv (fn [entity] [:db/retractEntity entity])
                (db/q '[:find [?entity ...] :where [?entity :seon.source.test/marker]] @connection))
          {:seon.source.test/marker digest})))

(defn populate-fails!
  [_]
  (throw (ex-info "population failed" {::injected true})))

(defn populate-from-data!
  [{:keys [:seon.db/connection :seon.source.test/marker]}]
  (test-support/transacted! connection [{:seon.source.test/marker marker}]))

(defn populate-blocked!
  [request]
  (.countDown ^CountDownLatch @blocked-entered)
  (test-support/await-event! @blocked-release "release blocked publication")
  (populate! request)
  nil)



(defn- fixture-program!
  "Analyze the small publication program in its own source directory."
  {:malli/schema [:=> [:cat :string] :seon.fn.manifest/manifest]}
  [root]
  (let [resources (io/file "test/resources/publication-program")]
    (doseq [file (file-seq resources)
            :when (and (.isFile file) (.endsWith (.getName file) ".txt"))]
      (let [relative (str (.relativize (.toPath resources) (.toPath file)))
            target (io/file root (subs relative 0 (- (count relative) 4)))]
        (.mkdirs (.getParentFile target))
        (io/copy file target)))
    (fn/build-manifest {:seon.fn/roots [root]})))

(defn- with-store
  [body]
  (let [projection (schema/handed-projection)]
  (test-support/with-database
   (fn [_canonical]
     (let [root (str "tmp/source-test/" (random-uuid))
           dir (str root "/data/store")]
       (.mkdirs (io/file root))
       (try
         (test-support/populate-published-operator-root!
          root {:seon.test/fixture-observation
                "Publication and branch-head races require a private physical store."})
         (let [opened (store/open-store! {:seon.store/dir dir})]
           (try
             (doseq [branch (remove #{:db source/current-branch} (registry/roster opened))]
               (registry/retire-branch! {:seon.store/store opened :seon.store/branch branch}))
             (let [connection (store/open-branch! opened source/current-branch)]
               (try
                 (db/carry-connection-projection-state!
                  connection (sci.eval/projection-state @connection projection))
                 (test-support/transacted!
                  connection
                  (into probe-schema
                        (map #(vector :db/retractEntity %))
                        (db/q '[:find [?source ...] :where [?source :seon.source/digest]]
                              (db/db connection))))
                 (finally (d/release connection))))
             (schema/call-with-projection
              projection
              #(body (assoc opened
                            :seon.schema/projection projection
                            :seon.fn/manifest (fixture-program! (str root "/program")))))
             (finally (store/release-store! opened))))
         (finally (test-support/delete-recursively! root))))))))

(defn- publish
  ([opened digest]
   (publish opened digest 'seon.cluster.source-test/populate!))
  ([opened digest populate]
   (publish opened digest populate {}))
  ([opened digest populate populate-request]
   (let [database (d/branch-as-db (:seon.store/connection-object opened) source/current-branch)
         previous (db/carry-projection-state
                   database (sci.eval/projection-state database (:seon.schema/projection opened)))]
    (try
     (source/publish! (merge (select-keys populate-request [:seon.source/expected-commit-id])
                     {:seon.store/store opened
                     :seon.source/digest digest
                     :seon.source/populate populate
                     :seon.source/changed-paths
                     (mapv :seon.fn.file/relative-path
                           (:seon.fn.manifest/artifacts (:seon.fn/manifest opened)))
                     :seon.source/populate-request
                     (merge {:seon.fn/manifest (:seon.fn/manifest opened)
                             :seon.source/previous-database previous
                             :seon.source/change-classes #{:program}}
                            populate-request)
                     :seon.source/progress! @#'cluster/*source-progress!*}))
     (finally (d/release-materialized-db database))))))

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

(deftest ^{:seon.test/fixture-observation
           "A database branch cannot isolate the physical-store scratch branch whose refused publication must be retired."}
  flat-scratch-write-refusal-retires-the-candidate
  (with-store
    (fn [opened]
      (let [result
            (with-redefs [db/transact!
                          (fn [& _]
                            {:seon.db.write.attempt/request-id "source-refusal" :seon.error/at (java.util.Date.) :seon.error/layer :seon.db/invocation :seon.error/operation 'seon.db/transact!
                             :seon.error/message "injected refusal"})]
              (refusal #(publish opened digest-a)))]
        (is (= :seon.cluster.source/scratch-schema-refused
               (:seon.cluster.source/rule result)))
        (is (= "injected refusal"
               (get-in result
                       [:seon.source/transaction-result :seon.error/message])))
        (is (= #{:db :current-src} (set (registry/roster opened))))
        (is (empty? (scratch-branches opened)))))))

(deftest ^{:seon.test/fixture-observation
           "A database branch cannot isolate cluster branch heads retained across a second physical-store publication."}
  incremental-publication-does-not-change-an-existing-cluster
  (with-store
    (fn [opened]
      (let [a (publish opened digest-a)]
        (registry/ensure-cluster!
         {:seon.store/store opened
          :seon.boot/cluster-name "incremental-a"
          :seon.source/commit-id (:seon.source/commit-id a)})
        (let [b (publish opened digest-b 'seon.cluster.source-test/populate-from-data!
                         {:seon.source/expected-commit-id (:seon.source/commit-id a)
                          :seon.source.test/marker "upserted"})]
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



(deftest publication-input-digests-include-unreported-edits-and-deletions
  (let [before {"src/a.clj" "a1" "src/b.clj" "b1"}
        after {"src/a.clj" "a2" "src/new.clj" "n1"}]
    (is (= {:seon.test.cache/changed ["src/a.clj" "src/new.clj"]
            :seon.test.cache/removed ["src/b.clj"]}
           (seon.test.cache/changed-inputs before after)))))

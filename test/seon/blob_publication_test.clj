(ns seon.blob-publication-test
  "Deterministic two-sided publication/collection orderings on a file store."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [datahike.gc-guard :as gc-guard]
            [konserve.core :as k]
            [konserve.impl.defaults :as konserve.defaults]
            [seon.blob :as blob]
            [seon.cluster.registry :as registry]
            [seon.cluster.store :as store]
            [seon.db :as db]
            [seon.test-support :as support])
  (:import [java.util Date]
           [java.util.concurrent CountDownLatch]))

(defn- await!
  [latch event]
  (support/await-event! latch event))

(def ^:private blob-schema
  "What collection reads to root a blob, and nothing else: one synthetic attribute whose declared schema is
  `:seon.blob/digest` (`seon.cluster.registry/referenced-blobs`), beside the registry attributes declaring it."
  [{:db/ident :seon.schema/key :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :seon.schema/form :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident ::root :db/valueType :db.type/string :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident ::blob :db/valueType :db.type/string :db/cardinality :db.cardinality/one}])

(defn- declare-blob-attribute!
  "Install `blob-schema` and declare `::blob` a digest on `opened`'s main branch, beside the executing program's
  own declarations of `:seon.blob/digest` and of the writer's two bounds, then fork `branch` from it."
  [opened branch]
  (support/transacted! (:seon.store/connection-object opened) blob-schema)
  (support/transacted! (:seon.store/connection-object opened)
                       (conj (mapv #(db/pull (db/db (:seon.db/connection (support/execution-handle nil)))
                                             [:seon.schema/key :seon.schema/form] [:seon.schema/key %])
                                   [:seon.blob/digest :seon.config.db/validation-node-limit :seon.config.db/write-time-limit-ms])
                             {:seon.schema/key ::blob :seon.schema/form ":seon.blob/digest"}))
  (registry/branch! {:seon.store/store opened :seon.cluster.registry/from :db :seon.store/branch branch}))

(defn- blob-root!
  "Reference `digest` from a fact whose attribute is declared a digest, as error evidence references its shown text."
  [connection digest]
  (support/transacted! connection [{::root digest ::blob digest}]))

(defn- rooted-digests
  [connection]
  (set
   (db/q '[:find [?digest ...]
           :where [_ ::blob ?digest]]
         @connection)))

(defn- leave-orphan!
  [connection staged]
  (try
    (blob/with-publication!
     connection [staged]
     #(throw (ex-info "simulated root-transaction crash"
                      {:seon.test/root-transaction :crashed})))
    (catch clojure.lang.ExceptionInfo failure
      (is (= :crashed (:seon.test/root-transaction (ex-data failure)))))))

(deftest ^{:seon.test/fixture-observation "Publication and collection contend over physical store-global blob keys, which an ordinary branch does not isolate."} publication-and-collection-are-exclusive-in-both-orderings
  (let [root (str "tmp/blob-publication-test/" (random-uuid))
        directory (str root "/store")
        branch :blob-publication
        first-content "reused orphan fixed in a delete batch"
        second-content "publisher admitted before sweep"
        crash-content "published without a committed root"
        first-digest (blob/digest first-content)
        second-digest (blob/digest second-content)
        crash-digest (blob/digest crash-content)
        opened (store/open-store! {:seon.store/dir directory})]
    (try
      (declare-blob-attribute! opened branch)
      (let [connection (store/open-branch! opened branch)]
        (try
          (testing "a batch-contained orphan cannot overlap publication"
            (let [orphan (blob/stage! connection first-content)
                  staged (blob/stage! connection first-content)
                  batch-fixed (CountDownLatch. 1)
                  release-batch (CountDownLatch. 1)
                  publication-requested (CountDownLatch. 1)
                  target-key (konserve.defaults/key->store-key first-digest)
                  original-acquire gc-guard/acquire-reachability-permit!]
              (leave-orphan! connection orphan)
              (is (k/exists? (:store @connection) first-digest {:sync? true}))
              (let [collection
                    (future
                      (registry/collect!
                       opened (Date.)
                       {:datahike.gc/sweep-opts
                        {:konserve.gc/batch-issued
                         (fn [store-keys]
                           (when (some #{target-key} store-keys)
                             (.countDown batch-fixed)
                             (await! release-batch :release-fixed-blob-batch)))}}))]
                (await! batch-fixed :orphan-blob-batch-fixed)
                (with-redefs [gc-guard/acquire-reachability-permit!
                              (fn
                                ([store-id mode]
                                 (when (= :blob mode)
                                   (.countDown publication-requested))
                                 (original-acquire store-id mode))
                                ([store-id mode opts]
                                 (when (= :blob mode)
                                   (.countDown publication-requested))
                                 (original-acquire store-id mode opts)))]
                  (let [publication
                        (future
                          (blob/with-publication!
                           connection [staged]
                           #(blob-root! connection first-digest)))]
                    (await! publication-requested :publication-queued)
                    (is (not (realized? publication)))
                    (is (not (contains? (rooted-digests connection)
                                        first-digest)))
                    (.countDown release-batch)
                    (is (pos? (:seon.cluster.registry/swept
                               (support/await-event!
                                collection :batch-contained-collection))))
                    (support/await-event! publication :queued-publication)
                    (is (contains? (rooted-digests connection) first-digest)))))))

          (testing "an admitted publisher holds its permit through the root transaction"
            (let [staged (blob/stage! connection second-content)
                  root-entered (CountDownLatch. 1)
                  release-root (CountDownLatch. 1)
                  sweep-requested (CountDownLatch. 1)
                  batch-issued (CountDownLatch. 1)
                  original-acquire gc-guard/acquire-sweep-permit!
                  publication
                  (future
                    (blob/with-publication!
                     connection [staged]
                     (fn []
                       (.countDown root-entered)
                       (await! release-root :release-root-transaction)
                       (blob-root! connection second-digest))))]
              (await! root-entered :publisher-entered-root-transaction)
              (with-redefs [gc-guard/acquire-sweep-permit!
                            (fn [store-id opts]
                              (.countDown sweep-requested)
                              (original-acquire store-id opts))]
                (let [collection
                      (future
                        (registry/collect!
                         opened (Date.)
                         {:datahike.gc/sweep-opts
                          {:konserve.gc/batch-issued
                           (fn [_store-keys]
                             (.countDown batch-issued))}}))]
                  (await! sweep-requested :sweep-queued-behind-publication)
                  (is (= 1 (.getCount batch-issued)))
                  (is (not (realized? collection)))
                  (.countDown release-root)
                  (support/await-event! publication :rooted-publication)
                  (support/await-event! collection :post-publication-collection)
                  ;; A second pass over a live store need not sweep zero
                  ;; (`registry/collect!` docstring); what must hold is that the
                  ;; rooted blob survives it.
                  (registry/collect! opened (Date.))
                  (is (= second-content (blob/get connection second-digest)))))))

          (testing "an unreferenced blob is readable by digest until an explicit sweep collects it"
            ;; MCP results are blob-only (owner ruling #19): no entity roots them.
            (let [unreferenced (blob/put! connection "an unreferenced MCP value")]
              (is (= "an unreferenced MCP value" (blob/get connection unreferenced)))
              (is (pos? (registry/collect! opened (Date.))))
              (is (nil? (blob/get connection unreferenced))
                  "a swept digest reads as absent; get_value refuses it as collected")))

          (testing "a crashed publication remains collectable"
            (let [staged (blob/stage! connection crash-content)]
              (leave-orphan! connection staged)
              (is (k/exists? (:store @connection) crash-digest {:sync? true}))
              (is (pos? (registry/collect! opened (Date.))))
              (is (false? (k/exists? (:store @connection)
                                     crash-digest {:sync? true})))
              (is (= #{first-digest second-digest}
                     (rooted-digests connection)))))
          (finally
            (d/release connection))))
      (finally
        (store/release-store! opened)))

    (let [reopened (store/open-store! {:seon.store/dir directory})]
      (try
        (let [connection (store/open-branch! reopened branch)]
          (try
            (is (= #{first-digest second-digest}
                   (rooted-digests connection)))
            (is (= first-content (blob/get connection first-digest)))
            (is (= second-content (blob/get connection second-digest)))
            (finally
              (d/release connection))))
        (finally
          (store/release-store! reopened)
          (support/delete-recursively! (io/file root)))))))

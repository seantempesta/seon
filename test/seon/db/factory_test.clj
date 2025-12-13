(ns seon.db.factory-test
  "Tests for XTDB node factory.

  Tests cover:
  - In-memory node creation and lifecycle
  - Persistent node creation (file-based)
  - Proper cleanup"
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [seon.db.factory :as factory]
   [seon.db.node :as node])
  (:import
   [java.io File]))

(deftest create-in-memory-node-test
  (testing "Can create and close in-memory node"
    (let [xtdb-node (factory/create-node :test {:in-memory? true})]
      (try
        (is (some? xtdb-node) "Node should be created")

        ;; Verify node is functional by running a simple query
        (let [result (node/query xtdb-node '(from :nonexistent [*]))]
          (is (vector? result) "Should be able to query node"))

        (finally
          (factory/stop-node xtdb-node))))))

(deftest create-in-memory-node-idempotent-close-test
  (testing "Closing a node multiple times is safe"
    (let [node (factory/create-node :test {:in-memory? true})]
      (factory/stop-node node)
      (is (nil? (factory/stop-node node)) "Second close should not throw"))))

(deftest create-persistent-node-test
  (testing "Can create persistent node with file storage"
    (let [test-path "target/test-db/factory-test"
          _ (-> (File. test-path) .mkdirs)  ; Ensure parent exists
          xtdb-node (factory/create-node :test {:path test-path})]
      (try
        (is (some? xtdb-node) "Node should be created")

        ;; Verify storage dirs were created
        (is (.exists (File. (str test-path "/log"))) "Log dir should exist")
        (is (.exists (File. (str test-path "/storage"))) "Storage dir should exist")

        ;; Verify node is functional by running a simple query
        (let [result (node/query xtdb-node '(from :nonexistent [*]))]
          (is (vector? result) "Should be able to query node"))

        (finally
          (factory/stop-node xtdb-node)
          ;; Clean up test data - simple recursive delete
          (letfn [(delete-recursively [^File file]
                    (when (.exists file)
                      (when (.isDirectory file)
                        (doseq [child (.listFiles file)]
                          (delete-recursively child)))
                      (.delete file)))]
            (delete-recursively (File. test-path))))))))

(deftest domain-isolation-test
  (testing "Multiple in-memory nodes are independent"
    (let [node1 (factory/create-node :domain1 {:in-memory? true})
          node2 (factory/create-node :domain2 {:in-memory? true})]
      (try
        ;; Write to node1 using XTDB v2 transaction format
        (node/execute-tx! node1 [[:put-docs :test/doc1 {:xt/id :test/doc1 :domain :domain1}]])

        ;; Verify node2 doesn't see node1's data
        (let [result (node/query node2 '(from :test/doc1 [xt/id domain]))]
          (is (empty? result) "Node2 should not see Node1's data"))

        (finally
          (factory/stop-node node1)
          (factory/stop-node node2))))))

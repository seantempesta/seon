(ns seon.db.backend-test
  "Behavioral tests for the Datahike backend adapter."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.store :as store]
            [seon.db.backend :as backend]
            [seon.db.branch :as branch])
  (:import [java.io File]))

(defn- delete-tree!
  [path]
  (let [root (File. ^String path)]
    (when (.exists root)
      (run! (fn [^File file] (.delete file))
            (reverse (file-seq root))))))

(deftest backend-facts-are-stable-and-use-the-database-layout
  (let [memory-request {::backend/database-name :cluster/alpha
                        ::backend/backend :memory}
        file-request {::backend/database-name :cluster/alpha
                      ::backend/backend :file}]
    (is (= (backend/backend-facts memory-request)
           (backend/backend-facts memory-request))
        "one database name has one deterministic backend identity")
    (is (uuid? (first
                (::branch/connection-id
                 (backend/backend-facts memory-request)))))
    (is (= :db
           (second
            (::branch/connection-id
             (backend/backend-facts memory-request)))))
    (is (= "data/clusters/alpha/db"
           (::backend/path (backend/backend-facts file-request))))
    (is (= "data/clusters/bare/db"
           (::backend/path
            (backend/backend-facts
             (assoc file-request ::backend/path "bare"))))
        "a bare path cannot create backend files in the process directory")
    (is (= "/tmp/seon-explicit-db"
           (::backend/path
            (backend/backend-facts
             (assoc file-request ::backend/path "/tmp/seon-explicit-db")))))))

(deftest datahike-config-is-pure-and-confines-third-party-shape
  (let [root (str (System/getProperty "java.io.tmpdir")
                  "/seon-backend-test-" (random-uuid))
        path (str root "/database")
        initial-tx [{:db/ident :test.initial/id
                     :db/valueType :db.type/string
                     :db/cardinality :db.cardinality/one}]
        request {::backend/database-name :test/pure
                 ::backend/backend :file
                 ::backend/path path
                 ::backend/initial-tx initial-tx}
        config (backend/datahike-config request)]
    (try
      (is (= {:backend :file
              :path path
              :id (first
                   (::branch/connection-id
                    (backend/backend-facts request)))}
             (:store config)))
      (is (= :db (:branch config)))
      (is (= :write (:schema-flexibility config)))
      (is (true? (:keep-history? config)))
      (is (true? (:fuse-index-roots? config))
          "every newly created Seon database uses the measured root layout")
      (is (= initial-tx (:initial-tx config)))
      (is (not (.exists (File. root)))
          "constructing configuration performs no filesystem writes")
      (is (true? (::backend/created?
                  (backend/ensure-parent-dir! {::backend/path path}))))
      (is (false? (::backend/created?
                   (backend/ensure-parent-dir! {::backend/path path}))))
      (finally
        (delete-tree! root)))))

(deftest distinct-database-names-have-distinct-identities
  (testing "backend identity follows the database fact, not its path"
    (let [path "/tmp/shared-location-is-not-identity"
          alpha (backend/backend-facts
                 {::backend/database-name :cluster/alpha
                  ::backend/backend :file
                  ::backend/path path})
          beta (backend/backend-facts
                {::backend/database-name :cluster/beta
                 ::backend/backend :file
                 ::backend/path path})]
      (is (not= (first (::branch/connection-id alpha))
                (first (::branch/connection-id beta))))
      (is (= (::backend/path alpha) (::backend/path beta))))))

(deftest explicit-connection-id-separates-route-from-store
  (let [store-id (random-uuid)
        main-connection-id [store-id :db]
        branch-connection-id [store-id :experiment/one]
        main-config
        (backend/datahike-config
         {::backend/database-name :route/main
          ::backend/backend :memory
                       ::branch/connection-id main-connection-id})
        branch-config
        (backend/datahike-config
         {::backend/database-name :route/experiment
          ::backend/backend :memory
                         ::branch/connection-id branch-connection-id})]
    (is (= store-id (get-in main-config [:store :id])))
    (is (= store-id (get-in branch-config [:store :id])))
    (is (= [store-id :db] (store/connection-id main-config)))
    (is (= [store-id :experiment/one]
           (store/connection-id branch-config)))
    (is (not= (store/connection-id main-config)
              (store/connection-id branch-config)))))

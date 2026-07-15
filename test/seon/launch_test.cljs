(ns seon.launch-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [seon.db.coordinate :as coordinate]
            [seon.db.protocol :as protocol]
            [seon.launch :as launch]
            [seon.schema :as schema]))

(defn- ordinary
  [cluster-dir flavor build-id]
  (launch/default-descriptor
   {::launch/cluster-dir cluster-dir
    ::launch/artifact-flavor flavor
    ::launch/client-build-id build-id
    ::launch/request-socket-path "tmp/source-req.sock"
    ::launch/publish-socket-path "tmp/source-pub.sock"
    ::launch/writer-repl-port-file "tmp/source-writer.port"
    ::launch/process-dir "tmp/source-process"
    ::launch/log-dir "logs/source"
    ::launch/http-port 7890
    ::launch/http-port-file "tmp/source-http.port"}))

(deftest default-descriptor-derives-default-and-acme-with-one-shape
  (doseq [[cluster-dir flavor build-id cluster]
          [["data/clusters/default"
            :seon.dev.artifact.flavor/default "client" "default"]
           ["data/clusters/acme/"
            :seon.dev.artifact.flavor/acme "acme-client" "acme"]]]
    (let [descriptor (ordinary cluster-dir flavor build-id)]
      (is (schema/valid-candidate-value? ::launch/descriptor descriptor))
      (is (= cluster (get-in descriptor
                             [::launch/runtime ::launch/runtime-cluster])))
      (is (= cluster (get-in descriptor
                             [::launch/database ::protocol/database-name])))
      (is (= (str (str/replace cluster-dir #"/$" "") "/db")
             (get-in descriptor
                     [::launch/database ::protocol/database-path])))
      (is (= {:seon.client/autonomous? true}
             (get-in descriptor
                     [::launch/runtime :seon.client/launch-capability]))))))

(deftest branch-descriptor-inherits-source-owners-and-isolates-target-data
  (let [source-attachment
        {::coordinate/database-id
         #uuid "9dcfa740-5f7f-4ff5-ac08-a9c8b605a8aa"
         ::coordinate/branch :db}
        source (assoc-in
                (ordinary "data/clusters/acme"
                          :seon.dev.artifact.flavor/acme "acme-client")
                [::launch/database ::coordinate/attachment]
                source-attachment)
        target-coordinate
        {::coordinate/database-id
         #uuid "9dcfa740-5f7f-4ff5-ac08-a9c8b605a8aa"
         ::coordinate/branch :trial
         ::coordinate/commit-id
         #uuid "a2bd215f-7ec6-47dc-a627-f8e4948df581"
         ::coordinate/t 42}
        branch
        (launch/branch-descriptor
         {::launch/source-descriptor source
          ::launch/runtime-cluster "acme-trial"
          ::launch/target-database-name "acme-trial"
          ::launch/target-coordinate target-coordinate
          ::launch/process-dir "tmp/acme-trial"
          ::launch/log-dir "logs/acme-trial"
          ::launch/http-port 0
          ::launch/http-port-file "tmp/acme-trial/http.port"
          ::launch/writable-blob-dir "data/branches/acme-trial/blobs"})]
    (is (schema/valid-candidate-value? ::launch/descriptor branch))
    (is (= {:seon.client/autonomous? false}
           (get-in branch
                   [::launch/runtime :seon.client/launch-capability])))
    (is (= (::launch/writer-owner source) (::launch/writer-owner branch)))
    (is (= (select-keys (::launch/runtime source)
                        [::launch/artifact-flavor ::launch/client-build-id])
           (select-keys (::launch/runtime branch)
                        [::launch/artifact-flavor ::launch/client-build-id])))
    (is (= (coordinate/attachment target-coordinate)
           (get-in branch [::launch/database ::coordinate/attachment])))
    (is (= target-coordinate
           (get-in branch [::launch/database ::coordinate/coordinate])))
    (is (= "data/clusters/acme/db"
           (get-in branch [::launch/database ::protocol/database-path])))
    (is (= {:my.blob/writable-dir "data/branches/acme-trial/blobs"
            :my.blob/read-only-dirs ["data/clusters/acme/blobs"]}
           (::launch/blob-storage-view branch)))))

(deftest branch-descriptor-rejects-protected-or-overlapping-targets
  (let [database-id #uuid "9dcfa740-5f7f-4ff5-ac08-a9c8b605a8aa"
        source (assoc-in
                (ordinary "data/clusters/default"
                          :seon.dev.artifact.flavor/default "client")
                [::launch/database ::coordinate/attachment]
                {::coordinate/database-id database-id
                 ::coordinate/branch :db})
        request
        {::launch/source-descriptor source
         ::launch/runtime-cluster "trial"
         ::launch/target-database-name "trial"
         ::launch/target-coordinate
         {::coordinate/database-id
          database-id
          ::coordinate/branch :trial
          ::coordinate/commit-id
          #uuid "a2bd215f-7ec6-47dc-a627-f8e4948df581"
          ::coordinate/t 42}
         ::launch/process-dir "tmp/trial"
         ::launch/log-dir "logs/trial"
         ::launch/http-port 0
         ::launch/http-port-file "tmp/trial/http.port"
         ::launch/writable-blob-dir "data/branches/trial/blobs"}]
    (testing "main branch is never a lifecycle target"
      (is (thrown? js/Error
                   (launch/branch-descriptor
                    (assoc-in request
                              [::launch/target-coordinate ::coordinate/branch]
                              :db)))))
    (testing "every target-private path rejects normalized source containment"
      (doseq [crossed
              [(assoc request ::launch/writable-blob-dir
                      "data/clusters/default/blobs/../blobs/branch")
               (assoc request ::launch/process-dir
                      "data/clusters/default/db/process")
               (assoc request ::launch/log-dir "data/clusters")
               (assoc request ::launch/http-port-file
                      "data/clusters/default/blobs/http.port")]]
        (is (thrown? js/Error (launch/branch-descriptor crossed)))))))

(deftest complete-coordinate-claim-must-agree-with-retained-attachment
  (let [descriptor (ordinary "data/clusters/default"
                             :seon.dev.artifact.flavor/default "client")
        point {::coordinate/database-id
               #uuid "9dcfa740-5f7f-4ff5-ac08-a9c8b605a8aa"
               ::coordinate/branch :db
               ::coordinate/commit-id
               #uuid "a2bd215f-7ec6-47dc-a627-f8e4948df581"
               ::coordinate/t 17}
        pinned (launch/with-coordinate
                {::launch/descriptor descriptor
                 ::coordinate/coordinate point})]
    (is (= point (get-in pinned
                         [::launch/database ::coordinate/coordinate])))
    (is (= (coordinate/attachment point)
           (get-in pinned [::launch/database ::coordinate/attachment])))
    (is (thrown? js/Error
                 (launch/with-coordinate
                  {::launch/descriptor pinned
                   ::coordinate/coordinate
                   (assoc point ::coordinate/branch :other)})))))

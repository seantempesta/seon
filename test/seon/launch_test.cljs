(ns seon.launch-test
  (:require [cljs.reader :as reader]
            [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [seon.db.coordinate :as coordinate]
            [seon.db.protocol :as protocol]
            [seon.launch :as launch]
            [seon.schema :as schema]))

(def ^:private digest-a (apply str (repeat 64 "a")))
(def ^:private digest-b (apply str (repeat 64 "b")))

(defn- restore-startup-fixture []
  (let [database-id #uuid "9dcfa740-5f7f-4ff5-ac08-a9c8b605a8aa"
        consumer-generation
        #uuid "c5d99792-8f1b-4c22-a0ab-0f15cd11d739"
        point
        (fn [branch commit-id t]
          {::coordinate/database-id database-id
           ::coordinate/branch branch
           ::coordinate/commit-id commit-id
           ::coordinate/t t})
        pre (point :db
                   #uuid "7755012e-48ea-422b-9bdb-9a5b00f06378" 51)
        selected (point :seon.branch/retained
                        #uuid "64a83a56-14c2-467f-9bb8-3641b273be5f" 47)
        prepared (assoc selected ::coordinate/branch
                        :seon.restore.target/r-restoretest1)
        undo (assoc pre ::coordinate/branch
                    :seon.restore.undo/r-restoretest1)
        forced (point :db
                      #uuid "497628ef-2308-455a-ab83-9487584bf2ed" 47)]
    {:seon.dev.restore/startup-identity
     {:seon.dev.restore/intent-id
      #uuid "a5217d14-f006-49f5-8504-fbf65ec22e85"
      :seon.dev.restore/plan-digest digest-a
      :seon.dev.restore/reachable-hash-digest digest-b
      :seon.dev.restore/consumer-generations
      {:seon.dev.process/pod consumer-generation}}
     :seon.db.restore-admin/result
     {:seon.db.restore-admin/intent-id
      #uuid "a5217d14-f006-49f5-8504-fbf65ec22e85"
      :seon.db.restore-admin/plan-digest digest-a
      :seon.db.restore-admin/outcome
      :seon.db.restore-admin.outcome/applied
      :seon.db.restore-admin/pre-restore-main-coordinate pre
      :seon.db.restore-admin/selected-target-coordinate selected
      :seon.db.restore-admin/prepared-target-coordinate prepared
      :seon.db.restore-admin/undo-coordinate undo
      :seon.db.restore-admin/forced-main-coordinate forced
      :seon.db.restore-admin/branch-roster
      #{:db :seon.branch/retained
        :seon.restore.target/r-restoretest1
        :seon.restore.undo/r-restoretest1}
      :seon.db.restore-admin/force-invoked? true
      :seon.db.restore-admin/connection-state
      :seon.db.restore-admin.connection/released}
     :my.blob/materialization-result
     {:my.blob/ok? true
      :my.blob/target-coordinate selected
      :my.blob/reachable-hash-digest digest-b
      :my.blob/hash-count 2
      :my.blob/verified-count 2
      :my.blob/newly-materialized-count 1
      :my.blob/repaired-count 0}}))

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

(deftest restore-startup-is-closed-serializable-and-generation-bound
  (let [startup (restore-startup-fixture)
        forced-main (get-in startup [:seon.db.restore-admin/result
                                     :seon.db.restore-admin/forced-main-coordinate])
        pinned (launch/with-coordinate
                {::launch/descriptor
                 (ordinary "data/clusters/default"
                           :seon.dev.artifact.flavor/default "client")
                 ::coordinate/coordinate forced-main})
        descriptor (launch/with-restore-startup
                    {::launch/descriptor pinned
                     ::launch/restore-startup startup})
        encoded (pr-str descriptor)]
    (is (= descriptor (reader/read-string encoded)))
    (is (= descriptor (launch/validate-descriptor descriptor)))
    (is (= #uuid "c5d99792-8f1b-4c22-a0ab-0f15cd11d739"
           (get-in startup
                   [:seon.dev.restore/startup-identity
                    :seon.dev.restore/consumer-generations
                    :seon.dev.process/pod])))
    (is (= forced-main
           (get-in descriptor [::launch/database ::coordinate/coordinate])))
    (is (= {:seon.client/autonomous? false}
           (get-in descriptor
                   [::launch/runtime :seon.client/launch-capability])))
    (is (thrown? js/Error
                 (launch/validate-descriptor
                  (assoc-in descriptor
                            [::launch/runtime
                             :seon.client/launch-capability
                             :seon.client/autonomous?]
                            true))))
    (is (false?
         (schema/valid-candidate-value?
          ::launch/restore-startup
          (assoc startup :seon.test/unknown true))))))

(deftest restore-startup-rejects-cross-owner-evidence-mutations
  (let [startup (restore-startup-fixture)
        different-coordinate
        (assoc (get-in startup [:seon.db.restore-admin/result
                                :seon.db.restore-admin/forced-main-coordinate])
               ::coordinate/t 99)
        mutations
        [(assoc-in startup [:seon.db.restore-admin/result
                            :seon.db.restore-admin/intent-id]
                   #uuid "4fe1ec1e-5bf4-46ed-b76b-f6a1fc8a786e")
         (assoc-in startup [:seon.db.restore-admin/result
                            :seon.db.restore-admin/plan-digest]
                   digest-b)
         (assoc-in startup [:my.blob/materialization-result
                            :my.blob/reachable-hash-digest]
                   digest-a)
         (assoc-in startup [:my.blob/materialization-result
                            :my.blob/target-coordinate]
                   different-coordinate)
         (assoc-in startup [:seon.dev.restore/startup-identity
                            :seon.dev.restore/consumer-generations]
                   {:seon.dev.process/other
                    #uuid "33333333-3333-4333-8333-333333333333"})]]
    (doseq [mutation mutations]
      (is (schema/valid-candidate-value? ::launch/restore-startup mutation))
      (is (thrown? js/Error (launch/validate-restore-startup mutation))))
    (is (thrown?
         js/Error
         (launch/with-restore-startup
          {::launch/descriptor
           (ordinary "data/clusters/default"
                     :seon.dev.artifact.flavor/default "client")
           ::launch/restore-startup startup})))
    (is (false?
         (schema/valid-candidate-value?
          ::launch/restore-startup
          (assoc-in startup [:seon.db.restore-admin/result
                             :seon.db.restore-admin/outcome]
                    :seon.db.restore-admin/rejected))))))

(deftest ordinary-descriptor-bytes-remain-unchanged-without-restore
  (let [descriptor (ordinary "data/clusters/default"
                             :seon.dev.artifact.flavor/default "client")
        encoded (pr-str descriptor)]
    (is (= descriptor (launch/validate-descriptor descriptor)))
    (is (= encoded (pr-str (launch/validate-descriptor descriptor))))
    (is (= descriptor (reader/read-string encoded)))
    (is (not (contains? descriptor ::launch/restore-startup)))))

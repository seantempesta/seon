(ns seon.client-session-test
  (:require [cljs.test :refer [async deftest is]]
            [seon.client :as client]
            [seon.db :as db]
            [seon.db.coordinate :as coordinate]
            [seon.db.protocol :as protocol]
            [seon.launch :as launch]))

(defn- descriptor
  []
  (launch/default-descriptor
   {::launch/cluster-dir "data/clusters/client-test"
    ::launch/artifact-flavor :seon.dev.artifact.flavor/default
    ::launch/client-build-id "client"
    ::launch/request-socket-path "tmp/client-test-db.sock"
    ::launch/publish-socket-path "tmp/client-test-pub.sock"
    ::launch/writer-repl-port-file "tmp/client-test-writer.port"
    ::launch/process-dir "tmp/client-test"
    ::launch/log-dir "logs/client-test"
    ::launch/http-port 7890
    ::launch/http-port-file "tmp/client-test-http.port"}))

(deftest bootstrap-opens-one-session-before-authority-preparation
  (async done
    (let [effects (atom [])
          original-descriptor launch/process-launch-descriptor
          original-open db/open-session!
          original-provenance db/ensure-provenance!
          original-transact db/transact!
          opened-coordinate
          {::coordinate/database-id
           #uuid "9dcfa740-5f7f-4ff5-ac08-a9c8b605a8aa"
           ::coordinate/branch :db
           ::coordinate/commit-id
           #uuid "a2bd215f-7ec6-47dc-a627-f8e4948df581"
           ::coordinate/t 17}
          opened {::db/database-name "client-test"
                  ::db/attachment (coordinate/attachment opened-coordinate)
                  ::db/coordinate opened-coordinate
                  ::db/capabilities {}}]
      (set! launch/process-launch-descriptor (descriptor))
      (set! db/open-session!
            (fn [request]
              (swap! effects conj [:open request])
              (js/Promise.resolve opened)))
      (set! db/ensure-provenance!
            (fn [request]
              (swap! effects conj [:provenance request])
              (js/Promise.resolve {::db/provenance-action :converged})))
      (set! db/transact!
            (fn [& [request]]
              (swap! effects conj [:schema (contains? request ::db/tx-data)])
              (js/Promise.resolve {::db/ok? true})))
      (-> (client/open-database-session! {::client/prepare-writes? true})
          (.then
           (fn [actual]
             (is (= opened actual))
             (is (= [:open :provenance :schema]
                    (mapv first @effects)))
             (is (= {::db/socket-path "tmp/client-test-db.sock"
                     ::db/database-name "client-test"
                     ::db/backend :file
                     ::db/database-path "data/clusters/client-test/db"}
                    (second (first @effects))))))
          (.catch (fn [error] (is false (str "session bootstrap threw " error))))
          (.finally
           (fn []
             (set! launch/process-launch-descriptor original-descriptor)
             (set! db/open-session! original-open)
             (set! db/ensure-provenance! original-provenance)
             (set! db/transact! original-transact)
             (done)))))))

(deftest restore-coordinate-selects-only-its-existing-attachment
  (let [point {::coordinate/database-id
               #uuid "9dcfa740-5f7f-4ff5-ac08-a9c8b605a8aa"
               ::coordinate/branch :db
               ::coordinate/commit-id
               #uuid "a2bd215f-7ec6-47dc-a627-f8e4948df581"
               ::coordinate/t 17}
        pinned (launch/with-coordinate
                {::launch/descriptor (descriptor)
                 ::coordinate/coordinate point})
        selection ((deref #'client/session-selection) pinned)]
    (is (= (coordinate/attachment point) (::db/attachment selection)))
    (is (nil? (::protocol/coordinate selection)))))

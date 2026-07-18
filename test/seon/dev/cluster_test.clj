(ns seon.dev.cluster-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is run-tests]]
            [seon.dev.artifact :as artifact]
            [seon.dev.cluster :as cluster]
            [seon.dev.config :as config]
            [seon.dev.process :as process]
            [seon.dev.state :as state]
            [seon.launch :as launch]))

(defn- target-request []
  (let [configuration (config/load! (System/getProperty "user.dir"))]
    (cluster/request {::cluster/configuration configuration
                      ::cluster/name "experiment"})))

(deftest public-name-derives-one-private-autonomous-cluster
  (let [{::cluster/keys [configuration target-configuration name]}
        (target-request)
        root (:seon.dev.config/root configuration)
        descriptor (:seon.dev.config/launch-descriptor target-configuration)]
    (is (= "experiment" name))
    (is (= "experiment"
           (get-in descriptor [::launch/runtime ::launch/runtime-cluster])))
    (is (= {:seon.client/autonomous? true}
           (get-in descriptor
                   [::launch/runtime :seon.client/launch-capability])))
    (is (= (::launch/writer-owner
            (:seon.dev.config/launch-descriptor configuration))
           (::launch/writer-owner descriptor)))
    (is (= (str (fs/path root "data/clusters/experiment/db"))
           (get-in descriptor
                   [::launch/database :seon.db.protocol/database-path])))
    (is (= (str (fs/path root "data/clusters/experiment/blobs"))
           (get-in descriptor [::launch/blob-storage-view
                               :my.blob/writable-dir])))
    (doseq [invalid ["" "../experiment" "Experiment" "a/b" "-a" "a-"]]
      (is (thrown? Exception
                   (cluster/request {::cluster/configuration configuration
                                     ::cluster/name invalid}))))))

(deftest open-and-restart-use-the-one-pod-supervisor-path
  (let [{::cluster/keys [target-configuration] :as request} (target-request)
        manifest {:seon.dev.artifact/application-digest "application"}
        pod {:seon.dev.process/id process/pod-id}
        calls (atom [])
        status-value {:seon.dev.target/status :seon.dev.target.status/ready}]
    (with-redefs [artifact/read-manifest (fn [_] manifest)
                  process/specs (fn [_ _] {process/pod-id pod})
                  process/with-startup-ownership (fn [_ transition]
                                                   (transition
                                                    (fn [_id acquire! _cleanup]
                                                      (acquire!))))
                  state/with-lock (fn [_ _ _ transition] (transition))
                  process/ensure! (fn [selected spec acquire!]
                                    (is (= target-configuration selected))
                                    (is (= pod spec))
                                    (acquire! process/pod-id
                                              #(swap! calls conj :ensure)))
                  process/clean-or-force!
                  (fn [{:seon.dev.process/keys [configuration operation targets]}]
                    (swap! calls conj [operation targets])
                    (is (= target-configuration configuration))
                    {:seon.dev.process/operation operation
                     :seon.dev.process/classification
                     :seon.dev.process.classification/clean
                     :seon.dev.process/budget-ms 1
                     :seon.dev.process/elapsed-ms 0
                     :seon.dev.process/results []})
                  process/status (fn [selected selected-manifest]
                                   (is (= target-configuration selected))
                                   (is (= manifest selected-manifest))
                                   status-value)]
      (is (= status-value (cluster/open! request)))
      (is (= [:ensure] @calls))
      (reset! calls [])
      (is (= status-value (cluster/restart! request)))
      (is (= [[:seon.dev.process.operation/restart #{process/pod-id}]
              :ensure]
             @calls)))))

(deftest close-stops-only-the-pod-and-does-not-delete-cluster-data
  (let [{::cluster/keys [target-configuration] :as request} (target-request)
        calls (atom [])
        result
        {:seon.dev.process/operation :seon.dev.process.operation/down
         :seon.dev.process/classification
         :seon.dev.process.classification/clean
         :seon.dev.process/budget-ms 1
         :seon.dev.process/elapsed-ms 0
         :seon.dev.process/results []}]
    (with-redefs [state/with-lock (fn [_ _ _ transition] (transition))
                  process/clean-or-force!
                  (fn [request]
                    (reset! calls request)
                    result)]
      (is (= result (cluster/close! request)))
      (is (= target-configuration
             (:seon.dev.process/configuration @calls)))
      (is (= #{process/pod-id} (:seon.dev.process/targets @calls)))
      (is (= :seon.dev.process.operation/down
             (:seon.dev.process/operation @calls))))))

(deftest status-projects-the-existing-process-and-dependency-health
  (let [{::cluster/keys [target-configuration] :as request} (target-request)
        manifest {:seon.dev.artifact/application-digest "application"}
        expected {:seon.dev.target/status :seon.dev.target.status/degraded
                  :seon.dev.target/external-dependencies
                  {process/writer-id {:seon.dev.process/ready? false}}}]
    (with-redefs [artifact/read-manifest (fn [_] manifest)
                  process/status (fn [selected selected-manifest]
                                   (is (= target-configuration selected))
                                   (is (= manifest selected-manifest))
                                   expected)]
      (is (= expected (cluster/status request))))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'seon.dev.cluster-test)]
    (when (pos? (+ fail error)) (System/exit 1))))

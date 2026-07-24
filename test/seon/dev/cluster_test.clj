(ns seon.dev.cluster-test
  (:require [babashka.fs :as fs]
            [babashka.process :as shell]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]
            [seon.dev.artifact :as artifact]
            [seon.dev.cluster :as cluster]
            [seon.dev.config :as config]
            [seon.dev.process :as process]
            [seon.dev.release :as release]
            [seon.dev.state :as state]
            [seon.launch :as launch]))

(defn- target-request []
  (let [configuration (config/load! (System/getProperty "user.dir"))]
    (cluster/request {::cluster/configuration configuration
                      ::cluster/name "experiment"})))

(deftest current-manifest-uses-the-owning-reader-for-source-and-release
  (let [manifest {:seon.dev.artifact/application-digest "application"}
        calls (atom [])]
    (with-redefs [artifact/current-manifest
                  (fn [configuration]
                    (swap! calls conj [:artifact configuration])
                    manifest)
                  artifact/read-manifest
                  (fn [configuration]
                    (swap! calls conj [:artifact-read configuration])
                    manifest)
                  release/read-manifest!
                  (fn [path]
                    (swap! calls conj [:release path])
                    manifest)]
      (is (= manifest
             (#'cluster/current-manifest!
              {:seon.dev.config/source-checkout? true})))
      (is (= manifest
             (#'cluster/current-manifest!
              {:seon.dev.config/source-checkout? false
               :seon.dev.config/artifact-manifest "/release.edn"})))
      (is (= manifest
             (#'cluster/manifest!
              {:seon.dev.config/source-checkout? true})))
      (is (= manifest
             (#'cluster/manifest!
              {:seon.dev.config/source-checkout? false
               :seon.dev.config/artifact-manifest "/release.edn"})))
      (is (= [[:artifact {:seon.dev.config/source-checkout? true}]
              [:release "/release.edn"]
              [:artifact-read {:seon.dev.config/source-checkout? true}]
              [:release "/release.edn"]]
             @calls)))))

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
    (is (= (str (fs/path root "data/clusters/experiment/packages"))
           (::launch/packages-dir descriptor)))
    (doseq [invalid ["" "../experiment" "Experiment" "a/b" "-a" "a-"]]
      (is (thrown? Exception
                   (cluster/request {::cluster/configuration configuration
                                     ::cluster/name invalid}))))))

(deftest package-clusters-live-in-operator-state-not-the-immutable-release
  (let [source (::cluster/configuration (target-request))
        configuration
        (assoc source
               :seon.dev.config/root "/immutable/release"
               :seon.dev.config/source-checkout? false
               :seon.dev.config/cluster-dir
               "/operator-state/data/clusters/control")
        target
        (with-redefs [config/select-manifest
                      (fn [selected _config-path] selected)]
          (::cluster/target-configuration
           (cluster/request {::cluster/configuration configuration
                             ::cluster/name "experiment"})))
        descriptor (:seon.dev.config/launch-descriptor target)]
    (is (= "/operator-state/data/clusters/experiment/db"
           (get-in descriptor
                   [::launch/database :seon.db.protocol/database-path])))
    (is (= "/operator-state/tmp/seon-clusters/experiment"
           (get-in descriptor [::launch/process ::launch/process-dir])))
    (is (= "/operator-state/logs/clusters/experiment"
           (get-in descriptor [::launch/process ::launch/log-dir])))))

(deftest package-skeleton-is-generated-and-reset-from-the-cluster-coordinate
  (let [root (fs/create-temp-dir {:prefix "seon-cluster-packages-"})
        packages-dir (fs/path root "packages")
        descriptor (assoc (:seon.dev.config/launch-descriptor
                           (::cluster/target-configuration (target-request)))
                          ::launch/packages-dir (str packages-dir))
        package-json (fs/path packages-dir "npm/package.json")
        deps-edn (fs/path packages-dir "deps.edn")]
    (try
      (is (= (str packages-dir)
             (cluster/ensure-package-skeleton! descriptor)))
      (is (= {:dependencies {} :trustedDependencies []}
             (json/parse-string (slurp (str package-json)) true)))
      (is (= {:deps {}} (edn/read-string (slurp (str deps-edn)))))
      (spit (str (fs/path packages-dir "npm/stale")) "stale")
      (spit (str deps-edn) "{:deps {stale/lib {:mvn/version \"1\"}}}\n")
      (is (= (str packages-dir)
             (cluster/reset-package-skeleton! descriptor)))
      (is (not (fs/exists? (fs/path packages-dir "npm/stale"))))
      (is (= {:dependencies {} :trustedDependencies []}
             (json/parse-string (slurp (str package-json)) true)))
      (is (= {:deps {}} (edn/read-string (slurp (str deps-edn)))))
      (finally (fs/delete-tree root {:force true})))))

(deftest open-and-restart-use-the-one-pod-supervisor-path
  (let [{::cluster/keys [target-configuration] :as request} (target-request)
        manifest {:seon.dev.artifact/application-digest "application"}
        pod {:seon.dev.process/id process/pod-id}
        calls (atom [])
        package-roots (atom [])
        status-value {:seon.dev.target/status :seon.dev.target.status/ready}]
    (with-redefs [artifact/read-manifest (fn [_] manifest)
                  cluster/ensure-package-skeleton!
                  (fn [descriptor]
                    (swap! package-roots conj (::launch/packages-dir descriptor)))
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
             @calls))
      (is (= [(::launch/packages-dir
               (:seon.dev.config/launch-descriptor
                target-configuration))]
             @package-roots)))))

(deftest apply-runs-the-current-pod-artifact-once-and-publishes-after-success
  (let [{::cluster/keys [configuration target-configuration] :as request}
        (target-request)
        root (fs/create-temp-dir {:prefix "seon-cluster-apply-"})
        process-dir (str (fs/path root "process"))
        target-configuration
        (-> target-configuration
            (assoc :seon.dev.config/root (str root))
            (assoc-in [:seon.dev.config/launch-descriptor
                       ::launch/process ::launch/process-dir]
                      process-dir))
        request (assoc request ::cluster/target-configuration
                       target-configuration)
        manifest {:seon.dev.artifact/application-digest "application"}
        pod {:seon.dev.process/id process/pod-id
             :seon.dev.process/argv ["/runtime/bun" "/runtime/client.js"]
             :seon.dev.process/environment {"EXISTING" "pod"}}
        result {:seon.cluster.apply/ok? true
                :seon.cluster.apply/changed? false}
        effects (atom [])]
    (try
      (with-redefs
       [artifact/current-manifest
        (fn [selected]
          (is (= configuration selected))
          manifest)
        process/specs
        (fn [selected selected-manifest]
          (is (= target-configuration selected))
          (is (= manifest selected-manifest))
          {process/pod-id pod})
        process/status
        (fn [selected selected-manifest]
          (is (= target-configuration selected))
          (is (= manifest selected-manifest))
          {:seon.dev.target/external-dependencies
           {process/writer-id {:seon.dev.process/ready? true}}})
        process/read-process
        (fn [selected id]
          (is (= target-configuration selected))
          (is (= process/pod-id id))
          nil)
        cluster/ensure-package-skeleton!
        (fn [_] (swap! effects conj :packages))
        state/with-lock
        (fn [selected owner timeout-ms transition]
          (swap! effects conj [:lock selected owner timeout-ms])
          (transition))
        shell/sh
        (fn [{:keys [cmd env dir]}]
          (swap! effects conj [:run cmd env dir])
          (spit (get env "SEON_CLUSTER_APPLY_RESULT")
                (str (pr-str result) "\n"))
          {:exit 0 :out "" :err ""})
        config/publish-applied-manifest!
        (fn [selected]
          (swap! effects conj [:publish selected])
          selected)]
       (is (= result (cluster/apply! request))))
      (let [[lock packages run publish] @effects
            [_ argv environment directory] run]
        (is (= [:lock configuration :cluster 600000] lock))
        (is (= :packages packages))
        (is (= ["/runtime/bun" "/runtime/client.js" "cluster-apply"]
               argv))
        (is (= "pod" (get environment "EXISTING")))
        (is (str/starts-with?
             (get environment "SEON_CLUSTER_APPLY_RESULT")
             (str (fs/path process-dir "cluster-apply"))))
        (is (= (str root) directory))
        (is (= [:publish target-configuration] publish)))
      (finally (fs/delete-tree root {:force true})))))

(deftest apply-requires-a-ready-writer-and-an-absent-pod
  (let [{::cluster/keys [target-configuration] :as request} (target-request)
        manifest {:seon.dev.artifact/application-digest "application"}
        pod {:seon.dev.process/id process/pod-id}
        ran? (atom false)]
    (with-redefs [artifact/current-manifest (constantly manifest)
                  process/specs (fn [_ _] {process/pod-id pod})
                  state/with-lock (fn [_ _ _ transition] (transition))
                  shell/sh (fn [_] (reset! ran? true))]
      (with-redefs [process/status
                    (fn [_ _]
                      {:seon.dev.target/external-dependencies
                       {process/writer-id
                        {:seon.dev.process/ready? false}}})
                    process/read-process (constantly nil)]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"writer to be ready"
             (cluster/apply! request))))
      (with-redefs [process/status
                    (fn [_ _]
                      {:seon.dev.target/external-dependencies
                       {process/writer-id
                        {:seon.dev.process/ready? true}}})
                    process/read-process
                    (fn [selected id]
                      (is (= target-configuration selected))
                      (is (= process/pod-id id))
                      {:seon.dev.process/id process/pod-id})
                    process/reported-process-status (constantly
                                                     :seon.dev.process.status/alive)]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"pod to be closed"
             (cluster/apply! request)))))
    (is (false? @ran?))))

(deftest apply-never-publishes-the-selected-manifest-after-client-failure
  (let [{::cluster/keys [target-configuration] :as request} (target-request)
        manifest {:seon.dev.artifact/application-digest "application"}
        pod {:seon.dev.process/id process/pod-id
             :seon.dev.process/argv ["/runtime/bun" "/runtime/client.js"]
             :seon.dev.process/environment {}}
        published? (atom false)]
    (with-redefs [artifact/current-manifest (constantly manifest)
                  process/specs (fn [_ _] {process/pod-id pod})
                  process/status
                  (fn [_ _]
                    {:seon.dev.target/external-dependencies
                     {process/writer-id {:seon.dev.process/ready? true}}})
                  process/read-process (constantly nil)
                  cluster/ensure-package-skeleton! (constantly nil)
                  state/with-lock (fn [_ _ _ transition] (transition))
                  fs/create-dirs (constantly nil)
                  shell/sh (constantly {:exit 1
                                        :out "apply output"
                                        :err "apply failed"})
                  config/publish-applied-manifest!
                  (fn [_] (reset! published? true))]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"exited unsuccessfully"
           (cluster/apply! request))))
    (is (false? @published?))))

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

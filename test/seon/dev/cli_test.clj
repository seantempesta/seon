(ns seon.dev.cli-test
  (:require [babashka.fs :as fs]
            [babashka.process :as shell]
            [clojure.test :refer [deftest is testing]]
            [seon.dev.artifact :as artifact]
            [seon.dev.cli :as cli]
            [seon.dev.process :as process]
            [seon.dev.state :as state]))

(deftest ready-url-selects-an-ordinary-agent-from-the-root-feed
  (let [requested (atom [])]
    (with-redefs-fn
      {#'shell/sh
       (fn [{:keys [cmd]}]
         (swap! requested conj cmd)
         {:exit 0
          :out (str "<a href=\"/agent/root\"></a>"
                    "<a href=\"/agent/root/debug\"></a>"
                    "<a href=\"/agent/quiet-rivers-turn\"></a>")
          :err ""})}
      (fn []
        (is (= "http://127.0.0.1:7890/agent/quiet-rivers-turn"
               (#'cli/ordinary-agent-url "http://127.0.0.1:7890")))))
    (is (= "http://127.0.0.1:7890/agent/root/feed"
           (last (first @requested))))))

(deftest ready-url-falls-back-to-the-valid-root-page
  (with-redefs-fn
    {#'shell/sh (constantly {:exit 22 :out "" :err "unavailable"})}
    (fn []
      (is (= "http://127.0.0.1:7890/"
             (#'cli/ordinary-agent-url "http://127.0.0.1:7890"))))))

(defn- configuration [root]
  {:seon.dev.config/root root
   :seon.dev.config/environment {}})

(deftest test-targets-route-to-the-canonical-runners
  (let [root (str (fs/normalize (fs/absolutize ".")))
        config (configuration root)
        pod (str (fs/path root "bin/test-cljs"))
        database (str (fs/path root "bin/test-writer"))
        operator ["bb" "--config" (str (fs/path root "bb.edn"))
                  "--deps-root" root "-m" "seon.dev.test-runner"]]
    (is (= [[pod "--test=seon.db-test"]]
           (#'cli/test-commands config ["pod" "seon.db-test"])))
    (is (= [[pod "--no-build" "--test=seon.db-test/query-roundtrip"]]
           (#'cli/test-commands
             config ["pod" "--no-build" "seon.db-test/query-roundtrip"])))
    (is (= [[database "seon.db.registry-test"]]
           (#'cli/test-commands config ["database" "seon.db.registry-test"])))
    (is (= [(conj operator "seon.dev.cli-test")]
           (#'cli/test-commands config ["operator" "seon.dev.cli-test"])))
    (is (= [operator [database] [pod]]
           (#'cli/test-commands config ["all"])))))

(deftest selector-free-targets-reject-accidental-arguments
  (let [config (configuration (str (fs/normalize (fs/absolutize "."))))]
    (testing "only the combined complete gate rejects selectors"
      (is (thrown? Exception
                   (#'cli/test-commands config ["all" "extra"]))))
    (is (thrown? Exception
                 (#'cli/test-commands config ["unknown"])))))

(deftest start-options-are-explicit-and-ordered
  (is (= {:seon.dev.start/open? true
          :seon.dev.start/config-path "config/system.edn"}
         (#'cli/parse-start-options ["--config" "config/system.edn" "--open"])))
  (is (thrown? Exception (#'cli/parse-start-options ["--config"])))
  (is (thrown? Exception (#'cli/parse-start-options ["--unknown"]))))

(deftest config-is-implicit-only-for-a-fresh-database
  (let [root (fs/create-temp-dir {:prefix "seon-cli-config-"})
        cluster (fs/path root "data/clusters/default")
        default-config (fs/path root "config/system.edn")
        explicit-config (fs/path root "config/custom.edn")
        base {:seon.dev.config/root (str root)
              :seon.dev.config/cluster-dir (str cluster)
              :seon.dev.config/environment {}}]
    (try
      (fs/create-dirs (fs/parent default-config))
      (spit (str default-config) "{}\n")
      (spit (str explicit-config) "{}\n")
      (testing "first-ever boot selects the shipped manifest"
        (is (= (str default-config)
               (get-in (#'cli/select-config base nil)
                       [:seon.dev.config/environment "SEON_CONFIG"]))))
      (testing "an existing database receives no ambient config apply"
        (fs/create-dirs (fs/path cluster "db"))
        (spit (str (fs/path cluster "db/root.ksv")) "database")
        (is (nil? (get-in (#'cli/select-config base nil)
                          [:seon.dev.config/environment "SEON_CONFIG"]))))
      (testing "an explicit relative path is rooted at the checkout"
        (is (= (str explicit-config)
               (get-in (#'cli/select-config base "config/custom.edn")
                       [:seon.dev.config/environment "SEON_CONFIG"]))))
      (finally (fs/delete-tree root {:force true})))))

(deftest legacy-database-layout-is-never-silently-replaced
  (let [root (fs/create-temp-dir {:prefix "seon-cli-legacy-db-"})
        cluster (fs/path root "data/clusters/acme")
        configuration {:seon.dev.config/cluster-dir (str cluster)
                       :seon.dev.config/cluster-name "acme"}]
    (try
      (fs/create-dirs (fs/path cluster "store"))
      (is (thrown-with-msg?
            Exception #"Refusing to create a fresh database"
            (#'cli/assert-current-database-layout! configuration)))
      (is (= {:seon.dev.target/name :seon.dev.target/development
              :seon.dev.target/status
              :seon.dev.target.status/ownership-conflict
              :seon.dev.target/failure
              :seon.dev.target.failure/legacy-database-layout
              :seon.dev.target/cluster-name "acme"
              :seon.dev.target/database-path (str (fs/path cluster "db"))
              :seon.dev.target/legacy-database-path
              (str (fs/path cluster "store"))}
             (#'cli/status-value configuration)))
      (fs/create-dirs (fs/path cluster "db"))
      (is (= configuration
             (#'cli/assert-current-database-layout! configuration)))
      (finally (fs/delete-tree root {:force true})))))

(deftest cluster-reset-rebuilds-before-reconciling
  (let [root (fs/create-temp-dir {:prefix "seon-cli-reset-"})
        cluster (fs/path root "data/clusters/default")
        database (fs/path cluster "db")
        configuration {:seon.dev.config/root (str root)
                       :seon.dev.config/cluster-dir (str cluster)
                       :seon.dev.config/cluster-name "default"
                       :seon.dev.config/environment {}}
        stopped (atom [])
        reconciled (atom [])]
    (try
      (fs/create-dirs database)
      (spit (str (fs/path database "old.ksv")) "old database")
      (with-redefs-fn
        {#'state/with-lock (fn [_configuration _owner _timeout thunk]
                            (thunk))
         #'process/stop! (fn [_configuration process-id]
                           (swap! stopped conj process-id))
         #'artifact/read-manifest
         (fn [_]
           (throw (ex-info "reset must not reuse a manifest" {})))
         #'cli/select-config (fn [selected _path] selected)
         #'cli/reconcile-development!
         (fn [selected]
           (is (not (fs/exists? database)))
           (swap! reconciled conj selected)
           {:seon.dev.target/status :seon.dev.target.status/ready})}
        (fn [] (#'cli/reset-cluster! configuration ["default"])))
      (is (= [process/pod-id process/writer-id] @stopped))
      (is (= [configuration] @reconciled))
      (finally (fs/delete-tree root {:force true})))))

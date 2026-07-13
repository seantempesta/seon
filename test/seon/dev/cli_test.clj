(ns seon.dev.cli-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [seon.dev.cli :as cli]))

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
    (is (= [operator [database] [pod]]
           (#'cli/test-commands config ["all"])))))

(deftest selector-free-targets-reject-accidental-arguments
  (let [config (configuration (str (fs/normalize (fs/absolutize "."))))]
    (testing "operator and all are complete named gates"
      (is (thrown? Exception
                   (#'cli/test-commands config ["operator" "extra"])))
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

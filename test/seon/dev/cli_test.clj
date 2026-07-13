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

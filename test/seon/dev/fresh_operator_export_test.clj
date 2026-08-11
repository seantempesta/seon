(ns seon.dev.fresh-operator-export-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [seon.cluster.registry :as registry]
            [seon.cluster.store :as store]
            [seon.db :as db]
            [seon.test-support :as test-support])
  (:import [java.util.concurrent TimeUnit]))

(def ^:private project-root
  (.getCanonicalFile (io/file (System/getProperty "user.dir"))))

(defn- fresh-root
  []
  (let [root (io/file project-root "tmp" "fresh-operator-export-test"
                      (str (random-uuid)))]
    (.mkdirs root)
    root))

(defn- run-seon
  [root & arguments]
  (let [command (into [(str (io/file project-root "bin" "seon"))
                       "--root" (.getCanonicalPath (io/file root))]
                      arguments)
        process (.start
                 (doto (ProcessBuilder. ^java.util.List command)
                   (.directory project-root)
                   (.redirectErrorStream true)))
        output (future (slurp (.getInputStream process)))
        completed? (.waitFor process 180 TimeUnit/SECONDS)]
    (when-not completed?
      (.destroyForcibly process)
      (.waitFor process 10 TimeUnit/SECONDS))
    {:seon.dev.fresh-operator-export-test/completed? completed?
     :seon.dev.fresh-operator-export-test/exit
     (when completed? (.exitValue process))
     :seon.dev.fresh-operator-export-test/output
     (deref output 10000 "The operator output reader did not finish.")}))

(deftest ^{:seon.test/long "Starts a scratch root and exports its live store."}
  export-verb-produces-an-openable-queryable-store
  (let [root (fresh-root)
        cluster-name "export-verb"
        destination (io/file root "exported")
        exported-store (io/file destination "store")]
    (try
      (test-support/populate-published-operator-root! root)
      (let [cold-destination (io/file root "cold-export")
            cold (run-seon root "export" (.getPath cold-destination))]
        (is (= 1 (::exit cold)) (::output cold))
        (is (str/includes? (::output cold) "bin/seon start")
            (::output cold))
        (is (false? (.exists cold-destination))))
      (let [started (run-seon root "start" cluster-name)]
        (is (true? (::completed? started)) (::output started))
        (is (= 0 (::exit started)) (::output started)))
      (let [extra (run-seon root "export" (.getPath destination) "extra")]
        (is (= 1 (::exit extra)) (::output extra))
        (is (str/includes? (::output extra) "export DESTINATION-PATH")
            (::output extra)))
      (let [outcome (run-seon root "export" (.getPath destination))]
        (is (true? (::completed? outcome)) (::output outcome))
        (is (= 0 (::exit outcome)) (::output outcome))
        (is (str/includes? (::output outcome)
                           (.getCanonicalPath exported-store))
            (::output outcome)))
      (let [occupied (run-seon root "export" (.getPath destination))]
        (is (= 1 (::exit occupied)) (::output occupied))
        (is (str/includes? (::output occupied)
                           "must not exist or must be an empty directory")
            (::output occupied)))
      (let [exported (store/open-store!
                      {:seon.store/dir (.getPath exported-store)})]
        (try
          (let [connection
                (store/open-branch!
                 exported (registry/cluster-branch cluster-name))]
            (try
              (is (= "root"
                     (db/q '[:find ?id .
                             :in $ ?id
                             :where [?agent :seon.cluster.agent/id ?id]]
                           @connection "root")))
              (finally
                (d/release connection))))
          (finally
            (store/release-store! exported))))
      (finally
        (try
          (run-seon root "down" "--force")
          (catch Throwable _))
        (test-support/delete-recursively! root)))))

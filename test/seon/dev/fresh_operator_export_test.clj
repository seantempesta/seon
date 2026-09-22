(ns seon.dev.fresh-operator-export-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [seon.cluster.registry :as registry]
            [seon.cluster.process :as process]
            [seon.operator :as client]
            [seon.cluster.store :as store]
            [seon.db :as db]
            [seon.test-support :as test-support]))

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
        result (process/run-process!
                {:seon.operator.subprocess/argv command
                 :seon.operator.subprocess/directory (str project-root)
                 :seon.operator.subprocess/merge-error? true
                 :seon.operator.subprocess/deadline-ms (client/operator-boot-bound-ms {})})]
    {:seon.dev.fresh-operator-export-test/completed? true
     :seon.dev.fresh-operator-export-test/exit (:seon.operator.subprocess/exit result)
     :seon.dev.fresh-operator-export-test/output (:seon.operator.subprocess/output result)}))

(deftest ^{:seon.test/fixture-observation "The exported physical store must be independently openable and queryable after the operator export command."} ^{:seon.test/long
           "One real cold start followed by connected export, store copy/reidentify, reopen and query proof."
             :seon.test/long-ms 360000}
  export-verb-produces-an-openable-queryable-store
  (let [root (fresh-root)
        cluster-name "export-verb"
        destination (io/file root "exported")
        exported-store (io/file destination "store")]
    (try
      (test-support/populate-published-operator-root!
       root {:seon.test/fixture-observation "CLI export must produce a physically independent store that can reopen."})
      (let [cold-destination (io/file root "cold-export")
            cold (run-seon root "export" (.getPath cold-destination))]
        (is (= 1 (::exit cold)) (::output cold))
        (is (str/includes? (::output cold) "No live exact-root JVM")
            (::output cold))
        (is (false? (.exists cold-destination))))
      (let [started (run-seon root "start" cluster-name)]
        (is (true? (::completed? started)) (::output started))
        (is (= 0 (::exit started)) (::output started)))
      (let [extra (run-seon root "export" (.getPath destination) "extra")]
        (is (= 1 (::exit extra)) (::output extra))
        (is (str/includes? (::output extra) "Use export PATH")
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
                           "an export never overwrites one")
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
                             :where [?agent :seon.agent/id ?id]]
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

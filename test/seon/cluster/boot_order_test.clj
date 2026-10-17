(ns seon.cluster.boot-order-test
  "Boot builds store → facts → flow: the cluster adopts the publication its JVM runs before any boot step reads or
  writes the branch (`seon.cluster/adopt-publication!`)."
  (:require [babashka.fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.cluster.boot :as boot]
            [seon.cluster.process :as process]
            [seon.cluster.registry :as registry]
            [seon.cluster.source :as source]
            [seon.db :as db]
            [seon.fs :as fs]
            [seon.id :as id]
            [seon.schema.edn :as schema.edn]
            [seon.test-support :as support]))

(def ^:private agent-schemas "resources/seon/schemas/seon.agent.edn")

(defn- without-graph-process
  "The text of `seon.agent.edn` with `:seon.agent/graph-process` undeclared: a program from before it existed."
  {:malli/schema [:=> [:cat :string] :string]}
  [text]
  (->> (str/split-lines text)
       (remove #(str/includes? % "graph-process"))
       (str/join "\n")))

(defn- publish-older-cluster!
  "Publish `candidate` (a clone of this JVM's source lacking `:seon.agent/graph-process`) into `root`'s store
  and fork `cluster-name` from it, as `bin/seon init` does."
  {:malli/schema [:=> [:cat :string :string :string] :seon.source/commit-id]}
  [root candidate cluster-name]
  (binding [schema.edn/*resource-loader*
            (#'cluster/candidate-resources candidate (.getClassLoader (class cluster/refresh-source!)))]
    (let [published (cluster/refresh-source! root [] nil candidate)
          store-dir (:seon.boot/store-dir (cluster/resolve-bootstrap {:seon.boot/root root}))
          held (cluster/acquire-root-store! store-dir)]
      (try
        (let [base (cluster/source-base! held cluster-name)]
          (registry/ensure-cluster! {:seon.store/store held :seon.boot/cluster-name cluster-name
                                     :seon.source/commit-id (:seon.source/commit-id base)
                                     :seon.schema/projection (:seon.schema/projection base)})
          (:seon.source/commit-id published))
        (finally (cluster/release-root-store! store-dir))))))

(deftest ^{:seon.test/long "Two full publications (an older clone, then this checkout's changed schema resource) and one in-JVM boot."
           :seon.test/long-ms 600000}
  a-boot-adopts-a-new-attribute-before-its-first-read
  (let [parent (str (babashka.fs/create-temp-dir {:prefix "seon-boot-order-"}))
        candidate (str (io/file parent "candidate"))
        root (str (io/file parent "root"))
        cluster-name (str "boot-order-" (id/id))]
    (try
      (#'support/clone-directory! (fs/source-directory) candidate)
      ;; a clone of a committed archive keeps its read-only modes
      (let [file (io/file candidate agent-schemas)
            text (without-graph-process (slurp file))]
        (.setWritable file true)
        (spit file text))
      (support/preserving-instrumentation-state
       (fn []
         (let [older (publish-older-cluster! root candidate cluster-name)
               ;; recovery reads `:seon.agent/graph-process` with no check before it
               instance (boot/start! {:seon.boot/root root :seon.boot/cluster-name cluster-name})]
           (try
             (let [connection (:seon.boot/cluster-connection instance)
                   adopted (:seon.source/commit-id (db/pull (db/db connection) [:seon.source/commit-id]
                                                            [:seon.cluster/name cluster-name]))]
               (is (empty? (:seon.boot/missing-layers (boot/readiness instance))))
               (is (not= older adopted) "the boot published this checkout's schema resource")
               (is (= (:seon.source/commit-id (source/current (:seon.store/store instance))) adopted)
                   "the cluster adopted the publication before it stood")
               ;; a graph this boot started names this process; none names a previous one
               (let [observed (db/q '[:find ?agent-id ?process-id
                                      :where
                                      [?agent :seon.agent/graph-process ?process]
                                      [?agent :seon.agent/id ?agent-id]
                                      [?process :seon.db.process/id ?process-id]]
                                    (db/db connection))
                     started (:seon.agent/started @(:seon.agent/routing instance))]
                 (is (every? #(= (cluster/process-identity (process/current-identity)) (second %)) observed)
                     "every graph process value names this process")
                 (is (every? #(contains? started (first %)) observed)
                     "every graph process value names a graph this process runs")))
             (finally (boot/stop! instance))))))
      (finally (fs/delete-recursively! (str (babashka.fs/parent parent)) parent)))))

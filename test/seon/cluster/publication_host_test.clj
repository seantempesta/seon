(ns seon.cluster.publication-host-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.cluster.boot :as boot]
            [seon.cluster.registry :as registry]
            [seon.cluster.source :as source]
            [seon.cluster.store :as store]
            [seon.db :as db]
            [seon.operator :as operator]
            [seon.id :as id]
            [seon.operator.runtime :as runtime]
            [seon.cluster.process :as state]
            [seon.schema :as schema]
            [seon.test-support :as support]))

(deftest ^{:seon.test/long "Publish and boot a real cluster in the test JVM, then send operator requests through its advertised prepl."
           :seon.test/long-ms 1200000}
  live-init-keeps-the-host-process-and-launches-no-jvm
  (let [root (.getCanonicalFile (io/file "tmp" (str "publication-operator-" (id/id))))
        name (str "publication-" (id/id))
        fork-name (str name "-fork")
        repository (.getCanonicalFile (io/file "."))
        records-file (io/file root "subprocesses.edn")
        _ nil]
    (.mkdirs root)
    (try
      (support/preserving-instrumentation-state
       (fn []
         (cluster/refresh-source! (str root "/data/clusters"))
         (let [instance (boot/start! {:seon.boot/root (str root "/data/clusters")
                                         :seon.boot/cluster-name name})
               advertisement (:seon.boot/advertisement instance)
               record (select-keys advertisement [:seon.boot/pid :seon.boot/start-instant])
               before (operator/selected-processes (str root))
               projection-reads (atom 0)
               derive-projection schema/projection-from-database
               published (:seon.source/commit-id (source/current (:seon.store/store instance)))]
           (try
             (is (contains? before record))
             (spit (io/file root "hook.edn")
                   (pr-str (-> (edn/read-string (slurp (io/file repository ".claude/seon-hook.edn")))
                               (assoc-in [:schema-admission :enabled] true)
                               (assoc-in [:current-source :root]
                                         (str (.relativize (.toPath repository) (.toPath root)))))))
             (spit (io/file root "invalid.edn") "{:seon.config/on-core-error :invalid}")
             (with-redefs [schema/projection-from-database
                           (fn [database & arguments]
                             (when (= (registry/cluster-branch fork-name)
                                      (get-in database [:config :branch]))
                               (swap! projection-reads inc))
                             (apply derive-projection database arguments))]
             (doseq [arguments [["init" fork-name]
                                ["init"]
                                ["init" "--changed" "src/my/note.clj"]
                                ["init" "--dev" name "--changed" "src/my/note.clj"]
                                ["config" "apply" name "config/default.edn"]
                                ["status" "--verbose"]
                                ["schema-admission" "resources/seon/schemas/seon.source.edn"]
                                ["config" "apply" name (str root "/invalid.edn")]]]
               (let [program
                     (pr-str
                      `(do
                         (require 'babashka.process 'seon.operator)
                         (let [start# babashka.process/process
                               records# (atom [])]
                           (try
                             (with-redefs [babashka.process/process
                                           (fn [& args#]
                                             (let [record# (apply start# args#)]
                                               (swap! records# conj
                                                      {:seon.operator.subprocess/argv (:cmd record#)
                                                       :seon.boot/pid (.pid (:proc record#))})
                                               record#))]
                               ~(if (= "schema-admission" (first arguments))
                                  `(do
                                     (binding [*in* (java.io.StringReader. "{}")
                                               *out* (java.io.StringWriter.)]
                                       (load-file "bin/seon-hook"))
                                     (let [result# ((resolve (symbol "run-schema-admission"))
                                                    {:seon.hook/file-paths [~(second arguments)]})]
                                       (when-not (= :available (:seon.hook.analysis/status result#))
                                         (throw (ex-info "Hook schema admission failed." result#)))))
                                  `(seon.operator/-main
                                    "--seon-root" ~(str root) ~@arguments)))
                             (finally
                               (spit ~(str records-file) (pr-str @records#)))))))
                     started (System/nanoTime)
                     result (state/run-process!
                             {:seon.operator.subprocess/argv
                              ["bb" "--config" (str (io/file repository "bb.edn"))
                               "--deps-root" (str repository)
                               "--classpath" (str/join java.io.File/pathSeparator
                                                        (map #(str (io/file repository %))
                                                             ["script" "src" "resources"]))
                               "-e" program]
                              :seon.operator.subprocess/directory (str repository)
                              :seon.operator.subprocess/extra-env
                              {"SEON_OPERATOR_EPHEMERAL_OWNER_PID" (str (:seon.boot/pid record))
                               "SEON_HOOK_CONFIG" (str (io/file root "hook.edn"))
                               "SEON_HOOK_STATE_DIR" (str (io/file root "hook-state"))}
                              :seon.operator.subprocess/deadline-ms 1200000
                              :seon.operator.subprocess/merge-error? true})
                     invalid? (= (last arguments) (str root "/invalid.edn"))
                     output (:seon.operator.subprocess/output result)]
                 (println "operator measurement" (pr-str arguments)
                          "elapsed-ms" (quot (- (System/nanoTime) started) 1000000))
                 (is (= (if invalid? 1 0) (:seon.operator.subprocess/exit result)) output)
                 (when invalid?
                   (is (str/includes? output ":seon.error/at") output)
                   (is (str/includes? output ":invalid") output))
                 (when (and (not invalid?) (zero? (:seon.operator.subprocess/exit result)))
                   (let [records (edn/read-string (slurp records-file))]
                     (is (every? #(pos-int? (:seon.boot/pid %)) records))
                     (is (not-any? #(contains? #{"clojure" "java"}
                                               (.getName (io/file (first (:seon.operator.subprocess/argv %)))))
                                   records)
                         (pr-str records))))
                 (is (= before (operator/selected-processes (str root)))))))
             (is (zero? @projection-reads)
                 "An exact source fork carries the existing projection instead of reading the whole program.")
             (let [connection (store/open-branch! (:seon.store/store instance)
                                                  (registry/cluster-branch fork-name))]
               (try
                 (is (= published
                        (:seon.source/commit-id
                         (db/pull (db/db connection) [:seon.source/commit-id]
                                  [:seon.cluster/name fork-name]))))
                 (finally (store/release-branch! connection))))
             (finally
               (boot/stop! (get @runtime/running-instances name instance)))))))
      (finally
        (when-let [instance (get @runtime/running-instances name)]
          (boot/stop! instance))
        (support/delete-recursively! (str root))))))

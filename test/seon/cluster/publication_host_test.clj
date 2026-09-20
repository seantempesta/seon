(ns seon.cluster.publication-host-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.cluster.registry :as registry]
            [seon.cluster.source :as source]
            [seon.fresh-operator :as operator]
            [seon.id :as id]
            [seon.operator.runtime :as runtime]
            [seon.operator.state :as state]
            [seon.test-support :as support]))

(deftest ^{:seon.test/long "Publish and boot a real cluster in the test JVM, then send operator requests through its advertised prepl."
           :seon.test/long-ms 1200000}
  live-init-keeps-the-host-process-and-launches-no-jvm
  (let [root (.getCanonicalFile (io/file "tmp" (str "publication-operator-" (id/id))))
        name (str "publication-" (id/id))
        fork-name (str name "-fork")
        repository (.getCanonicalFile (io/file "."))
        records-file (io/file root "subprocesses.edn")
        generation (random-uuid)]
    (.mkdirs root)
    (try
      (support/preserving-instrumentation-state
       (fn []
         (state/claim-root! repository root (state/current-process-identity) name)
         (cluster/refresh-source! (str root "/data/clusters"))
         (let [instance (cluster/start! {:seon.boot/root (str root "/data/clusters")
                                         :seon.boot/cluster-name name})
               advertisement (:seon.boot/advertisement instance)
               record (merge (select-keys advertisement [:seon.boot/pid :seon.boot/start-instant])
                             {:seon.operator.process-record/generation generation
                              :seon.operator.process-record/root (str root)
                              :seon.operator.process-record/log (str root "/host.log")})
               _ (#'operator/write-process-record! (str root) record)
               before (#'operator/read-process-records (str root))
               published (:seon.source/commit-id (source/current (:seon.store/store instance)))]
           (try
             (is (= [record] (:seon.fresh-operator/process-records before)))
             (spit (io/file root "hook.edn")
                   (pr-str (-> (edn/read-string (slurp (io/file repository ".claude/seon-hook.edn")))
                               (assoc-in [:schema-admission :enabled] true)
                               (assoc-in [:current-source :root]
                                         (str (.relativize (.toPath repository) (.toPath root)))))))
             (spit (io/file root "invalid.edn") "{:seon.config/on-core-error :invalid}")
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
                         (require 'babashka.process 'seon.fresh-operator)
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
                                  `(seon.fresh-operator/-main
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
                              :seon.operator.subprocess/deadline-ms (#'operator/publication-bound-ms)
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
                 (is (= before (#'operator/read-process-records (str root))))))
             (is (= published
                    (registry/branch-commit-id
                     {:seon.store/store (:seon.store/store instance)
                      :seon.store/branch (registry/cluster-branch fork-name)})))
             (finally
               (cluster/stop! (get @runtime/running-instances name instance)))))))
      (finally
        (state/delete-process-claim! repository generation)
        (state/delete-edn! (state/root-claim-path repository root))
        (when-let [instance (get @runtime/running-instances name)]
          (cluster/stop! instance))
        (support/delete-recursively! (str root))))))

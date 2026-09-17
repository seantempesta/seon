(ns ^{:seon.test/platform
      "Reset preflight and recovery are required before the bulk tier."
      :seon.test/long
      "The namespace boots real isolated operator roots."
      :seon.test/long-ms 600000}
    seon.dev.fresh-operator-reset-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.dev.clj-kondo :as dev.kondo]
            [seon.dev.fresh-operator-test :as operator-test]
            [seon.operator.state :as operator.state])
  (:import [java.util Date]))

(defn- fresh-root [] (#'operator-test/fresh-root))
(defn- delete-recursively! [root] (#'operator-test/delete-recursively! root))
(defn- operator-private-outcome [function-name & arguments]
  (let [outcome (apply #'operator-test/operator-private-outcome function-name arguments)]
    (into {} (map (fn [[field value]] [(keyword "seon.dev.fresh-operator-reset-test" (name field)) value])) outcome)))


(def ^:private immediate-refusal-bound-ms 5000)
(def ^:private real-boot-bound-ms 300000)

(deftest lifecycle-holder-evidence-is-immediate-and-stale-records-are-reclaimed
  (let [root (fresh-root)
        path (operator.state/root-lifecycle-lock-path (str root))
        holder-file (str path ".holder.edn")
        started (System/nanoTime)
        acquired (promise)
        release (promise)
        request {:seon.operator.lock/path path
                 :seon.operator.lock/command "publication regression"
                 :seon.operator.lock/acquisition-timeout-ms 1000
                 :seon.operator.lock/hold-timeout-ms immediate-refusal-bound-ms}]
    (try
      (operator.state/write-edn!
       holder-file {:seon.boot/pid 2147483647
                    :seon.boot/start-instant (Date. 0)
                    :seon.operator.lock/command "dead publication"})
      (let [output (with-out-str
                     (is (= :reclaimed
                            (operator.state/with-lifecycle-lock!
                             request (constantly :reclaimed)))))]
        (is (str/includes? output "reclaimed stale lifecycle holder record") output)
        (is (str/includes? output "alive=false") output))
      (let [holder (future
                     (operator.state/with-lifecycle-lock!
                      request #(do (deliver acquired true)
                                   (deref release immediate-refusal-bound-ms :expired))))]
        (try
          (is (= true (deref acquired immediate-refusal-bound-ms :expired)))
          (let [output (java.io.StringWriter.)
                refusal (binding [*out* output]
                          (try
                            (operator.state/with-lifecycle-lock!
                             (assoc request
                                    :seon.operator.lock/acquisition-timeout-ms 10000
                                    :seon.operator.lock/holder-timeout-ms 300)
                             #(throw (ex-info "waiter must not run" {})))
                            (catch clojure.lang.ExceptionInfo failure (ex-data failure))))
                lines (filter #(str/starts-with? % "! waiting")
                              (str/split-lines (str output)))]
            (is (= :seon.operator/lock-acquisition-timeout (:seon.error/kind refusal)))
            (is (= 1 (count lines)) (str output))
            (is (str/includes? (str output) "publication regression"))
            (is (str/includes? (str output) "alive=true"))
            (is (str/includes? (str output) "started")))
          (finally
            (deliver release :done)
            (is (not= :expired (deref holder immediate-refusal-bound-ms :expired))))))
      (is (< (/ (- (System/nanoTime) started) 1000000) immediate-refusal-bound-ms))
      (finally (deliver release :done) (delete-recursively! root)))))

(deftest source-syntax-refuses-before-lock-or-destruction
  (let [root (fresh-root)
        source (fresh-root)
        sentinel (io/file root "data/store/sentinel")
        acquired (promise)
        release (promise)
        holder (future
                 (operator.state/with-lifecycle-lock!
                  {:seon.operator.lock/path (operator.state/root-lifecycle-lock-path (str root))
                   :seon.operator.lock/command "syntax regression holder"
                   :seon.operator.lock/acquisition-timeout-ms immediate-refusal-bound-ms
                   :seon.operator.lock/hold-timeout-ms 30000}
                  #(do (deliver acquired true) (deref release 30000 :expired))))]
    (try
      (doseq [path ["bin/seon" "bb.edn" "deps.edn"
                    ".clj-kondo/config.edn"
                    "script/seon/fresh_operator.clj"
                    "script/seon/dev/clj_kondo.clj" "script/seon/dev/state.clj"
                    "src/seon/operator/state.clj" "src/seon/fs.clj"
                    "src/seon/id.clj" "src/seon/db.clj" ".claude/seon-hook.edn"
                    "config/default.edn"]]
        (let [target (io/file source path)]
          (io/make-parents target)
          (io/copy (io/file @#'operator-test/project-root path) target)))
      (java.nio.file.Files/createSymbolicLink
       (.toPath (io/file source "reference-code"))
       (.toPath (io/file @#'operator-test/project-root "reference-code"))
       (make-array java.nio.file.attribute.FileAttribute 0))
      (java.nio.file.Files/createSymbolicLink
       (.toPath (io/file source "src/seon/test"))
       (.toPath (io/file @#'operator-test/project-root "src/seon/test"))
       (make-array java.nio.file.attribute.FileAttribute 0))
      (let [git-common
            (operator.state/run-process!
             {:seon.operator.subprocess/argv ["git" "rev-parse" "--git-common-dir"]
              :seon.operator.subprocess/directory (str @#'operator-test/project-root)
              :seon.operator.subprocess/deadline-ms immediate-refusal-bound-ms})
            common-file (io/file @#'operator-test/project-root
                                 (str/trim (:seon.operator.subprocess/output git-common)))
            checkout (.getParentFile (.getCanonicalFile common-file))
            result
            (operator.state/run-process!
             {:seon.operator.subprocess/argv
              ["cp" "-R"
               (str (io/file checkout ".clj-kondo/.cache"))
               (str (io/file source ".clj-kondo/.cache"))]
              :seon.operator.subprocess/deadline-ms immediate-refusal-bound-ms})]
        (is (zero? (:seon.operator.subprocess/exit git-common)) (pr-str git-common))
        (is (zero? (:seon.operator.subprocess/exit result)) (pr-str result)))
      (io/make-parents sentinel)
      (spit sentinel "preserved")
      (let [result (operator.state/run-process!
                    {:seon.operator.subprocess/argv ["git" "init" "-q"]
                     :seon.operator.subprocess/directory (str source)
                     :seon.operator.subprocess/deadline-ms immediate-refusal-bound-ms})]
        (is (zero? (:seon.operator.subprocess/exit result)) (pr-str result)))
      (let [result (operator.state/run-process!
                    {:seon.operator.subprocess/argv ["git" "add" "."]
                     :seon.operator.subprocess/directory (str source)
                     :seon.operator.subprocess/deadline-ms immediate-refusal-bound-ms})]
        (is (zero? (:seon.operator.subprocess/exit result)) (pr-str result)))
      (let [result (operator.state/run-process!
                    {:seon.operator.subprocess/argv
                     ["git" "-c" "core.hooksPath=/dev/null" "-c" "commit.gpgsign=false"
                      "-c" "user.name=Test" "-c" "user.email=test@example.invalid"
                      "commit" "-qm" "fixture"]
                     :seon.operator.subprocess/directory (str source)
                     :seon.operator.subprocess/deadline-ms immediate-refusal-bound-ms})]
        (is (zero? (:seon.operator.subprocess/exit result)) (pr-str result)))
      (let [classpath-result
            (operator.state/run-process!
             {:seon.operator.subprocess/argv ["clojure" "-Spath"]
              :seon.operator.subprocess/directory (str source)
              :seon.operator.subprocess/deadline-ms immediate-refusal-bound-ms})
            classpath (str/trim (:seon.operator.subprocess/output classpath-result))
            input-digest ((var-get (ns-resolve 'seon.dev.clj-kondo 'input-digest))
                          (str source) classpath)
            contents ((var-get (ns-resolve 'seon.dev.clj-kondo 'cache-contents))
                      (str source))]
        (is (zero? (:seon.operator.subprocess/exit classpath-result))
            (pr-str classpath-result))
        (operator.state/write-edn!
         (io/file source "tmp/test-changed/dependency-cache.edn")
         {::dev.kondo/input-digest input-digest
          ::dev.kondo/contents contents}))
      (is (= true (deref acquired immediate-refusal-bound-ms :expired)))
      (spit (io/file source "src/seon/db.clj") "\n)\n" :append true)
      (doseq [arguments [["reset" "--force"] ["start"] ["init"]]]
        (let [started (System/nanoTime)
              result (operator.state/run-process!
                      {:seon.operator.subprocess/argv
                       (into ["bash" "bin/seon" "--root" (str root)] arguments)
                       :seon.operator.subprocess/directory (str source)
                       :seon.operator.subprocess/deadline-ms immediate-refusal-bound-ms
                       :seon.operator.subprocess/merge-error? true})
              output (:seon.operator.subprocess/output result)
              elapsed (/ (- (System/nanoTime) started) 1000000)]
          (println "syntax refusal" arguments "elapsed-ms=" elapsed)
          (is (= 1 (:seon.operator.subprocess/exit result)) output)
          (is (str/includes? output "src/seon/db.clj:") output)
          (is (str/includes? output "phase=preflight failed; output="))
          (is (not (str/includes? output "! waiting")))
          (is (not (str/includes? output "phase=down")))
          (is (= "preserved" (slurp sentinel)))
          (when (= "reset" (first arguments))
            (is (str/includes? output "store NOT destroyed (9 bytes remain)")))
          (is (< elapsed immediate-refusal-bound-ms))))
      (io/copy (io/file @#'operator-test/project-root "src/seon/db.clj")
               (io/file source "src/seon/db.clj"))
      (let [broken (io/file source "test/seon/preflight_broken_test.clj")]
        (io/make-parents broken)
        (spit broken
              "(ns seon.preflight-broken-test)\n(def observed (d/listen! nil nil))\n")
        (let [result (operator.state/run-process!
                      {:seon.operator.subprocess/argv
                       ["bash" "bin/seon" "--root" (str root) "reset" "--force"]
                       :seon.operator.subprocess/directory (str source)
                       :seon.operator.subprocess/deadline-ms immediate-refusal-bound-ms
                       :seon.operator.subprocess/merge-error? true})
              output (:seon.operator.subprocess/output result)]
          (is (= 1 (:seon.operator.subprocess/exit result)) output)
          (is (str/includes? output "test/seon/preflight_broken_test.clj:") output)
          (is (str/includes? output "Unresolved namespace d") output)
          (is (not (str/includes? output "! waiting")) output)
          (is (not (str/includes? output "phase=down")) output)
          (is (= "preserved" (slurp sentinel))))
        (.delete broken)
        (let [baseline
              (:seon.fresh-operator/source-snapshot
               ((var-get (ns-resolve 'seon.fresh-operator 'source-preflight!))
                source))]
          (spit broken
                "(ns seon.preflight-broken-test)\n(def observed (d/listen! nil nil))\n")
          (let [outcome
                (operator-private-outcome
                 'phase! (str root) "reset" :start
                 #((var-get (ns-resolve 'seon.fresh-operator 'source-preflight!))
                   source baseline))]
            (is (= :start
                   (:seon.fresh-operator/phase (::data outcome)))
                (pr-str outcome))
            (is (str/includes? (::message outcome)
                               "test/seon/preflight_broken_test.clj")
                (pr-str outcome)))
          (.delete broken)
          (is (map?
               ((var-get (ns-resolve 'seon.fresh-operator 'source-preflight!))
                source baseline)))))
      (deliver release :done)
      (is (not= :expired (deref holder immediate-refusal-bound-ms :expired)))
      (let [form `(do
                    (require 'seon.operator.state)
                    (assert (nil? (find-ns 'seon.db)))
                    (seon.operator.state/with-lifecycle-lock!
                     {:seon.operator.lock/path ~(str (operator.state/root-lifecycle-lock-path (str root)))
                      :seon.operator.lock/command "broken-source cleanup regression"
                      :seon.operator.lock/acquisition-timeout-ms ~immediate-refusal-bound-ms
                      :seon.operator.lock/hold-timeout-ms ~immediate-refusal-bound-ms}
                     (fn []
                       (seon.operator.state/cleanup-root-under-lock!
                        ~(str source) ~(str root) ~(str root)))))
            result (operator.state/run-process!
                    {:seon.operator.subprocess/argv
                     ["bb" "--classpath" "script:src:resources" "-e" (pr-str form)]
                     :seon.operator.subprocess/directory (str source)
                     :seon.operator.subprocess/deadline-ms immediate-refusal-bound-ms
                     :seon.operator.subprocess/merge-error? true})]
        (is (zero? (:seon.operator.subprocess/exit result)) (pr-str result))
        (is (not (.exists sentinel))))
      (finally
        (deliver release :done)
        (deref holder immediate-refusal-bound-ms :expired)
        (delete-recursively! source)
        (delete-recursively! root)))))

(deftest cluster-boot-omits-test-namespaces-and-in-process-run-loads-one
  (let [root (fresh-root)
        project-root @#'operator-test/project-root
        broken (io/file project-root "test/seon/boot_unloadable_test.clj")
        runnable (io/file project-root "test/seon/boot_runner_smoke_test.clj")]
    (try
      (let [cache (dev.kondo/ensure-dependency-cache! (str project-root))]
        (is (#{:current :warmed} (::dev.kondo/status cache)) (pr-str cache)))
      (spit broken
            (str "(ns seon.boot-unloadable-test)\n"
                 "(throw (ex-info \"test namespace loaded during boot\" {}))\n"))
      (spit runnable
            (str "(ns seon.boot-runner-smoke-test\n"
                 "  (:require [clojure.test :refer [deftest is]]))\n"
                 "(deftest indexed-smoke (is (= 4 (+ 2 2))))\n"))
      (operator.state/with-lifecycle-lock!
       {:seon.operator.lock/path
        (operator.state/root-lifecycle-lock-path (str root))
        :seon.operator.lock/command "boot without test namespaces"
        :seon.operator.lock/acquisition-timeout-ms real-boot-bound-ms
        :seon.operator.lock/hold-timeout-ms real-boot-bound-ms}
       (fn []
         (doseq [[function-name arguments]
                 [['init! []] ['init! ["default"]]
                  ['start! ["default"]]]]
           (let [outcome (operator-private-outcome
                          function-name (str root) arguments)]
             (is (nil? (::message outcome)) (pr-str outcome))
             (when (::message outcome)
               (throw (ex-info "The isolated operator phase refused."
                               (::data outcome))))))))
      (let [advertisement
            (edn/read-string
             (slurp (io/file root "data/clusters/default/prepl.edn")))
            dependency-cache
            ((var-get (ns-resolve 'seon.fresh-operator
                                  'ensure-dependency-cache!)))
            test-classpath (:seon.dev-cache/test-classpath dependency-cache)
            form
            (pr-str
             `(do
                (require 'seon.db 'seon.operator 'seon.schema 'seon.sci.eval
                         'seon.test 'seon.test.runner)
                (let [connection# (seon.operator/connection "default")
                      database# (seon.db/db connection#)
                      loader# (seon.test/test-loader ~test-classpath)
                      projection# (seon.schema/projection-from-database database#)
                      ctx# ((deref (ns-resolve 'seon.test
                                               (symbol "with-test-loader")))
                            loader#
                            (fn [] (seon.sci.eval/cluster-ctx database# connection#)))
                      test-var#
                      (seon.test/resolve-test
                       {:seon.db/db database#
                        :seon.db/connection connection#
                        :seon.test/identity
                        'seon.boot-runner-smoke-test/indexed-smoke
                        :seon.sci.eval/ctx ctx#
                        :seon.test/class-loader loader#
                        :seon.schema/projection projection#})
                      provenance# (seon.test.runner/provenance database#)
                      result#
                      (if (:seon.error/kind test-var#)
                        test-var#
                        (seon.test/run
                         test-var# connection#
                         {:seon.db/db database#
                          :seon.db/connection connection#
                          :seon.sci.eval/ctx ctx#
                          :seon.test.run/provenance provenance#
                          :seon.test/remaining-ms 60000}))]
                  (select-keys result#
                               [:seon.error/kind :seon.test/sym
                                :seon.test/pass-count :seon.test/fail-count
                                :seon.test/error-count]))))
            outcome
            (operator-private-outcome 'prepl-eval! advertisement form 60000)
            _ (is (nil? (::message outcome)) (pr-str outcome))
            _ (when (::message outcome)
                (throw (ex-info "The in-process test run refused."
                                (::data outcome))))
            result (edn/read-string (:val (last (::value outcome))))]
        (is (= {:seon.test/sym
                'seon.boot-runner-smoke-test/indexed-smoke
                :seon.test/pass-count 1
                :seon.test/fail-count 0
                :seon.test/error-count 0}
               result)
            (pr-str result)))
      (finally
        (operator-private-outcome 'down! (str root) ["--force"])
        (.delete broken)
        (.delete runnable)
        (delete-recursively! root)))))

(deftest reset-phase-failure-stops-the-command-and-retains-evidence
  (let [root (fresh-root)
        phases [:down :destroy :republish :refork :start :adopt]]
    (try
      (doseq [failed phases]
        (let [started (System/nanoTime)
              calls (atom [])
              operation (fn [phase]
                          (swap! calls conj phase)
                          (when (= failed phase)
                            (throw (ex-info "planted phase failure" {:seon.test/phase phase}))))
              outcome
              (with-redefs-fn
                {#'operator.state/reclaim-invalid-claims! (constantly 0)
                 (ns-resolve 'seon.fresh-operator 'down-recorded-processes!)
                 (fn [& _] (operation :down))
                 (ns-resolve 'seon.fresh-operator 'reconcile-process-records!)
                 (fn [& _] {:seon.fresh-operator/process-records []})
                 (ns-resolve 'seon.fresh-operator 'cleanup-managed-root!)
                 (fn [& _] (operation :destroy) {:seon.operator.cleanup/removed-file-bytes 0})
                 (ns-resolve 'seon.fresh-operator 'init!)
                 (fn [_ args]
                   (operation (case args [] :republish ["default"] :refork
                                    ["--dev" "default"] :adopt)))
                 (ns-resolve 'seon.fresh-operator 'start!)
                 (fn [& _] (operation :start))}
                #(operator-private-outcome 'reset! (str root) ["--force"]))
              data (::data outcome)]
          (is (= failed (:seon.fresh-operator/phase data)) (pr-str outcome))
          (is (= (conj (vec (take-while #(not= failed %) phases)) failed) @calls))
          (is (str/includes? (::message outcome) (str "phase=" (name failed))))
          (is (str/includes? (slurp (:seon.fresh-operator/log data)) "planted phase failure"))
          (is (< (/ (- (System/nanoTime) started) 1000000) immediate-refusal-bound-ms))))
      (finally (delete-recursively! root)))))

(deftest reset-phase-records-derive-incomplete-status-and-continuation
  (let [root (fresh-root)
        operations (io/file root "data/operator/operations")
        operation-id "4242"]
    (try
      (.mkdirs operations)
      (doseq [phase [:lifecycle :down :destroy :republish :refork :start]]
        (spit (io/file operations (str "reset-" (name phase) "-" operation-id ".log"))
              (if (= :start phase)
                "phase=start started\nplanted failure\n"
                (str "phase=" (name phase) " started\nphase=" (name phase) " complete\n"))))
      (is (= :start
             ((var-get (ns-resolve 'seon.fresh-operator 'reset-incomplete-phase))
              (str root))))
      (is (= ["bin/seon start default" "bin/seon init --dev default"]
             ((var-get (ns-resolve 'seon.fresh-operator 'reset-continuation))
              :start)))
      (let [output
            (with-out-str
              ((var-get (ns-resolve 'seon.fresh-operator
                                    'print-reset-continuation!)) :start))]
        (is (= (str "reset continuation:\n"
                    "bin/seon start default\n"
                    "bin/seon init --dev default\n")
               output)))
      (let [output
            (with-out-str
              ((var-get (ns-resolve 'seon.fresh-operator 'status!))
               (str root) []))]
        (is (str/includes? output "reset incomplete at start") output))
      ; A later reset refusal before destroy must not hide the prior gap.
      (let [path (io/file operations "reset-lifecycle-4343.log")]
        (spit path "phase=lifecycle started\n")
        (java.nio.file.Files/setLastModifiedTime
         (.toPath path)
         (java.nio.file.attribute.FileTime/fromMillis
          (+ 1000 (System/currentTimeMillis)))))
      (is (= :start
             ((var-get (ns-resolve 'seon.fresh-operator 'reset-incomplete-phase))
              (str root))))
      (let [path (io/file operations "start-start-5252.log")]
        (spit path "phase=start started\nphase=start complete\n")
        (java.nio.file.Files/setLastModifiedTime
         (.toPath path)
         (java.nio.file.attribute.FileTime/fromMillis
          (+ 2000 (System/currentTimeMillis)))))
      (is (nil?
           ((var-get (ns-resolve 'seon.fresh-operator 'reset-incomplete-phase))
            (str root))))
      (let [output
            (with-out-str
              ((var-get (ns-resolve 'seon.fresh-operator 'status!))
               (str root) []))]
        (is (not (str/includes? output "reset incomplete at")) output))
      (finally (delete-recursively! root)))))

(deftest managed-root-cleanup-loads-no-program-and-never-follows-symlinks
  (let [root (fresh-root)
        outside (fresh-root)
        sentinel (io/file outside "sentinel")
        store-dir (io/file root "data/store")]
    (try
      (.mkdirs store-dir)
      (spit sentinel "outside")
      (spit (io/file store-dir "inside") "inside")
      (java.nio.file.Files/createSymbolicLink
       (.toPath (io/file store-dir "link")) (.toPath outside)
       (make-array java.nio.file.attribute.FileAttribute 0))
      (let [result (operator.state/cleanup-root-under-lock!
                    (str root) (str root) (str root))]
        (is (:seon.operator.cleanup/complete? result))
        (is (not (.exists store-dir)))
        (is (= "outside" (slurp sentinel))))
      (finally (delete-recursively! root) (delete-recursively! outside)))))

(deftest reset-census-and-stale-repair-name-the-source-process-and-log
  (let [root (fresh-root)
        source (io/file root "src/seon/db.clj")
        log (io/file root "logs/default/seon.log")
        advertisement (io/file root "data/clusters/default/prepl.edn")
        process-identity (operator.state/current-process-identity)
        record (assoc process-identity
                      :seon.operator.process-record/generation (random-uuid)
                      :seon.operator.process-record/repository-root (str root)
                      :seon.operator.process-record/log (str log))]
    (try
      (io/make-parents source)
      (spit source ")")
      (let [output (with-out-str
                     (operator-private-outcome 'print-process-record-census!
                                               (str root) [record] []))]
        (is (str/includes? output (str "pid=" (:seon.boot/pid process-identity))))
        (is (str/includes? output "state=alive"))
        (is (str/includes? output (str "source=" root)))
        (is (str/includes? output (str "log=" log))))
      (operator.state/write-edn!
       (str advertisement) {:seon.boot/pid 2147483647
                            :seon.boot/start-instant (Date. 0)})
      (let [output (with-out-str
                     (operator-private-outcome 'clear-stale-advertisements! (str root)))]
        (is (str/includes? output "removed stale advertisement; pid=2147483647 alive=false; log="))
        (is (not (.exists advertisement))))
      (finally (delete-recursively! root)))))

(deftest a-child-deadline-keeps-output-and-stops-at-its-phase
  (let [root (fresh-root)
        started (System/nanoTime)]
    (try
      (let [result
            (with-redefs-fn
              {(ns-resolve 'seon.fresh-operator 'child-jvm-command)
               (constantly ["python3" "-c"
                            "import os,time\nos.write(1,b':before-timeout\\n')\ntime.sleep(10)"])}
              #(operator-private-outcome
                'phase! (str root) "reset" :republish
                (fn []
                  ((ns-resolve 'seon.fresh-operator 'run-child-jvm!)
                   {:seon.fresh-operator/root (str root)
                    :seon.fresh-operator/deadline-ms 300}))))
            data (::data result)]
        (is (= :republish (:seon.fresh-operator/phase data)))
        (is (true? (:seon.operator.subprocess/reaped? data)))
        (is (str/includes? (slurp (:seon.fresh-operator/child-output data)) ":before-timeout"))
        (is (str/includes? (slurp (:seon.fresh-operator/log data)) "child output="))
        (is (< (/ (- (System/nanoTime) started) 1000000) immediate-refusal-bound-ms)))
      (finally (delete-recursively! root)))))

(deftest phase-duration-is-visible-on-success-and-refusal
  (let [root (fresh-root)]
    (try
      (doseq [refuse? [false true]]
        (let [output (with-out-str
                       (operator-private-outcome
                        'phase! (str root) "reset" :down
                        #(if refuse?
                           (throw (ex-info "duration refusal" {}))
                           :completed)))
              prefix "● reset phase=down elapsed-ms="
              line (first (filter #(str/starts-with? % prefix)
                                  (str/split-lines output)))
              separator (when line (str/index-of line " log="))
              elapsed (when separator (parse-long (subs line (count prefix) separator)))]
          (is (some? elapsed) output)
          (is (and (number? elapsed) (<= 0 elapsed)
                   (< elapsed immediate-refusal-bound-ms)) output)
          (when separator
            (is (str/includes? (slurp (subs line (+ separator 5)))
                               "phase=down elapsed-ms=")))))
      (finally (delete-recursively! root)))))

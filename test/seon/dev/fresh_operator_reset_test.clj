(ns seon.dev.fresh-operator-reset-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.dev.fresh-operator-test :as operator-test]
            [seon.operator.state :as operator.state])
  (:import [java.util Date]))

(defn- fresh-root [] (#'operator-test/fresh-root))
(defn- delete-recursively! [root] (#'operator-test/delete-recursively! root))
(defn- operator-private-outcome [function-name & arguments]
  (let [outcome (apply #'operator-test/operator-private-outcome function-name arguments)]
    (into {} (map (fn [[field value]] [(keyword "seon.dev.fresh-operator-reset-test" (name field)) value])) outcome)))


(def ^:private immediate-refusal-bound-ms 5000)

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
      (doseq [path ["bin/seon" "bb.edn" "script/seon/fresh_operator.clj"
                    "script/seon/dev/clj_kondo.clj" "script/seon/dev/state.clj"
                    "resources/seon/operator/state.clj" "src/seon/fs.clj"
                    "src/seon/id.clj" "src/seon/db.clj" ".claude/seon-hook.edn"
                    "config/default.edn"]]
        (let [target (io/file source path)]
          (io/make-parents target)
          (io/copy (io/file @#'operator-test/project-root path) target)))
      (java.nio.file.Files/createSymbolicLink
       (.toPath (io/file source "reference-code"))
       (.toPath (io/file @#'operator-test/project-root "reference-code"))
       (make-array java.nio.file.attribute.FileAttribute 0))
      (spit (io/file source "src/seon/db.clj") "\n)\n" :append true)
      (io/make-parents sentinel)
      (spit sentinel "preserved")
      (let [result (operator.state/run-process!
                    {:seon.operator.subprocess/argv ["git" "init" "-q"]
                     :seon.operator.subprocess/directory (str source)
                     :seon.operator.subprocess/deadline-ms immediate-refusal-bound-ms})]
        (is (zero? (:seon.operator.subprocess/exit result)) (pr-str result)))
      ; An empty commit makes every copied source an untracked preflight input.
      (let [result (operator.state/run-process!
                    {:seon.operator.subprocess/argv
                     ["git" "-c" "core.hooksPath=/dev/null" "-c" "commit.gpgsign=false"
                      "-c" "user.name=Test" "-c" "user.email=test@example.invalid"
                      "commit" "--allow-empty" "-qm" "fixture"]
                     :seon.operator.subprocess/directory (str source)
                     :seon.operator.subprocess/deadline-ms immediate-refusal-bound-ms})]
        (is (zero? (:seon.operator.subprocess/exit result)) (pr-str result)))
      (is (= true (deref acquired immediate-refusal-bound-ms :expired)))
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

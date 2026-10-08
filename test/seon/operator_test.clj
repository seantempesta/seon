(ns seon.operator-test
  (:require [clojure.core.server :as server]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.operator :as operator]
            [seon.test-support :as test-support]))

(deftest operator-reads-refusals-that-carry-vars-and-objects
  ;; A refusal keeps its proposed transaction, so the prepl prints `#'v` and
  ;; `#object[...]`, which EDN cannot read; the refusal must still arrive whole.
  (let [server-name "operator-prepl-value"
        socket (server/start-server {:name server-name :port 0
                                     :accept 'clojure.core.server/io-prepl})
        refusal (pr-str
                 `{:seon.error/message "Write refused."
                   :seon.error/at #inst "2026-09-24T19:43:22.760-00:00"
                   :seon.id #uuid "5f1c2c1e-0000-4000-8000-000000000000"
                   :seon.db.write.attempt/transaction
                   [[:db.fn/call (var clojure.core/identity) (Object.)]]})
        run (fn [form]
              (try (#'operator/prepl-value!
                    {:seon.boot/prepl-host "127.0.0.1"
                     :seon.boot/prepl-port (.getLocalPort socket)}
                    form (* 1000 test-support/event-backstop-seconds))
                   (catch clojure.lang.ExceptionInfo failure failure)))]
    (try
      (let [value (run refusal)
            [[_ var-form object]] (:seon.db.write.attempt/transaction value)
            thrown (run (str "(throw (ex-info \"Write refused.\" " refusal "))"))]
        (is (= "Write refused." (:seon.error/message value)))
        (is (inst? (:seon.error/at value)))
        (is (uuid? (:seon.id value)))
        (is (= '(var clojure.core/identity) var-form))
        (is (= 'object (:tag object)))
        (is (= "Write refused." (ex-message thrown)))
        (is (nil? (:seon.operator/reader-error (ex-data thrown))))
        (is (map? (get-in (ex-data thrown) [:seon.error/offending :val]))))
      (finally (server/stop-server server-name)))))

(deftest a-failed-boot-result-line-arrives-whole
  ;; A failed boot writes `{:seon.error/message … :seon.error/cause
  ;; (Throwable->map cause)}` on the child's boot channel. An armed contract's
  ;; refusal carries its `[:fn … #'predicate]` explanation, which EDN rejects
  ;; ("No dispatch macro for: '") and so hid the boot's own cause (2026-09-26).
  (let [refusal (ex-info "seon.config/effective-in refused return value."
                         {:seon.error/expected [:fn #'clojure.core/map?]
                          :seon.error/offending (Object.)})
        line (pr-str {:seon.error/message (ex-message refusal)
                      :seon.error/cause (Throwable->map refusal)})
        value (#'operator/read-prepl-val line)]
    (is (thrown? RuntimeException (edn/read-string {:default tagged-literal} line)))
    (is (= "seon.config/effective-in refused return value." (:seon.error/message value)))
    (is (= '(var clojure.core/map?)
           (second (get-in value [:seon.error/cause :data :seon.error/expected]))))))

(deftest heap-dumps-are-read-from-the-managed-roots-heap-directory
  ;; The 2026-09-26 OOM dump was written under the pruned program archive
  ;; (a relative -XX:HeapDumpPath) and lost on the restart that recovered.
  ;; The operator names <root>/data/heap at launch and reports its dumps.
  (let [root (.getCanonicalFile (java.io.File. (str "tmp/operator-heap-test-" (random-uuid))))
        heap (java.io.File. root "data/heap")
        dump (fn [filename modified]
               (let [file (java.io.File. heap filename)]
                 (spit file "")
                 (.setLastModified file modified)
                 (.getCanonicalPath file)))]
    (try
      (is (= [] (#'operator/heap-dumps (str root))) "no directory, no dumps")
      (.mkdirs heap)
      (let [older (dump "java_pid1.hprof" 1000)
            newer (dump "java_pid2.hprof" 2000)]
        (dump "notes.txt" 3000)
        (is (= [newer older] (#'operator/heap-dumps (str root)))))
      (finally (test-support/delete-recursively! root)))))

(deftest a-class-directory-a-live-process-names-is-never-pruned
  ;; Two roots starting at once (2026-09-28): one start's prune deleted the
  ;; class directory the other JVM was loading from (NoSuchFileException).
  (let [repository (str (java.nio.file.Files/createTempDirectory
                         "seon-prune" (make-array java.nio.file.attribute.FileAttribute 0)))
        classes (java.io.File. repository "target/dev-program-classes")
        directory (fn [name age-ms]
                    (doto (java.io.File. classes name) .mkdirs
                      (.setLastModified (- (System/currentTimeMillis) age-ms))))
        program (directory "program" 0)
        held (directory "held-oldest" 900000)
        _ (doseq [i (range 4)] (directory (str "recent-" i) (* 1000 (inc i))))
        ;; Held as a launched JVM holds it: on its classpath. (macOS hides a
        ;; platform binary's arguments, so the holder is this JVM's java.)
        java (str (System/getProperty "java.home") "/bin/java")
        holder (.start (ProcessBuilder. ^java.util.List
                                        [java "-cp" (str (.getCanonicalPath held) ":" (System/getProperty "java.class.path"))
                                         "clojure.main" "-e" "(Thread/sleep 30000)"]))]
    (try
      ;; Its arguments are readable once the process is up.
      (loop [tries 50]
        (when (and (pos? tries)
                   (not (some #(.contains ^String % (.getCanonicalPath held)) (#'operator/live-arguments))))
          (Thread/sleep 100)
          (recur (dec tries))))
      (#'operator/prune-program-classes! repository (.getCanonicalPath program))
      (is (.isDirectory held) "the directory a live process names survives")
      (is (.isDirectory program))
      (is (= 3 (count (filter #(.startsWith (.getName ^java.io.File %) "recent-") (.listFiles classes))))
          "the most recently used unheld directories are kept, the rest pruned")
      (finally
        (.destroy holder)
        (doseq [file (reverse (file-seq (java.io.File. repository)))] (.delete ^java.io.File file))))))

(deftest check-names-the-tests-its-throwaway-jvm-runs
  ;; `check PATH --test NS --test NS/NAME` carries both test names in order;
  ;; the runner receives a namespace as --ns and a test as --test.
  (let [root (System/getProperty "java.io.tmpdir")
        request (operator/parse-argv ["--seon-root" root "check" "src/seon/operator.clj"
                                      "--test" "seon.error.refusal-test"
                                      "--test" "seon.operator-test/check-names-the-tests-its-throwaway-jvm-runs"])
        refused (try (operator/parse-argv ["--seon-root" root "status" "--test" "seon.operator-test"])
                     (catch clojure.lang.ExceptionInfo refusal (ex-data refusal)))]
    (is (= ["seon.error.refusal-test" "seon.operator-test/check-names-the-tests-its-throwaway-jvm-runs"]
           (:seon.operator/tests request)))
    (is (= 1 (count (:seon.operator/paths request))))
    (is (= ["--ns" "seon.error.refusal-test"
            "--test" "seon.operator-test/check-names-the-tests-its-throwaway-jvm-runs"]
           (#'operator/test-check-arguments (:seon.operator/tests request) false)))
    (is (= :refused (:seon.operator/disposition refused)))))

(deftest a-successful-start-reply-carries-counts-not-branch-heads
  ;; `start --head` printed ~10 KB, mostly one entry per captured branch head.
  (let [heads (into {} (map (fn [n] [(str "agent-" n) (str "commit-" n)])) (range 110))
        result {:seon.operator/source {:seon.source/git-sha "abc123"}
                :seon.operator/phases {:seon.operator/total-ms 7000}
                :seon.boot/readiness {:seon.boot/pid 42 :seon.boot/ready-ms 5000}
                :seon.operator/stopped-processes [{:seon.boot/pid 41}]
                :seon.operator/adoption {:converged true}
                :seon.operator/archives {:seon.operator/pruned ["a" "b"]}
                :seon.operator/captured-heads {:seon.operator/heads heads}}
        summary (#'operator/start-summary result "/logs/operator-start.edn")]
    (is (= 110 (:seon.operator/captured-heads-count summary)))
    (is (= 2 (:seon.operator/pruned-archives-count summary)))
    (is (= [42 "abc123" true] ((juxt :seon.boot/pid :seon.source/git-sha :seon.operator/adopted?) summary)))
    (is (not (contains? summary :seon.operator/captured-heads)))))

(deftest a-launched-jvm-carries-the-configured-heap-cap
  (let [xmx (fn [request] (filterv #(str/starts-with? % "-J-Xmx")
                                   (operator/launch-argv request "/src" nil "/heap" "nil")))]
    (testing "the shipped default caps every launch"
      (is (= [(str "-J-Xmx" (:seon.config.operator/heap-max-bytes @operator/shipped-default-decisions))]
             (xmx {:seon.operator/managed-root "/root"}))))
    (testing "a manifest's dial replaces it"
      (is (= ["-J-Xmx1073741824"]
             (xmx {:seon.operator/managed-root "/root"
                   :seon.config/manifest {:seon.config.operator/heap-max-bytes 1073741824}}))))))

(deftest check-slots-are-granted-in-ticket-order
  ;; One slot, a holder, then three waiters filed 150 ms apart (the first behind
  ;; a ticket whose process is dead): they must acquire in filing order.
  (let [root (str (.getCanonicalFile (java.io.File. (str "tmp/operator-check-test-" (random-uuid)))))
        pid (.pid (java.lang.ProcessHandle/current))
        dead (let [p (.start (ProcessBuilder. ["true"]))] (.waitFor p) (.pid p))
        order (atom [])
        hold (#'operator/check-slot! root 20000 1 pid)
        waiter (fn [label]
                 (future (let [slot (#'operator/check-slot! root 20000 1 pid)]
                           (swap! order conj label)
                           (Thread/sleep 100)
                           (.close (:seon.operator/channel slot)))))]
    (try
      (spit (doto (java.io.File. root (format "data/check/queue/%015d-%d" 1 dead)) (-> .getParentFile .mkdirs)) "")
      (let [futures (vec (for [label [:a :b :c]]
                           (do (Thread/sleep 150) (waiter label))))]
        (.close (:seon.operator/channel hold))
        (run! deref futures)
        (is (= [:a :b :c] @order))
        (is (empty? (.listFiles (java.io.File. root "data/check/queue")))
            "granted tickets and the dead one are gone"))
      (finally (run! #(.delete ^java.io.File %) (reverse (file-seq (java.io.File. root))))))))

(deftest the-check-slot-count-is-a-dial
  ;; Resolved at run time: the rehearsal analyzes the running JVM's script/,
  ;; which lacks a var this commit adds (see c62a1d6b1).
  (let [slots (requiring-resolve 'seon.operator/operator-check-slots)]
    (is (= 2 (slots nil)))
    (is (= 1 (slots {:seon.config.operator/check-slots 1})))))

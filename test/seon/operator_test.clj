(ns seon.operator-test
  (:require [babashka.fs :as fs]
            [clojure.core.server :as server]
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

(deftest check-names-the-tests-its-throwaway-jvm-runs
  ;; `check PATH --test NS --test NS/NAME` carries both test names in order;
  ;; the runner receives a namespace as --ns and a test as --test.
  (let [root (System/getProperty "java.io.tmpdir")
        request (operator/parse-argv ["--seon-root" root "check" root
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

(deftest check-reads-a-deleted-tracked-path-as-a-path
  ;; `bin/lane commit --test T PATH` for a deletion hands `check` a path that is
  ;; gone from the working tree; only a name no index tracks is a stray test name.
  (let [repository (str (java.nio.file.Files/createTempDirectory
                         "seon-stray" (make-array java.nio.file.attribute.FileAttribute 0)))
        git (fn [& args] (#'operator/command! (into ["git" "-c" "user.name=t" "-c" "user.email=t@t"] args) repository 30000))]
    (try
      (git "init" "-q")
      (spit (java.io.File. repository "gone.txt") "x")
      (spit (java.io.File. repository "kept.txt") "x")
      (git "add" "-A")
      (git "commit" "-q" "-m" "c")
      (.delete (java.io.File. repository "gone.txt"))
      (is (= "seon.operator-test/a-test" (operator/stray-positional repository ["gone.txt" "seon.operator-test/a-test"]))
          "a deleted tracked path is no stray; a test name is")
      (is (nil? (operator/stray-positional repository ["gone.txt"])))
      (finally
        (doseq [file (reverse (file-seq (java.io.File. repository)))] (.delete ^java.io.File file))))))

(deftest check-at-a-revision-names-the-tests-without-paths
  (let [root (System/getProperty "java.io.tmpdir")
        request (operator/parse-argv ["--seon-root" root "check" "--at" "HEAD~1"
                                      "--test" "seon.operator-test/check-at-a-revision-names-the-tests-without-paths"])
        with-path (try (operator/parse-argv ["--seon-root" root "check" "--at" "HEAD~1" root])
                       (catch clojure.lang.ExceptionInfo refusal (ex-data refusal)))]
    (is (= "HEAD~1" (:seon.operator/at request)))
    (is (= 1 (count (:seon.operator/tests request))))
    (is (= [] (:seon.operator/paths request)))
    (is (some? with-path) "a path beside --at is refused")))

(deftest a-successful-start-reply-carries-counts-not-branch-heads
  ;; `start --head` printed ~10 KB, mostly one entry per captured branch head.
  (let [heads (into {} (map (fn [n] [(str "agent-" n) (str "commit-" n)])) (range 110))
        result {:seon.operator/source {:seon.source/git-sha "abc123"}
                :seon.operator/phases {:seon.operator/total-ms 7000}
                :seon.boot/readiness {:seon.boot/pid 42 :seon.boot/ready-ms 5000}
                :seon.operator/stopped-processes [{:seon.boot/pid 41}]
                :seon.operator/archives {:seon.operator/pruned ["a" "b"]}
                :seon.operator/captured-heads {:seon.operator/heads heads}}
        summary (#'operator/start-summary result "/logs/operator-start.edn")]
    (is (= 110 (:seon.operator/captured-heads-count summary)))
    (is (= 2 (:seon.operator/pruned-archives-count summary)))
    (is (= [42 "abc123"] ((juxt :seon.boot/pid :seon.source/git-sha) summary)))
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

(deftest a-launched-jvm-writes-its-crash-forensics-beside-its-heap-dump
  (let [argv (operator/launch-argv {:seon.operator/managed-root "/root"} "/src" nil "/heap" "nil")]
    (is (= #{"-J-XX:HeapDumpPath=/heap" "-J-XX:FlightRecorderOptions=dumppath=/heap"
             "-J-XX:ErrorFile=/heap/hs_err_pid%p.log"}
           (into #{} (filter #(or (str/includes? % "HeapDumpPath") (str/includes? % "dumppath")
                                  (str/includes? % "ErrorFile")))
                 argv)))))

(deftest a-launched-jvm-binds-the-agents-working-root-to-the-checkout
  (let [root (fn [request] (get-in (operator/boot-request request "/checkout")
                                   [:seon.config/environment :seon.config.fs/working-root]))]
    (testing "the checkout is the working root, not the archive the JVM runs in"
      (is (= "/checkout" (root {:seon.operator/managed-root "/root" :seon.operator/command :start}))))
    (testing "a request that decides the dial keeps its decision"
      (is (= "/archive" (root {:seon.operator/managed-root "/root" :seon.operator/command :start
                               :seon.config/environment {:seon.config.fs/working-root "/archive"}})))
      (is (nil? (root {:seon.operator/managed-root "/root" :seon.operator/command :start
                       :seon.config/manifest {:seon.config.fs/working-root "/elsewhere"}}))))))

(deftest a-throwaway-jvm-never-takes-the-roots-derived-web-port
  ;; A check's throwaway JVM boots a cluster named "default", whose derived port
  ;; is the root's bookmark (7994). Booted in the root's restart window it held
  ;; that port, and the root's new JVM served on an ephemeral one for its whole life.
  (let [port (fn [request] (find (:seon.config/environment (operator/boot-request request "/checkout"))
                                 :seon.config.web/port))]
    (testing "a throwaway JVM's view binds a port the operating system chooses"
      (is (= [:seon.config.web/port 0]
             (port {:seon.operator/managed-root "/check" :seon.operator/command :start
                    :seon.operator/throwaway? true}))))
    (testing "the root's own JVM keeps its derived port"
      (is (nil? (port {:seon.operator/managed-root "/root" :seon.operator/command :start}))))))

(deftest a-throwaway-jvm-never-collects-its-store
  ;; A check store is deleted whole at exit; a template whose compact task is
  ;; due would otherwise run a real collection at boot, under the tests.
  (let [dial (fn [request] (find (:seon.config/environment (operator/boot-request request "/checkout"))
                                 :seon.config.maintenance/collect?))]
    (is (= [:seon.config.maintenance/collect? false]
           (dial {:seon.operator/managed-root "/check" :seon.operator/command :start
                  :seon.operator/throwaway? true})))
    (is (nil? (dial {:seon.operator/managed-root "/root" :seon.operator/command :start})))))

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

(deftest a-dead-checks-disposable-root-is-swept-and-a-live-ones-kept
  ;; A killed check never reaches its `finally`; the next check deletes its
  ;; root only when its owning pid is gone, never a template, a slot's root,
  ;; the queue or a link.
  (let [root (str (.getCanonicalFile (java.io.File. (str "tmp/operator-sweep-test-" (random-uuid)))))
        outside (java.io.File. (str root "-outside"))
        pid (.pid (java.lang.ProcessHandle/current))
        dead (let [p (.start (ProcessBuilder. ["true"]))] (.waitFor p) (.pid p))
        sha "0123456789abcdef0123456789abcdef01234567"
        place (fn [relative] (doto (java.io.File. root relative) .mkdirs))
        dead-root (place (str "data/check/0123456789ab-" dead "/data/store"))
        live-root (place (str "data/check/0123456789ab-" pid))
        building (place (str "data/check/template/" sha "_slot0.store-" dead))
        template (place (str "data/check/template/" sha "_slot0"))
        slot-root (place "data/check/slot-0")
        queue (place "data/check/queue")
        link (java.io.File. root (str "data/check/abcdef012345-" dead))]
    (try
      (.mkdirs outside)
      (java.nio.file.Files/createSymbolicLink (.toPath link) (.toPath outside)
                                              (make-array java.nio.file.attribute.FileAttribute 0))
      (let [swept (set (#'operator/swept-check-roots! root))]
        (is (= #{(str (.getParentFile (.getParentFile dead-root))) (str building)} swept))
        (is (every? #(.isDirectory ^java.io.File %) [live-root template slot-root queue outside])))
      (finally
        (run! #(.delete ^java.io.File %) (reverse (file-seq (java.io.File. root))))
        (.delete link)
        (.delete outside)))))

(deftest check-templates-beyond-the-dial-are-pruned-least-recently-cloned-first
  ;; Retention keeps the `kept` most recently cloned templates; one whose lock
  ;; another holder has is never deleted under it.
  (let [directory (doto (.getCanonicalFile (java.io.File. (str "tmp/operator-template-test-" (random-uuid)))) .mkdirs)
        template (fn [sha age-ms]
                   (doto (java.io.File. directory sha) .mkdirs
                     (.setLastModified (- (System/currentTimeMillis) age-ms))))
        newest (template "aaaa_slot0" 1000)
        held (template "bbbb_slot1" 2000)
        oldest (template "cccc_slot0" 3000)
        channel (.getChannel (java.io.RandomAccessFile. (java.io.File. directory "bbbb_slot1.lock") "rw"))
        _ (.lock channel)]
    (try
      (is (= [(str oldest)] (#'operator/pruned-templates! directory 1))
          "the held template survives; the free older one goes")
      (is (and (.isDirectory newest) (.isDirectory held)))
      (.close channel)
      (is (= [(str held)] (#'operator/pruned-templates! directory 1)))
      (finally
        (.close channel)
        (run! #(.delete ^java.io.File %) (reverse (file-seq directory)))))))

(deftest the-check-slot-count-is-a-dial
  ;; Resolved at run time: the rehearsal analyzes the running JVM's script/,
  ;; which lacks a var this commit adds (see c62a1d6b1).
  (let [slots (requiring-resolve 'seon.operator/operator-check-slots)
        templates (requiring-resolve 'seon.operator/operator-check-templates)]
    (is (= 2 (slots nil)))
    (is (= 1 (slots {:seon.config.operator/check-slots 1})))
    (is (pos-int? (templates nil)) "the template retention is a shipped dial")))

(deftest an-archive-a-check-holds-before-its-jvm-launches-is-pruned-only-after-release
  ;; A check's archive has no JVM naming it yet (2026-10-08: another launch's
  ;; prune deleted what a check was about to launch from).
  (let [root (str (java.nio.file.Files/createTempDirectory
                   "seon-hold" (make-array java.nio.file.attribute.FileAttribute 0)))
        archive (fn [name] (doto (java.io.File. root (str "data/source/" name)) .mkdirs))
        kept (archive "kept")
        held (archive "held")
        other (archive "other")
        repository (operator/repository-root)
        pid (.pid (java.lang.ProcessHandle/current))
        hold-file (java.io.File. (#'operator/holds-directory repository)
                                 (str (#'operator/hold-name (.getCanonicalPath held)) "." pid))]
    (try
      (#'operator/hold! repository (.getCanonicalPath held))
      (#'operator/prune-archives! root (.getCanonicalPath kept))
      (is (.isDirectory held) "an archive a live pid holds survives the prune")
      (is (not (.isDirectory other)) "an unheld archive is pruned")
      ;; The holder ends: its hold names a dead pid.
      (let [dead (let [p (.start (ProcessBuilder. ["true"]))] (.waitFor p) (.pid p))]
        (.renameTo hold-file (java.io.File. (.getParentFile hold-file)
                                            (str/replace (.getName hold-file) (str "." pid) (str "." dead)))))
      (#'operator/prune-archives! root (.getCanonicalPath kept))
      (is (not (.isDirectory held)) "released, it is pruned like any other archive")
      (is (not-any? #(str/starts-with? (.getName ^java.io.File %) (#'operator/hold-name (.getCanonicalPath held)))
                    (.listFiles (#'operator/holds-directory repository)))
          "the dead hold is swept")
      (finally
        (.delete hold-file)
        (doseq [file (reverse (file-seq (java.io.File. root)))] (.delete ^java.io.File file))))))

(deftest a-shared-cache-link-is-replaced-atomically
  ;; A reader of the link (a rehearsal copying the analyzer cache) must never
  ;; find it missing while another check re-links it.
  (let [root (str (java.nio.file.Files/createTempDirectory
                   "seon-link" (make-array java.nio.file.attribute.FileAttribute 0)))
        old-shared (doto (java.io.File. root "old") .mkdirs)
        new-shared (doto (java.io.File. root "new") .mkdirs)
        source (doto (java.io.File. root "source") .mkdirs)
        link (java.io.File. source ".clj-kondo/.cache")
        missing (atom 0)
        running (atom true)]
    (try
      (spit (java.io.File. source "dependency-pins.txt") "")
      (#'operator/share-caches! {".clj-kondo/.cache" (.getPath old-shared)} (.getPath source) [])
      (let [reader (future (while @running
                             (when-not (.isDirectory link) (swap! missing inc))))]
        (dotimes [i 200]
          (#'operator/share-caches! {".clj-kondo/.cache" (.getPath (if (even? i) new-shared old-shared))}
                                    (.getPath source) []))
        (reset! running false)
        @reader)
      (is (zero? @missing) "the link resolves at every instant")
      (is (empty? (filter #(str/includes? (.getName ^java.io.File %) ".link-")
                          (.listFiles (.getParentFile link)))) "no temporary link is left")
      (finally
        (reset! running false)
        (doseq [file (reverse (file-seq (java.io.File. root)))] (.delete ^java.io.File file))))))

(deftest ^{:seon/tasks #{'seon.operator/an-extracted-archive-is-read-only}}
  an-extracted-archive-is-read-only
  (let [base (java.nio.file.Files/createTempDirectory "seon-sealed" (make-array java.nio.file.attribute.FileAttribute 0))
        archive (str base "/archive")
        source (java.io.File. archive "src/seon/sci/forgive.clj")
        repository (operator/repository-root)
        tracked (slurp (.getInputStream (.start (doto (ProcessBuilder. ^java.util.List ["git" "ls-tree" "-r" "-z" "--name-only" "HEAD"])
                                                  (.directory (java.io.File. repository))))))]
    (try
      (#'operator/extract-archive! repository "HEAD" archive)
      (is (.canWrite source) "a freshly extracted archive is writable until sealed")
      (#'operator/seal-archive! archive tracked)
      (is (not (.canWrite source)) "a committed source file refuses a write once sealed")
      (is (thrown? java.io.IOException (spit source "edited"))
          "an agent's relative spit into the running archive fails")
      (is (.createNewFile (java.io.File. archive "placed-beside-the-program"))
          "a directory stays writable for the caches a launch places")
      (finally
        (doseq [file (reverse (file-seq (.toFile base)))] (.delete ^java.io.File file))))
    (is (not (.exists (.toFile base))) "a sealed archive is deleted whole")))

(deftest ^{:seon/tasks #{'seon.operator/an-extracted-archive-is-read-only}}
  a-pinned-dependency-source-is-read-only
  (let [root (java.nio.file.Files/createTempDirectory "seon-sealed-pin" (make-array java.nio.file.attribute.FileAttribute 0))
        repository (operator/repository-root)
        path "reference-code/aero"
        pin (str/trim (slurp (.getInputStream (.start (doto (ProcessBuilder. ^java.util.List ["git" "rev-parse" (str "HEAD:" path)])
                                                         (.directory (java.io.File. repository)))))))
        pinned (#'operator/pinned-checkout! (str root) repository path pin)
        source (java.io.File. pinned "LICENSE")
        product (java.io.File. pinned "target-classes-prepared")]
    (try
      (is (.canWrite source) "a freshly archived pin is writable until sealed")
      (.mkdirs product)
      (#'operator/seal-pinned! (str (java.io.File. repository path)) pin pinned)
      (is (not (.canWrite source)) "a tracked dependency file refuses a write once sealed")
      (is (thrown? java.io.IOException (spit source "edited")) "an agent's spit into a pin fails")
      (is (.createNewFile (java.io.File. product "Prepared.class")) "a build-product directory stays writable")
      (is (do (#'operator/seal-pinned! (str (java.io.File. repository path)) pin pinned) true)
          "sealing again after a preparation rerun is idempotent")
      (finally
        (doseq [file (reverse (file-seq (.toFile root)))] (.delete ^java.io.File file))))
    (is (not (.exists (.toFile root))) "a sealed pin is deleted whole")))

(deftest changed-dependencies-name-the-inputs-a-running-jvm-cannot-adopt
  ;; The running JVM's classpath is fixed at launch, so its rehearsal cannot
  ;; adopt a candidate that changes `deps.edn` or a gitlink pin; the throwaway
  ;; JVM, booted from the candidate's archive, is that candidate's proof. The
  ;; operator names the changed dependency inputs between the running commit
  ;; and the candidate so `check!` leaves the rehearsal to the throwaway.
  (let [repository (str (java.nio.file.Files/createTempDirectory
                         "seon-deps" (make-array java.nio.file.attribute.FileAttribute 0)))
        git (fn [& args] (str/trim (#'operator/command! (into ["git" "-c" "user.name=t" "-c" "user.email=t@t"] args)
                                                       repository 30000)))
        commit (fn [message] (git "add" "deps.edn" "src.clj") (git "commit" "-q" "-m" message) (git "rev-parse" "HEAD"))]
    (try
      (git "init" "-q")
      (spit (java.io.File. repository "deps.edn") "{:deps {a/a {} b/b {}}}")
      (spit (java.io.File. repository "src.clj") "1")
      (git "add" "-A")
      (let [base (commit "base")
            _ (spit (java.io.File. repository "src.clj") "2")
            source-only (commit "source")
            _ (spit (java.io.File. repository "deps.edn") "{:deps {a/a {}}}")
            removal (commit "removal")
            _ (git "update-index" "--add" "--cacheinfo" (str "160000," base ",reference-code/pin"))
            pinned (commit "pin")]
        (is (= [] (#'operator/changed-dependencies repository base source-only)))
        (is (= ["deps.edn"] (#'operator/changed-dependencies repository source-only removal)))
        (is (= ["reference-code/pin"] (#'operator/changed-dependencies repository removal pinned)))
        (is (= ["deps.edn" "reference-code/pin"] (#'operator/changed-dependencies repository base pinned))))
      (finally
        (doseq [file (reverse (file-seq (java.io.File. repository)))] (.delete ^java.io.File file))))))

(deftest the-embed-server-status-observes-and-stop-without-a-record-stops-nothing
  ;; Health reports the embed server; it never launches one. A root that recorded no embed server stops none:
  ;; stop and down terminate only the exact identity the root's own start recorded.
  (let [root (str (fs/create-temp-dir {:prefix "seon-embed-sidecar-"}))]
    (try
      (let [status (operator/embed-server-status root)]
        (is (#{:healthy :unreachable} (:seon.operator.embed/state status)))
        (is (pos-int? (:seon.operator.embed/port status)))
        (is (not (fs/exists? (fs/path root "data/embed-server.edn"))) "observing records nothing")
        (is (nil? (operator/embed-server-stop! root)) "nothing recorded, nothing stopped"))
      (finally (fs/delete-tree root)))))

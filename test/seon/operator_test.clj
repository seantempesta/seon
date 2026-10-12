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

(deftest a-changed-test-tree-keys-its-own-class-directory
  ;; 2026-10-12: boot compiles seon.test-support (test/) into the class
  ;; directory, which was keyed by src and script only, so a check of a changed
  ;; test-support loaded its predecessor's classes (stamped newer than any source).
  (let [repository (str (java.nio.file.Files/createTempDirectory
                         "seon-classes-key" (make-array java.nio.file.attribute.FileAttribute 0)))
        git (fn [& args] (#'operator/command! (into ["git" "-c" "user.name=t" "-c" "user.email=t@t"] args) repository 30000))
        write! (fn [path text] (let [file (java.io.File. repository ^String path)]
                                 (.mkdirs (.getParentFile file))
                                 (spit file text)))
        commit! (fn [] (git "add" "-A") (git "commit" "-q" "-m" "c") (str/trim (git "rev-parse" "HEAD")))
        classes {:seon.dev-cache/status :hit :seon.dev-cache/digest "d"}
        directory (fn [sha] (#'operator/program-classes repository (str "/archives/" sha) classes))]
    (try
      (git "init" "-q")
      (doseq [path ["src/a.clj" "script/b.clj" "deps.edn" "reference-code/c" "test/d.clj" "docs/e.md"]]
        (write! path path))
      (let [base (commit!)
            _ (write! "docs/e.md" "changed")
            docs (commit!)
            _ (write! "test/d.clj" "changed")
            tests (commit!)]
        (is (= (directory base) (directory docs)) "a docs change shares its predecessor's classes")
        (is (not= (directory docs) (directory tests)) "a test change compiles its own"))
      (finally
        (doseq [file (reverse (file-seq (java.io.File. repository)))] (.delete ^java.io.File file))))))

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

(deftest check-at-a-revision-names-the-tests-without-paths
  (let [root (System/getProperty "java.io.tmpdir")
        request (operator/parse-argv ["--seon-root" root "check" "--at" "HEAD~1"
                                      "--test" "seon.operator-test/check-at-a-revision-names-the-tests-without-paths"])
        with-path (try (operator/parse-argv ["--seon-root" root "check" "--at" "HEAD~1" "src/seon/operator.clj"])
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

(deftest a-checks-adoption-answers-its-failure-as-data
  ;; A check's throwaway JVM resumes the base's template store, whose `default`
  ;; keeps the base's program until it adopts the candidate's publication; a
  ;; test the candidate adds is eligible only after that adoption. This test is
  ;; itself new to its commit: the check runs it by name only because of it.
  ;; With no JVM at the root, the adoption answers a diagnostic, never throws.
  (let [root (str (.getCanonicalFile (java.io.File. (str "tmp/operator-adopt-test-" (random-uuid)))))]
    (try
      (.mkdirs (java.io.File. root))
      (let [adoption (#'operator/adopted! root)]
        (is (string? (:seon.error/message adoption)))
        (is (= :client-failed (:seon.operator/disposition adoption))))
      (finally (run! #(.delete ^java.io.File %) (reverse (file-seq (java.io.File. root))))))))

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

(deftest a-class-directory-a-check-holds-before-its-jvm-launches-is-pruned-only-after-release
  ;; A check copying a directory has no JVM naming it yet (2026-10-08: another
  ;; launch's prune deleted it, NoSuchFileException on a class).
  (let [repository (str (java.nio.file.Files/createTempDirectory
                         "seon-hold" (make-array java.nio.file.attribute.FileAttribute 0)))
        classes (java.io.File. repository "target/dev-program-classes")
        directory (fn [name age-ms]
                    (doto (java.io.File. classes name) .mkdirs
                      (.setLastModified (- (System/currentTimeMillis) age-ms))))
        program (directory "program" 0)
        held (directory "held-oldest" 900000)
        _ (doseq [i (range 4)] (directory (str "recent-" i) (* 1000 (inc i))))
        pid (.pid (java.lang.ProcessHandle/current))]
    (try
      (#'operator/hold! repository (.getCanonicalPath held))
      (#'operator/prune-program-classes! repository (.getCanonicalPath program))
      (is (.isDirectory held) "a directory a live pid holds survives the prune")
      ;; The holder ends: its hold names a dead pid.
      (let [dead (let [p (.start (ProcessBuilder. ["true"]))] (.waitFor p) (.pid p))
            hold-file (first (filter #(.endsWith (.getName ^java.io.File %) (str "." pid))
                                     (.listFiles (#'operator/holds-directory repository))))]
        (.renameTo hold-file (java.io.File. (.getParentFile hold-file)
                                            (str/replace (.getName hold-file) (str "." pid) (str "." dead)))))
      (#'operator/prune-program-classes! repository (.getCanonicalPath program))
      (is (not (.isDirectory held)) "released, it is pruned like any old directory")
      (is (empty? (.listFiles (#'operator/holds-directory repository))) "the dead hold is swept")
      (finally
        (doseq [file (reverse (file-seq (java.io.File. repository)))] (.delete ^java.io.File file))))))

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

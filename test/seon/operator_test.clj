(ns seon.operator-test
  (:require [clojure.core.server :as server]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
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

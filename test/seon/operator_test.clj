(ns seon.operator-test
  (:require [clojure.core.server :as server]
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

(deftest status-prints-a-bounded-summary-of-500-failed-tests
  ;; `bin/seon status` printed 1.5 MB; the terminal face keeps counts and the
  ;; top whole entries, and names where the whole value is.
  (let [faults (vec (for [i (range 500)]
                      {:seon.error/signature (str "sig-" i) :seon.error/operation 'seon.test/run
                       :seon.problems/occurrences i :seon.error/message (apply str (repeat 3000 "x"))}))
        observation {:seon.dev.mcp/cluster "default" :seon.dev.mcp/health :faulted
                     :seon.dev.mcp/problem-counts {:seon.problems/failed-tests 500}
                     :seon.dev.mcp/open-faults faults
                     :seon.dev.mcp/problems-unavailable
                     {:seon.error/operation 'seon.problems/problems
                      :seon.error/message (apply str (repeat 1500000 "y"))}}
        summary (operator/status-summary {:seon.boot/pid 1 :seon.operator/clusters [observation]})
        [cluster] (:seon.operator/clusters summary)]
    (is (< (count (pr-str summary)) 4096) "the printed summary stays under 4 KB")
    (is (= {:seon.problems/failed-tests 500} (:seon.dev.mcp/problem-counts cluster)))
    (is (= 500 (get-in cluster [:seon.dev.mcp/open-faults :seon.operator/count])))
    (is (= 495 (get-in cluster [:seon.dev.mcp/open-faults :seon.operator/omitted])))
    (is (= 3000 (get-in cluster [:seon.dev.mcp/open-faults :seon.operator/top 0 :seon.operator/message-chars])))
    (is (= 1500000 (get-in cluster [:seon.dev.mcp/problems-unavailable :seon.operator/message-chars])))
    (is (clojure.string/includes? (:seon.operator/requery summary) "runtime_status"))))

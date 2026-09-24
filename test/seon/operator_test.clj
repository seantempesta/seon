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

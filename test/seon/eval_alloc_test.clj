(ns seon.eval-alloc-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [seon.mcp :as mcp]
            [seon.test-support :as support]))

(deftest an-evaluation-reply-shows-what-it-allocated
  (support/with-running-agent
   (fn [running agent-id _]
     (let [call (fn [code]
                  (let [reply (json/read-str
                               (mcp/call (json/write-str
                                          {"name" "eval_clj"
                                           "arguments" {"cluster" running "agent" agent-id "code" code}})))]
                    (support/mcp-reply reply)))
           ;; one 50,000,016-byte long array
           large (call "(alength (long-array 6250000))")
           small (call "(+ 1 2)")]
       (is (= 6250000 (:seon.repl/value large)))
       (is (<= 50.0 (:seon.repl/alloc-mb large) 80.0)
           "a large allocation is measured on the evaluation's virtual thread")
       (is (= 3 (:seon.repl/value small)))
       (is (<= 0.0 (:seon.repl/alloc-mb small) 5.0)
           "an arithmetic form allocates next to nothing")
       (is (not (contains? small :seon.repl/findings)))))))

(deftest a-measured-call-reports-allocation-and-reads
  (support/with-running-agent
   (fn [running agent-id _]
     (let [call (fn [code]
                  (let [reply (json/read-str
                               (mcp/call (json/write-str
                                          {"name" "eval_clj"
                                           "arguments" {"cluster" running "agent" agent-id "code" code}})))]
                    (support/mcp-reply reply)))
           allocation (:seon.repl/value
                       (call "(select-keys (seon.profile/measure (fn [] (alength (long-array 6250000))))
                                [:seon.profile/value :seon.profile/alloc-bytes :seon.profile/alloc-scope])"))
           reads (:seon.repl/value
                  (call "(select-keys (seon.profile/measure
                                        (fn [] (dotimes [_ 3] (seon.db/pull '[:db/ident] [:db/ident :db/ident]))))
                           [:seon.profile/reads :seon.profile/called])"))]
       (is (= 6250000 (:seon.profile/value allocation)))
       (is (<= 50000000 (:seon.profile/alloc-bytes allocation) 80000000))
       (is (= :jvm (:seon.profile/alloc-scope allocation)) "SCI evaluates on a virtual thread")
       (is (= 3 (get-in reads [:seon.profile/called 'seon.db/pull]))
           "compiled seon.db reads made from SCI are counted")
       (is (<= 3 (:seon.profile/reads reads)))))))

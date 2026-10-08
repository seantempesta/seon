(ns seon.eval-alloc-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [seon.mcp :as mcp]
            [seon.test-support :as support]))

(defn- answer
  "The parsed MCP result of `code` evaluated as `agent-id` on `running`."
  {:malli/schema [:=> [:cat :string :string :string] :map]}
  [running agent-id code]
  (json/read-str (mcp/call (json/write-str {"name" "eval_clj"
                                            "arguments" {"cluster" running "agent" agent-id "code" code}}))))

(defn- while-another-thread-allocates
  "The parsed MCP result `call` answers while a platform thread allocates 1 MB arrays until it returns."
  {:malli/schema [:=> [:cat [:=> [:cat] :map]] :map]}
  [call]
  (let [stop (java.util.concurrent.atomic.AtomicBoolean.)
        noise (.start (Thread/ofPlatform)
                      ^Runnable (fn [] (while (not (.get stop))
                                         (java.util.Arrays/fill ^bytes (byte-array 1000000) (byte 1)))))]
    (try (call)
         (finally (.set stop true)
                  (.join ^Thread noise (java.time.Duration/ofSeconds 1))))))

(deftest an-evaluation-reply-shows-what-it-allocated
  (support/with-running-agent
   (fn [running agent-id _]
     ;; one 100,000,016-byte array, while another thread allocates throughout
     (let [large (while-another-thread-allocates #(answer running agent-id "(alength (byte-array 100000000))"))
           small (answer running agent-id "(+ 1 2)")
           request (support/mcp-request large)]
       (is (= 100000000 (:seon.repl/value (support/mcp-reply large))))
       (is (<= 100.0 (:seon.repl/alloc-mb (support/mcp-reply large)) 101.0)
           "the evaluation's own thread, never the other thread's allocation")
       (is (<= 100.0 (:seon.cost/alloc-mb request) 101.0) "the request's arms are the form's")
       (is (pos? (:seon.cost/cpu-ms request)))
       (is (< (:seon.repl/alloc-mb (support/mcp-reply small)) 1.0))
       (is (not-any? #(contains? (support/mcp-reply %) :seon.repl/findings) [large small]))))))

(deftest a-measured-call-reports-allocation-and-reads
  (support/with-running-agent
   (fn [running agent-id _]
     (let [call (fn [code] (support/mcp-reply (answer running agent-id code)))
           allocation (:seon.repl/value
                       (call "(select-keys (seon.profile/measure (fn [] (alength (long-array 6250000))))
                                [:seon.profile/value :seon.profile/alloc-bytes :seon.profile/alloc-scope])"))
           reads (:seon.repl/value
                  (call "(select-keys (seon.profile/measure
                                        (fn [] (dotimes [_ 3] (seon.db/pull '[:db/ident] [:db/ident :db/ident]))))
                           [:seon.profile/reads :seon.profile/ranked-by-calls])"))]
       (is (= 6250000 (:seon.profile/value allocation)))
       (is (<= 50000000 (:seon.profile/alloc-bytes allocation) 51000000))
       (is (= :thread (:seon.profile/alloc-scope allocation)) "SCI evaluates on a platform worker")
       (is (= 3 (some #(when (= 'seon.db/pull (:seon.profile/sym %)) (:seon.profile/calls %))
                       (:seon.profile/ranked-by-calls reads)))
           "compiled seon.db reads made from SCI are counted")
       (is (<= 3 (:seon.profile/reads reads)))))))

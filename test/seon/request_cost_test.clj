(ns seon.request-cost-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.mcp :as mcp]
            [seon.test-support :as support]))

(defn- answer
  "The parsed MCP result of `code` evaluated as `agent-id` on `running`."
  {:malli/schema [:=> [:cat :string :string :string :string] :map]}
  [running agent-id mode code]
  (json/read-str (mcp/call (json/write-str {"name" "eval_clj"
                                            "arguments" {"cluster" running "agent" agent-id "mode" mode
                                                         "code" code}}))))

(deftest a-reply-carries-its-request-cost
  (support/with-running-agent
   (fn [running agent-id _]
     (let [ping (answer running agent-id "sci" ":ping")
           {total :seon.cost/ms stages :seon.cost/stages} (support/mcp-request ping)
           form-ms (:seon.repl/ms (support/mcp-reply ping) 0)
           defined (support/mcp-request
                    (answer running agent-id "sci" "(in-ns 'request.cost) (defn twice [x] (* 2 x))"))]
       (is (= :ping (:seon.repl/value (support/mcp-reply ping))))
       (is (pos? total) "a ping costs its real wall time, never 0")
       (is (<= (reduce + (vals stages)) (+ total 0.5)) "stages are disjoint parts of the request")
       (is (<= form-ms total))
       (is (every? #(contains? stages %) [:custody :fork :read :evaluate :settle :print]))
       (is (every? #(contains? (:seon.cost/stages defined) %) [:settle :test-step]))))))

(deftest a-jvm-reply-carries-its-request-cost
  (support/with-running-agent
   (fn [running agent-id _]
     (let [{total :seon.cost/ms stages :seon.cost/stages} (support/mcp-request (answer running agent-id "jvm" "(+ 1 2)"))]
       (is (pos? total))
       (is (<= (reduce + (vals stages)) (+ total 0.5)))
       (is (every? #(contains? stages %) [:custody :evaluate :settle :print]))))))

(deftest a-request-counts-its-own-calls-reads-and-transactions
  (support/with-running-agent
   (fn [running agent-id connection]
     (let [_warm (answer running agent-id "sci" ":ping")
           ping (support/mcp-request (answer running agent-id "sci" ":ping"))
           pulls (support/mcp-request
                  (answer running agent-id "sci"
                          "(seon.db/pull '[:db/ident] [:db/ident :db/ident]) (seon.db/pull '[:db/ident] [:db/ident :db/ident]) (seon.db/pull '[:db/ident] [:db/ident :db/ident])"))
           ;; another thread reads the same database throughout the next request
           stop (java.util.concurrent.atomic.AtomicBoolean.)
           made (java.util.concurrent.atomic.AtomicLong.)
           noise (.start (Thread/ofPlatform)
                         ^Runnable (fn [] (while (not (.get stop))
                                            (db/pull (db/db connection) '[:db/ident] [:db/ident :db/ident])
                                            (.incrementAndGet made))))
           noisy (support/mcp-request (answer running agent-id "sci" ":ping"))
           _ (.set stop true)
           _ (.join ^Thread noise (java.time.Duration/ofSeconds 1))]
       (is (pos? (:seon.cost/calls ping)))
       (is (pos? (:seon.cost/transactions ping)) "the request's settlement commits")
       (is (<= 3 (- (:seon.cost/reads pulls) (:seon.cost/reads ping))))
       (is (pos? (.get made)))
       (is (= (:seon.cost/reads ping) (:seon.cost/reads noisy))
           "another thread's reads are not this request's")))))

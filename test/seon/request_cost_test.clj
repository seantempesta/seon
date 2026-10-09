(ns seon.request-cost-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [seon.batch :as batch]
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

(deftest ^{:seon/tasks #{'seon.batch/a-request-cost-counts-only-the-evaluation-arm
                        'seon.repl/a-request-spends-seconds-outside-its-named-stages}}
  a-request-shows-the-all-thread-window-and-accounts-for-unnamed-time
  (support/with-running-agent
   (fn [running agent-id connection]
     (doseq [[kind mode code expected]
             [[:trivial "sci" "(+ 1 2)" 3]
              [:define "sci" "(in-ns 'request.cost.measure) (defn tiny {:malli/schema [:=> [:cat :int] :int]} [x] (inc x)) (clojure.test/deftest tiny-test (clojure.test/is (= 2 (tiny 1))))" nil]
              [:redefine "sci" "(defn tiny {:malli/schema [:=> [:cat :int] :int]} [x] (+ 1 x))" nil]
              [:check "sci" "(select-keys (my.test/check {:seon.test/changed ['request.cost.measure/fresh-test]}) [:seon.test/executed-count :seon.test/passed?])" {:seon.test/executed-count 1 :seon.test/passed? true}]
              [:write "sci" "(my.program/write-branch! {:seon.fn/root \".\" :seon.program/identities #{}})" nil]
              [:source "sci" "(my.program/source 'seon.id/id)" nil]
              [:virtual "jvm" "(let [task (java.util.concurrent.FutureTask. ^java.util.concurrent.Callable (fn [] (alength (byte-array 8000000)))) thread (.start (Thread/ofVirtual) task)] (.join thread) (.get task))" 8000000]]]
       (when (= kind :check)
         (support/apply-config! connection (:seon.cluster/name (support/execution-handle nil)) {:seon.config.test/step? false})
         (answer running agent-id "sci" "(clojure.test/deftest fresh-test (clojure.test/is (= 3 (tiny 2))))"))
       (let [before (batch/jvm-counters)
             reply (answer running agent-id mode code)
             after (batch/jvm-counters)
             cost (support/mcp-request reply)
             window (:seon.cost/jvm-window cost)
             outer (batch/jvm-window before after)]
         (is (false? (get reply "isError")))
         (when expected (is (= expected (:seon.repl/value (support/mcp-reply reply)))))
         (is (= :unavailable (:seon.cost/causal-attribution cost)))
         (is (= :includes-unrelated-work (:seon.cost/attribution window)))
         (is (contains? (:seon.cost/stages cost) :unattributed))
         (is (< (Math/abs (double (- (:seon.cost/ms cost) (reduce + (vals (:seon.cost/stages cost)))))) 0.000001))
         ;; Outer JSON encoding and decoding are outside the request's window.
         (is (<= (:seon.cost/alloc-mb window) (:seon.cost/alloc-mb outer)))
         (is (<= (:seon.cost/cpu-ms window) (:seon.cost/cpu-ms outer)))
         (is (<= (- (:seon.cost/alloc-mb outer) (:seon.cost/alloc-mb window))
                 (max 8.0 (* 0.2 (:seon.cost/alloc-mb outer)))))
         (when (= mode "jvm") (is (<= 8.0 (:seon.cost/alloc-mb window)))))))))

(deftest unavailable-counters-are-unknown-and-overlapping-stages-are-visible
  (let [window (batch/jvm-window [-1 -1] [-1 -1])
        cost (batch/request-cost (System/nanoTime) {:overlapping 1000000.0} nil)]
    (is (not (contains? window :seon.cost/alloc-mb)))
    (is (not (contains? window :seon.cost/cpu-ms)))
    (is (= :counter-unavailable (get-in window [:seon.cost/unmeasured :seon.cost/alloc-mb])))
    (is (neg? (get-in cost [:seon.cost/stages :unattributed])))))

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

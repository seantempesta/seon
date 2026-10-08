(ns seon.stale2-measure-test
  "Copy to test/seon/stale2_measure_test.clj and run
  `bin/seon check [src/seon/sci/eval.clj src/seon/sci/arm.clj] test/seon/stale2_measure_test.clj --test seon.stale2-measure-test --include-long`:
  the redefining MCP SCI pass of a callee with `n` callers another writer defined,
  for a fresh agent (every caller re-interpreted) and a carried one, shown as the
  failing assertion's actual value."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [seon.mcp :as mcp]
            [seon.profile :as profile]
            [seon.test-support :as support]))

(defn- pass!
  "Wall ms and allocated MB of one MCP SCI pass, and its value."
  [code agent-id running]
  (let [mx ^com.sun.management.ThreadMXBean (java.lang.management.ManagementFactory/getThreadMXBean)
        bytes0 (.getTotalThreadAllocatedBytes mx)
        t0 (System/nanoTime)
        result (json/read-str (mcp/call (json/write-str {"name" "eval_clj"
                                                         "arguments" {"code" code "agent" agent-id "cluster" running}})))]
    {:ms (/ (quot (- (System/nanoTime) t0) 100000) 10.0)
     :mb (/ (quot (- (.getTotalThreadAllocatedBytes mx) bytes0) 100000) 10.0)
     :value (:seon.repl/value (support/reply (get (last (get result "content")) "text")))}))

(defn- median [xs] (nth (sort xs) (quot (count xs) 2)))

(defn- profiled
  "Calls and total nanoseconds of `sym`'s armed cells so far."
  [sym]
  (reduce (fn [[calls nanos] held]
            [(+ calls (profile/histogram-calls (profile/counts held)))
             (+ nanos (.sum ^java.util.concurrent.atomic.LongAdder (:seon.profile/total-counter held)))])
          [0 0] (filter #(= sym (:seon.profile/sym %)) (profile/cells))))

(def ^:private timed-syms '[seon.sci.eval/reinterpret-inherited-callers! seon.fn/reverse-closure])

(defn- timed
  "`thunk`'s value and, per timed function, its calls and mean microseconds during it."
  [thunk]
  (let [before (mapv profiled timed-syms) value (thunk) after (mapv profiled timed-syms)]
    [value (into {} (map (fn [sym [c0 n0] [c1 n1]]
                           [(symbol (name sym)) [(- c1 c0) (if (pos? (- c1 c0)) (quot (- n1 n0) (* 1000 (- c1 c0))) 0)]])
                         timed-syms before after))]))

(deftest ^{:seon.test/long "measurement" :seon.test/long-ms 600000} measure
  (support/with-running-agent
   (fn [running agent-id connection]
     (let [other (str "other-" agent-id)
           shared (fn [n] (str "(in-ns 'scratch.m" n ") (clojure.core/refer-clojure) "))
           rows
           (vec
            (for [n [0 1 10 50]]
              (let [in (shared n)
                    _ (pass! (str in "(defn g [] 0)") agent-id running)
                    _ (support/transacted! connection (support/agent-tx @connection (str other n)))
                    _ (when (pos? n)
                        (pass! (str in (apply str (for [i (range n)] (str "(defn c" i " [] (g)) "))))
                               (str other n) running))
                    probe (str "[" (if (pos? n) (str "(c" (dec n) ")") "(g)") " (g)]")
                    [fresh fresh-reinterpret] (timed #(vec (for [i (range 5)]
                                 (let [fresh-id (str "fresh-" n "-" i "-" agent-id)]
                                   (support/transacted! connection (support/agent-tx @connection fresh-id))
                                   (pass! (str in "(defn g [] " (inc i) ") " probe) fresh-id running)))))
                    [carried carried-reinterpret] (timed #(vec (for [i (range 5)]
                                   (pass! (str in "(defn g [] " (+ 10 i) ") " probe) agent-id running))))]
                {:n n
                 :fresh-ms (median (map :ms fresh)) :fresh-mb (median (map :mb fresh))
                 :carried-ms (median (map :ms carried)) :carried-mb (median (map :mb carried))
                 :fresh-reinterpret fresh-reinterpret :carried-reinterpret carried-reinterpret
                 :values [(mapv :value fresh) (mapv :value carried)]})))]
       (is (= :show rows))))))

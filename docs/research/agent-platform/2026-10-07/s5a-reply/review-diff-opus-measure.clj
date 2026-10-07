;; Disposable measurement used by review-s5a-opus (2026-10-07). To rerun, copy to
;; test/seon/review_s5a_measure_test.clj, set `out`, and run
;;   bin/seon check test/seon/review_s5a_measure_test.clj --test seon.review-s5a-measure-test --include-long
;; then delete the copy. It measures MCP passes of n x (+ 1 1) (sci and jvm, n = 1/10/100,
;; one warm-up then three runs) and, for SCI n=100, the turn (evaluate-source!) and the
;; reply print (seon.mcp/observed over the held receipts) apart.
(ns seon.review-s5a-measure-test
  (:require [clojure.data.json :as json] [clojure.string]
            [clojure.test :refer [deftest is]]
            [seon.cluster.agent :as agent]
            [seon.mcp :as mcp]
            [seon.operator.runtime :refer [running-instances]]
            [seon.profile :as profile]
            [seon.test-support :as support]))

(def ^:private out "measure.edn")

(defn- mx ^com.sun.management.ThreadMXBean [] (java.lang.management.ManagementFactory/getThreadMXBean))

(defn- bracket
  {:malli/schema [:=> [:cat fn?] :map]}
  [f]
  (let [mark (profile/begin) b0 (.getTotalThreadAllocatedBytes (mx)) t0 (System/nanoTime)
        v (f)
        t1 (System/nanoTime) b1 (.getTotalThreadAllocatedBytes (mx))
        ex (profile/explain mark 25)]
    {:ms (/ (- t1 t0) 1e6) :mb (/ (- b1 b0) 1e6) :calls (:seon.profile/calls ex)
     :growths (mapv (juxt :seon.profile/sym :seon.profile/calls :seon.profile/total-ms) (:seon.profile/growths ex))
     :value v}))

(defn- pass
  {:malli/schema [:=> [:cat :string :string :int :string] :map]}
  [running agent-id n mode]
  (let [code (clojure.string/join "\n" (repeat n "(+ 1 1)"))
        request (json/write-str {"name" "eval_clj" "arguments" {"code" code "agent" agent-id "cluster" running "mode" mode}})]
    (-> (bracket #(mcp/call request)) (dissoc :value) (assoc :mode mode :n n))))

(defn- split
  {:malli/schema [:=> [:cat :string :string :int] :map]}
  [running agent-id n]
  (let [found (#'seon.mcp/instance @running-instances running)
        custody (#'seon.mcp/execution found agent-id nil)
        cluster-name (:seon.cluster/name found)
        code (clojure.string/join "\n" (repeat n "(+ 1 1)"))
        turn (bracket #(agent/evaluate-source! (assoc custody :seon.agent/segmenter 'seon.turn/code-sources
                                                      :seon.cluster.reply/text code)))
        answer (:value turn)
        reply (bracket #(#'seon.mcp/observed (:seon.turn.loop/evaluated-sources answer) (:seon.turn/id answer)
                                              (:seon.db/db answer) agent-id cluster-name))]
    {:n n :turn (dissoc turn :value) :reply (dissoc reply :value)}))

(deftest ^{:seon.test/long "disposable measurement" :seon.test/long-ms 240000} measure
  (support/with-running-agent
   (fn [running agent-id _]
     (doall (for [mode ["sci" "jvm"] n [1 10 100]] (pass running agent-id n mode)))
     (let [runs (doall (for [_ (range 3) mode ["sci" "jvm"] n [1 10 100]] (pass running agent-id n mode)))
           splits (doall (for [_ (range 3)] (split running agent-id 100)))]
       (spit out (pr-str {:runs runs :splits splits}))
       (is (seq runs))))))

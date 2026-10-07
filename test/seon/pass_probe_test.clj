(ns seon.pass-probe-test
  "The S0 probe measures real passes: each record is complete, its readings
  are positive where the pass must have done work, and the passes did what
  they say (the definition is callable after it; the model pass made one
  provider request and closed its turn). Durable commits and store bytes are
  proven on a throwaway root: a test world's branches are in-memory overlays."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.pass-probe :as probe]
            [seon.test-support :as support]))

(defn- measured?
  "True when `record` is complete with every reading a pass must move."
  {:malli/schema [:=> [:cat :map] :boolean]}
  [record]
  (and (true? (::probe/complete? record))
       (pos? (::probe/allocated-bytes record))
       (pos? (::probe/checked-calls record))
       (pos? (::probe/attempts record))
       (pos? (::probe/tokens record))))

(deftest ^{:seon.test/long "Hosts two agents on fresh branches and runs three MCP SCI passes (one a contracted defn) and one capped model turn through the fixture provider; measured 0.1-0.9 s per pass on a throwaway root."
           :seon.test/long-ms 60000}
  a-pass-of-each-entrant-is-measured
  (support/with-running-agent
   (fn [running _ _]
     (let [mcp (probe/run-mcp running "sci" 1 #{1} true)
           model (probe/run-model running 1 #{})]
       (testing "an ordinary MCP pass, a definition and the pass that calls it"
         (is (= [:ordinary :defn :after-defn] (mapv ::probe/kind mcp)))
         (is (every? measured? mcp))
         (is (every? false? (map ::probe/error? mcp)) "the definition installed and the next pass called it"))
       (testing "one capped model pass answered by the fixture provider"
         (is (= [:ordinary] (mapv ::probe/kind model)))
         (is (every? measured? model))
         (is (= [1] (mapv ::probe/provider-requests model)))
         (is (= [:closed] (mapv #(get-in % [::probe/turn :seon.turn.loop/outcome]) model))))))))

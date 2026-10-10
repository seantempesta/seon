(ns seon.test.interpreted-fixture-test
  "An agent's interpreted test runs inside its namespace's fixtures in the world its check acquires."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [seon.mcp :as mcp]
            [seon.test-support :as support]))

(defn- agent-value
  "The value of the last form of `code` evaluated as `agent-id` through `seon.mcp/call`."
  {:malli/schema [:=> [:cat :string :seon.agent/id :string] :seon.schema/value]}
  [running agent-id code]
  (->> (get (json/read-str (mcp/call (json/write-str {"name" "eval_clj"
                                                       "arguments" {"code" code "agent" agent-id "cluster" running}})))
            "content")
       (keep #(let [item (support/reply (get % "text"))]
                (when (and (map? item) (contains? item :seon.repl/value)) [(:seon.repl/value item)])))
       last
       first))

(deftest ^{:seon/tasks #{'seon.sci.eval/a-world-acquired-from-rows-runs-its-namespace-fixtures}
           :seon.test/long "Two eval requests of an own-branch agent, each with its automatic test step: about 3 s, as seon.test.redefined-callee-test's four measured 3.2 s."
           :seon.test/long-ms 15000}
  a-checked-test-runs-inside-its-namespace-fixture
  (support/with-running-agent
   (fn [running _ _]
     (let [agent-id (str "fixtured-" (random-uuid))
           _ (support/own-branch-agent!
              running agent-id
              (str "(ns fx.area (:require [clojure.test :refer [deftest is use-fixtures]]))\n"
                   "(use-fixtures :each (fn [f] (binding [*print-length* 7] (f))))\n"
                   "(deftest fixtured-test (is (= 7 *print-length*)))"))
           checked (agent-value running agent-id
                                "(select-keys (my.test/check {:seon.test/identities #{'fx.area/fixtured-test} :seon.test/rerun? true}) [:seon.test/passed? :seon.test/executed-count])")]
       (is (= 1 (:seon.test/executed-count checked)))
       (is (true? (:seon.test/passed? checked)) "the checked test ran inside its namespace's fixture")))))

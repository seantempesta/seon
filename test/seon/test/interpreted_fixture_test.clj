(ns seon.test.interpreted-fixture-test
  "An agent's interpreted test runs inside its namespace's fixtures in the world its check acquires."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [seon.fn :as function]
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
                   "(defn print-seven {:malli/schema [:=> [:cat [:=> [:cat] :any]] :any]} [f] (binding [*print-length* 7] (f)))\n"
                   "(use-fixtures :each print-seven)\n"
                   "(deftest fixtured-test (is (= 7 *print-length*)))"))
           checked (agent-value running agent-id
                                "(select-keys (my.test/check {:seon.test/identities #{'fx.area/fixtured-test} :seon.test/rerun? true}) [:seon.test/passed? :seon.test/executed-count])")]
       (is (= 1 (:seon.test/executed-count checked)))
       (is (true? (:seon.test/passed? checked)) "the checked test ran inside its namespace's fixture")))))

(deftest ^{:seon/tasks #{'seon.sci.eval/a-world-acquired-from-rows-runs-its-namespace-fixtures}}
  a-files-use-fixtures-calls-are-its-namespace-fixtures
  (let [root (io/file "tmp" (str "fixture-index-" (random-uuid)))
        file (io/file root "test/fx/file.clj")]
    (io/make-parents file)
    (try
      (spit file (str "(ns fx.file (:require [clojure.test :as t :refer [deftest is use-fixtures]]))\n"
                      "(defn- around [f] (f))\n"
                      "(use-fixtures :each around (fn [f] (binding [*print-length* 1] (f))))\n"
                      "(t/use-fixtures :once fx.file/around)\n"
                      "(deftest fixtured (is true))\n"))
      (let [namespace-entity (->> (function/analyze-entities {:seon.fn/root (.getCanonicalPath root) :seon.fn/roots ["test"]})
                                  (filter #(= 'fx.file (:seon.ns/name %)))
                                  first)]
        (is (= #{{:seon.ns.fixture/runs :each :seon.ns.fixture/position 0 :seon.ns.fixture/function 'fx.file/around}
                 {:seon.ns.fixture/runs :once :seon.ns.fixture/position 0 :seon.ns.fixture/function 'fx.file/around}}
               (:seon.ns/fixtures namespace-entity))
            "each named fixture, by when it runs; the anonymous one is no data"))
      (finally (support/delete-recursively! root)))))

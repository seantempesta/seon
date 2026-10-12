(ns seon.test.redefined-callee-test
  "A test an agent defined runs its branch's current callee after the agent redefines it."
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

(deftest ^{:seon/tasks #{'seon.test/an-unchanged-red-is-reused-after-the-agent-redefines-what-it-reaches}
           :seon.test/long "Four eval requests of an own-branch agent, each with its automatic test step: 3.2 s measured on default's JVM (run 543b18698139)."
           :seon.test/long-ms 15000}
  a-red-test-reruns-green-after-its-callee-is-redefined
  (support/with-running-agent
   (fn [running _ _]
     (let [agent-id (str "redefined-callee-" (random-uuid))
           _ (support/own-branch-agent!
              running agent-id
              (str "(ns rc.area (:require [clojure.test :refer [deftest is]]))\n"
                   "(defn area \"The area.\" {:malli/schema [:=> [:cat :int] :int]} [r] r)\n"
                   "(deftest area-test (is (= 25 (area 5))))"))
           check "(select-keys (my.test/check {:seon.test/identities #{'rc.area/area-test}}) [:seon.test/passed? :seon.test/executed-count :seon.test/reused-count])"
           red (agent-value running agent-id check)
           _ (agent-value running agent-id
                          "(in-ns 'rc.area)\n(defn area \"The area.\" {:malli/schema [:=> [:cat :int] :int]} [r] (* r r))")
           checked (agent-value running agent-id check)
           again (agent-value running agent-id check)]
       (is (false? (:seon.test/passed? red)) "the test is red against the first definition")
       (is (true? (:seon.test/passed? checked)) "the test is green against the redefinition")
       (is (true? (:seon.test/passed? again)) "an unchanged check stays green")
       (is (= 1 (:seon.test/reused-count again)) "an unchanged check reuses the recorded green")))))

(deftest ^{:seon.test/long "Four eval requests of an own-branch agent, each with its automatic test step: about 3 s measured on default's JVM."
           :seon.test/long-ms 15000}
  a-test-reruns-when-a-function-it-calls-by-a-computed-name-changes
  (support/with-running-agent
   (fn [running _ _]
     (let [agent-id (str "computed-callee-" (random-uuid))
           _ (support/own-branch-agent!
              running agent-id
              (str "(ns rc.computed (:require [clojure.test :refer [deftest is]]))\n"
                   "(defn k2 \"Four.\" {:malli/schema [:=> [:cat] :int]} [] 4)\n"
                   "(deftest computed-test (is (= 4 ((requiring-resolve (symbol \"rc.computed\" (str \"k\" 2)))))))"))
           check "(select-keys (my.test/check {:seon.test/identities #{'rc.computed/computed-test}}) [:seon.test/passed? :seon.test/executed-count :seon.test/reused-count])"
           green (agent-value running agent-id check)
           _ (agent-value running agent-id
                          "(in-ns 'rc.computed)\n(defn k2 \"Five.\" {:malli/schema [:=> [:cat] :int]} [] 5)")
           checked (agent-value running agent-id check)]
       (is (true? (:seon.test/passed? green)) "the test is green against the first definition")
       (is (= 1 (:seon.test/executed-count checked)) "a changed function the body called by a computed name re-executes the test")
       (is (false? (:seon.test/passed? checked)) "the test is red against the redefinition")))))

(ns seon.test.redefined-callee-test
  "A test an agent defined runs its branch's current callee after the agent redefines it."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [seon.cluster.agent :as agent]
            [seon.mcp :as mcp]
            [seon.operator.runtime :as operator.runtime]
            [seon.test-support :as support]
            [sci.core :as sci]))

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

(deftest ^{:seon.test/long "Three eval requests of an own-branch agent, each with its automatic test step."
           :seon.test/long-ms 15000}
  a-test-step-runs-the-redefinitions-its-eval-request-made
  ;; The agent's base context held each evaluated root it was given at settlement, and the
  ;; agent context kept its own Vars of b and a after the base reacquired the program with
  ;; Vars of its own: the redefined test, analyzed against the agent's Var of a, read that
  ;; Var's first root in the test world forked from the base, so (a) was 11.
  (support/with-running-agent
   (fn [running _ _]
     (let [agent-id (str "stale-step-" (random-uuid))
           step (fn [code]
                  (support/mcp-tests (json/read-str (mcp/call (json/write-str {"name" "eval_clj"
                                                                                "arguments" {"code" code "agent" agent-id "cluster" running}})))))
           _ (support/own-branch-agent!
              running agent-id
              (str "(ns rc.stale (:require [clojure.test :refer [deftest is]]))\n"
                   "(defn b \"One.\" {:malli/schema [:=> [:cat] :int]} [] 1)\n"
                   "(defn a \"Ten more than b.\" {:malli/schema [:=> [:cat] :int]} [] (+ 10 (b)))\n"
                   "(deftest a-test (is (= 11 (a))))"))
           _ (step "(in-ns 'rc.stale)\n(defn b \"Two.\" {:malli/schema [:=> [:cat] :int]} [] 2)")
           redefined (step (str "(in-ns 'rc.stale)\n"
                                "(defn c \"A hundred.\" {:malli/schema [:=> [:cat] :int]} [] 100)\n"
                                "(defn a \"c plus b.\" {:malli/schema [:=> [:cat] :int]} [] (+ (c) (b)))\n"
                                "(deftest a-test (is (= 102 (a))))"))]
       (is (= {:seon.test/passed? true :seon.turn.loop/red []} (:seon.turn.loop/reaching redefined))
           "the step runs the bodies the eval request defined")))))

(deftest ^{:seon.test/long "Three eval requests of an own-branch agent, each with its automatic test step, and one branch acquisition."
           :seon.test/long-ms 15000}
  a-branch-acquired-from-a-writers-base-runs-the-program-its-head-holds
  ;; Settlement copied the agent's evaluated roots, analyzed against its own Vars, into its
  ;; branch's base context, which then claimed the head's program: a branch acquired from that
  ;; base read the first definition of b through the copied a, so (a) was 101 and a-test failed.
  (support/with-running-agent
   (fn [running _ _]
     (let [agent-id (str "base-writer-" (random-uuid))
           step (fn [code] (agent-value running agent-id code))
           {lane :seon.store/branch} (support/own-branch-agent!
                                      running agent-id
                                      (str "(ns rc.base (:require [clojure.test :refer [deftest is]]))\n"
                                           "(defn b \"One.\" {:malli/schema [:=> [:cat] :int]} [] 1)\n"
                                           "(defn a \"Ten more than b.\" {:malli/schema [:=> [:cat] :int]} [] (+ 10 (b)))\n"
                                           "(deftest a-test (is (= 11 (a))))"))
           _ (step "(in-ns 'rc.base)\n(defn b \"Two.\" {:malli/schema [:=> [:cat] :int]} [] 2)")
           _ (step (str "(in-ns 'rc.base)\n"
                        "(defn c \"A hundred.\" {:malli/schema [:=> [:cat] :int]} [] 100)\n"
                        "(defn a \"c plus b.\" {:malli/schema [:=> [:cat] :int]} [] (+ (c) (b)))\n"
                        "(deftest a-test (is (= 102 (a))))"))
           routing (:seon.agent/routing (:seon.turn.loop/cluster (get @operator.runtime/running-instances running)))
           execution (get-in @(get-in @routing [:seon.agent/children lane]) [:seon.agent/contexts [lane agent-id]])
           member (agent/acquire-context! execution nil {:seon.agent/isolate? true})]
       (try
         (is (= [102 [:pass]]
                (sci/eval-string* (sci/fork (:seon.sci.eval/ctx member))
                                  (str "(let [reported (atom [])]\n"
                                       "  (binding [clojure.test/report (fn [m] (swap! reported conj (:type m)))]\n"
                                       "    ((:test (meta #'rc.base/a-test))))\n"
                                       "  [(rc.base/a) @reported])")))
             "the acquired branch runs the bodies the writer's branch head holds")
         (finally (agent/release-context! member)))))))

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

(deftest ^{:seon/tasks #{'seon.test/a-test-reading-task-tags-reruns-when-a-test-is-tagged}
           :seon.test/long "Four eval requests of an own-branch agent, each with its automatic test step: 4.9 s measured on default's JVM (lane chain-3)."
           :seon.test/long-ms 15000}
  a-test-reading-task-tags-reruns-when-a-test-is-tagged
  ;; A Prove step's own proof reads the tags of the program it holds (TASK-CHAIN §3b): its red must not be
  ;; reused once the agent tags a test with its successor (chain experiment 3, attempts 285fdccc2fa6 and 02110a533e26).
  (support/with-running-agent
   (fn [running _ _]
     (let [agent-id (str "tag-reader-" (random-uuid))
           _ (support/own-branch-agent!
              running agent-id
              (str "(seon.task/create! {:seon.task/ns 'rc.tags :seon.task/name \"next-step\" :seon.task/plan \"The next step.\"})\n"
                   "(ns rc.tags (:require [clojure.test :refer [deftest is]]))\n"
                   "(deftest step-proof (is (seq (:seon.task/proofs (seon.task/obligations (seon.db/db) 'rc.tags/next-step)))))"))
           check "(select-keys (my.test/check {:seon.test/identities #{'rc.tags/step-proof}}) [:seon.test/passed? :seon.test/executed-count :seon.test/reused-count])"
           red (agent-value running agent-id check)
           _ (agent-value running agent-id "(in-ns 'rc.tags)\n(deftest ^{:seon/tasks #{'rc.tags/next-step}} product (is (= 1 2)))")
           checked (agent-value running agent-id check)]
       (is (false? (:seon.test/passed? red)) "the step proof is red while no test carries the successor's tag")
       (is (= 1 (:seon.test/executed-count checked)) "a tag written since the red result re-executes the step proof")
       (is (true? (:seon.test/passed? checked)) "the step proof is green once a test carries the successor's tag")))))

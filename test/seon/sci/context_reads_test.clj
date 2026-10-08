(ns seon.sci.context-reads-test
  "Host code reading a Var an agent's branch redefined reads it as the
  context it means binds it (`sci.ctx-store/with-ctx`), never as the cluster
  base it shares the Var with. Each row: main defines, a branch agent
  redefines through the MCP entrance (`seon.mcp/call`), and the host site
  answers the branch's definition."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [seon.mcp :as mcp]
            [seon.test-support :as support]))

(defn- step
  "The parsed items of `agent-id`'s eval request over `code`."
  {:malli/schema [:=> [:cat :string :string :string] [:sequential :seon.schema/value]]}
  [code agent-id running]
  (map #(support/reply (get % "text"))
       (get (json/read-str (mcp/call (json/write-str {"name" "eval_clj"
                                                      "arguments" {"code" code "agent" agent-id "cluster" running}})))
            "content")))

(defn- red
  "The tests an eval request's test step ran red, proofs and reaching tests."
  {:malli/schema [:=> [:cat [:sequential :seon.schema/value]] [:set :symbol]]}
  [items]
  (let [tests (some :seon.turn.loop/tests items)]
    (into #{} (mapcat #(get-in tests [% :seon.turn.loop/red])) [:seon.turn.loop/proofs :seon.turn.loop/reaching])))

(def ^:private busy
  "An interpreted 400 ms spin: the SCI time limit interrupts it (a host sleep it does not)."
  "(let [d (+ 400 (System/currentTimeMillis))] (loop [] (when (< (System/currentTimeMillis) d) (recur))))")

(deftest ^{:seon.test/long-ms 100 :seon.test/long "a fixture the next test redefines on a branch; its own body is trivial"}
  loaded-bounded
  ;; loaded from this file, so the cluster base holds it; a branch's
  ;; redefinition shares its Var with that base
  (is true))

(deftest ^{:seon/tasks #{'seon.sci.eval/an-inherited-test-never-sees-the-branch-redefinition-of-its-callee}
           :seon.test/long "two MCP eval requests, each running a test body that spins 400 ms in its test step"
           :seon.test/long-ms 15000}
  a-branch-redefinition-of-a-loaded-test-runs-under-its-own-bound
  ;; seon.test reads the test Var's `:seon.test/long-ms` (the SCI time limit)
  ;; as the member's context binds it, not as the loaded definition declared it
  (support/with-running-agent
   (fn [running agent-id _]
     (let [brancher (str "branch-" agent-id)
           prefix "(in-ns 'seon.sci.context-reads-test) "]
       (testing "the branch's redefinition declares a longer bound than its body takes"
         (let [items (step (str prefix "(clojure.test/deftest ^{:seon.test/long-ms 4000} loaded-bounded " busy
                                " (clojure.test/is true))")
                           brancher running)]
           (is (= [true #{}] [(get-in (some :seon.turn.loop/tests items) [:seon.turn.loop/reaching :seon.test/passed?])
                              (red items)])
               "the body ran green under the branch's 4000 ms bound, not the loaded 100 ms")))
       (testing "control: a body over its own declared bound is red"
         (is (contains? (red (step (str prefix "(clojure.test/deftest ^{:seon.test/long-ms 100} over " busy
                                        " (clojure.test/is true))")
                                   brancher running))
                        'seon.sci.context-reads-test/over)))))))

(defn- answered
  "The last form's value the entrance answers for `code` as `agent-id`."
  {:malli/schema [:=> [:cat :string :string :string] :seon.schema/value]}
  [code agent-id running]
  (:seon.repl/value (last (filter #(contains? % :seon.repl/value) (step code agent-id running)))))

(deftest a-host-thread-reads-the-current-definition
  ;; Clojure's Var roots are global: any thread reads the current root. An SCI
  ;; fn invoked on a thread with no `sci.ctx-store/*ctx*` reads the Var's own
  ;; field binding, which a redefinition in a later eval request never writes
  (support/with-running-agent
   (fn [running agent-id _]
     (let [prefix "(in-ns 'scratch.ctx-reads-thread) (clojure.core/refer-clojure) "]
       (answered (str prefix "(def holder 1) (defn priv [] :first)") agent-id running)
       (answered (str prefix "(def holder 2) (defn priv [] :second)") agent-id running)
       (is (= [[2 :second] [2 :second] [2 :second]]
              (answered (str prefix "[@(future [holder (priv)])"
                             " (let [p (promise)] (.start (Thread. (fn [] (deliver p [holder (priv)])))) (deref p 2000 :timeout))"
                             " (.get (.submit (java.util.concurrent.ForkJoinPool/commonPool) ^Callable (fn [] [holder (priv)])))]")
                        agent-id running)))))))

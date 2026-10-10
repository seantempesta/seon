(ns seon.sci.library-aliases-test
  "An agent in a file namespace resolves the libraries its ns form requires
  through their aliases and full names, as the namespace's compiled code does."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
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

(deftest ^{:seon.test/long "three MCP eval requests on a fresh agent branch"
           :seon.test/long-ms 15000}
  a-file-namespace-resolves-its-library-aliases
  ;; seon.error requires [malli.core :as m] and [malli.error :as me]; nothing
  ;; in the agent's requests requires either
  (support/with-running-agent
   (fn [running agent-id _]
     (let [prefix "(in-ns 'seon.error) "
           items (fn [code] (step (str prefix code) agent-id running))
           values (fn [code] (keep :seon.repl/value (items code)))]
       (is (= [:int :int]
              (rest (values "(m/type (m/schema :int)) (malli.core/type :int)"))))
       (let [defined (items (str "(defn alias-probe {:malli/schema [:=> [:cat :any] :string]} [x]"
                                 " (or (me/error-message {:schema (m/schema :int) :value x}) \"\"))"))]
         (is (= ['seon.error/alias-probe []]
                [(some #(get-in % [:seon.repl/value :seon.render.var/symbol]) defined)
                 (keep :seon.repl/error defined)])
             "the defn using the file's aliases is admitted without error"))
       (is (= "should be an integer" (last (values "(alias-probe :a)"))))))))

(deftest ^{:seon.test/long "two MCP eval requests on a fresh agent branch, the second running its branch test step"
           :seon.test/long-ms 15000}
  an-agent-in-a-test-namespace-writes-a-proof-with-its-helpers
  ;; my.program-test aliases seon.test-support as support and defines the
  ;; private helper shown-value; neither is required by the agent
  (support/with-running-agent
   (fn [running agent-id _]
     (let [values (fn [items] (keep :seon.repl/value items))
           entered (step "(in-ns 'my.program-test) [(fn? support/agent-value) (fn? shown-value)]" agent-id running)
           proof (step (str "(deftest agent-written-proof"
                            " (is (fn? support/agent-value)) (is (fn? shown-value)))")
                       agent-id running)]
       (is (= [true true] (last (values entered))) "the file's alias and private helper resolve")
       (is (= [] (keep :seon.repl/error proof)) "the deftest using them is admitted")
       (is (= {:seon.test/passed? true :seon.turn.loop/red []}
              (some #(get-in % [:seon.turn.loop/tests :seon.turn.loop/reaching]) proof))
           "the branch test step runs the new test green")))))

(deftest ^{:seon/tasks #{'seon.sci.lookup/an-agent-runs-its-proofs-with-clojure-test}
           :seon.test/long "one MCP eval request on a fresh agent branch that runs a named test through my.test/check"
           :seon.test/long-ms 15000}
  an-agent-requires-a-test-namespace-and-runs-its-deftest
  ;; seon.id-test is a test namespace the agent's base context holds empty
  (support/with-running-agent
   (fn [running agent-id _]
     (let [items (step (str "(require 'seon.id-test)"
                            " (def run (clojure.test/run-test-var #'seon.id-test/a-host-object-has-no-identity))"
                            " [(select-keys run [:test :fail :error :type]) (mapv :seon.test/sym (:seon.test/results run))"
                            "  (string? (:seon.test.run/id run))]")
                       agent-id running)]
       (is (= [] (keep :seon.repl/error items)) "the require and the run answer without error")
       (is (= [{:test 1 :fail 0 :error 0 :type :summary} ['seon.id-test/a-host-object-has-no-identity] true]
              (last (keep :seon.repl/value items)))
           "the deftest ran through the recorded test path and answers its summary")))))

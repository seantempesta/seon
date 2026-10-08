(ns seon.armed-calls-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [seon.mcp :as mcp]
            [seon.test-support :as support]))

(deftest ^{:seon/tasks #{'seon.repl/the-slow-fix-ranks-the-whole-process}}
  a-slow-reply-carries-no-armed-call-counts
  (support/with-running-agent
   (fn [running agent-id _]
     (let [call (fn [code]
                  (let [reply (json/read-str
                               (mcp/call (json/write-str
                                          {"name" "eval_clj"
                                           "arguments" {"cluster" running "agent" agent-id "code" code}})))]
                    (support/mcp-reply reply)))]
       (call "(defn pf {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))")
       (let [reply (call "(do (Thread/sleep 1100) (dorun (map pf (range 300))) 1)")]
         (is (= :slow (-> reply :seon.repl/findings first :seon.repl/finding)))
         (is (empty? (select-keys reply [:seon.repl/calls :seon.repl/repeated]))))))))

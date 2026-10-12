(ns seon.uncontracted-warning-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [seon.mcp :as mcp]
            [seon.test-support :as support]))

(defn- finding-of
  "The reply's `:seon.repl/findings` entry of kind `:uncontracted`, or nil."
  [reply]
  (some #(when (= :uncontracted (:seon.repl/finding %)) %) (:seon.repl/findings reply)))

^{:seon/tasks '[seon.fn/an-uncontracted-defn-is-admitted-without-its-warning]}
(deftest an-uncontracted-defn-reply-warns-with-the-growing-count
  (support/with-running-agent
   (fn [running agent-id _]
     (let [call (fn [code]
                  (support/mcp-reply
                   (json/read-str (mcp/call (json/write-str {"name" "eval_clj"
                                                             "arguments" {"cluster" running "agent" agent-id "code" code}})))))
           first-reply (call "(in-ns 'my.uw) (defn one [x] x)")
           second-reply (call "(defn two [x] x)")
           contracted (call "(defn three {:malli/schema [:=> [:cat :int] :int]} [x] x)")]
       (is (= 1 (:seon.eval/uncontracted-count (finding-of first-reply))))
       (is (= #{'my.uw/one} (set (:seon.eval/uncontracted (finding-of first-reply)))))
       (is (= 2 (:seon.eval/uncontracted-count (finding-of second-reply))))
       (is (nil? (finding-of contracted)))))))

(deftest an-any-slot-defn-reply-names-the-slot
  (support/with-running-agent
   (fn [running agent-id _]
     (let [call (fn [code]
                  (support/mcp-reply
                   (json/read-str (mcp/call (json/write-str {"name" "eval_clj"
                                                             "arguments" {"cluster" running "agent" agent-id "code" code}})))))
           reply (call "(in-ns 'my.uwa) (defn loose {:malli/schema [:=> [:cat :any] :int]} [x] 1)")]
       (is (= #{['my.uwa/loose :seon.fn.contract.finding/any :seon.fn.contract.position/input 0]}
              (set (:seon.eval/contract-gaps (finding-of reply)))))))))

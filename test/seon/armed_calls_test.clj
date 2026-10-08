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

(deftest ^{:seon/tasks #{'seon.instrument/a-refusal-of-a-database-argument-cannot-be-built}}
  a-database-passed-for-a-connection-is-an-ordinary-argument-refusal
  (support/with-running-agent
   (fn [running agent-id _]
     (let [reply (support/mcp-reply
                  (json/read-str
                   (mcp/call (json/write-str
                              {"name" "eval_clj"
                               "arguments" {"cluster" running "agent" agent-id "mode" "jvm"
                                            "code" "(seon.db/transact! (seon.db/db (seon.cluster.boot/connection \"default\")) [])"}}))))
           error (:seon.repl/error reply)]
       (is (= :seon.instrument/contract-error (:seon.error/type error)))
       (is (not (re-find #"cannot be cast" (str (:seon.error/message error))))
           "the refusal is built, not the class cast of walking a database")))))

(deftest ^{:seon/tasks #{'seon.sci.eval/a-quoted-alias-refuses-the-whole-requests-program-facts}}
  a-quoted-alias-is-refused-at-its-own-form-and-the-rest-stands
  (support/with-running-agent
   (fn [running agent-id _]
     (let [replies (support/mcp-replies
                    (json/read-str
                     (mcp/call (json/write-str
                                {"name" "eval_clj"
                                 "arguments" {"cluster" running "agent" agent-id
                                              "code" (str "(ns scratch.quoted-alias.util)"
                                                          "(defn clamp {:malli/schema [:=> [:cat :int] :int]} [x] x)"
                                                          "(ns scratch.quoted-alias.app (:require [scratch.quoted-alias.util :as u]))"
                                                          "(require '[scratch.quoted-alias.util :as 'u])"
                                                          "(defn bounded {:malli/schema [:=> [:cat :int] :int]} [x] (u/clamp x))")}}))))]
       (is (= [:seon.repl/error] (keep #(when (:seon.repl/error %) :seon.repl/error) replies))
           "only the quoted require answers an error")
       (is (empty? (mapcat :seon.repl/diagnostics replies))
           "no form's program facts are refused")))))

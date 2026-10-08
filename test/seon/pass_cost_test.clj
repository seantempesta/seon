(ns seon.pass-cost-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.mcp :as mcp]
            [seon.profile :as profile]
            [seon.test-support :as support]))

(deftest ^{:seon/tasks #{'seon.turn/each-form-of-a-pass-costs-megabytes}}
  a-pass-reads-its-terminal-ordinal-once
  (support/with-running-agent
   (fn [running agent-id _connection]
     (doseq [n [1 10]]
       (let [request (json/write-str
                      {"name" "eval_clj"
                       "arguments" {"code" (str/join "\n" (repeat n "(+ 1 1)"))
                                    "agent" agent-id "cluster" running}})
             mark (profile/begin)
             reply (json/read-str (mcp/call request))
             measured (profile/explain mark 100000)
             calls (reduce + 0 (keep #(when (= 'seon.turn/terminal-agent-ordinal (:seon.profile/sym %))
                                       (:seon.profile/calls %))
                                    (:seon.profile/by-calls measured)))
             values (support/mcp-replies reply)]
         (is (false? (get reply "isError")))
         (is (= (vec (repeat n 2)) (mapv :seon.repl/value values)))
         (is (every? #(not (contains? % :seon.repl/diagnostics)) values))
         (is (= 1 calls)))))))

(deftest ^{:seon/tasks #{'seon.turn/each-form-of-a-pass-costs-megabytes}}
  jvm-render-settings-are-acquired-once-per-pass
  (support/with-running-agent
   (fn [running agent-id _connection]
     (let [counts
           (mapv
            (fn [n]
              (let [request (json/write-str
                             {"name" "eval_clj"
                              "arguments" {"code" (str/join "\n" (repeat n "(+ 1 1)"))
                                           "mode" "jvm" "agent" agent-id "cluster" running}})
                    mark (profile/begin)
                    reply (json/read-str (mcp/call request))
                    measured (profile/explain mark 100000)
                    values (support/mcp-replies reply)]
                (is (false? (get reply "isError")))
                (is (= (vec (repeat n 2)) (mapv :seon.repl/value values)))
                (is (every? #(not (contains? % :seon.repl/diagnostics)) values))
                (reduce + 0 (keep #(when (= 'seon.config/effective (:seon.profile/sym %))
                                    (:seon.profile/calls %))
                                 (:seon.profile/by-calls measured)))))
            [1 10])]
       (is (pos? (first counts)))
       (is (<= (second counts) (+ 2 (first counts))))))))

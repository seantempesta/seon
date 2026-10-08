(ns seon.schema.register-cost-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [seon.mcp :as mcp]
            [seon.profile :as profile]
            [seon.test-support :as support]))

(deftest ^{:seon/tasks #{'seon.cluster.branch-evidence/a-schema-registered-on-a-branch-is-invisible-to-its-test-run}}
  an-agent-registration-admits-one-identity-incrementally
  (support/with-running-agent
   (fn [running agent-id _connection]
     (let [request (json/write-str
                    {"name" "eval_clj"
                     "arguments" {"code" "(seon.schema/register! :scratch.register-cost/probe [:map [:scratch.register-cost/n :int]])"
                                  "agent" agent-id "cluster" running}})
           mark (profile/begin)
           reply (json/read-str (mcp/call request))
           measured (profile/explain mark 100000)
           calls (fn [sym] (reduce + 0 (keep #(when (= sym (:seon.profile/sym %)) (:seon.profile/calls %))
                                             (:seon.profile/by-calls measured))))
           value (:seon.repl/value (support/reply (get-in reply ["content" 0 "text"])))]
       (is (false? (get reply "isError")))
       (is (= :scratch.register-cost/probe (:seon.schema/key value)))
       (is (pos? (calls 'seon.schema/projection-with-schema))
           "the registration is admitted against the active projection")
       ;; The whole-population gate reads every one of ~3,400 declarations'
       ;; references; one admitted identity reads its own few.
       (is (< (calls 'seon.schema/contract-references) 100)
           "no whole-population gate runs for one agent registration")))))

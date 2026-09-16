(ns my.test-test
  "An agent running its own declared tests keeps its cluster's custody.

  `my.test/run` is what an AGENT calls inside its own evaluation, and inside
  an evaluation the elided `seon.db` arity is the documented affordance
  (AGENTS §3). The run therefore has to reach the agent's cluster — but the
  Var runs behind a `bound-fn` on a virtual thread, so for a day it reached
  whichever cluster happened to be bound on the creating thread, and then,
  once that inheritance was removed, nothing at all
  (`docs/seon/issues/an-agents-own-test-loses-its-clusters-custody.md`).
  The connection is now a VALUE the caller hands the one run seam, and this
  asserts the agent's half of it end to end: a real SCI evaluation, the
  agent's own `deftest`, its own declared test roster, its own cluster."
  (:require [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.config :as config]
            [seon.db :as db]
            [seon.test-support :as support]))

(deftest an-agents-own-test-reaches-its-cluster-through-the-elided-arity
  (support/with-database
   (fn [connection]
     (config/apply! {:seon.db/connection connection
                     :seon.boot/cluster-name "own-tests"
                     :seon.config/manifest {:seon.config.ai/no-provider true}})
     (support/transacted! connection [{:seon.agent/id "owner"
                                       :seon.agent/namespace
                                       {:seon.ns/name 'my.agents.owner}}])
     (cluster/ensure-cluster-entity! connection "own-tests"
                                     cluster/boot-process-identity)
     (let [ctx (support/fork-cluster-ctx connection)
           basis (db/basis-t (db/db connection))
           declared
           (support/agent-value
            ctx
            (str "(deftest my-cluster-is-reachable"
                 "  (is (= " basis " (seon.db/basis-t (seon.db/db)))"
                 "      (pr-str (seon.db/db))))")
            'my.agents.owner)
           results (support/agent-value ctx "(my.test/run)" 'my.agents.owner)]
       (is (not (:seon.error/kind declared))
           (str "the agent's own deftest is admitted: " (pr-str declared)))
       (is (vector? results)
           (str "my.test/run answers its declared tests: " (pr-str results)))
       (let [result (first results)]
         (is (= "my.agents.owner/my-cluster-is-reachable"
                (:seon.test/sym result))
             (str "the agent's one declared test ran: " (pr-str results)))
         (is (= 1 (:seon.test/pass-count result))
             (str "and its elided read reached the agent's own cluster, "
                  "instead of refusing with no connection bound: "
                  (pr-str result)))
         (is (= 0 (+ (:seon.test/fail-count result)
                     (:seon.test/error-count result)))
             (pr-str result)))))))

(ns seon.cluster.entrance-supplier-test
  (:require [clojure.test :refer [deftest is]]
            [sci.core :as sci.core]
            [my.program :as program]
            [seon.cluster.acquisition-test :as acquisition-test]
            [seon.cluster.agent :as agent]
            [seon.cluster.registry :as registry]
            [seon.config :as config]
            [seon.db :as db]
            [seon.env :as env]
            [seon.sci.eval :as sci]
            [seon.test-support :as support]))

(defn caller-context
  "Expose the argument supplied to this ordinary caller for the host assertion."
  {:malli/schema [:=> [:cat :my.program/context] :my.program/context]}
  [context]
  context)

(defn supplied-acquisition-proof
  "An ordinary supplied-default caller needs no separate acquisition plumbing."
  {:malli/schema [:=> [:cat :my.program/context] :map]}
  [context]
  (let [connection (:seon.db/connection context)
        captured (db/commit-id (db/db connection))
        held (:seon.store/store context)
        execution (agent/acquire-context!
                   context nil {:seon.agent/isolate? true
                                :seon.cluster.registry/from captured})
        branch (:seon.agent/branch execution)
        before (contains? (registry/roster held) branch)
        result
        (try
          (let [evaluation
                (sci/evaluate
                 {:seon.sci.eval/ctx (:seon.sci.eval/ctx execution)
                  :seon.db/db (db/db (:seon.db/connection execution))
                  :seon.cluster.eval/ns [:seon.ns/name 'seon.cluster.entrance-supplier-test]
                  :seon.cluster.eval/source "(+ 20 22)"
                  :seon.sci.eval/time-limit-ms 5000
                  :seon.sci.admit/caps (config/result-caps config/defaults)
                  :seon.config/on-core-error :panic})
                supplied (program/supplied-context
                          (env/scope (env/of (:seon.sci.eval/ctx execution))
                                     {:my.program/executing-ctx (:seon.sci.eval/ctx execution)}))]
            {:seon.proof/value (:seon.sci.admit/value evaluation)
             :seon.proof/captured? (= captured (:seon.source/commit-id execution))
             :seon.proof/rostered? before
             :seon.proof/isolated? (not (identical? connection (:seon.db/connection execution)))
             :seon.proof/carried? (and (identical? held (:seon.store/store supplied))
                                      (identical? (:seon.agent/context-state context)
                                                  (:seon.agent/context-state supplied))
                                      (identical? (:seon.db/connection execution)
                                                  (:seon.db/connection supplied)))})
          (finally (agent/release-context! execution)))]
    (assoc result :seon.proof/unlinked? (not (contains? (registry/roster held) branch)))))

(deftest supplied-context-acquires-and-releases-an-isolated-evaluation
  (#'acquisition-test/with-published-store
   (fn [connection held]
    (db/call-with-custody {:seon.db/connection connection}
     (fn []
     (support/seed-cluster! connection "acquisition")
     (support/transacted!
      connection
      (agent/creation-tx {:seon.agent/id "supplier-live"
                         :seon.cluster/name "acquisition"
                         :seon.ns/name 'seon.cluster.entrance-supplier-test}))
     (let [ctx (support/fork-cluster-ctx connection "acquisition")
           source {:seon.db/connection connection :seon.cluster/name "acquisition"
                   :seon.sci.eval/ctx ctx :seon.agent/context-state (atom {})
                   :seon.store/store held}
           live (agent/acquire-context! source "supplier-live")]
       (try
         (is (identical? connection (:seon.db/connection live)))
         (is (= :live (:seon.agent/mode live)))
         (is (false? (:seon.agent/owns-branch? live)))
         (let [started (System/nanoTime)
               context (sci.core/eval-string*
                        (:seon.sci.eval/ctx live)
                        "(seon.cluster.entrance-supplier-test/caller-context)")
               result (supplied-acquisition-proof context)]
           (println "SUPPLIED-ACQUISITION-MS" (/ (- (System/nanoTime) started) 1e6))
           (is (= {:seon.proof/value 42 :seon.proof/captured? true
                   :seon.proof/rostered? true :seon.proof/isolated? true
                   :seon.proof/carried? true :seon.proof/unlinked? true}
                  result)))
         (finally (agent/release-context! live)))
       (is (some? (db/commit-id (db/db connection))))))))))

(ns seon.sci.desk-test
  "Recurring acceptance for agent-scoped SCI desk facts."
  (:require [clojure.test :refer [deftest is testing]]
            [sci.core :as sci]
            [seon.cluster.loop :as loop]
            [seon.cluster.run :as run]
            [seon.config :as config]
            [seon.db :as db]
            [seon.sci.eval :as eval]
            [seon.test-support :as test-support]))

(def ^:private caps (config/result-caps (config/defaults)))

(defn- evaluate!
  [ctx namespace-name source]
  (eval/evaluate
   {:seon.cluster.run.form/source source
    :seon.cluster.run.form/ns [:seon.ns/name namespace-name]
    :seon.sci.eval/ctx ctx
    :seon.sci.admit/caps caps
    :seon.sci.eval/time-limit-ms 30000
    :seon.config/on-core-error :panic}))

(defn- desk-row
  [agent-id namespace-name intern-name attributes]
  (let [qualified (str (symbol (str namespace-name) (str intern-name)))]
    (merge
     {:seon.def/key (pr-str [agent-id qualified])
      :seon.def/id qualified
      :seon.def/agent [:seon.cluster.agent/id agent-id]
      :seon.def/ns [:seon.ns/name namespace-name]
      :seon.def/name intern-name
      :seon.def/ordinal 0
      :seon.schema.admission/source :agent}
     attributes)))

(deftest atom-snapshots-are-emitted-after-in-place-mutation
  (let [ctx (eval/build-base-ctx)
        created (evaluate! ctx 'user "(def scratch (atom 1))")
        mutated (evaluate! ctx 'user "(swap! scratch inc)")]
    (is (= [{:seon.def/id "user/scratch"
             :seon.def/atom? true
             :seon.sci.eval/value 1}]
           (mapv #(select-keys % [:seon.def/id :seon.def/atom?
                                  :seon.sci.eval/value])
                 (:seon.sci.eval/desk-defs created))))
    (is (= 2 (get-in mutated [:seon.sci.eval/desk-defs 0
                              :seon.sci.eval/value]))
        "the identical atom root still emits its newly settled snapshot")))

(deftest turn-forks-rehydrate-only-the-selected-agent-and-state-loss
  (test-support/with-database
   (fn [connection]
     (let [namespace-name 'my.agents.desk
           agent-a "desk-a"
           agent-b "desk-b"]
       (db/transact!
        connection
        {:tx-data
         [{:seon.cluster.agent/id agent-a
           :seon.cluster.agent/namespace
           {:seon.ns/name namespace-name}}
          {:seon.cluster.agent/id agent-b
           :seon.cluster.agent/namespace
           {:seon.ns/name 'my.agents.other}}
          (desk-row agent-a namespace-name 'helper
                    {:seon.def/source "(def helper (fn [x] (inc x)))"})
          (desk-row agent-a namespace-name 'data
                    {:seon.def/value-edn "{:answer 42}"})
          (desk-row agent-a namespace-name 'scratch
                    {:seon.def/value-edn "7" :seon.def/atom? true})
          (desk-row agent-a namespace-name 'lost
                    {:seon.def/unrestorable-reason "not store-faithful"})
          (desk-row agent-b 'my.agents.other 'data
                    {:seon.def/value-edn "99"})]})
       (let [base (eval/cluster-ctx @connection connection)
             a (eval/fork-for-turn
                {:seon.sci.eval/ctx base
                 :seon.db/db @connection
                 :seon.db/connection connection
                 :seon.cluster.agent/id agent-a})
             ctx (:seon.sci.eval/ctx a)
             resolve-root #(some-> (sci/resolve ctx %) deref)]
         (is (nil? (sci/resolve base 'my.agents.desk/data))
             "the live base remains program-only")
         (is (= 5 ((resolve-root 'my.agents.desk/helper) 4)))
         (is (= {:answer 42} (resolve-root 'my.agents.desk/data)))
         (is (= 7 @(resolve-root 'my.agents.desk/scratch)))
         (is (= ["could not restore `lost`: not store-faithful"
                 "restored `scratch` from its last settled value"]
                (:seon.sci.eval/desk-notices a))))
       (testing "clearing is explicit and the next turn takes the same path"
         (db/transact! connection
                       {:tx-data
                        (run/clear-desk-tx
                         {:seon.def/agent
                          [:seon.cluster.agent/id agent-a]})})
         (let [after-clear
               (eval/fork-for-turn
                {:seon.sci.eval/ctx (eval/cluster-ctx @connection connection)
                 :seon.db/db @connection
                 :seon.db/connection connection
                 :seon.cluster.agent/id agent-a})]
           (is (nil? (sci/resolve (:seon.sci.eval/ctx after-clear)
                                  'my.agents.desk/data)))
           (is (empty? (:seon.sci.eval/desk-notices after-clear)))))))))

(deftest restore-ladder-prefers-a-pure-form-and-forces-atom-snapshots
  (test-support/with-database
   (fn [connection]
     (db/transact! connection
                   {:tx-data [{:seon.config.eval.result/blob-threshold 32768}]})
     (let [ordinary
           {:seon.sci.eval/desk-defs
            [{:seon.def/id "user/data"
              :seon.def/ns [:seon.ns/name 'user]
              :seon.def/name 'data
              :seon.def/source "(def data {:answer 42})"
              :seon.sci.eval/value {:answer 42}
              :seon.sci.eval/referenced-vars #{}
              :seon.sci.eval/unproven-called-vars #{}
              :seon.sci.eval/nondeterministic-calls #{}
              :seon.sci.eval/impure-calls #{}}]
            :seon.sci.admit/record
            {:seon.eval/outcome :ok :seon.eval/host-interop-count 0}}
           atom-evaluation
           (assoc-in ordinary [:seon.sci.eval/desk-defs 0]
                     (assoc (get-in ordinary [:seon.sci.eval/desk-defs 0])
                            :seon.def/id "user/scratch"
                            :seon.def/name 'scratch
                            :seon.def/atom? true
                            :seon.sci.eval/value 9))
           store-values (deref #'loop/store-desk-values!)
           stored-ordinary (store-values connection ordinary)
           stored-atom (store-values connection atom-evaluation)
           ordinary-row (first (#'loop/desk-rows @connection "agent"
                                                   stored-ordinary 0))
           atom-row (first (#'loop/desk-rows @connection "agent"
                                               stored-atom 1))]
       (is (contains? ordinary-row :seon.def/source))
       (is (not (contains? ordinary-row :seon.def/value-edn)))
       (is (true? (:seon.def/atom? atom-row)))
       (is (= "9" (:seon.def/value-edn atom-row)))
       (is (not (contains? atom-row :seon.def/source)))))))

(ns seon.render.retained-test
  (:require [clojure.test :refer [deftest is]]
            [sci.core :as sci]
            [seon.config :as config]
            [seon.db :as db]
            [seon.render :as render]
            [seon.test-support :as support]))

(deftest identical-database-retains-reads-without-replaying-them
  (support/with-database
   (fn [connection]
     (let [namespace-name 'seon.render-simplification.fixture-a
           _ (db/transact! connection [{:seon.ns/name namespace-name
                                       :seon.ns/doc "one"}])
           ctx (support/fork-cluster-ctx connection)
           profile (render/agent-render-profile (config/defaults))
           caps (config/result-caps (config/defaults))
           calls (atom {})
           checks (atom 0)
           read-current? db/read-evidence-current?
           call-id [::namespace]
           request (fn []
                     {:seon.db/db @connection
                      :seon.sci.eval/ctx ctx
                      :seon.render/namespace namespace-name
                      :seon.render/value {:seon.ns/name namespace-name}
                      :seon.render/output :seon.render/ai
                      :seon.render/profile profile
                      :seon.render.call/id call-id
                      :seon.render/retained-calls @calls
                      :seon.render/captured-calls calls
                      :seon.render/candidate-call-ids #{call-id}
                      :seon.sci.admit/caps caps
                      :seon.sci.eval/time-limit-ms 2000
                      :seon.config/on-core-error :panic})]
       (sci/binding [sci/ns (sci/create-ns namespace-name)]
         (sci/eval-form
          ctx
          '(defn namespace-ai [value]
             (seon.db/q '[:find ?doc . :in $ ?name
                          :where [?namespace :seon.ns/name ?name]
                                 [?namespace :seon.ns/doc ?doc]]
                        (:seon.db/db value) (:seon.ns/name value)))))
       (is (= "one" (render/render-call (request))))
       (is (seq (:seon.render.call/read-evidence (get @calls call-id))))
       (with-redefs [db/read-evidence-current?
                     (fn [database evidence]
                       (swap! checks inc)
                       (read-current? database evidence))]
         (is (= "one" (render/render-call (request))))
         (is (zero? @checks) "an identical immutable database cannot change a read")
         (db/transact! connection [{:seon.ns/name namespace-name :seon.ns/doc "two"}])
         (is (= "two" (render/render-call (request))))
         (is (pos? @checks) "a changed database still validates dependencies"))))))

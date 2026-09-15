(ns seon.render.retained-test
  (:require [clojure.test :refer [deftest is]]
            [sci.core :as sci]
            [seon.config :as config]
            [seon.db :as db]
            [seon.render :as render]
            [seon.test-support :as support]))

(deftest equal-committed-database-skips-read-replay
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
                     {:seon.db/db (db/db connection)
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
       (let [a (:seon.db/db (request)) b (:seon.db/db (request))]
         (is (not (identical? a b)))
         (is (= a b))
         (is (render/same-committed-database? a b))
         (is (not (render/same-committed-database? a (db/history b))))
         (is (not (render/same-committed-database? a (db/since b (:max-tx b))))))
       (is (= "one" (render/render-call (request))))
       (is (seq (:seon.render.call/read-evidence (get @calls call-id))))
       (with-redefs [db/read-evidence-current?
                     (fn [database evidence]
                       (swap! checks inc)
                       (read-current? database evidence))]
         (is (= "one" (render/render-call (request))))
         (is (zero? @checks) "equal committed wrappers must not replay reads")
         (db/transact! connection [{:seon.ns/name namespace-name :seon.ns/doc "two"}])
         (is (= "two" (render/render-call (request))))
         (is (pos? @checks) "a changed database still validates dependencies")
         (let [previous (get @calls call-id)]
           (db/transact! connection [{:seon.cluster/name "retained-program"
                                      :seon.source/commit-id #uuid "f54229d7-54eb-472d-9ae8-917a0f97af71"}])
           (is (= "two" (render/render-call (request))))
           (is (not= (:seon.render/source-generation previous)
                     (:seon.render/source-generation (get @calls call-id)))
               "a changed adopted program invalidates the retained evidence"))
         (support/with-database
          (fn [other]
            (db/transact! other [{:seon.ns/name namespace-name :seon.ns/doc "other connection"}])
            (is (not (render/same-committed-database? (db/db connection) (db/db other))))
            (is (= "other connection"
                   (render/render-call (assoc (request) :seon.db/db (db/db other))))))))))))

(ns seon.repl.autocomplete-test
  "Turn-example curation tests."
  (:require
   [cljs.test :refer [async deftest is]]
   [seon.db :as db]
   [seon.repl.autocomplete :as autocomplete]))

(def ^:private database
  {:db-name "default"
   :t 10
   :as-of nil
   :since nil
   :history false
   :datahike/commit-id #uuid "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"})

(deftest rate-consumes-one-database-value
  (async done
    (let [original-db db/db
          original-entity db/entity
          original-transact db/transact!]
      (set! db/db
            (fn
              ([] (js/Promise.resolve database))
              ([_request] (js/Promise.resolve database))))
      (set! db/entity
            (fn
              ([_eid]
               (js/Promise.resolve {:seon.agent.turn/id "turn-1"}))
              ([_database _eid]
               (js/Promise.resolve {:seon.agent.turn/id "turn-1"}))))
      (set! db/transact!
            (fn [& requests]
              (let [request (first requests)]
                (is (= database (:seon.db/db request)))
              (is (= [{:seon.agent.turn/id "turn-1"
                       :seon.repl.autocomplete/rating :gold}]
                       (:seon.db/tx-data request))))
              (js/Promise.resolve
               {:db-before database :db-after database})))
      (-> (autocomplete/rate!
           {:seon.agent.turn/id "turn-1"
            :seon.repl.autocomplete/rating :gold})
          (.then
           #(is (true? (:seon.repl.autocomplete/ok? %))))
          (.catch #(is false (str %)))
          (.finally
           (fn []
             (set! db/db original-db)
             (set! db/entity original-entity)
             (set! db/transact! original-transact)
             (done)))))))

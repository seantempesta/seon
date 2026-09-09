(ns seon.returned-error-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.config :as config]
            [seon.db :as db]
            [seon.repl :as repl]
            [seon.sci.eval :as evaluation]
            [seon.test-support :as support]))

(deftest returned-refusal-is-a-schema-first-error-with-unchanged-history
  (support/with-database
   (fn [connection]
     (config/apply! {:seon.db/connection connection
                    :seon.boot/cluster-name "returned-error"})
     (let [setup (db/transact! connection
                               [[:db/add "cluster" :seon.cluster/name "returned-error"]
                                {:my.plan.item/id "error/item" :my.plan.item/title "Verify"}])
           _ (is (:db-after setup) (pr-str setup))
           ctx (support/fork-cluster-ctx connection "returned-error")
           configuration (support/effective-config)
           source "(seon.db/transact! [[:db/add [:my.plan.item/id \"error/item\"] :my.plan.item/title 42]])"
           before (db/basis-t @connection)
           result (evaluation/evaluate
                   {:seon.cluster.eval/source source
                    :seon.sci.eval/ctx ctx
                    :seon.db/db @connection
                    :seon.sci.admit/caps (config/result-caps configuration)
                    :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms configuration)
                    :seon.config/on-core-error :panic})
           shown (:seon.eval/value result)
           response (repl/response result)
           parsed (edn/read-string response)
           diagnostic (:seon.sci.admit/value result)]
       (println "RETURNED-ERROR"
                (pr-str {:shown shown :bytes (alength (.getBytes shown "UTF-8"))
                         :response response}))
       (is (= :seon.db/invalid-write (:seon.error/kind diagnostic)) (pr-str result))
       (is (:seon.schema/form diagnostic))
       (is (= 42 (:seon.db/offending diagnostic)))
       (is (str/starts-with? shown "Expected:") shown)
       (is (< (alength (.getBytes shown "UTF-8")) 300) shown)
       (is (= shown (:seon.repl/error parsed)) response)
       (is (not (contains? parsed :seon.repl/value)) response)
       (is (= before (db/basis-t @connection)))
       (is (= "Verify" (:my.plan.item/title
                        (db/pull @connection [:my.plan.item/title]
                                 [:my.plan.item/id "error/item"]))))
       (is (= response
              (repl/response (dissoc result :seon.sci.admit/value :seon.sci.eval/ctx))))
       (is (= "Expected: saved schema\nGot: saved value"
              (:seon.repl/error
               (edn/read-string
                (repl/response {:seon.cluster.eval/error "saved refusal"
                                :seon.eval/value "Expected: saved schema\nGot: saved value"})))))))))

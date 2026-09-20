(ns seon.cluster.publication-convergence-test
  (:require [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.db :as db]
            [seon.test-support :as support]))

(deftest recorded-adoption-skips-all-reconciliation-and-reload
  (support/with-database
   (fn [connection]
     (let [name "publication-convergence"
           commit (java.util.UUID/randomUUID)]
       (support/seed-cluster! connection name)
       (support/transacted! connection
                            [{:db/id [:seon.cluster/name name]
                              :seon.source/commit-id commit}])
       (let [before (db/basis-t (db/db connection))
             instance {:seon.boot/cluster-connection connection
                       :seon.boot/advertisement {:seon.boot/cluster-name name}}
             published {:seon.source/commit-id commit}
             entries (atom [])
             observe (fn [label f]
                       (fn [& args]
                         (swap! entries conj label)
                         (apply f args)))]
         (with-redefs-fn
           {#'cluster/load-development-definitions!
            (observe :reload @#'cluster/load-development-definitions!)
            #'cluster/declaration-changes
            (observe :reconcile @#'cluster/declaration-changes)}
           (fn []
             ;; No source store or SCI context is needed once the durable
             ;; adoption fact names this publication. Both calls must return.
             (dotimes [_ 2]
               (is (nil? (#'cluster/development-source-refresh!
                          nil instance published published [] {}))))))
         (is (empty? @entries))
         (is (= before (db/basis-t (db/db connection)))))))))

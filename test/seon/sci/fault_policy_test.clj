(ns seon.sci.fault-policy-test
  (:require [clojure.test :refer [deftest is]]
            [sci.core :as sci]
            [seon.db :as db]
            [seon.instrument :as instrument]
            [seon.schema :as schema]
            [seon.sci.eval :as eval]
            [seon.test-support :as support]
            [seon.print.value]))

(deftest an-unconfigured-cluster-refuses-arming-instead-of-defaulting-its-fault-policy
  ;; The branch's value as of the transaction before its first config entity
  ;; holds the installed schema and no config, so no cluster's effective
  ;; config can be read.
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           first-config (db/q '[:find (min ?tx) . :where [_ :seon.config/cluster _ ?tx]]
                              (db/history database))
           unconfigured (db/as-of database (dec first-config))
           projection (schema/handed-projection)
           ctx (eval/build-base-ctx projection)
           source "(defn ^{:malli/schema [:=> [:cat :int] :int]} unconfigured [x] x)"
           _ (sci/eval-string* ctx source)
           entity (support/program-fn-entity database 'user/unconfigured source)
           failure (support/refusal-data
                    #(#'eval/install-function-contract! ctx entity projection unconfigured))]
       (is (:seon.fn/spec entity) "The subject declares a contract to arm.")
       (is (= :seon.boot/cluster-name (:seon.config/error-key failure)) (seon.print.value/bounded-text failure))
       (is (= 'seon.sci.eval/database-effective-config (:seon.error/operation failure)))
       (is (not (:seon.instrument/registration-observation failure))
           "The refusal is the config read's, not a record-mode recorder demand.")
       (is (not (::instrument/interpreted-original (meta @(sci/resolve ctx 'user/unconfigured))))
           "Nothing was armed under a guessed fault policy.")))))

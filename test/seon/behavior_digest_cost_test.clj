(ns seon.behavior-digest-cost-test
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.profile :as profile]
            [seon.test-support :as support]))

(deftest the-functions-of-a-namespace-share-one-resolver-context-derivation
  (support/with-running-agent
   (fn [_ _ connection]
     (support/declare-source!
      connection 'tq.bdc
      (str "(ns tq.bdc (:require [clojure.string :as str]))\n"
           (apply str (for [n (range 6)]
                        (str "(defn f" n " \"Doc " n ".\" [x] (str/trim x))\n")))))
     (let [database (db/db connection)
           entities (mapv :e (filter #(re-find #"^tq\.bdc/f" (str (:v %)))
                                     (db/datoms database :aevt :seon.fn/sym)))
           measured (profile/measure #(mapv (partial db/behavior-digest database) entities))]
       (is (= 6 (count entities)))
       (is (= 6 (count (distinct (:seon.profile/value measured)))))
       (is (= 7 (profile/calls-of measured 'seon.db/declaration-value))
           "six functions and their one namespace")))))

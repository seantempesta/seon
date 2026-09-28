(ns seon.health-storage-test
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.health :as health]
            [seon.profile :as profile]
            [seon.profile.measurement :as measurement]
            [seon.test-support :as support])
  (:import [java.util.concurrent.atomic LongAdder]))

(deftest stored-regressions-follow-measurement-updates
  (support/with-database
   (fn [connection]
     (let [sym (symbol "health.probe" (str (random-uuid)))
           cells (mapv #(profile/cell {:seon.profile/sym sym
                                      :seon.profile/digest (apply str (repeat 64 %))
                                      :seon.profile/scope :seon.profile/host}) ["a" "b"])
           rows (fn [] (filterv #(= sym (:seon.profile.measurement/sym %))
                               (health/derived (db/db connection) ::regressions health/regressions)))
           measure! (fn [cell bucket at]
                      (.add ^LongAdder (aget ^objects (:seon.profile/histogram cell) bucket) 50)
                      (support/transacted!
                       connection
                       (mapv #(assoc % :seon.profile.measurement/at at)
                             (measurement/significant-tx (db/db connection)
                                                        [[sym (:seon.profile/digest cell)]]))))]
       (measure! (first cells) 18 #inst "2026-09-28T20:00Z")
       (is (= [] (rows)))
       (measure! (second cells) 22 #inst "2026-09-28T21:00Z")
       (is (= #{[18 22]} (set (map (juxt :seon.health/before-bucket :seon.health/after-bucket) (rows)))))
       (measure! (second cells) 25 #inst "2026-09-28T22:00Z")
       (is (= #{[18 25]} (set (map (juxt :seon.health/before-bucket :seon.health/after-bucket) (rows)))))))))

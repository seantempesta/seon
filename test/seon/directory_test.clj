(ns seon.directory-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [seon.cluster.agent :as agent]
            [seon.config :as config]
            [seon.db :as db]
            [seon.test-support :as support]))

(deftest an-empty-directory-observes-later-schema-declarations
  (support/with-database
   (fn [connection]
     (config/apply! {:seon.db/connection connection :seon.boot/cluster-name "directory"})
     (let [written (db/transact!
                    connection
                    (into [[:db/add "cluster" :seon.cluster/name "directory"]]
                          (agent/creation-tx
                           {:seon.agent/id "directory" :seon.ns/name 'my.agents.directory
                            :seon.cluster/name "directory"})))
           _ (is (:db-after written))
           ctx (support/fork-cluster-ctx connection "directory")
           configuration (support/effective-config)
           captured (atom [])
           read! (fn [source]
                   (reset! captured [])
                   (binding [db/*read-evidence-sink* captured]
                     (support/evaluate
                      {:seon.cluster.eval/source source
                       :seon.sci.eval/ctx ctx :seon.db/db @connection
                       :seon.sci.admit/caps (config/result-caps configuration)
                       :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms configuration)})))
           initial (read! "(dir my.agents.directory)")
           evidence (db/read-evidence @captured)]
       (is (not (:seon.cluster.eval/error initial)))
       (is (= {:schemas {} :functions []} (:seon.sci.admit/value initial)))
       (is (seq evidence))
       (is (true? (db/read-evidence-current? @connection evidence)))
       (let [assigned (db/transact!
                       connection
                       [[:db/add [:seon.schema/key :seon.print/options] :seon.schema/ns
                         [:seon.ns/name 'my.agents.directory]]])]
         (is (:db-after assigned)))
       (is (false? (db/read-evidence-current? @connection evidence)))
       (let [updated (read! "(dir my.agents.directory)")
             entities (:seon.sci.admit/value updated)
             functions (:seon.sci.admit/value (read! "(dir my.message)"))]
         (is (not (:seon.cluster.eval/error updated)))
         (is (= [:seon.print/options] (vec (keys (:schemas entities)))))
         (is (vector? (get-in entities [:schemas :seon.print/options])))
         (is (= entities (edn/read-string (:seon.eval/shown updated))))
         (is (some #(= 'my.message/send (:sym %)) (:functions functions)))
         (is (not (seq (:seon.cluster.eval/output updated)))))))))

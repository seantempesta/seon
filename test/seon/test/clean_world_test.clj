(ns seon.test.clean-world-test
  "A cluster's test member runs in the loaded published program plus the facts
  boot seeds for a cluster: none of the cluster's agents, turns, messages or
  errors reach it."
  (:require [clojure.test :refer [deftest is]]
            [seon.cluster.boot :as boot]
            [seon.cluster.process :as cluster.process]
            [seon.cluster.source :as source]
            [seon.config :as config]
            [seon.db :as db]
            [seon.program :as program]
            [seon.test-support :as support]))

(deftest a-member-world-is-the-loaded-program-with-boot-facts-only
  (let [member (support/execution-handle nil)
        cluster (:seon.cluster/name member)
        world (db/db (:seon.db/connection member))
        live (db/db (boot/connection cluster))
        base (:seon.source/commit-id
              (db/pull live [:seon.source/commit-id] [:seon.cluster/name cluster]))
        published (source/database (:seon.store/store member) base)
        pairs (fn [database attribute] (map (juxt :e :v) (db/datoms database :aevt attribute)))
        installed (filter #(get-in published [:schema %])
                          (program/program-attributes (db/carried-projection world)))
        entity (fn [database lookup] (:db/id (db/pull database [:db/id] lookup)))
        boot-facts (set (keep identity
                              [(entity world [:seon.cluster/name cluster])
                               (entity world [:seon.config/cluster cluster])
                               (entity world [:seon.db.process/id
                                              (cluster.process/id (cluster.process/current-identity))])]))
        added (remove #(= (:e %) (:tx %))
                      (db/datoms (db/since world (db/basis-t published)) :eavt))]
    (is (= "root" (:seon.agent/id (db/pull live [:seon.agent/id] [:seon.agent/id "root"])))
        "the subject: boot seeded root in the cluster itself")
    (is (= :isolated (:seon.agent/mode member)))
    (doseq [attribute [:seon.agent/id :seon.error/id :seon.message/about :seon.turn/id]]
      (is (empty? (db/datoms world :aevt attribute))
          (str "the member inherits no " attribute " rows")))
    (is (< 100 (count installed)) "the program partition is derived from entity schemas")
    (is (empty? (remove #(= (pairs published %) (pairs world %)) installed))
        "every program-partition attribute holds exactly the published commit's rows")
    (is (= base (:seon.source/commit-id
                 (db/pull world [:seon.source/commit-id] [:seon.cluster/name cluster])))
        "the world records the program it was branched from")
    (is (= (config/effective live cluster) (config/effective world cluster))
        "boot's config facts carry the cluster's effective configuration")
    (is (seq added))
    (is (= #{} (reduce disj (set (map :e added)) boot-facts))
        "only the cluster, config and process entities are added to the published commit")))

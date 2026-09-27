(ns seon.test.clean-world-test
  "A cluster's test member runs in the loaded published program plus the facts
  boot seeds for a cluster and the cluster's differing program rows: none of
  its agents, turns, messages or errors reach it. A member whose reach includes
  `seon.effect/request*`, as this one does through `boot/connection`'s refusal
  path, also holds exactly its own attempt agent and turn (design §2c, owner
  2026-09-25), which the test names from its request context."
  (:require [clojure.test :refer [deftest is]]
            [seon.cluster.boot :as boot]
            [seon.cluster.process :as cluster.process]
            [seon.cluster.source :as source]
            [seon.config :as config]
            [seon.db :as db]
            [seon.effect :as effect]
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
        program-attributes (program/program-attributes (db/carried-projection world))
        digests #(program/digest-map % {:seon.program/max-datoms 100000})
        entity (fn [database lookup] (:db/id (db/pull database [:db/id] lookup)))
        boot-facts (set (keep identity
                              [(entity world [:seon.cluster/name cluster])
                               (entity world [:seon.config/cluster cluster])
                               (entity world [:seon.db.process/id
                                              (cluster.process/id (cluster.process/current-identity))])]))
        attempt effect/*request-context*
        attempt-txs (set (for [[attribute value] [[:seon.agent/id (:seon.agent/id attempt)]
                                                  [:seon.turn/id (:seon.turn/id attempt)]]
                               :when value
                               datom (db/datoms world :avet attribute value)]
                           (:tx datom)))
        added (remove #(or (= (:e %) (:tx %)) (attempt-txs (:tx %)))
                      (db/datoms (db/since world (db/basis-t published)) :eavt))]
    (is (= "root" (:seon.agent/id (db/pull live [:seon.agent/id] [:seon.agent/id "root"])))
        "the subject: boot seeded root in the cluster itself")
    (is (= :isolated (:seon.agent/mode member)))
    (is (= 1 (count attempt-txs))
        "this effect-reaching member's attempt agent and turn are written in one transaction")
    (is (= [(:seon.agent/id attempt)] (map :v (db/datoms world :aevt :seon.agent/id)))
        "the member's only agent is its own attempt agent")
    (is (= [(:seon.turn/id attempt)] (map :v (db/datoms world :aevt :seon.turn/id)))
        "the member's only turn is its attempt's")
    (doseq [attribute [:seon.error/id :seon.message/about]]
      (is (empty? (db/datoms world :aevt attribute))
          (str "the member inherits no " attribute " entities")))
    (is (< 100 (count program-attributes)) "the program partition is derived from entity schemas")
    (is (= (digests live) (digests world))
        "the member's program is its cluster's: the published commit plus the differing entities")
    (is (= base (:seon.source/commit-id
                 (db/pull world [:seon.source/commit-id] [:seon.cluster/name cluster])))
        "the world records the program it was branched from")
    (is (= (config/effective live cluster) (config/effective world cluster))
        "boot's config facts carry the cluster's effective configuration")
    (is (seq added))
    (is (= #{} (into #{} (comp (remove (comp boot-facts :e))
                               (remove (comp program-attributes :a))
                               (map :e))
                     added))
        "beside program entities and the attempt's two transactions only the cluster, config and process entities are added")))

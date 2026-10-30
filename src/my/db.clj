(ns my.db
  "An agent's database functions, on its own branch (from proto/flow f2-final proto.agent's
  agent API). Referred into every agent namespace.
    (db)                the branch's current db value, in this process
    (q query & inputs)  d/q against (db)
    (pull pattern eid)  d/pull against (db)
    (transact! tx-data) tx-data to the writer for THIS branch; applied here when the
                        writer answers; answers {:t :datoms}. Inside a test (a fork of the
                        agent) it applies to the fork's db only: a test's writes vanish."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [datahike.api :as d] [seon.agent :as agent]))

(defn db "The branch's current db value." {:malli/schema [:=> [:cat] :seon.host/db]}
  [] (agent/db))
(defn q "d/q against (db)." {:malli/schema [:=> [:cat :any [:* :any]] :any]}
  [query & inputs] (apply d/q query (agent/db) inputs))
(defn datoms "d/datoms against (db): the index's datoms, lazily, read only as far as taken. See docs/agent.md#small-answers."
  {:malli/schema [:=> [:cat :keyword [:* :any]] [:sequential :any]]}
  [index & components] (apply d/datoms (agent/db) index components))
(defn pull "d/pull against (db)." {:malli/schema [:=> [:cat :any :any] [:maybe :map]]}
  [pattern eid] (d/pull (agent/db) pattern eid))
(defn transact! "Write tx-data to this agent's branch; answers {:t :datoms}." {:malli/schema [:=> [:cat :seon.db/tx-data] :seon.agent/tx-result]}
  [tx-data] (agent/transact! tx-data))

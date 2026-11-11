(ns my.db
  "An agent's database functions, on its own branch (from proto/flow f2-final proto.agent's
  agent API). Referred into every agent namespace.
    (db)                the branch's current db value, in this process
    (q query & inputs)  d/q against (db)
    (pull pattern eid)  d/pull against (db)
    (at opts)           the db at a point: {:t t} this branch as of t, {:commit uuid}, {:branch b}
                        (committed value only), {:branch b :t t}; no opts, (db); an error map when it is none
    (transact! tx-data) tx-data to the writer for THIS branch; applied here when the
                        writer answers; answers {:t :datoms}. Inside a test (a fork of the
                        agent) it applies to the fork's db only: a test's writes vanish."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [datahike.api :as d] [seon.agent :as agent] [seon.db :as pdb] [seon.render :as render]))

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
(defn pulled
  "Pulled entity node as its data model (seon.render/pulled, the one producer; the pull view lays the same map out): its schema,
  where its pattern came from, each clause with what the pull holds for it, a join's members and how many hold each sub-clause,
  what its schema names that the pattern does not ask, what the entity holds that it does not ask, and the renders that draw
  it. With one argument, the entity a pull starts from, by its own pattern; a node joined inside another's pull was pulled
  by the sub-pattern at its path, the second argument (a join's :pattern). See docs/agent.md#looking-at-what-you-pulled.

  ## Examples

  ```clojure
  (my.db/pulled @ctx)
  (let [j (first (filter :members (:clauses (my.db/pulled @ctx))))] (:pattern j))
  ```"
  {:malli/schema [:function [:=> [:cat :any] [:maybe :map]] [:=> [:cat :any [:maybe [:sequential :any]]] [:maybe :map]]]}
  ([node] (pulled node nil))
  ([node pattern] (render/pulled (agent/db) (agent/branch-index) node pattern)))

(defn at
  "The db at a named point, for d/q, d/pull, d/entity and my.agent/history ({:db it}): no opts is (db);
  {:t t} this branch as of t (a t it never had is refused, a t from history is read as it was);
  {:commit uuid} that commit; {:branch b} branch b's last committed value, so an uncommitted write on b is
  not seen; {:branch b :t t} b as of t. :commit with :branch or :t, and any other opt, is refused; an
  unknown commit, branch or t answers :seon.db/unresolved. See docs/storage.md#history (seon.db/at)."
  {:malli/schema [:function [:=> [:cat] :seon.host/db]
                  [:=> [:cat :seon.db/at-opts] [:or :seon.host/db :seon.db/unresolved :seon.db/no-history]]]}
  ([] (agent/db))
  ([opts] (pdb/at (:store-conn @agent/state) (agent/db) opts)))

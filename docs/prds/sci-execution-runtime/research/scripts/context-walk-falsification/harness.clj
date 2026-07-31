;;; Shared apparatus for the context-walk falsification lane (2026-07-31).
;;; Run: clojure -M:test -i tmp/falsify/harness.clj -i tmp/falsify/<probe>.clj
(ns falsify.harness
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.fn.analyzer]
            [seon.test-support :as ts]
            [seon.render.walk :as walk]
            [seon.cluster.agent :as agent]
            [seon.ai.tokens :as tokens]))

;;; The shared .clj-kondo/.cache is poisoned (clojure.core/vswap! recorded with
;;; the macro's &form/&env arglist), which makes populate-source! REFUSE.
;;; Process-local redirect so this lane can build a realistic corpus.
(alter-var-root #'seon.fn.analyzer/cache-directory
                (constantly "tmp/falsify/kondo-cache"))

(def caps
  {:seon.config.eval.result/max-depth 12
   :seon.config.eval.result/max-collection 64
   :seon.config.eval.result/max-string 4096
   :seon.config.eval.result/max-nodes 4096})

;;; Spec §2.1's proposed instruction family, declared as raw Datahike rows so
;;; the DRAFT's own shape is what gets attacked.
(def instruction-schema
  [{:db/ident :seon.cluster.instruction/id
    :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :seon.cluster.instruction/text
    :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :seon.cluster.agent/instructions
    :db/valueType :db.type/ref :db/cardinality :db.cardinality/many}])

(defn tx! [conn tx-data] (d/transact conn {:tx-data tx-data}))

(defn ns-eid [db sym] (d/q '[:find ?e . :in $ ?n :where [?e :seon.ns/name ?n]] db sym))

(def instructions
  [[:reply-grammar "Reply with balanced Clojure forms; prose becomes comments."]
   [:messaging "Address a peer by agent id; one message per recipient."]
   [:declining "Decline an assignment by settling the run with a reason."]
   [:global "Project instructions: read the closest AGENTS.md before editing."]])

(defn seed!
  "Two agents on real corpus namespaces, sharing the four instruction rows."
  [conn]
  (tx! conn instruction-schema)
  (tx! conn (into [] (map (fn [[id text]]
                            {:seon.cluster.instruction/id id
                             :seon.cluster.instruction/text text}))
                  instructions))
  (let [db @conn
        alpha-ns (ns-eid db 'seon.cluster.run)
        beta-ns  (ns-eid db 'seon.render.block)
        irefs (mapv (fn [[id _]] [:seon.cluster.instruction/id id]) instructions)]
    (tx! conn [{:seon.cluster.agent/id "alpha"
                :seon.cluster.agent/namespace alpha-ns
                :seon.cluster.agent/instructions irefs}
               {:seon.cluster.agent/id "beta"
                :seon.cluster.agent/namespace beta-ns
                :seon.cluster.agent/instructions irefs}]))
  conn)

(defn message!
  [conn {:keys [id to from content at]}]
  (tx! conn [(cond-> {:seon.cluster.message/id id
                      :seon.cluster.message/content content
                      :seon.cluster.message/at (or at (java.util.Date.))
                      :seon.cluster.message/to [:seon.cluster.agent/id to]}
               from (assoc :seon.cluster.message/from
                           [:seon.cluster.agent/id from]))]))

(defn walk-node
  ([db lookup] (walk-node db lookup 2))
  ([db lookup distance] (walk-node db lookup distance nil))
  ([db lookup distance overrides]
   (walk/neighborhood
    (cond-> {:seon.db/db db
             :seon.render.walk/lookup lookup
             :seon.render/kind :seon.render/ai
             :seon.render/floor 'seon.render.block/data-prose
             :seon.render/distance distance
             :seon.sci.admit/caps caps}
      overrides (assoc :seon.render/overrides overrides)))))

(defn nodes [node]
  (tree-seq :seon.render.walk/neighbours :seon.render.walk/neighbours node))

(defn node-text [n]
  (let [o (:seon.render/output n)]
    (cond (string? o) o
          (some? o) (pr-str o)
          :else (:seon.error/message (:seon.error/value n)))))

(defn with-db [f]
  (ts/with-database {:seon.test-support/extra-schema []} (fn [conn] (f conn))))

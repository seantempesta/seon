;; store-structure-opus: one fingerprint of history, as-of and since answers of a
;; database value, computed identically by the running (parent) Datahike and by
;; the changed fork over the same commit, so equal fingerprints mean equal reads.
(require '[datahike.api :as d])

(defn datom-rows [datoms] (mapv (juxt :e :a :v :tx :added) datoms))

(defn history-fingerprint
  "Hashes and counts of the history of `attrs` (AEVT), an as-of and a since view
  at `t`, and the history EAVT of `entities`, over `db`."
  [db attrs entities t]
  (let [h (d/history db)
        a-rows (into {} (for [a attrs] [a (let [r (datom-rows (d/datoms h :aevt a))] [(count r) (hash r)])]))
        e-rows (into {} (for [e entities] [e (let [r (datom-rows (d/datoms h :eavt e))] [(count r) (hash r)])]))
        as-of (d/as-of db t)
        since (d/since db t)]
    {:history-aevt a-rows
     :history-eavt e-rows
     :as-of (into {} (for [a attrs] [a (let [r (datom-rows (d/datoms as-of :aevt a))] [(count r) (hash r)])]))
     :since (into {} (for [a attrs] [a (let [r (datom-rows (d/datoms since :aevt a))] [(count r) (hash r)])]))
     :as-of-entities (hash (for [e entities] (into {} (d/pull as-of '[*] e))))
     :history-query (hash (d/q '[:find ?e ?v ?tx ?added :in $ [?a ...] :where [?e ?a ?v ?tx ?added]] h (vec (take 5 attrs))))}))

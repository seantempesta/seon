(ns probe-branch-fork-parallel
  "Surface: the platform's own cheap forking as the test isolation mechanism —
   `seon.test-support/with-database` branch forks of one in-memory base, run
   concurrently.

   Hypothesis under test: N parallel forks each transacting a distinct marker
   see ONLY their own datoms, their own schema evolution, and their own basis;
   the bounded branch-lease pool never hands one live branch to two fixtures."
  (:require [seon.db :as db]
            [seon.test-support :as support]))

(set! *warn-on-reflection* true)

(defn- marker-attribute [index]
  (keyword "probe.branch" (str "marker-" index)))

(defn- fork-body [index]
  (let [attribute (marker-attribute index)
        mine (str "fork-" index)]
    (support/with-database
     {:seon.test-support/extra-schema
      (support/file-store-probe-schema attribute)}
     (fn [connection]
       (db/transact! connection {:tx-data [{attribute mine}]})
       (Thread/sleep 5)
       (let [own (support/file-store-markers connection attribute)
             foreign
             (into #{}
                   (comp (remove #(= % index))
                         (map marker-attribute)
                         (mapcat #(support/file-store-markers connection %)))
                   (range 0 24))
             schema-keys (set (keys (:schema @connection)))]
         {:probe/index index
          :probe/own own
          :probe/foreign foreign
          :probe/foreign-schema
          (into #{}
                (comp (remove #(= % index)) (map marker-attribute)
                      (filter schema-keys))
                (range 0 24))})))))

(defn run
  "Run parallel branch forks and report any datom or schema leakage."
  [{:keys [forks] :or {forks 24}}]
  (let [results (mapv deref (mapv #(future (fork-body %)) (range forks)))
        leaks (filterv (fn [{:probe/keys [index own foreign foreign-schema]}]
                         (or (not= own #{(str "fork-" index)})
                             (seq foreign)
                             (seq foreign-schema)))
                       results)]
    {:probe/name 'probe-branch-fork-parallel
     :probe/surface
     "seon.test-support/with-database branch forks under concurrency"
     :probe/verdict (if (seq leaks) :fail :pass)
     :probe/forks forks
     :probe/leaks (vec (take 5 leaks))}))

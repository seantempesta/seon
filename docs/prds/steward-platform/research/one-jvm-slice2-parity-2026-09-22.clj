; Remove the retired source digest from the current parity oracle only.
(require '[clojure.edn :as edn]
         '[seon.schema :as schema]
         '[seon.schema.edn :as schema.edn]
         '[seon.schema.datahike :as datahike]
         'seon.fn 'seon.fn.analyzer 'seon.cluster 'seon.cluster.source)
(let [fixture "test/seon/schema/datahike_parity.edn"
      before (edn/read-string (slurp fixture))
      removed #{:seon.source/toolchain-digest}
      forms (-> (:seon.bridge.parity/forms before)
                (dissoc :seon.source/toolchain-digest)
                (assoc :seon.fn.manifest/manifest
                       (:seon.fn.manifest/manifest
                        (edn/read-string (slurp "resources/seon/schemas/seon.fn.manifest.edn")))))
      after (-> before
                (assoc :seon.bridge.parity/forms forms
                       :seon.bridge.parity/input-digest
                       (schema/sha-256 [(.getBytes (schema/canonical-data-string forms) "UTF-8")]))
                (update :seon.bridge.parity/core-attributes #(into [] (remove removed) %))
                (update :seon.bridge.parity/attributes #(into [] (remove removed) %))
                (update :seon.bridge.parity/native #(into [] (remove (comp removed :db/ident)) %)))
      actual (schema.edn/packaged-forms)
      projection (schema/declaration-projection actual)
      attributes (datahike/database-attributes-in projection)]
  (assert (= (:seon.bridge.parity/initial-baseline before)
             (:seon.bridge.parity/initial-baseline after)))
  (assert (= forms actual))
  (assert (= (:seon.bridge.parity/attributes after) attributes))
  (assert (= (:seon.bridge.parity/native after)
             (datahike/malli->datahike-schema-in projection attributes)))
  (spit fixture (pr-str after))
  (prn {:removed removed :schemas (count forms) :attributes (count attributes) :mismatches 0}))
(shutdown-agents)

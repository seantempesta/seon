;; Slice 0 evidence: subtract only the removed declarations from the captured
;; parity oracle. Preserve historical forms and every surviving native attribute.
(require '[clojure.edn :as edn]
         '[clojure.java.shell :as shell]
         '[clojure.set :as set]
         '[seon.schema :as schema]
         'seon.schema.edn 'seon.schema.datahike
         'seon.cluster 'seon.cluster.source 'seon.fresh-operator)
(let [path "resources/seon/schemas/seon.source.edn"
      old (shell/sh "git" "show" (str "a70995402:" path))
      _ (assert (zero? (:exit old)) (:err old))
      removed (set/difference (set (keys (edn/read-string (:out old))))
                              (set (keys (edn/read-string (slurp path)))))
      _ (assert (= 13 (count removed)))
      fixture "test/seon/schema/datahike_parity.edn"
      before (edn/read-string (slurp fixture))
      forms (apply dissoc (:seon.bridge.parity/forms before) removed)
      after (-> before
                (assoc :seon.bridge.parity/forms forms
                       :seon.bridge.parity/input-digest
                       (schema/sha-256 [(.getBytes (schema/canonical-data-string forms) "UTF-8")]))
                (update :seon.bridge.parity/core-attributes #(into [] (remove removed) %))
                (update :seon.bridge.parity/attributes #(into [] (remove removed) %))
                (update :seon.bridge.parity/native
                        #(into [] (remove (comp removed :db/ident)) %)))]
  (assert (= (:seon.bridge.parity/initial-baseline before)
             (:seon.bridge.parity/initial-baseline after)))
  (binding [*print-length* nil *print-level* nil]
    (spit fixture (pr-str after)))
  (prn {:removed removed
        :forms [(count (:seon.bridge.parity/forms before)) (count forms)]
        :attributes [(count (:seon.bridge.parity/attributes before))
                     (count (:seon.bridge.parity/attributes after))]
        :input-digest (:seon.bridge.parity/input-digest after)}))
(let [expected (edn/read-string (slurp "test/seon/schema/datahike_parity.edn"))
      forms (seon.schema.edn/packaged-forms)
      projection (schema/declaration-projection forms)
      attributes (seon.schema.datahike/database-attributes-in projection)]
  (assert (= (:seon.bridge.parity/forms expected) forms))
  (assert (= (:seon.bridge.parity/attributes expected) attributes))
  (assert (= (:seon.bridge.parity/native expected)
             (seon.schema.datahike/malli->datahike-schema-in projection attributes)))
  (prn {:schemas (count forms) :attributes (count attributes) :mismatches 0}))
(shutdown-agents)

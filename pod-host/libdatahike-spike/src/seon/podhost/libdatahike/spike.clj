(ns seon.podhost.libdatahike.spike
  "Read-only datahike spike: validates that the query engine alone compiles
   to WASM, without datahike's async writer (which drags in core.async,
   monitor synchronization, and SVM @Delete blockers).

   This is the V1 in-pod read replica shape per spec-01: queries against
   a baked-in datom snapshot; writes are server-side via the single-writer
   auth gateway."
  (:require [datahike.query :as q])
  (:gen-class))

;; Literal datoms — the EAV tuples a real datahike :memory backend would hold
;; after transacting the equivalent map data.  Format: [entity-id attribute value].
(def datoms
  [[1 :name "Alpha"]    [1 :version 1]
   [2 :name "Seon"]    [2 :version 2]
   [3 :name "Datahike"][3 :version 3]])

(defn -main [& _args]
  (println "== libdatahike-WASM read-only spike ==")
  (println "Querying" (count datoms) "datoms...")
  (let [result (q/q '[:find ?n ?v
                      :in $
                      :where [?e :name ?n] [?e :version ?v]]
                    datoms)]
    (println "Result:" (pr-str result))
    (println "Done.  Count:" (count result))))

;; render-selection workbench. Evaluate at default's JVM REPL as one form:
;;   (eval (read-string (slurp "/Users/sean/src/seon/tmp/render-selection/proof.clj")))
;; Defines nothing; every write is speculative (seon.db/with: the armed writer's
;; checks and final-report validator, never committed).
(let [dir (or (System/getenv "SEON_PROOF_DIR") "/Users/sean/src/seon/tmp/render-selection/")
      run (fn [file] (eval (read-string (slurp (str dir file)))))
      ms (fn [t0] (/ (Math/round (/ (- (System/nanoTime) t0) 1e4)) 100.0))
      db @(seon.cluster.boot/connection "default")
      installed (:schema (seon.db/schema-database db))
      attrs [:seon.render/ai :seon.render/html :seon.render/form]
      t0 (System/nanoTime)
      declared-callers (#'seon.fn/declared-callers identity db)
      dc-ms (ms t0)
      render-targets (into #{} (mapcat (fn [a] (keep :v (seon.db/datoms db :aevt a)))) attrs)]
  {:storage-before
   {:installed (select-keys installed attrs)
    :datoms (into {} (map (fn [a] [a (count (datahike.api/datoms db :aevt a))])) attrs)
    :raw-value-classes (frequencies (for [a attrs d (datahike.api/datoms db :aevt a)] (.getName (class (:v d)))))}
   :test-selection-before
   {:declared-callers-targets (count declared-callers) :ms dc-ms
    :decoded-render-targets (count render-targets)
    :render-targets-reached (count (filter render-targets (keys declared-callers)))}
   :retype-rehearsal (run "rehearsal.clj")
   :ancestry (run "ancestry.clj")
   :discovery (run "discovery.clj")})

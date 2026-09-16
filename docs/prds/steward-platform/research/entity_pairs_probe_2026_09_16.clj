(ns entity-pairs-probe-2026-09-16
  (:require [seon.db] [seon.fn] [seon.operator]
            [seon.render.ns] [seon.render.test]))

;; Evaluate this read-only form through MCP JVM mode on default.
;; It measures the existing query offered explicitly by the function pair.
(let [database (seon.db/db (seon.operator/connection "default"))
      start (System/nanoTime)
      result (seon.fn/tests-reaching database "seon.id/id")]
  (cond-> {:seon.test/count (count result)
           :seon.test/elapsed-ms (/ (- (System/nanoTime) start) 1000000.0)}
    (:seon.error/kind result)
    (assoc :seon.error/kind (:seon.error/kind result))))

;; Each form is an independent MCP JVM probe; inspect the complete stdout.
(let [database (seon.db/db (seon.operator/connection "default"))
      entity (seon.db/pull database '[*] [:seon.fn/sym "seon.id/id"])
      start (System/nanoTime)
      value (seon.render.ns/function-ai
              {:seon.db/db database :seon.render/value entity})]
  (prn {:entity-pairs/ms (/ (- (System/nanoTime) start) 1e6)
        :entity-pairs/output value}))

(let [database (seon.db/db (seon.operator/connection "default"))
      entity (seon.db/pull database '[*]
               [:seon.test/sym
                "seon.fn-test/quoted-private-handler-symbol-is-indexed-as-the-runtime-symbol"])
      start (System/nanoTime)
      value (seon.render.test/render-html
              {:seon.db/db database :seon.render/value entity})]
  (prn {:entity-pairs/ms (/ (- (System/nanoTime) start) 1e6)
        :entity-pairs/output value}))

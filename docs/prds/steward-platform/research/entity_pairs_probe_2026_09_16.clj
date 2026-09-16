(ns entity-pairs-probe-2026-09-16
  (:require [seon.db] [seon.fn] [seon.operator]))

;; Evaluate this read-only form through MCP JVM mode on default.
;; It measures the existing query required by the proposed function pair.
(let [database (seon.db/db (seon.operator/connection "default"))
      start (System/nanoTime)
      result (seon.fn/tests-reaching database "seon.id/id")]
  (cond-> {:seon.test/count (count result)
           :seon.test/elapsed-ms (/ (- (System/nanoTime) start) 1000000.0)}
    (:seon.error/kind result)
    (assoc :seon.error/kind (:seon.error/kind result))))

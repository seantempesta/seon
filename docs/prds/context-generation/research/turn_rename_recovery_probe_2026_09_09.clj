(ns turn-rename-recovery-probe-2026-09-09
  (:require [datahike.api]
            [seon.cluster.run]
            [seon.db]
            [seon.operator]))

; Run in the default JVM after development adoption. All writes below are
; Datahike `with` values, never transactions on default's connection.
(let [connection (seon.operator/connection "default")
      before @connection
      now (java.util.Date. 1788933600000)
      id "turn-rename-recovery-probe"
      report (datahike.api/with
              before
              [{:seon.cluster.agent/id id}
               {:seon.cluster.run/id id
                :seon.cluster.run/agent [:seon.cluster.agent/id id]
                :seon.cluster.run/opened-at now
                :seon.cluster.work/situation :generate}
               {:seon.cluster.eval/id (str id "-unfinished")
                :seon.cluster.eval/run [:seon.cluster.run/id id]
                :seon.cluster.eval/ordinal 0
                :seon.cluster.eval/source "(+ 1 1)"}])
      database (:db-after report)
      operations (seon.cluster.run/recover-call
                  database
                  {:seon.cluster.run/id id
                   :seon.cluster.run/now now})
      after (:db-after (datahike.api/with database operations))
      turn (seon.db/pull after '[*] [:seon.cluster.run/id id])
      evaluation (seon.db/pull after '[*]
                              [:seon.cluster.eval/id (str id "-unfinished")])]
  {:seon.test/closed (= now (:seon.cluster.run/closed-at turn))
   :seon.test/interrupted (= now (:seon.cluster.eval/interrupted-at evaluation))
   :seon.test/idempotent
   (empty? (seon.cluster.run/recover-call
             after {:seon.cluster.run/id id :seon.cluster.run/now now}))
   :seon.test/default-unchanged (= (seon.db/basis-t before)
                                  (seon.db/basis-t @connection))
   :seon.test/only-close-and-evaluation (= 2 (count operations))
   :seon.test/operations (count operations)})

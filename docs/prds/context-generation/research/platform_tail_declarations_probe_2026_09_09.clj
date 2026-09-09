(ns platform-tail-declarations-probe-2026-09-09
  (:require [seon.turn :as turn]))

; Read-only JVM probe: construct transaction data, never submit it.
(let [rows [{:seon.test/sym "fixture.batch/target-test"
             :seon.test/ns [:seon.ns/name 'fixture.batch]
             :seon.test/source "(clojure.test/deftest target-test)"
             :seon.schema.admission/source :agent
             :seon.test/subject [:seon.fn/sym "fixture.batch/target"]}
            {:seon.fn/sym "fixture.batch/target"
             :seon.fn/ns [:seon.ns/name 'fixture.batch]
             :seon.fn/source "(defn target [] 1)"
             :seon.schema.admission/source :agent
             :seon.fn/arglists "([])"
             :seon.fn/private? false
             :seon.fn/spec "[:=> [:cat] :int]"}]
      requests (mapv (fn [ordinal row]
                       {:seon.turn/id "platform-tail-probe"
                        :seon.cluster.eval/ordinal ordinal
                        :seon.eval/value "nil"
                        :seon.program/row row})
                     (range 2) rows)
      ordered (turn/receipt-settle-batch-tx requests)
      ordinary (turn/receipt-settle-batch-tx
                (mapv #(dissoc % :seon.program/row) requests))]
  (assert (= 2 (count ordered)))
  (assert (= 1 (count ordinary)))
  {:seon.test/ordered-call-count (count ordered)
   :seon.test/ordered-calls (mapv #(str (second %)) ordered)
   :seon.test/ordinary-call-count (count ordinary)
   :seon.test/ordinary-calls (mapv #(str (second %)) ordinary)
   :seon.test/transactions-submitted 0})

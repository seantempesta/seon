(require 'seon.db 'seon.operator 'seon.render.transcript)
(let [database (seon.db/db (seon.operator/connection "default"))
      rows (#'seon.render.transcript/turn-rows database "juniper")
      intervals (seon.db/q '[:find ?id ?opened ?closed :in $ [?id ...]
                             :where [?turn :seon.turn/id ?id ?opened]
                                    [?turn :seon.turn/closed-tx ?closed]]
                           database (mapv :seon.turn/id rows))
      bean (java.lang.management.ManagementFactory/getThreadMXBean)
      thread-id (.threadId (Thread/currentThread))
      measure (fn [f]
                (let [bytes (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean thread-id)
                      start (System/nanoTime) result (f)]
                  [result {:ms (/ (- (System/nanoTime) start) 1e6)
                           :allocated-mb (/ (- (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean thread-id) bytes) 1e6)}]))
      [before before-cost]
      (measure #(seon.db/q '[:find ?id ?symbol :in $ [[?id ?from ?to]]
                             :where (or [?f :seon.fn/sym ?symbol ?tx] [?f :seon.test/sym ?symbol ?tx])
                                    [(<= ?from ?tx)] [(<= ?tx ?to)]] database intervals))
      [after after-cost]
      (measure #(let [facts (seon.db/q '[:find ?symbol ?tx
                                        :where (or [?f :seon.fn/sym ?symbol ?tx]
                                                   [?f :seon.test/sym ?symbol ?tx])] database)]
                  (set (for [[id from to] intervals [sym tx] facts :when (<= from tx to)] [id sym]))))]
  (println {:basis (seon.db/basis-t database) :equal? (= (set before) after)
            :effects (count after) :before before-cost :after after-cost}))

;; Evaluate this form over the PREPL of an isolated committed root.
;; Replace symbols with the identical named selection on parent and candidate.
;; The central-round entrance supplies the execution handle and stores results.
;; seon.profile/measure includes child-thread costs in its JVM window; its
;; alloc-bytes counts this calling thread only. Function totals are inclusive
;; and must not be added across callers or threads. Keep the root between calls.
(do
 (require 'seon.central-round)
 (let [symbols '[seon.id-test/a-host-object-has-no-identity]
       snapshot (fn [] (reduce (fn [m c]
                           (let [s (:seon.profile/sym c)]
                             (update m s #(mapv + (or % [0 0])
                                               [(.sum ^java.util.concurrent.atomic.LongAdder (:seon.profile/total-counter c))
                                                (.sum ^java.util.concurrent.atomic.LongAdder (:seon.profile/entry-counter c))]))))
                         {} (seon.profile/cells)))
       before (snapshot)
       measured (seon.profile/measure #(eval (seon.central-round/batch-form symbols)))
       after (snapshot)]
   (assoc (dissoc measured :seon.profile/called)
          :round-waste/totals
          (into {} (keep (fn [[s [n c]]]
                          (let [[n0 c0] (get before s [0 0])]
                            (when (> n n0) [s [(/ (- n n0) 1e6) (- c c0)]])))) after))))

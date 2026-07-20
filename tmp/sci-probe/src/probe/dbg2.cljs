(ns probe.dbg2
  "Bun-side interrupt probe: sci :interrupt-fn cancels a tight CPU loop
   in-process — the exact thing seon.eval's timeout documents it cannot do."
  (:require [sci.core :as sci]
            [sci.interrupt :as interrupt]))

(defn -main [& _]
  (let [deadline (atom (+ (js/performance.now) 200))
        ctx (sci/init {:interrupt-fn
                       (fn []
                         (when (> (js/performance.now) @deadline)
                           (interrupt/interrupt! "budget exceeded")))})
        t0 (js/performance.now)
        r (try (sci/eval-string* ctx "(loop [i 0] (recur (inc i)))")
               (catch :default e {:host-caught (.-message e)}))
        elapsed (- (js/performance.now) t0)
        ;; and: sandboxed code cannot swallow it
        _ (reset! deadline (+ (js/performance.now) 200))
        r2 (try (sci/eval-string* ctx
                  "(loop [i 0]
                     (let [x (try (inc i) (catch :default e :swallowed))]
                       (recur (if (number? x) x 0))))")
                (catch :default e {:host-caught (.-message e)}))]
    (println "tight-loop interrupt:" (pr-str r) "after" (js/Math.round elapsed) "ms")
    (println "swallow attempt:" (pr-str r2))
    (js/process.exit 0)))

(set! *main-cli-fn* -main)

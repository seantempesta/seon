(ns seon.test.node-preload
  "Test-process setup shared by every Shadow Node test build."
  (:require
    [seon.log :as log]
    [seon.runtime.admission :as admission]))

;; Shadow inserts dev preloads after shadow.test.env and before every test
;; namespace. Quiet third-party trace/debug output before database fixtures load.
(log/quiet-library-logs!)

(defn- integer-environment-value
  [name]
  (let [value (some-> js/process .-env (aget name))
        parsed (when value (js/parseInt value 10))]
    (when (and (number? parsed) (js/Number.isSafeInteger parsed)
               (pos? parsed))
      parsed)))

(defn- owner-alive?
  [pid]
  (try
    (.kill js/process pid 0)
    true
    (catch :default _ false)))

(defn- install-process-owner-monitor!
  []
  (when-let [owner-pid (integer-environment-value "SEON_TEST_OWNER_PID")]
    (let [started-at (js/Date.now)
          timeout-ms (integer-environment-value "SEON_TEST_TIMEOUT_MS")
          check! (fn []
                   (cond
                     (not (owner-alive? owner-pid)) (.exit js/process 143)
                     (and timeout-ms
                          (>= (- (js/Date.now) started-at) timeout-ms))
                     (.exit js/process 124)))]
      (check!)
      ;; Keep this interval referenced. If an async test loses its completion
      ;; callback, the process must reach the deadline rather than drain into a
      ;; false-green exit while later namespaces never ran.
      (js/setInterval check! 250))))

(install-process-owner-monitor!)

;; Unit tests invoke executable boundaries without cold-starting a cluster.
;; Production starts closed and opens only through committed reconstruction;
;; the Node-test process supplies that already-proven prerequisite once so
;; ordinary focused tests do not each invent a publication fixture. Admission
;; tests reset the same cell explicitly and exercise the real transition.
(reset! @#'admission/!state
        {::admission/status :available
         ::admission/generation 0})

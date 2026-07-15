(ns seon.test.node-preload
  "Test-process setup shared by every Shadow Node test build."
  (:require
    [seon.log :as log]
    [seon.runtime.admission :as admission]))

;; Shadow inserts dev preloads after shadow.test.env and before every test
;; namespace. Quiet third-party trace/debug output before database fixtures load.
(log/quiet-library-logs!)

;; Unit tests invoke executable boundaries without cold-starting a cluster.
;; Production starts closed and opens only through committed reconstruction;
;; the Node-test process supplies that already-proven prerequisite once so
;; ordinary focused tests do not each invent a publication fixture. Admission
;; tests reset the same cell explicitly and exercise the real transition.
(reset! @#'admission/!state
        {::admission/status :available
         ::admission/generation 0})

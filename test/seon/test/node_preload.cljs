(ns seon.test.node-preload
  "Test-process setup shared by every Shadow Node test build."
  (:require
    [seon.log :as log]))

;; Shadow inserts dev preloads after shadow.test.env and before every test
;; namespace. Quiet third-party trace/debug output before database fixtures load.
(log/quiet-library-logs!)

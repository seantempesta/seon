(ns seon.primer.schema
  "Malli schemas for Primer domain."
  (:require [malli.core :as m]))

(def registry
  (atom (m/default-schemas)))

(ns seon.flow.fixtures.beta
  (:require [seon.flow.fixtures.alpha :as alpha]
            [seon.schema :as schema]))

(schema/register! :seon.flow.fixtures.beta/offset :int)

(defn shifted-value
  "Read and shift one fixture value."
  [id]
  (inc (alpha/lookup-value id)))

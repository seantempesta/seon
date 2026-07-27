(ns seon.flow.fixtures.alpha
  (:require [seon.schema :as schema]))

(schema/register! :seon.flow.fixtures.alpha/value :int)

(defn lookup-value
  "Increment one fixture value."
  [value]
  (inc value))

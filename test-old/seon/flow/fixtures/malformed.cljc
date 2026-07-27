(ns seon.flow.fixtures.malformed
  (:require [seon.schema :as schema]))

(schema/register! :seon.flow.fixtures.malformed/value :int)

(defn broken
  [value]
  (+ value 1)

(ns seon.flow.fixtures.gamma
  (:require [seon.schema :as schema]))

(schema/register! :seon.flow.fixtures.gamma/enabled :boolean)

(defn enabled?
  "Coerce one fixture value to boolean."
  [value]
  (boolean value))

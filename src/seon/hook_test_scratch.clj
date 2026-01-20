(ns seon.hook-test-scratch
  (:require [malli.core :as m]))

(defn add-numbers
  "Adds two numbers together."
  {:malli/schema [:=> [:cat :int :int] :int]}
  [a b]
  (+ a b))

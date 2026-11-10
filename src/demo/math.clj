(ns demo.math (:refer-clojure :exclude [defn defn-]) (:require [seon.contract :refer [defn defn-]]))

(defn half "Half of n." {:malli/schema [:=> [:cat :int] [:or :int ratio?]]} [n] (/ n 2))

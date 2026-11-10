(ns demo.math (:refer-clojure :exclude [defn defn-]) (:require [seon.contract :refer [defn defn-]]))

(defn double "Twice n." {:malli/schema [:=> [:cat :int] :int]} [n] (* 2 n))

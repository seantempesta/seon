(ns seon.web.router
  "Data-driven Reitit routing over `:seon.route/*` entities in Datahike (forward match + reverse `path-for`)."
  (:require [reitit.core :as r]
            [reitit.trie]))

(defn compile-router
  "Compiles a Reitit route vector into a live router."
  {:malli/schema [:=> [:cat [:vector :any]] :any] :cost/ticks 2000000}
  [routes]
  (r/router routes))
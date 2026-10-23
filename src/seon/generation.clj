(ns seon.generation
  "The e2e loop's subject inside Seon's own source (:seon.config.source/dirs): a task
  redefines generation on its branch, its merge is written back to src, and the binary
  built from that commit answers the new value (seon.e2e/loop-run)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]]))

(defn generation
  "Which generation of Seon's own source this binary was built from: the loop's subject
  (seon.e2e/loop-run). A task redefines it on its branch, the merge is written back and
  committed on the merges branch, and the binary built from that commit answers the new
  value."
  {:malli/schema [:=> [:cat] :int]}
  [] 1)

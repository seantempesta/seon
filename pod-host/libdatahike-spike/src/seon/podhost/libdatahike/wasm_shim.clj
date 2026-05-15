(ns seon.podhost.libdatahike.wasm-shim
  "Build-time entry point that explicitly requires Clojure stdlib namespaces
   that ship as .clj source only (or are dynamically resolved via
   requiring-resolve / macroexpansion) so that the Clojure compiler emits
   their __init.class files into target/classes/ during AOT.

   Without this shim, the compiler skips namespaces that aren't statically
   required by the user code's import graph.  At runtime under Web Image,
   their RT.load fallback to .clj source compilation fails because SVM
   disallows runtime class definition."
  (:require
   ;; Hidden / source-only standard library deps
   [clojure.core.specs.alpha]
   [clojure.core.server]
   [clojure.spec.gen.alpha]
   [clojure.spec.alpha]
   [clojure.pprint]
   [clojure.set]
   [clojure.walk]
   [clojure.edn]
   ;; Our real entry point
   [seon.podhost.libdatahike.spike :as spike])
  (:gen-class))

(defn -main [& args]
  (apply spike/-main args))

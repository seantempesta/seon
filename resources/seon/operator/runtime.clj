(ns seon.operator.runtime
  "Process-root custody that is absent from every cluster program graph.

  This namespace deliberately lives on the runtime classpath outside the
  indexed `src` and `test` roots. Cluster code may refer to these Vars, but a
  cluster database has no `:seon.ns` row for this namespace, so SCI acquisition
  never installs it.")

(defonce running-instances (atom {}))

(defonce root-store-holder (atom {}))

(defonce held-flocks (atom {}))


;; The Var space: one SCI Var per program name for the whole process, which
;; every SCI context binds and every analysed function body holds
;; (`seon.sci.runtime/space-var`). Vars are identity with no data form, so a
;; second space would split every analysis keyed against it.
(defonce sci-var-space
  {:seon.sci.runtime/space (random-uuid)
   :seon.sci.runtime/vars (java.util.concurrent.ConcurrentHashMap.)})

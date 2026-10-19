(ns seon.operator.runtime
  "Process-root custody that is absent from every cluster program graph.

  This namespace deliberately lives on the runtime classpath outside the
  indexed `src` and `test` roots. Cluster code may refer to these Vars, but a
  cluster database has no `:seon.ns` row for this namespace, so SCI acquisition
  never installs it.")

(defonce running-instances (atom {}))

(defonce root-store-holder (atom {}))

(defonce held-flocks (atom {}))


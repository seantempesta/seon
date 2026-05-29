(ns seon.repl
  "Sidecar overlay for V0's `seon.repl`. The V0 version requires
   `datahike.api` for its `dev-init!` path (which opens an in-memory
   datahike conn). Under the sidecar, the conn is JVM-owned; the guest
   never opens a local datahike conn. This overlay drops the
   `datahike.api` require + the `ensure-conn!` plumbing while keeping
   the compile-state / bootstrap-init surface that `seon.agent`,
   `seon.eval`, and `seon.render` actually reach for.

   Public surface kept (used by V0 substrate):
     - !compile-state    (defonce atom)
     - !init-version     (defonce atom)
     - !conn             (defonce atom, will hold the sidecar conn)
     - ensure-bootstrap! (async, returns compile-state)
     - parse-forms       (re-exported from seon.parse)

   Dropped (only used by V0's `dev-init!` flow that we don't run here):
     - ensure-conn!      (would open a datahike :memory conn)
     - dev-init!         (composes ensure-bootstrap! + ensure-conn!)"
  (:require
    [seon.eval :as seval]
    [seon.parse]))

(def parse-forms
  "Re-exported from `seon.parse/parse-forms`."
  seon.parse/parse-forms)

(defonce !compile-state (atom nil))
(defonce !init-version  (atom nil))
(defonce !conn          (atom nil))

(defn ^:async ensure-bootstrap! []
  (if (and @!compile-state
           (identical? @!init-version seval/init-version))
    @!compile-state
    (let [state (await (seval/init-bootstrap!))]
      (reset! !compile-state state)
      (reset! !init-version seval/init-version)
      state)))

(defn ^:async dev-init!
  "STUB — `dev-init!` under sidecar is just `ensure-bootstrap!`.
   The conn comes from the sidecar wiring, not from a local
   datahike instance."
  []
  (await (ensure-bootstrap!)))

(ns seon.system
  "Integrant system configuration and component definitions.

  Defines init-key and halt-key! methods for all system components:
  - :seon/xtdb-node - XTDB v2 database node
  - :seon/schema-registry - Malli schema registry
  - :seon.web.server/http-server - HTTP server for web UI
  - :seon/nrepl-server - nREPL for REPL-driven development"
  (:require [integrant.core :as ig]
            [clojure.java.io :as io]
            [taoensso.timbre :as log]
            [seon.db.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; XTDB Node Component
;;; ---------------------------------------------------------------------------

(defmethod ig/init-key :seon/xtdb-node
  [_ {:keys [storage memory-cache disk-cache compactor]}]
  (log/info "Starting XTDB node..." {:storage storage :compactor compactor})
  (require '[xtdb.node :as xtn])
  (require '[xtdb.api :as xt])
  ;; Ensure XTQL protocol namespaces are loaded to prevent classloader mismatches
  ;; after (reset). This guarantees the PlanQuery protocol extensions are in place.
  (require '[xtdb.xtql.plan])
  (let [start-node (resolve 'xtn/start-node)
        node (if (= storage :in-memory)
               (start-node)
               ;; XTDB v2 API: [:local {:path ...}] format
               (let [base-path (if (map? storage) (:path storage) (str storage))
                     config (cond-> {:log [:local {:path (io/file base-path "log")}]
                                     :storage [:local {:path (io/file base-path "objects")}]}
                              memory-cache (assoc :memory-cache memory-cache)
                              disk-cache (assoc :disk-cache disk-cache)
                              compactor (assoc :compactor compactor))]
                 (start-node config)))]
    (log/info "XTDB node started" {:compactor compactor})
    node))

(defmethod ig/halt-key! :seon/xtdb-node
  [_ node]
  (log/info "Stopping XTDB node...")
  (when node
    (.close node))
  (log/info "XTDB node stopped"))

;;; ---------------------------------------------------------------------------
;;; Primer XTDB Node Component
;;; ---------------------------------------------------------------------------

(defmethod ig/init-key :seon.primer/xtdb-node
  [_ {:keys [storage]}]
  (log/info "Starting Primer XTDB node..." {:storage storage})
  (require '[xtdb.node :as xtn])
  (require '[xtdb.api :as xt])
  (let [start-node (resolve 'xtn/start-node)
        node (if (= storage :in-memory)
               (start-node)
               ;; XTDB v2 API: [:local {:path ...}] format
               (let [base-path (if (map? storage) (:path storage) (str storage))]
                 (start-node {:log [:local {:path (io/file base-path "log")}]
                              :storage [:local {:path (io/file base-path "objects")}]})))]
    ;; Initialize ctx system with this node
    (require 'seon.primer.ctx)
    ((resolve 'seon.primer.ctx/init!) node)
    ((resolve 'seon.primer.ctx/start-auto-sync!) 5000) ; 5 second sync
    (log/info "Primer XTDB node started")
    node))

(defmethod ig/halt-key! :seon.primer/xtdb-node
  [_ node]
  (log/info "Stopping Primer XTDB node...")
  ;; Stop auto-sync before closing node
  (require 'seon.primer.ctx)
  ((resolve 'seon.primer.ctx/stop-auto-sync!))
  (when node
    (.close node))
  (log/info "Primer XTDB node stopped"))

;;; ---------------------------------------------------------------------------
;;; Python Bridge Component - DISABLED
;;; ---------------------------------------------------------------------------
;;; Python code moved to src/ml_options/_python_disabled
;;; To re-enable, restore the python directory and uncomment this section

;; (defmethod ig/init-key :seon/python-bridge
;;   [_ {:keys [conda-env auto-initialize?]}]
;;   (log/info "Initializing Python bridge..." {:conda-env conda-env})
;;   (when auto-initialize?
;;     (require '[libpython-clj2.python :as py])
;;     (let [initialize! (resolve 'py/initialize!)]
;;       ;; libpython-clj will read python.edn for configuration
;;       (initialize!)))
;;   (log/info "Python bridge initialized")
;;   {:conda-env conda-env
;;    :initialized? auto-initialize?})

;; (defmethod ig/halt-key! :seon/python-bridge
;;   [_ bridge]
;;   (log/info "Python bridge shutdown")
;;   ;; libpython-clj manages its own cleanup
;;   nil)

;;; ---------------------------------------------------------------------------
;;; Schema Registry Component
;;; ---------------------------------------------------------------------------

(defmethod ig/init-key :seon/schema-registry
  [_ _]
  (log/info "Initializing Malli schema registry...")
  (let [registry-value @schema/registry]
    (log/info "Schema registry initialized" {:schema-count (count registry-value)})
    registry-value))

(defmethod ig/halt-key! :seon/schema-registry
  [_ _]
  (log/info "Schema registry shutdown")
  nil)

;;; ---------------------------------------------------------------------------
;;; nREPL Server Component
;;; ---------------------------------------------------------------------------

(defmethod ig/init-key :seon/nrepl-server
  [_ {:keys [enabled? port bind]}]
  (if enabled?
    (do
      (require 'nrepl.server)
      (require 'cider.nrepl)
      (log/info "Starting nREPL server" {:port port :bind bind})
      (let [start-server (resolve 'nrepl.server/start-server)
            handler @(resolve 'cider.nrepl/cider-nrepl-handler)
            server (start-server :port port :bind bind :handler handler)]
        ;; Write .nrepl-port file for tooling discovery
        (spit ".nrepl-port" (str port))
        (log/info "nREPL server started" {:port port})
        server))
    (do
      (log/info "nREPL server disabled for this profile")
      nil)))

(defmethod ig/halt-key! :seon/nrepl-server
  [_ server]
  (when server
    (log/info "Stopping nREPL server...")
    (require 'nrepl.server)
    ((resolve 'nrepl.server/stop-server) server)
    ;; Clean up .nrepl-port file
    (when (.exists (java.io.File. ".nrepl-port"))
      (.delete (java.io.File. ".nrepl-port")))
    (log/info "nREPL server stopped")))

;; Keep nREPL alive during (reset) - critical for REPL-driven development
(defmethod ig/suspend-key! :seon/nrepl-server [_ server] server)

(defmethod ig/resume-key :seon/nrepl-server
  [key opts old-opts old-server]
  (if (= opts old-opts)
    old-server
    (do (ig/halt-key! key old-server)
        (ig/init-key key opts))))


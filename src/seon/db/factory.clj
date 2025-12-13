(ns seon.db.factory
  "Creates XTDB nodes for domains.

  This factory enables Seon to manage multiple XTDB database nodes,
  with each domain (trading, health, finance, etc.) having its own
  isolated database instance."
  (:require
   [xtdb.node :as xtn]))

(defn create-node
  "Create an XTDB node for a domain.

  Args:
    domain-id - keyword identifier like :trading, :health
    opts - configuration map:
           {:path \"data/trading\"} - for persistent storage
           {:in-memory? true} - for ephemeral in-memory node

  Returns:
    An XTDB node instance

  Examples:
    ;; Persistent node for production
    (create-node :trading {:path \"data/trading\"})

    ;; In-memory node for testing
    (create-node :test {:in-memory? true})"
  [domain-id opts]
  (if (:in-memory? opts)
    (xtn/start-node)
    (let [base-path (:path opts)]
      (xtn/start-node {:log [:local {:path (str base-path "/log")}]
                       :storage [:local {:path (str base-path "/storage")}]}))))

(defn stop-node
  "Stop an XTDB node gracefully.

  Args:
    node - XTDB node instance to close

  Note: This is idempotent - safe to call multiple times."
  [node]
  (.close node))

(ns my.agent
  "Read and update my record through request maps."
  (:require [seon.agent :as agent]))

(defn settings
  "Read my setting overrides; omitted settings inherit the cluster defaults."
  {:malli/schema [:=> [:cat :my.plan/request] [:or :seon.config/agent-overlay :seon.error/value]]}
  [request]
  (agent/settings (:seon.db/db request) (:seon.agent/id request)))

(defn settings!
  "Change supplied setting overrides and return my resulting overrides."
  {:malli/schema [:=> [:cat :my.agent/settings-request] [:or :seon.config/agent-overlay :seon.error/value]]}
  [request]
  (agent/settings! (dissoc request :seon.db/connection :seon.agent/id)
                   (:seon.db/connection request) (:seon.agent/id request)))

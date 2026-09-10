(ns my.agent
  "Read and update my record through request maps."
  (:refer-clojure :exclude [identity])
  (:require [seon.agent :as agent]
            [seon.run :as run]))

(defn identity
  "Read my identity, assigned namespace, and its steward."
  {:malli/schema [:=> [:cat :my.plan/request] [:or :my.agent/identity :seon.error/value]]}
  [request]
  (agent/identity (:seon.db/db request) (:seon.agent/id request)))

(defn done
  "End my session now; a later outside wake may start another session.

  Finish only after verifying the requested result and sending any reply.
  Call preparation supplies my database and agent identity.

  Example:
  (my.agent/done {})"
  {:malli/schema [:=> [:cat :my.plan/request] [:or :my.turn/wait :seon.error/value]]}
  [_request]
  (run/wait "Session complete."))

(defn settings
  "Read my setting overrides; omitted settings inherit the cluster defaults."
  {:malli/schema [:=> [:cat :my.plan/request] [:or :my.agent/settings :seon.error/value]]}
  [request]
  (agent/settings (:seon.db/db request) (:seon.agent/id request)))

(defn settings!
  "Change supplied setting overrides and return my resulting overrides."
  {:malli/schema [:=> [:cat :my.agent/settings-request] [:or :seon.config/agent-overlay :seon.error/value]]}
  [request]
  (agent/settings! (dissoc request :seon.db/connection :seon.agent/id)
                   (:seon.db/connection request) (:seon.agent/id request)))

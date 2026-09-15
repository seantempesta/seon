(ns ^{:seon.ns/context-relevant? true} my.agent
  "Read and update my record through request maps."
  (:refer-clojure :exclude [identity])
  (:require [seon.agent :as agent]
            [seon.run :as run]))

(defn identity
  "Read my identity, assigned namespace, and its steward.

  Returns :my.agent/id, with :my.agent/namespace and :my.agent/steward
  when assigned. My database and identity are supplied.

  Example:
  (my.agent/identity)"
  {:malli/schema [:=> [:cat :my.plan/request] [:or :my.agent/identity :seon.error/value]]}
  [request]
  (agent/identity (:seon.db/db request) (:seon.agent/id request)))

(defn done
  "End my session now; a later outside wake may start another session.

  Finish only after verifying the requested result and sending any reply.
  Call preparation supplies my database and agent identity.
  Returns {:my.turn/disposition :wait :my.turn/note \"Session complete.\"}; the
  turn interprets this value when it is the result of a form.

  Example:
  (my.agent/done {})"
  {:malli/schema [:=> [:cat :my.plan/request] [:or :my.turn/wait :seon.error/value]]}
  [_request]
  (run/wait "Session complete."))

(defn settings
  "Read my setting overrides of cluster defaults.

  Returns a map of :seon.config setting overrides. Omitted settings inherit
  cluster defaults; seon.agent/effective-settings reads effective values.

  Example:
  (my.agent/settings)"
  {:malli/schema [:=> [:cat :my.plan/request] [:or :my.agent/settings :seon.error/value]]}
  [request]
  (agent/settings (:seon.db/db request) (:seon.agent/id request)))

(defn settings!
  "Change supplied setting overrides and return my resulting overrides.

  Supply fully qualified :seon.config keys. Omitted keys retain their values.
  Returns a map of :seon.config setting overrides for my agent.

  Example:
  (my.agent/settings! {:seon.config.eval/time-limit-ms 10000})"
  {:malli/schema [:=> [:cat :my.agent/settings-request] [:or :seon.config/agent-overlay :seon.error/value]]}
  [request]
  (agent/settings! (dissoc request :seon.db/connection :seon.agent/id)
                   (:seon.db/connection request) (:seon.agent/id request)))

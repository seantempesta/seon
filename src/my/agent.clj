(ns ^{:seon.ns/context-relevant? true} my.agent
  "Read and update my record through request maps."
  (:refer-clojure :exclude [identity])
  (:require [seon.agent :as agent]
            [seon.db :as db]
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

  Supply per-agent dials, the fully qualified :seon.config keys that
  :seon.config/agent-overlay declares. Omitted keys retain their values.
  Returns a map of :seon.config setting overrides for my agent. My connection
  and identity are supplied.

  Example:
  (my.agent/settings! {:seon.config.eval/time-limit-ms 10000})"
  {:malli/schema [:=> [:cat :seon.config/agent-overlay :seon.db/connection :seon.agent/id]
                  [:or :seon.config/agent-overlay :seon.error/value]]}
  [overrides connection agent-id]
  (agent/settings! overrides connection agent-id))

(defn branch
  "Read the branch my context holds and its live or isolated mode.

  The branch is the one my supplied connection holds. The mode is the one my
  acquisition of that branch decided (seon.cluster.agent/acquire-context!),
  read from its entry in my execution's roster; neither is derived from a cluster name.

  Example:
  (my.agent/branch)"
  {:malli/schema [:=> [:cat [:map [:seon.agent/id :seon.agent/id]
                             [:my.program/context :my.program/context]]]
                  [:map [:seon.agent/branch :seon.agent/branch]
                   [:seon.agent/mode :seon.agent/mode]]]}
  [{agent-id :seon.agent/id
    {connection :seon.db/connection roster :seon.agent/routing} :my.program/context}]
  ;; Mode is relative to the acquiring parent, so only this agent's own entry answers.
  (let [held (get-in (db/db connection) [:config :branch])
        mode (:seon.agent/mode (get-in (some-> roster deref) [:seon.agent/contexts [held agent-id]]))]
    (when-not (and held mode)
      (throw (ex-info "No acquisition of this agent holds its connection's branch."
                      {:seon.agent/id agent-id :seon.agent/branch held})))
    {:seon.agent/branch held :seon.agent/mode mode}))

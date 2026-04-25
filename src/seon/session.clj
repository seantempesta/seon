(ns seon.session
  "Owner of `:seon.session` — the canonical agent session registry.

   One row per running agent JVM: id, namespace, port, status, ctx checkpoint.
   Phase 3 step 1 of the datahike migration: this namespace declares the
   schemas that the `:seon.db/flow` conn-process installs at init. Public
   API (launch/checkpoint/stop/resume) lands in later Phase 3 steps; this
   file is plumbing only."
  (:require [seon.db.schema :as db-schema]
            [seon.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; Attribute Schemas
;;; ---------------------------------------------------------------------------

(schema/register! ::agent
                  [:string {:min 1
                            :seon.db/identity true
                            :description "Session id (e.g. \"a5ba3e\")"}])

(schema/register! ::namespace
                  [:symbol {:description "Agent namespace symbol (e.g. seon.apps.demo)"}])

(schema/register! ::port
                  [:int {:min 1 :max 65535
                         :description "nREPL port the agent JVM is listening on"}])

(schema/register! ::pid
                  [:int {:min 1 :description "Operating-system process id of the agent JVM"}])

(schema/register! ::started-at
                  [:inst {:description "Wall-clock time the session started"}])

(schema/register! ::status
                  [:enum :starting :running :idle :stopping :stopped :crashed :merged])

(schema/register! ::ctx
                  [:string {:description "Serialized ctx checkpoint blob (Nippy/pr-str)"}])

;;; ---------------------------------------------------------------------------
;;; Entity Schema
;;; ---------------------------------------------------------------------------

(def agent-entity-schema
  "Malli :map schema for an agent session row, installed on the
   `:seon.session` datahike DB via `:seon.db/flow`'s `:namespace-schemas`."
  [:map
   [::agent ::agent]
   [::namespace ::namespace]
   [::port {:optional true} ::port]
   [::pid {:optional true} ::pid]
   [::started-at ::started-at]
   [::status ::status]
   [::ctx {:optional true} ::ctx]])

(db-schema/register-entity-schema! "seon.session/agent" agent-entity-schema)

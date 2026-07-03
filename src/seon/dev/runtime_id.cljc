(ns seon.dev.runtime-id
  "The MCP runtime-addressing probe surface (mcp-agent-id-unification
   PRD, 2026-06-10). ONE id grammar: a runtime answers the probe with
   the VECTOR of ids it hosts — core `:seon.agent/id` strings for
   agent-hosting processes (the pod hosts every agent it resumed or
   minted), or `proc:<name>` for non-agent infrastructure runtimes
   (wire-node = `proc:wire`). `bin/mcp-server-cljs` resolves an
   `agent_id` eval by MEMBERSHIP: it enumerates shadow runtimes, evals
   `(seon.dev.runtime-id/hosted)` in each, and pins the runtime whose
   answer contains the id.

   The `proc:` prefix can never collide with a real agent id —
   `seon.db/new-id!` never emits `:` — so the two populations are
   disjoint by construction while sharing one resolver.

   ZERO requires by design: this ns is compiled into every build that
   wants to be MCP-addressable (`:client`, `:wire-node`, `:node-agent`)
   and must cost nothing beyond one atom — the slim `:wire-node` build
   must not drag the bootstrap compiler or the store layer."
  (:require [clojure.string :as str]))

;; defonce — a hot reload of any ns that calls host! must not wipe the
;; hosted set; re-arm paths re-host idempotently anyway.
(defonce ^:private !hosted (atom #{}))

(defn host!
  "Register `id` as hosted by THIS process; idempotent.

   `id` is a core `:seon.agent/id` string, or `proc:<name>` for infra runtimes."
  {:malli/schema [:=> [:catn [:seon.dev.runtime-id/id :string]] :string]}
  [id]
  (when-not (str/blank? id)
    (swap! !hosted conj id))
  id)

(defn unhost!
  "Remove `id` from this process's hosted set. Idempotent."
  {:malli/schema [:=> [:catn [:seon.dev.runtime-id/id :string]] :string]}
  [id]
  (swap! !hosted disj id)
  id)

(defn hosted
  "The ids this runtime answers to — sorted vector.

   THE probe form `bin/mcp-server-cljs` evals into each shadow runtime to resolve
   `agent_id` → client-id by membership."
  {:malli/schema [:=> [:cat] [:vector :string]]}
  []
  (vec (sort @!hosted)))

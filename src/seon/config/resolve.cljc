(ns seon.config.resolve
  "Pure manifest, singleton, and boot-envelope resolution."
  (:require #?@(:bb [[aero.core :as aero]]
                :cljs [[aero.core :as aero]])
            [clojure.string :as str]
            #?(:cljs [goog.string :as gstring])
            [malli.core :as m]
            [malli.transform :as mt]
            [seon.db.protocol :as protocol]
            [seon.schema :as schema]))

;; The optional pull-reference corpus directory is one ordinary string. Corpus
;; rows are never injected into an agent's context tree. Roots identify by id
;; "root", never a stored `:seon.agent/kind` / config `:role`.
(schema/register! :seon.config/skills-dir
  [:string
   {:min 1
    :error/message
    "The skills directory must be a non-empty string; omit :seon.config/skills-dir for no corpus."}])

;;; NAMESPACE SOURCE-STORAGE policy (#42 explicit listing). The cluster manifest
;;; chooses the non-`my.*` namespace files whose complete source is indexed and
;;; therefore available for a per-agent namespaces block to select. Runtime
;;; render selection lives only on that block; cluster config does not carry a
;;; second current/full/compact switch.

(schema/register! :seon.config/namespaces-spec
  [:map {:closed true}
   ;; Complete source is stored for these framework namespaces so a namespaces
   ;; block may select it later. This list does not itself render anything.
   [:seon.config/always
    {:optional true}
    [:vector
     {:min 1
      :error/message
      "The always-source declaration must contain symbols; omit it to use the shipped policy."}
     :symbol]]])

;; The RESOLVED symbol policy the renderer + boot indexer read. Registered once + referenced by
;; [[resolve-namespaces]].
(schema/register! :seon.config/namespaces-policy
  [:map
   [:seon.config/always
    [:set
     {:min 1
      :error/message
      "The resolved always-source policy must contain at least one symbol."}
     :symbol]]])

(schema/register! :seon.config/route-spec
  [:map
   [:seon.config/removes {:optional true} [:vector :keyword]]])

;;; Per-knob LEAF attrs — each knob's ONE registered shape, referenced by BOTH
;;; the manifest section specs below AND the `:seon.config` singleton entity
;;; schema (config-db-migration 2026-07-10): register once, reference
;;; everywhere. Enum/int/boolean/string scalars store natively as singleton
;;; datoms; collection knobs whose database shape differs from their manifest
;;; declaration are registered with the singleton block.

;; Shared positive-int cap shape — every render/eval/timeout cap knob
;; references it (register-once, no inline duplication).
(schema/register! :seon.config/cap [:int {:min 1}])

(schema/register! :seon.config.render/database-edn-cap   :seon.config/cap)
(schema/register! :seon.config.render/eval-cap           :seon.config/cap)
(schema/register! :seon.config.render/message-cap        :seon.config/cap)
(schema/register! :seon.config.render/result-body-cap    :seon.config/cap)
(schema/register! :seon.config.render/value-max-depth    :seon.config/cap)
(schema/register! :seon.config.render/value-max-keys     :seon.config/cap)
(schema/register! :seon.config.render/value-max-items    :seon.config/cap)
(schema/register! :seon.config.render/value-max-path-segments :seon.config/cap)
(schema/register! :seon.config.render/value-max-path-bytes :seon.config/cap)
(schema/register! :seon.config.render/value-max-realized-items :seon.config/cap)
(schema/register! :seon.config.render/value-max-string   :seon.config/cap)
(schema/register! :seon.config.render/value-shape-sample :seon.config/cap)
(schema/register! :seon.config.render/value-verbatim-cap :seon.config/cap)
(schema/register! :seon.config.render/value-width        :seon.config/cap)
;; TOKEN cap (not chars — the auto-run family is token-denominated) for ONE
;; current-ns auto-run render fn's ai output (seon.agent.ctx.render-fns).
(schema/register! :seon.config.render/render-fn-token-cap :seon.config/cap)
;; MODEL-TRANSPORT evidence bounds are cluster policy, not provider selection.
;; They therefore live on the `:seon.config` singleton instead of the separate
;; `:seon.ai/config` row. Policy values live only in selected manifests;
;; absence remains absence in a config-free historical database.
(schema/register! :seon.config.model-transport/response-identity-cap
  [:int {:min 1}])
(schema/register! :seon.config.model-transport/endpoint-cap
  [:int {:min 1}])
(schema/register! :seon.config/model-transport
  [:map
   [:seon.config.model-transport/response-identity-cap
    {:optional true} :seon.config.model-transport/response-identity-cap]
   [:seon.config.model-transport/endpoint-cap
    {:optional true} :seon.config.model-transport/endpoint-cap]])
;; EXPLICIT-CHARACTER knobs (transcript-render redesign) — for content the
;; agent edits byte-exactly. Every DEFAULT reproduces today's bytes, so an
;; absent section / `{}` boot is byte-identical.
;;   :whitespace     :raw     — literal (default) | :visible — `·`/`→` glyphs
;;   :tabs           :literal — literal `\t` (default) | :arrow — `→`
;;   :trailing-ws    :off     — no marker (default) | :dot — `·` on trailing ws
;;   :content-layout :structured — multi-line body (default) | :single-line
;;   :line-numbers   false    — no gutter (default) | true — 1-based gutter
(schema/register! :seon.config.render/whitespace     [:enum :raw :visible])
(schema/register! :seon.config.render/tabs           [:enum :literal :arrow])
(schema/register! :seon.config.render/trailing-ws    [:enum :off :dot])
(schema/register! :seon.config.render/content-layout [:enum :structured :single-line])
(schema/register! :seon.config.render/line-numbers   :boolean)
;; Repair dial scalars (level enum inlined by the LEAF rule — `seon.config`
;; loads before `seon.repl.parse.repair`, so no keyword ref to `:seon.repl.parse.repair/level`).
(schema/register! :seon.config.repair/level
  [:enum :off :safe-syntax :symbols :aggressive])
(schema/register! :seon.config.repair/max-fixes-per-form :seon.config/cap)
(schema/register! :seon.config.repair/budget-ms          :seon.config/cap)

(def repair-class-attributes
  "Manifest repair classes mapped to their native singleton attributes."
  {:seon.repl.parse.repair/delimiters
   :seon.config.repair.class/delimiters?
   :seon.repl.parse.repair/def-vs-defn
   :seon.config.repair.class/def-vs-defn?
   :seon.repl.parse.repair/undeclared-var
   :seon.config.repair.class/undeclared-var?})

(doseq [attribute (vals repair-class-attributes)]
  (schema/register! attribute :boolean))
;; Multi-agent dials (watchdog staleness, schedule-breaker N + window).
(schema/register! :seon.config.watchdog/stale-ms    :seon.config/cap)
(schema/register! :seon.config.breaker/crash-count  :seon.config/cap)
(schema/register! :seon.config.breaker/window-ms    :seon.config/cap)
(schema/register! :seon.config.root/recent-limit    :seon.config/cap)
(schema/register! :seon.config/reactive-settle-ms :seon.config/cap)
(schema/register! :seon.config/reactive-structural-settle-ms :seon.config/cap)
(schema/register! :seon.config/reactive-max-latency-ms :seon.config/cap)
(schema/register! :seon.config.execution/host-tier? :boolean)
(schema/register! :seon.config.execution/host-respawn-backoff-ms
  :seon.config/cap)
(schema/register!
 :seon.config/execution
 [:map {:closed true}
  [:seon.config.execution/host-tier?
   {:optional true} :seon.config.execution/host-tier?]
  [:seon.config.execution/host-respawn-backoff-ms
   {:optional true} :seon.config.execution/host-respawn-backoff-ms]])

;;; DATABASE READ RESOURCE POLICY — runaway-work ceilings, not pagination.
;;; Datahike defines `max-work` as charged execution steps, `max-results` as
;;; retained result nodes (including nested pull values), and
;;; `max-result-weight` as shallow scalar/container weight. The latter is not a
;;; byte count. The protocol's 4 MiB encoded-frame cap is a separate hard
;;; boundary because Transit overhead and string encoding are data-dependent.
(schema/register! :seon.config.database.query/max-work :seon.config/cap)
(schema/register! :seon.config.database.query/max-results :seon.config/cap)
(schema/register! :seon.config.database.query/max-result-weight :seon.config/cap)
(schema/register! :seon.config.database.pull/max-work :seon.config/cap)
(schema/register! :seon.config.database.pull/max-results :seon.config/cap)
(schema/register! :seon.config.database.pull/max-result-weight :seon.config/cap)
(def operational-keys
  "Boot-critical configuration attributes carried by every launch."
  [:seon.config.database.writer/jvm-heap-mb
   :seon.config.database.read/max-work
   :seon.config.database.read/max-results
   :seon.config.database.read/max-result-weight
   :seon.config.database.read/deadline-ms
   :seon.config.database.executor/selected-processors
   :seon.config.database.executor/maximum-queued-request-bytes
   :seon.config.database.executor.read/maximum-active
   :seon.config.database.executor.read/maximum-queued
   :seon.config.database.executor.read/maximum-queued-by-database
   :seon.config.database.executor.knn/maximum-active
   :seon.config.database.executor.knn/maximum-queued
   :seon.config.database.executor.knn/maximum-queued-by-database
   :seon.config.database.executor.provider/maximum-active
   :seon.config.database.executor.provider/maximum-queued
   :seon.config.database.executor.provider/maximum-queued-by-database
   :seon.config.database.executor.mutation/maximum-active
   :seon.config.database.executor.mutation/maximum-queued
   :seon.config.database.executor.mutation/maximum-queued-by-database
   :seon.config.database.executor.delivery/maximum-active
   :seon.config.database.executor.delivery/maximum-queued
   :seon.config.database.executor.delivery/maximum-queued-by-database
   :seon.config.database.executor.hnsw/maximum-active
   :seon.config.database.executor.hnsw/maximum-queued
   :seon.config.database.executor.hnsw/maximum-queued-by-database
   :seon.config.database.transport/maximum-frame-bytes
   :seon.config.database.transport/maximum-connections
   :seon.config.database.transport/maximum-input-bytes
   :seon.config.database.transport/maximum-response-slots
   :seon.config.database.transport/maximum-session-response-slots
   :seon.config.database.transport/maximum-output-bytes
   :seon.config.database.transport/maximum-session-output-bytes
   :seon.config.database.transport/shutdown-timeout-ms
   :seon.config.database.transport/codec-workers
   :seon.config.database.transport/codec-worker-queue-capacity])

(doseq [attribute operational-keys]
  (schema/register! attribute :seon.config/cap))

(def enforced-keys
  "Operational attributes enforced by launch constructor surfaces."
  #{:seon.config.database.writer/jvm-heap-mb
    :seon.config.database.read/max-work
    :seon.config.database.read/max-results
    :seon.config.database.read/max-result-weight
    :seon.config.database.read/deadline-ms
    :seon.config.database.executor/selected-processors
    :seon.config.database.executor/maximum-queued-request-bytes
    :seon.config.database.executor.read/maximum-active
    :seon.config.database.executor.read/maximum-queued
    :seon.config.database.executor.read/maximum-queued-by-database
    :seon.config.database.executor.knn/maximum-active
    :seon.config.database.executor.knn/maximum-queued
    :seon.config.database.executor.knn/maximum-queued-by-database
    :seon.config.database.executor.provider/maximum-active
    :seon.config.database.executor.provider/maximum-queued
    :seon.config.database.executor.provider/maximum-queued-by-database
    :seon.config.database.executor.mutation/maximum-active
    :seon.config.database.executor.mutation/maximum-queued
    :seon.config.database.executor.mutation/maximum-queued-by-database
    :seon.config.database.executor.delivery/maximum-active
    :seon.config.database.executor.delivery/maximum-queued
    :seon.config.database.executor.delivery/maximum-queued-by-database
    :seon.config.database.executor.hnsw/maximum-active
    :seon.config.database.executor.hnsw/maximum-queued
    :seon.config.database.executor.hnsw/maximum-queued-by-database
    :seon.config.database.transport/maximum-frame-bytes
    :seon.config.database.transport/maximum-connections
    :seon.config.database.transport/maximum-input-bytes
    :seon.config.database.transport/maximum-response-slots
    :seon.config.database.transport/maximum-session-response-slots
    :seon.config.database.transport/maximum-output-bytes
    :seon.config.database.transport/maximum-session-output-bytes
    :seon.config.database.transport/shutdown-timeout-ms
    :seon.config.database.transport/codec-workers
    :seon.config.database.transport/codec-worker-queue-capacity})

(schema/register! :seon.hardware/cores :seon.config/cap)
(schema/register! :seon.hardware/system-memory-bytes :seon.config/cap)
(schema/register! :seon.hardware/fd-soft-limit :seon.config/cap)
(schema/register! :seon.config.resolve/hardware-observations
  [:map {:closed true}
   [:seon.hardware/cores :seon.hardware/cores]
   [:seon.hardware/system-memory-bytes :seon.hardware/system-memory-bytes]
   [:seon.hardware/fd-soft-limit :seon.hardware/fd-soft-limit]])
(schema/register! :seon.launch.envelope/generation [:int {:min 0}])
(schema/register! :seon.launch.envelope/disposition [:enum :enforced :carried])
(schema/register! :seon.launch.envelope/dispositions
  [:map-of :keyword :seon.launch.envelope/disposition])
(def ^:private operational-manifest-entries
  (mapv (fn [attribute] [attribute {:optional true} attribute])
        operational-keys))

(schema/register! :seon.config/database
  (into [:map {:closed true}
   [:seon.config.database.query/max-work
    {:optional true} :seon.config.database.query/max-work]
   [:seon.config.database.query/max-results
    {:optional true} :seon.config.database.query/max-results]
   [:seon.config.database.query/max-result-weight
    {:optional true} :seon.config.database.query/max-result-weight]
   [:seon.config.database.pull/max-work
    {:optional true} :seon.config.database.pull/max-work]
   [:seon.config.database.pull/max-results
    {:optional true} :seon.config.database.pull/max-results]
   [:seon.config.database.pull/max-result-weight
    {:optional true} :seon.config.database.pull/max-result-weight]]
   operational-manifest-entries))

;;; RENDER BOUNDS — the GLOBAL, cluster-wide render/value display caps (#46).
;;; These are NOT per-agent: they bound the value/eval/message renderers for
;;; the whole cluster, seeded from this manifest section into the
;;; `:seon.config` singleton at boot. Env OVERRIDES config (owner model): the
;;; manifest declares each knob as `#long #or [#env SEON_RENDER_* default]` in
;;; `config/system.edn` — env set → the coerced env value, env unset → the
;;; manifest default. The keys here are `{:optional true}` WITHOUT a `:default`
;;; (decision: default in ONE place — the manifest `#or`); the accessors below
;;; apply the SAME literal as their own fallback when the section is absent.
(schema/register! :seon.config/render
  [:map {:closed true}
   [:seon.config.render/database-edn-cap   {:optional true} :seon.config.render/database-edn-cap]
   [:seon.config.render/eval-cap           {:optional true} :seon.config.render/eval-cap]
   [:seon.config.render/message-cap        {:optional true} :seon.config.render/message-cap]
   [:seon.config.render/result-body-cap    {:optional true} :seon.config.render/result-body-cap]
   [:seon.config.render/value-max-depth    {:optional true} :seon.config.render/value-max-depth]
   [:seon.config.render/value-max-keys     {:optional true} :seon.config.render/value-max-keys]
   [:seon.config.render/value-max-items    {:optional true} :seon.config.render/value-max-items]
   [:seon.config.render/value-max-path-segments
    {:optional true} :seon.config.render/value-max-path-segments]
   [:seon.config.render/value-max-path-bytes
    {:optional true} :seon.config.render/value-max-path-bytes]
   [:seon.config.render/value-max-realized-items
    {:optional true} :seon.config.render/value-max-realized-items]
   [:seon.config.render/value-max-string   {:optional true} :seon.config.render/value-max-string]
   [:seon.config.render/value-shape-sample {:optional true} :seon.config.render/value-shape-sample]
   [:seon.config.render/value-verbatim-cap {:optional true} :seon.config.render/value-verbatim-cap]
   [:seon.config.render/value-width        {:optional true} :seon.config.render/value-width]
   [:seon.config.render/render-fn-token-cap {:optional true} :seon.config.render/render-fn-token-cap]
   [:seon.config.render/whitespace     {:optional true} :seon.config.render/whitespace]
   [:seon.config.render/tabs           {:optional true} :seon.config.render/tabs]
   [:seon.config.render/trailing-ws    {:optional true} :seon.config.render/trailing-ws]
   [:seon.config.render/content-layout {:optional true} :seon.config.render/content-layout]
   [:seon.config.render/line-numbers   {:optional true} :seon.config.render/line-numbers]])

;; THE manifest — the registry of known sections. A future section = ONE more
;; optional key here + a resolver fn. Every key optional ⇒ `{}` (config absent)
;; validates ⇒ identity everywhere.
;;; ============================================================
;;; AGENT-CONTEXT — the v3 two-level context config (decisions 13/16/4). ONE
;;; nested map: agent-level scalars + a `:seon.agent/ctx` vector of BLOCK maps
;;; (component-ref'd onto the agent at transact). The manifest owns the desired
;;; seed tree; absent config means an empty tree, never a hidden code fallback.
;;; LEAF rule holds: the block vector is a loose
;;; `[:vector :map]` (block/attr shapes register + validate downstream at
;;; install!/transact!), so `seon.config` never requires `seon.agent.ctx` —
;;; the `:seon.render/ai` values are literal quoted symbols
;;; (VERIFIED to survive `m/decode` as `cljs.core/Symbol`), not var refs.
;;; ============================================================

;; Agent-level keys are persisted on the agent entity and read reactively.
;; Runtime/home keys plus the complete non-secret `:seon.ai/agent-*` overlay are
;;       declared HERE (referencing their owning ns's registered shape) so the
;;       recursive decode validates overrides before `seon.agent` includes
;;       them in the atomic birth transaction. Default = today's value ⇒ parity.
;; The persisted agent-level dials (`:seon.agent.lifecycle/wake?`, `:seon.eval/home-requires`)
;; carry NO schema `:default` here — their DEFAULT lives ONCE at the CONSUMER
;; (the runtime's acquired wake value → true; `seon.agent.home/home-requires-for` → the
;; `home-ns-require-specs` const). So a no-config agent never gets the datom
;; (the consumer's fallback = byte-parity), and the manifest sets the key ONLY to
;; OVERRIDE. Declared LEAF-shaped (NOT a keyword ref — `seon.config` is a leaf
;; that loads before `seon.eval`/`seon.client`, and the full shape is validated
;; downstream at `transact!`, the same rule the block vector uses).
(def ^:private agent-model-config-entries
  "Leaf-shaped per-agent model fields accepted before `seon.ai` loads.

   These logical values are validated again by `:seon.ai/agent-config` when
   transacted. `:seon.ai/agent-api-key-env` stores only an environment-variable
   name; credentials never enter config or the database."
  (let [inherit-error
        {:error/message
         "Explicit :inherit is invalid for a per-agent AI override; absence means inherit."}]
    [[:seon.ai/agent-provider
      {:optional true}
      [:enum inherit-error
       :deepseek :anthropic :openai-compat :diffusiongemma :typeahead]]
     [:seon.ai/agent-model
      {:optional true} [:string (assoc inherit-error :min 1)]]
     [:seon.ai/agent-temperature
      {:optional true} [:double inherit-error]]
     [:seon.ai/agent-max-tokens
      {:optional true} [:int inherit-error]]
     [:seon.ai/agent-completion-limit-field
      {:optional true}
      [:enum inherit-error :max-tokens :max-completion-tokens]]
     [:seon.ai/agent-thinking
      {:optional true} [:string (assoc inherit-error :min 1)]]
     [:seon.ai/agent-timeout-ms
      {:optional true} [:int inherit-error]]
     [:seon.ai/agent-base-url
      {:optional true} [:string (assoc inherit-error :min 1)]]
     [:seon.ai/agent-api-key-env
      {:optional true} [:string (assoc inherit-error :min 1)]]
     [:seon.ai/agent-dg-backend
      {:optional true} [:enum inherit-error :vllm :control]]
     [:seon.ai/agent-extra-body-edn
      {:optional true} [:string (assoc inherit-error :min 1)]]
     [:seon.ai/agent-max-retries
      {:optional true} [:int (assoc inherit-error :min 0)]]
     [:seon.ai/agent-attempt-timeout-ms
      {:optional true} [:int (assoc inherit-error :min 1)]]
     [:seon.ai/agent-fallback-variant
      {:optional true} [:and inherit-error :seon.config/model-variant]]
     ;; Turn grammar is launch-role data too. A planning or generated repair
     ;; agent must be able to consume one multi-namespace batch without changing
     ;; the cluster default used by ordinary agents.
     [:seon.config/repl-mode
      {:optional true} [:or [:enum :inherit] :seon.config/repl-mode]]]))

(def ^:private agent-model-config-schema
  (into [:map {:closed true}] agent-model-config-entries))

;; The cluster-default, non-secret LLM selection. These are the global row's
;; owning `:seon.ai/*` terms, kept leaf-shaped because `seon.config` loads
;; before `seon.ai`. Credentials remain environment values; `api-key-env`
;; stores only the name of the environment variable that holds one.
(schema/register! :seon.config/ai
  [:map {:closed true}
   [:seon.ai/provider
    [:enum :deepseek :anthropic :openai-compat :diffusiongemma :typeahead]]
   [:seon.ai/model {:optional true} [:string {:min 1}]]
   [:seon.ai/thinking {:optional true} [:string {:min 1}]]
   [:seon.ai/base-url {:optional true} [:string {:min 1}]]
   [:seon.ai/api-key-env {:optional true} [:string {:min 1}]]])

(schema/register! :seon.config/ai-row
  [:map {:closed true}
   [:seon.ai/id [:= "config"]]
   [:seon.ai/provider
    [:enum :deepseek :anthropic :openai-compat :diffusiongemma :typeahead]]
   [:seon.ai/model {:optional true} [:string {:min 1}]]
   [:seon.ai/thinking {:optional true} [:string {:min 1}]]
   [:seon.ai/base-url {:optional true} [:string {:min 1}]]
   [:seon.ai/api-key-env {:optional true} [:string {:min 1}]]])

(schema/register! :seon.config/ai-rows
  [:vector {:max 1} :seon.config/ai-row])

(schema/register! :seon.config/model-variant
                  [:and {:seon.db/identity true} :keyword [:not= :inherit]])
(schema/register! :seon.config/model-variants-spec
                  [:map-of :seon.config/model-variant
                   agent-model-config-schema])

(schema/register! :seon.config/model-variant-entity
  (into [:map {:seon.db/entity true}
         [:seon.config/model-variant :seon.config/model-variant]]
        agent-model-config-entries))

(schema/register! :seon.config/agent-context
  [:or
   (into
    [:map
     ;; Persisted agent datoms — override-only (no default; consumer owns it).
     [:seon.agent.lifecycle/wake? {:optional true} :boolean]
     [:seon.eval/home-requires {:optional true} [:vector :any]]
     [:seon.agent/ctx {:optional true :default []} [:vector :map]]]
    agent-model-config-entries)
   :nil])

;; The ROOT override — a SPARSE agent-context merged over `:seon.config/agent-context`
;; by [[context-config-for]] (block upsert-by-name). Its `:canvas` block sets
;; root's canvas = `system-view`, REPLACING the hardcoded client.cljs root branch.
;; NOT decoded through the transformer directly (it's a partial override layer);
;; only the MERGED result is decoded. Same loose `[:vector :map]` leaf shape.
(schema/register! :seon.config/root-context
  [:or
   (into
    [:map
     ;; root can override its home-ns require list (e.g. add `[seon.agent :as agent]`
     ;; so root additionally shows the orchestration card). Same leaf shape +
     ;; override-only semantics as `:seon.config/agent-context` — merged by
     ;; [[context-config-for]] onto the defaulted base for id "root".
     [:seon.eval/home-requires {:optional true} [:vector :any]]
     [:seon.agent/ctx {:optional true} [:vector :map]]]
    agent-model-config-entries)
   :nil])

;; The core-fault escalation dial (error-blame-strict-gate, RULED
;; 2026-07-04): what a `:core`-fault `seon.error/record!` does BEYOND
;; persisting its datom. `:crash` = persist first, then loud exit (dev,
;; after the catch-site sweep); `:gate` = pod stays alive, the CI-shaped
;; wrappers (bin/test-cljs, dev hook) fail any run that accumulated one
;; (the SHIPPED default); `:log` = datom + derived section only
;; (prod/demo). `:agent` faults never escalate in ANY mode.
(schema/register! :seon.config/on-core-error [:enum :crash :gate :log])

;;; WEB-ACCESS POLICY — the host-owned reachability policy for
;;; `seon.agent.web/fetch` (UNIFIES the old private-range SSRF guard + domain
;;; allowlist into ONE config). The AUTHORITATIVE `:seon.agent.web/policy` enum
;;; (:open/:public-only/:allowlist) lives in `seon.agent.web` (the owning ns).
;;; Here the mode is validated as a LEAF `:keyword` — the LEAF rule: `seon.config`
;;; loads BEFORE `seon.agent.web`, and `schema/register!` asserts compilability
;;; EAGERLY, so a forward keyword-ref would break boot. The enum-level check
;;; happens downstream in [[web-policy]] (coerces an unrecognized mode to the
;;; SSRF-safe `:public-only` — fail-closed, never open by accident).
;;; `allowed-domains` matters only under `:allowlist`. Absent section ⇒ the
;;; accessor's `:public-only` fallback. The master on/off grant (SEON_WEB) stays
;;; ENV — it gates whether web is available at all; this policy shapes
;;; reachability once it is (env gate + config policy, two concerns).
;;; The same section also carries the WEB-SEARCH backend (`seon.agent.web/search`):
;;; `:seon.agent.web/search-backend` (which grounded/SERP provider — leaf
;;; `:keyword`, enum-checked downstream in [[web-search-config]], default
;;; `:gemini-grounding`) + `:seon.agent.web/search-model` (the model id for the
;;; grounded backend, default `"gemini-3.1-flash-lite"`). Backend choice is
;;; host-owned CONFIG (never env — env carries only the API KEY, read live);
;;; a new backend (Serper) slots in here WITHOUT changing the function shape.
(schema/register! :seon.config/web-spec
  [:map
   [:seon.agent.web/policy          {:optional true} :keyword]
   [:seon.agent.web/allowed-domains {:optional true} [:vector :string]]
   [:seon.agent.web/search-backend  {:optional true} :keyword]
   [:seon.agent.web/search-model    {:optional true} :string]])

;;; FORM-AUTOFIX (repair) — the pre-flight repair dial (owner rulings
;;; 2026-07-05; design docs/prds/agent-ctx/research/form-autofix-system-
;;; 2026-07-05.md). Levels as config data; per-class kill switches are a
;;; `{class-kw boolean}` map COMBINED with the class registry in
;;; `seon.repl.parse.repair/class-levels` (enablement is computed, never a call-site
;;; list). `:aggressive` is an enum slot only — not implemented. `classes`
;;; stays a plain map HERE (the manifest shape); resolution projects its three
;;; closed entries onto native optional boolean attributes on the singleton.
(schema/register! :seon.config/repair-classes-spec
  (into [:map {:closed true}]
        (map (fn [class] [class {:optional true} :boolean]))
        (keys repair-class-attributes)))

(schema/register! :seon.config/repair
  [:map {:closed true}
   [:seon.config.repair/level              {:optional true} :seon.config.repair/level]
   [:seon.config.repair/classes            {:optional true} :seon.config/repair-classes-spec]
   [:seon.config.repair/max-fixes-per-form {:optional true} :seon.config.repair/max-fixes-per-form]
   [:seon.config.repair/budget-ms          {:optional true} :seon.config.repair/budget-ms]])

;;; MULTI-AGENT dials (multiagent-context-spec). All three are core config
;;; numbers, plumbed the same way as `:seon.config/on-core-error` (a plain
;;; literal in the manifest, absent ⇒ the accessor's default): the spawn
;;; DEPTH cap (Piece 2 — how deep the spawn tree may go), the heartbeat
;;; WATCHDOG staleness threshold (Piece 2c), and the schedule-wake circuit
;;; BREAKER's N + window (Piece 2d). Absent ⇒ byte-identical to the defaults.
(schema/register! :seon.config/spawn-depth-cap [:int {:min 0}])
(schema/register! :seon.config/watchdog
  [:map [:seon.config.watchdog/stale-ms {:optional true} :seon.config.watchdog/stale-ms]])
(schema/register! :seon.config/schedule-breaker
  [:map
   [:seon.config.breaker/crash-count {:optional true} :seon.config.breaker/crash-count]
   [:seon.config.breaker/window-ms   {:optional true} :seon.config.breaker/window-ms]])
(schema/register! :seon.config/root
  [:map
   [:seon.config.root/recent-limit
    {:optional true} :seon.config.root/recent-limit]])
(schema/register! :seon.config/reactive
  [:map {:closed true}
   [:seon.config/reactive-settle-ms
    {:optional true} :seon.config/reactive-settle-ms]
   [:seon.config/reactive-structural-settle-ms
    {:optional true} :seon.config/reactive-structural-settle-ms]
   [:seon.config/reactive-max-latency-ms
    {:optional true} :seon.config/reactive-max-latency-ms]
   [:seon.config.database.query/max-work
    {:optional true} :seon.config.database.query/max-work]
   [:seon.config.database.query/max-results
    {:optional true} :seon.config.database.query/max-results]
   [:seon.config.database.query/max-result-weight
    {:optional true} :seon.config.database.query/max-result-weight]
   [:seon.config.database.pull/max-work
    {:optional true} :seon.config.database.pull/max-work]
   [:seon.config.database.pull/max-results
    {:optional true} :seon.config.database.pull/max-results]
   [:seon.config.database.pull/max-result-weight
    {:optional true} :seon.config.database.pull/max-result-weight]])

;;; REPL MODE (repl-mode Phase 1) — how the agent's REPL turn resolves a
;;; form's result. The DEFAULT is per-MODEL ([[default-repl-mode]]); an
;;; explicit manifest value always wins. `:batch`: the turn is one LLM call writing N
;;; forms; a model-typed result is STRIPPED at the reply boundary and the
;;; real values arrive interleaved next turn. `:stream`: the SDK stream is
;;; consumed delta-by-delta and ABORTED the moment one complete top-level
;;; form has streamed — one form per turn, its real value in the next
;;; transcript. The manifest value is read ONCE at boot and reconciled into
;;; a DB datom on the singleton cluster-config entity; the turn loop + the
;;; transcript masthead read the DATOM (config-through-DB), never this key.
(schema/register! :seon.config/repl-mode [:enum :batch :stream])

;;; RUN RESOURCE POLICY — generous safety ceilings, not normal stop reasons.
;;; The manifest section resolves into three scalar singleton datoms so the
;;; frozen database value completely determines what a new run will seed.
(schema/register! :seon.config.run/batch-turn-limit  [:int {:default 100 :min 1}])
(schema/register! :seon.config.run/stream-form-limit [:int {:default 300 :min 1}])
(schema/register! :seon.config.run/deadline-ms       [:int {:default 1800000 :min 1}])
(schema/register! :seon.config/run
  [:map
   [:seon.config.run/batch-turn-limit  {:optional true} :seon.config.run/batch-turn-limit]
   [:seon.config.run/stream-form-limit {:optional true} :seon.config.run/stream-form-limit]
   [:seon.config.run/deadline-ms       {:optional true} :seon.config.run/deadline-ms]])

;;; ============================================================
;;; THE `:seon.config` SINGLETON — one cluster-config entity, ATTRIBUTE-PER-KEY
;;; (config-db-migration-spec 2026-07-10). The owner contract: config is read
;;; at BOOT and TRANSACTED into the db (this singleton); from then on EVERY
;;; runtime read begins with one ordinary singleton row acquired by the owning
;;; database operation. Each knob is its OWN registered attr — a real type is the knob's
;;; contract, NEVER an EDN-blob dump of the whole config. The homogeneous
;;; `:seon.config/always` and web allowlist attrs use native cardinality-many
;;; storage; exact config reconciliation retracts removed values before asserting
;;; the desired set. The
;;; singleton is ONE entity in the boot `#{:config}` `seon.runtime.state/reconcile!`
;;; desired set (routes/skills pattern) — upsert-by-identity keeps it current +
;;; retract-protected; NO second mechanism.
;;; ============================================================

;; The fixed singleton identity value — the one cluster-config entity per store.
(def cluster-config-id "cluster")

(schema/register! :seon.config/id [:and {:seon.db/identity true} :string])

;;; The scalar/enum per-knob attrs are registered ONCE with their manifest
;;; section specs above (the LEAF-attr block before `:seon.config/render`) —
;;; the singleton entity schema below references those registrations. Only
;;; the knobs whose DATOM shape differs from their manifest shape live here.
;;; Homogeneous collections use native cardinality-many values. Entity-shaped
;;; configuration uses explicit component refs.
;; The always-on FULL-source ns render list — the resolved symbol set
;; (`:seon.config/namespaces` `:always`). Native cardinality-many symbols.
(schema/register! :seon.config/always
  [:set
   {:min 1
    :error/message
    "The always-source policy must contain symbols; omit its manifest declaration to use the shipped policy."}
   :symbol])
;; The web allowlist hosts (meaningful only under `:allowlist`). This established
;; cardinality-many string attribute is already installed in durable databases.
(schema/register! :seon.agent.web/allowed-domains [:vector :string])
;; The cluster system-prompt TEXT — OPTIONAL, no default (absent ⇒ not seeded
;; ⇒ `seon.ai/effective-system-prompt` falls through to the shipped
;; `seon.agent.ctx/system-text`, preserving the pre-datom behavior). The
;; read side is request override → THIS ordinary acquired datom →
;; the shipped default. The value is the literal prompt string (a manifest
;; keeps it inline; `config/minimal.edn` is the worked example).
(schema/register! :seon.config/system-text :string)
;; Named context RENDER PROFILES — `{profile-kw → [block-patch …]}`, each
;; patch a `:seon.agent.ctx/profile` entry (block name + per-block config
;; overrides) that the compiled prompt child renders as a curated subset of
;; the agent's blocks. Config-through-DB so an as-of render
;; regenerates under the profile IN FORCE at that t (the byte-exact
;; contract — `seon.repl.autocomplete` reads its `:autocomplete` profile
;; off the passed db value, code default when absent). OPTIONAL, no
;; default (absent ⇒ not seeded ⇒ consumers use their code defaults).
;; EDN-slot bridged (mixed `:or`, the ::home-requires pattern).
(schema/register! :seon.config/context-profiles
  [:or [:map-of :keyword [:vector :map]] :nil])
;; Named launch-time model configurations. Each child is identified by its
;; variant keyword and owned by the singleton through this component ref. The
;; one registered value shape admits transaction refs and acquired child maps;
;; the explicit storage facet keeps both database bridges on :db.type/ref.
(schema/register! :seon.config/model-variants
  [:vector
   {:seon.db/component true}
   [:or
    {:seon.db/value-type :db.type/ref}
    :seon.db/ref
    :seon.config/model-variant-entity]])

;; The singleton entity schema — every knob optional (a `{}` manifest seeds the
;; resolved defaults; `:seon.config/id` is the only required key).
(schema/register! :seon.config/singleton
  (into [:map {:seon.db/entity true}
   [:seon.config/id                          :seon.config/id]
   [:seon.config/skills-dir         {:optional true} :seon.config/skills-dir]
   [:seon.config/repl-mode          {:optional true} :seon.config/repl-mode]
   [:seon.config.run/batch-turn-limit  {:optional true} :seon.config.run/batch-turn-limit]
   [:seon.config.run/stream-form-limit {:optional true} :seon.config.run/stream-form-limit]
   [:seon.config.run/deadline-ms       {:optional true} :seon.config.run/deadline-ms]
   [:seon.config.execution/host-tier?
    {:optional true} :seon.config.execution/host-tier?]
   [:seon.config.execution/host-respawn-backoff-ms
    {:optional true} :seon.config.execution/host-respawn-backoff-ms]
   [:seon.config.model-transport/response-identity-cap
    {:optional true} :seon.config.model-transport/response-identity-cap]
   [:seon.config.model-transport/endpoint-cap
    {:optional true} :seon.config.model-transport/endpoint-cap]
   [:seon.config/on-core-error      {:optional true} :seon.config/on-core-error]
   [:seon.config/spawn-depth-cap    {:optional true} :seon.config/spawn-depth-cap]
   [:seon.config/always             {:optional true} :seon.config/always]
   [:seon.config/system-text        {:optional true} :seon.config/system-text]
   [:seon.config/context-profiles   {:optional true} :seon.config/context-profiles]
   [:seon.config/model-variants     {:optional true} :seon.config/model-variants]
   [:seon.config/agent-context      {:optional true} :seon.config/agent-context]
   [:seon.config/root-context       {:optional true} :seon.config/root-context]
   [:seon.config.render/database-edn-cap   {:optional true} :seon.config/cap]
   [:seon.config.render/eval-cap           {:optional true} :seon.config/cap]
   [:seon.config.render/message-cap        {:optional true} :seon.config/cap]
   [:seon.config.render/result-body-cap    {:optional true} :seon.config/cap]
   [:seon.config.render/value-max-depth    {:optional true} :seon.config/cap]
   [:seon.config.render/value-max-keys     {:optional true} :seon.config/cap]
   [:seon.config.render/value-max-items    {:optional true} :seon.config/cap]
   [:seon.config.render/value-max-path-segments
    {:optional true} :seon.config.render/value-max-path-segments]
   [:seon.config.render/value-max-path-bytes
    {:optional true} :seon.config.render/value-max-path-bytes]
   [:seon.config.render/value-max-realized-items
    {:optional true} :seon.config.render/value-max-realized-items]
   [:seon.config.render/value-max-string   {:optional true} :seon.config/cap]
   [:seon.config.render/value-shape-sample {:optional true} :seon.config/cap]
   [:seon.config.render/value-verbatim-cap {:optional true} :seon.config/cap]
   [:seon.config.render/value-width        {:optional true} :seon.config/cap]
   [:seon.config.render/render-fn-token-cap {:optional true} :seon.config/cap]
   [:seon.config.render/whitespace     {:optional true} :seon.config.render/whitespace]
   [:seon.config.render/tabs           {:optional true} :seon.config.render/tabs]
   [:seon.config.render/trailing-ws    {:optional true} :seon.config.render/trailing-ws]
   [:seon.config.render/content-layout {:optional true} :seon.config.render/content-layout]
   [:seon.config.render/line-numbers   {:optional true} :seon.config.render/line-numbers]
   [:seon.config.repair/level              {:optional true} :seon.config.repair/level]
   [:seon.config.repair/max-fixes-per-form {:optional true} :seon.config/cap]
   [:seon.config.repair/budget-ms          {:optional true} :seon.config/cap]
   [:seon.config.repair.class/delimiters?
    {:optional true} :seon.config.repair.class/delimiters?]
   [:seon.config.repair.class/def-vs-defn?
    {:optional true} :seon.config.repair.class/def-vs-defn?]
   [:seon.config.repair.class/undeclared-var?
    {:optional true} :seon.config.repair.class/undeclared-var?]
   ;; LEAF types for the web knobs — `seon.agent.web` (which registers the
   ;; authoritative `:seon.agent.web/policy`/`search-backend`/`search-model`
   ;; enums) loads AFTER this leaf ns, so a schema-keyword ref here would break
   ;; boot. The DATOM's storage type comes from web's own registration (present
   ;; by boot-seed time); this entity schema only validates loosely.
   [:seon.agent.web/policy          {:optional true} :keyword]
   [:seon.agent.web/search-backend  {:optional true} :keyword]
   [:seon.agent.web/search-model    {:optional true} :string]
   [:seon.agent.web/allowed-domains {:optional true} :seon.agent.web/allowed-domains]
   [:seon.config.watchdog/stale-ms   {:optional true} :seon.config/cap]
   [:seon.config.breaker/crash-count {:optional true} :seon.config/cap]
   [:seon.config.breaker/window-ms   {:optional true} :seon.config/cap]
   [:seon.config.root/recent-limit   {:optional true} :seon.config/cap]
   [:seon.config/reactive-settle-ms
    {:optional true} :seon.config/reactive-settle-ms]
   [:seon.config/reactive-structural-settle-ms
    {:optional true} :seon.config/reactive-structural-settle-ms]
   [:seon.config/reactive-max-latency-ms
    {:optional true} :seon.config/reactive-max-latency-ms]
   ]
  operational-manifest-entries))

(schema/register! :seon.config/manifest
  [:map
   [:seon.config/skills-dir    {:optional true} :seon.config/skills-dir]
   [:seon.config/repl-mode     {:optional true} :seon.config/repl-mode]
   [:seon.config/run           {:optional true} :seon.config/run]
   [:seon.config/execution     {:optional true} :seon.config/execution]
   [:seon.config/model-transport {:optional true} :seon.config/model-transport]
   [:seon.config/namespaces    {:optional true} :seon.config/namespaces-spec]
   [:seon.config/routes        {:optional true} [:vector :seon.config/route-spec]]
   [:seon.config/render        {:optional true} :seon.config/render]
   [:seon.config/system-text   {:optional true} :seon.config/system-text]
   [:seon.config/context-profiles {:optional true} :seon.config/context-profiles]
   [:seon.config/ai             {:optional true} :seon.config/ai]
   [:seon.config/model-variants {:optional true} :seon.config/model-variants-spec]
   [:seon.config/on-core-error {:optional true} :seon.config/on-core-error]
   [:seon.config/web           {:optional true} :seon.config/web-spec]
   [:seon.config/repair        {:optional true} :seon.config/repair]
   [:seon.config/spawn-depth-cap   {:optional true} :seon.config/spawn-depth-cap]
   [:seon.config/watchdog          {:optional true} :seon.config/watchdog]
   [:seon.config/schedule-breaker  {:optional true} :seon.config/schedule-breaker]
   [:seon.config/root              {:optional true} :seon.config/root]
   [:seon.config/reactive          {:optional true} :seon.config/reactive]
   [:seon.config/database          {:optional true} :seon.config/database]
   [:seon.config/agent-context {:optional true} :seon.config/agent-context]
   [:seon.config/root-context  {:optional true} :seon.config/root-context]])

;;; Function arg/return shapes — leaf `[:vector :map]` (full shapes validated
;;; downstream); registered once + referenced so the resolver specs don't
;;; re-inline the shape.
(schema/register! ::agent-id :string)
(schema/register! ::routes [:vector :map])

;;; ── `#merge` COMPOSITION — the manifest-aware combine (config-merge trap,
;;; 2026-07-11) ──
;;; A per-cluster manifest (`config/acme.edn`, `config/minimal*.edn`) composes as
;;; `#merge [#include "system.edn" {overrides}]`. Aero's SHIPPED `#merge` reader
;;; is `(apply merge values)` — a SHALLOW map merge, so a top-level key in the
;;; override REPLACES the base's value WHOLESALE. That is correct for a scalar
;;; knob but WRONG for the nested `:seon.config/agent-context`: a sparse override
;;; that sets only `:seon.eval/home-requires` silently DROPPED the base's
;;; `:seon.agent/ctx` block tree, and the schema `:default` then quietly filled
;;; the LEGACY tree — acme ran the wrong context for a day (the 1bd1d21d cutover
;;; regression). We OVERRIDE aero's `'merge` reader (its `reader` multimethod is
;;; the designed extension point) with a manifest-aware combine that applies —
;;; for the `:seon.config/agent-context` key ONLY — the SAME replaces-wholesale
;;; rule [[resolve-agent-context]] already documents: an override that declares
;;; `:seon.agent/ctx` replaces the tree wholesale; a SPARSE override (no
;;; `:seon.agent/ctx`) is a PATCH whose keys win while every unstated key
;;; (`:seon.agent/ctx` included) inherits from the base. Every OTHER top-level
;;; key stays shallow-replace, byte-identical to aero's default — so the
;;; wholesale-replacing minimal.edn family (which DECLARES `:seon.agent/ctx`, and
;;; deliberately drops the base home-requires to fall back to the consumer
;;; default) is untouched. ONE merge rule, applied at the ONE composition seam.

(defn- merge-home-requires
  "Overlay additional home requires on the base vector by namespace identity.

   A repeated namespace replaces its base require spec in place; a new
   namespace appends. This keeps specialization additive while allowing a
   deliberate alias/refer refinement without emitting duplicate requires."
  [base additions]
  (reduce
    (fn [requires spec]
      (let [target (first spec)]
        (if-let [index (first (keep-indexed
                               (fn [i current]
                                 (when (= target (first current)) i))
                               requires))]
          (assoc requires index spec)
          (conj requires spec))))
    (vec (or base []))
    (or additions [])))

(defn- combine-agent-context
  "Combine a base `:seon.config/agent-context` map with an `override` map.

   An `override` that declares `:seon.agent/ctx` REPLACES the whole map (the
   documented replaces-wholesale contract the minimal.edn family relies on to
   also drop the base `:seon.eval/home-requires`). A SPARSE `override` (no
   `:seon.agent/ctx`) is a PATCH: scalar keys win, home requirements merge by
   namespace identity, and every unstated key — the block tree included —
   inherits from `base`. Matches [[resolve-agent-context]]'s
   `explicit-ctx?` rule."
  [base override]
  (if (contains? override :seon.agent/ctx)
    override
    (cond-> (merge base override)
      (contains? override :seon.eval/home-requires)
      (assoc :seon.eval/home-requires
             (merge-home-requires
               (:seon.eval/home-requires base)
               (:seon.eval/home-requires override))))))

(defn- merge-manifest-pair
  "Shallow-merge `override` over `base`, then re-combine the nested
   `:seon.config/agent-context` maps via [[combine-agent-context]] so a sparse
   override can never silently drop the base's block tree. Only that ONE key is
   special-cased; every other top-level key keeps aero's shallow replace."
  [base override]
  (let [m (merge base override)]
    (cond-> m
      (and (map? (:seon.config/agent-context base))
           (map? (:seon.config/agent-context override)))
      (assoc :seon.config/agent-context
             (combine-agent-context (:seon.config/agent-context base)
                                    (:seon.config/agent-context override))))))

;; OVERRIDE aero's built-in `#merge` (shipped `(apply merge values)`) with the
;; manifest-aware fold. Registered at ns load — `seon.config` is the pod's ONLY
;; aero user, so the blast radius is exactly the config-manifest composition this
;; fixes. `values` is the vector of maps after the `#merge` tag.
#?(:bb
   (do
     (defmethod aero/reader 'merge
       [_opts _tag values]
       (reduce merge-manifest-pair {} values))

     (defmethod aero/reader 'env
       [{::keys [environment]} _tag value]
       (get environment (str value)))

     (defmethod aero/reader 'envf
       [{::keys [environment]} _tag [format-string & values]]
       (apply format format-string
              (map #(str (get environment (str %))) values)))

     (defn read-manifest
       "Read one Aero manifest using only the supplied environment data."
       [path environment]
       (aero/read-config path {::environment environment})))
   :cljs
   (do
     (defmethod aero/reader 'merge
       [_opts _tag values]
       (reduce merge-manifest-pair {} values))

     (defmethod aero/reader 'env
       [{::keys [environment]} _tag value]
       (get environment (str value)))

     (defmethod aero/reader 'envf
       [{::keys [environment]} _tag [format-string & values]]
       (apply gstring/format format-string
              (map #(str (get environment (str %))) values)))

     (defn read-manifest
       "Read one Aero manifest using only the supplied environment data."
       [path environment]
       (aero/read-config path {::environment environment})))
   :clj
   (defn read-manifest
     "Aero manifest IO belongs to the Babashka operator and CLJS pod."
     [_path _environment]
     (throw (ex-info "Aero manifest IO is unavailable in the writer runtime." {}))))

(def ^:private default-namespaces-policy
  "The shipped namespace full-source storage policy.

   Complete source for these framework exemplars is indexed so a per-agent
   namespaces block may select it. `my.*` source follows its structural storage
   rule independently. This policy never decides what renders."
  {:seon.config/always '[my.kb my.data my.ui my.canvas
                          my.plan seon.agent.message seon.agent.lifecycle]})

(defn resolve-namespaces
  "Resolve the `:seon.config/namespaces` section into render policy.

   The `manifest` section becomes the policy
   the renderer + boot indexer read (`seon.agent.ctx.namespaces`). KEY-LEVEL
   merge over [[default-namespaces-policy]]. An absent section uses the shipped
   storage set; a present section overrides the one list. Symbols become the
   current ns-name database values."
  {:malli/schema [:=> [:catn [:seon.config.resolve/manifest :seon.config/manifest]]
                  :seon.config/namespaces-policy]}
  [manifest]
  (let [merged (merge default-namespaces-policy (:seon.config/namespaces manifest))]
    {:seon.config/always
     (into #{} (:seon.config/always merged))}))

;;; ============================================================
;;; THE CONFIG RESOLVER. `resolve-config-singleton` maps a manifest
;;; to the FLAT `:seon.config` singleton entity map — every SCALAR knob resolved
;;; to its EFFECTIVE value (env→manifest→default, coerced) + the three decoded
;;; collections. It is the ONE pre-session resolution point used to seed a
;;; fresh database. After the database session opens, the owning operation acquires and decodes
;;; the database singleton once, then passes that ordinary map to the pure
;;; accessors below. There is no ambient reader or fallback.
;;; ============================================================

(def ^:private default-transformer
  "The one recursive schema-default resolver for config sections. Optional
   keys carrying Malli defaults are materialized before singleton flattening."
  (mt/default-value-transformer {:malli.transform/add-optional-keys true}))

(defn default-run-policy
  "The run section with every schema default materialized.

   This is the sole no-manifest fallback used before a config singleton exists;
   normal runtime reads the resolved scalar datoms from the database."
  {:malli/schema [:=> [:cat] :seon.config/run]}
  []
  (m/decode :seon.config/run {} default-transformer))

(defn- coerce-enum
  "`v` when it is in `allowed`, else `fallback` — the belt for a stale/invalid
   value (the manifest validator already rejects a bad literal loudly at load)."
  [v allowed fallback]
  (if (contains? allowed v) v fallback))

(defn- default-repl-mode
  "The per-model REPL default from explicit environment data."
  [environment]
  (let [provider (or (get environment "SEON_AI_PROVIDER") "deepseek")
        model (or (get environment "SEON_AI_MODEL") "")]
    (if (or (= "deepseek" provider) (re-find #"deepseek" model))
      :stream
      :batch)))

(def default-reactive-policy
  "Reactive timing used when an older persisted configuration has no policy."
  {:seon.config/reactive-settle-ms 16
   :seon.config/reactive-structural-settle-ms 300
   :seon.config/reactive-max-latency-ms 500})

(def default-database-query-policy
  "Generous query ceilings used only to stop runaway database work."
  {:seon.config.database.query/max-work 100000000
   :seon.config.database.query/max-results 1000000
   :seon.config.database.query/max-result-weight 3000000})

(def default-database-pull-policy
  "Generous pull ceilings used only to stop runaway database work."
  {:seon.config.database.pull/max-work 25000000
   :seon.config.database.pull/max-results 1000000
   :seon.config.database.pull/max-result-weight 3000000})

(def configuration-read-profile
  "Datahike ceilings for acquiring the complete config singleton.

   W1 relocates this named policy into aero-backed database facts. The current
   values cover the largest retained singleton tree used by prompt, agent-view,
   and execution-child acquisition."
  {:datahike.resource/max-work 100000
   :datahike.resource/max-results 4096
   :datahike.resource/max-result-weight (* 1024 1024)})

(defn- reject-floor!
  [values key floor reason]
  (let [value (get values key)]
    (when (< value floor)
      (throw
       (ex-info
        (str "Configured " key " is below its operational floor.")
        {key value
         :seon.config/floor floor
         :seon.config/reason reason
         :seon.config/steering
         (str "Set " key " to at least " floor ". " reason)})))))

(defn- reject-ordering!
  [values smaller-key larger-key reason]
  (let [smaller (get values smaller-key)
        larger (get values larger-key)]
    (when (> smaller larger)
      (throw
       (ex-info
        (str "Configured " smaller-key " exceeds " larger-key ".")
        {smaller-key smaller
         larger-key larger
         :seon.config/reason reason
         :seon.config/steering
         (str "Set " smaller-key " to no more than " larger-key ". " reason)})))))

(defn- positive-environment-int
  [environment key fallback]
  (let [raw (get environment key)
        parsed (when raw
                 (try
                   #?(:cljs (js/parseInt raw 10)
                      :bb (Long/parseLong raw)
                      :clj (Long/parseLong raw))
                   (catch #?(:cljs :default :default Throwable) _ nil)))]
    (if (and (int? parsed) (pos? parsed)) parsed fallback)))

(defn- declared-attempt-timeouts
  [manifest]
  (keep (fn [configuration]
          (let [value (:seon.ai/agent-attempt-timeout-ms configuration)]
            (when (pos-int? value) value)))
        (concat
         (vals (get manifest :seon.config/model-variants {}))
         [(get manifest :seon.config/agent-context {})
          (get manifest :seon.config/root-context {})])))

(defn- validate-liveness-relations!
  [manifest environment run]
  (let [attempt-horizon
        (reduce max
                (positive-environment-int
                 environment "SEON_LLM_ATTEMPT_TIMEOUT_MS" 120000)
                (declared-attempt-timeouts manifest))
        run-deadline (:seon.config.run/deadline-ms run)
        turn-horizon
        (positive-environment-int environment "SEON_TURN_TIMEOUT_MS" 900000)
        watchdog-stale-ms
        (get-in manifest
                [:seon.config/watchdog :seon.config.watchdog/stale-ms]
                1200000)]
    (when (< run-deadline attempt-horizon)
      (throw
       (ex-info
        "The run deadline is shorter than one resolved LLM attempt."
        {:seon.config.run/deadline-ms run-deadline
         :seon.ai/agent-attempt-timeout-ms attempt-horizon
         :seon.config/floor attempt-horizon
         :seon.config/reason
         "A run deadline shorter than one attempt permits zero turns to complete."
         :seon.config/steering
         (str "Set :seon.config.run/deadline-ms to at least "
              attempt-horizon ".")})))
    (when (<= watchdog-stale-ms turn-horizon)
      (throw
       (ex-info
        "The watchdog staleness threshold does not exceed one turn horizon."
        {:seon.config.watchdog/stale-ms watchdog-stale-ms
         :seon.config/turn-timeout-ms turn-horizon
         :seon.config/floor (inc turn-horizon)
         :seon.config/reason
         "The watchdog must not close a run while its bounded turn can still complete."
         :seon.config/steering
         (str "Set :seon.config.watchdog/stale-ms to at least "
              (inc turn-horizon) ".")})))))

(defn resolve-operational-values
  "Resolve every boot-critical limit from manifest and hardware data."
  [manifest hardware]
  (let [database (get manifest :seon.config/database {})
        observed-processors (max 1 (:seon.hardware/cores hardware))
        selected-processors
        (get database :seon.config.database.executor/selected-processors
             observed-processors)
        _ (when-not (pos-int? selected-processors)
            (throw
             (ex-info
              "Selected processors must be a positive integer."
              {:seon.config.database.executor/selected-processors
               selected-processors
               :seon.hardware/cores observed-processors
               :seon.config/steering
               "Set :seon.config.database.executor/selected-processors to a positive integer."})))
        processors (min observed-processors selected-processors)
        cpu-workers (max 1 (dec processors))
        knn (max 1 (min 2 (quot cpu-workers 2)))
        mutation (max 1 (min 4 (quot (inc processors) 2)))
        provider (min 6 processors)
        read-queue (max 16 (* 8 cpu-workers))
        read-database-queue (min read-queue (max 16 (* 4 cpu-workers)))
        mutation-queue (max 64 (* 16 mutation))
        system-mb (quot (:seon.hardware/system-memory-bytes hardware) (* 1024 1024))
        heap-mb (get database :seon.config.database.writer/jvm-heap-mb
                     (-> (quot system-mb 16) (max 512) (min 4096)))
        heap-bytes (* heap-mb 1024 1024)
        maximum-connections
        (get database :seon.config.database.transport/maximum-connections
             (-> (* 16 cpu-workers) (max 64)
                 (min 1024 (quot (:seon.hardware/fd-soft-limit hardware) 4))))
        maximum-frame-bytes
        (get database :seon.config.database.transport/maximum-frame-bytes
             protocol/maximum-frame-bytes)
        read-active
        (get database :seon.config.database.executor.read/maximum-active
             cpu-workers)
        read-maximum-queued
        (get database :seon.config.database.executor.read/maximum-queued
             read-queue)
        read-queue-waves
        (max 1 (quot (+ read-maximum-queued (dec read-active)) read-active))]
    (when (> maximum-frame-bytes protocol/maximum-frame-bytes)
      (throw (ex-info "Configured frame bytes exceed the protocol ceiling."
                      {:seon.config.database.transport/maximum-frame-bytes maximum-frame-bytes
                       :seon.db.protocol/maximum-frame-bytes protocol/maximum-frame-bytes})))
    (when (< maximum-frame-bytes 65536)
      (throw (ex-info "Configured frame bytes are below the proven boot floor."
                      {:seon.config.database.transport/maximum-frame-bytes maximum-frame-bytes
                       :seon.config/floor 65536
                       :seon.config/reason
                       "The 4096-byte session-open exchange is distinct; committed boot pages require the proven 65536-byte end-to-end floor."
                       :seon.config/steering
                       "Set :seon.config.database.transport/maximum-frame-bytes to at least 65536."})))
    (let [resolved
          {:seon.config.database.writer/jvm-heap-mb heap-mb
     :seon.config.database.read/max-work
     (get database :seon.config.database.read/max-work 100000000)
     :seon.config.database.read/max-results
     (get database :seon.config.database.read/max-results 1000000)
     :seon.config.database.read/max-result-weight
     (get database :seon.config.database.read/max-result-weight 3000000)
     :seon.config.database.read/deadline-ms
     (get database :seon.config.database.read/deadline-ms
          (* 30000 read-queue-waves))
     :seon.config.database.executor/selected-processors processors
     :seon.config.database.executor/maximum-queued-request-bytes
     (get database :seon.config.database.executor/maximum-queued-request-bytes
          (-> (quot heap-bytes 16) (max (* 8 1024 1024))
              (min (* 64 1024 1024))))
     :seon.config.database.executor.read/maximum-active
     read-active
     :seon.config.database.executor.read/maximum-queued
     read-maximum-queued
     :seon.config.database.executor.read/maximum-queued-by-database
     (get database :seon.config.database.executor.read/maximum-queued-by-database
          read-database-queue)
     :seon.config.database.executor.knn/maximum-active
     (get database :seon.config.database.executor.knn/maximum-active knn)
     :seon.config.database.executor.knn/maximum-queued
     (get database :seon.config.database.executor.knn/maximum-queued
          (max 4 (* 2 knn)))
     :seon.config.database.executor.knn/maximum-queued-by-database
     (get database :seon.config.database.executor.knn/maximum-queued-by-database 2)
     :seon.config.database.executor.provider/maximum-active
     (get database :seon.config.database.executor.provider/maximum-active provider)
     :seon.config.database.executor.provider/maximum-queued
     (get database :seon.config.database.executor.provider/maximum-queued
          (* 2 provider))
     :seon.config.database.executor.provider/maximum-queued-by-database
     (get database :seon.config.database.executor.provider/maximum-queued-by-database 2)
     :seon.config.database.executor.mutation/maximum-active
     (get database :seon.config.database.executor.mutation/maximum-active mutation)
     :seon.config.database.executor.mutation/maximum-queued
     (get database :seon.config.database.executor.mutation/maximum-queued mutation-queue)
     :seon.config.database.executor.mutation/maximum-queued-by-database
     (get database :seon.config.database.executor.mutation/maximum-queued-by-database
          mutation-queue)
     :seon.config.database.executor.delivery/maximum-active
     (get database :seon.config.database.executor.delivery/maximum-active cpu-workers)
     :seon.config.database.executor.delivery/maximum-queued
     (get database :seon.config.database.executor.delivery/maximum-queued
          (max 16 (* 4 cpu-workers)))
     :seon.config.database.executor.delivery/maximum-queued-by-database
     (get database :seon.config.database.executor.delivery/maximum-queued-by-database 1)
     :seon.config.database.executor.hnsw/maximum-active
     (get database :seon.config.database.executor.hnsw/maximum-active 1)
     :seon.config.database.executor.hnsw/maximum-queued
     (get database :seon.config.database.executor.hnsw/maximum-queued 1)
     :seon.config.database.executor.hnsw/maximum-queued-by-database
     (get database :seon.config.database.executor.hnsw/maximum-queued-by-database 1)
     :seon.config.database.transport/maximum-frame-bytes maximum-frame-bytes
     :seon.config.database.transport/maximum-connections maximum-connections
     :seon.config.database.transport/maximum-input-bytes
     (get database :seon.config.database.transport/maximum-input-bytes
          (min (* 32 1024 1024) (quot heap-bytes 16)))
     :seon.config.database.transport/maximum-response-slots
     (get database :seon.config.database.transport/maximum-response-slots
          maximum-connections)
     :seon.config.database.transport/maximum-session-response-slots
     (get database :seon.config.database.transport/maximum-session-response-slots
          (max 1 (quot maximum-connections 4)))
     :seon.config.database.transport/maximum-output-bytes
     (get database :seon.config.database.transport/maximum-output-bytes
          (min (* 256 1024 1024) (quot heap-bytes 2)))
     :seon.config.database.transport/maximum-session-output-bytes
     (get database :seon.config.database.transport/maximum-session-output-bytes
          (min (* 128 1024 1024) (quot heap-bytes 4)))
     :seon.config.database.transport/shutdown-timeout-ms
     (get database :seon.config.database.transport/shutdown-timeout-ms 5000)
     :seon.config.database.transport/codec-workers
     (get database :seon.config.database.transport/codec-workers
          (max 2 (min 8 processors)))
     :seon.config.database.transport/codec-worker-queue-capacity
     (get database :seon.config.database.transport/codec-worker-queue-capacity 256)}]
      (reject-floor!
       resolved :seon.config.database.writer/jvm-heap-mb 2
       "The pinned JVM refuses a one-megabyte maximum heap before the writer can start.")
      (reject-floor!
       resolved :seon.config.database.transport/maximum-connections 2
       "Normal operation requires one retained pod connection and one host connection.")
      (reject-floor!
       resolved :seon.config.database.read/max-result-weight 60000
       "Committed-program admission requests a 60000 result-weight page budget.")
      (reject-floor!
       resolved :seon.config.database.executor/maximum-queued-request-bytes
       (+ 4 maximum-frame-bytes)
       "The executor must admit one maximum frame plus its four-byte header.")
      (reject-floor!
       resolved :seon.config.database.transport/maximum-input-bytes
       (+ 4 maximum-frame-bytes)
       "A session otherwise pauses permanently before one maximum frame arrives.")
      (reject-floor!
       resolved :seon.config.database.transport/maximum-output-bytes
       maximum-frame-bytes
       "The authority must be able to reserve one maximum response frame.")
      (reject-floor!
       resolved :seon.config.database.transport/maximum-session-output-bytes
       maximum-frame-bytes
       "Each session must be able to reserve one maximum response frame.")
      (reject-ordering!
       resolved
       :seon.config.database.transport/maximum-session-response-slots
       :seon.config.database.transport/maximum-response-slots
       "A session cannot reserve more response slots than the authority owns.")
      (reject-ordering!
       resolved
       :seon.config.database.transport/maximum-session-output-bytes
       :seon.config.database.transport/maximum-output-bytes
       "A session cannot reserve more output bytes than the authority owns.")
      resolved)))

(defn resolve-config-singleton
  "The FLAT `:seon.config` singleton entity map for `manifest`.

   Every knob RESOLVED to its effective value (the default reproduces today's
   byte-parity behavior). The one explicit pre-session resolution point seeds
   the database. `:seon.config/system-text` is
   OPTIONAL (no default): included ONLY when the manifest carries it; the exact
   desired-state reconcile retracts a previously stored value when it is later
   omitted."
  {:malli/schema [:=> [:cat :seon.config/manifest [:map-of :string :string]
                      :seon.config.resolve/hardware-observations]
                  :seon.config/singleton]}
  [manifest environment hardware]
  (let [r   (get manifest :seon.config/render {})
        run (merge (default-run-policy) (get manifest :seon.config/run {}))
        transport (get manifest :seon.config/model-transport {})
        rep (get manifest :seon.config/repair {})
        web (get manifest :seon.config/web {})
        root (get manifest :seon.config/root {})
        reactive (get manifest :seon.config/reactive {})
        execution (get manifest :seon.config/execution {})
        database (get manifest :seon.config/database {})
        _ (validate-liveness-relations! manifest environment run)
        nsp (resolve-namespaces manifest)
        host-respawn-backoff-ms
        (get execution
             :seon.config.execution/host-respawn-backoff-ms
             1000)
        _ (reject-floor!
           {:seon.config.execution/host-respawn-backoff-ms
            host-respawn-backoff-ms}
           :seon.config.execution/host-respawn-backoff-ms
           1000
           "At least one second must separate failed host-reconcile attempts so repeated demand cannot spin operator subprocesses.")]
    (cond-> {:seon.config/id cluster-config-id
             :seon.config/repl-mode
             (let [d (default-repl-mode environment)]
               (coerce-enum (get manifest :seon.config/repl-mode d) #{:batch :stream} d))
             :seon.config.run/batch-turn-limit
             (:seon.config.run/batch-turn-limit run)
             :seon.config.run/stream-form-limit
             (:seon.config.run/stream-form-limit run)
             :seon.config.run/deadline-ms
             (:seon.config.run/deadline-ms run)
             :seon.config.execution/host-tier?
             (boolean
              (get execution :seon.config.execution/host-tier? false))
             :seon.config.execution/host-respawn-backoff-ms
             host-respawn-backoff-ms
             :seon.config/always     (:seon.config/always nsp)
             :seon.config/on-core-error
             (coerce-enum (get manifest :seon.config/on-core-error :gate) #{:crash :gate :log} :gate)
             :seon.config/spawn-depth-cap
             (let [v (get manifest :seon.config/spawn-depth-cap 1)] (if (and (int? v) (>= v 0)) v 1))
             :seon.config.render/database-edn-cap   (get r :seon.config.render/database-edn-cap 16384)
             :seon.config.render/eval-cap           (get r :seon.config.render/eval-cap 1500)
             :seon.config.render/message-cap        (get r :seon.config.render/message-cap 4000)
             :seon.config.render/result-body-cap    (get r :seon.config.render/result-body-cap 16384)
             :seon.config.render/value-max-depth    (get r :seon.config.render/value-max-depth 3)
             :seon.config.render/value-max-keys     (get r :seon.config.render/value-max-keys 8)
             :seon.config.render/value-max-items    (get r :seon.config.render/value-max-items 8)
             :seon.config.render/value-max-path-segments
             (get r :seon.config.render/value-max-path-segments 32)
             :seon.config.render/value-max-path-bytes
             (get r :seon.config.render/value-max-path-bytes 4096)
             :seon.config.render/value-max-realized-items
             (get r :seon.config.render/value-max-realized-items 1024)
             :seon.config.render/value-max-string   (get r :seon.config.render/value-max-string 80)
             :seon.config.render/value-shape-sample (get r :seon.config.render/value-shape-sample 8)
             :seon.config.render/value-verbatim-cap (get r :seon.config.render/value-verbatim-cap 1500)
             :seon.config.render/value-width        (get r :seon.config.render/value-width 72)
             :seon.config.render/render-fn-token-cap (get r :seon.config.render/render-fn-token-cap 2000)
             :seon.config.render/whitespace     (get r :seon.config.render/whitespace :raw)
             :seon.config.render/tabs           (get r :seon.config.render/tabs :literal)
             :seon.config.render/trailing-ws    (get r :seon.config.render/trailing-ws :off)
             :seon.config.render/content-layout (get r :seon.config.render/content-layout :structured)
             :seon.config.render/line-numbers   (boolean (get r :seon.config.render/line-numbers false))
             :seon.config.repair/level
             (coerce-enum (get rep :seon.config.repair/level :symbols)
                          #{:off :safe-syntax :symbols :aggressive} :symbols)
             :seon.config.repair/max-fixes-per-form (get rep :seon.config.repair/max-fixes-per-form 1)
             :seon.config.repair/budget-ms          (get rep :seon.config.repair/budget-ms 50)
             :seon.agent.web/policy
             (coerce-enum (get web :seon.agent.web/policy :public-only)
                          #{:open :public-only :allowlist} :public-only)
             :seon.agent.web/search-backend
             (coerce-enum (get web :seon.agent.web/search-backend :gemini-grounding)
                          #{:gemini-grounding :serper} :gemini-grounding)
             :seon.agent.web/search-model    (get web :seon.agent.web/search-model "gemini-3.1-flash-lite")
             :seon.agent.web/allowed-domains (vec (get web :seon.agent.web/allowed-domains []))
             :seon.config.watchdog/stale-ms
             (get-in manifest [:seon.config/watchdog :seon.config.watchdog/stale-ms] 1200000)
             :seon.config.breaker/crash-count
             (get-in manifest [:seon.config/schedule-breaker :seon.config.breaker/crash-count] 3)
             :seon.config.breaker/window-ms
             (get-in manifest [:seon.config/schedule-breaker :seon.config.breaker/window-ms] 1800000)
             :seon.config.root/recent-limit
             (get root :seon.config.root/recent-limit 12)
             :seon.config/reactive-settle-ms
             (get reactive :seon.config/reactive-settle-ms
                  (:seon.config/reactive-settle-ms default-reactive-policy))
             :seon.config/reactive-structural-settle-ms
             (get reactive :seon.config/reactive-structural-settle-ms
                  (:seon.config/reactive-structural-settle-ms default-reactive-policy))
             :seon.config/reactive-max-latency-ms
             (get reactive :seon.config/reactive-max-latency-ms
                  (:seon.config/reactive-max-latency-ms default-reactive-policy))
             :seon.config.database.query/max-work
             (get database :seon.config.database.query/max-work
                  (:seon.config.database.query/max-work default-database-query-policy))
             :seon.config.database.query/max-results
             (get database :seon.config.database.query/max-results
                  (:seon.config.database.query/max-results default-database-query-policy))
             :seon.config.database.query/max-result-weight
             (get database :seon.config.database.query/max-result-weight
                  (:seon.config.database.query/max-result-weight default-database-query-policy))
             :seon.config.database.pull/max-work
             (get database :seon.config.database.pull/max-work
                  (:seon.config.database.pull/max-work default-database-pull-policy))
             :seon.config.database.pull/max-results
             (get database :seon.config.database.pull/max-results
                  (:seon.config.database.pull/max-results default-database-pull-policy))
             :seon.config.database.pull/max-result-weight
             (get database :seon.config.database.pull/max-result-weight
                  (:seon.config.database.pull/max-result-weight default-database-pull-policy))}
      (contains? manifest :seon.config/system-text)
      (assoc :seon.config/system-text (:seon.config/system-text manifest))
      (contains? transport :seon.config.model-transport/response-identity-cap)
      (assoc :seon.config.model-transport/response-identity-cap
             (:seon.config.model-transport/response-identity-cap transport))
      (contains? transport :seon.config.model-transport/endpoint-cap)
      (assoc :seon.config.model-transport/endpoint-cap
             (:seon.config.model-transport/endpoint-cap transport))
      (contains? manifest :seon.config/context-profiles)
      (assoc :seon.config/context-profiles (:seon.config/context-profiles manifest))
      (seq (:seon.config/model-variants manifest))
      (assoc :seon.config/model-variants
             (into []
                   (map (fn [[variant configuration]]
                          (assoc configuration
                                 :seon.config/model-variant variant)))
                   (sort-by (comp str key)
                            (:seon.config/model-variants manifest))))
      (contains? manifest :seon.config/skills-dir)
      (assoc :seon.config/skills-dir (:seon.config/skills-dir manifest))
      (contains? manifest :seon.config/agent-context)
      (assoc :seon.config/agent-context (:seon.config/agent-context manifest))
      (contains? manifest :seon.config/root-context)
      (assoc :seon.config/root-context (:seon.config/root-context manifest))

      true
      (merge (into {}
                   (keep (fn [[class enabled?]]
                           (when-let [attribute
                                      (get repair-class-attributes class)]
                             [attribute enabled?])))
                   (:seon.config.repair/classes rep))
             (resolve-operational-values manifest hardware)))))

(defn execution-host-respawn-backoff-ms
  "The demand-triggered host reconcile backoff from one resolved singleton."
  {:malli/schema [:=> [:cat [:maybe :seon.config/singleton]] :int]}
  [configuration]
  (get configuration
       :seon.config.execution/host-respawn-backoff-ms
       1000))

(defn resolve-ai-config
  "The declared cluster-default LLM desired rows.

   Startup reconciles a declared value before `seon.ai/sync!`; therefore the
   resolution order is declared manifest value, existing database fact when
   this section is absent, then the environment's first-boot seed when no row
   exists. Returns zero or one row. `:seon.ai/api-key-env` is a variable name,
   never a secret."
  {:malli/schema [:=> [:cat :seon.config/manifest] :seon.config/ai-rows]}
  [manifest]
  (if-let [selection (:seon.config/ai manifest)]
    [(assoc selection :seon.ai/id "config")]
    []))

(defn resolve-envelope
  "Resolve the complete boot envelope and each key's disposition."
  [manifest hardware generation]
  (merge
   (resolve-operational-values manifest hardware)
   {:seon.launch.envelope/generation generation
    :seon.launch.envelope/hardware-observations hardware
    :seon.launch.envelope/dispositions
    (into {}
          (map (fn [attribute]
                 [attribute (if (contains? enforced-keys attribute)
                              :enforced
                              :carried)]))
          operational-keys)}))

(defn envelope-divergences
  "Operational keys whose launch and committed values differ."
  [envelope configuration]
  (into {}
        (keep (fn [attribute]
                (let [launched (get envelope attribute)
                      committed (get configuration attribute)]
                  (when (not= launched committed)
                    [attribute
                     {:seon.launch.envelope/value launched
                      :seon.config/value committed}]))))
        operational-keys))

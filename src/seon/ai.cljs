(ns seon.ai
  "Shared LLM surface (fix-everything PRD C-18 + C-20) — the provider
   call settings are DATA, not compiled constants. A downstream
   deployment retunes the LLM (provider, model, thinking, budgets)
   without forking an adapter ns.

   One singleton row (identity `::id` = \"config\") carries up to
   ten attrs: `::provider`, `::model`, `::temperature`,
   `::max-tokens`, `::thinking`, `::timeout-ms`, `::base-url`,
   `::api-key-env`, `::dg-backend`, and `::extra-body-edn`. Adapters
   ([[seon.ai.openai-compat]],
   [[seon.ai.anthropic]]) read it PER CALL via [[current]] —
   reactive-context: no cached atom, absent row/attr = each adapter's
   shipped defaults, byte-identical wire bodies to the pre-C-18 output.

   ENV/CONFIG SEEDS ONCE → THE DB OWNS THE ROW. [[sync!]] (called from
   `seon.client/start-agent!` at boot) SEEDS the row from the `SEON_AI_*`
   env vars ONLY when it is unconfigured (a fresh store). Once seeded the
   DB is authoritative: a later boot does NOT re-sync, so a runtime
   transact against the row (a model/provider switch) PERSISTS across
   reboots. Env is the INITIAL config source, not the row's owner — to
   re-seed from env, clear the config row first. API KEYS are never
   stored: they are read from the environment at call time
   ([[seon.ai.anthropic]]/[[seon.ai.openai-compat]]); only the non-secret
   config (provider/model/…) is seeded and DB-owned. (NB: this DIVERGES
   from `seon.web.brand`, which is still strictly env-owned — flagged for
   later convergence; the same seed-once model likely fits branding too.)

   `::thinking` is stored as a STRING (the env var's shape):
   \"false\" (off — the default), \"true\" (on), or a reasoning-effort
   string like \"high\"/\"max\". [[thinking-mode]] parses it to the
   value adapters consume (false / true / effort string). This row
   REPLACES deepseek's old `!thinking` + `set-thinking!` atom and its
   `!timeout-ms` + `set-timeout-ms!` — one mechanism, no parallel
   knobs.

   This ns also owns the shared `:seon.ai/*` schema vocabulary (the
   errors-are-values envelope, request/response field shapes) and the
   provider-agnostic system-prompt resolution. The LLM `system` role
   message is the HARDCODED, system-specific seon mechanics
   ([[seon.agent.ctx/system-text]] — REPL doctrine, environment orientation,
   common DB ops, standing teachings); both providers send the same one.
   It is decoupled from SOUL.md / AGENTS.md, which are FILE-LOADED
   CONTEXT sections (`seon.agent.ctx/file-block`), not the system
   message."
  (:require [clojure.string :as str]
            [cljs.reader :as reader]
            ["node:crypto" :as node-crypto]
            [seon.agent.ctx :as ctx]
            [seon.config :as config]
            [seon.db :as db]
            [seon.log :as log]
            [seon.schema :as schema]))

;; ============================================================
;; Shared schemas — the :seon.ai/* vocabulary both providers use.
;; (Moved here from the OpenAI-compatible adapter — the keywords'
;; namespace now matches the code ns that registers them.)
;; ============================================================

(schema/register! ::text :string)
(schema/register! ::model :string)
(schema/register! ::temperature :double)
(schema/register! ::max-tokens :int)
(schema/register! ::system-prompt :string)
(schema/register! ::ctx :string)
(schema/register! ::usage :map)
(schema/register! ::msg :string)
(schema/register! ::status :int)
(schema/register! ::timeout? :boolean)
;; TRANSPORT-shaped failure: js/fetch THREW before any HTTP status
;; arrived — DNS failure, connection refused/reset, the observed live
;; "fetch failed" (2026-06-11). This is the ONE retryable class: the
;; request may never have reached the provider, so one bounded retry
;; (seon.agent's turn loop) is safe and cheap. HTTP-status errors
;; (4xx/5xx) and unparseable bodies are PROCESSING errors (never
;; flagged); a wall-clock abort is :seon.ai/timeout? — also never
;; flagged, it already burned the full timeout budget.
(schema/register! ::transport? :boolean)
(schema/register! ::raw-body :string)
;; The server's `Retry-After` for a 429/503, already PARSED to
;; milliseconds (delta-seconds or HTTP-date → ms-from-now; never
;; negative). Present ONLY when the provider sent the header on a
;; retryable HTTP-status error; the agent turn loop's backoff honors it.
(schema/register! ::retry-after-ms :int)

;; Tool/function-calling + extra-request + provider-metadata vocabulary
;; (SDK migration, 2026-06-16). These ride third-party-shaped maps: the
;; OpenAI/Anthropic tool defs, the returned tool_calls / tool_use, the
;; unrecognized top-level response fields (#25), and the generic extra
;; request body (e.g. Qwen's chat_template_kwargs). :any / :map are
;; deliberate third-party boundaries — seon does not own these shapes.
(schema/register! ::tools          [:sequential :map])
(schema/register! ::tool-choice    :any)
(schema/register! ::tool-calls     [:sequential :map])
(schema/register! ::provider-fields [:map])
(schema/register! ::extra-body     [:map])

;; Streaming (repl-mode `:stream`, Phase 1). `::stream?` on the llm-fn arg
;; asks the adapter to consume the SDK stream delta-by-delta and ABORT the
;; moment one complete top-level form has streamed (one form per turn).
;; `::estimated?` marks a response whose `::usage` is a CLIENT-SIDE
;; `seon.ai.tokens/estimate` (an aborted stream loses the provider's final
;; usage chunk), so the turn record is honest that its token counts are
;; estimates, not provider-reported.
(schema/register! ::stream?    :boolean)
(schema/register! ::estimated? :boolean)
;; Process-local host cancellation capability. AbortSignal is a third-party JS
;; object and is never persisted; :any is intentional at this boundary.
(schema/register! ::abort-signal :any)

(defn llm-arg->ctx
  "The ctx string from a bare-string or request-map llm-fn argument.

   The argument is EITHER a bare ctx string (the back-compat shape every
   adapter still accepts) OR a request map carrying `:seon.ai/ctx` (the
   widened shape the turn loop passes when it needs streaming). Coerce,
   never break: a plain string passes through."
  {:malli/schema [:=> [:catn [::arg :any]] :string]}
  [arg]
  (if (map? arg) (str (::ctx arg)) (str arg)))

(defn llm-arg->stream?
  "Whether the llm-fn argument requested streaming.

   `:seon.ai/stream?` true on a request-map arg, false for a bare string
   (back-compat)."
  {:malli/schema [:=> [:catn [::arg :any]] :boolean]}
  [arg]
  (boolean (and (map? arg) (::stream? arg))))

(defn llm-arg->abort-signal
  "The optional host AbortSignal carried by a request-map LLM argument.

   A legacy bare-string argument has no signal. The signal is process-local
   transport control, never database data."
  {:malli/schema [:=> [:catn [::arg :any]] [:maybe ::abort-signal]]}
  [arg]
  (when (map? arg) (::abort-signal arg)))

(defn aborted?
  "True when `signal` is an aborted host AbortSignal. nil is false."
  {:malli/schema [:=> [:catn [::signal [:maybe ::abort-signal]]] :boolean]}
  [signal]
  (boolean (and signal (.-aborted signal))))

;; The errors-are-values envelope for LLM calls. Every failure mode
;; (timeout, fetch throw, HTTP non-2xx, unparseable body, refusal)
;; resolves to a response map carrying this under :seon.ai/error —
;; never a rejected Promise. Callers (seon.agent's turn loop) MUST
;; surface it.
(schema/register!
  ::error
  [:map
   [::msg            ::msg]
   [::status         {:optional true} ::status]
   [::timeout?       {:optional true} ::timeout?]
   [::transport?     {:optional true} ::transport?]
   [::retry-after-ms {:optional true} ::retry-after-ms]
   [::raw-body       {:optional true} ::raw-body]])

;; ------------------------------------------------------------
;; Retry-After parsing — ONE place so both adapters (anthropic /
;; openai-compat) extract the header identically (no duplicate shape).
;; ------------------------------------------------------------

(defn parse-retry-after-ms
  "Parse an HTTP `Retry-After` header value to MILLISECONDS, or nil.

   The header is either delta-seconds (e.g. \"30\") or an HTTP-date (e.g.
   \"Wed, 21 Oct 2026 07:28:00 GMT\"); a date resolves to the delay from
   now (clamped non-negative). Blank/unparseable → nil."
  {:malli/schema [:=> [:catn [::header [:maybe :string]]] [:maybe :int]]}
  [header]
  (when (and (string? header) (not (str/blank? header)))
    (let [trimmed (str/trim header)
          secs    (js/Number trimmed)]
      (if (js/isFinite secs)
        (max 0 (js/Math.round (* secs 1000)))
        (let [t (js/Date.parse trimmed)]
          (when-not (js/isNaN t)
            (max 0 (- t (js/Date.now)))))))))

(defn- header-get
  "Read header `k` (case-insensitively) off either a `Headers` instance
   (`.get`) or a plain header object. nil when absent."
  [headers k]
  (when headers
    (if (fn? (.-get headers))
      (.get headers k)
      (or (aget headers k) (aget headers (str/lower-case k))))))

(defn error-retry-after-ms
  "The `Retry-After` (ms) carried by an SDK APIError's headers, or nil.

   Reads the header off the error's `.headers` (a `Headers` instance on the
   official SDKs) and parses via [[parse-retry-after-ms]]. `e` is a
   third-party SDK error object (`:any` boundary)."
  {:malli/schema [:=> [:catn [::error-obj :any]] [:maybe :int]]}
  [e]
  (parse-retry-after-ms (header-get (some-> e .-headers) "retry-after")))

;; ============================================================
;; The config row — :seon.ai/config singleton (identity ::id "config").
;; ============================================================

(schema/register! ::id [:string {:seon.db/identity true}])  ; always "config"
;; :openai-compat = any OpenAI-compatible chat-completions gateway
;; (enterprise, bearer-keyed). Same wire path as :deepseek
;; (seon.ai.openai-compat) with endpoint + key resolved from ::base-url /
;; ::api-key-env instead of the shipped deepseek defaults.
;; :typeahead = the diffusion typeahead STEP-LOOP provider
;; (seon.ai.typeahead) — the same worker endpoint/key config as
;; :diffusiongemma (SEON_DG_ENDPOINT), a different wire mode (mode=step).
;;
;; Each provider is DEFINED here with its declared wire locality —
;; :frontier = a hosted frontier chat LLM; :local-worker = the LOCAL
;; diffusion-worker family, whose endpoint/key resolve from
;; SEON_DG_ENDPOINT (seon.ai.diffusiongemma owns that wire; the worker
;; owns its own model + gen-config caps, which is also why
;; [[shipped-defaults]] ships none of the five for it). The ::provider
;; enum derives from this map's keys, so a provider CANNOT be added
;; without declaring its locality — one definition site, no drift.
(def provider-locality
  "Declared locality of every `::provider`, at its definition site."
  {:deepseek       :frontier
   :anthropic      :frontier
   :openai-compat  :frontier
   :diffusiongemma :local-worker
   :typeahead      :local-worker})

(schema/register! ::provider (into [:enum] (keys provider-locality)))

(defn frontier-provider?
  "True when provider `p` is a frontier LLM, not a local worker.

   Reads the colocated [[provider-locality]] declaration — the planner
   derivation (`my.plan.internal/planner-for`) uses this to require a
   frontier provider for the consulted planner."
  {:malli/schema [:=> [:cat ::provider] :boolean]}
  [p]
  (= :frontier (get provider-locality p)))
;; DiffusionGemma backend selector (env SEON_DG_BACKEND — the SEON_DG_*
;; names are kept for continuity; the local process is `diffusion-server`).
;; (DB-ownable like
;; ::provider). :control = the transformers RunPod worker that keeps the
;; per-step LogitsProcessor seam (seon.ai.diffusiongemma); :vllm = an
;; OpenAI-compatible serving endpoint (reuses seon.ai.openai-compat).
;; Only consulted when ::provider is :diffusiongemma; default :control.
(schema/register! ::dg-backend [:enum :vllm :control])
;; The FULL chat-completions URL of an OpenAI-compatible gateway
;; (e.g. "https://gw.example.com/v1/chat/completions") — NOT a prefix
;; the adapter appends a path to. One semantic: what you set is what
;; js/fetch POSTs to. Required when ::provider is :openai-compat
;; (no shipped default endpoint — missing = legible error at call
;; time). Env: SEON_AI_BASE_URL.
(schema/register! ::base-url [:string {:min 1}])
;; The NAME of the env var holding the bearer API key — never the key
;; itself (keys are read at call time from process.env, never
;; transacted). Env: SEON_AI_API_KEY_ENV. Absent → the provider's
;; default (DEEPSEEK_API_KEY for :deepseek) and the conventional
;; SEON_AI_API_KEY fallback — see seon.ai.openai-compat's key resolution.
(schema/register! ::api-key-env [:string {:min 1}])
;; "false" | "true" | reasoning-effort string ("high"/"max"/…). Stored
;; as the env var's string shape; [[thinking-mode]] is the parse.
(schema/register! ::thinking [:string {:min 1}])
(schema/register! ::timeout-ms :int)
;; The :seon.ai/extra-body map (generic extra request fields, e.g. Qwen's
;; chat_template_kwargs) is a [:map] — datahike can't bridge it. The config
;; row stores its EDN STRING form under ::extra-body-edn (env:
;; SEON_AI_EXTRA_BODY, an EDN map like "{:chat_template_kwargs
;; {:enable_thinking false}}"); [[config-extra-body]] reads it back into the
;; map adapters merge. This is the data-only door for the agent turn loop —
;; the loop builds the adapter with no opts, so the request-opt path is
;; unreachable there (task #30, 2026-06-16). ::tools / ::tool-choice stay
;; request-opt-only (inherently per-call; no persisted form yet).
(schema/register! ::extra-body-edn [:string {:min 1}])
(schema/register! ::extra-body-digest [:string {:min 64 :max 64}])

;; The RESOLVED LLM config as a VALUE — what an agent runs
;; under. NEVER stored (owner correction 2026-07-04, derive-don't-store:
;; the per-turn `llm-*` stamping this shape once fed is deleted);
;; [[resolved-config]] derives it from ANY db value on demand — the live
;; db for current intent, or the immutable value returned by
;; `seon.db/at-coordinate` for a past turn's complete rendered coordinate.
;; `::thinking` is the row-shape STRING
;; ("false"/"true"/effort — the [[thinking-mode]] vocabulary). Keys with
;; no value at any resolution tier (anthropic never sends temperature;
;; the diffusiongemma worker owns its own model + caps) are ABSENT.
(schema/register! ::resolved-config
  [:map
   [::provider    ::provider]
   [::model       {:optional true} ::model]
   [::temperature {:optional true} ::temperature]
   [::max-tokens  {:optional true} ::max-tokens]
   [::thinking    {:optional true} ::thinking]
   [::timeout-ms  {:optional true} ::timeout-ms]
   [::base-url    {:optional true} ::base-url]
   [::api-key-env {:optional true} ::api-key-env]
   [::dg-backend  {:optional true} ::dg-backend]
   [::extra-body-digest {:optional true} ::extra-body-digest]])

;; The SHIPPED per-provider defaults for the `::resolved-config` keys —
;; the LAST tier of the ONE resolution chain (request opt → agent
;; override → config row → THIS). Single source: the adapters read their
;; default constants FROM here (seon.ai.openai-compat, seon.ai.anthropic)
;; and [[resolved-config]] reports them — one map, zero drift.
;; :openai-compat shares the deepseek adapter's wire path and fallbacks.
;; The worker still owns DiffusionGemma weights and generation caps. Material
;; endpoint/timeout/backend defaults live here too: adapters and historical
;; resolution must report one value rather than drift independently.
(def shipped-defaults
  "Per-provider shipped defaults for the `:seon.ai/resolved-config` keys."
  {:deepseek       {::model "deepseek-v4-pro" ::temperature 0.7
                    ::max-tokens 4096 ::thinking "false"
                    ::timeout-ms 60000
                    ::base-url "https://api.deepseek.com/v1"}
   :openai-compat  {::model "deepseek-v4-pro" ::temperature 0.7
                    ::max-tokens 4096 ::thinking "false"
                    ::timeout-ms 60000}
   :anthropic      {::model "claude-opus-4-8" ::max-tokens 16000
                    ::thinking "false" ::timeout-ms 60000}
   :diffusiongemma {::dg-backend :control}})

;; The config attrs a row (or the env) may carry — shared shape for
;; [[sync-tx-data]]'s two inputs and the row read.
(schema/register! ::row
  [:map
   [::provider   {:optional true} ::provider]
   [::model      {:optional true} ::model]
   [::temperature {:optional true} ::temperature]
   [::max-tokens {:optional true} ::max-tokens]
   [::thinking   {:optional true} ::thinking]
   [::timeout-ms {:optional true} ::timeout-ms]
   [::base-url    {:optional true} ::base-url]
   [::api-key-env {:optional true} ::api-key-env]
   [::dg-backend  {:optional true} ::dg-backend]
   [::extra-body-edn {:optional true} ::extra-body-edn]])

(schema/register! ::config
  [:map {:seon.db/entity true}
   [::id          ::id]
   [::provider    {:optional true} ::provider]
   [::model       {:optional true} ::model]
   [::temperature {:optional true} ::temperature]
   [::max-tokens  {:optional true} ::max-tokens]
   [::thinking    {:optional true} ::thinking]
   [::timeout-ms  {:optional true} ::timeout-ms]
   [::base-url    {:optional true} ::base-url]
   [::api-key-env {:optional true} ::api-key-env]
   [::dg-backend  {:optional true} ::dg-backend]
   [::extra-body-edn {:optional true} ::extra-body-edn]])

(schema/register! ::synced? :boolean)
(schema/register! ::sync-request
  [:map
   [::row {:optional true} ::row]
   [::env ::row]])
(schema/register! ::sync-response [:map [::synced? ::synced?]])

;; ============================================================
;; PER-AGENT LLM overrides (config-driven-agent-init CP-1). Each is an
;; agent-entity attr overriding the global :seon.ai/config row for that
;; ONE agent; `:inherit` (the default) resolves to the global row. The
;; value arm REUSES the existing global-row value shape by keyword (the
;; register-once rule) — `::provider`/`::model`/`::temperature`/
;; `::max-tokens`/`::thinking`. `::agent-max-retries` replaces the
;; SEON_AI_MAX_RETRIES env read (seon.agent.turn). READ per call:
;; [[current]] lays the ambient agent's overrides over the global row
;; ([[overlay-agent-overrides]]), so every adapter's resolution chain is
;; explicit request opt → the AGENT's own config → the global row →
;; shipped defaults. What an agent resolves to is a QUERY, never a
;; stored stamp: [[resolved-config]] derives the effective config (with
;; per-key provenance) from any db value — live, or `db/as-of` a past
;; turn's basis-t (derive-don't-store).
;; ============================================================

(schema/register! ::agent-provider    [:or {:default :inherit} [:enum :inherit] ::provider])
(schema/register! ::agent-model       [:or {:default :inherit} [:enum :inherit] ::model])
(schema/register! ::agent-temperature [:or {:default :inherit} [:enum :inherit] ::temperature])
(schema/register! ::agent-max-tokens  [:or {:default :inherit} [:enum :inherit] ::max-tokens])  ; OUTPUT cap
(schema/register! ::agent-thinking    [:or {:default :inherit} [:enum :inherit] ::thinking])
(schema/register! ::agent-max-retries [:or {:default :inherit} [:enum :inherit] [:int {:min 0}]])
;; PARKED (decision 21): ::agent-context-window — a NEW input budget,
;; nothing enforces it today. Deferred to phase 2.

;; The attr order is the sync + row-read iteration order.
(def ^:private config-attrs
  [::provider ::model ::temperature ::max-tokens ::thinking ::timeout-ms
   ::base-url ::api-key-env ::dg-backend ::extra-body-edn])

;; ============================================================
;; Env reads — SEON_AI_*, parsed to the attr's concrete type.
;; ============================================================

(defn- parse-double*
  [s]
  (let [v (js/parseFloat s)] (when-not (js/isNaN v) v)))

(defn- parse-int*
  [s]
  (let [v (js/parseInt s 10)] (when-not (js/isNaN v) v)))

(defn- parse-provider
  [s]
  (case s
    "deepseek"      :deepseek
    "anthropic"     :anthropic
    "openai-compat" :openai-compat
    "diffusiongemma" :diffusiongemma
    "typeahead"     :typeahead
    nil))

(defn- parse-dg-backend
  [s]
  (case s
    "control" :control
    "vllm"    :vllm
    nil))

(defn- parse-extra-body-edn
  "Validate SEON_AI_EXTRA_BODY: it must read as an EDN MAP. Returns the
   raw string (stored verbatim — [[config-extra-body]] re-reads it) when
   it parses to a map, else nil (env-row logs LOUDLY and skips it)."
  [s]
  (try
    (when (map? (reader/read-string s)) s)
    (catch :default _ nil)))

;; attr → [env-var-name parse-fn]. parse-fn returns nil on an
;; unparseable value — [[env-row]] logs LOUDLY and skips it (a typo'd
;; SEON_AI_TEMPERATURE must not take the boot down, but must never be
;; silent either).
(def ^:private env-var-specs
  {::provider    ["SEON_AI_PROVIDER"    parse-provider]
   ::model       ["SEON_AI_MODEL"       identity]
   ::temperature ["SEON_AI_TEMPERATURE" parse-double*]
   ::max-tokens  ["SEON_AI_MAX_TOKENS"  parse-int*]
   ::thinking    ["SEON_AI_THINKING"    identity]
   ::timeout-ms  ["SEON_AI_TIMEOUT_MS"  parse-int*]
   ::base-url    ["SEON_AI_BASE_URL"    identity]
   ::api-key-env ["SEON_AI_API_KEY_ENV" identity]
   ::dg-backend  ["SEON_DG_BACKEND"     parse-dg-backend]
   ::extra-body-edn ["SEON_AI_EXTRA_BODY" parse-extra-body-edn]})

(defn env-row
  "The LLM-config attrs present in the environment, `::row`-shaped.

   Only the keys whose SEON_AI_* var is set, non-blank, and parseable.
   An unparseable value logs LOUDLY and is skipped."
  {:malli/schema [:=> [:cat] ::row]}
  []
  (reduce-kv
    (fn [m attr [var-name parse]]
      (if-let [raw (config/env-string var-name)]
        (if-some [v (parse raw)]
          (assoc m attr v)
          (do (log/error-console!
                "seon.ai"
                (str var-name " is set but unparseable — IGNORED "
                     "(adapter defaults apply): " (pr-str raw)))
              m))
        m))
    {}
    env-var-specs))

;; ============================================================
;; The row read + effective config.
;; ============================================================

(defn- row
  "The config row's attrs from db value `db` as a `::row` map, or nil
   when no `::id` \"config\" entity exists. An existing entity with no
   config attrs returns {} (distinct from nil — retracts may target
   it)."
  [db]
  (when-let [e (ffirst (db/query {:seon.db/query '[:find ?e
                                                   :where [?e ::id "config"]]
                                  :seon.db/db db}))]
    (let [ent (db/entity {:seon.db/db db :seon.db/ref e})]
      (into {}
            (keep (fn [attr]
                    (when-some [v (get ent attr)] [attr v])))
            config-attrs))))

(defn- global-config
  "The GLOBAL `::config` row's set attrs — `::row`-shaped, possibly {}. The
   pure global read (no per-agent overlay); NEVER throws ({} on the conn-not-up
   boot edge). 0-arity reads the ambient `seon.db/*conn*`, 1-arity an explicit
   db value."
  ([] (or (try (some-> db/*conn* deref row)
               (catch :default _ nil))
          {}))
  ([db] (or (row db) {})))

;; The per-agent LLM override attrs (each `:inherit` by default) mapped to the
;; GLOBAL config-row attr they override (config-driven agent-init, move 10).
;; `:inherit` (the default) → use the global row's value = byte-parity for a
;; no-override agent.
(def ^:private agent-override-attrs
  {::agent-provider    ::provider
   ::agent-model       ::model
   ::agent-temperature ::temperature
   ::agent-max-tokens  ::max-tokens
   ::agent-thinking    ::thinking})

(schema/register! ::agent-id [:string {:min 1}])
(schema/register! ::effective-config-request [:map [::agent-id ::agent-id]])

(defn- agent-override-values
  "Agent `id`'s non-`:inherit` `::agent-*` override VALUES from db value
   `db`, keyed by the global attr each overrides. Per-attr install-gated
   (querying a never-installed attr THROWS on datahike-cljs). Nil db /
   nil id / no such agent → {}."
  [db id]
  (let [installed (when db (db/installed-schema db))
        agent     (when (and db id)
                    (db/entity {:seon.db/db db :seon.db/ref [:seon.agent/id id]}))]
    (if-not agent
      {}
      (reduce-kv
        (fn [m agent-attr global-attr]
          ;; `::agent-*` overrides are MIXED-`:or` schemas → stored pr-str'd by
          ;; the bridge; decode on read. Gate each attr by the installed schema.
          (let [v (when (contains? installed agent-attr)
                    (some->> (get agent agent-attr)
                             (db/decode-edn-value agent-attr)))]
            (if (or (nil? v) (= :inherit v))
              m
              (assoc m global-attr v))))
        {}
        agent-override-attrs))))

(defn- overlay-agent-overrides
  "Lay agent `id`'s `::agent-*` override datoms over `global` (a `::row`),
   `:inherit`/absent → keep the global value. Reads the ambient conn's
   current db. Nil id / no agent → `global` unchanged."
  [global id]
  (merge global (agent-override-values (some-> db/*conn* deref) id)))

(defn current
  "The EFFECTIVE LLM config the adapters read PER CALL.

   Reactive-context (no cache): the GLOBAL `::config` row with the CURRENT agent's `::agent-*`
   overrides laid over it (config-driven agent-init — per-agent LLM). The agent
   is the ambient `seon.db/current-agent-id` (fiber-local across the adapter's
   awaits); OUTSIDE an agent scope (boot or direct render) it is just the global row.
   `:inherit` (the default) ⇒ the global value = byte-parity for a no-override
   agent. NEVER throws ({} on the conn-not-up boot edge). 1-arity takes an
   explicit db value (global-only — a render read, not an agent call)."
  {:malli/schema [:function
                  [:=> [:cat] ::row]
                  [:=> [:catn [::db :seon.db/db-val]] ::row]]}
  ([] (overlay-agent-overrides (global-config) (db/current-agent-id)))
  ([db] (global-config db)))

(defn effective-config-for
  "The EFFECTIVE LLM config for a SPECIFIC agent `id`.

   The explicit-id path — the global `::config` row with the agent's `::agent-*` overrides laid over it
   (`:inherit`/absent → the global value). Same overlay [[current]] applies for
   the AMBIENT agent; this arity is for a caller naming an id out of scope.
   A no-override agent resolves EXACTLY the global row = byte-parity."
  {:malli/schema [:=> [:cat ::effective-config-request] ::row]}
  [{::keys [agent-id]}]
  (overlay-agent-overrides (global-config) agent-id))

;; WHERE a resolved value came from — provenance by DERIVATION (the
;; resolver re-walks the chain), never storage. Same key set as
;; `::resolved-config`.
(schema/register! ::source [:enum :agent-override :config-row :default])
(schema/register! ::provenance
  [:map
   [::provider    ::source]
   [::model       {:optional true} ::source]
   [::temperature {:optional true} ::source]
   [::max-tokens  {:optional true} ::source]
   [::thinking    {:optional true} ::source]
   [::timeout-ms  {:optional true} ::source]
   [::base-url    {:optional true} ::source]
   [::api-key-env {:optional true} ::source]
   [::dg-backend  {:optional true} ::source]
   [::extra-body-digest {:optional true} ::source]])
(schema/register! ::resolved-config-request
  [:map
   [:seon.db/db :seon.db/db-val]
   [:seon.agent/id {:optional true} ::agent-id]])
(schema/register! ::resolved-config-response
  [:map
   [::resolved-config ::resolved-config]
   [::provenance      ::provenance]])

(defn- extra-body-digest
  "SHA-256 of the exact database-owned EDN bytes when they parse as a map."
  [raw]
  (let [parsed (try (reader/read-string raw) (catch :default _ nil))]
    (when (map? parsed)
      (-> (.createHash node-crypto "sha256")
          (.update raw "utf8")
          (.digest "hex")))))

(defn resolved-config
  "The effective LLM config an agent runs under, derived from a db value.

   Pure fn of `:seon.db/db` — derive-don't-store: nothing persists this,
   asking again re-derives it. Per key the chain is the agent's own
   `::agent-*` override datom → the global `::config` row → the
   provider's [[shipped-defaults]] entry (`::provider` itself falls back
   to `:deepseek`). Returns the `::resolved-config` VALUE plus
   `::provenance` — the same keys mapped to where each value came from
   (`:agent-override` / `:config-row` / `:default`). A key with no value
   at any tier is absent from both. Omit `:seon.agent/id` for the
   global-only view. (A section fn surfacing this per agent is the
   natural UI follow-on.)

   Time travel — resolve a past turn's complete rendered coordinate with
   `seon.db/at-coordinate`, then pass that returned immutable db value to
   this same pure function:

     (resolved-config
       {:seon.db/db    historical-db
        :seon.agent/id agent-id})"
  {:malli/schema [:=> [:cat ::resolved-config-request] ::resolved-config-response]}
  [{db :seon.db/db id :seon.agent/id}]
  (let [overrides (agent-override-values db id)
        row-cfg   (global-config db)
        pick      (fn [k defaults]
                    (cond
                      (contains? overrides k) [(get overrides k) :agent-override]
                      (contains? row-cfg k)   [(get row-cfg k)   :config-row]
                      (contains? defaults k)  [(get defaults k)  :default]))
        [prov prov-src] (or (pick ::provider {}) [:deepseek :default])
        defaults  (get shipped-defaults prov {})]
    (let [resolved
          (reduce
            (fn [acc k]
              (if-let [[v src] (pick k defaults)]
                (-> acc
                    (assoc-in [::resolved-config k] v)
                    (assoc-in [::provenance k] src))
                acc))
            {::resolved-config {::provider prov}
             ::provenance      {::provider prov-src}}
            [::model ::temperature ::max-tokens ::thinking ::timeout-ms
             ::base-url ::api-key-env ::dg-backend])]
      (if-let [[raw src] (pick ::extra-body-edn defaults)]
        (if-let [digest (extra-body-digest raw)]
          (-> resolved
              (assoc-in [::resolved-config ::extra-body-digest] digest)
              (assoc-in [::provenance ::extra-body-digest] src))
          resolved)
        resolved))))

(defn agent-max-retries
  "The per-agent LLM retry COUNT for agent `id`.

   The agent's `::agent-max-retries` datom when set to an int, else
   `default-n` (`:inherit`, the default, → the env/const default =
   byte-parity). REPLACES the `SEON_AI_MAX_RETRIES` env read at the sole
   retry site (move 10)."
  {:malli/schema [:=> [:catn [::agent-id [:maybe :string]] [::default-n :int]] :int]}
  [agent-id default-n]
  (let [db (some-> db/*conn* deref)
        ;; `::agent-max-retries` is a MIXED-`:or` schema → stored pr-str'd by
        ;; the bridge; decode on read. `:inherit` (the default) → `default-n`.
        v  (when (and db agent-id
                      (contains? (db/installed-schema db) ::agent-max-retries))
             (some->> (:seon.ai/agent-max-retries
                        (db/entity {:seon.db/db db :seon.db/ref [:seon.agent/id agent-id]}))
                      (db/decode-edn-value ::agent-max-retries)))]
    (if (int? v) v default-n)))

(defn config-extra-body
  "The `:seon.ai/extra-body` map from the config row's `::extra-body-edn`.

   Sourced from env SEON_AI_EXTRA_BODY — the DATA-ONLY door for the agent
   turn loop, which builds the adapter with no request opts. `{}` when unset or
   unreadable (a direct transact of a non-map / malformed EDN is swallowed
   here, not surfaced as a crash). Adapters merge a non-empty result into
   the wire body; a per-call `:seon.ai/extra-body` opt still wins."
  {:malli/schema [:=> [:cat] ::extra-body]}
  []
  (let [v (try (some-> (::extra-body-edn (current)) reader/read-string)
               (catch :default _ nil))]
    (if (map? v) v {})))

(schema/register! ::thinking-value
  [:or :boolean [:string {:min 1}]])

(defn thinking-mode
  "Parse a `::row` map's `::thinking` string to the adapter value.

   Adapters consume: absent or \"false\" → false (off — the default),
   \"true\" → true, anything else → the string itself (a
   reasoning-effort level like \"high\"/\"max\")."
  {:malli/schema [:=> [:catn [::row ::row]] ::thinking-value]}
  [{::keys [thinking]}]
  (case thinking
    (nil "false") false
    "true"        true
    thinking))

(defn provider
  "The active LLM provider — config row `::provider`, else env, else default.

   The DB-owned config row's `::provider` (read
   per call via [[current]]), else `SEON_AI_PROVIDER` env (the initial
   seed source, and the only one readable on the pre-conn boot edge where
   [[current]] returns `{}`), else `:deepseek`. ROW-FIRST now that the DB
   owns the row after env seeds it — so a runtime provider switch
   persists and a later boot honors the row, not a (possibly unset) env."
  {:malli/schema [:=> [:cat] ::provider]}
  []
  (or (::provider (current))
      (::provider (env-row))
      :deepseek))

(defn dg-backend
  "The active DiffusionGemma backend — only when provider is `:diffusiongemma`.

   Consulted only when [[provider]] is `:diffusiongemma`: the DB-owned
   config row's `::dg-backend` (read
   per call via [[current]]), else `SEON_DG_BACKEND` env, else
   `:control` (the transformers worker with the per-step seam). `:vllm`
   routes the diffusiongemma provider through the OpenAI-compatible
   serving path (`seon.ai.openai-compat`); `:control` builds the
   `seon.ai.diffusiongemma` async-job adapter. ROW-FIRST so a runtime
   backend switch persists (same seed-once contract as [[provider]])."
  {:malli/schema [:=> [:cat] ::dg-backend]}
  []
  (or (::dg-backend (current))
      (::dg-backend (env-row))
      (::dg-backend (:diffusiongemma shipped-defaults))))

;; ============================================================
;; Shared system-prompt resolution — request override → the cluster's
;; `:seon.config/system-text` DATOM → the shipped default. Both providers
;; send the same one.
;;
;; The system role message is NOT the soul and NOT any file: it is the
;; environment orientation + REPL doctrine the cluster runs under. A cluster
;; that seeds `:seon.config/system-text` (manifest → the `:seon.config`
;; singleton datom at boot — config-through-DB) owns its system message as
;; DB state (live-tunable by a transact, replay-visible); absent the datom,
;; the shipped default is `seon.agent.ctx/system-text` (byte-identical to
;; the pre-datom database state). SOUL.md / AGENTS.md are FILE-LOADED CONTEXT
;; sections (`seon.agent.ctx/file-block`), wired into
;; the manifest-declared context blocks — they ride the user-message context,
;; not here. There is NO per-call file read in this path: the datom read is
;; `seon.config/config-view` (the db singleton post-conn; the boot manifest
;; resolve is only the pre-conn sliver).
;; ============================================================

(schema/register! ::prompt-request
  [:map [::system-prompt {:optional true} ::system-prompt]])

;; Honest boundary between the two blocks every LLM call sends: the
;; hardcoded SYSTEM message (block 1) and the assembled context (block 2).
;; A reader of the debug text sees exactly where the system message ends
;; and the context begins — the same two blocks the adapters wire
;; (openai-compat `:messages [{:role "system" …}]`, anthropic
;; `:system [{:type "text" …}]`).
(def system-boundary
  "\n\n;; ──────── ↑ system message  │  ↓ context (:seon.ai/ctx) ────────\n\n")

;; Request for [[debug-full-prompt]]: the assembled context (block 2) plus
;; the same optional `::system-prompt` override `effective-system-prompt`
;; honors, passed straight through.
(schema/register! ::debug-prompt-request
  [:map
   [::ctx :string]
   [::system-prompt {:optional true} ::system-prompt]])

(defn effective-system-prompt
  "The system message content for a call.

   One `or` chain: the request's explicit `:seon.ai/system-prompt` override
   when given, else the cluster's `:seon.config/system-text` datom (the
   `:seon.config` singleton via `seon.config/config-view` —
   config-through-DB, seeded from the manifest at boot, live-tunable by a
   transact), else the shipped default (`seon.agent.ctx/system-text`). All
   three are byte-stable within a cluster, the same for every agent and
   turn, so the system block caches. This is NOT the soul and NOT a file —
   SOUL.md / AGENTS.md are context sections, decoupled from the system
   message. Never throws."
  {:malli/schema [:=> [:cat ::prompt-request] ::system-prompt]}
  [{::keys [system-prompt]}]
  (or system-prompt
      (:seon.config/system-text (config/config-view))
      ctx/system-text))

(defn debug-full-prompt
  "The FULL prompt as the agent sees it.

   The resolved system block
   ([[effective-system-prompt]]), a boundary, then the assembled context
   (block 2). THE single source both the debug view preview
   (`seon.agent.debug/ctx-preview`) and the persisted per-turn log use,
   so the two debug surfaces are byte-identical to each other and to what
   the adapters wire. This is a DEBUG representation only — it is NEVER
   sent to the LLM (the adapters add the system block themselves; sending
   this would double it). `:seon.ai/ctx` is the assembled context; an
   explicit `:seon.ai/system-prompt` override is honored (passed through
   to [[effective-system-prompt]])."
  {:malli/schema [:=> [:cat ::debug-prompt-request] :string]}
  [{::keys [ctx] :as request}]
  (str (effective-system-prompt request) system-boundary ctx))

;; ============================================================
;; Shared error logging — a failed LLM call is NEVER silent.
;; ============================================================

(schema/register! ::provider-label [:string {:min 1}])

(defn log-error!
  "ERROR-log an LLM failure with the live agent + turn identity.

   Read from the ALS scopes seon.agent establishes around each turn, so a
   timed-out/failed call is NEVER silent in logs/pod.log. `provider-label`
   names the provider (\"DeepSeek\"/\"Anthropic\"). Best-effort — never throws."
  {:malli/schema [:=> [:catn [::provider-label ::provider-label]
                       [::error ::error]]
                  :nil]}
  [provider-label error-map]
  (try
    (let [agent-id (db/current-agent-id)
          turn-id  (:seon.agent.turn/current-id (db/current-tx-context))]
      (log/error!
        (cond-> {:seon.log/source  ::complete
                 :seon.log/message (str provider-label " call failed"
                                        (when turn-id (str " (turn " turn-id ")"))
                                        " — " (::msg error-map))
                 :seon.log/data    error-map}
          agent-id (assoc :seon.log/agent agent-id))))
    (catch :default _ nil))
  nil)

;; ============================================================
;; Boot sync — env owns the row (same contract as seon.web.brand).
;; ============================================================

(defn sync-tx-data
  "Tx-data SEEDING the config row from the environment, ONCE.

   Pure — both inputs passed in. SEED-ONCE → THE DB OWNS:
     - `existing` row already configured (≥1 config attr) → `[]`
       (the DB owns it; env is ignored and runtime switches are kept);
     - row unconfigured (nil / `{}`) AND env carries config → ONE upsert
       seeding every present env config attr;
     - nothing to seed → `[]`.
   NEVER retracts — a runtime switch (an attr changed in the DB but not
   in env) is preserved across the next boot. `::row` absent/nil/`{}`
   means unconfigured."
  {:malli/schema [:=> [:cat ::sync-request] :seon.db/tx-data]}
  [{existing ::row env ::env}]
  (if (seq existing)
    []
    (let [seed (select-keys env config-attrs)]
      (if (seq seed)
        [(assoc seed ::id "config")]
        []))))

(defn ^:async sync!
  "SEED the config row on the ambient conn from the SEON_AI_* env vars.

   Once, only when the row is unconfigured (see ns doc: env SEEDS, the DB
   OWNS). Awaited by `seon.client/start-runtime!` before boot readiness;
   idempotent — a boot with an already-configured row transacts nothing,
   so runtime switches persist. Failures log LOUDLY and resolve
   `{::synced? false}` — LLM config must never take the boot down."
  {:malli/schema [:=> [:cat] ::sync-response]}
  []
  (try
    (let [tx (sync-tx-data (let [existing (row @db/*conn*)]
                             (cond-> {::env (env-row)}
                               (some? existing) (assoc ::row existing))))]
      (if (empty? tx)
        {::synced? false}
        (let [{ok?   :seon.db/ok?
               error :seon.db/error} (await (db/transact! {:seon.db/tx-data tx}))]
          (if ok?
            (log/info-console! "seon.ai" "LLM config row seeded from env (DB owns it now)"
                               {:tx-ops (count tx)})
            (log/error-console! "seon.ai"
                                "LLM config env seed transact FAILED — adapters use the default config"
                                error))
          {::synced? (boolean ok?)})))
    (catch :default e
      (log/error-console! "seon.ai" "LLM config env sync threw" e)
      {::synced? false})))

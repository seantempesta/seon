(ns seon.ai
  "Shared LLM surface (fix-everything PRD C-18 + C-20) — the provider
   call settings are DATA, not compiled constants. A downstream
   deployment retunes the LLM (provider, model, thinking, budgets)
   without forking an adapter ns.

   One singleton row (identity `::id` = \"config\") carries up to
   ten attrs: `::provider`, `::model`, `::temperature`,
   `::max-tokens`, `::thinking`, `::timeout-ms`, `::base-url`,
   `::api-key-env`, `::dg-backend`, and `::extra-body-edn`. The execution
   boundary pulls ordinary config and agent maps from one immutable database
   coordinate and calls [[resolved-config-from-rows]]. Provider requests carry
   that `::config-resolution`; adapters never read a local database.

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
            [seon.db.protocol :as protocol]
            [seon.log :as log]
            [seon.schema :as schema]))

;; ============================================================
;; Shared schemas — the :seon.ai/* vocabulary both providers use.
;; (Moved here from the OpenAI-compatible adapter — the keywords'
;; namespace now matches the code ns that registers them.)
;; ============================================================

(schema/register! ::text :string)
(schema/register! ::model :string)
(schema/register! ::response-model [:string {:min 1}])
(schema/register! ::system-fingerprint [:string {:min 1}])
(schema/register! ::request-id [:string {:min 1}])
(schema/register! ::adapter
  [:enum :openai-compat :anthropic :diffusiongemma :typeahead :stub])
(schema/register! ::endpoint [:string {:min 1}])
(schema/register! ::temperature :double)
(schema/register! ::max-tokens :int)
(schema/register! ::system-prompt :string)
(schema/register! ::ctx :string)
(schema/register! ::usage :map)
(schema/register! ::msg :string)
(schema/register! ::endpoint-error [:map [::msg ::msg]])
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
(schema/register! ::evidence-error [:string {:min 1}])

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

(defn openai-sdk-base-url
  "The OpenAI SDK root derived from one configured endpoint."
  {:malli/schema [:=> [:catn [::base-url ::base-url]] ::base-url]}
  [url]
  (cond
    (str/ends-with? url "/chat/completions")
    (subs url 0 (- (count url) (count "/chat/completions")))

    (str/ends-with? url "/completions")
    (subs url 0 (- (count url) (count "/completions")))

    :else url))

(defn openai-request-endpoint
  "The redacted normalized chat-completions endpoint for evidence."
  {:malli/schema [:=> [:catn [::base-url ::base-url]
                             [:seon.config.model-transport/endpoint-cap
                              :seon.config.model-transport/endpoint-cap]]
                  [:or ::endpoint ::endpoint-error]]}
  [url endpoint-cap]
  (try
    (let [parsed (js/URL. url)
          path (.-pathname parsed)
          root-path
          (cond
            (str/ends-with? path "/chat/completions")
            (subs path 0 (- (count path) (count "/chat/completions")))

            (str/ends-with? path "/completions")
            (subs path 0 (- (count path) (count "/completions")))

            :else path)
          endpoint-path
          (str (str/replace root-path #"/+$" "") "/chat/completions")]
      ;; URL.origin intentionally excludes userinfo; query and fragment are
      ;; excluded by reconstructing from protocol/host/pathname only.
      (let [endpoint (str (.-protocol parsed) "//" (.-host parsed) endpoint-path)]
        (if (<= (count endpoint) endpoint-cap)
          endpoint
          {::msg "The normalized OpenAI endpoint exceeds the evidence bound."})))
    (catch :default _
      {::msg "The configured OpenAI endpoint is not a valid URL."})))

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
   [::evidence-error {:optional true} ::evidence-error]
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
;; {:enable_thinking false}}"); the resolver reads it back into the
;; map adapters merge. [[resolved-config-from-rows]] parses it once into the
;; authority resolution passed to the provider. ::tools / ::tool-choice stay
;; request-opt-only (inherently per-call; no persisted form yet).
(schema/register! ::extra-body-edn [:string {:min 1}])
(schema/register! ::extra-body-digest [:string {:min 64 :max 64}])
(schema/register! ::credential-class
  [:enum :configured-env :provider-default-env :conventional-env])
(schema/register! ::credential-source
  [:map
   [::credential-class ::credential-class]
   [::api-key-env ::api-key-env]])

;; The RESOLVED LLM config as a VALUE — what an agent runs
;; under. NEVER stored (owner correction 2026-07-04, derive-don't-store:
;; the per-turn `llm-*` stamping this shape once fed is deleted);
;; [[resolved-config-from-rows]] derives it from ordinary maps pulled at one
;; immutable database coordinate.
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
   [:seon.config.model-transport/response-identity-cap
    {:optional true} :seon.config.model-transport/response-identity-cap]
   [:seon.config.model-transport/endpoint-cap
    {:optional true} :seon.config.model-transport/endpoint-cap]
   [::extra-body-digest {:optional true} ::extra-body-digest]])

;; The SHIPPED per-provider defaults for the `::resolved-config` keys —
;; the LAST tier of the ONE resolution chain (request opt → agent
;; override → config row → THIS). Single source: the adapters read their
;; through [[resolved-config-from-rows]] — one map, zero drift.
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

(defn resolved-adapter
  "The provider adapter selected by one immutable resolved config."
  {:malli/schema [:=> [:cat ::resolved-config] ::adapter]}
  [config]
  (case (::provider config)
    :anthropic :anthropic
    :diffusiongemma (if (= :control (::dg-backend config))
                      :diffusiongemma
                      :openai-compat)
    :typeahead :typeahead
    :openai-compat))

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
;; SEON_AI_MAX_RETRIES env read (seon.agent.turn). The execution boundary
;; resolves explicit request opt → the agent's own config → the global row →
;; shipped defaults from ordinary maps pulled at one database coordinate.
;; What an agent resolves to is derived evidence, never a stored stamp.
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

(def ^:private config-pull-max-work 100000)
(def ^:private config-pull-max-results 256)
(def ^:private config-pull-max-result-weight (* 1024 1024))

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

(defn agent-config-pull-pattern
  "Pull pattern for one agent's ordinary LLM override values."
  {:malli/schema [:=> [:cat] [:vector :keyword]]}
  []
  (into [:seon.agent/id ::agent-max-retries] (keys agent-override-attrs)))

(defn- decode-agent-override
  [value]
  (if (string? value)
    (try (reader/read-string value)
         (catch :default _ value))
    value))

(defn- agent-row-override-values
  [agent]
  (reduce-kv
    (fn [m agent-attr global-attr]
      (let [v (some-> (get agent agent-attr) decode-agent-override)]
        (if (or (nil? v) (= :inherit v))
          m
          (assoc m global-attr v))))
    {}
    agent-override-attrs))

(defn- agent-row-max-retries
  [agent]
  (let [value (some-> (::agent-max-retries agent) decode-agent-override)]
    (when (int? value) value)))

(schema/register! ::agent-id [:string {:min 1}])

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
   [:seon.config.model-transport/response-identity-cap {:optional true} ::source]
   [:seon.config.model-transport/endpoint-cap {:optional true} ::source]
   [::extra-body-digest {:optional true} ::source]])
(schema/register! ::resolved-config-response
  [:map
   [::resolved-config ::resolved-config]
   [::provenance      ::provenance]
   [::agent-max-retries {:optional true} ::agent-max-retries]
   [::extra-body      {:optional true} ::extra-body]])
(schema/register! ::config-resolution ::resolved-config-response)
(schema/register! ::request
  [:map {:closed true}
   [::ctx ::ctx]
   [::system-prompt {:optional true} ::system-prompt]
   [::stream? {:optional true} ::stream?]
   [::abort-signal {:optional true} ::abort-signal]
   [::config-resolution ::config-resolution]])
(schema/register! ::config-evidence
  [:map
   [::resolved-config ::resolved-config]
   [::provenance ::provenance]
   [::credential-source {:optional true} ::credential-source]])

(defn- extra-body-digest
  "SHA-256 of the exact database-owned EDN bytes when they parse as a map."
  [raw]
  (let [parsed (try (reader/read-string raw) (catch :default _ nil))]
    (when (map? parsed)
      (-> (.createHash node-crypto "sha256")
          (.update raw "utf8")
          (.digest "hex")))))

(def ^:private model-transport-cap-attrs
  [:seon.config.model-transport/response-identity-cap
   :seon.config.model-transport/endpoint-cap])

(defn config-pull-pattern
  "Pull pattern for the ordinary database values that resolve LLM config."
  {:malli/schema [:=> [:cat] [:vector :keyword]]}
  []
  (into [::id] config-attrs))

(defn model-transport-pull-pattern
  "Pull pattern for model-transport limits on the cluster config row."
  {:malli/schema [:=> [:cat] [:vector :keyword]]}
  []
  (vec model-transport-cap-attrs))

(defn- resolve-config-values
  [row-cfg overrides transport-caps]
  (let [pick (fn [k defaults]
               (cond
                 (contains? overrides k) [(get overrides k) :agent-override]
                 (contains? row-cfg k)   [(get row-cfg k) :config-row]
                 (contains? defaults k)  [(get defaults k) :default]))
        [prov prov-src] (or (pick ::provider {}) [:deepseek :default])
        defaults (get shipped-defaults prov {})
        resolved
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
          ::base-url ::api-key-env ::dg-backend])
        resolved
        (reduce-kv
         (fn [acc attr [value source]]
           (-> acc
               (assoc-in [::resolved-config attr] value)
               (assoc-in [::provenance attr] source)))
         resolved
         transport-caps)]
    (if-let [[raw src] (pick ::extra-body-edn defaults)]
      (let [body (try (reader/read-string raw) (catch :default _ nil))]
        (if-let [digest (and (map? body) (extra-body-digest raw))]
          (-> resolved
              (assoc ::extra-body body)
              (assoc-in [::resolved-config ::extra-body-digest] digest)
              (assoc-in [::provenance ::extra-body-digest] src))
          resolved))
      resolved)))

(defn resolved-config-from-rows
  "Resolve effective LLM configuration from ordinary pulled maps.

   Both maps must come from the same immutable database coordinate. This is
   the process-independent form used by execution children and turn retries."
  {:malli/schema
   [:=> [:catn [::config-row ::row] [::agent-row :map]]
    ::resolved-config-response]}
  [config-row agent-row]
  (cond->
   (resolve-config-values
    (select-keys config-row config-attrs)
    (agent-row-override-values agent-row)
    (into {}
          (keep (fn [attr]
                  (when (contains? config-row attr)
                    [attr [(get config-row attr) :config-row]])))
          model-transport-cap-attrs))
    (some? (agent-row-max-retries agent-row))
    (assoc ::agent-max-retries (agent-row-max-retries agent-row))))

(defn bounded-evidence-error
  "Bound an evidence error using one resolved positive cap."
  {:malli/schema
   [:=> [:catn [::message ::evidence-error]
                 [:seon.config.model-transport/response-identity-cap
                  :seon.config.model-transport/response-identity-cap]]
    ::evidence-error]}
  [message response-identity-cap]
  (subs message 0 (min response-identity-cap (count message))))

(defn config-evidence
  "Bounded non-secret evidence for one resolved provider request."
  {:malli/schema
   [:function
    [:=> [:cat ::config-resolution] ::config-evidence]
    [:=> [:cat ::config-resolution ::credential-source] ::config-evidence]]}
  ([resolution]
   (select-keys resolution [::resolved-config ::provenance]))
  ([resolution credential-source]
   (assoc (config-evidence resolution)
          ::credential-source credential-source)))

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

;; ============================================================
;; Shared provider-boundary system-prompt resolution — the prompt compiler
;; supplies the cluster's frozen `:seon.config/system-text` value as the
;; request override; a direct adapter call without one uses the shipped
;; default. Both providers send the same ordinary string and never read the
;; database themselves.
;;
;; The system role message is NOT the soul and NOT any file: it is the
;; environment orientation + REPL doctrine the cluster runs under. The prompt
;; compiler resolves the cluster singleton at the turn's immutable database
;; value and passes the resulting system message here. SOUL.md / AGENTS.md are
;; file-loaded context sections (`seon.agent.ctx/file-block`) wired into the
;; manifest-declared context blocks; they ride the user-message context, not
;; here. There is no database or file read in this provider-boundary function.
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

   The request's explicit `:seon.ai/system-prompt` is the prompt compiler's
   frozen cluster value. A direct adapter call without that value uses the
   shipped default (`seon.agent.ctx/system-text`). This function is pure: it
   never reads the database or a file. The system message is not the soul;
   SOUL.md / AGENTS.md remain context sections."
  {:malli/schema [:=> [:cat ::prompt-request] ::system-prompt]}
  [{::keys [system-prompt]}]
  (or system-prompt ctx/system-text))

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
  "Seed the config row from the `SEON_AI_*` environment.

   Once, only when the row is unconfigured (see ns doc: env SEEDS, the DB
   OWNS). Awaited by `seon.client/start-runtime!` before boot readiness;
   idempotent — a boot with an already-configured row transacts nothing,
   so runtime switches persist. Failures log LOUDLY and resolve
   `{::synced? false}` — LLM config must never take the boot down."
  {:malli/schema [:=> [:cat] ::sync-response]}
  []
  (try
    (let [acquired
          (await
            (db/execute-many
              {::db/members
               [{::protocol/operation protocol/pull-operation
                 ::protocol/selector (into [::id] config-attrs)
                 ::protocol/entity-id [::id "config"]
                 :datahike.resource/max-work config-pull-max-work
                 :datahike.resource/max-results config-pull-max-results
                 :datahike.resource/max-result-weight
                 config-pull-max-result-weight}]}))
          member (first (::db/results acquired))
          _ (when (:seon.error/message acquired)
              (throw (ex-info "LLM config acquisition failed."
                              {:seon.db/error acquired
                               :seon.error/kind :core-bug})))
          _ (when-not (true? (::protocol/success? member))
              (throw (ex-info "LLM config acquisition failed."
                              {:seon.db/error member
                               :seon.error/kind :core-bug})))
          existing (some-> (::protocol/result member)
                           (select-keys config-attrs))
          tx (sync-tx-data (cond-> {::env (env-row)}
                             (some? existing) (assoc ::row existing)))]
      (if (empty? tx)
        {::synced? false}
        (let [{ok?   :seon.db/ok?
               error :seon.db/error}
              (await
                (db/transact!
                  {::db/tx-data tx
                   ::db/expected-db (::db/db acquired)}))]
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

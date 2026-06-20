(ns seon.ai
  "Shared LLM surface (fix-everything PRD C-18 + C-20) — the provider
   call settings are DATA, not compiled constants. A downstream
   deployment retunes the LLM (provider, model, thinking, budgets)
   without forking an adapter ns.

   One singleton row (identity `::id` = \"config\") carries up to
   eight attrs: `::provider`, `::model`, `::temperature`,
   `::max-tokens`, `::thinking`, `::timeout-ms`, `::base-url`,
   `::api-key-env`. Adapters ([[seon.ai.openai-compat]],
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
   provider-agnostic system-prompt resolution — both providers send
   the same store-resident soul."
  (:require [clojure.string :as str]
            [cljs.reader :as reader]
            [my.soul :as soul]
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

;; The errors-are-values envelope for LLM calls. Every failure mode
;; (timeout, fetch throw, HTTP non-2xx, unparseable body, refusal)
;; resolves to a response map carrying this under :seon.ai/error —
;; never a rejected Promise. Callers (seon.agent's turn loop) MUST
;; surface it.
(schema/register!
  ::error
  [:map
   [::msg        ::msg]
   [::status     {:optional true} ::status]
   [::timeout?   {:optional true} ::timeout?]
   [::transport? {:optional true} ::transport?]
   [::raw-body   {:optional true} ::raw-body]])

;; ============================================================
;; The config row — :seon.ai/config singleton (identity ::id "config").
;; ============================================================

(schema/register! ::id [:string {:seon.db/identity true}])  ; always "config"
;; :openai-compat = any OpenAI-compatible chat-completions gateway
;; (enterprise, bearer-keyed). Same wire path as :deepseek
;; (seon.ai.openai-compat) with endpoint + key resolved from ::base-url /
;; ::api-key-env instead of the shipped deepseek defaults.
(schema/register! ::provider [:enum :deepseek :anthropic :openai-compat])
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
   [::extra-body-edn {:optional true} ::extra-body-edn]])

(schema/register! ::synced? :boolean)
(schema/register! ::sync-request
  [:map
   [::row {:optional true} ::row]
   [::env ::row]])
(schema/register! ::sync-response [:map [::synced? ::synced?]])

;; The attr order is the sync + row-read iteration order.
(def ^:private config-attrs
  [::provider ::model ::temperature ::max-tokens ::thinking ::timeout-ms
   ::base-url ::api-key-env ::extra-body-edn])

;; ============================================================
;; Env reads — SEON_AI_*, parsed to the attr's concrete type.
;; ============================================================

(defn- env-val
  "process.env value for `var-name`, or nil when unset/blank (or when
   there is no Node process env at all)."
  [var-name]
  (let [v (some-> (.. js/globalThis -process) (.-env) (aget var-name))]
    (when (and (string? v) (not (str/blank? v))) v)))

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
   ::extra-body-edn ["SEON_AI_EXTRA_BODY" parse-extra-body-edn]})

(defn env-row
  "The LLM-config attrs present in the environment — `::row`-shaped,
   only the keys whose SEON_AI_* var is set, non-blank, and parseable.
   An unparseable value logs LOUDLY and is skipped."
  {:malli/schema [:=> [:cat] ::row]}
  []
  (reduce-kv
    (fn [m attr [var-name parse]]
      (if-let [raw (env-val var-name)]
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

(defn current
  "The config row's set attrs — `::row`-shaped, possibly {}. Adapters
   call this PER CALL (reactive-context — no cache) and apply their
   own defaults for absent keys: explicit request opt > config row >
   adapter default. 0-arity reads the ambient `seon.db/*conn*` and
   NEVER throws ({} on the conn-not-up boot edge); 1-arity takes an
   explicit db value."
  {:malli/schema [:function
                  [:=> [:cat] ::row]
                  [:=> [:catn [::db :seon.db/db-val]] ::row]]}
  ([] (or (try (some-> db/*conn* deref row)
               (catch :default _ nil))
          {}))
  ([db] (or (row db) {})))

(defn config-extra-body
  "The `:seon.ai/extra-body` map from the config row's `::extra-body-edn`
   (env SEON_AI_EXTRA_BODY) — the DATA-ONLY door for the agent turn loop,
   which builds the adapter with no request opts. `{}` when unset or
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
  "Parse a `::row` map's `::thinking` string to the value adapters
   consume: absent or \"false\" → false (off — the default),
   \"true\" → true, anything else → the string itself (a
   reasoning-effort level like \"high\"/\"max\")."
  {:malli/schema [:=> [:catn [::row ::row]] ::thinking-value]}
  [{::keys [thinking]}]
  (case thinking
    (nil "false") false
    "true"        true
    thinking))

(defn provider
  "The active LLM provider: the DB-owned config row's `::provider` (read
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

;; ============================================================
;; Shared system-prompt resolution — both providers send the same
;; store-resident soul.
;; ============================================================

(def fallback-system-prompt
  "Minimal boot-edge fallback ONLY — used when the store has no
   :my.soul rows yet (or the conn is not up). The REAL identity lives
   in the store as the :my.soul \"identity\" row, seeded at boot from
   the repo's SOUL.md and editable at runtime by transact (see
   my.soul); the universal REPL mechanics are hardcoded in the
   `<system>` block (seon.ctx/system-text), not here."
  (str "You are Seon, a bonded Clojure agent. Your entire output is "
       "read and evaluated as ClojureScript source — act by emitting "
       "forms, narrate with ; line comments, no markdown fences."))

(schema/register! ::prompt-request
  [:map [::system-prompt {:optional true} ::system-prompt]])

(defn effective-system-prompt
  "The system message content for a call: the request's explicit
   `:seon.ai/system-prompt` override when given, else the
   store-resident soul (`my.soul/system-prompt-text` — the
   priority-ordered :my.soul rows, seeded at boot from SOUL.md and
   runtime-editable by transact), else [[fallback-system-prompt]]
   (store empty or unavailable). Never throws."
  {:malli/schema [:=> [:cat ::prompt-request] ::system-prompt]}
  [{::keys [system-prompt]}]
  (or system-prompt
      (try (not-empty (soul/system-prompt-text))
           (catch :default _ nil))
      fallback-system-prompt))

;; ============================================================
;; Shared error logging — a failed LLM call is NEVER silent.
;; ============================================================

(schema/register! ::provider-label [:string {:min 1}])

(defn log-error!
  "ERROR-log an LLM failure with the live agent + turn identity (read
   from the ALS scopes seon.agent establishes around each turn) so a
   timed-out/failed call is NEVER silent in logs/pod.log.
   `provider-label` names the provider (\"DeepSeek\"/\"Anthropic\").
   Best-effort — never throws."
  {:malli/schema [:=> [:catn [::provider-label ::provider-label]
                       [::error ::error]]
                  :nil]}
  [provider-label error-map]
  (try
    (let [agent-id (db/current-agent-id)
          turn-id  (:seon.db/turn-id (db/current-tx-context))]
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
  "Tx-data SEEDING the config row from the environment, ONCE (pure —
   both inputs passed in). SEED-ONCE → THE DB OWNS:
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
  "SEED the config row on the ambient `seon.db/*conn*` from the SEON_AI_*
   env vars — once, only when the row is unconfigured (see ns doc: env
   SEEDS, the DB OWNS). Called from `seon.client/start-agent!` at boot;
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

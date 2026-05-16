---
type: research
status: active
tags: [research, agent, render]
---

# Spec-rewrite cluster: LLM + Render

Read-only research draft to feed into spec-02 §G (LLM access) and §E (ctx → system message render). Scope: LiteLLM operational shape, CLJC HTTP client choice, and ctx render ordering. All claims verified or marked `(unverified: ...)`.

## Findings

### Q1. LiteLLM proxy operational shape

- **Launch**: official image is `docker.litellm.ai/berriai/litellm:main-latest`. Mount config at `/app/config.yaml`, pass `--config /app/config.yaml`. Pip-installed `litellm[proxy]` CLI exists for non-Docker runs.
- **Port**: `4000` is the documented default (`http://0.0.0.0:4000`).
- **Config**: `config.yaml` has `model_list:` (each entry maps a client-facing `model_name` to a provider-specific `litellm_params: {model, api_key, api_base, ...}`, with `os.environ/FOO` interpolation for secrets) plus `general_settings:` and `litellm_settings:` blocks.
- **Health**: `/health` is unauthenticated and intended for readiness checks; `/health/liveliness` and `/health/readiness` also exist (unverified: which one is most appropriate for Integrant readiness — `/health/readiness` likely matches Integrant `:ready?` semantics).
- **Cost tracking**: native — every request through the proxy logs spend, prompt-tokens, completion-tokens, total-tokens, and dollar cost. Surfaced via `/spend/logs`, Prometheus metrics, or persisted to a DB if `database_url` is configured. **We do not need seon.llm middleware for cost** — the proxy is authoritative; seon.llm just records the per-request usage map the proxy returns.
- **Auth**: provider API keys live in `litellm_params.api_key` (typically `os.environ/...`). The proxy itself is gated by a `master_key` (env: `LITELLM_MASTER_KEY`, format `sk-...`). Clients send `Authorization: Bearer sk-...`. Virtual keys per-user/team can be minted via the master key.
- **Decision implied**: Integrant component `:seon.llm.proxy` that ProcessBuilder-spawns Docker (or local `litellm` CLI) is the clean shape — owns config-file path, master key, process handle, `/health/readiness` ping in `:ready?`. Defer to external container for production; spawn locally for `bin/start`.
- **Security caveat**: LiteLLM 1.82.7 / 1.82.8 were pulled (March 2026 supply-chain incident); pin to `>= 1.83.0`.

### Q2. CLJC HTTP client

Seon already depends on `hato/hato {:mvn/version "1.0.0"}` and uses it in `seon/src/seon/ai/gemini.clj` (a working OpenAI-style JSON POST with bearer auth). That settles JVM: **hato**. Sync-default returning a response map; async via `:async? true` → `CompletableFuture`. Body shape `(http/post url {:headers ... :body (json/generate-string body) :timeout T :as :text})`. Match the gemini.clj pattern verbatim.

CLJS choice is between `cljs-http` (core.async channel return — pulls in core.async at the pod boundary; awkward to `deref`), `lambdaisland/fetch` (returns a JS Promise directly; thinnest wrapper over `fetch`), and raw `js/fetch`. **Recommend `lambdaisland/fetch`** — pod is already a JS environment, promise is native, return-shape (Ring-ish map) is closest to hato's, and we avoid forcing core.async into the pod's transport seam. Both legs return something derefable via `@` on JVM and `(.then …)` / `js/await`-compatible promise in CLJS; we expose a single `manifold.deferred`-style or `js/Promise`-on-JVM-via-`completable-future` abstraction. The minimal-cruft path:

```clojure
(ns seon.llm
  "Cross-runtime LLM client. Thin wrapper over LiteLLM-proxy OpenAI-compat
   API. Provider/model swap is the :model string."
  #?(:clj  (:require [hato.client :as http]
                     [cheshire.core :as json])
     :cljs (:require [lambdaisland.fetch :as fetch])))

(def ^:dynamic *proxy-url* "http://localhost:4000")
(def ^:dynamic *master-key* nil) ;; set from env at boot

(defn- post-json
  "Platform-specific POST. Returns a promise/future resolving to {:status :body}."
  [url body headers]
  #?(:clj  (http/post url {:headers     headers
                           :body        (json/generate-string body)
                           :content-type :json
                           :as          :string
                           :async?      true
                           :timeout     60000})
     :cljs (fetch/post url {:body         body
                            :headers      headers
                            :content-type :json
                            :accept       :json})))

(defn- parse-response
  [{:keys [status body] :as resp}]
  (let [parsed #?(:clj  (json/parse-string body true)
                  :cljs body)] ;; lambdaisland/fetch auto-decodes JSON
    (if (= 200 status)
      {::ok?         true
       ::text        (get-in parsed [:choices 0 :message :content])
       ::tool-uses   (get-in parsed [:choices 0 :message :tool_calls])
       ::stop-reason (keyword (get-in parsed [:choices 0 :finish_reason]))
       ::usage       (:usage parsed)
       ::model       (:model parsed)
       ::raw         parsed}
      {::ok? false ::status status ::body parsed})))

(defn complete
  "POST /v1/chat/completions on the LiteLLM proxy.
   Returns a derefable (CompletableFuture on JVM, Promise on CLJS)."
  {:malli/schema [:=> [:cat ::complete-request] ::complete-response]}
  [{:keys [messages model max-tokens tools] :as req
    :or   {model "openrouter/auto" max-tokens 4096}}]
  (let [body    (cond-> {:messages   messages
                         :model      model
                         :max_tokens max-tokens}
                  tools (assoc :tools tools))
        headers (cond-> {"content-type" "application/json"}
                  *master-key* (assoc "authorization" (str "Bearer " *master-key*)))
        url     (str *proxy-url* "/v1/chat/completions")]
    #?(:clj  (-> (post-json url body headers)
                 (.thenApply (reify java.util.function.Function
                               (apply [_ resp] (parse-response resp)))))
       :cljs (.then (post-json url body headers) parse-response))))
```

The `#?(:clj :cljs)` reader-conditional surface is small — three sites: namespace require, the POST call, the JSON-parse step. Everything else (request build, response normalization, malli schema) is shared.

### Q3. Render priority + system-message ordering

Three candidate ordering schemes:

1. **ctx-vector order** — whatever order the agent put entries in is what the LLM sees. Agent has direct, transactable control.
2. **Schema-registered `:seon.render/priority`** — int per key; lower = earlier. Stable across rebuilds; opaque to the agent unless surfaced.
3. **Hidden schema-registration order** — order of `register!` calls. Brittle, opaque, rejected.

**Recommend a hybrid: explicit-priority-with-fallback.** Read a `:seon.render/priority` int from the entity schema's properties (registered next to `:seon.render/ai`, per Path D). Keys without one default to `:seon.render/priority 500`. Within a priority bucket, ctx-vector order wins. This gives:

- A stable cache-friendly prefix (instructions / schema docs / fn library set low priority, e.g. 100/200/300).
- Dynamic tail-content (notes, working-on, recent-messages, current task) sits at default 500+.
- Agent can re-order intra-bucket by re-transacting their ctx vector (cheap, no schema change).
- Agent can override a key's priority by transacting an entity-scoped schema property (the Path D / Malli-default mechanism already in flight).

**Cache thresholds (verified)**:

- **Anthropic**: minimum cacheable prefix = 1024 tokens for Claude 3.x and Opus; 2048 for Sonnet 4.x; 4096 for Haiku 4.5. Up to 4 `cache_control` breakpoints per prompt. Default TTL 5 min, optional 1 hour. Cache hits cost 10% of base; writes cost 125%.
- **OpenAI**: automatic at 1024+ tokens, hits in 128-token increments, exact-prefix match. `prompt_cache_key` parameter influences routing for better hit rate.

Both providers reward *stable content first, dynamic content last* — which is exactly what priority-then-vector-order produces. Tools and system message come before messages in Anthropic's cache key (tools, system, messages) so anything in the system message ordering matters relative to itself and to user/assistant turns that follow.

Code sketch:

```clojure
(ns seon.agent.flow
  (:require [seon.schema :as schema]
            [seon.agent.ctx :as ctx]))

(def ^:private DEFAULT-PRIORITY 500)

(defn- priority-of
  "Look up :seon.render/priority on the entity schema for k. Falls back to
   DEFAULT-PRIORITY if unregistered or absent."
  [k]
  (or (get-in (schema/schema-definition k) [1 :seon.render/priority])
      DEFAULT-PRIORITY))

(defn render-system-message
  "Walk the built ctx, render each value to text, concat into a system message.
   Ordering: stable by :seon.render/priority (asc), then by ctx-vector order
   for ties. ctx-vector order is preserved through the (reduce conj [] ...)
   in build-ctx so we can use map-indexed for tie-break.
   Resolves :seon.render/ai per Path D — symbol → requiring-resolve → call,
   string → identity, nil/missing → fallback `(pr-str v)`."
  [ctx-map indexed-keys]
  (->> indexed-keys
       (map-indexed (fn [idx k]
                      [(priority-of k) idx k (get ctx-map k)]))
       (sort-by (juxt first second))
       (map (fn [[_ _ k v]] (ctx/render-key-for-ai k v ctx-map)))
       (remove nil?)
       (clojure.string/join "\n\n")))
```

`indexed-keys` is the seq of keys in ctx-vector order, threaded in from `build-ctx`. Sort is by `[priority, original-index]`, then we drop both and emit. Rendered output is concatenated with `\n\n` between sections — header markers (`[INSTRUCTIONS]`, `[NOTES]`, …) come from each render fn, not the assembler. `nil` renderer output is filtered (so an agent can effectively suppress a key by writing a render fn that returns nil for a given state).

(unverified: whether `seon.schema/schema-definition` returns the raw `[:type props ...]` triple or unwraps it — gemini.clj-style RE-PL probing will confirm. The destructure `(get-in … [1 :seon.render/priority])` assumes Malli `[:string {…}]` shape and is the same access pattern seon already uses elsewhere.)

## Draft spec section — "LLM access & ctx rendering"

> Replaces `spec-02 §G` and tightens `spec-02 §E.5 → render-system-message` after the renderer-redesign Path D resolution and the LiteLLM operational shape.

### G.1 LiteLLM proxy as an Integrant component

LiteLLM proxy runs as a sidecar process: a Python service exposing an OpenAI-compatible HTTP API on `localhost:4000` (its documented default). Both JVM master and CLJS pod call it via plain HTTP. The proxy is the authoritative cost-tracker — it logs per-request spend, prompt-tokens, completion-tokens, total-tokens, and dollar cost into its own store (or a Postgres URL we hand it). `seon.llm` does not re-implement cost tracking; it surfaces the `usage` map the proxy returns.

Two deployment shapes:

1. **Local dev / `bin/start`** — Integrant component `:seon.llm/proxy` ProcessBuilder-spawns `docker run -v $(pwd)/litellm-config.yaml:/app/config.yaml -e LITELLM_MASTER_KEY=... -p 4000:4000 docker.litellm.ai/berriai/litellm:main-latest --config /app/config.yaml`. `:ready?` polls `GET http://localhost:4000/health/readiness` until 200. `:halt!` SIGTERMs the child.
2. **Production / external** — same Docker image, run as a sibling container by the deployment substrate. The Integrant component degrades to a config record (URL + master-key), no spawn.

Pin LiteLLM to `>= 1.83.0` (versions 1.82.7 and 1.82.8 were pulled for a supply-chain incident in March 2026).

Config (`litellm-config.yaml`) declares `model_list:` mapping client-facing model names (e.g., `openrouter/auto`, `anthropic/claude-opus-4`) to provider configs, with provider API keys via `os.environ/<NAME>` interpolation. The proxy is gated by `LITELLM_MASTER_KEY` (env, format `sk-...`). seon.llm sends it as `Authorization: Bearer <master-key>`. Per-user virtual keys are a follow-on (Phase U surface).

### G.2 `seon.llm` — thin CLJC wrapper

```clojure
(ns seon.llm
  "Cross-runtime LLM client. POSTs to a LiteLLM-proxy OpenAI-compat endpoint.
   Provider/model swap is the :model string."
  #?(:clj  (:require [hato.client :as http]
                     [cheshire.core :as json])
     :cljs (:require [lambdaisland.fetch :as fetch])))
```

JVM uses `hato` (already on seon's deps; gemini.clj is the reference pattern). CLJS uses `lambdaisland/fetch` — JS Promise return matches the pod's native async substrate without dragging core.async into the transport seam. Both legs return a derefable; the public API is `(seon.llm/complete request-map)` → promise of `{::ok? ::text ::tool-uses ::stop-reason ::usage ::model ::raw}`. The body is small — three reader-conditional sites (namespace require, POST call, JSON parse). Schemas (`::complete-request`, `::complete-response`) are CLJC and apply via `:malli/schema` instrumentation on the JVM, schema-validation only on CLJS.

`:seon.llm/proxy-url` and `:seon.llm/master-key` are bound from the Integrant component's config at runtime; seon.llm reads them via `^:dynamic` vars so test code can rebind to a local mock.

### G.3 ctx render — order, priority, prefix-cache discipline

The agent's ctx is a vector of `[key value-or-symbol]` pairs (per spec-02 §E.1). `build-ctx` reduces the vector into a map with symbol-resolution per Path D — symbols pointing at `:seon.fn` entities get `requiring-resolve`'d (JVM) or compiled+invoked (CLJS pod) on the in-flight `acc`. `render-system-message` then walks the built map and produces the system message string.

**Ordering rule**: stable by `:seon.render/priority` (ascending int from the entity schema's properties; default `500`), with ctx-vector order as the tie-breaker. This is prefix-cache friendly:

- Static spine (instructions=100, schema-summary=200, fn-library=300) sits at the front of every system message.
- Dynamic tail (notes, working-on, recent-messages) sits at default 500+ and may shift each tick without invalidating the cached prefix.
- Agent control: re-transacting the ctx vector reorders intra-bucket. Setting `:seon.render/priority` on a schema (via the same registration mechanism as `:seon.render/ai`) reorders across buckets.

**Cache thresholds** (verified May 2026): Anthropic caches at 1024+ tokens for Claude 3.x / Opus, 2048+ for Sonnet 4.x, 4096+ for Haiku 4.5; supports 4 explicit `cache_control` breakpoints; 5-min default TTL (1-hour optional). OpenAI caches automatically at 1024+ tokens in 128-token increments via exact-prefix match. **Both reward stable-content-first**, which is what `[priority, vector-index]` ordering produces by construction.

`render-system-message` implementation (drops in to `seon.agent.flow`):

```clojure
(defn render-system-message
  [ctx-map ctx-vec]
  (let [indexed (map-indexed (fn [i [k _]] [(priority-of k) i k]) ctx-vec)]
    (->> indexed
         (sort-by (juxt first second))
         (map (fn [[_ _ k]] (ctx/render-key-for-ai k (get ctx-map k) ctx-map)))
         (remove nil?)
         (clojure.string/join "\n\n"))))
```

`ctx/render-key-for-ai` (already in renderer-redesign Path D) handles per-key dispatch: read `:seon.render/ai` from the value's schema, resolve symbol→fn if present, call on the entity, fall back to `pr-str` otherwise. Per-key render fns own their own section markers (e.g. `"[INSTRUCTIONS]\n..."`); the assembler only joins with blank lines.

(unverified: exact shape returned by `seon.schema/schema-definition` for a registered key — the priority lookup currently assumes Malli `[:type {props}]` form. Confirm at REPL before merging.)

---

## Top-3 implications for spec-02

1. **§G needs an Integrant component spec for `:seon.llm/proxy`** — not just "runs as a sidecar." Owns: process lifecycle, `/health/readiness` poll for `:ready?`, master-key from env, config file path. Two modes: spawn-Docker (local) vs config-record (external).
2. **`seon.llm.cljc` HTTP picks are decidable now**: `hato` on JVM (already in deps, gemini.clj is the reference), `lambdaisland/fetch` on CLJS (Promise-native, avoids core.async at the pod transport seam). The `#?(:clj :cljs)` surface is three sites.
3. **Render order should be `[priority, vector-index]`, not pure schema-registration order.** Priority lives next to `:seon.render/ai` in the entity schema (Path D mechanism, no new registration surface). Stable spine first, dynamic tail last — matches Anthropic ≥1024 / OpenAI ≥1024 cache thresholds and gives the agent transactable control of order without schema rewrites.

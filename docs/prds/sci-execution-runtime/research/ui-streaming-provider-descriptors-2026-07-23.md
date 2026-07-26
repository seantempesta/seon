---
type: research
status: active
tags: [research, agent, ui, runtime]
---

# UI streaming with multi-form batch + provider descriptors as data (2026-07-23)

Read-only review complete on `codex/runtime-reliability-refactor`. No files, builds, tests, or REPL sessions were touched.

## Decision summary

- Preserve multi-form batch by separating wire streaming from reply evaluation. Both consumers must read to natural EOF/`[DONE]`; partial publication is presentation-only and cannot affect parsing, evaluation, cancellation, retries, usage, or final persistence.
- For the current deployment, prefer coalesced database publication using one cardinality-one, unindexed, no-history attempt attribute. It reuses the one database/reactive/feed mechanism and gives correct reconnect and terminal atomicity.
- Switch the architectural choice to the policy-fenced ephemeral channel when expected simultaneous streaming makes partial transactions consume material writer headroom—roughly high tens of streams at 2–3 publications/s, and clearly by 100 streams against the measured ~300 tx/s ceiling.
- Provider descriptors belong as component rows under the existing cluster config singleton. Hosted providers select one of two fixed wire cores, `:openai-compat` or `:anthropic`; provider identity never appears in dispatch code.
- Kimi, Z.AI, OpenRouter, and DeepSeek fit descriptor rows. LiteLLM’s native Gemini implementation does not: it requires native request/response transforms and JSONL streaming. Gemini is row-only only if Google’s separate OpenAI-compatible surface passes qualification.

## Part A — UI streaming with complete multi-form batch

## A0. Shared transport contract

The current code conflates wire streaming with first-form evaluation:

- The CLJS SDK consumer aborts once its reader recognizes one form: [openai_compat.cljs:290](/Users/sean/src/seon/src/seon/ai/openai_compat.cljs:290).
- The JVM SSE fold installs the same first-form abort predicate: [openai_compat/core.cljc:186](/Users/sean/src/seon/src/seon/ai/openai_compat/core.cljc:186), [http.clj:106](/Users/sean/src/seon/src/seon/ai/http.clj:106).
- A stale `reply-program` streaming branch discards subsequent forms: [turn/core.cljc:150](/Users/sean/src/seon/src/seon/agent/turn/core.cljc:150).

Replace that coupling with two explicit internal facts:

```clojure
{:seon.ai/wire-stream? true
 :seon.ai/reply-evaluation :batch}
```

Required behavior:

1. Both consumers fold every content delta through natural completion.
2. Each content update invokes an optional non-blocking presentation sink with the newest cumulative text.
3. Sink failure or backpressure drops presentation updates only; it cannot fail or slow the provider fold.
4. A terminal choices-empty usage chunk is retained.
5. The final accumulated text is parsed once as the complete program and all forms are evaluated through the existing batch path.
6. Genuine operator cancellation, timeout, interruption, or response-byte failure still closes the upstream transport. Only those exceptional endings estimate usage.
7. No partial form is evaluated.

The OpenAI core already requests `stream_options.include_usage` and its fold already tolerates usage-only chunks: [openai_compat/core.cljc:16](/Users/sean/src/seon/src/seon/ai/openai_compat/core.cljc:16), [openai_compat/core.cljc:171](/Users/sean/src/seon/src/seon/ai/openai_compat/core.cljc:171). The implementation change is therefore to remove first-form termination, expose the progress sink, and delete the first-form semantic branch—not to invent a new parser.

## A1. Candidate 1: coalesced database publication

### Exact data shape

Add one optional attribute to the existing attempt entity:

```clojure
:seon.ai.attempt/partial-text
[:string {:seon.db/no-history? true}]
```

Contract:

- Cardinality one.
- Unindexed.
- Value is the complete UTF-8 prefix, not a delta.
- Present only while the attempt is open.
- Absence means no partial text; never store `nil`.
- No separate stream entity, status, timestamp, sequence, or partial blob.
- Existing attempt identity, claim epoch, and transaction metadata provide identity and provenance.

Do not use a blob ref for each prefix. Content addressing would produce a distinct immutable blob for every cumulative value, retaining approximately quadratic prefix bytes and requiring a new orphan-cleanup story. The direct string is the least-bad database representation.

### History and physical cost

The fork supports per-attribute history suppression:

- Predicate: [utils.cljc:48](/Users/sean/src/seon/reference-code/datahike/src/datahike/db/utils.cljc:48).
- Cardinality-one transaction behavior: [transaction.cljc:429](/Users/sean/src/seon/reference-code/datahike/src/datahike/db/transaction.cljc:429).
- Datahike regression: [time_variance_test.cljc:217](/Users/sean/src/seon/reference-code/datahike/test/datahike/test/time_variance_test.cljc:217).

Seon’s backend enables global history at [backend.clj:123](/Users/sean/src/seon/src/seon/db/backend.clj:123), but the schema bridge currently translates only identity, unique, index, and component properties: [schema.clj:114](/Users/sean/src/seon/src/seon/db/datahike/schema.clj:114). Therefore `:seon.db/no-history? → :db/noHistory` is a prerequisite.

With `noHistory`:

- Superseded partials do not enter temporal EAVT/AEVT.
- The attribute must remain unindexed, avoiding temporal/current AVET work.
- Terminal retraction leaves no queryable partial in current state or history.

It does not eliminate:

- the transaction and commit;
- primary EAVT/AEVT path changes;
- transaction-report bytes;
- immutable storage nodes awaiting GC;
- cumulative-prefix copying.

If `F` snapshots are published for a final response of `B` bytes, text traffic remains approximately `F × B / 2`. At 150 evenly growing snapshots, that is roughly 75 final-response equivalents.

### Coalescer and R27 configuration

Use one process-local, single-slot latest-wins coalescer per attempt:

```clojure
:seon.config.model-stream/partial-publish-settle-ms
```

The configured cadence should initially target 2–3 transactions/s per active stream, but its committed default needs measured calibration provenance. It is scheduling policy, not an R27 circuit breaker.

Any maximum partial/request frame size is a true R27 limit: database fact, unit, docstring, ≥100× measured legitimate P99.9 where feasible, and loud failure. Never silently truncate the reply.

Each publication must:

1. use the newest cumulative prefix;
2. verify the attempt remains open;
3. verify the current claim/epoch;
4. assert the single partial attribute;
5. make stale timers or prior processes holding runs fail as values.

### Terminal cleanup

The terminal transaction must atomically:

- retract `:seon.ai.attempt/partial-text`;
- transition the attempt from open to terminal;
- link the existing final reply blob;
- advance the turn phase.

Crash/open-attempt recovery must retract it as part of recording the crash outcome. After terminal commit acknowledgement, cancel the coalescer. A pending late flush is still rejected by the open-attempt/claim fence.

This makes it impossible for a page to observe “terminal attempt plus stale partial.”

### Reactive interest and reconnect

The renderer reads `:seon.ai.attempt/partial-text` only for the current open attempt. Normal read-evidence capture then installs the interest through [reactive.cljs:327](/Users/sean/src/seon/src/seon/reactive.cljs:327); the writer starts delivery from changed attributes at [writer.clj:2858](/Users/sean/src/seon/src/seon/db/writer.clj:2858).

The implementation must prove that the installed dependency retains the attempt or agent constraint. If it degenerates to attribute-only interest, all pages that read this attribute may recompute on every agent’s stream even though equality suppression later avoids a morph.

Reconnect naturally repaints the current prefix. After terminal cleanup it paints the final blob. Historical feeds must ignore this attribute because no-history partials are intentionally non-replayable.

### Datastar morph

Render a stable child such as:

```html
<div id="agent-<agent-id>-reply-progress"
     class="reply-progress">escaped partial text</div>
```

Render partials as escaped plain text with `white-space: pre-wrap`; incomplete Markdown is unstable and expensive.

The current feed patches the whole `#app-view`: [datastar.cljs:125](/Users/sean/src/seon/src/seon/web/datastar.cljs:125). That is functionally sufficient because Idiomorph can preserve stable descendants, though it still pays whole-view render and serialization cost. Measure before adding a narrower database render unit.

Do not use Datastar signals. Signals are client/form state; the partial reply is server-owned output.

## A2. Candidate 2: policy-fenced ephemeral channel

The valid topology is:

```text
run-holding process
  → private typed web-render ingress
  → existing agent feed/socket
  → child-element Datastar patch
```

There must not be a second browser EventSource.

Envelope:

```clojure
{:seon.ai.stream/agent-id ...
 :seon.ai.stream/run-id ...
 :seon.ai.stream/turn-id ...
 :seon.ai.stream/attempt-id ...
 :seon.ai.stream/claim-epoch ...
 :seon.ai.stream/sequence ...
 :seon.ai.stream/cumulative-text ...}
```

The web-render process keeps only the newest snapshot for a live feed/attempt. The preferred ownership is an extension of the existing feed registry, not a standalone atom-based subsystem.

Mandatory fences:

- live presentation only;
- accepted only when the database says the same attempt and epoch are open;
- bounded and latest-wins;
- never visible to agents, prompts, evaluation, retry, receipts, observability, reproduction, authorization, or historical feeds;
- never written to the database or blob storage;
- loss changes only animation;
- the terminal database transaction remains the only final truth;
- removing the entire channel cannot change any semantic result.

This is the same sort of deliberately isolated dynamic tail as readline/host telemetry, whose policy boundary is documented at [render-ctx-portability-research:64](/Users/sean/src/seon/docs/prds/sci-execution-runtime/research/render-ctx-portability-research-2026-07-23.md:64).

### Reconnect behavior

- Reconnecting to the same live web-render process may receive its newest cached prefix.
- After web-render restart or cache loss, canonical database state paints “generating” without replayed text.
- The next run-holding process snapshot restores progress.
- If the run-holding process finishes while disconnected, the terminal database transition paints the final reply.
- Last consumer disconnect drops the cache.
- Historical/as-of feeds never include it.

That lossiness is part of the policy, not a bug. If partial text must survive process restart, it is no longer ephemeral and the database candidate wins.

### Canonical-event priority

The current socket path is raw one-slot latest-wins: [datastar.cljs:279](/Users/sean/src/seon/src/seon/web/datastar.cljs:279). An ephemeral child patch must never replace a pending canonical terminal morph.

Make pending items structured:

- canonical database morph replaces any pending partial;
- newer partial replaces older partial;
- partial cannot replace pending canonical state;
- terminal canonical state installs an attempt tombstone rejecting late sequences.

Without this priority rule, the side channel is incorrect.

### Morph granularity

For this candidate, use a child-only outer-element patch on the existing SSE socket. The canonical database render includes the stable progress placeholder; the ephemeral layer fills only that element.

A whole-app canonical morph must either include the newest ephemeral overlay or be immediately followed by its current child patch, otherwise unrelated database updates would erase visible progress. Keep that overlay in the web serialization tail, outside pure `seon.render` bytes and reactive read evidence.

## Recommendation and cutoff

Use the database candidate now, conditional on the no-history bridge facet and physical-growth probe.

It preserves one database-derived UI mechanism, current reconnect semantics, R26 ownership, selective interests, and terminal atomicity. The side channel adds a new run-holding process→web-render protocol and a priority-aware overlay path that the current JVM agent feed does not yet provide.

Choose ephemeral at the deployment architecture level—not dynamically—when either condition holds:

- partial text is explicitly presentation-only and may be lost on web-render restart; and
- measured concurrent streaming would materially consume writer headroom or worsen terminal transaction/delivery P99.

At 2–3 partial transactions/s:

| Simultaneous streams | Added tx/s |
|---:|---:|
| 16 | 32–48 |
| 64 | 128–192 |
| 100 | 200–300 |

Against the researched ~300 tx/s pipelined ceiling, 100 simultaneous streams clearly requires ephemeral delivery. Low tens favor the database mechanism, subject to load evidence.

## Part B — provider descriptors as registered data

## B1. Physical schema and ownership

Attach descriptor rows to `[:seon.config/id "cluster"]` through a component-many connection, following the existing model-variant mechanism at [resolve.cljc:931](/Users/sean/src/seon/src/seon/config/resolve.cljc:931):

```clojure
:seon.config/provider-descriptors
;; component-many refs
```

Descriptor attributes:

```clojure
{:seon.ai.provider/id                         :keyword ; unique identity
 :seon.ai.provider/adapter-core               #{:openai-compat :anthropic}
 :seon.ai.provider/locality                   #{:frontier :local-worker}

 :seon.ai.provider/default-base-url           :string
 :seon.ai.provider/endpoint-policy            #{:openai-chat-completions
                                                :anthropic-messages}

 :seon.ai.provider/credential-header          :string
 :seon.ai.provider/credential-prefix          :string
 :seon.ai.provider/default-api-key-env        :string

 :seon.ai.provider/retry-after-header         :string
 :seon.ai.provider/retry-after-format         #{:delta-seconds-or-http-date}

 :seon.ai.provider/default-model              :string
 :seon.ai.provider/default-temperature        :double
 :seon.ai.provider/default-max-tokens         :int
 :seon.ai.provider/default-thinking           :string

 :seon.ai.provider/completion-limit-field     :seon.ai/completion-limit-field
 :seon.ai.provider/thinking-policy            #{:omit
                                                :openai-reasoning-effort
                                                :deepseek-thinking-toggle
                                                :anthropic-adaptive}
 :seon.ai.provider/stream-options-policy      #{:none
                                                :openai-include-usage
                                                :anthropic-native-events}

 :seon.ai.provider/streaming-advertised?      :boolean
 :seon.ai.provider/streaming-actually-works?  :boolean
 :seon.ai.provider/usage-in-stream?           :boolean
 :seon.ai.provider/function-calling?          :boolean
 :seon.ai.provider/response-format?           :boolean

 :seon.ai.provider/allowed-tool-choices       #{:auto :none :required}
 :seon.ai.provider/quirks                     #{...}

 :seon.ai.provider.usage/input-field          :keyword
 :seon.ai.provider.usage/output-field         :keyword
 :seon.ai.provider.usage/total-field          :keyword
 :seon.ai.provider.usage/cached-direct-fields #{:keyword}
 :seon.ai.provider.usage/cached-parent-field  :keyword
 :seon.ai.provider.usage/cached-nested-field  :keyword}
```

Optional public static headers, if required, should be registered child rows with `name` and `value`; do not serialize a second arbitrary header map. Reject credential-bearing names. API-key values remain process environment inputs.

`streaming-advertised?` records code/provider claims. `streaming-actually-works?` is asserted only after a Seon live qualification that proves multi-form completion, terminal usage, byte bounds, timeout/cancellation, and error translation. Runtime streaming admission requires the latter.

Config/provider selections should become refs to `:seon.ai.provider/id`; the frozen resolution may expose the keyword ID for transport metadata.

## B2. Resolution and dispatch

The pure resolver should:

1. Pull the agent overlay, cluster singleton, model variants, and descriptor children from one immutable database value.
2. Resolve provider selection through agent override → cluster selection → configured default.
3. Resolve exactly one descriptor. Missing or duplicate rows return a configuration error value.
4. Resolve model fields through agent/variant → active config → descriptor defaults.
5. Embed the resolved descriptor plus descriptor digest/provenance in both primary and fallback resolutions.
6. Dispatch only on `:seon.ai.provider/adapter-core`.
7. Let the selected portable core interpret endpoint, auth, completion-limit, thinking, stream-options, usage paths, and capability data.

Adding a compatible provider then requires a descriptor row, catalog entry, and qualification evidence—never a provider branch.

This replaces:

- the compiled provider map/enum: [provider.cljc:11](/Users/sean/src/seon/src/seon/ai/provider.cljc:11);
- shipped provider cases and credentials: [ai/core.cljc:34](/Users/sean/src/seon/src/seon/ai/core.cljc:34), [ai/core.cljc:112](/Users/sean/src/seon/src/seon/ai/core.cljc:112);
- the global function registry atom: [dispatch.cljs:14](/Users/sean/src/seon/src/seon/ai/dispatch.cljs:14);
- JVM provider dispatch arms: [http.clj:211](/Users/sean/src/seon/src/seon/ai/http.clj:211).

Extra-body remains in the existing resolver:

```text
descriptor defaults
→ modeled core request fields
→ resolved config/agent extra-body
→ explicit operation extra-body
```

The JVM already implements config-then-operation precedence at [openai_compat/core.cljc:207](/Users/sean/src/seon/src/seon/ai/openai_compat/core.cljc:207). The Node leaf currently chooses one extra-body map wholesale at [openai_compat.cljs:180](/Users/sean/src/seon/src/seon/ai/openai_compat.cljs:180); descriptor work must make the two leaves byte-equivalent.

Fallback selection independently resolves its provider descriptor through the existing `:seon.ai/fallback-config-resolution`: [ai/core.cljc:332](/Users/sean/src/seon/src/seon/ai/core.cljc:332). The current durable phase does not consume that fallback; wiring it is separate parity work, not justification for another registry.

## B3. Provider rows mined from LiteLLM

| Provider | Descriptor behavior | Source and qualification |
|---|---|---|
| DeepSeek | Base `https://api.deepseek.com`; append `/chat/completions`; Bearer auth; `max_tokens`; DeepSeek thinking toggle; `stream_options.include_usage`; OpenAI SSE/tools/response format; cached-token sources `prompt_cache_hit_tokens`, `cached_tokens`, or `prompt_tokens_details.cached_tokens`. | Endpoint [deepseek.clj:8](/Users/sean/src/seon/reference-code/litellm-clj/src/litellm/providers/deepseek.clj:8), request fields [deepseek.clj:55](/Users/sean/src/seon/reference-code/litellm-clj/src/litellm/providers/deepseek.clj:55), streaming/tools [deepseek.clj:102](/Users/sean/src/seon/reference-code/litellm-clj/src/litellm/providers/deepseek.clj:102), cached paths [openai_compatible.clj:164](/Users/sean/src/seon/reference-code/litellm-clj/src/litellm/providers/openai_compatible.clj:164). No new wire core. |
| Kimi | Base `https://api.moonshot.ai/v1`; append `/chat/completions`; Bearer auth; `max_completion_tokens`; response format and stream-options passthrough; prompt-cache/safety fields remain extra-body; tools supported. | Endpoint [kimi.clj:9](/Users/sean/src/seon/reference-code/litellm-clj/src/litellm/providers/kimi.clj:9), fields [kimi.clj:76](/Users/sean/src/seon/reference-code/litellm-clj/src/litellm/providers/kimi.clj:76), stream/tools [kimi.clj:119](/Users/sean/src/seon/reference-code/litellm-clj/src/litellm/providers/kimi.clj:119). K2.7 “thinking cannot be disabled” is model-specific [kimi.clj:45](/Users/sean/src/seon/reference-code/litellm-clj/src/litellm/providers/kimi.clj:45); keep it in model/catalog data, not a provider branch. No new wire core. |
| Z.AI | Base `https://api.z.ai/api/paas/v4`; append `/chat/completions`; Bearer auth; `max_tokens`; reasoning/thinking fields; include-usage stream options; auto-only tool choice. `do_sample`, `tool_stream`, and `thinking.clear_thinking` remain extra-body until a maintained caller needs modeled fields. | Endpoint [zai.clj:9](/Users/sean/src/seon/reference-code/litellm-clj/src/litellm/providers/zai.clj:9), fields [zai.clj:52](/Users/sean/src/seon/reference-code/litellm-clj/src/litellm/providers/zai.clj:52), auto-only tools [zai.clj:80](/Users/sean/src/seon/reference-code/litellm-clj/src/litellm/providers/zai.clj:80), streaming [zai.clj:114](/Users/sean/src/seon/reference-code/litellm-clj/src/litellm/providers/zai.clj:114). No new wire core. |
| OpenRouter | Base `https://openrouter.ai/api/v1`; append `/chat/completions`; Bearer auth; OpenAI messages/tools. Optional `HTTP-Referer`/`X-Title` are public config/header rows, not copied LiteLLM branding. | Endpoint/auth/headers [openrouter.clj:150](/Users/sean/src/seon/reference-code/litellm-clj/src/litellm/providers/openrouter.clj:150), declared streaming/tools [openrouter.clj:185](/Users/sean/src/seon/reference-code/litellm-clj/src/litellm/providers/openrouter.clj:185). Its vendored stream transform drops usage [openrouter.clj:236](/Users/sean/src/seon/reference-code/litellm-clj/src/litellm/providers/openrouter.clj:236), so `usage-in-stream?` remains false/unqualified until a direct probe. No new wire core. |
| Gemini | The vendored behavior is native GenerateContent: native roles/content/tools, model-in-path endpoint, `x-goog-api-key`, native candidate/usage transforms, and JSON-per-line rather than SSE. | Transforms [gemini.clj:15](/Users/sean/src/seon/reference-code/litellm-clj/src/litellm/providers/gemini.clj:15), endpoint/auth [gemini.clj:181](/Users/sean/src/seon/reference-code/litellm-clj/src/litellm/providers/gemini.clj:181), JSONL stream [gemini.clj:305](/Users/sean/src/seon/reference-code/litellm-clj/src/litellm/providers/gemini.clj:305). This exact contract requires a third wire core. A row is allowed only after Google’s separate OpenAI-compatible surface passes qualification. |

LiteLLM’s shared compatible handler parses only integer Retry-After values: [openai_compatible.clj:223](/Users/sean/src/seon/reference-code/litellm-clj/src/litellm/providers/openai_compatible.clj:223). Seon already accepts fractional seconds and HTTP dates: [ai/core.cljc:215](/Users/sean/src/seon/src/seon/ai/core.cljc:215). Compatible descriptors should select that existing policy; retry authority remains in the turn layer.

## Sizing

| Piece | Estimate |
|---|---:|
| Complete-stream semantics, delta sink, usage-only fixtures, remove first-form branch | 2–3 days |
| No-history bridge facet and regression | 1 day |
| Database coalescer, fenced writes, terminal/crash cleanup | 2–4 days |
| DB-derived partial renderer and browser/load proof | 2–3 days |
| Ephemeral ingress/feed overlay instead, including canonical priority | 4–7 days |
| Provider descriptor schema, singleton reconciliation, pull shape | 3–5 days |
| Remove provider atom/enums/cases; fixed two-core dispatch | 3–5 days |
| Generic endpoint/auth/stream/usage policy interpretation | 3–4 days |
| DeepSeek row and parity | 1 day |
| Kimi, Z.AI, OpenRouter row + paid qualification | 1–2 days each |
| Cached usage normalization/disagreement evidence | 2–3 days |
| Existing fallback consumption parity | 2–3 days |
| Gemini via proven OpenAI-compatible surface | 1–2 days after proof |
| Native Gemini third core, if ruled necessary | 5–9 days plus architecture ruling |

## UNCLEARs and exact probes

1. **Complete-stream fixture:** Feed both consumers content containing at least two top-level forms, a choices-empty usage chunk, then `[DONE]`. Assert cumulative callbacks, byte-identical final text, both forms retained, provider usage not estimated, and no close before `[DONE]`.

2. **Callback isolation:** Make the presentation sink throw, block, and reject updates. The provider fold, usage, attempt receipt, and evaluation result must remain identical.

3. **No-history semantics:** Transact `A → AB → ABC → retract` on a test no-history attr. Assert current state and `history` contain no partial; inspect `as-of` and `since` at each basis. The fork proves history omission, but exact old-basis behavior still needs a Seon regression.

4. **Physical amplification:** Compare ordinary history, no-history string prefixes, and blob prefixes for database/index/commit/blob growth. Include post-GC measurement.

5. **Interest specificity:** Capture the installed dependency for agent A, update agent B, and prove A receives neither recomputation nor notification.

6. **Throughput cutoff:** At 1/16/64/100 streams, measure writer tx/s, commit P95/P99, terminal transaction latency, delivery lag/resync, reactive pending replacement, render time, and SSE bytes.

7. **Frame bounds:** Compare the largest permitted provider response with the database protocol’s actual UTF-8 transaction envelope. Any required maximum becomes a loud R27 fact.

8. **Browser races:** Exercise slow client, reconnect, web-render restart, run-holding process crash/reacquire, late partial after terminal, and historical feed. Canonical terminal state must always win.

9. **Config acquisition parity:** From one immutable database value, run the pod and run-holding process acquisition paths and assert byte-identical primary/fallback resolutions. The pod currently merges a legacy AI row at [ctx/driver.cljs:300](/Users/sean/src/seon/src/seon/agent/ctx/driver.cljs:300), while the run-holding process pulls the cluster singleton at [driver/host.clj:67](/Users/sean/src/seon/src/seon/agent/driver/host.clj:67).

10. **Per-provider qualification:** For DeepSeek, Kimi, Z.AI, and OpenRouter, retain raw request/events and prove endpoint, auth, multi-form completion, terminal usage, cached-token spellings, Retry-After, timeout, and response-byte enforcement before setting `streaming-actually-works?`.

11. **OpenRouter usage:** Send exactly `stream=true` plus `stream_options.include_usage=true`; prove whether a terminal usage-only event arrives.

12. **Gemini gate:** Verify the official OpenAI-compatible base/path, auth, SSE framing, tools, response format, usage-in-stream, cached usage, and error behavior through Seon’s existing core. If any required behavior is GenerateContent-only, stop and rule on a third core.

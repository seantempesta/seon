---
type: reference
status: active
tags: [reference, agent, config]
---

# LLM providers and settings

Fresh Seon has one AI implementation: `src/seon/ai.clj`. It builds an
OpenAI-compatible Chat Completions request, sends it with the JDK HTTP client,
and returns either one completion value or one flat error value. There are no
provider adapter namespaces, Node SDK leaves, provider registry, pod-local
workers, or `:seon.ai/config` singleton in the fresh tree.

## Configuration authority

AI settings are ordinary `:seon.config.ai/*` dials declared by the admitted
schema resource. The config section of `resources/seon/schema.edn` declares the
provider, request, backup, and retry dials; its AI section declares the
mutually exclusive literal `:seon.config.ai/no-auth` dial alongside the request
unions that consume it. `config/default.edn` owns shipped values and their
provenance. A sparse manifest overrides those defaults and is applied through
the standard config path:

```sh
bin/seon start research --config config/research.edn
bin/seon config apply research config/research.edn
```

There are no `SEON_AI_*` synchronization variables. The only environment read
is the credential value named by `:seon.config.ai/api-key-variable`; the name
is a database fact, while the secret is read at the HTTP leaf and never stored.
An unauthenticated endpoint instead uses the mutually exclusive literal
`:seon.config.ai/no-auth true`.

## Current dials

The schema is the exhaustive authority. The primary target uses:

| Dial | Meaning |
|---|---|
| `:seon.config.ai/endpoint` | Absolute Chat Completions endpoint. |
| `:seon.config.ai/model` | Provider-owned model name. |
| `:seon.config.ai/max-tokens` | Positive combined reasoning and visible-output budget, sent as `max_tokens`. |
| `:seon.config.ai/thinking` | Optional `:disabled`, `:low`, `:high`, or `:max`; absence leaves the provider default untouched. |
| `:seon.config.ai/temperature` | Optional temperature. |
| `:seon.config.ai/top-p` | Optional nucleus-sampling value. |
| `:seon.config.ai/frequency-penalty` | Optional frequency penalty. |
| `:seon.config.ai/presence-penalty` | Optional presence penalty. |
| `:seon.config.ai/stop` | Optional vector of one to four stop strings. |
| `:seon.config.ai/response-format` | Optional `:json-object`, sent as the OpenAI-compatible response-format document. |
| `:seon.config.ai/extra-body-edn` | Optional EDN string containing a map with string keys for provider-specific request fields. |
| `:seon.config.ai/api-key-variable` | Name of the credential environment variable. |
| `:seon.config.ai/no-auth` | Literal `true` for an endpoint that requires no credential. |
| `:seon.config.ai/timeout-ms` | JDK HTTP request deadline in milliseconds. |

`extra-body-edn` cannot override request-builder fields or headers. Invalid
EDN, non-map values, non-string keys, and protected-key conflicts become flat
pre-call error values.

The optional backup target is enabled by
`:seon.config.ai.backup/model`. Its endpoint, credential-variable name, and
deadline are optional overrides over the primary target. With no backup model,
the backup is absent rather than present with nil values.

The retry dials are
`:seon.config.ai.retry/base-delay-ms`, `/multiplier`, `/jitter-fraction`,
`/maximum-delay-ms`, `/maximum-retries`, and `/maximum-total-delay-ms`.
`seon.ai/delays` derives a finite schedule value; the run loop reduces over it.

## Shipped defaults

The current `config/default.edn` decisions are:

```clojure
{:seon.config.ai/endpoint "https://api.deepseek.com/chat/completions"
 :seon.config.ai/model "deepseek-v4-flash"
 :seon.config.ai/max-tokens 65536
 :seon.config.ai/thinking :disabled
 :seon.config.ai/api-key-variable "DEEPSEEK_API_KEY"
 :seon.config.ai/timeout-ms 180000}
```

Temperature, top-p, penalties, stop, response format, extra body, no-auth, and
all backup dials ship absent. Ordinary per-turn agents therefore use explicit
non-thinking mode. A planner opts into thinking through the same per-agent
setting mechanism; no separate planner provider path exists.

The shipped retry values are a 500 ms base, `2.0` multiplier, `0.25` jitter,
4,000 ms per-wait maximum, two retries, and 3,000 ms maximum total delay. These
values are considered only for a conclusively unpaid transient failure with no
backup.

## Per-agent inheritance and per-turn resolution

Every AI dial is registered with `:seon.config/per-agent true`. The schema
loader derives `:seon.config/agent-overlay` from those registrations, so there
is no maintained roster of overridable keys. An agent override is the same
attribute placed sparsely on that agent's `:seon.cluster.agent/id` entity:

```clojure
{:seon.cluster.agent/id "planner"
 :seon.config.ai/thinking :high}
```

`seon.ai/agent-overlay` pulls only the schema-derived overlay attributes, and
`seon.ai/settings` merges them over `seon.config/effective`. The run loop reads
the cluster settings and agent row from one immutable database value once at
the start of each `:call` turn. Failover and backoff attempts reuse that
resolved map. A config apply or agent-entity update affects the next turn; it
does not mutate a turn already in flight or require a graph rebuild.

## Request and response contract

`seon.ai/request-body` emits string-keyed provider data containing `model`,
`messages`, `stream`, and the schema-derived wire settings. Streaming also
sends `stream_options.include_usage`. Thinking maps to the OpenAI-compatible
`thinking` document and `reasoning_effort`; `:disabled` omits
`reasoning_effort`. For DeepSeek, configured temperature, top-p, frequency
penalty, and presence penalty are treated as inert while thinking is enabled.

`seon.ai/complete` performs exactly one HTTP attempt. It does not retry or
fail over. The run loop is the sole owner that may make another attempt, and
only `seon.ai/disposition` decides whether it may:

- output observed, a timeout after transmission, or an unknown transport phase
  is terminal;
- a conclusively unpaid failure may use the configured backup; and
- a conclusively unpaid transient failure without a backup may use the finite
  backoff schedule.

Nothing re-calls a request that may have performed paid work. Timeouts,
transport failures, provider rejections, and unreadable responses are flat
`:seon.error` values with transport evidence; none throws into the agent loop.

Streaming is a transport choice only. `seon.ai/stream-fold` publishes complete
partial snapshots to an injected sink while retaining the newest usage
document independently of content chunks. A sink may lag, drop, or throw
without affecting transport, parsing, usage, or the final completion. A
streamed and non-streamed call return the same completion shape.

## Durable attempt facts

The loop commits one `:seon.ai/attempt` entity after every recorded call. The
row carries its run, ordinal, instant, endpoint, model, and the exact resolved
settings as `:seon.ai.attempt/settings-edn`. When present, provider usage is
stored as the provider-owned open map in `:seon.ai.attempt/usage-edn`.

Reasoning content is stored inline or by content-addressed blob according to
the configured result blob threshold. Finish reason and transport evidence are
observations on the attempt. Failure is represented by a ref to the committed
error fact. A backup points to the failed attempt through
`:seon.ai.attempt/failover-from`; a retry records its preceding delay. There is
no stored success label, normalized provider enum, or turn-level duplicate of
these facts.

`seon.ai/normalize-usage` derives comparable prompt, completion, total, and
cached token counts from a persisted provider usage document. The original
document remains the authority.

## Provider scope

Fresh source supports the OpenAI-compatible request and response shape
implemented in `seon.ai`; it does not maintain a provider-name catalog. To use
another compatible hosted or local endpoint, select its endpoint, model,
authentication shape, and deadline through the dials above and prove that its
responses satisfy `seon.ai/completion-text` and `seon.ai/stream-event`.

The former `seon.ai.openai-compat.*`, `seon.ai.anthropic.*`, Node SDK,
DiffusionGemma, typeahead, descriptor-row, preload, and pod configuration
material is historical quarry. It is not a second supported runtime and was
deleted from this maintained reference.

## Sources checked

- `src/seon/ai.clj` — settings, target construction, wire mapping, JDK HTTP
  leaf, streaming fold, disposition, and usage normalization.
- `src/seon/cluster/loop.clj` — one-resolution-per-turn and durable attempt
  transactions.
- The config and AI sections of `resources/seon/schema.edn` — provider dials,
  their per-agent metadata, and request/completion/attempt schemas.
- `src/seon/schema/edn.clj` — schema-derived effective and agent-overlay maps.
- `config/default.edn` — shipped decisions and provenance.

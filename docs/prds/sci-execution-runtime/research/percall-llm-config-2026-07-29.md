---
type: research
status: active
tags: [research, ai, config]
---

# Per-call and per-agent LLM configuration — 2026-07-29

## Result

The old system did build useful per-agent model configuration. Its final
resolution was a pure, presence-sensitive overlay over one acquired database
value, and it supported complete independent provider targets in one cluster.
That mechanism is worth quarrying. Its configuration singleton, birth-profile
registration, derived-state detour, and split per-call adapter surface are not.

The fresh design should have one pure `resolve-target` function:

1. select a provider descriptor from call, agent, then role default;
2. overlay present agent facts on that descriptor's default target;
3. overlay the call's ordinary `:seon.ai/*` map last;
4. validate the resolved target against the selected descriptor; and
5. return the target or a flat error value.

There is no process-global fallback. The cluster primary and backup are role
defaults, not global mutable configuration. Per-agent values are ordinary facts
on the agent row. Per-call values are ordinary arguments. Neither belongs in
`:seon.config/effective`, needs a `:seon.config.*` identity, or requires a
model-variant registration.

Thinking must not be one mixed stored scalar. The provider families expose
different controls:

- Ollama's native Chat API with Qwen: a Boolean `think` switch;
- OpenAI-compatible reasoning models: `reasoning_effort`;
- Anthropic: `thinking.type` plus `budget_tokens`.

Those fields need distinct native schemas and descriptor-owned projection.
Absence means inherit. This preserves the owner ruling that the planner can
think hard, repair calls can be minimal, and a fast one-form-at-a-time call can
request no thinking, without restoring `:inherit`, stringly typed values, or a
generic extra-body config system.

## Scope and dependency ledger

This is analysis only. No source or schema was changed.

The mined implementation is the disabled quarry under `src-old/`; the fresh
implementation is under `src/`. Relevant maintained dependency revisions are:

- OpenAI Node `6f849f4ff24f70167bf82d37c8c83e3f8b1c5472`;
- Anthropic TypeScript SDK
  `fbee0d149ce08532885d766d9b1dc99133181d8e`;
- LiteLLM Clojure
  `14bcdd949c0207d6c4988a3db887a1a7fa1c5522`.

The exact source boundaries read were:

- old resolution: `src-old/seon/ai/core.cljc`;
- old provider policy: `src-old/seon/ai/provider.cljc`;
- old request projection:
  `src-old/seon/ai/openai_compat/core.cljc` and
  `src-old/seon/ai/anthropic/core.cljc`;
- old loop acquisition: `src-old/seon/agent/driver.clj`;
- fresh target and request projection: `src/seon/ai.cljc`;
- fresh call site: `src/seon/cluster/loop.cljc`;
- fresh contracts: `src/seon/schema/ai.edn`,
  `src/seon/schema/config.edn`, `src/seon/schema/agent.edn`, and
  `src/seon/schema/run.edn`;
- current defaults: `config/default.edn`;
- local provider evidence:
  `docs/prds/sci-execution-runtime/research/local-provider-2026-07-28.md`;
- provider policy:
  `docs/seon/reference/llm-adapters.md`;
- OpenAI request type:
  `reference-code/openai-node/src/resources/chat/completions/completions.ts`
  and `reference-code/openai-node/src/resources/shared.ts`;
- Anthropic request type:
  `reference-code/anthropic-sdk-typescript/src/resources/messages/messages.ts`.

The governing ruling is
`docs/prds/sci-execution-runtime/plan/README.md:711-729,779-782`: thinking is
per use; every model-execution setting is local to an agent or situation;
descriptor rows remain defaults; and the call-site map wins.

## Quarry: what the old system actually built

### Stored schemas

The final quarry had a common configuration vocabulary and native per-agent
mirrors. `src-old/seon/ai/core.cljc:40-80,310-334` registered and mapped:

| Effective field | Agent-row fact | Value |
|---|---|---|
| `:seon.ai/provider` | `:seon.ai/agent-provider` | provider keyword |
| `:seon.ai/model` | `:seon.ai/agent-model` | non-empty string |
| `:seon.ai/temperature` | `:seon.ai/agent-temperature` | double |
| `:seon.ai/max-tokens` | `:seon.ai/agent-max-tokens` | integer |
| `:seon.ai/completion-limit-field` | `:seon.ai/agent-completion-limit-field` | `:max-tokens` or `:max-completion-tokens` |
| `:seon.ai/thinking` | `:seon.ai/agent-thinking` | non-empty string |
| `:seon.ai/timeout-ms` | `:seon.ai/agent-timeout-ms` | integer |
| `:seon.ai/base-url` | `:seon.ai/agent-base-url` | non-empty string |
| `:seon.ai/api-key-env` | `:seon.ai/agent-api-key-env` | environment-variable name |
| `:seon.ai/dg-backend` | `:seon.ai/agent-dg-backend` | `:vllm` or `:control` |
| `:seon.ai/extra-body-edn` | `:seon.ai/agent-extra-body-edn` | EDN string |

Agent rows also carried retry count, outer attempt timeout, and an optional
fallback model-variant selector. Credentials themselves were never facts; only
the environment-variable name was stored.

This became complete only in `4129738a` (“Complete per-agent model
configuration”). Before that commit, agent rows could vary provider, model,
temperature, output cap, and thinking, but not endpoint, credential variable,
timeout, local backend, or extra body. Two agents pointed at different
OpenAI-compatible gateways could therefore not have complete independent
targets. The archived issue
`docs/seon/issues/archive/per-agent-model-transport-overrides.md:10-18`
records that failure and its proof.

### Resolution order

`src-old/seon/ai/core.cljc:442-450` converted only present agent attributes to
their effective keys. `resolve-config-values` at lines 470-526 then resolved
each key by presence:

```clojure
agent-row override
  -> cluster configuration row
  -> selected provider descriptor / shipped provider default
```

Provider identity followed the same first-present rule, with DeepSeek as the
last shipped default. Selecting the provider happened before resolving its
other defaults. The function returned ordinary resolved data plus per-key
provenance (`:agent-override`, `:config-row`, or `:default`).

`resolved-config-from-rows` at lines 528-592 combined descriptor defaults with
shipped defaults, resolved the primary, and optionally resolved a separate
fallback variant. It did not depend on ambient connection state.

At the turn boundary, `src-old/seon/agent/driver.clj:578-599` pulled the cluster
configuration and the agent row, then put the resulting immutable resolution
in the one model-request map. The later frozen-input work ensured retries used
that same acquired resolution instead of rereading the connection.

### How overrides reached provider calls

The portable OpenAI projection at
`src-old/seon/ai/openai_compat/core.cljc:11-55` accepted the request and frozen
resolution. Direct request values for model, temperature, and max tokens won
over the resolved values. Descriptor policy chose the completion-limit key and
thinking projection. The Anthropic projection at
`src-old/seon/ai/anthropic/core.cljc:15-50` likewise let direct model and max
tokens win.

That sounds like a full per-call contract, but it was not. At commit
`4129738a`, the provider-specific OpenAI request schema admitted direct model,
temperature, max-tokens, tools, tool-choice, and extra-body values. The shared
turn request in `src/seon/ai/dispatch.cljs` admitted only context, system,
streaming, abort, and the frozen configuration resolution. `llm-fn` selected
the adapter from that resolution and called it with no option map.

The old reference document states the consequence directly at
`docs/seon/reference/llm-adapters.md:522-538`: per-call extra-body worked for
direct `complete` calls but was unreachable from the agent turn loop. Thinking
also came only from the frozen resolved configuration; it was not a direct
request field. Therefore:

- per-agent override was a real end-to-end turn feature;
- direct adapter calls had some per-call overrides;
- ordinary agent turns did **not** have one complete per-call override seam;
- per-call thinking budgets are new work.

### What worked

- Presence, not a sentinel, represented inheritance.
- Each stored override used its native Datahike value type.
- Resolution was pure over acquired rows and retained provenance.
- Provider selection occurred per call rather than only when an agent armed.
- Endpoint and credential-variable selection could differ for two agents in
  one cluster.
- Request assembly consumed one frozen resolution for all attempts.
- Descriptor rows owned provider-specific projection policy instead of a
  provider conditional scattered through the loop.

### Bugs and lessons from history

| Commit / issue | Failure | Lesson retained |
|---|---|---|
| `2a04e5992` | Initial per-agent model values landed. | The agent row is a valid locality boundary. |
| `0e5141439` | Model/temperature/max/thinking resolved per call, but the provider adapter was selected once from the global provider when the agent armed. | Resolve provider and target together at the paid-call boundary. |
| `9b4a819e2` then owner correction `560a5f226` | Resolved model config was stamped as per-turn derived datoms. | Derive defaults and provenance; record only the actual remote-call occurrence and request evidence. |
| `3ecc9eb18` | An attempt reread mutable provider configuration while assembling the request. | Freeze one ordinary target before calling the external service. |
| `4129738a` | The first overlay was incomplete for alternate endpoints and credentials. | A target override must be complete enough to produce a callable target. |
| `bd357aa57` | Agent-specific fallback needed a second resolved target. | Primary and backup are separate roles; never smear primary overrides onto backup. |
| `da8a4fce4` | Stored `:inherit` mixed keyword/string/numeric schemas and required decoding. | Absence means inherit; use native typed attributes and `contains?`. |
| `df78bb8d2`; `turn-retries-reread-provider-inputs.md` | Retries could observe later model, prompt, retry, and timeout facts. | Prompt and target come from one immutable database value and remain frozen across retries. |
| `de1458b24`; `agents-run-config-pull-pattern-contract-rejects-component-pulls.md` | A pull-pattern contract claimed keyword members but returned component-pull maps. | Schema the actual pull data, not an idealized shorthand. |
| `llm-config-pull-used-entity-count-as-node-budget.md` | A singleton entity count was misused as a Datahike pull work bound. | Resource limits use the dependency's actual accounting unit. |
| `jvm-claimant-rejects-inherited-attempt-timeout.md` | The JVM rejected an absent agent timeout before applying the inherited default. | Validate after resolution, not optional layers independently. |
| `typeahead-provider-decoding-reversed-arguments.md` | A stored provider override was decoded with reversed arguments and disappeared. | Keep acquired values ordinary and remove avoidable encode/decode layers. |
| named variant follow-up in `per-agent-model-transport-overrides.md:45-69` | A birth-only profile selector had to be registered, copied, reacquired on stale retry, and originally broke reuse after profile deletion. | A one-call override is an argument, never a registered profile or copied durable row. |

## Fresh state inventory

### Target assembly

`src/seon/ai.cljc:93-137` has one pure `targets` projection from
`:seon.config/effective`. It builds a cluster-wide primary with:

- `:seon.ai/endpoint`;
- `:seon.ai/model`;
- `:seon.ai/max-tokens`;
- `:seon.ai/timeout-ms`;
- either `:seon.ai/api-key-variable` or
  `:seon.config.ai/no-auth true`.

An optional backup inherits that primary and may replace model, endpoint,
credential-variable name, and timeout. The primary and backup are assembled
once into the cluster loop handle.

`src/seon/schema/config.edn:71-128,193-222,255-286,318-342` declares the
primary, backup, and retry dials. The same population is currently repeated in
the manifest, effective map, and database entity. The active issue
`docs/seon/issues/a-dial-exists-has-no-single-authority.md` owns eliminating
that hand synchronization. Its locality boundary at lines 115-120 explicitly
excludes per-agent and per-situation target data from config dial derivation.

`config/default.edn:115-138` ships DeepSeek, max tokens `8192`, and a
60-second remote-call deadline. No backup is shipped.

### Request and call site

`src/seon/schema/ai.edn:5-78` defines a closed target and a closed request.
They know endpoint, model, max-tokens, auth/no-auth, timeout, prompt/system, and
optional streaming. They do not know provider descriptor identity,
temperature, an agent overlay, or any thinking control.

`src/seon/ai.cljc:204-226` projects the compatible JSON body. It emits
`model`, `max_tokens`, `stream`, messages, and optional stream usage. It emits
no temperature or thinking field. `complete` at lines 584-615 is otherwise the
right final shape: one complete ordinary request map in, one completion or flat
error value out, with no process-global configuration read.

The loop at `src/seon/cluster/loop.cljc:558-623` renders and durably captures
the prompt, then always starts with `(:seon.ai/primary cluster)` and calls:

```clojure
(ai/complete (assoc target :seon.ai/prompt text))
```

There is no agent pull or situation override. Retries reuse the same target;
failover replaces it with the cluster backup. The attempt fact currently
records endpoint and model, but not max-tokens or thinking controls
(`src/seon/schema/ai.edn:115-142` and
`src/seon/cluster/loop.cljc:305-354`).

`src/seon/schema/agent.edn` has no LLM request/creation facts. Its blueprint
comment still says agents have no per-agent variation, which this unit will
deliberately change. The installed agent entity shape actually lives at
`src/seon/schema/run.edn:15-23`; that is the map that must admit and install
the durable override attributes.

`config/system.edn:420-449` still contains old-system model-variant examples
using `:seon.ai/agent-*`. They are quarry data, not evidence that the fresh
schema or loop consumes those facts.

## Thinking controls by provider family

### Ollama and Qwen

The qualified local target is Ollama `0.32.1` serving
`qwen3.5:35b-a3b-coding-nvfp4`. The evidence is
`local-provider-2026-07-28.md:76-113`.

Ollama's [thinking contract](https://docs.ollama.com/capabilities/thinking)
uses a Boolean `think` control for Qwen on native `/api/chat`. Its
`low`/`medium`/`high` levels are documented for GPT-OSS, not Qwen. Ollama's
[OpenAI-compatible endpoint](https://docs.ollama.com/api/openai-compatibility)
does not list native `think`; it lists `reasoning_effort` and
`reasoning.effort`. In the local compatible-endpoint calibration,
`reasoning_effort=low` did not bound Qwen: a test consumed all 4,096 completion
tokens without visible content. At 8,192, the sustained drive still had one
call consume the entire budget as reasoning. Therefore:

- `max_tokens` is a total completion bound, not a thinking budget;
- `reasoning_effort` must not be presented as an honest Qwen budget;
- Qwen's documented Boolean off switch requires an Ollama-native descriptor
  and request projector;
- `reasoning_effort :none` on the currently selected `/v1/chat/completions`
  target requires local qualification before it can be called a reliable off
  switch;
- vLLM/SGLang Qwen targets use the different concrete field
  `chat_template_kwargs.enable_thinking`, as documented in
  `docs/seon/reference/llm-adapters.md:443-497`.

The selected descriptor must say which of those concrete projections its
server accepts. A generic extra-body string is not the fresh model.

### OpenAI-compatible reasoning effort

The maintained OpenAI type defines:

```text
none | minimal | low | medium | high | xhigh
```

at `reference-code/openai-node/src/resources/shared.ts:300-312`. Chat
Completions exposes it as `reasoning_effort` at
`reference-code/openai-node/src/resources/chat/completions/completions.ts:2064-2079`.
Support remains model-dependent: some models do not accept `none`, some support
only a subset, and some force one effort.

The old descriptor policy correctly sent `reasoning_effort` only for providers
declaring `:openai-reasoning-effort`; it did not send a made-up `thinking`
field to every compatible gateway
(`docs/seon/reference/llm-adapters.md:192-204`).

### Anthropic thinking tokens

The maintained Anthropic request type has three thinking variants:
`:disabled`, `:adaptive`, and `:enabled`. Enabled thinking carries
`budget_tokens`, which must be at least 1,024 and less than `max_tokens`
(`reference-code/anthropic-sdk-typescript/src/resources/messages/messages.ts:1703-1755`).
The Messages request accepts that thinking object at lines 2983-2994.

Temperature is not a universal fallback: the same request type marks
temperature deprecated for models after Opus 4.6 and says values other than
`1.0` are rejected (`messages.ts:2976-2981`). The resolved target must omit
temperature when absent, and descriptor validation must reject an unsupported
present value rather than silently dropping or coercing it.

## Recommended fresh design

### One target vocabulary, three localities

Use the same effective target keys at every locality:

- descriptor row: defaults;
- agent row: sparse durable overrides;
- call argument: sparse ephemeral overrides.

Only the agent facts need `agent-` names because they coexist on an entity with
the effective vocabulary. The resolver renames them to the target keys. The
call map already uses target keys and needs no translation.

Provider selection is a connection to a descriptor row. The descriptor owns
the adapter core, endpoint/auth defaults, supported thinking projection, and
model capability constraints. Selecting a descriptor is how a call changes
provider family coherently; a caller should not have to reconstruct
authentication policy.

### Exact schema additions

Add these leaf and composite schemas in `src/seon/schema/ai.edn`:

```clojure
;; descriptor identity and request projection
:seon.ai.provider/id
[:keyword {:seon.db/identity true}]

:seon.ai.provider/adapter-core
[:enum :openai-compat :anthropic :ollama]

:seon.ai.provider/thinking-policy
[:enum :omit
 :ollama-think
 :qwen-chat-template-enable-thinking
 :openai-reasoning-effort
 :anthropic-budget-tokens]

;; ordinary target/call values
:seon.ai/provider :seon.ai.provider/id
:seon.ai/temperature [:double {:min 0.0}]
:seon.ai/think :boolean
:seon.ai/reasoning-effort
[:enum :none :minimal :low :medium :high :xhigh]
:seon.ai/thinking-enabled? :boolean
:seon.ai/thinking-budget-tokens [:int {:min 1024}]
:seon.ai/no-auth [:= true]

:seon.ai.provider/descriptor
[:or
 [:map {:closed true :seon.db/entity true}
  [:seon.ai.provider/id :seon.ai.provider/id]
  [:seon.ai.provider/adapter-core :seon.ai.provider/adapter-core]
  [:seon.ai.provider/thinking-policy :seon.ai.provider/thinking-policy]
  [:seon.ai/endpoint :seon.ai/endpoint]
  [:seon.ai/model :seon.ai/model]
  [:seon.ai/max-tokens :seon.ai/max-tokens]
  [:seon.ai/api-key-variable :seon.ai/api-key-variable]
  [:seon.ai/timeout-ms :seon.ai/timeout-ms]
  [:seon.ai/temperature {:optional true} :seon.ai/temperature]
  [:seon.ai/think {:optional true} :seon.ai/think]
  [:seon.ai/reasoning-effort {:optional true} :seon.ai/reasoning-effort]
  [:seon.ai/thinking-enabled?
   {:optional true}
   :seon.ai/thinking-enabled?]
  [:seon.ai/thinking-budget-tokens
   {:optional true}
   :seon.ai/thinking-budget-tokens]]
 [:map {:closed true :seon.db/entity true}
  [:seon.ai.provider/id :seon.ai.provider/id]
  [:seon.ai.provider/adapter-core :seon.ai.provider/adapter-core]
  [:seon.ai.provider/thinking-policy :seon.ai.provider/thinking-policy]
  [:seon.ai/endpoint :seon.ai/endpoint]
  [:seon.ai/model :seon.ai/model]
  [:seon.ai/max-tokens :seon.ai/max-tokens]
  [:seon.ai/no-auth :seon.ai/no-auth]
  [:seon.ai/timeout-ms :seon.ai/timeout-ms]
  [:seon.ai/temperature {:optional true} :seon.ai/temperature]
  [:seon.ai/think {:optional true} :seon.ai/think]
  [:seon.ai/reasoning-effort {:optional true} :seon.ai/reasoning-effort]
  [:seon.ai/thinking-enabled?
   {:optional true}
   :seon.ai/thinking-enabled?]
  [:seon.ai/thinking-budget-tokens
   {:optional true}
   :seon.ai/thinking-budget-tokens]]]

:seon.ai/overrides
[:map {:closed true}
 [:seon.ai/provider {:optional true} :seon.ai/provider]
 [:seon.ai/endpoint {:optional true} :seon.ai/endpoint]
 [:seon.ai/model {:optional true} :seon.ai/model]
 [:seon.ai/temperature {:optional true} :seon.ai/temperature]
 [:seon.ai/max-tokens {:optional true} :seon.ai/max-tokens]
 [:seon.ai/timeout-ms {:optional true} :seon.ai/timeout-ms]
 [:seon.ai/think {:optional true} :seon.ai/think]
 [:seon.ai/reasoning-effort {:optional true} :seon.ai/reasoning-effort]
 [:seon.ai/thinking-enabled?
  {:optional true}
  :seon.ai/thinking-enabled?]
 [:seon.ai/thinking-budget-tokens
  {:optional true}
  :seon.ai/thinking-budget-tokens]]
```

`thinking-enabled?` and `thinking-budget-tokens` are separate native values:

- false projects Anthropic `{"thinking":{"type":"disabled"}}`;
- a present budget projects
  `{"thinking":{"type":"enabled","budget_tokens":n}}`;
- true without a budget projects `{"thinking":{"type":"adaptive"}}` only when
  the descriptor declares that the selected model supports adaptive thinking.

`thinking-budget-tokens` therefore implies enabled thinking. A present budget
combined with `thinking-enabled? false` is invalid. This makes the useful
per-call form just `{:seon.ai/thinking-budget-tokens n}` without silently
dropping a partial override.

The three thinking families are mutually exclusive after descriptor selection.
Do not add a mixed `:seon.ai/thinking` value such as Boolean-or-keyword-or-int.
That would recreate the storage and decoding class deleted by `da8a4fce4`.

Replace `:seon.config.ai/no-auth` inside target/request shapes with
`:seon.ai/no-auth`. The config dial remains
`:seon.config.ai/no-auth`; `targets` performs the locality projection just as
it already projects endpoint/model/max-tokens. A provider request should not
carry a config-dial identity.

Extend both auth arms of `:seon.ai/target` and `:seon.ai/request` with required
`:seon.ai/provider` and the optional ordinary fields above. The structural
schemas remain closed. The pure descriptor validation then enforces the
provider-specific combinations, so an OpenAI target cannot carry Anthropic
budget fields and an Anthropic target cannot carry `reasoning_effort`. Do not
turn the set of provider ids into a hand-written Malli `:multi` roster.
`:seon.ai/request` remains a resolved target plus prompt/system/stream/sink.

Register the sparse durable mirror leaves beside the AI vocabulary, then add
them as optional entries to the actual agent entity,
`:seon.cluster.agent/agent` in `src/seon/schema/run.edn`:

```clojure
:seon.ai/agent-provider
[:and {:seon.db/index true} :seon.db/ref]
:seon.ai/agent-endpoint :seon.ai/endpoint
:seon.ai/agent-model :seon.ai/model
:seon.ai/agent-temperature :seon.ai/temperature
:seon.ai/agent-max-tokens :seon.ai/max-tokens
:seon.ai/agent-timeout-ms :seon.ai/timeout-ms
:seon.ai/agent-think :seon.ai/think
:seon.ai/agent-reasoning-effort :seon.ai/reasoning-effort
:seon.ai/agent-thinking-enabled? :seon.ai/thinking-enabled?
:seon.ai/agent-thinking-budget-tokens
:seon.ai/thinking-budget-tokens
```

The provider ref pulls `:seon.ai.provider/id`. All other fields are native
cardinality-one values. Absence means inherit; nil and `:inherit` are invalid.
`agent-row->target-overrides` must extract the pulled
`:seon.ai.provider/id` keyword from that ref; it must never copy an eid,
lookup-ref vector, or pulled entity map into the ordinary target.

Add **no** per-agent or per-call entries to `src/seon/schema/config.edn`.
Descriptor defaults may be configurable facts, but an override is not a dial.
The current flat cluster roles do need explicit descriptor identity: either
replace each role's field bundle with a ref to
`:seon.ai.provider/descriptor`, or add only the default selectors
`:seon.config.ai/provider` and optional
`:seon.config.ai.backup/provider`, both using
`:seon.ai.provider/id`. The backup selector inherits the primary when absent.
Never infer a provider or thinking policy from its endpoint string.

Add **no** model-variant selector, call registry row, EDN-string extra body, or
override transaction for a call.

The paid-call receipt must retain the exact normalized controls that were sent.
Extend `:seon.ai/attempt` with optional `:seon.ai/provider`,
`:seon.ai/max-tokens`, `:seon.ai/temperature`, and the applicable thinking
field(s). These are remote-call observations, not a stored derived projection.
They are especially necessary for ephemeral call overrides, which cannot be
re-derived later from database history.

### Pure resolution function spec

The proposed public contract is:

```clojure
(resolve-target
 {:seon.ai/default-provider provider-id
  :seon.ai/provider-descriptors {provider-id descriptor, ...}
  :seon.ai/default-target target
  :seon.ai/agent-row agent-row
  :seon.ai/overrides call-overrides})
;; => :seon.ai/target | :seon.error/value
```

Semantics:

```clojure
provider-id =
  first present of
    call-overrides :seon.ai/provider
    agent-row      :seon.ai/agent-provider -> pulled descriptor id
    default-provider

base =
  if provider-id equals default-provider,
    selected role's complete default-target
  else
    callable defaults of provider-descriptors[provider-id]

agent-provider =
  agent row's pulled provider id when present,
  otherwise default-provider

agent-layer =
  agent-row->target-overrides(agent-row), only when
  agent-provider equals provider-id;
  otherwise {}

target =
  base
  |> overlay-present(agent-layer)
  |> overlay-present(call-overrides)
  |> validate-against(provider-descriptors[provider-id])
```

The provider equality guards are essential. A call that switches from the
role's DeepSeek default to Ollama must not inherit DeepSeek's endpoint/model,
and it must not inherit an agent's Anthropic budget or model. The different
provider descriptor supplies a coherent new base; the call map then wins over
that base. A same-provider call retains the useful sparse agent layer.

`overlay-present` must use `contains?`, never `or`, `some?`, or truthiness.
That is load-bearing for `false` thinking controls. It copies only the declared
override keys and never inserts absent keys with nil.

Validation is total and returns a flat error value. It must prove:

- the selected provider descriptor exists;
- the final endpoint, model, max-tokens, timeout, and exactly one auth shape
  are present;
- only the selected descriptor's thinking fields are present;
- Anthropic `budget_tokens >= 1024` and
  `budget_tokens < max_tokens`;
- an Anthropic budget is not combined with
  `:seon.ai/thinking-enabled? false`;
- the selected model accepts the requested effort, disabled mode, adaptive
  mode, and temperature;
- a Qwen descriptor never treats `reasoning_effort` as a reliable budget.

The source function should carry:

```clojure
{:malli/schema
 [:=> [:cat :seon.ai/resolve-target-request]
  [:or :seon.ai/target :seon.error/value]]}
```

The returned target is ordinary data directly accepted by `complete`.
Provenance, when needed for diagnosis, is another pure projection over the
same three maps; it is not stamped as a second durable configuration record.

### Call-site integration

At `src/seon/cluster/loop.cljc:558-623`, bind one immutable database value
before rendering. Use it both for `prompt/prompt` and for pulling the agent
override fields. Resolve the primary once before the first remote call.
Backoff retries reuse that exact target.

Resolve backup independently from the backup descriptor. Primary agent/call
overrides must not automatically overlay the backup and turn failover into a
second call to the same target. If a situation needs a custom backup, it passes
a separate explicit backup override map through the same `resolve-target`
function. This retains the quarry's fallback-variant lesson without restoring
variant registration.

The final primary call remains simple:

```clojure
(ai/complete
 (cond-> (assoc resolved-target :seon.ai/prompt text)
   system (assoc :seon.ai/system system)
   sink (assoc :seon.ai/stream? true :seon.ai/sink sink)))
```

The planner supplies hard reasoning in that call's override map. A repair
situation supplies minimal effort or a small valid Anthropic token budget. A
fast one-form situation supplies the selected target's qualified no-thinking
control:

- Ollama-native Qwen: `{:seon.ai/think false}`;
- supported OpenAI reasoning model:
  `{:seon.ai/reasoning-effort :none}`;
- Anthropic model that permits disabling:
  `{:seon.ai/thinking-enabled? false}`.

If a selected model cannot disable or reduce thinking, resolution returns a
legible error or the caller selects a compatible descriptor. It must never
silently reinterpret “none” as “provider default.”

## Acceptance evidence for the later implementation

The implementation unit should prove:

- two agent rows in one database value resolve different provider descriptors,
  endpoints, models, and thinking controls without changing cluster defaults;
- a call override wins each corresponding agent fact, including explicit
  Boolean false;
- absence falls through agent, role default, then descriptor default, with no
  nil or sentinel;
- an ordinary turn actually sends its call override on the provider HTTP
  request, not merely in a direct adapter test;
- retries send byte-identical target controls from the frozen resolution;
- backup resolution remains independent of primary overrides;
- Ollama-native/Qwen false, OpenAI `reasoning_effort`, and Anthropic
  `budget_tokens` each appear under their provider's concrete request field;
- mismatched thinking fields and Anthropic budget/max violations return flat
  error values and make zero network calls;
- attempt facts record the exact resolved output and thinking controls;
- no override field was added to `:seon.config/effective`, no call created a
  configuration entity, and no model-variant registration was required.

The shortest live proof depends on the selected Ollama descriptor:

- for a new native `/api/chat` descriptor, call with
  `:seon.ai/think false`;
- for the current `/v1/chat/completions` descriptor, first qualify
  `:seon.ai/reasoning-effort :none` against the actual Qwen build rather than
  assuming that the documented compatible field behaves like native `think`.

Then inspect the attempt datoms and recorded provider request. Pair that proof
with pure tests for Anthropic projection; spending on a hosted provider is
unnecessary to prove resolution.

## Orchestrator review note (2026-07-29)

Gemini review flagged the proposed `:seon.ai.provider/descriptor` as an
`[:or]` of two entity-tagged maps — `register!` derives `:seon.entity/id-attr`
from the TOP-LEVEL form only, so the nested `{:seon.db/entity true}` tags are
invisible. At design review, reshape to ONE top-level entity map with the two
authentication keys optional (the established partial-unrepresentable idiom,
see ai.cljc targets), never a union of entity maps.

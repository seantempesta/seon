---
type: prd
status: active
tags: [prd, ai, config, agent]
---

# Model settings: shipped defaults, per-agent overrides, one resolution

Design-only. No production file is changed by this document.

## Why this exists

The live audit (`research/deepseek-thinking-live-proof-2026-08-01.md`, commit
`2019ffa6e`) proved fresh Seon could not express thinking on/off/effort at
all: there was no dial, so every paid call ran the provider's defaults under
a starved output budget. The thinking dial is being patched in a narrow lane
(landed in the working tree: `:seon.config.ai/thinking`, `:seon.ai/thinking`,
`:seon.ai/finish-reason`). This document designs the general mechanism the
owner asked for — **every model/request setting flows from configured
defaults and is overridable per agent** — so the next vendor parameter is a
declaration rather than an incident.

The thing that actually bit is not "thinking was missing". It is that **the
provider surface evolves and our builder has no vocabulary for it.** Every
option below is weighted by who maintains that vocabulary going forward.

## Dependency ledger

| Dependency | Read at | What it establishes |
|---|---|---|
| `src/seon/schema/edn.clj:61-117` | `config-dial?`, `derive-config-forms` | config composites are **already derived** from registered dials — `:seon.config/manifest`, `/effective`, `/entity` are folds, not hand lists |
| `src/seon/config.cljc:137-229` | `default-decisions`, `compile-manifest` | precedence defaults → overlay → environment, closed validation, one digest, one desired row |
| `src/seon/config.cljc:254-275` | `effective` | runtime reads the row and `select-keys` the dial set |
| `src/seon/ai.cljc:93-154` | `targets`, `retry-strategy` | the one projection from dials to `:seon.ai/target` + strategy |
| `src/seon/ai.cljc:204-238` (working tree) | `request-body` | the one wire-document builder; string keys; thinking landed here |
| `src/seon/cluster.clj:1033-1072` | `loop-handle` | **targets are captured ONCE at boot** into the proc handle |
| `src/seon/cluster/loop.cljc:1065-1110` | the `:call` branch | the one place a target becomes a request |
| `resources/seon/schema/agent.edn:1-55` | agent attributes | an agent is attributes + connections; no kind stamp |
| `research/deepseek-thinking-mode-api-2026-08-01.md` | vendor capture | thinking toggle, effort mapping, **silently ignored sampling params** |
| `docs/seon/reference/llm-adapters.md:16-41,151-156` | descriptor-row model | hosted providers are DATA rows selecting one of two wire cores |
| `docs/seon/issues/archive/per-agent-model-transport-overrides.md` | the quarry | State A already solved this once: per-agent overrides + `:seon.config/model-variant` named sparse maps |
| `reference-code/litellm-clj` @ `14bcdd9` (2026-06-21) | src/ read in full for chat path | see the steal/reject table |

REPL probe (`tmp/ai-settings-design/probe.clj`, run against `clojure -M:dev`):
33 registered dials, 17 of them AI; `:seon.config.ai/thinking` present and
optional; `max-tokens` now 65536; `(config/defaults)` carries no thinking key,
so absence = the provider's own default (thinking on, effort high).

---

## Part 1 — the litellm-clj verdict

### What it actually is

169 commits, last commit **2026-06-21** (six weeks stale at the time of
writing), `VERSION` 0.3, tagged `v0.3.0-alpha.2`. 7,214 source LOC, 4,559 test
LOC (e2e tests require live keys). Deps it drags in: `hato`, `cheshire`,
`logback-classic`, `tools.logging`, `core.async 1.6.681`, `malli 0.13.0`.

Its currency is genuinely better than expected: `providers/deepseek.clj:10-15`
knows `deepseek-v4-flash` and `deepseek-v4-pro` costs, and lines 41-53 know
`thinking` + `reasoning_effort`. That is the strongest argument for it.

**And it is wrong for us in exactly the place we care about.**
`providers/deepseek.clj:46-53`:

```clojure
{:low    {:thinking {:type "enabled"} :reasoning_effort "high"}
 :medium {:thinking {:type "enabled"} :reasoning_effort "high"}
 :high   {:thinking {:type "enabled"} :reasoning_effort "high"}}
```

Ask litellm-clj for `:low` on `deepseek-v4-flash` and it sends `"high"`. The
vendor capture says requested `low` maps to actual `low` on Flash. This is a
hand-maintained mapping table that is already silently wrong for our default
model — which is the precise failure mode the standing "no hand lists" rule
exists to prevent, arriving pre-installed.

### Steal / reject

| Idea | Source | Verdict | Why |
|---|---|---|---|
| **`:extra-body` passthrough merged last** | `providers/openai_compatible.clj:114-124` | **STEAL — the single most valuable idea** | A vendor parameter shipped this morning is usable this afternoon with no code change. This is the direct answer to "the provider surface evolved and we had no vocabulary." |
| **Protected keys refused, not silently overridden** | `openai_compatible.clj:11-29` | **STEAL** | The escape hatch must not be able to rewrite `model`, `messages`, `stream`, or auth. Refusal is loud; ours becomes a flat error value, not a throw. |
| **One canonical kebab param map → per-wire snake_case emission** | `deepseek.clj:55-67` (`optional-field-mappings`) | **STEAL the shape, not the table** | A declarative `[canonical wire coercion]` triple beats a `cond->` chain. Ours is derived from the dial registry rather than typed out per provider. |
| **`reasoning_content` is a sibling of `content`** on message and delta | `openai_compatible.clj:144-148,196-201` | **STEAL** | Confirms the vendor capture independently; our parser must retain it, not drop it (needed for tool-call continuations). |
| **Usage keeps cache detail** (`prompt_tokens_details.cached_tokens`) | `openai_compatible.clj:164-175` | **STEAL as a read-time derivation** | We already store `usage-edn` open; the normalization belongs at read, which is what `llm-adapters.md:151-156` also says. |
| **`thinking` config shape validated as one value** | `specs.clj:87-98` | Already ours, better | The landed `:seon.ai/thinking` enum makes contradictory toggle/effort pairs *unrepresentable*; litellm's map lets you say `{:type "disabled"}` plus `reasoning_effort "max"`. |
| Per-provider multimethod fan-out (10 arms × 12 multimethods) | `providers/core.clj:25-467` | **REJECT** | 120 hand-written dispatch arms to express "these providers speak OpenAI". `llm-adapters.md:16-41` already ruled: two wire cores, providers are DATA rows. |
| Exceptions as the error model | `providers/core.clj:477-493`, `openai_compatible.clj:223-242` | **REJECT** | Directly violates errors-as-values. We would wrap every call in a translating try/catch — i.e. re-implement `disposition`'s evidence capture anyway, from a lossier input. |
| `router.clj` + `wrappers.clj:11,61` (`with-fallback`, `with-retry`) | — | **REJECT, and it is dangerous** | It retries model calls generically. The owner's ruling forbids re-calling a model that may have done paid work; `seon.ai/disposition` decides that from transport-phase evidence. Adopting litellm's retry would silently reinstate paid retries. |
| Hard-coded cost + rate-limit tables per provider | `deepseek.clj:10-15,132-136` | **REJECT** | Hand lists, and the rate limits are invented ("conservative defaults"). |
| `estimate-tokens` = chars/4 | `providers/core.clj:704-709` | **REJECT** | We have `seon.ai.tokens/estimate` as the one estimator; a second one is a second mechanism. |
| `default-provider-config` (timeout 30000, max-retries 3) | `providers/core.clj:670-676` | **REJECT** | Tuned constants with no named event, in a library layer where our config cannot see them. |
| Blocking HTTP inside a `go` block | `openai_compatible.clj:268-275` | **REJECT — and it is a bug** | `http/post` and a `BufferedReader` loop run inside `(go …)`. That parks a core.async dispatch thread on network IO, which is exactly the `:io`/`:compute` law violation Seon's flow architecture forbids. Adopting their streaming path imports it. |
| Its own `core.async` stream channel | `streaming.clj`, `openai_compatible.clj:261-299` | **REJECT** | Our streaming rides the cluster's one sliding-1 conn under the transport law. A second channel discipline means a second loss semantics. |

### The three options, owner-gate format

**Recommendation first: (C), with the (B) work as its body. Do not adopt (A).**

---

**(A) Adopt litellm-clj as the request-building + provider-mapping layer.**

- *Guarantee*: one dependency owns provider vocabulary; adding a provider is
  a config row on their side.
- *Cost/risk*: we inherit an alpha library, six weeks stale, whose DeepSeek
  effort table is already wrong for our default model; whose error model is
  exceptions (we must translate every one back into evidence for
  `disposition`); whose retry/router would reinstate paid retries if anyone
  ever reached for it; whose streaming does blocking IO in a `go` block; and
  which adds `hato` + `cheshire` + `logback` + a second `core.async` pin. We
  would delete roughly 120 lines of `ai.cljc` (`request-body`,
  `stream-event`, `completion-text`, parts of `send-request`) and add a
  translation layer of comparable size around a foreign exception model.
- *Operational trade-off*: our upgrade cadence becomes theirs. When DeepSeek
  changes the Pro effort mapping in early August 2026 (the vendor page says it
  will), we wait for an alpha library's maintainer, or we fork — and a fork is
  the treadmill plus a dependency.
- *Capability given up*: the payment-safety evidence chain. `disposition`
  works because the leaf records `request-transmitted?`/`output-observed?`
  from the JDK's own exception taxonomy (`ai.cljc:354-369`). Behind hato +
  their error wrapping, that phase evidence is not recoverable. **Adopting (A)
  costs us the mechanism that enforces the no-retry ruling.**

**(B) Steal the param-normalization model into our own builder.**

- *Guarantee*: one declarative canonical→wire mapping; the escape hatch makes
  unknown vendor params expressible immediately.
- *Cost/risk*: ~1 day. We keep the treadmill for *response shapes* (the
  genuinely hard part), but the treadmill for *request params* mostly
  disappears behind `extra-body`.
- *Operational trade-off*: we maintain the vocabulary. That is honest: for a
  two-wire-core, effectively-one-provider system, the vocabulary is small.
- *Capability given up*: nothing we have.

**(C) A thin boundary: their tables consumed as DATA, our builder stays ours.**

- *Assessment of separability*: their tables are plain `def` maps in provider
  namespaces (`deepseek.clj:10-15,46-53`) — mechanically separable. **But
  litellm-clj vendors no model registry**: there is no
  `model_prices_and_context_window.json` anywhere in the checkout; those small
  maps are re-typed by hand. So the data worth consuming is not in *this*
  repository. The maintained artifact is upstream Python LiteLLM's
  `model_prices_and_context_window.json` (thousands of models, cost, context
  window, max output, supported-params flags, updated continuously).
- *Guarantee*: cost/context/max-output/supported-param facts arrive as data
  from a genuinely maintained source, reconciled into cluster facts like any
  other manifest; our builder, our errors, our transport.
- *Cost/risk*: a vendoring + reconcile slice (defer until a consumer exists —
  spend reporting or an automatic `max-tokens` floor). Risk is stale-pin drift,
  handled by the standing upstream-delta sweep.
- *Operational trade-off*: someone else maintains model *facts*; we maintain
  *wire mechanics*. That is the correct split — model facts are a large
  changing table (their comparative advantage), wire mechanics are 200 lines
  we must be able to debug at 2am (ours).
- *Capability given up*: nothing; it is additive and gated on a consumer.

**Verdict.** Do (B) now as the body of this design. Keep (C) as the queued
follow-on the day a cost/limit consumer exists, sourced from upstream LiteLLM's
JSON, not from litellm-clj. Reject (A): it trades our payment-safety evidence
and our error model for a table that is already wrong for our default model.

---

## Part 2 — the settings mechanism

### The shape in one paragraph

Every model/request setting is registered exactly once as a `:seon.config.ai*`
dial. `seon.schema.edn/derive-config-forms` already folds the dial set into
`:seon.config/manifest`, `:seon.config/effective`, and `:seon.config/entity`;
it gains **one more derived composite**, `:seon.config/agent-overlay`, built
from the same fold with every entry optional. The agent entity carries the
**same attribute idents** — Datahike attributes are global, an agent is its
attributes, and absence means inherit. One pure function merges cluster row
under agent overlay and projects targets + strategy. It is called at exactly
one place: the loop's `:call` branch, once per turn.

### Layer 1 — shipped defaults → cluster facts (exists; extend the inventory)

Unchanged mechanism: `config/default.edn` decides every dial, a sparse cluster
manifest overlays it, `compile-manifest` validates and reconciles one row.
What changes is only how many dials there are.

### Layer 2 — per-agent overrides (new)

A dial declares itself overridable with one property on its registration:

```clojure
:seon.config.ai/thinking
[:and {:seon.config/optional true :seon.config/per-agent true} :seon.ai/thinking]
```

`derive-config-forms` gains one branch:

```clojure
:seon.config/agent-overlay
(into [:map {:closed true}] per-agent-entries)   ; every entry {:optional true}
```

No hand list anywhere: the overlay schema, the admissible attribute set, and
the merge domain are all the same fold over the same registrations. Adding a
setting is one registration plus one `config/default.edn` line; the agent
override surface follows automatically.

**Why the same idents on the agent entity, not a component settings entity.**
A component ref would mean two spellings for one meaning (`:seon.config.ai/model`
on the config row, something else on the settings entity), a join to read, and
a second place for the merge to go wrong. The EAV model already says an entity
*is* its attributes; `:seon.config.ai/model` on an agent entity is the same
fact about a different subject. It also makes the override queryable directly
("which agents override the model?" is one `:where` clause, no join) and makes
retraction ordinary. The cost is that these attributes now appear on two kinds
of subject — which is fine, because Seon has no kinds.

**Why not per-agent `:seon.config/model-variant` named sparse maps** (the State A
precedent, `docs/seon/issues/archive/per-agent-model-transport-overrides.md:45-51`):
that mechanism exists to let a *caller* select a bundle at birth without seeing
provider details. It is a birth-time convenience over the same attributes,
never a second resolution path. It is queued, not designed here; if it returns
it must copy resolved attributes into the birth transaction exactly as State A
did, and the selector must remain unstored.

### Layer 3 — the request itself

Nothing. There is no third layer. A turn does not carry ad-hoc settings; if a
call needs different settings, that is a fact about the agent.

### Resolution — one function, defined order, one call site

```clojure
(defn settings
  "The resolved model settings for one agent. PURE.
  Two layers at runtime, because the third is already folded in: the
  shipped default was resolved into the cluster row at apply! time.
  Agent absence means inherit — never nil, never a sentinel."
  {:malli/schema [:=> [:cat :seon.config/effective :seon.config/agent-overlay]
                  :seon.config/effective]}
  [cluster-dials agent-overlay]
  (merge cluster-dials agent-overlay))
```

`targets` and `retry-strategy` are unchanged: they still take one effective
map. The only new function above them reads the agent's overlay:

```clojure
(defn agent-overlay
  "One agent's declared setting overrides, from a database value."
  [db agent-id] ; select-keys over the derived per-agent attribute set
  ...)
```

**The one call site.** Today `loop-handle` (`cluster.clj:1042-1057`) captures
`(ai/targets dials)` and `(ai/retry-strategy dials)` into the proc handle **at
boot**. That must move. The handle keeps the connection and cluster name; the
`:call` branch resolves per turn:

```clojure
(let [dials     (config/effective db cluster-name)
      resolved  (ai/settings dials (ai/agent-overlay db agent-id))
      targets   (ai/targets resolved)
      strategy  (ai/retry-strategy resolved)]
  …)
```

Two things fall out for free. Per-agent settings become possible at all. And
the acquisition boundary changes from **boot-captured** to **live**: changing
the model dial today requires a cluster restart, and after this it applies on
the next turn. Document that boundary in `loop.cljc` and test it — the config
skill's rule (`.agents/skills/seon-context-config/SKILL.md:87-104`) is that
every new dial states its acquisition boundary.

Cost of the live read: one `d/pull` of the config row plus one of the agent
entity per turn, against a value we already hold. Negligible beside a paid
remote call. Do not cache it; a cached resolution is stored-derived state.

### Uniform overridability

**Recommendation: every AI dial `targets`/`retry-strategy` reads is
per-agent-overridable, with no curated exceptions.** Any split ("model yes,
endpoint no") is a hand list, and the archived State A issue records exactly
why the split fails: two agents on different OpenAI-compatible gateways in one
cluster could not derive independent requests. See Q3 for the security edge
this opens once agents can write their own facts.

---

## Part 3 — the complete settings inventory

Layer column: **C** = cluster dial, **A** = per-agent overridable (all C are
also A under the uniform recommendation). Status: **landed** / **rename** /
**new** / **deferred**.

### Descriptor-row fields (travel with the target)

| Setting | Attribute | Schema | Consumer | Status |
|---|---|---|---|---|
| Endpoint | `:seon.config.ai/endpoint` | `[:string {:min 1}]` | `targets` → `:seon.ai/endpoint` | landed |
| Model | `:seon.config.ai/model` | `[:string {:min 1}]` | `targets` → wire `model` | landed |
| Credential variable NAME | `:seon.config.ai/api-key-variable` | `[:string {:min 1}]` | `credential` at the leaf | landed |
| Explicit no-auth | `:seon.config.ai/no-auth` | `[:= true]`, optional | `complete` | landed |
| Deadline | `:seon.config.ai/timeout-ms` | `[:int {:min 1}]` | JDK request timeout | landed |
| Backup model / endpoint / credential / timeout | `:seon.config.ai.backup/*` | optional overrides | `targets` backup role | landed |

The backup family stays *overrides over the primary* — a backup exists exactly
when `:seon.config.ai.backup/model` is present. That rule survives per-agent
resolution unchanged, because the merge happens **before** `targets` projects
either role.

### Request-shaping settings (the new inventory)

| Setting | Attribute | Schema | Wire | Consumer | Status |
|---|---|---|---|---|---|
| Output budget | `:seon.config.ai/max-tokens` | `[:int {:min 1}]` | `max_tokens` | `request-body` | landed |
| Thinking + effort | `:seon.config.ai/thinking` | `[:enum :disabled :low :high :max]`, optional | `thinking.type` + `reasoning_effort` | `request-body` | landed (this lane) |
| Temperature | `:seon.config.ai/temperature` | `[:double {:min 0.0 :max 2.0}]`, optional | `temperature` | `request-body` | new — **inert under thinking** |
| Top-p | `:seon.config.ai/top-p` | `[:double {:min 0.0 :max 1.0}]`, optional | `top_p` | `request-body` | new — inert under thinking |
| Frequency penalty | `:seon.config.ai/frequency-penalty` | `[:double {:min -2.0 :max 2.0}]`, optional | `frequency_penalty` | `request-body` | new — inert under thinking |
| Presence penalty | `:seon.config.ai/presence-penalty` | `[:double {:min -2.0 :max 2.0}]`, optional | `presence_penalty` | `request-body` | new — inert under thinking |
| Stop sequences | `:seon.config.ai/stop` | `[:vector {:min 1 :max 4} [:string {:min 1}]]`, optional | `stop` | `request-body` | new |
| Structured output | `:seon.config.ai/response-format` | `[:enum :json-object]`, optional | `response_format` | `request-body` | new — see Q2 |
| Vendor passthrough | `:seon.config.ai/extra-body-edn` | `[:string {:min 1}]`, optional | merged last | `request-body` | new — the escape hatch |

Bounds come from the vendor/`litellm.specs` ranges (`specs.clj:42-48`), which
agree with the OpenAI-compatible contract.

### Deliberately NOT declared

| Setting | Why |
|---|---|
| `stream` | A transport choice the loop makes from whether a sink exists (`loop.cljc:1090`), not a policy. Making it a dial would let configuration break streaming. |
| `tools` / `tool-choice` | Seon's reply path is code evaluation, not tool-calling. **No dial without a consumer.** When a tool path lands, it declares its own. |
| `seed`, `user`, `prompt_cache_key`, `logprobs` | No consumer today; `extra-body` covers experimentation without a schema commitment. |
| Retry family per call | `:seon.config.ai.retry/*` stays exactly as it is — six dials, cluster-declared, per-agent overridable by the uniform rule. |
| Cost, rate limits, context window | Model *facts*, not settings. Option (C) territory: reconcile them from a maintained registry when a consumer exists. |

---

## Part 4 — passthrough and the honesty surface

### The escape hatch

`:seon.config.ai/extra-body-edn` is an EDN map string (EDN because the value is
an open, vendor-owned document; same precedent as `usage-edn`). `request-body`
merges it **last**, and refuses protected keys:

```clojure
;; stolen from openai_compatible.clj:11-29, minus the throw
(def ^:private builder-owned-keys
  #{"model" "messages" "stream" "max_tokens" "authorization"})
```

An extra-body that names a builder-owned key produces a flat
`::extra-body-conflict` error value at resolution time — before any paid call,
never a throw, and never a silent override. Keeping the protected set derived
from what `request-body` actually emits (rather than a parallel literal) is a
detail for the implementer; a literal set that drifts from the builder is the
predictable defect here.

### The honesty question

The vendor **silently ignores** `temperature`, `top_p`, `presence_penalty`,
and `frequency_penalty` in thinking mode
(`research/deepseek-thinking-mode-api-2026-08-01.md`, confirmed live). A
builder that sends temperature under thinking is lying to its caller about
control it does not have.

**Design: never send an inert parameter, and record that it was requested.**
One pure function owns the whole question:

```clojure
(defn wire-settings
  "Split resolved settings into what the wire will honour and what it
  will ignore, for this model and this thinking state. PURE, and the ONE
  place inertness is decided — a second site is how the receipt starts
  disagreeing with the request."
  {:malli/schema [:=> [:cat :seon.ai/request] :seon.ai/wire-settings]}
  [request] ; => {:seon.ai/sent {...} :seon.ai/inert #{...}}
  ...)
```

`request-body` emits only `:seon.ai/sent`. The attempt row gains **one**
observation:

```clojure
:seon.ai.attempt/settings-edn  ; the resolved settings map, canonical EDN
```

Not the inert set — that is a pure function of `settings-edn` plus the row's
`:seon.ai/model`, derived at read exactly as the row's existing docstring
requires ("the row stores OBSERVATIONS only"). "Requested but inert" is
therefore a query, not a stored label, and it stays correct if our
understanding of the vendor's inertness changes.

**Where the agent sees it.** Derived, never stored: a render function that
queries the agent's resolved settings and shows the inert subset, omitting
itself when the set is empty (the standing derive-warnings rule). An agent
that sets temperature and gets thinking mode learns it from its own context,
not from a log nobody reads.

**Is inertness a hand list?** Partly, and the honest framing matters: it is a
*vendor-documented contract*, captured with provenance in the research doc and
cited at the one site that uses it — the same status as `status-class`'s HTTP
mapping (`ai.cljc:338-352`). It becomes a genuine hand list the moment a second
provider needs a different answer; that is the trigger to move it into the
descriptor row as data (`llm-adapters.md:16-41`), and Q2 asks the owner whether
to pay for that now.

---

## Part 5 — migration, sized as slices

Each slice is independently landable and independently provable.

**S1 — the derived overlay (schema mechanism only).** Add
`:seon.config/per-agent` to `config-dial?`'s properties handling and derive
`:seon.config/agent-overlay` in `derive-config-forms`
(`src/seon/schema/edn.clj:61-117`). Mark the existing AI dials per-agent. No
behavior change; proof is a schema test that the overlay's key set equals the
per-agent dial set and every entry is optional.

**S2 — resolution and the one call site.** Add `seon.ai/settings` and
`seon.ai/agent-overlay`. Remove `(ai/targets dials)` and
`(ai/retry-strategy dials)` from `loop-handle` (`cluster.clj:1042-1057`);
resolve in the `:call` branch (`loop.cljc:1065-1080`). Proof: a live cluster
where two agents resolve different models from one config row, and a live
proof that changing the cluster dial applies on the next turn without a
restart. Delete the boot-capture path in the same commit.

**S3 — the request-shaping inventory.** Register temperature/top-p/penalties/
stop/response-format with bounds; add `config/default.edn` decisions (all
`:seon.config/absent` — the shipped default must remain "the provider's
default", because inventing a temperature is inventing a magic number). Extend
`:seon.ai/request` and `:seon.ai/target`. Proof: generative round-trip that
`request-body` emits exactly the declared settings and nothing else.

**S4 — `wire-settings` and the honesty surface.** The split function, the
`settings-edn` observation on the attempt row, the derived inert projection,
and the render block. Proof: a live thinking-mode call with temperature set
whose attempt row shows temperature requested and the wire document shows it
absent.

**S5 — `extra-body-edn` and protected keys.** Last, because it is the one that
can break a request at runtime. Proof: an extra-body naming `model` produces a
flat error before any call.

**S6 (queued, gated on a consumer) — option (C).** Vendor upstream LiteLLM's
`model_prices_and_context_window.json` under `reference-code/`, reconcile the
slice we need into facts.

### What the thinking lane's surface becomes

**Unchanged, and it is the first instance of the pattern.**
`:seon.config.ai/thinking` is already an optional dial whose absence inherits
the provider's default; `:seon.ai/thinking` is already a single value making
contradictory pairs unrepresentable; `targets` already carries it through
(`ai.cljc:123-124`). S1 adds `:seon.config/per-agent true` to its registration
— one property — and it is per-agent overridable. Nothing is renamed and
nothing is rewritten. That is the check that this design is a generalization of
what landed rather than a replacement for it.

### Files touched

| File | Slice | Change |
|---|---|---|
| `src/seon/schema/edn.clj` | S1 | one derived composite, one property |
| `resources/seon/schema/config.edn` | S1,S3 | `:seon.config/per-agent` marks; new dials |
| `resources/seon/schema/ai.edn` | S3,S4 | request/target entries; `wire-settings`; `settings-edn` |
| `config/default.edn` | S3 | new decisions, all `:seon.config/absent` |
| `src/seon/ai.cljc` | S2-S5 | `settings`, `agent-overlay`, `wire-settings`; `request-body` extended |
| `src/seon/cluster.clj` | S2 | `loop-handle` stops capturing targets |
| `src/seon/cluster/loop.cljc` | S2,S4 | resolve per turn; record `settings-edn` |
| `resources/seon/schema/agent.edn` | — | **no change**: the agent carries config idents, which are already registered |

---

## Part 6 — crash walk

Resolution is derived per call from facts; nothing is stored-derived.

| Kill point | What survives | What is lost | Correct? |
|---|---|---|---|
| Before resolution | agent + config facts | nothing | Yes — next turn re-derives. |
| After resolution, before prompt capture | facts | the resolved value (a pure function of facts) | Yes — recompute is free and identical. |
| After capture, before the attempt row | the capture | the settings actually used, if a call fired | Same as today's row 3: "the call was never recorded", not "never happened". Unchanged by this design. |
| After the attempt row | `settings-edn` + usage + evidence | — | Yes — the receipt says what was sent. |
| **Settings changed mid-turn** (operator or agent writes an override while a turn is in flight) | both bases | — | The turn resolved ONCE, before capture, and **failover and backoff reuse that same resolved value** — exactly as they reuse the one prompt capture (`loop.cljc:1042-1052`). A backup call must not silently run different settings than the primary it is replacing. The new settings apply on the next turn. |
| Settings changed between attempt 1 and a later run | facts | — | Later runs resolve fresh; the attempt chain shows the change as different `settings-edn` values per row, which is the forensic property we want. |

The rule this encodes: **one turn, one resolution, recorded**. That is the same
discipline as the prompt capture, for the same reason.

---

## Part 7 — open questions for the owner

**Q1 — Uniform overridability, or a curated subset?**
*Recommendation: uniform.* Every AI dial is per-agent overridable, no
exceptions, because any subset is a hand list and the State A archive records
the split failing (two gateways in one cluster). The cost is that `endpoint`
and `api-key-variable` become per-agent, which is what Q3 is about.
Alternative: mark only request-shaping settings per-agent and keep the
descriptor row cluster-wide — simpler blast radius, re-opens the exact gap
State A closed.

**Q2 — Is inertness a per-provider descriptor fact now, or a documented
constant at the one site?**
*Recommendation: a constant at the one site, with a cited capture.* We speak to
one provider family; the descriptor-row generalization is real work
(`llm-adapters.md:16-41`) with no second consumer to justify it, and the
trigger to pay for it is unambiguous — the day a second provider's inert set
differs. Alternative: build it as descriptor data now, paying a day to avoid a
later refactor of a mechanism we are already touching.

**Q3 — When agents can write their own facts (ruling #31 / the write-surface
hole), may an agent set its OWN `endpoint` and `api-key-variable`?**
This is the sharp one. Under uniform overridability plus a general write
surface, an agent could set `:seon.config.ai/endpoint` to a server it controls
while `:seon.config.ai/api-key-variable` still names `DEEPSEEK_API_KEY` — and
the leaf would faithfully send our credential to that endpoint. Nothing in the
current design prevents it, and it is not hypothetical once agents write facts.
*Recommendation: split the settings by whether they can move a credential.*
Request-shaping settings (thinking, temperature, max-tokens, stop, penalties,
extra-body) are agent-writable. The credential-bearing pair (`endpoint`,
`api-key-variable`, and the backup equivalents) is **operator-writable only** —
enforced as an admission rule at the write surface, derived from a
`:seon.config/credential-bearing true` property on the registration, not a
name list. This is not "grants" and does not touch ruling #20: every agent may
still *call* everything; this bounds one specific effect — where our credential
is sent. Alternatives: allow it and rely on receipt forensics (fast, and we
find out afterwards), or forbid per-agent endpoint entirely (re-opens the
State A gap).

**Q4 — Should `max-tokens` acquire a floor derived from the model?**
The audit's actual damage was a starved budget under thinking, not only a
missing toggle. A model registry (option C) could supply each model's real max
output, and `request-body` could refuse a configured budget that starves
thinking. *Recommendation: not yet* — one dial, one honest default (65536 as
landed), and revisit when option (C) lands a registry. Building a derived floor
without the registry means inventing the numbers, which is the failure mode we
are trying to leave.

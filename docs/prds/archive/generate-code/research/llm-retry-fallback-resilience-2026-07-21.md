---
type: research
status: active
tags: [research, agent]
---

# LLM retry and provider fallback resilience — current state and design

Audit date 2026-07-21, branch `codex/runtime-reliability-refactor`. Question:
do we need a Clojure retry package, how do we survive an overloaded
Moonshot/Kimi, and should agents carry a configured fallback model.

## Headline answers

1. **Retry with exponential backoff + jitter + Retry-After already exists and
   is good.** `seon.retry` (`src/seon/retry.cljc`) is a deliberate async
   errors-as-values port of the `again` library's strategy combinators
   (vendored at `reference-code/again/`), and `seon.agent.turn/call-llm!` is
   the enforced sole retry authority. Do NOT adopt a retry package: `again`
   and diehard/Failsafe are JVM-only (Thread/sleep, thrown Exceptions) and
   every LLM call runs in the Bun CLJS pod; the port already exists and is
   ~90 lines of pure combinators plus one `^:async` executor.
2. **The real gap is not retry — it is (a) timeout classification and (b) the
   absence of any fallback provider.** The observed Kimi failure mode is the
   300 s attempt timeout, which `llm-retryable?` deliberately does NOT retry
   ("already burned its full budget"), so today a busy Kimi fails the planner
   turn on the first attempt, the run closes `:error`, and the generated root
   strands `:open` (issue
   `docs/seon/issues/generated-root-has-no-planner-retry-path.md`).
3. **Fallback should be data on the existing per-agent transport overlay**,
   consulted only inside `call-llm!` after same-provider retries exhaust (or
   on an attempt timeout), with provenance already free — per-attempt rows
   (`:seon.ai.attempt/*`) already record which provider/model served each
   attempt.

## 1. Current state map (file:line)

### Where the call executes

- Bun CLJS pod only. `seon.ai.openai-compat/make-client`
  (`src/seon/ai/openai_compat.cljs:350-359`) constructs the official `openai`
  Node SDK client with `maxRetries 0`; `complete` (`:432-562`) does the
  fetch via the SDK (`.create` / `.stream`). `anthropic.cljs` mirrors it.
  Adapter selection: `seon.ai.dispatch/adapter`
  (`src/seon/ai/dispatch.cljs:40-67`).
- The JVM side (`src/seon/db/*.clj`, `embed.clj`) makes **no LLM calls**
  (embeddings only, separate Vertex path). diehard has no call site to serve.

### The retry stack (BUILT)

| Layer | Owner | Detail |
|---|---|---|
| Strategy combinators | `src/seon/retry.cljc:54-131` | constant/additive/multiplicative builders; jitter, per-wait clamp, `max-retries`, `max-duration` (total backoff ceiling) |
| Executor | `src/seon/retry.cljc:169-195` | `^:async with-retry!`; thunk never throws; `:seon.retry/retry?` predicate; `:seon.retry/override` (Retry-After); `:seon.retry/on-retry` log hook; returns `{result retries}` |
| Tuning | `src/seon/agent/turn.cljs:707-712` | base 500 ms × 2, jitter 0.5, per-wait clamp 20 s, total cap 60 s, default 4 retries |
| Retry count config | `:seon.ai/agent-max-retries` per agent (turn.cljs:741; `config/system.edn:297,305` — both shipped variants set 1) |
| Classifier | `llm-retryable?` `src/seon/agent/turn.cljs:714-729` | retry iff `:seon.ai/transport?`, status 429, or 5xx |
| Per-attempt fence | `bounded-llm-attempt!` `turn.cljs:845-888` | fresh `AbortController` per attempt, `race-timeout` against frozen `:seon.ai/agent-attempt-timeout-ms` (default 120 s; planning variant 360 s); timeout resolves to an error VALUE `{:seon.ai/timeout? true}` |
| Retry-After honor | `turn.cljs:918-921` + `seon.ai/parse-retry-after-ms` / `error-retry-after-ms` (`src/seon/ai.cljs:197-231`) | server header overrides the strategy delay, clamped to 20 s |
| Attempt provenance | `attempt-row` `turn.cljs:753-827`; schema `turn.cljs:96-149` | one queryable component row per attempt: ordinal, provider, adapter, requested-model, response-model, request-id, system-fingerprint, error-status, finish-reason, both timeout bounds, outcome, usage |
| Exhaustion surface | `ask-and-eval!` `turn.cljs:956-968` | turn closes `:seon.agent.turn/status :error` + `:seon.agent.turn/error` + attempts + `:seon.agent.turn/llm-retries` |
| Loop reaction | `src/seon/agent/loop.cljs:460-497` | turn `:error` → close run `:error`. **No higher-level re-drive** of the LLM call; the run halts as designed |

### What happens today per failure class

| Event | Adapter result | Retried? | Where it surfaces |
|---|---|---|---|
| HTTP 429 | `:seon.ai/status 429` (+ `:retry-after-ms` when header present) — `error->envelope` `openai_compat.cljs:313-339` | YES, with server Retry-After | attempt row `error-status 429`; on exhaustion turn `:error` |
| HTTP 5xx | `:seon.ai/status 5xx` | YES | same |
| Network reset / DNS | `APIConnectionError` → `:seon.ai/transport? true` | YES | same |
| SDK/adapter timeout, user abort | `APIConnectionTimeoutError`/`APIUserAbortError` → `:seon.ai/timeout? true` | **NO** | attempt row `:adapter-timeout`; turn `:error` first attempt |
| Outer attempt-cap timeout (the live Kimi case) | synthesized `{:seon.ai/timeout? true}` (`turn.cljs:878-884`) | **NO** | attempt row `:outer-timeout`; turn `:error`; generate-code root strands |
| Mid-stream failure (`:stream` mode) | `stream-until-form!` catches to a value, re-thrown into `error->envelope` (`openai_compat.cljs:370-416,528-532`); partial text discarded | per resulting class (usually transport → YES) | fresh attempt re-sends the frozen prompt from scratch |
| 400 / 401 / 402 / 403 / 404 | `:seon.ai/status n` | NO (correct — incl. the DeepSeek 402 mid-run payment case) | turn `:error`, msg names the HTTP status |
| Truncation with NO visible text (Kimi length-limited reasoning-only; DeepSeek false-done family) | error value "exhausted the configured completion limit before returning visible text" (`openai_compat.cljs:292-295`), usage retained | NO (no status/transport flag) | turn `:error`; live-proven at `6e3b741d` (32-token K3 probe) |
| Empty content but reasoning present, not truncated | logged (`openai_compat.cljs:280-288`); turn-outcome guard re-prompts | n/a | next-turn re-prompt, not a transport retry |
| Config gap (no key / no base-url) | plain-msg error, deliberately never transport-flagged (`config-error`, `openai_compat.cljs:304-311`) | NO | turn `:error` with remediation text |

### Kimi K3 live timeout evidence

- `docs/prds/generate-code/roadmap.md:53-56`: live graduation birthed the
  planner on Kimi K3 "and, after two live K3 timeouts, the Muse gateway at
  ~6–9 s replies".
- `docs/seon/issues/generated-root-has-no-planner-retry-path.md`: root
  `tn6d6i8ywnek`, planner `red-pugs-spend`, "OpenAI-compat request timed out /
  aborted" after the 300 s planning cap, turn `:error` → run `:error`, root
  stranded `:open` (log ref inside the issue). Three strand causes fixed
  2026-07-21; the **no-reply strand** (planner fails before any publication)
  remains open — that is exactly the "busy provider fails the turn" gap.
- Shipped `:planning` variant (`config/system.edn:283-297`): kimi-k3,
  `max_completion_tokens` 16384, adapter timeout 300 s, outer fence 360 s,
  max-retries 1.
- `docs/seon/reference/llm-adapters.md:221-245` + issue
  `kimi-k3-continuation-compatibility.md`: K3 config truth (always reasons,
  only `max` effort, `MOONSHOT_API_KEY`, no Kimi-specific retry path needed).
- Planner retry stance (roadmap Stage 8 exits, `:766-788`): provider
  failures/empty K3 output/deadlines are error values; retries are among the
  recorded run metrics; K3 invoked only when planning explicitly selects it.

## 2. Option analysis — "should we use a Clojure retry package?"

### liwp/again as a dependency — first-class evaluation (owner's suggestion)

The maintained source is already mirrored per repo policy at
`reference-code/again/` (HEAD `0b4f195`, 2026-06-25; latest release 2.0.0,
2026-06-13 per its `CHANGELOG.md`) and was read for this evaluation. Verdict:
**no as a pod dependency — it does not run in ClojureScript at all; and there
is no JVM LLM call site to scope it to.** Specifics:

1. **No ClojureScript support.** The entire library is one JVM file,
   `reference-code/again/src/again/core.clj` — no `.cljc`/`.cljs` anywhere in
   the checkout. Concrete JVM-only forms: `(Thread/sleep (long delay))` in
   the private `sleep` (`core.clj:112-118`), `System/currentTimeMillis`
   (`:90`), and `catch Exception` / `InterruptedException` with
   `.interrupt (Thread/currentThread)` in the executor (`:151-169`). None of
   these compile under CLJS.
2. **Synchronous-only; async/promise operations are not supported even
   incidentally.** `with-retries*` (`core.clj:130-174`) calls `(f)` inline
   and treats any non-throwing return as success. A `^:async`
   promise-returning `f` returns a Promise object immediately — the executor
   would record `:success` on attempt 1 before the request ever settles, and
   a later rejection would be invisible to it. Failure detection is
   exclusively `catch Exception`, the opposite of the pod's errors-as-values
   contract. Both properties disqualify it for every LLM call site.
3. **Strategy vocabulary IS pure data** — the good part, and it is already
   ours. A strategy is a plain (possibly lazy/infinite) seq of millisecond
   delays built/manipulated by pure fns (`core.clj:3-110`); the options map
   (`::again.core/strategy`, `::callback`, `::user-context`,
   `::wall-clock-timeout`) is ordinary data. `seon.retry`
   (`src/seon/retry.cljc:18-25`) is a faithful port of exactly these
   combinators with only the executor rewritten `^:async` +
   errors-as-values (and `:seon.retry/retry?`/`::override` replacing the
   exception-based `::callback`/`::fail` protocol). Adopting the lib would
   duplicate the combinators we already own while the part we need
   replaced — the executor — is the part the lib cannot provide.
4. **Maintenance is healthy** (active commits through 2026-06; 2.0.0 added
   `max-wall-clock-duration` and a circuit breaker,
   `core.clj:227-431`), so tracking its design upstream via the existing
   mirror remains the right relationship: `seon.retry`'s docstring names the
   breaker as the FUTURE composable manipulator, and the vendored 2.0.0
   source is now the concrete port basis if attempt-row evidence ever
   justifies one (its state machine is a CAS'd atom + pure `decide-*` steps,
   `core.clj:291-359` — straightforwardly portable).
5. **JVM-side scope:** there are no JVM LLM calls (Section 1), so there is
   nothing to scope `again` (or diehard) to on that side. If a JVM boundary
   ever needs generic retries, `again` would be the natural pick there — with
   the mirror already in place.

### Remaining options

| Option | Verdict |
|---|---|
| diehard (Failsafe) | **No.** JVM-only; zero JVM LLM call sites exist. |
| promesa or another CLJS promise lib | **No.** Not vendored in `reference-code/`, would add a second async idiom beside the pod's native `^:async`/`await`, and offers nothing `seon.retry` lacks. Repo policy (one mechanism; deps must be mirrored and read first) both point the same way. |
| Strengthen `seon.retry` in place | **Yes.** The one candidate addition is the circuit breaker now present in vendored `again` 2.0.0 (see above). During a sustained Kimi outage every planner call independently burns its backoff budget; a consecutive-failure trip + half-open probe (or, simpler, the fallback below) is the graceful-degradation piece. |

**Recommendation:** keep `seon.retry` exactly as the mechanism; no new
dependency. Spend the effort on (a) timeout classification for fallback
eligibility and (b) the data-configured fallback in `call-llm!`.

## 3. Retryability classification (target)

Errors are already values with a uniform envelope (`:seon.ai/error` carrying
optional `:seon.ai/status`, `:seon.ai/transport?`, `:seon.ai/timeout?`,
`:seon.ai/retry-after-ms`); both adapters share `error->envelope` branch
order (timeout/abort → connection → APIError-with-status → plain). Provider
awareness is already handled *structurally*: the official SDK normalizes
every provider's non-2xx into an APIError subclass carrying `.status` and
headers, so DeepSeek, Moonshot, and the Muse gateway all land in the same
classified shape. No per-provider conditional is needed or wanted
(`src/seon/ai/AGENTS.md`: never add call-site provider conditionals). No
Moonshot-specific 429 body shape is recorded anywhere in the repo — do not
special-case one without a captured attempt row proving the need.

| Error shape | Same-provider retry | Fallback-eligible (proposed) |
|---|---|---|
| `:seon.ai/transport? true` (connection reset, DNS) | yes (today) | yes |
| status 429 | yes (today, Retry-After honored) | yes, after exhaustion |
| status 5xx | yes (today) | yes, after exhaustion |
| `:seon.ai/timeout? true` (adapter or outer cap) | no (today, keep) | **yes — the live Kimi case; this is the change** |
| status 400/401/403/404 | no | no (a fallback would mask a real config/request bug) |
| status 402 (DeepSeek payment mid-run) | no | no — surface to owner; a silent fallback would hide a billing stop |
| truncation-without-text, refusal, unparseable | no | no (model-behavior, not transport; the turn/outcome guards own it) |
| config gap (no key/base-url) | no (never transport-flagged) | no |

## 4. Fallback design sketch (data, not mechanism)

Per-agent transport config already exists as ordinary optional attributes
copied at birth: `:seon.ai/agent-provider`, `/agent-model`, `/agent-base-url`,
`/agent-api-key-env`, … (`src/seon/ai/AGENTS.md:22-33`), with named sparse
variants in `config/system.edn:282-305` selected by
`:seon.config/model-variant` at every birth function
(`src/seon/agent.cljs:422-433,555-582`). **No fallback attribute exists today**
(`rg model-fallback` → nothing).

Proposed shape — reference the existing named variants rather than inlining a
second transport row:

```clojure
;; on the agent entity (birth-copied like every other :seon.ai/agent-* attr)
:seon.ai/agent-fallback-variants [:execution]   ; ordered, usually length 1

;; config/system.edn — the planning variant carries its own degradation
:seon.config/model-variants
{:planning  {:seon.ai/agent-provider :openai-compat
             :seon.ai/agent-model "kimi-k3"
             ...
             :seon.ai/agent-fallback-variants [:planning-fallback]}
 :planning-fallback {:seon.ai/agent-provider :deepseek
                     :seon.ai/agent-model "deepseek-v4-pro" ...}}
```

Semantics:

- **Resolution is frozen with the turn.** The turn's one config acquisition
  resolves the primary AND each fallback into complete immutable
  `:seon.ai/config-resolution` values (same
  `seon.ai/resolved-config-from-rows` path). `call-llm!` never re-reads
  config mid-turn — this preserves the existing frozen-resolution invariant
  (`turn.cljs:834-843`).
- **One mechanism.** The fallback loop lives inside
  `seon.agent.turn/call-llm!` (the sole retry authority): an outer ordered
  reduction over resolutions, each run through the existing
  `retry/with-retry!` with its own strategy; advance to the next resolution
  only when the final result is fallback-eligible per the table above.
  Adapters stay single-attempt; `seon.ai.dispatch/adapter` is simply called
  per resolution. Nothing new in `seon.retry` is required.
- **Per-call only, never a silent switch.** The agent's configured attributes
  are untouched; the next turn starts again at the primary. Any "provider X
  keeps failing" signal is a DERIVED render over recent attempt rows
  (derive-don't-store), not a stored flag.
- **Provenance is already free.** `:seon.agent.turn/llm-attempts` rows carry
  provider/adapter/requested-model/response-model per attempt; fallback
  attempts just continue the ordinal sequence, so "which provider actually
  served the turn" = the provider of the last `:success` attempt row. Add at
  most `:seon.ai.attempt/fallback-ordinal` (which resolution served the
  attempt) for direct querying.
- **Mid-stream failure restarts from scratch.** The prompt text is frozen for
  the turn and partial `:stream` text is already discarded on failure
  (`::text ""` in the error branch), so the fallback attempt is a clean
  re-send. Cost note: the fallback provider has a cold prompt cache — one
  full-price prompt, acceptable for a degradation path.
- **Cost guard.** Default posture per owner budget rules: fall back DOWN or
  SIDEWAYS in price (DeepSeek is the cheap reliable default; Muse is the
  proven fast planner), never up to a premium model implicitly. Ordinary
  execution agents (already on cheap defaults) ship with NO fallback unless
  configured.
- **Bounds compose.** Each resolution keeps its own attempt fence and retry
  count; worst-case turn latency = sum over resolutions of
  (attempts × attempt-cap + backoff cap), all already bounded — with a
  length-1 fallback list the turn still cannot park the run.

## 5. Kimi/Moonshot specifics

- **Rate-limit/concurrency tiers: no numbers are recorded in this repo**
  (checked `docs/seon/reference/llm-adapters.md`, generate-code research, and
  issue notes). The doc cites the Kimi quickstart/pricing pages; if tier
  numbers matter for the planner's concurrency, capture them from
  `platform.kimi.ai` into the model catalog with a checked date first. Do not
  design against invented limits.
- Recorded overload evidence is timeouts, not 429s: two live 300 s planner
  timeouts on 2026-07-21, after which the operator manually switched the
  planner to Muse. That manual switch is precisely the fallback this design
  automates.
- **Yes, the planning role should carry a configured fallback.** Two
  candidates, both live-proven in Seon: `deepseek-v4-pro` (thinking-capable,
  cheap, DeepSeek default family) and the Muse gateway (~6–9 s planner
  replies during the 2026-07-21 graduation, and per standing owner ruling the
  default for agentic work). Owner picks; the mechanism does not care.

## Open owner decisions

1. **Timeout → fallback-eligible.** Confirm that an attempt-cap timeout,
   while still never retried same-provider, advances to the fallback
   resolution. This single change addresses the observed Kimi failures.
2. **Planning fallback target:** `deepseek-v4-pro` (asked in the prompt) vs
   Muse (live-proven planner, standing default for agentic work). Also
   whether the fallback keeps `:batch` repl-mode (it should — variant data
   already carries `:seon.config/repl-mode`).
3. **Fallback reference form:** named variant refs (recommended — one config
   authority, no duplicated transport rows) vs inline sparse maps on the
   agent.
4. **Default fallback for ordinary agents:** none (recommended), or a
   cluster-row fallback for 429/5xx exhaustion too.
5. **Circuit breaker:** defer (recommended) — per-call fallback plus bounded
   backoff already caps waste; port `again`'s breaker into `seon.retry` only
   if attempt-row evidence later shows sustained-outage backoff burn matters.
6. **402 visibility:** payment exhaustion stays non-retryable and
   non-fallback; decide whether it should additionally raise a derived
   owner-facing warning render (it currently surfaces only as the turn error).

## Related issues to update when implemented

- `docs/seon/issues/generated-root-has-no-planner-retry-path.md` — the
  no-reply strand; a planning fallback plus (separately) a bounded root-level
  re-drive closes it.
- `docs/seon/issues/kimi-k3-continuation-compatibility.md` — unchanged by
  this design; no Kimi-specific retry path is wanted.

---
type: research
status: completed
tags: [research, orchestrator]
---

# Config coherence audit (2026-06-28)

Owner-requested read-only audit of the Seon config surface: (1) docs
completeness, (2) `SEON_*` naming coherence + a rename map, (3) LLM-config
coherence/flexibility, (4) — the load-bearing question — can a custom
non-chat-completion **diffusion** API plug into the LLM layer, and what a new
adapter must implement.

All claims are grounded to `file:line`. READ-ONLY audit; no `src/` edits.

## TL;DR

- **Docs (1):** `.env.example` is honest about the *required* set (one LLM key)
  but is **materially incomplete** — ~20 env vars are read by live pod code and
  **absent** from `.env.example`, including operationally-important ones
  (`SEON_PROFILE`, `SEON_CONFIG`, `SEON_SKILLS_DIR`, `SEON_AI_MAX_RETRIES`,
  `SEON_INSTRUMENT`) plus the entire render-cap tuning family. No *required* var
  is undocumented; the gap is in optional knobs.
- **Naming (2):** The `SEON_AI_*` and `SEON_<DOMAIN>_DIR` families are coherent.
  The mess is the **render/output-cap family** — six+ vars across four prefixes
  (`RESULT_`, `STORE_`, `EVAL_`, `MESSAGE_`, `TRANSCRIPT_`, `VALUE_`) with three
  suffix conventions (`_CAP`, `_BUDGET`, `_RENDER_CAP`). The `ROOT` pair
  (`SEON_ROOT` / `SEON_RUNTIME_ROOT`) is opaque. Rename map below.
- **Consolidation is PARTIAL (key smell).** `seon.config`'s docstring claims
  "Nothing outside this ns reads `process.env` for a knob" — this is **false**.
  Render caps, agent-loop bounds, fs grants, brand, instrument, tile-SCI are all
  still read via direct `js/process.env` access in their own namespaces. So a
  rename is NOT "one place + env files" yet; finishing consolidation is the
  prerequisite that makes the rename cheap.
- **LLM config (3):** The `:seon.ai/config` singleton row is a genuinely good
  design — env-seeds-once / DB-owns / read-per-call (reactive) / precedence
  `opt > row > default`. It is flexible for chat-completion providers. Its limit:
  the vocabulary is **chat-tuned** (`temperature`, `thinking`, `max-tokens`,
  `messages`); the anthropic adapter already drops `temperature` as dead, so the
  row is a *union of provider knobs*, not a clean abstraction.
- **Diffusion (4): YES — a diffusion adapter can plug in TODAY**, without
  generalizing the seam first. The loop-facing contract is provider-agnostic
  (`(fn [ctx-string]) -> Promise<{:text "…"}>`); all chat-completion assumptions
  live *inside* the existing adapters (request-builder + response-parser are
  already adapter-private). The clean path is a **new `seon.ai.diffusion` ns**
  following anthropic/openai_compat, plus **three one-line registration edits**.
  Nothing in the shared seam must change first.

## (1) Config-docs verdict

`.env.example` (174 lines) is the sole config surface (no config file). It is
well-organized and correct about the **true required set**:

- **Required to run:** exactly ONE LLM API key for the active provider
  (`DEEPSEEK_API_KEY` by default — README.md:87, README.md:105-106). Everything
  else has a code default or is set by `bin/seon`. Honest and clearly marked.
- **Required-but-undocumented:** NONE. The one true requirement (the key) is
  documented.

**The gap: optional vars read by live code but absent from `.env.example`.**
Verified by cross-checking every `process.env` / `env-val` / `env-int` read in
`src/seon/` against `.env.example`:

| Var | Read site | Note |
|-----|-----------|------|
| `SEON_PROFILE` | config.cljs:129, system.edn:32 | **Operationally central** — selects the per-cluster manifest profile (`:default`/`:minimal`). Undocumented despite being the headline config-system seam. |
| `SEON_CONFIG` | config.cljs:139 | Manifest path override. Undocumented. |
| `SEON_SKILLS_DIR` | config.cljs:186 | Skill corpus dir. Undocumented. |
| `SEON_AI_MAX_RETRIES` | agent/turn.cljs:327 | LLM retry count. Undocumented (and not in the LLM section). |
| `SEON_INSTRUMENT` | instrument.cljc:174 | Instrumentation kill-switch (named in CLAUDE.md). Undocumented in `.env.example`. |
| `SEON_RESULT_VARS_CAP` | config.cljs:215 | Render/eval cap. Undocumented. |
| `SEON_STORE_EDN_CAP` | config.cljs:222 | " |
| `SEON_RESULT_BODY_RENDER_CAP` | config.cljs:229 | " |
| `SEON_EVAL_RENDER_CAP` | agent/ctx.cljs:391 | " |
| `SEON_MESSAGE_RENDER_CAP` | agent/ctx.cljs:445 | " |
| `SEON_TRANSCRIPT_CHAR_BUDGET` | agent/ctx/transcript.cljs:50 | " (and `_CHAR_` violates the token-reporting rule nominally) |
| `SEON_VALUE_MAX_DEPTH/KEYS/ITEMS/STRING/SHAPE_SAMPLE` | render/value.cljs:74-78 | value-renderer internals |
| `SEON_VALUE_VERBATIM_CAP` | render/value.cljs:87 | " |
| `SEON_VALUE_WIDTH` | render/value.cljs:290 | " |
| `SEON_DEFAULT_TURN_LIMIT` | agent/run.cljs:107 | work-bound override |
| `SEON_MAX_TURNS_PER_LOOP` | agent/run.cljs:105 (comment) | stale reference to a deleted loop |
| `SEON_TICK_MS` | agent/loop.cljs:494 | ticker cadence |
| `SEON_TEST_TIMEOUT_MS` | test/runner.cljs:353 | per-test bound |

Conversely, `.env.example` **over-documents** a few internal/JVM-track vars not
read by the active pod (`SEON_GYM_PAID`, `SEON_NREPL_PORT`, `SEON_CLJS_PROJECT`
have no `src/seon/*.cljs` read site). So the file drifts in both directions.

**Verdict:** docs are good on *required* but stale on *optional*. The fix is a
documentation pass (add the table above to `.env.example`, ideally only after
finishing consolidation so the read sites are stable), not a structural change.

## (2) Naming coherence + rename map

Full `SEON_*` surface enumerated from `bin/seon`, `bin/acme`, and all
`src/seon/*.cljs` reads. Coherent families (LEAVE AS-IS):

- `SEON_AI_*` (provider/model/temperature/max-tokens/thinking/timeout-ms/
  base-url/api-key-env/extra-body) — clean `SEON_AI_<KNOB>`. Even the subtle
  `SEON_AI_API_KEY` (the key) vs `SEON_AI_API_KEY_ENV` (the *name* of the var
  holding the key) is coherent and well-documented (.env.example:34-38).
- `SEON_<DOMAIN>_DIR` (`SEON_CLUSTER_DIR` / `SEON_LOG_DIR` / `SEON_PROC_DIR`) —
  consistent. `SEON_FS_*` (`ROOT`/`READ_ONLY`/`LOCK`) — clearly its own grant
  namespace.

### Rename map (ranked by worth-it)

**Note on cost:** the prompt assumed consolidation makes renames "one place + env
files." That holds only for vars routed through `seon.config` accessors
(`SEON_RESULT_VARS_CAP`, `SEON_STORE_EDN_CAP`, `SEON_RESULT_BODY_RENDER_CAP`,
`SEON_AI_*`). The others are still read at their own namespace's
`js/process.env` site — so each rename below also touches that read site. Pair
any render-cap rename with **finishing the consolidation** (route the remaining
caps through `seon.config`), which is the real win.

1. **(HIGHEST, but it's docs not rename)** Document the ~20 undocumented vars
   (see §1 table). Zero churn, removes the biggest real gap.

2. **(HIGH) Unify the render/output-cap family.** Six+ vars, four prefixes,
   three suffixes — genuinely confusing. Propose one `SEON_RENDER_*` family with
   a single `_CAP` suffix:
   - `SEON_RESULT_BODY_RENDER_CAP` → `SEON_RENDER_RESULT_CAP`
   - `SEON_EVAL_RENDER_CAP` → `SEON_RENDER_EVAL_CAP`
   - `SEON_MESSAGE_RENDER_CAP` → `SEON_RENDER_MESSAGE_CAP`
   - `SEON_TRANSCRIPT_CHAR_BUDGET` → `SEON_RENDER_TRANSCRIPT_CAP` (drops the
     token-rule-violating `_CHAR_` and the lone `_BUDGET` suffix)
   - `SEON_STORE_EDN_CAP` → `SEON_RENDER_STORE_EDN_CAP` (it's a pr-str display
     truncation)
   - `SEON_RESULT_VARS_CAP` → `SEON_EVAL_RESULT_VARS_CAP` (this one is a *count
     of live vars kept*, not a render width — keep it distinct from the render
     family; rename only to disambiguate from the render caps above)
   - `SEON_VALUE_*` (7 vars) → keep as the value-renderer sub-family, but they
     belong under the render umbrella conceptually; optionally
     `SEON_RENDER_VALUE_*`. Lower priority (internal, rarely set).
   Worth it: clarity gain is high and the family is just-touched. **Blocked on**
   finishing consolidation (these read at render/value.cljs + agent/ctx.cljs,
   not config.cljs).

3. **(MEDIUM) `SEON_RUNTIME_ROOT` → `SEON_ARTIFACT_ROOT`.** It is *the seon
   checkout that owns the COMPILED ARTIFACTS* (platform.cljs:77,
   .env.example:98-101), not "the runtime." The name actively misleads.
   Distinct from `SEON_ROOT` (the checkout/cwd root, bin/seon-set). Renaming
   conveys the real distinction.

4. **(LOW) `SEON_SOUL` (the disable toggle, ctx.cljs:265) → `SEON_NO_SOUL`** to
   match the existing `SEON_NO_AUTO_BOOT` negative-flag convention, and to stop
   it reading like a truncated `SEON_SOUL_FILE`.

5. **(SKIP) `SEON_DEFAULT_TURN_LIMIT` / `SEON_MAX_TURNS_PER_LOOP`** — the latter
   is only a *stale comment* referencing a deleted loop (agent/run.cljs:105).
   Delete the stale reference; no rename needed.

6. **(SKIP) `SEON_AI_*`, the `_DIR` family, `SEON_FS_*`** — already coherent.

## (3) LLM-config coherence + flexibility

The `:seon.ai/config` singleton row (identity `::id` = "config", ai.cljs:155)
carries up to nine attrs (ai.cljs:224-226): `::provider ::model ::temperature
::max-tokens ::thinking ::timeout-ms ::base-url ::api-key-env
::extra-body-edn`. Design (ai.cljs ns doc, lines 15-34):

- **Env seeds once → DB owns.** `sync!` (ai.cljs:478) seeds the row from
  `SEON_AI_*` only when unconfigured; later runtime transacts persist across
  reboots. Secrets are never stored — keys read live at call time.
- **Read per call (reactive-context).** Adapters call `ai/current` (ai.cljs:311)
  on every request; no cached atom. Precedence: explicit request opt > config
  row > shipped adapter default (anthropic.cljs:149-150,
  openai_compat.cljs:200-204).
- **Provider-agnostic system prompt.** `effective-system-prompt` (ai.cljs:399) =
  hardcoded `ctx/system-text` unless overridden; both adapters send the same.

**Verdict: coherent and flexible for chat-completion providers.** A deployment
retunes provider/model/budgets without forking an adapter. The clean limits:

- The vocabulary **assumes chat-completion**: `temperature` and `thinking` are
  chat concepts; `max-tokens` maps to a chat `max_tokens`; the system prompt
  becomes a `messages` system role / `:system` block. The anthropic adapter
  already **drops `temperature`** (sampling params 400 on Opus 4.7+/Fable —
  anthropic.cljs:60-61), so the row is already a *union of per-provider knobs*
  with attrs that are dead for some providers, not a clean shared abstraction.
- The only *generic* request door is `::extra-body-edn` (ai.cljs:187, env
  `SEON_AI_EXTRA_BODY`) → `config-extra-body` (ai.cljs:326), which adapters
  **merge into the chat-completion body**. It is an escape hatch for extra
  *chat* fields, not a re-shaping of the request.

### The adapter contract (traced loop → provider → loop)

1. **Loop side.** `seon.agent/run-turn-once!` takes an `llm-fn`. The required
   shape is `(fn [ctx-string]) -> Promise<{:text "…"}>` (anthropic.cljs:366-380,
   openai_compat.cljs:398-413). The loop reads only `:text` (plus an optional
   top-level `:seon.ai/error` and `:seon.ai/raw`). **It does NOT assume
   chat-completion.**
2. **Selection.** `seon.client/current-llm-fn` (client.cljs:1907) is a
   `(case (ai/provider) :anthropic … <else openai/deepseek>)` — a **hardcoded
   case**, not a registry/multimethod. Falls back to `stub-llm` when no key.
3. **Adapter side.** Each adapter ns exposes `agent-adapter` ([opts]) returning
   `(fn [ctx-text] …)`. Inside: a **private** request-builder (`request-params`,
   chat-shaped — anthropic.cljs:123, openai_compat.cljs:176), an SDK HTTP call
   (`.stream` + `.finalMessage` / `.finalChatCompletion`), and a **private**
   response-parser (`parse-completion`) that collapses the provider response to
   `:seon.ai/text`. Errors-as-values via `ai/log-error!` + `:seon.ai/error`.

**Contract to add a provider:** implement an adapter ns whose `agent-adapter`
returns `(fn [ctx] -> Promise<{:text "…" :seon.ai/raw … :seon.ai/error …?}>)`,
then register it at **three points**: (a) add the keyword to the `::provider`
enum (ai.cljs:160), (b) add a `parse-provider` case (ai.cljs:240), (c) add a
branch to `current-llm-fn` (client.cljs:1907). The request-builder and
response-parser are entirely adapter-owned — the shared seam imposes only the
fn-of-ctx → `{:text}` shape.

Minor smell: three edit points to add a provider is a mild "a registry would be
cleaner" signal, but at N=4 providers it's three one-line edits — not worth a
multimethod refactor.

## (4) The diffusion adapter seam — CAN it plug in, and how

**YES. A diffusion adapter can plug in today, with no prior generalization of
the seam.** The reasoning:

- The loop-facing contract is **provider-agnostic** — it needs only
  `(fn [ctx-string]) -> Promise<{:text "…"}>`. A diffusion model's "final
  denoised text" maps cleanly onto `:text`.
- Every chat-completion assumption (messages array, `temperature`/`thinking`
  fields, SDK `.stream`) lives **inside** the existing adapters, in their
  *private* `request-params` / `parse-completion`. There is nothing
  chat-shaped in the shared `seon.ai` seam that a diffusion adapter is forced
  through. The request-builder and response-parser are **already adapter-owned**
  — so the diffusion adapter simply writes its own.

### Cleanest path: a new `seon.ai.diffusion` ns (following anthropic/openai_compat)

The diffusion adapter:

1. **Builds its OWN request** — prompt + denoising/diffusion params. It reads the
   ctx-string and may reuse `ai/effective-system-prompt` (ai.cljs:399) +
   `ai/current` (ai.cljs:311) for model/base-url/timeout, but it is NOT obliged
   to use the OpenAI `messages` shape. It calls its own endpoint via `js/fetch`
   or a custom client (the openai/anthropic SDKs are not involved).
2. **Parses its OWN response** — token-stream-with-confidence or a non-message
   completion — down to `{:text "…"}`. Confidence/denoise metadata that the loop
   doesn't consume can be stashed under `:seon.ai/raw` / `:seon.ai/provider-fields`
   (additive, optional; the loop ignores it).
3. **Reuses the error envelope** — `ai/log-error!` + `:seon.ai/error` with
   `:timeout?`/`:transport?`/`:status` exactly like the existing adapters
   (anthropic.cljs:255, openai_compat.cljs:274).
4. **Exposes `agent-adapter`** returning `(fn [ctx-text] -> Promise<{:text …}>)`.

Then the same **three registration edits** as any provider: `::provider` enum
(ai.cljs:160), `parse-provider` (ai.cljs:240), `current-llm-fn` case
(client.cljs:1907) — plus a key/endpoint-configured predicate for the stub
fallback (mirror `openai/api-key-configured?`, openai_compat.cljs:168).

### What does NOT need generalizing first

- The loop contract, the errors-as-values envelope, the per-call config read,
  the system-prompt resolution — all reusable as-is.
- `temperature` / `thinking` config attrs — the diffusion adapter simply ignores
  them (precedent: anthropic ignores `temperature`).

### Where the diffusion knobs live (the one real decision)

Diffusion params (denoise steps, guidance scale, noise schedule) have **no
config-row slot**. Two options:

- **(a) Ride `::extra-body-edn`** (env `SEON_AI_EXTRA_BODY`, ai.cljs:187) — the
  diffusion adapter reads `ai/config-extra-body` (ai.cljs:326) and uses it as its
  param map. Works **today, zero `seon.ai` edits**. Caveat: extra-body was
  designed to be *merged into a chat body*; here the adapter just treats it as
  "my param map" — fine, because the adapter owns its request builder entirely.
- **(b) Add first-class `:seon.ai/*` diffusion attrs** — one `schema/register!`
  each + add to the `config-attrs` vector (ai.cljs:224) + `env-var-specs`
  (ai.cljs:261). More work, but makes diffusion knobs DB-owned/runtime-tunable
  like the chat knobs, and discoverable in the row.

**Recommendation for the diffusion agent:** start with path (a) (extra-body) to
prove the adapter end-to-end with zero shared-seam edits, then promote the
recurring knobs to first-class `:seon.ai/*` attrs (path b) once the param set
stabilizes. The prompt's worry that "the `extra-body` escape hatch isn't enough"
is only half-true: extra-body is enough as a *data carrier* because the diffusion
adapter does NOT route through the chat request-builder — it builds its own
request and reads extra-body as a plain map. The request-builder + response-parser
being adapter-owned is exactly why no generalization is required.

## Code smells flagged (for follow-up tasks)

1. **`seon.config` consolidation is incomplete + docstring is false.** config.cljs:155-156
   claims "Nothing outside this ns reads `process.env` for a knob," but render
   caps (render/value.cljs:65-78,87,290), agent-ctx caps (agent/ctx.cljs:391,445,
   265-267), transcript (transcript.cljs:50), run/loop bounds (agent/run.cljs:107,
   agent/loop.cljs:494), test runner (test/runner.cljs:353), fs grants
   (agent/fs/internal.cljs:76,83,94), brand (web/brand.cljs:89-94,159),
   instrument (instrument.cljc:174), tile-SCI (render/sci.cljs:90) all read
   `js/process.env` directly. render/value.cljs even defines its **own** private
   `env-int` (render/value.cljs:65) duplicating config.cljs:169. Finish the
   consolidation OR correct the docstring.
2. **`.env.example` drift both ways** — ~20 read vars undocumented; a few
   documented vars (`SEON_GYM_PAID`, `SEON_NREPL_PORT`, `SEON_CLJS_PROJECT`) have
   no active-pod read site.
3. **Stale comment** referencing the deleted `SEON_MAX_TURNS_PER_LOOP` loop
   (agent/run.cljs:105).
4. **`SEON_TRANSCRIPT_CHAR_BUDGET`** nominally violates the token-reporting rule
   (`_CHAR_` in a knob name).
</content>
</invoke>

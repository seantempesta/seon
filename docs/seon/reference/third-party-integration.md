---
type: reference
status: active
tags: [reference, agent, web, database]
---

# Seon integration guide (for a downstream / third-party deployment)

How to run, configure, and customize a Seon pod from a downstream deployment.
Covers the recent agent-deafness fix, LLM configuration, build-time function
overrides, and persona (soul). Everything here is on `main`.

## 1. Critical fix — agents no longer go deaf after one message

**Symptom (now fixed):** a freshly-created agent (a "new chat" / `POST
/agents/new`) handled exactly one message, then went permanently silent — later
messages logged a `POST /chat` but no turn ever opened.

**Root cause:** a turn-close transaction failed silently. The LLM usage
telemetry was being written under an attribute typed as an unbridgeable `:map`,
so the close transaction was rejected; the code ignored the failure, so the
turn (and the agent's `:seon.agent/state`) stayed `:running`. The wake logic
treats `:running` as "busy," so it skipped every subsequent message. This only
happened on the **real LLM provider** path (the stub emits no usage), which is
why it slipped past tests and didn't affect agents that existed at boot.

**Fix:** (a) usage telemetry is now stored correctly (serialized string); and
(b) a failsafe guarantees the agent **always** returns to `:idle` on every turn
exit — success, error, or a failed close — so a single missed reset can never
deafen an agent again.

**What you need to do:**
1. Pull `main` (includes the fix) and restart the pod: `bin/seon restart pod`.
2. Any agents already stuck `:running` recover automatically; if not, transact
   `{:seon.agent/id "<id>" :seon.agent/state :idle}`.

**Verify:** open the pod UI, start a **new chat**, send a message → reply, then
send a **second** message → it should reply (the bug was: no second reply).

## 2. Configuring the LLM (provider / model / keys)

The LLM settings are **data** in the DB (`:seon.ai/config`), and they are
**runtime-switchable**. As of the latest change the ownership model is
**seed-once → the DB owns it**:

- On a **fresh store**, the config is seeded from the `SEON_AI_*` environment
  variables (below).
- After that, **the DB owns it** — a runtime change (transacting
  `:seon.ai/config`) **persists across reboots**, and the env vars are no longer
  re-applied. (Previously the env re-synced on every boot and retracted anything
  not in the env, clobbering runtime switches — that is fixed.)

Set these in the environment that launches the pod, then restart:

**Anthropic**
```sh
export SEON_AI_PROVIDER=anthropic
export ANTHROPIC_API_KEY=sk-ant-...        # read at call time, never stored
export SEON_AI_MODEL=claude-opus-4-8       # optional (default)
bin/seon restart pod
```

**DeepSeek**
```sh
export SEON_AI_PROVIDER=deepseek
export DEEPSEEK_API_KEY=sk-...
export SEON_AI_MODEL=deepseek-v4-pro       # optional (default)
bin/seon restart pod
```

**Any OpenAI-compatible gateway**
```sh
export SEON_AI_PROVIDER=openai-compat
export SEON_AI_BASE_URL=https://your-gateway/v1/chat/completions   # FULL URL
export SEON_AI_MODEL=your-model-id
export SEON_AI_API_KEY=your-key            # or SEON_AI_API_KEY_ENV=MY_KEY_VAR
bin/seon restart pod
```

Optional tuning (all `SEON_AI_*`): `TEMPERATURE`, `MAX_TOKENS`, `THINKING`
(`false|true|high|max`), `TIMEOUT_MS`, `EXTRA_BODY` (an EDN map).

**Key resolution.** Anthropic reads `ANTHROPIC_API_KEY` specifically.
DeepSeek / openai-compat resolve, in order: the var named by
`SEON_AI_API_KEY_ENV` → (deepseek only) `DEEPSEEK_API_KEY` → `SEON_AI_API_KEY`.
**API keys are always read from the environment at call time — never stored in
the DB.**

**Changing config later.** Since the DB now owns the row after the first seed,
the way to change provider/model after the initial boot is either: transact
`:seon.ai/config` at runtime (persists), or clear the config row to re-seed from
the env. Verify with `(seon.ai/provider)` and `(seon.ai/current)` in the pod
REPL.

**Note:** initial config is **configuration, not a code override** — do NOT use
the override mechanism (§3) to set LLM config; use these env vars / runtime
transact.

## 3. Overriding a core function at build time ("no more hooks")

To change the **code behavior** of a core function, ship a build-time override.
It is per-function and surgical — no forking core, no hooks.

**Mechanism.** A preload namespace under **your own prefix** that `(:require)`s
the core namespace and `set!`s the var to your implementation. The build emits
in dependency order (your override loads after core), and the dev build's late
binding routes **every existing caller** to your version with no recompile.

**Reference example:** `examples/third-party-override/` in the repo — a
`deps.edn`, `src/example/overrides.cljs`, and a README. Copy it and rename
`example.*` to your prefix (must not start with `seon.` or `my.`).

```clojure
(ns yourco.overrides
  (:require [seon.some.core-ns]))
(set! seon.some.core-ns/the-fn (fn [...] ...your version...))
```

**Enable it:**
```sh
export SEON_EXTRA_SRC="$(pwd)/path/to/your-override-project"
export SEON_EXTRA_PRELOAD=yourco.overrides
bin/seon restart cljs-watch    # recompiles with your source root + preload
bin/seon restart pod
```

**Gating:** with these env vars unset, the override directory is **not on the
classpath and is never compiled** — it costs nothing until you enable it. So it
can live in your deployment permanently and you turn it on per environment.

**Caveats:**
- **Dev build only.** `set!` re-pointing relies on the dev (`:none`) build; an
  `:advanced` build silently no-ops it. Keep the pod dev-compiled.
- **Config is not an override** — use §2 for LLM settings.
- **Agents cannot override core.** An agent that tries to redefine a compiled
  core function is dropped + warned; build-time overrides (this mechanism) are
  the supported way to change core behavior. Agents only define in their own
  namespaces.

## 4. The model (context)

- **Compiled package** = kernel + core + your build-time overrides — the base
  image, compiled into the bundle.
- **DB layer** = the agents' own code (their namespaces) + configuration data
  (LLM config, persona). Loaded from the database on boot.
- You (the downstream) customize **code** via build-time overrides (§3) and
  **configuration** via env/DB data (§2, §5). Agents operate within their own
  namespaces and cannot change core.

## 5. Persona / soul

The agent's persona is **data** — `:my.soul` rows (priority-ordered into the
system prompt) — seeded at boot from a `SOUL.md` file, **runtime-editable, and
persistent** (the same seed-once → DB-owns model as the LLM config: a runtime
edit survives reboots).

- **Today:** to customize the persona, transact `:my.soul` rows at runtime (they
  persist), or edit the seed `SOUL.md`. Agent-added instructions use a separate
  channel (`my.kb.instruction`, rendered as the `<instructions>` section).
- **Coming:** a `SEON_SOUL_PATH` (point it at **your** `SOUL.md`) so a downstream
  supplies the initial persona on a fresh store without forking the repo — same
  seed-once → DB-owns shape. (Ask if you need this prioritized.)

## 6. Testing the 2026-06-21 updates (tile isolation + late-bound override)

Concrete eval recipes for the work that just landed. Run them in the pod REPL
against a live agent id.

### 6.1 A hung tile no longer freezes the pod

Wire a non-terminating fn as the agent's canvas and confirm it aborts at the
wall-clock budget instead of wedging the single pod thread:

```clojure
;; persist an interpreted fn + point the tile at it
(seon.db/transact!
  {:seon.db/tx-data [{:seon.fn/sym "my.t/hang"
                      :seon.fn/source "(defn hang [m] (loop [] (recur)))"}
                     {:seon.agent/id "<id>"
                      :seon.render.canvas/content 'my.t/hang}]})
(seon.render/render-agent-canvas {:seon.agent/id "<id>"})
```

Expect the `welcome` fallback within ~the budget (not a hang); `/agents` keeps
answering; `:seon.render.canvas/content` is retracted back to welcome; the
agent is messaged once (deduped). A tile that THROWS instead shows the calm
"Updating this panel" card and notifies the agent — no crash, content kept so a
fix takes effect. Set `SEON_TILE_SCI=0` to disable bounding. Mechanism +
caveats: [[components/renderer]].

### 6.2 An override flows through a late-bound caller

```clojure
(seon.demo/greet-loudly)                       ;; => "hello from core!"
(set! seon.demo/greeting (fn [] "x"))
(seon.demo/greet-loudly)                       ;; => "x!"
;; redefining via defn works the same:
(defn seon.demo/greeting [] "y")
(seon.demo/greet-loudly)                       ;; => "y!"
```

Both `set!` and `defn` redefinition route every existing caller to the new
version — this is the agent-facing override surface (the §3 build-time override
is the same late-binding mechanism applied at compile time).

### 6.3 Your own compiled persona / tile fns

A fn compiled from your source is NOT indexed by default (no `:seon.fn/source`
row), so it renders on the UNBOUNDED compiled path. To make it agent-visible AND
boundable, index it via `SEON_EXTRA_SRC` plus the `!extra-core-vars` preload
registration — see [[components/extra-src]]. Verify the fn picked up a source
row:

```clojure
(seon.client/index-core!)   ;; your fn now appears with :seon.fn/source
```

### 6.4 Caveats

- Proven on the dev `:none` build (what the pod and the suite run).
- `:advanced` is unsupported; `:simple` release is not yet validated — the
  override-via-runtime-eval path is optimization-immune, but the core's
  `globalThis` fn resolution under `:simple` is unverified (see
  [[prds/agent-runtime/research/shadow-late-binding-and-extra-src-2026-06-21]]).
- The native host-loop / ReDoS residual is not bounded (deferred Layer 2 —
  killable worker).
- The `reply!` `:malli/schema` was also fixed in this batch (no behavior
  change).

## 7. Operating the pod

- `bin/seon status` — process states + the pod URL.
- `bin/seon restart pod` / `restart cljs-watch` — restart after config/build
  changes.
- `bin/seon tail pod` — boot + agent activity logs.
- The pod serves its UI/API on its HTTP port (default `7890`).

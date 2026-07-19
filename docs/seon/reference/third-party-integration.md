---
type: reference
status: active
tags: [reference, agent, web, database]
---

# Seon integration guide

How a downstream deployment runs Seon and supplies configuration. Seon is one
application with a supervised ClojureScript pod and JVM database writer; use
`bin/seon` for their lifecycle rather than starting either process directly.

```sh
bin/seon up
bin/seon status
bin/seon restart
bin/seon down
```

The current web entry points are `/`, `POST /agents`, and `/agent/{id}`.
Agent availability is derived from runs and lifecycle facts; there is no
mutable `:seon.agent/state` recovery knob.

## 1. Configuration ownership

An explicitly selected `SEON_CONFIG` manifest reconciles its declared subset
into database facts. Runtime code reads the database, not the manifest or
environment. Reopening an existing database does not require the original
manifest; explicitly applying one later repairs drift and writes nothing when
the database already agrees.

Use `config/system.edn` as the complete example. A downstream manifest may
compose it with Aero `#include` and `#merge`, as `config/acme.edn` does. Keep
credentials out of manifests and Git.

## 2. Models, providers, and credentials

[[llm-adapters]] is the single maintained model catalog and configuration
reference. It records each provider's exact environment variables, endpoint
shape, named per-agent model variants, measured behavior, dated pricing,
recommendations, and primary refresh links. Prices are snapshots; refresh the
linked vendor page before a material run.

The cluster-wide fallback is the `:seon.ai/config` database entity. Named
sparse maps under `:seon.config/model-variants` provide launch roles such as
`:planning` and `:execution`. Passing
`:seon.config/model-variant` to `create!`, `mint!`, `start!`, or `delegate!`
copies that variant's ordinary non-secret provider attributes onto the new
agent atomically. Existing agents are not silently retuned.

Keys are read from the process environment at request time and never stored in
the database. OpenAI-compatible agents store only the name of the environment
variable in `:seon.ai/agent-api-key-env`. For example, Kimi K3 uses
`MOONSHOT_API_KEY`; DeepSeek uses `DEEPSEEK_API_KEY` by default. Do not put the
credential value in an agent entity or config manifest.

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
bin/seon restart
```

**Gating:** with these env vars unset, the override directory is **not on the
classpath and is never compiled** — it costs nothing until you enable it. So it
can live in your deployment permanently and you turn it on per environment.

**Caveats:**
- Validate an optimized downstream artifact separately; development
  late-binding evidence does not prove every Closure optimization mode.
- **Config is not an override** — use §2 for LLM settings.
- **Agents cannot override core.** An agent that tries to redefine a compiled
  core function is dropped + warned; build-time overrides (this mechanism) are
  the supported way to change core behavior. Agents only define in their own
  namespaces.

The maintained runnable consumer is `acme/`; use `bin/acme` to exercise an
isolated downstream database and artifact without forking Seon. See
[[components/extra-src]] for source registration and optimized-entry details.

## 4. The model (context)

- **Compiled package** = kernel + core + your build-time overrides — the base
  image, compiled into the bundle.
- **Database layer** = agent-authored namespaces plus configuration facts,
  plans, messages, runs, and evidence. It is the runtime authority.
- You (the downstream) customize **code** via build-time overrides (§3) and
  **configuration** via manifest/database data (§1–2). Agents operate within their own
  namespaces and cannot change core.

## 5. Persona / soul

`SOUL.md` and `AGENTS.md` are live file-backed context blocks, not stored
persona entities and not the model system message. Set `SEON_SOUL_FILE` to a
different repo-relative identity file or set `SEON_SOUL=false` to omit the
soul block. Repository instructions remain in `AGENTS.md`.

## 6. Operating and proving a downstream

- `bin/seon status` reports the default cluster and endpoint.
- `bin/seon logs pod --follow` follows pod activity.
- `bin/acme up|status|restart|down` operates the maintained isolated downstream
  harness.
- Verify changes through the database facts and user-visible route they affect;
  a successful build alone is not runtime proof.

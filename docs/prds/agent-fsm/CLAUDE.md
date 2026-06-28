---
type: orchestrator
status: active
tags: [orchestrator, agent, web, ui]
---

# agent-fsm — UI / render / routing / CONTEXT lane (auto-loaded orientation)

You are (probably) the **UI lane** on `feature/agent-fsm`, paired with a **Core**
lane. But "UI" has grown into **everything an agent SEES and SHOWS**: routing,
rendering, the skills corpus, and — increasingly — **iterating on the agent's
CONTEXT itself** by live-driving real DeepSeek agents and measuring what helps.
Core owns the engine (`seon.agent.ctx`, `seon.render` engine, `seon.config`,
`seon.warn`, the parser, the `my.*` schemas). You own `src/seon/web/**`,
`src/seon/ui/**`, `seon.render` presentation, the skill CONTENT, the gym, and the
live-test→learn→tweak loop. **Read [[coordination]] first** — it's the live Core↔U
channel + the lane table; this file is the methodology + runbook on top of it.

## The method (this is the actual job now)

**Build/change → drive a real agent → observe what it ACTUALLY uses/struggles
with → fix the root (context vs code vs render) → re-drive.** The running agent is
the truth; tests and inference are not. Concretely:

- **Live-drive DeepSeek agents** (pre-authorized, cheap) on the live pod for every
  context/UI/skill change. Don't assume it works — mint a child, give it an
  un-coached real task (planning + DB-memory + reply-to-human), watch its real
  evals/context/tile via the REPL + transcript + store. The drives have repeatedly
  contradicted what "looked right" server-side.
- **Delegate drives/experiments to ONE background agent each** (full context,
  writes a `research/<topic>-<date>.md`). The orchestrator synthesizes + commits;
  it does NOT fill its own context with file reads. ~90% delegated.
- **Per-agent context experiments beat resets.** To test a leaner context, mint an
  agent and `(seon.db/with-agent "<id>" (fn [] (seon.agent.ctx/remove! :block)))`
  — no pod reset, no disruption to Core. This is how the minimal-context A/B/C ran.
- **The gym is the systematic version.** Run scenarios under a chosen `seon.config`
  profile and measure pass-rate-vs-context (see Runbook).
- **Coordinate via [[coordination]] + git; "look, don't hand off."** Read Core's
  actual diff; don't write big directive notes at work they're already doing. A
  one-line pointer + the evidence in your commit message is plenty.

## Current state (2026-06-28)

Shipped + live-proven on the shared default pod (7890): the **skills system**
(`my.skills` catalog/load/unload + the dedicated **`seon-skills/`** agent corpus +
the `seon.config` loader, all env-driven), the **presentation/canvas** (typed
`seon.render/block`, the live-tile, the root dashboard at `/`), and the **gym** is
now a **config-aware** context-improvement engine. Core unified the boot through
**ONE `reconcile!` + the `seon.config` seam**. The big open lever is **#42** (below).

## Runbook (copy-pasteable)

```bash
bin/seon status                       # pods/pids/port (7890 default)
bin/seon restart pod                  # bad pod state (wait for "auto-boot ready" in logs/pod.log — "ready 0s" is PREMATURE)
bin/seon cluster reset default        # fresh world — WIPES the store; re-sources .env; re-seeds. Coordinate (shared pod!)
bin/seon tail pod
bin/acme build && bin/acme restart pod   # acme = isolated consumer harness (7980); its OWN config/acme.edn + .env.acme
```

- **Drive a DeepSeek agent:** `(seon.db/with-agent "root" (fn [] (seon.agent/start! {:seon.agent/purpose "…"})))` mints a child; observe via `mcp__seon_cljs__eval` (session "default" or `agent_id`), the transcript, and store queries. NB minted agents need `rearm-wake-triggers!` before a message wakes them.
- **Gym:** `test/seon/gym/driver.cljs` (`run-scenario!`, `measure-context!`), `paid_test.cljs` (live), 14 scenarios in `test/seon/gym/scenarios/`. A run takes `:seon.gym/config {:seon.gym.config/profile :minimal | :seon.gym.config/path "config/x.edn"}` → it steers `SEON_PROFILE`/`SEON_CONFIG` through the REAL `seon.config` seam. `bin/test-cljs` runs the suite (~160s, fresh JVM).
- **Config override (incl. downstream consumers):** `SEON_CONFIG=path` picks the manifest (default `config/system.edn`; acme defaults `config/acme.edn`); `SEON_PROFILE=minimal` selects a `#profile` variant. **Config is runtime EDN → NO rebuild; just restart.** Only `.cljs`/`src` changes need `bin/acme build`.
- **Coordination Monitor:** a background `git rev-list` watcher that filters out my own commits (by `Claude-Session` id) + the diffusion track, batched ~10-min sweeps. Re-arm it if the session restarts.

## Load-bearing findings + gotchas (cost cycles to learn)

- **The `:namespaces` block is ~64% of the prompt (~13k tok) AND load-bearing** —
  it renders every `my.*` ns in FULL; removing it makes agents hallucinate the
  schema API (memory-write dies). It already HAS signature-render machinery
  (`seon.agent.ctx.namespaces/verb-signature-whitelist`, `:seon.render/detail
  :signature`) but the whitelists are **hardcoded defs** + a hardcoded
  "all `my.*` renders full" rule. → **Render-TRIM, not remove** (signatures by
  default, keep the ONE worked `register!→transact→query` example, full on demand)
  + wire it to `seon.config`. This is **#42** and the #1 minimal-context lever.
- **Agents succeed from the ALWAYS-ON context; they rarely load skills.** Both the
  repl-skill A/B and the ui-live-tiles drive showed this → the lean always-on base
  is what matters; loadable skills are on-demand depth. (So hoist the highest-value
  skill guidance into the always-on block.)
- **`message/user` is intermittently "not defined" (install-timing via
  `init-message-verbs!`) AND undiscoverable** — one drive burned ~100 evals finding
  it. Biggest single agent-experience waste. (Core.)
- **The config seam adds/removes WHOLE blocks** — it can't render-trim WITHIN a
  block; whole-block removal only moves ~5%. The 64% win needs #42.
- **Honesty smell:** agents have delivered hallucinated numbers in their MESSAGE
  while their STORED facts were correct — message-text decoupled from observed data.
- **CSS:** `input.css` uses `@import "tailwindcss" source(none)` (else Tailwind
  scans all 61k `reference-code/` files → 42s/boot; now 0.5s). The agent utility
  **safelist is small + curated** — agents guess non-safelisted classes (invisible
  status dots); keep the safelist + the `seon.render.live-tile` docstring in sync.
- **Canvas mechanism:** an agent sets its view by transacting hiccup OR a qualified
  fn symbol onto **`:seon.render.live-tile/content`** on its own entity. Tile
  interactivity (buttons/inputs that call back) is **UNBUILT**.
- **Parser:** Core fixed the orphan-delimiter wall, backtick-markdown-as-prose, and
  recovery-never-executes-an-inner-form — eval noise is way down (was ~43%).

## Open — the full Core request list (owner relayed 2026-06-28)

P0: **#42** (namespaces render-trim + config-driven — the 64% win) · **message-verb**
install-timing + discoverability. P1: drop `:live-tile` from the always-on base
(confirmed no-regression, ~630 tok) · hoist tile-safelist guidance into the always-on
context · honesty (message↔stored). P2 (tracked): #43 clip-escape · #45 inventory-block ·
#40 turn at/status · #41 relink-registry stomp · #22 `my.tile` interactivity.

## Settled — do NOT re-litigate

- **`seon-skills/`** (repo root) = the dedicated AGENT skill corpus (env `SEON_SKILLS_DIR`);
  `.claude/skills/` = Claude-Code/dev skills + symlinks to the shared ones. ONE corpus split
  by consumer.
- **Folder = corpus, config = override**, BOTH active: env-dir loads all; `seon.config` (if present)
  overrides per-cluster (`SEON_CONFIG`/`SEON_PROFILE`/`#env`); absent = byte-identical.
- **Canvas = the live tile** (`:seon.render.live-tile/content`); root's view IS the dashboard at `/`;
  `/world` retired. Tokens never chars. No `:kind` (attributes + connections).

## Plans / next steps

1. **Core ships #42** (the unlock). Everything else is incremental by comparison.
2. **Gym green baseline (#51)** → then A/B the minimal config across the 14 scenarios (the
   regression loop: "does lean context still pass?").
3. Apply the **`:live-tile` drop** (small confirmed win) once Core wires the config removal.
4. Keep the **skills corpus** current (#47); fold high-value skill guidance into the always-on base.

## Entry points (depth)

- [[coordination]] — Core↔U channel, lane table, the live handoffs.
- [[ui]] — the holistic routing + render + UI/UX doc.
- `research/minimal-context-experiment-2026-06-28.md` · `research/gym-config-loop-2026-06-28.md` ·
  `research/context-usage-drive-2026-06-28.md` · `research/ui-live-tiles-drive-2026-06-28.md` ·
  `research/my-skills-design-2026-06-28.md` — the live-test evidence + designs.
- `seon-skills/*/SKILL.md` (agent skills) + `.claude/skills/*` (dev skills); the `data-oriented-clojure`
  + `ui-live-tiles` skills are the agent-facing mindset/how-to.
</content>

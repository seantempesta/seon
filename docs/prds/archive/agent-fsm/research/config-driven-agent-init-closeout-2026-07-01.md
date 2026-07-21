---
type: research
status: completed
tags: [config, agent, flow, milestone]
---

# Config-driven agent-init — CP-4 → CP-5.5 closeout + final per-dial ledger

The build (CP-4 rip-out → CP-4.5 dial activation → CP-5 intended changes →
CP-5.5 acme) is complete on `feature/agent-fsm`. Suite green throughout (886
tests / 4087 assertions, 0 failures — the two `FAIL in () (:)` are the
pre-existing `seon.test.async-*` deliberate-failure probes). This note is the
durable closeout: the checkpoint summary + the FINAL per-dial ledger (zero
silently-inert dials — every one is WIRED, REMOVED, or PARKED-with-note).

## Checkpoint summary (commits, all live-proven + gym-measured)

- **CP-4** (`87274cec`) — clean-break rip-out of the ~18 legacy mechanisms
  (`#profile`, `:loadouts`/`:role`/`:default-load`, `resolve-loadout`/
  `resolve-skill-rows`/`agent-role`, `stable-priority-max` const, the
  `SEON_DEFAULT_TURN_LIMIT`/`SEON_RENDER_*_CAP` env reads) + env→manifest render
  caps (`:seon.config/render` section, #46). Grep-to-zero on every ripped symbol.
- **`:my.skills/load`** (`6fa6fb7e`) — the presence-set seeds `:skill/<name>`
  blocks (default `[:repl]`).
- **CP-4.5** — the root-cause fix (`seed-default-ctx!` transacts agent-level
  keys) + dial activation: `wake?` (`9f195df7`), `home-requires` (`9f195df7`),
  soul migration + dead-`default-seed-blocks` deletion (`b484a829`), REMOVE
  toolkit/cite-card?/summary-head? + PARK capabilities (`e97cc650`), per-agent
  LLM (`2a04e599`).
- **CP-5** — transcript age-banding `::tiers`+`::turns-retained` (`b563280c`),
  eval-result decay schedule ON (`4d31c47f`), escape-clipping route flip
  (`363554c1`). Balloon measurement (`7bb03dc1`).
- **CP-5.5** — `config/acme.edn` migrated + `config/acme-minimal.edn`
  (`2f8a6b46`); live override-proof drive on the acme cluster (below).

## Final per-dial ledger

**WIRED + live-proven** (a non-default value observably changes behavior):

| dial | consumer | proof |
|------|----------|-------|
| `:seon.config/render/*` (10 caps) | `store-edn-cap`/`value-*` etc. | manifest read; env-override hatch works (72→40) |
| `:my.skills/load` | `expand-skill-blocks` | `[:repl]`→1 block; `[:repl :datahike]`→2; `[]`→0 |
| `:seon.client/wake?` | `wake-armed?` gates the trigger | `false`→no auto-wake; retract→true |
| `:seon.eval/home-requires` | `home-requires-for` → `home-ns-form` | REACTIVE: DB datom-if-present (re-arm), config-fallback-if-absent (mint), const-if-neither. LIVE: transact override→2-spec on re-arm; retract→6-spec const; fresh id→const |
| `:seon.agent.ctx/cache-breakpoint` | `render-context-ai` | reads datom (default 20) |
| `:seon.agent.ctx/escape-clipping?` | `format-eval-row`/`message->renderable` | `true`(default)→5000-char msg whole; `false`→clips at 4000 |
| `:seon.agent.run/default-turn-limit`/`-deadline-ms` | `open-run!` | datom seeds run bounds |
| `:seon.ai/agent-model/temperature/max-tokens/thinking` | `ai/current` overlay (ambient id) | `::agent-model` override → adapter uses it; `:inherit`→global |
| `:seon.ai/agent-provider` | `ai/provider` overlay → `seon.client/current-llm-fn` selects the adapter PER CALL (task #88) | agent's `::agent-provider` differs from global → its turn routes to THAT adapter; `:inherit`→global adapter (byte-parity) |
| `:seon.ai/agent-max-retries` | `agent-max-retries` @ turn.cljs | `:inherit`→env default |
| `:seon.agent.ctx.transcript/tiers`+`/turns-retained` | `clip-events-by-tiers` | empty→render-all (parity); tier→eviction window |
| `:seon.agent.ctx.transcript/result-decay` (+`::decay-level` reified) | `decay-cap-for-offset` × per-eval offset | 0→16384, 2→1500, 5→200; old eval 28× smaller |
| `:seon.render.live-canvas/content` | `live-tile-block` | root→`system-view` |
| soul/agents identity blocks | `identity-file-blocks` (config) | SEON_SOUL off→none; on+file→`:soul`@5 |

**REMOVED** (registered, zero consumers, no clean seam — owner three-fates):
`:seon.eval/toolkit` (process-level const, no per-agent seam),
`:seon.agent.ctx.transcript/summary-head?` (renders unconditionally),
`:seon.agent.ctx.transcript/cite-card?` (#63 guard unconditionally on). Each
left a one-line NOTE at its old site.

**PARKED** (kept registered + a tracked note, deferred — NOT inert):
`:seon.agent.ctx/capability` + `/capabilities` — per-agent capability
enforcement over search[grep]/fs/http is a phase-2 mechanism (a check at each
provider verb); marked PARKED with the wiring plan at the register site.

**Dead code removed:** `seon.agent.ctx/default-seed-blocks` (uncalled; its
soul-seeding migrated to `seon.config/identity-file-blocks` first).

## CP-5 intended-change gym (before/after, free tier, git-tracked trend)

| | baseline 2a04e599 | combined (escape+decay+tiers) |
|-|-------------------|-------------------------------|
| pass-rate | 1.0 | 1.0 |
| eval-error-rate | 0 | 0 |
| transcript block tokens | 3047 | 3047 |
| total tokens | 879,589 | 881,478 (+0.21%, :namespaces variance) |

No regression on the battery. The escape-clipping balloon (gym scenarios too
short to show it) is bounded by the decay — see
[[cp5-balloon-measurement-2026-07-01]]: 12 large evals → newest full (4197 tok),
oldest stub (150 tok), total 10,874 vs 120,000 without decay.

## CP-5.5 acme override-proof (LIVE on the acme cluster, pod 7980 / wire 7981)

Fresh `bin/acme cluster reset` seeded root from the migrated `config/acme.edn`.
Verified against the acme store (via `seon.server.wire/state` `:conn`) — the
default pod was NEVER touched:

- **6 skill blocks** rendered (`:skill/{clojurescript,data-modeling,
  data-oriented-clojure,datahike,repl,ui-canvas}`) from `:my.skills/load`.
- **per-agent `:seon.ai/agent-provider :inherit`** datom on root.
- **acme's OWN decay** `[{0 16384}{3 800}]` reified onto the transcript block.
- **LLM env→DB dual-default**: global `:seon.ai/config` row = `:openai-compat`
  (from `.env.acme`); root's `:inherit` resolves to it. Both paths work.
- **DIAL-COVERAGE spot-check** — every dial CLASS settable from `config/acme.edn`
  with ZERO `src/seon` edits: skill presence-set, block presence, per-agent LLM,
  reified decay level (NOT a blob), namespaces full-source policy. PASS.

## Open follow-ons (flagged, not gaps)

- **Per-agent PROVIDER selection** still uses the global adapter (chosen at
  re-arm via `current-llm-fn`, not per-call). Per-agent model/temp/tokens/
  thinking FLOW; switching an agent to a different PROVIDER needs per-call
  adapter selection — a bounded follow-on.
- **Cross-lane (namespace-display lane):** the `::full-source`/`::with-tests`/
  compact-card render + one stale `namespaces.cljs` `default-seed-blocks`
  docstring are that lane's; untouched.

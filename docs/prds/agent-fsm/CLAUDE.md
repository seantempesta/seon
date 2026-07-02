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

**If you are the CORE lane, jump to [§ Core lane — how to be the best Core
agent](#core-lane--how-to-be-the-best-core-agent) below** — that section is your
charter; the UI methodology above is context on what your engine changes must
serve.

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

## Current state (2026-06-29)

**Config-driven agent-init is COMPLETE (CP-4 → CP-5.5, 2026-07-01).** ONE manifest
(`config/system.edn`, `SEON_CONFIG` override) drives the whole agent context via
`seon.config/resolve-agent-context` → `seed-default-ctx!`. The ~18 legacy
mechanisms (`#profile`, `:loadouts`/`:role`/`:default-load`, `stable-priority-max`,
the `SEON_RENDER_*_CAP` env reads) are RIPPED; render caps are a manifest section
(#46). Every config dial is WIRED / REMOVED / PARKED-with-note — zero silently
inert (per-agent LLM, `wake?`, `home-requires`, `:my.skills/load`→skill blocks,
soul→config, escape-clipping-full, eval age-decay, transcript tiers). CP-5's
intended changes (escape-clipping full + eval decay) are gym-green (no battery
regression) and the balloon is bounded by the decay (12 big evals → 10,874 tok vs
120k). acme migrated + LIVE override-proven (its 6 skills / per-agent LLM / own
decay render; every dial class file-settable, zero src edits). Full per-dial
ledger + proofs: [[research/config-driven-agent-init-closeout-2026-07-01]].

The night's central deliverable is **PROVEN**: the **`my.*` toolkit**
(`my.data`/`my.ui`/`my.tile`) went from built-but-invisible to
**composable-and-honest**. A real DeepSeek agent now builds its tiles with the
toolkit (drive-measured `my.data` 15×/23×, `my.ui` 9×/11×, `my.tile` 3× wired to
agent-defined handlers) instead of hand-rolling broken `[:div]`s. **Canvas-first
is reliable** — agents wire a re-deriving tile as their PRIMARY surface unprompted,
now including **planning/goal** asks (a one-sentence guidance fix took canvas-drive
1/3→3/3). **Fabrication is FIXED** (cite-card #63 + canvas-first: the agent cites
the computed value off its derived tile instead of lying in prose). The
**transcript is bounded** (#62 eviction, ~20k→~1.4k/turn — the #1 token lever).
**`:kind` is purged** at the root (attribute-presence everywhere). The **gym is now
a measured, self-defending fitness function** (`bin/gym-scorecard`) over a
well-rounded 23-scenario battery; two facets proved **handled** (error-recovery,
planning-resume). Live pod reset to current; toolkit indexing live-proven. Depth:
[[research/overnight-2026-06-28]].

## Runbook (copy-pasteable)

```bash
bin/gym-scorecard                     # THE fitness function — FREE every iter: SHA-keyed battery × axes line
bin/gym-scorecard --paid --k=N        # add live paid drives (N samples → pass^k noise-robustness)
bin/seon status                       # pods/pids/port (7890 default)
bin/seon restart pod                  # bad pod state (wait for "auto-boot ready" in logs/pod.log — "ready 0s" is PREMATURE)
bin/seon cluster reset default        # fresh world — WIPES the store; re-sources .env; re-seeds. Coordinate (shared pod!)
bin/seon tail pod
bin/acme build && bin/acme restart pod   # acme = isolated consumer harness (7980); its OWN config/acme.edn + .env.acme
```

**The loop = measure → drive the weakest facet → fix the GENERAL root → re-measure →
keep IFF it lifts the whole battery (accretive-or-revert, no overfit).** The scorecard
is the honest judge: pass × per-block-tokens × eval-error-rate × **toolkit-adoption**
× **pass^k** noise-robustness. Run it FREE every iteration; the `--paid` drives are
for the weakest facet that the FREE axes can't fully see.

- **Drive a DeepSeek agent:** `(seon.db/with-agent "root" (fn [] (seon.agent/start! {:seon.agent/purpose "…"})))` mints a child; observe via `mcp__seon_cljs__eval` (session "default" or `agent_id`), the transcript, and store queries. NB minted agents need `rearm-wake-triggers!` before a message wakes them.
- **Gym:** `test/seon/gym/driver.cljs` (`run-scenario!`, `measure-context!`), `paid_test.cljs` (live), **23 scenarios** in `test/seon/gym/scenarios/` (every facet). A run takes `:seon.gym/config {:seon.gym.config/profile :minimal | :seon.gym.config/path "config/x.edn"}` → it steers `SEON_PROFILE`/`SEON_CONFIG` through the REAL `seon.config` seam. `bin/test-cljs` runs the suite (~160s, fresh JVM). **Gym scenarios run hermetically (scratch conns)** — A/B a context change without a pod reset.
- **Config override (incl. downstream consumers):** `SEON_CONFIG=path` picks the manifest (default `config/system.edn`; acme defaults `config/acme.edn`); `SEON_PROFILE=minimal` selects a `#profile` variant. **Config is runtime EDN → NO rebuild; just restart.** Only `.cljs`/`src` changes need `bin/acme build`.
- **Coordination Monitor:** a background `git rev-list` watcher that filters out my own commits (by `Claude-Session` id) + the diffusion track, batched ~10-min sweeps. Re-arm it if the session restarts.

## Load-bearing findings + laws (hard-won this session — these cost cycles)

- **The render-prominence LAW: a toolkit verb's value IS its worked example.** A
  COMPOSITION verb (`my.data`/`my.ui`/`my.tile`) rendered as a bare signature is
  **undiscoverable** — the #42 signature-trim DROVE `my.data` adoption to 0× (agent
  hand-rolled the footgun path, eval-error-rate 0.357 RED). So **keep the `my.*`
  toolkit FULL** in `seon.agent.ctx.namespaces/canonical-full-my-ns`
  (`#{:my.kb :my.data :my.ui :my.tile}`) AND **required in `client.cljs`** (so it's
  indexed at boot — otherwise it renders with ZERO fns, name-only). The law applies
  to COMPOSITION verbs, NOT simple-call ones: `seon.agent.todo` is a plain call, its
  worked-example role is redundant now that 4 full `my.*` examples exist → trim it to
  signatures (#74, ~3.3k save). So the realistic namespaces lever is ~3.3k, not 18k —
  the toolkit is *earning* its tokens.
- **Canvas-first MITIGATES fabrication.** The derived tile is computed-from-data;
  prose is where agents lie. A budget agent's PROSE fabricated ($155) while its
  `my.data`-derived CANVAS was correct ($136) → moving the agent onto the canvas
  fixed the judge (fail→PASS). This is an honesty benefit on TOP of the cite-card.
- **Cache-stability: freeze aged transcript clips BYTE-IDENTICAL.** The transcript
  eviction bands by **AGE, not recency-weight** — an aged clip must render the same
  bytes every turn so the LLM prompt cache holds. Recency-weighting would re-flow
  old text and bust the cache.
- **Measure the RIGHT thing.** The FREE scorecard `total-tokens` MISSED the #42
  adoption regression (confounded by scenario count + non-namespaces blocks). Only a
  **paid composition drive** + a standing **toolkit-adoption axis** caught it. FREE
  axes for cheap iteration; paid drives + the right axis for what FREE can't see.
- **`pass^k` — single-sample drives are NOISE.** Weak-model variance flips a scenario
  run-to-run (`canvas-goal-board` single-sample miss was model variance). Average over
  k samples before believing a pass or a regression.
- **Hermetic test fixtures.** Concurrent loop runs (scorecard + suite at once) FLAKE
  on shared fs/DB state. `search_test` fixed with pid-scoped hermetic fixtures (20/20);
  `index_core_test` is the same class (#69). Aggressive parallelism needs hermetic tests.
- **The toolkit is reached by FULL-qualification, not home-ns aliases.** Home-ns
  aliases (`db/`/`message/`/`todo/`) are home-ONLY — they break in agent-authored
  `my.*` nses (~60 `"db/transact! is not defined"` per fn-authoring drive). Agents must
  fully-qualify in new nses (#73, Core: always-on/error-render/auto-refer).
- **The gym's guards must be ALIAS-TOLERANT.** A predicate matching only `my\.tile/`
  false-negatives `tile/button` from `[my.tile :as tile]` — it scored a perfect
  composition 0/14. The robust `toolkit-calls` axis caught it; guards now alias-tolerant.
- **Agents succeed from the ALWAYS-ON context; they rarely load skills** → the lean
  always-on base is what matters; hoist the highest-value skill guidance into it.
- **CSS:** `input.css` uses `@import "tailwindcss" source(none)` (else Tailwind scans
  61k `reference-code/` files → 42s/boot; now 0.5s). The agent utility **safelist is
  small + curated** — agents guess non-safelisted classes (invisible status dots); keep
  the safelist + the `seon.render.live-tile` docstring in sync.
- **Canvas mechanism:** an agent sets its view by transacting hiccup OR a qualified fn
  symbol onto **`:seon.render.live-tile/content`** on its own entity. `my.tile`
  interactivity (agent-defined handler wired to a button) is now PROVEN composable.

## Core lane — how to be the best Core agent

You own the **engine**: `seon.agent.*` (loop/run/turn/FSM/ctx), `seon.render`
engine, `seon.config`, `seon.warn`, the parser, the `my.*` schemas, the
bootstrap/seed, `seon.db`/`seon.eval`, and the resilience primitives
(`seon.retry`, the LLM adapters). The UI lane consumes what you produce — so a
change that alters the agent's CONTEXT, FSM, or verb surface is a Core change
even when the UI lane requested it. Single ownership: don't fix a context bug in
a render fn.

**Your method is REPL-first, not UI-first.** Observe the live pod, test the
assumption, read the source (`reference-code/` for any lib semantics you'd
otherwise guess — datahike / malli / sci / clojurescript self-host), then
implement, then live-prove in the pod (a datom read back, an eval result) — not
just a green suite. The suite runs UNINSTRUMENTED, so it can hide an
instrumentation-only break.

**Core invariants you must hold (root `CLAUDE.md`, the parts that bite):**

- **Errors-as-values at every agent-facing boundary** — `:seon/error` /
  `:seon.ai/error`, never a throw into the agent loop. Resilience surfaces a
  value the render derives a line from.
- **Schema-first + instrumented.** Every public fn carries a correct
  `:malli/schema` — it's validated at runtime, a wrong schema throws. Register
  shared shapes ONCE and reference them; never inline a duplicated constraint.
- **No `:kind`/`:type`.** An entity is its attributes + refs. FIND by
  attribute-presence, IDENTIFY by `:db.unique/identity`, RELATE by refs.
- **Derive, don't store.** A new agent-facing surface is a section/render fn
  that queries the DB, not a stored counter/flag/notification. Self-healing
  because nothing needs clearing.
- **One mechanism, in place.** No `foo-v2`, no parallel ns to "house a fix";
  the tree is a feature branch — atomic refactors are the cheap option.
- **Don't reinvent — but make it FIT the pod.** A JVM-only lib (blocking
  `Thread/sleep`, exception-based, `.clj`-only) cannot run in the CLJS pod; port
  the proven DESIGN to native async / errors-as-values instead. Worked example:
  `seon.retry` ports the `again` lib's strategy-as-seq combinators, with a fresh
  `^:async` errors-as-values executor (`seon.agent.turn/call-llm!` is the sole
  LLM retry authority — no parallel retry path).

**Coordination (shared tree, peers live):** commit per unit with EXPLICIT
pathspecs (never `git add -A` — peers have uncommitted work); `bin/test-cljs`
green ONCE per unit (not per edit); NEVER overlap `cljs.test/run-tests` in the
live pod (wedges async — `bin/seon restart pod` to recover); after a
context/FSM/verb change re-align ALL agent-facing context + `bin/seon cluster
reset default` to re-seed the shared pod; capture each flagged cross-lane
casualty as a tracked task with `file:line`.

**Your slice of the focus queue.** #42 (namespaces signature-render +
config-driven) is the #1 unlock and is YOURS — the signature machinery already
exists (`seon.agent.ctx.namespaces/verb-signature-whitelist`,
`:seon.render/detail :signature`); render-TRIM `my.*` to signatures by default
(keep the one worked `register!→transact!→query` example), full body on demand,
and wire `full-source-whitelist`/`verb-signature-whitelist`/`render-depth` to
`seon.config` profiles. Also Core: the `message/user` `init-message-verbs!`
install-timing race + discoverability, and the data-integrity bugs #40/#41. See
[§ Open](#open--the-full-core-request-list-owner-relayed-2026-06-28) for the
full list with severities.

## Settled — do NOT re-litigate

- **`seon-skills/`** (repo root) = the dedicated AGENT skill corpus (env `SEON_SKILLS_DIR`);
  `.claude/skills/` = Claude-Code/dev skills + symlinks to the shared ones. ONE corpus split
  by consumer.
- **Folder = corpus, config = override**, BOTH active: env-dir loads all; `seon.config` (if present)
  overrides per-cluster (`SEON_CONFIG`/`SEON_PROFILE`/`#env`); absent = byte-identical.
- **Canvas = the live tile** (`:seon.render.live-tile/content`); root's view IS the dashboard at `/`;
  `/world` retired. Tokens never chars. No `:kind` (attributes + connections).
- **Docstring convention** — public fn line 1 = complete ≤72-char sentence (78 cap) ending in
  terminal punctuation; it's the compact-card summary + renders everywhere. Enforced by
  `seon.dev.docstring` (warn). Full rule: `docs/conventions.md` "Function Docstrings" +
  [[compact-namespace-cards-spec]]. Compact-card render = presence-sets on the namespaces block
  (`::full-source`/`::with-tests`), NOT a `:map-of` — see [[config-driven-agent-init-namespaces-additions-2026-07-01]].

## Plans / next steps

**VALIDATED (live-proven, committed):** toolkit composable + honest; canvas-first
(incl. planning); transcript eviction (#62); `:kind` root purge; `:live-tile` trim;
the scorecard fitness function + 23-scenario battery; error-recovery + planning-resume
facets handled.

**Core-gated (verified findings waiting on Core):**

1. **#42 explicit-listing config** — the `my.*` FULL vs signature decision wired to
   `seon.config` profiles (the trim landed; the per-profile control is the remainder).
2. **#73 home-ns alias collision** — agents can't use `db/`/`message/`/`todo/` aliases
   in new `my.*` nses (always-on/error-render/auto-refer fix).
3. **#74 todo signature-trim** — drop `seon.agent.todo` to signatures (~3.3k); verify
   todo usage holds after (it's a simple-call verb, render-prominence law doesn't apply).

**Owner decision:** **#66 `:kind` Category B** — the recurrence engine (A) is purged;
B is value-classification (`:seon.error/kind`, `:seon.warn/kind`, render/predicate
classes). Purge the WORD everywhere (rename→`class`/`shape`, a real multi-file refactor)
vs stop at entity-kinds (this pass)? Read = B is taste/consistency, not correctness.

**Then:** keep the loop running — measure → drive the weakest facet → fix general →
re-measure → keep-iff-lifts-battery. Build the interactive gym scenario that exercises
`my.tile` controls directly. Keep the skills corpus current (#47).

## Entry points (depth)

- **[[research/overnight-2026-06-28]]** — the night's running report: exec summary +
  every validated landing with live-proof. The depth behind this file.
- [[coordination]] — Core↔U channel, lane table, the live handoffs.
- [[ui]] — the holistic routing + render + UI/UX doc.
- `research/toolkit-reachable-verification-2026-06-28.md` · `research/canvas-drive-validation-2026-06-28.md` ·
  `research/namespaces-trim-validation-2026-06-28.md` · `research/facet-gaps-drive-2026-06-28.md` —
  the live-drive evidence behind the laws above.
- `seon-skills/*/SKILL.md` (agent skills) + `.claude/skills/*` (dev skills); the `data-oriented-clojure`
  + `ui-live-tiles` skills are the agent-facing mindset/how-to.

---
type: prd
status: active
tags: [prd, agent, web, flow]
---

# Substrate asks batch — 2026-06-16

## Context

The aria (downstream consumer) integration filed a batch of substrate asks in
`tmp/2026-06-11-seon-asks.md` (a living, gitignored doc — current reality, not a
log). The LLM-related asks are **done** on `feature/llm-sdk-migration` and are
out of scope here:

- **#30** extra-body unreachable from the loop — CLOSED (`9e9e90a`+`f66ae9b`).
- **#25** preserve + persist provider metadata — CLOSED in the SDK migration
  (`2bfadea`): both adapters carry unrecognized top-level completion fields as
  open `:seon.ai/provider-fields`; `ask-and-eval!` persists per-turn
  `:seon.agent.turn/llm-usage` + `:seon.agent.turn/llm-meta` (EDN). Loose ends
  (LLM branch, non-blocking): no unit test for the tier-2 persistence; live
  Sangam `provider_specific_fields` round-trip unverified (paid call).
- **#19** mark/strip model-authored fake result-comments — already shipped as
  **C-19** (`7390414`), inherited from the `feature/agent-runtime` base.

This PRD covers the **remaining open, non-LLM asks**. They are independent
substrate/UI/boot fixes.

## Branch + deployment safety (read before touching anything)

- Work on **`feature/substrate-asks`**, stacked on the `feature/llm-sdk-migration`
  tip (the LLM branch merges independently first; this rebases onto the base
  after). It was branched at the LLM tip with **no working-tree change**.
- **The live Aria/Qwen pod runs from this working tree.** Do NOT checkout a
  branch that lacks the LLM migration (the working tree would lose the
  `seon.ai.openai-compat` adapter, cljs-watch would recompile old code, and the
  running deployment would break on reload). Stay on `feature/substrate-asks`.
- The pod is deployed `:openai-compat` (Qwen `sangam-virtue`). Env-coupled cljs
  tests (`seon.ai-test/provider-defaults-to-deepseek`, `my.soul-test`) "fail" on
  ambient store/env — not regressions. See [[project-env-coupled-cljs-tests]].
- **cljs.test wedge gotcha:** never fire overlapping `cljs.test/run-tests` in the
  live pod (wedges the shared async continuation; doesn't self-clear). Restart
  the pod (`bin/seon restart pod`, wait for `agent roster` in `logs/pod.log`)
  for a pristine run, or verify a single behavior by evaluating the fn directly.
  Never head an async test's `->` thread with a forward-`declare`d var (compiles
  to `true.then(...)` → wedge); head with a var defined ABOVE the test.

## Agreed sequencing (user, 2026-06-16)

**Quick wins first** (this batch), then #16, then design+build the two
architectural asks (#27, #28) as separate units. Delegate each to a `seon-agent`
(opus); run agents SEQUENTIALLY (not parallel) to avoid live-pod test
contention. Full suite once per unit at the natural checkpoint.

## Quick wins (do these first)

### #29 — Replay re-executes side-effecting bare `def`s → ghost messages (BUG)

The only real bug here; it silently poisons every agent mint. Found live
2026-06-12: a test agent self-tee'd `(def virtue-eval (seon.agent/message!
{...to user... "Running virtue eval…"}))`. The tee indexed it as a `:seon.fn`
row; **replay re-executed it on every pod boot and agent mint**, so every fresh
agent's chat opened with a ghost message from an agent that no longer exists.
Aria fixed the instance by retracting the fn row + 8 ghost messages.

**Ask:** make replay (or the tee/indexer) refuse bare non-`defn` `def`s whose
init form calls effectful substrate fns (`message!` / `reply!` / `transact!`),
or at minimum replay them in a no-send sandbox.

**Where to look:** the tee/indexer that turns evals into `:seon.fn`/`:seon.ns`/
`:seon.schema` rows, and the boot/mint replay path that re-evaluates them
(`src/seon/client.cljs` boot; the indexer ns; the analyzer that classifies
forms). Relates to the #14 replay fix `72f6aab` ("B4"). Read the
code-as-data-runtime concept ([[docs/seon/concepts/code-as-data-runtime]]).

**Acceptance:** a bare `def` whose init calls `message!`/`reply!`/`transact!`
is NOT replayed (or runs in a no-send sandbox) on boot/mint; `defn`s and pure
`def`s are unaffected; existing replay round-trip tests still pass. Live-prove:
mint a fresh agent after seeding such a def — no ghost message.

### #26 — Header chips count substrate bootstrap

`/agents` header chips (TURNS · FNS · FINDINGS · DATOMS) count the whole store
(≈200 fns / ≈8K datoms of seon internals), so a fresh world's numbers say
nothing about what the cluster learned. `/data` (shipped 2026-06-12) already has
the right semantics: post-bootstrap rows by default, `?system=1` for the full
view.

**Ask:** apply the same post-bootstrap filter to the header counters (datoms +
fns at least), ideally with the same `?system` toggle. Also **hide zero-count
chips** (FINDINGS=0 reads as confusing dead weight — demo viewers keep asking
what it is).

**Where:** the `/agents` page render + the counter queries (web routes/html;
reuse `/data`'s post-bootstrap filter — find how `/data` distinguishes
bootstrap from post-bootstrap rows and apply it here).

**Acceptance:** fresh world shows ~0 for learned fns/datoms; zero-count chips
absent; `?system=1` shows the full counts.

### #28-UI — Move the ✓ complete button to the card corner

Sean couldn't find the ✓ button (eaa03a1) — it sits inline next to "open" in the
card action row and reads as decoration ("oh, it's next to open. That's not
intuitive").

**Ask:** move it to the **upper-right corner of the agent card**
(absolute-positioned in the card, not in the action row); keep the
muted-default / amber-hover treatment. (The queued revive-from-UI follow-up
would fit the same corner on completed cards under `?completed=1` — optional.)

**Where:** the agent-card component in the `/agents` render. POST endpoint
`/agent/<id>/complete` (`seon.agent/complete!`) is unchanged.

**Acceptance:** ✓ sits in the card's upper-right corner, muted→amber on hover,
still POSTs complete.

### #15 — Configurable identity-seed filename (SOUL.md → AGENTS.md)

`my.soul/soul-md-path` hardcodes `"SOUL.md"`. Aria's identity file is now
`pod/AGENTS.md` with a `SOUL.md → AGENTS.md` symlink workaround.

**Ask:** read the seed filename from an env var (`SEON_SOUL_FILE`?) and/or try
`AGENTS.md` as a fallback name. Cosmetic.

**Where:** `src/my/soul.cljs` (`soul-md-path` + the boot seed read in
`client.cljs`). Mirror the env-owns-config pattern used elsewhere
(`SEON_AI_*`, `SEON_BRAND_*`).

**Acceptance:** `SEON_SOUL_FILE=AGENTS.md` (or a fallback to `AGENTS.md` when
`SOUL.md` absent) seeds identity from that file; default unchanged when unset;
aria can drop the symlink.

## Then (small-medium polish)

### #16 — Fold generic REPL discipline into the substrate `<system>`

Aria's identity file carries substrate-generic guidance every downstream product
would copy: (a) hiccup shape rules for tile fns (splice children with `into`;
call the fn once before wiring), (b) "printed results are clipped — bind and
process with code", (c) "never write expected results; your output is REPL
input", (d) provenance/confidence discipline for `my.kb.*` writes.

**Ask:** fold these into the substrate `<system>` section (or `my.kb.system`
defaults) so downstream identity files can be pure product persona. Relates to
#13's parser contract and the C-19 render. Content/wording work — keep it tight;
don't duplicate what the parser contract already enforces.

## Architectural (design first — separate units, likely own PRDs)

### #27 — On-reply hook for ambient post-processing (virtue panel)

Aria wants a virtue panel reacting to EVERY assistant reply (Sean, 2026-06-12),
not just on-demand agent self-scoring (model-dependent, per-agent).

**Ask:** a substrate `on-reply` hook — register a fn that fires on each
assistant message with text + agent id, runs async, and can transact rows
(scores/tags/flags) keyed to that turn, independent of agent cooperation. Plus a
way to render a panel BESIDE the chat (not the agent's own tile) reading those
rows. Generalizes live moderation, sentiment, cost rollups. Visual target:
port angelic's `DecisionSpectrumRadar` (pure-SVG polygon-per-cluster) to
hiccup-SVG. See `docs/2026-06-12-virtue-tile.md`.

**Design notes:** fits the reactive-context model — the panel is a section fn
querying the rows the hook writes ([[docs/seon/concepts/reactive-context]]).
The hook is the genuinely new mechanism (where does reply emission fire — find
`seon.agent/reply!`/`message!` and the turn-close path; the hook should fire
there, async, fail-soft). Design before building (EnterPlanMode).

### #28 — Boot-seed downstream `my.*` source from a consumer-owned dir

Seon ships `my.kb`/`my.soul`/`my.kb.system` as compiled source required at boot.
A downstream product (Aria) has no build of its own and seon's tree is read-only
to it (IP boundary), so Aria's durable product code (e.g. `my.virtue`) has
nowhere first-class to live (agent-authored `:seon.fn` rows are
snapshot-only/fragile; raw evals don't persist).

**Ask:** `SEON_SEED_DIR=<abs path>` whose `*.cljs` files are evaluated through
the recording path at startup (persisted as replayable
`:seon.fn`/`:seon.ns`/`:seon.schema` rows), OR compiled+required like the
built-in `my.*`. The durable home for Aria's `pod/seed/my_virtue.cljs`; closes
the `aria.*`/compiled-ns gap. Relates to #16 and #27. This is the natural home
for the code the #27 hook would call. Read [[docs/seon/concepts/code-as-data-runtime]]
("the substrate source IS the bootstrap"). Design before building; coordinate
with #29 (the recording/replay path is the same machinery — seeding through it
must not re-fire side effects).

## Verification (all asks)

Live proof, not inference. After each unit: targeted tests for the touched ns;
live-prove the behavior on the pod (mint a fresh agent for #29; load `/agents`
for #26/#28-UI; boot with `SEON_SOUL_FILE` for #15). Full cljs suite once at the
batch checkpoint — on a freshly-restarted pod (see the wedge gotcha). Report
honest counts.

## Pointers

- Asks source of truth: `tmp/2026-06-11-seon-asks.md` (OPEN sections #26, #28,
  #27, #15, #16, #28-UI, #29; closed asks in its table).
- `docs/2026-06-12-virtue-tile.md` (#27 visual target).
- Concepts: [[docs/seon/concepts/reactive-context]],
  [[docs/seon/concepts/code-as-data-runtime]].
- Replay/tee prior art: commit `72f6aab` ("B4", #14 replay fix).

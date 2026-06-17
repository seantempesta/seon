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

**SHIPPED (narrow):** committed `a69e89a` — `seon.eval/effectful-bare-def?`
classifier skips teeing/replaying a bare def whose init calls
`message!`/`reply!`/`transact!`; used at both the tee (`build-tee-entities`)
and the replay filter (`query-program-graph-entries`). 527 tests green,
live-proved the poison row is dropped from the replay set.

**FOLLOW-UP — stricter persistence policy (user, 2026-06-17), supersedes the
narrow guard.** Persist/replay ONLY a literal `defn`, a schema registration,
or a `deftest`. Everything else is NOT persisted or resumed, and the agent gets
a warning that it won't be:

- `defn` → `:seon.fn` (the only fn-defining form that persists).
- `schema/register!` → `:seon.schema`.
- `deftest` → `:seon.test`.
- bare `(def x …)` — rejected + warned (regardless of init purity).
- `(def f (fn …))` — ALSO rejected + warned (write `defn`; "no `(def _ (fn …))`
  crap"). The classifier must read the original FORM HEAD (`defn` vs `def`) —
  the analyzer's `:fn-var?` is `true` for both `defn` and `(def f (fn …))`
  (both macroexpand identically) and so cannot distinguish them.
- Implication: `effectful-bare-def?` becomes dead code (subsumed) and is
  removed; replay drops any stored row that is not a `defn`/schema/test.
- Warning surface: a non-persisted def leaves NO DB row, so the warning is
  stamped at eval-time (on the `:seon.eval` entity the warnings section
  queries) or returned inline in the eval result — confirm against
  `seon.warn`. Rationale: "don't store/replay arbitrary code — if an agent
  causes a crash, resuming re-causes it; `defn`/schema/`deftest` are
  declarative and safe to re-eval."
- This is a separate unit on `feature/overridable-substrate`; it is compatible
  with the overridable-substrate design (overrides are `defn`s → persist).

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

**Status (2026-06-16): ALREADY SATISFIED by the 2026-06-12 unasked UI work** —
no code change needed. Live-verified against the pod (`:7890`):

- Default `/agents` chips: `agents · turns · facts` only. `facts` is the /data
  default row count via the SAME `data-scan` post-bootstrap derivation
  (`inspector.cljs` `cluster-stats`/`agents-dash-fragment`, ~L171–265,
  L1206–1265) — header and /data can never disagree.
- Zero-count chips are hidden (`(when (or system? (pos? …)) …)`), so the
  confusing `FINDINGS=0` chip is gone (findings folded into `facts`).
- `?system=1` (same query param as /data) reveals the machinery row:
  `datoms · fns · schemas · tests` (full-store counts — the intended "full
  view" semantics).

Divergence from the literal ask: the default view omits `fns`/`datoms` entirely
rather than showing post-bootstrap-filtered counts for them. This is arguably
cleaner (machinery lives behind the toggle; the default surfaces only
user-meaningful learned `facts`) and satisfies the acceptance's intent
(no misleading full-store numbers by default; zero chips absent; full counts
under `?system=1`).

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

### Design (2026-06-16 research)

Full design + source citations:
[[docs/prds/agent-runtime/research/27-28-architecture-2026-06-16]].

**Chosen fire-site:** `seon.agent/ask-and-eval!`, the SUCCESS branch
(`src/seon/agent.cljs:1066-1069`). It is the one place per turn that holds the
raw assistant completion (`reply-text`, derived in `ask-and-eval-reply!` at
`agent.cljs:959`), the agent id, and the turn id, BEFORE the turn closes —
firing here catches EVERY turn that called the LLM, not just turns where the
model chose to `reply!` (the user-facing `reply!` is the WRONG point: not every
turn emits one, defeating "every reply"). Thread `reply-text` into the call.

**Mechanism:** a process atom `!on-reply-hooks` of registered SYMBOLS (a
legitimate runtime artifact per reactive-context, same shape as `seon.warn`'s
check registry); `register-on-reply!` / `unregister-on-reply!` verbs. At
fire-time, `fire-on-reply-hooks!` resolves each symbol via
`seval/lookup-value` (late resolution → hot-reload-safe, like `:seon.render/ai`
slots), runs it inside `db/with-agent` (re-enter the ALS scope on the new
microtask), fire-and-forget with a `.catch` that only logs — NOT awaited,
cannot change the turn outcome (mirrors `persist-prompt!`'s fail-soft posture).
The hook transacts its own `:my.virtue/*` rows keyed to the turn ref.

**Data shapes (Malli):**

```clojure
(schema/register! :seon.agent.reply/text :string)
(schema/register! :seon.agent/on-reply-input
  [:map
   [:seon.agent/id         :seon.agent/id]
   [:seon.agent.turn/id    :seon.agent.turn/id]
   [:seon.agent.reply/text :seon.agent.reply/text]])
;; downstream, in the #28 seed dir (NOT src/ — IP boundary):
(schema/register! :my.virtue/id        [:string {:seon.db/identity true}])
(schema/register! :my.virtue/turn      :seon.db/ref)
(schema/register! :my.virtue/dimension :keyword)
(schema/register! :my.virtue/score     :int)
(schema/register! :my.virtue/at        :inst)
```

**Panel:** a stacked pane in the existing consumer-view right column
(`inspector.cljs:1106-1111`, the tile column) — zero layout reflow, reuses the
existing per-agent SSE push (`consumer-snapshot` / `push-agent!`,
`inspector.cljs:969-985,1700`). The panel body is a section fn querying the
turn's `:my.virtue/*` rows — pure reactive-context, vanishes on a fresh world.
The SVG radar (`my.virtue/radar-svg`) lives in the seed dir as `[:svg …]`
hiccup (axis spokes + `[:polygon …]` per cluster, pure cljs math, no deps —
like `activity-sparkline`). Note: `docs/2026-06-12-virtue-tile.md` does NOT
exist in this tree and the angelic source is not vendored — port from the
visual spec only.

**Checklist:**

1. Register `:seon.agent.reply/text` + `:seon.agent/on-reply-input` in
   `seon.agent`.
2. Add `!on-reply-hooks` atom + `register-on-reply!` / `unregister-on-reply!`
   (store symbols; resolve late).
3. Add `fire-on-reply-hooks!` (fail-soft, not awaited, `with-agent` re-entry);
   call from `ask-and-eval!`'s success branch threading `reply-text`.
4. Add the consumer-view panel fragment; extend `consumer-snapshot` /
   `consumer-payloads`; panel resolves a configurable downstream symbol
   (default absent = no panel).
5. (Downstream) `my.virtue` schemas + hook fn + `radar-svg` in the #28 seed
   dir.

**Open questions:** fire per-turn only (recommended) or also on user-facing
`reply!`? Panel stacked-in-tile-column (recommended) vs third grid column?
Hook concurrency backpressure (defer — add a single-flight latch only if it
bites).

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

### Design (2026-06-16 research)

Full design + source citations:
[[docs/prds/agent-runtime/research/27-28-architecture-2026-06-16]].

**Recommendation: `SEON_SEED_DIR` driving the RECORDED path** (primary), with
the already-designed `SEON_EXTRA_SRC` compiled path (task #36, partially wired:
`!extra-substrate-vars` / `read-src-file` probe / `substrate-ns-set` union all
exist in `client.cljs`) as the power-user sibling. Recorded is what the ask
literally requests, needs NO build (the consumer's stated blocker), and is the
home for the #27 hook code.

**Seed-dir flow through the recording path:** mirror `creation-evals!`
(`client.cljs:1842-1906`), which already evals a source string through
`seval/eval-batch!` as a system-origin boot turn — that IS the recording path.
New step in `start-agent!` AFTER `replay-program-graph!` (`client.cljs:1985`),
BEFORE the per-agent boots: read `SEON_SEED_DIR` via the `env-val` pattern
(`brand.cljs:88`; nil/blank = no-op), list+read `*.cljs` (sorted, Node `fs`),
eval each through `eval-batch!` inside a `{:seon.db/origin :seed-dir}`
tx-context. detect-and-tee (`eval.cljs:876`) then persists the
`:seon.fn`/`:seon.ns`/`:seon.schema` rows automatically — no separate persist
code; they replay on every subsequent boot.

**#29 coordination (critical):** the seed step MUST be idempotent — on the
second boot the file's defs are already program-graph rows, so re-evaling would
re-fire effectful top-level forms (exactly the #29 ghost-message bug). Guard:
before evaling a file, query the program graph for its ns and SKIP files whose
defs already exist (same conn-dedup `substrate-index-tx` does,
`client.cljs:1417`). First boot seeds; later boots let REPLAY reconstitute.
And #28's correctness DEPENDS on #29 landing — the seed dir is a new SOURCE of
exactly the rows #29's "don't replay effectful bare `def`s" makes safe. Build
#28 AFTER #29. Seed-file contract: `defn`/pure `def` only; wiring via verb
calls (`register-on-reply!`), never `(def x (effectful …))`.

**Env var:** `SEON_SEED_DIR` (absolute path), `SEON_*` family, default absent =
byte-identical behavior. Read via `env-val`, NOT through `artifact-path` (the
dir is the consumer's, like `read-src-file`'s `SEON_EXTRA_SRC` probe). If both
`SEON_SEED_DIR` and `SEON_EXTRA_SRC` name the same ns, compiled wins (it is in
`substrate-ns-set`, so seed-dir rows for that ns replay-skip) — use one path
per ns.

**Compiled-vs-recorded tradeoff:** recorded persists rows that survive file
deletion and replay each boot (needs the #29 guard); compiled re-derives from
source each boot, is never replayed (no side-effect risk), and gets full Malli
instrumentation via `index-substrate!`. Recorded is for product code with no
build (Aria's `my.virtue`); compiled is for exemplar nses a downstream wants
agents reading whole. **Recommend recorded for #28.**

**Checklist:**

1. `seed-dir` helper (`env-val "SEON_SEED_DIR"`; nil/blank → no-op).
2. List + read `*.cljs` (sorted) via Node `fs`.
3. Idempotency guard: skip files whose ns defs already exist in the program
   graph (conn-dedup shape).
4. Eval each remaining file through `eval-batch!` as a system-origin boot turn
   (mirror `creation-evals!`), `{:seon.db/origin :seed-dir}`.
5. Call in `start-agent!` after `replay-program-graph!`, before per-agent
   boots.
6. Depends on #29 (the effectful-bare-`def` replay guard must land first).
7. Document the seed-dir contract (`my.*` prefix, `defn`/pure `def`, verb-call
   wiring).

**Open questions:** `my.*` prefix asymmetry — `SEON_EXTRA_SRC` REJECTS `my.*`
(would replay-skip agent corpus) but the seed dir WANTS `my.*` (it IS replayed
corpus); confirm `my.*` and ensure a seed-dir ns is never also in
`substrate-ns-set`. Provenance origin: distinct `:seed-dir` (recommended, for
audit) vs reuse `:substrate-seed`? Re-seed on file CHANGE (v1: no,
first-boot-only).

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

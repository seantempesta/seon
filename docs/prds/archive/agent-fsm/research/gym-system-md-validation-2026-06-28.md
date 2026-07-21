---
type: research
status: draft
tags: [research, agent]
---

# Gym-driven SYSTEM.md validation — fresh-context, system-message-only probes (2026-06-28)

> Owner ask (2026-06-28): "live DeepSeek tests with just the system message, make sure all of
> the ideas are clearly explained by directly testing the agent. Fresh context for each test so
> we can see if they know it directly or if they have to experiment and fail. Multi-turn is fine.
> Look into our gym — I think we can do this via EDN configs?"

This is a FINDINGS + TEST-DESIGN doc, not code. It answers: can EDN configs drive fresh-context,
system-message-only validation of a proposed `SYSTEM.md` against a live DeepSeek agent, scoring
whether each idea is understood DIRECTLY vs experiment-and-fail? Every claim is grounded in the
source/docs cited inline (file:line).

## TL;DR — feasibility verdict

**PARTLY — gated on one small gym-lane wiring + (for true "system-message-only") the unbuilt
reconcile/desired-set.** Concretely:

- **Fresh context per test: YES, today, for free.** `run-scenario!` opens a fresh scratch
  `:memory` conn and mints fresh agent ids on every call (`driver.cljs:1357`, `:1408`,
  `ensure-agent!` `:1237`). k reps = k calls; each scorecard carries a fresh `run-id`
  (`:1524`). The gym is *already* the fresh-context-per-run harness the ask describes.
- **Drive a live DeepSeek agent through the real loop with scoring: YES, today.** The `:paid`
  tier wires the active provider (`SEON_AI_PROVIDER=deepseek`) through `run-loop!` and scores
  mechanically + with an LLM judge (`paid-adapter` `:1128`, `drive-loop!` `:1212`).
- **Swap the system message via an EDN config: NOT today, but it is a ~10-line gym-lane change,
  not a build.** The override PATH exists end-to-end (`effective-system-prompt` honors
  `:seon.ai/system-prompt`, `ai.cljs:360`); the adapter accepts opts (`agent-adapter`,
  `openai_compat.cljs:412`). What's missing is a scenario field carrying the SYSTEM.md and a
  thread-through in `paid-adapter`. No `:seon.gym.scenario/system-prompt` field exists today
  (scenario schema `driver.cljs:264-278`), no `SEON_AI_SYSTEM_PROMPT` env, no `::system-prompt`
  in `config-attrs` (`ai.cljs:178`, `:223-231`).
- **Strip to system-message-ONLY (minimal/no context blocks): GATED.** Context blocks are
  SEED-COPIED into every fresh agent at creation from `default-seed-blocks` (`ctx.cljs:1599`),
  inside `agent/create!`. There is no EDN field for "which blocks to keep," and the clean
  "set the desired block-set as a config" path is exactly the unbuilt `reconcile!` /
  desired-set from `holistic-state-management-2026-06-28.md` (not in `src/` — confirmed by grep).
  Without it, every probe runs system-message + the FULL default stack (`:namespaces` ~37k
  tokens, `:inventory`, `my.kb.shared/instructions`, `:transcript`), which *also* teaches the
  agent and confounds "did they learn it from the SYSTEM message."

So: **the EDN-config vehicle is the right one** (the holistic doc's "a config = a desired-set
reconciled in" is precisely the model — set the system message AND the kept block-set in one
desired-set). It is **half-built**: fresh context + live drive + scoring are real today; the
"swap the system message + strip blocks" half needs (a) a tiny scenario field for round 1, and
(b) the reconcile for the clean version.

## 1. The gym, explained (file:line)

**What it IS.** `seon.gym.driver` (`test/seon/gym/driver.cljs`) is a scenario harness. A scenario
is EDN DATA: a `:seon.gym.scenario/id`, fixtures (tx-data seeded before the run), `:turns` (user
messages, optionally per-agent designator), and PASS-PREDICATES — datalog queries against the
post-run store + transcript/prompt checks the driver evaluates MECHANICALLY, plus optional
`:llm-judge` predicates (rubric + reference facts → graded verdict) recorded on a SEPARATE
scorecard axis (`driver.cljs:1-71`). Scenarios load via `load-scenarios!` (`:463`), every scenario
Malli-validated and self-bait-checked at load (`check-self-bait!` `:425` — a scenario's question
text may never appear verbatim in its own fixtures, so a "did the agent consult X" predicate can't
pass by string coincidence).

**How it drives a live agent.** `run-scenario!` (`:1357`):
1. opens a fresh scratch `:memory` conn (`client/open-agent-conn!`, `:1408`) and swaps the root
   `seon.db/*conn*` for the run (restored in `finally`, `:1559`);
2. seeds THE WORLD A POD BOOTS INTO via `seed-scenario-world!` (`:1273`) — `client/boot-seed!`
   (handlers + entity-schema decomposition + core index + soul seed, under `:core-seed` origin)
   then the scenario's prior-agent registrations + fixtures under a synthetic prior-agent id;
3. per turn: lazily boots the turn's agent (`ensure-agent!` `:1237`, which runs the same
   `client/bootstrap-turn!` hello+park a live agent runs), lands the question as a real user
   message (`send-user-message!` `:1161`), and drives per tier:
   - `:paid` → `drive-loop!` (`:1212`) runs the REAL `seon.agent.loop/run-loop!` with
     `paid-adapter` (`:1128`) — the active provider's adapter (`anthropic`/`openai-compat`),
     selected by `SEON_AI_PROVIDER` exactly like the live pod (`current-llm-fn`);
   - `:stub` → scripted replay, free, no LLM.

**How it isolates.** Every run is on a throwaway `:memory` conn; the live cluster store (7890) and
the acme harness (7980) are untouched by construction (`:15-20`, live-run doc confirms "the live
default cluster and the acme harness were untouched"). Schema keys minted during the run (fixtures
OR the agent's own `register!` evals) are dropped in `finally` (`:1562`) so one scenario can't leak
into the next. Filesystem capability is configured to `src/` + `docs/` ONLY, deliberately EXCLUDING
`test/` (`:1426-1429`) so a paid agent can't grep the judge's reference text out of the harness —
the filesystem variant of the self-bait rule.

**How it scores.** Mechanical predicate kinds (`:177-180`): `:datalog` (query the post-run store,
match `:expect` — `:non-empty`/`:count`/`:count<=`/`:every-in`…), `:transcript-includes/excludes`,
`:first-eval-matches` (regex on the chronologically first eval's source — `:554`),
`:eval-count-matching` (count evals whose source matches a pattern — `:731`), `:domain-attrs`
(agent-provenance attrs only, the no-fork surface — `:739`), and `:prompt-includes/excludes/
every-turn` (the referee's EYES: assert against the verbatim prompt blob the agent actually saw,
persisted per turn via `seon.debug` capture forced ON for the run — `:608`, `:1406`). The LLM judge
grades the agent's verbatim reply against a rubric + reference facts on a SEPARATE
`:judge-pass?`/`:judge-results` axis, so "behaved right, answered wrong" stays a distinct signature
(`:335-352`, `:799`). Every blind spot (missing prompt blob, broken predicate, out-of-range turn) is
scored RED, never a silent pass (`:608-663`, `:678-748`).

**"No cheating / no coaching."** Two structural rules enforce it: the agent never reads the harness
(filesystem excludes `test/`, `:1426`), and the gym tests the AGENT, not the context LAYOUT —
structural/layout predicates were ripped out (user r2, 2026-06-11; `:122-131`, `:766-773`); per-turn
context telemetry (`turn-profiles`) is recorded but NEVER gates `pass?`. Fixes to seon must be
general mechanisms, never answer-shaped (the live-run doc flags runtime issues, never patches `src/`).

**Fresh-context-per-run is already the model.** Each `run-scenario!` call = a fresh scratch store +
freshly-minted agents booting from the seed. There is no carry-over between runs. Multi-turn within
ONE scenario reuses the SAME agent (continuity across turns is preserved — that's how a multi-turn
task works); cross-RUN there is zero shared state. This is exactly the ask's "fresh context for each
test."

## 2. Feasibility, concretely — the gap to system-message-only EDN configs

### What's possible TODAY (no src change, or gym-lane only)

| Capability | Status | Grounding |
|---|---|---|
| Fresh context per test | ✅ today | `run-scenario!` scratch conn + fresh agent ids (`:1357`,`:1408`,`:1237`) |
| Live DeepSeek drive through the real loop | ✅ today | `:paid` tier → `paid-adapter` → `drive-loop!` (`:1128`,`:1212`) |
| Mechanical + judge scoring, RED on blind spots | ✅ today | predicate kinds (`:177`), judge axis (`:340`), calibration (`:981`) |
| k reps for a pass-RATE | ✅ today | call `run-scenario!` k times; fresh `run-id` each (`:1524`) |
| Probe wrong-shape / failed evals | ✅ today | `:seon.eval/ok?` is a stored boolean per eval (`eval.cljs:2133`), joinable via `:seon.agent.turn/evals` — a `:datalog` predicate counts `[?ev :seon.eval/ok? false]` scoped to caused runs |

### What's GATED (and on exactly what)

1. **Set the system message from a config.** The override path is fully present —
   `effective-system-prompt` returns `:seon.ai/system-prompt` when given, else the hardcoded
   `ctx/system-text` (`ai.cljs:360-369`); both adapters read it (`anthropic.cljs:161`,
   `openai_compat.cljs:200`); `agent-adapter` accepts `opts` that flow into `complete`'s request
   (`openai_compat.cljs:412-426`). The ONLY missing links: (a) a `:seon.gym.scenario/system-prompt`
   field (the scenario schema `:264-278` has none), and (b) `paid-adapter` (`:1128`) constructs the
   adapter with NO opts, so nothing injects the override. **Gap = ~10 lines, test/ only**: add the
   optional field, read it in `run-scenario!`, pass `{:seon.ai/system-prompt <text>}` into
   `paid-adapter`/`agent-adapter`. No `src/` change. (A SYSTEM.md *file* is read with
   `node:fs`/`reader` exactly as `load-scenarios!` already does, `:471`.)

   Note the gym already proves env knobs reach the agent: it calls `(ai/sync!)` during seed
   (`:1454`) so `SEON_AI_MODEL`/`_TIMEOUT_MS` are honored — but `config-attrs` (`ai.cljs:223-231`)
   has NO system-prompt entry, so env CANNOT carry it. The scenario-field route is the right one.

2. **Strip to system-message-ONLY (minimal blocks).** Blocks are SEED-COPIED at agent creation from
   `default-seed-blocks` (`ctx.cljs:1599-1666`) — `:namespaces`, `:live-tile`, `:warnings`,
   `:open-todos`, `:inventory`, `:transcript`, plus `my.kb.shared/instructions` and the SOUL/AGENTS
   file-blocks. There is no EDN knob to seed a *reduced* set. Two routes:
   - **Clean (gated):** the unbuilt `reconcile!` + desired-set (`holistic-state-management-2026-06-28.md`).
     There, a config IS a desired-set (`{system message + kept block-set}`) reconciled in. This is
     literally the ask's "EDN config." NOT in `src/` (grep confirms). This is the right long-term home.
   - **Manual precursor (gym-lane):** after `ensure-agent!`, run `seon.agent.ctx/remove!`
     (`ctx.cljs` install!/remove! block, the existing scope-aware verb) inside the agent's scope to
     strip the heavy blocks before driving — a driver hook keyed off a scenario field
     (`:seon.gym.scenario/keep-blocks [...]`). This is the "hand-stripped context" the ask
     anticipates; it's the same diff the reconcile will later own.

   **Why this matters (the sharpest finding):** if you DON'T strip, the `:namespaces` block alone is
   ~37k tokens / ~84% of the prompt (gym-findings + live-run docs) and renders the agent's whole
   `my.*` world + tools in full, plus `my.kb.shared/instructions`. That block stack TEACHES — so a
   probe that "passes" can't be attributed to the SYSTEM message. The owner's "system message only"
   instinct is exactly what isolates the variable.

### Verdict

Can EDN configs drive fresh-context, system-message-only tests? **PARTLY / GATED-ON-block-strip.**
Fresh context + live drive + scoring are real today. The system-message SWAP is a tiny gym-lane
field. Truly system-message-ONLY (blocks stripped) is gated on the reconcile/desired-set (clean) or
a `remove!`-after-boot driver hook (manual precursor). The EDN-config-as-desired-set vehicle is
confirmed correct by how context is actually assembled (seed-copied blocks + a separately-resolved
system message) — it just isn't wired end-to-end yet.

## 3. The test PROTOCOL for validating a SYSTEM.md

### 3.1 Fresh context — how it's guaranteed

One `run-scenario!` call per rep = one fresh scratch `:memory` store + freshly-minted agents
booting from the seed (`:1357`,`:1408`,`:1237`). To test a SYSTEM.md: set
`:seon.gym.scenario/system-prompt` to that file's text (round-1 field) and run each probe scenario
k≥5 times for a pass-RATE. **k≥5 is non-negotiable**: gym-findings (n=2) showed 2 of 4 behavioral
axes FLIPPED verdict between byte-identical runs — single-run pass/fail is high-variance noise;
attributing comprehension to the system message needs rates, not one verdict (matches the
agentic-benchmarks survey's `pass^k`). Each rep is independent context by construction.

### 3.2 Minimal blocks — round 1 vs clean

- **Round 1 (today):** run with the full default block stack but choose probes whose mechanic is
  SYSTEM-message-specific (forms-start-with-`(`, errors-are-values, `result/<id>`, terminal verbs).
  The judge + a dedicated observer cover the gap. Acceptable for a first read; note in the scorecard
  that blocks weren't stripped.
- **Clean (when reconcile or the `remove!` hook lands):** strip to `{system message + transcript}`
  (the transcript is structurally required — it's where the agent's turns render) so a pass is
  attributable to the SYSTEM message alone.

### 3.3 The ideas to probe (drawn from the ideal-system-md draft) + the signal per idea

For each: the IDEA (system-message claim), the SIGNAL that says "knew it directly," and the SIGNAL
that says "experimented and failed first." `eval-ok` below = a `:datalog` predicate counting
`[?ev :seon.eval/ok? false]` evals (failed/parse-error/instrument-throw) scoped to the run's caused
turns; `eval-count` = `:eval-count-matching` on a source regex; `first-eval` = `:first-eval-matches`.

1. **Forms must start with `(` on a new line; prose needs `;`** (`system-text` "EVAL MECHANICS"
   `ctx.cljs:922`). DIRECT: first substantive eval parses & runs (`eval-ok` count 0 before first
   success). FAILED: ≥1 NOTE/parse-failure or `:seon.eval/ok? false` eval first; transcript shows a
   parse-recovery line. *Single-turn idea; pure mechanical.*
2. **Reach for `result/<id>` instead of re-querying** (`system-text` "RESULT VARS" `:958`). DIRECT:
   `eval-count` pattern `result/` ≥1 AND no duplicate identical query. FAILED: the same query form
   re-run across turns (`eval-count` of the query pattern >1 with no `result/` reference).
   *Legit multi-turn (the result only exists on a later turn).*
3. **Errors are values, not throws** (`system-text` "ERRORS ARE VALUES" `:976`). DIRECT: after an
   `:seon.eval/ok? false` eval, the next eval reads/destructures the error map; the reply (judge
   axis) describes the defect, never "it threw an exception." FAILED: agent reports a throw / abandons
   / retries blindly without reading the envelope. *Legit multi-turn; needs the judge for the reply.*
4. **Curate context — load a big doc, work, unload it** (ideal draft pillar 5; `install!`/`remove!`
   exist `ctx.cljs:1697`, the named `load-doc!` verb does NOT — ideal Part 3). DIRECT: `eval-count`
   for `install!`/`remove!` (or `load-doc!`/`unload!` once built) bracketing the work. FAILED: agent
   never loads, tries to answer from a clipped view, or re-queries repeatedly. *Legit multi-turn;
   PARTIALLY GATED — probes `install!`/`remove!` today, the ergonomic verb later.*
5. **Finish with verbs: `(complete …)` / `(wait …)` / `(message/user …)`** (`system-text`;
   ideal draft "Talk and finish with verbs"). DIRECT: `first-eval`/`eval-count` shows the terminal
   verb; the run closes cleanly (terminates axis) WITHOUT hitting the turn-limit. FAILED: the run
   drives to its turn-cap with no terminal verb, or the agent narrates "done" in prose and idles
   without `(complete …)`. *Single- or multi-turn.*
6. **`register!` before `transact!`; two-segment namespaces** (`system-text` "THE SHARED STORE"
   `:991`). DIRECT: `eval-count` shows `register!` precedes `transact!`; `:domain-attrs`
   `:every-in` shows NO forked attr; zero unregistered-attr error envelopes (`eval-ok` 0). FAILED:
   ≥1 unregistered-attr / bad-keyword error envelope before recovery. *Single- or multi-turn.*
7. **`my.*` is yours, `seon.*` is the protected floor** (`system-text`; ideal "Shape your
   environment"). DIRECT: new code lands under `my.*`. FAILED: an attempt to redefine a `seon.*`
   fn (error envelope). *Single-turn.*
8. **Discover, don't guess (grep / inventory / render-namespace)** (`system-text` "Never
   hallucinate a fn name — discover it" `:1005`). DIRECT: `first-eval` is a `grep`/`store-inventory`/
   `render-namespace` call before acting. FAILED: agent calls a non-existent fn (error), then
   discovers. *Single- or multi-turn.*
9. **State across turns: `defn`/atom persist, bare `def` doesn't** (`system-text` "STATE ACROSS
   TURNS" `:970`). DIRECT: agent defines a helper one turn, calls it the next; holds mutable state
   in an atom. FAILED: relies on a bare `(def x …)` and finds it gone, or recomputes. *Legit
   multi-turn by construction — needs ≥2 turns.*
10. **ClojureScript/Node, never `java.*`** (`system-text` line `:894-900`). DIRECT: js/ interop only.
    FAILED: a `java.*` import attempt (error envelope; `eval-ok`). *Single-turn.*

### 3.4 The distinguishing signal — "knew it directly" vs "experimented and failed"

Across all probes, the same family of signals separates the two, and most are AUTOMATABLE from the
post-run store the gym already captures:

- **turn-count-to-success** — fewer turns to the correct terminal verb / answer = knew it. (Derive
  from `:seon.agent.turn` count on the caused run, mirroring `agent-turn-count`.)
- **failed-evals-before-first-success** — `[?ev :seon.eval/ok? false]` count before the first
  `:seon.eval/ok? true` substantive eval (`eval.cljs:2133`). This is THE direct-vs-experimented
  axis. The current `:eval-count-matching` counts but does not ORDER relative to success — a small
  new predicate kind (or a CLJS-side fold over the chronological `eval-at+source` `:511`) makes it a
  first-class score. *Automatable, small.*
- **parse-recovery events** — transcript renders a parse error + caret; `:transcript-includes` on the
  recovery marker, or `eval-ok` count, quantifies wrong-shape attempts.
- **re-query duplication** — the same query form run twice without a `result/<id>` reference =
  didn't internalize result vars (probe 2).
- **turn-cap-without-terminal-verb** — the run hit its bound with no `(complete …)`/`(wait …)` =
  didn't internalize the lifecycle (probe 5).
- **reply mischaracterizes errors as throws** — judge axis only (probe 3); no mechanical proxy.

Automatable vs human: the eval/turn/transcript signals are mechanical predicates the gym runs today
(`:datalog` on `:seon.eval/ok?`, `:eval-count-matching`, `:first-eval-matches`,
`:transcript-includes`). The "directly vs experimented" rate is automatable with the small
ordered-failed-eval predicate. The semantic reads (errors-as-values reply, did-they-truly-grok-it)
need the LLM judge PLUS a dedicated human observer of the real prompt/evals — the standing
"pair live drives with a dedicated observer; server-side mechanics passing ≠ the context helps the
agent" rule.

### 3.5 Which probes legitimately need multiple turns

By construction: #2 (result var only exists on a later turn), #3 (error then handle), #4 (load,
work, unload), #9 (define then call). #5 (complete only AFTER computing the answer — the
system-message rule "if a reply depends on a value you haven't computed, query this turn, reply next"
`ctx.cljs:929`). The rest can pass in one turn but tolerate more. Multi-turn-legit is fine and
expected — the ask explicitly allows it.

## 4. Recommendation — the smallest path

1. **Start on the GYM, not acme.** The gym is the fresh-context-per-run harness; acme
   (`docs/seon/components/acme-harness.md`) is a PERSISTENT isolated cluster (pod 7980 / wire 7981,
   real store, one long-lived agent) for downstream-shape bugs and override-proofing — the WRONG
   tool for fresh-per-test (no per-run reset). Keep acme for the separate "do the consumer's
   overrides still render" check.
2. **Round 1 (now, gym-lane only, ~10 lines, no `src/` change):** add optional
   `:seon.gym.scenario/system-prompt` (string OR a file path read with `node:fs`) to the scenario
   schema (`driver.cljs:264`); in `run-scenario!` thread it into `paid-adapter`/`agent-adapter` as
   `{:seon.ai/system-prompt <text>}` (the override the resolution path already honors,
   `ai.cljs:360`). Write ~10 probe scenarios (§3.3), each `:paid`, with mechanical predicates on
   `:seon.eval/ok?` / `:eval-count-matching` / `:first-eval-matches` + an `:llm-judge` for the
   semantic reads. Run each k≥5 for a pass-RATE. Accept the full block stack for this round; note it.
3. **Add the ordered "failed-evals-before-first-success" predicate** (small) so the
   direct-vs-experimented axis is a real number, not inferred.
4. **Clean version (when it lands):** strip to `{system message + transcript}` via the
   `holistic-state-management` `reconcile!`/desired-set — at which point the EDN config genuinely
   IS `{SYSTEM.md + kept block-set}` reconciled into a fresh agent, the end-state the ask describes.
   The manual precursor (a `remove!`-after-boot driver hook keyed on `:seon.gym.scenario/keep-blocks`)
   is available earlier if block isolation is needed before reconcile ships.
5. **Scoring split:** mechanical (eval-ok counts, eval-count, first-eval, transcript, terminates,
   no-fork) is automatable and trustworthy at k≥5. Semantic (errors-as-values reply, genuine
   comprehension) needs the judge + a dedicated human observer per the standing rule. Do NOT trust an
   n=1 behavioral verdict — the gym's own n=2 flip is the proof.

## Notes / honesty

- `reconcile!`/config-loader: confirmed NOT in `src/` (grep). `holistic-state-management-2026-06-28.md`
  and `config-loader-2026-06-28.md` are drafts; the ideal SYSTEM.md (`ideal-system-md-2026-06-28.md`)
  is a review artifact, not wired in.
- `system-text` is ~38k chars ≈ 9.5k tokens (per the ideal-system-md draft; it is one big `def`
  string starting `ctx.cljs:881`). It and `default-seed-blocks` (`:1599`) are the two halves a config
  must parameterize (system message + block-set) — they are assembled by SEPARATE paths
  (`effective-system-prompt` vs seed-copy), which is exactly why one desired-set is the right unifier.
- Gemini was not consulted — the question is fully answerable from source, and the ask said
  ground in source first (Gemini flaky today).

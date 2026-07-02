---
type: orchestrator
status: active
tags: [orchestrator, agent]
---

# agent-ctx — cross-lane channel

**The live Tooling↔Eval channel.** Append-only log of handoffs, cross-lane
flags, and shared decisions. The durable shared STATE (open tensions, settled,
pointers) lives in [[CLAUDE]]; this file is the chronological *conversation*
between the lanes. Read the tail before you start; append when you flag, hand
off, or decide something the other lane needs.

Lanes: **Tooling** (runtime/FSM/ctx-engine/`my.*` — "how context renders + what
agents have") · **Eval** (inspect-ai suite/scorecard/context-A/Bs — "does it
work + what agents see"). Boundary + contract: [[CLAUDE]] §"The contract".

Shared truth: `evals/scorecard.jsonl`. Attribution rule: a failing row is
context-defect / tool-defect / flake / model — the eval lane classifies and
hands tool-defects here with rendered-context evidence.

## Log

### 2026-07-02 — chunk opened (both lanes)

- **Eval → Tooling:** two tool defects queued with evidence — (1) fresh-world
  `my.kb` renders "0 fns, 0 schemas"; (2) turn-6 recall visibility gap during
  `/solve` (candidate root = `seon.db/*conn*` single dynamic root, see
  `docs/seon/orchestrator/issues/tx-feed-pump-timeouts.md`). Absorbed into
  [[CLAUDE]] §"Open tensions".
- **Tooling → Eval:** `my.plan` was renamed from `seon.agent.todo` + redesigned
  (deps/pace/expect/active/blocked, position anchor, windowed render) — the
  planning bench must re-ground on the new verbs
  (`plan!`/`step!`/`active!`/`needs!`/`done!`), the old verbs are gone.
- **Both:** channel named (`coordination.md`); boundary agreed as drawn in
  [[eval-lane-plan]]; the chunk builds on agent-fsm's 2026-07-02 shipped
  capstone; merge of `feature/agent-fsm` → main pending the peer wind-down
  commit, then both lanes branch `feature/agent-ctx` off main.

### 2026-07-02 — merged to main, `feature/agent-ctx` open (tooling lane)

- **Tooling → Eval:** `feature/agent-fsm` MERGED to main (`72dd8392`, --no-ff,
  owner-authorized; the config lane's CP-3 verification notes committed first
  as `5c09af38`). Working branch is now **`feature/agent-ctx`** — branch off
  it / rebase onto it, not agent-fsm.
- Phase-1 required-key resolution landed as the branch's first unit
  (`a6362630`: `seon.instrument` injecting wrapper + `instrument_inject_test`);
  full `bin/test-cljs` checkpoint running at time of writing.
- Housekeeping: all `reference-code/` entries verified as proper submodules
  (83/83 gitlinks, all at recorded SHAs); dev-hook detritus (`logs/`, `tmp/`)
  cleaned out of `reference-code/` and the `mvm`/`transformers` checkouts.
  Known wart: the dev hook writes `logs/`+`tmp/` relative to the edited file's
  tree, so editing inside a submodule litters it.
- **Owner ruling (both lanes):** pod split is now HARD — tooling lane = default
  pod (7890), eval lane = acme (7980). Separate systems: no cross-lane
  restart/reset coordination needed anymore; each lane keeps its own pod on the
  latest build + current context. [[CLAUDE]] §"How to run" updated.

### 2026-07-02 — post-merge smoke GREEN on acme (eval lane)

- **Eval:** post-merge smoke 4/4 PASS on a fresh acme reset (latest bundle, the
  45429044 freshness guard fired): shell/web grants live-verified in-pod
  (`{:seon.agent.shell/granted? true}` / `{:seon.agent.web/enabled? true}`);
  tx-feed pump failures **0** across boot + a 6-turn `/solve` drive + 8.5 min
  settled (pre-fix baseline 1–3/boot); `:seon.config/skills` manifest-owned
  loadout renders exactly the six configured skills, bodies verbatim from the
  configured dir. Lane path unblocked; calibration run dispatched next.
- **Eval → Tooling (tool defect, evidence attached):** every fresh boot logs
  `tile fn my.plan.internal/plan-block could not run under SCI bounding
  (Unable to resolve symbol: db/*conn*) — rendering it on the UNBOUNDED
  compiled path` (`logs/acme/pod.log:38`). Candidate root: the ns's
  `:seon.ns/source` require aliases not stored, so SCI can't resolve the
  `db/*conn*` alias. A hang in that fn would wedge the pod unbounded. Issue:
  `docs/seon/orchestrator/issues/sci-bounding-fallback-plan-block.md`.
- **Eval (self-owned):** `bin/seon` start/readiness echoes now print the real
  `$LOG_DIR` path via `log_file` (they hardcoded `logs/…` and lied under
  `bin/acme`); harness-doc `/agents` route drift filed
  (`docs/seon/orchestrator/issues/acme-harness-agents-route-drift.md`).

### 2026-07-02 — suite green + plan ratified with owner (tooling lane)

- **Tooling:** post-merge suite GREEN — 937 tests / 4310 assertions, 0/0
  (`e0f63c05`; both failures were stale tests — skills path now derived from
  `config/skills-dir`, run-close awaited not raced). Phase-1 instrument patch
  cleared of involvement.
- **Tooling → Eval:** SCI-bounding plan-block defect RECEIVED — queued in
  [[CLAUDE]] tensions + roadmap item 6; likely shares a root with the `*conn*`
  single-dynamic-root stability unit.
- **Tooling → Eval (skills corpus):** owner ruling — `.claude/skills` and
  `seon-skills/` are now SEPARATE corpora (`68d73395`, symlinks replaced with
  real copies): seon-skills = the agents' in-runtime corpus (yours to tune via
  the manifest), .claude/skills = Claude Code's dev corpus. They may diverge;
  agent-skill content should read from the AGENT's perspective (verbs, REPL,
  my.*), not repo-dev perspective — content rewrites are shared work, flag
  here before big edits.
- **Tooling → Eval (heads-up, owner-ordered):** all six `seon-skills/` are
  being REWRITTEN for the agent's in-runtime perspective (dev-material
  stripped, every example REPL-verified against the live default pod, verbs
  queried from the running system, full qualification per the alias law).
  Re-baseline any skill-sensitive rows after it lands.
- **Owner rulings:** (1) the principle canon (eight core ideas incl.
  never-crash/isolation + one-human bond) folded into
  `docs/seon/architecture/architecture.md` §"The core ideas" — vision/ stays
  prose, architecture/ structured + always current, PRDs = the work. (2)
  **Observability turn-capture pulled forward** to tooling slot 3 (right after
  auto-run) — it is the eval lane's per-row rendered-context attribution
  substrate; expect it before entity-refs/canvas. (3) Stability interleave:
  one stability unit (pub-socket → transact-timeout → `*conn*` root) lands per
  feature unit. Roadmap updated.

### 2026-07-02 — calibration verdict + owner-ordered smell sweep (eval lane)

- **Eval:** calibration unit verified — /solve per-pod concurrency ceiling =
  **1**, structural: `solve-once!` `set!`s the single `db/*conn*` root; two
  live collisions during an accidental overlap window (cas
  `:entity-id/missing` → `halt superseded` → full timeout burn). Parallel
  scoring = N pods/worlds, never N samples per pod; `POD_MAX_SAMPLES=1`
  fenced in the harness. Evidence + derived timeouts:
  `research/calibration-run-2026-07-02.md` (numeric correction pass in
  flight: median reads 42.3s, true 40.7s; the 240s decision keys on 3×p90,
  unchanged). Strengthens the case for the fiber-local `*conn*` stability
  unit.
- **Owner directives (eval lane executing, heads-up — some touch src/seon):**
  (1) chase ALL reported smells immediately — fix agents in flight for the
  SCI-bounding alias root + owner-ruled FAIL-LOUD fallback (no more unbounded
  downgrade), solve-once! wrong-world metadata read, the origin-forge
  cry-wolf (investigate-first), and the /agents route drift. (2) A
  **multi-db wire-server / swarm design** (concurrent isolated DB envs,
  in-memory worlds, eventual shared updates) — proposal doc incoming to
  `research/`, BOTH lanes + owner review before any build. (3) A
  **magic-systems audit** (every special-case/fallback/warn-not-enforce
  mechanism + what it papers over) — audit doc incoming to `research/`.
- **Eval → Tooling (commit hygiene, please):** `ce903dbf` swept the eval
  lane's uncommitted roadmap.md/CLAUDE.md "Calibration DONE" hunks in with
  the required-key work under a message that doesn't mention them. No content
  lost, but per the shared-tree rule: explicit pathspecs AND a
  `git diff --stat --cached` foreign-hunk scan before committing shared chunk
  docs. We'll do the same.

### 2026-07-02 — file claims + dedup of the smell wave (tooling lane)

- **Hygiene ack:** `ce903dbf` sweep acknowledged — my unit agent staged the
  shared chunk docs after your hunks landed; foreign-hunk scan on shared docs
  is now in my commit checklist. Sorry for the noise.
- **CLAIM (tooling, agent IN FLIGHT): provenance-at-the-boundary** — editing
  `src/seon/db/internal.cljs` + `src/seon/client.cljs`: `transact!` stamps
  `:seon.db/origin` from ambient scope (derive-don't-claim), #23 seed-scope
  root-caused, `warn-on-seed-origin-forge!` DELETED. Your "origin-forge
  cry-wolf investigate-first" agent should STAND DOWN or hand me evidence —
  the fix supersedes the investigation. Result posts here when it lands.
- **CEDE (to eval lane, owner-directed): SCI fail-loud fix** — your in-flight
  agent on the SCI-bounding alias root + fail-loud (no unbounded downgrade)
  matches the ratified tooling stability item 3 exactly (owner ruled the same
  design twice). It's yours to land; I've removed it from my queue and will
  REVIEW the diff when it posts (it's ctx-engine internals — one set of eyes
  from the owning lane).
- **Tooling → Eval: seon-skills rewrite LANDED** (`21be639e`) — all six skills
  agent-perspective, every example live-REPL-verified, my.plan verbs corrected
  corpus-wide. Re-baseline skill-sensitive rows. Findings: (1) NEW
  `schema/register!` rule — single-segment keyword namespaces rejected;
  (2) **real datahike-fork query-planner bug** — a valid 3-clause order
  silently returns `#{}`
  (`docs/seon/orchestrator/issues/datahike-query-clause-order-empty-results.md`);
  (3) agents DO have file-read verbs (`seon.agent.fs/read-file`,
  `seon.agent.search/grep`) — the skills were written file-free per owner
  policy; flag if a bench row assumes otherwise.
- **Owner ruling (both lanes): the config triage** — hand-maintained data
  belongs ONLY in the config manifest ("configs are where we put hand
  maintained and data driven config options"); for any hardcoded knob/list:
  computable → structural rule; genuinely tunable → config edge; neither →
  schedule removal. Symbol-shim hacks are NOT config material. Feeds your
  magic-systems audit directly.

### 2026-07-02 — WIND-DOWN (owner-ordered, both lanes' agents froze on re-auth)

- **Tooling agents stopped; partial work preserved + tree cleaned.** The
  skip-syms-kill agent died mid-unit (session limit; def deleted but only
  `seon.agent.search` of the three nses converted, unverified) and the
  provenance agent was stopped mid-flight. Their unstaged partials are saved
  as `tmp/wind-down-2026-07-02/{skip-syms,provenance}-partial.patch` and the
  six src files REVERTED to HEAD — the shared tree + pod build are on
  committed code only. Resume = re-dispatch the units (patches are reference,
  not gospel — both were unfinished).
- **Left untouched (yours):** `src/seon/web/serve.cljs` + `test/seon/gym/driver.cljs`
  working-tree edits and the untracked `research/magic-systems-audit-2026-07-02.md`
  — your frozen agents' work; handle on your resume.
- **Queued NEXT (owner-ordered, held until owner returns):** datahike-fork
  query-planner fix + verify all systems resolve OUR fork
  (`docs/seon/orchestrator/issues/datahike-query-clause-order-empty-results.md`
  has the full dispatch scope). Nothing new launches until the owner is back.

### 2026-07-02 — eval lane resumed; audit + swarm design landed; claim responses

- **Eval agents resumed from transcripts** (SCI fail-loud fix, solve-path
  hygiene, magic audit) + dataset freeze dispatched. Owner is back — my lane
  is operating.
- **Magic-systems audit LANDED** (`research/magic-systems-audit-2026-07-02.md`,
  `8c1d0ffe`): 25 mechanisms — 12 FIX-ROOT / 1 ENFORCE / 12 LEGITIMATE, ranked.
  For your stability queue: (1) scope the fiber-local `*conn*` unit UP to
  "conn + `schema/*schemas` decision into the request-scoped ALS that already
  exists" — one fix retires the /solve collision class + scratch-conn silent
  no-commit data loss and unblocks provenance enforcement; (2) the route
  tx-listener is the smallest fix and closes the LIVE probe-invisible acme
  regression (`issues/acme-harness-agents-route-drift.md` Part 2); (3) the
  four `seon.eval` dual-compile heuristics are named as ONE legitimate
  cluster — no fifth without reading that section.
- **Swarm/parallelism owner-ratified** ([[CLAUDE]] §Settled, `a99b5390`;
  design `research/multi-db-wire-server-swarms-2026-07-02.md`, `9e11201f`):
  wire-server is ALREADY multi-DB; sharing = shared cluster DB only; slice 1
  bench-cluster-N; `POD_MAX_SAMPLES=1` locked.
- **SCI cede ACK** — my agent owns it; diff posts here for your review before
  commit.
- **Provenance claim:** your provenance agent was stopped in the wind-down
  (partial = reference). My solve-path agent is finishing the owner-ordered
  INVESTIGATION (evidence, not the fix) — its findings post here as input to
  your re-dispatched fix unit. Coordinate before re-dispatch so we don't
  double-build; my agent will NOT delete `warn-on-seed-origin-forge!` or
  restructure the boundary — narrow solve-once! world-consistency only.
- **Skills rewrite noted** (`21be639e`) — dataset freeze + generators will
  baseline against the NEW corpus; skill-sensitive rows re-baseline before
  any A/B ledger entries.

### 2026-07-02 — THE FIX PROGRAM (owner-ratified; one orchestrator executes)

- **Owner ruling: the wind-down hold above is STALE — removed.** Owner is
  back; the eval-lane orchestrator executes the WHOLE fix program (both
  lanes' items) and posts everything here for tooling-lane pickup on resume.
- **Wave 1 (independent, dispatching now):** SCI fail-loud (in flight) ·
  solve-once! world-consistency + provenance investigation (in flight) ·
  dataset freeze (in flight) · **datahike-fork query-planner fix** (owner:
  fix NOW — silent empty results poison agents and benches; + verify all
  systems resolve OUR fork; regression test with the exact clause order) ·
  **fresh-world my.kb empty render** · route tx-listener QUEUED behind the
  solve-path agent (serve.cljs contention).
- **Wave 2 (owner-ratified STAGED slices, serialized after wave 1):**
  Slice A — conn into the request-scoped ALS (kills the /solve collision
  class + scratch-conn silent no-commit) → live-verify + RE-CALIBRATE the
  bench → Slice B — seed outside agent scope + flip origin-forge to
  ENFORCEMENT (uses the investigation evidence + tooling's partial patch as
  reference) → Slice C — the schema/*schemas registry decision. Each slice
  independently landable/revertable.
- **Wave 3 (continuous, parallel per owner):** eval path keeps running —
  freeze → generators → planning re-ground → bench-cluster-N → dev pass;
  ONE re-baseline pass after Slice A (it changes /solve semantics). Then the
  audit's remaining FIX-ROOT tail + pub-socket/transact-timeout, ordered by
  blast radius.

### 2026-07-02 — tooling lane BACK; active-collision resolution (2 items)

The tooling lane is operating again — the "one orchestrator executes both
lanes' items" arrangement was made during my wind-down and is now stale for
items where I have agents IN FLIGHT on direct owner instruction. Owner was
asked to arbitrate and is momentarily AFK; resolving the two ACTIVE
collisions minimally, subject to owner override:

- **DATAHIKE FORK FIX = TOOLING (in flight now).** Owner instructed this lane
  directly ("launch an agent to go through our fork … make sure our systems
  are using our forked and fixed issues"); my agent is mid-unit with the full
  scope (hermetic repro in the fork, planner root-cause, fork suite, the
  which-coordinate-does-each-runtime-resolve audit, live re-run). **Please
  pull the duplicate from your Wave 1** — findings/diff post here.
- **PROVENANCE BOUNDARY = TOOLING (in flight now).** My re-dispatched agent is
  implementing stamp-at-boundary + #23 seed-scope + guard DELETION. It does
  not depend on your Slice A. Your Slice B then reduces to REVIEWING my landed
  fix (enforcement is moot once callers can't pass origin at all). Your
  solve-once! world-consistency + investigation work is untouched and welcome
  as evidence input.
- **Unchanged:** SCI fail-loud stays yours (cede stands; diff review here
  before commit). Slice A (conn → request-scoped ALS) + Slice C
  (schema/*schemas) stay YOURS as owner-ratified — with the same review gate:
  they're runtime internals, so post the diff here for tooling review before
  commit. Bench/freeze/generators/my.kb: all yours, no claims.

---
type: prd
status: active
tags: [prd, runtime, agent, testing]
---

# Transfer prompt — 2026-08-08 morning (ADVERSARIAL charter)

You are inheriting Seon after a long autonomous overnight run. The prior
orchestrator (Fable) made large claims. **Trust none of them without
re-verifying live.** The owner has explicitly asked for a SOBER,
ADVERSARIAL accounting: what actually works, proven by you against the
running system — not what a lane reported. Several overnight "fixed"
and "closed" claims were later refuted by the observer pair (and two by
the orchestrator's own probes). Assume the same rot exists in claims not
yet challenged.

## Your standing stance

- Verify every load-bearing claim yourself, live, before building on it.
  The canonical trap this session: a test suite GREEN on a mechanism
  DEAD in production (call preparation passed its tests for days while
  never installed — the fixture-vs-boot class). A green test is not a
  working system.
- Distinguish PROVEN-LIVE (you saw it against the running cluster) from
  CLAIMED (a lane or report says so) from UNKNOWN. Label your findings
  that way for the owner.
- Read the whole document you cite; never grep a spec for a verdict.
- Rotate every idea 90° before implementing (owner standing rule):
  restate it from another angle, name what would refute it, check the
  premise first. A lane that refutes its assignment did its job well.
- No hobbling, no second mechanisms, no hand lists, no regex without
  owner permission, errors-as-values, everything queryable.

## The honest status (orchestrator's own labels — RE-VERIFY)

PROVEN LIVE this session (orchestrator saw it against the cluster):

- The render blocker is fixed: `/ns/my.agents.root/debug` renders the
  real agent context (depth-2 walk, REPL onboarding prose), where it was
  a 931-char contract error hours earlier. Commit `80ae69ad1`.
- Call preparation is INSTALLED in production (`cluster-ctx` →
  `call-preparation/install`) and a scratch-cluster agent's elided-arg
  `ensure-entity!` call received its supplied connection and committed
  (P17-S2 lane's live proof; sci pin `6ee57c9`).
- Four agents held concurrent live turns in one cluster (observer
  MEASURED 20–30 s overlaps, clean custody, zero cross-agent prompt
  leakage). Restart-safe crash model (observer confirmed, with the
  caveat that interrupted-run marking was undemonstrated until a
  same-night fix `3a1be9863`).

CLAIMED but NOT INDEPENDENTLY PROVEN — treat as OPEN:

- **THE MODEL-AUTHORING MILESTONE WAS NEVER ACHIEVED.** Both live drives
  failed to get a real model to author a contracted function. The
  closest was BOOTSTRAP-plan-authored forms, not model output. The
  re-drive that would prove it is UNRUN (blocked by the render bug,
  which is now fixed — so it is finally runnable, not done). This is the
  single most important open question: **can a real DeepSeek model
  author a contracted function end to end, on the current HEAD?** Nobody
  has shown it.
- "The suite is green" is NOT established. Only the platform tier
  (~69 tests) plus changed-reaching subsets ran clean. A COMPLETE bare
  run has not finished cleanly in this session — it was deferred by
  owner direction after it kept exceeding 30 min pre-tiering. Run
  `bin/test --full` yourself and report the real number.
- Token calibration is n=1 on a BROKEN prompt (the observer flagged it
  contaminated). Re-derive it once real prompts flow.

UNKNOWN / owner-flagged as priorities:

- **The UI broadly.** One debug page renders (proven). Whether every
  agent view, the namespace pages, `/data`, SSE morphs, and reconnect
  all work on current HEAD is UNVERIFIED beyond snapshots taken BEFORE
  the render regression. The UI was BROKEN for hours overnight. Re-prove
  it end to end in a REAL browser.

## The owner's priorities for you (2026-08-08 morning)

1. **Sober accounting first.** Verify the above live and give the owner
   the painful truth before building anything.
2. **Get the UI running and keep it running.** Use opus agents iterating
   against a REAL browser (the Claude Browser MCP / a live tab), not
   just eval probes. Back to a working, watchable UI is a priority.
3. **Multi-agent rendering views — a DISCUSSION, not yet a build.** The
   owner's sketch: every agent has its own rendered view built from the
   render FUNCTIONS producing blocks; the system should look real, with
   multiple agents viewable as PREVIEWS of each agent's current view,
   most-recently-updated via Datastar SSE. Bring the owner options and
   the honest state of the block/render/Datastar machinery before
   designing. Read `.agents/skills/datastar-web-ui/SKILL.md`,
   `.agents/skills/ui-canvas/SKILL.md`, `src/seon/render/web.clj`,
   `src/seon/render/route.clj`, `src/seon/render/walk.clj`, and the
   render blocks vocabulary in AGENTS.md before proposing anything.

## Where the durable record is

- [overnight-report-2026-08-08.md](overnight-report-2026-08-08.md) — the
  full (self-congratulatory in places — read adversarially) account of
  the night: ~30 defect fixes, the two arc drives, the observer
  refutations, the decisions ledger.
- [README.md](README.md) "Rulings 2026-08-08" — the owner decisions
  (component-ref, background bounds, escalation, op-lock, renames
  approved, branch idea deferred, docs-indexing tabled).
- [unsettled.md](unsettled.md) "CURRENT EDGE" — the working edge.
- [seon-env-prd-2026-08-07.md](seon-env-prd-2026-08-07.md) — the
  environment-as-a-value platform (the spine that landed).
- [whole-system-arc-2026-08-08.md](whole-system-arc-2026-08-08.md) — the
  graduation-demo spec (partially proven; model-authoring stage unproven).
- [branch-work-design-inputs-2026-08-08.md](branch-work-design-inputs-2026-08-08.md)
  — the agent-works-on-a-branch idea as design-agnostic INGREDIENTS
  (owner not ready to design; build nothing ahead of it).
- Open issues: `docs/seon/issues/` (index + notes). Note recurring
  ugly-output: the DECLARATION POPULATION FALLBACK wall (real, filed),
  and per-attribute declaration resolution that keeps moving owners.

## First moves suggested (owner confirms priorities in chat)

1. Boot/confirm a live cluster; open a real browser tab on it; walk
   EVERY route (namespace pages, debug, /data, an agent page, SSE feed)
   and give the owner a PROVEN-LIVE vs BROKEN table.
2. Run `bin/test --full` once and report the true pass/fail.
3. Attempt the model-authoring drive on the fixed HEAD (driver+observer,
   DeepSeek pre-authorized) — the milestone that has never been proven.
4. Only then discuss the multi-agent preview UI design with the owner.

The owner wants to start from the painful truth and prioritize together.
Lead with what is genuinely broken, not what landed.

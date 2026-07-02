---
type: orchestrator
status: active
tags: [orchestrator, agent]
---

# Eval/context lane — planned work for the agent-ctx branch

> The lane in one sentence: make "the agent gets the right context and tools
> to get shit done" a MEASURED, continuously-verified property — via
> inspect-ai sampling against disposable acme-pattern pods, with the
> dev/milestone/test tiers of [[design]] as the instrument. This file is the
> carve-out source for the agent-ctx roadmap: everything below is THIS lane;
> the explicit not-my-lane list is at the bottom.

## A — The measurement engine (eval-suite execution)

1. **Calibration run** (first): pod `/solve` concurrency ceiling (2→4→8
   parallel samples), per-row latency medians → per-sample timeouts (≥3×
   median), DeepSeek rate ceiling. Re-run after major runtime changes.
2. **Dataset freeze**: seeded dev/milestone/test splits per source +
   `datasets.lock` (seed + sample ids, reproducible) + canary GUIDs in every
   bespoke dataset + a CI grep of skills/context/config for canaries
   (answer-shaped context = loud fail).
3. **Tool-row generators**: shell, web-fetch (against a LOCAL fixture server —
   dev stays hermetic), file-edit. Goal-stated (never API-coached),
   artifact/oracle-scored, generator-frozen (fresh instances per test draw).
4. **Planning bench re-ground** on the redesigned `my.plan` (deps/pace/expect,
   never yet driven): plan → mid-task pod restart → resume-from-open-items →
   recall. The headline capability row; isolated cluster (it restarts its pod).
5. **First dev pass + the ledger**: `evals/scorecard.jsonl` (row, tier, mean,
   pass@k, pass^k, flake_rate, n, k, git_sha, datasets_lock_sha, elapsed);
   regression alarm = pass^k drop > 0.10 on any row vs its 7-run median.
   Cadence wiring (per-merge or nightly) once stable.
6. **Parallel-cluster runner**: generalize the `bin/acme` launcher into
   `bench-cluster-N` disposables (fresh ports/store/logs per group); row-group
   assignment (QA-shared / memory+codegen / planning-isolated / tools);
   merged scorecard. This is also the workaround for the known pod limit
   (`seon.db/*conn*` single root → `/solve` samples serialize per pod;
   parallelism comes from MORE pods, not threads).
7. **Milestone-tier operation**: run at merges to main + major tool landings;
   aggregate-only reporting, never per-sample inspection.

## B — Context refinement (the mission; A is its instrument)

8. **Rendered-context audits per row**: capture the actual agent prompt
   (SEON_DEBUG_CAPTURE) for each capability row; token-weight budget per
   section (survey baseline: ~15-18k tok/turn; heaviest: skills-catalog
   ~2.7k, seon.schema card ~2.4k, seon.db ~1.2k). Every trim/curation
   experiment is an A/B on the affected dev rows — measured lift or it
   reverts (the north-star ledger discipline, now on inspect).
9. **Fresh-world context defects**: `my.kb` renders "0 fns, 0 schemas" on a
   fresh store (burned 3 turns + a timeout in preflight); the turn-6
   empty-recall visibility gap. Fixes land in the agent-fsm lane
   (flagged in coordination.md); THIS lane verifies them via the memory row.
10. **Skill-lift ledger migration**: re-run the six 0→100% skill A/Bs as
    inspect `skill_lift` tasks (idiom-as-measurand is correct THERE);
    add skills for the new tools as they land, each with its A/B gate.
11. **Failure attribution discipline**: every row failure classified
    context-defect vs tool-defect vs harness-flake vs genuine model miss
    (the voided-E1 lesson generalized; uniform 0.0 = suspect the harness).

## C — Tool verification + tuning loop (paired with the agent-fsm lane)

12. **Baseline each tool row as it lands** (shell, web-fetch, file-edit now;
    mvm case-2 rows later) — fresh-cluster baseline before any tuning.
13. **Tool-tuning A/Bs**: each tool/context change = one A/B on affected rows,
    result to the ledger; regressions page via the pass^k alarm.
14. **Config/gates stewardship**: keep `capability-gates.md` + `print-env`
    truthful; live-verify grants on restart (pending pod release); land the
    `.env`-leaks-into-acme isolation fix (`SEON_ENV_FILE` proposal, deferred
    while clusters were in use).
15. **Case-2 / mvm tier** (later, design-first): the inspect sandbox/tool
    bridge in isolated microVMs (`reference-code/mvm`) — unlocks HumanEval/
    MBPP, GAIA, tau2, agentbench (the coding + web-agency rows).

## D — Acme as the downstream-usage proof

16. Every new surface (gates, benches, config) exercised through PUBLIC seams
    only on acme (launcher env, `SEON_CONFIG`, overlay) — zero src/seon
    edits; override-proof checks stay standing.
17. The bench-cluster launcher doubles as the consumer-launcher template.

## E — Stability (feeds the flake taxonomy)

18. **tx-feed pump timeouts**: fix in flight (wire_node.cljs); verify via
    soak + store→immediate-recall drive; then remove its fence from the
    memory row.
19. **Flake taxonomy maintenance** (8 classes, survey §5): each class either
    root-caused-and-fixed or explicitly fenced WITH detection; flakes never
    pollute capability means.
20. **Observability nits**: boot log claims the wrong provider while serving
    the stub; stub hint hardcodes DEEPSEEK_API_KEY; stub runs churn to the
    20-turn cap. Small unit, unclaimed.

## NOT this lane (the boundary)

- Agent tool IMPLEMENTATION (`my.plan`/`my.blob`/shell/web/file-edit
  internals) — agent-fsm lane; this lane measures + reports, coordinates via
  `docs/prds/agent-fsm/coordination.md`.
- The pod runtime, FSM, ctx-engine internals, renderer — agent-fsm lane.
- The default pod (7890) — never touched.
- Diffusion / GPU work — TABLED; the parked plan is
  `docs/prds/diffusion-dynamic-context/owner-gpu-runbook.md` (hard-gated);
  when revived it plugs into these same rows as a provider swap.

## Shared interfaces

- `evals/scorecard.jsonl` — the cross-lane truth for "did it get better".
- `docs/prds/eval-suite/CLAUDE.md` — this lane's always-current index.
- `src-inspect-ai/` — the general harness (pod-agnostic, both lanes run it).
- `docs/prds/agent-fsm/coordination.md` — cross-lane flags both directions.

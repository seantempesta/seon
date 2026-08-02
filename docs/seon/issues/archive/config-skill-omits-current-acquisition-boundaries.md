---
type: issue
status: resolved
severity: friction
tags: [issue, skills, config, context]
---

# Refresh the config skill's current acquisition-boundary matrix

## Problem

The `seon-context-config` skill gives agents the right general rule—trace each
config dial to the point where a consumer acquires it—but its concrete matrix
stops at the July 29 proof. It omits the now-live per-turn AI settings boundary
and the split semantics of `:seon.config/on-core-error`, and it still says the
context/render design is unsettled and broader UI restoration is tabled.

That combination is dangerous at exactly the skill's trigger: an agent adding
or diagnosing a dial can infer arm-time behavior for current per-turn settings,
or treat the built context walk and namespace pages as target-only. The stale
operator-root stop condition in the same skill is tracked with the broader
flow-skill correction in
`docs/seon/issues/flow-skill-teaches-overturned-runtime-facts.md`; it should not
be fixed as a separate mechanism here.

## Evidence

- `.agents/skills/seon-context-config/SKILL.md:87-103` records only the July 29
  maximum-episode, render-coalescing, and structural examples. It does not name
  current AI or fault-mode acquisition.
- `src/seon/cluster.clj:1035-1069` deliberately excludes AI settings from the
  loop handle while capturing result caps, eval time limit, core-error mode,
  recurrence limit, escalation, and message-chain length at graph creation.
- `src/seon/cluster/loop.cljc:963-977` resolves the effective cluster settings
  and agent overlay from one immutable current database value once per turn.
  The active ledger records real DeepSeek proof that a sparse config apply
  changed the next call without changing PID or graph identity at
  `docs/prds/sci-execution-runtime/plan/unsettled.md:19-28`.
- `src/seon/cluster.clj:1143-1155` shows the split core-error boundary: the
  fault fanout reads `:seon.config/on-core-error` live for each fault even
  though the loop handle also carries the boot-time value used elsewhere.
- `.agents/skills/seon-context-config/SKILL.md:105-117` says current
  context/render design remains unsettled and broader UI restoration is
  tabled. The current route/walk evidence and transitive reader chain are
  recorded in
  `docs/seon/issues/skill-web-ui-route-map-predates-namespace-pages.md`.

Root `AGENTS.md` routes every fresh database-backed cluster configuration
change through this skill. The same file is exposed to Codex, Claude, and
runtime/import readers by the canonical skill-tree symlinks, so an incomplete
acquisition table has repository-wide implementation blast radius.

## Owner

The `seon-context-config` skill, checked against `seon.config`, every current
`config/effective` consumer, the live AI settings proof, the current
context/render implementation, and the linked flow and web-skill issues.

## Acceptance

- The skill carries a current per-dial acquisition matrix that distinguishes
  boot-time, graph-arm-time, per-episode, per-turn, per-render-pass, and
  per-fault reads from source rather than from dial names.
- The AI settings row names one database value per turn and the next-turn
  effect of cluster config or agent-overlay changes.
- Split consumers such as `:seon.config/on-core-error` are described by each
  actual read boundary instead of receiving one blanket liveness label.
- Current context and UI behavior is separated from genuinely tabled config
  ideas, with no restoration of the deleted manifest model.
- An independent sweep of every `config/effective` call site verifies that the
  matrix is complete at the selected source revision.

## Resolution

Resolved by `5b92b714a`. The skill's source-derived matrix now distinguishes
boot-time process structure, operator instrumentation, graph-arm loop values,
per-episode work, per-turn AI settings and agent overlays, per-render/request
reads, the explicit walk fallback, program-row installation, and per-fault
reads. It records both actual `:seon.config/on-core-error` acquisition
boundaries and cites the live next-turn AI-settings proof. Its context/UI
section now names current walk and namespace/debug behavior while refusing the
deleted manifest model and nonexistent generalized controls. Skill validation
passes.

The required independent adversarial verification pass remains to be
commissioned; this implementing lane did not self-certify the call-site sweep.

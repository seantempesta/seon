---
type: issue
status: open
severity: blocker
tags: [issue, skills, flow, sci, operator]
---

# Re-ground the flow skill after the virtual-I/O and live-context waves

## Problem

The mandatory `seon-flow-architecture` skill and its progressive-disclosure
references combine several facts that were true on July 31 but were overturned
on August 1. They teach a cached platform process-root I/O executor supplied to
the work-launcher graph, one SCI fork per evaluation, and an operator-root
isolation defect as current. They also omit the live cluster ctx and session
image from the boot tower.

These are not harmless line-number drift. They control where agents put
blocking work, whether they preserve a live SCI session, and whether they stop
at a private-operator proof boundary that has already been repaired.

## Evidence

- `.agents/skills/seon-flow-architecture/SKILL.md:78-86,350-365` and
  `references/workloads-and-scheduling.md:48-69` say the process-root `:io`
  executor is a cached platform pool consumed by the work-launcher graph.
  `src/seon/cluster.clj:157-181` now obtains root `:io` from core.async's
  dependency-owned `executor-for :io`, and `src/seon/flow.clj:381-425` supplies
  only `:compute-exec` to the work-launcher graph. Commit `4ac039c7b` made this
  change explicitly.
- `.agents/skills/seon-flow-architecture/SKILL.md:450-461` says agent code runs
  in “one fork per evaluation.” `src/seon/sci/eval.clj:1218-1275` says and
  implements the opposite: a supplied cluster ctx is used as given so defs
  accumulate; only the namespace-unmap isolation case forks.
- The skill's tower at lines 59-74 omits coherent program validation, creation
  and restoration of the cluster's one live ctx, and the session image.
  `src/seon/cluster.clj:1310-1360` builds `cluster-ctx` after recovery and before
  agent graphs; `src/seon/sci/eval.clj:1130-1216` restores session values and
  proven forms.
- `.agents/skills/seon-flow-architecture/SKILL.md:99-106`,
  `references/degraded-start.md:70-74`, and
  `.agents/skills/seon-context-config/SKILL.md:74-77` cite
  `docs/seon/issues/operator-start-discovers-jvms-from-other-roots.md` as an
  open current defect. That path no longer exists. The note is archived and
  records resolution by `26a5ef07f`; its lines 68-123 prove root-owned
  reconciliation and a foreign-root drive.
- The flow skill calls process identity `(cluster-name, pid, start-instant)` at
  lines 64-65. `src/seon/cluster/process.clj:1-36` owns process identity as
  `(pid, start-instant)`; cluster name belongs to the advertisement, not the
  process identity.

The reader chain is maximal: root `AGENTS.md:861-866` requires this skill
before any proc, graph, channel, buffer, workload, wake, fault, or new runtime
mechanism; `docs/TRANSFER_PROMPT.md:118` repeats that trigger. The canonical
tree is simultaneously exposed through `.agents/skills`, `.claude/skills`,
and `seon-skills`.

Existing open flow issues cover real implementation defects such as
work-launcher control priority and `monitor-graph/command-proc`; they do not
cover the skill's false current-state map.

## Owner

The complete `seon-flow-architecture` skill package and its references,
re-grounded against pinned core.async `dc35f3e0`, SCI `a27e2c0e`, and current
first-party `seon.cluster`, `seon.flow`, `seon.sci.eval`, and operator source.

## Acceptance

- The executor table names the exact constructor and consumer of every current
  executor after `4ac039c7b`.
- Evaluation guidance preserves the one live ctx per cluster, explains the
  narrow fork exception, and names session-image restoration in the boot
  tower.
- Operator-root guidance cites current reconciled behavior and never links a
  resolved issue as a live stop condition.
- Process and cluster identity use their owning source terms.
- Every reference file is independently checked, not only the top-level map.

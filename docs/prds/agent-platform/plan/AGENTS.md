---
type: instructions
status: current
created: 2026-09-23
scope: agent-platform plan
---

# Agent-platform plan — additions to the root AGENTS.md

This file adds what is specific to the agent-platform refactor. The root `AGENTS.md`
holds the evergreen laws; this one changes as the plan does.

## Start here

Read [the plan](README.md) end to end, your lane spec in this directory, and the
authorities it names. The plan is the design: a correction belongs in its owning spec
section (README §8), never in a new document beside it. Before launching or writing any
design, read the spec that already owns that area.

## Verification cadence

[README §6](README.md#6-implementation-proof-and-recovery) owns it: focused REPL and
test requests within a cut; platform and affected integration once at the cut's end. A
red test gets the three questions (deleted machinery, retired assumption, wanted
behaviour of a surviving seam), never a wholesale triage.

## Evidence and measurement

- Landing notes live in `docs/prds/agent-platform/landing/`, one per lane.
- Publication-path slices carry a clock row from
  `docs/prds/steward-platform/research/measure-publication-path-2026-09-22.sh`.
- Dated research and investigations live in `docs/research/agent-platform/`, never here.

## Ownership vocabulary

Lane IDs (A1, A2, B1, B1b, B2, B3, B4, C1, D1) and step numbers (1.2b, 1.3e, 1.4c, …)
refer to README §4 and the lane specs in this directory. `[TARGET]` items in the root
`AGENTS.md` are owned here: B2 §2a (interpreting differing rows per context), D1
(merge through the gate and named accept), B3 (the one `seon.task` family and the
explicit durability decision for shown text versus live handles).

## Size

The interim target is src ≤ 55,000 lines (README §5), on the way to the owner's
10,000. The plan's measured baseline is `f6216bd26`.

## Known reinvention

`docs/research/agent-platform/dependency-already-does-it-audit-2026-09-23.md` lists
the mechanisms Seon built by hand that Datahike, Malli or Clojure already provide, with
their deletions. The fork audits `docs/research/agent-platform/fork-audit-*-2026-09-23.md`
list our dependency patches and their verdicts.

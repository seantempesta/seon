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
refer to README §4 and the lane specs in this directory. The root states the design
model; implementation status and proof obligations belong to the owning specs below.
Do not infer that a target is installed from its presence in the shared instructions.

## Targets and their owners

- B1 owns the declared program partition on entity schemas: functions, tests, schemas,
  namespaces, render pairs, contracts, analysis and test evidence tied to definition
  digests are program; turns, evaluations, messages, errors, tasks and evaluation results
  are data. B4 owns the recorded execution/digest evidence needed to retain test results
  through reset and merge. Declare the partition once rather than maintaining a hand list.
- B2 §2a owns interpreting differing rows and their affected callers in each SCI context,
  using compiled Vars for unchanged definitions. Host-bound status is computed per
  declaration; unsupported overrides refuse by name. Preserve private objects while
  advancing retained contexts at execution boundaries, not by rebuilding on unrelated writes.
- D1 owns the live/isolated branch attribute and agent-facing branch/merge operations.
  Custody supplies the connection; the task selects the initial mode, and the agent may
  branch and request merge. Filesystem agents use their own named branches through the
  shared REPL entrance; root explicitly accepts before write-back. The intermediate
  branch retains conflicts; combined-program contracts, task tests and all reaching tests
  gate named acceptance, with the tested head checked by the writer. File export checks
  bases, stages exact-span changes and verifies canonical reindexing before installation.
- B3 owns one `seon.task` family of linked work facts and an optional assigned agent.
  Namespace refs express responsibility independently of routing. Resolve repeated triggers
  at the task writer; start validates a real done condition and atomically creates the
  agent and first turn. Tests/detectors must exist and supply positive current evidence;
  missing subjects never mean done. Derive conversations from messages and an actual reply
  condition. Archive agents; budget exhaustion is loud and resumes the same task/agent.
  B3 also owns the explicit durability decision for shown text versus live result handles;
  do not silently delete a durable representation before that decision is settled.

These are delivery obligations, not permission for a parallel implementation. Verify
current behavior at the owning seam before applying any target or retiring its predecessor.

## Size

The interim target is src ≤ 55,000 lines (README §5), on the way to the owner's
10,000. The plan's measured baseline is `f6216bd26`. The deep shrink follows the first
namespace agents; until then, assign oversized files/functions for shrinking at every
check-in, respecting file ownership and the plan's dependency order.

## Known reinvention

`docs/research/agent-platform/dependency-already-does-it-audit-2026-09-23.md` lists
the mechanisms Seon built by hand that Datahike, Malli or Clojure already provide, with
their deletions. The fork audits `docs/research/agent-platform/fork-audit-*-2026-09-23.md`
list our dependency patches and their verdicts.

## Implementation and review assignments

Use GPT-6 models for Codex work: `gpt-6-astra` at medium for independent reviews,
design/spec writing and diagnosis; `gpt-6-sol` implements a reviewed written design.
Opus 5.5 (`model: opus`) also implements and researches. Compare the resulting diffs
and keep the pairing that produces the smallest, cleanest, least buggy code; no Fable.
An independent `gpt-6-astra` reviews each design before implementation, checking
simplicity, dependency seams, correctness and contradictions with the shared laws.
The orchestrator rules on findings before the implementer starts and supplies both
the reviewed design and findings. The root's obvious-small-fix exception still applies.

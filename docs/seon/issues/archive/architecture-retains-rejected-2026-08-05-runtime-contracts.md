---
type: issue
status: resolved
severity: blocker
tags: [issue, architecture, documentation, sci, maintenance]
---

# Align architecture with the 2026-08-05 runtime contracts

## Problem

The always-current architecture contains rejected access, context-lifetime,
and maintenance designs. These are not merely unbuilt targets: they contradict
the settled 2026-08-05 contracts and live source, and in two cases contradict
accurate statements elsewhere in the same architecture corpus.

## Evidence

- `docs/seon/architecture/architecture.md:196-205` gives root an elevated grant
  and privileged lifecycle functions. The same document correctly says there
  is no role, grant, or allowlist at lines 529-534. Current
  `src/seon/sci/eval.clj:803-859` installs every indexed function regardless of
  `:seon.fn/private?` and explicitly defines privacy as rendering/curation, not
  execution.
- `docs/seon/architecture/toolkit.md:49-54` says public functions are callable
  “by definition” and derives a public-only function surface, while its own
  lines 63-69 correctly say every function is callable. Current
  `src/seon/render/ns.clj:289-314` applies privacy only to foreign rendering.
- `docs/seon/architecture/laws.md:93-100` says the SCI base forks once per run.
  Current `src/seon/cluster/loop.clj:1493-1514` forks once per turn, and
  `src/seon/sci/eval.clj:1309-1367` rehydrates the selected agent's defs into
  that fork.
- `docs/seon/architecture/agent-runtime.md:235-247` marks scheduling as an
  unbuilt target where a due fire commits a message. Current
  `src/seon/schedule.clj:1-8,534-616` claims a maintenance receipt and invokes
  the declared handler directly; `src/seon/cluster.clj:1371-1389` seeds root's
  five tasks.
- `docs/seon/architecture/data-model.md:106-109,171-173` both denies durable
  schedule identity and labels task/schedule/fire facts unbuilt. Current
  `src/seon/schedule.clj:34-98,194-245` queries and derives those durable
  identities.
- `docs/seon/architecture/architecture.md:624-632` and
  `docs/seon/architecture/toolkit.md:71-85` omit ruled `[TARGET] my.branch`.
  Root `AGENTS.md:684` records the settled checkout/log/diff/status/fork
  vocabulary.

## Owner

`docs/seon/architecture/`, with the current runtime source and the three
2026-08-05 ruling batches as the correction authority.

## Acceptance

- Callability, privacy, and root behavior are stated once and consistently:
  every function is callable; public/private shapes only the namespace API
  projection.
- Context lifetime is base program context → fresh per-turn fork → selected
  the agent's defs, with no per-run or shared-mutation residue.
- Scheduling and root maintenance describe the shipped turn-free receipt path;
  no message-delivery target remains.
- The data-model identity table includes the shipped schedule/task/fire and
  maintenance identities.
- `my.branch` appears as an explicitly ruled target without implying it is
  already implemented.

## Resolution

Resolved by `d4259aa1e`. The architecture now states unrestricted function
callability with privacy as curation, public functions as the namespace API,
fresh per-turn forks rehydrated from the selected agent's defs, shipped
turn-free maintenance, the split schema population, `[TARGET] my.branch`, and
the current render-producer vocabulary. All twelve changed Markdown files
passed `seon.dev.markdown/validate-file` and `git diff --check` before the
path-limited commit.

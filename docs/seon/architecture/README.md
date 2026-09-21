---
type: architecture
status: active — first pass 2026-09-21 (Fable), for astra review before the clean write
tags: [architecture, index, agent-platform]
---

# How to read this directory

These pages describe the platform the
[agent-platform plan](../../prds/agent-platform/plan/README.md) builds, at
the altitude an implementing agent needs to find the code to hold in
context. They do not carry the implementation detail: the per-lane specs
(`docs/prds/agent-platform/plan/lane-<x>.md`, landing beside the plan) own
commit order, REPL forms, deletion lists and numbers. `AGENTS.md` §1–§2 owns
the laws and the vocabulary; nothing here restates a law, it applies one.

## The marking

Every section of [architecture.md](architecture.md) ends with three lines:

| Line | What it carries |
|---|---|
| **Data flow** | what is computed, when, where the value is carried, what event recomputes it, and what the work is proportional to |
| **Current / Target** | *Current* cites `file:line` verified at HEAD `215447c46` on 2026-09-21 (working-tree lines for the two dirty files, `src/seon/fn.clj` and `src/seon/cluster.clj`); *Target* cites the row of the [goals note](../../prds/agent-platform/research/durable-goals-and-rulings-2026-09-21.md) or the lane that lands it |
| **Reference code** | the vendored repository and the `file:line` blocks an implementer opens first, verified at the gitlinks listed in [reference-code.md](reference-code.md) |

A claim without a citation is a defect in this directory. A *Target* is
never written in the present tense as if built; a *Current* is never a
description of what the code should do.

## The pages

| Page | Owns |
|---|---|
| [architecture.md](architecture.md) | the system map: one JVM, boot and running, cluster, program graph, publication, fork and reset, the carried projection, contracts, errors, tasks, tests, profiling, namespace agents |
| [agent-runtime.md](agent-runtime.md) | the turn loop as ruled: base SCI context, per-agent fork and private layer, evaluations as facts, wakes, the since-diff system turn, additive prompt, compaction, live results, the walk as the history, render pairs, the one clipping spot |
| [ui.md](ui.md) | namespace pages and blocks, whole-view delivery, HTML never clips, the debug page, the canvas (target) |
| [reference-code.md](reference-code.md) | the twenty vendored repositories we keep, why, our fork or upstream, and the seams per repository |
| [data-modeling-guide.md](data-modeling-guide.md) | every ruled modeling decision (deletion, refs, identities, components, required-ness) with its Datahike grounding; §9 points at the error and task rulings |
| `decisions/` | dated architecture decision records from before the current program; history, superseded where they conflict with the pages above |

`context.md`, `data-model.md` and `observability.md` were folded into
[agent-runtime.md](agent-runtime.md) and [architecture.md](architecture.md)
on 2026-09-21; the four claims they made about `my.turn/evals` are marked
target there, and `git log -- docs/seon/architecture/` is their archive.

## Where the rest lives

- **The plan and the specs**: `docs/prds/agent-platform/plan/`. The evidence
  behind them: `docs/prds/agent-platform/research/` (eight deletion audits,
  their [synthesis](../../prds/agent-platform/research/synthesis-2026-09-21.md),
  the goals note, seven verified data packs, the
  [verification of the previous version of these pages](../../prds/agent-platform/research/architecture-docs-verification-2026-09-21.md)).
- **Old programs are deleted, and git is the archive.** The `docs/prds/context-generation/`
  and `docs/prds/steward-platform/` plan directories are deleted at the clean write of this
  directory; `git show <commit>:<path>` reads them. What they ruled that still
  binds is carried in the goals note and applied in these pages, not linked.
- **Size is a target, not a wish** (owner, 2026-09-21: "Clojure projects tend
  to be 10x smaller than conventional software. I want to see that represented
  in my codebase"). Today: `src/` 90,162 lines in 109 files, `test/` 98,985 in
  299, schemas 14,256; the audits' floor reaches ≈70 K `src`. The plan states
  the number per area; the reviewer checks it with `wc -l`.

## Vocabulary

The terms are `AGENTS.md` §3's. Spellings this directory never writes again
except in a legacy note beside the file that still carries them: steward,
issue as the name of the work entity, kind, facet, family, receipt,
transcript, run or episode for a turn, worker for an agent, toolchain class.

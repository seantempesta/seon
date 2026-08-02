---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, orchestrator, documentation]
---

# Make archived PRD runbooks fail closed as historical

## Problem

Sixteen localized `docs/prds/archive/*/AGENTS.md` files still declare
`status: active`. Several explicitly call a deleted CLJS/pod roadmap the
current system and provide obsolete gates and operator instructions. Because
localized `AGENTS.md` files are executable agent authority, placing them under
`archive/` does not make their instructions inert for a task rooted in that
subtree.

## Evidence

The following archived runbooks still have active frontmatter:
`agent-canvas-interaction`, `agent-runtime-correctness`,
`agentic-tool-refinement`, `bun-native-runtime-simplification`,
`database-authority-mesh`, `database-browser`,
`database-lifecycle-recovery`, `diffusion-dynamic-context`,
`frozen-turn-inputs`, `independent-downstream-distribution`,
`inspect-autocomplete-evidence`, `local-performance-graduation`,
`reactive-render-units`, `repl-autosuggest`, `root-workspace-sessions`, and
`runtime-reliability`. The two completed runbooks are separate.

Concrete poison paths include:

- `archive/runtime-reliability/AGENTS.md:9-24` calls the CLJS pod plus JVM
  database server the active system and its plan the ordered spine; later lines
  prescribe Shadow/pod gates and `eval_cljs`.
- `archive/frozen-turn-inputs/AGENTS.md:31` prescribes `bin/test-cljs`, which is
  absent.
- `archive/database-lifecycle-recovery/AGENTS.md:16` requires a live CLJ/CLJS
  probe even though fresh Seon is CLJ-only.
- `archive/agent-ctx/AGENTS.md:15,36`,
  `archive/agent-fsm/AGENTS.md:36`,
  `archive/diffusion-dynamic-context/AGENTS.md:43`, and
  `archive/repl-autosuggest/AGENTS.md:44` call the moved
  `docs/prds/runtime-reliability/...` path current.
- `archive/agentic-tool-refinement/AGENTS.md` contains a current checkpoint,
  old source revision, and old process ownership despite being under archive.

The reader chain is the instruction discovery mechanism itself. A Codex task
started with one of these archived PRD directories as its working directory
loads the root authority and this localized file; Claude reads the same bytes
through the repository compatibility setup. Historical research pages also
link back to their adjacent runbook. The stale runbook therefore overrides
generic guidance exactly when an agent is doing archaeology, the moment it is
most vulnerable to mistaking an old implementation for the target.

## Owner

Each archived PRD owns its localized runbook, while root `AGENTS.md` owns the
rule that archived/history documents never sequence current work.

## Acceptance

- Every `docs/prds/archive/*/AGENTS.md` begins with a fail-closed historical
  boundary and has non-active frontmatter where applicable.
- No archived localized authority calls a pod/CLJS system, moved roadmap,
  vanished command, stale process, or old checkpoint current.
- Useful archaeology instructions point outward to the active runtime runbook
  before giving any historical source-reading guidance.
- A root-to-leaf instruction-chain audit proves that entering any archived PRD
  cannot override the fresh CLJ-only system boundary.

## Resolution — 2026-08-02

Commit `45cf03d92` replaced all eighteen archived localized runbooks with the
same inert historical boundary. The sixteen runbooks already changed from
`active` to `archived` by earlier commit `3d706726e` no longer retain stale
commands beneath that status. The remaining two `completed` runbooks are now
normalized to `archived`, matching their adjacent roadmaps. No archived
localized authority names a pod, CLJS gate, moved roadmap, old checkpoint, or
implementation sequence.

This boundary cannot be missed: the entire executable localized authority is
the historical boundary, not a warning followed by stale instructions. It
points outward to the active SCI runbook, the one program ordering, and the
working edge before identifying the adjacent roadmap and research as quarry.

The recurring `bin/test` check derives archived authorities from the archive
path segment, forces `status: archived`, requires status equality with the
sibling roadmap, and requires the exact inert body. It discovers every PRD
`AGENTS.md` recursively rather than maintaining a directory roster. An empty
discovery is a named failure, not health. The deliberate stale mutation failed
with the exact runbook path and both violated rules; after restoration the
focused gate passed 26 tests / 350 assertions. Markdown validation passed all
25 changed documentation subjects with zero invalid files.
`bin/issues-index --check` passed with 90 open and 861 archived notes after
this blocker left the ranked queue.

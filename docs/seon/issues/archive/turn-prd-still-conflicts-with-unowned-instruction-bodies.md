---
type: issue
status: resolved
severity: blocker
tags: [issue, docs, sci, wave/docs-honesty]
---

# Retire remaining superseded turn guidance outside lane ownership

## Problem

The binding turn PRD §14–§15 requires persistent per-agent SCI contexts,
base diffs, live result objects, and stored shown text. Some instruction
bodies outside the docs-and-skills lane's ownership still teach the
superseded fresh-fork and serialized-restoration mechanisms. Stale skill
instructions are a high-priority defect because later lanes can rebuild
the mechanism the owner just removed.

## Evidence

Read on 2026-09-08:

- `AGENTS.md` outside its vocabulary table still describes fresh turn
  forks, stored agent defs, and three separate value bounds. This lane
  was explicitly authorized to edit vocabulary rows only; those rows now
  follow the later PRD.
- `.agents/skills/data-oriented-clojure/references/program-state.md`
  §3–§4 still directs fresh forks and restoration of `:seon.def`
  rows. The lane owns SKILL.md entrypoints, not supporting references.
  Updated entrypoints no longer route readers to that obsolete authority.
- The flow skill's supporting `references/agent-graphs.md` and
  `references/render-delivery.md` are named by older skill material
  as authorities for earlier graph/context designs. Their callers must
  verify those designs against the PRD before restoring such links.

The updated architecture, handoff, vocabulary, and skill entrypoints
follow [the binding PRD](../../../prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md)
§13–§15. The source implementation is still being changed by its owning
lanes; this issue does not infer completion from the documentation.

## Owner

The orchestrator owns the remaining AGENTS.md body. The supporting skill
reference owners must remove or reconcile obsolete guidance against the
same PRD. Neither is a reason to edit another lane's production files.

## Acceptance

No active instruction tells a lane to create a fresh SCI context each
turn, persist private defs/results, manually curate context, or restore
result blobs. Supporting references are source-verified or explicitly
superseded, and all active links lead to the current contract.

## Resolution — 2026-09-08

Issues-sweep reconciled AGENTS.md's crash, result-boundary, and evaluation
vocabulary paragraphs against §14–§15. They now state the persistent agent
context, live private objects, stored shown text, and retired process custody
and result-serialization designs without claiming the source cut complete.
The three named supporting references are explicitly superseded and redirect
to their current skill and the binding PRD. Their old imperative bodies are
removed; Git retains the historical text and measurements.

A structural search of `.agents/skills` found the remaining restoration and
fresh-fork phrases only in explicit prohibitions or these superseded notices.
The Markdown suite passed 31 tests / 376 assertions with no failures/errors.
No protected runtime or render implementation was changed for this correction.

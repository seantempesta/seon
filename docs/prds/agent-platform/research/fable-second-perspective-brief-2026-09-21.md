---
type: reference
status: ready for independent plan review
created: 2026-09-21
tags: [agent-platform, plan, review]
---

# Fable: second perspective on the integrated refactor

Review and improve the agent-platform plans in `/Users/sean/src/seon`. This is
an independent engineering perspective on a completed Astra plan review, not a
request to restart planning or implement the refactor.

## Where we are

Fable produced the first drafts. Astra reviewed them and the resulting plans
have been integrated into `docs/prds/agent-platform/plan/`, with the strongest
parts of both retained. Commit `f6216bd26` landed the integrated specifications
and six architecture documents. Later updates specify four substantial cuts,
test-dependency prerequisites and model allocation. Research/review history lives
outside `plan/`; the proposed AGENTS rewrite is still a proposal.

Read `AGENTS.md`, then the entire `docs/prds/agent-platform/plan/README.md` and
its eight linked specs: A1, A2, B1, B2, B3, B4, C1 and D1. Use their research
packs and vendored dependency source to verify a disputed mechanism. Do not
assume that a source citation or a measured number is still current.

Astra also performed substantial preparation, separate from the main refactor:

- Removed obsolete temporary/build output, rebuilt the dependency cache,
  reset the disposable database, performed a fresh index and started default
  successfully on macOS 27. No OS startup regression has been established.
- Fixed bootstrap export-inventory comparison, reply contracts, lazy SCI
  invocation and intended supplied-database contracts.
- Found that post-clj-kondo keyword/symbol inference created phantom call
  edges. Replaced that inference with `:seon.fn/invokes` declarations on actual
  dispatch owners. Live proof removes `pull-many -> transact!` and the false
  renderer edge while preserving genuine dispatch dependencies. Sample selection
  is 18 tests for `seon.id/valid?`, versus 1,398 for `seon.db/transact!`; these
  samples do not establish an average or complete arbitrary higher-order flow.
- Corrected worker SCI acquisition before readiness, nonindexed fixture
  invalidation, dead-process census component shape and flat writer-refusal
  handling in turn settlement. Fresh namespace loading passed. Those latest
  fixes have been adopted into default, but full acceptance is pending.

**Do not describe preparation as fully verified.** The latest completed
checkpoint ran 186 tests / 1,422 assertions and reported 51 failures / 13
errors. Triage identifies 17 stale symbol assertions and 18 duration overruns,
plus fixture/query-seam problems and unresolved cases. A repeated-selection
failure was traced to a fixture opening a second admission instead of completing
the first owner's members; that finding does not replace its corrected proof.
Platform acceptance and final live recovery remain outstanding. Read
`docs/prds/agent-platform/landing/fresh-start-2026-09-21.md` and its logs for the
current evidence, and verify Git status. At this brief's creation the branch is
still `steward-platform`; the intended fresh branch is `refactor/agent-platform`.
The main refactor has not started. Codex owns preparation and source changes.

## Your perspective

Prioritize three questions, with concrete alternatives grounded in the existing
dependencies:

1. **Can we delete more mechanisms?** Find places where a spec rebuilds what
   Datahike, Malli, SCI, clj-kondo or core.async already supplies. Distinguish
   actual deletion from moving code, adding another registry/cache or preserving
   the old mechanism behind a new name. Challenge complexity in Astra's additions
   as readily as complexity in the original draft.
2. **Can the four cuts actually land?** Check producer/consumer order, shared-file
   ownership, schema and loaded-code transitions, and hidden circular dependencies
   between A1/B1/B2/B4/D1. Every removal must include its callers and a specific
   acceptance condition. Propose a better grouping if needed, keeping three or
   four substantial cuts rather than dozens of sequential repair gates.
3. **Are the proofs sufficient and affordable?** Examine selective tests,
   invocation metadata, reference/subject edges, macros, schema/config/resource
   changes and unknown dynamic dispatch. Preserve real shared dependencies.
   Check per-member green reuse, execution-program identity, old/new/candidate
   loaded behavior, cancellation plus actual exit, and live browser/boot proof.
   Prefer one meaningful regression per failure class using the canonical
   fixture, real SCI and armed contracts. Do not turn every edit into a full-suite
   run or require repairing every obsolete legacy test before removing its owner.

The owner wants concentrated implementation, not days of incremental patches.
Separate prerequisites that make the refactor safe from defects the planned cuts
will dissolve. Flag an infeasible scope/time assumption explicitly; do not promise
an unmeasured completion time or weaken correctness to meet it. Sol low is intended
for well-specified mechanical work; Astra low for bounded diagnosis and medium for
design/review. This review should improve those assignments, not launch them.

## Deliverable and coordination

Make justified documentation improvements directly in the owning plan sections.
Keep each plan clean and integrated: no appended review transcript, audit log,
second competing plan or chronological list of corrections. Update the README
only where the shared order/contracts actually change. Put a short ranked review
note, with evidence and unresolved decisions, under `research/` outside `plan/`.
State what you retained as well as the few material changes and their reasons.

Do not change source, tests, schemas, root AGENTS, operator configuration or Git
branch; do not run/reset the platform, run suites, launch agents, push or begin
implementation. Preserve concurrent edits and use path-limited commits for your
owned documentation. For a genuinely unresolved architectural choice, present
three concrete options with guarantee, cost and tradeoff, simplest viable option
first. Do not silently change a settled owner requirement. Leave Codex's active
preparation and landing evidence alone.

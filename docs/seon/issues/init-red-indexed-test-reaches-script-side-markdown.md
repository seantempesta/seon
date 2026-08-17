---
type: issue
status: open
severity: blocker
tags: [issue, test, operator, wave/program-graph-indexing]
---

# bin/seon init is red: indexed test reaches script-side seon.dev.markdown

## Problem

`bin/seon init` exits 1 at HEAD on unresolved
`md/validate-repository-pins` at `test/seon/dev/markdown_test.clj:203` and
`:211`. The public implementation is
`script/seon/dev/markdown.clj:1112` (moved there by `fd9777027` for the
operator boundary), outside the indexed `src/` + `test/` inputs. Clj-kondo
therefore emits a blocking `:unresolved-var` finding before publication.

## Evidence

An isolated `bin/seon --root tmp/core-call-init-before.EhUpvA init` refused
before source publication completed on 2026-08-17. Its 17.97-second wall time
is a failed attempt, not an initialization baseline. `bin/test`'s own
program-graph build was green the same day, so the breakage is
publication-specific.

The refusal blocks every lane that must run or time a complete initialization.
It also prints every warning around the one blocking finding; that separate
diagnostic defect remains recorded in
`docs/seon/issues/context-wave-leaves-three-small-honesty-defects.md`.

## Owner

The program-graph publication boundary must make the repository-pin test and
its implementation occupy one indexed source model. Candidate resolutions are:

1. Move the test to the script side with its subject, explicitly accepting
   that the main gate no longer discovers it.
2. Move `seon.dev.markdown` back under `src/` as first-party code.
3. Admit script-side namespace targets as name-only externals during source
   publication, preserving the test in the gate.

The third follows the existing name-only external precedent without reading an
absent subject as health.

## Acceptance

The repository-pin regression remains discovered by its intended gate, and a
fresh isolated `bin/seon init` completes without an unresolved-var finding for
`md/validate-repository-pins`.

## Resolution (2026-08-17, orchestrator)

Option 1 executed after option 2 was falsified live: moving the
namespace into `src/` traded the unresolved-var refusal for a
projection refusal — its contracts use var-quoted private schemas,
which the STATIC indexer cannot read (neither can bare symbols; only
registered keywords or self-contained forms). Namespace AND test now
live together on the script side; isolated init is green. NAMED COST:
the repository-pin regression is no longer discovered by the main
gate. Follow-up debt: convert seon.dev.markdown's schemas to a
registered family (resources/seon/schemas/), after which both files
may return to the indexed model and this issue's original acceptance
is restorable.

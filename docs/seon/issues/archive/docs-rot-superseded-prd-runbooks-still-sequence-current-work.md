---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, orchestrator, documentation]
---

# Make superseded PRD runbooks fail closed

## Problem

The localized runbooks for `docs/prds/source-cleanup/` and
`docs/prds/generate-code/` still declare themselves active and instruct agents
to execute roadmaps for the deleted CLJS/pod system. These directories are not
under `archive/`, so neither their location nor their frontmatter warns a
reader that the active SCI execution-runtime program has retained them only as
quarry.

Because a localized `AGENTS.md` is executable agent authority, this is not
ordinary historical prose. A task rooted in either directory receives a stale
program ordering and stale implementation owners after reading the current
root authority.

## Evidence

- `docs/prds/source-cleanup/AGENTS.md:2-18` has `status: active`, calls its
  adjacent roadmap the live five-stage ledger, schedules pod retirement, and
  prescribes three old suites. Its roadmap is likewise active and begins with
  CLJS owners such as `src/seon/runtime/recovery.cljs`,
  `src/seon/agent/loop.cljs`, and `src/seon/eval.cljs`.
- `docs/prds/generate-code/AGENTS.md:2-38` has `status: active`, calls Stage 1
  the approved current phase, and directs implementation through
  `seon.agent`, `seon.repl.internal`, `seon.eval/eval-batch!`,
  `seon.reactive/observe!`, and the old `:namespaces` context block. None of
  the three named runtime namespaces exists under fresh `src/`; the only
  fresh eval owner is `src/seon/sci/eval.clj`.
- The generate-code runbook also treats `:seon.eval/ns` and persisted
  eval/program/plan entities from the old workflow as current. Fresh run
  schema instead names the parse-time namespace
  `:seon.cluster.eval/namespace` in the RUN section of `resources/seon/schema.edn`.
- `docs/prds/sci-execution-runtime/research/generate-code-quarry-2026-07-29.md:56-74`
  explicitly identifies the old implementation as pod-era quarry, and
  lines 274-286 say not to restore `seon.ai.generate-code`, its observer
  registry, root scheduler, or child lifecycle. The active localized runbook
  therefore reverses the current program's own source-grounded ruling.

The reader chain is direct and transitive:

1. instruction discovery loads either localized `AGENTS.md` for work rooted
   in its PRD directory;
2. that runbook declares its adjacent `roadmap.md` the current implementation
   ledger;
3. the roadmap sends the reader into old research and deleted CLJS owners;
4. active SCI-runtime research still links the old generate-code roadmap as
   quarry, while `docs/seon/reference/llm-adapters.md:355` links its design
   audit without an enclosing historical boundary.

The outermost live reader is therefore the localized runbook. Repairing
individual research pages first would leave the executable stale sequencing
authority intact.

## Owner

The two PRD directories own their localized runbooks and status. The SCI
execution-runtime roadmap owns the current ordering and the explicit
generate-code quarry boundary.

## Acceptance

- Both localized runbooks fail closed before any implementation instruction:
  they identify the material as historical quarry and point to the active SCI
  execution-runtime roadmap for current sequencing.
- Neither runbook nor its adjacent roadmap has active status while it names
  deleted CLJS/pod owners, commands, suites, context blocks, or scheduler
  mechanisms.
- Inbound links that intentionally mine these PRDs label them historical at
  the link site; no active reference page presents an old design audit as a
  current implementation contract.
- A root-to-leaf instruction-chain audit proves that entering either PRD
  cannot override the current CLJ-only runtime and great-deletion boundary.

## Resolution — 2026-08-02

Commit `45cf03d92` made frontmatter lifecycle authoritative at this boundary.
Both `generate-code` and `source-cleanup` runbooks and roadmaps now use
`status: superseded`. Their localized `AGENTS.md` files contain only the inert
historical boundary, so instruction discovery has no stale implementation
directions left to execute. The package-capabilities inbound now labels the
generate-code roadmap historical quarry.

The generic markdown validator previously required only that `status` exist:
an executable stale runbook with `status: active` returned valid with no
violations. The recurring
`seon.dev.markdown-test/historical-prd-authorities-fail-closed-test` now
discovers localized PRD authorities by the `AGENTS.md` filename and sibling
`roadmap.md`, requires their statuses to match, and requires every
`superseded` runbook to equal the one inert boundary body. It does not select
`research/` pages or archived issue notes, so dated historical records retain
what was true when written.

The check refuses absence: discovering zero localized authorities produces
`authority-subjects-absent`. Its mutation proof changed one real archived
runbook back to `status: active`; `bin/test seon.dev.markdown-test` failed with
both `archived-authority-status` and `authority-roadmap-status`. Restoring the
status passed 26 tests / 350 assertions. Markdown validation passed all 25
changed documentation subjects with zero invalid files.
`bin/issues-index --check` passed with 90 open and 861 archived notes after
this blocker left the ranked queue.

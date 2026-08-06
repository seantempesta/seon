---
type: issue
status: open
severity: blocker
tags: [issue, prd, documentation, orchestrator]
---

# Give executable PRD briefs truthful lifecycle status

## Problem

The active SCI-runtime `plan/` directory and top-level PRD inventory retain
superseded executable briefs with `status: active`. The runbook correctly warns
that old briefs are archaeology, but a reader opening a named file sees no
local fail-closed boundary and can implement the deleted read-facade,
session-image, shared-context, or pod/Bun designs.

## Evidence

- `docs/prds/sci-execution-runtime/plan/seondb-facade-contract-spec.md:1-13,67-103`
  is active and specifies a read facade whose writes remain elsewhere. Current
  `src/seon/db.clj:1-14` is the one database namespace for reads and writes;
  root `AGENTS.md` explicitly bans “facade” for this owner.
- `docs/prds/sci-execution-runtime/plan/stateless-resume-design-2026-08-01.md:1-20,84-106`
  and `plan/repl-session-context-2026-08-01.md:1-14` are active executable
  designs for the deleted session-image/shared-session model. Current
  `src/seon/sci/eval.clj:1309-1367` and
  `resources/seon/schemas/seon.def.edn:1-45` implement the two-world desk.
- `docs/prds/sci-execution-runtime/plan/ambient-injection-prd-2026-08-05-r2-draft.md:1-24`
  says both “RULED / graduated from draft” and “unruled replacement draft,”
  while retaining `status: draft`.
- `docs/prds/sci-execution-runtime/plan/unsettled.md:19-37,79-86` names the
  current edge and dependency-ready queue; most active-status dated briefs do
  not occur there. The local runbook itself says older executable briefs are
  archaeology at `docs/prds/sci-execution-runtime/AGENTS.md:24-27`.
- `docs/prds/readme.md:7-18` recognizes only the SCI runtime as active,
  `package-capabilities` as a successor, and two superseded roots. Nine other
  top-level folders declare active status without appearing in that lifecycle
  map. In particular, `docs/prds/package-capabilities/roadmap.md:37-55` still
  sequences work through deleted Bun execution children and an open pod-host
  decision.

## Owner

`docs/prds/readme.md`, the active SCI-runtime runbook, the status/frontmatter of
current `plan/` briefs, and the top-level PRD folder lifecycle.

## Acceptance

- Every executable plan brief is either current and reachable from the active
  dependency ledger, or locally fails closed as completed/superseded history.
- Ruled drafts have one truthful status and no contradictory preamble.
- Completed or superseded top-level PRDs move intact to
  `docs/prds/archive/<original-folder>/`; inbound current links label them as
  history.
- The PRD authority map lists every non-archived top-level PRD and its honest
  lifecycle.
- Historical rename referents remain intact; the cleanup changes authority and
  navigation, not dated evidence.

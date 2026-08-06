---
type: reference
status: active
tags: [prd, reference, agent]
---

# PRD authority map

This page indexes PRD lifecycle; it never sequences work. Current ordering is
owned only by the [SCI execution-runtime program](/docs/prds/sci-execution-runtime/plan/README.md),
with its [working edge](/docs/prds/sci-execution-runtime/plan/unsettled.md).
The [issue index](/docs/seon/issues/index.md) is the ranked queue.

- `sci-execution-runtime/` contains the active program and its current runbook.
- `error-model/` is the active successor currently named by the working edge.
- `accretion-testing/`, `background-work/`, `operational-events/`, and
  `in-server-tests/` remain non-archived pending an explicit current dependency
  edge, as recorded by the 2026-08-06 drift audit.
- `archive/` contains archived historical quarry, including the retained
  `_example-feature/` template.

## Authority lifecycle

Every localized PRD `AGENTS.md` is executable authority, while its adjacent
`roadmap.md` carries that chunk's lifecycle. Their frontmatter statuses must
match. Current authorities use `status: active`; retained superseded
authorities use `status: superseded`; authorities below `archive/` use
`status: archived`.

Historical `research/` documents and archived issue notes are dated records,
not localized sequencing authorities. Their original lifecycle metadata stays
intact; no one retcons them to describe the current system.

The recurring `seon.dev.markdown-test/historical-prd-authorities-fail-closed-test`
discovers every `docs/prds/**/AGENTS.md`, requires a sibling roadmap, enforces
matching lifecycle status, forces every archive runbook to `archived`, and
requires every archived or superseded runbook to contain only the inert
historical boundary. If discovery finds no runbooks, the test reports
`authority-subjects-absent` and fails.

## Historical reading

Enter historical PRDs through their inert localized runbook. Return to the
current program before reading the adjacent roadmap or research, extract only
dated evidence, and re-derive every useful claim against current architecture
and source. Historical commands, owners, gates, and sequencing are never
instructions to execute.

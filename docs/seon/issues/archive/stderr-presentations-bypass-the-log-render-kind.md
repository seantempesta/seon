---
type: issue
status: superseded
severity: friction
tags: [issue, architecture, render, error, runtime]
---

# Route ordinary stderr presentations through log renders

## Problem

Several fresh-tree paths compose human-facing stderr text directly even though
`:seon.render/log` is the process-log output kind. This leaves a second log
presentation mechanism beside the render router and makes the same fault look
different depending on which path emitted it.

## Evidence

`src/seon/cluster.clj:590-606` builds separate dropped-fault and development
panic lines with `println`, `ex-message`, and `pr-str`; the development path
also commits the same fault through `seon.error`. `src/seon/cluster/export.clj:
91-99,195-200` defines and calls another local warning formatter even though
its own docstring says a logging owner should replace it.
`src/seon/instrument.clj:199-204` adds a third stderr formatter for the
zero-instrumented-vars fault.

The error family's ordinary log projection is already
`seon.error/log-line`, declared on a notice as `:seon.render/log`
(`src/seon/error.clj:348-364,450-486` at the audit snapshot). The architecture
names that kind as the process-log consumer at
`docs/seon/architecture/ui.md:47-64`.

The recursion-fence fallback at `src/seon/cluster.clj:498-503` is excluded from
this issue: it runs only when durable fault handling itself failed, so asking
the failed path or router to render it would not be a sound recovery
dependency.

## Owner

The process-log consumer of `seon.render`, with `seon.error` owning fault log
units and the export owner supplying an ordinary warning unit.

## Acceptance

- A successfully normalized core fault reaches stderr through its
  `:seon.render/log` declaration and `seon.render/render`, including development
  panic output.
- Export fallback warnings are ordinary log units or use the same log
  projection consumer; `warn!` is not a second formatter.
- Drop reporting has one explicit process-local unit or a documented
  last-resort boundary when no durable fact exists.
- Only recursion-fence output remains direct, and a regression proves a fault's
  ordinary stderr bytes come from the same log projection used by
  `seon.problems`.

## Resolution 2026-07-29

Archived by `4a4d2c44f` as a boundary-classification error.
`:seon.render/log` remains the one reusable human projection of a renderable
notice. The direct stderr sites cited here are process-control annunciators
rather than competing projections:

- `commit-fault!` reports that the durability path itself failed and cannot
  depend recursively on that path;
- the counted-dropping callback reports a fault that was not admitted and
  therefore has no durable notice to render;
- development panic first commits the fault, then announces the selected
  process-control disposition without making that line durable;
- the instrumentation zero-count line is a startup invariant failure; and
- the export warning announces selection of a slow fallback path, not a
  projection of domain or fault data.

Routing those lines through the projection contract would manufacture units for
control events or make the failed machinery a dependency of its own
annunciator. The architecture now states this boundary explicitly. Durable
fault notices still declare `:seon.render/log`, route through
`seon.render/render`, and share that derived projection with `seon.problems`;
the existing error and render suites prove the reusable path.

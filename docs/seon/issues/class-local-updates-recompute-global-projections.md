---
type: issue
status: open
severity: friction
tags: [issue, performance, render, schema, class/n9, class-kill, wave/class-kill-queue]
---

# Make local updates unable to recompute global projections

## Problem

Several local operations reconstruct or rerun a whole schema, source,
namespace, render, or run projection. Costs that should scale with the changed
dependency closure instead scale with the complete database/program graph.

## Evidence

Current open members carry `class/n9` and are derived with
`bin/issues-index --class class/n9`.

## Owner

The immutable-basis projection caches and recorded dependency edges at schema,
publication, run-loop, and render boundaries.

## Acceptance

- Derived state rides the database value/program generation that determines
  it, and invalidation is the recorded transitive dependency closure.
- Local constructors cannot call a global rebuild or full render walk.
- Cost properties show unchanged work remains constant while total graph size
  grows, and changed work scales with the affected closure.

## Folded members — 2026-09-21

Four instances are archived (`status: superseded`, `superseded-by` this note);
their full evidence stays at `archive/<name>.md`. The membership query no
longer returns them, so the list is stated here.

| Member (in `archive/`) | Claim | Current file:line |
|---|---|---|
| `complete-publication-takes-seventy-seconds.md` | Complete source publication costs 59–159 s against the ten-second law | see paragraph below |
| `core-namespace-pages-spend-seven-seconds-without-declaration-fallbacks.md` | `GET /ns/seon.db` measured 7.647 s with one declaration fallback, refuting the declaration-bridge attribution | `src/seon/render/web.clj` route derivation |
| `render-package-proc-reruns-unchanged-renderers.md` | The package proc derives the complete walk before comparing retained evidence, so a one-block change still invokes eight renderers (1.8–1.9 s per steady pass) | `src/seon/render/web.clj:457` (`render.walk/neighborhood` precedes the comparison) |
| `retained-render-packages-survive-producer-replacement.md` | The stale-output half of the same boundary: a retained package keeps serving `renderer unavailable` blocks after the selected producer's Var is replaced | `src/seon/render.clj:977-985` |

**Folded evidence — the measured publication numbers.** From
`complete-publication-takes-seventy-seconds.md`: after repairing a discarded
construction projection and a per-wrapper config-defaults rebuild, complete
publication/adoption succeeded in default PID 41822 at 159,359 ms and
150,095 ms; population alone took 25–26 s and reading published rows another
25–26 s. Two earlier `bin/seon init --dev default` requests exited 1 at the
declared 30,000 ms silence boundary after the compiled-population progress
event (11,191 entities, 23,734 identities, 39,441 keyword facts), with command
totals of 74,711 ms and 59,232 ms (`data/operator/operations/init-init-42258.log`,
`init-init-45646.log`). Those are the class's headline numbers: the cost is
proportional to the whole program where it should be proportional to the
change. Landing note:
[publication update repair](../../prds/context-generation/research/publication-update-repair-2026-09-19.md).

`turn-bookkeeping-exceeds-recorded-regression-bound.md` carries `class/n9` but
is NOT folded: it records an unattributed 5,645–7,198 ms bookkeeping
measurement against an unchanged 300 ms assertion and states explicitly that
no performance cause is inferred. Nothing in it demonstrates a local update
recomputing a global projection, so it remains open on its own.

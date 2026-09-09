---
type: issue
status: resolved
severity: blocker
tags: [issue, render, walk, history, debug-page, class/total-boundary]
---

# The render proc faults in `seon.render.walk/history-entries` on a Long

Observed 2026-09-08 16:00 on a freshly reforked `default` (commit
`6aa081b3…`), Juniper reseeded through the fixture, first debug page load:

```
SEON CORE FAULT (dev panic): Don't know how to create ISeq from: java.lang.Long
:seon.error/proc  :seon.render.web/render
:seon.error/cid   :seon.render.web/context
:seon.instrument/fn seon.render.walk/history-entries
```

The fault fires on the render proc's `::context` demand (the debug page's
agent-context request). `history-entries` receives a Long where it walks a
sequence — most likely an evaluation/turn attribute whose shape changed
under the turn cut (`:seon.turn/*` rows, `:seon.eval/*` ordinal or basis)
while the walk still expects the run-form vector. The fault's own data was
capped (`:seon.eval/missing :over-bound`), so the offending value is not in
the fact; reproduce with `(seon.render.walk/history-entries db agent)` on
`default` and read the complete envelope.

A render must never throw (AGENTS §2.4): whatever the shape, the history
renders a typed elision naming the attribute, and the proc keeps serving
the page. Fix the shape at its writer AND make the walk total.

## Verified cause and resolution, 2026-09-08

The numeric value is a legitimate anonymous component's entity lookup, not
an incorrectly written evaluation field. `history-entries` called `first`
on that lookup while detecting message identities. Commit `985a830b5` inspects a
lookup's first element only when the lookup is a vector. The numeric entity
ID and its shown text remain intact; no elision or writer change is needed.
`seon.render.web-debug-test/history-preserves-numeric-entity-lookups` proves
this with the canonical database and armed contracts.
The context demand channel is also gone: context acquisition runs on its
caller's thread. See the
[landing note](../../../prds/context-generation/research/page-feed-landing-2026-09-08.md).

## Reverified at HEAD, 2026-09-09

The requested top-level issue had already been archived by `e23e74022`.
The original numeric-lookup fix is `985a830b5`; its regression was recorded
in `baa1dde54`.
`ff9507c1b` moved outcomes to shown text; `0b7c8043c` and `a90ed5cce`
accepted shown-text settlements in ordered episodes. `595b0bf7c` then
deleted `walk/history-entries` and its obsolete regression, replacing
history assembly with `seon.eval/of-agent` through the evaluation schema's
render pair. The original helper cannot be called at HEAD because it no
longer exists; absence alone is not this issue's closure proof.

At `c6db5d67c` (rendering identical to entering `053a71446`), the new
`seon.render.web-debug-test/saved-history-preserves-shown-text-with-numeric-lookups`
uses the canonical database and real SCI under armed contracts. It stores
an anonymous plan component, renders a saved evaluation through a Long agent
lookup, requires the exact shown bytes and equality with a lookup-ref call,
then changes the component and requires unchanged history. No production
change is needed: the old sequence operation is absent from this path.

The committed read-only
[scratch probe](../../../prds/context-generation/research/platform_tail_history_probe_2026_09_09.clj)
observed Juniper agent **35252**, anonymous plan **35259**, **10 entries /
5,022 UTF-8 bytes**, identical numeric/ref history, no-provider **true**,
provider attempts **0**. MCP JVM execution took **222 ms**. The already
rebound debug page returned HTTP 200. Exact commands and gates are in the
[platform-tail note](../../../prds/context-generation/research/platform-tail-landing-2026-09-09.md).
Status stays resolved; this is a re-verification of the replacement path.

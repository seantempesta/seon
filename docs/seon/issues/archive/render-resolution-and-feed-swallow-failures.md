---
type: issue
status: resolved
severity: friction
tags: [issue, render, errors, web]
---

# Preserve render resolution and feed failure evidence

## Problem

The render path catches broad failures at three boundaries and turns them into
absence or silence. A broken renderer can therefore select the generic floor,
look merely unresolvable, or close an SSE feed without leaving the evidence
needed to distinguish those outcomes.

## Evidence

`src/seon/render.clj:282-305` catches every `Throwable` from namespace Var
resolution and schema matching, returning `false` or `nil`. `resolve-unit` at
lines 323-329 consequently marks those failures as an ordinary fall to the
floor. A direct probe that made `requiring-resolve` throw returned
`{:seon.render/ai seon.render.block/data-prose,
:seon.render/would-fall-to-floor? true}`.

The final Var lookup at `src/seon/render.clj:364-382` catches the same broad
class and emits `::unresolvable` without the throwable class, message, or
ex-data. `src/seon/render/web.clj:775-797` then catches every failure in the
SSE writer thread as `_`, records nothing, and closes the connection.

These are not equivalent to genuine absence. They cross the one render
contract used by both the AI and HTML projections.

## Owner

The `seon.render` resolution result and the web feed's existing core-fault
path.

## Acceptance

- One resolution function returns a declaration, genuine absence, or a flat
  structured error preserving the failing symbol, throwable class, message,
  and ex-data.
- The floor is selected only after successful resolution proves absence.
- An SSE writer failure reaches the existing fault committer with page, tab,
  and projection provenance before the feed closes.
- Regressions inject namespace load, schema matching, projection resolution,
  and writer failures and distinguish all four from ordinary absence.

## Research update — 2026-08-02 render-model map

The counted change map is
`docs/prds/sci-execution-runtime/research/render-model-2026-08-02.md`.
The issue survives intact and is broader than the router catch sites:

- `src/seon/render/block.clj:309-324` currently exposes the renderer's internal
  error message to humans;
- `src/seon/render/walk.clj:844-857` prints the same failure to every viewing
  agent rather than specifically the renderer owner;
- `src/seon/render/web.clj:804-842` has no asserted feed-writer fault path; and
- `src/seon/cluster.clj:954-962` does not hand the existing fault channel to the
  web service.

The settled persistent surface is event-derived: unavailable after the
fault/message commit, loading only while the exact repair message has an open
held run, then success or unavailable after settlement. An unchanged renderer
signature must neither recommit nor remessage. This requires renderer,
projection, and stable call identity in the durable repair signature; the
current signature omits them (`src/seon/error.clj:251-258,315-323`).

No code or closure evidence was produced in this research lane, so the issue
remains open.

## Resolution

Commits `230e5f452` and `a837c063a` remove the silent resolution branches and
the silent feed catch. The selector now returns deterministic flat ambiguity
and invocation failures, renderer failure rows retain their private value
while public HTML says only unavailable, and the explicitly assigned namespace
owner receives one digest-identified durable message. Repeating the same
failure produces no second row. A feed-writer exception enters the cluster's
existing fault channel with agent, page, tab, and HTML-output provenance.

The ruled limits remain explicit: agentless stakeholder fan-out is absent
because no stakeholder relation exists, and loading is absent because no
repair-acceptance event exists. Neither gap was replaced by a list or clock.

Proof: `seon.render-simplification-test/broken-renderer-is-private-to-browser-
and-loud-to-owner`, `seon.render.web-test/a-feed-writer-failure-enters-the-
cluster-fault-path`, and the focused render gate (61 tests, 305 assertions;
only the sealed Step 8 package-reuse assertions remained red before Step 8).

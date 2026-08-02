---
type: issue
status: open
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

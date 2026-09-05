---
type: issue
status: open
severity: friction
tags: [issue, render, wave/agent-context]
---

# Carry the viewing namespace through direct and nested rendering

## Problem

The owner's customization rule prefers a renderer in the viewing agent's
namespace. Current traversal derives the rendered member's namespace, and nested
selection skips namespace candidates altogether.

## Evidence

Source inspection, 2026-09-05: src/seon/render/walk.clj:477-491 derives the
member namespace; :560-574 supplies it to rendering. src/seon/render.clj:178-207
searches that supplied namespace; :437-498 nested traversal checks explicit and
schema renderers only. This is a source finding; a two-viewer live regression
is not yet run. [Investigation](../../prds/context-generation/research/design-lab-investigation-2026-09-05.md).

## Owner

The existing selection and recursive invocation in seon.render and its caller
in seon.render.walk. The current walk is subject to redesign, not protected.

## Acceptance

The same stored value, directly and inside a larger value, uses an applicable
local renderer from viewer A and the general default for viewer B. Explicit
overrides remain visible. The source entity and viewing entity are independent
inputs and the candidate explanation reflects actual invocation.

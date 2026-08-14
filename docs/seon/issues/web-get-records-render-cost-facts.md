---
type: issue
status: open
severity: blocker
tags: [issue, render, database, web, wave/live-drive-render]
---

# Keep read-only web observation from writing render-cost facts

## Problem

Reading an agent page mutates the observed cluster once per selected render
call. This violates the Drive observer's read-only contract, changes the basis
being measured, and makes observation itself a source of render wakes.

## Evidence

Immediately before the observer curled the isolated Drive 1 web UI, the
database basis was `t=536870976`. The agent, debug, and root GET probes ran
from `06:12:58Z` through `06:13:12Z`. Immediately afterward:

- basis was `t=536871061` (`+85` transactions);
- 84 `:seon.render.cost/estimated-tokens` facts existed;
- their timestamps were exactly `06:12:58Z` through `06:13:12Z`;
- their estimated-token sum was 35,290.

No render-cost fact existed in the Drive opening interval
`05:39:40Z`–`05:39:47Z`. `src/seon/render.clj` constructs
`render-cost-fact` and calls `seon.db/transact!` from the selected-render path;
the cluster listener wakes rendering on transaction reports.

## Owner

The render-cost observation seam in `src/seon/render.clj` and the cluster
render wake boundary.

## Acceptance

A GET of an agent, debug, or root page leaves database basis unchanged while
still making render cost observable. One live regression holds a database
value before and after the GET and proves no cost-recording transaction can
self-wake rendering.

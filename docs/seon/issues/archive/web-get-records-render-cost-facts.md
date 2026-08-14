---
type: issue
status: resolved
severity: blocker
tags: [issue, render, database, web, wave/live-drive-render]
---

# Keep read-only web observation from writing render-cost facts

## Problem

Reading an agent page mutated the observed cluster once per selected render
call. This violated the Drive observer's read-only contract, changed the basis
being measured, and made observation itself a source of render wakes.

## Preserved Drive 1 evidence

Immediately before the observer curled the isolated Drive 1 web UI, the
database basis was `t=536870976`. The agent, debug, and root GET probes ran
from `06:12:58Z` through `06:13:12Z`. Immediately afterward:

- basis was `t=536871061` (`+85` transactions);
- 84 `:seon.render.cost/estimated-tokens` facts existed;
- their timestamps were exactly `06:12:58Z` through `06:13:12Z`;
- their estimated-token sum was 35,290.

No render-cost fact existed in the Drive opening interval
`05:39:40Z`–`05:39:47Z`. The preserved root at `tmp/drive-1-root` was read
only during diagnosis. The recording transaction was at the one selected-call
seam in `src/seon/render.clj`; the web page walk reached that seam with a
connection and retained-call evidence even though it was only observing.

## Resolution

The selected-call seam now records a render-cost fact only for a new retained
call that carries all of the agent-context receipt evidence:

- the call was not reused;
- it has a `:seon.render.call/id` and a
  `:seon.render/captured-calls` collector;
- it carries the held `:seon.cluster.run/id`; and
- it carries the cluster `:seon.db/connection` used to commit the fact.

Those are the legitimate recording calls: `/ai` calls made while assembling a
held run's agent context. Page, root, and debug walks do not carry the held run
identity. They may retain their render-call evidence for equality and read
dependency checks, but they never transact render cost.

`only-agent-context-render-receipts-record-cost` exercises both consumers with
the same database, connection, retained-call shape, and agent render profile.
The web-like HTML call returns a block and is retained while database basis and
the render-cost relation remain unchanged. The following held-run `/ai` call
advances basis exactly through cost recording and leaves one cost fact whose
estimate matches its output.

## Verification

Focused isolated gate on 2026-08-14:

```text
bin/test seon.render-coverage-test
Ran 5 tests containing 155 assertions.
0 failures, 0 errors.
```

The successful gate used isolated operator root
`tmp/test-runs/run.IV4bQr`, which the runner removed after completion.

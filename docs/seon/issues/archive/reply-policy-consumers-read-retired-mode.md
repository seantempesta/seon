---
type: issue
status: resolved
tags:
  - issue
  - runtime
---

# Reply-policy consumers read the retired mode

Run allocation and historical web validation still interpreted
`:seon.config/repl-mode` after reply policy split into wire-streaming and
reply-evaluation axes. Explicit per-agent policy facts could therefore select
the wrong run limit or make valid historical attempts appear malformed.

Resolved on 2026-07-23 by acquiring both cluster and agent policy projections,
resolving them through the driver's policy accessor, and consuming each axis
at its actual decision point. Regression fixtures now prove agent precedence
and conform to the persisted attempt entity schema.

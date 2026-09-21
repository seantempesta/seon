---
type: issue
status: open
severity: blocker
created: 2026-09-21
tags: [agent-platform, bounded-execution, test-fixture, class/absence-as-health]
---

# Cancelled Future state is not body exit evidence

The B2 replacement proposal at `60db9f18a` used a second `.get` after
`cancel(true)` and `.isDone` to permit context reuse. A 16 ms live probe
returned done=true, thread-alive=true, exit-observed=false, and an immediate
CancellationException from get. The body exited only after the probe released
its latch; cleanup awaited exit and joined the thread.

Use the execution body's actual-exit completion, with a distinct terminal case
for cancellation before entry. Keep context reuse refused while work remains
alive; graph disarm cannot prove host effects stopped. B2's plan now says this.
Production retirement remains gated by no-overlap/exit proof, including a
noncooperative body and shutdown from the owning proc without self-join.

Exact form, measured result and core.async FutureTask seam:
[REPL verification](../../research/agent-platform/repl-verification-deep-review-2026-09-21.md).

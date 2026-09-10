---
type: issue
status: open
severity: friction
tags: [issue, render, performance, database, wave/render-acquisition-performance]
created: 2026-09-10
---

# Root warm requests can exceed 300 ms during read-evidence replay

## Evidence

After `d6d399561` removes undeclared-ref acquisition, default PID 23557
served six immediate-warm root samples in 0.051657, 0.082493, 0.164832,
0.087337, 0.378654, and 0.335110 seconds. The median is 0.1260845 seconds,
but two samples exceed the prior issue's 0.3-second target. Each response
is 55,630 bytes, with 120 live agent turns. These measurements include a
concurrent virtual-thread dump; they do not isolate its overhead.

The slower round-4 handler was sampled at 2026-09-10T21:16:54.100114Z:

```text
datahike.pull-api/pull-plan-with-evidence (pull_api.cljc:563)
seon.db/pull-plan-with-evidence (db.clj:698)
seon.db/replay-read (db.clj:724)
seon.db/read-evidence-current? (db.clj:832)
seon.render.web/candidate-call-ids (web.clj:1971)
seon.render.web/derive-page (web.clj:2095)
```

This is evidence of where that sample was executing, not proof that replay
accounts for its entire latency. Across two actual scheduled firings all
149 saved root/Juniper entity-cache outputs remained identical and their
read evidence remained current. The old every-ref expansion is therefore
not an explanation for this remaining observation.

Full numbers and reproduction are in
[the root-walk landing](../../prds/context-generation/research/root-walk-landing-2026-09-10.md).
The committed sampler is `test/seon/render/root_walk_probe.py`; disposable
dumps were inspected and removed after the stack above was recorded.

## Owner and boundary

`seon.render.web/candidate-call-ids` consumes the database owner's evidence
comparison. The root-walk assignment explicitly preserves read evidence and
the shared cache, so this is a separate measured follow-up. No new timer,
cap, or cache was added to mask it. The orchestrator owns index scheduling.

## Acceptance

Separate sampler overhead from HTTP work; measure warm requests on a fixed
declared-concern population across real operational writes. If evidence
replay owns the cost, strengthen its existing authority without suppressing
real dependency changes. Require actual page contents, nonempty evidence,
and invalidation after a declared concern changes.

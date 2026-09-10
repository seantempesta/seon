---
type: issue
status: resolved
severity: blocker
tags: [issue, render, performance, agent, class/p1]
created: 2026-09-10
resolved: 2026-09-10
---

# Root's page walks every installed ref attribute, and schedule fires keep it cold

## Observed (2026-09-10 14:35–14:45, default, pid 23557)

Twenty-four `GET /` samples with a `jcmd Thread.dump_to_file` every second
(virtual threads included; `Thread/getAllStackTraces` omits them and showed
nothing): 1.1 s warm, and 16.6 s, 13.4 s, 9.8 s, 10.9 s on four of the
first eight loads (`tmp/root-probe/log.txt`). The handler thread was
RUNNABLE the whole time in `seon.render.walk/acquired-tree` →
`acquire-entity` → `seon.db/pull` → `datahike.pull-api/pull-pattern-frame`
and `seon.db/decode-pull-entity` (`src/seon/render/walk.clj:411-495`,
called from `seon.render.web/refresh-root` at `web.clj:2003`).

## Why

1. `acquired-tree` expands the root agent through EVERY installed ref
   attribute, forward and reverse (138 ref attributes; `walk.clj:449-453`).
   The turn PRD rules that blocks come from declared concerns and reverse
   concerns only when the schema declares them; the walk ignores that and
   pulls the whole neighbourhood at distance 2.
2. Reverse refs into root today: `seon.maintenance.request/agent` 734,
   `seon.schedule.fire/agent` 734, `seon.turn/agent` 14, six others ≤ 6.
   Every scheduled blob-retention firing (`seon.schedule/fire-due!` →
   `seon.blob.retention/reclaim!`, both visible in the same dumps) writes a
   fire row and a maintenance request referencing root, so root's read
   evidence goes stale on every firing and the page derives cold again.
   Juniper has no such rows: its page is 0.1 s.

## Wanted

Lane observation, 2026-09-10: the initial MCP runtime-status request timed
out and reported unknown health. A bounded JVM evaluation on the same
default process then succeeded (425 ms); diagnosis continues through that
working MCP evaluation surface. No default restart or refork was performed.

- The walk follows declared concerns (the schema's render pairs and
  component/derived-query declarations), never "every ref attribute";
  width caps are not a substitute for not walking.
- Schedule fires and maintenance requests do not accumulate unbounded
  rows pointing at an agent, or are not concerns of the agent page.
- Root's page warm ≤ 0.3 s and cold ≤ 1 s, measured with the same sampler,
  through several schedule firings.

## Resolution

`d6d399561f8821e0c384716ff68672c51d0324fb` derives acquisition from each
entity's schema-declared concerns and components, retaining namespace
requires. It deletes every-ref expansion; schedule fires and maintenance
requests are operational facts, not declared agent concerns. The existing
shared cache and read-evidence mechanism remain in place.

Default cold root: 7.555628 → 0.748105 seconds; distinct pulled entities:
2,276 → 132 despite the concurrent trial growing root's turns from 14 to
120. At the identical original database basis the acquisition shrinks from
2,278 to 23 members, with identical HTML text in identical order. Two actual
firings preserve all 149 saved root/Juniper cache outputs and current evidence.
Fast 27/158, scoped 27/162, platform 84/505 tests/assertions are green.

This resolves the traversal and operational-invalidation defect. The strict
0.3-second warm target is not universal: median 0.1260845 seconds, maximum
0.378654 seconds in the six-round sampler. The remaining read-evidence
replay observation is tracked in
[the follow-up](../root-page-warm-read-evidence-replay-exceeds-300ms.md).
Full measurements and the successful in-place default adoption are in
[the landing note](../../../prds/context-generation/research/root-walk-landing-2026-09-10.md).

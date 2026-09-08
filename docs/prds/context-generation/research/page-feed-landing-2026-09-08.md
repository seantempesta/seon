---
type: research
status: active
tags: [web, render, adoption, verification]
---

# Page and feed landing — 2026-09-08

Bounded ownership: `src/seon/render/*.clj`, public CSS, their tests, and
the web-server adoption hunk only in `src/seon/cluster.clj`.

Read the AGENTS.md lane rules, turn PRD §10 and §§13–17, the plan README
and working edge, and all three assigned issues end to end. No default
stop, refork, or restart was performed by this lane.

## Initial observations

- MCP answered on PID 45036. Readiness named port 7994; the advertisement
  still named 58444. The render proc's ping was unknown.
- The assigned Juniper debug GET returned **404 in 0.001537 s**, body
  `not found`. This is a failed page observation, not a latency pass.
- The next MCP evaluation reported `repl-unavailable`, advertisement
  missing. Browser MCP reported no browser available. Live verification
  will be retried without operating another lane's session.
- Shared working-tree edits were present in the runner, hooks, fixtures,
  and other namespaces. They were preserved; gates select owned paths.

## Dependency ledger and design

- http-kit accepts a Ring function and retains it for the server lifetime:
  `reference-code/http-kit/src/org/httpkit/server.clj:79–180`.
  `seon.render.web/start!` supplies the function and a virtual-thread executor.
- Adoption already reloads definitions and applies instrumentation in
  `seon.cluster/development-source-refresh!`; its source contains no server
  stop or start. Socket shutdown remains in cluster shutdown. There is no
  justified rebind to add to adoption.
- The server request closure now calls the `handler` Var, so an existing
  listener observes the current constructed handler after reload.
- core.async's mult/tap is the existing delta delivery owner. http-kit's
  `write-state` drain-or-close completion remains the backpressure owner
  (`reference-code/http-kit/src/org/httpkit/server.clj:321`).

## Verification

The armed fast socket regression passed: **1 test, 7 assertions, zero
failures/errors**. It starts the canonical web fixture's real HTTP server,
redefines the handler factory while retaining its actual implementation,
and verifies a subsequent request invokes that current Var on the same
listener. Instrumentation state is restored by the canonical fixture.

Default reappeared as PID 79120, correctly advertised on port 7994, without
this lane operating its lifecycle. The Juniper GET returned **200 in
1.065880 s**. The baseline Playwright screenshot at 1600×1000 confirms the
narrow paired previews and repeated unavailable-function output:

![Before](page-feed-before-2026-09-08.png)

The committed `test/seon/render/page_feed_probe.py` launched curl every
200 ms for 60 seconds: **300 requests, zero non-200, maximum 5.292470 s**.
An existing publication held the lifecycle lock throughout that sampling;
the lane's explicit adoption was queued behind it. This establishes
availability under that observed load, not completion of the queued
adoption. The >5 s response is a performance defect still to resolve.

The isolated gate `SEON_TEST_WORKERS=3 bin/test --paths
src/seon/render/web.clj test/seon/render/web_adoption_test.clj --
seon.render.web-adoption-test` passed: **1 test, 7 assertions, zero
failures/errors**, exit 0; its successful root was removed by the runner.

Pending: completed-adoption coverage, first-event
load probe, and after screenshot. No completion claim is made yet.

## Feed implementation and probes

The first commit is `dd7fc589a`. A second 60-second curl sample during the
explicit adoption path recorded **300/300 HTTP 200**, maximum **6.403608 s**.
The listener's identity remained **254437913**, port **7994**. Adoption
reached reload, SCI acquisition, and instrumentation, then refused its final
publication marker with `Source changed during development adoption; the
next edit must converge it.` This is availability through an adoption
attempt, not a converged-source claim.

GET and SSE now call `current-page`, using the existing page derivation on
one captured database value. A connection taps before capture so it cannot
miss a racing publication; its independent first paint does not acquire a
proc revision. The first proc delivery therefore uses a complete keyframe;
later contiguous revisions retain the existing delta/drain semantics.
The proc no longer owns joins' first paint. The unbounded tap read and the
old proc-settlement join mechanism are removed.

The paused-proc real-socket regression passed under armed contracts:
**1 test, 8 assertions, zero failures/errors**. It verifies first paint
while the proc is paused and a `datastar-patch-signals` event containing
`seon.error/kind = :seon.await/backstop-fired` before EOF. The JSON event
preserves the namespace of the error kind and names the declared bound.
The same test passed the isolated `bin/test --paths src/seon/render/web.clj
test/seon/render/web_test.clj test/seon/render/web_feed_test.clj --
seon.render.web-feed-test` gate with `SEON_TEST_WORKERS=3`: exit 0,
**1 test, 8 assertions**. The runner removed its successful root.

A hot reload followed by re-arming **909 functions** served the full GET
in **1.119114 s**. A later three-tab probe timed out at **20 s** during
navigation under write load. The live thread dump located both the proc
and request in `seon.turn/system-plan` → `seon.db/read-evidence-changes` →
Datahike historical datom merging. A subsequent GET took **2.618699 s**.
These are failed latency probes, not evidence of a stopped HTTP listener.
The debug page still attempted a prospective turn while its evaluation
query was unavailable; the next UI slice handles that missing dependency.

## Immediate page repair after the 16:35 owner ruling

The expanded attribute page exposed a lane defect: `block-metadata` passed
scalar, absent, and collection values to `value/transacted`, whose contract
requires an entity map. It now transacts maps only; other values remain on
the ordinary value renderer path. The canonical, instrumented regression
includes nil, an integer, text, and a collection as well as schema metadata.

`SEON_TEST_WORKERS=3 bin/test --paths src/seon/render/web.clj
test/seon/render/web_debug_test.clj resources/public/css/input.css --
seon.render.web-debug-test`: **2 tests, 13 assertions, zero failures/errors**.
After reloading the web Var and re-arming **908 functions**, default debug
answered **200 in 1.454248 s, 776208 bytes**. The running server was preserved.
The unfinished walk optimization was shelved to `tmp/page-feed-walk-shelved.patch`;
further work proceeds in `tmp/page-feed-wt` and the requested scratch root.

I reread the updated feed issue end to end. The new priority is caller-owned
derivation with shared read-evidence caches and deletion of the context demand
channel, before further page layout work. The sub-second concurrent plain-page
and turn-context measurements remain outstanding.

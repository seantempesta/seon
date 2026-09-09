---
type: defect
status: open
severity: friction
tags: [adoption, web, dev-cluster, class/availability]
---

# Development adoption drops the web server while it reloads

Observed 2026-09-08 14:26 on `default` by the page monitor: during
`init --dev default --changed src/seon/render/value.clj …` (a hook
publication) the debug page went from 200 to connection refused (HTTP 000)
and came back after the adoption. Every hook publication therefore takes
the owner's window down for the length of a reload, and with several lanes
editing that is most of the time.

## Why

Adoption reloads the changed namespaces in `:seon.ns/requires` order and
re-acquires SCI; somewhere on that path the http-kit server or its render
proc is stopped and restarted instead of the Var-level swap the live-update
law promises ("re-evaluating a `defn` against the running system changes
proc behaviour immediately"). A reload should never close the listening
socket.

## To do

Find the stop on the adoption path (`seon.cluster/refresh-source!` →
`development-source-refresh!` → the render/web graph) and make adoption
swap Vars under a live server; regression: a page request issued DURING an
adoption is served (200), never refused. Owner: the lane holding the
refresh path (`hook-coalesce`, 2026-09-08).

## Refresh-path investigation (2026-09-08, 14:28–14:54)

No call from `development-source-refresh!` to server or graph shutdown was
found. The first-party `web/stop!` call is in `cluster/disarm-agents!`, called
by `cluster/stop!`; the development adoption path reconciles facts, reloads
Vars, acquires SCI, instruments, and offers a render notification. Treat the
claimed stop/restart cause above as a hypothesis until a call is observed.

The first probe used `/debug`, which is not a declared route and returned
404. It was corrected to `route/path ::route/agent-debug {:id "root"}`.
A request to that valid route accepted a connection but timed out after
15 seconds without response bytes. HTTP 000 from that curl request was a
read timeout, not connection refusal. A successful served-page proof is
still required; neither outcome establishes an adoption-triggered close.

The cohosted adoption regression now requests the declared debug page at
four adoption progress stages and asserts that the same HTTP server object
remains installed. It is still under verification. The saved live probe
separately records request statuses/errors and server identity so a tool
read timeout does not erase the evidence.

At 15:04, a JDK 26 `Thread.dump_to_file -format=json` of PID 36758
showed two live http-kit selector loops. Of 23 stacks containing
`seon.render.web`, 22 request stacks were in schema projection work:
14 in `schema/admission-from-asserting-transaction`, five in
`schema/fold-contract-validations`, and three in schema compilation.
The request chain includes `debug-prompt → turn-function-result →
read-evidence-changes → replay-read → decode-query-result →
projection-from-database`. The remaining render-proc stack waited on a
Datahike transaction from `render/render-call`. This sampled evidence
locates expensive request work, not a closed listening socket. The web
owner has concurrent edits; this lane did not alter it.

The completed live adoption probe retained identical server identity and
beta program digest `4061e67f50c7a7a7d8991302fe36ce938651825d791370d35324d39be250920d`
before/after. It refused at SCI acquisition; all five requests timed out,
including the request before adoption. This is not an availability pass.

Fresh PID 45036 repeated the probe in 91,415 ms. Analysis refused an
unresolved `transcript` namespace in the concurrently edited
`test/seon/render_source_test.clj:317:33`, before any adoption stage.
The initial page timed out; its old URL later refused a connection and the
registered server object had changed. The registry then named port 7994
instead of 58444; requesting that URL also timed out. This additional
observation cannot attribute the server replacement to development adoption,
which this request never entered. The availability acceptance remains open.

Page/feed lane observation, 2026-09-08: MCP initially answered on PID 45036
with readiness port 7994 and advertisement port 58444. The assigned Juniper
debug URL returned 404 in 0.001537 s. A subsequent MCP evaluation reported
`repl-unavailable` because the advertisement was missing. The lane did not
stop or restart default; this observation cannot attribute an outage to
adoption. Live availability verification remains required.


## In-place handler change and verification boundary, 2026-09-08

The server now resolves the handler Var for each request (`dd7fc589a`);
program adoption does not require stopping or rebinding http-kit. No port
rewrite is needed on that path. Two earlier 200 ms curl runs returned 300/300
HTTP 200 across adoption stages, but adoption refused its final convergence
check. A later 600-request debug load recorded 125 HTTP 200 and 475 curl
10-second deadlines while adoption waited on the lifecycle lock. The old
JVM then disappeared; its last observed log line was an invalid-schema dev
panic, not evidence of an http-kit-only restart. No default lifecycle action
was performed by this lane. A fully converged, zero-outage adoption is still
a pending proof; do not mark this resolved from the handler change alone.
See [the landing note](../../prds/context-generation/research/page-feed-landing-2026-09-08.md).

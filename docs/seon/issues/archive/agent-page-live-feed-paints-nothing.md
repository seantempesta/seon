---
type: issue
status: superseded
severity: blocker
tags: [issue, web, render, flow]
---

# Commit feed-writer failures instead of swallowing them

## Problem

The original empty-feed defect is fixed: namespace-page GET and SSE now call
the same `seon.render.web/page-of`, and an opening feed paints every walked
unit. The note remains actionable only for its second acceptance boundary.
The feed's virtual writer still catches every `Throwable` and returns `nil`,
so a render, serialization, or socket-writing defect closes the SSE stream
without one durable core-fault fact. A tab can stop painting with no queryable
explanation.

## Evidence

- `2c74a2353` replaced the structurally empty block-membership producer with
  namespace-walk units. `src/seon/render/web.clj:300-340` derives the page, and
  `feed` and `page-response` both consume that function.
- `test/seon/render/web_test.clj:285-296` asserts that an opening feed paints
  every unit returned by `page-of`; the socket tests also prove a committed
  message appears and reconnect repaints current facts.
- `src/seon/render/web.clj:792` still implements `(catch Throwable _ nil)` in
  the writer thread. No fault channel or committer call receives the thrown
  value before `finally` closes the SSE generator.

## Owner

`seon.render.web/feed` at the existing cluster fault-committer boundary. The
destination is the **render writer fault-provenance wave**; do not add a web
error registry or a second logging-only path.

## Acceptance

- A deliberately failing initial paint and a deliberately failing later patch
  each become one durable core-fault fact with cluster, agent, and feed
  provenance.
- The socket and tap still close exactly once after the fault.
- Existing initial-paint, committed-message, and reconnect tests remain green;
  no second page producer is introduced.

## Supersession

Superseded during the 2026-08-02 issue-graph reconciliation by
[[render-resolution-and-feed-swallow-failures]]. Commit `2c74a2353` already
resolved this note's original empty-feed defect. Its only surviving evidence
is the same `seon.render.web` swallowed writer failure now owned by the broader
render-resolution issue, which also carries the upstream declaration and
schema-resolution failures. Keeping both notes would schedule one catch site
twice.

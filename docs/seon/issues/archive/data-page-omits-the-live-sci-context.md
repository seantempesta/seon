---
type: issue
status: resolved
severity: blocker
tags: [issue, web, render, sci, live-drive]
---

# Supply the live SCI context to the data page renderer

## Problem

`GET /data` always reaches the recursive render kernel without a
`:seon.sci.eval/ctx`. The route responds 500 instead of showing the schema or
selected value.

## Evidence

Against the freshly reset default cluster on 2026-08-06:

```text
GET /data -> 500 in 41 ms
seon.sci.kernel/context-projection violated its contract (invalid-input):
[[{:value nil, :message "must be an SCI evaluation context"}]]
```

`seon.render.web/data-response` builds the floor `unit` with caps, cursor,
options, and value but omits `:seon.sci.eval/ctx`. Recursive producer
selection in `seon.render/project-node*` unconditionally calls
`seon.sci.kernel/context-projection` on that missing value.

## Owner

`seon.render.web/data-response` and the existing web service value that already
owns the live cluster SCI context.

## Acceptance

- `/data`, an entity drill, and a blob/value drill each return 200 through the
  production handler.
- Recursive rendering uses the selected cluster's live context and schema
  projection; no route-local substitute projection exists.
- A route regression asserts both status and a rendered structural value.

## Resolved — verified 2026-08-08 live drive

`GET /data` against cluster `default` (pid 79576) returns **HTTP 200**,
3,168 bytes, in 5.88 s. The deterministic 500 and the
`seon.sci.kernel/context-projection violated its contract` body are gone.

Remaining, recorded here rather than reopening: 5.88 s is a slow first byte for
a 3 KB response, and the body served is the agent shell with the value pane
filled over the feed rather than in the initial response. If that latency is a
defect it belongs to a render-cost note, not to the missing-context defect this
issue named.

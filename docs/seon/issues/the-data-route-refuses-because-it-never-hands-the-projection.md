---
type: issue
status: open
severity: blocker
tags: [issue, render, web, config, class/p1, wave/visual-qa]
---

# Hand the projection to the /data route's config read

## Problem

`/data` returns HTTP 500 on every cluster. The response body is a single
unstyled line of text with no page chrome at all:

```text
Effective config requires the projection handed to this operation.
```

The refusal itself is honest and evidence-complete — this is law 2.1 catching
a fetch-at-call-time read. The defect is that the caller commits it: the
`/data` route reads effective config without handing the projection it
already resolved, so the page cannot render at all.

## Evidence

Reproduced 2026-08-14 against BOTH live targets, twice each:

```text
$ curl -s -w " [status:%{http_code} time:%{time_total}s]\n" http://127.0.0.1:55156/data
Effective config requires the projection handed to this operation. [status:500 time:0.360224s]

$ curl -s -w " [status:%{http_code} time:%{time_total}s]\n" http://127.0.0.1:7994/data
Effective config requires the projection handed to this operation. [status:500 time:0.344890s]
```

Port 55156 is the Drive 1 attempt-5 cluster; port 7994 is the shared default
freshly booted onto HEAD. No JavaScript console messages are produced — the
failure is entirely server-side.

The refusing constructor is `seon.config/effective` at
`src/seon/config.clj:530-543`, which returns
`:seon.config/missing-projection` unless `(schema/handed-projection)` is
present. The unguarded caller is `src/seon/render/web.clj:1909`:

```clojure
effective (config/effective db (current-cluster-name db))
```

The sibling call at `src/seon/render/web.clj:978` is the one to compare
against.

## Owner

`seon.render.web` — the `/data` route's config read. `seon.config/effective`
is behaving correctly and should not be loosened.

## Acceptance

`/data` answers 200 with a rendered page on a freshly booted cluster. The
route hands `seon.config/effective` the projection it resolved for its own
extent, exactly like production callers, and a recurring proof exercises the
route through the live boot path rather than a fixture that pre-installs a
projection.

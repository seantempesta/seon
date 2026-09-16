---
type: issue
status: open
severity: friction
tags: [issue, schema, performance, publication, class/p1]
---

# `packaged-forms` per-call resource reads recur at every new caller

## Problem

`seon.schema.edn/packaged-forms` lists, reads and EDN-parses every schema
resource on every call (`src/seon/schema/edn.clj:377`). The 2026-08-07 fix
([archived note](archive/packaged-forms-rereads-every-schema-resource-per-call.md))
repaired the callers of that day; it left the function itself unguarded, so
the class returned with the next caller that did not know:

- 2026-09-16: `seon.cluster.source/identity-ref` called it once per test
  evidence ref inside the publication transaction (`preserved-evidence-tx`,
  added at `64230d4de`/`734253727`). A complete publication exceeded the
  operator's 180 s bound three times; `jstack 53320` showed the writer in
  `identity-ref` → `packaged-forms` → `read-schema-resource` under seven
  nested `retry-with-tempid` frames. Fixed at the callers (`b023e93a9`,
  `8d48f1c51`: forms acquired once per operation and handed in).

Law 2.1 names the disease: fetch at call time. The instance fixes are
correct; the class needs the function itself to be cheap on repeat calls.

## Proposed fix

Make `packaged-forms` derive from an observable: memoize the population on
the digest of the resource directory listing plus each file's length and
mtime (one `stat` pass, no parse), recomputing only when that key changes.
That keeps "running code reads current resources" (a lane's schema edit is
still seen on the next call) while a hot loop pays one stat pass, not a
hundred parses. One regression: a thousand calls with unchanged resources
perform one parse; touching a resource performs a second.

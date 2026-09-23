---
type: issue
status: open
created: 2026-09-23
severity: defect
tags: [issue, render, db, read-evidence]
---

# `seon.db/index-evidence-current` refuses a nil source on an agent page GET

## Observation (lane shrink-web-a, 2026-09-23)

A scratch root at `ee55febe8` (provider keys unset, one `root` agent), after
five warm `GET /agent/root` and five `GET /agent/root/debug`, carried one stored
fault in its readiness problems (`tmp/shrink-web-a/move.log`, error at
2026-09-23T03:51:38Z):

    seon.db/index-evidence-current refused source at []: expected a map, got nil.
    Fix: Supply a map at []. Called from seon.db (db.clj:1174).
    :seon.error/layer :seon.instrument/invocation, :seon.instrument/check :input

The pages returned 200. A caller passes nil where a read-evidence source map is
declared. The caller has not been identified. Its first frame is `db.clj:1174`
at `ee55febe8`.

## Next

Reproduce with one agent-page render on a branch of default, identify the caller that
hands nil, and fix that owner: absence is typed unknown, never a nil argument.

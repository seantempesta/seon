---
type: issue
status: open
severity: friction
tags: [issue, operator, wave/operator-status-face]
---

# Let read-only status inspect a live root owned by another process

## Problem

`bin/seon --root ROOT status` refuses before reporting advertisements when the
root's creator differs from the observer process. That makes the sanctioned
read-only discovery command unusable for an independent observer of an
isolated live cluster.

## Evidence

For `tmp/drive-1-root`, the advertisement was readable and PID 46196 was alive,
but status returned only:

```clojure
{:seon.error/kind :seon.operator/root-creator-mismatch
 :seon.operator.claim/creator
 #:seon.boot{:pid 47641, :start-instant #inst "2026-08-14T05:44:02.082-00:00"}
 :seon.operator.claim/requested-creator
 #:seon.boot{:pid 41702, :start-instant #inst "2026-08-14T05:27:27.242-00:00"}}
```

Direct MCP discovery against the same explicit root and cluster found the
advertisement and evaluated `(+ 1 2)` successfully.

## Owner

The root-claim admission split between read-only operator inspection and
lifecycle mutation.

## Acceptance

`status` can inspect an explicit live root without creator custody and cannot
mutate it. Start/stop/init continue to enforce the exact creator/process
identity. A regression proves the read/write authority split.

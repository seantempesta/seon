---
type: issue
status: open
severity: blocker
created: 2026-09-23
tags: [publication, one-jvm, concurrency, datahike, class]
---

# Concurrent publications through the live JVM race at `force-branch!`

## Evidence

`bin/test --prepare-head-base` at HEAD `245f693f6`, while three lanes ran
fast iterations that also publish their bases through the live default
JVM (`seon.fresh-operator/live-root-value!` → the cluster's prepl):

```text
Message:  The cluster threw during the prepl operation: clojure.lang.ExceptionInfo: Branch head changed before force-branch!.
```

(`tmp/orchestrator/prepare-366ad0be0.log`; retained root swept.) Two
publishers reached `current-src` in one JVM; the second observed the head
moved between its read and its `force-branch!` and refused. The refusal is
honest (it never overwrote), but the design admits the race: since slice 1
every operator and hook request goes through the running JVM, so
publication of `current-src` is now a shared, concurrent entry point.

## Fix shape (Datahike's own serialization)

Datahike's writer serializes transactions per connection; a publication's
read-then-`force-branch!` must not straddle that: either the head move is
one transaction on the branch's connection (`:db.fn/call` deciding on the
current head inside the writer), or publications of the same branch are
serialized under the JVM's existing lifecycle lock with the waiting request
told what it waited for. Never a retry loop around the refusal. Regression:
two concurrent `refresh-source!` calls on one live host produce one head
and two consistent replies.

Owner: the redesign lane (slice 4, `src/seon/cluster/source.clj`).

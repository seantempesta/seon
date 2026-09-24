---
type: issue
status: open
severity: blocking
created: 2026-09-24
tags: [issue, publication, hook, branch]
---

# Publication refuses while the candidate branch holds a connection

## Problem

After default (pid 70372, started 2026-09-24T16:53:17Z from the checkout) ran
test requests, every hook publication refused:

```
{:seon.error/operation seon.cluster/refresh-source!,
 :seon.error/message "branch :cluster-default-candidate still has a connection in this process",
 :seon.cluster.registry/refused :seon.cluster.registry/cluster-connected, ...}
```

Two consecutive saves of `test/seon/db_test.clj` at 17:07:54Z and 17:08:07Z
refused this way (3.9–5.2 s each). The edited test row stayed unpublished:
`(:seon.test/source ...)` for
`seon.db-test/transaction-success-and-refusal-shapes-are-declared` lacked the
new text.

Just before this, a `bin/test-check default --ns seon.call-preparation-test`
request failed twice with "PREPL evaluation failed", because
`seon.test/green?` refused a result missing `:seon.test/run-basis-t`
(`src/seon/test.clj:1874`, while another lane had uncommitted edits there).
That request is the suspected holder of the candidate connection. This is not
yet proven.

## Wanted

A failed request releases every branch connection it acquired. Publication
names the holder when it refuses, and nothing stays unpublished without being
reported. Owner: `seon.cluster/refresh-source!` and the registry's
`cluster-connected` rule.

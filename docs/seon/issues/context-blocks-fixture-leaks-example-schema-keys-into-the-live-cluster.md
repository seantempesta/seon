---
type: issue
status: open
severity: friction
created: 2026-09-17
tags: [testing, schema, own-nothing-global, class]
---

# A fixture leaks `:example/*` schema keys into the live cluster's projection

## Problem

The first in-process run under the new registry drift check reported, on
`default` (pid 88182):

```
Worker-global state changed:
  :snapshot-schema-keys {:drift-added [":example/order" ":example/order-row"]}
Live cluster schema registry changed and was restored:
  [{:seon.cluster/name "default"
    :seon.test.runner/drift-added [":example/order" ":example/order-row"]}]
```

`:example/order` and `:example/order-row` are `test/seon/context_blocks_fixture.clj`'s
`seon.schema/register!` forms. They reached the LIVE cluster's own schema
projection — the same shape that caused the 2026-09-17 write storm, where a
leaked `:seon.schema-usage-guardb/entity-id` made every write on `default`
refuse and grew the store ~1 GB a minute.

The keys were restored automatically, and the run that observed them was not
the run that leaked them — this is a standing leak from whichever earlier
in-process run loaded that fixture.

## Why this is filed rather than fixed here

The write-storm lane added the check that found it (`::snapshot-schema-keys`
in `seon.test.runner/ambient-snapshot`, restored by `seon.test/run`), and
those owners are committed. This is a different fixture with a different
owner, and the leak predates the check.

## Fix shape

Find where `context_blocks_fixture`'s registrations escape their delta — the
suite evaluates `seon.schema/register!` source through an SCI context, so the
likely path is a ctx whose `seon.env/state-carrier` is shared with the live
cluster rather than forked (`seon.sci.eval/fork-cluster-ctx`'s 4-arity takes
the caller's projection-state atom VERBATIM; only the 3-arity allocates a new
one). Then bracket the suite with
`seon.test-support/preserving-schema-registry`.

Related: `a-failing-turn-write-refires-without-bound-and-fills-the-store`.

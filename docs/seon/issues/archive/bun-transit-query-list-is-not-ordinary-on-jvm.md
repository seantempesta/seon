---
type: issue
status: resolved
severity: blocker
tags: [issue, database, cljs]
---

# Bun Transit query list is not ordinary on the JVM

## Problem

A Bun aggregate query that is ordinary protocol data became a lazy sequence
after the JVM decoded its Transit frame, so canonical request validation
rejected it before Datahike execution.

## Evidence

Transit CLJS encodes a nonempty ClojureScript list with the semantic `list`
tag. Transit CLJ 1.0.333's default handler completed that tag with `(seq l)`,
which is a `clojure.lang.LazySeq` under the selected Clojure runtime. The
protocol deliberately accepts eager lists and rejects other sequential values.
The original direct and `execute-many` requests were valid; their default JVM
decodes were not.

## Owner

`seon.db.transport.uds` owns Transit encoding and decoding. The ordinary-value
contract remains in `seon.db.protocol`; Datahike query semantics are unchanged.

## Acceptance

A Bun-produced aggregate query round-trips through the JVM decoder as the same
ordinary query form; request validation succeeds on both sides; direct and
`execute-many` aggregate queries return the expected result; and no transport
adapter or query-specific rewrite is introduced.

## Resolution

Resolved by the `Normalize Transit lists at the JVM socket boundary` commit.
The JVM UDS decoder now uses Transit CLJ's reusable handler-map API to decode
the semantic `list` tag into an eager `clojure.lang.PersistentList`. It does
not relax the protocol's rejection of arbitrary lazy sequences.

The focused JVM checkpoint ran `seon.db.transport-uds-test` and
`seon.db.writer-integration-test`: 53 tests and 411 assertions passed. The
codec regression proves direct and `execute-many` aggregate forms decode as
lists and remain canonical requests. The framed UDS integration executes both
forms against the same coordinate and returns scalar count `2` from each.

---
type: issue
status: resolved
severity: blocker
tags: [issue, context, database, runtime, live-drive]
---

# Read the capture basis with the total reader, not the identity reader

## Problem

`seon.context/capture-tx` read the rendered database value's basis through
`seon.db/database-value-identity`, whose output contract requires a
`:datahike/commit-id` uuid. An as-of database value — which is exactly what
`seon.cluster.run/opening-db` now supplies — has no commit id, so the contract
threw INTO the turn proc. Root's `seon.cluster.agent/turn` `:step` died before
any context capture, any provider call, and any receipt. The cluster booted
with a permanently non-advancing root agent.

## Evidence

Fresh cluster `default` (pid 79576, booted 2026-08-08T04:30:56Z). The durable
core fault, error `db9b5b2a-1feb-48b4-9243-2a9439346119`, basis 536870986:

```text
seon.error/kind        :seon.instrument/contract-violated
seon.error/proc        seon.cluster.agent/turn
seon.error/op          :step
seon.instrument/fn     seon.db/database-value-identity
seon.error/message     seon.db/database-value-identity violated its contract
                       (invalid-output): {:datahike/commit-id [{:value nil,
                       :message "should be a uuid"}], ...}
```

Reproduced directly in the live JVM:

```clojure
(seon.db/database-value-identity (datahike.api/as-of (seon.db/db conn) 536870990))
;=> THROWS, commit-id nil
(seon.db/basis-t              (datahike.api/as-of (seon.db/db conn) 536870990))
;=> 536870994
```

At 04:35 and again at 04:38 the drive committed human messages through
`POST /agent/root/message` (HTTP 204 both times). Before the fix: no run, no
capture, no attempt, no receipt, and no new error — the turn was simply dead.

## Cause

Commit `419a5e529` ("Read the capture basis through the database interface")
correctly stopped reading `:max-tx` as a map key but reached for
`database-value-identity`, whose contract is stricter than the question asked.
`seon.db/basis-t`, declared immediately below it in the same namespace, is the
total reader for every value shape and its docstring says so.

## Resolution

`src/seon/context.clj` now reads `(long (db/basis-t db))`. The `:t` value is
byte-identical (both go through `dbi/-max-tx`); only the commit-id requirement
is dropped.

Verified live by hot-reloading `seon.context` into the running cluster:

```clojure
(seon.context/capture-tx {:seon.cluster.run/id "probe"
                          :seon.cluster.prompt/rendered-context
                          {:seon.db/db asof :seon.cluster.prompt/text "x"
                           :seon.context/contributions []}})
;=> capture id "probe-context-536870997"
```

The turn then advanced immediately: context capture
`a7e24a23-…-context-536870998` committed, a real DeepSeek attempt ran, thirteen
evaluation receipts landed, and the run closed at 04:39:47 — the first time
this cluster reached a settled run.

## Owed

A class regression is still owed and is NOT in this change: `capture-tx` must
be exercised against all four database value shapes (current, as-of, since,
history), because the class is "a database value read through a reader that is
not total over its shapes," and the sibling instance is still open in
[the walk's as-of refusal](walk-refuses-an-as-of-database-value-and-empties-the-agent-context.md).
